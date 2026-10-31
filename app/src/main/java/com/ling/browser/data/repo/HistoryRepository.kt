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
     * 写入图标字节，并**回填给同一站点的其它记录**。已存在则覆盖。
     *
     * 为什么按站点回填而不是只写这一条：图标是站点级的资源
     * （`https://host/favicon.ico`），同一站点的不同页面拿到的永远是同一张图。
     * 而历史记录天然会攒下大量同站条目（几次搜索就是好几条 Bing），
     * 逐条抓等于把同一张图重复下载 N 次。
     *
     * 失败时（[bytes] 为空数组）同样回填：同站点的其它记录没必要再各试一遍。
     *
     * 只更新 favicon 一列：补图标是后台动作，不能反过来覆盖标题或访问时间。
     */
    suspend fun setFavicon(url: String, bytes: ByteArray?) = withContext(Dispatchers.IO) {
        val targets = urlsOnSameSite(url)
        val values = ContentValues().apply {
            if (bytes == null) putNull("favicon") else put("favicon", bytes)
        }
        val writable = db.writableDatabase
        // 一个事务里写完：同站点可能十几条，逐条 autocommit 会触发多次磁盘同步，
        // 而且中途失败会留下"一半有图标一半没有"的状态。
        writable.beginTransaction()
        try {
            targets.forEach { target ->
                writable.update("history", values, "url = ?", arrayOf(target))
            }
            writable.setTransactionSuccessful()
        } finally {
            writable.endTransaction()
        }
        refresh()
    }

    /**
     * 与 [url] 同站点的全部历史 URL（含它自己）。
     *
     * 解析不出 host 时退化成"只有它自己"：脏数据（比如导入时混进来的非标准
     * 地址）不该因为 host 为 null 而互相串图标。
     */
    private fun urlsOnSameSite(url: String): List<String> {
        val key = siteKeyOf(url) ?: return listOf(url)
        val sameSite = _history.value
            .filter { siteKeyOf(it.url) == key }
            .map { it.url }
        // 快照里可能还没有这条（刚记录、尚未 refresh），兜底把自身带上
        return if (url in sameSite) sameSite else sameSite + url
    }

    /**
     * 哪些历史记录还没有图标，按最近访问排序取前 [limit] 个。
     *
     * 三条约束：
     *
     *  1. **只看最近 [MAX_FAVICON_ROWS] 条**。历史是自动累积的，可能有上千条；
     *     给全部补图标意味着上千次网络请求 —— 而用户看历史基本只看最近的。
     *     老记录没有图标不影响使用（UI 有字母占位）。
     *  2. **null 才算缺失**，空数组表示"抓过但失败"，不再重试。
     *     不这样区分的话，每次打开历史页都会重新请求同一批注定失败的站点。
     *  3. **按站点去重**。一批 8 个如果全是同一站点的不同页面，等于只补了
     *     一个站点却排满了整批名额 —— 去重后同样一批能覆盖 8 个不同站点。
     *     （回填负责补齐同站点的其它条目，见 [setFavicon]。）
     *
     * 返回 URL 而不是实体：调用方只需要拿它去下载。
     */
    fun urlsMissingFavicon(limit: Int = 8): List<String> =
        pickFaviconTargets(_history.value, limit, MAX_FAVICON_ROWS)

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

// ------------------------------------------------------------------ 纯函数

/**
 * 从历史列表里挑出这一轮要抓图标的 URL。
 *
 * 抽成顶层纯函数是为了能脱离 Android 单独测（[HistoryRepository] 需要
 * SQLiteOpenHelper，JVM 单测里跑不起来）—— 与 `deriveFolders` 同一思路。
 *
 * 规则：
 *  - 只看前 [scanRows] 条（按最近访问排序）。历史可能上千条，全量补意味着
 *    上千次请求，而用户翻历史基本只看最近的。
 *  - `favicon == null` 才算缺失。空数组表示"抓过但失败"，跳过、不重试。
 *  - **按站点去重**：一批名额应当覆盖尽可能多的站点，而不是被同一站点的
 *    多个页面占满。同站点的其它条目由 [HistoryRepository.setFavicon] 回填。
 *  - host 解析不出来时退回用 URL 本身做去重键，避免脏数据被误判成同一站点。
 */
internal fun pickFaviconTargets(
    history: List<HistoryEntry>,
    limit: Int,
    scanRows: Int,
): List<String> {
    if (limit <= 0 || scanRows <= 0) return emptyList()
    val seen = HashSet<String>()
    val picked = ArrayList<String>(limit)
    for (entry in history.take(scanRows)) {
        if (picked.size >= limit) break
        if (entry.favicon != null) continue
        val key = siteKeyOf(entry.url) ?: entry.url
        if (!seen.add(key)) continue
        picked.add(entry.url)
    }
    return picked
}

/**
 * 站点键：`host`（小写），非默认端口时带上 `:port`。解析失败返回 null。
 *
 * 为什么必须带端口：[URI.host] 不含端口，`localhost:8080` 与 `localhost:9090`
 * 会被当成同一站点 —— 而它们通常是两个完全不同的服务，图标也各不相同。
 * 默认端口（80/443，[URI.port] 为 -1）不拼，避免 "example.com:443" 这种噪音。
 */
internal fun siteKeyOf(url: String): String? {
    val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return null
    val host = uri.host?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
    // URI 只在**省略**端口时返回 -1；显式写出来的 `:443` 会如实返回 443。
    // 所以要显式比对默认端口，否则 https://a.com 与 https://a.com:443
    // 会被当成两个站点，各抓一次图标。
    val defaultPort = when (uri.scheme?.lowercase()) {
        "http" -> 80
        "https" -> 443
        else -> -1
    }
    return if (uri.port > 0 && uri.port != defaultPort) "$host:${uri.port}" else host
}
