package com.ling.browser.data.repo

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.ling.browser.data.db.LingDatabase
import com.ling.browser.data.db.TabSnapshot
import com.ling.browser.data.db.toTabSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 会话（标签页）持久化仓库。
 *
 * 职责很小但有两个必须想清楚的点：
 *
 * **一、不保存无痕标签。** 无痕模式的全部意义就是「关掉不留痕」。
 * 把它的 URL 写进磁盘，哪怕只是本地 SQLite，也直接违背了这个承诺 ——
 * 用户下次启动会看到上次无痕浏览的网站。所以 [save] 直接过滤掉
 * `isIncognito` 的标签，恢复时自然也不会出现。
 *
 * **二、主页标签是否值得存。** 存。用户可能同时开着主页和若干网页，
 * 全部还原才是「恢复现场」。主页有固定的逻辑地址（ling://home），
 * 存下来再加载是安全的 —— 见 [com.ling.browser.web.HomePage.isHomeUrl]。
 *
 * 表结构固定为「全量替换」：先 DELETE 再批量 INSERT。
 * 标签数量是个位数，没必要做增量 diff；而增量更新一旦漏掉删除操作，
 * 就会残留已被关掉的标签，很难查。
 */
class SessionRepository(private val db: LingDatabase) {

    /** 读出上次的标签快照，按 position 升序（即用户看到的顺序）。 */
    suspend fun load(): List<TabSnapshot> = withContext(Dispatchers.IO) {
        runCatching {
            db.readableDatabase.rawQuery(
                "SELECT * FROM tabs ORDER BY position ASC",
                null,
            ).use { c -> buildList { while (c.moveToNext()) add(c.toTabSnapshot()) } }
        }.getOrDefault(emptyList())
    }

    /**
     * 全量覆盖保存。[snapshots] 里的 `isIncognito` 项会被丢弃。
     *
     * 放在一个事务里：中途失败（例如进程被杀）时要么全是旧数据、
     * 要么全是新数据，不会出现"删了旧的、还没来得及写新的"这种空窗 ——
     * 那会让用户的标签全丢。
     */
    suspend fun save(snapshots: List<TabSnapshot>) = withContext(Dispatchers.IO) {
        val keep = snapshots.filterNot { it.isIncognito }
        runCatching {
            val wdb = db.writableDatabase
            wdb.beginTransaction()
            try {
                wdb.delete("tabs", null, null)
                keep.forEachIndexed { index, snap ->
                    val values = ContentValues().apply {
                        put("id", snap.id)
                        put("url", snap.url)
                        put("title", snap.title)
                        // 用当前下标而不是 snap.position：
                        // 保证落库的顺序与入参顺序严格一致，不留空洞。
                        put("position", index)
                        put("is_incognito", if (snap.isIncognito) 1 else 0)
                    }
                    wdb.insertWithOnConflict(
                        "tabs",
                        null,
                        values,
                        SQLiteDatabase.CONFLICT_REPLACE,
                    )
                }
                wdb.setTransactionSuccessful()
            } finally {
                wdb.endTransaction()
            }
        }
    }

    /** 清空快照。用户在设置里清除数据时调用。 */
    suspend fun clear() = withContext(Dispatchers.IO) {
        runCatching { db.writableDatabase.delete("tabs", null, null) }
    }
}
