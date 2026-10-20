package com.ling.browser.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BlockRules 的单元测试。
 *
 * 订阅名单的解析是"看起来简单、实际最容易埋雷"的一段：各家格式混排、
 * 注释与元素规则夹在域名之间，任何一处判错都可能把一条**元素级规则**
 * 变成"封死整个站点"的域名规则，或者把 IP 当域名存进去。这里把能想到的
 * 脏输入一次盖全 —— 纯字符串，不碰 Android，本地 JVM 直接跑。
 */
class BlockRulesTest {

    // --------------------------------------------- 三种格式

    @Test
    fun `Hosts 格式只取第二个字段`() {
        // 0.0.0.0 / 127.0.0.1 只是 Hosts 的占位 IP，我们不做 DNS 劫持，
        // 真正要用的是第二个字段（域名），IP 必须被丢掉。
        val r = BlockRules.parse(
            "0.0.0.0 ad.doubleclick.net\n" +
                "127.0.0.1 mm.admob.com",
        )
        assertEquals(setOf("ad.doubleclick.net", "mm.admob.com"), r.domains)
        assertFalse("IP 不该被当成域名", "0.0.0.0" in r.domains)
        assertFalse("IP 不该被当成域名", "127.0.0.1" in r.domains)
        assertEquals(2, r.lines)
        assertEquals(0, r.skipped)
    }

    @Test
    fun `AdGuard domains-only 裸域名可解析`() {
        val r = BlockRules.parse("example.org\nexample.com")
        assertEquals(setOf("example.org", "example.com"), r.domains)
    }

    @Test
    fun `AdGuard 域名规则去掉双竖线与尖括号`() {
        val r = BlockRules.parse("||example.org^\n||ads.example.com^")
        assertEquals(setOf("example.org", "ads.example.com"), r.domains)
    }

    @Test
    fun `三种格式可混排`() {
        val r = BlockRules.parse(
            "# 说明注释\n" +
                "0.0.0.0 hosts.example.com\n" +
                "plain.example.com\n" +
                "||adguard.example.com^",
        )
        assertEquals(
            setOf("hosts.example.com", "plain.example.com", "adguard.example.com"),
            r.domains,
        )
    }

    // --------------------------------------------- 注释

    @Test
    fun `各种注释写法都不算跳过`() {
        // 注释是名单的常态，不该计入 skipped —— 否则"跳过多少行"这个
        // 数字会被注释灌水，用户看不出真正被丢掉的是哪一类规则。
        val r = BlockRules.parse(
            "# Hosts 注释\n" +
                "! ABP 注释\n" +
                "[Adblock Plus 2.0]\n" +
                "; 有些名单用分号\n" +
                "// 也有用双斜杠的\n" +
                "example.com",
        )
        assertEquals(setOf("example.com"), r.domains)
        assertEquals(6, r.lines)
        assertEquals(0, r.skipped)
    }

    @Test
    fun `BOM 不影响首行判断`() {
        // \uFEFF 不是空白字符，trim 动不了它；若不去掉，首行的 `#` 注释判断会失效，
        // 于是一行注释被当成"候选域名"，再被 normalize 拒掉后计入 skipped。
        val r = BlockRules.parse("\uFEFF# 注释\nexample.com")
        assertEquals(setOf("example.com"), r.domains)
        assertEquals(0, r.skipped)
    }

    @Test
    fun `行内注释与 ABP 修饰符被剥掉`() {
        // `$third-party` 这类修饰符对域名级规则没有意义，剥掉后仍是同一个域名。
        val a = BlockRules.parse("ad.example.com # 说明")
        assertEquals(setOf("ad.example.com"), a.domains)

        val b = BlockRules.parse("ad.example.com\$third-party")
        assertEquals(setOf("ad.example.com"), b.domains)
    }

    // --------------------------------------------- 有意跳过的写法

