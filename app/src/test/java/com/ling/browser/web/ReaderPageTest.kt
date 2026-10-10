package com.ling.browser.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阅读视图 HTML 生成测试。
 *
 * 与 [HomePageTest] 同一套路：断言生成物的结构不变量。
 * 这类测试的价值在于**锁住版式约束** —— 阅读视图的排版是手写 CSS，
 * 很容易在后续改动里丢掉某条关键规则（比如限制行宽），
 * 而那在手机上不明显、在大屏上才难读。
 */
class ReaderPageTest {

    private fun render(
        title: String = "标题",
        site: String = "示例站",
        url: String = "https://example.com/post/1",
        content: String = "<p>正文内容</p>",
        dark: Boolean = false,
        fontPx: Int = ReaderPage.FontSize.MEDIUM.px,
    ): String = ReaderPage.html(
        title = title,
        site = site,
        url = url,
        content = content,
        background = "#ffffff",
        onBackground = "#000000",
        primary = "#0066cc",
        primaryContainer = "#eeeeee",
        onPrimaryContainer = "#111111",
        dark = dark,
        fontPx = fontPx,
    )

    // ------------------------------------------------------- 基本结构

    @Test
    fun `是完整 HTML 文档`() {
        val html = render()
        assertTrue("应以 DOCTYPE 开头", html.startsWith("<!DOCTYPE html>"))
        assertTrue("应闭合 html", html.contains("</html>"))
        assertTrue("应声明 utf-8", html.contains("charset=\"utf-8\""))
        assertTrue("应有 viewport", html.contains("name=\"viewport\""))
    }

    @Test
    fun `不引用任何外部资源`() {
        // 与主页同理：完全自包含才能离线渲染、不泄漏阅读行为
        val html = render()
        assertFalse("不应有外链样式", html.contains("<link"))
        assertFalse("不应有外链脚本", html.contains("<script"))
    }

    // ------------------------------------------------------- 正文注入

    @Test
    fun `正文原样注入不转义`() {
        // 正文是**要当 HTML 渲染的**，转义了就变成一堆可见的标签文本
        val html = render(content = "<p>带 <b>标签</b> 的正文</p>")
        assertTrue("正文标签应保留", html.contains("<p>带 <b>标签</b> 的正文</p>"))
    }

    @Test
    fun `标题与站点名做转义`() {
        // 标题来自第三方网页，不转义就是 XSS —— 虽然内容也在同一页面，
        // 但注入的 <script> 能读到我们放进页面的任何数据
        val html = render(title = "<script>alert(1)</script>", site = "a&b")
        assertFalse("标题里的脚本不应成为可执行标签", html.contains("<script>alert(1)</script>"))
        assertTrue("应转义尖括号", html.contains("&lt;script&gt;"))
        assertTrue("应转义 &", html.contains("a&amp;b"))
    }

    @Test
    fun `原文链接做转义`() {
        val html = render(url = "https://x.com/?a=1&b=\"2\"")
        assertFalse("URL 里的裸引号会破坏 href 属性", html.contains("b=\"2\""))
    }

    // ------------------------------------------------------- 主题与字号

    @Test
    fun `深色模式写入 color-scheme`() {
        // 不写这行，深色模式下滚动条仍然是白的
        assertTrue(render(dark = true).contains("color-scheme: dark"))
        assertTrue(render(dark = false).contains("color-scheme: light"))
    }

    @Test
    fun `主题色通过 CSS 变量注入`() {
        val html = render()
        for (name in listOf("--bg", "--fg", "--primary", "--pc", "--on-pc")) {
            assertTrue("应定义变量 $name", html.contains("$name:"))
            assertTrue("应使用变量 $name", html.contains("var($name)"))
        }
    }

    @Test
    fun `字号写入 CSS 变量`() {
        val html = render(fontPx = 24)
        assertTrue("应定义字号变量", html.contains("--font-size: 24px"))
        assertTrue("正文应使用字号变量", html.contains("font-size: var(--font-size)"))
    }

    @Test
    fun `原地调字号只改 CSS 变量、不重导航`() {
        // 调字号若走"重渲染"，loadDataWithBaseURL 会压入历史条目（返回键在
        // 同一篇文章的不同字号之间来回），还会把滚动位置拉回顶部。
        val js = ReaderPage.fontSizeJs(21)
        assertTrue("应把新字号写到 :root", js.contains("setProperty('--font-size', '21px')"))
        assertFalse("不能触发导航", js.contains("location"))
        assertFalse("不能重写文档", js.contains("document.write"))
        assertFalse("不能重新载入", js.contains("reload"))
    }

    @Test
    fun `原地调字号用到的变量与 HTML 模板一致`() {
        // 两处变量名一旦漂移，调字号就会静默失效（页面毫无反应）
        val html = render(fontPx = 16)
        assertTrue("模板应定义 --font-size", html.contains("--font-size: 16px"))
        assertTrue(
            "脚本应改同一个变量",
            ReaderPage.fontSizeJs(16).contains("'--font-size'"),
        )
    }

