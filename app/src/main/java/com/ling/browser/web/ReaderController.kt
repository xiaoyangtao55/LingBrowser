package com.ling.browser.web

/**
 * 阅读模式的宿主依赖。
 *
 * 只列出阅读模式真正需要的几样事实：当前标签、WebView 表、正文缓存、
 * 设置里的 JS 开关与字号、内部页配色，以及三样副作用（改标签状态、
 * 导航、回主页）。其余一概不交给它。
 */
internal interface ReaderHost {
    val activeId: String
    val activeTab: TabState?

    fun webView(tabId: String): LingWebView?
    fun tab(tabId: String): TabState?
    fun session(tabId: String): TabSession
    fun findSession(tabId: String): TabSession?

    val javaScriptEnabled: Boolean
    val readerFontSize: ReaderPage.FontSize
    val internalColors: WebTabManager.InternalColors

    fun updateTab(tabId: String, transform: (TabState) -> TabState)
    fun loadUrl(tabId: String, url: String)
    fun loadHome(tabId: String)
}

/**
 * 阅读模式：正文提取、渲染、字号、退出，以及阅读视图的配色刷新脚本。
 *
 * 从 [WebTabManager] 拆出的功能簇。正文缓存仍挂在 [TabSession] 上，
 * 因此"关标签 = 一次丢弃"的生命周期不变量没有被拆散（见 `recycleTab`）。
 */
internal class ReaderController(private val host: ReaderHost) {

    /** 内部页配色。由宿主持有并对外暴露，这里只读。 */
    private val internalColors: WebTabManager.InternalColors
        get() = host.internalColors

    /** 当前标签的逻辑地址（[TabState.url]），见 [InternalPage]。 */
    private fun logicalUrlOf(id: String): String? = host.tab(id)?.url

    /**
     * 进入阅读模式。
     *
     * 流程：在当前页面里跑提取脚本 → 解析结果 → 用 [ReaderPage] 重新渲染。
     *
     * 三个必须处理的现实情况：
     *  1. **用户关掉了 JavaScript** —— 提取脚本根本不会执行，
     *     `evaluateJavascript` 回调拿到 null。此时明确告诉用户原因，
     *     而不是笼统说"不支持阅读模式"。
     *  2. **页面不适合阅读**（首页/视频页/图片站）—— 这是正常情况，
     *     提示要温和，不要像报错。
     *  3. **主页与阅读视图自身** —— 不能对它们再提取，否则会递归。
     *
     * @param onResult 结果回调，供上层弹提示。成功时不调用（页面已经变了）。
     */
    fun enterReaderMode(onResult: (ReaderResult) -> Unit = {}) {
        val id = host.activeId
        val wv = host.webView(id) ?: return
        val tab = host.tab(id) ?: return

        // 主页/阅读视图自身不能提取，否则会递归。判据用 [InternalPage.isInternal]：
        // 它连 reload 后的 data: 形式也认得出（只看 wv.url 会把刷新过的主页当成普通网页）。
        if (InternalPage.isInternal(wv.url, logicalUrlOf(id))) {
            onResult(ReaderResult.NoContent("already_reader"))
            return
        }
        if (!host.javaScriptEnabled) {
            // 阅读模式依赖注入脚本提取正文，没有 JS 就无法工作。
            // 这条要和其他失败原因分开，因为用户自己能解决（去设置里打开）。
            onResult(ReaderResult.Error("js_disabled"))
            return
        }
        // 页面还没加载完就提取，会拿半成品 DOM：正文可能只有一半，
        // canonical / og:url 往往还没解析到（于是原文地址退回 location.href，
        // 又回到自定义协议的老问题）。这种情况让用户稍等再点，
        // 比渲染出一个残缺页面（或直接失败）体验更好。
        //
        // 判据用 TabState.isLoading 与 wv.progress 双保险：
        // isLoading 在部分站点可能因为跳转时序提前变 false，
        // 而 progress 是 WebView 自己报的、更贴近真实渲染进度。
        if (tab.isLoading || wv.progress < 100) {
            onResult(ReaderResult.Error("page_loading"))
            return
        }

        wv.evaluateJavascript(ReaderExtractorJs.SCRIPT) { raw ->
            when (val result = ReaderResult.parse(raw)) {
                is ReaderResult.Ok -> {
                    // 提取是异步的，回来时用户可能已经切走或又加载了新页面。
                    // 不做这个校验就会把 A 页面的正文渲染到 B 页面上。
                    if (host.activeId != id) return@evaluateJavascript
                    host.session(id).readerContent = result
                    renderReader(id, result)
                }
                else -> onResult(result)
            }
        }
    }

