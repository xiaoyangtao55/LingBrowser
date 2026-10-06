package com.ling.browser.data.repo

import com.ling.browser.data.db.HistoryEntry
import com.ling.browser.data.db.LingDatabase
import com.ling.browser.data.db.historyValues
import com.ling.browser.data.db.toHistory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** 浏览历史仓库。 */
class HistoryRepository(private val db: LingDatabase) {

    private val _history = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val history: StateFlow<List<HistoryEntry>> = _history.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _history.value = queryAll()
    }

    private fun queryAll(): List<HistoryEntry> = runCatching {
        db.readableDatabase.rawQuery("SELECT * FROM history ORDER BY visited_at DESC", null)
            .use { c -> buildList { while (c.moveToNext()) add(c.toHistory()) } }
    }.getOrDefault(emptyList())

    suspend fun search(query: String): List<HistoryEntry> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val like = "%$query%"
        runCatching {
            db.readableDatabase.rawQuery(
                "SELECT * FROM history WHERE title LIKE ? OR url LIKE ? ORDER BY visited_at DESC LIMIT 20",
                arrayOf(like, like),
            ).use { c -> buildList { while (c.moveToNext()) add(c.toHistory()) } }
        }.getOrDefault(emptyList())
    }

    /**
     * 记录一次访问。同一 URL 已存在时只累加次数并刷新时间，
     * 避免历史列表被同一页面刷屏（与 Via 的「去重 + 置顶」行为一致）。
     */
    suspend fun record(title: String, url: String, incognito: Boolean) = withContext(Dispatchers.IO) {
        if (incognito) return@withContext // 无痕模式不落库
        if (url.isBlank() || url.startsWith("about:") || url.startsWith("data:")) return@withContext

        val existing = runCatching {
            db.readableDatabase.rawQuery("SELECT * FROM history WHERE url = ? LIMIT 1", arrayOf(url))
                .use { c -> if (c.moveToFirst()) c.toHistory() else null }
        }.getOrNull()

        val now = System.currentTimeMillis()
        // 用 UPDATE 保留原行 id（INSERT OR REPLACE 会换掉整行、改掉 id，
        // 导致 UI 上正在展示的那条记录的删除按钮失效）。
        if (existing == null) {
            db.writableDatabase.insertWithOnConflict(
                "history",
                null,
                historyValues(title.ifBlank { url }, url, now, 1),
                android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE,
            )
        } else {
            db.writableDatabase.update(
                "history",
                historyValues(title.ifBlank { existing.title }, url, now, existing.visitCount + 1),
                "url = ?",
                arrayOf(url),
            )
        }
        refresh()
    }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        db.writableDatabase.delete("history", "id = ?", arrayOf(id.toString()))
        refresh()
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        db.writableDatabase.delete("history", null, null)
        refresh()
    }
}
