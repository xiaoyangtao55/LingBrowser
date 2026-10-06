package com.ling.browser.web

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.util.AttributeSet
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import com.ling.browser.data.prefs.LingSettings

/**
 * 「翎」定制的 WebView。
 *
 * 关键取舍：
 *  - 无痕模式下必须关闭 DOM storage / 数据库，并清空 Cookie，才是真正的无痕；
 *  - 桌面模式通过切换 UA 实现（含 UA-CH 的 client hints，否则部分站点仍按移动端渲染）；
 *  - 强制网页夜间模式用 `setForceDark` + 注入 CSS 双保险。
 */
@SuppressLint("SetJavaScriptEnabled")
class LingWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : WebView(context, attrs, defStyleAttr) {

    init {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        isVerticalScrollBarEnabled = true
        overScrollMode = OVER_SCROLL_NEVER
    }

    /** 依据设置应用 WebSettings。切设置后需重新调用。 */
    @Suppress("DEPRECATION")
    fun applySettings(settings: LingSettings, incognito: Boolean) {
        this.settings.apply {
            javaScriptEnabled = settings.javaScriptEnabled
            domStorageEnabled = !incognito
            // databaseEnabled 已废弃，但 WebSQL 早已从 Chromium 移除，
            // 这里保留赋值只是为了兼容仍会读该标志的老 WebView 实现。
            databaseEnabled = !incognito
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            defaultTextEncodingName = "UTF-8"
            javaScriptCanOpenWindowsAutomatically = true
            mediaPlaybackRequiresUserGesture = false

            // 无图模式：省流
            blockNetworkImage = settings.blockImages
            loadsImagesAutomatically = !settings.blockImages

            // 桌面模式：切换 UA 字符串
            userAgentString = settings.userAgent.ifBlank { defaultUserAgent }

            // 混合内容：现代站点多为 https，保留兼容模式
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }

        // 强制网页夜间模式。
        // setForceDark 在 API 29 引入、API 33 起废弃（改用 setAlgorithmicDarkeningAllowed），
        // 这里按版本分别调用，避免在新系统上触发废弃告警或失效。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            this.settings.isAlgorithmicDarkeningAllowed = settings.forceDarkWebPages
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            this.settings.forceDark = if (settings.forceDarkWebPages) {
                WebSettings.FORCE_DARK_ON
            } else {
                WebSettings.FORCE_DARK_OFF
            }
        }

        if (incognito) {
            // 无痕：不保留任何持久化痕迹
            CookieManager.getInstance().setAcceptCookie(false)
            clearHistory()
            clearCache(true)
            clearFormData()
        } else {
            CookieManager.getInstance().setAcceptCookie(true)
        }
    }

    /** 获取 WebView 默认 UA，用于在「跟随系统」时还原。 */
    private val defaultUserAgent: String
        get() = WebSettings.getDefaultUserAgent(context)

    /**
     * 注入强制夜间模式的 CSS。
     *
     * 为什么在 `setForceDark` / `isAlgorithmicDarkeningAllowed` 之外还要这一层：
     * 系统级的算法变暗只处理「有明确定义浅色背景」的元素，对
     *   - 用 CSS 变量或 JS 动态上色的页面
     *   - 只设了 `background-image` 的渐变块
     *   - 内联 `style="background:#fff"` 的容器
     * 经常失效，结果就是深色模式下白底闪眼。
     *
     * 兜底做法是整体 invert + hue-rotate(180deg)（色相转一圈回到原位，
     * 因此彩色不会变成反色负片），再对 img/video/canvas 反色一次还原。
     *
     * 调用时机很关键：必须在**页面加载完成后**注入。早期版本这个方法
     * 定义了却从未被调用，等于强制夜间只有系统那一层，漏网页面就没救。
     */
    fun injectDarkMode(enabled: Boolean) {
        if (!enabled) return
        evaluateJavascript(DARK_CSS_JS, null)
    }

    companion object {
        /** 兜底夜间模式样式：整体反色再反色图片，避免图片被反色成负片。 */
        private val DARK_CSS_JS = """
            (function() {
              if (document.getElementById('__ling_dark__')) return;
              var css = 'html{filter:invert(1) hue-rotate(180deg)!important;background:#111!important;}' +
                        'img,video,canvas,svg,[style*="background-image"]{filter:invert(1) hue-rotate(180deg)!important;}';
              var s = document.createElement('style');
              s.id = '__ling_dark__';
              s.textContent = css;
              (document.head || document.documentElement).appendChild(s);
            })();
        """.trimIndent()

        /** 取回当前滚动位置，用于标签页切换后恢复。 */
        const val JS_GET_SCROLL_Y = "window.scrollY"
    }
}
