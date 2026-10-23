package com.ling.browser.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import com.ling.browser.data.prefs.DownloadLocation
import java.io.File
import java.io.OutputStream

/**
 * 下载目标位置解析器。
 *
 * 根据用户设置的 [DownloadLocation] 与系统版本，决定文件落在哪里、
 * 以及用什么方式写入（直接文件 vs MediaStore）。
 *
 * 两种位置：
 *  - [DownloadLocation.APP_PRIVATE]：始终用应用外部私有目录，零权限；
 *  - [DownloadLocation.PUBLIC_DOWNLOADS]：公共 Download 目录。
 *      API >= 29 走 MediaStore（无需权限），API < 29 直接写公共目录
 *      （需要 WRITE_EXTERNAL_STORAGE 运行时权限，由调用方保证）。
 *
 * 断点续传的核心是「拿到已下载字节数」和「从指定偏移开始写」。
 * 这里统一通过 [resolve] 返回一个 [Target]，调用方用它的 [Target.length]
 * 判断起点，用 [Target.outputStream] 拿到可 seek 的写入通道。
 */
object DownloadDestination {

    /**
     * 解析一个下载目标。
     *
     * @param context      上下文
     * @param fileName     文件名
     * @param location     用户设置的保存位置
     * @param existingPath 上一次记录下来的落点（[DownloadEntry.localPath]）。续传时
     *                     若它是 `content://` URI，则**直接复用它**：MediaStore 的
     *                     未完成条目（`IS_PENDING=1`）在 Android 10+ 默认对查询不可见，
     *                     只凭文件名去查是找不到的，只能把它记下来当回执。
     * @return 下载目标；若公共目录权限不足可能返回 null（调用方需降级或提示）
     */
    fun resolve(
        context: Context,
        fileName: String,
        location: DownloadLocation,
        existingPath: String? = null,
    ): Target? {
        // 续传优先复用已记录的 MediaStore URI —— 这是跨版本最可靠的续传定位方式，
        // 绕开了「pending 条目查不到」和「SIZE 列滞后」两个坑。
        if (existingPath != null && existingPath.startsWith(CONTENT_SCHEME)) {
            return MediaStoreTarget(context, Uri.parse(existingPath))
        }
        return when (location) {
            DownloadLocation.APP_PRIVATE -> resolvePrivate(context, fileName)
            DownloadLocation.PUBLIC_DOWNLOADS -> resolvePublic(context, fileName)
        }
    }

    // -------------------------------------------------------------- 冲突处理 / SAF

    /**
     * 给同名文件挑一个不冲突的名字（`GM_download` 的 `conflictAction: 'uniquify'`）。
     *
     * 只在当前落点里查重：`name (1).ext`、`name (2).ext` …… 找到空位为止。
     * 试满 [MAX_UNIQUE_TRIES] 次仍有冲突时退到时间戳后缀，保证一定返回可用名。
     */
    fun uniqueName(context: Context, fileName: String, location: DownloadLocation): String {
        if (!exists(context, fileName, location)) return fileName
        val dot = fileName.lastIndexOf('.')
        val base = if (dot > 0) fileName.substring(0, dot) else fileName
        val ext = if (dot > 0) fileName.substring(dot) else ""
        for (i in 1..MAX_UNIQUE_TRIES) {
            val candidate = "$base ($i)$ext"
            if (!exists(context, candidate, location)) return candidate
        }
        return "$base-${System.currentTimeMillis()}$ext"
    }

