package com.ling.browser.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内部页判定与地址归一化的单元测试。
 *
 * 守着两个真实故障：
 *  1. [InternalPage.isInternal] 若把 null / 空串也算作内部页，冷启动时
 *     `WebView.url` 还是 null，会被误判成"已经在主页上"而跳过渲染 → 主页空白；
 *  2. [InternalPage.logicalUrl] 缺失时，WebView 回调报回来的 baseUrl
 *     （`https://home.ling.invalid/`）会被原样写进 TabState，同一个页面在
 *     状态里出现两种 url 值，会话快照里还存下一个解析不了的假 https 地址。
 */
class InternalPageTest {

    @Test
    fun `主页与阅读视图都算内部页`() {
        assertTrue("主页逻辑地址", InternalPage.isInternal(HomePage.URL))
        assertTrue("主页 baseUrl（WebView 报回来的就是它）", InternalPage.isInternal(HomePage.BASE_URL))
        assertTrue("阅读视图逻辑地址", InternalPage.isInternal(ReaderPage.URL))
        assertTrue("阅读视图 baseUrl", InternalPage.isInternal(ReaderPage.BASE_URL))
    }

    @Test
    fun `空地址不是内部页`() {
        // 关键区别：HomePage.isHomeUrl 按**地址栏语义**把 null/空串也算主页，
        // 而"当前文档是不是内部页"必须区分"还没加载"与"已在主页上"。
        assertFalse("null 表示还没加载任何页面", InternalPage.isInternal(null))
        assertFalse("空串同理", InternalPage.isInternal(""))
        assertFalse("纯空白同理", InternalPage.isInternal("   "))
    }

    @Test
    fun `普通页面不是内部页`() {
        assertFalse(InternalPage.isInternal("https://example.com"))
        assertFalse("about:blank 是 WebView 的初始空白页", InternalPage.isInternal("about:blank"))
        assertFalse(
            "前缀相似的域名不能被误判",
            InternalPage.isInternal("https://home.ling.invalid.example.com/"),
        )
    }

    @Test
    fun `baseUrl 归一化回逻辑地址`() {
        assertEquals("主页 baseUrl", HomePage.URL, InternalPage.logicalUrl(HomePage.BASE_URL))
        assertEquals("主页逻辑地址本身", HomePage.URL, InternalPage.logicalUrl(HomePage.URL))
        assertEquals("阅读视图 baseUrl", ReaderPage.URL, InternalPage.logicalUrl(ReaderPage.BASE_URL))
    }

    @Test
    fun `普通地址原样返回`() {
        assertEquals(
            "普通页面不能被改写",
            "https://example.com/a?b=1",
            InternalPage.logicalUrl("https://example.com/a?b=1"),
        )
    }

    @Test
    fun `空地址归一化为 null 表示不要覆盖状态`() {
        assertNull("null", InternalPage.logicalUrl(null))
        assertNull("空串与纯空白", InternalPage.logicalUrl("  "))
    }
}
