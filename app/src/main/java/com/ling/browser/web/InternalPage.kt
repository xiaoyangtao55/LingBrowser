package com.ling.browser.web

/**
 * 「内部页」——由 `loadDataWithBaseURL` 原生渲染的自包含页面：内置主页与阅读视图。
 *
 * 抽成独立对象而不是在每处各写一遍 `HomePage.isHomeUrl(u) || ReaderPage.isReaderUrl(u)`：
 * 这个判定决定了「要不要对当前文档做网页层面的处理」，一旦某处漏掉半个条件，
 * 症状都是**静默失效**（颜色被反色、地址栏残留旧网址、把合成地址写进历史）。
 * 收敛到一处之后，新增内部页时只要改这里。
 *
 * 还有一层必须一起处理的现实：WebView 对 `loadDataWithBaseURL` 渲染的页面在
 * **reload**（地址栏刷新、渲染进程被系统回收后重建、历史前进/后退重放）时，
 * 回调与 `getUrl()` 报回来的不再是 baseUrl，而是
 * `data:text/html;charset=utf-8;base64,…`（见 [isDataReloadForm]）。
 * 只看地址串的话，刷新一次主页就"不再是内部页"了 —— 症状是原生背景层停画
 * 叠加夜间兜底反色 CSS 被注入，整页变成一片白。因此下面所有判定都接受一个
 * [logicalUrl]（当前标签的逻辑地址）作为"这串 data 地址究竟是主页还是阅读视图"的线索。
 */
internal object InternalPage {

    /**
     * WebView 对内部页 reload 后回写地址的前缀。
     *
     * 完整形式是 `data:text/html;charset=utf-8;base64,<整页 HTML>` ——
     * 后半段每页都不同且很长，前缀足以定位这层"重载形式"，不必再往下匹配。
     */
    const val DATA_HTML_PREFIX = "data:text/html"

    /** 该地址是否是内部页 reload 后回写的 `data:` 形式。 */
    fun isDataReloadForm(url: String?): Boolean =
        url?.trim()?.startsWith(DATA_HTML_PREFIX) == true

    /**
     * 该地址是否是**当前文档**形态的主页。
     *
     * 与 [HomePage.isHomeUrl] 有两处必须不同：
     *  1. 要求地址**非空** —— 后者按地址栏语义把 null/空串也算主页，
     *     而刚建出来、还没加载页面的 WebView `url` 正是 null；
     *  2. reload 后的 `data:` 形式按 [logicalUrl] 归一 —— 否则主页刷新一次就不被
     *     认作主页了：原生背景层停画、夜间兜底反色 CSS 命中，页面整块发白。
     */
    fun isHomeDocument(url: String?, logicalUrl: String? = null): Boolean {
        val u = url?.trim().orEmpty()
        if (u.isEmpty()) return false
        if (HomePage.isHomeUrl(u)) return true
        return isDataReloadForm(u) && isLogicalHome(logicalUrl)
    }

    /** 阅读视图版的 [isHomeDocument]。 */
    fun isReaderDocument(url: String?, logicalUrl: String? = null): Boolean {
        val u = url?.trim().orEmpty()
        if (u.isEmpty()) return false
        if (ReaderPage.isReaderUrl(u)) return true
        return isDataReloadForm(u) && isLogicalReader(logicalUrl)
    }

    /**
     * 该地址是否是内部页。
     *
     * ⚠️ 不能用 [HomePage.isHomeUrl] 单独充当这个判据：那个函数按**地址栏语义**
     * 把 null / 空串也算作主页（地址栏在主页上就该是空的），而"当前文档是不是
     * 内部页"必须要求地址非空 —— 刚建出来、还没加载任何页面的 WebView
     * `url` 正是 null，混用会把"还没加载"误判成"已经在主页上"，
     * 于是跳过真正的渲染，主页停在空白页。
     *
     * @param logicalUrl 当前标签的逻辑地址（TabState.url），用于把 reload 后的
     *   `data:` 形式归一到"具体是哪种内部页"。需要区分主页/阅读视图的调用方传它；
     *   不传时 `data:` 形式一律按"是内部页"处理（见下方说明）。
     */
    fun isInternal(url: String?, logicalUrl: String? = null): Boolean {
        val u = url?.trim().orEmpty()
        if (u.isEmpty()) return false
        if (isHomeDocument(u, logicalUrl) || isReaderDocument(u, logicalUrl)) return true
        // 不传 logicalUrl 的调用方只有 [LingWebView.injectDarkMode] 的夜间兜底判定，
        // 它只关心"要不要给当前文档套 invert 反色"。`data:` 形式必是本 App
        // loadDataWithBaseURL 渲染的内部页残留文档，这里按"是内部页"处理：
        // 误判成普通网页会把整页颜色反过来（正是白屏的成因之一），
        // 反向误判只是少暗化一个自包含页面，无害。
        return logicalUrl == null && isDataReloadForm(u)
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
     *
     * reload 后的 `data:` 形式同样按 [logicalUrl] 归一；拿不到线索时返回 null，
     * 由调用方保留原状态 —— 绝不能把那一长串 base64 地址写进 TabState。
     */
    fun logicalUrl(url: String?, logicalUrl: String? = null): String? {
        val u = url?.trim().orEmpty()
        if (u.isEmpty()) return null
        return when {
            HomePage.isHomeUrl(u) -> HomePage.URL
            ReaderPage.isReaderUrl(u) -> ReaderPage.URL
            isDataReloadForm(u) -> when {
                isLogicalHome(logicalUrl) -> HomePage.URL
                isLogicalReader(logicalUrl) -> ReaderPage.URL
                else -> null
            }
            else -> u
        }
    }

    /** [logicalUrl] 是否指向主页。必须排除空串 —— [HomePage.isHomeUrl] 把它当主页。 */
    private fun isLogicalHome(logicalUrl: String?): Boolean {
        val l = logicalUrl?.trim().orEmpty()
        return l.isNotEmpty() && HomePage.isHomeUrl(l)
    }

    /** [logicalUrl] 是否指向阅读视图。 */
    private fun isLogicalReader(logicalUrl: String?): Boolean {
        val l = logicalUrl?.trim().orEmpty()
        return l.isNotEmpty() && ReaderPage.isReaderUrl(l)
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
