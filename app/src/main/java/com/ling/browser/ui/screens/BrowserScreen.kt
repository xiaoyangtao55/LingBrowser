package com.ling.browser.ui.screens

import com.ling.browser.LingApplication
import com.ling.browser.ui.theme.LingIcons
import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.security.KeyChain
import android.view.View
import android.view.ViewGroup
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.lifecycle.viewModelScope
import androidx.compose.ui.window.PopupProperties
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ling.browser.data.prefs.NightMode
import com.ling.browser.ui.BrowserViewModel
import com.ling.browser.ui.PendingDownload
import com.ling.browser.ui.components.AddressBar
import com.ling.browser.ui.components.BottomToolbar
import com.ling.browser.ui.components.ConsoleSheet
import com.ling.browser.ui.components.DesktopModeBanner
import com.ling.browser.ui.components.HomeBackgroundLayer
import com.ling.browser.ui.components.IncognitoBanner
import com.ling.browser.ui.components.MoreItem
import com.ling.browser.ui.components.MoreSheet
import com.ling.browser.ui.components.ScriptMenuSheet
import com.ling.browser.ui.components.TabsSheet
import com.ling.browser.util.UrlUtils
import com.ling.browser.web.SecurityState
import com.ling.browser.web.CertPrompt
import com.ling.browser.web.FormResubmitPrompt
import com.ling.browser.web.JsDialogRequest
import com.ling.browser.web.SafeBrowsingPrompt
import com.ling.browser.web.WebAuthRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 浏览器主界面：地址栏 + WebView 容器 + 底部工具栏。
 *
 * WebView 的宿主用一个 [FrameLayout]，由 [BrowserViewModel.tabManager] 负责挂载/切换实例。
 * 这样切标签只是换 View，不会重建 WebView。
 *
 * 布局分工：
 *  - 地址栏右侧常驻「刷新/停止」——最高频操作，放在触手可及处；
 *  - 底部工具栏只留 5 个动作，低频项收进「更多」；「更多」与「标签页」
 *    都以 BottomSheet（[ModalBottomSheet]）形式自底部升起；
 *  - 内置主页由 Compose 渲染（见 [HomeScreen]），WebView 里留一个 about:blank 占位，
 *    因此主页也能正常参与前进/后退历史。
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel,
    onOpenBookmarks: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSniff: () -> Unit,
    /**
     * 本页是否接管返回键。
     *
     * 必须由调用方显式给出（而不是依赖"谁后注册谁生效"）：
     * 当有二级页面在栈上时传 false，把返回键让给路由层，
     * 这样"设置页返回却退出应用"的问题不会因为组合顺序变化而复现。
     */
    canHandleBack: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val tabs by viewModel.tabs.collectAsStateWithLifecycle()
    val activeId by viewModel.activeId.collectAsStateWithLifecycle()
    val addressText by viewModel.addressText.collectAsStateWithLifecycle()
    val addressEditing by viewModel.addressEditing.collectAsStateWithLifecycle()
    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()
    val isBookmarked by viewModel.isBookmarked.collectAsStateWithLifecycle()

    val activeTab = tabs.firstOrNull { it.id == activeId }

    // 主页原生背景层是否**绘制**：只要当前标签停在内置主页就画。
    // 纯色档画主题背景色、自定义图片档在它之上叠照片与遮罩 —— 主页期间
    // WebView 自身背景是透明的，主页的背景颜色全部由这一层给。
    // 切到普通网页后必须停止绘制，否则网页自身的透明区域会露出下面这层色。
    // （组件本身仍无条件调用，见下面 Box 里的说明。）
    val activeUrl = activeTab?.url
    val homeBackgroundVisible = activeUrl != null && UrlUtils.isHome(activeUrl)
    val pendingDownload by viewModel.pendingDownload.collectAsStateWithLifecycle()
    // 点开 `.user.js` 链接时先弹确认，不直接安装。
    val pendingUserScript by viewModel.pendingUserScript.collectAsStateWithLifecycle()
    // `GM_download({ saveAs: true })`：非 null 时拉起系统文件选择器。
    val scriptSaveAs by viewModel.scriptSaveAs.collectAsStateWithLifecycle()
    val scriptSaveAsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*"),
    ) { uri -> viewModel.completeScriptSaveAs(uri) }
    LaunchedEffect(scriptSaveAs?.requestId) {
        val req = scriptSaveAs ?: return@LaunchedEffect
        // 脚本没给 name 时用占位名，用户可以在选择器里改。
        scriptSaveAsLauncher.launch(req.fileName ?: "download")
    }
    // 页面里 GM_registerMenuCommand 注册的命令；只展示当前标签页的。
    val allScriptMenus by viewModel.userScriptMenus.collectAsStateWithLifecycle()
    val installedScripts by viewModel.userScripts.collectAsStateWithLifecycle()
    val activeScriptMenus = allScriptMenus.filter { it.tabId == activeId }
    // 页面错误：只展示当前标签页的（每页导航后会被清空）。
    val consoleErrors by viewModel.consoleErrors.collectAsStateWithLifecycle()
    val activeConsoleErrors = consoleErrors.filter { it.tabId == activeId }
    // 阅读模式是否开启。必须 collect（而不是直接读 viewModel.readerActive），
    // 否则进入/退出后菜单文案不会刷新。
    val readerActive by viewModel.readerActive.collectAsStateWithLifecycle()
    // 长按命中的链接/图片；非 null 时弹上下文菜单
    val hitTarget by viewModel.hitTarget.collectAsStateWithLifecycle()

    // 两个 BottomSheet 的展开状态：「更多」与「标签页」。
    // 面板本体是 ModalBottomSheet（自屏幕底部升起），这里只留一个布尔开关。
    var showMoreSheet by remember { mutableStateOf(false) }
    var showTabsSheet by remember { mutableStateOf(false) }
    // 「脚本菜单」只有在当前页真的注册了命令时才存在。
    var showScriptMenuSheet by remember { mutableStateOf(false) }
    // 「页面错误」面板：只收录 ERROR 级控制台消息，当前页没有错误时不出现。
    var showConsoleSheet by remember { mutableStateOf(false) }
    var exitConfirm by remember { mutableStateOf(false) }

    // 网页 JS 对话框（alert/confirm/prompt/onbeforeunload）。
    // 非 null 时弹对应框；处理完置 null。prompt 的输入值单独存一个 state，
    // 因为输入过程中对话框不消失。
    var jsDialog by remember { mutableStateOf<JsDialogRequest?>(null) }
    var promptInput by remember { mutableStateOf("") }

    // 证书错误（主框架与子资源）。队列在 WebTabManager 里，这里只存当前展示的一条
    // 与「本次浏览过程中不再询问」的勾选状态。
    var certPrompt by remember { mutableStateOf<CertPrompt?>(null) }
    var certRemember by remember { mutableStateOf(false) }

    // POST 结果页刷新：WebView 自己不会重发请求体，由这里弹窗确认。
    // 队列同样在 WebTabManager 里，这里只存当前展示的一条。
    var formPrompt by remember { mutableStateOf<FormResubmitPrompt?>(null) }

    // Safe Browsing 命中（恶意软件 / 钓鱼 …）。与证书错误同样是排队弹窗，
    // 这里只存当前展示的一条。
    var safePrompt by remember { mutableStateOf<SafeBrowsingPrompt?>(null) }

    // 网页全屏（HTML5 视频的「全屏」按钮）。非 null 时用一层覆盖整个界面，
    // callback 由 WebView 提供，退出全屏时必须回调它，否则 WebView 仍以为
    // 自己在全屏状态，之后再点全屏不会有任何反应。
    var fullscreenView by remember { mutableStateOf<View?>(null) }
    var fullscreenCallback by remember {
        mutableStateOf<WebChromeClient.CustomViewCallback?>(null)
    }
    // 退出全屏：先清状态摘掉覆盖层，再通知 WebView 把视频切回内联。
    // 顺序反了会出现"覆盖层已摘、WebView 又触发一次 onHideCustomView"的抖动。
    val exitFullscreen: () -> Unit = {
        val callback: WebChromeClient.CustomViewCallback? = fullscreenCallback
        fullscreenCallback = null
        fullscreenView = null
        if (callback != null) {
            runCatching { callback.onCustomViewHidden() }
        }
        Unit
    }

    // 网页向宿主索要能力的请求（HTTP 认证 / 定位 / 摄像头麦克风）。
    var authRequest by remember { mutableStateOf<WebAuthRequest?>(null) }
    var authUser by remember { mutableStateOf("") }
    var authPassword by remember { mutableStateOf("") }

    // 用户已在应用内点过「允许」、正等系统运行时授权的请求。
    // 单独存一份而不是复用 authRequest：确认框这时已经关闭，但网络/页面
    // 那边的 handler 还挂着，必须等系统弹窗的结果回来才能兑现。
    var pendingGeo by remember { mutableStateOf<WebAuthRequest.Geolocation?>(null) }
    var pendingDevice by remember { mutableStateOf<WebAuthRequest.Device?>(null) }

    // 把当前挂着的请求按"拒绝/取消"兑现并清空。
    //
    // 三条用途缺一不可：用户点了拒绝、对话框被外部关闭、以及**新请求到来时
    // 挤掉旧请求** —— WebView 的这几类 callback/handler 都必须恰好兑现一次，
    // 不兑现就是页面永远等待（401 卡住 / 定位不返回 / getUserMedia 挂起）。
    val denyPendingAuth: () -> Unit = {
        val req = authRequest
        authRequest = null
        when (req) {
            is WebAuthRequest.HttpAuth -> runCatching { req.handler.cancel() }
            is WebAuthRequest.Geolocation ->
                runCatching { req.callback.invoke(req.origin, false, false) }
            is WebAuthRequest.Device -> runCatching { req.request.deny() }
            null -> Unit
        }
        pendingGeo?.let { runCatching { it.callback.invoke(it.origin, false, false) } }
        pendingDevice?.let { runCatching { it.request.deny() } }
        pendingGeo = null
        pendingDevice = null
        Unit
    }

    // 定位：应用内同意之后才向系统申请，两者都过才真正 allow。
    val geoPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val req = pendingGeo
        pendingGeo = null
        if (req != null) {
            runCatching { req.callback.invoke(req.origin, grants.values.any { it }, false) }
        }
    }

    // 摄像头/麦克风：缺哪个系统权限就申请哪个；只要有一个被拒就整体 deny，
    // 避免"只给了摄像头"却把麦克风也 grant 出去。
    val devicePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val req = pendingDevice
        pendingDevice = null
        if (req != null) {
            if (grants.values.all { it }) {
                runCatching { req.request.grant(req.resources.toTypedArray()) }
            } else {
                runCatching { req.request.deny() }
            }
        }
    }

    // 收起面板时先播完退场动画再卸载组件：直接把开关置 false 会让面板
    // "啪"地消失，跳过 ModalBottomSheet 自带的收起动画。
    val scope = rememberCoroutineScope()
    val moreSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val tabsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scriptMenuSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val consoleSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // 关闭「更多」面板。
    val closeMoreSheet: () -> Unit = {
        scope.launch { moreSheetState.hide() }.invokeOnCompletion {
            if (!moreSheetState.isVisible) showMoreSheet = false
        }
        Unit
    }
    // 关闭「标签页」面板。
    val closeTabsSheet: () -> Unit = {
        scope.launch { tabsSheetState.hide() }.invokeOnCompletion {
            if (!tabsSheetState.isVisible) showTabsSheet = false
        }
        Unit
    }

    // WebView 宿主容器
    val host = remember {
        FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(AndroidColor.TRANSPARENT)
            // ⚠️ 必须显式打开可聚焦。FrameLayout 默认 `focusable = false`，
            // 而 WebView 是被 addView 进来的**子 View**：父容器不可聚焦时，
            // 触摸事件虽然能到 WebView，但焦点无法在 View 树里正常流转，
            // 网页里的输入框就唤不起输入法（见 focusWebContent 的说明）。
            isFocusable = true
            isFocusableInTouchMode = true
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
    }

    DisposableEffect(host) {
        viewModel.tabManager.attachHost(host)
        // 把系统的内存压力回调接到 WebView 实例池上（见 WebTabManager.updateCacheLimit）。
        // 挂在 attachHost 的同一个 DisposableEffect 里，是为了让它的生命周期
        // 与宿主容器严格一致：detach 时必须摘掉，否则 Application 会一直
        // 持有一个已销毁 Activity 的 ViewModel。
        val app = host.context.applicationContext as? LingApplication
        app?.onTrimMemoryListener = { level -> viewModel.tabManager.updateCacheLimit(level) }
        onDispose {
            app?.onTrimMemoryListener = null
            viewModel.tabManager.detachHost(host)
        }
    }

    // 网页 <input type="file"> → 系统文件选择器。
    //
    // WebView 自己不会拉起任何界面：不实现 onShowFileChooser 时点击完全无反应，
    // 这正是"网页上传按钮点了没反应、没拉起文件管理"的成因，必须由宿主代劳。
    //
    // 这里刻意用 StartActivityForResult 而不是 GetContent / OpenDocument 那几个
    // 封装契约：单选 / 多选 / 取目录 / 存盘四种模式与 accept 类型都要在**运行时**
    // 决定，而封装契约的输入类型在组合期就固定了，只有自己拼 Intent 才能全覆盖。
    //
    // pendingFileChooser 存"这一次"的结果回调。WebView 要求 onReceiveValue
    // 至多调用一次，故发起前存好、返回后立刻清空，避免重复兑现。
    var pendingFileChooser by remember { mutableStateOf<((Array<Uri>?) -> Unit)?>(null) }
    val fileChooserLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val deliver = pendingFileChooser
        pendingFileChooser = null
        // 取消时也回调 null（见 WebTabManager.onShowFileChooser 的说明）。
        deliver?.invoke(collectFileChooserResult(result.resultCode, result.data))
    }

    DisposableEffect(Unit) {
        viewModel.tabManager.onShowFileChooser = { params, onResult ->
            pendingFileChooser = onResult
            runCatching { fileChooserLauncher.launch(buildFileChooserIntent(params)) }
                .onFailure {
                    // 没有能处理该 Intent 的应用（如极简系统缺 DocumentsUI）：
                    // 必须清 pending 并兑现 null，否则这次点击会把 WebView 卡在
                    // "等待选择"，页面之后再也点不动上传按钮。
                    pendingFileChooser = null
                    onResult(null)
                }
        }
        onDispose { viewModel.tabManager.onShowFileChooser = null }
    }

    // 网页 JS 对话框：alert / confirm / prompt / onbeforeunload。
    //
    // 不接管时 WebView 会弹系统 Dialog（标题固定"JavaScript"，跟应用风格脱节）。
    // 这里统一用 Compose AlertDialog 渲染。JsResult 必须且只能兑现一次：
    // 确认走 confirm()，取消/关闭走 cancel()，关闭对话框（onDismiss）按取消处理。
    DisposableEffect(Unit) {
        viewModel.tabManager.onJsAlert = { url, message, result ->
            jsDialog = JsDialogRequest.Alert(url, message, result)
        }
        viewModel.tabManager.onJsConfirm = { url, message, result ->
            jsDialog = JsDialogRequest.Confirm(url, message, result)
        }
        viewModel.tabManager.onJsPrompt = { url, message, defaultValue, result ->
            promptInput = defaultValue
            jsDialog = JsDialogRequest.Prompt(url, message, defaultValue, result)
        }
        viewModel.tabManager.onJsBeforeUnload = { url, message, result ->
            jsDialog = JsDialogRequest.BeforeUnload(url, message, result)
        }
        onDispose {
            viewModel.tabManager.onJsAlert = null
            viewModel.tabManager.onJsConfirm = null
            viewModel.tabManager.onJsPrompt = null
            viewModel.tabManager.onJsBeforeUnload = null
        }
    }

    // 网页全屏：WebView 只负责把容器 View 准备好，铺满屏幕是宿主的事。
    // 与 onShowFileChooser 同一套委托模式 —— 不在用时置 null，避免残留引用。
    DisposableEffect(Unit) {
        viewModel.tabManager.onShowCustomView = { view, callback ->
            fullscreenView = view
            fullscreenCallback = callback
        }
        // 网页自己退出全屏（视频播完 / 调了 exitFullscreen）：只摘覆盖层，
        // 不再回调 callback —— WebView 已经知道全屏结束了。
        viewModel.tabManager.onHideCustomView = {
            fullscreenView = null
            fullscreenCallback = null
        }
        onDispose {
            viewModel.tabManager.onShowCustomView = null
            viewModel.tabManager.onHideCustomView = null
        }
    }

    // 网页向宿主要能力：HTTP 认证 / 定位 / 摄像头麦克风。
    //
    // 新请求会先把旧请求按拒绝兑现（见 denyPendingAuth）——
    // 同一时刻只留一个待兑现的 handler，否则旧的那个永远等不到结果。
    DisposableEffect(Unit) {
        viewModel.tabManager.onHttpAuthRequest = { host, realm, handler ->
            denyPendingAuth()
            authUser = ""
            authPassword = ""
            authRequest = WebAuthRequest.HttpAuth(host, realm, handler)
        }
        viewModel.tabManager.onGeolocationRequest = { origin, callback ->
            denyPendingAuth()
            authRequest = WebAuthRequest.Geolocation(origin, callback)
        }
        viewModel.tabManager.onPermissionRequest = { origin, resources, request ->
            denyPendingAuth()
            authRequest = WebAuthRequest.Device(origin, resources, request)
        }
        onDispose {
            viewModel.tabManager.onHttpAuthRequest = null
            viewModel.tabManager.onGeolocationRequest = null
            viewModel.tabManager.onPermissionRequest = null
            // 界面都销毁了，还挂着的 handler 已无人能兑现，按拒绝处理。
            denyPendingAuth()
        }
    }

    // 网页要求客户端证书（mTLS）→ 系统 KeyChain 选择器。
    //
    // 系统对话框自带 UI，不需要我们再画一层；要害是三条路径**都必须**
    // 兑现 request：没有 Activity、用户取消、证书读取失败 —— 漏一条，
    // TLS 握手就会一直挂着，页面表现为永远加载不完。
    DisposableEffect(Unit) {
        val activity = context.findActivity()
        viewModel.tabManager.onClientCertRequest = { request ->
            if (activity == null) {
                request.cancel()
            } else {
                runCatching {
                    KeyChain.choosePrivateKeyAlias(
                        activity,
                        { alias ->
                            if (alias == null) {
                                request.cancel()
                            } else {
                                // 读 KeyStore 走 IO；读完后 CoroutineScope 自动回到主线程
                                // 再 proceed()（handler 的兑现必须在主线程）。
                                viewModel.viewModelScope.launch {
                                    val loaded = withContext(Dispatchers.IO) {
                                        runCatching {
                                            val key = KeyChain.getPrivateKey(activity, alias)
                                            val chain = KeyChain.getCertificateChain(activity, alias)
                                            if (key == null || chain.isNullOrEmpty()) {
                                                null
                                            } else {
                                                key to chain
                                            }
                                        }.getOrNull()
                                    }
                                    if (loaded == null) {
                                        request.cancel()
                                    } else {
                                        request.proceed(loaded.first, loaded.second)
                                    }
                                }
                            }
                        },
                        request.keyTypes,
                        request.principals,
                        request.host,
                        request.port,
                        null,
                    )
                }.onFailure {
                    // 系统里没有证书安装器（极简 ROM）时会抛异常，
                    // 不兜住就等于把这个请求永久挂起。
                    request.cancel()
                }
            }
        }
        onDispose { viewModel.tabManager.onClientCertRequest = null }
    }

    // 证书错误弹窗：主框架与子资源共用一条队列（见 WebTabManager.handleSslError）。
    // 同一时刻只展示一条；用户裁决后队列自动推下一条，全空时收到 null 关弹窗。
    DisposableEffect(Unit) {
        viewModel.tabManager.onCertPrompt = { prompt ->
            if (prompt == null) {
                certPrompt = null
                certRemember = false
            } else {
                // 换了一条才清勾选：同一条的重复回调不该抹掉用户刚勾上的状态。
                if (prompt != certPrompt) certRemember = false
                certPrompt = prompt
            }
        }
        onDispose {
            viewModel.tabManager.onCertPrompt = null
            // 界面已销毁，没人能再回答这些 handler；不取消就会让握手悬空。
            viewModel.tabManager.cancelCertPrompts()
            certPrompt = null
        }
    }

    // 表单重提交弹窗：与证书弹窗一样，Dispose 时必须把待裁决项兑现，
    // 否则该 WebView 的加载会一直停在等待状态。
    DisposableEffect(Unit) {
        viewModel.tabManager.onFormResubmitPrompt = { prompt -> formPrompt = prompt }
        onDispose {
            viewModel.tabManager.onFormResubmitPrompt = null
            viewModel.tabManager.cancelFormResubmits()
            formPrompt = null
        }
    }

    // Safe Browsing 弹窗：命中后 WebView 会一直等 SafeBrowsingResponse 的裁决，
    // 因此界面销毁时必须把所有待裁决项按「返回安全页」兑现。
    DisposableEffect(Unit) {
        viewModel.tabManager.onSafeBrowsingPrompt = { prompt -> safePrompt = prompt }
        onDispose {
            viewModel.tabManager.onSafeBrowsingPrompt = null
            viewModel.tabManager.cancelSafeBrowsing()
            safePrompt = null
        }
    }

    // 切标签时退出全屏：全屏容器里装的是上一个标签的 View，留着会盖住新页面，
    // 而那个 View 的宿主 WebView 可能已经被回收销毁。
    LaunchedEffect(activeTab?.id) {
        if (fullscreenView != null) exitFullscreen()
    }

    // 外部协议（zhihu: / weixin: / tel: / mailto: / intent: …）交给系统
    LaunchedEffect(Unit) {
        viewModel.tabManager.onExternalUri = { uri ->
            runCatching {
                // intent:// 必须走 Intent.parseUri 才能还原成目标 Intent，
                // 直接 ACTION_VIEW 包一个 intent: 地址是没有应用能接的
                // （会静默失败，用户看到"点了没反应"）。
                val intent = if (uri.scheme.equals("intent", ignoreCase = true)) {
                    android.content.Intent.parseUri(uri.toString(), 0)
                } else {
                    android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
                }
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            }.getOrElse { false }
        }
        viewModel.tabManager.onDownloadRequested = { url, mimeType ->
            // 名字不在这里定：requestDownload 会按「Content-Disposition → URL 末段
            // → MIME 反推 + 时间戳」三级优先级推断，且允许被响应头纠正。
            // 注意这里只是「请求」，真正开始下载要等用户在确认框里点确认。
            viewModel.requestDownload(url, mimeType, fileName = null)
        }
    }

    // 页面加载完成后刷新收藏态
    LaunchedEffect(activeTab?.url) {
        viewModel.refreshBookmarkState()
    }

    // 把 Material You 配色同步给阅读视图。
    // 主页已改为 Compose 渲染（见 HomeScreen），颜色直接读 MaterialTheme，
    // 不再需要这一路同步；阅读视图仍是 HTML，主题色只能"原生算好 → 注入 CSS 变量"。
    val colorScheme = MaterialTheme.colorScheme
    // 界面当前的明暗：当作"网页要不要重算暗化"的触发器。
    val uiDark = colorScheme.background.luminance() < 0.5f
    LaunchedEffect(colorScheme) {
        viewModel.applyInternalColors(
            background = colorScheme.background,
            onBackground = colorScheme.onBackground,
            primary = colorScheme.primary,
            onPrimary = colorScheme.onPrimary,
            primaryContainer = colorScheme.primaryContainer,
            onPrimaryContainer = colorScheme.onPrimaryContainer,
            dark = uiDark,
        )
    }

    // 「跟随系统」时，运行中切换系统明暗**不经过设置流**（settings.collect 不触发），
    // 但界面会跟着系统变 —— 网页侧的暗化必须在这里补算一次，否则会出现
    // "界面已经变深、网页还是亮的"。阅读视图由上面的配色同步覆盖。
    LaunchedEffect(uiDark) {
        viewModel.refreshWebDarkMode()
    }

    // 后退：优先收起地址栏编辑态，其次走网页历史，最后才是退出确认。
    // 由 canHandleBack 显式控制是否接管 —— 二级页面在栈上时本处理器整体让位。
    BackHandler(enabled = canHandleBack) {
        if (addressEditing) {
            viewModel.onAddressFocusChanged(false)
        } else if (activeTab?.canGoBack == true) {
            viewModel.goBack()
        } else {
            exitConfirm = true
        }
    }

    // 输入法是否可见。键盘弹出时底部工具栏会收起：
    // 它的位置本来就在窗口最下沿、会被键盘完全盖住，留着只会白占一块高度；
    // 收起后网页视口正好落到键盘上沿（见下面 Column 的 imePadding）。
    val imeVisible = WindowInsets.isImeVisible

    // 底部手势条的内边距不在这里留，交给 BottomToolbar 自己吃：
    // 工具栏底色要铺到屏幕最下沿（见其文件头说明）。
    //
    // imePadding 必须加在**整列**上，不能只加在网页内容区：
    // 后者会按"I 高度的键盘"从内容区底部扣除，而内容区本身又不延伸到窗口
    // 最下沿（下面还有工具栏），结果键盘上方凭空多出「工具栏高度」的一块空白。
    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        // ---- 地址栏 ----
        // 状态栏内边距由地址栏自己吃：提示条现在在它下方，不能替它顶到状态栏。
        AddressBar(
            text = addressText,
            isEditing = addressEditing,
            isLoading = activeTab?.isLoading == true,
            progress = activeTab?.progress,
            security = activeTab?.security ?: SecurityState.SECURE,
            suggestions = suggestions,
            onTextChange = viewModel::onAddressChanged,
            onFocusChange = viewModel::onAddressFocusChanged,
            // 焦点交还给网页内容。少了这一步，Compose 释放焦点后焦点悬空，
            // 网页里的输入框唤不起输入法（真机实测确认）。
            onReleaseFocus = { viewModel.tabManager.focusWebContent() },
            onSubmit = viewModel::submitAddress,
            onSuggestionClick = { s ->
                viewModel.navigate(s.url)
                viewModel.onAddressFocusChanged(false)
            },
            onReloadOrStop = {
                if (activeTab?.isLoading == true) viewModel.stopLoading() else viewModel.refresh()
            },
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )

        // ---- 地址栏下方的模式提示条 ----
        // 放在地址栏之下而不是之上：提示属于「当前页面处于什么模式」的说明，
        // 不该挤占地址栏上方的视觉起点；放在下方也更贴近页面内容。
        AnimatedVisibility(visible = activeTab?.isIncognito == true) {
            IncognitoBanner()
        }
        AnimatedVisibility(visible = settings.desktopMode && activeTab?.isIncognito != true) {
            DesktopModeBanner()
        }

        // ---- 网页内容区 ----
        // 键盘避让由外层 Column 的 imePadding 统一处理：这里再加一次会
        // 与工具栏占位叠加，表现为键盘上方多出一块空白（见上面的说明）。
        Box(
            modifier = Modifier.weight(1f),
        ) {
            // 原生背景层：画在 WebView **之下**，主页的背景颜色全由它给
            // —— 纯色档画主题背景色，自定义图片档在它之上叠照片与顶部遮罩。
            // 主页期间 WebView 自身背景是透明的（见 WebTabManager.loadHome），
            // 因此能透出这一层。
            //
            // ⚠️ 必须**无条件**调用、用 visible 参数控制是否绘制，不能写成
            // `if (homeBackgroundVisible) { HomeBackgroundLayer(…) }`：
            // 条件式挂载会改变同一 Box 里后续子项（下面的 AndroidView）的槽位，
            // WebView 因此被重建，而重建后的 WebView 会带回一层默认白底把背景层
            // 整个盖住 —— 表现为"改了背景模式要手动按一下主页按钮才生效"。
            HomeBackgroundLayer(
                mode = settings.homeBackground,
                imageUri = settings.homeBackgroundImageUri,
                visible = homeBackgroundVisible,
                modifier = Modifier.fillMaxSize(),
            )

            // WebView 宿主始终挂载：错误页以覆盖层的形式盖在上面。
            // 主页前景（HomeScreen）也是覆盖层 —— WebView 里只有 about:blank 占位，
            // 但那个占位正是主页那一帧历史记录，返回键因此仍能回到主页。
            //
            // 标签页面板是**半透明叠加层**（见 TabsScreen），背后的 WebView
            // 必须继续渲染，否则面板后面就是空白。
            AndroidView(
                factory = { host },
                modifier = Modifier.fillMaxSize(),
            )

            // 主页前景：时钟 + 搜索框。WebView 里只是 about:blank 占位
            // （见 WebTabManager.loadHome），真正的界面在这里。
            // 只在**内置主页**时挂载（自定义主页是普通网页，照常显示 WebView）。
            if (homeBackgroundVisible) {
                HomeScreen(
                    onSearch = viewModel::submitAddress,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            activeTab?.errorText?.let { err ->
                ErrorScreen(
                    message = err,
                    url = activeTab.url,
                    onRetry = viewModel::refresh,
                    // 只有证书错误才给「继续访问」逃生口；其余加载失败没有"硬闯"一说。
                    onProceedInsecure = if (activeTab.security == SecurityState.INSECURE_CERT) {
                        viewModel::proceedInsecure
                    } else {
                        null
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                )
            }

            // 二级页面（标签页 / 书签 / 历史 / 设置）在场时，WebView 仍要
            // 继续渲染 —— 标签页面板是半透明的，背后得有内容。但它不该再
            // 接收触摸：Compsoe 的点击会命中上层面板，而 AndroidView 是真实
            // 的 View，仍可能抢焦点（例如弹出软键盘或触发网页滚动）。
            // 这里盖一层透明且可点击的 Box 把事件吃掉。
            // canHandleBack 等价于"当前没有二级页面"，直接复用。
            if (!canHandleBack) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                        ),
                )
            }
        }

        // ---- 底部工具栏 ----
        // 「更多」与「标签页」只把点击转出去，具体面板由下面的 ModalBottomSheet 承载。
        // 键盘弹出时收起：键盘已经盖住这个位置，继续占高只会把网页视口顶上去；
        // 收起后网页一直延伸到键盘上沿，没有任何空白条。
        AnimatedVisibility(visible = !imeVisible) {
            BottomToolbar(
                canGoBack = activeTab?.canGoBack == true,
                canGoForward = activeTab?.canGoForward == true,
                tabCount = tabs.size,
                isIncognito = activeTab?.isIncognito == true,
                onBack = { viewModel.goBack() },
                onForward = { viewModel.goForward() },
                onHome = { viewModel.goHome() },
                onTabs = { showTabsSheet = true },
                onMore = { showMoreSheet = true },
            )
        }
    }

    // ---- 网页全屏覆盖层 ----
    // 盖住整个界面（含地址栏与工具栏），排在 Column 之后绘制。
    // 刻意不用 Dialog 承载：Dialog 另开窗口，部分机型上视频纹理在独立
    // 窗口里会黑屏（SurfaceView 不跟随宿主窗口的合成）。
    fullscreenView?.let { view ->
        FullscreenMediaOverlay(view = view, onExit = exitFullscreen)
    }

    // ---- 「标签页」面板 ----
    // 用 ModalBottomSheet 自屏幕底部升起：遮罩、圆角、拖动关闭、入场/退场动画
    // 都由组件提供，TabsScreen 只负责面板内容（见其文件头说明）。
    if (showTabsSheet) {
        TabsSheet(
            tabs = tabs,
            activeId = activeId,
            sheetState = tabsSheetState,
            onDismiss = { showTabsSheet = false },
            onSelect = {
                viewModel.switchTab(it)
                closeTabsSheet()
            },
            onClose = viewModel::closeTab,
            onNewTab = {
                viewModel.newTab()
                closeTabsSheet()
            },
            onNewIncognitoTab = {
                viewModel.newIncognitoTab()
                closeTabsSheet()
            },
            onCloseAll = { tabs.forEach { viewModel.closeTab(it.id) } },
            onMoveTab = viewModel::moveTab,
        )
    }

    // ---- 「更多」面板 ----
    // 原来锚定在工具栏「更多」按钮上的下拉菜单，现在同样改为自底部升起的面板。
    // 内容排成图标网格（见 [MoreGrid]）：11 项竖排要占满大半屏，横排 4 列后
    // 只有 3 行，面板更矮、基本不需要滚动。
    if (showMoreSheet) {
        val items = listOfNotNull(
            MoreItem(
                icon = if (isBookmarked) LingIcons.Bookmark else LingIcons.BookmarkAdd,
                label = if (isBookmarked) "取消收藏" else "添加书签",
                onClick = {
                    viewModel.toggleBookmark()
                    closeMoreSheet()
                },
            ),
            MoreItem(
                icon = LingIcons.BookmarkBorder,
                label = "书签",
                onClick = {
                    onOpenBookmarks()
                    closeMoreSheet()
                },
            ),
            MoreItem(
                icon = LingIcons.History,
                label = "历史记录",
                onClick = {
                    onOpenHistory()
                    closeMoreSheet()
                },
            ),
            MoreItem(
                icon = LingIcons.Download,
                label = "下载",
                onClick = {
                    onOpenDownloads()
                    closeMoreSheet()
                },
            ),
            MoreItem(
                icon = LingIcons.PrivacyTip,
                label = "新建无痕标签页",
                onClick = {
                    viewModel.newIncognitoTab()
                    closeMoreSheet()
                },
            ),
            MoreItem(
                icon = LingIcons.DesktopWindows,
                label = if (settings.desktopMode) "关闭电脑模式" else "开启电脑模式",
                onClick = {
                    viewModel.setDesktopMode(!settings.desktopMode)
                    closeMoreSheet()
                },
            ),
            MoreItem(
                icon = LingIcons.Nightlight,
                // 四列比原来窄，长文案会折成两行；「夜间：」前缀比
                // 「夜间模式：」更省一格，取值仍与设置页一致。
                label = when (settings.nightMode) {
                    NightMode.FOLLOW_SYSTEM -> "夜间：跟随系统"
                    NightMode.ALWAYS_ON -> "夜间：已开启"
                    NightMode.ALWAYS_OFF -> "夜间：已关闭"
                },
                onClick = {
                    viewModel.setNightMode(
                        when (settings.nightMode) {
                            NightMode.FOLLOW_SYSTEM -> NightMode.ALWAYS_ON
                            NightMode.ALWAYS_ON -> NightMode.ALWAYS_OFF
                            NightMode.ALWAYS_OFF -> NightMode.FOLLOW_SYSTEM
                        }
                    )
                    closeMoreSheet()
                },
            ),
            // 阅读模式。文案随状态切换，让用户一眼看出点下去是进还是出。
            MoreItem(
                icon = LingIcons.Article,
                label = if (readerActive) "退出阅读模式" else "阅读模式",
                onClick = {
                    viewModel.toggleReaderMode()
                    closeMoreSheet()
                },
            ),
            // 资源嗅探：先触发嗅探（写结果），再进二级页展示。
            // 嗅探是异步的，但页面会 collect sniffResults，结果回来后自动刷新。
            MoreItem(
                icon = LingIcons.Movie,
                label = "资源嗅探",
                onClick = {
                    viewModel.sniffResources()
                    onOpenSniff()
                    closeMoreSheet()
                },
            ),
            // 页面错误：只有当前页真的报了错才出现，避免面板里多一个常年灰着的入口。
            if (activeConsoleErrors.isNotEmpty()) {
                MoreItem(
                    icon = LingIcons.Block,
                    label = "页面错误（${activeConsoleErrors.size}）",
                    onClick = {
                        showMoreSheet = false
                        showConsoleSheet = true
                    },
                )
            } else {
                null
            },
            // 用户脚本通过 GM_registerMenuCommand 注册的命令。只有当前页
            // 真的注册过才出现 —— 空面板没有意义。
            if (activeScriptMenus.isNotEmpty()) {
                MoreItem(
                    icon = LingIcons.Javascript,
                    label = "脚本菜单",
                    onClick = {
                        showMoreSheet = false
                        showScriptMenuSheet = true
                    },
                )
            } else {
                null
            },
            MoreItem(
                icon = LingIcons.Settings,
                label = "设置",
                onClick = {
                    onOpenSettings()
                    closeMoreSheet()
                },
            ),
        )
        MoreSheet(
            items = items,
            sheetState = moreSheetState,
            onDismiss = { showMoreSheet = false },
        )
    }

    // ---- 「脚本菜单」面板 ----
    // 命令属于具体页面实例：导航后 WebTabManager 会清空，列表随之变空，
    // 这里主动收起，避免用户对着已失效的命令点击。
    LaunchedEffect(activeScriptMenus.isEmpty()) {
        if (activeScriptMenus.isEmpty()) showScriptMenuSheet = false
    }
    if (showScriptMenuSheet) {
        ScriptMenuSheet(
            commands = activeScriptMenus,
            installedScripts = installedScripts,
            sheetState = scriptMenuSheetState,
            onDismiss = { showScriptMenuSheet = false },
            onInvoke = { command ->
                showScriptMenuSheet = false
                viewModel.invokeUserScriptMenu(command.tabId, command.scriptId, command.commandId)
            },
        )
    }

    // ---- 「页面错误」面板 ----
    // 与脚本菜单同理：导航会清空当前页的错误，列表空了就收起面板。
    LaunchedEffect(activeConsoleErrors.isEmpty()) {
        if (activeConsoleErrors.isEmpty()) showConsoleSheet = false
    }
    if (showConsoleSheet) {
        ConsoleSheet(
            entries = activeConsoleErrors,
            sheetState = consoleSheetState,
            onDismiss = { showConsoleSheet = false },
        )
    }

    // ---- 长按上下文菜单 ----
    // 现代 Chromium WebView 不再自带链接/图片的长按菜单，长按正文只会进入
    // 文字选择。菜单由我们自己实现：命中结果经 LingWebView → WebTabManager
    // → ViewModel 送上来，这里按目标类型决定列出哪些动作。
    //
    // 用**贴着手指弹出的小浮层**而不是 ModalBottomSheet：上下文菜单是"对这一点
    // 的动作"，用户在长按位置做选择，视线不该被拉到屏幕底部；菜单本身只有
    // 2~4 行，占满整屏宽度也显得过重。
    val target = hitTarget
    if (target != null) {
        Popup(
            popupPositionProvider = remember(target) {
                TouchAnchoredPositionProvider(target.anchorX, target.anchorY)
            },
            onDismissRequest = viewModel::dismissHitTarget,
            // focusable：返回键与点击外部都能关掉，和系统菜单一致
            properties = PopupProperties(focusable = true),
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shadowElevation = 8.dp,
                modifier = Modifier.widthIn(max = 240.dp),
            ) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    val copy: (String, String) -> Unit = { text, label ->
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(ClipData.newPlainText(label, text))
                        viewModel.dismissHitTarget()
                        viewModel.showMessage("已复制$label")
                    }

                    if (target.hasLink) {
                        ContextMenuAction("在新标签页打开") {
                            viewModel.openHitTarget(inNewTab = true)
                        }
                        ContextMenuAction("复制链接地址") {
                            copy(target.url.orEmpty(), "链接地址")
                        }
                    }
                    if (target.hasImage) {
                        ContextMenuAction("打开图片") {
                            viewModel.openHitTargetImage(inNewTab = false)
                        }
                        ContextMenuAction("在新标签页打开图片") {
                            viewModel.openHitTargetImage(inNewTab = true)
                        }
                        ContextMenuAction("保存图片") {
                            viewModel.saveHitTargetImage()
                        }
                        ContextMenuAction("复制图片地址") {
                            copy(target.imageUrl.orEmpty(), "图片地址")
                        }
                    }
                }
            }
        }
    }

    // ---- 下载确认 ----
    // 网页可以自行触发下载（弹窗广告、误触都会），因此不能静默开始。
    val pending = pendingDownload
    if (pending != null) {
        AlertDialog(
            onDismissRequest = viewModel::dismissPendingDownload,
            icon = { Icon(LingIcons.Download, contentDescription = null) },
            title = { Text("要下载这个文件吗？") },
            text = {
                Column {
                    Text(
                        text = pending.fileName,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = buildString {
                            append(sizeLabel(pending))
                            pending.mimeType?.let {
                                append("  ·  ")
                                append(it)
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = UrlUtils.hostOf(pending.url),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            confirmButton = {
                Row {
                    // 复制链接放在「下载」旁边而不是塞进正文：两个都是这个对话框的
                    // 主操作，并且复制后对话框**不关闭** —— 用户可能还想顺手下载。
                    TextButton(onClick = {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(
                            ClipData.newPlainText("下载链接", pending.url),
                        )
                        viewModel.showMessage("已复制下载链接")
                    }) { Text("复制链接") }
                    TextButton(onClick = viewModel::confirmPendingDownload) { Text("下载") }
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissPendingDownload) { Text("取消") }
            },
        )
    }

    // ---- 用户脚本安装确认 ----
    // 打开 `.user.js` 链接时不加载原始代码，先问一句再装。与下载确认同理：
    // 点了一个链接，不代表用户愿意让任意 JS 长期跑在所有匹配页面上。
    // 元数据头在弹窗打开后并行拉取，拿到后补上"脚本会跑在哪些页面"。
    pendingUserScript?.let { pending ->
        val preview = pending.preview
        AlertDialog(
            onDismissRequest = viewModel::dismissUserScriptInstall,
            icon = { Icon(LingIcons.Javascript, contentDescription = null) },
            title = { Text("安装这个用户脚本？") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        text = preview?.name?.takeIf { it.isNotBlank() }
                            ?: pending.url.substringAfterLast('/').substringBefore('?')
                                .ifBlank { "user.js" },
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = buildString {
                            if (!preview?.version.isNullOrBlank()) append("v${preview?.version} · ")
                            append(UrlUtils.hostOf(pending.url))
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    Spacer(Modifier.height(12.dp))
                    Text("生效范围", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(4.dp))
                    when {
                        pending.loading -> Text(
                            text = "正在读取脚本信息…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        preview?.error != null -> Text(
                            text = "无法读取脚本信息：${preview.error}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        preview == null -> Unit
                        // 没有 @match/@include 的脚本装上也不会运行，直说比让用户
                        // 装完发现"没反应"更好。
                        preview.patterns.isEmpty() -> Text(
                            text = "没有声明 @match / @include，安装后不会在任何页面运行。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        else -> preview.patterns.forEach { pattern ->
                            Text(
                                text = "· $pattern",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                ),
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "脚本会以网页权限运行，能读写页面内容与 Cookie。" +
                            "请只安装可信来源的脚本。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = viewModel::confirmUserScriptInstall,
                    // 读不到元数据也不拦着：真正安装会再报一次同样的错误，
                    // 这里只是不让人在"还在读"的时候就点下去。
                    enabled = !pending.loading,
                ) { Text("安装") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissUserScriptInstall) { Text("取消") }
            },
        )
    }

    // ---- 退出确认 ----
    if (exitConfirm) {
        AlertDialog(
            onDismissRequest = { exitConfirm = false },
            title = { Text("退出翎浏览器？") },
            text = { Text("当前还有 ${tabs.size} 个标签页。") },
            confirmButton = {
                TextButton(onClick = {
                    exitConfirm = false
                    (context as? android.app.Activity)?.finish()
                }) { Text("退出") }
            },
            dismissButton = {
                TextButton(onClick = { exitConfirm = false }) { Text("取消") }
            },
        )
    }

    // ---- 证书错误（主框架 / 子资源） ----
    //
    // 交互对齐 Via：警告 + 原因 + 「本次浏览过程中不再询问」+ 取消/确定。
    // 必须明确二选一：onDismissRequest（返回键 / 点外部）按「取消」处理，
    // 否则 handler 一直挂着，页面会停在半加载状态。
    certPrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = {
                viewModel.tabManager.resolveCertPrompt(proceed = false, remember = false)
            },
            title = { Text("警告") },
            text = {
                Column {
                    Text("与 ${prompt.host} 的连接不安全：")
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "— ${prompt.reason}",
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (!prompt.mainFrame) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "由页面内的图片 / 脚本等子资源发起，不是当前网页本身。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { certRemember = !certRemember },
                    ) {
                        Checkbox(
                            checked = certRemember,
                            onCheckedChange = { certRemember = it },
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("本次浏览过程中不再询问")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.tabManager.resolveCertPrompt(proceed = true, remember = certRemember)
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.tabManager.resolveCertPrompt(proceed = false, remember = false)
                }) { Text("取消") }
            },
        )
    }

    // ---- POST 结果页刷新 ----
    //
    // 与 Chrome 一致：明确问一次要不要重新提交表单数据。两个按钮都必须兑现
    // WebTabManager 里的 resend / dontResend，onDismissRequest 按取消处理。
    formPrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = { viewModel.tabManager.resolveFormResubmit(resend = false) },
            title = { Text("重新提交表单？") },
            text = {
                Text("${prompt.host} 需要重新发送刚才提交的表单数据才能刷新此页面。")
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.tabManager.resolveFormResubmit(resend = true)
                }) { Text("重新提交") }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.tabManager.resolveFormResubmit(resend = false)
                }) { Text("取消") }
            },
        )
    }

    // ---- Safe Browsing 警告 ----
    //
    // 「返回安全页」是默认动作（onDismissRequest 同此），与 Chrome 的默认按钮一致。
    // 「仍要继续」只在用户明确点击时兑现 proceed —— 这一页仍可能被恶意内容利用。
    safePrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = { viewModel.tabManager.resolveSafeBrowsing(proceed = false) },
            title = { Text("安全警告") },
            text = {
                Column {
                    Text(
                        text = if (prompt.host.isBlank()) "此页面" else prompt.host,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "— ${prompt.threat}",
                        color = MaterialTheme.colorScheme.error,
                    )
                    if (!prompt.mainFrame) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "由页面内的图片 / 脚本等子资源发起，不是当前网页本身。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.tabManager.resolveSafeBrowsing(proceed = false)
                }) { Text("返回安全页") }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.tabManager.resolveSafeBrowsing(proceed = true)
                }) { Text("仍要继续") }
            },
        )
    }

    // ---- 网页 JS 对话框 ----
    jsDialog?.let { req ->
        val dismiss: () -> Unit = { jsDialog = null }
        val hostLabel = UrlUtils.hostOf(req.url).ifBlank { req.url }
        AlertDialog(
            // 关闭对话框按取消处理：JsResult.cancel()，页面拿到 false / null。
            onDismissRequest = {
                when (req) {
                    is JsDialogRequest.Alert -> req.result.cancel()
                    is JsDialogRequest.Confirm -> req.result.cancel()
                    is JsDialogRequest.Prompt -> req.result.cancel()
                    is JsDialogRequest.BeforeUnload -> req.result.cancel()
                }
                dismiss()
            },
            title = { Text(hostLabel) },
            text = {
                Column {
                    if (req.message.isNotBlank()) {
                        Text(
                            text = req.message,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (req is JsDialogRequest.Prompt) {
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = promptInput,
                            onValueChange = { promptInput = it },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    when (req) {
                        is JsDialogRequest.Alert -> req.result.confirm()
                        is JsDialogRequest.Confirm -> req.result.confirm()
                        is JsDialogRequest.Prompt -> req.result.confirm(promptInput)
                        is JsDialogRequest.BeforeUnload -> req.result.confirm()
                    }
                    dismiss()
                }) {
                    Text(
                        when (req) {
                            is JsDialogRequest.Alert -> "确定"
                            is JsDialogRequest.Confirm -> "确定"
                            is JsDialogRequest.Prompt -> "确定"
                            is JsDialogRequest.BeforeUnload -> "离开"
                        }
                    )
                }
            },
            dismissButton = if (req is JsDialogRequest.Alert) {
                null
            } else {
                {
                    TextButton(onClick = {
                        when (req) {
                            is JsDialogRequest.Alert -> {} // alert 没有取消按钮
                            is JsDialogRequest.Confirm -> req.result.cancel()
                            is JsDialogRequest.Prompt -> req.result.cancel()
                            is JsDialogRequest.BeforeUnload -> req.result.cancel()
                        }
                        dismiss()
                    }) {
                        Text(if (req is JsDialogRequest.BeforeUnload) "留在本页" else "取消")
                    }
                }
            },
        )
    }

    // ---- 网页向宿主要能力：HTTP 认证 / 定位 / 摄像头麦克风 ----
    authRequest?.let { req ->
        when (req) {
            is WebAuthRequest.HttpAuth -> AlertDialog(
                onDismissRequest = denyPendingAuth,
                title = { Text("需要登录") },
                text = {
                    Column {
                        Text(
                            text = req.origin + if (req.realm.isNotBlank()) "（${req.realm}）" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = authUser,
                            onValueChange = { authUser = it },
                            label = { Text("用户名") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = authPassword,
                            onValueChange = { authPassword = it },
                            label = { Text("密码") },
                            singleLine = true,
                            // 密码明文显示会在录屏/演示时直接泄露
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val user = authUser
                        val pass = authPassword
                        authRequest = null
                        runCatching { req.handler.proceed(user, pass) }
                    }) { Text("登录") }
                },
                dismissButton = {
                    TextButton(onClick = denyPendingAuth) { Text("取消") }
                },
            )

            is WebAuthRequest.Geolocation -> AlertDialog(
                onDismissRequest = denyPendingAuth,
                title = { Text("允许获取位置？") },
                text = {
                    Text("${originLabel(req.origin)} 想要获取你的位置信息。")
                },
                confirmButton = {
                    TextButton(onClick = {
                        authRequest = null
                        if (hasLocationPermission(context)) {
                            runCatching { req.callback.invoke(req.origin, true, false) }
                        } else {
                            // 应用内同意 ≠ 系统已授权：还要过一遍运行时权限，
                            // 结果回来后才真正 allow（见 geoPermissionLauncher）。
                            pendingGeo = req
                            geoPermissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION,
                                ),
                            )
                        }
                    }) { Text("允许") }
                },
                dismissButton = {
                    TextButton(onClick = denyPendingAuth) { Text("拒绝") }
                },
            )

            is WebAuthRequest.Device -> {
                val labels = req.resources.map(::webResourceLabel)
                val summary = if (labels.isEmpty()) "未知能力" else labels.joinToString("、")
                AlertDialog(
                    onDismissRequest = denyPendingAuth,
                    title = { Text("允许使用设备？") },
                    text = {
                        Text("${originLabel(req.origin)} 请求使用：$summary")
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val needed = req.resources
                                .flatMap(::requiredRuntimePermissions)
                                .distinct()
                                .filter { !hasPermission(context, it) }
                            authRequest = null
                            if (needed.isEmpty()) {
                                runCatching { req.request.grant(req.resources.toTypedArray()) }
                            } else {
                                pendingDevice = req
                                devicePermissionLauncher.launch(needed.toTypedArray())
                            }
                        }) { Text("允许") }
                    },
                    dismissButton = {
                        TextButton(onClick = denyPendingAuth) { Text("拒绝") }
                    },
                )
            }
        }
    }
}

