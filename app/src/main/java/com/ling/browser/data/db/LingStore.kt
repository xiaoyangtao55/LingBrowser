package com.ling.browser.data.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.ling.browser.data.prefs.DownloadLocation

/**
 * 手写的 SQLite 存储层。
 *
 * 为什么不用 Room / androidx.sqlite：这两者要么依赖 KSP 注解处理
 * （与 AGP 9 的内置 Kotlin 插件冲突），要么引入额外的框架包。
 * 本应用只有 3 张结构简单的表，直接使用平台自带的 [SQLiteOpenHelper]
 * 是最省依赖、也最贴合「翎」轻量取向的做法。
 *
 * 所有公开方法都应在 IO 线程调用（仓库层已用 Dispatchers.IO 包住）。
 */
class LingDatabase(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DB_NAME,
    null,
    VERSION,
) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS bookmarks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                url TEXT NOT NULL,
                folder TEXT,
                created_at INTEGER NOT NULL,
                favicon BLOB
            )
            """.trimIndent()
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_bookmark_url ON bookmarks(url)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                url TEXT NOT NULL,
                visited_at INTEGER NOT NULL,
                visit_count INTEGER NOT NULL DEFAULT 1,
                favicon BLOB
            )
            """.trimIndent()
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_history_url ON history(url)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS tabs (
                id TEXT PRIMARY KEY,
                url TEXT NOT NULL,
                title TEXT NOT NULL,
                position INTEGER NOT NULL,
                is_incognito INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )

        createDownloadsTable(db)
        createCredentialsTable(db)
    }

    /**
     * 建 downloads 表与索引。
     *
     * 单独抽出来是因为 [onCreate] 与 [onUpgrade] 都要用：v1 的库升级时
     * 也必须补建这张表（见 onUpgrade 里的 `oldVersion < 2` 分支）。
     */
    private fun createDownloadsTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS downloads (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                download_id INTEGER NOT NULL,
                file_name TEXT NOT NULL,
                url TEXT NOT NULL,
                total_bytes INTEGER NOT NULL DEFAULT 0,
                downloaded_bytes INTEGER NOT NULL DEFAULT 0,
                mime_type TEXT,
                status TEXT NOT NULL,
                local_path TEXT,
                download_location TEXT NOT NULL DEFAULT 'APP_PRIVATE',
                source TEXT NOT NULL DEFAULT 'BUILT_IN',
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_download_download_id " +
                "ON downloads(download_id)"
        )
    }

    /**
     * 密码库表。
     *
     * 只存密文：`password_cipher` / `password_iv` 是 AES-GCM 的输出，
     * 密钥在 Android Keystore 里，**明文永远不落盘**。
     *
     * `site` 存 eTLD+1（见 `SiteMatcher`），不是完整 URL：同一站点的
     * `www` / `login` 子域共用一条记录，也避免把路径里的敏感参数写进库。
     *
     * (site, username) 建唯一索引：捕获到同一站点的同一用户名时走更新，
     * 而不是不断插入重复条目。[username] 允许空串（只有密码框的站点）。
     */
    private fun createCredentialsTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS credentials (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                site TEXT NOT NULL,
                username TEXT NOT NULL DEFAULT '',
                password_cipher BLOB NOT NULL,
                password_iv BLOB NOT NULL,
                title TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                last_used_at INTEGER
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_credential_site_username " +
                "ON credentials(site, username)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // ⚠️ 这里**不再**无条件 DROP 重建。
        //
        // 上一版写的是"尚未正式发版，直接重建即可"。但用户已经在真机上
        // 装了带书签/历史的版本，再重建就是把他的收藏和记录全抹掉 ——
        // 而我们恰恰是要求他装新包来验证的。所以从 2 -> 3 开始走增量迁移。
        //
        // 每个版本的迁移都要能从任意旧版本逐级升上来，因此按序判断而不是
        // `when (oldVersion)` 精确匹配。
        if (oldVersion < 2) {
            // v2 新增了 downloads 表，但建表语句只在 onCreate 里 —— 直接从 v1
            // 升上来的库没有这张表。所有下载读写都包在 runCatching 里，
            // 缺表不会抛错，只会静默变成"下载永远失败、下载页永远空白"。
            // 建表语句带 IF NOT EXISTS，这里与 onCreate 共用同一段。
            createDownloadsTable(db)
        }
        if (oldVersion < 3) {
            // 书签图标。NULL 表示还没有图标，UI 退回字母占位。
            // 存 BLOB 而不是路径/URL：图标很小（几十 KB 的 PNG），
            // 存库能让书签列表离线也显示图标，且卸载即清理、不留残 file。
            runCatching { db.execSQL("ALTER TABLE bookmarks ADD COLUMN favicon BLOB") }
        }
        if (oldVersion < 5) {
            // 历史记录的网站图标。与书签同理存 BLOB：图标很小，存库能离线显示、
            // 卸载即清理。NULL 表示"还没抓过"，空数组表示"抓过但失败"。
            runCatching { db.execSQL("ALTER TABLE history ADD COLUMN favicon BLOB") }
        }
        // 只在 v2/v3 需要：v1 的库在上面的 < 2 分支里新建的表已带这一列。
        if (oldVersion in 2..3) {
            // 下载保存位置（应用私有 / 公共目录）。老记录默认为应用私有目录，
            // 与此前 DownloadManager 落到 getExternalFilesDir 的行为一致。
            runCatching {
                db.execSQL(
                    "ALTER TABLE downloads ADD COLUMN download_location TEXT NOT NULL DEFAULT 'APP_PRIVATE'"
                )
            }
        }
        if (oldVersion < 6) {
            // 下载来源（内建引擎 / 系统 DownloadManager）。此前靠
            // `download_id == id` 猜，两者都是小整数，撞号就会把系统下载
            // 当成内建（暂停无效、取消不 remove、删错文件）。
            //
            // v1 的库在上面的 < 2 分支新建 downloads 表时已带这一列，
            // 再 ALTER 会报 duplicate column，所以只对 v2..v5 生效。
            if (oldVersion >= 2) {
                // 老记录没有历史信息，只能按旧约定回填：download_id == id 即内建。
                runCatching {
                    db.execSQL(
                        "ALTER TABLE downloads ADD COLUMN source TEXT NOT NULL DEFAULT 'BUILT_IN'"
                    )
                }
                runCatching {
                    db.execSQL(
                        "UPDATE downloads SET source = 'SYSTEM' WHERE download_id != id"
                    )
                }
            }
        }
        if (oldVersion < 7) {
            // 新增密码库表。老用户升级时库是空表，不影响既有书签/历史/下载。
            createCredentialsTable(db)
        }
    }

    companion object {
        const val DB_NAME = "ling.db"

        /**
         * 7：新增 credentials 表（密码库，密码只存 Keystore 加密后的密文）。
         * 6：downloads 增加 source 列（区分内建引擎与系统 DownloadManager）。
         * 5：history 增加 favicon 列（历史记录图标）。
         * 4：downloads 增加 download_location 列（下载保存位置）。
         * 3：bookmarks 增加 favicon 列（书签图标）。
         * 2：新增 downloads 表。
         * 1：初版（bookmarks / history / tabs）。
         */
        const val VERSION = 7
    }
}

