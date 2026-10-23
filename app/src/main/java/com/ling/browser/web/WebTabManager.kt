package com.ling.browser.web

import android.annotation.SuppressLint
import android.content.Context
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
import android.webkit.SafeBrowsingResponse
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
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
import org.json.JSONObject

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
     * 启用中的用户脚本快照（见 [com.ling.browser.data.repo.UserScriptRepository]）。
     *
     * 与订阅域名同理：web 层不认识仓库，由 UI 层经 [applyUserScripts] 注入。
     * 快照里的 `valuesJson` 是 `GM_getValue` 的只读初值，注入时随包装代码一起下发。
     */
    private var userScripts: List<EnabledUserScript> = emptyList()

    /** tabId -> document-start 的注册句柄；脚本变更或实例重建时要先摘掉旧的。 */
    private val scriptHandlers = mutableMapOf<String, MutableList<ScriptHandler>>()

    /** tabId -> 本页已注入过的 "脚本|时机|URL"，防止同一页重复执行。 */
    private val injectedScripts = mutableMapOf<String, MutableSet<String>>()

    /** tabId -> (`脚本|命令` -> 菜单项)，供 UI 展示「脚本菜单」。 */
    private val scriptMenus = LinkedHashMap<String, MutableMap<String, UserScriptMenuCommand>>()

    private val _userScriptMenus = MutableStateFlow<List<UserScriptMenuCommand>>(emptyList())

    /** 当前各标签页注册的脚本菜单命令。 */
    val userScriptMenus: StateFlow<List<UserScriptMenuCommand>> = _userScriptMenus.asStateFlow()

    // ---------------------------------------------------------------- Safe Browsing

    /** 尚未裁决的命中（FIFO，兜底用）。 */
    private val safeQueue = ArrayDeque<PendingSafeBrowsing>()

    /** 当前正在弹窗的那一条；null 表示没有弹窗。 */
    private var safeShown: PendingSafeBrowsing? = null

    /** Safe Browsing 弹窗回调；参数为 null 表示关掉弹窗。 */
    var onSafeBrowsingPrompt: ((SafeBrowsingPrompt?) -> Unit)? = null

    /**
     * 标签关闭回调。
     *
     * 用户脚本的 `GM_openInTab(..., { onclose })` 需要知道「自己开的标签被关了」，
     * 但那只簿记在 ViewModel 里，所以这里只发出通知，由上层决定回不给脚本。
     */
    var onTabClosed: ((tabId: String) -> Unit)? = null

    private class PendingSafeBrowsing(
        val prompt: SafeBrowsingPrompt,
        val callback: SafeBrowsingResponse,
    )

    // ---------------------------------------------------------------- 页面控制台错误

    /** tabId -> 最近的 ERROR 级控制台消息（新页导航时清空）。 */
    private val consoleByTab = LinkedHashMap<String, MutableList<ConsoleEntry>>()

    private val _consoleErrors = MutableStateFlow<List<ConsoleEntry>>(emptyList())

    /** 当前各标签页收集到的控制台错误，供「页面错误」面板展示。 */
    val consoleErrors: StateFlow<List<ConsoleEntry>> = _consoleErrors.asStateFlow()

    /**
     * 系统 WebView 是否支持 document-start 注入。
     *
     * 不支持时 `@run-at document-start` 的脚本降级到 [ScriptRunAt.END]
     * （首帧已提交，是可达的最早时机）。特性检查本身在老系统上可能抛异常，
     * 因此包一层 runCatching，失败按"不支持"处理。
     */
    private val documentStartSupported: Boolean = runCatching {
        WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
    }.getOrDefault(false)

    /**
     * tabId -> 已提取的正文。
     *
     * 为什么要缓存：进入阅读视图后，页面的 DOM 已经被替换掉了，
     * 原文不再存在于 WebView 里。此时若用户调整字号，重新跑提取
     * 只会得到"没有正文" —— 必须留一份原始结果用来重排版。
     *
     * 缓存随标签关闭 / 退出阅读模式清理，避免长期占内存
     * （正文 HTML 通常几十 KB，几个标签累积起来也不小）。
     */
    private val readerContent = mutableMapOf<String, ReaderResult.Ok>()

    /**
     * tabId -> 用户已明确放行的证书错误站点（主机名）。
     *
     * 证书错误页「继续访问」的临时白名单：放行后本标签页再访问该主机就自动
     * 放行，不再反复弹确认。只在本标签存活期间有效 —— 关标签即清
     * （见 [recycleTab]），**不落盘、不跨标签**，避免一次手滑变成长期放行。
     */
    private val certExceptions = mutableMapOf<String, MutableSet<String>>()

    /**
     * 尚未裁决的证书错误（FIFO）。
     *
     * 一个页面可能同时触发多个子资源握手失败，而 [SslErrorHandler] 必须逐个
     * 兑现 —— 弹窗同一时刻只显示一个，其余排队；超过 [MAX_PENDING_CERTS]
     * 的直接取消，避免坏页面把队列撑爆。
     */
    private val certQueue = ArrayDeque<PendingCert>()

    /** 当前正在弹窗的那一条；null 表示没有弹窗。 */
    private var certShown: PendingCert? = null

    /**
     * 证书错误弹窗回调：参数为 null 表示"当前没有待裁决项，关掉弹窗"。
     *
     * 用可空参数而不是两个回调，是为了让 UI 只维护一个状态源。
     */
    var onCertPrompt: ((CertPrompt?) -> Unit)? = null

    /** 队列里的一条：等待用户裁决的 handler 与展示信息。 */
    private class PendingCert(
        val tabId: String,
        val prompt: CertPrompt,
        val handler: SslErrorHandler,
    )

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
     * 主页搜索框提交的回调（查询串，可能是一句搜索词也可能是个网址）。
     *
     * 判定"网址还是搜索词"要 UrlUtils + 用户选的搜索引擎，那是 UI 层的事；
     * 这里只负责把网页提交上来的字符串转出去。
     */
    var onSearchRequested: ((query: String) -> Unit)? = null

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
    fun applyUserScripts(scripts: List<EnabledUserScript>) {
        userScripts = scripts
        webViews.forEach { (id, wv) -> registerDocumentStart(id, wv) }
        // 脚本被停用/删除后，它注册的菜单也不该再出现在界面里。
        pruneMenus(scripts.mapTo(HashSet()) { it.id })
    }

    /**
     * 给一个 WebView 注册全部 document-start 脚本。
     *
     * `addDocumentStartJavaScript` 是按 WebView 注册、对**后续每次导航**生效的，
     * 因此这里先摘掉旧句柄再按最新快照重注册。无痕标签整体跳过：脚本可能把
     * 数据经 GM 存储写进磁盘，那与"不留痕迹"直接冲突。
     */
    private fun registerDocumentStart(tabId: String, wv: LingWebView) {
        scriptHandlers.remove(tabId)?.forEach { handler -> runCatching { handler.remove() } }
        if (!documentStartSupported || userScripts.isEmpty()) return
        if (_tabs.value.firstOrNull { it.id == tabId }?.isIncognito == true) return

        val handlers = mutableListOf<ScriptHandler>()
        for (entry in userScripts) {
            if (entry.script.runAt != ScriptRunAt.START) continue
            val origins = entry.script.allowedOrigins
            if (origins.isEmpty()) continue
            runCatching {
                handlers += WebViewCompat.addDocumentStartJavaScript(
                    wv,
                    scriptWrapper(entry.script, entry.id, entry.valuesJson, entry.requireCode, entry.resourcesJson),
                    origins,
                )
            }
        }
        if (handlers.isNotEmpty()) scriptHandlers[tabId] = handlers
    }

    /**
     * 在某个时机把匹配当前 URL 的脚本注入页面。
     *
     * 每个"脚本 + 时机 + URL"只注入一次：`onPageCommitVisible` 与
     * `onPageFinished` 都可能对同一页触发多次，不设闸门会让脚本重复执行。
     * 闸门在 [onPageStarted] 清零（新一次主框架导航）。
     */
    private fun injectUserScripts(
        tabId: String,
        wv: LingWebView,
        url: String,
        runAt: ScriptRunAt,
    ) {
        if (userScripts.isEmpty()) return
        if (_tabs.value.firstOrNull { it.id == tabId }?.isIncognito == true) return

        val injected = injectedScripts.getOrPut(tabId) { mutableSetOf() }
        for (entry in userScripts) {
            val actual = entry.script.runAt
            val due = actual == runAt ||
                // document-start 的脚本在 END 再兜一次：注册是按 WebView 提前做的，
                // 脚本在页面打开之后才导入、或注册被系统静默拒绝时，注册就不会生效，
                // 而 END 之外的时机不会补跑，表现为"脚本凭空消失"。
                // 是否真的需要补，由下面页面里的注入标记决定。
                (actual == ScriptRunAt.START && runAt == ScriptRunAt.END)
            if (!due) continue
            if (!entry.script.matches(url)) continue
            if (!injected.add("${entry.id}|$runAt|$url")) continue
            val code = scriptWrapper(
                entry.script,
                entry.id,
                entry.valuesJson,
                entry.requireCode,
                entry.resourcesJson,
            )
            // document-start 不可用时 START 直接降级到 END，无需探测；
            // 可用时先问页面"这份脚本跑过了吗"，避免同一页重复执行。
            if (actual == ScriptRunAt.START && documentStartSupported) {
                val probe = "!!(window.__lingGMInjected&&window.__lingGMInjected[${jsString(entry.id)}])"
                wv.evaluateJavascript(probe) { seen ->
                    if (seen != "true") wv.evaluateJavascript(code, null)
                }
            } else {
                wv.evaluateJavascript(code, null)
            }
        }
    }

    // ---------------------------------------------------------------- 脚本菜单 / 跨页值同步

    /** 页面里 `GM_registerMenuCommand` 注册一条命令。 */
    fun registerUserScriptMenu(
        tabId: String,
        scriptId: String,
        commandId: String,
        label: String,
        accessKey: String = "",
    ) {
        if (commandId.isEmpty()) return
        val clean = label.trim().ifEmpty { "未命名命令" }.take(MAX_MENU_LABEL)
        // 只接受单个字母/数字：accessKey 在桌面端是菜单的键盘助记键，这里存下来
        // 供「脚本菜单」面板展示并支持外接键盘直接触发。
        val key = accessKey.trim().take(1).uppercase()
            .takeIf { it.isNotEmpty() && it[0].isLetterOrDigit() } ?: ""
        scriptMenus.getOrPut(tabId) { LinkedHashMap() }[menuKey(scriptId, commandId)] =
            UserScriptMenuCommand(tabId, scriptId, commandId, clean, key)
        publishMenus()
    }

    /** 页面里 `GM_unregisterMenuCommand` 移除一条命令。 */
    fun unregisterUserScriptMenu(tabId: String, scriptId: String, commandId: String) {
        val map = scriptMenus[tabId] ?: return
        if (map.remove(menuKey(scriptId, commandId)) == null) return
        if (map.isEmpty()) scriptMenus.remove(tabId)
        publishMenus()
    }

    /** 主框架导航 / 关标签时清空该页的命令：回调函数已经随旧文档销毁。 */
    private fun clearUserScriptMenus(tabId: String) {
        if (scriptMenus.remove(tabId) == null) return
        publishMenus()
    }

    /** 脚本被停用或删除时，摘掉它留下的命令。 */
    private fun pruneMenus(enabledIds: Set<String>) {
        var changed = false
        val iterator = scriptMenus.values.iterator()
        while (iterator.hasNext()) {
            val map = iterator.next()
            if (map.values.removeAll { it.scriptId !in enabledIds }) changed = true
            if (map.isEmpty()) {
                iterator.remove()
                changed = true
            }
        }
        if (changed) publishMenus()
    }

    /**
     * 执行一条菜单命令。
     *
     * 只查原生侧登记过的命令，再把命令 id 交给页面自己的分发器 —— 原生从不
     * 拼接脚本体，避免把任意字符串送进 `evaluateJavascript`。
     */
    fun invokeUserScriptMenu(tabId: String, scriptId: String, commandId: String): Boolean {
        val registered = scriptMenus[tabId]?.containsKey(menuKey(scriptId, commandId)) == true
        val wv = webViews[tabId]
        if (!registered || wv == null) return false
        wv.evaluateJavascript(
            "window.__lingGMMenu&&window.__lingGMMenu(${jsString(scriptId)},${jsString(commandId)})",
            null,
        )
        return true
    }

    /**
     * 把 GM 值的变更广播给**其它**标签页。
     *
     * 只发消息不落盘：[com.ling.browser.data.repo.UserScriptRepository] 已经写过了。
     * 发起页自己不需要广播 —— 它的 `V` 与监听器在 `GM_setValue` 里已同步更新。
     */
    fun broadcastUserScriptValue(
        scriptId: String,
        key: String,
        oldValue: Any?,
        newValue: Any?,
        exceptTabId: String?,
    ) {
        if (webViews.isEmpty()) return
        val js = "window.__lingGMValue&&window.__lingGMValue(" +
            "${jsString(scriptId)},${jsString(key)},${jsLiteral(oldValue)},${jsLiteral(newValue)},true)"
        for ((tabId, wv) in webViews) {
            if (tabId == exceptTabId) continue
            if (_tabs.value.firstOrNull { it.id == tabId }?.isIncognito == true) continue
            wv.evaluateJavascript(js, null)
        }
    }

    /** 通知点击回传页面里的 `onclick`（详情见 [com.ling.browser.ui.BrowserViewModel.postUserScriptNotification]）。 */
    fun dispatchUserScriptNotificationClick(tabId: String, scriptId: String, notificationId: String) {
        val wv = webViews[tabId] ?: return
        wv.evaluateJavascript(
            "window.__lingGMNotice&&window.__lingGMNotice(${jsString(scriptId)},${jsString(notificationId)},'click')",
            null,
        )
    }

    /**
     * 通知被点击后回传 `ondone`。
     *
     * Tampermonkey 里 `ondone` 在通知关闭时触发；Android 无法可靠监听"划掉通知"，
     * 因此在点击（必然伴随通知收起）时补一次 `done=true` —— 覆盖绝大多数用法。
     */
    fun dispatchUserScriptNotificationDone(tabId: String, scriptId: String, notificationId: String) {
        val wv = webViews[tabId] ?: return
        wv.evaluateJavascript(
            "window.__lingGMNotice&&window.__lingGMNotice(${jsString(scriptId)},${jsString(notificationId)},'done')",
            null,
        )
    }

    private fun publishMenus() {
        _userScriptMenus.value = scriptMenus.values
            .flatMap { it.values }
            .sortedWith(compareBy({ it.tabId }, { it.scriptId }))
    }

    private fun menuKey(scriptId: String, commandId: String): String = "$scriptId|$commandId"

    /** null / JSON null 都按 JS 的 `undefined` 传，与 Tampermonkey 的删除语义一致。 */
    private fun jsLiteral(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "undefined"
        is String -> jsString(value)
        is Number, is Boolean -> value.toString()
        else -> runCatching { JSONObject.wrap(value)?.toString() }.getOrNull() ?: "undefined"
    }

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
        loadUrl(tab.id, target)
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
            (wv.parent as? FrameLayout)?.removeView(wv)
            wv.stopLoading()
            wv.loadUrl("about:blank")
            wv.destroy()
        }
        // 用户脚本的两份簿记随标签一起清掉：句柄跟着 WebView 已销毁，
        // 注入记录留着只会让同名 tabId 复用时误判"这页已经跑过"。
        scriptHandlers.remove(id)
        injectedScripts.remove(id)
        scriptMenus.remove(id)
        publishMenus()
        // 该标签的待裁决 Safe Browsing 与控制台记录同样随标签一起清掉。
        cancelSafeBrowsingFor(id)
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
        tab?.let { readerContent.remove(it.id) }
        // 证书放行白名单同样随手清掉：逃生口不该比标签活得久
        tab?.let { certExceptions.remove(it.id) }
        // 还有未裁决的证书错误也一并取消：标签都没了，没人会再回答弹窗。
        tab?.let { cancelCertPromptsFor(it.id) }
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
        _activeId.value = id
        val wv = obtainWebView(id)
        host?.let { h ->
            h.removeAllViews()
            (wv.parent as? FrameLayout)?.removeView(wv)
            h.addView(wv)
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
        // 内置主页由原生渲染，绝不交给 WebView 解析协议 ——
        // 早期版本直接把 about:home 喂给 WebView，结果触发 onReceivedError 白屏。
        if (HomePage.isHomeUrl(url)) {
            loadHomeOrRefresh(id, wv)
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
            loadHome(id, wv)
            return
        }
        // 普通网页恢复 WebView 自身的不透明底（白色，与默认一致）：
        // 主页那层透明底是主页专用的，若带到普通网页上，
        // 页面自身的透明区域会露出下面的 Compose 背景，观感不对。
        wv.setOpaqueBackground(true)
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
    fun enterReaderMode(onResult: (ReaderResult) -> Unit = {}) {
        val id = _activeId.value
        val wv = webViews[id] ?: return
        val tab = _tabs.value.firstOrNull { it.id == id } ?: return

        // 主页/阅读视图自身不能提取，否则会递归。判据用 [InternalPage.isInternal]：
        // 它连 reload 后的 data: 形式也认得出（只看 wv.url 会把刷新过的主页当成普通网页）。
        if (InternalPage.isInternal(wv.url, logicalUrlOf(id))) {
            onResult(ReaderResult.NoContent("already_reader"))
            return
        }
        if (!settings.javaScriptEnabled) {
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
                    if (_activeId.value != id) return@evaluateJavascript
                    readerContent[id] = result
                    renderReader(id, result)
                }
                else -> onResult(result)
            }
        }
    }

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
     * 用阅读视图替换当前页面。
     *
     * 与主页一样用 loadDataWithBaseURL：自包含、不联网、主题色由原生注入。
     */
    private fun renderReader(id: String, result: ReaderResult.Ok) {
        val wv = webViews[id] ?: return
        updateTab(id) {
            it.copy(
                url = ReaderPage.URL,
                title = result.title.ifBlank { it.title },
                errorText = null,
                isLoading = false,
                progress = 100,
            )
        }
        // 阅读视图有自己烘好的主题底色，恢复 WebView 不透明底即可（与旧版一致）。
        wv.setOpaqueBackground(true)
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
        fontSize: ReaderPage.FontSize = settings.readerFontSize,
    ): String = ReaderPage.html(
        title = result.title,
        site = result.site,
        url = result.url,
        content = result.html,
        background = homeColors.background,
        onBackground = homeColors.onBackground,
        primary = homeColors.primary,
        primaryContainer = homeColors.primaryContainer,
        onPrimaryContainer = homeColors.onPrimaryContainer,
        dark = homeColors.dark,
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
        val id = _activeId.value
        val wv = webViews[id] ?: return
        // 不在阅读视图（例如刚切走）：什么都不用做，下次进入阅读模式会用新字号渲染。
        // 用 isReaderDocument 而不是 ReaderPage.isReaderUrl(wv.url)：阅读视图被
        // reload（刷新/进程重建）后 wv.url 是 data: 形式，只看 baseUrl 会漏判。
        if (!InternalPage.isReaderDocument(wv.url, logicalUrlOf(id))) return
        wv.evaluateJavascript(ReaderPage.fontSizeJs(fontSize.px), null)
    }

    /** 退出阅读模式：回到原文。 */
    fun exitReaderMode() {
        val id = _activeId.value
        val cached = readerContent[id] ?: return
        readerContent.remove(id)
        // 原文地址来自提取脚本（优先 canonical），正常情况下是 http(s)。
        // 万一它仍是个自定义协议，loadUrl 会拒绝加载，用户就**卡在阅读视图
        // 出不去**了 —— 所以这里退回网页历史：back 能回到进入阅读模式前的
        // 那个真实页面。再不行就只能留在原地，至少不白屏。
        if (isNavigable(cached.url)) {
            loadUrl(id, cached.url)
        } else {
            val wv = webViews[id]
            if (wv != null && wv.canGoBack()) {
                wv.goBack()
            } else {
                // 没有可回退的历史：退回主页，避免把用户困在一个
                // 已经"退出"了却还显示着的阅读页面上。
                loadHome(id, obtainWebView(id))
            }
        }
    }

    /** 当前标签是否处于阅读视图。 */
    fun isReaderActive(): Boolean = ReaderPage.isReaderUrl(activeTab?.url)

    /**
     * 阅读视图对应的**原文地址**（不在阅读模式时为 null）。
     *
     * 地址栏需要它：阅读视图的逻辑地址是合成的 `ling://reader`，
     * 既不能复制也没法改，拿去提交还会让 WebView 报 ERR_UNKNOWN_URL_SCHEME；
     * 进入编辑态时应该给出能用的真实地址（来源就是提取时缓存下来的 canonical）。
     */
    fun readerOriginalUrl(id: String = _activeId.value): String? = readerContent[id]?.url

    /**
     * 进入主页：**已经在主页上就原地刷新**，否则才完整渲染。
     *
     * 为什么不能一律 [loadHome]：`loadDataWithBaseURL` 每次调用都会往 WebView
     * 历史里压入一个新条目（见 [HomePage.themeUpdateJs] 的说明）。而「主页」是
     * 底部工具栏上的常驻按钮，用户很容易连点 —— 那样会攒下一串一模一样的历史条目：
     * 返回键是亮的，按一下却回到同一个主页（看着像"没反应"），要连按好几次才能退出主页。
     * 原地刷新只改 CSS 变量，不产生导航，行为与主题切换时完全一致。
     */
    private fun loadHomeOrRefresh(id: String, wv: LingWebView) {
        if (isHomeRendered(id, wv)) {
            // 原地刷新：主页的背景由原生层供色、HTML 与 WebView 自身都透明
            // （见 [HomePage.TRANSPARENT_BG]），这里只需把主题变量刷成当前配色。
            wv.setOpaqueBackground(false)
            wv.evaluateJavascript(homeThemeJs(), null)
            updateTab(id) {
                it.copy(
                    // 顺手把可能被带偏的状态补回来（见 [InternalPage.logicalUrl]）
                    url = HomePage.URL,
                    title = "主页",
                    errorText = null,
                    isLoading = false,
                    progress = 100,
                    // 主页是历史起点：停在主页时返回键始终不可用（见本函数说明）。
                    canGoBack = false,
                    canGoForward = wv.canGoForward(),
                )
            }
            return
        }
        loadHome(id, wv)
    }

    /**
     * 当前标签的逻辑地址（[TabState.url]）。
     *
     * 判定"WebView 现在这一页是哪种内部页"时要靠它：WebView 对内部页 reload
     * 后回写的 `data:` 地址本身分不出是主页还是阅读视图（见 [InternalPage]）。
     */
    private fun logicalUrlOf(id: String): String? =
        _tabs.value.firstOrNull { it.id == id }?.url

    /**
     * WebView 是否**已经真的渲染出主页**。
     *
     * 判据收敛到 [InternalPage.isHomeDocument]，它替我们处理两件容易出错的事：
     *   - **要求地址非空**：[HomePage.isHomeUrl] 按地址栏语义把 null / 空串也算作
     *     主页，而刚建出来、还没加载任何页面的 WebView `url` 正是 null ——
     *     拿它当"是不是已经在主页上"的判据，冷启动会跳过真正的渲染，主页停在空白页；
     *   - **认得出 reload 后的 `data:` 形式**：地址栏刷新 / 渲染进程重建后 WebView
     *     回写的是一长串 `data:text/html;base64,…`，只按 baseUrl 匹配会误判成
     *     "不在主页"，于是走 `wv.reload()` 重放同一份 data URL，状态被那一长串
     *     地址污染、原生背景层停画，页面发白。
     */
    private fun isHomeRendered(id: String, wv: LingWebView): Boolean =
        InternalPage.isHomeDocument(wv.url, logicalUrlOf(id))

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
        // 主页**始终**透明（WebView 与 HTML 都是）：背景一律由原生背景层画在
        // WebView 之下（纯色档画主题色、自定义图片档再叠照片）。
        // 这样从"WebView 建出来"到"HTML 首帧"那段异步窗口里，屏上是原生层的
        // 主题色，而不是 WebView 默认的白底 —— 冷启动的那段白闪正是从这里来的。
        wv.setOpaqueBackground(false)
        wv.loadDataWithBaseURL(
            HomePage.BASE_URL,
            HomePage.html(
                onBackground = homeColors.onBackground,
                primaryContainer = homeColors.primaryContainer,
                onPrimaryContainer = homeColors.onPrimaryContainer,
                dark = homeColors.dark,
                clock = clockText(),
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
    )

    /**
     * 由 UI 层在主题变化时写入。
     *
     * ⚠️ 这个字段必须在**创建 WebView 之前**就已是正确值，因为 [loadHome]
     * 渲染时直接读它。冷启动时 [newTab] 会先建好 WebView，若此时还是默认
     * 浅色配色，主页就会一直用浅色 —— 这正是"主页不跟随夜间模式"的成因。
     */
    var homeColors: HomeColors = HomeColors()

    /**
     * 给当前所有显示主页的标签页补渲染一次，**没有 WebView 的就先建出来**。
     *
     * 这里没有单独的 refreshHome()：早先版本的实现是
     * `webViews[tab.id]?.let { loadHome(...) }`，对"显示主页但 WebView
     * 尚未创建"的标签页会静默跳过 —— 而这恰恰是冷启动时的常态，
     * 主页于是停在默认浅色配色上，表现为"没有跟随夜间模式"。
     * 现在统一走 obtainWebView，直接消灭那条会静默失效的路径。
     */
    fun ensureHomeRendered() {
        _tabs.value.filter { HomePage.isHomeUrl(it.url) }.forEach { tab ->
            // 统一走 [loadHomeOrRefresh]（也就是「点主页按钮」那条路径），
            // 不要再在这里手写一份"原地刷新"：
            //   - 它会自己判断是否已经真的渲染出主页，冷启动 / 被 LRU 回收时
            //     走完整渲染（此时直接 evaluateJavascript 会打在 about:blank 上白注入）；
            //   - 它同时对齐 WebView 自身背景与标签状态。
            // 早先这里另写了一份分支，漏了 WebView 背景那一行，导致两条路径行为
            // 不一致 —— 改背景模式后主页仍是不透明白底，表现为"设了没生效、
            // 得手动按一下主页按钮"。
            loadHomeOrRefresh(tab.id, obtainWebView(tab.id))
        }
        // 阅读视图同样是原生渲染的自包含页面，也必须跟着重新配色 ——
        // 否则在阅读模式里切换夜间模式时，页面会停在旧配色。
        // 同样走原地刷新（只改 CSS 变量，不重排版、不压历史）。
        _tabs.value.filter { ReaderPage.isReaderUrl(it.url) }.forEach { tab ->
            val wv = webViews[tab.id]
            // 判据同 isReaderDocument：阅读视图被 reload 后 wv.url 是 data: 形式，
            // 只看 baseUrl 会漏判，切夜间模式时配色停在不更新。
            if (wv != null && InternalPage.isReaderDocument(wv.url, tab.url)) {
                wv.evaluateJavascript(readerThemeJs(), null)
            }
        }
    }

    /**
     * 主页 HTML 该用的背景色。
     *
     * 恒为 [HomePage.TRANSPARENT_BG]：主页的背景由原生背景层供色，见
     * [HomePage.TRANSPARENT_BG] 的说明。抽成一个取值点，保证"完整渲染"（[loadHome]）
     * 与"原地刷新"（[homeThemeJs]）用的是同一个背景。
     */
    private fun homeBackgroundColor(): String = HomePage.TRANSPARENT_BG

    /** 主页原地刷新的 JS：只改主题变量，见 [HomePage.themeUpdateJs]。 */
    private fun homeThemeJs(): String = HomePage.themeUpdateJs(
        background = homeBackgroundColor(),
        onBackground = homeColors.onBackground,
        primary = homeColors.primary,
        onPrimary = homeColors.onPrimary,
        primaryContainer = homeColors.primaryContainer,
        onPrimaryContainer = homeColors.onPrimaryContainer,
        dark = homeColors.dark,
    )

    /** 阅读视图原地刷新的 JS：与主页共用同一份脚本，见 [HomePage.themeUpdateJs]。 */
    private fun readerThemeJs(): String = HomePage.themeUpdateJs(
        background = homeColors.background,
        onBackground = homeColors.onBackground,
        primary = homeColors.primary,
        onPrimary = homeColors.onPrimary,
        primaryContainer = homeColors.primaryContainer,
        onPrimaryContainer = homeColors.onPrimaryContainer,
        dark = homeColors.dark,
    )

    /** 当前时间文本（`HH:mm`），供主页时钟使用。 */
    private fun clockText(): String =
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date())

    /**
     * 刷新显示中主页的时钟。
     *
     * 由 UI 层每分钟调用一次（见 BrowserScreen 的定时器）。主页是**零脚本** HTML，
     * 时间不能自己走，只能由原生侧把文本注入回去 —— 只改那一个文本节点，
     * 不碰 DOM 结构、不触发导航、不压历史。
     */
    fun updateHomeClock() {
        val js = HomePage.clockJs(clockText())
        _tabs.value.filter { HomePage.isHomeUrl(it.url) }.forEach { tab ->
            val wv = webViews[tab.id] ?: return@forEach
            if (isHomeRendered(tab.id, wv)) wv.evaluateJavascript(js, null)
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
        val wv = obtainWebView(id)
        // 主页是原生拼接的 HTML：配色是 [loadHome] 时烘进 `:root` 的内联变量。
        // 若直接 reload，会重新解析那份**旧** HTML，把配色拉回备份时的值
        // （切了夜间模式后再点刷新，主页会退回旧配色）。
        // 对主页改用重新渲染，保证配色与当前状态一致；普通网页照常 reload。
        if (isHomeRendered(id, wv)) {
            loadHomeOrRefresh(id, wv)
        } else {
            wv.reload()
        }
    }

    /**
     * 放行当前标签页的证书错误并重新导航（证书错误页的「继续访问」）。
     *
     * 只把**当前主机**加入本标签的临时白名单，然后重走一次导航：这一次
     * [LingWebViewClient.onReceivedSslError] 上报后，[handleSslError] 会发现
     * 白名单命中而直接 `proceed()`，不再弹窗。
     *
     * 用 [loadUrl] 而不是 `wv.reload()`：证书握手失败后 WebView 停在错误态，
     * `reload()` 未必会重新发起网络请求，显式导航更可靠。
     */
    fun proceedInsecure(id: String = _activeId.value) {
        val tab = _tabs.value.firstOrNull { it.id == id } ?: return
        val host = runCatching { Uri.parse(tab.url).host }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: return
        certExceptions.getOrPut(id) { mutableSetOf() }.add(host)
        loadUrl(id, tab.url)
    }

    /**
     * 网页证书校验失败：已放行过的主机直接 `proceed()`，否则排队弹窗。
     *
     * 主框架与子资源统一处理 —— 子资源静默取消会让页面"看起来正常但缺内容"。
     */
    private fun handleSslError(
        tabId: String,
        host: String,
        reason: String,
        mainFrame: Boolean,
        handler: SslErrorHandler,
    ) {
        if (host.isBlank()) {
            handler.cancel()
            return
        }
        if (certExceptions[tabId]?.contains(host) == true) {
            handler.proceed()
            // 放行 ≠ 安全：地址栏继续标「证书错误」。
            if (mainFrame) {
                updateTab(tabId) {
                    it.copy(security = SecurityState.INSECURE_CERT, errorText = null)
                }
            }
            return
        }
        // 坏页面可能一口气触发几十个子资源失败；超出队列上限的直接拒绝，
        // 不能让每个都挂一个永不兑现的 handler。
        if (certQueue.size >= MAX_PENDING_CERTS) {
            handler.cancel()
            return
        }
        certQueue.addLast(PendingCert(tabId, CertPrompt(host, reason, mainFrame), handler))
        showNextCert()
    }

    /**
     * 用户对待裁决证书错误的选择。
     *
     * [remember] 对应 Via 的「本次浏览过程中不再询问」：把主机加入本标签的
     * 白名单，之后同主机不再弹窗（关标签失效，不落盘）。
     */
    fun resolveCertPrompt(proceed: Boolean, remember: Boolean) {
        val item = certShown ?: return
        certShown = null
        if (proceed) {
            if (remember) {
                certExceptions.getOrPut(item.tabId) { mutableSetOf() }.add(item.prompt.host)
            }
            item.handler.proceed()
            if (item.prompt.mainFrame) {
                updateTab(item.tabId) {
                    it.copy(security = SecurityState.INSECURE_CERT, errorText = null)
                }
            }
        } else {
            item.handler.cancel()
            if (item.prompt.mainFrame) markCertBlocked(item.tabId, item.prompt.reason)
        }
        showNextCert()
    }

    /**
     * 把队列里所有待裁决项按拒绝处理并关掉弹窗。
     *
     * 用于 UI 销毁 / 关标签：这些 handler 已经没人能回答，留着会让 TLS
     * 握手永远悬空（页面停在半加载状态）。
     */
    fun cancelCertPrompts() {
        val items = ArrayList<PendingCert>()
        certShown?.let { items.add(it) }
        certShown = null
        while (true) {
            items.add(certQueue.removeFirstOrNull() ?: break)
        }
        for (item in items) {
            item.handler.cancel()
            if (item.prompt.mainFrame) markCertBlocked(item.tabId, item.prompt.reason)
        }
        onCertPrompt?.invoke(null)
    }

    /** 关标签时清理该标签的待裁决项（其余标签的继续保留）。 */
    private fun cancelCertPromptsFor(tabId: String) {
        certShown?.takeIf { it.tabId == tabId }?.let {
            it.handler.cancel()
            certShown = null
            showNextCert()
        }
        val iterator = certQueue.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            if (item.tabId == tabId) {
                item.handler.cancel()
                iterator.remove()
            }
        }
    }

    /** 队列里挑一条展示；没有待裁决项时向 UI 发 null 关掉弹窗。 */
    private fun showNextCert() {
        if (certShown == null) {
            while (true) {
                val next = certQueue.removeFirstOrNull() ?: break
                val listener = onCertPrompt
                if (listener == null) {
                    // UI 还没接线：这条没人能回答，只能拒绝。
                    next.handler.cancel()
                    if (next.prompt.mainFrame) markCertBlocked(next.tabId, next.prompt.reason)
                    continue
                }
                certShown = next
                break
            }
        }
        onCertPrompt?.invoke(certShown?.prompt)
    }

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

    /**
     * Safe Browsing 命中：排队弹窗，由用户决定继续还是返回。
     *
     * 队列上限很小 —— 主框架命中本来就是低频事件；超出时直接退回安全页，
     * 不能让 callback 悬空（悬空等于 WebView 一直等在那里）。
     */
    private fun handleSafeBrowsingHit(
        tabId: String,
        url: String,
        mainFrame: Boolean,
        threatType: Int,
        callback: SafeBrowsingResponse,
    ) {
        if (safeQueue.size >= MAX_PENDING_SAFE) {
            callback.backToSafety(true)
            return
        }
        val host = runCatching { Uri.parse(url).host }.getOrNull().orEmpty()
        safeQueue.addLast(
            PendingSafeBrowsing(
                SafeBrowsingPrompt(tabId, host, safeThreatLabel(threatType), mainFrame),
                callback,
            ),
        )
        showNextSafe()
    }

    /** 用户对 Safe Browsing 警告的选择：true = 继续访问，false = 返回安全页。 */
    fun resolveSafeBrowsing(proceed: Boolean) {
        val item = safeShown ?: return
        safeShown = null
        if (proceed) item.callback.proceed(false) else item.callback.backToSafety(true)
        showNextSafe()
    }

    /** UI 销毁时把待裁决项全部按「返回安全页」兑现，避免 callback 悬空。 */
    fun cancelSafeBrowsing() {
        val items = ArrayList<PendingSafeBrowsing>()
        safeShown?.let { items.add(it) }
        safeShown = null
        while (true) {
            items.add(safeQueue.removeFirstOrNull() ?: break)
        }
        for (item in items) item.callback.backToSafety(true)
        onSafeBrowsingPrompt?.invoke(null)
    }

    /** 关标签时只清理该标签的待裁决项。 */
    private fun cancelSafeBrowsingFor(tabId: String) {
        safeShown?.takeIf { it.prompt.tabId == tabId }?.let {
            it.callback.backToSafety(true)
            safeShown = null
            showNextSafe()
        }
        val iterator = safeQueue.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            if (item.prompt.tabId == tabId) {
                item.callback.backToSafety(true)
                iterator.remove()
            }
        }
    }

    private fun showNextSafe() {
        if (safeShown == null) {
            while (true) {
                val next = safeQueue.removeFirstOrNull() ?: break
                if (onSafeBrowsingPrompt == null) {
                    // UI 还没接线：没人能回答，直接退回安全页。
                    next.callback.backToSafety(true)
                    continue
                }
                safeShown = next
                break
            }
        }
        onSafeBrowsingPrompt?.invoke(safeShown?.prompt)
    }

    private fun safeThreatLabel(threatType: Int): String = when (threatType) {
        WebViewClient.SAFE_BROWSING_THREAT_MALWARE -> "恶意软件"
        WebViewClient.SAFE_BROWSING_THREAT_PHISHING -> "钓鱼网站"
        WebViewClient.SAFE_BROWSING_THREAT_UNWANTED_SOFTWARE -> "有害软件"
        // SAFE_BROWSING_THREAT_BILLING（值为 4）在部分编译 SDK 里没有公开常量。
        4 -> "可能有害的应用"
        else -> "危险网站"
    }

    // ---------------------------------------------------------------- 页面控制台错误

    /**
     * 记录一条 ERROR 级控制台消息。
     *
     * 只做**内存**留存（每页最多 [MAX_CONSOLE_ERRORS] 条），新页导航即清空：
     * 它用于让用户看见"页面/脚本报了什么错"，不是调试器，不需要持久化。
     */
    private fun recordConsoleError(tabId: String, message: String, source: String, line: Int) {
        if (message.isBlank()) return
        val list = consoleByTab.getOrPut(tabId) { mutableListOf() }
        val last = list.lastOrNull()
        // 同一个循环里反复报同一条错误时不要刷屏。
        if (last != null && last.message == message && last.line == line) return
        list.add(ConsoleEntry(tabId, message.take(MAX_CONSOLE_MESSAGE), source, line))
        while (list.size > MAX_CONSOLE_ERRORS) list.removeAt(0)
        publishConsoleErrors()
    }

    /** 清空某标签页的控制台错误（主框架导航 / 关闭标签时调用）。 */
    fun clearConsoleErrors(tabId: String) {
        if (consoleByTab.remove(tabId) == null) return
        publishConsoleErrors()
    }

    private fun publishConsoleErrors() {
        _consoleErrors.value = consoleByTab.values.flatten()
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

            // 这个 WebView 是新建的（或刚从 LRU 里被回收后重建），
            // document-start 的注册随之丢失，必须重新挂上。
            registerDocumentStart(id, wv)
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    updateTab(id) { it.copy(progress = newProgress) }
                }

                override fun onReceivedTitle(view: WebView?, title: String?) {
                    // 主页标题固定为「主页」：WebView 会报 <title>翎</title>，
                    // 那个是页面标题，不适合出现在标签列表里。
                    // 用 isHomeDocument：主页 reload 后 view.url 是 data: 形式，
                    // 只看 baseUrl 会漏判，主页被页面标题「翎」顶掉。
                    val isHomePage = InternalPage.isHomeDocument(view?.url, logicalUrlOf(id))
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

                // window.open() → 新标签。
                //
                // 不在这里 newTab：onCreateWindow 的契约是"返回一个 WebView 让
                // 父 WebView 把子页面加载进去"。我们给一个临时 WebView 接住，
                // 等它的 onPageStarted 拿到目标 URL 后再 newTab，然后销毁临时
                // WebView —— 这样 window.open 的目标地址（含 JS 跳转后的最终 URL）
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
                    if (!isUserGesture) return false
                    val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                    val tempWebView = LingWebView(context)
                    var opened = false
                    tempWebView.webViewClient = object : WebViewClient() {
                        override fun onPageStarted(
                            view: WebView?, url: String?, favicon: Bitmap?,
                        ) {
                            if (opened) return
                            opened = true
                            url?.let { this@WebTabManager.newTab(it, incognito) }
                            view?.post { view.destroy() }
                        }
                    }
                    transport.webView = tempWebView
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
                    // 新一次主框架导航：用户脚本的"本页已注入"闸门清零。
                    // 不放在 onPageFinished 里清 —— 那会把同页的第二次回调当成新页。
                    injectedScripts.remove(id)
                    // 脚本菜单的回调绑在旧文档上，导航后必然失效，整批清掉。
                    clearUserScriptMenus(id)
                    // 控制台错误属于旧文档，新页从零开始记。
                    clearConsoleErrors(id)
                    // 内置主页与阅读视图都是用 loadDataWithBaseURL 加载的，
                    // WebView 回调回来的是 baseUrl（HomePage.BASE_URL /
                    // ReaderPage.BASE_URL）而不是逻辑地址（ling://home /
                    // ling://reader）。这里必须挡住这次回写，否则：
                    //   1. TabState.url 被改写成 baseUrl，主页与阅读视图的
                    //      识别（以及 ensureHomeRendered 的过滤）会全部失效；
                    //   2. canGoBack/canGoForward 被重置，返回键会闪一下。
                    //
                    // 自定义协议（zhihu:// 等）同样不写回：它已经被
                    // shouldOverrideUrlLoading 交给系统应用，页面并没有真的
                    // 导航过去。若把它记进 TabState.url，地址栏会显示一个
                    // 打不开的地址，退出阅读模式时还会被回填给 WebView，
                    // 直接触发 ERR_UNKNOWN_URL_SCHEME。
                    //
                    // ⚠️ 判据必须用 [InternalPage.isInternal]，不能只看 baseUrl：
                    // reload（刷新 / 渲染进程重建）后回调报回来的是一长串
                    // `data:text/html;base64,…`（见 [InternalPage.isDataReloadForm]），
                    // 只看 baseUrl 会把它当成普通网页写进 TabState.url —— 地址栏
                    // 显示那一长串 base64，主页也不再被认作主页（背景层停画、发白）。
                    // 传入当前标签的逻辑地址，以便区分是主页还是阅读视图的 data 形式。
                    val currentUrl = logicalUrlOf(id)
                    val isInternal = InternalPage.isInternal(url, currentUrl)
                    val isHome = InternalPage.isHomeDocument(url, currentUrl)
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
                            // （见 onMixedContent / handleSslError）。
                            security = SecurityState.baseForUrl(storedUrl),
                            isLoading = !isInternal,
                            errorText = null,
                            // 主页是历史起点：任何一次回调都不能把返回键点亮。
                            // loadDataWithBaseURL 每次调用都会往历史栈压条目，
                            // 之后 onPageStarted/Finished 读到 wv.canGoBack() 是 true，
                            // 会覆盖掉 loadHome/loadHomeOrRefresh 写下的 false ——
                            // 这里显式再挡一次，保证"停在主页 ⟺ 不可返回"始终成立。
                            canGoBack = if (isHome) false else canGoBack(),
                            canGoForward = canGoForward(),
                        )
                    }
                },
                onPageFinished = { url, title ->
                    // 阅读视图与主页同理：它在状态里的 url 是逻辑地址 ling://reader，
                    // 不能让回调把它改写成 ReaderPage.BASE_URL。
                    // 自定义协议同样不写回（原因见 onPageStarted）。
                    // reload 后的 data: 形式同样按逻辑地址归一（见 onPageStarted 说明）。
                    val currentUrl = logicalUrlOf(id)
                    val isInternal = InternalPage.isInternal(url, currentUrl)
                    val isHome = InternalPage.isHomeDocument(url, currentUrl)
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
                    // 内部页（主页 / 阅读视图）反过来：文档就绪后要按**当前**配色
                    // 重新着色一次。它们的颜色是"烘"进 HTML 字符串里的，任何一次
                    // 文档重建只要没经过 applyHomeTheme，页面就停在烘死时的旧配色 ——
                    // 地址栏刷新（reload 重放同一份 data URL）、渲染进程被系统回收后
                    // 重建、历史前进/后退到旧条目都会走到这里。
                    // 原地刷新只改 :root 上的 CSS 变量，不触发导航、不压历史。
                    if (isInternal) {
                        // 主页的 WebView 底必须保持透明：背景由原生层供色
                        // （见 HomeBackgroundLayer）。若上一页是普通网页，WebView 底
                        // 已被 loadUrl 设回不透明白色，这里必须重置，否则原生背景层
                        // 会被白底盖住 —— 表现为"从网页退回主页后背景发白"。
                        // 阅读视图自带不透明底色（renderReader 设的），保持原样即可。
                        if (isHome) wv.setOpaqueBackground(false)
                        wv.evaluateJavascript(
                            if (isHome) homeThemeJs() else readerThemeJs(),
                            null,
                        )
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
                        injectUserScripts(id, wv, url, ScriptRunAt.END)
                        injectUserScripts(id, wv, url, ScriptRunAt.IDLE)

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
                    if (!InternalPage.isInternal(url, logicalUrlOf(id))) {
                        injectUserScripts(id, wv, url, ScriptRunAt.END)
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
                    handleSslError(id, host, reason, mainFrame, handler)
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
                    handleSafeBrowsingHit(id, url, mainFrame, threatType, callback)
                },
                onExternalScheme = { uri -> onExternalUri?.invoke(uri) ?: false },
                // 主页搜索框：只把查询串转出去，判定交给 UI 层（UrlUtils + 搜索引擎）
                onInternalSearch = { query -> onSearchRequested?.invoke(query) },
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
            // document-start 的注册句柄跟着实例一起失效，先清掉记录避免重复移除。
            scriptHandlers.remove(tabId)?.forEach { handler ->
                runCatching { handler.remove() }
            }
            injectedScripts.remove(tabId)
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
        // ⚠️ 主页/阅读视图的 `wv.url` 是 loadDataWithBaseURL 的 **baseUrl**（reload 后
        // 还会变成一长串 `data:text/html;base64,…`），不是写进 TabState 的逻辑地址。
        // 原样写回会让同一个页面在状态里出现两种 url 值，会话快照里还会存下
        // https://home.ling.invalid/ 这种永远解析不了的假地址 ——
        // 所有"把实时地址写回状态"的地方都先过 InternalPage 归一化，并把**当前标签
        // 的逻辑地址**作为线索传进去：reload 后的 data: 形式要靠它才分得清是主页
        // 还是阅读视图（也就才能写回正确的逻辑地址，而不是丢掉线索原样写回 base64）。
        val live = wv.url?.trim().orEmpty()
        updateTab(id) { tab ->
            tab.copy(
                url = InternalPage.logicalUrl(live, tab.url) ?: tab.url,
                // 内部页的标题是**语义标题**（主页固定「主页」、阅读视图是文章标题）。
                // 这里若照抄 wv.title，主页会被页面自己的 <title>翎</title> 覆盖掉，
                // 在标签列表里变成"翎" —— 与 onReceivedTitle 的处理保持一致。
                title = if (InternalPage.isInternal(live, tab.url)) {
                    tab.title
                } else {
                    wv.title?.takeIf { t -> t.isNotBlank() } ?: tab.title
                },
                // 主页是历史起点：切回主页标签时返回键同样必须不可用（与
                // loadHome / 页面回调三处保持一致）。
                canGoBack = if (InternalPage.isHomeDocument(live, tab.url)) false else wv.canGoBack(),
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
        scriptMenus.clear()
        publishMenus()
        // UI 即将销毁：待裁决项全部退回安全页，控制台记录也没有留存的必要。
        cancelSafeBrowsing()
        consoleByTab.clear()
        publishConsoleErrors()
    }

    companion object {
        /** 同时存活的 WebView 实例上限。Via 这类轻量浏览器也维持类似的少量实例。 */
        private const val MAX_WEBVIEWS = 6

        /** 注入到页面的 GM 桥对象名（`window.LingGM`），与 [scriptWrapper] 里的垫片对应。 */
        private const val USER_SCRIPT_BRIDGE = "LingGM"

        /**
         * 证书错误排队上限。
         *
         * 子资源也弹窗后，一个引用了几十张坏证书图片的页面会连续上报；
         * 队列封顶后其余直接取消，避免内存里堆满永不兑现的 handler。
         */
        private const val MAX_PENDING_CERTS = 8

        /** 表单重提交的待裁决上限：低频事件，超过就直接放弃重提交。 */
        private const val MAX_PENDING_FORMS = 4

        /** 菜单文案上限：脚本可能塞进整段说明，界面行撑不下。 */
        private const val MAX_MENU_LABEL = 60

        /** Safe Browsing 待裁决上限：低频事件，超过就直接退回安全页。 */
        private const val MAX_PENDING_SAFE = 4

        /** 每个标签页保留的控制台错误条数。 */
        private const val MAX_CONSOLE_ERRORS = 30

        /** 单条控制台错误的展示长度，避免长堆栈把面板撑爆。 */
        private const val MAX_CONSOLE_MESSAGE = 500
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