/**
 * 授权框里的来源标签。
 *
 * HTTP 认证给的是 host（可能带端口），定位与设备能力给的是 origin
 * （`https://example.com`）—— 两种都先试 host，取不到再原样显示。
 */
private fun originLabel(origin: String): String = UrlUtils.hostOf(origin).ifBlank { origin }

/** 把 `PermissionRequest` 的资源名翻成用户能看懂的词。 */
private fun webResourceLabel(resource: String): String = when (resource) {
    PermissionRequest.RESOURCE_VIDEO_CAPTURE -> "摄像头"
    PermissionRequest.RESOURCE_AUDIO_CAPTURE -> "麦克风"
    PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID -> "受保护媒体（DRM）"
    PermissionRequest.RESOURCE_MIDI_SYSEX -> "MIDI 设备"
    else -> resource
}

/**
 * 某个网页资源对应的系统运行时权限。
 *
 * DRM 与 MIDI 不需要运行时权限，返回空列表；摄像头/麦克风没有系统授权时
 * `PermissionRequest.grant()` 会静默失败（页面拿到 NotAllowedError），
 * 所以必须先申请。
 */
private fun requiredRuntimePermissions(resource: String): List<String> = when (resource) {
    PermissionRequest.RESOURCE_VIDEO_CAPTURE -> listOf(Manifest.permission.CAMERA)
    PermissionRequest.RESOURCE_AUDIO_CAPTURE -> listOf(Manifest.permission.RECORD_AUDIO)
    else -> emptyList()
}