// ------------------------------------------------------------------ 行映射

internal fun Cursor.toBookmark(): Bookmark = Bookmark(
    id = getLong(getColumnIndexOrThrow("id")),
    title = getString(getColumnIndexOrThrow("title")),
    url = getString(getColumnIndexOrThrow("url")),
    folder = getColumnIndex("folder").let { if (it >= 0 && !isNull(it)) getString(it) else null },
    createdAt = getLong(getColumnIndexOrThrow("created_at")),
    // 列可能不存在（老库尚未迁移完成），故不抛异常
    favicon = getColumnIndex("favicon").let {
        if (it >= 0 && !isNull(it)) getBlob(it) else null
    },
)

internal fun Cursor.toHistory(): HistoryEntry = HistoryEntry(
    id = getLong(getColumnIndexOrThrow("id")),
    title = getString(getColumnIndexOrThrow("title")),
    url = getString(getColumnIndexOrThrow("url")),
    visitedAt = getLong(getColumnIndexOrThrow("visited_at")),
    visitCount = getInt(getColumnIndexOrThrow("visit_count")),
    // 列可能不存在（老库尚未迁移完成），故不抛异常 —— 与书签那边同一处理
    favicon = getColumnIndex("favicon").let {
        if (it >= 0 && !isNull(it)) getBlob(it) else null
    },
)

internal fun Cursor.toTabSnapshot(): TabSnapshot = TabSnapshot(
    id = getString(getColumnIndexOrThrow("id")),
    url = getString(getColumnIndexOrThrow("url")),
    title = getString(getColumnIndexOrThrow("title")),
    position = getInt(getColumnIndexOrThrow("position")),
    isIncognito = getInt(getColumnIndexOrThrow("is_incognito")) != 0,
)

