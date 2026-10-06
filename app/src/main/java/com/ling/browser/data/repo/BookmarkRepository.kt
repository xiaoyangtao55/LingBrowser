package com.ling.browser.data.repo

import android.content.ContentValues
import com.ling.browser.data.db.Bookmark
import com.ling.browser.data.db.LingDatabase
import com.ling.browser.data.db.bookmarkValues
import com.ling.browser.data.db.toBookmark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * 书签仓库。
 *
 * 对外暴露 [bookmarks] StateFlow：所有写操作在写库后立即刷新快照，
 * UI 直接 collect 即可，不需要额外的通知机制。
 */
class BookmarkRepository(private val db: LingDatabase) {

    private val _bookmarks = MutableStateFlow<List<Bookmark>>(emptyList())
    val bookmarks: StateFlow<List<Bookmark>> = _bookmarks.asStateFlow()

    init {
        // 首次构造时同步加载一次（在主线程做一次轻量查询可接受）；
        // 之后由各写操作负责刷新。
        refresh()
    }

    fun refresh() {
        _bookmarks.value = queryAll()
    }

    private fun queryAll(): List<Bookmark> = runCatching {
        db.readableDatabase.rawQuery("SELECT * FROM bookmarks ORDER BY created_at DESC", null)
            .use { c -> buildList { while (c.moveToNext()) add(c.toBookmark()) } }
    }.getOrDefault(emptyList())

    suspend fun search(query: String): List<Bookmark> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val like = "%$query%"
        runCatching {
            db.readableDatabase.rawQuery(
                "SELECT * FROM bookmarks WHERE title LIKE ? OR url LIKE ? ORDER BY created_at DESC LIMIT 20",
                arrayOf(like, like),
            ).use { c -> buildList { while (c.moveToNext()) add(c.toBookmark()) } }
        }.getOrDefault(emptyList())
    }

    suspend fun isBookmarked(url: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            db.readableDatabase.rawQuery("SELECT 1 FROM bookmarks WHERE url = ? LIMIT 1", arrayOf(url))
                .use { it.moveToFirst() }
        }.getOrDefault(false)
    }

    /** 已收藏则取消，未收藏则添加。返回操作后是否处于「已收藏」状态。 */
    suspend fun toggle(title: String, url: String): Boolean = withContext(Dispatchers.IO) {
        val exists = isBookmarked(url)
        if (exists) {
            db.writableDatabase.delete("bookmarks", "url = ?", arrayOf(url))
        } else {
            db.writableDatabase.insertWithOnConflict(
                "bookmarks",
                null,
                bookmarkValues(title.ifBlank { url }, url, null, System.currentTimeMillis()),
                android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
            )
        }
        refresh()
        !exists
    }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        db.writableDatabase.delete("bookmarks", "id = ?", arrayOf(id.toString()))
        refresh()
    }

    /** 只改标题/文件夹，不动 url。 */
    suspend fun rename(id: Long, title: String, folder: String?) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("title", title)
            if (folder == null) putNull("folder") else put("folder", folder)
        }
        db.writableDatabase.update("bookmarks", values, "id = ?", arrayOf(id.toString()))
        refresh()
    }
}
