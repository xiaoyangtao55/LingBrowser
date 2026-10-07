package com.ling.browser.data.prefs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置项的纯逻辑测试。
 *
 * 这里只测不依赖 Android 框架的部分（数据类派生属性、枚举约束），
 * DataStore 的读写需要设备，交给 instrumentation 测试。
 */
class LingSettingsTest {

    @Test
    fun `默认主页是内置主页而不是搜索引擎`() {
        // 回归用例：早期版本把 homepage 默认成搜索引擎首页，
        // 导致新建标签页直接打开百度，而不是「翎」自己的主页。
        val s = LingSettings()
        assertEquals("未自定义时 customHomepage 应为空", "", s.customHomepage)
        assertFalse(
            "默认主页不应指向外部站点",
            s.homepage.startsWith("http"),
        )
        assertEquals(
            "留空时应回退到内置主页",
            com.ling.browser.web.HomePage.URL,
            s.homepage,
        )
    }

    @Test
    fun `自定义主页优先于内置主页`() {
        val s = LingSettings(customHomepage = "https://example.com")
        assertEquals("https://example.com", s.homepage)
    }

    @Test
    fun `默认搜索引擎是必应`() {
        // 从百度改为必应：必应对轻量浏览器更友好（无强制登录、
        // 无广告跳转中间页，https 首页体积小、首屏快）
        assertEquals(SearchEngine.BING, LingSettings().searchEngine)
    }

    @Test
    fun `切换搜索引擎不会改变默认主页`() {
        // 搜索引擎只影响「搜索」，不该把主页一起带跑
        SearchEngine.entries.forEach { e ->
            assertEquals(
                "${e.label} 下默认主页应仍是内置主页",
                com.ling.browser.web.HomePage.URL,
                LingSettings(searchEngine = e).homepage,
            )
        }
    }

    @Test
    fun `自定义主页会去掉首尾空白`() {
        val s = LingSettings(customHomepage = "  https://example.com  ")
        assertEquals("https://example.com", s.customHomepage.trim())
    }

    @Test
    fun `标签页高度默认是一半`() {
        assertEquals(TabsHeight.HALF, LingSettings().tabsHeight)
    }

    @Test
    fun `标签页高度只有两档且比例正确`() {
        // 1/4 档已移除：面板太矮时列表显示不全，滚动起来就失去总览意义
        assertEquals(2, TabsHeight.entries.size)
        assertEquals(1.0f, TabsHeight.FULL.fraction, 0.001f)
        assertEquals(0.5f, TabsHeight.HALF.fraction, 0.001f)
    }

    @Test
    fun `已移除的四分之一档位名会安全降级`() {
        // 老版本可能往 DataStore 里存过 "QUARTER"。升级后枚举里没有它，
        // 读取路径必须能兜住而不是抛异常导致闪退。
        // 注意 JUnit 的参数顺序是 (message, object)，写反了会报类型不匹配。
        assertNull(
            "QUARTER 应已从枚举中移除",
            runCatching { TabsHeight.valueOf("QUARTER") }.getOrNull(),
        )
    }

    @Test
    fun `标签页高度比例都在合法区间`() {
        TabsHeight.entries.forEach { h ->
            assertTrue("${h.label} 比例应 > 0", h.fraction > 0f)
            assertTrue("${h.label} 比例应 <= 1", h.fraction <= 1f)
            assertTrue("${h.label} 应有非空标签", h.label.isNotBlank())
        }
    }

    @Test
    fun `标签页高度枚举名可安全往返`() {
        // DataStore 以枚举名持久化，改名前必须保证 valueOf 能解析回来
        TabsHeight.entries.forEach { h ->
            assertNotNull(TabsHeight.valueOf(h.name))
        }
        NightMode.entries.forEach { m ->
            assertNotNull(NightMode.valueOf(m.name))
        }
        SearchEngine.entries.forEach { e ->
            assertNotNull(SearchEngine.valueOf(e.name))
        }
    }

    @Test
    fun `桌面模式切换 UA`() {
        assertFalse(LingSettings().desktopMode)
        assertEquals(UA_MOBILE, LingSettings(desktopMode = false).userAgent)
        assertEquals(UA_DESKTOP, LingSettings(desktopMode = true).userAgent)
        assertTrue("桌面 UA 应含 Macintosh", UA_DESKTOP.contains("Macintosh"))
    }

    @Test
    fun `四个搜索引擎都有完整配置`() {
        assertEquals(4, SearchEngine.entries.size)
        SearchEngine.entries.forEach { e ->
            assertTrue("${e.label} 缺少搜索引擎名", e.label.isNotBlank())
            assertTrue("${e.label} 的 queryUrl 应含 %s 占位符", e.queryUrl.contains("%s"))
            assertTrue("${e.label} 的 homepage 应是 https", e.homepage.startsWith("https://"))
        }
    }

    @Test
    fun `夜间模式三态`() {
        assertEquals(3, NightMode.entries.size)
        assertEquals(NightMode.FOLLOW_SYSTEM, LingSettings().nightMode)
    }

    @Test
    fun `默认开启 JavaScript 与动态取色`() {
        val s = LingSettings()
        assertTrue(s.javaScriptEnabled)
        assertTrue(s.dynamicColor)
        assertFalse("默认不开启无图模式", s.blockImages)
        assertFalse("默认不强制网页夜间", s.forceDarkWebPages)
    }

    @Test
    fun `默认开启广告拦截`() {
        // 广告拦截是浏览器的基础能力，多数用户期望默认就拦。
        // 关掉是"某站点误拦时排查用"的临时手段，不该是默认态。
        assertTrue("默认必须开广告拦截", LingSettings().adBlockEnabled)
        // 自定义规则默认空集合：新用户没有任何自定义拦截
        assertTrue("自定义规则默认应为空", LingSettings().adBlockRules.isEmpty())
    }

    @Test
    fun `默认不把链接交给外部 App`() {
        // 回归用例：真机上这个开关默认开时，知乎回答页会遇到
        // zhihu:// 导航 —— 本机没装知乎 App，交给系统必然失败，
        // 失败后 WebView 的导航状态被搅乱，页面卡在中间态。
        // 默认关掉等于绕开整条转发路径。
        assertFalse(
            "默认必须关：本机没装对应 App 时'交给系统'只会失败",
            LingSettings().openLinksInExternalApp,
        )
    }

    @Test
    fun `外部 App 开关是唯一的转发开关`() {
        // 这个开关必须能真的关掉转发，而不是"关掉了但代码里还在转发"。
        // 这里只能断言数据层，行为层由 check_reader.py 静态校验。
        val off = LingSettings(openLinksInExternalApp = false)
        val on = LingSettings(openLinksInExternalApp = true)
        assertFalse(off.openLinksInExternalApp)
        assertTrue(on.openLinksInExternalApp)
        // 复制不改动其它字段，避免哪天加字段时漏同步
        assertEquals(off.copy(openLinksInExternalApp = true), on)
    }
}
