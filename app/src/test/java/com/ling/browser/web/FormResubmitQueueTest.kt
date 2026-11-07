package com.ling.browser.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 表单重提交的排队规则。
 *
 * 不变量：两个开关必须**发且只发一个**，否则 WebView 的加载会永远悬着。
 * 用 lambda 代替 `android.os.Message` 后，这些规则可以在纯 JVM 上验证。
 */
class FormResubmitQueueTest {

    private class Recorder {
        var resend = 0
        var dontResend = 0
        fun onResend() { resend++ }
        fun onDontResend() { dontResend++ }
        val total get() = resend + dontResend
    }

    private fun queueWithPrompt(sink: MutableList<FormResubmitPrompt?>) =
        FormResubmitQueue().apply { onPrompt = { sink.add(it) } }

    @Test
    fun `接入 UI 后按 FIFO 逐条弹出`() {
        val prompts = mutableListOf<FormResubmitPrompt?>()
        val q = queueWithPrompt(prompts)
        val a = Recorder()
        val b = Recorder()

        q.handle("t1", "a.com", a::onResend, a::onDontResend)
        q.handle("t2", "b.com", b::onResend, b::onDontResend)

        // 只弹第一条，第二条在队列里等（重复回调同一个 prompt 是既有行为，
        // 对 Compose 的 mutableStateOf 是幂等的）
        assertEquals(FormResubmitPrompt("a.com"), prompts.first())
        assertEquals(setOf(FormResubmitPrompt("a.com")), prompts.toSet())
        assertEquals(0, a.total)
        assertEquals(0, b.total)

        q.resolve(resend = true)
        assertEquals(1, a.resend)
        assertEquals(0, a.dontResend)
        assertEquals(FormResubmitPrompt("b.com"), prompts.last())

        q.resolve(resend = false)
        assertEquals(0, b.resend)
        assertEquals(1, b.dontResend)
        // 全部处理完，最后一次回调是 null（关弹窗）
        assertNull(prompts.last())
    }

    @Test
    fun `UI 未接线时立即按不重新提交兑现`() {
        val q = FormResubmitQueue()
        val a = Recorder()
        q.handle("t1", "a.com", a::onResend, a::onDontResend)
        assertEquals(0, a.resend)
        assertEquals(1, a.dontResend)
    }

    @Test
    fun `超过上限的请求直接放弃重提交`() {
        val prompts = mutableListOf<FormResubmitPrompt?>()
        val q = queueWithPrompt(prompts)
        val extras = mutableListOf<Recorder>()

        // 1 条在展示 + 4 条排队，之后再来就应立即兑现
        repeat(5) { i ->
            val r = Recorder()
            extras += r
            q.handle("t$i", "h$i.com", r::onResend, r::onDontResend)
        }
        val rejected = Recorder()
        q.handle("t9", "h9.com", rejected::onResend, rejected::onDontResend)

        assertEquals(1, rejected.dontResend)
        assertEquals(0, rejected.resend)
        // 前面的都还在等裁决
        assertTrue(extras.all { it.total == 0 })
    }

    @Test
    fun `关标签只取消该标签的待裁决项`() {
        val prompts = mutableListOf<FormResubmitPrompt?>()
        val q = queueWithPrompt(prompts)
        val a = Recorder()
        val b = Recorder()

        q.handle("t1", "a.com", a::onResend, a::onDontResend)
        q.handle("t2", "b.com", b::onResend, b::onDontResend)

        q.cancelFor("t1")
        assertEquals(1, a.dontResend)
        assertEquals(0, a.resend)
        assertEquals(0, b.total)
        // 弹窗应切到第二条
        assertEquals(FormResubmitPrompt("b.com"), prompts.last())
    }

    @Test
    fun `销毁时把展示中与排队中的全部兑现`() {
        val prompts = mutableListOf<FormResubmitPrompt?>()
        val q = queueWithPrompt(prompts)
        val a = Recorder()
        val b = Recorder()

        q.handle("t1", "a.com", a::onResend, a::onDontResend)
        q.handle("t2", "b.com", b::onResend, b::onDontResend)

        q.cancelAll()
        assertEquals(1, a.dontResend)
        assertEquals(1, b.dontResend)
        assertEquals(0, a.resend + b.resend)
        assertNull(prompts.last())
    }
}
