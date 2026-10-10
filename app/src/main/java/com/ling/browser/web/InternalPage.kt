package com.ling.browser.web

/**
 * 「内部页」——由 `loadDataWithBaseURL` 原生渲染的自包含页面：内置主页与阅读视图。
 *
 * 抽成独立对象而不是在每处各写一遍 `HomePage.isHomeUrl(u) || ReaderPage.isReaderUrl(u)`：
 * 这个判定决定了「要不要对当前文档做网页层面的处理」，一旦某处漏掉半个条件，
 * 症状都是**静默失效**（颜色被反色、地址栏残留旧网址、把合成地址写进历史）。
 * 收敛到一处之后，新增内部页时只要改这里。
 */
internal object InternalPage {

    /**
     * 该地址是否是内部页。
     *
     * ⚠️ 不能用 [HomePage.isHomeUrl] 单独充当这个判据：那个函数按**地址栏语义**
     * 把 null / 空串也算作主页（地址栏在主页上就该是空的），而"当前文档是不是
     * 内部页"必须要求地址非空 —— 刚建出来、还没加载任何页面的 WebView
     * `url` 正是 null，混用会把"还没加载"误判成"已经在主页上"，
     * 于是跳过真正的渲染，主页停在空白页。
     */
    fun isInternal(url: String?): Boolean {
        val u = url?.trim().orEmpty()
        if (u.isEmpty()) return false
        return HomePage.isHomeUrl(u) || ReaderPage.isReaderUrl(u)
    }

    /**
     * 把 WebView 报回来的地址归一化成**逻辑地址**：
     * baseUrl（`https://home.ling.invalid/`）→ `ling://home`，
     * `https://reader.ling.invalid/` → `ling://reader`，普通地址原样返回，
     * 空地址返回 null 表示"还没有地址，别覆盖状态里的旧值"。
     *
     * 内部页的 `WebView.getUrl()` 是 baseUrl 而不是我们写进 TabState 的逻辑地址，
     * 原样写回去会让同一个页面在状态里出现两种 url 值（会话快照里还会存下
     * 一个永远解析不了的假 https 地址）。所有"把实时地址写回状态"的地方都要先过这里。
     */
    fun logicalUrl(url: String?): String? {
        val u = url?.trim().orEmpty()
        if (u.isEmpty()) return null
        return when {
            HomePage.isHomeUrl(u) -> HomePage.URL
            ReaderPage.isReaderUrl(u) -> ReaderPage.URL
            else -> u
        }
    }

    /**
     * 内部页"对外"的地址：收藏、分享、写进会话快照都用它。
     *
     *   - 主页：**没有**对外地址（返回 null）—— 收藏它、恢复它都没有意义；
     *   - 阅读视图：还原成**原文地址**。它的逻辑地址是合成的 `ling://reader`，
     *     存下来既打不开（WebView 不认识这个 scheme），也没法再渲染
     *     （正文缓存不落盘）；
     *   - 普通页面：就是自己。
     *
     * @param readerOriginalUrl 阅读视图对应的原文地址（由 WebTabManager 的
     *   正文缓存提供）。拿不到时返回 null —— 宁可"不能收藏"，也不要存下一个
     *   点不开的 `ling://reader`。
     */
    fun externalUrl(url: String?, readerOriginalUrl: String?): String? {
        val u = url?.trim().orEmpty()
        if (u.isEmpty() || HomePage.isHomeUrl(u)) return null
        if (ReaderPage.isReaderUrl(u)) return readerOriginalUrl?.trim()?.takeIf { it.isNotEmpty() }
        return u
    }
}
