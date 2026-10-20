package com.ling.browser.web

import android.webkit.WebView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 长按命中结果的翻译规则。
 *
 * 直接测 `hitTargetOf(type, extra)` 而不是造一个 `WebView.HitTestResult`：
 * 那个对象只能由 WebView 现场创建，JVM 单测里没有 WebView 可依附。
 */
@Suppress("DEPRECATION") // 与被测代码同理：老命中类型仍要覆盖
class HitTargetTest {

    private val anchor = WebView.HitTestResult.ANCHOR_TYPE
    private val image = WebView.HitTestResult.IMAGE_TYPE
    private val imageAnchor = WebView.HitTestResult.IMAGE_ANCHOR_TYPE
    private val srcAnchor = WebView.HitTestResult.SRC_ANCHOR_TYPE
    private val srcImageAnchor = WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
    private val editText = WebView.HitTestResult.EDIT_TEXT_TYPE

    @Test
    fun `链接命中只带链接地址`() {
        val t = hitTargetOf(anchor, "https://example.org/a")!!
        assertEquals(HitTarget.Kind.LINK, t.kind)
        assertEquals("https://example.org/a", t.url)
        assertTrue(t.hasLink)
        assertFalse("链接目标不该被当成图片", t.hasImage)
    }

    @Test
    fun `链接里套媒体也按链接处理`() {
        // SRC_ANCHOR_TYPE 的 extra 是外层 <a> 的地址
        val t = hitTargetOf(srcAnchor, "https://example.org/page")!!
        assertEquals(HitTarget.Kind.LINK, t.kind)
        assertEquals("https://example.org/page", t.url)
    }

    @Test
    fun `图片命中只带图片地址`() {
        val t = hitTargetOf(image, "https://cdn.example.org/a.png")!!
        assertEquals(HitTarget.Kind.IMAGE, t.kind)
        assertNull("图片命中不该有链接", t.url)
        assertEquals("https://cdn.example.org/a.png", t.imageUrl)
        assertTrue(t.hasImage)
        assertFalse(t.hasLink)
    }

    @Test
    fun `图片套链接时仅有图片地址`() {
        // <a><img></a>：Chromium 只稳定给图上的地址，菜单的链接项据此自然缺席
        val t = hitTargetOf(imageAnchor, "https://cdn.example.org/a.png")!!
        assertEquals(HitTarget.Kind.LINK_IMAGE, t.kind)
        assertTrue(t.hasImage)
        assertFalse(t.hasLink)
    }

    @Test
    fun `媒体套链接按图片加链接处理`() {
        // SRC_IMAGE_ANCHOR_TYPE：视频/图片外面套着 <a>，extra 是外层链接上的图
        val t = hitTargetOf(srcImageAnchor, "https://cdn.example.org/a.png")!!
        assertEquals(HitTarget.Kind.LINK_IMAGE, t.kind)
        assertEquals("https://cdn.example.org/a.png", t.imageUrl)
    }

    @Test
    fun `输入框和正文交回 WebView`() {
        // 长按在这里是文字选择，抢走事件会让复制/粘贴失效
        assertNull(hitTargetOf(editText, "一段文字"))
    }

    @Test
    fun `未知类型不处理`() {
        assertNull(hitTargetOf(WebView.HitTestResult.UNKNOWN_TYPE, "x"))
        assertNull(hitTargetOf(WebView.HitTestResult.PHONE_TYPE, "13800000000"))
        assertNull(hitTargetOf(WebView.HitTestResult.EMAIL_TYPE, "a@b.c"))
    }

    @Test
    fun `地址为空时不弹菜单`() {
        assertNull(hitTargetOf(anchor, null))
        assertNull(hitTargetOf(anchor, ""))
        assertNull(hitTargetOf(anchor, "   "))
        assertNull(hitTargetOf(image, null))
    }
}
