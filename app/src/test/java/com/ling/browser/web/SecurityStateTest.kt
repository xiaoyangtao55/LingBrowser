package com.ling.browser.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 连接安全状态的纯逻辑回归。
 *
 * 只测能从 URL 协议直接算出的**基础态**（[SecurityState.baseForUrl]）——
 * 混合内容与证书错误来自 WebView 回调，构造 WebView 才能测，
 * 属于真机回归范畴，这里不覆盖。
 */
class SecurityStateTest {

    @Test
    fun `明文 http 判为不安全`() {
        assertEquals(SecurityState.INSECURE_HTTP, SecurityState.baseForUrl("http://example.com/a"))
        // 协议大小写不敏感：WebView 可能回传大写的 scheme
        assertEquals(SecurityState.INSECURE_HTTP, SecurityState.baseForUrl("HTTP://example.com"))
    }

    @Test
    fun `https 与内部页判为安全基础态`() {
        // https、内部主页/阅读视图、about:/data: 都不是"不安全"
        for (url in listOf(
            "https://example.com",
            "HTTPS://example.com",
            "ling://home",
            "ling://reader",
            "about:blank",
            "data:text/html;base64,AAA",
        )) {
            assertEquals("$url 应为安全基础态", SecurityState.SECURE, SecurityState.baseForUrl(url))
        }
    }

    @Test
    fun `只有安全态不算不安全`() {
        assertFalse(SecurityState.SECURE.isInsecure)
        assertTrue(SecurityState.INSECURE_HTTP.isInsecure)
        assertTrue(SecurityState.INSECURE_MIXED.isInsecure)
        assertTrue(SecurityState.INSECURE_CERT.isInsecure)
    }
}
