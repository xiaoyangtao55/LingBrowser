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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 早期版本直接重建；正式发版前应替换为增量迁移。
        db.execSQL("DROP TABLE IF EXISTS bookmarks")
        db.execSQL("DROP TABLE IF EXISTS history")
        db.execSQL("DROP TABLE IF EXISTS tabs")
        onCreate(db)
    }

    companion object {
        const val DB_NAME = "ling.db"
        const val VERSION = 1
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
