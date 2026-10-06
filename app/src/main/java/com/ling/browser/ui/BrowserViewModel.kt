package com.ling.browser.ui

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ling.browser.LingApplication
import com.ling.browser.data.db.Bookmark
import com.ling.browser.data.db.HistoryEntry
import com.ling.browser.data.prefs.LingSettings
import com.ling.browser.data.prefs.NightMode
import com.ling.browser.data.prefs.SearchEngine
import com.ling.browser.data.prefs.TabsHeight
import com.ling.browser.util.UrlUtils
import com.ling.browser.web.TabState
import com.ling.browser.web.WebTabManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
