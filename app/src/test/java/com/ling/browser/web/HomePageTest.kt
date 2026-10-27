package com.ling.browser.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内置主页的地址判定。
 *
 * 主页内容已改为原生 Compose 渲染（见 `ui/screens/HomeScreen.kt`），
 * 这里只剩「哪些地址算主页」这一条纯字符串规则。
 *
 * 这条规则曾被一个真实缺陷逼出来：早期版本把 `about:home` 直接交给 WebView，
 * 而 WebView 只认识 `about:blank`，于是 `onReceivedError` 被触发、主页白屏。
 */
class HomePageTest {

    @Test
    fun `主页地址被正确识别`() {
        assertTrue(HomePage.isHomeUrl(HomePage.URL))
        assertTrue("兼容旧快照里的 about:home", HomePage.isHomeUrl("about:home"))
        assertTrue("地址栏停在主页时是空的", HomePage.isHomeUrl(""))
        assertTrue("纯空白也算空", HomePage.isHomeUrl("   "))
        assertTrue(HomePage.isHomeUrl(null))
    }

    @Test
    fun `普通网址不会被误判为主页`() {
        assertFalse(HomePage.isHomeUrl("https://example.com"))
        assertFalse(HomePage.isHomeUrl("http://ling://home"))
        assertFalse(HomePage.isHomeUrl("https://www.baidu.com"))
        assertFalse("about:blank 是 WebView 的空白文档，不是主页", HomePage.isHomeUrl("about:blank"))
        assertFalse("大小写不同不算主页", HomePage.isHomeUrl("LING://HOME"))
        assertFalse("前缀相似但不是主页", HomePage.isHomeUrl("ling://homepage"))
    }

    @Test
    fun `主页 scheme 不与 about 冲突`() {
        // 关键：不能再用 about: 前缀，WebView 会尝试自己解析并失败
        assertFalse(
            "主页 URL 不应使用 about: 前缀",
            HomePage.URL.startsWith("about:"),
        )
        assertTrue(HomePage.URL.startsWith("ling://"))
    }

    @Test
    fun `主页地址与阅读视图地址不同`() {
        // 两者若相同，主页会被当成阅读视图，退出逻辑会错乱
        assertFalse(HomePage.URL == ReaderPage.URL)
    }
}