    @Test
    fun `元素级规则被跳过且绝不退化成域名`() {
        // ⚠️ 这是最危险的一条：`example.com##.ad` 若被 `#` 截断，
        // 会得到 `example.com` —— 一条"封死整个站点"的域名规则。
        val r = BlockRules.parse(
            "example.com##.ad-banner\n" +
                "shop.example.com#@#.promo\n" +
                "example.org#?#.sponsor",
        )
        assertTrue("元素规则不该产出任何域名", r.domains.isEmpty())
        assertEquals(3, r.skipped)
    }

    @Test
    fun `例外白名单被跳过`() {
        // @@||example.com^ 表示"放行"，需要的是另一套机制（且会与内置表打架），
        // 这里静默跳过而不是反过来理解成"拦截"。
        val r = BlockRules.parse("@@||example.com^")
        assertTrue(r.domains.isEmpty())
        assertEquals(1, r.skipped)
    }

    @Test
    fun `路径规则被跳过而不是截成域名`() {
        // 路径规则需要另一套引擎；截到域名等于"只拦某路径"变成"封死整个站点"。
        val r = BlockRules.parse("||example.com/ads/\n/ads/*\n/tracking/pixel.gif")
        assertTrue(r.domains.isEmpty())
        assertEquals(3, r.skipped)
    }

    @Test
    fun `只有 IP 没有域名的行被跳过`() {
        val r = BlockRules.parse("0.0.0.0\n127.0.0.1\n255.255.255.255")
        assertTrue(r.domains.isEmpty())
        assertEquals(3, r.skipped)
    }

    @Test
    fun `Unicode 与非法标签被拒`() {
        // WebView 给出的 host 是 punycode；收下 Unicode 域名只会得到一条永不命中的死规则。
        val r = BlockRules.parse("中文.example.com\n-bad.example.com\nexample-.com")
        assertTrue(r.domains.isEmpty())
        assertEquals(3, r.skipped)
    }

    // --------------------------------------------- 去重与上限

    @Test
    fun `重复域名去重但行数如实统计`() {
        val r = BlockRules.parse("example.com\nexample.com\nEXAMPLE.com")
        assertEquals(setOf("example.com"), r.domains)
        assertEquals(3, r.lines)
    }

    @Test
    fun `超过条数上限即截断并标记`() {
        val r = BlockRules.parse("a.com\nb.com\nc.com\nd.com", maxRules = 2)
        assertTrue(r.truncated)
        assertEquals(2, r.domains.size)
        // 被砍掉的两条要计入 skipped，否则 UI 报不出"丢了多少"。
        assertEquals(2, r.skipped)
    }

    // --------------------------------------------- 域名归一化

    @Test
    fun `normalizeDomain 归一化各种写法`() {
        assertEquals("example.com", BlockRules.normalizeDomain("*.example.com"))
        assertEquals("example.com", BlockRules.normalizeDomain(".example.com"))
        assertEquals("example.com", BlockRules.normalizeDomain("Example.COM"))
        assertEquals("example.com", BlockRules.normalizeDomain("  example.com  "))
        assertEquals("example.com", BlockRules.normalizeDomain("example.com."))
        assertEquals("example.com", BlockRules.normalizeDomain("example.com:8080"))
        assertEquals("ad.example.com", BlockRules.normalizeDomain("AD.Example.com:8080"))
    }

    @Test
    fun `normalizeDomain 拒绝非域名`() {
        assertNull(BlockRules.normalizeDomain(""))
        assertNull(BlockRules.normalizeDomain("   "))
        assertNull("纯数字与点（IPv4）应拒绝", BlockRules.normalizeDomain("127.0.0.1"))
        assertNull("无点的单段应拒绝", BlockRules.normalizeDomain("localhost"))
        assertNull("Unicode 应拒绝", BlockRules.normalizeDomain("中文.com"))
        assertNull("含空格应拒绝", BlockRules.normalizeDomain("exa mple.com"))
        assertNull("标签以短横线开头应拒绝", BlockRules.normalizeDomain("-bad.com"))
    }
}
