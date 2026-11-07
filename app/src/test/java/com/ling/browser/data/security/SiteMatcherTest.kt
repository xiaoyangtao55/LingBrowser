package com.ling.browser.data.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 站点归并规则测试。
 *
 * 这些用例就是密码库的安全边界：一旦有人把 `topPrivateDomain()` 换成
 * "取最后两段"或 `endsWith`，下面的用例会立刻失败。
 */
class SiteMatcherTest {

    // ------------------------------------------------------- 正常归并

    @Test
    fun `子域归并到 eTLD+1`() {
        assertEquals(
            "example.com",
            SiteMatcher.siteFromUrl("https://www.example.com/login?next=/a"),
        )
        assertEquals(
            "example.com",
            SiteMatcher.siteFromUrl("https://login.example.com/"),
        )
    }

    @Test
    fun `多级公共后缀按 PSL 处理`() {
        // 直接取最后两段会得到 co.uk，让任意两个英国站点共享密码
        assertEquals(
            "example.co.uk",
            SiteMatcher.siteFromUrl("https://a.b.example.co.uk/path"),
        )
    }

    @Test
    fun `端口与末尾点被忽略`() {
        assertEquals(
            "example.com",
            SiteMatcher.siteFromUrl("https://example.com:8443/x"),
        )
        assertEquals(
            "example.com",
            SiteMatcher.siteFromUrl("https://www.example.com./x"),
        )
    }

    @Test
    fun `主机名统一小写`() {
        assertEquals(
            "example.com",
            SiteMatcher.siteFromUrl("https://WWW.Example.COM/"),
        )
    }

    // ------------------------------------------------------- 安全边界

    @Test
    fun `http 页面拒绝保存`() {
        assertNull(SiteMatcher.siteFromUrl("http://example.com/login"))
        assertNull(SiteMatcher.siteFromUrl("ftp://example.com/login"))
    }

    @Test
    fun `IP 地址不视为可保存站点`() {
        assertNull(SiteMatcher.siteFromUrl("https://127.0.0.1/login"))
        assertNull(SiteMatcher.siteFromUrl("https://192.168.1.10/"))
        assertNull(SiteMatcher.siteFromUrl("https://[::1]/"))
    }

    @Test
    fun `localhost 与无后缀主机不视为可保存站点`() {
        assertNull(SiteMatcher.siteFromUrl("https://localhost:8443/"))
        assertNull(SiteMatcher.siteFromUrl("https://intranet/"))
    }

    @Test
    fun `相似域名不会被误判为同一站点`() {
        assertEquals("evil-example.com", SiteMatcher.siteFromUrl("https://evil-example.com/"))
        // 反过来的伪装：真正的站点是 evil.com，不能命中 example.com
        assertEquals("evil.com", SiteMatcher.siteFromUrl("https://example.com.evil.com/"))
    }

    @Test
    fun `非法输入返回 null`() {
        assertNull(SiteMatcher.siteFromUrl(""))
        assertNull(SiteMatcher.siteFromUrl("not a url"))
    }

    // ------------------------------------------------------- 手动输入归一

    @Test
    fun `normalizeSite 接受裸域名`() {
        assertEquals("example.com", SiteMatcher.normalizeSite("example.com"))
        assertEquals("example.com", SiteMatcher.normalizeSite("  www.example.com  "))
    }

    @Test
    fun `normalizeSite 接受 HTTPS 完整地址`() {
        assertEquals(
            "example.com",
            SiteMatcher.normalizeSite("https://login.example.com/oauth"),
        )
    }

    @Test
    fun `normalizeSite 拒绝显式 HTTP`() {
        // 不偷偷升级成 https：用户必须明确密码属于哪个安全站点
        assertNull(SiteMatcher.normalizeSite("http://example.com"))
    }

    @Test
    fun `normalizeSite 对空输入返回 null`() {
        assertNull(SiteMatcher.normalizeSite(""))
        assertNull(SiteMatcher.normalizeSite("   "))
    }
}
