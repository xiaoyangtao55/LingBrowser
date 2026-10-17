package com.ling.browser.data.repo

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import com.ling.browser.data.db.DownloadEntry
import com.ling.browser.data.db.DownloadStatus
import com.ling.browser.data.db.LingDatabase
import com.ling.browser.data.db.downloadValues
import com.ling.browser.data.db.toDownload
import com.ling.browser.data.prefs.DownloadLocation
import com.ling.browser.data.prefs.DownloaderType
import com.ling.browser.data.prefs.LingSettings
import com.ling.browser.download.DownloadEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 下载仓库。
 *
 * 根据用户设置的 [DownloaderType] 分发到不同的下载后端：
 *  - [DownloaderType.BUILT_IN] 内建下载引擎（[DownloadEngine]）：实时进度、暂停/恢复、断点续传；
 *  - [DownloaderType.SYSTEM]   系统 DownloadManager：稳定省心，进度靠轮询。
 *
 * 两套后端共用同一张 downloads 表与同一个 [downloads] 流，UI 层无感知。
 *
 * 历史上这里只对接系统 DownloadManager；换成内建引擎后保留系统路径作为可选，
 * 让偏好系统下载器行为的用户有退路。
 */
class DownloadRepository(
    private val context: Context,
    private val db: LingDatabase,
    private val engine: DownloadEngine,
    /** 当前设置的提供者。引擎是推送式的，但下载器类型/位置需要在 enqueue 时现读。 */
    private val settingsProvider: () -> LingSettings,
) {

    private val manager: DownloadManager? =
        context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager

    /** 下载列表流：内建引擎推送 + 系统下载器轮询的合并视图。 */
    val downloads: StateFlow<List<DownloadEntry>> = engine.downloads

    /**
     * 发起一次下载。
     *
     * 返回 false 表示请求没能交给后端（URL 不合法、系统下载服务不可用、
     * 或公共目录存储权限不足）。
     */
    suspend fun enqueue(url: String, fileName: String?, mimeType: String?): Boolean {
        val settings = settingsProvider()
        return when (settings.downloaderType) {
            DownloaderType.BUILT_IN -> engine.enqueue(url, fileName, mimeType, settings.downloadLocation)
            DownloaderType.SYSTEM -> enqueueSystem(url, fileName, mimeType, settings.downloadLocation)
        }
    }

    /**
     * 取消一个未完成的任务。
     *
     * 语义是「不下了，并把已下载的残片和记录一并清掉」——与 [remove] 完全相同，
     * 直接复用：用户点取消要的是干净地放弃，而不是留下一条「失败」记录
     * 和半个文件。
     */
    suspend fun cancel(entry: DownloadEntry) = remove(entry, deleteFile = true)

    suspend fun remove(entry: DownloadEntry, deleteFile: Boolean) {
        when (entry.source) {
            DownloadSource.BUILT_IN -> engine.remove(entry.downloadId, deleteFile)
            DownloadSource.SYSTEM -> removeSystem(entry, deleteFile)
        }
    }

    suspend fun pause(entry: DownloadEntry) {
        // 只有内建引擎支持暂停；系统 DownloadManager 无应用内暂停能力，
        // 调过来直接忽略（UI 层本就不该给系统下载器的条目显示暂停按钮）。
        if (entry.source == DownloadSource.BUILT_IN) engine.pause(entry.downloadId)
    }

    suspend fun resume(entry: DownloadEntry) {
        if (entry.source == DownloadSource.BUILT_IN) engine.resume(entry.downloadId)
    }

    suspend fun clearAll(deleteFiles: Boolean) {
        // 两套后端的记录都在同一张表里，统一删表即可；
        // 但进行中的系统下载要先交给 DownloadManager 取消。
        val all = queryAll()
        all.filter { it.isRunning && it.source == DownloadSource.SYSTEM }
            .forEach { manager?.remove(it.downloadId) }
        engine.clearAll(deleteFiles)
    }

    /** 引擎是推送式的，系统下载器需要回查。这里两种都刷一遍。 */
    fun refresh() {
        engine.refresh()
        refreshSystem()
    }

    // ---------------------------------------------------- 系统 DownloadManager

    private suspend fun enqueueSystem(
        url: String,
        fileName: String?,
        mimeType: String?,
        location: DownloadLocation,
    ): Boolean = withContext(Dispatchers.IO) {
        val dm = manager ?: return@withContext false
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return@withContext false
        }

        val name = fileName?.takeIf { it.isNotBlank() }
            ?: url.substringAfterLast('/').substringBefore('?').takeIf { it.isNotBlank() }
            ?: "download"

        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle(name)
            setDescription("来自「翎」浏览器")
            setAllowedOverMetered(true)
            setAllowedOverRoaming(false)
            setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            )
            mimeType?.let { setMimeType(it) }
            when (location) {
                DownloadLocation.APP_PRIVATE ->
                    setDestinationInExternalFilesDir(
                        context, Environment.DIRECTORY_DOWNLOADS, name,
                    )
                DownloadLocation.PUBLIC_DOWNLOADS ->
                    setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS, name,
                    )
            }
        }

        val id = runCatching { dm.enqueue(request) }.getOrNull()
            ?: return@withContext false

        db.writableDatabase.insertWithOnConflict(
            "downloads",
            null,
            downloadValues(
                downloadId = id,
                fileName = name,
                url = url,
                totalBytes = 0,
                downloadedBytes = 0,
                mimeType = mimeType,
                status = DownloadStatus.PENDING,
                localPath = null,
                downloadLocation = location,
                createdAt = System.currentTimeMillis(),
            ),
            android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
        )
        refreshSystem()
        true
    }

    private suspend fun removeSystem(entry: DownloadEntry, deleteFile: Boolean) =
        withContext(Dispatchers.IO) {
            if (entry.isRunning) manager?.remove(entry.downloadId)
            if (deleteFile) {
                entry.localPath?.let { path -> runCatching { File(path).delete() } }
            }
            db.writableDatabase.delete("downloads", "id = ?", arrayOf(entry.id.toString()))
            refreshSystem()
        }

    /** 向系统 DownloadManager 回查所有系统下载条目的实时状态。 */
    private fun refreshSystem() {
        val dm = manager ?: return
        val rows = queryAll()
        val synced = rows.map { entry ->
            if (entry.source == DownloadSource.SYSTEM) syncFromSystem(entry) else entry
        }
        synced.filterIndexed { i, e -> e != rows[i] }.forEach { persistSystem(it) }
        // 内建引擎那边已经推送了最新状态，这里只需把系统部分的变化合进去
        engine.overwriteSystemEntries(synced.filter { it.source == DownloadSource.SYSTEM })
    }

    private fun syncFromSystem(entry: DownloadEntry): DownloadEntry {
        val dm = manager ?: return entry
        val cursor = runCatching {
            dm.query(DownloadManager.Query().setFilterById(entry.downloadId))
        }.getOrNull() ?: return entry

        cursor.use { c ->
            if (!c.moveToFirst()) {
                return if (entry.isRunning) entry.copy(status = DownloadStatus.FAILED) else entry
            }
            val statusIdx = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
            val totalIdx = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            val doneIdx = c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val localIdx = c.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)

            val status = if (statusIdx >= 0) c.getInt(statusIdx) else -1
            val total = if (totalIdx >= 0) c.getLong(totalIdx) else 0L
            val done = if (doneIdx >= 0) c.getLong(doneIdx) else 0L
            val localUri = if (localIdx >= 0 && !c.isNull(localIdx)) c.getString(localIdx) else null

            val mapped = when (status) {
                DownloadManager.STATUS_PENDING -> DownloadStatus.PENDING
                DownloadManager.STATUS_RUNNING -> DownloadStatus.RUNNING
                DownloadManager.STATUS_PAUSED -> DownloadStatus.PAUSED
                DownloadManager.STATUS_SUCCESSFUL -> DownloadStatus.SUCCESS
                DownloadManager.STATUS_FAILED -> DownloadStatus.FAILED
                else -> entry.status
            }

            return entry.copy(
                status = mapped,
                totalBytes = total.coerceAtLeast(0L),
                downloadedBytes = done.coerceAtLeast(0L),
                localPath = localUri?.removePrefix("file://") ?: entry.localPath,
            )
        }
    }

    private fun persistSystem(entry: DownloadEntry) {
        runCatching {
            val values = ContentValues().apply {
                put("status", entry.status.name)
                put("total_bytes", entry.totalBytes)
                put("downloaded_bytes", entry.downloadedBytes)
                if (entry.localPath == null) putNull("local_path") else put("local_path", entry.localPath)
            }
            db.writableDatabase.update(
                "downloads", values, "id = ?", arrayOf(entry.id.toString()),
            )
        }
    }

    private fun queryAll(): List<DownloadEntry> = runCatching {
        db.readableDatabase.rawQuery(
            "SELECT * FROM downloads ORDER BY created_at DESC",
            null,
        ).use { c -> buildList { while (c.moveToNext()) add(c.toDownload()) } }
    }.getOrDefault(emptyList())
}