    /**
     * `conflictAction: 'overwrite'`：开写前删掉同名旧文件。
     *
     * 必须删掉而不是直接用：解析目标时会把已有字节数当成续传起点，
     * 不删就会把新内容追加到旧文件尾部，拼出一个坏文件。
     */
    fun deleteExisting(context: Context, fileName: String, location: DownloadLocation) {
        when (location) {
            DownloadLocation.APP_PRIVATE ->
                runCatching { File(privateDir(context), fileName).delete() }
            DownloadLocation.PUBLIC_DOWNLOADS ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    mediaStoreId(context, fileName)?.let { id ->
                        runCatching {
                            context.contentResolver.delete(
                                Uri.withAppendedPath(mediaStoreCollection(), id.toString()),
                                null,
                                null,
                            )
                        }
                    }
                } else {
                    runCatching { File(publicDir(), fileName).delete() }
                }
        }
    }

    /** 用户通过 SAF 选定的目标（`GM_download` 的 `saveAs`）。 */
    fun resolveUri(context: Context, uri: Uri): Target = UriTarget(context, uri)

    private fun exists(context: Context, fileName: String, location: DownloadLocation): Boolean =
        when (location) {
            DownloadLocation.APP_PRIVATE -> File(privateDir(context), fileName).exists()
            DownloadLocation.PUBLIC_DOWNLOADS ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    mediaStoreId(context, fileName) != null
                } else {
                    File(publicDir(), fileName).exists()
                }
        }

    private fun privateDir(context: Context): File =
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.filesDir, "downloads")

    private fun publicDir(): File =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun mediaStoreCollection(): Uri =
        MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun mediaStoreId(context: Context, fileName: String): Long? = runCatching {
        context.contentResolver.query(
            mediaStoreCollection(),
            arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.DISPLAY_NAME} = ?",
            arrayOf(fileName),
            null,
        )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
    }.getOrNull()

    // -------------------------------------------------------------- 私有目录

    private fun resolvePrivate(context: Context, fileName: String): Target {
        val dir = privateDir(context)
        if (!dir.exists()) dir.mkdirs()
        return FileTarget(File(dir, fileName))
    }

    // -------------------------------------------------------------- 公共目录

    private fun resolvePublic(context: Context, fileName: String): Target? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolvePublicMediaStore(context, fileName)
        } else {
            resolvePublicLegacy(fileName)
        }
    }

    /**
     * Android 10+：通过 MediaStore 写入公共 Download 目录。
     *
     * 不需要存储权限。已存在的同名文件会被复用（用于断点续传）。
     *
     * 调用方已用 `SDK_INT >= Q` 分派；[MediaStore.Downloads.getContentUri] 是
     * API 29 才有的，用注解把这个前提写清楚，免静态分析误报。
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun resolvePublicMediaStore(context: Context, fileName: String): Target? {
        val resolver = context.contentResolver
        val collection = mediaStoreCollection()

        // 先查有没有同名文件（断点续传时复用）
        val existing = mediaStoreId(context, fileName)

        val uri = if (existing != null) {
            Uri.withAppendedPath(collection, existing.toString())
        } else {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            runCatching { resolver.insert(collection, values) }.getOrNull() ?: return null
        }

        return MediaStoreTarget(context, uri)
    }

    /**
     * Android 9 及以下：直接写公共 Download 目录。
     *
     * 需要 WRITE_EXTERNAL_STORAGE 权限。调用方负责申请。
     */
    private fun resolvePublicLegacy(fileName: String): Target? {
        val dir = publicDir()
        if (!dir.exists() && !dir.mkdirs()) return null
        return FileTarget(File(dir, fileName))
    }

    // -------------------------------------------------------------- 目标抽象

    /** 一个可写入的下载目标。 */
    sealed interface Target {
        /** 已下载的字节数（用于断点续传起点）。 */
        fun length(): Long

        /** 打开一个可写入的输出流。append=true 时从文件末尾追加。 */
        fun outputStream(append: Boolean): OutputStream

        /** 完成后调用：标记 MediaStore 条目为非 pending（对 FileTarget 无操作）。 */
        fun finalize() {}

        /** 给用户看的本地路径/标识，存进 DownloadEntry.localPath。 */
        fun displayPath(): String

        /** 删除已下载的文件。 */
        fun delete()
    }

    /** 基于文件系统的目标（应用私有目录 / 旧版公共目录）。 */
    class FileTarget(private val file: File) : Target {
        override fun length(): Long = if (file.exists()) file.length() else 0L
        override fun outputStream(append: Boolean): OutputStream {
            val parent = file.parentFile
            if (parent != null && !parent.exists()) parent.mkdirs()
            // File.outputStream() 没有 append 参数，必须用 FileOutputStream 构造函数。
            return java.io.FileOutputStream(file, append)
        }
        override fun displayPath(): String = file.absolutePath
        override fun delete() { runCatching { file.delete() } }
    }

    /** 基于 MediaStore 的目标（Android 10+ 公共目录）。 */
    class MediaStoreTarget(
        private val context: Context,
        private val uri: Uri,
    ) : Target {
        /**
         * 已写入的真实字节数。
         *
         * **不能读 MediaStore 的 `SIZE` 列**：对正在写入的 `IS_PENDING=1` 条目，
         * 该列是滞后甚至为 0 的（只有媒体扫描/提交后才刷新），拿它当续传起点
         * 会被误判成「一点都没下」，于是整文件重下 —— 这正是「暂停再恢复即重下」
         * 的成因。改为通过文件描述符取文件的**真实大小**（statSize），
         * 它反映底层文件的当前长度，与写入进度一致。
         */
        override fun length(): Long {
            val realSize = runCatching {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
            }.getOrNull()
            if (realSize != null && realSize > 0) return realSize
            return sizeColumn()
        }

        /** 兜底：查 `SIZE` 列（文件描述符打不开时才用得上）。 */
        private fun sizeColumn(): Long = runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.Downloads.SIZE),
                null, null, null,
            )?.use { c ->
                if (c.moveToFirst()) c.getLong(c.getColumnIndexOrThrow(MediaStore.Downloads.SIZE))
                else 0L
            } ?: 0L
        }.getOrDefault(0L)

        override fun outputStream(append: Boolean): OutputStream {
            // "wa" = write + append；"w" = write + truncate
            val mode = if (append) "wa" else "w"
            val fd = context.contentResolver.openFileDescriptor(uri, mode)
                ?: throw java.io.IOException("无法打开 MediaStore 输出流")
            // 用 FileDescriptor 构造 OutputStream；RandomAccessFile 不支持
            // ParcelFileDescriptor 的 fd 直接构造（它需要真实文件路径），
            // 这里用 FileOutputStream 的 fd 构造函数写入。
            return java.io.FileOutputStream(fd.fileDescriptor)
        }

        override fun finalize() {
            runCatching {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }
                context.contentResolver.update(uri, values, null, null)
            }
        }

        override fun displayPath(): String = uri.toString()

        override fun delete() {
            runCatching { context.contentResolver.delete(uri, null, null) }
        }
    }

    /**
     * 用户通过 SAF（`ACTION_CREATE_DOCUMENT`）选定的文档。
     *
     * 一律从头写：用户已经在系统对话框里确认过覆盖，`length()` 固定为 0 也就
     * 不会触发 Range 续传 —— 对任意 provider 做跨进程续传没有可靠语义。
     */
    class UriTarget(
        private val context: Context,
        private val uri: Uri,
    ) : Target {
        override fun length(): Long = 0L

        override fun outputStream(append: Boolean): OutputStream {
            val resolver = context.contentResolver
            // "wt" 会截断已有内容；个别 provider 不认这个模式，退回默认写模式。
            return runCatching { resolver.openOutputStream(uri, "wt") }.getOrNull()
                ?: resolver.openOutputStream(uri)
                ?: throw java.io.IOException("无法写入所选文件")
        }

        override fun displayPath(): String = uri.toString()

        override fun delete() {
            runCatching {
                android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri)
            }
        }
    }
}

/**
 * 同名文件的处理策略，对应 `GM_download` 的 `conflictAction`。
 *
 * `prompt` 在移动端退化成 [UNIQUE]：弹一个"是否覆盖"的对话框打断自动化并不合适，
 * 而默认覆盖会静默毁掉用户已有的文件。
 */
enum class ConflictAction { UNIQUE, OVERWRITE }

/** `content://` URI 前缀，用于判断记录的落点是否是 MediaStore 目标。 */
private const val CONTENT_SCHEME = "content://"

/** `uniquify` 时最多尝试的序号，超过就退到时间戳，避免病态情况下死循环。 */
private const val MAX_UNIQUE_TRIES = 100
