package com.ling.browser.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 嗅探结果解析测试。
 *
 * 重点是：数组解析（含逗号切分、括号配对）、字段转义、以及各种
 * `evaluateJavascript` 的返回形态（带引号/裸 JSON/null）。
 */
class SniffResultTest {

    // ---- 正常解析

    @Test
    fun `解析成功且资源类型正确`() {
        val raw = """
            {"status":"ok","resources":[
                {"type":"video","url":"https://a.com/v.mp4","title":"片名"},
                {"type":"audio","url":"https://a.com/a.mp3","title":""},
                {"type":"image","url":"https://a.com/i.jpg","title":"图"},
                {"type":"download","url":"https://a.com/f.zip","title":"包"}
            ]}
        """.trimIndent()
        val result = SniffResult.parse(raw) as SniffResult.Ok
        assertEquals(4, result.resources.size)
        assertEquals(SniffResult.Resource.Type.VIDEO, result.resources[0].type)
        assertEquals("https://a.com/v.mp4", result.resources[0].url)
        assertEquals("片名", result.resources[0].title)
        assertEquals(SniffResult.Resource.Type.DOWNLOAD, result.resources[3].type)
    }

    @Test
    fun `空 resources 数组是成功不是错误`() {
        val raw = """{"status":"ok","resources":[]}"""
        val result = SniffResult.parse(raw) as SniffResult.Ok
        assertTrue(result.resources.isEmpty())
    }

    @Test
    fun `evaluateJavascript 带引号包裹也能解析`() {
        val raw = "\"{\\\"status\\\":\\\"ok\\\",\\\"resources\\\":[{\\\"type\\\":\\\"video\\\",\\\"url\\\":\\\"https://a.com/v.mp4\\\",\\\"title\\\":\\\"\\\"}]}\""
        val result = SniffResult.parse(raw) as SniffResult.Ok
        assertEquals(1, result.resources.size)
        assertEquals("https://a.com/v.mp4", result.resources[0].url)
    }

    // ---- 容错

    @Test
    fun `null 返回错误`() {
        assertTrue(SniffResult.parse(null) is SniffResult.Error)
        assertTrue(SniffResult.parse("null") is SniffResult.Error)
        assertTrue(SniffResult.parse("") is SniffResult.Error)
    }

    @Test
    fun `非对象返回错误`() {
        assertTrue(SniffResult.parse("[1,2,3]") is SniffResult.Error)
    }

    @Test
    fun `status 非 ok 返回错误并带 reason`() {
        val raw = """{"status":"error","reason":"boom"}"""
        val result = SniffResult.parse(raw) as SniffResult.Error
        assertEquals("boom", result.reason)
    }

    @Test
    fun `缺少 resources 字段返回错误`() {
        assertTrue(SniffResult.parse("""{"status":"ok"}""") is SniffResult.Error)
    }

    @Test
    fun `坏条目被跳过而不毁掉整份结果`() {
        val raw = """
            {"status":"ok","resources":[
                {"type":"video","url":"https://a.com/v.mp4","title":"ok"},
                {"type":"weird","url":"https://a.com/x","title":"bad"},
                {"type":"image","url":"","title":"nourl"}
            ]}
        """.trimIndent()
        val result = SniffResult.parse(raw) as SniffResult.Ok
        assertEquals(1, result.resources.size)
        assertEquals("https://a.com/v.mp4", result.resources[0].url)
    }

    // ---- 字段转义

    @Test
    fun `title 里的转义引号正确还原`() {
        val raw = """{"status":"ok","resources":[{"type":"video","url":"https://a.com/v.mp4","title":"他说\"你好\""}]}"""
        val result = SniffResult.parse(raw) as SniffResult.Ok
        assertEquals("他说\"你好\"", result.resources[0].title)
    }

    // ---- arrayField 边界

    @Test
    fun `arrayField 括号配对切分`() {
        val json = """{"resources":[{"a":1},{"b":2}]}"""
        val items = SniffResult.arrayField(json, "resources")!!
        assertEquals(2, items.size)
        assertEquals("""{"a":1}""", items[0])
        assertEquals("""{"b":2}""", items[1])
    }

    @Test
    fun `arrayField 括号不配对返回 null`() {
        val json = """{"resources":[{"a":1}"""
        assertEquals(null, SniffResult.arrayField(json, "resources"))
    }

    @Test
    fun `field 取数字值`() {
        assertEquals("42", SniffResult.field("""{"n":42}""", "n"))
        assertEquals("true", SniffResult.field("""{"b":true}""", "b"))
    }
}