/**
 * 一条下载记录的来源后端。
 *
 * 内建引擎的 downloadId 等于表主键 id（>0），系统 DownloadManager 的 ID
 * 是系统分配的长整型——两者值域重叠，无法仅凭 ID 区分。
 *
 * 因此用 [DownloadEntry.localPath] 的形式来推断：
 *  - 系统下载器完成后会返回 `file://` 或 `content://`  URI，去掉前缀后是绝对路径；
 *  - 内建引擎私有目录也是绝对路径；
 *  两者无法靠路径区分。
 *
 * 更可靠的方式：在 downloads 表加一个 source 列。但为了不做数据库迁移，
 * 这里用一个约定——**系统下载器的 downloadId 是系统返回的原始 ID，
 * 内建引擎的 downloadId 等于主键 id**。由于引擎插入时用
 * `UPDATE downloads SET download_id = id`，而系统下载器插入时用系统返回的 ID，
 * 可以用「download_id == id」判断是否为内建来源。
 *
 * ⚠️ 这个约定要求内建引擎的 downloadId 始终等于主键 id（见
 * [DownloadEngine.insertTask]）。如果将来改了这一点，要同步更新这里的判定。
 */
val DownloadEntry.source: DownloadSource
    get() = if (downloadId == id) DownloadSource.BUILT_IN else DownloadSource.SYSTEM

/**
 * 该条目是否支持应用内的暂停 / 继续。
 *
 * 只有内建引擎有这条能力；系统 DownloadManager 应用内无法暂停，通知栏上的
 * 「暂停 / 继续」按钮对系统条目必须隐藏，否则按下去没有任何反应。
 */
val DownloadEntry.supportsPauseControl: Boolean
    get() = source == DownloadSource.BUILT_IN

enum class DownloadSource { BUILT_IN, SYSTEM }