private fun hasPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

/** 粗略或精确定位任一已授权即可 —— 两种精度都能满足浏览器的定位请求。 */
private fun hasLocationPermission(context: Context): Boolean =
    hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
        hasPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)

/**
 * 确认框里的大小文案。
 *
 * 三种状态分开写：探测中、探测失败（大小未知）、已知大小。
 * 复用下载页的 [formatBytes]，两处口径一致。
 */
private fun sizeLabel(pending: PendingDownload): String = when {
    pending.probing -> "正在获取大小…"
    pending.sizeBytes != null && pending.sizeBytes > 0 -> formatBytes(pending.sizeBytes)
    else -> "大小未知"
}

/**
 * 加载失败页。
 *
 * @param onProceedInsecure 证书错误时的「继续访问」入口；为 null 表示该错误
 *   不可放行（普通加载失败没有"硬闯"一说），此时不渲染这个按钮。
 */
@Composable
private fun ErrorScreen(    message: String,
    url: String,
    onRetry: () -> Unit,
    onProceedInsecure: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "无法打开该网页",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = url,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(
            modifier = Modifier.padding(top = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(onClick = onRetry) { Text("重试") }
            // 证书错误专属逃生口。故意用低调的 TextButton、并明写「不安全」：
            // 它是"我已知晓风险、仍要进"，不能做成显眼的推荐按钮。
            onProceedInsecure?.let { proceed ->
                TextButton(onClick = proceed) {
                    Text("继续访问（不安全）", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/**
 * 网页全屏（HTML5 视频）的承载层。
 *
 * [view] 是 WebView 在 `onShowCustomView` 里给出的容器，视频画面就在里面，
 * 只能原样挂上来、不能重建。退出只有两条路径：
 *  - 用户按返回键 → [onExit]，由调用方回调 `CustomViewCallback` 通知 WebView；
 *  - 网页自己退出 → `onHideCustomView` 把状态清掉，本层随之卸载。
 */
@Composable
private fun FullscreenMediaOverlay(view: View, onExit: () -> Unit) {
    val rootView = LocalView.current
    val window = (rootView.context as? Activity)?.window

    // 全屏期间藏起系统栏，退出时必须还原 —— 不还原会把状态栏/导航栏永久藏掉。
    DisposableEffect(Unit) {
        window?.let {
            WindowInsetsControllerCompat(it, rootView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                // 允许边缘滑动临时呼出系统栏，避免用户"彻底出不去"
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        onDispose {
            window?.let {
                WindowInsetsControllerCompat(it, rootView)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // 返回键只退出全屏，不再触发网页后退或"退出应用"。
    BackHandler { onExit() }

    AndroidView(
        factory = { ctx ->
            FrameLayout(ctx).apply {
                // WebView 给的 View 可能仍挂在别的父容器上，先摘再挂
                (view.parent as? ViewGroup)?.removeView(view)
                addView(
                    view,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
        },
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    )

    // 本层卸载时把 View 从容器摘下，否则它会一直被这棵视图树引用着。
    DisposableEffect(view) {
        onDispose { (view.parent as? ViewGroup)?.removeView(view) }
    }
}

/**
 * 长按菜单里的一行动作。
 *
 * 竖排一行一个动词，行高按 40dp 左右压：上下文菜单只有 2~4 项，
 * 做成图标网格或大卡片都会显得比"点上那一下"更重。
 */
@Composable
private fun ContextMenuAction(
    label: String,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

/**
 * 让长按菜单贴着手指弹出，并整体夹在窗口内。
 *
 * 返回的坐标是**窗口坐标**（Compose `PopupPositionProvider` 的约定），
 * 与 [com.ling.browser.web.HitTarget.anchorX] 的算法一致 —— 两边都是
 * "WebView 在窗口中的位置 + 触摸点"。
 *
 * 放不下时先整块往左挪，再考虑翻到手指上方：菜单盖住手指会挡掉用户
 * 正在看的那一小块内容，还会让人以为没点中。
 */
private class TouchAnchoredPositionProvider(
    private val anchorX: Int,
    private val anchorY: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val margin = CONTEXT_MENU_MARGIN
        val x = anchorX
            .coerceAtMost(windowSize.width - popupContentSize.width - margin)
            .coerceAtLeast(margin)
        // 手指下方放不下就翻到上方；上方也放不下再夹回窗口内
        val wantedY = if (anchorY + popupContentSize.height + margin > windowSize.height) {
            anchorY - popupContentSize.height
        } else {
            anchorY
        }
        val y = wantedY
            .coerceAtMost(windowSize.height - popupContentSize.height - margin)
            .coerceAtLeast(margin)
        return IntOffset(x, y)
    }
}

/** 菜单与窗口边缘的最小间距（px）。 */
private const val CONTEXT_MENU_MARGIN = 16

/**
 * 「取目录」选择模式常量的取值（AOSP 定义 = 3）。
 *
 * FileChooserParams 上的 MODE_OPEN_FOLDER 在 AOSP 里标了 `@hide`，不会导出到
 * SDK 桩 android.jar —— 直接引用会 Unresolved reference（构建实测报错），
 * 因此按 AOSP 定义用字面量。运行时 getMode() 仍会返回 3。
 */
private const val MODE_OPEN_FOLDER = 3

/**
 * 把 WebView 的文件选择约束翻译成系统文件选择 Intent。
 *
 * 四种模式各自对应一个 Intent：取目录用 ACTION_OPEN_DOCUMENT_TREE，存盘用
 * ACTION_CREATE_DOCUMENT，其余（单选 / 多选）都用 ACTION_OPEN_DOCUMENT。
 */
private fun buildFileChooserIntent(params: WebChromeClient.FileChooserParams?): Intent {
    val mode = params?.mode ?: WebChromeClient.FileChooserParams.MODE_OPEN

    // accept 类型既可能是正经 MIME（"image/*"），也可能是扩展名（".png"）或空串。
    // 只保留含 "/" 的合法 MIME：扩展名对 DocumentsUI 无效，会把可选范围收窄成空；
    // 一个合法项都没有时退回 "*/*"，否则用户什么文件都选不了（点了白点）。
    val mimeTypes = params?.acceptTypes.orEmpty()
        .map { it.trim() }
        .filter { it.contains('/') }

    return when (mode) {
        MODE_OPEN_FOLDER -> Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)

        WebChromeClient.FileChooserParams.MODE_SAVE ->
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = mimeTypes.firstOrNull() ?: "*/*"
            }

        else -> Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            // 多类型时 type 必须是 "*/*"，真正的类型集合交给 EXTRA_MIME_TYPES；
            // 只有一种类型则直接写进 type。
            type = if (mimeTypes.size == 1) mimeTypes[0] else "*/*"
            if (mimeTypes.size > 1) {
                putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toTypedArray())
            }
            if (mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
        }
    }
}

/**
 * 从文件选择结果里收集用户选中的 URI。
 *
 * 多选走 ClipData，单选走 data；取消（resultCode != RESULT_OK）返回 null ——
 * 调用方会把这个 null 原样兑现给 WebView，以解除它对本次选择的等待。
 */
private fun collectFileChooserResult(resultCode: Int, data: Intent?): Array<Uri>? {
    if (resultCode != Activity.RESULT_OK) return null
    data?.clipData?.let { clip ->
        val uris = ArrayList<Uri>(clip.itemCount)
        for (i in 0 until clip.itemCount) {
            clip.getItemAt(i).uri?.let { uris.add(it) }
        }
        if (uris.isNotEmpty()) return uris.toTypedArray()
    }
    return data?.data?.let { arrayOf(it) }
}

/**
 * 从 Compose 的 Context 里挖出 Activity。
 *
 * `LocalContext` 在 Activity 内通常就是 Activity 本身，但经过主题包装后
 * 可能是 ContextWrapper。`KeyChain.choosePrivateKeyAlias` 只收 Activity，
 * 因此要沿 baseContext 一路上溯。
 */
private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
