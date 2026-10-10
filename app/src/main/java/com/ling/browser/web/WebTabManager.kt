package com.ling.browser.web

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebStorage
import android.webkit.WebView
import android.widget.FrameLayout
import com.ling.browser.data.db.TabSnapshot
import com.ling.browser.data.prefs.LingSettings
import com.ling.browser.data.prefs.NightMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

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

    /** tabId -> WebView。仅在内存中存活，不参与状态序列化。 */
    private val webViews = LinkedHashMap<String, LingWebView>()

    /** 标签页切换时用于承载 WebView 的宿主容器。 */
    private var host: FrameLayout? = null

    private var settings: LingSettings = LingSettings()

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

    /** 需要 UI 层处理的副作用（打开外部应用、下载等）。 */
    var onExternalUri: ((Uri) -> Boolean)? = null

    /** 每次页面访问的回调，用于写历史。 */
    var onVisited: ((url: String, title: String, incognito: Boolean) -> Unit)? = null

    /** 下载请求回调（由 UI 层弹出确认或直接交给系统下载器）。 */
    var onDownloadRequested: ((url: String, mimeType: String?) -> Unit)? = null

    /**
     * 主页搜索框提交的回调（查询串，可能是一句搜索词也可能是个网址）。
     *
     * 判定"网址还是搜索词"要 UrlUtils + 用户选的搜索引擎，那是 UI 层的事；
     * 这里只负责把网页提交上来的字符串转出去。
     */
    var onSearchRequested: ((query: String) -> Unit)? = null

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
    fun newTab(url: String? = null, incognito: Boolean = settings.incognito): TabState {
        val target = url ?: settings.homepage
        val tab = TabState(url = target, isIncognito = incognito)
        _tabs.update { it + tab }
        switchTo(tab.id)
        loadUrl(tab.id, target)
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
        val remaining = _tabs.value.filterNot { it.id == id }
        webViews.remove(id)?.let { wv ->
            (wv.parent as? FrameLayout)?.removeView(wv)
            wv.stopLoading()
            wv.loadUrl("about:blank")
            wv.destroy()
        }

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
        updateTab(id) { it.copy(url = url, errorText = null, isLoading = true, progress = 0) }
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

        if (HomePage.isHomeUrl(wv.url) || ReaderPage.isReaderUrl(wv.url)) {
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

        if (HomePage.isHomeUrl(wv.url) || ReaderPage.isReaderUrl(wv.url)) {
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
        // 不在阅读视图（例如刚切走）：什么都不用做，下次进入阅读模式会用新字号渲染
        if (!ReaderPage.isReaderUrl(wv.url)) return
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
     * 原地刷新只改 CSS 变量与快捷入口，不产生导航，行为与主题切换时完全一致。
     */
    private fun loadHomeOrRefresh(id: String, wv: LingWebView) {
        if (isHomeRendered(wv)) {
            wv.evaluateJavascript(homeThemeJs(), null)
            updateTab(id) {
                it.copy(
                    // 顺手把可能被带偏的状态补回来（见 [InternalPage.logicalUrl]）
                    url = HomePage.URL,
                    title = "主页",
                    errorText = null,
                    isLoading = false,
                    progress = 100,
                    canGoBack = wv.canGoBack(),
                    canGoForward = wv.canGoForward(),
                )
            }
            return
        }
        loadHome(id, wv)
    }

    /**
     * WebView 是否**已经真的渲染出主页**。
     *
     * 不能直接对 `wv.url` 用 [HomePage.isHomeUrl]：那个函数按地址栏语义把
     * null / 空串也算作主页，而刚建出来、还没加载任何页面的 WebView `url`
     * 正是 null —— 拿它当"是不是已经在主页上"的判据，冷启动会跳过真正的渲染，
     * 主页停在空白页。因此这里额外要求地址非空。
     */
    private fun isHomeRendered(wv: LingWebView): Boolean {
        val u = wv.url?.trim().orEmpty()
        return u.isNotEmpty() && HomePage.isHomeUrl(u)
    }

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
        wv.loadDataWithBaseURL(
            HomePage.BASE_URL,
            HomePage.html(
                background = homeColors.background,
                onBackground = homeColors.onBackground,
                primary = homeColors.primary,
                onPrimary = homeColors.onPrimary,
                primaryContainer = homeColors.primaryContainer,
                onPrimaryContainer = homeColors.onPrimaryContainer,
                dark = homeColors.dark,
                links = homeColors.links,
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
        val links: List<Pair<String, String>> = emptyList(),
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
            val existing = webViews[tab.id]
            // 关键：只有当 WebView 已经**真的渲染出主页**（url 落到 BASE_URL）
            // 才走原地刷新。冷启动时首帧前 WebView 还是 about:blank，此时
            // evaluateJavascript 会打在空文档上、白注入一次 —— 必须走完整渲染。
            if (existing != null && isHomeRendered(existing)) {
                // 已渲染的主页：原地刷新主题变量与快捷入口，**不重导航**。
                // 重导航会压入新历史条目，导致"切深色后按返回回到浅色主页"。
                existing.evaluateJavascript(homeThemeJs(), null)
            } else {
                // 冷启动 / 被 LRU 回收后重建：完整渲染。
                // 此时 WebView 尚无内容，无所谓历史，loadDataWithBaseURL 只产生一个条目。
                loadHome(tab.id, obtainWebView(tab.id))
            }
        }
        // 阅读视图同样是原生渲染的自包含页面，也必须跟着重新配色 ——
        // 否则在阅读模式里切换夜间模式时，页面会停在旧配色。
        // 同样走原地刷新（只改 CSS 变量，不重排版、不压历史）。
        _tabs.value.filter { ReaderPage.isReaderUrl(it.url) }.forEach { tab ->
            val wv = webViews[tab.id]
            if (wv != null && ReaderPage.isReaderUrl(wv.url)) {
                wv.evaluateJavascript(readerThemeJs(), null)
            }
        }
    }

    /** 主页原地刷新的 JS：主题变量 + 快捷入口，见 [HomePage.themeUpdateJs]。 */
    private fun homeThemeJs(): String = HomePage.themeUpdateJs(
        background = homeColors.background,
        onBackground = homeColors.onBackground,
        primary = homeColors.primary,
        onPrimary = homeColors.onPrimary,
        primaryContainer = homeColors.primaryContainer,
        onPrimaryContainer = homeColors.onPrimaryContainer,
        dark = homeColors.dark,
        linksHtml = HomePage.linksHtml(homeColors.links),
    )

    /** 阅读视图原地刷新的 JS：只改主题变量（快捷入口传空）。 */
    private fun readerThemeJs(): String = HomePage.themeUpdateJs(
        background = homeColors.background,
        onBackground = homeColors.onBackground,
        primary = homeColors.primary,
        onPrimary = homeColors.onPrimary,
        primaryContainer = homeColors.primaryContainer,
        onPrimaryContainer = homeColors.onPrimaryContainer,
        dark = homeColors.dark,
        linksHtml = "",
    )

    fun reload(id: String = _activeId.value) {
        obtainWebView(id).reload()
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
            // 注意：在 LingWebView 的 apply 作用域里，裸写 `settings` 会解析成
            // WebView.settings（WebSettings），必须显式限定到本类的字段。
            applySettings(this@WebTabManager.settings, incognito, this@WebTabManager.shouldDarkenPages())
            // 无痕的清理只做**一次**（放在 applySettings 里会在每次设置变化时清空它的历史）
            if (incognito) resetForIncognito()
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    updateTab(id) { it.copy(progress = newProgress) }
                }

                override fun onReceivedTitle(view: WebView?, title: String?) {
                    // 主页标题固定为「主页」：WebView 会报 <title>翎</title>，
                    // 那个是页面标题，不适合出现在标签列表里。
                    val isHomePage = HomePage.isHomeUrl(view?.url)
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
            }
            webViewClient = LingWebViewClient(
                isIncognito = incognito,
                onPageStarted = { url ->
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
                    val isInternal = HomePage.isHomeUrl(url) || ReaderPage.isReaderUrl(url)
                    val isHome = HomePage.isHomeUrl(url)
                    val keep = isInternal || !isNavigable(url)
                    updateTab(id) {
                        it.copy(
                            // 三种情况分开处理，不能简单"keep 就保留旧 url"：
                            //  - 回到主页（历史前进/后退触发）：必须写成逻辑地址
                            //    ling://home，否则旧网站 URL 残留在 TabState.url，
                            //    地址栏就一直显示那个网址；
                            //  - 阅读视图 / 自定义协议：保留既有逻辑地址；
                            //  - 普通页面：写回真实 url。
                            url = when {
                                isHome -> HomePage.URL
                                keep -> it.url
                                else -> url
                            },
                            isLoading = !isInternal,
                            errorText = null,
                            canGoBack = canGoBack(),
                            canGoForward = canGoForward(),
                        )
                    }
                },
                onPageFinished = { url, title ->
                    // 阅读视图与主页同理：它在状态里的 url 是逻辑地址 ling://reader，
                    // 不能让回调把它改写成 ReaderPage.BASE_URL。
                    // 自定义协议同样不写回（原因见 onPageStarted）。
                    val isInternal = HomePage.isHomeUrl(url) || ReaderPage.isReaderUrl(url)
                    val isHome = HomePage.isHomeUrl(url)
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
                            canGoBack = canGoBack(),
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

                        // 历史记录只记真实网址。阅读视图的 url 是合成的
                        // ling://reader，主页是 ling://home —— 两者都没有记录价值，
                        // 记进去只会在历史列表里留下点不开的条目。
                        // 注意：它们对应的**原文地址**早在进入阅读模式前就已记过，
                        // 所以这里跳过不会漏记。
                        onVisited?.invoke(url, title, incognito)
                    }
                },
                onProgress = { p -> updateTab(id) { it.copy(progress = p) } },
                onReceivedTitle = { t ->
                    // 主页保留「主页」这个标题；阅读视图的标题已是文章标题，
                    // 让它跟随 <title> 更新即可，但合成的 ling://reader 不该被覆盖。
                    val cur = _tabs.value.firstOrNull { it.id == id }
                    if (!HomePage.isHomeUrl(cur?.url) && !ReaderPage.isReaderUrl(cur?.url)) {
                        updateTab(id) { it.copy(title = t) }
                    }
                },
                onReceivedIcon = { icon -> applyFavicon(id, icon) },
                onError = { _, desc ->
                    updateTab(id) { it.copy(isLoading = false, errorText = desc) }
                },
                onExternalScheme = { uri -> onExternalUri?.invoke(uri) ?: false },
                // 主页搜索框：只把查询串转出去，判定交给 UI 层（UrlUtils + 搜索引擎）
                onInternalSearch = { query -> onSearchRequested?.invoke(query) },
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
                adBlockRules = { this@WebTabManager.settings.adBlockRules },
            )
            setDownloadListener { url, _, _, mimeType, _ ->
                onDownloadRequested?.invoke(url, mimeType)
            }
        }

        webViews[id] = wv
        // Cookie 策略是进程级的：新建的 WebView 只有正好是当前激活标签时才动全局策略，
        // 后台标签（例如 ensureHomeRendered 给非激活标签补建实例）等切过去时再对齐。
        if (id == _activeId.value) syncCookiePolicy(id)
        enforceCacheLimit()
        return wv
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
        // ⚠️ 主页/阅读视图的 `wv.url` 是 loadDataWithBaseURL 的 **baseUrl**，不是写进
        // TabState 的逻辑地址。原样写回会让同一个页面在状态里出现两种 url 值，
        // 会话快照里还会存下 https://home.ling.invalid/ 这种永远解析不了的假地址 ——
        // 所有"把实时地址写回状态"的地方都先过 InternalPage 归一化。
        val live = wv.url?.trim().orEmpty()
        val internalPage = InternalPage.isInternal(live)
        updateTab(id) {
            it.copy(
                url = InternalPage.logicalUrl(live) ?: it.url,
                // 内部页的标题是**语义标题**（主页固定「主页」、阅读视图是文章标题）。
                // 这里若照抄 wv.title，主页会被页面自己的 <title>翎</title> 覆盖掉，
                // 在标签列表里变成"翎" —— 与 onReceivedTitle 的处理保持一致。
                title = if (internalPage) {
                    it.title
                } else {
                    wv.title?.takeIf { t -> t.isNotBlank() } ?: it.title
                },
                canGoBack = wv.canGoBack(),
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
    }

    companion object {
        /** 同时存活的 WebView 实例上限。Via 这类轻量浏览器也维持类似的少量实例。 */
        private const val MAX_WEBVIEWS = 6
    }
}
