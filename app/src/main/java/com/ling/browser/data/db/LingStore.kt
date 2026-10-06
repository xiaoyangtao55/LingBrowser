package com.ling.browser.data.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

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
                visit_count INTEGER NOT NULL DEFAULT 1
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
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        // 按系统下载 ID 查记录是最高频的操作（每次刷新进度都要用）
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_download_download_id " +
                "ON downloads(download_id)"
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
        if (oldVersion < 3) {
            // 书签图标。NULL 表示还没有图标，UI 退回字母占位。
            // 存 BLOB 而不是路径/URL：图标很小（几十 KB 的 PNG），
            // 存库能让书签列表离线也显示图标，且卸载即清理、不留残 file。
            runCatching { db.execSQL("ALTER TABLE bookmarks ADD COLUMN favicon BLOB") }
        }
    }

    companion object {
        const val DB_NAME = "ling.db"

        /**
         * 3：bookmarks 增加 favicon 列（书签图标）。
         * 2：新增 downloads 表。
         * 1：初版（bookmarks / history / tabs）。
         */
        const val VERSION = 3
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
