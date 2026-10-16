package com.ling.browser.web

/**
 * 「内部页」——由 `loadDataWithBaseURL` 原生渲染的自包含页面：内置主页与阅读视图。
 *
 * 这个判定决定了「要不要对当前文档做网页层面的处理」，散在各处时任何一处漏判
 * 都是**静默失效**（颜色被反色、地址栏残留旧网址、把合成地址写进历史）。
 * 所以把"某地址属于哪类内部页"收敛成**唯一入口** [typeOf]，其余
 * [isInternal] / [isHomeDocument] / [isReaderDocument] / [logicalUrl] 都从它派生 ——
 * 地址形态与线索匹配只写一次，新增内部页时只动 [typeOf]。
 *
 * 还有一层必须一起处理的现实：WebView 对 `loadDataWithBaseURL` 渲染的页面在
 * **reload**（地址栏刷新、渲染进程被系统回收后重建、历史前进/后退重放）时，
 * 回调与 `getUrl()` 报回来的不再是 baseUrl，而是
 * `data:text/html;charset=utf-8;base64,…`（见 [isDataReloadForm]）。这时地址串本身
 * 分不出主页还是阅读视图，必须借用当前标签的逻辑地址 [logicalUrl] 当线索。
 * 只看地址串的话，刷新一次主页就"不再是内部页"了 —— 症状是原生背景层停画、
 * 叠加夜间兜底反色 CSS 被注入，整页变成一片白。
 *
 * "是不是主页"在本 App 里有三种语义，用途不同、别混用：
 *  - **地址栏语义**（[HomePage.isHomeUrl]）：null / 空串也算主页 —— 地址栏停在主页上
 *    本就该是空的。只该用于地址栏与"入口地址"判断。
 *  - **文档语义**（[isHomeDocument] / [isInternal]）：要求地址**非空**、且覆盖 reload 的
 *    `data:` 形式 —— 判断"当前文档是不是内部页"。刚建出来、还没加载任何页面的
 *    WebView `url` 正是 null，混用会把"还没加载"误判成"已在主页上"，跳过真正的渲染。
 *  - **逻辑语义**（[logicalUrl] 的入参 / [typeOfLogical]）：只看 TabState 里的逻辑地址，
 *    不涉及 `data:` 形式。
 */
internal object InternalPage {

    /**
     * 内部页类型。新增内部页时，这里与 [typeOf] 各加一处，其余函数与调用点不动。
     */
    enum class Type { HOME, READER }

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
     * **唯一判据入口**：判断 [url] 属于哪类内部页；null 表示不是内部页（或无法判定）。
     *
     * 地址的 4 种形态都在这里一次处理，调用方不必感知：
     *  - 逻辑地址 `ling://home` / `ling://reader`；
     *  - baseUrl `https://home.ling.invalid/` / `https://reader.ling.invalid/`
     *    （`getUrl()` 报回来的就是它）；
     *  - reload 后回写的 `data:text/html;base64,…`（须靠 [logicalUrl] 线索区分）；
     *  - 遗留快照里的 `about:home`（[HomePage.isHomeUrl] 一并认）。
     *
     * @param logicalUrl 当前标签的逻辑地址（TabState.url）。仅 `data:` 形态需要它来
     *   区分主页 / 阅读视图 —— 拿不到线索时返回 null，交由调用方保留原状态。
     */
    fun typeOf(url: String?, logicalUrl: String? = null): Type? {
        val u = url?.trim().orEmpty()
        if (u.isEmpty()) return null
        if (HomePage.isHomeUrl(u)) return Type.HOME
        if (ReaderPage.isReaderUrl(u)) return Type.READER
        if (isDataReloadForm(u)) return typeOfLogical(logicalUrl)
        return null
    }

    /**
     * 该地址是否是**当前文档**形态的主页（要求地址非空、覆盖 reload 的 `data:` 形式）。
     *
     * 与 [HomePage.isHomeUrl] 的地址栏语义不同，见文件头说明。
     */
    fun isHomeDocument(url: String?, logicalUrl: String? = null): Boolean =
        typeOf(url, logicalUrl) == Type.HOME

    /** 阅读视图版的 [isHomeDocument]。 */
    fun isReaderDocument(url: String?, logicalUrl: String? = null): Boolean =
        typeOf(url, logicalUrl) == Type.READER

    /**
     * 该地址是否是内部页。
     *
     * ⚠️ 不能用 [HomePage.isHomeUrl] 单独充当这个判据：那个函数按**地址栏语义**
     * 把 null / 空串也算作主页，而"当前文档是不是内部页"必须要求地址非空。
     *
     * @param logicalUrl 当前标签的逻辑地址（TabState.url），用于把 reload 后的
     *   `data:` 形式归一到"具体是哪种内部页"。需要区分主页 / 阅读视图的调用方传它；
     *   不传时 `data:` 形式一律按"是内部页"处理（见下）。
     */
    fun isInternal(url: String?, logicalUrl: String? = null): Boolean {
        if (typeOf(url, logicalUrl) != null) return true
        // 不传 logicalUrl 的调用方只有 [LingWebView.injectDarkMode] 的夜间兜底判定，
        // 它只关心"要不要给当前文档套 invert 反色"。`data:` 形式必是本 App
        // loadDataWithBaseURL 渲染的内部页残留文档，这里按"是内部页"处理：
        // 误判成普通网页会把整页颜色反过来（正是白屏的成因之一），
        // 反向误判只是少暗化一个自包含页面，无害。
        return logicalUrl == null && isDataReloadForm(url)
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
        return when (typeOf(u, logicalUrl)) {
            Type.HOME -> HomePage.URL
            Type.READER -> ReaderPage.URL
            // 剩下的 `data:` 形态拿不到线索：返回 null，交由调用方保留原状态。
            null -> if (isDataReloadForm(u)) null else u
        }
    }

    /**
     * [logicalUrl] 线索指向哪类内部页。
     *
     * 必须排除空串 —— [HomePage.isHomeUrl] 会把空串也当主页，而线索为空意味着
     * "状态里还没有地址"，此时不能反推出任何内部页类型。
     */
    private fun typeOfLogical(logicalUrl: String?): Type? {
        val l = logicalUrl?.trim().orEmpty()
        if (l.isEmpty()) return null
        if (HomePage.isHomeUrl(l)) return Type.HOME
        if (ReaderPage.isReaderUrl(l)) return Type.READER
        return null
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
