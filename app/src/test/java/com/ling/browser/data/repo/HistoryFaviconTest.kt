package com.ling.browser.data.repo

import com.ling.browser.data.db.HistoryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 历史图标挑选规则的回归。
 *
 * 这些规则都是"看起来直觉、改起来容易错"的地方：去重与回填是一对，
 * 改了一个不改另一个就会出现「同一个站点反复抓」或「有的有图标有的没有」。
 */
class HistoryFaviconTest {

    private fun entry(
        url: String,
        favicon: ByteArray? = null,
        visitedAt: Long = 0L,
    ) = HistoryEntry(
        id = url.hashCode().toLong(),
        title = url,
        url = url,
        visitedAt = visitedAt,
        favicon = favicon,
    )

    /** 抓过但失败的标记：空数组。 */
    private val tried = ByteArray(0)
    private val ok = byteArrayOf(1, 2, 3)

    @Test
    fun `同站点多个页面只取一条`() {
        val history = listOf(
            entry("https://cn.bing.com/search?q=a"),
            entry("https://cn.bing.com/search?q=b"),
            entry("https://cn.bing.com/search?q=c"),
        )
        val picked = pickFaviconTargets(history, limit = 8, scanRows = 50)

        assertEquals(1, picked.size)
        // 取出现顺序里的第一条
        assertEquals("https://cn.bing.com/search?q=a", picked[0])
    }

    @Test
    fun `不同站点各自占一个名额`() {
        val history = listOf(
            entry("https://a.com/1"),
            entry("https://b.com/2"),
            entry("https://c.com/3"),
        )
        val picked = pickFaviconTargets(history, limit = 8, scanRows = 50)

        assertEquals(listOf("https://a.com/1", "https://b.com/2", "https://c.com/3"), picked)
    }

    @Test
    fun `已抓过但失败的跳过 不重复请求`() {
        val history = listOf(
            entry("https://a.com/1", favicon = tried),
            entry("https://b.com/2"),
        )
        val picked = pickFaviconTargets(history, limit = 8, scanRows = 50)

        assertEquals(listOf("https://b.com/2"), picked)
    }

    @Test
    fun `已有图标的跳过`() {
        val history = listOf(
            entry("https://a.com/1", favicon = ok),
            entry("https://b.com/2"),
        )
        val picked = pickFaviconTargets(history, limit = 8, scanRows = 50)

        assertEquals(listOf("https://b.com/2"), picked)
    }

    @Test
    fun `limit 生效`() {
        val history = (1..10).map { entry("https://site$it.com/page") }
        val picked = pickFaviconTargets(history, limit = 3, scanRows = 50)

        assertEquals(3, picked.size)
        assertEquals("https://site1.com/page", picked[0])
    }

    @Test
    fun `scanRows 限制只看最近若干条`() {
        // 前 2 条已经有了，剩下的在第 3 条之后 —— scanRows=2 时不该看到它们
        val history = listOf(
            entry("https://a.com/1", favicon = ok),
            entry("https://b.com/2", favicon = ok),
            entry("https://c.com/3"),
        )
        val picked = pickFaviconTargets(history, limit = 8, scanRows = 2)

        assertTrue(picked.isEmpty())
    }

    @Test
    fun `limit 或 scanRows 非正数时返回空`() {
        val history = listOf(entry("https://a.com/1"))
        assertTrue(pickFaviconTargets(history, limit = 0, scanRows = 50).isEmpty())
        assertTrue(pickFaviconTargets(history, limit = 8, scanRows = 0).isEmpty())
        assertTrue(pickFaviconTargets(history, limit = -1, scanRows = 50).isEmpty())
    }

    @Test
    fun `解析不出 host 的地址用自身去重 不会互相吞掉`() {
        // 这类地址进不了历史（record 已过滤），但导入的历史可能带脏数据
        val history = listOf(
            entry("not-a-url-1"),
            entry("not-a-url-2"),
        )
        val picked = pickFaviconTargets(history, limit = 8, scanRows = 50)

        assertEquals(2, picked.size)
    }

    @Test
    fun `host 大小写不同视为同一站点`() {
        val history = listOf(
            entry("https://Example.COM/1"),
            entry("https://example.com/2"),
        )
        val picked = pickFaviconTargets(history, limit = 8, scanRows = 50)

        assertEquals(1, picked.size)
    }

    @Test
    fun `不同端口算不同站点`() {
        // URI.host 不含端口，只看 host 的话 localhost:8080 与 localhost:9090
        // 会被并成一条 —— 而它们通常是两个完全不同的服务。站点键必须带端口。
        val history = listOf(
            entry("http://localhost:8080/a"),
            entry("http://localhost:9090/b"),
        )
        val picked = pickFaviconTargets(history, limit = 8, scanRows = 50)

        assertEquals(2, picked.size)
    }

    @Test
    fun `siteKeyOf 解析常规地址`() {
        assertEquals("cn.bing.com", siteKeyOf("https://cn.bing.com/search?q=a"))
        // host 统一小写
        assertEquals("example.com", siteKeyOf("http://Example.com/x"))
    }

    @Test
    fun `siteKeyOf 默认端口不拼进键`() {
        // URI.port 对默认端口返回 -1，因此不会出现 "example.com:443" 这种噪音
        assertEquals("example.com", siteKeyOf("https://example.com/x"))
        assertEquals("example.com", siteKeyOf("https://example.com:443/x"))
    }

    @Test
    fun `siteKeyOf 非默认端口带上端口`() {
        assertEquals("localhost:8080", siteKeyOf("http://localhost:8080/a"))
    }

    @Test
    fun `siteKeyOf 对畸形地址返回 null`() {
        assertNull(siteKeyOf("not a url"))
        assertNull(siteKeyOf(""))
    }
}
