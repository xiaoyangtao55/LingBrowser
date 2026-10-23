package com.ling.browser.ui

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.webkit.CookieManager
import android.widget.Toast
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.ling.browser.LingApplication
import com.ling.browser.MainActivity
import com.ling.browser.R
import com.ling.browser.data.db.Bookmark
import com.ling.browser.data.db.DownloadEntry
import com.ling.browser.data.db.HistoryEntry
import com.ling.browser.data.repo.BlockSubscription
import com.ling.browser.data.repo.ImportResult
import com.ling.browser.data.repo.MAX_BACKUP_BYTES
import com.ling.browser.data.repo.ScriptImportResult
import com.ling.browser.data.repo.UserScriptEntry
import com.ling.browser.data.repo.ValueWrite
import com.ling.browser.data.repo.deriveFolders
import com.ling.browser.data.repo.parseNetscapeHtml
import com.ling.browser.data.repo.planImport
import com.ling.browser.data.repo.toNetscapeHtml
import com.ling.browser.data.prefs.DownloadLocation
import com.ling.browser.data.prefs.DownloaderType
import com.ling.browser.data.prefs.HomeBackground
import com.ling.browser.data.prefs.LingSettings
import com.ling.browser.data.prefs.NightMode
import com.ling.browser.data.prefs.ReaderFontSize
import com.ling.browser.data.prefs.SearchEngine
import com.ling.browser.download.ConflictAction
import com.ling.browser.util.FaviconFetcher
import com.ling.browser.util.UrlUtils
import com.ling.browser.web.ConsoleEntry
import com.ling.browser.web.HitTarget
import com.ling.browser.web.InternalPage
import com.ling.browser.web.ReaderPage
import com.ling.browser.web.ReaderResult
import com.ling.browser.web.ScriptHttpRequest
import com.ling.browser.web.ScriptHttpResponse
import com.ling.browser.web.ScriptHttpTooLargeException
import com.ling.browser.web.SniffResult
import com.ling.browser.web.TabState
import com.ling.browser.web.UrlScheme
import com.ling.browser.web.UserScriptMenuCommand
import com.ling.browser.web.WebTabManager
import com.ling.browser.web.connectAllowed
import com.ling.browser.web.jsString
import java.io.ByteArrayOutputStream
import java.net.SocketTimeoutException
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
import org.json.JSONObject

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
        // 主页搜索框提交：判定"网址还是搜索词"用地址栏同一套逻辑（UrlUtils），
        // 在当前标签打开 —— 网页那边只负责把输入交回来，不做任何判定。
        tabManager.onSearchRequested = { query ->
            navigate(UrlUtils.toUrl(query, settings.value.searchEngine))
        }
        // 长按链接/图片：只把命中结果交给 UI，菜单项由界面按目标类型拼
        tabManager.onLongPress = { target ->
            _hitTarget.value = target
        }
        // 脚本开的标签被用户关掉时，把 onclose 送回发起页。
        tabManager.onTabClosed = { closedId ->
            notifyScriptTabClosed(closedId)
            // GM_getTab 的数据只在该标签存活期间有意义。
            scriptTabData.remove(closedId)
        }

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
            handleUserScriptMessage(tabId, message)
        }
        // 点开 `.user.js` 安装链接：只记下地址弹确认，不直接加载，也不直接安装。
        tabManager.onUserScriptLink = { url -> _pendingUserScript.value = url }

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
        val url = UrlUtils.toUrl(raw, settings.value.searchEngine)
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
        openExternalUrl(UrlUtils.toUrl(query, settings.value.searchEngine))
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

    /**
     * 当前标签「可收藏」的地址与标题。
     *
     * 阅读视图必须换成**原文地址**：它的逻辑地址是合成的 `ling://reader`，
     * 收藏下来就是一个点不开的书签。
     * 主页则没有收藏价值（返回 null）。
     */
    private fun bookmarkTarget(): BookmarkTarget? {
        val tab = activeTab() ?: return null
        val url = InternalPage.externalUrl(tab.url, tabManager.readerOriginalUrl()) ?: return null
        return BookmarkTarget(url, tab.displayTitle)
    }

    private data class BookmarkTarget(val url: String, val title: String)

    fun toggleBookmark() {
        val tab = activeTab() ?: return
        val target = bookmarkTarget()
        if (target == null) {
            // 两种"没有可收藏地址"要分开说，否则用户不知道为什么点了没反应
            _message.value = if (UrlUtils.isHome(tab.url)) {
                "主页无需收藏"
            } else {
                "找不到这篇文章的原网页地址，无法收藏"
            }
            return
        }
        viewModelScope.launch {
            val now = container.bookmarks.toggle(target.title, target.url)
            _isBookmarked.value = now
            _message.value = if (now) "已加入书签" else "已取消收藏"
            if (now) {
                // 页面刚打开过，WebView 已经把图标交给我们了（缩略图用的
                // 就是它），直接复用省一次网络请求；没有再走后台抓取。
                val bytes = tab.favicon
                    ?.takeIf { !it.isRecycled }
                    ?.let { encodeIcon(it) }
                if (bytes != null) {
                    container.bookmarks.setFavicon(target.url, bytes)
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
        // 用 bookmarkTarget()：阅读视图要按**原文地址**查收藏态，否则
        // 明明收藏过这篇文章，菜单里还显示"添加书签"。
        // 主页没有收藏态可言，直接置 false（不能提前 return —— 那会把上一个
        // 页面的状态留在界面上）。
        val url = bookmarkTarget()?.url
        viewModelScope.launch {
            _isBookmarked.value = url != null && container.bookmarks.isBookmarked(url)
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
            val written = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver
                        .openOutputStream(target)?.use { output ->
                            output.write(text.toByteArray(Charsets.UTF_8))
                        } ?: error("无法写入所选位置")
                }.isSuccess
            }
            _message.value = if (written) {
                "已导出 ${list.size} 条书签"
            } else {
                "导出失败，请换个位置重试"
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
     * 待确认的下载请求。
     *
     * 网页触发下载时不直接开始，先交给 UI 弹确认框 —— 有些页面会自动
     * 发起下载（弹窗广告、误触），静默落盘既费流量又让人措手不及。
     * 用户确认后再调 [confirmPendingDownload]。
     */
    private val _pendingDownload = MutableStateFlow<PendingDownload?>(null)
    val pendingDownload: StateFlow<PendingDownload?> = _pendingDownload.asStateFlow()

    /**
     * 待确认的用户脚本安装链接（`.user.js`）。
     *
     * 链接本身不加载，先由 UI 弹确认框；确认后走 [importUserScriptFromUrl]，
     * 与在管理页里粘贴网址是**同一条**导入路径（同样有大小/条数上限与解析校验）。
     */
    private val _pendingUserScript = MutableStateFlow<String?>(null)
    val pendingUserScript: StateFlow<String?> = _pendingUserScript.asStateFlow()

    /**
     * `GM_download({ saveAs: true })` 等待用户挑保存位置。
     *
     * 非 null 时 UI 拉起系统文件选择器（`ACTION_CREATE_DOCUMENT`），结果通过
     * [completeScriptSaveAs] 回传。同一时刻只允许一个：选择器是模态的，
     * 叠两个请求会分不清结果属于谁。
     */
    private val _scriptSaveAs = MutableStateFlow<ScriptSaveAsRequest?>(null)
    val scriptSaveAs: StateFlow<ScriptSaveAsRequest?> = _scriptSaveAs.asStateFlow()

    /**
     * 「去申请存储权限」的信号，值只增不减。
     *
     * 当用户选择公共下载目录且系统是 Android 9 及以下时，下载前需要
     * `WRITE_EXTERNAL_STORAGE` 运行时权限。权限申请必须由 Activity 发起，
     * 因此 ViewModel 在这里发信号，UI（MainActivity）收到后用
     * ActivityResultContracts.RequestPermission 拉起系统弹窗，结果再通过
     * [onStoragePermissionResult] 回传。
     *
     * 用递增计数而不是 Boolean：连续两次下载都要各触发一次（Boolean 第二次变化不了）。
     */
    private val _requestStoragePermission = MutableStateFlow(0)
    val requestStoragePermission: StateFlow<Int> = _requestStoragePermission.asStateFlow()

    /**
     * 「去申请通知权限」的信号，值只增不减（理由同上）。
     *
     * 内建下载器靠前台服务的常驻通知展示进度；Android 13 起没有
     * `POST_NOTIFICATIONS` 授权时，通知栏不会出现任何东西 —— 前台服务照跑，
     * 用户却什么都看不到。申请同样必须由 Activity 发起，这里发信号，
     * 授权结果不影响下载，因此无需回传处理。
     */
    private val _requestNotificationPermission = MutableStateFlow(0)
    val requestNotificationPermission: StateFlow<Int> = _requestNotificationPermission.asStateFlow()

    /**
     * 等待存储权限结果的下载请求。
     *
     * 用户点确认后若发现需要权限，先把请求存在这里，权限结果回来后
     * 再决定是真正入队还是提示失败。期间 [_pendingDownload] 已被清空，
     * 避免确认框残留。
     */
    private var pendingAfterPermission: PendingDownload? = null

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

        // 内建下载器的进度靠常驻通知呈现，Android 13+ 需要通知权限。
        // 不阻塞下载：弹窗与下载并行，授权后通知随即出现。
        maybeRequestNotificationPermission()

        // 公共下载目录在 Android 9 及以下需要 WRITE_EXTERNAL_STORAGE 运行时权限。
        // 应用私有目录与 Android 10+ 的 MediaStore 路径都不需要权限，直接入队。
        val loc = settings.value.downloadLocation
        val needPermission = loc == DownloadLocation.PUBLIC_DOWNLOADS &&
            android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q

        if (needPermission) {
            val granted = checkSelfHasStoragePermission()
            if (!granted) {
                pendingAfterPermission = req
                _requestStoragePermission.update { it + 1 }
                return
            }
        }
        enqueueDownload(req)
    }

    /**
     * 存储权限申请结果回调。
     *
     * 由 MainActivity 在权限弹窗返回后调用。
     *  - 已授权：继续之前挂起的下载；
     *  - 拒绝：提示用户无法下载到公共目录，可在设置里切到私有目录。
     */
    fun onStoragePermissionResult(granted: Boolean) {
        val req = pendingAfterPermission ?: return
        pendingAfterPermission = null
        if (granted) {
            enqueueDownload(req)
        } else {
            _message.value = "没有存储权限，无法下载到公共目录。可在设置里选择「应用私有目录」。"
        }
    }

    /**
     * 真正把下载交给仓库。
     *
     * 抽出成单独方法：权限申请前后都要走这一步，避免复制粘贴。
     * 注意参数顺序是 (url, fileName, mimeType)，与 [com.ling.browser.data.repo.DownloadRepository.enqueue] 一致。
     */
    private fun enqueueDownload(req: PendingDownload) {
        viewModelScope.launch {
            val id = container.downloads.enqueue(req.url, req.fileName, req.mimeType)
            _message.value = if (id != null) "已开始下载" else "无法下载该文件"
        }
    }

    /** 同步检查应用是否已持有 WRITE_EXTERNAL_STORAGE。 */
    private fun checkSelfHasStoragePermission(): Boolean {
        val ctx = getApplication<Application>()
        return androidx.core.content.ContextCompat.checkSelfPermission(
            ctx,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /**
     * 内建下载器下载时申请通知权限（Android 13+）。
     *
     * 系统下载器由 DownloadManager 自己发通知，与我们的通知权限无关，故跳过。
     * 已授权或系统版本不需要时也不打扰用户。
     */
    private fun maybeRequestNotificationPermission() {
        if (settings.value.downloaderType != DownloaderType.BUILT_IN) return
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return
        val ctx = getApplication<Application>()
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            ctx,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) _requestNotificationPermission.update { it + 1 }
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
                // 不用 getContentLengthLong：它需要 API 24，而 minSdk 是 23。
                // 直接解析响应头等价，且对分块传输同样返回 -1。
                val len = conn.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
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
            _message.value = "已取消下载并删除文件"
        }
    }

    /** 暂停下载（保留已下载部分，可后续恢复）。 */
    fun pauseDownload(entry: DownloadEntry) {
        viewModelScope.launch { container.downloads.pause(entry) }
    }

    /** 恢复已暂停的下载（自动用 Range 续传）。 */
    fun resumeDownload(entry: DownloadEntry) {
        viewModelScope.launch { container.downloads.resume(entry) }
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

    /** 切换内置主页的背景模式（纯色 / 自定义图片）。 */
    fun setHomeBackground(mode: HomeBackground) = io { container.settings.setHomeBackground(mode) }

    /** 记录用户选中的主页背景图片 URI（持久化读权限由设置页取图时申请）。 */
    fun setHomeBackgroundImageUri(uri: String) =
        io { container.settings.setHomeBackgroundImageUri(uri) }
    fun setNightMode(mode: NightMode) = io { container.settings.setNightMode(mode) }
    fun setDesktopMode(on: Boolean) = io { container.settings.setDesktopMode(on) }
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

    /**
     * 从网址导入用户脚本。
     *
     * 导入会拉网络、解析元数据头，结果只能经 [_message] 回报 ——
     * 静默完成会让用户以为"点了没反应"。
     */
    fun importUserScriptFromUrl(url: String) = viewModelScope.launch {
        reportScriptImport(container.userScripts.importFromUrl(url))
    }

    /** 从本地文件导入。用 SAF 选文件，**不需要**存储权限。 */
    fun importUserScriptFromLocal(uri: Uri) = viewModelScope.launch {
        reportScriptImport(container.userScripts.importFromLocal(uri))
    }

    /** 重新拉取网址脚本。本地导入的没有远端，仓库会拒绝并给出原因。 */
    fun refreshUserScript(id: String) = viewModelScope.launch {
        reportScriptImport(container.userScripts.refresh(id), verb = "更新")
    }

    /** 启用 / 停用。停用只是不再注入，脚本源码与值表都保留。 */
    fun setUserScriptEnabled(id: String, enabled: Boolean) = io {
        container.userScripts.setEnabled(id, enabled)
    }

    /** 删除脚本（连同源码、元信息与 GM 值表）。 */
    fun removeUserScript(id: String) = io { container.userScripts.remove(id) }

    /** 确认安装待确认的脚本链接。 */
    fun confirmUserScriptInstall() {
        val url = _pendingUserScript.value ?: return
        _pendingUserScript.value = null
        importUserScriptFromUrl(url)
    }

    /**
     * 放弃安装。
     *
     * 链接可能来自地址栏提交或页面点击，导航都已被取消 —— 取消后要把地址栏
     * 文本拉回当前页面，否则它会一直显示那个永远不会加载的 `.user.js` 地址。
     */
    fun dismissUserScriptInstall() {
        if (_pendingUserScript.value == null) return
        _pendingUserScript.value = null
        syncAddressFromActiveTab()
    }

    private fun reportScriptImport(result: ScriptImportResult, verb: String = "导入") {
        _message.value = when (result) {
            is ScriptImportResult.Ok -> buildString {
                val entry = result.entry
                append("已$verb「${entry.script.name}」")
                if (entry.script.version.isNotBlank()) append(" v${entry.script.version}")
                append("，匹配 ${entry.script.patternLabels.size} 条规则")
            }
            is ScriptImportResult.Failed -> "${verb}失败：${result.reason}"
        }
    }

    /** GM_xmlhttpRequest 的在途请求：只有存着 tabId / 脚本 id 才能把结果送回页面。 */
    private class RunningScriptRequest(
        val tabId: String,
        val scriptId: String,
        val job: Job,
        val request: ScriptHttpRequest,
    )

    private val scriptRequests = HashMap<String, RunningScriptRequest>()

    /**
     * 一个由脚本打开的标签。
     *
     * [originTabId] 是发起 `GM_openInTab` 的那一页 —— `onclose` 回调要送回那里，
     * 因为回调函数只存在于那个页面的 JS 环境里。`close()` 与 `onclose` 都只认
     * 原生自己登记的 requestId，页面拿 id 关不掉别人的标签。
     */
    private class ScriptTabHandle(
        val tabId: String,
        val originTabId: String,
        val scriptId: String,
        /** 脚本是否声明了 `onclose`；没有就不用留着簿记等关闭。 */
        val notifyClose: Boolean,
    )

    /** `GM_openInTab`：requestId -> 打开出去的标签。 */
    private val scriptTabs = LinkedHashMap<String, ScriptTabHandle>()

    /** `GM_download`：requestId -> 下载任务，用于 abort 与终态去重。 */
    private class ScriptDownloadHandle(
        val id: Long,
        val tabId: String,
        val scriptId: String,
    )

    private val scriptDownloads = HashMap<String, ScriptDownloadHandle>()

    /**
     * `GM_getTab` / `GM_saveTab` 的**按标签**内存态：tabId -> 数据。
     *
     * 与 `GM_setValue` 不同，它不落盘、也不跨标签广播：同一标签页里的所有脚本
     * 共享这一份，标签关掉就随之丢弃（见 [tabManager.onTabClosed]）。
     */
    private val scriptTabData = HashMap<String, JSONObject>()

    companion object {
        /** 同时进行的脚本请求上限：跨域请求不受 CORS 约束，需要自己兜一道。 */
        private const val MAX_SCRIPT_REQUESTS = 8
        private const val DEFAULT_SCRIPT_TIMEOUT_MS = 30_000
        private const val MIN_SCRIPT_TIMEOUT_MS = 1_000
        private const val MAX_SCRIPT_TIMEOUT_MS = 120_000

        /** `GM_openInTab` 记下的标签数上限，避免脚本无限开标签把表撑大。 */
        private const val MAX_SCRIPT_TABS = 32

        /** `GM_xmlhttpRequest` 进度事件的最小间隔（字节），避免逐块刷屏。 */
        private const val PROGRESS_STEP_BYTES = 64L * 1024L

        /** `GM_notification` 的通知渠道与点击回传用的 extras。 */
        const val SCRIPT_CHANNEL_ID = "userscript"
        const val SCRIPT_SILENT_CHANNEL_ID = "userscript_silent"
        const val EXTRA_SCRIPT_TAB = "ling.gm.tab"
        const val EXTRA_SCRIPT_ID = "ling.gm.script"
        const val EXTRA_SCRIPT_NOTICE = "ling.gm.notice"
        const val EXTRA_SCRIPT_NOTICE_URL = "ling.gm.notice.url"

        /** 通知大图的上限：超过就放弃，免得为一条通知冒 OOM 风险。 */
        private const val MAX_NOTIFICATION_ICON_BYTES = 2 * 1024 * 1024
    }

    /**
     * 处理页面里 GM 垫片发来的消息。
     *
     * 消息是页面 JS 构造的，因此**不可信**：脚本 id 必须在仓库里存在且启用
     * （由 [com.ling.browser.data.repo.UserScriptRepository.setValue] 二次校验），
     * 非法 JSON 直接丢弃。写入只进该脚本自己的值文件，不碰任何其它状态。
     */
    private fun handleUserScriptMessage(tabId: String, raw: String) {
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return
        val scriptId = obj.optString("s")
        if (scriptId.isEmpty()) return
        // 桥对象对整个页面可见，消息本身不可信：脚本必须存在且启用。
        // 这一道同时挡住"已停用脚本继续开标签 / 读剪贴板 / 发通知"。
        if (container.userScripts.enabledScripts.value.none { it.id == scriptId }) return
        when (obj.optString("t")) {
            "set" -> {
                val key = obj.optString("k")
                val value = obj.opt("v")
                viewModelScope.launch {
                    val result = container.userScripts.setValue(scriptId, key, value)
                    if (result is ValueWrite.Changed) {
                        // 其它标签页里的同名脚本要同步看到新值（remote=true）。
                        tabManager.broadcastUserScriptValue(scriptId, key, result.old, value, tabId)
                    }
                }
            }
            "open" -> openScriptTab(tabId, scriptId, obj)
            "tabClose" -> scriptTabs.remove(obj.optString("id"))?.let { tabManager.closeTab(it.tabId) }
            // `window.close()`：只关脚本自己开出来的标签。用户手动打开的标签
            // 在浏览器里也不允许脚本关掉，这里同样拒绝。
            "winClose" -> if (scriptTabs.values.any { it.tabId == tabId }) {
                tabManager.closeTab(tabId)
            }
            "tabGet" -> deliverScriptEvent(
                tabId, scriptId, obj.optString("id"), "tab",
                scriptTabData[tabId] ?: JSONObject(),
            )
            "tabSave" -> {
                scriptTabData[tabId] = obj.optJSONObject("v") ?: JSONObject()
            }
            "tabsGet" -> {
                // 按标签 id 分表，键就是其它标签能看到的标识。
                val all = JSONObject()
                for (t in tabManager.tabs.value) {
                    all.put(t.id, scriptTabData[t.id] ?: JSONObject())
                }
                deliverScriptEvent(tabId, scriptId, obj.optString("id"), "tab", all)
            }
            "del" -> {
                val key = obj.optString("k")
                viewModelScope.launch {
                    val result = container.userScripts.deleteValue(scriptId, key)
                    if (result is ValueWrite.Changed) {
                        tabManager.broadcastUserScriptValue(scriptId, key, result.old, null, tabId)
                    }
                }
            }
            "clip" -> {
                val text = obj.optString("v")
                if (text.isNotEmpty()) {
                    // type 缺省是纯文本；'html' / 'text/html' 走富文本剪贴板。
                    val type = obj.optString("ty", "text")
                    val html = type.equals("html", true) || type.contains("html", true)
                    // 消息来自 UI 线程（WebMessageListener 的契约），可以直接碰剪贴板。
                    runCatching {
                        val ctx = getApplication<Application>()
                        val clip = if (html) {
                            ClipData.newHtmlText("翎 · 用户脚本", text, text)
                        } else {
                            ClipData.newPlainText("翎 · 用户脚本", text)
                        }
                        ctx.getSystemService(ClipboardManager::class.java)
                            ?.setPrimaryClip(clip)
                    }
                }
            }
            "menu" -> tabManager.registerUserScriptMenu(
                tabId,
                scriptId,
                obj.optString("id"),
                obj.optString("n"),
                obj.optString("ak"),
            )
            "menuDel" -> tabManager.unregisterUserScriptMenu(tabId, scriptId, obj.optString("id"))
            "notice" -> postUserScriptNotification(tabId, scriptId, obj)
            "xhr" -> startScriptRequest(tabId, scriptId, obj)
            "xa" -> abortScriptRequest(obj.optString("id"))
            "dl" -> startScriptDownload(tabId, scriptId, obj)
            "dlAbort" -> abortScriptDownload(obj.optString("id"))
        }
    }

    /**
     * 触发一条 `GM_registerMenuCommand`。
     *
     * 命令是否还属于当前页由 [WebTabManager] 二次校验：UI 拿到列表到用户点击
     * 之间可能已经导航，那里查不到命令就直接放弃。
     */
    fun invokeUserScriptMenu(tabId: String, scriptId: String, commandId: String) {
        tabManager.invokeUserScriptMenu(tabId, scriptId, commandId)
    }

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
    ) {
        // details.url 表示"点通知就打开这个地址"；没有 url 才是纯回调。
        if (!url.isNullOrBlank()) {
            val scheme = runCatching { Uri.parse(url).scheme?.lowercase(Locale.ROOT) }.getOrNull()
            if (scheme == "http" || scheme == "https") {
                val incognito = tabManager.tabs.value.firstOrNull { it.id == tabId }?.isIncognito == true
                tabManager.newTab(url, incognito = incognito, select = true)
            }
        }
        tabManager.dispatchUserScriptNotificationClick(tabId, scriptId, notificationId)
        tabManager.dispatchUserScriptNotificationDone(tabId, scriptId, notificationId)
    }

    /**
     * 脚本开的标签被关闭：把 `onclose` 送回发起页，并清掉簿记。
     *
     * 脚本自己调 `close()` 时映射已被提前摘除，这里查不到 —— 符合 Tampermonkey
     * 的语义（自己关掉不算"被动关闭"）。
     */
    private fun notifyScriptTabClosed(closedTabId: String) {
        val stale = scriptTabs.filterValues { it.tabId == closedTabId }
        if (stale.isEmpty()) return
        for ((requestId, handle) in stale) {
            scriptTabs.remove(requestId)
            if (handle.notifyClose) {
                deliverScriptEvent(
                    handle.originTabId,
                    handle.scriptId,
                    requestId,
                    "close",
                    JSONObject().put("tabId", closedTabId),
                )
            }
        }
    }

    /**
     * `GM_openInTab`：按 options 打开新标签。
     *
     * `active=false` 时只建标签、开始加载，不切过去（后台打开）。
     * 页面给的 id 由原生记下来，只用于 `handle.close()` 与 onload 回调 ——
     * 映射表之外的值一律忽略，避免成为"任意页面关任意标签"的入口。
     */
    private fun openScriptTab(tabId: String, scriptId: String, details: JSONObject) {
        val requestId = details.optString("id")
        val url = details.optString("u")
        val scheme = runCatching { Uri.parse(url).scheme?.lowercase(Locale.ROOT) }.getOrNull()
        if (scheme != "http" && scheme != "https") {
            if (requestId.isNotEmpty()) {
                deliverScriptEvent(
                    tabId, scriptId, requestId, "error",
                    scriptFailurePayload("只支持 http(s) 地址"),
                )
            }
            return
        }
        // insert：0 = 末尾（默认），1 = 当前标签之后，>1 按 1 起算的位置。
        val insert = details.optInt("ins", 0)
        val originIndex = tabManager.tabs.value.indexOfFirst { it.id == tabId }
        val position = when {
            insert == 1 && originIndex >= 0 -> originIndex + 1
            insert > 1 -> insert - 1
            else -> null
        }
        val tab = tabManager.newTab(
            url = url,
            incognito = details.optBoolean("inc", false),
            select = details.optBoolean("active", true),
            position = position,
        )
        if (requestId.isEmpty()) return
        scriptTabs[requestId] = ScriptTabHandle(
            tabId = tab.id,
            originTabId = tabId,
            scriptId = scriptId,
            notifyClose = details.optBoolean("oc", false),
        )
        while (scriptTabs.size > MAX_SCRIPT_TABS) scriptTabs.remove(scriptTabs.keys.first())
        deliverScriptEvent(tabId, scriptId, requestId, "load", JSONObject().put("tabId", tab.id))
    }

    /**
     * `GM_notification`：发一条系统通知。
     *
     * 权限被拒时退化成 Toast：什么提示都没有比"少了一条通知"更难排查。
     * 通知点击走 [MainActivity]（singleTask），由 Intent extras 找回标签与脚本。
     */
    private fun postUserScriptNotification(tabId: String, scriptId: String, details: JSONObject) {
        val notificationId = details.optString("id").takeIf { it.isNotEmpty() } ?: return
        val entry = container.userScripts.enabledScripts.value.firstOrNull { it.id == scriptId }
            ?: return
        val title = details.optString("title").ifBlank { entry.script.name }
        val text = details.optString("text")
        val ctx = getApplication<Application>()

        val permitted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            androidx.core.content.ContextCompat.checkSelfPermission(
                ctx,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!permitted) {
            // 借既有的权限申请通道：用户授权后，脚本下一次通知就能正常显示。
            _requestNotificationPermission.update { it + 1 }
            Toast.makeText(ctx, listOf(title, text).filter { it.isNotBlank() }.joinToString("："), Toast.LENGTH_LONG).show()
            return
        }

        ensureUserScriptChannel(ctx)
        // 通知 id 只在单个脚本内自增，不同脚本会撞成同一个 "n1"；
        // 拼上脚本 id 再取哈希，才能让 PendingIntent 与通知槽互不覆盖。
        val notifyId = "$scriptId|$notificationId".hashCode()
        val clickUrl = details.optString("u")
        val intent = Intent(ctx, MainActivity::class.java).apply {
            putExtra(EXTRA_SCRIPT_TAB, tabId)
            putExtra(EXTRA_SCRIPT_ID, scriptId)
            putExtra(EXTRA_SCRIPT_NOTICE, notificationId)
            if (clickUrl.isNotBlank()) putExtra(EXTRA_SCRIPT_NOTICE_URL, clickUrl)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pending = PendingIntent.getActivity(
            ctx,
            notifyId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // 静默必须换一个低优先级渠道：Android 8+ 的提示音由渠道决定，
        // 单靠 setSilent 在默认渠道上压不住声音。
        val silent = details.optBoolean("silent", false)
        val channel = if (silent) SCRIPT_SILENT_CHANNEL_ID else SCRIPT_CHANNEL_ID
        val builder = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(pending)
        // timeout：到点自动收起，脚本不用自己记着删。
        details.optLong("to", 0L).takeIf { it > 0 }?.let { builder.setTimeoutAfter(it) }
        if (silent) builder.setSilent(true)

        fun show(b: NotificationCompat.Builder) {
            runCatching {
                NotificationManagerCompat.from(ctx).notify(notifyId, b.build())
            }.onFailure {
                Toast.makeText(ctx, "$title：$text", Toast.LENGTH_LONG).show()
            }
        }
        show(builder)

        // image 是网络地址（少数脚本用 data: URL）：先发文字版，图解码好再原地替换，
        // 避免网络慢时整条通知都不出现。
        val imageUrl = details.optString("img")
        if (imageUrl.isNotBlank()) {
            viewModelScope.launch {
                val icon = loadNotificationImage(imageUrl) ?: return@launch
                show(builder.setLargeIcon(icon))
            }
        }
    }

    /**
     * 拉取 `GM_notification` 的图片。
     *
     * 上限 2 MiB：解码整张图很吃内存，不值得为一条通知冒 OOM 的险；
     * 超限就当没有图，通知照常显示。
     */
    private suspend fun loadNotificationImage(raw: String): android.graphics.Bitmap? =
        withContext(Dispatchers.IO) {
            runCatching {
                val bytes = if (raw.startsWith("data:", ignoreCase = true)) {
                    val comma = raw.indexOf(',')
                    if (comma < 0) return@runCatching null
                    android.util.Base64.decode(raw.substring(comma + 1), android.util.Base64.DEFAULT)
                } else {
                    val conn = java.net.URL(raw).openConnection()
                    conn.connectTimeout = 5_000
                    conn.readTimeout = 5_000
                    conn.getInputStream().use { input ->
                        val out = ByteArrayOutputStream()
                        val buf = ByteArray(8192)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            if (out.size() + n > MAX_NOTIFICATION_ICON_BYTES) return@use null
                            out.write(buf, 0, n)
                        }
                        out.toByteArray()
                    }
                } ?: return@runCatching null
                if (bytes.size > MAX_NOTIFICATION_ICON_BYTES) return@runCatching null
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
        }

    private fun ensureUserScriptChannel(ctx: Application) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(SCRIPT_CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    SCRIPT_CHANNEL_ID,
                    "用户脚本通知",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "由 GM_notification 发出的通知"
                },
            )
        }
        // 静默通知单独一个低优先级渠道：IMPORTANCE_LOW 才真的不响铃、不弹横幅。
        if (nm.getNotificationChannel(SCRIPT_SILENT_CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    SCRIPT_SILENT_CHANNEL_ID,
                    "用户脚本静默通知",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "GM_notification({ silent: true }) 发出的通知"
                    setSound(null, null)
                    enableVibration(false)
                },
            )
        }
    }

    /**
     * 发起一次 `GM_xmlhttpRequest`。
     *
     * 与页面自己的 `fetch` 的关键差别是不受 CORS 限制，因此这里**必须**先确认
     * 脚本仍然存在且启用 —— 桥对象暴露给整个页面，不能让任意网页借它发跨域请求。
     * Cookie 从 [CookieManager] 取，与页面共享登录态（Tampermonkey 默认行为）。
     */
    private fun startScriptRequest(tabId: String, scriptId: String, details: JSONObject) {
        val requestId = details.optString("id").takeIf { it.isNotEmpty() } ?: return
        val script = container.userScripts.enabledScripts.value.firstOrNull { it.id == scriptId }
            ?: return

        fun fail(message: String) {
            deliverScriptEvent(tabId, scriptId, requestId, "error", scriptFailurePayload(message))
        }

        if (scriptRequests.size >= MAX_SCRIPT_REQUESTS) {
            fail("并发请求过多")
            return
        }
        val url = details.optString("u")
        val uri = Uri.parse(url)
        val scheme = runCatching { uri.scheme?.lowercase(Locale.ROOT) }.getOrNull()
        if (scheme != "http" && scheme != "https") {
            fail("只支持 http(s) 请求")
            return
        }
        // @connect 是作者声明的目标白名单。没有声明时放行（Tampermonkey 会弹窗
        // 询问，我们没有逐请求确认 UI，静默拒绝会让老脚本全部失效）。
        val targetHost = uri.host?.lowercase(Locale.ROOT)
        val pageHost = tabManager.tabs.value.firstOrNull { it.id == tabId }?.url
            ?.let { runCatching { Uri.parse(it).host?.lowercase(Locale.ROOT) }.getOrNull() }
        if (!connectAllowed(script.script.connects, targetHost, pageHost)) {
            fail("@connect 未授权访问 $targetHost")
            return
        }

        val method = details.optString("m", "GET").uppercase(Locale.ROOT)
        val headers = LinkedHashMap<String, String>()
        details.optJSONObject("h")?.let { h ->
            for (key in h.keys()) headers[key] = h.optString(key)
        }
        val rawData = details.opt("d")
        val hasBody = rawData != null && rawData !== JSONObject.NULL
        // 对象/数组形式的数据按 JSON 发送，与 Tampermonkey 的处理一致。
        val isJsonBody = hasBody && rawData !is String
        if (isJsonBody && headers.keys.none { it.equals("Content-Type", ignoreCase = true) }) {
            headers["Content-Type"] = "application/json; charset=utf-8"
        }
        val body = when {
            !hasBody -> null
            rawData is String -> rawData.toByteArray(Charsets.UTF_8)
            else -> rawData.toString().toByteArray(Charsets.UTF_8)
        }
        val timeout = details.optInt("to", DEFAULT_SCRIPT_TIMEOUT_MS)
            .coerceIn(MIN_SCRIPT_TIMEOUT_MS, MAX_SCRIPT_TIMEOUT_MS)
        val responseType = details.optString("rt")

        // 已在 UI 线程（WebMessageListener 回调），CookieManager 可以安全调用。
        val cookie = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
        // 进度从 IO 线程来，evalInTab 必须在主线程：这里按 64 KiB 节流后再切线程，
        // 避免 2 MiB 响应把 dispatch 队列刷爆。
        var lastReported = 0L
        val request = ScriptHttpRequest(url, method, headers, body, timeout, cookie) { received, total ->
            if (received - lastReported >= PROGRESS_STEP_BYTES || received == total) {
                lastReported = received
                viewModelScope.launch(Dispatchers.Main) {
                    if (scriptRequests[requestId] != null) {
                        deliverScriptEvent(
                            tabId,
                            scriptId,
                            requestId,
                            "progress",
                            JSONObject()
                                .put("loaded", received)
                                .put("total", total)
                                .put("lengthComputable", total > 0),
                        )
                    }
                }
            }
        }

        val job = viewModelScope.launch {
            try {
                val response = withContext(Dispatchers.IO) { request.execute() }
                withContext(Dispatchers.Main) {
                    // 脚本请求拿到的 Set-Cookie 也要落回 WebView 的 CookieStore，
                    // 否则"用 GM_xhr 登录、页面自己刷新"这类流程会丢会话。
                    for (value in response.setCookies) {
                        runCatching { CookieManager.getInstance().setCookie(response.finalUrl, value) }
                    }
                    if (scriptRequests[requestId] != null) {
                        deliverScriptEvent(
                            tabId,
                            scriptId,
                            requestId,
                            "load",
                            scriptResponsePayload(response, responseType),
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ScriptHttpTooLargeException) {
                if (scriptRequests[requestId] != null) fail("响应体超过 2 MiB")
            } catch (e: SocketTimeoutException) {
                if (scriptRequests[requestId] != null) {
                    deliverScriptEvent(
                        tabId,
                        scriptId,
                        requestId,
                        "timeout",
                        scriptFailurePayload("请求超时"),
                    )
                }
            } catch (e: Exception) {
                if (scriptRequests[requestId] != null) fail(e.message ?: "请求失败")
            } finally {
                scriptRequests.remove(requestId)
            }
        }
        scriptRequests[requestId] = RunningScriptRequest(tabId, scriptId, job, request)
    }

    /** 中止在途请求：先断开 socket，再取消协程，最后把 abort 事件交给脚本。 */
    private fun abortScriptRequest(requestId: String) {
        val running = scriptRequests.remove(requestId) ?: return
        running.request.abort()
        running.job.cancel()
        deliverScriptEvent(
            running.tabId,
            running.scriptId,
            requestId,
            "abort",
            scriptFailurePayload("已中止"),
        )
    }

    private fun scriptFailurePayload(message: String): JSONObject = JSONObject().apply {
        put("readyState", 4)
        put("status", 0)
        put("statusText", "")
        put("finalUrl", "")
        put("responseHeaders", "")
        put("responseText", "")
        put("error", message)
    }

    /**
     * `GM_download`：直接进下载队列，不再弹浏览器自己的确认框。
     *
     * 与 Tampermonkey 对齐：脚本已经明确表达了下载意图，再确认一次只会打断自动化。
     * 文件名只取最后一段，避免 `../` 之类的路径穿越。
     */
    private fun startScriptDownload(tabId: String, scriptId: String, details: JSONObject) {
        val requestId = details.optString("id").takeIf { it.isNotEmpty() } ?: return
        fun fail(message: String) {
            deliverScriptEvent(tabId, scriptId, requestId, "error", scriptFailurePayload(message))
        }
        val url = details.optString("u")
        val scheme = runCatching { Uri.parse(url).scheme?.lowercase(Locale.ROOT) }.getOrNull()
        if (scheme != "http" && scheme != "https") {
            fail("只支持 http(s) 下载")
            return
        }
        val fileName = details.optString("n")
            .replace('\\', '/')
            .substringAfterLast('/')
            .trim()
            .takeIf { it.isNotEmpty() }
        val headers = LinkedHashMap<String, String>()
        details.optJSONObject("h")?.let { h ->
            for (key in h.keys()) headers[key] = h.optString(key)
        }
        // Tampermonkey 的缺省是 uniquify；'prompt' 在移动端也按 uniquify 处理
        // （不弹"是否覆盖"打断自动化，也绝不静默覆盖用户文件）。
        val conflict = when (details.optString("ca").lowercase(Locale.ROOT)) {
            "overwrite" -> ConflictAction.OVERWRITE
            else -> ConflictAction.UNIQUE
        }
        val request = ScriptSaveAsRequest(
            requestId = requestId,
            url = url,
            fileName = fileName,
            tabId = tabId,
            scriptId = scriptId,
            headers = headers,
            conflict = conflict,
        )
        if (details.optBoolean("sa", false)) {
            // saveAs：先让用户挑位置，拿到 URI 再从 completeScriptSaveAs 入队。
            if (_scriptSaveAs.value != null) {
                fail("已有保存请求进行中")
                return
            }
            _scriptSaveAs.value = request
            return
        }
        enqueueScriptDownload(request, targetUri = null)
    }

    /**
     * 系统文件选择器返回后继续入队；[uri] 为 null 表示用户取消。
     *
     * 取消也要回 `onerror`：脚本那边正 await 这个下载，不回调就会一直挂着。
     */
    fun completeScriptSaveAs(uri: Uri?) {
        val request = _scriptSaveAs.value ?: return
        _scriptSaveAs.value = null
        if (uri == null) {
            deliverScriptEvent(
                request.tabId, request.scriptId, request.requestId, "error",
                scriptFailurePayload("已取消保存"),
            )
            return
        }
        enqueueScriptDownload(request, targetUri = uri.toString())
    }

    /** 把一次 `GM_download` 交给下载队列，并开始跟踪它的生命周期。 */
    private fun enqueueScriptDownload(request: ScriptSaveAsRequest, targetUri: String?) {
        maybeRequestNotificationPermission()
        viewModelScope.launch {
            val id = withContext(Dispatchers.IO) {
                container.downloads.enqueue(
                    request.url, request.fileName, null, request.headers,
                    targetUri, request.conflict,
                )
            } ?: run {
                deliverScriptEvent(
                    request.tabId, request.scriptId, request.requestId, "error",
                    scriptFailurePayload("无法下载该文件"),
                )
                return@launch
            }
            scriptDownloads[request.requestId] = ScriptDownloadHandle(id, request.tabId, request.scriptId)
            trackScriptDownload(request.requestId, id, request.url, request.tabId, request.scriptId)
        }
    }

    /**
     * 跟踪一次 `GM_download` 的生命周期。
     *
     * [com.ling.browser.data.repo.DownloadRepository.downloads] 已经汇总了内建引擎与
     * 系统下载器的进度，这里只用轮询把「进度 / 成功 / 失败」翻译成页面里的回调：
     *  - 成功：`onload`（此时文件真的下完了，而不是入队成功）；
     *  - 失败或被取消：`onerror`；
     *  - 进行中：`onprogress`。
     */
    private suspend fun trackScriptDownload(
        requestId: String,
        downloadId: Long,
        url: String,
        tabId: String,
        scriptId: String,
    ) {
        var lastBytes = -1L
        var missingSince = 0L
        var ticks = 0
        while (true) {
            // 已被 abort：映射被摘掉，静默退出（abort 自己发过回调了）。
            if (scriptDownloads[requestId]?.id != downloadId) return
            // 系统下载器的进度靠回查，隔几秒刷一次即可，没必要每秒查全表。
            if (ticks++ % 3 == 1) {
                withContext(Dispatchers.IO) { container.downloads.refresh() }
            }
            val entry = container.downloads.downloads.value.firstOrNull { it.downloadId == downloadId }
            if (entry == null) {
                // 刚入队时流里可能还没这条；连续 3 秒都找不到才当作用户在下载列表里删掉了。
                val now = System.currentTimeMillis()
                if (missingSince == 0L) {
                    missingSince = now
                } else if (now - missingSince > 3_000) {
                    scriptDownloads.remove(requestId)
                    deliverScriptEvent(
                        tabId, scriptId, requestId, "error",
                        scriptFailurePayload("下载已取消"),
                    )
                    return
                }
            } else {
                missingSince = 0L
                when (entry.status) {
                    com.ling.browser.data.db.DownloadStatus.SUCCESS -> {
                        scriptDownloads.remove(requestId)
                        // 补一条终态进度：小文件常在两次轮询之间就下完了，
                        // 只发 load 会让脚本的进度条永远停在 0。Tampermonkey 的
                        // onprogress 末次也是 loaded === total。
                        val total = entry.totalBytes
                        if (total > 0 && lastBytes < total) {
                            deliverScriptEvent(
                                tabId, scriptId, requestId, "progress",
                                JSONObject()
                                    .put("loaded", total)
                                    .put("total", total)
                                    .put("lengthComputable", true),
                            )
                        }
                        deliverScriptEvent(
                            tabId, scriptId, requestId, "load",
                            JSONObject()
                                .put("finalUrl", url)
                                .put("downloadId", downloadId.toString())
                                .put("name", entry.fileName),
                        )
                        return
                    }
                    com.ling.browser.data.db.DownloadStatus.FAILED -> {
                        scriptDownloads.remove(requestId)
                        deliverScriptEvent(
                            tabId, scriptId, requestId, "error",
                            scriptFailurePayload("下载失败"),
                        )
                        return
                    }
                    else -> {
                        val loaded = entry.downloadedBytes
                        if (loaded != lastBytes) {
                            if (lastBytes < 0 || loaded - lastBytes >= PROGRESS_STEP_BYTES || loaded == entry.totalBytes) {
                                lastBytes = loaded
                                deliverScriptEvent(
                                    tabId, scriptId, requestId, "progress",
                                    JSONObject()
                                        .put("loaded", loaded)
                                        .put("total", entry.totalBytes)
                                        .put("lengthComputable", entry.totalBytes > 0),
                                )
                            }
                        }
                    }
                }
            }
            delay(1_000)
        }
    }

    /**
     * `GM_download` 返回句柄的 `abort()`。
     *
     * 先把映射摘掉再去取消：跟踪协程看到映射没了就会退出，避免它再补一条 error
     * 与这里的回调重复。
     */
    private fun abortScriptDownload(requestId: String) {
        val handle = scriptDownloads.remove(requestId) ?: return
        viewModelScope.launch {
            val entry = container.downloads.downloads.value
                .firstOrNull { it.downloadId == handle.id }
            if (entry != null) {
                withContext(Dispatchers.IO) { container.downloads.cancel(entry) }
            }
            deliverScriptEvent(
                handle.tabId, handle.scriptId, requestId, "error",
                scriptFailurePayload("已取消"),
            )
        }
    }

    private fun scriptResponsePayload(
        response: ScriptHttpResponse,
        responseType: String,
    ): JSONObject = JSONObject().apply {
        put("readyState", 4)
        put("status", response.status)
        put("statusText", response.statusText)
        put("finalUrl", response.finalUrl)
        put("responseHeaders", response.headers)
        put("responseText", response.body)
        put("responseType", responseType)
        // 二进制响应体只能以 base64 过桥；垫片里再解回 ArrayBuffer / Blob。
        if (responseType == "arraybuffer" || responseType == "blob") {
            put(
                "responseBase64",
                Base64.encodeToString(response.raw, Base64.NO_WRAP),
            )
        }
    }

    /**
     * 把事件投递回页面。
     *
     * 走 `JSON.parse(字符串字面量)` 而不是直接把 JSON 拼进代码：响应体里可能出现
     * 引号、反斜杠甚至 U+2028，只有先按 JS 字符串转义再解析才不会被当成代码。
     */
    private fun deliverScriptEvent(
        tabId: String,
        scriptId: String,
        requestId: String,
        event: String,
        payload: JSONObject,
    ) {
        val js = "window.__lingGMDispatch&&window.__lingGMDispatch(" +
            jsString(scriptId) + "," +
            jsString(requestId) + "," +
            jsString(event) + ",JSON.parse(" +
            jsString(payload.toString()) + "))"
        tabManager.evalInTab(tabId, js)
    }
    fun setDynamicColor(on: Boolean) = io { container.settings.setDynamicColor(on) }

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

    // ------------------------------------------------------------ 内置主页

    /**
     * 把当前主题配色同步给内置主页。
     *
     * 主页是 WebView 渲染的 HTML，拿不到 Compose 的 ColorScheme，
     * 因此这里把颜色转成 CSS 十六进制串传过去。
     *
     * ⚠️ 不能在「配色没变」时提前 return：背景模式（纯色 ↔ 自定义图片）
     * 变化时配色不变，但 WebView 自身的透明度需要由 [WebTabManager.ensureHomeRendered]
     * 重新保障（普通网页会把 WebView 底设回不透明白色，见 WebTabManager.loadUrl）。
     * 若只靠配色变化驱动，切换背景模式后主页会停在不透明白底、原生背景层被盖住，
     * 表现为"改了背景模式要手动刷新才生效"。
     */
    fun applyHomeTheme(
        background: Color,
        onBackground: Color,
        primary: Color,
        onPrimary: Color,
        primaryContainer: Color,
        onPrimaryContainer: Color,
        dark: Boolean,
    ) {
        val colors = WebTabManager.HomeColors(
            background = background.toCssHex(),
            onBackground = onBackground.toCssHex(),
            primary = primary.toCssHex(),
            onPrimary = onPrimary.toCssHex(),
            primaryContainer = primaryContainer.toCssHex(),
            onPrimaryContainer = onPrimaryContainer.toCssHex(),
            dark = dark,
        )
        // homeColors 只在真正变化时才写：StateFlow 结构化去重依赖字段值变化，
        // 无谓赋值会让下游 collect 多跑一轮。但 ensureHomeRendered 必须每次都调
        // —— 背景模式切换不改变配色，却要靠它把 WebView 底重新设回透明。
        if (colors != tabManager.homeColors) {
            tabManager.homeColors = colors
        }
        // 用 ensureHomeRendered 而不是 refreshHome：冷启动时主页 WebView
        // 可能还没建出来，refreshHome 会静默跳过，导致主页停在默认浅色。
        tabManager.ensureHomeRendered()
    }

    /**
     * 刷新内置主页上的时钟（`HH:mm`）。
     *
     * 由 UI 层的定时器每分钟调用一次（见 BrowserScreen）：主页是零脚本 HTML，
     * 时间不能自己走，只能由原生侧把文本注入进去。
     */
    fun updateHomeClock() = tabManager.updateHomeClock()

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

/**
 * 一次等待用户挑保存位置的 `GM_download({ saveAs: true })`。
 *
 * [conflict] 在用户挑完位置后仍要带进队列：SAF 目标由用户确认，
 * 但下载记录、abort、onprogress 都还走同一条队列。
 */
data class ScriptSaveAsRequest(
    val requestId: String,
    val url: String,
    val fileName: String?,
    val tabId: String,
    val scriptId: String,
    val headers: Map<String, String>,
    val conflict: ConflictAction,
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
