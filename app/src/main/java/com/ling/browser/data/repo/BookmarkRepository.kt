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

    // ------------------------------------------------------------ 文件夹

    /**
     * 所有已存在的文件夹名，按字典序。
     *
     * 直接从书签表 `SELECT DISTINCT folder` 派生，**不另建 folders 表**。
     * 理由：文件夹的唯一定义就是"有书签归属于它"。单独建表会引入
     * 两个真相来源 —— 删掉最后一个书签后文件夹表里还留着一个空壳，
     * 用户看到点进去什么都没有的幽灵文件夹。派生法天然不会出现这种状态。
     *
     * 代价是重命名文件夹要批量 UPDATE 所有归属书签（见 [renameFolder]），
     * 但书签量级很小，完全可以接受。
     */
    fun folders(): List<String> = deriveFolders(_bookmarks.value)

    /** 指定文件夹内的书签；[folder] 为 null 表示"未分类"。 */
    fun inFolder(folder: String?): List<Bookmark> = _bookmarks.value.filter { it.folder == folder }

    /** 把书签移动到某文件夹（null = 移出到未分类）。 */
    suspend fun move(id: Long, folder: String?) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            if (folder == null) putNull("folder") else put("folder", folder)
        }
        db.writableDatabase.update("bookmarks", values, "id = ?", arrayOf(id.toString()))
        refresh()
    }

    /**
     * 重命名文件夹：把归属于 [from] 的书签全部改挂到 [to]。
     *
     * 用一条 UPDATE 批量完成，而不是逐条 move —— 逐条会在中途失败时
     * 留下一半旧名一半新名的状态。
     *
     * [to] 为 null 表示"解散文件夹"，里面的书签退回未分类（不删除书签）。
     */
    suspend fun renameFolder(from: String, to: String?) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            if (to == null) putNull("folder") else put("folder", to)
        }
        db.writableDatabase.update("bookmarks", values, "folder = ?", arrayOf(from))
        refresh()
    }

    /**
     * 删除文件夹，**但保留其中的书签**（退回未分类）。
     *
     * 刻意不连带删除书签：用户说"删掉这个文件夹"时，几乎不会是要
     * 把里面的收藏一起丢掉。真要删书签可以进文件夹逐条删。
     */
    suspend fun deleteFolder(folder: String) = renameFolder(folder, null)

    /** 该文件夹名是否已被占用（大小写不敏感）。 */
    fun folderExists(name: String): Boolean =
        folders().any { it.equals(name.trim(), ignoreCase = true) }

    // ------------------------------------------------------------ 网站图标

    /**
     * 写入某个书签的图标字节。已存在则覆盖。
     *
     * 只更新 favicon 一列，不动标题/文件夹 —— 避免"补图标"这个后台动作
     * 反过来覆盖掉用户刚改的名字。
     */
    suspend fun setFavicon(url: String, bytes: ByteArray?) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            if (bytes == null) putNull("favicon") else put("favicon", bytes)
        }
        db.writableDatabase.update("bookmarks", values, "url = ?", arrayOf(url))
        refresh()
    }

    /**
     * 哪些书签还没有图标，最多取 [limit] 个。
     *
     * "没有"指 favicon 为 **null**。空数组（`ByteArray(0)`）表示
     * "已经尝试抓取但失败了"，不算缺失 —— 否则每次打开书签页都会
     * 重新请求同一批注定失败的站点，白耗流量。
     *
     * 返回 URL 而不是 Bookmark：调用方只需要拿它去下载，
     * 传整个对象会让 favicon 字段跟着一起流转，容易误用。
     */
    fun urlsMissingFavicon(limit: Int = 8): List<String> =
        _bookmarks.value.filter { it.favicon == null }.map { it.url }.take(limit)
}

// ------------------------------------------------------------------ 纯函数

/**
 * 从书签列表推导出文件夹名集合。
 *
 * 抽成顶层纯函数是为了能脱离 Android 单独测（`BookmarkRepository`
 * 需要 SQLiteOpenHelper，JVM 单测里跑不起来）。
 *
 * 几个规则：
 *  - `null`（未分类）与空串**不算**文件夹。空串是脏数据，
 *    早期版本的 insert 可能写过，直接当成"未分类"处理。
 *  - 名字做 `trim` 后再去重：`" 新闻 "` 和 `"新闻"` 是同一个文件夹，
 *    否则用户会看到两个看起来完全一样的条目。
 *  - 去重**大小写敏感**（`News` 与 `news` 算两个）：中文场景无影响，
 *    而英文用户确实可能想要两个不同的文件夹。
 *  - 结果按字典序，保证 UI 顺序稳定（`ignoreCase` 排序，
 *    避免小写名全部排在大写名之后）。
 */
internal fun deriveFolders(bookmarks: List<Bookmark>): List<String> =
    bookmarks.mapNotNull { it.folder?.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .sortedWith(String.CASE_INSENSITIVE_ORDER)