internal fun Cursor.toDownload(): DownloadEntry = DownloadEntry(
    id = getLong(getColumnIndexOrThrow("id")),
    downloadId = getLong(getColumnIndexOrThrow("download_id")),
    fileName = getString(getColumnIndexOrThrow("file_name")),
    url = getString(getColumnIndexOrThrow("url")),
    totalBytes = getLong(getColumnIndexOrThrow("total_bytes")),
    downloadedBytes = getLong(getColumnIndexOrThrow("downloaded_bytes")),
    mimeType = getColumnIndex("mime_type").let {
        if (it >= 0 && !isNull(it)) getString(it) else null
    },
    // 枚举名存库。取不到（例如降级安装、脏数据）时退回 PENDING 而不是崩溃。
    status = DownloadStatus.entries
        .firstOrNull { it.name == getString(getColumnIndexOrThrow("status")) }
        ?: DownloadStatus.PENDING,
    localPath = getColumnIndex("local_path").let {
        if (it >= 0 && !isNull(it)) getString(it) else null
    },
    downloadLocation = getColumnIndex("download_location").let {
        if (it >= 0 && !isNull(it)) {
            DownloadLocation.entries.firstOrNull { loc -> loc.name == getString(it) }
                ?: DownloadLocation.APP_PRIVATE
        } else DownloadLocation.APP_PRIVATE
    },
    // 列可能不存在（老库尚未迁移完成）：此时按内建引擎处理，与 v5 及更早的
    // 默认行为一致。迁移会按 download_id == id 回填历史系统条目。
    source = getColumnIndex("source").let {
        if (it >= 0 && !isNull(it)) {
            DownloadSource.entries.firstOrNull { s -> s.name == getString(it) }
                ?: DownloadSource.BUILT_IN
        } else DownloadSource.BUILT_IN
    },
    createdAt = getLong(getColumnIndexOrThrow("created_at")),
)

internal fun downloadValues(
    downloadId: Long,
    fileName: String,
    url: String,
    totalBytes: Long,
    downloadedBytes: Long,
    mimeType: String?,
    status: DownloadStatus,
    localPath: String?,
    downloadLocation: DownloadLocation,
    source: DownloadSource = DownloadSource.BUILT_IN,
    createdAt: Long,
) = ContentValues().apply {
    put("download_id", downloadId)
    put("file_name", fileName)
    put("url", url)
    put("total_bytes", totalBytes)
    put("downloaded_bytes", downloadedBytes)
    if (mimeType == null) putNull("mime_type") else put("mime_type", mimeType)
    put("status", status.name)
    if (localPath == null) putNull("local_path") else put("local_path", localPath)
    put("download_location", downloadLocation.name)
    put("source", source.name)
    put("created_at", createdAt)
}

internal fun bookmarkValues(title: String, url: String, folder: String?, createdAt: Long) =
    ContentValues().apply {
        put("title", title)
        put("url", url)
        if (folder == null) putNull("folder") else put("folder", folder)
        put("created_at", createdAt)
    }

internal fun historyValues(title: String, url: String, visitedAt: Long, visitCount: Int) =
    ContentValues().apply {
        put("title", title)
        put("url", url)
        put("visited_at", visitedAt)
        put("visit_count", visitCount)
    }

/**
 * 只映射元信息，**不读密码列**：密文只在需要解密时单独查询，
 * 避免列表刷新把整库密文都拖进内存。
 */
internal fun Cursor.toPasswordEntry(): PasswordEntry = PasswordEntry(
    id = getLong(getColumnIndexOrThrow("id")),
    site = getString(getColumnIndexOrThrow("site")),
    username = getString(getColumnIndexOrThrow("username")),
    title = getColumnIndex("title").let {
        if (it >= 0 && !isNull(it)) getString(it) else null
    },
    createdAt = getLong(getColumnIndexOrThrow("created_at")),
    updatedAt = getLong(getColumnIndexOrThrow("updated_at")),
    lastUsedAt = getColumnIndex("last_used_at").let {
        if (it >= 0 && !isNull(it)) getLong(it) else null
    },
)

internal fun credentialValues(
    site: String,
    username: String,
    ciphertext: ByteArray,
    iv: ByteArray,
    title: String?,
    now: Long,
) = ContentValues().apply {
    put("site", site)
    put("username", username)
    put("password_cipher", ciphertext)
    put("password_iv", iv)
    if (title == null) putNull("title") else put("title", title)
    put("created_at", now)
    put("updated_at", now)
}
