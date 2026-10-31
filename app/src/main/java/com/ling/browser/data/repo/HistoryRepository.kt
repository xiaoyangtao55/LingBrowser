package com.ling.browser.data.repo

import android.content.ContentValues
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

    /**
     * 批量写入导入的记录，返回真正新增的条数。
     *
     * 用 `CONFLICT_IGNORE` 而不是先查后插：库里 `url` 上有唯一索引，
     * 由它来裁决重复既省一次查询，也避免"查的时候没有、插的时候已经存在"的竞态。
     * 走事务是因为导入可能有上千条，逐条 autocommit 会慢得肉眼可见。
     */
    suspend fun importAll(entries: List<HistoryEntry>): Int = withContext(Dispatchers.IO) {
        if (entries.isEmpty()) return@withContext 0
        val db = db.writableDatabase
        var added = 0
        db.beginTransaction()
        try {
            for (entry in entries) {
                val row = db.insertWithOnConflict(
                    "history",
                    null,
                    historyValues(entry.title, entry.url, entry.visitedAt, entry.visitCount),
                    android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE,
                )
                if (row != -1L) added++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        refresh()
        added
    }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        db.writableDatabase.delete("history", "id = ?", arrayOf(id.toString()))
        refresh()
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        db.writableDatabase.delete("history", null, null)
        refresh()
    }

    // ------------------------------------------------------------ 网站图标

    /**
     * 写入某条历史的图标字节。已存在则覆盖。
     *
     * 只更新 favicon 一列：补图标是后台动作，不能反过来覆盖标题或访问时间。
     * 按 url 定位而不是 id —— 同一 URL 在库里去重后只有一行。
     */
    suspend fun setFavicon(url: String, bytes: ByteArray?) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            if (bytes == null) putNull("favicon") else put("favicon", bytes)
        }
        db.writableDatabase.update("history", values, "url = ?", arrayOf(url))
        refresh()
    }

    /**
     * 哪些历史记录还没有图标，按最近访问排序取前 [limit] 个。
     *
     * 两条约束，都与书签那边不同：
     *
     *  1. **只看最近 [MAX_FAVICON_ROWS] 条**。历史是自动累积的，可能有上千条；
     *     给全部补图标意味着上千次网络请求 —— 而用户看历史基本只看最近的。
     *     老记录没有图标不影响使用（UI 有字母占位）。
     *  2. **null 才算缺失**，空数组表示"抓过但失败"，不再重试。
     *     不这样区分的话，每次打开历史页都会重新请求同一批注定失败的站点。
     *
     * 返回 URL 而不是实体：调用方只需要拿它去下载。
     */
    fun urlsMissingFavicon(limit: Int = 8): List<String> =
        _history.value.asSequence()
            .filter { it.favicon == null }
            .take(MAX_FAVICON_ROWS)
            .map { it.url }
            .take(limit)
            .toList()

    private companion object {
        /**
         * 参与图标抓取的历史条目上限（按最近访问排序的前 N 条）。
         *
         * 50 条足以覆盖"用户翻回最近浏览"的实际范围，同时把最坏情况下的
         * 网络请求量压到 50 次以内 —— 且只在用户进历史页时才发生。
         */
        const val MAX_FAVICON_ROWS = 50
    }
}
