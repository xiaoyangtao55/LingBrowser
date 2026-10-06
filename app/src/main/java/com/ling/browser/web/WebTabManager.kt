package com.ling.browser.web

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebStorage
import android.webkit.WebView
import android.widget.FrameLayout
import com.ling.browser.data.prefs.LingSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 标签页与 WebView 实例的管理者。
 *
 * 设计要点：WebView 的创建代价很高（每个实例约数 MB 原生内存），
 * 因此这里为每个标签页保留一个长期存活的 WebView，切换标签只是换
 * 挂载到容器里的那一个；超过上限时回收最久未使用的实例。
 */
@SuppressLint("SetJavaScriptEnabled")
class WebTabManager(private val context: Context) {

    private val _tabs = MutableStateFlow<List<TabState>>(emptyList())
    val tabs: StateFlow<List<TabState>> = _tabs.asStateFlow()

    private val _activeId = MutableStateFlow("")
    val activeId: StateFlow<String> = _activeId.asStateFlow()

    /** tabId -> WebView。仅在内存中存活，不参与状态序列化。 */
    private val webViews = LinkedHashMap<String, LingWebView>()

    /** 标签页切换时用于承载 WebView 的宿主容器。 */
    private var host: FrameLayout? = null

    private var settings: LingSettings = LingSettings()

    /** 需要 UI 层处理的副作用（打开外部应用、下载等）。 */
    var onExternalUri: ((Uri) -> Boolean)? = null

    /** 每次页面访问的回调，用于写历史。 */
    var onVisited: ((url: String, title: String, incognito: Boolean) -> Unit)? = null

    /** 下载请求回调（由 UI 层弹出确认或直接交给系统下载器）。 */
    var onDownloadRequested: ((url: String, mimeType: String?) -> Unit)? = null

    /** 当前标签页。 */
    val activeTab: TabState?
        get() = _tabs.value.firstOrNull { it.id == _activeId.value }

    fun attachHost(container: FrameLayout) {
        host = container
        // 把当前激活标签的 WebView 挂上去
        webViews[_activeId.value]?.let { wv ->
            (wv.parent as? FrameLayout)?.removeView(wv)
            container.addView(wv)
        }
    }

    fun detachHost(container: FrameLayout) {
        container.removeAllViews()
        if (host === container) host = null
    }

    /** 应用设置；已存在的 WebView 立即生效。 */
    fun applySettings(newSettings: LingSettings) {
        settings = newSettings
        webViews.forEach { (id, wv) ->
            val incognito = _tabs.value.firstOrNull { it.id == id }?.isIncognito ?: false
            wv.applySettings(newSettings, incognito)
        }
    }

    // ---------------------------------------------------------------- 标签页操作

    /**
     * 新建标签页并切换过去。
     *
     * 不传 URL 时落在主页：优先用户自定义的主页，否则是「翎」的内置主页。
     */
    fun newTab(url: String? = null, incognito: Boolean = settings.incognito): TabState {
        val target = url ?: settings.homepage
        val tab = TabState(url = target, isIncognito = incognito)
        _tabs.update { it + tab }
        switchTo(tab.id)
        loadUrl(tab.id, target)
        return tab
    }

    /** 关闭标签页；关掉最后一个时自动新建一个空白页。 */
    fun closeTab(id: String) {
        val remaining = _tabs.value.filterNot { it.id == id }
        webViews.remove(id)?.let { wv ->
            (wv.parent as? FrameLayout)?.removeView(wv)
            wv.stopLoading()
            wv.loadUrl("about:blank")
            wv.destroy()
        }

        if (remaining.isEmpty()) {
            _tabs.value = emptyList()
            newTab()
            return
        }

        _tabs.value = remaining
        if (_activeId.value == id) {
            switchTo(remaining.last().id)
        }
    }

    /** 关闭除 [keepId] 外的全部标签页。 */
    fun closeOthers(keepId: String) {
        _tabs.value.filter { it.id != keepId }.forEach { closeTab(it.id) }
    }

    /** 切换激活标签。 */
    fun switchTo(id: String) {
        if (_activeId.value == id && webViews[id] != null) return
        _activeId.value = id
        val wv = obtainWebView(id)
        host?.let { h ->
            h.removeAllViews()
            (wv.parent as? FrameLayout)?.removeView(wv)
            h.addView(wv)
        }
        syncNavState(id)
    }

