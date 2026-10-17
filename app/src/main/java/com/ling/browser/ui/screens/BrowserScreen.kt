package com.ling.browser.ui.screens

import com.ling.browser.ui.theme.LingIcons
import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ling.browser.data.prefs.NightMode
import com.ling.browser.data.prefs.TabsHeight
import com.ling.browser.ui.BrowserViewModel
import com.ling.browser.ui.PendingDownload
import com.ling.browser.ui.components.AddressBar
import com.ling.browser.ui.components.BottomToolbar
import com.ling.browser.ui.components.DesktopModeBanner
import com.ling.browser.ui.components.HomeBackgroundLayer
import com.ling.browser.ui.components.IncognitoBanner
import com.ling.browser.util.UrlUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
 *  - 内置主页由 WebView 直接渲染（见 [com.ling.browser.web.HomePage]），不再是覆盖层，
 *    因此主页也能正常参与前进/后退历史。
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    // 纯色档画主题背景色、自定义图片档在它之上叠照片与遮罩 —— 主页 HTML 与
    // WebView 自身背景都是透明的，主页的背景颜色全部由这一层给。
    // 切到普通网页后必须停止绘制，否则网页自身的透明区域会露出下面这层色。
    // （组件本身仍无条件调用，见下面 Box 里的说明。）
    val activeUrl = activeTab?.url
    val homeBackgroundVisible = activeUrl != null && UrlUtils.isHome(activeUrl)
    val pendingDownload by viewModel.pendingDownload.collectAsStateWithLifecycle()
    // 阅读模式是否开启。必须 collect（而不是直接读 viewModel.readerActive），
    // 否则进入/退出后菜单文案不会刷新。
    val readerActive by viewModel.readerActive.collectAsStateWithLifecycle()

    // 两个 BottomSheet 的展开状态：「更多」与「标签页」。
    // 面板本体是 ModalBottomSheet（自屏幕底部升起），这里只留一个布尔开关。
    var showMoreSheet by remember { mutableStateOf(false) }
    var showTabsSheet by remember { mutableStateOf(false) }
    var exitConfirm by remember { mutableStateOf(false) }

    // 收起面板时先播完退场动画再卸载组件：直接把开关置 false 会让面板
    // "啪"地消失，跳过 ModalBottomSheet 自带的收起动画。
    val scope = rememberCoroutineScope()
    val moreSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val tabsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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
        onDispose { viewModel.tabManager.detachHost(host) }
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
            // 文件名交给 DownloadManager 从 Content-Disposition 推断
            // （比在这里猜 URL 末段可靠），因此传 null。
            // 注意这里只是「请求」，真正开始下载要等用户在确认框里点确认。
            viewModel.requestDownload(url, mimeType, fileName = null)
        }
    }

    // 页面加载完成后刷新收藏态
    LaunchedEffect(activeTab?.url) {
        viewModel.refreshBookmarkState()
    }

    // 把 Material You 配色同步给内置主页，并重绘正在显示的主页。
    // 这样切换深浅色后，主页会立即跟着变。
    val colorScheme = MaterialTheme.colorScheme
    // 界面当前的明暗：既喂给主页配色，也当作"网页要不要重算暗化"的触发器。
    val uiDark = colorScheme.background.luminance() < 0.5f
    // settings.homeBackground / homeBackgroundImageUri 也作为 key：背景模式
    // （纯色 ↔ 自定义图片）变化时，原生背景层虽然会跟着 settings 重组，
    // 但 WebView 自身的透明度需要由 applyHomeTheme → ensureHomeRendered 重新保障
    // （见 BrowserViewModel.applyHomeTheme 的说明）。这一步不经过配色变化，
    // 必须显式触发，否则切换后主页仍停在不透明白底、原生背景层被盖住，
    // 表现为"改了背景模式要手动刷新才生效"。
    LaunchedEffect(colorScheme, settings.homeBackground, settings.homeBackgroundImageUri) {
        viewModel.applyHomeTheme(
            background = colorScheme.background,
            onBackground = colorScheme.onBackground,
            primary = colorScheme.primary,
            onPrimary = colorScheme.onPrimary,
            primaryContainer = colorScheme.primaryContainer,
            onPrimaryContainer = colorScheme.onPrimaryContainer,
            dark = uiDark,
        )
    }

    // 主页时钟：主页是**零脚本** HTML（见 tools/check_home_html.py 对 <script> 的禁令），
    // 时间不会自己走，只能由原生侧每分钟把当前 HH:mm 注入回那个文本节点。
    // 对齐到**下一个整分**再刷新，而不是固定 sleep 60s：后者会让显示的时间
    // 比真实时间慢将近一整分钟（效果上等于时钟一直慢一拍）。
    LaunchedEffect(Unit) {
        while (true) {
            delay(CLOCK_PERIOD_MS - System.currentTimeMillis() % CLOCK_PERIOD_MS)
            viewModel.updateHomeClock()
        }
    }

    // 「跟随系统」时，运行中切换系统明暗**不经过设置流**（settings.collect 不触发），
    // 但界面会跟着系统变 —— 网页侧的暗化必须在这里补算一次，否则会出现
    // "界面已经变深、网页还是亮的"。主页/阅读视图由上面的配色同步覆盖。
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding(),
    ) {
        // ---- 顶部提示条 ----
        AnimatedVisibility(visible = activeTab?.isIncognito == true) {
            IncognitoBanner(modifier = Modifier.statusBarsPadding())
        }
        AnimatedVisibility(visible = settings.desktopMode && activeTab?.isIncognito != true) {
            DesktopModeBanner(modifier = Modifier.statusBarsPadding())
        }

        // ---- 地址栏 ----
        AddressBar(
            text = addressText,
            isEditing = addressEditing,
            isLoading = activeTab?.isLoading == true,
            progress = activeTab?.progress,
            isSecure = activeTab?.url?.startsWith("https") == true,
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
                .then(
                    if (activeTab?.isIncognito != true && !settings.desktopMode) {
                        Modifier.statusBarsPadding()
                    } else {
                        Modifier
                    }
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )

        // ---- 网页内容区 ----
        // imePadding：键盘弹出时把内容区从底部压矮，WebView 视口随之变短，
        // 主页 HTML 的 flex 垂直居中就会把时钟与搜索框一起**上移** ——
        // 这正是"搜索框跟随输入法上下移动"。没有它时（windowSoftInputMode
        // 已是 adjustResize，但 edge-to-edge 下窗口本身不缩放），搜索框会
        // 被键盘整块盖住。
        Box(
            modifier = Modifier
                .weight(1f)
                .imePadding(),
        ) {
            // 原生背景层：画在 WebView **之下**，主页的背景颜色全由它给
            // —— 纯色档画主题背景色，自定义图片档在它之上叠照片与顶部遮罩。
            // 主页 HTML 与 WebView 自身背景都是透明的（见 WebTabManager.loadHome），
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
            // 主页现在由 WebView 自己渲染（见 HomePage），不再是覆盖层 ——
            // 这样主页也能正常参与前进/后退历史。
            //
            // 标签页面板是**半透明叠加层**（见 TabsScreen），背后的 WebView
            // 必须继续渲染，否则面板后面就是空白。
            AndroidView(
                factory = { host },
                modifier = Modifier.fillMaxSize(),
            )

            activeTab?.errorText?.let { err ->
                ErrorScreen(
                    message = err,
                    url = activeTab.url,
                    onRetry = viewModel::refresh,
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

    // ---- 「标签页」面板 ----
    // 用 ModalBottomSheet 自屏幕底部升起：遮罩、圆角、拖动关闭、入场/退场动画
    // 都由组件提供，TabsScreen 只负责面板内容（见其文件头说明）。
    if (showTabsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showTabsSheet = false },
            sheetState = tabsSheetState,
            // 半屏档位用半透明色，透出背后的网页（"标签页是临时面板而非独立页面"）；
            // 全屏档位回退为不透明色，否则整屏半透明会让网页文字与列表标题互相干扰。
            containerColor = if (settings.tabsHeight == TabsHeight.FULL) {
                MaterialTheme.colorScheme.surface
            } else {
                MaterialTheme.colorScheme.surface.copy(alpha = TABS_SHEET_ALPHA)
            },
        ) {
            TabsScreen(
                tabs = tabs,
                activeId = activeId,
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
                tabsHeight = settings.tabsHeight,
                onTabsHeightChange = viewModel::setTabsHeight,
                // 高度档位由 settings 决定（全屏 / 一半）：把它落在**内容**的高度上，
                // 而不是依赖 sheet 自带的"半展开"锚点 —— 后者记不住用户的档位选择。
                modifier = Modifier.fillMaxHeight(settings.tabsHeight.fraction),
            )
        }
    }

    // ---- 「更多」面板 ----
    // 原来锚定在工具栏「更多」按钮上的下拉菜单，现在同样改为自底部升起的面板。
    if (showMoreSheet) {
        ModalBottomSheet(
            onDismissRequest = { showMoreSheet = false },
            sheetState = moreSheetState,
        ) {
            // 条目较多：加一层 verticalScroll，小屏 / 横屏时也能滚到底。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 12.dp),
            ) {
                MoreMenuItem(
                    icon = if (isBookmarked) LingIcons.Bookmark else LingIcons.BookmarkAdd,
                    label = if (isBookmarked) "取消收藏" else "添加书签",
                    onClick = {
                        viewModel.toggleBookmark()
                        closeMoreSheet()
                    },
                )
                MoreMenuItem(
                    icon = LingIcons.BookmarkBorder,
                    label = "书签",
                    onClick = {
                        onOpenBookmarks()
                        closeMoreSheet()
                    },
                )
                MoreMenuItem(
                    icon = LingIcons.History,
                    label = "历史记录",
                    onClick = {
                        onOpenHistory()
                        closeMoreSheet()
                    },
                )
                MoreMenuItem(
                    icon = LingIcons.Download,
                    label = "下载",
                    onClick = {
                        onOpenDownloads()
                        closeMoreSheet()
                    },
                )
                MoreMenuItem(
                    icon = LingIcons.PrivacyTip,
                    label = "新建无痕标签页",
                    onClick = {
                        viewModel.newIncognitoTab()
                        closeMoreSheet()
                    },
                )
                MoreMenuItem(
                    icon = LingIcons.DesktopWindows,
                    label = if (settings.desktopMode) "关闭电脑模式" else "开启电脑模式",
                    onClick = {
                        viewModel.setDesktopMode(!settings.desktopMode)
                        closeMoreSheet()
                    },
                )
                MoreMenuItem(
                    icon = LingIcons.Nightlight,
                    label = when (settings.nightMode) {
                        NightMode.FOLLOW_SYSTEM -> "夜间模式：跟随系统"
                        NightMode.ALWAYS_ON -> "夜间模式：已开启"
                        NightMode.ALWAYS_OFF -> "夜间模式：已关闭"
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
                )
                // 阅读模式。文案随状态切换，让用户一眼看出点下去是进还是出。
                MoreMenuItem(
                    icon = LingIcons.Article,
                    label = if (readerActive) "退出阅读模式" else "阅读模式",
                    onClick = {
                        viewModel.toggleReaderMode()
                        closeMoreSheet()
                    },
                )
                // 资源嗅探：先触发嗅探（写结果），再进二级页展示。
                // 嗅探是异步的，但页面会 collect sniffResults，结果回来后自动刷新。
                MoreMenuItem(
                    icon = LingIcons.Movie,
                    label = "资源嗅探",
                    onClick = {
                        viewModel.sniffResources()
                        onOpenSniff()
                        closeMoreSheet()
                    },
                )
                MoreMenuItem(
                    icon = LingIcons.Settings,
                    label = "设置",
                    onClick = {
                        onOpenSettings()
                        closeMoreSheet()
                    },
                )
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
                TextButton(onClick = viewModel::confirmPendingDownload) { Text("下载") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissPendingDownload) { Text("取消") }
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
}

/**
 * 主页时钟的刷新周期（毫秒）。
 *
 * 一分钟一次：主页时钟只显示 `HH:mm`，粒度就是分钟，再密没有意义、
 * 反而白白唤醒 WebView 执行一次 JS。
 */
private const val CLOCK_PERIOD_MS = 60_000L

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

/** 加载失败页。 */
@Composable
private fun ErrorScreen(    message: String,
    url: String,
    onRetry: () -> Unit,
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
        }
    }
}

/**
 * 「更多」面板里的单个条目。
 *
 * 用 [ListItem] 而不是下拉菜单项：面板改成 BottomSheet 后条目要更大、更好点
 * （贴合单手操作），ListItem 的默认内边距与排版正合适。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoreMenuItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { Icon(icon, contentDescription = null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
