package com.ling.browser.web

import android.annotation.SuppressLint
import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Bundle
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.ClientCertRequest
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.HttpAuthHandler
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.View
import android.widget.FrameLayout
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.ling.browser.data.db.TabSnapshot
import com.ling.browser.data.prefs.LingSettings
import com.ling.browser.data.prefs.NightMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 一次等待用户裁决的证书错误。
 *
 * [mainFrame] 为 false 表示图片/脚本/iframe 等子资源 —— 这类失败以前是静默
 * 取消的，表现成"页面正常但少了内容"；现在与主框架一样弹窗询问。
 * 主机名给 UI 展示，[reason] 是 [certErrorReason] 的中文短句。
 */
data class CertPrompt(
    val host: String,
    val reason: String,
    val mainFrame: Boolean,
)

/**
 * 一次等待用户裁决的表单重提交。
 *
 * POST 结果页刷新时，WebView 不知道该不该把表单数据再发一遍（本地缓存里没有
 * 请求体），默认行为是**什么都不做** —— 表现为"刷新没反应"。这里与 Chrome
 * 对齐：弹窗问一次，用户确认才重新提交。
 */
data class FormResubmitPrompt(val host: String)

/**
 * 一条由 `GM_registerMenuCommand` 注册的菜单命令。
 *
 * 命令活在一个具体的页面实例里（回调函数只在那一页存在），因此必须带
 * [tabId]；页面导航后原生侧会整批清掉，由新页重新注册。
 */
data class UserScriptMenuCommand(
    val tabId: String,
    val scriptId: String,
    val commandId: String,
    val label: String,
    /** `options.accessKey`：单个按键字符（大写），空串表示没指定。 */
    val accessKey: String = "",
)

/**
 * 一次等待用户裁决的 Safe Browsing 命中。
 *
 * [threat] 是本地化后的威胁类型（恶意软件 / 钓鱼网站 …），[host] 用于展示。
 * 与证书错误不同，这里同一时刻通常只有一个主框架命中，队列只作为兜底。
 */
data class SafeBrowsingPrompt(
    val tabId: String,
    val host: String,
    val threat: String,
    val mainFrame: Boolean,
)

/** 页面控制台的一条错误记录（只收 ERROR 级别，用于「页面错误」面板）。 */
data class ConsoleEntry(
    val tabId: String,
    val message: String,
    val source: String,
    val line: Int,
)

/**
 * 一个标签的附属簿记。WebView 本身仍在 `webViews` 里 —— 那张表同时是 LRU 访问序。
 * 关闭标签时只需要丢弃这个对象，不必再逐个 remove 七张表。
 */
internal class TabSession {
    /** document-start 的注册句柄；脚本变更或实例重建时要先摘掉旧的。 */
    val scriptHandlers = mutableListOf<ScriptHandler>()

    /** 本页已注入过的 "脚本|时机|URL"，防止同一页重复执行。 */
    val injectedScripts = mutableSetOf<String>()

    /** (`脚本|命令` -> 菜单项)，供 UI 展示「脚本菜单」。 */
    val menus = LinkedHashMap<String, UserScriptMenuCommand>()

    /** 已提取的正文；退出阅读模式 / 标签关闭时丢弃。 */
    var readerContent: ReaderResult.Ok? = null

    /** 用户已明确放行的证书错误站点（主机名），只在本标签存活期间有效。 */
    val certExceptions = mutableSetOf<String>()

    /** 最近的 ERROR 级控制台消息（新页导航时清空）。 */
    val console = mutableListOf<ConsoleEntry>()

    /** 这个标签里的 `about:blank` 是不是"主页占位"，见 [WebTabManager.isHomePlaceholder]。 */
    var isHomePlaceholder = false

    /**
     * 实例被回收时存下的回退/前进历史栈（[android.webkit.WebView.saveState]）。
     *
     * 为什么要它：WebView 实例一销毁，历史栈就没了 —— 用户切回旧标签时
     * 即使页面重新加载出来，按返回也回不到上一页。saveState 存的是**历史栈**
     * 而非渲染结果，所以恢复后仍要重新加载当前页，但"翻到哪"这条链能接上。
     *
     * 用 Bundle 而不是自己序列化：Android 的 saveState/restoreState 是一对
     * 配套 API —— `saveState(outState: Bundle)` 把历史栈写进 Bundle，
     * `restoreState(inState: Bundle)` 再读回来。自己存 WebBackForwardList
     * 没有可用的恢复入口（它只是个只读快照）。
     *
     * 回收实例时写入，[WebTabManager.obtainWebView] 建新实例后消费并清空。
     */
    var savedState: Bundle? = null

