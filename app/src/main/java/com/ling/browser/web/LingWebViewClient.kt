package com.ling.browser.web

import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
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
    /** 返回 true 表示已由外部接管（例如外部应用打开），WebView 不应继续加载。 */
    private val onExternalScheme: (Uri) -> Boolean,
) : WebViewClient() {

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
     *  2. **内置页不拦**：主页 ling://home、阅读视图 ling://reader 是
     *     loadDataWithBaseURL 渲染的自包含内容，本就没有广告子请求，
     *     但为了绝对安全，非 http(s) 一律走 AdBlocker 内部的 scheme 判断放行。
     */
    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?,
    ): WebResourceResponse? {
        if (request == null) return null
        val url = request.url?.toString() ?: return null
        if (!adBlockEnabled()) return null
        if (AdBlocker.shouldBlock(url, request.isForMainFrame)) {
            return WebResourceResponse("text/plain", "utf-8", EMPTY_STREAM)
        }
        return null
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
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
