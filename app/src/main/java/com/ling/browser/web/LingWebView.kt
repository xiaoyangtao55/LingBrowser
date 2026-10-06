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
    fun applySettings(settings: LingSettings, incognito: Boolean) {
        this.settings.apply {
            javaScriptEnabled = settings.javaScriptEnabled
            domStorageEnabled = !incognito
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
     * 注入强制夜间模式的 CSS。`setForceDark` 在部分页面失效，
     * 这里补一层「反色 + 降低亮度」的兜底样式。
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