    /**
     * 实例被回收时的滚动位置（CSS 像素）。
     *
     * 为什么单独存一个数字而不做"全状态序列化"：现代 SPA 的 store 在闭包里，
     * `window` 上根本读不到，序列化出来也还原不回去（框架不会拿它重建组件树）。
     * 而滚动位置是用户切回来时最直接感知的东西 —— 一个数字就能拿回 80% 的体验。
     *
     * 由 [captureScrollY] 在销毁前异步抓取（evaluateJavascript 是异步的，
     * 不能在 destroy 里同步等），重建后由 [restoreScrollY] 在页面加载完成时用。
     */
    var scrollY: Int = 0
}

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

    private val _blockedCount = MutableStateFlow(0L)

    /**
     * 本次运行累计拦下的广告/跟踪请求数（应用重启即清零）。
     *
     * 纯内存计数、**不落盘**：拦截发生在每个子资源的 `shouldInterceptRequest`
     * 上，如果每次命中都写一次 DataStore，磁盘会被打爆 —— 而"拦了多少"本身
     * 只是个参考值，不值得为它付持久化代价。
     *
     * 累加走 [MutableStateFlow.update]（内部是 compareAndSet 重试），
     * 因此多个后台线程同时命中也不会丢计数。
     */
    val blockedCount: StateFlow<Long> = _blockedCount.asStateFlow()

    /** tabId -> WebView。仅在内存中存活，不参与状态序列化。 */
    private val webViews = LinkedHashMap<String, LingWebView>()

    /**
     * 当前允许同时存活的 WebView 实例数（动态，见 [updateCacheLimit]）。
     *
     * 为什么不是常量：实例数是**内存压力**的函数，不是固定值。
     * 平时给足（[MAX_WEBVIEWS_DEFAULT]），标签切来切去都是原样；
     * 系统报内存吃紧时再收缩，牺牲"切回来是原样"换取不被 OOM 杀掉。
     *
     * 只写不读之外的线程安全：只在主线程读写（onTrimMemory 也投递到主线程，
     * 见 [updateCacheLimit]），与 [webViews] 同线程，无需加锁。
     */
    private var maxWebViews: Int = MAX_WEBVIEWS_DEFAULT

    /**
     * [obtainWebView] 最近一次是否从 [TabSession.savedState] 恢复了历史栈。
     *
     * 只由 [obtainWebView] 写、[switchTo] 紧接着读一次，因此不需要更复杂的传递方式。
     * 语义是"这个新实例已经有内容了，别再 loadUrl 覆盖它"。
     */
    private var restoredFromSnapshot = false

    /**
     * tabId -> 该标签的附属簿记（脚本句柄、注入记录、菜单、阅读缓存、证书放行、
     * 控制台记录、主页占位标记）。WebView 不在其中，见 [webViews]。
     *
     * 只在主线程读写，与 [webViews] 同线程；关闭标签时整份丢弃，不必逐表 remove。
     */
    private val sessions = HashMap<String, TabSession>()

    /** 取（必要时创建）标签的附属簿记。 */
    private fun session(id: String): TabSession = sessions.getOrPut(id) { TabSession() }

    /** 主线程消息队列：用于把"销毁临时 WebView"这类动作推迟到回调之外。 */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 标签页切换时用于承载 WebView 的宿主容器。 */
    private var host: FrameLayout? = null

    private var settings: LingSettings = LingSettings()

    /**
     * 订阅名单的域名并集（见 [com.ling.browser.data.repo.BlockListRepository.enabledDomains]）。
     *
     * 由 UI 层经 [applyBlockSubscriptions] 注入 —— web 层不认识订阅仓库，
     * 从而保持"web 不依赖 data"的既有分层（与 [onVisited] 等回调同理）。
     */
    private var subscriptionDomains: Set<String> = emptySet()

    /**
     * 自定义规则 + 订阅域名的**并集**，预先算好供拦截热路径直接查表。
     *
     * 为什么不在这条 lambda 里现并：拦截发生在 `shouldInterceptRequest`，
     * 那是**每个子资源**都要跑一次的热路径，而订阅名单可能有十万条 ——
     * 每次请求都并一遍等于白白烧 CPU、还分配一堆临时集合。
     * 改动只发生在设置变化（[applySettings]）与订阅启停（[applyBlockSubscriptions]）
     * 这两个低频时刻，算一次就够了。
     */
    private var mergedBlockRules: Set<String> = settings.adBlockRules

    /**
     * 用户脚本注入 / GM 桥（document-start 注册、按时机注入、菜单、值广播）。
     *
     * 脚本快照、菜单状态与注入记录都收在这个功能簇里（见 [ScriptBridge]）；
     * 宿主只提供 session / WebView 表与"是否无痕"这三样事实。
     */
    private val scriptBridge = ScriptBridge(object : ScriptBridgeHost {
        override fun session(tabId: String): TabSession = this@WebTabManager.session(tabId)

        override fun findSession(tabId: String): TabSession? = this@WebTabManager.sessions[tabId]

        override val allSessions: Collection<TabSession>
            get() = this@WebTabManager.sessions.values

        override val webViews: Map<String, LingWebView>
            get() = this@WebTabManager.webViews

        override fun isIncognito(tabId: String): Boolean =
            this@WebTabManager._tabs.value.firstOrNull { it.id == tabId }?.isIncognito == true
    })

    /** 当前各标签页注册的脚本菜单命令。 */
    val userScriptMenus: StateFlow<List<UserScriptMenuCommand>> get() = scriptBridge.menus

    /**
     * 阅读模式（提取 / 渲染 / 字号 / 退出 / 配色脚本，见 [ReaderController]）。
     *
     * 正文缓存仍在每个标签的 [TabSession] 上 —— 宿主只把"取缓存、改状态、
     * 导航"这几样能力借给它，"关标签一次清空"的簿记方式没有改变。
     */
    private val reader = ReaderController(object : ReaderHost {
        override val activeId: String
            get() = this@WebTabManager._activeId.value

        override val activeTab: TabState?
            get() = this@WebTabManager.activeTab

        override fun webView(tabId: String): LingWebView? = this@WebTabManager.webViews[tabId]

        override fun tab(tabId: String): TabState? =
            this@WebTabManager._tabs.value.firstOrNull { it.id == tabId }

        override fun session(tabId: String): TabSession =
            this@WebTabManager.session(tabId)

        override fun findSession(tabId: String): TabSession? =
            this@WebTabManager.sessions[tabId]

        override val javaScriptEnabled: Boolean
            get() = this@WebTabManager.settings.javaScriptEnabled

        override val readerFontSize: ReaderPage.FontSize
            get() = this@WebTabManager.settings.readerFontSize

        override val internalColors: InternalColors
            get() = this@WebTabManager.internalColors

        override fun updateTab(tabId: String, transform: (TabState) -> TabState) =
            this@WebTabManager.updateTab(tabId, transform)

        override fun loadUrl(tabId: String, url: String) =
            this@WebTabManager.loadUrl(tabId, url)

        override fun loadHome(tabId: String) =
            this@WebTabManager.loadHome(tabId)
    })

    // ---------------------------------------------------------------- Safe Browsing

    /** Safe Browsing 的裁决队列（命中排队 + 弹窗，见 [SafeBrowsingQueue]）。 */
    private val safePrompts = SafeBrowsingQueue()

    /** Safe Browsing 弹窗回调；参数为 null 表示关掉弹窗。 */
    var onSafeBrowsingPrompt: ((SafeBrowsingPrompt?) -> Unit)?
        get() = safePrompts.onPrompt
        set(value) { safePrompts.onPrompt = value }

    /**
     * 标签关闭回调。
     *
     * 用户脚本的 `GM_openInTab(..., { onclose })` 需要知道「自己开的标签被关了」，
     * 但那只簿记在 ViewModel 里，所以这里只发出通知，由上层决定回不给脚本。
     */
    var onTabClosed: ((tabId: String) -> Unit)? = null

    // ---------------------------------------------------------------- 页面控制台错误

    private val _consoleErrors = MutableStateFlow<List<ConsoleEntry>>(emptyList())

    /** 当前各标签页收集到的控制台错误，供「页面错误」面板展示。 */
    val consoleErrors: StateFlow<List<ConsoleEntry>> = _consoleErrors.asStateFlow()

    /** 证书错误的裁决队列（白名单命中直接放行，否则排队弹窗，见 [CertPromptQueue]）。 */
    private val certPrompts = CertPromptQueue(
        sessionOf = { session(it) },
        findSession = { sessions[it] },
        onMainFrameInsecure = { id ->
            updateTab(id) { it.copy(security = SecurityState.INSECURE_CERT, errorText = null) }
        },
        onMainFrameBlocked = { id, reason -> markCertBlocked(id, reason) },
    )

    /**
     * 证书错误弹窗回调：参数为 null 表示"当前没有待裁决项，关掉弹窗"。
     *
     * 用可空参数而不是两个回调，是为了让 UI 只维护一个状态源。
     */
    var onCertPrompt: ((CertPrompt?) -> Unit)?
        get() = certPrompts.onPrompt
        set(value) { certPrompts.onPrompt = value }

    /**
     * 尚未裁决的表单重提交（FIFO）。
     *
     * 与证书错误同理：`resend` / `dontResend` 两个 [Message] 必须且只能发一个，
     * 否则 WebView 的加载会卡在等待状态。队列上限很小 —— 这本来就是低频事件，
     * 超出的直接按"不重新提交"处理。
     */
    private val formQueue = ArrayDeque<PendingFormResubmit>()

    /** 当前正在弹窗的那一条；null 表示没有弹窗。 */
    private var formShown: PendingFormResubmit? = null

    /** 表单重提交弹窗回调；参数为 null 表示关掉弹窗。 */
    var onFormResubmitPrompt: ((FormResubmitPrompt?) -> Unit)? = null

    private class PendingFormResubmit(
        val tabId: String,
        val prompt: FormResubmitPrompt,
        val resend: Message,
        val dontResend: Message,
    )

    /** 需要 UI 层处理的副作用（打开外部应用、下载等）。 */
    var onExternalUri: ((Uri) -> Boolean)? = null

    /** 每次页面访问的回调，用于写历史。 */
    var onVisited: ((url: String, title: String, incognito: Boolean) -> Unit)? = null

    /** 下载请求回调（由 UI 层弹出确认或直接交给系统下载器）。 */
    var onDownloadRequested: ((url: String, mimeType: String?) -> Unit)? = null

    /**
     * 打开 `.user.js` 安装链接的回调（由 UI 层弹安装确认）。
     *
     * 安装链接不加载：原始 JS 当网页渲染只会得到一屏代码，且用户离开后
     * 容易误以为"已经装上了"。确认与导入都由 UI 层负责。
     */
    var onUserScriptLink: ((url: String) -> Unit)? = null

    /**
     * 长按网页中的链接 / 图片 / 媒体时的回调（由 UI 层弹上下文菜单）。
     *
     * 只转发，不做任何判定 —— 菜单要展示哪些动作取决于当前页面状态
     * （能不能开新标签、当前是不是无痕），那只有 UI 层知道。
     */
    var onLongPress: ((HitTarget) -> Unit)? = null

    /**
     * 用户脚本通过 GM 桥发来的消息（原始 JSON）。
     *
     * 只转发不算账：解析消息、校验脚本 id、落库或开标签都属于 UI 层
     * （见 [com.ling.browser.ui.BrowserViewModel]），web 层不导入 data 层。
     */
    var onUserScriptMessage: ((tabId: String, message: String) -> Unit)? = null

    /**
     * 网页 `<input type="file">` 点击后的文件选择回调（由 UI 层接管）。
     *
     * 参数 1 是 WebView 给出的选择约束（单选 / 多选 / 取目录 / 存盘 + accept 类型），
     * 参数 2 是结果回传：**必须且只能回调一次**，传 null 表示用户取消。
     * 用户取消时也要回调 null —— 否则 WebView 会一直停在"等待选择"状态，
     * 之后该页面再点上传按钮就全部没反应。
     */
    var onShowFileChooser: (
        (params: WebChromeClient.FileChooserParams?, onResult: (Array<Uri>?) -> Unit) -> Unit
    )? = null

    /**
     * 网页 `alert()` 对话框。
     *
     * 由 UI 层接管渲染，用户点击后**必须且只能**调用一次 [JsResult.confirm]。
     * 不传此回调时 WebView 走系统默认 Dialog（标题固定为"JavaScript"，
     * 样式与应用不一致）。
     */
    var onJsAlert: ((url: String, message: String, result: JsResult) -> Unit)? = null

    /** 网页 `confirm()` 对话框。确认走 [JsResult.confirm]，取消/关闭走 [JsResult.cancel]。 */
    var onJsConfirm: ((url: String, message: String, result: JsResult) -> Unit)? = null

    /**
     * 网页 `prompt()` 对话框。
     *
     * 确认时 [JsPromptResult.confirm] 接收用户输入的字符串；取消/关闭走
     * [JsPromptResult.cancel]，页面拿到 null。
     */
    var onJsPrompt: ((url: String, message: String, defaultValue: String, result: JsPromptResult) -> Unit)? = null

    /**
     * 页面 `onbeforeunload` 确认。
     *
     * 语义和 confirm 相同，但文案应提示"离开此页会丢失未保存内容"。用户点
     * 离开才 confirm，留在此页就 cancel。
     */
    var onJsBeforeUnload: ((url: String, message: String, result: JsResult) -> Unit)? = null

    /**
     * 网页请求全屏（HTML5 视频的「全屏」按钮）。
     *
     * 契约：UI 层把 [view] 挂到自己的全屏容器上，并保存 [callback]；
     * 用户退出全屏时调用 `callback.onCustomViewHidden()` 通知 WebView。
     * 不设置时什么都不做 —— 网页的全屏请求被静默忽略，视频只能内联播放。
     */
    var onShowCustomView: ((view: View, callback: WebChromeClient.CustomViewCallback) -> Unit)? = null

    /**
     * 网页主动退出全屏（视频播完、页面自己调了 `exitFullscreen`）。
     *
     * 与 [onShowCustomView] 配对：UI 层据此摘掉全屏容器。
     * 注意此时**不要**再调用 callback，WebView 已经知道全屏结束了。
     */
    var onHideCustomView: (() -> Unit)? = null

    /**
     * HTTP 基础认证（401 + `WWW-Authenticate`）。
     *
     * 不接管时由 [LingWebViewClient] 直接 `cancel()`，页面停在 401；
     * 接管时 UI 层必须 `proceed()` 或 `cancel()` 恰好一次。
     */
    var onHttpAuthRequest: ((host: String, realm: String, handler: HttpAuthHandler) -> Unit)? = null

    /**
     * 网页要求客户端证书（mTLS）。
     *
     * 由 UI 层用系统 KeyChain 选择证书后 `proceed()`；不接管时直接 `cancel()`。
     * 同一个请求只能兑现一次，UI 侧不要在对话框重复回调。
     */
    var onClientCertRequest: ((request: ClientCertRequest) -> Unit)? = null

    /**
     * 网页请求定位（`navigator.geolocation`）。
     *
     * 不接管时按拒绝兑现（`callback.invoke(origin, false, false)`）——
     * callback 不调用，页面会一直等结果。
     */
    var onGeolocationRequest: ((origin: String, callback: GeolocationPermissions.Callback) -> Unit)? = null

    /**
     * 网页请求设备能力（`getUserMedia` 的摄像头/麦克风等）。
     *
     * [resources] 是 [PermissionRequest.resources] 的只读副本，取值见
     * [PermissionRequest.RESOURCE_VIDEO_CAPTURE] 等常量。不接管时 `deny()`。
     */
    var onPermissionRequest: ((
        origin: String,
        resources: List<String>,
        request: PermissionRequest,
    ) -> Unit)? = null

    /** 当前标签页。 */
    val activeTab: TabState?
        get() = _tabs.value.firstOrNull { it.id == _activeId.value }

    fun attachHost(container: FrameLayout) {
        host = container
        // 把当前激活标签的 WebView 挂上去。
        //
        // 用 obtainWebView 而不是查 webViews 缓存：冷启动恢复会话时，
        // 活动标签的 WebView 可能还没被建出来（见 restore 的懒加载说明），
        // 直接查缓存会拿到 null，结果是"标签数据都在、屏幕上却是空白"。
        val active = _activeId.value
        if (active.isEmpty()) return
        val wv = obtainWebView(active)
        (wv.parent as? FrameLayout)?.removeView(wv)
        container.addView(wv)
    }

    fun detachHost(container: FrameLayout) {
        container.removeAllViews()
        if (host === container) host = null
    }

    /**
     * 把输入焦点交还给网页内容。
     *
     * 为什么必须有这个方法：地址栏（Compose 的 `BasicTextField`）拿到焦点后，
     * 光在它自己那边 `clearFocus()` 是**不够**的 —— Compose 释放焦点并不会
     * 让焦点自动回到 WebView 上。结果是焦点悬空，用户点网页里的输入框时
     * **软键盘弹不出来**（真机实测：地址栏输入后回车，之后网页输入框
     * 一直唤不起输入法）。
     *
     * 必须显式 `requestFocus()` 交给宿主 View，并把焦点下沉到网页内部。
     * 三步缺一不可：
     *  1. 宿主要可聚焦（见 BrowserScreen 里 FrameLayout 的 isFocusable）
     *  2. [host] 自己 requestFocus —— 让焦点回到这一层 View 树
     *  3. `evaluateJavascript` 把焦点下沉到网页里的目标元素 ——
     *     View 层拿到焦点不等于网页内的 input 拿到焦点，后者要 JS 帮忙
     */
    fun focusWebContent() {
        val wv = webViews[_activeId.value] ?: return
        val container = host
        // 用 post 推迟到下一帧执行。
        //
        // 原因：本方法是在地址栏 `clearFocus()` 的**同一帧**里被调用的。
        // Android 的焦点变化要等这一帧的 View 树遍历结束才生效，紧接着
        // requestFocus 有可能被丢弃（表现为"偶尔管用、偶尔不管用"）。
        // post 到下一帧时焦点释放已经落定，requestFocus 才是可靠的。
        val run: () -> Unit = {
            runCatching {
                // 返回值刻意不接：真机上实测两者恒为 true
                // （日志 host=true webview=true wvHasFocus=true 已确认），
                // 排障用的 Log.d 在确认修复后已移除。
                container?.requestFocus()
                wv.requestFocus()
                // 把焦点下沉到网页里。View 层拿到焦点**不等于**网页内的
                // input 拿到焦点 —— 后者由 Blink 内部管理，要 JS 帮一把。
                // 不这样做时，用户点输入框仍可能唤不起输入法。
                wv.evaluateJavascript(
                    "(function(){try{" +
                        "var a=document.activeElement;" +
                        "if(a&&(a.tagName==='INPUT'||a.tagName==='TEXTAREA'||a.isContentEditable))return;" +
                        "document.body&&document.body.focus();" +
                        "}catch(e){}})()",
                    null,
                )
            }
            Unit
        }
        if (container != null) container.post(run) else run()
    }

    /** 应用设置；已存在的 WebView 立即生效。 */
    fun applySettings(newSettings: LingSettings) {
        settings = newSettings
        // 自定义规则可能变了，重算查表并集（见 mergedBlockRules）。
        rebuildBlockRules()
        // ⚠️ 必须是 shouldDarkenPages()（forceDarkWebPages && 夜间模式已解析为深色），
        // 不能只用 resolvedDark()：那等于跳过「网页跟随夜间模式暗化」这个开关本身。
        // 后果很直观 —— 关掉这个开关也是一次设置变化，会立刻把网页重新暗化一次，
        // 用户看到的是"关了没用"；之后改任何别的设置（搜索引擎、标签高度…）
        // 都会再暗化一遍。
        val darkTheme = shouldDarkenPages()
        webViews.forEach { (id, wv) ->
            val incognito = _tabs.value.firstOrNull { it.id == id }?.isIncognito ?: false
            wv.applySettings(newSettings, incognito, darkTheme)
        }
    }

    /**
     * 注入订阅名单的域名并集，并重算拦截查表用的并集。
     *
     * 由 [com.ling.browser.ui.BrowserViewModel] 订阅
     * [com.ling.browser.data.repo.BlockListRepository.enabledDomains] 后调用：
     * 导入 / 启停 / 删除 / 更新订阅都会让那个流发新值，这里跟着重算一次，
     * 拦截 lambda 读的 [mergedBlockRules] 因此立即生效，无需重建 WebView。
     */
    fun applyBlockSubscriptions(domains: Set<String>) {
        subscriptionDomains = domains
        rebuildBlockRules()
    }

    /** 重算自定义规则 + 订阅域名的并集（低频调用，见 [mergedBlockRules]）。 */
    private fun rebuildBlockRules() {
        mergedBlockRules =
            if (subscriptionDomains.isEmpty()) {
                // 没有订阅时直接复用设置里那份集合，不复制 —— 绝大多数用户不会导入订阅。
                settings.adBlockRules
            } else {
                settings.adBlockRules + subscriptionDomains
            }
    }

    // ---------------------------------------------------------------- 用户脚本

    /**
     * 注入启用中的用户脚本，并重新注册 document-start。
     *
     * 由 [com.ling.browser.ui.BrowserViewModel] 订阅
     * [com.ling.browser.data.repo.UserScriptRepository.enabledScripts] 后调用：
     * 导入 / 启停 / 删除都会让那个流发新值。
     *
     * 重注册只影响**下一次导航**：当前页已经跑过的脚本不会重跑（否则改一下开关
     * 就会在页面上叠一份副作用）。
     */
    fun applyUserScripts(scripts: List<EnabledUserScript>) = scriptBridge.applyScripts(scripts)

    // ---------------------------------------------------------------- 脚本菜单 / 跨页值同步

    /** 页面里 `GM_registerMenuCommand` 注册一条命令（见 [ScriptBridge.registerMenu]）。 */
    fun registerUserScriptMenu(
        tabId: String,
        scriptId: String,
        commandId: String,
        label: String,
        accessKey: String = "",
    ) = scriptBridge.registerMenu(tabId, scriptId, commandId, label, accessKey)

    /** 页面里 `GM_unregisterMenuCommand` 移除一条命令。 */
    fun unregisterUserScriptMenu(tabId: String, scriptId: String, commandId: String) =
        scriptBridge.unregisterMenu(tabId, scriptId, commandId)

    /** 执行一条菜单命令（见 [ScriptBridge.invokeMenu]）。 */
    fun invokeUserScriptMenu(tabId: String, scriptId: String, commandId: String): Boolean =
        scriptBridge.invokeMenu(tabId, scriptId, commandId)

    /** 把 GM 值的变更广播给**其它**标签页（见 [ScriptBridge.broadcastValue]）。 */
    fun broadcastUserScriptValue(
        scriptId: String,
        key: String,
        oldValue: Any?,
        newValue: Any?,
        exceptTabId: String?,
    ) = scriptBridge.broadcastValue(scriptId, key, oldValue, newValue, exceptTabId)

    /** 通知点击回传页面里的 `onclick`（详情见 [com.ling.browser.ui.UserScriptCoordinator.postNotification]）。 */
    fun dispatchUserScriptNotificationClick(tabId: String, scriptId: String, notificationId: String) =
        scriptBridge.dispatchNotificationClick(tabId, scriptId, notificationId)

    /** 通知被点击后回传 `ondone`（细节见 [ScriptBridge.dispatchNotificationDone]）。 */
    fun dispatchUserScriptNotificationDone(tabId: String, scriptId: String, notificationId: String) =
        scriptBridge.dispatchNotificationDone(tabId, scriptId, notificationId)

    /** 命中一次拦截 +1。由后台线程经 [LingWebViewClient] 的回调调用（见 [blockedCount]）。 */
    private fun countBlocked() {
        _blockedCount.update { it + 1 }
    }

    /** 清零拦截计数。 */
    fun resetBlockedCount() {
        _blockedCount.value = 0
    }

    /**
     * 夜间模式是否已解析为深色。
     *
     * 这是"界面 + 网页内容"共用的唯一真相源：跟随系统时读系统 uiMode，
     * 强制开/关直接返回。Web 层用它来决定是否暗化网页，与 Compose 侧
     * `isSystemInDarkTheme()` 语义一致，保证两边同时切换。
     */
    private fun resolvedDark(): Boolean = when (settings.nightMode) {
        NightMode.FOLLOW_SYSTEM -> isSystemDark()
        NightMode.ALWAYS_ON -> true
        NightMode.ALWAYS_OFF -> false
    }

    /** 系统当前是否处于深色模式（读资源配置，与 Compose isSystemInDarkTheme 同源）。 */
    private fun isSystemDark(): Boolean {
        val mask = context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return mask == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    /**
     * 网页内容是否应暗化：`forceDarkWebPages`（意图开关）与夜间模式
     * 是否已解析为深色，两者同时成立。
     */
    private fun shouldDarkenPages(): Boolean =
        settings.forceDarkWebPages && resolvedDark()

    // ---------------------------------------------------------------- 标签页操作

    /**
     * 新建标签页并切换过去。
     *
     * 不传 URL 时落在主页：优先用户自定义的主页，否则是「翎」的内置主页。
     */
    fun newTab(
        url: String? = null,
        incognito: Boolean = settings.incognito,
        select: Boolean = true,
        /**
         * 插入位置（0 = 最前，null = 末尾）。
         *
         * `GM_openInTab` 的 `insert` 选项要能插到当前标签之后，而默认追加到末尾
         * 会让"从这一页开出去"的子标签跑到最边上。
         */
        position: Int? = null,
    ): TabState {
        // 安装链接不能作为标签内容：新标签落在主页，随后弹安装确认。
        val scriptUrl = url?.takeIf { isUserScriptUrl(it) }
        val target = if (scriptUrl != null) settings.homepage else url ?: settings.homepage
        val tab = TabState(url = target, isIncognito = incognito)
        _tabs.update { list ->
            if (position == null) {
                list + tab
            } else {
                list.toMutableList().apply {
                    add(position.coerceIn(0, size), tab)
                }
            }
        }
        // select=false 用于 GM_openInTab 的后台打开：建标签、开始加载，但不切过去。
        if (select) switchTo(tab.id)
        // 主页已由 switchTo → loadHome 装好占位，不能再走一次 loadUrl：
        // 两次紧邻的 about:blank 请求会在地址回写前互相覆盖，白白多一次导航。
        // 后台打开（select=false）没有经过 switchTo，仍交给 loadUrl 建实例并装占位。
        if (!select || !HomePage.isHomeUrl(target)) loadUrl(tab.id, target)
        scriptUrl?.let { onUserScriptLink?.invoke(it) }
        return tab
    }

    /** 导出当前标签用于持久化。无痕标签由 [TabSnapshot.isPersistable] 过滤掉。 */
    fun snapshots(): List<TabSnapshot> =
        // 带上阅读视图的原文地址：快照里存合成地址的话，重启恢复只会得到错误页
        _tabs.value.mapIndexed { i, tab -> tab.toSnapshot(i, readerOriginalUrl(tab.id)) }
            .filter { it.isPersistable }

    /**
     * 用上次保存的快照重建标签页。返回是否真的恢复了内容。
     *
     * 只恢复**第一个**标签对应的 WebView，其余等用户切过去时再懒加载
     * （[obtainWebView] 本来就是这个机制）。一次性把 N 个 WebView 全建出来
     * 会让冷启动明显变卡，而用户通常只会马上看第一个。
     *
     * 调用方需保证此时 [_tabs] 为空 —— 否则新旧标签会混在一起。
     */
    fun restore(snapshots: List<TabSnapshot>): Boolean {
        val valid = snapshots.filter { it.isPersistable }
        if (valid.isEmpty()) return false
        if (_tabs.value.isNotEmpty()) return false

        val restored = valid.map { snap ->
            TabState(
                // 复用快照里的 id，让"同一个标签"在重启前后保持一致；
                // 若 id 已被占用（理论上不会），退回到新生成的 id。
                id = snap.id,
                url = snap.url,
                title = snap.title,
                isIncognito = false,
            )
        }
        _tabs.value = restored

        val first = restored.first()
        // 加载内容。主页要交给内置渲染器，普通地址才走 WebView.loadUrl ——
        // 这条判断在 loadUrl 内部，这里直接复用即可。
        loadUrl(first.id, first.url)
        // 再切到这个标签：switchTo 会负责把 WebView 挂到宿主上，
        // 且能正确处理"宿主尚未 attach"的情况（那时它只建 WebView 不挂载，
        // 等 attachHost 被调用时会自动补挂）。
        switchTo(first.id)
        return true
    }

    /** 关闭标签页；关掉最后一个时自动新建一个空白页。 */
    fun closeTab(id: String) {
        val closing = _tabs.value.firstOrNull { it.id == id }
        // 先通知上层：脚本的 onclose 回调必须在标签簿记被清掉前找到来源信息。
        if (closing != null) onTabClosed?.invoke(id)
        val remaining = _tabs.value.filterNot { it.id == id }
        webViews.remove(id)?.let { wv ->
            // 与 enforceCacheLimit 同理：句柄必须先摘，再销毁实例。
            // 虽然下面会把整份 session 丢掉，但 destroy() 之后、sessions.remove()
            // 之前的这段窗口里，任何一次 registerDocumentStart 都可能踩到失效句柄。
            scriptBridge.releaseRendererHandles(id)
            (wv.parent as? FrameLayout)?.removeView(wv)
            wv.stopLoading()
            wv.loadUrl("about:blank")
            wv.destroy()
        }
        // 该标签的附属簿记随标签一起丢弃：脚本句柄跟着 WebView 已销毁，
        // 注入记录留着只会让同名 tabId 复用时误判"这页已经跑过"。
        sessions.remove(id)
        scriptBridge.publishMenus()
        // 控制台记录随 session 一起没了，但已发布的是快照，需要重算一次。
        publishConsoleErrors()
        // 该标签的待裁决 Safe Browsing 与控制台记录同样随标签一起清掉。
        safePrompts.cancelFor(id)
        clearConsoleErrors(id)

        // 回收 favicon 的像素内存。必须在移出列表后立刻做：
        // Bitmap 的像素是非托管内存，GC 只收得回 Java 对象壳，
        // 不等 recycle 就可能攒到 OOM。
        recycleTab(closing)

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

    /**
     * 释放标签持有的图像资源。
     *
     * 用 isRecycled 守卫：同一个 Bitmap 可能因为
     * `_tabs.update { it.copy(...) }` 被多个 TabState **共享**
     * （copy 不会复制 Bitmap，只复制引用），重复 recycle 会抛
     * IllegalStateException 或让其它仍在显示的标签变成空白。
     */
    private fun recycleTab(tab: TabState?) {
        tab?.favicon?.takeIf { !it.isRecycled }?.recycle()
        // 一并丢掉缓存的正文，否则关掉的标签会一直把它压在内存里
        tab?.let { sessions[it.id]?.readerContent = null }
        // 证书放行白名单同样随手清掉：逃生口不该比标签活得久
        tab?.let { sessions[it.id]?.certExceptions?.clear() }
        // 还有未裁决的证书错误也一并取消：标签都没了，没人会再回答弹窗。
        tab?.let { certPrompts.cancelFor(it.id) }
        // 表单重提交同理：不兑现会让该 WebView 的加载一直挂着。
        tab?.let { cancelFormResubmitsFor(it.id) }
    }

    /**
     * 把站点图标写入标签状态。两个来源共用：
     *   - `WebChromeClient.onReceivedIcon`
     *   - [LingWebViewClient] 的 `onPageStarted(view, url, favicon)`
     *
     * 后者的 favicon 参数此前被直接丢弃，是缩略图一直空着的真正原因 ——
     * 只接 WebChromeClient 不够，很多站点要到解析 `<link rel="icon">`
     * 之后才触发那个回调，时序上晚得多。
     *
     * icon 为 null 时**保留原图标**而不是清空：同页锚点跳转、iframe 加载
     * 都可能再触发一次不带 icon 的回调，清空会让图标闪一下退回字母占位。
     */
    private fun applyFavicon(id: String, icon: Bitmap?) {
        if (icon == null || icon.isRecycled) return
        updateTab(id) { tab ->
            // 已经就是同一个对象就什么都不做：
            // 两个回调经常对同一张图各触发一次，不拦会导致
            // 「先 recycle 掉自己、再把自己存回去」的自毁行为。
            if (tab.favicon === icon) return@updateTab tab
            // 换新图前回收旧的，否则反复导航会持续堆积像素内存
            tab.favicon?.takeIf { !it.isRecycled }?.recycle()
            tab.copy(favicon = icon)
        }
    }

    /** 关闭除 [keepId] 外的全部标签页。 */
    fun closeOthers(keepId: String) {
        // 先取快照再遍历：closeTab 会改 _tabs，直接遍历 live 列表会漏关。
        _tabs.value.filter { it.id != keepId }.map { it.id }.forEach { closeTab(it) }
    }

    /** 切换激活标签。 */
    fun switchTo(id: String) {
        if (_activeId.value == id && webViews[id] != null) return
        val recreated = webViews[id] == null
        _activeId.value = id
        val wv = obtainWebView(id)
        host?.let { h ->
            h.removeAllViews()
            (wv.parent as? FrameLayout)?.removeView(wv)
            h.addView(wv)
        }
        // 回收后重建的实例没有文档（纯白底）。统一走 loadUrl 重新加载标签
        // 自己的地址 —— 它内部已分派主页 / 自定义协议 / 阅读视图 / .user.js。
        //
        // ⚠️ 若该实例刚从 saveState 恢复过历史栈（见 obtainWebView），
        // 则**不能再 loadUrl**：那会把恢复出来的栈顶掉，返回键照样回不去。
        if (recreated && !restoredFromSnapshot) {
            val restoredUrl = _tabs.value.firstOrNull { it.id == id }?.url
            if (!restoredUrl.isNullOrBlank()) {
                loadUrl(id, restoredUrl)
            }
        }
        syncNavState(id)
        // CookieManager 是**进程级**单例：切标签后必须把全局策略对齐到新的激活标签，
        // 否则会把上一个标签（尤其是无痕）的策略留在全局 —— 见 LingWebView.applyCookiePolicy。
        syncCookiePolicy(id)
    }

    /**
     * 让全局 Cookie 策略跟着当前激活标签走。
     *
     * CookieManager 没有"按 WebView 隔离"这回事，因此只能维护"当前可见的标签
     * 决定全局策略"这一条不变量。两个调用时机：
     *   - 创建 WebView 时（且该标签就是激活标签，见 [obtainWebView]）；
     *   - 切换标签时（见 [switchTo]）。
     */
    private fun syncCookiePolicy(id: String) {
        val wv = webViews[id] ?: return
        val incognito = _tabs.value.firstOrNull { it.id == id }?.isIncognito ?: false
        wv.applyCookiePolicy(incognito)
    }

    /**
     * 把 [from] 位置的标签移动到 [to] 位置（标签列表拖拽排序用）。
     *
     * 语义是 **remove + add**，不是 swap：`[A,B,C,D].move(0,2)` 得到 `[B,C,A,D]`
     * —— 被拖的那一项落到目标下标，其余项依次让位。这与拖拽的直觉一致。
     *
     * 越界入参直接忽略（不抛异常）：拖拽时手指可能划出列表边界，
     * 这里必须容错，否则一次越界手势就会崩。
     *
     * 关于下标补偿：Kotlin 会**先求值全部实参**再调用 add，所以
     * `add(to, removeAt(from))` 里的 removeAt 已经生效、列表已经变短，
     * 此时 to 就是正确的最终下标，**不需要**任何 +1/-1 补偿。
     * （曾以为 from < to 时要补偿，实际验证 [A,B,C,D].move(0,3) 得到
     *   [B,C,D,A]，A 正确落在末尾 —— 补偿反而会错位。）
     */
    fun moveTab(from: Int, to: Int) {
        _tabs.update { list ->
            if (from !in list.indices || to !in list.indices) return@update list
            if (from == to) return@update list
            list.toMutableList().apply { add(to, removeAt(from)) }
        }
    }

    // ---------------------------------------------------------------- 导航操作

    fun loadUrl(id: String, url: String) {
        // `.user.js` 安装链接不加载：原始 JS 当网页显示没有意义，交给 UI 层确认安装。
        // 放在最前面：既不改 TabState，也不创建 WebView（新标签已在 [newTab] 落到主页）。
        if (isUserScriptUrl(url)) {
            onUserScriptLink?.invoke(url)
            return
        }
        val wv = obtainWebView(id)
        // 内置主页由 Compose 渲染，WebView 只留一个空白占位（见 [loadHome]）。
        if (HomePage.isHomeUrl(url)) {
            loadHome(id)
            return
        }
        // 自定义协议（zhihu://、weixin:// 等）不能交给 WebView：
        // 它不认识这些 scheme，会直接抛 net::ERR_UNKNOWN_URL_SCHEME 白屏。
        // 这类地址只应由系统应用接管（shouldOverrideUrlLoading 里已经这么做），
        // 但**地址仍可能被动地流到这里** —— 例如页面用自定义协议导航后
        // TabState.url 记下了它，用户退出阅读模式时又被回填。
        // 这里做最后一道防线：直接忽略，保持当前页面不动。
        if (!isNavigable(url)) {
            updateTab(id) { it.copy(isLoading = false, errorText = null) }
            return
        }
        // 阅读视图的逻辑地址是**合成的**：只能靠"正文缓存 + 重新渲染"得到，
        // 而缓存不落盘。旧版本把它当普通地址交给 WebView，结果是
        // ERR_UNKNOWN_URL_SCHEME 错误页 —— 上个标签停在阅读模式时，重启就会看到它。
        // 快照现在改存原文地址了（见 snapshots()），这里再兜一道：回主页。
        if (ReaderPage.isReaderUrl(url)) {
            loadHome(id)
            return
        }
        // 普通网页恢复 WebView 自身的不透明底（白色，与默认一致）：
        // 主页那层透明底是主页专用的（见 [loadHome]），若带到普通网页上，
        // 页面自身的透明区域会露出下面的 Compose 背景，观感不对。
        // 同时摘掉"主页占位文档"标记，夜间兜底 CSS 重新对它生效。
        wv.setOpaqueBackground(true)
        wv.isHomePlaceholderDocument = false
        updateTab(id) {
            it.copy(
                url = url,
                // 显式导航：安全状态回到按协议判定的基础态（混合内容 / 证书错误
                // 会在随后的加载回调里升级）。
                security = SecurityState.baseForUrl(url),
                errorText = null,
                isLoading = true,
                progress = 0,
            )
        }
        wv.loadUrl(url)
    }

    /**
     * 该地址能否交给 WebView 加载。
     *
     * 判定逻辑在 [UrlScheme] 里 —— 抽出去是为了能跑单元测试：
     * `WebTabManager` 依赖 WebView/Context，构造不出来，
     * 留在这里这段关键防线就等于没测。
     */
    private fun isNavigable(url: String): Boolean = UrlScheme.isNavigable(url)

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
    fun enterReaderMode(onResult: (ReaderResult) -> Unit = {}) =
        reader.enterReaderMode(onResult)

    /**
     * 嗅探当前页面的媒体资源（视频/音频/图片/可下载文件）。
     *
     * 与阅读模式同一套注入套路：`evaluateJavascript` 跑 [ResourceSnifferJs.SCRIPT]，
     * 回调里解析结构化结果。三个边界与 enterReaderMode 相同：
     *   - JS 关闭 → Error("js_disabled")，用户能自己去打开
     *   - 页面未加载完 → Error("page_loading")，提示稍候
     *   - 回调回来时用户可能已切走 → 丢弃（不校验会把 A 页结果挂到 B 页）
     *
     * 与阅读模式不同：嗅探**不改变当前页面**，只是读 DOM 拿结果，
     * 因此不需要缓存、不需要"退出"路径，结果直接交给 UI 展示。
     */
    fun sniffResources(onResult: (SniffResult) -> Unit = {}) {
        val id = _activeId.value
        val wv = webViews[id] ?: return
        val tab = _tabs.value.firstOrNull { it.id == id } ?: return

        // 内部页没有可嗅探的第三方媒体资源。与阅读模式同一判据：
        // [InternalPage.isInternal] 连 reload 后的 data: 形式也覆盖。
        if (InternalPage.isInternal(wv.url, logicalUrlOf(id))) {
            onResult(SniffResult.Error("internal_page"))
            return
        }
        if (!settings.javaScriptEnabled) {
            onResult(SniffResult.Error("js_disabled"))
            return
        }
        if (tab.isLoading || wv.progress < 100) {
            onResult(SniffResult.Error("page_loading"))
            return
        }

        wv.evaluateJavascript(ResourceSnifferJs.SCRIPT) { raw ->
            if (_activeId.value != id) return@evaluateJavascript
            onResult(SniffResult.parse(raw))
        }
    }

    /**
     * 原地调整阅读视图的正文大小。
     *
     * 实现见 [ReaderController.refreshReaderFontSize]：只改 CSS 变量，不重渲染。
     */
    fun refreshReaderFontSize(fontSize: ReaderPage.FontSize) =
        reader.refreshReaderFontSize(fontSize)

    /** 退出阅读模式：回到原文。 */
    fun exitReaderMode() = reader.exitReaderMode()

    /** 当前标签是否处于阅读视图。 */
    fun isReaderActive(): Boolean = reader.isReaderActive()

    /**
     * 阅读视图对应的**原文地址**（不在阅读模式时为 null）。
     *
     * 地址栏需要它：阅读视图的逻辑地址是合成的 `ling://reader`，
     * 既不能复制也没法改，拿去提交还会让 WebView 报 ERR_UNKNOWN_URL_SCHEME；
     * 进入编辑态时应该给出能用的真实地址（来源就是提取时缓存下来的 canonical）。
     */
    fun readerOriginalUrl(id: String = _activeId.value): String? = reader.readerOriginalUrl(id)

    /**
     * 当前标签的逻辑地址（[TabState.url]）。
     *
     * 判定"WebView 现在这一页是哪种内部页"时要靠它：WebView 对内部页 reload
     * 后回写的 `data:` 地址本身分不出主页还是阅读视图（见 [InternalPage]）。
     */
    private fun logicalUrlOf(id: String): String? =
        _tabs.value.firstOrNull { it.id == id }?.url

    /**
     * 这个标签里的 `about:blank` 是不是"主页占位"。
     *
     * about:blank 本身没有身份：`window.open` 的占位文档、页面自己跳到 blank
     * 都会出现它。只有装过主页占位的标签，才把 blank 解释成主页 —— 否则从
     * 第一个网页按返回时，状态会被写成 about:blank，主页不显示、地址栏还留着旧网址。
     */
    private fun isHomePlaceholder(id: String, url: String?): Boolean =
        url?.trim() == HOME_PLACEHOLDER_URL && sessions[id]?.isHomePlaceholder == true

    /**
     * 进入主页：状态切成主页，WebView 里只留一个空白占位文档。
     *
     * 主页由 Compose 渲染（见 `ui/screens/HomeScreen.kt`），WebView 不再承载它。
     * 但仍然装一个 about:blank：
     *  - 让历史里有"主页"这一帧 —— 从第一个网页按返回能回到主页，与旧行为一致；
     *  - **换掉上一页的文档**，停掉它的媒体播放与定时器。Compose 层只是盖住
     *    WebView，不换文档的话网页的声音会继续响。
     *
     * 只在第一次进入主页时装：反复点"主页"按钮不该往历史里堆空白条目。
     */
    private fun loadHome(id: String) {
        updateTab(id) {
            it.copy(
                url = HomePage.URL,
                title = "主页",
                errorText = null,
                isLoading = false,
                progress = 100,
                // 主页是历史起点：停在主页时返回键始终不可用。
                canGoBack = false,
                canGoForward = false,
            )
        }
        val wv = webViews[id] ?: return
        // 先打上"这是主页占位"的标记（无条件打）：about:blank 没有地址身份，
        // 夜间兜底 CSS 只能靠它拦（见 LingWebView.injectDarkMode）。
        // 放在早退之前：万一标记在某个时刻被清掉，这次回主页也要补回来。
        wv.isHomePlaceholderDocument = true
        // 已经在主页占位上（反复点「主页」）：什么都不做，别往历史里再压一条。
        // 判据必须看 WebView 的实时地址，而不是 session 里的 isHomePlaceholder：
        // 从主页打开网页后同一个标签的标记仍在，但这时再点「主页」必须真的回去。
        if (isHomePlaceholder(id, wv.url)) return
        session(id).isHomePlaceholder = true
        wv.stopLoading()
        // WebView 自身的底改透明：主页前景是 Compose，背景由原生背景层在
        // WebView 之下供色（见 HomeBackgroundLayer），白底会把背景整个盖住。
        // 普通网页在 [loadUrl] 里会重新设回不透明白底，因此这里每次回到主页都要重设。
        wv.setOpaqueBackground(false)
        wv.loadUrl(HOME_PLACEHOLDER_URL)
    }

    /**
     * 内部页（目前只剩阅读视图）的配色。
     *
     * 主页已改为原生 Compose 渲染，直接用 `MaterialTheme`；这里保留一份
     * 十六进制值，是因为阅读视图仍是 `loadDataWithBaseURL` 的 HTML 文档，
     * 主题色只能"原生算好 → 注入 CSS 变量"。
     */
    data class InternalColors(
        val background: String = "#FFFBFE",
        val onBackground: String = "#1C1B1F",
        val primary: String = "#1B6C4B",
        val onPrimary: String = "#FFFFFF",
        val primaryContainer: String = "#A8F2CB",
        val onPrimaryContainer: String = "#00210F",
        val dark: Boolean = false,
    )

    /**
     * 由 UI 层在主题变化时写入，见 [applyInternalColors]。
     *
     * ⚠️ 必须在**创建阅读视图文档之前**已是正确值：冷启动时若还是默认浅色，
     * 首屏阅读视图就会用错配色。
     */
    var internalColors: InternalColors = InternalColors()
        private set

    /**
     * 同步内部页配色，并给正在显示的阅读视图补一次色。
     *
     * 阅读视图的颜色烘在 HTML 里，文档重建（reload / 渲染进程回收 / 历史重放）
     * 都会停在烘死时的配色；原地改 CSS 变量既不动滚动位置，也不压历史。
     */
    fun applyInternalColors(colors: InternalColors) {
        internalColors = colors
        _tabs.value.filter { ReaderPage.isReaderUrl(it.url) }.forEach { tab ->
            val wv = webViews[tab.id] ?: return@forEach
            // 判据同 isReaderDocument：reload 后 wv.url 是 data: 形式，
            // 只看 baseUrl 会漏判，切夜间模式时配色停在不更新。
            if (InternalPage.isReaderDocument(wv.url, tab.url)) {
                wv.evaluateJavascript(reader.themeJs(), null)
            }
        }
    }

    /**
     * 在指定标签页的页面主世界执行一段 JS。
     *
     * 用于把 `GM_xmlhttpRequest` 的原生结果回传给脚本。**必须在 UI 线程调用**，
     * 且只做"投递"：标签已关闭或实例被 LRU 回收时静默忽略，调用方不需要
     * 区分这些情况 —— 请求结果本来就没有人接收了。
     */
    fun evalInTab(id: String, js: String) {
        webViews[id]?.evaluateJavascript(js, null)
    }

    fun reload(id: String = _activeId.value) {
        // 主页由 Compose 渲染，没有可刷新的文档：刷新按钮停在主页上时不做事。
        val tab = _tabs.value.firstOrNull { it.id == id }
        if (HomePage.isHomeUrl(tab?.url)) return
        obtainWebView(id).reload()
    }

    /**
     * 放行当前标签页的证书错误并重新导航（证书错误页的「继续访问」）。
     *
     * 只把**当前主机**加入本标签的临时白名单，然后重走一次导航：这一次
     * [LingWebViewClient.onReceivedSslError] 上报后，[CertPromptQueue.handle] 会发现
     * 白名单命中而直接 `proceed()`，不再弹窗。
     *
     * 用 [loadUrl] 而不是 `wv.reload()`：证书握手失败后 WebView 停在错误态，
     * `reload()` 未必会重新发起网络请求，显式导航更可靠。
     */
    fun proceedInsecure(id: String = _activeId.value) {
        val tab = _tabs.value.firstOrNull { it.id == id } ?: return
        val host = runCatching { Uri.parse(tab.url).host }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: return
        session(id).certExceptions.add(host)
        loadUrl(id, tab.url)
    }

    /**
     * 用户对待裁决证书错误的选择（见 [CertPromptQueue.resolve]）。
     *
     * [remember] 对应 Via 的「本次浏览过程中不再询问」：把主机加入本标签的
     * 白名单，之后同主机不再弹窗（关标签失效，不落盘）。
     */
    fun resolveCertPrompt(proceed: Boolean, remember: Boolean) = certPrompts.resolve(proceed, remember)

    /**
     * 把队列里所有待裁决项按拒绝处理并关掉弹窗。
     *
     * 用于 UI 销毁：这些 handler 已经没人能回答，留着会让 TLS 握手永远悬空
     * （页面停在半加载状态）。
     */
    fun cancelCertPrompts() = certPrompts.cancelAll()

    /** 主框架被拒绝：落证书错误页，而不是留一句泛化的加载失败。 */
    private fun markCertBlocked(tabId: String, reason: String) {
        updateTab(tabId) {
            it.copy(
                isLoading = false,
                security = SecurityState.INSECURE_CERT,
                errorText = "$reason，已阻断连接",
            )
        }
    }

    // ---------------------------------------------------------------- Safe Browsing

    /** 用户对 Safe Browsing 警告的选择：true = 继续访问，false = 返回安全页。 */
    fun resolveSafeBrowsing(proceed: Boolean) = safePrompts.resolve(proceed)

    /** UI 销毁时把待裁决项全部按「返回安全页」兑现，避免 callback 悬空。 */
    fun cancelSafeBrowsing() = safePrompts.cancelAll()

    // ---------------------------------------------------------------- 页面控制台错误

    /**
     * 记录一条 ERROR 级控制台消息。
     *
     * 只做**内存**留存（每页最多 [MAX_CONSOLE_ERRORS] 条），新页导航即清空：
     * 它用于让用户看见"页面/脚本报了什么错"，不是调试器，不需要持久化。
     */
    private fun recordConsoleError(tabId: String, message: String, source: String, line: Int) {
        if (message.isBlank()) return
        val list = session(tabId).console
        val last = list.lastOrNull()
        // 同一个循环里反复报同一条错误时不要刷屏。
        if (last != null && last.message == message && last.line == line) return
        list.add(ConsoleEntry(tabId, message.take(MAX_CONSOLE_MESSAGE), source, line))
        while (list.size > MAX_CONSOLE_ERRORS) list.removeAt(0)
        publishConsoleErrors()
    }

    /** 清空某标签页的控制台错误（主框架导航 / 关闭标签时调用）。 */
    fun clearConsoleErrors(tabId: String) {
        // 只清记录，不丢整份 session —— 该标签的其它簿记还要用。
        val ses = sessions[tabId] ?: return
        if (ses.console.isEmpty()) return
        ses.console.clear()
        publishConsoleErrors()
    }

    private fun publishConsoleErrors() {
        _consoleErrors.value = sessions.values.flatMap { it.console }
    }

    /**
     * POST 结果页刷新：交给 UI 决定是否重新提交。
     *
     * `onFormResubmission` 给的两个 [Message] 是二选一的开关，必须发且只发一个。
     * 与证书错误一样排队：同一时刻只弹一个，UI 还没接线时按"不重新提交"处理 ——
     * 否则消息永远不发，刷新会停在无响应状态。
     */
    private fun handleFormResubmission(tabId: String, resend: Message, dontResend: Message) {
        if (formQueue.size >= MAX_PENDING_FORMS) {
            dontResend.sendToTarget()
            return
        }
        val host = runCatching {
            Uri.parse(_tabs.value.firstOrNull { it.id == tabId }?.url.orEmpty()).host
        }.getOrNull().orEmpty()
        formQueue.addLast(
            PendingFormResubmit(
                tabId = tabId,
                prompt = FormResubmitPrompt(host.ifBlank { "此页面" }),
                resend = resend,
                dontResend = dontResend,
            ),
        )
        // 弹窗只在 UI 线程上操作；onFormResubmission 本身就在 UI 线程回调。
        showNextForm()
    }

    /** 用户对表单重提交的选择：true = 重新提交，false = 放弃。 */
    fun resolveFormResubmit(resend: Boolean) {
        val item = formShown ?: return
        formShown = null
        if (resend) item.resend.sendToTarget() else item.dontResend.sendToTarget()
        showNextForm()
    }

    /**
     * UI 销毁时把待裁决的表单重提交全部按"不重新提交"兑现。
     *
     * 不兑现就会让 WebView 的加载一直等着，页面永远停在刷新前的状态。
     */
    fun cancelFormResubmits() {
        formShown?.let { it.dontResend.sendToTarget() }
        formShown = null
        while (true) {
            val item = formQueue.removeFirstOrNull() ?: break
            item.dontResend.sendToTarget()
        }
        onFormResubmitPrompt?.invoke(null)
    }

    /** 关标签时清理该标签的待裁决项。 */
    private fun cancelFormResubmitsFor(tabId: String) {
        formShown?.takeIf { it.tabId == tabId }?.let {
            it.dontResend.sendToTarget()
            formShown = null
            showNextForm()
        }
        val iterator = formQueue.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            if (item.tabId == tabId) {
                item.dontResend.sendToTarget()
                iterator.remove()
            }
        }
    }

    /** 队列里挑一条展示；全部处理完时向 UI 发 null 关掉弹窗。 */
    private fun showNextForm() {
        if (formShown == null) {
            while (true) {
                val next = formQueue.removeFirstOrNull() ?: break
                if (onFormResubmitPrompt == null) {
                    next.dontResend.sendToTarget()
                    continue
                }
                formShown = next
                break
            }
        }
        onFormResubmitPrompt?.invoke(formShown?.prompt)
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

    /**
     * 清空 Cookie 与站点数据（localStorage、IndexedDB 等）。
     *
     * 与 [clearCacheData] 分开：用户可能只想退出登录态，不想把缓存也清掉
     * （缓存清掉后下次打开同一页面要重新下载全部资源）。
     */
    fun clearCookiesAndSiteData() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
    }

    /** 清空所有标签页的网页缓存（含磁盘缓存）。 */
    fun clearCacheData() {
        webViews.values.forEach { it.clearCache(true) }
    }

    /**
     * 清空 WebView 自己的前进/后退历史与表单记忆。
     *
     * 这是「历史记录」的一部分：库里的访问记录清了，页面里还能按返回键
     * 走回上一页就说不通。
     */
    fun clearWebViewNavigationHistory() {
        webViews.values.forEach {
            it.clearHistory()
            it.clearFormData()
        }
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
        // 复位：这个标记只描述"本次新建"，命中缓存的分支不该留下上一次的残留值。
        restoredFromSnapshot = false

        val wv = LingWebView(context)
        // 先建实例再配置（而不是 LingWebView(context).apply { ... }）：
        // 下面的回调 lambda 会在 apply 作用域**之外**执行，若没有这个名字，
        // 回调里想调 wv 自己的方法（如 injectDarkMode）就没有接收者可用。
        wv.apply {
            // 让 WebView 自身可聚焦：地址栏交出焦点后要把焦点还给网页，
            // 不可聚焦的 View 调 requestFocus() 是无效的。
            //
            // 刻意**不加** setOnFocusChangeListener -> focusWebContent：
            // 那样会在 focusWebContent 里 requestFocus 时再次触发监听器，
            // 形成无限递归。焦点归还由调用方显式触发（见 focusWebContent）。
            isFocusable = true
            isFocusableInTouchMode = true
            // 触摸网页时自动取回焦点。
            //
            // 这是 focusWebContent 之外的第二道保险：用户完全可能用别的方式
            // 把焦点弄丢（切标签、系统弹窗、返回手势……），只要他手指点在
            // 网页上，就说明意图是"跟网页交互"，此时必须有焦点，
            // 否则点输入框依然唤不起输入法。
            //
            // 用 setOnTouchListener **只观察不消费**（返回 false），
            // 事件照常传给 WebView，不影响滚动与点击。
            setOnTouchListener { v, _ ->
                if (!v.isFocused) v.requestFocus()
                false
            }
            // 长按链接/图片 → 上下文菜单。命中识别在 LingWebView 内部完成
            // （它要跑自己的手势检测器），这里只把结果转给 UI 层。
            //
            // 转发目标必须写成 this@WebTabManager：lambda 里裸写 onLongPress
            // 会解析成 LingWebView 自己的同名字段 —— 等于让回调调用自己，
            // 弹出菜单时栈溢出。
            onLongPress = { target ->
                this@WebTabManager.onLongPress?.invoke(target)
            }
            // 注意：在 LingWebView 的 apply 作用域里，裸写 `settings` 会解析成
            // WebView.settings（WebSettings），必须显式限定到本类的字段。
            applySettings(this@WebTabManager.settings, incognito, this@WebTabManager.shouldDarkenPages())
            // 无痕的清理只做**一次**（放在 applySettings 里会在每次设置变化时清空它的历史）
            if (incognito) resetForIncognito()

            // GM 桥：GM_setValue / GM_openInTab 的唯一出口。
            //
            // 白名单用 `*`：脚本本身可以匹配任意站点，桥无法按脚本逐条收敛；
            // 真正的门禁在消息处理端 —— 脚本 id 必须存在且处于启用状态，
            // 写入只会进该脚本自己的值文件（见 UserScriptRepository.setValue）。
            // 无痕标签不挂桥：与"无痕不注入用户脚本"保持一致。
            if (!incognito) {
                runCatching {
                    if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                        WebViewCompat.addWebMessageListener(
                            wv,
                            USER_SCRIPT_BRIDGE,
                            setOf("*"),
                        ) { _, message, _, _, _ ->
                            this@WebTabManager.onUserScriptMessage?.invoke(id, message.data.orEmpty())
                        }
                    }
                }
            }

            // 浏览器级"新标签"桥：页面里 `target="_blank"` 的点击经它上报。
            //
            // 为什么不靠 WebChromeClient.onCreateWindow：那条路要求宿主提供
            // 一个临时 WebView 接住弹窗，而实测在部分机型/WebView 版本上，
            // 即使把临时 WebView 挂进窗口也不会启动导航，回调永远不来，
            // 用户看到的就是"点了没反应"。改在页面里拦截点击更可靠。
            // 无痕标签也要挂：这是纯导航能力，不产生任何持久化数据。
            runCatching {
                if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                    WebViewCompat.addWebMessageListener(
                        wv,
                        TAB_BRIDGE_NAME,
                        setOf("*"),
                    ) { _, message, _, _, _ ->
                        this@WebTabManager.onTabLinkClick(id, message.data.orEmpty())
                    }
                }
            }

            // document-start 的注册随之丢失，必须重新挂上。
            scriptBridge.registerDocumentStart(id, wv)
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    updateTab(id) { it.copy(progress = newProgress) }
                }

                override fun onReceivedTitle(view: WebView?, title: String?) {
                    // 主页标题固定为「主页」：占位文档是 about:blank，标题会被清空，
                    // 这里不覆盖状态里的语义标题。阅读视图的标题已是文章标题，放行。
                    val isHomePage = isHomePlaceholder(id, view?.url) ||
                        InternalPage.isHomeDocument(view?.url, logicalUrlOf(id))
                    title?.takeIf { !isHomePage }?.let { t ->
                        updateTab(id) { it.copy(title = t) }
                    }
                }

                override fun onReceivedIcon(view: WebView?, icon: Bitmap?) {
                    // 来源之一：WebChromeClient。另一个来源是
                    // LingWebViewClient.onPageStarted 的 favicon 参数 ——
                    // 两者都汇到 applyFavicon，保证更新逻辑只有一份。
                    applyFavicon(id, icon)
                }

                override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                    val m = msg ?: return false
                    // 只留 ERROR：警告/日志量太大，对用户没有可读性。
                    if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                        recordConsoleError(id, m.message().orEmpty(), m.sourceId().orEmpty(), m.lineNumber())
                    }
                    // 返回 false 让 WebView 继续写入 logcat，方便开发时排查。
                    return false
                }

                override fun onShowFileChooser(
                    view: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?,
                ): Boolean {
                    // 网页点了 <input type="file">。
                    //
                    // 必须返回 true 表示"已受理"，但它同时意味着 WebView 会
                    // **一直等待** onReceiveValue 被调用 —— 所以下面任何时候
                    // 都要把回调兑现（没有 UI 接管或 callback 缺失时则回 false，
                    // 让 WebView 走默认行为，而不是留下一个永不兑现的等待）。
                    val callback = filePathCallback ?: return false
                    // ⚠️ 必须显式限定 this@WebTabManager：对象内部裸写
                    // onShowFileChooser 会解析成 WebChromeClient 自己的同名方法，
                    // 导致"永远读到方法引用"而不是 UI 层设置的回调。
                    val handler = this@WebTabManager.onShowFileChooser ?: return false
                    handler(fileChooserParams, callback::onReceiveValue)
                    return true
                }

                override fun onJsAlert(
                    view: WebView?, url: String?, message: String?, result: JsResult,
                ): Boolean {
                    val handler = this@WebTabManager.onJsAlert ?: return false
                    handler(url.orEmpty(), message.orEmpty(), result)
                    return true
                }

                override fun onJsConfirm(
                    view: WebView?, url: String?, message: String?, result: JsResult,
                ): Boolean {
                    val handler = this@WebTabManager.onJsConfirm ?: return false
                    handler(url.orEmpty(), message.orEmpty(), result)
                    return true
                }

                override fun onJsPrompt(
                    view: WebView?, url: String?, message: String?,
                    defaultValue: String?, result: JsPromptResult,
                ): Boolean {
                    val handler = this@WebTabManager.onJsPrompt ?: return false
                    handler(url.orEmpty(), message.orEmpty(), defaultValue.orEmpty(), result)
                    return true
                }

                override fun onJsBeforeUnload(
                    view: WebView?, url: String?, message: String?, result: JsResult,
                ): Boolean {
                    val handler = this@WebTabManager.onJsBeforeUnload ?: return false
                    handler(url.orEmpty(), message.orEmpty(), result)
                    return true
                }

                // `window.open()` → 新标签。
                //
                // `<a target="_blank">` 的点击不走这里，而是在页面里由
                // ScriptBridge 注入的新标签脚本拦截后交给宿主 —— 这条回调依赖宿主提供一个
                // 临时 WebView 接住弹窗，实测部分机型/WebView 版本上即使挂进
                // 窗口也不会启动导航，回调永远不来。window.open 没有 DOM 事件
                // 可拦，只能继续走这条路。
                //
                // 不在这里 newTab：onCreateWindow 的契约是"返回一个 WebView 让
                // 父 WebView 把子页面加载进去"。我们给一个临时 WebView 接住，
                // 等它拿到目标 URL 后再 newTab，然后销毁临时 WebView ——
                // 这样 window.open 的目标地址（含 JS 跳转后的最终 URL）
                // 才能被正确捕获。
                override fun onCreateWindow(
                    view: WebView?, isDialog: Boolean, isUserGesture: Boolean,
                    resultMsg: Message?,
                ): Boolean {
                    // 没有用户手势的 window.open 一律拒绝。
                    //
                    // 页面的 javaScriptCanOpenWindowsAutomatically 是开着的，
                    // 放行就等于让广告脚本在后台无限开标签（打开一个页面弹出
                    // 七八个新标签）。主流浏览器同样只放行点击触发的弹窗。
                    //
                    // 但 `isUserGesture` 不能单独作为依据：部分 WebView 版本上
                    // `<a target="_blank">` 的真实点击也会报 false，只信它就会
                    // 把用户点击当广告丢掉（点了没反应）。因此补一条"刚有点击"
                    // 的佐证 —— 手指 1.5s 内按过屏幕就放行。
                    val source = view as? LingWebView
                    if (!isUserGesture && source?.hasFreshUserInteraction() != true) {
                        return false
                    }
                    val transport = resultMsg?.obj as? WebView.WebViewTransport
                    if (transport == null) {
                        return false
                    }
                    val tempWebView = LingWebView(context)
                    // 复制来源标签的配置：临时 WebView 只用来抓地址，但页面若靠
                    // JS 重定向，关掉 JS 就会抓到中间地址而不是最终地址。
                    tempWebView.applySettings(
                        this@WebTabManager.settings,
                        incognito,
                        false,
                    )
                    // 必须真的挂到窗口上：完全游离的 WebView 在部分机型上不会
                    // 启动加载流程，导航回调永远不来 —— 表现就是用户点了
                    // `target="_blank"` 之后毫无反应。
                    //
                    // 尺寸压到 1×1 且**保持 VISIBLE**：`INVISIBLE` 的 WebView
                    // 可能因为拿不到合成器而不派发加载（实测 1×1 可见时才会
                    // 真正进入导航流程）。1 像素的遮挡可以忽略。
                    val container = host
                    if (container != null) {
                        container.addView(
                            tempWebView,
                            FrameLayout.LayoutParams(1, 1),
                        )
                    }
                    var opened = false
                    fun discard() {
                        container?.removeView(tempWebView)
                        // 不能在自己的导航/定时回调里同步 destroy：Chromium 那边
                        // 可能还在用这个 WebView，同步销毁有崩溃风险，推到下一轮
                        // 消息再销毁。
                        mainHandler.post { tempWebView.destroy() }
                    }
                    /** 拿到目标地址后统一走这里：开标签并回收临时 WebView。 */
                    fun openOnce(url: String?) {
                        if (opened) return
                        // about:blank 是 window.open() 的占位文档，真正的
                        // 地址要等脚本写入 location；直接放行会开出一个空标签。
                        if (url == null || url == "about:blank") return
                        opened = true
                        // 新标签继承来源标签的无痕状态：无痕里点开的链接
                        // 不能把内容落到常规标签上。
                        this@WebTabManager.newTab(url, incognito)
                        discard()
                    }
                    tempWebView.webViewClient = object : WebViewClient() {
                        // 先于网络请求触发，是拿到目标地址最快、最可靠的一处。
                        // `onPageStarted` 要等导航真正启动，某些机型上弹窗文档
                        // 一直停在 about:blank，就永远等不到。
                        override fun shouldOverrideUrlLoading(
                            view: WebView?, request: WebResourceRequest?,
                        ): Boolean {
                            val target = request?.url?.toString()
                            // about:blank 要放过去：那是 window.open() 的占位文档，
                            // 拦掉等于让弹窗脚本再也写不进 location。
                            // 无论走哪条回调，都不改变页面原有的加载结果 ——
                            // 这里只是"旁听"，真正的新标签已经由 openOnce 建好。
                            if (target == null || target == "about:blank") return false
                            openOnce(target)
                            return false
                        }

                        // minSdk 23 仍需旧版重载
                        @Deprecated("minSdk 23 仍需要旧版重载，见上")
                        @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
                        override fun shouldOverrideUrlLoading(
                            view: WebView?, url: String?,
                        ): Boolean {
                            if (url == null || url == "about:blank") return false
                            openOnce(url)
                            return false
                        }

                        override fun onPageStarted(
                            view: WebView?, url: String?, favicon: Bitmap?,
                        ) {
                            openOnce(url)
                        }
                    }
                    transport.webView = tempWebView
                    // 兜底回收：页面调用 window.open 后可能只写 about:blank 而不
                    // 导航（弹窗被自己的逻辑放弃），此时拿不到目标地址；不回收
                    // 就会一直挂着一个 WebView。
                    tempWebView.postDelayed({
                        if (!opened) {
                            opened = true
                            discard()
                        }
                    }, WINDOW_OPEN_TIMEOUT_MS)
                    return true
                }

                // 网页全屏：把 WebView 提供的容器 View 交给 UI 层铺满屏幕。
                //
                // 不接管时网页的「全屏」按钮毫无反应（视频只能内联播放），
                // 这是宿主必须代劳的事情之一 —— WebView 只负责准备 View。
                override fun onShowCustomView(
                    view: View?, callback: CustomViewCallback?,
                ) {
                    val handler = this@WebTabManager.onShowCustomView
                    if (view == null || callback == null || handler == null) return
                    handler(view, callback)
                }

                override fun onHideCustomView() {
                    this@WebTabManager.onHideCustomView?.invoke()
                }

                // navigator.geolocation：不接管时按拒绝兑现，页面才不会一直等。
                override fun onGeolocationPermissionsShowPrompt(
                    origin: String?,
                    callback: GeolocationPermissions.Callback?,
                ) {
                    val cb = callback ?: return
                    val delegate = this@WebTabManager.onGeolocationRequest
                    if (delegate == null) {
                        cb.invoke(origin, false, false)
                        return
                    }
                    delegate(origin.orEmpty(), cb)
                }

                // getUserMedia（摄像头/麦克风）：不接管时 deny()。
                // 未接管**不能什么都不做**：request 不兑现，页面的 getUserMedia
                // Promise 会永远挂起（表现为点了拍照没反应、也不报错）。
                override fun onPermissionRequest(request: PermissionRequest?) {
                    val req = request ?: return
                    val delegate = this@WebTabManager.onPermissionRequest
                    if (delegate == null) {
                        req.deny()
                        return
                    }
                    delegate(
                        req.origin?.toString().orEmpty(),
                        req.resources?.toList().orEmpty(),
                        req,
                    )
                }
            }
            webViewClient = LingWebViewClient(
                isIncognito = incognito,
                onPageStarted = { url ->
                    // 新一次主框架导航：注入闸门清零、旧文档的脚本菜单整批失效
                    // （细节见 ScriptBridge.onPageStarted）。
                    scriptBridge.onPageStarted(id)
                    // 控制台错误属于旧文档，新页从零开始记。
                    clearConsoleErrors(id)
                    // 内部页在 WebView 里的地址都不是逻辑地址：
                    //  - 主页是一个 about:blank 占位（见 [loadHome]）；
                    //  - 阅读视图是 loadDataWithBaseURL，回调报回来的是
                    //    ReaderPage.BASE_URL（reload 后是 `data:` 形式）。
                    // 这里必须挡住这次回写，否则 TabState.url 会被改成
                    // about:blank / baseUrl，主页识别、地址栏与返回键全部失效。
                    //
                    // 自定义协议（zhihu:// 等）同样不写回：它已经被
                    // shouldOverrideUrlLoading 交给系统应用，页面并没有真的
                    // 导航过去。若把它记进 TabState.url，地址栏会显示一个
                    // 打不开的地址，退出阅读模式时还会被回填给 WebView，
                    // 直接触发 ERR_UNKNOWN_URL_SCHEME。
                    //
                    // ⚠️ 阅读视图的判据必须用 [InternalPage.isInternal]，不能只看
                    // baseUrl：reload（刷新 / 渲染进程重建）后回调报回来的是一长串
                    // `data:text/html;base64,…`（见 [InternalPage.isDataReloadForm]）。
                    // 传入当前标签的逻辑地址，才能区分它是阅读视图的 data 形式。
                    val currentUrl = logicalUrlOf(id)
                    val isPlaceholder = isHomePlaceholder(id, url)
                    // 落到主页占位时（首次进入或从网页按返回），WebView 自身可能
                    // 还留着 [loadUrl] 设回去的不透明白底，必须重新透明，
                    // 否则原生背景层被盖住。
                    // 标记同时更新：about:blank 没有地址身份，夜间兜底 CSS 靠它拦
                    // （见 LingWebView.injectDarkMode）。
                    webViews[id]?.let { live ->
                        live.isHomePlaceholderDocument = isPlaceholder
                        if (isPlaceholder) live.setOpaqueBackground(false)
                    }
                    val isInternal = isPlaceholder || InternalPage.isInternal(url, currentUrl)
                    val isHome = isPlaceholder || InternalPage.isHomeDocument(url, currentUrl)
                    val keep = isInternal || !isNavigable(url)
                    updateTab(id) { tab ->
                        // 三种情况分开处理，不能简单"keep 就保留旧 url"：
                        //  - 回到主页（历史前进/后退触发）：必须写成逻辑地址
                        //    ling://home，否则旧网站 URL 残留在 TabState.url，
                        //    地址栏就一直显示那个网址；
                        //  - 阅读视图 / 自定义协议：保留既有逻辑地址；
                        //  - 普通页面：写回真实 url。
                        val storedUrl = when {
                            isHome -> HomePage.URL
                            keep -> tab.url
                            else -> url
                        }
                        tab.copy(
                            url = storedUrl,
                            // 新一次主框架导航：安全状态先按协议落到**基础态**，
                            // 混合内容 / 证书错误会在加载过程中把它升级
                            // （见 onMixedContent / CertPromptQueue.handle）。
                            security = SecurityState.baseForUrl(storedUrl),
                            isLoading = !isInternal,
                            errorText = null,
                            // 主页是历史起点：任何一次回调都不能把返回键点亮。
                            // about:blank 占位文档本身在历史里，若照抄
                            // wv.canGoBack() 会让「停在主页却有返回键」出现。
                            canGoBack = if (isHome) false else canGoBack(),
                            canGoForward = canGoForward(),
                        )
                    }
                },
                onPageFinished = { url, title ->
                    // 内部页的地址归一化同 onPageStarted：主页是 about:blank 占位，
                    // 阅读视图是 baseUrl / reload 后的 data: 形式，都不能原样写回。
                    val currentUrl = logicalUrlOf(id)
                    val isPlaceholder = isHomePlaceholder(id, url)
                    // 落到主页占位时（首次进入或从网页按返回），WebView 自身可能
                    // 还留着 [loadUrl] 设回去的不透明白底，必须重新透明，
                    // 否则原生背景层被盖住。
                    // 标记同时更新：about:blank 没有地址身份，夜间兜底 CSS 靠它拦
                    // （见 LingWebView.injectDarkMode）。
                    webViews[id]?.let { live ->
                        live.isHomePlaceholderDocument = isPlaceholder
                        if (isPlaceholder) live.setOpaqueBackground(false)
                    }
                    val isInternal = isPlaceholder || InternalPage.isInternal(url, currentUrl)
                    val isHome = isPlaceholder || InternalPage.isHomeDocument(url, currentUrl)
                    val keep = isInternal || !isNavigable(url)
                    updateTab(id) {
                        it.copy(
                            url = when {
                                isHome -> HomePage.URL
                                keep -> it.url
                                else -> url
                            },
                            // 主页标题固定为「主页」，不用 WebView 的 <title>翎</title>；
                            // 阅读视图的标题已经是文章标题，保留即可。
                            title = if (isHome) it.title else title.ifBlank { it.title },
                            isLoading = false,
                            progress = 100,
                            // 主页同 onPageStarted：绝不让返回键在主页上点亮。
                            canGoBack = if (isHome) false else canGoBack(),
                            canGoForward = canGoForward(),
                        )
                    }
                    // 实例重建后的滚动位置还原：页面刚加载完，scrollY 才有意义。
                    // 只在"重建 + 之前存过位置"时生效，用完即清（见 restoreScrollY）。
                    // 放在这里而不是 onPageStarted：那时文档还没布局，scrollTo 无效。
                    if (!isInternal && !isHome) {
                        sessions[id]?.let { ses -> restoreScrollY(wv, ses) }
                    }

                    // 阅读视图的颜色是"烘"进 HTML 字符串里的，任何一次文档重建
                    // （reload 重放 data URL、渲染进程被回收后重建、历史前进/后退
                    // 到旧条目）都会停在烘死时的旧配色。原地改 :root 的 CSS 变量
                    // 既不动滚动位置，也不压历史。
                    // 主页已改为 Compose 渲染，不存在"补色"这回事。
                    if (isInternal && !isHome) {
                        wv.evaluateJavascript(reader.themeJs(), null)
                    }

                    // 强制夜间模式的 CSS 兜底必须在页面加载**完成后**注入，
                    // 加载中注入会被随后到达的文档覆盖掉。
                    //
                    // 主页与阅读视图都**不注入**：它们是自包含 HTML，
                    // 配色由 CSS 变量精确控制。再套一层 invert+hue-rotate
                    // 会把这套配色整个反过来 —— 深色模式下反而变成亮底白字，
                    // 正好与预期相反。
                    if (!isInternal) {
                        // 用 shouldDarkenPages() 而非直接读 forceDarkWebPages：
                        // 暗化还要看夜间模式是否已解析为深色。两者一起算，
                        // 才是"网页内容是否该暗"的最终答案。
                        wv.injectDarkMode(this@WebTabManager.shouldDarkenPages())

                        // 用户脚本：END 在这里补一次（个别页面不发 onPageCommitVisible），
                        // 再跑 IDLE 那批。两处都有一页一次的闸门，不会重复执行。
                        // 顺序固定为 END → IDLE，与脚本时机的语义一致。
                        scriptBridge.injectScripts(id, wv, url, ScriptRunAt.END)
                        scriptBridge.injectScripts(id, wv, url, ScriptRunAt.IDLE)

                        // 历史记录只记真实网址。阅读视图的 url 是合成的
                        // ling://reader，主页是 ling://home —— 两者都没有记录价值，
                        // 记进去只会在历史列表里留下点不开的条目。
                        // 注意：它们对应的**原文地址**早在进入阅读模式前就已记过，
                        // 所以这里跳过不会漏记。
                        onVisited?.invoke(url, title, incognito)
                    }
                },
                onProgress = { p -> updateTab(id) { it.copy(progress = p) } },
                onPageVisible = { url ->
                    // 首帧已提交：document-end 的注入点。内部页不注入。
                    if (!isHomePlaceholder(id, url) &&
                        !InternalPage.isInternal(url, logicalUrlOf(id))
                    ) {
                        scriptBridge.injectScripts(id, wv, url, ScriptRunAt.END)
                    }
                },
                onReceivedTitle = { t ->
                    // 主页保留「主页」这个标题；阅读视图的标题已是文章标题，
                    // 让它跟随 <title> 更新即可，但合成的 ling://reader 不该被覆盖。
                    val cur = _tabs.value.firstOrNull { it.id == id }
                    if (!HomePage.isHomeUrl(cur?.url) && !ReaderPage.isReaderUrl(cur?.url)) {
                        updateTab(id) { it.copy(title = t) }
                    }
                },
                onReceivedIcon = { icon -> applyFavicon(id, icon) },
                onMixedContent = {
                    // 只在**基础态仍是安全**时升级：主框架本来就是 http 的话，
                    // 状态已是 INSECURE_HTTP，不必再标成混合内容；证书错误更不能被它覆盖。
                    // 该回调来自 shouldInterceptRequest 的后台线程，而 _tabs 是
                    // MutableStateFlow（update 原子且线程安全），因此这里直接写即可。
                    updateTab(id) { tab ->
                        if (tab.security == SecurityState.SECURE) {
                            tab.copy(security = SecurityState.INSECURE_MIXED)
                        } else {
                            tab
                        }
                    }
                },
                currentUrl = { _tabs.value.firstOrNull { it.id == id }?.url },
                // 主框架与子资源统一排队：白名单命中直接放行，否则交给 UI 弹窗。
                onSslErrorRequest = { host, reason, mainFrame, handler ->
                    certPrompts.handle(id, host, reason, mainFrame, handler)
                },
                onError = { _, desc ->
                    updateTab(id) { it.copy(isLoading = false, errorText = desc) }
                },
                // 渲染进程崩溃：同进程里所有 WebView 都已失效，统一销毁重建。
                onRendererGone = { dead -> recoverFromRendererGone(dead) },
                // HTTP 认证（401）。与上面几个同理用 lambda 动态读委托：
                // UI 的接线在 composition 之后，直接传值会永久拿到 null。
                onHttpAuthRequest = { host, realm, authHandler ->
                    this@WebTabManager.onHttpAuthRequest?.invoke(host, realm, authHandler)
                        ?: authHandler.cancel()
                },
                // 客户端证书（mTLS）：同样动态读委托，未接线时立即取消，
                // 别让 TLS 握手悬在半空。
                onClientCertRequest = { request ->
                    val handler = this@WebTabManager.onClientCertRequest
                    if (handler == null) request.cancel() else handler(request)
                },
                // POST 结果页刷新：交给 UI 弹窗决定是否重新提交。
                onFormResubmission = { _, dontResend, resend ->
                    handleFormResubmission(id, resend, dontResend)
                },
                // Safe Browsing：弹出「危险网站」确认框，而不是静默退回。
                onSafeBrowsingHit = { url, mainFrame, threatType, callback ->
                    safePrompts.handle(id, url, mainFrame, threatType, callback)
                },
                onExternalScheme = { uri -> onExternalUri?.invoke(uri) ?: false },
                // `.user.js` 以附件形式下发时不会走 shouldOverrideUrlLoading，
                // 而是落到下载监听里：这里同样交给安装确认，而不是当普通文件下载。
                onUserScriptLink = { url -> this@WebTabManager.onUserScriptLink?.invoke(url) },
                // 用 lambda 而不是直接传布尔值：WebViewClient 是建 WebView 时
                // 一次性构造的，传值会让开关"改完要重启才生效"。
                //
                // 必须写 this@WebTabManager.settings：在 apply 作用域里裸写
                // `settings` 会解析成 WebView.settings（WebSettings），
                // 那个对象没有 openLinksInExternalApp 字段。
                openExternal = { this@WebTabManager.settings.openLinksInExternalApp },
                // 广告拦截开关：与 openExternal 同理用 lambda（动态读设置），
                // 传值会让开关"改完要重启才生效"。默认开。
                adBlockEnabled = { this@WebTabManager.settings.adBlockEnabled },
                // 自定义规则 + 订阅域名的并集：读的是**预先算好**的那一份
                // （见 mergedBlockRules），不在热路径里现并十万条集合。
                adBlockRules = { this@WebTabManager.mergedBlockRules },
                // 命中拦截即计数（见 blockedCount）；回调在后台线程上，累加是线程安全的。
                onAdBlocked = { countBlocked() },
            )
            setDownloadListener { url, _, _, mimeType, _ ->
                // 用户脚本优先走安装流程：下载成文件后再导入多一步，且默认会存进下载目录。
                if (isUserScriptUrl(url)) {
                    onUserScriptLink?.invoke(url)
                } else {
                    onDownloadRequested?.invoke(url, mimeType)
                }
            }
        }

        // HTTP 认证回调挂在 WebViewClient 上（不是 WebChromeClient）。
        // 这里必须传"读委托的 lambda"而不是把委托本身拷进来：UI 的接线
        // 发生在 composition 之后，直接拷会永久拿到 null、401 永远被取消。
        // 恢复上次被回收时存下的历史栈（见 TabSession.savedState）。
        //
        // 时机很关键：必须在挂到视图树、开始导航**之前**调 restoreState，
        // 否则新实例已经导航到目标地址，restoreState 会把历史栈整个替换掉，
        // 表现为"页面跳回旧地址"。这里紧接着就 return，后续的 loadUrl
        // 会沿用恢复出来的历史。
        //
        // 消费后立刻清空：一份快照只该用一次，留着会让下一次重建
        // 又恢复到那个旧历史栈上。
        sessions[id]?.savedState?.let { state ->
            // restoreState 返回恢复后的 WebBackForwardList（失败时为 null）。
            // 调用方据此跳过"重新 loadUrl"，否则会把刚恢复的栈顶掉。
            restoredFromSnapshot =
                runCatching { wv.restoreState(state) != null }.getOrDefault(false)
            sessions[id]?.savedState = null
        }

        webViews[id] = wv
        // Cookie 策略是进程级的：新建的 WebView 只有正好是当前激活标签时才动全局策略，
        // 后台标签（例如 ensureHomeRendered 给非激活标签补建实例）等切过去时再对齐。
        if (id == _activeId.value) syncCookiePolicy(id)
        enforceCacheLimit()
        return wv
    }

    /**
     * 渲染进程崩溃后的恢复。
     *
     * 为什么必须重建**全部**实例：渲染进程是按进程共享的，崩一次，同一进程里
     * 所有 WebView 都会失效；官方要求这类实例必须从视图树摘下并 `destroy()`，
     * 之后对它的任何调用都是未定义行为。
     *
     * 只重建当前标签并重新加载它原来的地址，其余标签仍按 [obtainWebView] 的
     * 懒加载机制等用户切过去再建 —— 一次性重建所有标签会让崩溃恢复本身变成卡顿。
     *
     * @param dead 触发回调的那个实例。用它做身份校验：多个 WebView 会各自收到
     *   回调，只处理第一次（此时它仍在实例表里），后续（表里已是新建实例）直接忽略。
     */
    private fun recoverFromRendererGone(dead: WebView) {
        if (webViews.values.none { it === dead }) return

        val deadViews = webViews.toMap()
        webViews.clear()
        deadViews.forEach { (tabId, wv) ->
            // document-start 的注册句柄跟着实例一起失效：摘掉旧的、清空注入记录，
            // 但保留整份 session（标签本身还在）。
            scriptBridge.releaseRendererHandles(tabId)
            (wv.parent as? FrameLayout)?.removeView(wv)
            runCatching {
                wv.stopLoading()
                wv.destroy()
            }
        }
        host?.removeAllViews()

        val tab = _tabs.value.firstOrNull { it.id == _activeId.value } ?: return
        // loadUrl 内部会 obtainWebView 建新实例；内部页（主页/阅读视图）也由它分派。
        loadUrl(tab.id, tab.url)
        host?.let { container ->
            val wv = webViews[tab.id] ?: return@let
            (wv.parent as? FrameLayout)?.removeView(wv)
            container.addView(wv)
        }
    }

    /**
     * 异步抓取当前页面的滚动位置，写进 [TabSession.scrollY]。
     *
     * 为什么不能同步：`evaluateJavascript` 的回调要等 JS 引擎执行完才回来，
     * 而调用点（[enforceCacheLimit]）是个同步 while 循环，马上就要 destroy()。
     * 等它等于把整个回收流程改成异步队列 —— 代价远大于收益。
     *
     * 因此这里**发出去就不管**：回调回来时实例可能已经销毁，
     * 但 lambda 只捕获 [TabSession]（普通 Kotlin 对象），不碰 WebView，
     * 写一个 Int 是安全的。最坏情况是这次没抓到，scrollY 保持旧值。
     */
    private fun captureScrollY(wv: LingWebView, ses: TabSession) {
        runCatching {
            wv.evaluateJavascript(SCROLL_Y_JS) { raw ->
                // evaluateJavascript 的返回值是 JSON 编码的字符串（数字就是 "123"）
                raw?.trim()?.trim('"')?.toDoubleOrNull()?.let { y ->
                    if (y.isFinite() && y >= 0) ses.scrollY = y.toInt()
                }
            }
        }
    }

    /**
     * 页面加载完成后把滚动位置还原回去。
     *
     * 只在"实例是重建出来的"这条路上调用（见 switchTo），且用完即清 ——
     * 否则用户之后正常刷新也会被拽回旧位置。
     *
     * 用 `window.scrollTo` 而不是 `document.documentElement.scrollTop = y`：
     * 后者在部分站点（滚动容器不是 document）上无效，scrollTo 走的是同一套语义。
     */
    private fun restoreScrollY(wv: LingWebView, ses: TabSession) {
        val y = ses.scrollY
        if (y <= 0) return
        ses.scrollY = 0
        runCatching {
            wv.evaluateJavascript("try{window.scrollTo(0,$y)}catch(e){}", null)
        }
    }

    /**
     * 按系统内存压力调整实例上限。
     *
     * 由 UI 层从 `Application.onTrimMemory` 转发进来（web 层不认识 Activity/
     * Application 生命周期，与其它回调同一分层思路）。**必须投递到主线程**：
     * 它会改 [webViews] 并调 [enforceCacheLimit]，而那张表是主线程独占的。
     *
     * 分档依据是"还能不能撑住"：
     *  - 正常 / 后台：不收缩（后台时用户随时会切回来，砍了就是白重载）；
     *  - RUNNING_LOW：降一档，牺牲最久未用的那几个；
     *  - RUNNING_CRITICAL：降到最低，先别被系统杀掉。
     *
     * 调高时不主动补建实例 —— 那些标签切过去时 [obtainWebView] 自然会建。
     */
    fun updateCacheLimit(level: Int) {
        val target = when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
            -> MAX_WEBVIEWS_CRITICAL

            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE,
            -> MAX_WEBVIEWS_LOW

            else -> MAX_WEBVIEWS_DEFAULT
        }
        if (target == maxWebViews) return
        maxWebViews = target
        // 收缩后立刻执行，否则上限要等到下一次"新建实例"才生效 ——
        // 而内存危急时用户可能根本不建新实例，等于白设。
        enforceCacheLimit()
    }

    /** WebView 实例上限：超过则回收最久未使用的非激活实例。 */
    private fun enforceCacheLimit() {
        while (webViews.size > maxWebViews) {
            val victim = webViews.keys.firstOrNull { it != _activeId.value } ?: return
            webViews.remove(victim)?.let { wv ->
                // 实例销毁后主页占位身份一并失效：重建出来的 WebView 是空白的，
                // 若 session 里还留着这个标记，它自己导航到 about:blank 就会被误认成主页。
                // 只清标记、保留 session：标签本身还在。
                sessions[victim]?.isHomePlaceholder = false
                // 销毁前存下回退/前进历史栈：实例一没，这条链就断了。
                // saveState 必须在 destroy() **之前**、实例仍可用时调用 ——
                // 销毁后再调只会拿到 null。
                //
                // 注意它存的是历史栈，不是页面内容：恢复后仍要重新加载当前页，
                // 但用户按返回能回到上一页（见 obtainWebView 里的 restoreState）。
                sessions[victim]?.let { ses ->
                    ses.savedState = runCatching {
                        Bundle().also { out ->
                            @Suppress("DEPRECATION")
                            wv.saveState(out)
                        }
                    }.getOrNull()
                    // 滚动位置只能异步取：evaluateJavascript 的回调要等 JS 执行完，
                    // 而这里是同步循环。回调里只写一个 Int，不碰任何已销毁的对象 ——
                    // 此时 wv 可能已经 destroy，但 lambda 捕获的是 session，安全。
                    captureScrollY(wv, ses)
                }
                // ⚠️ 必须先摘 document-start 句柄，再 destroy()。
                //
                // ScriptHandler 绑的是**WebView 实例**，而 session 跟的是**标签**：
                // 实例被回收后 session 仍在，句柄也就跟着留下来。等用户切回这个标签、
                // obtainWebView 建出新实例并调 registerDocumentStart 时，第一步就是
                // 遍历旧句柄执行 remove() —— 那一步会去碰已经 destroy 的原生对象，
                // 在 Chromium 内部空指针崩溃：
                //
                //   WV.xB.remove
                //   → ScriptBridge.registerDocumentStart
                //   → WebTabManager.obtainWebView → switchTo → 用户点标签
                //   → SIGSEGV / SEGV_MAPERR（MI 8 与 MEIZU 两台真机均复现）
                //
                // 注意 runCatching 在这里**拦不住**：SIGSEGV 是信号，不是可捕获的
                // Java 异常，进程会直接死。唯一可靠的做法是别留下失效句柄。
                scriptBridge.releaseRendererHandles(victim)
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
        // ⚠️ 主页的 `wv.url` 是占位文档 `about:blank`（见 [loadHome]），阅读视图的
        // `wv.url` 是 loadDataWithBaseURL 的 **baseUrl**（reload 后还会变成一长串
        // `data:text/html;base64,…`），都不是写进 TabState 的逻辑地址。
        // 原样写回会让同一个页面在状态里出现两种 url 值，会话快照里还会存下
        // https://reader.ling.invalid/ 这种永远解析不了的假地址 ——
        // 所有"把实时地址写回状态"的地方都先归一化，并把**当前标签的逻辑地址**
        // 作为线索传进去：reload 后的 data: 形式要靠它才分得清是阅读视图
        // （也就才能写回正确的逻辑地址，而不是丢掉线索原样写回 base64）。
        val live = wv.url?.trim().orEmpty()
        updateTab(id) { tab ->
            val placeholder = isHomePlaceholder(id, live)
            val isInternal = placeholder || InternalPage.isInternal(live, tab.url)
            tab.copy(
                url = if (placeholder) HomePage.URL
                else InternalPage.logicalUrl(live, tab.url) ?: tab.url,
                // 内部页的标题是**语义标题**（主页固定「主页」、阅读视图是文章标题）。
                // 这里若照抄 wv.title，主页会被占位文档的空标题覆盖掉 ——
                // 与 onReceivedTitle 的处理保持一致。
                title = if (isInternal) {
                    tab.title
                } else {
                    wv.title?.takeIf { t -> t.isNotBlank() } ?: tab.title
                },
                // 主页是历史起点：切回主页标签时返回键同样必须不可用（与
                // loadHome / 页面回调三处保持一致）。
                canGoBack = if (placeholder || InternalPage.isHomeDocument(live, tab.url)) {
                    false
                } else {
                    wv.canGoBack()
                },
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
        // 连同 favicon 一起回收：Activity 销毁后这些像素再没人用，
        // 留着只会拖到下次 GC 周期，期间占用着可能几十 MB。
        _tabs.value.forEach { recycleTab(it) }
        _tabs.value = emptyList()
        _activeId.value = ""
        // 实例已销毁，document-start 的注册句柄先摘掉；随后整份簿记一起丢弃。
        scriptBridge.releaseAllHandlers()
        sessions.clear()
        scriptBridge.publishMenus()
        // UI 即将销毁：待裁决项全部退回安全页，控制台记录也没有留存的必要。
        cancelSafeBrowsing()
        publishConsoleErrors()
    }

    /**
     * 处理页面上报的 `target="_blank"` 点击。
     *
     * 桥对象在主世界，页面自己也能直接调 `window.LingTab.postMessage`，
     * 所以不能只信这条消息：必须搭配"刚刚确实有真实触摸"这一宿主侧事实，
     * 否则任何页面都能无限制地开标签（广告最爱的行为）。
     */
    private fun onTabLinkClick(tabId: String, raw: String) {
        val wv = webViews[tabId] ?: return
        if (!wv.hasFreshUserInteraction()) return
        if (!raw.startsWith("http://") && !raw.startsWith("https://")) return
        val incognito = _tabs.value.firstOrNull { it.id == tabId }?.isIncognito ?: false
        newTab(raw, incognito)
    }

    companion object {
        /**
         * WebView 实例上限的**默认值**（正常内存压力下）。
         *
         * 早期固定为 6，结果 7 个以上标签必然触发回收 —— 而回收意味着
         * "切回去要重新加载"，滚动位置、表单、视频进度全丢。这正是
         * "标签一多，切一个重载一个"的直接原因。
         *
         * 提到 10 之后，绝大多数人的日常使用（10 个标签以内）完全不会重载。
         * 真正的兜底交给 [updateCacheLimit] —— 由系统 onTrimMemory 回调
         * 按实际内存压力动态收缩，而不是一上来就按最坏情况砍。
         */
        private const val MAX_WEBVIEWS_DEFAULT = 10

        /** 内存吃紧时的实例上限（TRIM_MEMORY_RUNNING_LOW）。 */
        private const val MAX_WEBVIEWS_LOW = 6

        /** 内存危急时的实例上限（TRIM_MEMORY_RUNNING_CRITICAL）。 */
        private const val MAX_WEBVIEWS_CRITICAL = 3

        /**
         * 主页占位文档的地址。
         *
         * 主页本身由 Compose 渲染，WebView 里只需一个空白文档：
         * 它既是"主页"在历史里的那一帧（从第一个网页能按返回回到主页），
         * 也负责在进入主页时丢弃上一页的文档（停掉媒体与定时器）。
         */
        private const val HOME_PLACEHOLDER_URL = "about:blank"

        /** 注入到页面的 GM 桥对象名（`window.LingGM`），与 [scriptWrapper] 里的垫片对应。 */
        private const val USER_SCRIPT_BRIDGE = "LingGM"

        /** 浏览器级"新标签"桥在页面里的对象名（`window.LingTab`）。 */
        private const val TAB_BRIDGE_NAME = "LingTab"

        /** 表单重提交的待裁决上限：低频事件，超过就直接放弃重提交。 */
        private const val MAX_PENDING_FORMS = 4

        /** 每个标签页保留的控制台错误条数。 */
        private const val MAX_CONSOLE_ERRORS = 30

        /** 单条控制台错误的展示长度，避免长堆栈把面板撑爆。 */
        private const val MAX_CONSOLE_MESSAGE = 500

        /**
         * 读当前滚动位置。
         *
         * 用 `Math.round` 而不是原值：evaluateJavascript 会把它 JSON 编码成
         * `"123.456"`，直接取整省掉一次解析里的浮点误差。
         * 整个表达式包在 try 里 —— 页面可能已进入销毁流程，任何 DOM 访问都可能抛。
         */
        private const val SCROLL_Y_JS =
            "try{Math.round(window.scrollY||document.documentElement.scrollTop||0)}catch(e){0}"

        /**
         * 临时 WebView 的存活上限。
         *
         * 页面 `window.open()` 后可能只写 `about:blank` 而不导航（弹窗被自己的
         * 逻辑放弃），此时拿不到目标地址；不回收就会一直挂着一个 WebView。
         */
        private const val WINDOW_OPEN_TIMEOUT_MS = 5_000L
    }
}

/**
 * 网页 JS 对话框的待处理请求。
 *
 * UI 层用它作为 Compose state：非 null 时弹对应对话框，处理完（调用 [JsResult]
 * 或 [JsPromptResult]）后置 null。之所以把 [JsResult] 直接包进来，是因为
 * WebView 要求它**必须且只能**兑现一次，state 持有是最不容易漏调的方式。
 */
sealed interface JsDialogRequest {
    val url: String
    val message: String

    data class Alert(
        override val url: String,
        override val message: String,
        val result: JsResult,
    ) : JsDialogRequest

    data class Confirm(
        override val url: String,
        override val message: String,
        val result: JsResult,
    ) : JsDialogRequest

    data class Prompt(
        override val url: String,
        override val message: String,
        val defaultValue: String,
        val result: JsPromptResult,
    ) : JsDialogRequest

    data class BeforeUnload(
        override val url: String,
        override val message: String,
        val result: JsResult,
    ) : JsDialogRequest
}

/**
 * 网页发起的宿主级授权请求。
 *
 * 与 [JsDialogRequest] 分开：那类是网页弹给用户的对话框，这类是网页向浏览器
 * 索要**宿主能力**（身份凭证 / 位置 / 摄像头麦克风），三者的兑现方式完全不同
 * （`HttpAuthHandler.proceed/cancel`、`GeolocationPermissions.Callback.invoke`、
 * `PermissionRequest.grant/deny`），但都必须**恰好兑现一次** —— 漏调会让
 * 页面一直等待（401 卡住 / 定位不返回 / getUserMedia 的 Promise 永不落地）。
 */
sealed interface WebAuthRequest {
    /** 发起方标识：认证是 host，定位与设备能力是 origin。 */
    val origin: String

    /** HTTP 基础认证：401 + `WWW-Authenticate`。 */
    data class HttpAuth(
        override val origin: String,
        val realm: String,
        val handler: HttpAuthHandler,
    ) : WebAuthRequest

    /** 网页请求定位。 */
    data class Geolocation(
        override val origin: String,
        val callback: GeolocationPermissions.Callback,
    ) : WebAuthRequest

    /** 网页请求设备能力（摄像头/麦克风等）。 */
    data class Device(
        override val origin: String,
        val resources: List<String>,
        val request: PermissionRequest,
    ) : WebAuthRequest
}
