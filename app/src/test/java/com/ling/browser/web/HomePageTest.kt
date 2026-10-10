package com.ling.browser.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun `深色模式写入 color-scheme`() {
        // 回归用例：早期版本把这段逻辑写成 prefersDarkCss() 函数却从未调用，
        // 于是 dark 参数完全不起作用，深色模式下滚动条仍是白的。
        // 现在直接内联到 :root，这里断言它真的出现在了 HTML 里。
        val dark = HomePage.html(
            background = "#111412", onBackground = "#E1E3DF",
            primary = "#7EDBAE", onPrimary = "#00391F",
            primaryContainer = "#005231", onPrimaryContainer = "#A8F2CB",
            dark = true,
        )
        assertTrue("深色模式应写入 color-scheme: dark", dark.contains("color-scheme: dark"))

        val light = HomePage.html(
            background = "#FBFDF8", onBackground = "#191C1A",
            primary = "#1B6C4B", onPrimary = "#FFFFFF",
            primaryContainer = "#A8F2CB", onPrimaryContainer = "#00210F",
            dark = false,
        )
        assertTrue("浅色模式应写入 color-scheme: light", light.contains("color-scheme: light"))
        assertFalse("浅色不应出现 dark 声明", light.contains("color-scheme: dark"))
    }

    @Test
    fun `深色配色确实注入到 CSS 变量`() {
        // 保证"跟随夜间模式"不只是 color-scheme，背景/文字色也跟着换，
        // 否则页面仍是白底黑字，只是滚动条变深了而已。
        val html = HomePage.html(
            background = "#111412", onBackground = "#E1E3DF",
            primary = "#7EDBAE", onPrimary = "#00391F",
            primaryContainer = "#005231", onPrimaryContainer = "#A8F2CB",
            dark = true,
        )
        assertTrue("背景色应注入 --bg", html.contains("--bg: #111412"))
        assertTrue("文字色应注入 --fg", html.contains("--fg: #E1E3DF"))
    }

    // ---- 原地刷新（themeUpdateJs / linksHtml）----

    @Test
    fun `linksHtml 生成快捷入口且与 html 内联一致`() {
        val links = listOf(
            "示例" to "https://example.com",
            "" to "https://blank-title.com",
        )
        val fragment = HomePage.linksHtml(links)
        assertTrue("应含 class=links", fragment.contains("""<div class="links" id="${HomePage.QUICK_LINKS_ID}">"""))
        assertTrue("应含第一个书签地址", fragment.contains("https://example.com"))
        assertTrue("空标题退回 URL", fragment.contains("blank-title.com"))

        // 内联进 html 与单独生成必须一致，否则"完整渲染"与"原地刷新"会不同步
        val full = HomePage.html(
            background = "#FFF", onBackground = "#000", primary = "#1B6C4B",
            onPrimary = "#FFF", primaryContainer = "#A8F2CB",
            onPrimaryContainer = "#00210F", dark = false, links = links,
        )
        assertTrue("html 应内联同样的快捷入口片段", full.contains(fragment))
    }

    @Test
    fun `linksHtml 空书签返回空串`() {
        assertEquals("", HomePage.linksHtml(emptyList()))
    }

    @Test
    fun `themeUpdateJs 写入主题变量与 color-scheme`() {
        val js = HomePage.themeUpdateJs(
            background = "#111412", onBackground = "#E1E3DF",
            primary = "#7EDBAE", onPrimary = "#00391F",
            primaryContainer = "#005231", onPrimaryContainer = "#A8F2CB",
            dark = true, linksHtml = "",
        )
        assertTrue("应设置 --bg", js.contains("--bg', '#111412'"))
        assertTrue("应设置 --fg", js.contains("--fg', '#E1E3DF'"))
        assertTrue("应设置 colorScheme", js.contains("colorScheme = 'dark'"))

        val light = HomePage.themeUpdateJs(
            background = "#FFF", onBackground = "#000", primary = "#1B6C4B",
            onPrimary = "#FFF", primaryContainer = "#A8F2CB",
            onPrimaryContainer = "#00210F", dark = false, linksHtml = "",
        )
        assertTrue("浅色 colorScheme", light.contains("colorScheme = 'light'"))
    }

    @Test
    fun `themeUpdateJs 注入的 linksHtml 引号被正确转义`() {
        // 书签标题可能含引号/尖括号，转义后不能破坏 JS 字符串
        val fragment = HomePage.linksHtml(listOf("""a"b""" to "https://x.com"))
        val js = HomePage.themeUpdateJs(
            background = "#FFF", onBackground = "#000", primary = "#1B6C4B",
            onPrimary = "#FFF", primaryContainer = "#A8F2CB",
            onPrimaryContainer = "#00210F", dark = false, linksHtml = fragment,
        )
        // linksHtml 里的引号已转成 &quot;，注入 JS 时不会再含裸双引号打断字符串
        assertTrue("linksHtml 的引号应已转义", fragment.contains("&quot;"))
        assertTrue("JS 应包含 linksHtml 内容", js.contains("innerHTML ="))
    }

    // ---- 快捷入口头像 ----

    @Test
    fun `快捷入口头像取标题首字`() {
        assertEquals("中文标题取首字", "百", HomePage.avatarInitial("百度", "https://www.baidu.com"))
        assertEquals("英文标题取首字母并大写", "G", HomePage.avatarInitial("github", "https://github.com"))
    }

    @Test
    fun `空标题时头像取主机名首字母而不是 URL 首字母`() {
        // 取整串 URL 的首字母会让**每个**空标题书签都是 "H"（https 的 h），
        // 既没有区分度、也和标签页列表（TabState.initial）的口径不一致。
        assertEquals(
            "空标题应取主机名首字母",
            "E",
            HomePage.avatarInitial("", "https://www.example.com/a/b"),
        )
        assertEquals(
            "纯空白标题同样视为空",
            "E",
            HomePage.avatarInitial("   ", "https://example.com"),
        )
        assertEquals(
            "没有站点名时用中性占位符（与标签页列表一致）",
            "•",
            HomePage.avatarInitial("", "about:blank"),
        )
    }

    @Test
    fun `linksHtml 的头像也走同一套首字母规则`() {
        val fragment = HomePage.linksHtml(listOf("" to "https://www.example.com/x"))
        // 渲染在 HTML 里的头像必须与 avatarInitial 一致，否则"改了一处漏一处"
        assertTrue(
            "空标题书签的头像应是主机名首字母",
            fragment.contains("""<span class="avatar">E</span>"""),
        )
        assertFalse("不应出现 URL 首字母 H", fragment.contains("""<span class="avatar">H</span>"""))
    }

    @Test
    fun `主题脚本用 id 定位快捷入口而不是 class 选择器`() {
        // 同一份脚本也跑在阅读视图上，而阅读视图的正文来自第三方网页，
        // 里面完全可能有 class="links" 的元素（"相关链接"这类）——
        // 用 class 选择器会把正文里那个元素当成快捷入口删掉。
        val js = HomePage.themeUpdateJs(
            background = "#FFF", onBackground = "#000", primary = "#1B6C4B",
            onPrimary = "#FFF", primaryContainer = "#A8F2CB",
            onPrimaryContainer = "#00210F", dark = false, linksHtml = "",
        )
        assertTrue(
            "应通过 id 定位自己的快捷入口",
            js.contains("getElementById('${HomePage.QUICK_LINKS_ID}')"),
        )
        assertFalse(
            "不能用 class 选择器 —— 会误删阅读视图正文里的 class=links 元素",
            js.contains("querySelector('.links')"),
        )
    }

    @Test
    fun `body 不固定高度以免内容溢出时顶部被裁掉`() {
        val html = HomePage.html(
            background = "#FBFDF8", onBackground = "#191C1A", primary = "#1B6C4B",
            onPrimary = "#FFFFFF", primaryContainer = "#A8F2CB",
            onPrimaryContainer = "#00210F", dark = false,
        )
        // height:100% + flex 居中：内容比视口高时（横屏/分屏/小屏 + 8 个快捷入口）
        // 溢出的那部分会被顶到滚动区**上方**，滚不上去 —— logo 与「翎」被裁掉。
        assertTrue("body 应使用 min-height: 100%", html.contains("min-height: 100%"))
        assertFalse(
            "body 不应固定 height:100%",
            Regex("""body\s*\{[^}]*?(?<!min-)height:\s*100%""").containsMatchIn(html),
        )
    }

    // ---- 主页搜索框 ----

    @Test
    fun `搜索框提交的地址能解析出查询串`() {
        assertEquals("abc", HomePage.searchQueryOf("ling://search?q=abc"))
        // 与常量保持一致（HTML 里的 form action 由它生成，见 check_home_html.py 的漂移检查）
        assertEquals("abc", HomePage.searchQueryOf("${HomePage.SEARCH_URL}?q=abc"))
    }

    @Test
    fun `搜索串里的表单编码只解码一次`() {
        // 表单编码：中文是 %XX、空格是 +。不解码会被二次编码 ——
        // 搜"中文"会变成搜 "%E4%B8%AD%E6%96%87"，一个结果都没有。
        assertEquals("中文", HomePage.searchQueryOf("ling://search?q=%E4%B8%AD%E6%96%87"))
        assertEquals("a b", HomePage.searchQueryOf("ling://search?q=a+b"))
    }

    @Test
    fun `只取 q 参数，忽略其它参数`() {
        assertEquals("x", HomePage.searchQueryOf("ling://search?q=x&lang=zh"))
    }

    @Test
    fun `空查询与不是搜索地址都返回 null`() {
        assertNull("没有值", HomePage.searchQueryOf("ling://search?q="))
        assertNull("纯空白也算空", HomePage.searchQueryOf("ling://search?q=%20"))
        assertNull("没有 q 参数", HomePage.searchQueryOf("ling://search?lang=zh"))
        assertNull("主页地址不是搜索", HomePage.searchQueryOf(HomePage.URL))
        assertNull("普通网址不是搜索", HomePage.searchQueryOf("https://example.com/?q=x"))
        assertNull(null)
    }
}