    /** 交换两个标签页的位置（标签列表拖拽用）。 */
    fun moveTab(from: Int, to: Int) {
        _tabs.update { list ->
            if (from !in list.indices || to !in list.indices) return@update list
            list.toMutableList().apply { add(to, removeAt(from)) }
        }
    }

    // ---------------------------------------------------------------- 导航操作

    fun loadUrl(id: String, url: String) {
        val wv = obtainWebView(id)
        // 内置主页由原生渲染，绝不交给 WebView 解析协议 ——
        // 早期版本直接把 about:home 喂给 WebView，结果触发 onReceivedError 白屏。
        if (HomePage.isHomeUrl(url)) {
            loadHome(id, wv)
            return
        }
        updateTab(id) { it.copy(url = url, errorText = null, isLoading = true, progress = 0) }
        wv.loadUrl(url)
    }

    /** 渲染内置主页。 */
    private fun loadHome(id: String, wv: LingWebView) {
        // ⚠️ 这里必须写逻辑地址 HomePage.URL，不能写 HomePage.BASE_URL。
        // BASE_URL 只用于 loadDataWithBaseURL 的基地址；两者混用会让同一个页面
        // 在不同代码路径下产生两种 url 值，破坏 StateFlow 的结构化去重，
        // 进而可能让 refreshHome 反复重绘。
        updateTab(id) {
            it.copy(
                url = HomePage.URL,
                title = "主页",
                errorText = null,
                isLoading = false,
                progress = 100,
                canGoBack = false,
                canGoForward = false,
            )
        }
        wv.loadDataWithBaseURL(
            HomePage.BASE_URL,
            HomePage.html(
                background = homeColors.background,
                onBackground = homeColors.onBackground,
                primary = homeColors.primary,
                onPrimary = homeColors.onPrimary,
                primaryContainer = homeColors.primaryContainer,
                onPrimaryContainer = homeColors.onPrimaryContainer,
                dark = homeColors.dark,
                links = homeColors.links,
            ),
            "text/html",
            "utf-8",
            null,
        )
    }

    /**
     * 主页配色。原生侧算好颜色再注入 CSS，这样主页能跟随 Material You 动态取色，
     * 而不必在 HTML 里重复一套主题逻辑。
     */
    data class HomeColors(
        val background: String = "#FFFBFE",
        val onBackground: String = "#1C1B1F",
        val primary: String = "#1B6C4B",
        val onPrimary: String = "#FFFFFF",
        val primaryContainer: String = "#A8F2CB",
        val onPrimaryContainer: String = "#00210F",
        val dark: Boolean = false,
        val links: List<Pair<String, String>> = emptyList(),
    )

    /** 由 UI 层在主题变化时写入；下次打开主页即生效。 */
    var homeColors: HomeColors = HomeColors()

    /** 重新渲染所有正在显示主页的标签页（主题或书签变化时调用）。 */
    fun refreshHome() {
        _tabs.value.filter { HomePage.isHomeUrl(it.url) }.forEach { tab ->
            webViews[tab.id]?.let { loadHome(tab.id, it) }
        }
    }

    fun reload(id: String = _activeId.value) {
        obtainWebView(id).reload()
    }

    fun stopLoading(id: String = _activeId.value) {
        obtainWebView(id).stopLoading()
        updateTab(id) { it.copy(isLoading = false) }
    }

    fun goBack(id: String = _activeId.value): Boolean {
        val wv = obtainWebView(id)
        return if (wv.canGoBack()) {
            wv.goBack(); true
        } else false
    }

    fun goForward(id: String = _activeId.value): Boolean {
        val wv = obtainWebView(id)
        return if (wv.canGoForward()) {
            wv.goForward(); true
        } else false
    }

    fun clearHistory(id: String = _activeId.value) {
        obtainWebView(id).clearHistory()
        syncNavState(id)
    }

    /** 清空所有浏览数据（设置页的「清除数据」）。 */
    fun clearBrowsingData() {
        webViews.values.forEach {
            it.clearCache(true)
            it.clearHistory()
            it.clearFormData()
        }
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
    }

    // ---------------------------------------------------------------- 内部

