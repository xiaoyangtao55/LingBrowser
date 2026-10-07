package com.ling.browser.ui

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ling.browser.LingApplication
import com.ling.browser.data.db.Bookmark
import com.ling.browser.data.db.DownloadEntry
import com.ling.browser.data.db.HistoryEntry
import com.ling.browser.data.repo.deriveFolders
import com.ling.browser.data.prefs.LingSettings
import com.ling.browser.data.prefs.NightMode
import com.ling.browser.data.prefs.ReaderFontSize
import com.ling.browser.data.prefs.SearchEngine
import com.ling.browser.data.prefs.TabsHeight
import com.ling.browser.util.FaviconFetcher
import com.ling.browser.util.UrlUtils
import com.ling.browser.web.ReaderPage
import com.ling.browser.web.ReaderResult
import com.ling.browser.web.TabState
import com.ling.browser.web.WebTabManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
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

    /**
     * 文件夹名列表。从 [bookmarks] 派生而不是单独查询 ——
     * 这样书签一变，文件夹列表自动跟着变，不会出现两者不同步。
     */
    val bookmarkFolders: StateFlow<List<String>> = container.bookmarks.bookmarks
        .map { deriveFolders(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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

        // 启动时恢复上次的会话；没有可恢复的内容才开一个主页。
        //
        // ⚠️ 恢复是异步的（要读数据库），因此**不能**在这里同步判断
        // `tabs.isEmpty()` 就开主页 —— 那样会先开一个主页、再被恢复的
        // 标签覆盖或叠加，用户会看到一个多余的空标签。
        // 正确做法是等 restore 有结果后再决定。
        viewModelScope.launch {
            // 设置项为「不恢复」时直接跳过读取，开一个干净的主页。
            // 用 settings.value（而不是 collect）取一次即可：这是启动时
            // 的一次性决策，之后的开关变化对本次启动没有意义。
            val wantRestore = settings.value.restoreSession
            val restored = if (wantRestore) {
                runCatching { container.session.load() }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
            val ok = restored.isNotEmpty() && tabManager.restore(restored)
            if (!ok) {
                tabManager.newTab(url = UrlUtils.HOME_URL)
            }
            // 快照只用于本次恢复。读完立刻清掉，避免用户"清除数据"后
            // 又被遗留的快照复活 —— 每次真正保存时会重新写入。
            container.session.clear()
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

    /**
     * 提交地址栏输入并**立即收尾**（加载 + 退出编辑态）。
     *
     * 关键是收尾顺序：先把地址栏文本锁定成提交的 URL，再置编辑态为 false。
     * 因为 [onAddressFocusChanged] 在失焦时会调 [syncAddressFromActiveTab]，
     * 而那一刻 `tabManager.loadUrl` 可能还没把 `activeTab().url` 更新过来
     * （WebView 的 URL 要等页面真正开始加载才变），于是地址栏会被清空 ——
     * 表现为"点了回车，地址栏闪一下空白"。这里显式赋值避免那个竞态。
     */
    fun submitAddress(input: String = _addressText.value) {
        val query = input.trim()
        if (query.isEmpty()) return
        val url = UrlUtils.toUrl(query, settings.value.searchEngine)
        navigate(url)
        // 固定住地址栏文本，别让失焦回调读到尚未更新的 tab.url
        _addressText.value = url
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

    /** 标签列表拖拽排序：把 [from] 位置的标签移到 [to]。 */
    fun moveTab(from: Int, to: Int) = tabManager.moveTab(from, to)

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
            if (now) {
                // 页面刚打开过，WebView 已经把图标交给我们了（缩略图用的
                // 就是它），直接复用省一次网络请求；没有再走后台抓取。
                val bytes = tab.favicon
                    ?.takeIf { !it.isRecycled }
                    ?.let { encodeIcon(it) }
                if (bytes != null) {
                    container.bookmarks.setFavicon(tab.url, bytes)
                } else {
                    fetchMissingFavicons()
                }
            }
        }
    }

    /**
     * 给还没有图标的书签补图标。
     *
     * 分批（每次最多 8 个）且逐个串行：图标是锦上添花的东西，
     * 同时发起几十个连接既没必要也容易被站点限流。
     * 每次成功后仓库会 refresh，下一次调用自然取到下一批，
     * 所以这里只跑一批就够了 —— 不必写循环。
     */
    fun fetchMissingFavicons() {
        val pending = container.bookmarks.urlsMissingFavicon(limit = 8)
        if (pending.isEmpty()) return
        viewModelScope.launch {
            pending.forEach { url ->
                val bytes = FaviconFetcher.fetch(url)
                if (bytes != null) {
                    // setFavicon 内部会 refresh，书签流随之更新
                    container.bookmarks.setFavicon(url, bytes)
                } else {
                    // 取不到就写一个空数组占位，表示"试过了"。
                    // 不这样做的话每次进书签页都会重新去请求同一批失败站点，
                    // 白白耗流量。
                    container.bookmarks.setFavicon(url, ByteArray(0))
                }
            }
        }
    }

    private fun encodeIcon(bitmap: android.graphics.Bitmap): ByteArray? = runCatching {
        val out = java.io.ByteArrayOutputStream()
        @Suppress("DEPRECATION")
        bitmap.compress(android.graphics.Bitmap.CompressFormat.WEBP, 80, out)
        out.toByteArray().takeIf { it.isNotEmpty() }
    }.getOrNull()

    fun refreshBookmarkState() {
        val url = activeTab()?.url ?: return
        viewModelScope.launch {
            _isBookmarked.value = container.bookmarks.isBookmarked(url)
        }
    }

    fun deleteBookmark(id: Long) {
        viewModelScope.launch { container.bookmarks.delete(id) }
    }

    /**
     * 重命名书签（只改标题，不动 URL 与所属文件夹）。
     *
     * 名字留空时**保留原样**而不是存一个空标题：列表里一条没有文字的书签
     * 完全无法辨认，比不改更糟。
     */
    fun renameBookmark(id: Long, title: String) {
        val clean = title.trim()
        if (clean.isEmpty()) {
            _message.value = "名称不能为空"
            return
        }
        val current = container.bookmarks.bookmarks.value.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            container.bookmarks.rename(id, clean, current.folder)
            _message.value = "已重命名"
        }
    }

    // ------------------------------------------------------------ 书签文件夹

    /** 把书签移动到某文件夹；[folder] 为 null 表示移出到"未分类"。 */
    fun moveBookmarkToFolder(id: Long, folder: String?) {
        viewModelScope.launch {
            container.bookmarks.move(id, folder?.trim()?.takeIf { it.isNotEmpty() })
            _message.value = if (folder.isNullOrBlank()) "已移出文件夹" else "已移到「$folder」"
        }
    }

    /** 重命名文件夹。名字重复或为空时给出提示并放弃。 */
    fun renameBookmarkFolder(from: String, to: String) {
        val clean = to.trim()
        when {
            clean.isEmpty() -> _message.value = "文件夹名不能为空"
            // 只有改成**别的**已存在名字才算冲突；改回自己（含大小写变化）应放行
            !clean.equals(from, ignoreCase = false) && container.bookmarks.folderExists(clean) ->
                _message.value = "已存在同名文件夹"
            else -> viewModelScope.launch {
                container.bookmarks.renameFolder(from, clean)
                _message.value = "已重命名为「$clean」"
            }
        }
    }

    /** 删除文件夹，其中的书签退回"未分类"（不连带删除）。 */
    fun deleteBookmarkFolder(folder: String) {
        viewModelScope.launch {
            container.bookmarks.deleteFolder(folder)
            _message.value = "已删除文件夹，其中的书签移到了未分类"
        }
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
        // 会话快照也要清：不清的话用户"清除数据"后重启，
        // 上次的标签页会原样复活，看起来像清除没生效。
        clearSession()
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

    /**
     * 切换"恢复上次浏览页面"。
     *
     * 关掉时顺手把已存的快照清掉：否则用户关掉开关、重启一次、
     * 再打开开关，会看到**关掉之前**那一批早就该被遗忘的标签 ——
     * 既意外又像 bug。
     */
    fun setRestoreSession(enabled: Boolean) {
        io { container.settings.setRestoreSession(enabled) }
        if (!enabled) clearSession()
    }

    /**
     * 是否把非 http(s) 链接交给外部 App。
     *
     * 不需要像其他设置那样重建 WebView：判定发生在
     * `shouldOverrideUrlLoading` 里，每次都现读设置（见
     * `WebTabManager` 传进 `LingWebViewClient` 的 lambda），
     * 因此改完立即生效。
     */
    fun setOpenLinksInExternalApp(enabled: Boolean) =
        io { container.settings.setOpenLinksInExternalApp(enabled) }

    // ------------------------------------------------------------ 阅读模式

    /**
     * 当前标签是否处于阅读视图（菜单文案要用）。
     *
     * ⚠️ 必须从 [tabs] 这个 StateFlow **派生**，不能写成
     * `get() = tabManager.isReaderActive()` —— 那样它不是 Compose 状态，
     * 进入阅读模式后菜单文案不会刷新，仍显示"阅读模式"而不是"退出阅读模式"。
     * （`isReaderActive()` 本身是同步读取，只适合内部逻辑判断。）
     */
    val readerActive: StateFlow<Boolean> = tabs
        .map { list -> list.firstOrNull { it.id == activeId.value }?.url }
        .map { ReaderPage.isReaderUrl(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * 进入阅读模式。
     *
     * 失败时才给提示 —— 成功的话页面已经变成阅读视图，用户看得见，
     * 再弹一句"已进入阅读模式"纯属噪声。这是与"添加书签"不同的地方：
     * 加书签的结果在页面上看不出来，所以那边成功也要提示。
     */
    fun enterReaderMode() {
        tabManager.enterReaderMode { result ->
            _message.value = when (result) {
                // 用户自己能在设置里解决，所以要说清楚原因
                is ReaderResult.Error ->
                    when (result.reason) {
                        "js_disabled" -> "阅读模式需要 JavaScript，请在设置中开启"
                        "page_loading" -> "页面还在加载，请稍候再试"
                        else -> "无法进入阅读模式：${result.reason}"
                    }
                is ReaderResult.NoContent ->
                    when (result.reason) {
                        "already_reader" -> "已在阅读模式"
                        else -> "这个页面没有可提取的正文"
                    }
                is ReaderResult.Ok -> null
            }
        }
    }

    fun exitReaderMode() {
        tabManager.exitReaderMode()
        _message.value = "已退出阅读模式"
    }

    /**
     * 切换阅读模式。
     *
     * 一个入口同时负责进出，与 Via 一致：用户心智里这是"开/关阅读模式"
     * 一个开关，而不是两个动作。
     */
    fun toggleReaderMode() {
        if (tabManager.isReaderActive()) exitReaderMode() else enterReaderMode()
    }

    fun setReaderFontSize(size: ReaderFontSize) {
        io { container.settings.setReaderFontSize(size) }
        // 把新字号**直接传下去**，不要让它去读 settings：
        // 上面那行是异步落盘的，此刻 settings 里还是旧值，
        // 读它会导致"改了字号没反应，要再点一次才生效"。
        tabManager.refreshReaderFontSize(size)
    }

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
        // 用 ensureHomeRendered 而不是 refreshHome：冷启动时主页 WebView
        // 可能还没建出来，refreshHome 会静默跳过，导致主页停在默认浅色。
        tabManager.ensureHomeRendered()
    }

    // ------------------------------------------------------------ 其它

    fun consumeMessage() {
        _message.value = null
    }

    fun showMessage(text: String) {
        _message.value = text
    }

    // ------------------------------------------------------------ 会话恢复

    /**
     * 把当前标签页写入磁盘，用于下次启动恢复。
     *
     * ⚠️ 用 [NonCancellable] + 独立的 IO 调度，**不能**用 viewModelScope：
     * 这个方法由 Activity.onStop 触发，而紧接着 Activity 可能被销毁、
     * viewModelScope 同时被取消 —— 那时写库写到一半就会被打断。
     * 这里用 GlobalScope 语义的独立协程，保证写操作能跑完。
     *
     * 无痕标签在这一步被过滤掉（见 `TabSnapshot.isPersistable`）。
     */
    fun persistSession() {
        // 关掉"恢复上次浏览页面"后就不再写盘。
        //
        // 两个理由：一是写了也不会被读，纯属浪费；二是这更符合用户意图 ——
        // 选择不恢复通常意味着不想让自己的浏览记录留在磁盘上，
        // 继续写反而违背预期。
        if (!settings.value.restoreSession) return
        val snapshots = tabManager.snapshots()
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { container.session.save(snapshots) }
        }
    }

    /**
     * 用户主动清除浏览数据时，连同会话快照一起清掉。
     *
     * 否则"清除数据"之后重启，上次的标签页会原样复活 ——
     * 用户会认为清除没生效。
     */
    fun clearSession() {
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { container.session.clear() }
        }
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
