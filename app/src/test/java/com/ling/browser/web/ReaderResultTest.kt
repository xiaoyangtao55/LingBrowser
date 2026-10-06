package com.ling.browser.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阅读模式的结果解析与页面生成测试。
 *
 * 这两块都是**纯字符串逻辑**，不依赖 Android 框架，因此能在本地 JVM 上跑。
 * 之所以特意把它们从 WebView 里剥出来，就是为了让这里能测 ——
 * `evaluateJavascript` 的回调形态五花八门，出错又只在真机上才暴露。
 */
class ReaderResultTest {

    // ------------------------------------------------------- 正常解析

    @Test
    fun `解析成功的提取结果`() {
        val raw = """
            {"status":"ok","title":"标题","site":"示例站",
             "url":"https://example.com/a","textLength":1200,"html":"<p>正文</p>"}
        """.trimIndent()

        val r = ReaderResult.parse(raw)
        assertTrue("应解析为 Ok", r is ReaderResult.Ok)
        r as ReaderResult.Ok
        assertEquals("标题", r.title)
        assertEquals("示例站", r.site)
        assertEquals("https://example.com/a", r.url)
        assertEquals("<p>正文</p>", r.html)
        assertEquals(1200, r.textLength)
    }

    @Test
    fun `没有正文时返回 NoContent 而不是错误`() {
        // 「不适合阅读」是正常情况（首页、视频页），不该当成错误报给用户
        val r = ReaderResult.parse("""{"status":"no_content","reason":"not_readerable"}""")
        assertTrue("应是 NoContent", r is ReaderResult.NoContent)
        assertEquals("not_readerable", (r as ReaderResult.NoContent).reason)
    }

    @Test
    fun `脚本出错时返回 Error`() {
        val r = ReaderResult.parse("""{"status":"error","reason":"boom"}""")
        assertTrue("应是 Error", r is ReaderResult.Error)
        assertEquals("boom", (r as ReaderResult.Error).reason)
    }

    // --------------------------------------------- evaluateJavascript 的真实形态

    @Test
    fun `处理被引号包裹并转义的返回值`() {
        // evaluateJavascript 返回的是 JSON 编码的 JS 值：脚本 return 字符串时，
        // 回调收到的是**再套一层引号、内部转义**的形式。这是真机上最常见的
        // 形态，直接解析会失败。
        //
        // ⚠️ 构造这个输入时要小心：不能对已经是 JSON 的文本无脑
        // `replace("\"", "\\\"")` —— 那会把本来就正确的 `\"` 变成 `\\"`
        // （转义了一个反斜杠 + 一个裸引号），得到**非法 JSON**，
        // 测试就变成在验证错误的东西（踩过）。
        // 正确做法是让 JSON 真正编码一次。
        val inner = """{"status":"ok","title":"标\"题","html":"<p>a</p>","textLength":5}"""
        // 手工按 JSON 规则编码：反斜杠先转义，再转义引号
        val wrapped = "\"" + inner.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

        val r = ReaderResult.parse(wrapped)
        assertTrue("被引号包裹的返回值也应能解析，实际: $r", r is ReaderResult.Ok)
        r as ReaderResult.Ok
        assertEquals("标\"题", r.title)
        assertEquals("<p>a</p>", r.html)
    }

    @Test
    fun `HTML 里的换行与引号正确反转义`() {
        // 正文 HTML 必然含引号（属性）与换行，转义处理错了会得到破碎的 HTML
        val raw = """{"status":"ok","html":"<a href=\"x\">A</a>\n<p>B</p>","textLength":9}"""
        val r = ReaderResult.parse(raw)
        assertTrue(r is ReaderResult.Ok)
        assertEquals("<a href=\"x\">A</a>\n<p>B</p>", (r as ReaderResult.Ok).html)
    }

