package com.ling.browser.web

import com.ling.browser.util.UrlUtils
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 地址栏文案（非编辑态）的规则测试。
 *
 * 「加载完成后在地址栏显示网页标题」看起来只是一句文案切换，实际有三个必须
 * 处理好的退化场景，漏一个都会变成"看着像坏了"：
 *   1. **加载中**：`title` 还是上一个页面的（WebView 要等解析到 `<title>` 才更新），
 *      显示它就像"点了没反应"；
 *   2. **没有标题**：少数页面不给 `<title>`，得退回网址；
 *   3. **主页**：地址栏应当为空，方便直接输入。
 * 编辑态的取址（阅读视图要拿原文地址）在 BrowserViewModel 里，见 check_ux_fixes.py。
 */
class TabAddressBarTest {

    private fun barText(
        url: String,
        title: String = "",
        loading: Boolean = false,
    ): String = TabState(url = url, title = title, isLoading = loading).addressBarText

    @Test
    fun `加载完成后显示网页标题`() {
        assertEquals(
            "示例站 - 首页",
            barText("https://example.com", "示例站 - 首页"),
        )
    }

    @Test
    fun `加载中退回网址`() {
        // 关键：加载中 title 还是**上一个页面**的，显示标题会让人以为导航没生效
        assertEquals(
            "https://b.com/next",
            barText("https://b.com/next", "上一个页面", loading = true),
        )
    }

    @Test
    fun `没有标题时退回网址`() {
        assertEquals("https://example.com", barText("https://example.com", ""))
        assertEquals("https://example.com", barText("https://example.com", "   "))
    }

    @Test
    fun `标题两侧空白被去掉`() {
        assertEquals("示例站", barText("https://example.com", "  示例站  "))
    }

    @Test
    fun `主页地址栏为空`() {
        // 主页没有"网址"可显示，留空才能直接输入
        assertEquals("", barText(UrlUtils.HOME_URL, "主页"))
    }

    @Test
    fun `阅读视图显示文章标题而不是合成的 ling 地址`() {
        // ling://reader 是内部逻辑地址，露到地址栏上既不可读也不可提交
        assertEquals("文章标题", barText(ReaderPage.URL, "文章标题"))
    }
}
