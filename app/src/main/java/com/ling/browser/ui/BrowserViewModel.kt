package com.ling.browser.ui

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ling.browser.LingApplication
import com.ling.browser.data.db.Bookmark
import com.ling.browser.data.db.DownloadEntry
import com.ling.browser.data.db.HistoryEntry
import com.ling.browser.data.prefs.LingSettings
import com.ling.browser.data.prefs.NightMode
import com.ling.browser.data.prefs.SearchEngine
import com.ling.browser.data.prefs.TabsHeight
import com.ling.browser.util.UrlUtils
import com.ling.browser.web.TabState
import com.ling.browser.web.WebTabManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 浏览器主界面的状态与业务逻辑。
 *
 * 状态分三块：
 *  - [settings]：DataStore 持久化的偏好；
 *  - [tabs] / [activeId]：由 [WebTabManager] 持有的标签页；
 *  - [bookmarks] / [history]：Room 持久化的数据。
 */
class BrowserViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as LingApplication).container

    /** WebView 实例管理者。Activity 负责在合适时机 attachHost。 */
    val tabManager = WebTabManager(app)

    val settings: StateFlow<LingSettings> = container.settings.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, LingSettings())

    val tabs: StateFlow<List<TabState>> = tabManager.tabs
    val activeId: StateFlow<String> = tabManager.activeId

    val bookmarks: StateFlow<List<Bookmark>> = container.bookmarks.bookmarks
    val history: StateFlow<List<HistoryEntry>> = container.history.history
    val downloads: StateFlow<List<DownloadEntry>> = container.downloads.downloads

    /** 地址栏当前文本（与真实 URL 解耦：用户编辑时不被页面跳转覆盖）。 */
    private val _addressText = MutableStateFlow("")
    val addressText: StateFlow<String> = _addressText.asStateFlow()

    /** 地址栏是否处于编辑态。 */
    private val _addressEditing = MutableStateFlow(false)
    val addressEditing: StateFlow<Boolean> = _addressEditing.asStateFlow()

    /** 地址栏联想结果。 */
    private val _suggestions = MutableStateFlow<List<Suggestion>>(emptyList())
    val suggestions: StateFlow<List<Suggestion>> = _suggestions.asStateFlow()

    private val _isBookmarked = MutableStateFlow(false)
    val isBookmarked: StateFlow<Boolean> = _isBookmarked.asStateFlow()

    /** 一次性提示消息（Toast/Snackbar）。消费后由 UI 调 [consumeMessage]。 */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        // 设置变化时同步到所有 WebView
        viewModelScope.launch {
            settings.collect { tabManager.applySettings(it) }
        }
        // 标签页变化时同步地址栏
        viewModelScope.launch {
            tabManager.activeId.collect { syncAddressFromActiveTab() }
        }
        viewModelScope.launch {
            tabManager.tabs.collect { syncAddressFromActiveTab() }
        }
        // 页面访问写历史
        tabManager.onVisited = { url, title, incognito ->
            viewModelScope.launch { container.history.record(title, url, incognito) }
        }

        // 启动时若没有标签页，开一个主页
        if (tabManager.tabs.value.isEmpty()) {
            tabManager.newTab(url = UrlUtils.HOME_URL)
        }
    }

    // ------------------------------------------------------------ 地址栏

    fun onAddressChanged(text: String) {
        _addressText.value = text
        viewModelScope.launch {
            if (text.isBlank()) {
                _suggestions.value = emptyList()
                return@launch
            }
            val bm = container.bookmarks.search(text).map {
                Suggestion(it.title.ifBlank { it.url }, it.url, SuggestionKind.BOOKMARK)
            }
            val hs = container.history.search(text).map {
                Suggestion(it.title.ifBlank { it.url }, it.url, SuggestionKind.HISTORY)
            }
            // 书签优先，同 URL 去重
            _suggestions.value = (bm + hs).distinctBy { it.url }.take(8)
        }
    }

    fun onAddressFocusChanged(focused: Boolean) {
        _addressEditing.value = focused
        if (focused) {
            // 聚焦时全选真实 URL，便于直接改写
            _addressText.value = activeTab()?.url?.takeIf { !UrlUtils.isHome(it) } ?: ""
        } else {
            _suggestions.value = emptyList()
            syncAddressFromActiveTab()
        }
    }

    /** 提交地址栏输入：解析后加载。 */
    fun submitAddress(input: String = _addressText.value) {
        val query = input.trim()
        if (query.isEmpty()) return
        val url = UrlUtils.toUrl(query, settings.value.searchEngine)
        navigate(url)
        _addressEditing.value = false
        _suggestions.value = emptyList()
    }

    /** 在当前标签页导航到指定 URL。 */
    fun navigate(url: String) {
        val id = tabManager.activeId.value.ifBlank {
            tabManager.newTab(url).id
            return
        }
        tabManager.loadUrl(id, url)
    }

    /**
     * 回到主页。
     *
     * 用户在设置里填了自定义主页就跳过去，否则回到「翎」的内置主页 ——
     * 内置主页由原生渲染，不联网也能显示。
     * 具体取哪个地址由 [com.ling.browser.data.prefs.LingSettings.homepage] 决定。
     */
    fun goHome() {
        navigate(settings.value.homepage)
    }

    fun openInNewTab(url: String, incognito: Boolean = settings.value.incognito) {
        tabManager.newTab(url, incognito)
    }

    private fun syncAddressFromActiveTab() {
        if (_addressEditing.value) return
        _addressText.value = activeTab()?.url?.takeIf { !UrlUtils.isHome(it) } ?: ""
        refreshBookmarkState()
    }

    fun activeTab(): TabState? = tabManager.activeTab

    // ------------------------------------------------------------ 标签页

    fun newTab(url: String? = null) {
        tabManager.newTab(url)
    }

    fun newIncognitoTab(url: String? = null) {
        tabManager.newTab(url, incognito = true)
    }

    fun closeTab(id: String) = tabManager.closeTab(id)

    fun closeOthers(keepId: String) = tabManager.closeOthers(keepId)

    fun switchTab(id: String) {
        tabManager.switchTo(id)
        _addressEditing.value = false
        syncAddressFromActiveTab()
    }

    fun refresh() = tabManager.reload()

    fun stopLoading() = tabManager.stopLoading()

    fun goBack() = tabManager.goBack()

    fun goForward() = tabManager.goForward()

    // ------------------------------------------------------------ 书签

    fun toggleBookmark() {
        val tab = activeTab() ?: return
        if (UrlUtils.isHome(tab.url)) {
            _message.value = "主页无需收藏"
            return
        }
        viewModelScope.launch {
            val now = container.bookmarks.toggle(tab.displayTitle, tab.url)
            _isBookmarked.value = now
            _message.value = if (now) "已加入书签" else "已取消收藏"
        }
    }

    fun refreshBookmarkState() {
        val url = activeTab()?.url ?: return
        viewModelScope.launch {
            _isBookmarked.value = container.bookmarks.isBookmarked(url)
        }
    }

    fun deleteBookmark(id: Long) {
        viewModelScope.launch { container.bookmarks.delete(id) }
    }

    fun renameBookmark(id: Long, title: String, folder: String?) {
        viewModelScope.launch { container.bookmarks.rename(id, title, folder) }
    }

    // ------------------------------------------------------------ 下载

    /**
     * 待确认的下载请求。
     *
     * 网页触发下载时不直接开始，先交给 UI 弹确认框 —— 有些页面会自动
     * 发起下载（弹窗广告、误触），静默落盘既费流量又让人措手不及。
     * 用户确认后再调 [confirmPendingDownload]。
     */
    private val _pendingDownload = MutableStateFlow<PendingDownload?>(null)
    val pendingDownload: StateFlow<PendingDownload?> = _pendingDownload.asStateFlow()

    /**
     * 网页请求下载：先摆出确认框，并尽力探测文件大小。
     *
     * 大小探测是「尽力而为」：发一个 HEAD 请求读 Content-Length。
     * 很多站点不返回该响应头，或对 HEAD 直接 403/405，因此失败时
     * 界面显示「大小未知」而不是报错 —— 不能因为探不到大小就不让下载。
     */
    fun requestDownload(url: String, mimeType: String?, fileName: String? = null) {
        val name = fileName
            ?: url.substringAfterLast('/').substringBefore('?').takeIf { it.isNotBlank() }
            ?: "download"
        _pendingDownload.value = PendingDownload(
            url = url,
            fileName = name,
            mimeType = mimeType,
            sizeBytes = null,
            probing = true,
        )

        viewModelScope.launch {
            val size = probeContentLength(url)
            // 期间用户可能已经取消或确认了，只在仍是同一个请求时回填
            _pendingDownload.update { cur ->
                if (cur?.url == url) cur.copy(sizeBytes = size, probing = false) else cur
            }
        }
    }

    /** 用户确认下载。 */
    fun confirmPendingDownload() {
        val req = _pendingDownload.value ?: return
        _pendingDownload.value = null
        viewModelScope.launch {
            val ok = container.downloads.enqueue(req.url, req.mimeType, req.fileName)
            _message.value = if (ok) "已开始下载" else "无法下载该文件"
        }
    }

    /** 用户取消下载。 */
    fun dismissPendingDownload() {
        _pendingDownload.value = null
    }

    /**
     * 尽力获取远端文件大小。失败返回 null（界面显示「大小未知」）。
     *
     * 用 HEAD 而非 GET：只取响应头，不下载正文，代价极小。
     * 不用 DownloadManager 预检是因为它无法「只探测不下载」。
     */
    private suspend fun probeContentLength(url: String): Long? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = PROBE_TIMEOUT_MS
                readTimeout = PROBE_TIMEOUT_MS
                // 有些站点对无 UA 的请求直接拒绝
                setRequestProperty("User-Agent", USER_AGENT)
                instanceFollowRedirects = true
            }
            try {
                // 部分站点不实现 HEAD，回退到 Range 请求只取 1 字节
                if (conn.responseCode !in 200..299) {
                    return@runCatching probeWithRange(url)
                }
                val len = conn.contentLengthLong
                if (len > 0) len else probeWithRange(url)
            } finally {
                runCatching { conn.disconnect() }
            }
        }.getOrNull()
    }

    /** HEAD 不可用时的退路：Range: bytes=0-0，从 Content-Range 里读总大小。 */
    private fun probeWithRange(url: String): Long? = runCatching {
        val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = PROBE_TIMEOUT_MS
            readTimeout = PROBE_TIMEOUT_MS
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Range", "bytes=0-0")
        }
        try {
            // Content-Range: bytes 0-0/12345 → 取斜杠后的总大小
            val range = conn.getHeaderField("Content-Range")
            range?.substringAfterLast('/')?.trim()?.toLongOrNull()
        } finally {
            runCatching { conn.disconnect() }
        }
    }.getOrNull()

    fun cancelDownload(entry: DownloadEntry) {
        viewModelScope.launch {
            container.downloads.cancel(entry)
            _message.value = "已取消下载"
        }
    }

    fun removeDownload(entry: DownloadEntry, deleteFile: Boolean = false) {
        viewModelScope.launch { container.downloads.remove(entry, deleteFile) }
    }

    fun clearDownloads(deleteFiles: Boolean = false) {
        viewModelScope.launch {
            container.downloads.clearAll(deleteFiles)
            _message.value = if (deleteFiles) "已清除全部下载与文件" else "已清除下载记录"
        }
    }

    /**
     * 刷新下载进度。
     *
     * DownloadManager 没有进度回调，只能轮询。由下载列表页在可见时
     * 每秒调用一次；页面离开即停止，不在后台空转。
     */
    fun refreshDownloads() = container.downloads.refresh()

    // ------------------------------------------------------------ 历史

    fun deleteHistory(id: Long) {
        viewModelScope.launch { container.history.delete(id) }
    }

    fun clearHistory() {
        viewModelScope.launch {
            container.history.clear()
            _message.value = "已清除浏览历史"
        }
    }

    fun clearAllData() {
        tabManager.clearBrowsingData()
        viewModelScope.launch {
            container.history.clear()
            _message.value = "已清除缓存与 Cookie"
        }
    }

    // ------------------------------------------------------------ 设置

    fun setSearchEngine(engine: SearchEngine) = io { container.settings.setSearchEngine(engine) }
    fun setCustomHomepage(url: String) = io { container.settings.setCustomHomepage(url) }
    fun setNightMode(mode: NightMode) = io { container.settings.setNightMode(mode) }
    fun setDesktopMode(on: Boolean) = io { container.settings.setDesktopMode(on) }
    fun setForceDark(on: Boolean) = io { container.settings.setForceDarkWebPages(on) }
    fun setBlockImages(on: Boolean) = io { container.settings.setBlockImages(on) }
    fun setJavaScript(on: Boolean) = io { container.settings.setJavaScriptEnabled(on) }
    fun setDynamicColor(on: Boolean) = io { container.settings.setDynamicColor(on) }
    fun setTabsHeight(height: TabsHeight) = io { container.settings.setTabsHeight(height) }

    private fun io(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    // ------------------------------------------------------------ 内置主页

    /**
     * 把当前主题配色与书签快捷入口同步给内置主页。
     *
     * 主页是 WebView 渲染的 HTML，拿不到 Compose 的 ColorScheme，
     * 因此这里把颜色转成 CSS 十六进制串传过去；配色没变时直接跳过，避免无谓重绘。
     */
    fun applyHomeTheme(
        background: Color,
        onBackground: Color,
        primary: Color,
        onPrimary: Color,
        primaryContainer: Color,
        onPrimaryContainer: Color,
        dark: Boolean,
        links: List<Pair<String, String>>,
    ) {
        val colors = WebTabManager.HomeColors(
            background = background.toCssHex(),
            onBackground = onBackground.toCssHex(),
            primary = primary.toCssHex(),
            onPrimary = onPrimary.toCssHex(),
            primaryContainer = primaryContainer.toCssHex(),
            onPrimaryContainer = onPrimaryContainer.toCssHex(),
            dark = dark,
            links = links,
        )
        if (colors == tabManager.homeColors) return
        tabManager.homeColors = colors
        tabManager.refreshHome()
    }

    // ------------------------------------------------------------ 其它

    fun consumeMessage() {
        _message.value = null
    }

    fun showMessage(text: String) {
        _message.value = text
    }

    override fun onCleared() {
        super.onCleared()
        tabManager.destroyAll()
    }
}

