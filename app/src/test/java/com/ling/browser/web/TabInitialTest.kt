package com.ling.browser.web

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 标签缩略图的**占位字母**逻辑测试。
 *
 * 没有 favicon 时显示站点首字母，看起来是个纯装饰的小功能，
 * 但有两个容易忽略的实际问题：
 *   1. 绝大多数站点是 www.xxx.com，取首字母会全是 "w"，毫无区分度；
 *   2. 主页 ling://home 没有主机名，不能崩也不能显示空。
 * 这里把这两条约束固定下来。
 */
class TabInitialTest {

    private fun initialOf(url: String): String = TabState(url = url).initial

    @Test
    fun `跳过 www 前缀`() {
        // 这是本功能存在的意义：否则满屏都是 W
        assertEquals("B", initialOf("https://www.bing.com/search?q=a"))
        assertEquals("G", initialOf("https://www.github.com"))
        assertEquals("Z", initialOf("https://www.zhihu.com"))
    }

    @Test
    fun `没有 www 时取主机首字母`() {
        assertEquals("B", initialOf("https://bing.com"))
        assertEquals("M", initialOf("https://m.bilibili.com/video"))
    }

    @Test
    fun `首字母大写`() {
        assertEquals("B", initialOf("https://bing.com"))
        assertEquals("X", initialOf("https://xiaoyangtao55.github.io"))
    }

    @Test
    fun `国际化域名取首字符`() {
        assertEquals("E", initialOf("https://example.com"))
        // Unicode 主机名（punycode 形式）取其首字符
        assertEquals("X", initialOf("https://xn--fiqs8s.example"))
    }

    @Test
    fun `主页退回到固定占位符`() {
        // 内置主页没有主机名，绝不能显示空字符串或崩溃。
        // 注意不能用 hostOf：它对 ling://home 会退回原串，得到误导性的 "L"。
        assertEquals(TabState.NEUTRAL_INITIAL, initialOf(com.ling.browser.util.UrlUtils.HOME_URL))
    }

    @Test
    fun `无法解析的地址退回占位符`() {
        // hostOf 在解析失败时会退回原始 URL 字符串，
        // 直接用会得到 "A"（about:blank）这种无意义的字母。
        assertEquals(TabState.NEUTRAL_INITIAL, initialOf("about:blank"))
        assertEquals(TabState.NEUTRAL_INITIAL, initialOf(""))
        assertEquals(TabState.NEUTRAL_INITIAL, initialOf("ling://home"))
        assertEquals(TabState.NEUTRAL_INITIAL, initialOf("这不是地址"))
    }
}
