package com.ling.browser.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 地址协议判定测试。
 *
 * 对应一个真机报错：
 *   `位于 zhihu://answers/2090101772673085808?... 的网页无法加载，
 *    因为 net::ERR_UNKNOWN_URL_SCHEME`
 *
 * 知乎用自定义协议做内部跳转，那个地址先被记进了标签状态，
 * 后来退出阅读模式时又被回填给 WebView —— 而 WebView 不认识
 * `zhihu://`，于是白屏。这条判定就是拦住它的防线。
 */
class UrlSchemeTest {

    // ------------------------------------------------------- 可加载

    @Test
    fun `http 与 https 可加载`() {
        assertTrue(UrlScheme.isNavigable("https://www.zhihu.com/question/1/answer/2"))
        assertTrue(UrlScheme.isNavigable("http://example.com"))
        assertTrue(UrlScheme.isNavigable("https://example.com:8443/a?b=1#c"))
    }

    @Test
    fun `内部逻辑地址可加载`() {
        assertTrue(UrlScheme.isNavigable(HomePage.URL))
        assertTrue(UrlScheme.isNavigable(ReaderPage.URL))
    }

    @Test
    fun `浏览上下文协议可加载`() {
        // 这些 WebView 自己能处理，属于 handleUri 里放行的那批
        for (u in listOf(
            "about:blank",
            "data:text/html,<p>x</p>",
            "blob:https://a.com/1234",
            "file:///sdcard/a.html",
        )) {
            assertTrue("$u 应可加载", UrlScheme.isNavigable(u))
        }
    }

    // ------------------------------------------------------- 不可加载

    @Test
    fun `App 自定义协议不可加载`() {
        // 交给 WebView 必然白屏，只应由系统应用接管
        for (u in listOf(
            "zhihu://answers/2090101772673085808?mcid=c58f0c59-da4d-48f8-bc01-aaa9e9896fb1&callback_source=search_main",
            "weixin://dl/business/?t=abc",
            "alipays://platformapi/startapp?appId=1",
            "taobao://item.taobao.com/item.htm?id=1",
            "sinaweibo://detail?mblogid=1",
        )) {
            assertFalse("$u 不该交给 WebView", UrlScheme.isNavigable(u))
        }
    }

    @Test
    fun `非网页协议不可加载`() {
        for (u in listOf("tel:10086", "mailto:a@b.com", "sms:10086", "geo:39.9,116.4")) {
            assertFalse("$u 不该交给 WebView", UrlScheme.isNavigable(u))
        }
    }

    @Test
    fun `intent 协议不可加载`() {
        // intent:// 需要系统解析，WebView 直接加载会报错
        assertFalse(UrlScheme.isNavigable("intent://x#Intent;scheme=abc;end"))
    }

    // ------------------------------------------------------- 边界

    @Test
    fun `协议大小写不影响判定`() {
        // 地址栏与 Uri.parse 都保留原始大小写，必须归一化后再比较
        assertTrue(UrlScheme.isNavigable("HTTPS://EXAMPLE.COM"))
        assertTrue(UrlScheme.isNavigable("Http://example.com"))
        assertFalse(UrlScheme.isNavigable("ZHIHU://answers/1"))
    }

    @Test
    fun `无 scheme 的地址不可加载`() {
        // 让 WebView 去猜是危险的，这类输入应由调用方先补全成搜索或 https
        assertFalse(UrlScheme.isNavigable("example.com"))
        assertFalse(UrlScheme.isNavigable("www.zhihu.com/a/1"))
        assertFalse(UrlScheme.isNavigable("//example.com"))
        assertFalse(UrlScheme.isNavigable("://example.com"))
    }

    @Test
    fun `空串与空白不会崩且不可加载`() {
        // 这个判定在 UI 路径上，抛异常就是点一下闪退
        for (u in listOf("", "   ", "\t", "\n")) {
            assertFalse("空白输入应判为不可加载：'$u'", UrlScheme.isNavigable(u))
        }
    }

    @Test
    fun `畸形地址不会崩且不可加载`() {
        for (u in listOf("这不是地址", "http//缺少冒号", "1http://x", "-a://x", "中文协议://x")) {
            assertFalse("$u 应判为不可加载", UrlScheme.isNavigable(u))
        }
    }

    @Test
    fun `scheme 里允许加号点与横线`() {
        // RFC 3986 允许这些字符出现在 scheme 中
        assertFalse(UrlScheme.isNavigable("a+b://x"))
        assertFalse(UrlScheme.isNavigable("a.b://x"))
        assertFalse(UrlScheme.isNavigable("a-b://x"))
        // 它们不是已知可加载协议，因此仍应判 false —— 关键是**不崩**
        // 且不被误当成合法网页协议
    }

    @Test
    fun `前导空白被忽略`() {
        // 从网页里抓来的地址可能带空白
        assertTrue(UrlScheme.isNavigable("  https://example.com  "))
    }

    @Test
    fun `允许集合与 WebViewClient 放行的协议一致`() {
        // 两边不一致会出现"放行了却加载不了"（白屏）或
        // "能加载却被交给系统"（无故切出去）。这里把 handleUri
        // 里显式放行的协议逐一确认。
        for (scheme in listOf("http", "https", "file", "about", "data", "blob", "ling")) {
            assertTrue(
                "$scheme 被 WebViewClient 放行，但 isNavigable 不认",
                UrlScheme.isNavigable("$scheme://x"),
            )
        }
    }
}