    @Test
    fun `反转义各种 JSON 转义序列`() {
        // \/ 是 JSON 允许的转义（等价于 /），但**反斜杠加斜杠**是两个字符。
        // 这里两者都覆盖，避免把 `\\/`（反斜杠+斜杠）误当成一个 `/`。
        val raw = """{"status":"ok","html":"斜杠:\/ 反斜杠+斜杠:\\/ 制表:\t 引号:\"","textLength":9}"""
        val r = ReaderResult.parse(raw)
        assertTrue(r is ReaderResult.Ok)
        assertEquals(
            "斜杠:/ 反斜杠+斜杠:\\/ 制表:\t 引号:\"",
            (r as ReaderResult.Ok).html,
        )
    }

    // ------------------------------------------------------- 异常输入

    @Test
    fun `null 与空串不会崩`() {
        // 页面没加载完、或用户在设置里关了 JavaScript —— 最常见的情况
        assertTrue(ReaderResult.parse(null) is ReaderResult.Error)
        assertTrue(ReaderResult.parse("") is ReaderResult.Error)
        assertTrue(ReaderResult.parse("   ") is ReaderResult.Error)
        assertTrue(ReaderResult.parse("null") is ReaderResult.Error)
    }

    @Test
    fun `非法 JSON 返回 Error 而不是抛异常`() {
        // 这里在 UI 路径上，抛异常就是用户点一下闪退
        assertTrue(ReaderResult.parse("这不是 JSON") is ReaderResult.Error)
        assertTrue(ReaderResult.parse("{坏掉的") is ReaderResult.Error)
    }

    @Test
    fun `status 为 ok 但正文为空时降级为 NoContent`() {
        // 只信状态码会渲染出"只有标题、没有正文"的空页面，那看起来像 bug。
        // 必须用正文内容本身再判一次。
        val r = ReaderResult.parse("""{"status":"ok","html":"","textLength":0}""")
        assertTrue("空正文不该是 Ok，实际: $r", r is ReaderResult.NoContent)
    }

    @Test
    fun `缺少字段时用安全默认值`() {
        val r = ReaderResult.parse("""{"status":"ok","html":"<p>x</p>"}""")
        assertTrue(r is ReaderResult.Ok)
        r as ReaderResult.Ok
        assertEquals("", r.title)
        assertEquals("", r.site)
        assertEquals(0, r.textLength)
    }

    // ------------------------------------------------------- 字段解析

    @Test
    fun `field 能取出数字与布尔值的裸值`() {
        assertEquals("42", ReaderResult.field("""{"a":42}""", "a"))
        assertEquals("true", ReaderResult.field("""{"a":true}""", "a"))
    }

    @Test
    fun `field 取不存在的键返回 null`() {
        assertEquals(null, ReaderResult.field("""{"a":1}""", "b"))
    }

    @Test
    fun `field 不把值里的同名子串误当成键`() {
        // "textLength" 与 "length" 这类前缀重叠很容易踩坑
        val json = """{"textLength":7,"length":9}"""
        assertEquals("7", ReaderResult.field(json, "textLength"))
        assertEquals("9", ReaderResult.field(json, "length"))
    }

    // ------------------------------------------------------- 地址判定

    @Test
    fun `识别阅读视图地址`() {
        assertTrue(ReaderPage.isReaderUrl(ReaderPage.URL))
        assertTrue(ReaderPage.isReaderUrl(ReaderPage.BASE_URL))
        assertFalse(ReaderPage.isReaderUrl("https://example.com"))
        assertFalse(ReaderPage.isReaderUrl(null))
        assertFalse(ReaderPage.isReaderUrl(""))
    }

    @Test
    fun `阅读视图地址与主页地址不同`() {
        // 两者若相同，主页会被当成阅读视图，退出逻辑会错乱
        assertFalse(ReaderPage.URL == HomePage.URL)
        assertFalse(ReaderPage.BASE_URL == HomePage.BASE_URL)
        assertFalse(ReaderPage.isReaderUrl(HomePage.URL))
        assertFalse(HomePage.isHomeUrl(ReaderPage.URL))
    }
}
