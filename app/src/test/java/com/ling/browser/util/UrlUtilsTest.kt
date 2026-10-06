package com.ling.browser.util

import com.ling.browser.data.prefs.SearchEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 地址栏解析的单元测试。
 *
 * 这是全应用最容易出错、也最影响手感的一段纯逻辑：
 * 判断「输入的是网址还是搜索词」一旦判断错，用户就会看到搜索结果页而不是目标站点。
 */
class UrlUtilsTest {

    // ------------------------------------------------------------ 完整 URL

    @Test
    fun `完整 URL 原样返回`() {
        assertEquals("https://example.com", UrlUtils.toUrl("https://example.com", SearchEngine.BAIDU))
        assertEquals("http://a.b.c/d?e=1", UrlUtils.toUrl("http://a.b.c/d?e=1", SearchEngine.BAIDU))
        assertEquals(
            "https://example.com/path?q=1#frag",
            UrlUtils.toUrl("https://example.com/path?q=1#frag", SearchEngine.BAIDU),
        )
    }

    @Test
    fun `特殊 scheme 原样保留`() {
        assertEquals("about:blank", UrlUtils.toUrl("about:blank", SearchEngine.BAIDU))
        assertEquals("ftp://x.com/f", UrlUtils.toUrl("ftp://x.com/f", SearchEngine.BAIDU))
        assertEquals(
            "file:///sdcard/a.html",
            UrlUtils.toUrl("file:///sdcard/a.html", SearchEngine.BAIDU),
        )
    }

    // ------------------------------------------------------------ 裸域名

    @Test
    fun `裸域名补 https`() {
        assertEquals("https://example.com", UrlUtils.toUrl("example.com", SearchEngine.BAIDU))
        assertEquals("https://cn.bing.com", UrlUtils.toUrl("cn.bing.com", SearchEngine.BAIDU))
        assertEquals("https://sub.domain.co.uk", UrlUtils.toUrl("sub.domain.co.uk", SearchEngine.BAIDU))
    }

    @Test
    fun `裸域名带路径补 https`() {
        assertEquals(
            "https://example.com/path?q=1",
            UrlUtils.toUrl("example.com/path?q=1", SearchEngine.BAIDU),
        )
    }

    @Test
    fun `IP 与端口走 http`() {
        assertEquals("http://192.168.1.1", UrlUtils.toUrl("192.168.1.1", SearchEngine.BAIDU))
        assertEquals("http://192.168.1.1:8080", UrlUtils.toUrl("192.168.1.1:8080", SearchEngine.BAIDU))
    }

    @Test
    fun `中文域名补 https`() {
        assertEquals("https://例子.中国", UrlUtils.toUrl("例子.中国", SearchEngine.BAIDU))
    }

    // ------------------------------------------------------------ 搜索词

    @Test
    fun `含空格走搜索`() {
        val url = UrlUtils.toUrl("hello world", SearchEngine.BAIDU)
        assertTrue("应以百度搜索开头: $url", url.startsWith("https://www.baidu.com/s?wd="))
        assertTrue("空格应被编码: $url", url.contains("hello+world") || url.contains("hello%20world"))
    }

    @Test
    fun `纯中文无点走搜索`() {
        val url = UrlUtils.toUrl("中文搜索", SearchEngine.BAIDU)
        assertTrue("应走搜索: $url", url.startsWith("https://www.baidu.com/s?wd="))
    }

    @Test
    fun `单个词走搜索`() {
        val url = UrlUtils.toUrl("baidu", SearchEngine.BING)
        assertTrue("应走必应搜索: $url", url.startsWith("https://www.bing.com/search?q="))
    }

    @Test
    fun `各搜索引擎模板都正确`() {
        SearchEngine.entries.forEach { engine ->
            val url = UrlUtils.searchUrl("test", engine)
            assertTrue("${engine.label} 应包含编码后的查询词: $url", url.contains("test"))
            assertFalse("不应残留占位符: $url", url.contains("%s"))
        }
    }

    @Test
    fun `中文关键词被正确编码`() {
        val url = UrlUtils.searchUrl("测试", SearchEngine.BAIDU)
        assertFalse("不应出现未编码的中文: $url", url.contains("测试"))
        assertTrue("应包含百分号编码: $url", url.contains("%"))
    }

    // ------------------------------------------------------------ 边界

    @Test
    fun `空输入返回主页`() {
        assertEquals(SearchEngine.BAIDU.homepage, UrlUtils.toUrl("", SearchEngine.BAIDU))
        assertEquals(SearchEngine.BAIDU.homepage, UrlUtils.toUrl("   ", SearchEngine.BAIDU))
    }

    @Test
    fun `本地绝对路径转 file URL`() {
        assertEquals("file:///sdcard/a.html", UrlUtils.toUrl("/sdcard/a.html", SearchEngine.BAIDU))
    }

    @Test
    fun `Windows 路径转 file URL`() {
        assertEquals(
            "file://C:\\Users\\a.html",
            UrlUtils.toUrl("C:\\Users\\a.html", SearchEngine.BAIDU),
        )
        assertEquals("file://D:/x/y.html", UrlUtils.toUrl("D:/x/y.html", SearchEngine.BAIDU))
    }

    @Test
    fun `Windows 盘符不会被当成 scheme`() {
        // 回归用例：C: 只有 1 个字母，不能匹配 scheme；且 .html 不能触发裸域名分支
        val url = UrlUtils.toUrl("C:\\Users\\a.html", SearchEngine.BAIDU)
        assertTrue("应为 file URL，实际: $url", url.startsWith("file://"))
        assertFalse("不应走搜索", url.contains("baidu.com/s"))
    }

    @Test
    fun `不带双斜杠的 scheme 不被误判为搜索`() {
        // 回归用例：about:blank / data: / mailto: 没有 //
        assertEquals("about:blank", UrlUtils.toUrl("about:blank", SearchEngine.BAIDU))
        assertEquals(
            "mailto:a@b.com",
            UrlUtils.toUrl("mailto:a@b.com", SearchEngine.BAIDU),
        )
        assertTrue(
            UrlUtils.toUrl("data:text/html,hi", SearchEngine.BAIDU).startsWith("data:"),
        )
    }

    @Test
    fun `isUrl 判定`() {
        assertTrue(UrlUtils.isUrl("https://example.com"))
        assertTrue(UrlUtils.isUrl("example.com"))
        assertTrue(UrlUtils.isUrl("192.168.1.1"))
        assertFalse("含空格不是 URL", UrlUtils.isUrl("hello world"))
        assertFalse("单个词不是 URL", UrlUtils.isUrl("baidu"))
        assertFalse(UrlUtils.isUrl(""))
    }

    // ------------------------------------------------------------ 展示用

    @Test
    fun `prettify 去掉 scheme 与 www`() {
        assertEquals("example.com", UrlUtils.prettify("https://www.example.com/"))
        assertEquals("example.com/a", UrlUtils.prettify("https://www.example.com/a"))
        assertEquals("example.com/a", UrlUtils.prettify("http://example.com/a"))
    }

    @Test
    fun `hostOf 提取主机名`() {
        assertEquals("example.com", UrlUtils.hostOf("https://example.com/a/b?c=1"))
        // 无法解析时退回原串，不应抛异常
        assertEquals("not a url", UrlUtils.hostOf("not a url"))
    }

    @Test
    fun `isHome 识别主页`() {
        assertTrue(UrlUtils.isHome(""))
        assertTrue(UrlUtils.isHome(UrlUtils.HOME_URL))
        assertFalse(UrlUtils.isHome("https://example.com"))
    }
}
