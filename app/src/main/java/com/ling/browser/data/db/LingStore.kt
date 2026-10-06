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
                created_at INTEGER NOT NULL
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
        // 尚未正式发版，直接重建即可；发版后必须替换为增量迁移，
        // 否则用户升级会丢掉全部书签与历史。
        db.execSQL("DROP TABLE IF EXISTS bookmarks")
        db.execSQL("DROP TABLE IF EXISTS history")
        db.execSQL("DROP TABLE IF EXISTS tabs")
        db.execSQL("DROP TABLE IF EXISTS downloads")
        onCreate(db)
    }

    companion object {
        const val DB_NAME = "ling.db"

        /**
         * 2：新增 downloads 表。
         * 1：初版（bookmarks / history / tabs）。
         */
        const val VERSION = 2
    }
}

// ------------------------------------------------------------------ 行映射

internal fun Cursor.toBookmark(): Bookmark = Bookmark(
    id = getLong(getColumnIndexOrThrow("id")),
    title = getString(getColumnIndexOrThrow("title")),
    url = getString(getColumnIndexOrThrow("url")),
    folder = getColumnIndex("folder").let { if (it >= 0 && !isNull(it)) getString(it) else null },
    createdAt = getLong(getColumnIndexOrThrow("created_at")),
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
