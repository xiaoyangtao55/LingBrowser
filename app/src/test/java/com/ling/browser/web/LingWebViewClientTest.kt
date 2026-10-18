package com.ling.browser.web

import android.net.http.SslError
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 证书错误原因的映射回归。
 *
 * 只覆盖纯映射 [certErrorReason] —— 真正的握手失败要靠真机上的坏证书站点才能触发，
 * 属于真机回归。
 */
class LingWebViewClientTest {

    @Test
    fun `常见证书错误映射到具体原因`() {
        assertEquals("证书尚未生效", certErrorReason(SslError.SSL_NOTYETVALID))
        assertEquals("证书已过期", certErrorReason(SslError.SSL_EXPIRED))
        assertEquals("证书与域名不匹配", certErrorReason(SslError.SSL_IDMISMATCH))
        assertEquals("证书不受信任", certErrorReason(SslError.SSL_UNTRUSTED))
        assertEquals("证书日期无效", certErrorReason(SslError.SSL_DATE_INVALID))
    }

    @Test
    fun `未知错误码兜底为证书无效`() {
        // SSL_INVALID 与任意未知码都必须落到兜底分支，不能把原因显示成空白
        assertEquals("证书无效", certErrorReason(SslError.SSL_INVALID))
        assertEquals("证书无效", certErrorReason(999))
    }
}
