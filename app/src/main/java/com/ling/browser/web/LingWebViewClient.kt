package com.ling.browser.web

import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Message
import android.webkit.ClientCertRequest
import android.webkit.HttpAuthHandler
import android.webkit.RenderProcessGoneDetail
import android.webkit.SafeBrowsingResponse
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.RequiresApi
import java.io.ByteArrayInputStream

/**
 * 页面导航回调。
 *
 * 所有状态变更通过 lambda 回调给 [WebTabManager]，由它统一写回 [TabState]，
 * 避免 WebView 直接持有 ViewModel 造成生命周期泄漏。
 */
class LingWebViewClient(
    private val isIncognito: Boolean,
    private val onPageStarted: (url: String) -> Unit,
    private val onPageFinished: (url: String, title: String) -> Unit,
    /**
     * 首帧已提交（`onPageCommitVisible`）。
     *
     * 用户脚本里 `@run-at document-end` 的注入点：这是最早能保证文档存在的
     * 时机，比 `onPageFinished`（要等全部资源）贴近 DOMContentLoaded 得多。
     */
    private val onPageVisible: (url: String) -> Unit = {},
    private val onProgress: (progress: Int) -> Unit,
    private val onReceivedTitle: (title: String) -> Unit,
    private val onReceivedIcon: (Bitmap?) -> Unit,
    private val onError: (url: String, description: String) -> Unit,
    /**
     * 是否把非 http(s) 链接交给外部 App。
     *
     * 默认关。关掉时这类导航被静默吃掉，连 `onExternalScheme` 都不会调用 ——
     * 本机没装对应 App 时"交给系统"只会失败，失败后还容易把 WebView 的
     * 导航状态搞乱（真机日志表现为渲染进程被拆掉重建、页面卡在中间态）。
     * 关掉等于绕开整条转发路径。
     */
    private val openExternal: () -> Boolean = { false },
    /**
     * 是否启用广告拦截。
     *
     * 默认开。开关动态生效：拦截发生在每次请求的 `shouldInterceptRequest`
     * 里，每次都现读这个 lambda，因此改完设置立即生效，无需重建 WebView。
     */
    private val adBlockEnabled: () -> Boolean = { true },
    /** 用户自定义的广告拦截域名规则（裸域名）。动态读，改完立即生效。 */
    private val adBlockRules: () -> Set<String> = { emptySet() },
    /**
     * 拦下一个请求时回调一次，供调用方计数（见 [WebTabManager.blockedCount]）。
     *
     * 回调跑在 `shouldInterceptRequest` 的**后台线程**上，且每个命中的子资源
     * 都会触发一次 —— 因此接收方必须自己保证线程安全，且不能在这里做重活。
     */
    private val onAdBlocked: () -> Unit = {},
    /** 返回 true 表示已由外部接管（例如外部应用打开），WebView 不应继续加载。 */
    private val onExternalScheme: (Uri) -> Boolean,
    /**
     * 点开 `.user.js` 安装链接时的回调。
     *
     * 原始 JS 当网页渲染没有意义，这里直接取消导航，交给 UI 层弹安装确认。
     */
    private val onUserScriptLink: (url: String) -> Unit = {},
    /**
     * 检测到混合内容（HTTPS 页面里混入 HTTP 子资源）时回调一次。
     *
     * 回调发生在 `shouldInterceptRequest` 的后台线程上，因此每个页面只会
     * 上报一次（见 [mixedReported]），接收方需自行保证线程安全。
     */
    private val onMixedContent: () -> Unit = {},
    /**
     * 证书校验失败（主框架与子资源都算）。
     *
     * 不再替调用方决定 `proceed()` / `cancel()`：交给 [com.ling.browser.web.WebTabManager]
     * 统一排队、弹窗，用户确认后才兑现 handler。子资源也要上报 ——
     * 只静默取消的话，页面看起来"正常"，实际少了图片/脚本，用户毫无感知。
     *
     * @param host 握手失败的主机
     * @param reason 失败原因（人类可读，见 [certErrorReason]）
     * @param mainFrame 是否主框架（`onReceivedSslError` 没有该参数，按主机名比对推断）
     * @param handler 必须且只能兑现一次
     */
    private val onSslErrorRequest: ((
        host: String,
        reason: String,
        mainFrame: Boolean,
        handler: SslErrorHandler,
    ) -> Unit)? = null,
    /**
     * 当前标签的主框架 URL。
     *
     * 用来判定证书错误是否来自**主框架本身**：`onReceivedSslError` 没有
     * `isForMainFrame` 参数，而主框架握手失败时 `view.url` 往往还停在上一个
     * 页面，因此以 [TabState.url]（`loadUrl` / `onPageStarted` 已写入目标地址）
     * 作为参照更可靠。
     */
    private val currentUrl: () -> String? = { null },
    /**
     * 渲染进程崩溃（`onRenderProcessGone`）。
     *
     * 渲染进程是按进程共享的：崩一次，同一进程里**所有** WebView 都不可用，
     * 只能销毁重建。因此这里只把"哪个实例死了"报上去，销毁与重建由
     * [WebTabManager] 统一做（它才持有实例表与宿主容器）。
     */
    private val onRendererGone: (WebView) -> Unit = {},
    /**
     * HTTP 基础认证（401 + `WWW-Authenticate`）。
     *
     * ⚠️ 这个回调属于 **WebViewClient**，不是 WebChromeClient（容易记错）。
     * 传进来的 lambda 内部动态读委托，为 null 时必须 `cancel()` ——
     * 否则请求一直悬挂、页面停在 401。
     */
    private val onHttpAuthRequest: ((host: String, realm: String, handler: HttpAuthHandler) -> Unit)? =
        null,
    /**
     * 客户端证书请求（mTLS）。
     *
     * 企业内网 / 银行站点常要求客户端证书。不接管时**必须显式 `cancel()`**：
     * 父类默认就是取消，但把默认写出来才能保证"UI 尚未接线"的窗口期不会
     * 留下一个悬空请求（页面会一直停在握手阶段，没有任何提示）。
     * 证书选择交给系统 KeyChain 对话框，由 UI 层接线。
     */
    private val onClientCertRequest: ((ClientCertRequest) -> Unit)? = null,
    /**
     * POST 结果页刷新时的重提交询问（`onFormResubmission`）。
     *
     * WebView 默认什么都不做 —— 刷新会让页面停在原处，看起来"点了没反应"。
     * 两个 [Message] 是二选一的开关，接管方必须且只能 `sendToTarget()` 一个。
     */
    private val onFormResubmission: ((WebView, Message, Message) -> Unit)? = null,
    /**
     * Safe Browsing 命中危险站点。
     *
     * 默认实现直接 `backToSafety(true)` —— 用户只看到页面"退回去了"，
     * 完全不知道原因。这里交给 UI 弹窗，让用户明确选择继续还是返回。
     *
     * @param callback 必须且只能兑现一次（`backToSafety` / `proceed`）
     */
    private val onSafeBrowsingHit: ((
        url: String,
        mainFrame: Boolean,
        threatType: Int,
        callback: SafeBrowsingResponse,
    ) -> Unit)? = null,
) : WebViewClient() {

    /**
     * 本页是否已经上报过混合内容。
     *
     * `shouldInterceptRequest` 会为**每一个**子资源（每张图、每个音频）触发一次，
     * 不加这个闸门就会把同一次混合内容重复上报成百上千次。每次主框架开始加载
     * （[onPageStarted]）清零。
     *
     * 跨线程访问：写入在 main 线程的 onPageStarted，读取在后台的
     * shouldInterceptRequest，故用 @Volatile。
     */
    @Volatile
    private var mixedReported = false

    /**
     * 拦截资源请求 —— 广告拦截的唯一入口。
     *
     * 返回非 null 的 [WebResourceResponse] 即表示"用这个响应替换原本要发的请求"。
     * 拦截广告时返回一个**空响应**（204/空体），既不加载广告资源，也把
     * 广告脚本/跟踪像素的请求整个吞掉，比加载后再隐藏更干净、更省流量。
     *
     * 两个不能碰的边界：
     *  1. **主文档永不拦截**（见 [AdBlocker.shouldBlock] 的说明），
     *     否则广告落地页会整页白屏。
     *  2. **内置页不拦**：主页是原生 Compose 渲染（WebView 里只有 about:blank 占位），
     *     阅读视图 ling://reader 是 loadDataWithBaseURL 渲染的自包含内容，本就没有
     *     广告子请求，但为了绝对安全，非 http(s) 一律走 AdBlocker 内部的 scheme 判断放行。
     */
    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?,
    ): WebResourceResponse? {
        if (request == null) return null
        val url = request.url?.toString() ?: return null

        // 混合内容检测：主框架是 https，而这个子资源却是明文 http。
        //
        // WebView 没有「本页存在混合内容」的官方回调，只能在请求经过时旁路观察。
        // 被 WebView 自己拦掉的**主动**混合内容（脚本 / iframe / XHR）不会走到
        // 这里，能稳定观测到的是**实际发出的被动**混合内容（图片 / 音视频）——
        // 而这恰恰是最该提醒用户的那类。
        //
        // ⚠️ 这里**绝不能碰 WebView 实例**：shouldInterceptRequest 跑在后台线程，
        // 而 WebView 的方法（哪怕只是 `view.url`）内部都有 checkThread()，
        // 在非创建线程上调用会抛 Throwable 直接把进程带走。主框架的协议因此
        // 取自 [currentUrl]（读的是线程安全的 StateFlow），而不是 `view.url`。
        //
        // 这段判断必须在广告拦截的 early-return **之前**：两者互不影响，
        // 广告拦截关掉时混合内容照样要检测。
        if (!request.isForMainFrame &&
            !mixedReported &&
            url.startsWith("http://", ignoreCase = true) &&
            currentUrl()?.startsWith("https://", ignoreCase = true) == true
        ) {
            mixedReported = true
            onMixedContent()
        }

        if (!adBlockEnabled()) return null
        if (AdBlocker.shouldBlock(url, request.isForMainFrame, adBlockRules())) {
            // 计数点选在这里：只有真正拦下的请求才 +1，放行的不计，
            // 因此和用户看到的"挡掉多少"完全一致。
            onAdBlocked()
            return WebResourceResponse("text/plain", "utf-8", EMPTY_STREAM)
        }
        return null
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        // 新一次主框架导航开始：混合内容的"每页只报一次"闸门在这里清零。
        mixedReported = false
        // WebView 在开始加载时就会把它**已知**的站点图标交出来。
        // 之前这个参数被直接丢掉了，导致标签缩略图一直没有数据 ——
        // 光实现 WebChromeClient.onReceivedIcon 是不够的：
        // 那个回调只在 WebView 真正拿到图标时才触发，很多站点要等
        // 页面解析到 <link rel="icon"> 才给，时序上晚得多。
        // 两个来源都接上，缩略图出现得更早也更可靠。
        onReceivedIcon(favicon)
        url?.let(onPageStarted)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        if (url == null) return
        val title = view?.title.orEmpty()
        onReceivedTitle(title)
        onPageFinished(url, title)

        // 页面加载完成后补一次进度，避免进度条卡在 90%
        onProgress(100)
    }

    override fun onPageCommitVisible(view: WebView?, url: String?) {
        super.onPageCommitVisible(view, url)
        onProgress(100)
        url?.let(onPageVisible)
    }

    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
        super.doUpdateVisitedHistory(view, url, isReload)
        url?.let(onPageStarted)
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?,
    ) {
        super.onReceivedError(view, request, error)
        // 只处理主框架的错误，子资源失败不该弹错误页
        if (request?.isForMainFrame != true) return
        val desc = error?.description?.toString().orEmpty().ifBlank { "页面加载失败" }
        onError(request.url.toString(), desc)
    }

    override fun onReceivedHttpError(
        view: WebView?,
        request: WebResourceRequest?,
        errorResponse: WebResourceResponse?,
    ) {
        super.onReceivedHttpError(view, request, errorResponse)
        if (request?.isForMainFrame != true) return
        val code = errorResponse?.statusCode ?: return
        // 4xx/5xx 才提示；3xx 由 WebView 自行跟随
        if (code >= 400) {
            onError(request.url.toString(), "HTTP $code")
        }
    }

    /**
     * 证书校验失败。
     *
     * 两个容易踩的点：
     *  1. `onReceivedSslError` **没有** `isForMainFrame` 参数，只能用失败 URL 的
     *     主机名与当前主框架主机名比对来推断；主框架握手失败时 `view.url`
     *     常常还停在上一个页面，故优先看 [currentUrl]。
     *  2. 子资源失败同样要上报。旧实现直接 `cancel()`，结果是页面看似正常、
     *     实则少了图片/脚本，用户完全不知道证书有问题（Via 等浏览器会弹窗）。
     *
     * handler 的兑现完全交给调用方：未接线时立即取消，避免握手悬空。
     */
    override fun onReceivedSslError(
        view: WebView?,
        handler: SslErrorHandler?,
        error: SslError?,
    ) {
        val h = handler ?: return
        val failingHost = error?.url?.let { Uri.parse(it).host }.orEmpty()
        val mainHost = (currentUrl() ?: view?.url)?.let { Uri.parse(it).host }
        val mainFrame = failingHost.isNotEmpty() && failingHost == mainHost
        val reason = certErrorReason(error?.primaryError ?: 0)
        val delegate = onSslErrorRequest
        if (delegate == null) {
            h.cancel()
        } else {
            delegate(failingHost, reason, mainFrame, h)
        }
    }

    /**
     * Safe Browsing 命中（恶意软件 / 钓鱼 / 有害应用等）。
     *
     * 不接管时交回父类：默认会 `backToSafety(true)`，至少能保证页面被拦下。
     * 接管后必须由 UI 兑现 callback，且只能兑现一次。
     */
    @RequiresApi(Build.VERSION_CODES.O_MR1)
    override fun onSafeBrowsingHit(
        view: WebView,
        request: WebResourceRequest,
        threatType: Int,
        callback: SafeBrowsingResponse,
    ) {
        val delegate = onSafeBrowsingHit
        if (delegate == null) {
            super.onSafeBrowsingHit(view, request, threatType, callback)
            return
        }
        delegate(request.url.toString(), request.isForMainFrame, threatType, callback)
    }

    /**
     * HTTP 基础认证。
     *
     * 父类默认实现就是取消；这里显式写出，保证接管时也能兜住"UI 尚未接线"
     * 的窗口期（例如启动恢复标签时）。
     */
    override fun onReceivedHttpAuthRequest(
        view: WebView?,
        handler: HttpAuthHandler?,
        host: String?,
        realm: String?,
    ) {
        val authHandler = handler ?: return
        val delegate = onHttpAuthRequest
        if (delegate == null) {
            authHandler.cancel()
            return
        }
        delegate(host.orEmpty(), realm.orEmpty(), authHandler)
    }

    /**
     * 客户端证书（mTLS）。
     *
     * `proceed()` / `cancel()` 必须且只能调用一次 —— 由 UI 层在用户选完证书
     * （或取消系统对话框）后兑现；这里只负责把请求转交出去。
     */
    override fun onReceivedClientCertRequest(view: WebView?, request: ClientCertRequest?) {
        val req = request ?: return
        val delegate = onClientCertRequest
        if (delegate == null) {
            req.cancel()
            return
        }
        delegate(req)
    }

    /**
     * POST 结果页刷新。
     *
     * 没有接管方（或 `view` 已失效）时直接放弃重提交：两个 Message 一个都不发
     * 会让加载永远等下去，页面卡在"刷新中"。
     */
    override fun onFormResubmission(view: WebView?, dontResend: Message, resend: Message) {
        val delegate = onFormResubmission
        if (delegate == null || view == null) {
            dontResend.sendToTarget()
            return
        }
        delegate(view, dontResend, resend)
    }

    /**
     * 渲染进程崩溃。
     *
     * 必须返回 true 表示"已接管"：不处理时系统会**直接杀掉应用进程**
     * （官方文档原话：the application will be killed）。崩溃后的 WebView
     * 已不可再用，这里绝不能再碰它除 `destroy()` 之外的任何方法。
     */
    override fun onRenderProcessGone(
        view: WebView?,
        detail: RenderProcessGoneDetail?,
    ): Boolean {
        view?.let(onRendererGone)
        return true
    }

    override fun shouldOverrideUrlLoading(
        view: WebView?,
        request: WebResourceRequest?,
    ): Boolean {
        val uri = request?.url ?: return false
        return handleUri(view, uri)
    }

    // 这是 WebViewClient 的**旧版**重载（API 24 起被带 WebResourceRequest
    // 的版本取代），但必须保留：minSdk 是 23，24 以下只会调这一个。
    //
    // 两个注解缺一不可：
    //   @Deprecated  —— 抑制"覆盖了废弃成员却未标注废弃"的编译告警
    //   @Suppress    —— 抑制我们自己调用它时产生的废弃告警
    @Deprecated("minSdk 23 仍需要旧版重载，见上")
    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
        val uri = url?.let(Uri::parse) ?: return false
        return handleUri(view, uri)
    }

    private fun handleUri(view: WebView?, uri: Uri): Boolean {
        // 用户脚本安装链接：取消导航（不把原始 JS 当网页显示），交给 UI 层确认安装。
        if (isUserScriptUrl(uri.toString())) {
            onUserScriptLink(uri.toString())
            return true
        }

        // 判定统一走 UrlScheme：那是一条纯粹的字符串规则（可否交给 WebView 加载），
        // 抽出去之后能被单元测试完整覆盖。
        if (!UrlScheme.shouldTakeOver(uri.toString())) return false

        // 走到这里就是"WebView 加载不了、必须由我们接管"的协议。
        val scheme = uri.scheme?.lowercase().orEmpty()
        if (scheme == "ling") {
            // 内置主页与阅读视图：原生渲染，不交给系统
            // （否则会被系统当成未知协议，弹一个"没有应用可打开"）。
            return true
        }

        // 其余（zhihu: / weixin: / tel: / mailto: / intent: …）尝试交给系统。
        //
        // ⚠️ 关键点：无论外部是否成功，这里都必须返回 true，**不能**把
        // onExternalScheme 的结果直接返回。返回 false 等于告诉 WebView
        // "我没处理，你来加载" —— 而 WebView 不认识这些协议，结果是
        // `net::ERR_UNKNOWN_URL_SCHEME` 错误页；若页面反复发起该导航，
        // 就会在"尝试加载 → 报错"之间反复，表现为**一直闪**。
        // 系统打不开时宁可安静地什么都不做。
        //
        // 开关关掉时连尝试都不做：本机没装对应 App 时它本来就会失败，
        // 而失败后的副作用比"什么都不发生"糟糕得多。
        if (openExternal()) {
            onExternalScheme(uri)
        }
        return true
    }

    companion object {
        /** 拦截广告时返回的空输入流（空响应体）。 */
        private val EMPTY_STREAM = ByteArrayInputStream(ByteArray(0))
    }
}

/**
 * 把 `SslError.primaryError` 翻成人类可读的失败原因。
 *
 * 抽成**顶层 internal 函数**而不是类的 private 成员：这是一段纯映射，
 * 不该依赖 WebView 才能测（见 `LingWebViewClientTest`）。
 */
internal fun certErrorReason(primaryError: Int): String = when (primaryError) {
    SslError.SSL_NOTYETVALID -> "证书尚未生效"
    SslError.SSL_EXPIRED -> "证书已过期"
    SslError.SSL_IDMISMATCH -> "证书与域名不匹配"
    SslError.SSL_UNTRUSTED -> "证书不受信任"
    SslError.SSL_DATE_INVALID -> "证书日期无效"
    else -> "证书无效"
}
