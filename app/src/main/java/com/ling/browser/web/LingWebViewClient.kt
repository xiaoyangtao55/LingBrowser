package com.ling.browser.web

import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient

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
    /** 返回 true 表示已由外部接管（例如外部应用打开），WebView 不应继续加载。 */
    private val onExternalScheme: (Uri) -> Boolean,
) : WebViewClient() {

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
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

    @Suppress("DEPRECATION")
    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
        val uri = url?.let(Uri::parse) ?: return false
        return handleUri(view, uri)
    }

    private fun handleUri(view: WebView?, uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase().orEmpty()
        return when (scheme) {
            // 站内导航交给 WebView 自己处理
            "http", "https", "file", "about", "data", "blob" -> false
            // 内置主页：原生渲染，不交给系统（否则会被当成未知协议报错）
            "ling" -> false
            // 其余交给系统（tel: mailto: intent: 等）
            else -> onExternalScheme(uri)
        }
    }
}