    /** 取得（必要时创建）某个标签页的 WebView。 */
    private fun obtainWebView(id: String): LingWebView {
        webViews[id]?.let { wv ->
            // LRU：访问过的移到末尾
            webViews.remove(id)
            webViews[id] = wv
            return wv
        }

        val tab = _tabs.value.firstOrNull { it.id == id }
        val incognito = tab?.isIncognito ?: false

        val wv = LingWebView(context).apply {
            // 注意：在 LingWebView 的 apply 作用域里，裸写 `settings` 会解析成
            // WebView.settings（WebSettings），必须显式限定到本类的字段。
            applySettings(this@WebTabManager.settings, incognito)
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    updateTab(id) { it.copy(progress = newProgress) }
                }

                override fun onReceivedTitle(view: WebView?, title: String?) {
                    // 主页标题固定为「主页」：WebView 会报 <title>翎</title>，
                    // 那个是页面标题，不适合出现在标签列表里。
                    val isHomePage = HomePage.isHomeUrl(view?.url)
                    title?.takeIf { !isHomePage }?.let { t ->
                        updateTab(id) { it.copy(title = t) }
                    }
                }
            }
            webViewClient = LingWebViewClient(
                isIncognito = incognito,
                onPageStarted = { url ->
                    // 内置主页是用 loadDataWithBaseURL 加载的，WebView 回调回来的
                    // 是 baseUrl（HomePage.BASE_URL）而不是逻辑地址 ling://home。
                    // 这里必须挡住这次回写，否则：
                    //   1. TabState.url 被改写成 baseUrl，refreshHome 的过滤会漏掉本页；
                    //   2. canGoBack/canGoForward 被重置，返回键会闪一下。
                    val isHome = HomePage.isHomeUrl(url)
                    updateTab(id) {
                        it.copy(
                            url = if (isHome) it.url else url,
                            isLoading = !isHome,
                            errorText = null,
                            canGoBack = canGoBack(),
                            canGoForward = canGoForward(),
                        )
                    }
                },
                onPageFinished = { url, title ->
                    val isHome = HomePage.isHomeUrl(url)
                    updateTab(id) {
                        it.copy(
                            url = if (isHome) it.url else url,
                            // 主页标题固定为「主页」，不用 WebView 的 <title>翎</title>
                            title = if (isHome) it.title else title.ifBlank { it.title },
                            isLoading = false,
                            progress = 100,
                            canGoBack = canGoBack(),
                            canGoForward = canGoForward(),
                        )
                    }
                    // 主页是内部页面，不该进历史记录
                    if (!isHome) onVisited?.invoke(url, title, incognito)
                },
                onProgress = { p -> updateTab(id) { it.copy(progress = p) } },
                onReceivedTitle = { t ->
                    // 同上：主页保留「主页」这个标题
                    val cur = _tabs.value.firstOrNull { it.id == id }
                    if (!HomePage.isHomeUrl(cur?.url)) {
                        updateTab(id) { it.copy(title = t) }
                    }
                },
                onReceivedIcon = { },
                onError = { _, desc ->
                    updateTab(id) { it.copy(isLoading = false, errorText = desc) }
                },
                onExternalScheme = { uri -> onExternalUri?.invoke(uri) ?: false },
            )
            setDownloadListener { url, _, _, mimeType, _ ->
                onDownloadRequested?.invoke(url, mimeType)
            }
        }

        webViews[id] = wv
        enforceCacheLimit()
        return wv
    }

    /** WebView 实例上限：超过则回收最久未使用的非激活实例。 */
    private fun enforceCacheLimit() {
        while (webViews.size > MAX_WEBVIEWS) {
            val victim = webViews.keys.firstOrNull { it != _activeId.value } ?: return
            webViews.remove(victim)?.let { wv ->
                (wv.parent as? FrameLayout)?.removeView(wv)
                wv.destroy()
            }
        }
    }

    private fun updateTab(id: String, transform: (TabState) -> TabState) {
        _tabs.update { list ->
            list.map { if (it.id == id) transform(it) else it }
        }
    }

    private fun syncNavState(id: String) {
        val wv = webViews[id] ?: return
        updateTab(id) {
            it.copy(
                url = wv.url ?: it.url,
                title = wv.title?.takeIf { t -> t.isNotBlank() } ?: it.title,
                canGoBack = wv.canGoBack(),
                canGoForward = wv.canGoForward(),
            )
        }
    }

    /** 退出时释放全部 WebView，避免泄漏。 */
    fun destroyAll() {
        webViews.values.forEach {
            (it.parent as? FrameLayout)?.removeView(it)
            it.stopLoading()
            it.destroy()
        }
        webViews.clear()
        _tabs.value = emptyList()
        _activeId.value = ""
    }

    companion object {
        /** 同时存活的 WebView 实例上限。Via 这类轻量浏览器也维持类似的少量实例。 */
        private const val MAX_WEBVIEWS = 6
    }
}