    /**
     * 用阅读视图替换当前页面。
     *
     * 与主页一样用 loadDataWithBaseURL：自包含、不联网、主题色由原生注入。
     */
    private fun renderReader(id: String, result: ReaderResult.Ok) {
        val wv = host.webView(id) ?: return
        host.updateTab(id) {
            it.copy(
                url = ReaderPage.URL,
                title = result.title.ifBlank { it.title },
                errorText = null,
                isLoading = false,
                progress = 100,
            )
        }
        // 阅读视图有自己烘好的主题底色，恢复 WebView 不透明底即可（与旧版一致）。
        // 同时摘掉"主页占位文档"标记（见 LingWebView.isHomePlaceholderDocument）。
        wv.setOpaqueBackground(true)
        wv.isHomePlaceholderDocument = false
        wv.loadDataWithBaseURL(
            ReaderPage.BASE_URL,
            readerHtml(result),
            "text/html",
            "utf-8",
            null,
        )
    }

    /** 按当前主题与字号生成阅读视图 HTML。 */
    private fun readerHtml(
        result: ReaderResult.Ok,
        fontSize: ReaderPage.FontSize = host.readerFontSize,
    ): String = ReaderPage.html(
        title = result.title,
        site = result.site,
        url = result.url,
        content = result.html,
        background = internalColors.background,
        onBackground = internalColors.onBackground,
        primary = internalColors.primary,
        primaryContainer = internalColors.primaryContainer,
        onPrimaryContainer = internalColors.onPrimaryContainer,
        dark = internalColors.dark,
        fontPx = fontSize.px,
    )

    /**
     * 原地调整阅读视图的正文大小。
     *
     * 为什么不再重渲染：阅读视图的排版以 `:root` 上的 `--font-size` 为唯一来源，
     * 直接改这个变量就能重排（见 [ReaderPage.fontSizeJs]）。而重渲染的代价是实打实的 ——
     * `loadDataWithBaseURL` 每次调用都会往历史里压入一个条目（阅读视图和主页同理），
     * 于是反复调字号会让返回键在"同一篇文章的不同字号"之间来回；重渲染还会把
     * **滚动位置拉回顶部** —— 用户读到一半调字号，位置全丢。
     *
     * [fontSize] 显式传入而不是读 `settings.readerFontSize`：设置是异步落盘的，
     * 调用方刚 `setReaderFontSize` 完就重绘时，`settings` 里很可能还是旧值 ——
     * 表现为"改了字号没反应，要再点一次才生效"。
     */
    fun refreshReaderFontSize(fontSize: ReaderPage.FontSize) {
        val id = host.activeId
        val wv = host.webView(id) ?: return
        // 不在阅读视图（例如刚切走）：什么都不用做，下次进入阅读模式会用新字号渲染。
        // 用 isReaderDocument 而不是 ReaderPage.isReaderUrl(wv.url)：阅读视图被
        // reload（刷新/进程重建）后 wv.url 是 data: 形式，只看 baseUrl 会漏判。
        if (!InternalPage.isReaderDocument(wv.url, logicalUrlOf(id))) return
        wv.evaluateJavascript(ReaderPage.fontSizeJs(fontSize.px), null)
    }

    /** 退出阅读模式：回到原文。 */
    fun exitReaderMode() {
        val id = host.activeId
        val ses = host.findSession(id) ?: return
        val cached = ses.readerContent ?: return
        ses.readerContent = null
        // 原文地址来自提取脚本（优先 canonical），正常情况下是 http(s)。
        // 万一它仍是个自定义协议，loadUrl 会拒绝加载，用户就**卡在阅读视图
        // 出不去**了 —— 所以这里退回网页历史：back 能回到进入阅读模式前的
        // 那个真实页面。再不行就只能留在原地，至少不白屏。
        if (UrlScheme.isNavigable(cached.url)) {
            host.loadUrl(id, cached.url)
        } else {
            val wv = host.webView(id)
            if (wv != null && wv.canGoBack()) {
                wv.goBack()
            } else {
                // 没有可回退的历史：退回主页，避免把用户困在一个
                // 已经"退出"了却还显示着的阅读页面上。
                host.loadHome(id)
            }
        }
    }

    /** 当前标签是否处于阅读视图。 */
    fun isReaderActive(): Boolean = ReaderPage.isReaderUrl(host.activeTab?.url)

    /**
     * 阅读视图对应的**原文地址**（不在阅读模式时为 null）。
     *
     * 地址栏需要它：阅读视图的逻辑地址是合成的 `ling://reader`，
     * 既不能复制也没法改，拿去提交还会让 WebView 报 ERR_UNKNOWN_URL_SCHEME；
     * 进入编辑态时应该给出能用的真实地址（来源就是提取时缓存下来的 canonical）。
     */
    fun readerOriginalUrl(id: String = host.activeId): String? =
        host.findSession(id)?.readerContent?.url

    /** 阅读视图原地刷新的 JS，见 [ReaderPage.themeUpdateJs]。 */
    fun themeJs(): String = ReaderPage.themeUpdateJs(
        background = internalColors.background,
        onBackground = internalColors.onBackground,
        primary = internalColors.primary,
        onPrimary = internalColors.onPrimary,
        primaryContainer = internalColors.primaryContainer,
        onPrimaryContainer = internalColors.onPrimaryContainer,
        dark = internalColors.dark,
    )
}