    @Test
    fun `四档字号依次递增且都在合理区间`() {
        val sizes = ReaderPage.FontSize.entries.map { it.px }
        assertEquals("档位数量变了就要同步更新测试", 4, sizes.size)
        assertEquals("应严格递增", sizes.sorted(), sizes)
        assertTrue("最小字号不该低于 14px（太小不可读）", sizes.first() >= 14)
        assertTrue("最大字号不该超过 30px（太大一行放不下几个字）", sizes.last() <= 30)
    }

    @Test
    fun `每个字号档位都有中文标签`() {
        ReaderPage.FontSize.entries.forEach {
            assertTrue("档位 ${it.name} 缺标签", it.label.isNotBlank())
        }
    }

    // ------------------------------------------------------- 排版约束

    @Test
    fun `限制正文行宽`() {
        // 这是阅读视图存在的意义之一：满宽排版在大屏上会让眼睛换行找错行
        val html = render()
        assertTrue("正文容器应限制最大宽度", html.contains("max-width: 40em"))
    }

    @Test
    fun `行高足够大`() {
        // 中文正文行高低于 1.6 会明显拥挤
        val html = render()
        val m = Regex("line-height:\\s*([0-9.]+)").find(html)
        assertTrue("应设置行高", m != null)
        val lh = m!!.groupValues[1].toFloat()
        assertTrue("行高 $lh 对中文长文偏小", lh >= 1.6f)
    }

    @Test
    fun `适配刘海屏底部安全区`() {
        assertTrue(
            "底部内边距应包含 safe-area，否则内容会被手势条盖住",
            render().contains("env(safe-area-inset-bottom)"),
        )
    }

    @Test
    fun `图片与表格不会撑破屏宽`() {
        val html = render()
        assertTrue("图片应限宽", html.contains("max-width: 100%"))
        assertTrue("宽表格应可横向滚动", html.contains("overflow-x: auto"))
    }

    // ------------------------------------------------------- 元信息与降级

    @Test
    fun `标题为空时依次退回站点名与默认文案`() {
        assertTrue(render(title = "", site = "示例站").contains("<h1>示例站</h1>"))
        assertTrue(render(title = "", site = "").contains("<h1>阅读模式</h1>"))
    }

    @Test
    fun `站点名为空时不产生空的分隔点`() {
        // 直接拼 "·" 会在没站点名时留下一个孤零零的点
        val html = render(site = "")
        assertFalse("不应出现空的分隔点", html.contains("><span class=\"dot\">·</span><"))
    }

    @Test
    fun `给出返回原网页的入口`() {
        val html = render(url = "https://example.com/post/1")
        assertTrue("应提供原文链接", html.contains("view-source") || html.contains("查看原网页"))
        assertTrue("链接应指向原文", html.contains("https://example.com/post/1"))
    }

    @Test
    fun `畸形 URL 不会导致生成失败`() {
        // URI 解析失败要有兜底，否则阅读视图直接崩
        val html = render(url = "这不是网址")
        assertTrue(html.contains("</html>"))
    }

    // ------------------------------------------------------- 主题跟随

    @Test
    fun `深色与浅色产出不同的配色变量值`() {
        // 把主题色注入后必须真的体现在输出里，否则"跟随夜间模式"是假的
        val dark = ReaderPage.html(
            title = "t", site = "", url = "https://e.com", content = "<p>x</p>",
            background = "#121212", onBackground = "#e0e0e0",
            primary = "#90caf9",
            primaryContainer = "#00497d", onPrimaryContainer = "#d1e4ff",
            dark = true, fontPx = 18,
        )
        val light = render()
        assertTrue("深色应写入深色背景", dark.contains("--bg: #121212"))
        assertTrue("浅色应写入浅色背景", light.contains("--bg: #ffffff"))
        assertFalse("深色模板里不该残留浅色背景", dark.contains("--bg: #ffffff"))
        assertTrue("深色应标记 color-scheme", dark.contains("color-scheme: dark"))
        assertTrue("浅色应标记 color-scheme", light.contains("color-scheme: light"))
    }

    @Test
    fun `所有主题色都真的被使用而不只是定义`() {
        // 定义了却没用的变量是最隐蔽的失效方式：页面看起来"正常"，
        // 但换主题时它纹丝不动。
        val html = render()
        val defined = Regex("(--[a-z-]+):").findAll(html).map { it.groupValues[1] }.toSet()
        // --font-size 用 var() 引用，其余颜色变量也应被引用
        for (name in defined) {
            assertTrue("变量 $name 定义了却从未被使用", html.contains("var($name)"))
        }
        assertTrue("应至少定义了背景与前景", defined.containsAll(setOf("--bg", "--fg")))
    }
}
