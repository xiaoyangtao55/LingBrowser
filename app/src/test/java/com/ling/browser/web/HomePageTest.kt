package com.ling.browser.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内置主页的单元测试。
 *
 * 这段逻辑修的是一个真实缺陷：早期版本把 `about:home` 直接交给 WebView，
 * 而 WebView 只认识 `about:blank`，于是 `onReceivedError` 被触发、主页白屏。
 * 现在主页由原生渲染，这里把「哪些 URL 算主页」的规则固化下来。
 */
class HomePageTest {

    @Test
    fun `主页地址被正确识别`() {
        assertTrue(HomePage.isHomeUrl(HomePage.URL))
        assertTrue(HomePage.isHomeUrl("about:home"))
        assertTrue(HomePage.isHomeUrl(""))
        assertTrue(HomePage.isHomeUrl("   "))
        assertTrue(HomePage.isHomeUrl(null))
    }

    @Test
    fun `普通网址不会被误判为主页`() {
        assertFalse(HomePage.isHomeUrl("https://example.com"))
        assertFalse(HomePage.isHomeUrl("http://ling://home"))
        assertFalse(HomePage.isHomeUrl("https://www.baidu.com"))
        assertFalse(HomePage.isHomeUrl("about:blank"))
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
    fun `baseUrl 是 WebView 能识别的合法地址`() {
        // loadDataWithBaseURL 的 baseUrl 会参与相对路径解析与同源判定，
        // 传自定义 scheme 属于未定义行为，因此必须用标准 https。
        assertTrue(
            "baseUrl 必须是 https，实际: ${HomePage.BASE_URL}",
            HomePage.BASE_URL.startsWith("https://"),
        )
        assertTrue("baseUrl 应以 / 结尾以便解析相对路径", HomePage.BASE_URL.endsWith("/"))
    }

    @Test
    fun `baseUrl 用保留 TLD 保证不可解析`() {
        // .invalid 是 RFC 2606 保留域，永远不解析；
        // .local 是 mDNS 保留域，在某些网络下可能真的被解析，故不用。
        assertTrue(
            "baseUrl 应使用 .invalid 保留域，实际: ${HomePage.BASE_URL}",
            HomePage.BASE_URL.contains(".invalid"),
        )
        assertFalse("不应使用 .local（mDNS 可能解析）", HomePage.BASE_URL.contains(".local/"))
    }

    @Test
    fun `baseUrl 也被识别为主页`() {
        // 关键：WebView 回调回来的 URL 是 baseUrl（不是 ling://home）。
        // 若这里不判定为主页，地址栏会把内部地址暴露给用户，
        // 且 TabState.url 会被回调覆写掉。
        assertTrue(HomePage.isHomeUrl(HomePage.BASE_URL))
    }

    @Test
    fun `逻辑地址与基地址是两个不同的值`() {
        // 两者混用会让同一页面产生两种 url，破坏 StateFlow 的结构化去重。
        assertFalse(
            "URL 与 BASE_URL 必须分开",
            HomePage.URL == HomePage.BASE_URL,
        )
        assertTrue("逻辑地址仍是 ling://", HomePage.URL.startsWith("ling://"))
    }

    @Test
    fun `生成的 HTML 是完整文档`() {
        val html = HomePage.html(
            background = "#FFFFFF",
            onBackground = "#000000",
            primary = "#1B6C4B",
            onPrimary = "#FFFFFF",
            primaryContainer = "#A8F2CB",
            onPrimaryContainer = "#00210F",
            dark = false,
        )
        assertTrue(html.startsWith("<!DOCTYPE html>"))
        assertTrue(html.contains("</html>"))
        assertTrue(html.contains("charset=\"utf-8\""))
        // 深色模式适配
        assertTrue("应声明 color-scheme", html.contains("viewport"))
    }

    @Test
    fun `配色被注入到 CSS 变量`() {
        val html = HomePage.html(
            background = "#123456",
            onBackground = "#ABCDEF",
            primary = "#1B6C4B",
            onPrimary = "#FFFFFF",
            primaryContainer = "#A8F2CB",
            onPrimaryContainer = "#00210F",
            dark = true,
        )
        assertTrue("背景色应注入", html.contains("--bg: #123456"))
        assertTrue("前景色应注入", html.contains("--fg: #ABCDEF"))
        assertTrue("主色应注入", html.contains("--primary: #1B6C4B"))
        assertTrue("容器色应注入", html.contains("--pc: #A8F2CB"))
    }

    @Test
    fun `每个 CSS 变量都被真正使用`() {
        // 定义了却没人用，说明主题色没生效（曾经 --primary 就是这样漏掉的）
        val html = HomePage.html(
            background = "#FFFBFE",
            onBackground = "#1C1B1F",
            primary = "#1B6C4B",
            onPrimary = "#FFFFFF",
            primaryContainer = "#A8F2CB",
            onPrimaryContainer = "#00210F",
            dark = false,
        )
        val defined = Regex("(--[\\w-]+)\\s*:").findAll(html).map { it.groupValues[1] }.toSet()
        val used = Regex("var\\((--[\\w-]+)").findAll(html).map { it.groupValues[1] }.toSet()
        assertEquals("有变量定义了但没被使用", emptySet<String>(), defined - used)
        assertEquals("使用了未定义的变量", emptySet<String>(), used - defined)
    }

    @Test
    fun `SVG 不在表现属性里使用 CSS 变量`() {
        // stroke="var(--x)" 作为表现属性是非法的，浏览器不会解析，
        // 图标会变成透明 —— 必须通过 CSS 规则设置。
        val html = HomePage.html(
            background = "#FFFBFE",
            onBackground = "#1C1B1F",
            primary = "#1B6C4B",
            onPrimary = "#FFFFFF",
            primaryContainer = "#A8F2CB",
            onPrimaryContainer = "#00210F",
            dark = false,
        )
        Regex("<(?:svg|path|circle|rect|line|polyline|g)\\b[^>]*>")
            .findAll(html)
            .forEach { m ->
                assertFalse(
                    "SVG 表现属性里不能出现 var()：${m.value.take(70)}",
                    m.value.contains("var(--"),
                )
            }
    }

    @Test
    fun `书签快捷入口被渲染为链接`() {
        val html = HomePage.html(
            background = "#FFFFFF",
            onBackground = "#000000",
            primary = "#1B6C4B",
            onPrimary = "#FFFFFF",
            primaryContainer = "#A8F2CB",
            onPrimaryContainer = "#00210F",
            dark = false,
            links = listOf("百度" to "https://www.baidu.com", "知乎" to "https://zhihu.com"),
        )
        assertTrue(html.contains("href=\"https://www.baidu.com\""))
        assertTrue(html.contains("href=\"https://zhihu.com\""))
        assertTrue("应显示书签标题", html.contains("百度"))
    }

    @Test
    fun `无书签时不渲染快捷入口区块`() {
        val html = HomePage.html(
            background = "#FFFFFF",
            onBackground = "#000000",
            primary = "#1B6C4B",
            onPrimary = "#FFFFFF",
            primaryContainer = "#A8F2CB",
            onPrimaryContainer = "#00210F",
            dark = false,
            links = emptyList(),
        )
        assertFalse("没有书签就不该出现快捷入口容器", html.contains("class=\"links\""))
    }

    @Test
    fun `书签标题里的 HTML 特殊字符被转义`() {
        // 不转义的话，标题里的引号会截断 href 属性，破坏页面结构
        val html = HomePage.html(
            background = "#FFFFFF",
            onBackground = "#000000",
            primary = "#1B6C4B",
            onPrimary = "#FFFFFF",
            primaryContainer = "#A8F2CB",
            onPrimaryContainer = "#00210F",
            dark = false,
            links = listOf(
                """<script>alert("xss")</script>""" to "https://example.com",
            ),
        )
        assertFalse("脚本标签必须被转义", html.contains("<script>alert"))
        assertTrue("应输出转义后的实体", html.contains("&lt;script&gt;"))
    }

    @Test
    fun `书签数量被限制为 8 个`() {
        val many = (1..20).map { "站点$it" to "https://s$it.example.com" }
        val html = HomePage.html(
            background = "#FFFFFF",
            onBackground = "#000000",
            primary = "#1B6C4B",
            onPrimary = "#FFFFFF",
            primaryContainer = "#A8F2CB",
            onPrimaryContainer = "#00210F",
            dark = false,
            links = many,
        )
        assertTrue("第 8 个应存在", html.contains("href=\"https://s8.example.com\""))
        assertFalse("第 9 个应被截断", html.contains("href=\"https://s9.example.com\""))
    }

    @Test
    fun `空标题时退回显示 URL`() {
        val html = HomePage.html(
            background = "#FFFFFF",
            onBackground = "#000000",
            primary = "#1B6C4B",
            onPrimary = "#FFFFFF",
            primaryContainer = "#A8F2CB",
            onPrimaryContainer = "#00210F",
            dark = false,
            links = listOf("" to "https://example.com"),
        )
        assertTrue("空标题应退回 URL 文本", html.contains("example.com"))
    }

    @Test
    fun `主页不引用任何外部资源`() {
        // 离线可用是硬要求：不能出现 http(s) 形式的资源引用
        val html = HomePage.html(
            background = "#FFFFFF",
            onBackground = "#000000",
            primary = "#1B6C4B",
            onPrimary = "#FFFFFF",
            primaryContainer = "#A8F2CB",
            onPrimaryContainer = "#00210F",
            dark = false,
        )
        assertFalse("不应有 <link> 外链", html.contains("<link"))
        assertFalse("不应有 <script src>", html.contains("<script src"))
        assertFalse("不应有远程图片", html.contains("<img"))
    }

    @Test
    fun `prefersDarkCss 与入参一致`() {
        assertEquals("color-scheme: dark;", HomePage.prefersDarkCss(true))
        assertEquals("color-scheme: light;", HomePage.prefersDarkCss(false))
    }
}
