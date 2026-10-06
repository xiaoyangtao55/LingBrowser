package com.ling.browser.web

import com.ling.browser.data.db.TabSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话持久化的**过滤规则**测试。
 *
 * 核心是一条隐私承诺：**无痕标签绝不能落盘**。
 * 无痕模式的意义就是"关掉不留痕"，只要 URL 被写进 SQLite，
 * 用户下次启动就会看到上次无痕浏览的网站。
 *
 * 这条约束很容易在后续重构中被无声破坏（比如有人为了"完整恢复现场"
 * 把过滤去掉），所以用测试钉住。
 */
class SessionSnapshotTest {

    private fun tab(
        url: String = "https://example.com",
        title: String = "示例",
        incognito: Boolean = false,
    ) = TabState(url = url, title = title, isIncognito = incognito)

    // ------------------------------------------------------- 无痕绝不落盘

    @Test
    fun `无痕标签不产生持久化快照`() {
        val t = tab(url = "https://secret.example", incognito = true)
        assertFalse(
            "无痕标签必须被排除，否则用户下次启动会看到上次无痕浏览的网站",
            t.toSnapshot(0).isPersistable,
        )
    }

    @Test
    fun `普通标签可以持久化`() {
        assertTrue(tab().toSnapshot(0).isPersistable)
    }

    @Test
    fun `空白地址不持久化`() {
        // 空 URL 恢复出来只会得到一个打不开的标签，属于脏数据
        assertFalse(TabSnapshot(id = "x", url = "", title = "", position = 0).isPersistable)
        assertFalse(TabSnapshot(id = "x", url = "   ", title = "", position = 0).isPersistable)
    }

    @Test
    fun `主页应当被持久化`() {
        // 用户可能同时开着主页和若干网页，"完整还原现场"才是对的。
        // 主页有固定逻辑地址，重新加载是安全的。
        val home = tab(url = com.ling.browser.util.UrlUtils.HOME_URL, title = "主页")
        assertTrue("主页应能恢复", home.toSnapshot(0).isPersistable)
    }

    // ------------------------------------------------------- 快照内容

    @Test
    fun `快照保留 id 让重启前后是同一个标签`() {
        val t = tab()
        assertEquals(t.id, t.toSnapshot(0).id)
    }

    @Test
    fun `快照保留 url 与 title`() {
        val t = tab(url = "https://a.example/b", title = "标题 B")
        val s = t.toSnapshot(3)
        assertEquals("https://a.example/b", s.url)
        assertEquals("标题 B", s.title)
        assertEquals(3, s.position)
    }

    @Test
    fun `恢复后不再是无痕`() {
        // 快照里本来就不该有无痕项；即便手工构造一个，
        // isPersistable 也必须把它挡掉。
        val sneaky = TabSnapshot(
            id = "s", url = "https://x.example", title = "x",
            position = 0, isIncognito = true,
        )
        assertFalse(sneaky.isPersistable)
        // 模拟仓库层的过滤
        val kept = listOf(sneaky, tab().toSnapshot(1)).filter { it.isPersistable }
        assertEquals("无痕项必须被过滤掉，只留下普通标签", 1, kept.size)
        assertFalse(kept.first().isIncognito)
    }

    // ------------------------------------------------------- 顺序

    @Test
    fun `position 按列表顺序递增`() {
        val tabs = listOf(tab(url = "https://a.example"), tab(url = "https://b.example"))
        val snaps = tabs.mapIndexed { i, t -> t.toSnapshot(i) }
        assertEquals(listOf(0, 1), snaps.map { it.position })
    }

    @Test
    fun `过滤无痕后剩余项的相对顺序不变`() {
        val tabs = listOf(
            tab(url = "https://a.example"),
            tab(url = "https://secret.example", incognito = true),
            tab(url = "https://c.example"),
        )
        val snaps = tabs.mapIndexed { i, t -> t.toSnapshot(i) }
            .filter { it.isPersistable }
        assertEquals(
            "过滤掉中间的无痕项后，a 与 c 的前后关系必须保持",
            listOf("https://a.example", "https://c.example"),
            snaps.map { it.url },
        )
    }
}
