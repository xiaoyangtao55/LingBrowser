package com.ling.browser.ui

import android.app.Application
import android.net.Uri
import android.os.Build
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ling.browser.LingApplication
import com.ling.browser.data.db.Bookmark
import com.ling.browser.data.db.DownloadEntry
import com.ling.browser.data.db.HistoryEntry
import com.ling.browser.data.repo.BlockSubscription
import com.ling.browser.data.repo.ImportResult
import com.ling.browser.data.repo.MAX_BACKUP_BYTES
import com.ling.browser.data.prefs.AccentColor
import com.ling.browser.data.prefs.DownloadLocation
import com.ling.browser.data.prefs.DownloadNetwork
import com.ling.browser.data.prefs.DownloaderType
import com.ling.browser.data.prefs.HomeBackground
import com.ling.browser.data.prefs.LingSettings
import com.ling.browser.data.prefs.NightMode
import com.ling.browser.data.prefs.ReaderFontSize
import com.ling.browser.data.prefs.SearchEngine
import com.ling.browser.data.repo.UserScriptEntry
import com.ling.browser.data.repo.deriveFolders
import com.ling.browser.data.repo.parseHistoryHtml
import com.ling.browser.data.repo.parseNetscapeHtml
import com.ling.browser.data.repo.planHistoryImport
import com.ling.browser.data.repo.planImport
import com.ling.browser.data.repo.toHistoryHtml
import com.ling.browser.data.repo.toNetscapeHtml
import com.ling.browser.util.FaviconFetcher
import com.ling.browser.util.UrlUtils
import com.ling.browser.web.ConsoleEntry
import com.ling.browser.web.HitTarget
import com.ling.browser.web.ReaderPage
import com.ling.browser.web.ReaderResult
import com.ling.browser.web.SniffResult
import com.ling.browser.web.TabState
import com.ling.browser.web.UrlScheme
import com.ling.browser.web.UserScriptMenuCommand
import com.ling.browser.web.WebTabManager
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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

    /**
     * 首帧就用**已经落库**的那份设置（见 [AppContainer.initialSettings]），
     * 而不是 [LingSettings] 的默认值 —— 否则冷启动会先按默认配色画一帧，
     * 再翻成用户真实配色（深色用户表现为"先亮后暗"）。
     */
    val settings: StateFlow<LingSettings> = container.settings.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, container.initialSettings)

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

    /**
     * 广告拦截的订阅名单（见 [com.ling.browser.data.repo.BlockListRepository]）。
     *
     * 启用中的域名并集不在这里暴露 —— 它直接由上面 init 里的 collect 送进
     * [WebTabManager]，UI 只需要列表本身（条数、启停、更新时间）。
     */
    val blockSubscriptions: StateFlow<List<BlockSubscription>> = container.blockList.subscriptions

    /**
     * 已安装的用户脚本（见 [com.ling.browser.data.repo.UserScriptRepository]）。
     *
     * 与订阅名单一样，UI 只看列表；启用中的脚本快照在 init 里直接送进
     * [WebTabManager]，注入时不需要再经 ViewModel 中转。
     */
    val userScripts: StateFlow<List<UserScriptEntry>> = container.userScripts.scripts

    /** 当前页面里由 `GM_registerMenuCommand` 注册的命令，供「脚本菜单」面板展示。 */
    val userScriptMenus: StateFlow<List<UserScriptMenuCommand>> = tabManager.userScriptMenus

    /** 页面控制台里的 ERROR 消息，供「页面错误」面板展示。 */
    val consoleErrors: StateFlow<List<ConsoleEntry>> = tabManager.consoleErrors

    /**
     * 广告拦截的累计计数 = 已落盘的历史总数 + 本次运行新增。
     *
     * 直接显示这个和值：用户关心的是"一共挡了多少"，不关心它拆成几段。
     * 历史总数在退到后台时被更新（见 [flushBlockedCount]），因此这个值会随
     * 一次冷启动后的读回继续增长。
     */
    val blockedCount: StateFlow<Long> = combine(
        settings.map { it.adBlockBlockedTotal },
        tabManager.blockedCount,
    ) { saved, session -> saved + session }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            container.initialSettings.adBlockBlockedTotal,
        )

    /**
     * 清零拦截计数：历史总数与本次运行一起归零。
     *
     * 用独立协程写盘，理由同 [persistSession] —— 清零后用户可能立刻切走，
     * viewModelScope 会随之取消。
     */
    fun resetBlockedCount() {
        tabManager.resetBlockedCount()
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { container.settings.setAdBlockBlockedTotal(0L) }
        }
    }

    /**
     * 把本次运行新增的拦截数并进已落盘的历史总数，然后清掉会话计数。
     *
     * 由 `Activity.onStop` 触发（与 [persistSession] 同一时机）：这是**唯一**
     * 的落盘点，拦截热路径上一次盘都不写。写盘后先落库再清会话计数，避免
     * 出现"总和短暂变小"的闪动。
     *
     * 进程被强制杀死时 `onStop` 不保证走到 —— 这是本方案唯一的丢失窗口。
     */
    fun flushBlockedCount() {
        val session = tabManager.blockedCount.value
        if (session == 0L) return
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                // 增量写：DataStore 内的读-改-写是原子的，不会因为读到的旧值
                // 尚未被 Flow 刷新而漏加（见 SettingsStore.addAdBlockBlockedTotal）。
                container.settings.addAdBlockBlockedTotal(session)
                tabManager.resetBlockedCount()
            }
        }
    }

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

    /** 下载域：确认框、探测、权限、队列与网络限制询问。 */
    private val downloadDelegate = DownloadDelegate(object : DownloadHost {
        override val app: Application get() = getApplication()
        override val scope: CoroutineScope get() = viewModelScope
        override val settings get() = this@BrowserViewModel.settings
        override val downloads get() = container.downloads
        override val downloadEngine get() = container.downloadEngine

        override fun showMessage(text: String) {
            _message.value = text
        }
    })

    val pendingOpenDownload: StateFlow<Long?> = downloadDelegate.pendingOpenDownload

    fun requestOpenDownload(taskId: Long) = downloadDelegate.requestOpenDownload(taskId)

    fun consumeOpenDownload() = downloadDelegate.consumeOpenDownload()

    /**
     * 因网络限制被暂停、正等待用户决定「要不要继续」的下载任务。
     *
     * 用列表而不是单个值：网络一变可能同时停掉好几个任务，UI 按顺序逐个询问，
     * 回答一个就摘掉一个。
     */
    val networkPrompts: StateFlow<List<DownloadEntry>> = downloadDelegate.networkPrompts

    /** 用户同意在当前网络下继续这个任务（只放行它，不改全局设置）。 */
    fun allowDownloadOnCurrentNetwork(taskId: Long) =
        downloadDelegate.allowDownloadOnCurrentNetwork(taskId)

    /** 用户拒绝：保持暂停，等网络恢复后引擎仍会自动续传。 */
    fun keepDownloadPaused(taskId: Long) = downloadDelegate.keepDownloadPaused(taskId)

    /** 书签域：收藏、图标、重命名与文件夹管理。 */
    private val bookmarksDelegate = BookmarksDelegate(object : BookmarkHost {
        override val bookmarks get() = container.bookmarks
        override val scope: CoroutineScope get() = viewModelScope

        override fun activeTab(): TabState? = this@BrowserViewModel.activeTab()

        override fun readerOriginalUrl(): String? = tabManager.readerOriginalUrl()

        override fun showMessage(text: String) {
            _message.value = text
        }

        override fun setBookmarked(value: Boolean) {
            _isBookmarked.value = value
        }
    })

    /** 用户脚本域：安装、GM 桥、菜单、通知与脚本下载。 */
    private val userScriptCoordinator = UserScriptCoordinator(object : UserScriptHost {
        override val app: Application get() = getApplication()
        override val scope: CoroutineScope get() = viewModelScope
        override val userScripts get() = container.userScripts
        override val downloads get() = container.downloads
        override val tabs get() = tabManager.tabs

        override fun newTab(url: String, incognito: Boolean, select: Boolean, position: Int?): TabState =
            tabManager.newTab(url, incognito, select, position)

        override fun closeTab(id: String) = tabManager.closeTab(id)
        override fun evalInTab(id: String, js: String) = tabManager.evalInTab(id, js)

        override fun registerUserScriptMenu(
            tabId: String,
            scriptId: String,
            commandId: String,
            label: String,
            accessKey: String,
        ) = tabManager.registerUserScriptMenu(tabId, scriptId, commandId, label, accessKey)

        override fun unregisterUserScriptMenu(tabId: String, scriptId: String, commandId: String) =
            tabManager.unregisterUserScriptMenu(tabId, scriptId, commandId)

        override fun invokeUserScriptMenu(tabId: String, scriptId: String, commandId: String) {
            tabManager.invokeUserScriptMenu(tabId, scriptId, commandId)
        }

        override fun broadcastUserScriptValue(
            scriptId: String,
            key: String,
            oldValue: Any?,
            newValue: Any?,
            exceptTabId: String?,
        ) = tabManager.broadcastUserScriptValue(scriptId, key, oldValue, newValue, exceptTabId)

        override fun dispatchUserScriptNotificationClick(
            tabId: String,
            scriptId: String,
            notificationId: String,
        ) = tabManager.dispatchUserScriptNotificationClick(tabId, scriptId, notificationId)

        override fun dispatchUserScriptNotificationDone(
            tabId: String,
            scriptId: String,
            notificationId: String,
        ) = tabManager.dispatchUserScriptNotificationDone(tabId, scriptId, notificationId)

        override fun requestNotificationPermission() {
            downloadDelegate.requestNotificationPermissionSignal()
        }

        override fun maybeRequestNotificationPermission() = downloadDelegate.maybeRequestNotificationPermission()

        override fun showMessage(text: String) {
            _message.value = text
        }

        override fun syncAddressFromActiveTab() = this@BrowserViewModel.syncAddressFromActiveTab()
    })

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
        // 长按链接/图片：只把命中结果交给 UI，菜单项由界面按目标类型拼
        tabManager.onLongPress = { target ->
            _hitTarget.value = target
        }
        // 脚本开的标签被用户关掉时，把 onclose 送回发起页，并清掉该标签的 GM 簿记。
        tabManager.onTabClosed = { closedId -> userScriptCoordinator.onTabClosed(closedId) }

        // 订阅名单（启用中的域名并集）变化时同步给 WebTabManager 的拦截 lambda。
        // 与设置同理：拦截发生在 shouldInterceptRequest 里现读，改完立即生效，
        // 不需要重建 WebView。首次发射即把磁盘上已导入的名单接入（仓库启动时已读回）。
        viewModelScope.launch {
            container.blockList.enabledDomains.collect { domains ->
                tabManager.applyBlockSubscriptions(domains)
            }
        }

        // 用户脚本（启用中的快照）变化时重新注册注入。首次发射即把磁盘上
        // 已有的脚本接入（仓库启动时已读回）。
        viewModelScope.launch {
            container.userScripts.enabledScripts.collect { scripts ->
                tabManager.applyUserScripts(scripts)
            }
        }
        // 页面里 GM_* 的回调：写入值表、打开新标签、发跨域请求或写剪贴板。
        // 消息来自 WebView 的 UI 线程，因此这里可以直接操作标签页。
        tabManager.onUserScriptMessage = { tabId, message ->
            userScriptCoordinator.handleMessage(tabId, message)
        }
        // 点开 `.user.js` 安装链接：只记下地址弹确认，不直接加载，也不直接安装。
        tabManager.onUserScriptLink = { url -> userScriptCoordinator.promptInstall(url) }

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
            // 冷启动时从其它应用进来的链接，到这里才补得上（见 pendingExternalUrl）
            val pending = pendingExternalUrl
            if (pending != null) {
                pendingExternalUrl = null
                openExternalUrl(pending)
            }
            // 快照只用于本次恢复。读完立刻清掉，避免用户"清除数据"后
            // 又被遗留的快照复活 —— 每次真正保存时会重新写入。
            container.session.clear()
        }
    }

    // ------------------------------------------------------------ 地址栏

    fun onAddressChanged(text: String) {
        // 非编辑态不该有输入：地址栏显示的文案是从 TabState 派生的（标题 / 网址），
        // 任何"回写"都不是用户输入，而是显示文案把真实地址覆盖掉 ——
        // 真机上表现为"点标题进去编辑的是标题"（见 AddressBar 里为什么非编辑态
        // 不再挂输入框）。这里再兜一道，避免以后有人把输入框加回非编辑态。
        if (!_addressEditing.value) return
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
            _addressText.value = editableAddress()
        } else {
            _suggestions.value = emptyList()
            syncAddressFromActiveTab()
        }
    }

    /**
     * 进入编辑态时地址栏里放什么地址。
     *
     * 阅读视图要特殊处理：它的逻辑地址是合成的 `ling://reader`，既不能复制也没法改，
     * 拿它去提交还会让 WebView 报 ERR_UNKNOWN_URL_SCHEME 直接白屏 ——
     * 这里换成缓存下来的**原文地址**（canonical）。
     */
    private fun editableAddress(): String {
        tabManager.readerOriginalUrl()?.let { return it }
        return activeTab()?.url?.takeIf { !UrlUtils.isHome(it) } ?: ""
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
        val url = searchTarget(query)
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
        // 非编辑态显示的是"网页标题（加载中则退回网址）"，规则收敛在
        // TabState.addressBarText —— 那里有单测覆盖，这里只负责取值。
        _addressText.value = activeTab()?.addressBarText ?: ""
        refreshBookmarkState()
    }

    fun activeTab(): TabState? = tabManager.activeTab

    // -------------------------------------------- 外部入口（打开链接 / 分享文本）

    /**
     * 冷启动时从外部进来的链接先排在这里。
     *
     * 为什么需要：init 里"恢复会话还是开主页"是**异步**的（要读数据库），
     * 而冷启动时那条「用翎打开链接」的 Intent 一定早于这个决定 —— 那时直接
     * navigate 会被随后的 restore/newTab 覆盖掉，用户看到的就是
     * "点了链接，翎起来了，网页却没打开"。
     */
    private var pendingExternalUrl: String? = null

    /**
     * 「把界面拉回浏览页」的信号，值只增不减。
     *
     * 外部链接进来时用户可能正停在设置/书签页 —— 链接虽然在标签里打开了，
     * 屏幕上还是二级页面，看起来依旧像"点了没反应"。用递增计数而不是 Boolean：
     * 连续两次外部链接都要各触发一次（Boolean 第二次变化不了）。
     */
    private val _showBrowser = MutableStateFlow(0)
    val showBrowser: StateFlow<Int> = _showBrowser.asStateFlow()

    /**
     * 其它应用「用浏览器打开」进来的地址（ACTION_VIEW）。
     *
     * 落在哪个标签：当前标签还停在主页就复用它 —— 用户是从别处点进来的，
     * 不该先留一个空标签再新开一个；否则新开一个（与主流浏览器一致）。
     */
    fun openExternalUrl(raw: String) {
        val url = searchTarget(raw)
        if (!UrlScheme.isNavigable(url)) {
            // 例如 content:// / intent:// —— 交给 WebView 只会白屏，明确说一声
            _message.value = "不支持打开这个链接"
            return
        }
        if (tabManager.tabs.value.isEmpty()) {
            // 标签页还没建出来（冷启动的 init 尚未跑完），先排队
            pendingExternalUrl = url
            return
        }
        // 把界面拉回浏览页（用户可能正停在二级页面）
        _showBrowser.update { it + 1 }
        // 注意 UrlUtils.isHome 的形参是**非空** String：
        // 不能写成 isHome(activeTab()?.url)（可空链传给非空形参，编译不过）
        val active = activeTab()
        if (active == null || UrlUtils.isHome(active.url)) {
            navigate(url)
        } else {
            openInNewTab(url)
        }
    }

    /**
     * 其它应用分享过来的纯文本（ACTION_SEND + text/plain）：按**搜索词**处理。
     *
     * 分享过来的往往是一句话或一个标题，直接当网址打开十有八九是错的，
     * 走搜索引擎才符合预期（manifest 里那条 filter 的注释也是这个语义）。
     */
    fun openSharedText(text: String) {
        val query = text.trim()
        if (query.isEmpty()) return
        openExternalUrl(searchTarget(query))
    }

    /**
     * 按当前生效的搜索引擎解析输入。
     *
     * 取模板而不是枚举：自定义搜索引擎只有地址模板，走同一套逻辑才不会
     * 出现「设置了自定义引擎，地址栏却还在用必应」的分叉。
     */
    private fun searchTarget(input: String): String {
        val s = settings.value
        return UrlUtils.toUrl(input, s.searchQueryUrl, s.searchHomepage)
    }

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

    /**
     * 证书错误页的「继续访问」：放行当前站点的证书错误并重新加载。
     *
     * 只对**当前标签、当前主机**生效，关标签即失效（见 [WebTabManager.proceedInsecure]）。
     * 放行后地址栏仍标「证书错误」—— 用户点的是"我知道风险"，不是"这很安全"。
     */
    fun proceedInsecure() = tabManager.proceedInsecure()

    fun stopLoading() = tabManager.stopLoading()

    fun goBack() = tabManager.goBack()

    fun goForward() = tabManager.goForward()

    // ------------------------------------------------------------ 书签

    fun toggleBookmark() = bookmarksDelegate.toggleBookmark()

    fun fetchMissingFavicons() = bookmarksDelegate.fetchMissingFavicons()

    fun refreshBookmarkState() = bookmarksDelegate.refreshBookmarkState()

    fun deleteBookmark(id: Long) = bookmarksDelegate.deleteBookmark(id)

    /**
     * 重命名书签（只改标题，不动 URL 与所属文件夹）。
     *
     * 名字留空时**保留原样**而不是存一个空标题：列表里一条没有文字的书签
     * 完全无法辨认，比不改更糟。
     */
    fun renameBookmark(id: Long, title: String) = bookmarksDelegate.renameBookmark(id, title)

    // ------------------------------------------------------------ 书签文件夹

    /** 把书签移动到某文件夹；[folder] 为 null 表示移出到"未分类"。 */
    fun moveBookmarkToFolder(id: Long, folder: String?) =
        bookmarksDelegate.moveBookmarkToFolder(id, folder)

    /** 重命名文件夹。名字重复或为空时给出提示并放弃。 */
    fun renameBookmarkFolder(from: String, to: String) =
        bookmarksDelegate.renameBookmarkFolder(from, to)

    /** 删除文件夹，其中的书签退回"未分类"（不连带删除）。 */
    fun deleteBookmarkFolder(folder: String) = bookmarksDelegate.deleteBookmarkFolder(folder)

    fun renameBookmark(id: Long, title: String, folder: String?) =
        bookmarksDelegate.renameBookmark(id, title, folder)

    // ------------------------------------------------------------ 备份与恢复

    /**
     * 把全部书签导出到用户选定的文件。
     *
     * 成败都通过 [_message] 回报：写文件可能因为权限、空间不足或用户选了个
     * 只读位置而失败，静默失败会让用户以为"导出了但文件是空的"。
     */
    fun exportBookmarks(target: Uri) {
        viewModelScope.launch {
            val list = container.bookmarks.bookmarks.value
            val text = toNetscapeHtml(list)
            val written = writeTextTo(target, text)
            _message.value = if (written) {
                "已导出 ${list.size} 条书签"
            } else {
                "导出失败，请换个位置重试"
            }
        }
    }

    /** 把文本写到 SAF 选定的目标；失败返回 false（原因统一提示，不区分细类）。 */
    private suspend fun writeTextTo(target: Uri, text: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                getApplication<Application>().contentResolver
                    .openOutputStream(target)?.use { output ->
                        output.write(text.toByteArray(Charsets.UTF_8))
                    } ?: error("无法写入所选位置")
            }.isSuccess
        }

    /**
     * 把全部历史记录导出到用户选定的文件。
     *
     * 输出为带标题、网址与访问时间的 HTML 表：任何浏览器/文本编辑器都能打开，
     * 不像自有格式那样「导出后只有本应用认」。
     */
    fun exportHistory(target: Uri) {
        viewModelScope.launch {
            val list = container.history.history.value
            if (list.isEmpty()) {
                _message.value = "暂无历史记录可导出"
                return@launch
            }
            val text = toHistoryHtml(list)
            val written = writeTextTo(target, text)
            _message.value = if (written) {
                "已导出 ${list.size} 条历史记录"
            } else {
                "导出失败，请换个位置重试"
            }
        }
    }

    /**
     * 从用户选定的文件导入历史记录。
     *
     * 与书签导入同一套策略：**合并**而不是覆盖，库里已有同网址的条目跳过。
     * 导出文件是本应用自己的 HTML 表格式；解析只认「三列 + 链接」的行，
     * 误选普通网页不会把整页链接当成历史导进来（见 [parseHistoryHtml]）。
     */
    fun importHistory(source: Uri) {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) { readTextCapped(source) }
            if (text == null) {
                _message.value = "读取失败：文件过大或无法读取"
                return@launch
            }
            val parsed = parseHistoryHtml(text)
            if (parsed.isEmpty()) {
                _message.value = "这个文件里没有可导入的历史记录"
                return@launch
            }
            val plan = planHistoryImport(
                container.history.history.value.map { it.url }.toSet(),
                parsed,
            )
            val added = container.history.importAll(
                plan.toAdd.map {
                    HistoryEntry(
                        title = it.title,
                        url = it.url,
                        visitedAt = it.visitedAt,
                        visitCount = it.visitCount,
                    )
                },
            )
            _message.value = when {
                added == 0 -> "没有新增（${plan.skipped} 条已存在或重复）"
                plan.skipped == 0 -> "已导入 $added 条历史记录"
                else -> "已导入 $added 条，跳过 ${plan.skipped} 条重复"
            }
        }
    }

    /**
     * 从用户选定的文件导入书签。
     *
     * **合并**而不是覆盖：库里已有同网址的条目一律跳过。书签是用户自己攒的，
     * 任何"导入顺手删掉几条"的行为都不可原谅，因此这条路径从不 DELETE。
     */
    fun importBookmarks(source: Uri) {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) { readTextCapped(source) }
            if (text == null) {
                _message.value = "读取失败：文件过大或无法读取"
                return@launch
            }
            val parsed = parseNetscapeHtml(text)
            if (parsed.isEmpty()) {
                _message.value = "这个文件里没有可导入的书签"
                return@launch
            }
            // 以库里现有的网址为去重基准；跳过的条数要如实回报，
            // 否则用户看到导入后条数对不上，会以为数据丢了
            val plan = planImport(
                container.bookmarks.bookmarks.value.map { it.url }.toSet(),
                parsed,
            )
            val added = container.bookmarks.addAll(plan.toAdd)
            _message.value = when {
                added == 0 -> "没有新增（${plan.skipped} 条已存在）"
                plan.skipped == 0 -> "已导入 $added 条书签"
                else -> "已导入 $added 条，跳过 ${plan.skipped} 条重复"
            }
        }
    }

    /**
     * 读文件内容，**边读边数**上限。
     *
     * 不能"先整个读进内存再看有多大" —— 那正是要防的事。也刻意不用
     * `InputStream.readBytes()`：它会把整个流读完，没有上限。
     */
    private fun readTextCapped(source: Uri): String? = runCatching {
        getApplication<Application>().contentResolver.openInputStream(source)?.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_BACKUP_BYTES) return null
                out.write(buffer, 0, read)
            }
            String(out.toByteArray(), Charsets.UTF_8)
        }
    }.getOrNull()

    // ------------------------------------------------------------ 长按菜单

    /**
     * 长按网页内容命中的目标；非 null 时 UI 弹出上下文菜单。
     *
     * 放在 ViewModel 而不是 WebTabManager：菜单要不要显示、显示哪些项都取决于
     * 当前标签状态（无痕、能否新开标签），而且转屏/重组后菜单不能凭空消失。
     */
    private val _hitTarget = MutableStateFlow<HitTarget?>(null)
    val hitTarget: StateFlow<HitTarget?> = _hitTarget.asStateFlow()

    /** 关掉长按菜单（下滑、点遮罩、点动作后都走这里）。 */
    fun dismissHitTarget() {
        _hitTarget.value = null
    }

    /**
     * 打开长按命中的链接 / 媒体地址。
     *
     * [inNewTab] 为 true 时新开标签页 —— 长按链接最常见的意图就是"先留着，
     * 别打断当前页面"，所以菜单把新标签页放在第一项。
     */
    fun openHitTarget(inNewTab: Boolean) {
        val url = _hitTarget.value?.url ?: return
        _hitTarget.value = null
        if (inNewTab) newTab(url) else navigate(url)
    }

    /** 只打开图片本身（当前标签或新标签）。 */
    fun openHitTargetImage(inNewTab: Boolean) {
        val url = _hitTarget.value?.imageUrl ?: return
        _hitTarget.value = null
        if (inNewTab) newTab(url) else navigate(url)
    }

    /** 保存长按的图片：仍走下载确认框，不静默落盘。 */
    fun saveHitTargetImage() {
        val url = _hitTarget.value?.imageUrl ?: return
        _hitTarget.value = null
        requestDownload(url, mimeType = null, fileName = null)
    }

    // ------------------------------------------------------------ 下载

    /**
     * 待确认的下载请求（见 [DownloadDelegate]）。
     *
     * 网页触发下载时不直接开始，先交给 UI 弹确认框 —— 有些页面会自动
     * 发起下载（弹窗广告、误触），静默落盘既费流量又让人措手不及。
     * 用户确认后再调 [confirmPendingDownload]。
     */
    val pendingDownload: StateFlow<PendingDownload?> = downloadDelegate.pendingDownload

    /**
     * 待确认的用户脚本安装链接（`.user.js`），见 [UserScriptCoordinator]。
     *
     * 链接本身不加载，先由 UI 弹确认框；确认后走 [importUserScriptFromUrl]，
     * 与在管理页里粘贴网址是**同一条**导入路径（同样有大小/条数上限与解析校验）。
     * [PendingUserScript.preview] 是弹窗期间顺带拉回来的元数据：用户最关心的
     * 是"它会跑在哪些网站"，只给文件名等于让人盲签。
     */
    val pendingUserScript: StateFlow<PendingUserScript?> = userScriptCoordinator.pendingInstall

    /** `GM_download({ saveAs: true })` 等待用户挑保存位置，见 [UserScriptCoordinator]。 */
    val scriptSaveAs: StateFlow<ScriptSaveAsRequest?> = userScriptCoordinator.saveAs

    /** 「去申请存储权限」/「去申请通知权限」信号，见 [DownloadDelegate]。 */
    val requestStoragePermission: StateFlow<Int> = downloadDelegate.requestStoragePermission
    val requestNotificationPermission: StateFlow<Int> = downloadDelegate.requestNotificationPermission

    fun requestDownload(url: String, mimeType: String?, fileName: String? = null) =
        downloadDelegate.requestDownload(url, mimeType, fileName)

    fun confirmPendingDownload() = downloadDelegate.confirmPendingDownload()

    fun onStoragePermissionResult(granted: Boolean) = downloadDelegate.onStoragePermissionResult(granted)

    fun dismissPendingDownload() = downloadDelegate.dismissPendingDownload()

    fun cancelDownload(entry: DownloadEntry) = downloadDelegate.cancelDownload(entry)

    fun pauseDownload(entry: DownloadEntry) = downloadDelegate.pauseDownload(entry)

    fun resumeDownload(entry: DownloadEntry) = downloadDelegate.resumeDownload(entry)

    fun removeDownload(entry: DownloadEntry, deleteFile: Boolean = false) =
        downloadDelegate.removeDownload(entry, deleteFile)

    fun clearDownloads(deleteFiles: Boolean = false) = downloadDelegate.clearDownloads(deleteFiles)

    fun refreshDownloads() = downloadDelegate.refreshDownloads()

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

    /**
     * 给历史记录补网站图标。
     *
     * 与书签（[BookmarksDelegate.fetchMissingFavicons]）同一套路：分批、串行、
     * 失败写空数组占位。但**触发时机不同** —— 只在打开历史页时补一批，
     * 而不是每次导航后都抓：
     *
     *  - 历史是自动累积的，每条都抓意味着正常浏览时平白多出大量请求；
     *  - 用户看历史时才需要图标，那时补刚刚好。
     *
     * 仓库侧已经把候选限制在最近 50 条内（见 urlsMissingFavicon），
     * 因此这里不必再判断"太老的记录不值得抓"。
     */
    fun fetchMissingHistoryFavicons() {
        val pending = container.history.urlsMissingFavicon(limit = 8)
        if (pending.isEmpty()) return
        viewModelScope.launch {
            pending.forEach { url ->
                val bytes = FaviconFetcher.fetch(url)
                // 取不到就写空数组：表示"试过了"，下次不再重复请求。
                // setFavicon 内部会 refresh，历史流随之更新。
                container.history.setFavicon(url, bytes ?: ByteArray(0))
            }
        }
    }

    /**
     * 选择性清除浏览数据。
     *
     * 四项各自独立：用户可能只想清掉 Cookie 换个登录态，而不想丢掉历史；
     * 之前的一键全清做不到这一点。逐项执行后如实回报清掉了哪些，
     * 避免"点了清除但不确定清没清"。
     */
    fun clearBrowsingData(
        history: Boolean,
        cookies: Boolean,
        cache: Boolean,
        session: Boolean,
    ) {
        if (history) {
            viewModelScope.launch { container.history.clear() }
            tabManager.clearWebViewNavigationHistory()
        }
        if (cookies) tabManager.clearCookiesAndSiteData()
        if (cache) tabManager.clearCacheData()
        if (session) clearSession()

        val parts = buildList {
            if (history) add("历史记录")
            if (cookies) add("Cookie 与站点数据")
            if (cache) add("缓存")
            if (session) add("标签页会话")
        }
        _message.value = if (parts.isEmpty()) {
            "请至少选择一项"
        } else {
            "已清除${parts.joinToString("、")}"
        }
    }

    // ------------------------------------------------------------ 设置

    fun setSearchEngine(engine: SearchEngine) = io { container.settings.setSearchEngine(engine) }

    /**
     * 新增自定义搜索引擎。
     *
     * 校验放在这里而不是 UI：设置层的入口只有一个，规则集中在一处
     * 才不会出现"某个入口能存进畸形模板、搜索时崩溃"。
     *
     * @return null 表示成功；否则是给用户看的原因。
     */
    fun addSearchEngine(name: String, queryUrl: String): String? {
        val trimmedName = name.trim()
        val trimmedUrl = queryUrl.trim()
        if (trimmedName.isEmpty()) return "请输入搜索引擎名称"
        if (!trimmedUrl.contains("%s")) return "搜索地址需包含 %s 占位符"
        if (!trimmedUrl.startsWith("http://") && !trimmedUrl.startsWith("https://")) {
            return "搜索地址需以 http:// 或 https:// 开头"
        }
        if (settings.value.customSearchEngines.any { it.name == trimmedName }) {
            return "已存在同名搜索引擎"
        }
        io { container.settings.addCustomSearchEngine(trimmedName, trimmedUrl) }
        return null
    }

    fun removeSearchEngine(name: String) =
        io { container.settings.removeCustomSearchEngine(name) }

    fun selectCustomSearchEngine(name: String) =
        io { container.settings.selectCustomSearchEngine(name) }

    fun setCustomHomepage(url: String) = io { container.settings.setCustomHomepage(url) }

    /** 切换内置主页的背景模式（纯色 / 自定义图片）。 */
    fun setHomeBackground(mode: HomeBackground) = io { container.settings.setHomeBackground(mode) }

    /** 记录用户选中的主页背景图片 URI（持久化读权限由设置页取图时申请）。 */
    fun setHomeBackgroundImageUri(uri: String) =
        io { container.settings.setHomeBackgroundImageUri(uri) }
    fun setNightMode(mode: NightMode) = io { container.settings.setNightMode(mode) }
    fun setDesktopMode(on: Boolean) = io { container.settings.setDesktopMode(on) }

    /**
     * 保存自定义 User-Agent。返回 null 表示已保存，否则是错误原因（供 UI 就地提示）。
     *
     * 只接受可打印 ASCII：这个值会进入 WebView、下载与图标抓取等 HTTP 请求头，
     * 换行/控制字符等于允许在本地注入额外请求头；中文/emoji 则会被部分实现
     * 拒绝或写成乱码。空串是合法的，表示恢复"跟随电脑模式"。
     */
    fun setCustomUserAgent(raw: String): String? {
        val ua = raw.trim()
        if (ua.length > MAX_USER_AGENT_LENGTH) return "不能超过 $MAX_USER_AGENT_LENGTH 个字符"
        if (ua.any { it.code !in 0x20..0x7E }) return "只能使用英文字母、数字和常见符号"
        io { container.settings.setCustomUserAgent(ua) }
        return null
    }
    fun setForceDark(on: Boolean) = io { container.settings.setForceDarkWebPages(on) }
    fun setBlockImages(on: Boolean) = io { container.settings.setBlockImages(on) }
    fun setJavaScript(on: Boolean) = io { container.settings.setJavaScriptEnabled(on) }

    /**
     * 切换广告拦截。
     *
     * 与"用外部 App 打开链接"同理：判定发生在 `shouldInterceptRequest`
     * 里，每次都现读设置（lambda），改完立即生效，无需重建 WebView。
     */
    fun setAdBlockEnabled(on: Boolean) = io { container.settings.setAdBlockEnabled(on) }

    /**
     * 新增一条自定义广告拦截规则。
     *
     * 用 [com.ling.browser.web.AdBlocker.normalizeRule] 清洗后入库。
     * 返回 null 表示成功；否则返回错误原因（供 UI 就地提示）。
     */
    fun addAdBlockRule(raw: String): String? {
        val normalized = com.ling.browser.web.AdBlocker.normalizeRule(raw)
            ?: return "无效的域名，请输入如 ad.example.com"
        val current = settings.value.adBlockRules
        if (normalized in current) return "该规则已存在"
        io { container.settings.setAdBlockRules(current + normalized) }
        return null
    }

    /** 删除一条自定义广告拦截规则。 */
    fun removeAdBlockRule(rule: String) {
        val current = settings.value.adBlockRules
        io { container.settings.setAdBlockRules(current - rule) }
    }

    // ------------------------------------------------------------ 广告拦截订阅

    /**
     * 从网址导入一份订阅名单（Hosts / AdGuard 域名格式均可）。
     *
     * 导入要拉网络、写十几万行，结果只能经 [_message] 回报 —— 这是长耗时操作，
     * 静默完成会让用户以为"点了没反应"。
     */
    fun importBlockSubscriptionFromUrl(url: String) = viewModelScope.launch {
        reportBlockImport(container.blockList.importFromUrl(url))
    }

    /** 从本地文件导入。用 SAF 选文件，**不需要**存储权限。 */
    fun importBlockSubscriptionFromLocal(uri: Uri) = viewModelScope.launch {
        reportBlockImport(container.blockList.importFromLocal(uri))
    }

    /**
     * 重新拉取网址订阅。
     *
     * 本地导入的名单无法在线更新（见 [BlockSubscription.url]）：仓库会拒绝，
     * 这里把原因如实提示出来，而不是静默什么都不做。
     */
    fun refreshBlockSubscription(id: String) = viewModelScope.launch {
        reportBlockImport(container.blockList.refresh(id), verb = "更新")
    }

    /** 启用 / 停用一份订阅。停用只是不参与查表，名单文件仍保留。 */
    fun setBlockSubscriptionEnabled(id: String, enabled: Boolean) = io {
        container.blockList.setEnabled(id, enabled)
    }

    /** 删除一份订阅（连同它在私有目录里的名单文件）。 */
    fun removeBlockSubscription(id: String) = io { container.blockList.remove(id) }

    /**
     * 把一次导入/更新的结果转成一句话提示。
     *
     * 如实报出"条数 / 跳过多少行 / 是否被截断"：一份大名单里混着元素规则、
     * 例外、路径规则是常态，用户看到"文件里 11 万行、只生效 3 千条"若没有
     * 解释，会以为功能坏了。
     */
    private fun reportBlockImport(result: ImportResult, verb: String = "导入") {
        _message.value = when (result) {
            is ImportResult.Ok -> buildString {
                val sub = result.subscription
                append("已$verb「${sub.name}」：${sub.count} 条域名")
                if (sub.skipped > 0) append("，跳过 ${sub.skipped} 行")
                if (sub.truncated) append("（已达上限，仅保留前 ${sub.count} 条）")
            }
            is ImportResult.Failed -> "${verb}失败：${result.reason}"
        }
    }

    // ------------------------------------------------------------ 用户脚本

    fun importUserScriptFromUrl(url: String) = userScriptCoordinator.importFromUrl(url)

    fun importUserScriptFromLocal(uri: Uri) = userScriptCoordinator.importFromLocal(uri)

    fun refreshUserScript(id: String) = userScriptCoordinator.refresh(id)

    fun setUserScriptEnabled(id: String, enabled: Boolean) =
        userScriptCoordinator.setEnabled(id, enabled)

    fun removeUserScript(id: String) = userScriptCoordinator.remove(id)

    /** 确认安装待确认的脚本链接。 */
    fun confirmUserScriptInstall() = userScriptCoordinator.confirmInstall()

    /** 放弃安装（见 [UserScriptCoordinator.dismissInstall]）。 */
    fun dismissUserScriptInstall() = userScriptCoordinator.dismissInstall()

    /**
     * 触发一条 `GM_registerMenuCommand`。
     *
     * 命令是否还属于当前页由 [WebTabManager] 二次校验：UI 拿到列表到用户点击
     * 之间可能已经导航，那里查不到命令就直接放弃。
     */
    fun invokeUserScriptMenu(tabId: String, scriptId: String, commandId: String) =
        userScriptCoordinator.invokeMenu(tabId, scriptId, commandId)

    /**
     * 通知点击后回到页面触发 `details.onclick`。
     *
     * [MainActivity] 从 PendingIntent 的 extras 里读出三个 id 后调用这里。
     * 标签页已经关闭或页面已导航时静默丢弃 —— 回调函数早就没了。
     */
    fun onUserScriptNotificationClick(
        tabId: String,
        scriptId: String,
        notificationId: String,
        url: String? = null,
    ) = userScriptCoordinator.onNotificationClick(tabId, scriptId, notificationId, url)

    /**
     * 系统文件选择器返回后继续入队；[uri] 为 null 表示用户取消。
     *
     * 取消也要回 `onerror`：脚本那边正 await 这个下载，不回调就会一直挂着。
     */
    fun completeScriptSaveAs(uri: Uri?) = userScriptCoordinator.completeSaveAs(uri)

    companion object {
        /** `GM_notification` 的通知渠道与点击回传用的 extras（见 [UserScriptCoordinator]）。 */
        const val SCRIPT_CHANNEL_ID = UserScriptCoordinator.SCRIPT_CHANNEL_ID
        const val SCRIPT_SILENT_CHANNEL_ID = UserScriptCoordinator.SCRIPT_SILENT_CHANNEL_ID
        const val EXTRA_SCRIPT_TAB = UserScriptCoordinator.EXTRA_SCRIPT_TAB
        const val EXTRA_SCRIPT_ID = UserScriptCoordinator.EXTRA_SCRIPT_ID
        const val EXTRA_SCRIPT_NOTICE = UserScriptCoordinator.EXTRA_SCRIPT_NOTICE
        const val EXTRA_SCRIPT_NOTICE_URL = UserScriptCoordinator.EXTRA_SCRIPT_NOTICE_URL
    }

    fun setDynamicColor(on: Boolean) = io { container.settings.setDynamicColor(on) }

    /**
     * 选择固定配色预设。
     *
     * 在支持动态取色的机型上会**顺手关掉动态取色**：两者是二选一关系
     * （动态取色始终优先），不关的话用户点完看不到任何变化，会以为功能坏了。
     * Android 12 以下本来就没有动态取色，只改预设即可。
     */
    fun setAccentColor(color: AccentColor) = io {
        container.settings.setAccentColor(color)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && settings.value.dynamicColor) {
            container.settings.setDynamicColor(false)
        }
    }

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

    /**
     * 选择下载器类型（内建 / 系统）。
     *
     * 改完立即生效：下次下载就会走新的后端；已在进行中的下载不受影响
     * （已经交给对应后端了，中途切换只会让记录来源混乱）。
     */
    fun setDownloaderType(type: DownloaderType) = io { container.settings.setDownloaderType(type) }

    /**
     * 选择下载保存位置（应用私有目录 / 公共下载目录）。
     *
     * 同样只影响**之后**的下载；已下载/进行中的文件仍在原位置。
     */
    fun setDownloadLocation(location: DownloadLocation) =
        io { container.settings.setDownloadLocation(location) }

    /**
     * 选择下载的网络限制。
     *
     * 立刻生效：引擎由设置流驱动（见 `AppContainer`），收紧后正在跑的越界任务
     * 会被自动暂停，放宽后自动续传 —— 不需要重启应用。
     */
    fun setDownloadNetwork(network: DownloadNetwork) =
        io { container.settings.setDownloadNetwork(network) }

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

    /**
     * 资源嗅探结果。用 StateFlow 承载，二级页 `SniffedResourcesScreen`
     * collect 它来展示；嗅探是"读 DOM 拿结果"，不改变当前页面，
     * 因此结果是一次性的快照，进入二级页前触发即可。
     */
    private val _sniffResults = MutableStateFlow<SniffResult>(SniffResult.Ok(emptyList()))
    val sniffResults: StateFlow<SniffResult> = _sniffResults.asStateFlow()

    /**
     * 触发资源嗅探。
     *
     * 成功时把结果写进 [_sniffResults]（UI 已通过路由进入二级页，
     * 拿到结果后自行展示）；失败时走 [_message] 提示。
     */
    fun sniffResources() {
        tabManager.sniffResources { result ->
            when (result) {
                is SniffResult.Ok -> _sniffResults.value = result
                is SniffResult.Error ->
                    _message.value = when (result.reason) {
                        "js_disabled" -> "资源嗅探需要 JavaScript，请在设置中开启"
                        "page_loading" -> "页面还在加载，请稍候再试"
                        "internal_page" -> "内置页没有可嗅探的媒体资源"
                        else -> "嗅探失败：${result.reason}"
                    }
            }
        }
    }

    /** 清空嗅探结果（退出二级页时调用，避免旧结果残留）。 */
    fun clearSniffResults() {
        _sniffResults.value = SniffResult.Ok(emptyList())
    }

    /**
     * 下载嗅探到的资源：复用 [requestDownload] 的确认流程。
     *
     * 用户点「下载」确实表达了意图，但**下载仍然是有副作用的重动作**——
     * 会占流量、占存储、还可能下到一个意想不到的大文件（嗅探到的视频
     * 动辄几十 MB）。与网页触发的下载一致，弹确认框 + 探测大小，让用户
     * 看清"要下什么、大概多大"再决定，而不是静默开始。
     */
    fun downloadResource(url: String) = requestDownload(url, mimeType = null)

    private fun io(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    // ------------------------------------------------------ 内置页配色（阅读视图）

    /**
     * 把当前主题配色同步给阅读视图。
     *
     * 阅读视图仍是 `loadDataWithBaseURL` 渲染的 HTML，拿不到 Compose 的
     * ColorScheme，因此这里把颜色转成 CSS 十六进制串传过去。
     * 主页已改为原生 Compose 渲染（见 `ui/screens/HomeScreen.kt`），直接读
     * MaterialTheme，不再经过这里。
     */
    fun applyInternalColors(
        background: Color,
        onBackground: Color,
        primary: Color,
        onPrimary: Color,
        primaryContainer: Color,
        onPrimaryContainer: Color,
        dark: Boolean,
    ) {
        tabManager.applyInternalColors(
            WebTabManager.InternalColors(
                background = background.toCssHex(),
                onBackground = onBackground.toCssHex(),
                primary = primary.toCssHex(),
                onPrimary = onPrimary.toCssHex(),
                primaryContainer = primaryContainer.toCssHex(),
                onPrimaryContainer = onPrimaryContainer.toCssHex(),
                dark = dark,
            ),
        )
    }

    /**
     * 重新计算「网页是否暗化」并应用到所有 WebView。
     *
     * 为什么需要单独一个入口：「跟随系统」时，**运行中**切换系统明暗不经过设置流
     * （`settings.collect` 不会触发），但界面会跟着系统变（`isSystemInDarkTheme`）。
     * 只靠设置变化驱动就会出现"界面已经变深、网页还是亮的" —— 这正是 README §四.3
     * 记的那条遗留。由 UI 在解析出的明暗变化时调用（见 BrowserScreen 的 LaunchedEffect）。
     *
     * 只传 `settings.value` 就够：`shouldDarkenPages()` 会现读系统 uiMode，
     * 不必把明暗值再穿一层（两处各算一次正是漂移的来源）。
     */
    fun refreshWebDarkMode() {
        tabManager.applySettings(settings.value)
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

/** 自定义 UA 的长度上限：再长就不像 User-Agent，只会白占存储。 */
private const val MAX_USER_AGENT_LENGTH = 512

