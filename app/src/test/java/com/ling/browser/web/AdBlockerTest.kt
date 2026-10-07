package com.ling.browser.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AdBlocker 的单元测试。
 *
 * 全部走纯字符串，不碰 WebView —— 这正是 AdBlocker 抽出来的意义：
 * 拦截规则（域名/路径）是最容易在真机上"看似正常、实则漏拦或误拦"的部分，
 * 却最难肉眼验证。这里把边界一次覆盖清楚。
 */
class AdBlockerTest {

    // --------------------------------------------- 主文档保护

    @Test
    fun `主文档永不拦截`() {
        // 用户点开一个广告落地页，主文档必须放行，
        // 否则整页白屏且地址栏还显示正常地址，用户会以为浏览器坏了。
        for (u in listOf(
            "https://ads.example.com/landing",
            "https://doubleclick.net/page",
            "https://www.example.com/ads/landing-page",
        )) {
            assertFalse("主文档不应拦截：$u", AdBlocker.shouldBlock(u, isMainFrame = true))
        }
    }

    @Test
    fun `空值与非法输入不拦`() {
        assertFalse(AdBlocker.shouldBlock(null, isMainFrame = false))
        assertFalse(AdBlocker.shouldBlock("", isMainFrame = false))
        assertFalse(AdBlocker.shouldBlock("   ", isMainFrame = false))
    }

    @Test
    fun `非 http 协议不拦`() {
        // data:/blob:/file: 是内联资源，不该被域名规则碰
        for (u in listOf(
            "data:image/png;base64,xxx",
            "blob:https://example.com/uuid",
            "file:///sdcard/a.html",
        )) {
            assertFalse("非 http(s) 不应拦截：$u", AdBlocker.shouldBlock(u, isMainFrame = false))
        }
    }

    // --------------------------------------------- 域名命中

    @Test
    fun `精确域名命中拦截`() {
        for (u in listOf(
            "https://doubleclick.net/pixel.gif",
            "https://www.googlesyndication.com/ads.js",
            "https://cnzz.com/track.js",
        )) {
            assertTrue("应拦截：$u", AdBlocker.shouldBlock(u, isMainFrame = false))
        }
    }

    @Test
    fun `任意深度子域名都被兜住`() {
        // host 逐级缩短查表：a.b.doubleclick.net -> b.doubleclick.net -> doubleclick.net
        for (u in listOf(
            "https://a.b.doubleclick.net/x",
            "https://static.adservice.google.com/pixel",
            "https://deep.sub.umeng.com/log",
        )) {
            assertTrue("子域名应被兜住：$u", AdBlocker.shouldBlock(u, isMainFrame = false))
        }
    }

    @Test
    fun `正常域名不拦`() {
        for (u in listOf(
            "https://www.example.com/",
            "https://github.com/user/repo",
            "https://www.zhihu.com/question/1",
            "https://news.ycombinator.com/item?id=1",
        )) {
            assertFalse("正常域名不应拦：$u", AdBlocker.shouldBlock(u, isMainFrame = false))
        }
    }

    @Test
    fun `大小写与端口不影响判定`() {
        assertTrue(AdBlocker.shouldBlock("HTTPS://DOUBLECLICK.NET:443/x", isMainFrame = false))
        assertTrue(AdBlocker.shouldBlock("https://DoubleClick.Net/x", isMainFrame = false))
    }

    // --------------------------------------------- 路径命中

    @Test
    fun `路径关键字命中拦截`() {
        // 很多站点把广告放在自家域名的 /ads/ 目录，域名规则抓不到
        for (u in listOf(
            "https://www.example.com/ads/banner.js",
            "https://www.example.com/adserver/request",
            "https://news.example.com/pagead/imp",
        )) {
            assertTrue("路径应命中：$u", AdBlocker.shouldBlock(u, isMainFrame = false))
        }
    }

    @Test
    fun `路径关键字不误伤教学页`() {
        // /docs/ads-guide 不该拦：只匹配完整路径段，不碰普通单词里的 "ad"
        for (u in listOf(
            "https://www.example.com/docs/ads-guide",
            "https://www.example.com/readable/",
            "https://www.example.com/graduate/program",
        )) {
            assertFalse("不应误伤：$u", AdBlocker.shouldBlock(u, isMainFrame = false))
        }
    }

    // --------------------------------------------- host/path 解析

    @Test
    fun `hostOf 正确处理端口与 userinfo`() {
        assertEquals("doubleclick.net", AdBlocker.hostOf("https://doubleclick.net:8080/x"))
        assertEquals("example.com", AdBlocker.hostOf("https://user:pass@example.com/x"))
        assertEquals("example.com", AdBlocker.hostOf("https://EXAMPLE.COM/x"))
    }

    @Test
    fun `pathOf 只取路径不含 query`() {
        assertEquals("/ads/x", AdBlocker.pathOf("https://example.com/ads/x?ref=1"))
        assertEquals("", AdBlocker.pathOf("https://example.com"))
    }

    // --------------------------------------------- 自定义规则

    @Test
    fun `自定义域名规则命中并兜住子域名`() {
        val custom = setOf("ad.example.com")
        assertTrue(
            "自定义域名应命中：直接命中",
            AdBlocker.shouldBlock("https://ad.example.com/banner.js", false, custom),
        )
        assertTrue(
            "自定义域名应兜住子域名",
            AdBlocker.shouldBlock("https://static.ad.example.com/x", false, custom),
        )
        // 不相关的域名不受影响
        assertFalse(
            "无关域名不应误伤",
            AdBlocker.shouldBlock("https://example.com/x", false, custom),
        )
    }

    @Test
    fun `空自定义集合只走内置规则`() {
        // 与无参数调用行为一致
        assertTrue(AdBlocker.shouldBlock("https://doubleclick.net/x", false, emptySet()))
        assertFalse(AdBlocker.shouldBlock("https://normal.com/x", false, emptySet()))
    }

    @Test
    fun `主文档即使命中自定义规则也不拦`() {
        assertFalse(
            "主文档永不拦截，即便命中自定义规则",
            AdBlocker.shouldBlock("https://ad.example.com/landing", true, setOf("ad.example.com")),
        )
    }

    @Test
    fun `normalizeRule 清洗各种脏输入`() {
        assertEquals("ad.example.com", AdBlocker.normalizeRule("ad.example.com"))
        assertEquals("ad.example.com", AdBlocker.normalizeRule("  AD.Example.COM  "))
        assertEquals("ad.example.com", AdBlocker.normalizeRule("https://ad.example.com/path?x=1"))
        assertEquals("ad.example.com", AdBlocker.normalizeRule("https://ad.example.com:8080/"))
        assertEquals("ad.example.com", AdBlocker.normalizeRule("ad.example.com/path"))
    }

    @Test
    fun `normalizeRule 拒绝非法输入`() {
        assertNull("空串应拒绝", AdBlocker.normalizeRule(""))
        assertNull("纯空白应拒绝", AdBlocker.normalizeRule("   "))
        assertNull("无点单段应拒绝", AdBlocker.normalizeRule("localhost"))
        assertNull("非域名应拒绝", AdBlocker.normalizeRule("这不是域名"))
        assertNull("纯数字无点应拒绝", AdBlocker.normalizeRule("12345"))
    }
}