/** Compose Color -> CSS #rrggbb。 */
private fun Color.toCssHex(): String {
    val r = (red * 255f + 0.5f).toInt().coerceIn(0, 255)
    val g = (green * 255f + 0.5f).toInt().coerceIn(0, 255)
    val b = (blue * 255f + 0.5f).toInt().coerceIn(0, 255)
    return "#%02X%02X%02X".format(r, g, b)
}

enum class SuggestionKind { BOOKMARK, HISTORY }

data class Suggestion(
    val title: String,
    val url: String,
    val kind: SuggestionKind,
)

/**
 * 等待用户确认的下载。
 *
 * [sizeBytes] 为 null 表示探测失败或还没探完，界面据此显示「大小未知」
 * 或「正在获取大小」。绝不因为探不到大小就阻止下载 —— 那会误伤一大批
 * 不支持 HEAD 的站点。
 */
data class PendingDownload(
    val url: String,
    val fileName: String,
    val mimeType: String?,
    val sizeBytes: Long?,
    val probing: Boolean,
)

/** 大小探测超时。设短一些：探测只是锦上添花，不该让确认框卡着不显示。 */
private const val PROBE_TIMEOUT_MS = 4000

/**
 * 探测大小用的 UA。
 *
 * 用桌面版 UA 而不是应用自己的名字：部分站点对陌生 UA 返回 403，
 * 而桌面 UA 的通过率最高，这里只关心响应头，不影响后续真实下载。
 */
private const val USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0 Safari/537.36"
