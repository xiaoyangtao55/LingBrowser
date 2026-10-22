package com.ling.browser.web

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
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

    /**
     * 长按命中链接 / 图片 / 媒体资源时的回调，由 [com.ling.browser.web.WebTabManager]
     * 设置。返回的菜单由 UI 层渲染。
     *
     * 为什么需要自己识别：现代 Chromium WebView **不带**链接与图片的长按菜单
     * （旧版 WebKit 才有），长按正文只会进入文字选择。浏览器要给"新标签页打开 /
     * 复制链接地址"这类动作，只能自己接管。
     *
     * 这里刻意不用 `setOnLongClickListener`：那个回调依赖 View 的长按计时器，
     * 而 WebView 的触摸处理在 Chromium 内部（`AwContents`）另走一套手势识别，
     * 两者并不总是对齐；基于 [onTouchEvent] 自己跑 [GestureDetector] 则与
     * WebView 版本无关，行为可预测。
     */
    var onLongPress: ((HitTarget) -> Unit)? = null

    /** 本次手势是否已弹出过长按菜单（用于吞掉随后的 ACTION_UP）。 */
    private var longPressHandled = false

    private val longPressDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            // 必须返回 true：返回 false 时 GestureDetector 不再跟踪本次手势，
            // onLongPress 永远不会触发。
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onLongPress(e: MotionEvent) {
                val hit = hitTestResult ?: return
                // 命中目标为空（输入框、正文、电话/邮箱…）时什么都不做，
                // 把长按留给 WebView 自己 —— 那里是文字选择，抢走会弄坏复制/粘贴。
                val target = hitTargetOf(hit.type, hit.extra) ?: return
                val handler = onLongPress ?: return

                longPressHandled = true
                // 把触摸点换算成**窗口坐标**，菜单据此贴着手指弹出。
                // e.x / e.y 是 WebView 内部坐标，必须先加上它在窗口中的位置。
                val origin = IntArray(2)
                getLocationInWindow(origin)
                handler(
                    target.copy(
                        anchorX = origin[0] + e.x.toInt(),
                        anchorY = origin[1] + e.y.toInt(),
                    ),
                )
                // 触感反馈要和系统长按一致，否则用户会以为没按上
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }
        },
    )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 只"观察"不消费：手势检测器拿一份事件，WebView 自己照常处理，
        // 滚动、点击、双指缩放都不受影响（用户一滑动，检测器就会自行取消）。
        longPressDetector.onTouchEvent(event)

        if (event.actionMasked == MotionEvent.ACTION_UP && longPressHandled) {
            // 吞掉长按之后的抬起：否则这次抬起会被 WebView 当成一次点击，
            // 菜单刚弹出、页面就跳走了。
            longPressHandled = false
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN ||
            event.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            longPressHandled = false
        }
        return super.onTouchEvent(event)
    }

    /**
     * 依据设置应用 WebSettings。切设置后需重新调用。
     *
     * @param darkTheme 是否把网页内容暗化。由调用方在运行时算出
     *   `forceDarkWebPages && 夜间模式已解析为深色`，传进来的是**最终结果**
     *   而非中间开关 —— 这样"夜间模式关掉时网页立刻变亮"能在这里统一处理。
     *   内部页（主页/阅读视图）由 [injectDarkMode] 自己拦掉，调用方不必区分。
     */
    @Suppress("DEPRECATION")
    fun applySettings(settings: LingSettings, incognito: Boolean, darkTheme: Boolean) {
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
            // 让 window.open() 走 WebChromeClient.onCreateWindow，而不是在
            // 当前标签覆盖加载。配合 WebTabManager 里的 onCreateWindow 实现，
            // 把新页面作为新标签打开，行为与桌面浏览器一致。
            setSupportMultipleWindows(true)
            mediaPlaybackRequiresUserGesture = false
            // 定位开关：默认关时 `navigator.geolocation` 连询问回调都不会触发，
            // 网页拿到的直接就是 PERMISSION_DENIED。真正是否放行由
            // WebChromeClient.onGeolocationPermissionsShowPrompt 决定。
            @Suppress("DEPRECATION")
            setGeolocationEnabled(true)

            // 无图模式：省流
            blockNetworkImage = settings.blockImages
            loadsImagesAutomatically = !settings.blockImages

            // 桌面模式：切换 UA 字符串
            userAgentString = settings.userAgent.ifBlank { defaultUserAgent }

            // 混合内容：现代站点多为 https，保留兼容模式
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }

        // 网页夜间模式。setForceDark 在 API 29 引入、API 33 起废弃
        // （改用 setAlgorithmicDarkeningAllowed），按版本分别调用。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            this.settings.isAlgorithmicDarkeningAllowed = darkTheme
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            this.settings.forceDark = if (darkTheme) {
                WebSettings.FORCE_DARK_ON
            } else {
                WebSettings.FORCE_DARK_OFF
            }
        }

        // 立即把暗化 CSS 注入/移除到**当前已加载的页面**：
        // 上面两行系统级暗化对已经渲染出来的内容往往要等下次加载才生效，
        // 若只靠它，"切夜间模式网页不变"就会显得慢半拍（组件切换不同步）。
        injectDarkMode(darkTheme)

        // ⚠️ 这里**刻意不碰** Cookie 策略与无痕清理：
        //   - CookieManager 是进程级单例，在"遍历所有 WebView"的调用里各设一次，
        //     结果由最后一次调用决定（策略会随创建顺序漂移）→ 见 [applyCookiePolicy]；
        //   - 无痕的 clearHistory/clearCache/clearFormData 放在这里，会让用户在无痕
        //     标签页里**改任何设置**都清空它的前进/后退历史 → 见 [resetForIncognito]。
        // 两者都由 WebTabManager 在"创建 WebView / 切换标签"这两个正确的时机调用。
    }

    /**
     * 无痕标签的**一次性**清理：不保留任何持久化痕迹。
     *
     * 只在创建 WebView 时调用（见 `WebTabManager.obtainWebView`）。
     *
     * ⚠️ 早先这段清理写在 [applySettings] 里，而 applySettings 会在**每次设置变化**时
     * 遍历所有 WebView 跑一遍 —— 于是用户在无痕标签里切一下夜间模式、换个搜索引擎，
     * 它的前进/后退历史就被清空（返回键失效）、缓存被清空（整页重新下载）。
     */
    fun resetForIncognito() {
        clearHistory()
        clearCache(true)
        clearFormData()
    }

    /**
     * 应用 Cookie 策略：无痕不接受 Cookie，普通标签恢复接受。
     *
     * ⚠️ `CookieManager` 是**进程级**单例，没有"按 WebView 隔离"这回事。
     * 早先它在 [applySettings] 的遍历里按每个 WebView 各设一次，等于"最后一个
     * WebView 决定全局"，于是创建顺序一变策略就跑偏：
     *   - 普通标签丢登录态（全局 Cookie 被关掉）；
     *   - 或者无痕标签又接受了 Cookie，隐私承诺静默失效。
     * 现在改为**策略跟着当前激活的那个标签走**：创建 WebView 时设一次
     * （只在该标签就是激活标签时），每次切换标签再对齐一次
     * （见 `WebTabManager.syncCookiePolicy`）。
     */
    fun applyCookiePolicy(incognito: Boolean) {
        CookieManager.getInstance().setAcceptCookie(!incognito)
    }

    /** 获取 WebView 默认 UA，用于在「跟随系统」时还原。 */
    private val defaultUserAgent: String
        get() = WebSettings.getDefaultUserAgent(context)

    /**
     * 设置 WebView **自身**的背景是否不透明。
     *
     * 为什么需要：主页在「自定义图片」模式下把 HTML 背景改成透明、
     * 由原生层在 WebView 之下来画背景，但 WebView 本身默认有一层不透明白底 ——
     * 只让 HTML 透明是不够的，白底仍会把下面的图片整个盖住。
     *
     * [opaque] 为 true 时用白色，与 WebView 的默认底色一致，因此切回普通网页/阅读视图
     * 时行为与旧版完全相同；为 false 时透明，让原生背景层透出。
     */
    fun setOpaqueBackground(opaque: Boolean) {
        setBackgroundColor(
            if (opaque) android.graphics.Color.WHITE else android.graphics.Color.TRANSPARENT,
        )
    }

    /**
     * 注入 / 移除强制夜间模式的 CSS。
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
     * **内部页（内置主页 / 阅读视图）在这里被统一拦掉**，调用方不需要各自判断：
     * 它们的配色由 CSS 变量精确控制，套一层 invert 只会把主题整个反过来 ——
     * 深色主页（`--bg:#111412`）会被反成亮底（`#EEEBED`），正好与预期相反。
     * 判据必须落在 WebView 自己身上：`applySettings` 会遍历**所有** WebView，
     * 每个实例当前停在哪一页只有它自己知道，在调用点判一定会漏。
     *
     * [darken] 为 false 时**移除**已注入的样式，而不是只"不注入"：
     * 早期版本只注入不移除，导致关掉夜间模式后页面残留 `__ling_dark__`
     * 样式，切回浅色模式网页还是暗的 —— 组件切换不同步的又一根因。
     * 内部页同理走移除分支：万一历史版本已经注入过，这里会顺手清掉。
     */
    fun injectDarkMode(darken: Boolean) {
        // 内部页永远是"不要兜底暗化"，与调用方传什么无关
        val enabled = darken && !InternalPage.isInternal(url)
        evaluateJavascript(if (enabled) DARK_CSS_JS else REMOVE_DARK_CSS_JS, null)
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

        /** 移除已注入的夜间模式样式（关掉夜间模式时调用）。 */
        private val REMOVE_DARK_CSS_JS = """
            (function() {
              var s = document.getElementById('__ling_dark__');
              if (s && s.parentNode) s.parentNode.removeChild(s);
            })();
        """.trimIndent()

        /** 取回当前滚动位置，用于标签页切换后恢复。 */
        const val JS_GET_SCROLL_Y = "window.scrollY"
    }
}
