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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ling.browser.data.prefs.NightMode
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

    // 底部手势条的内边距不在这里留，交给 BottomToolbar 自己吃：
    // 工具栏底色要铺到屏幕最下沿（见其文件头说明）。
    Column(
        modifier = modifier.fillMaxSize(),
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
            // 面板是半透明的，透出背后的网页（"标签页是临时面板而非独立页面"）。
            // 曾经还有「全屏」档位：全屏时不透明、且不再透出网页，与独立页面无异，
            // 已随设置项一并移除，这里恒定用半透明容器色。
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = TABS_SHEET_ALPHA),
            dragHandle = { SheetDragHandle() },
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
                // 面板高度落在**内容**的高度上，而不是依赖 sheet 自带的"半展开"锚点。
                modifier = Modifier.fillMaxHeight(TABS_SHEET_HEIGHT_FRACTION),
            )
        }
    }

    // ---- 「更多」面板 ----
    // 原来锚定在工具栏「更多」按钮上的下拉菜单，现在同样改为自底部升起的面板。
    // 内容排成图标网格（见 [MoreGrid]）：11 项竖排要占满大半屏，横排 4 列后
    // 只有 3 行，面板更矮、基本不需要滚动。
    if (showMoreSheet) {
        ModalBottomSheet(
            onDismissRequest = { showMoreSheet = false },
            sheetState = moreSheetState,
            // 与底部工具栏同色，面板读起来和工具栏是同一层 chrome。
            // 网格条目自己不铺底色，因此面板自上而下只有这一种颜色 ——
            // 不会再出现「顶部一条与内容对不齐」的分色。
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            dragHandle = { SheetDragHandle() },
        ) {
            val items = listOf(
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
                MoreItem(
                    icon = LingIcons.Settings,
                    label = "设置",
                    onClick = {
                        onOpenSettings()
                        closeMoreSheet()
                    },
                ),
            )
            MoreGrid(
                items = items,
                modifier = Modifier
                    .fillMaxWidth()
                    // 小屏 / 横屏时条目可能超出一屏，留一层滚动兜底
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
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
 * 面板顶部的拖拽手柄。
 *
 * 不用 Material3 的 `BottomSheetDefaults.DragHandle`：它自带的底色与面板容器色
 * 不是同一个来源，实机上表现为顶部多出一条与内容对不齐的横条。这里显式画一条
 * 低对比的淡灰小横条（贴近系统手势条「小白条」的观感），且**不额外铺底色**——
 * 面板上、中、下因此同色。
 */
@Composable
private fun SheetDragHandle() {
    Box(
        modifier = Modifier
            .padding(top = 16.dp, bottom = 12.dp)
            .size(width = 40.dp, height = 4.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = SHEET_HANDLE_ALPHA)),
    )
}

/**
 * 手柄的透明度。
 *
 * 取 onSurface 的透明度分量：浅色主题下是淡灰、深色下是淡白，与系统手势条
 * 跟随明暗的行为一致。0.35 是「看得出、但不抢眼」的取值。
 */
private const val SHEET_HANDLE_ALPHA = 0.35f

/**
 * 「更多」面板的一个条目。
 *
 * 收成数据而不是逐条散写：网格要按「每行几个」分组，散写就分不出组了。
 */
private data class MoreItem(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit,
)

/** 「更多」面板的列数：四列在手机上每格约 80dp，图标 + 两行文字刚好放得下。 */
private const val MORE_GRID_COLUMNS = 4

/** 格子高度：上边距 12dp + 图标 24dp + 间距 8dp + 最多两行文字，末行留一点余量。 */
private val MORE_ITEM_HEIGHT = 84.dp

/**
 * 「更多」面板的图标网格：每行 [MORE_GRID_COLUMNS] 个，图标在上、文字在下。
 *
 * 用固定列数的 Row 分组，而不是 LazyVerticalGrid：总共只有 3 行，惰性化没有
 * 收益，反而会在 BottomSheet 里再嵌一层可滚动容器，与面板自身的拖动 / 收起
 * 手势抢事件。
 */
@Composable
private fun MoreGrid(
    items: List<MoreItem>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items.chunked(MORE_GRID_COLUMNS).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                row.forEach { item ->
                    MoreGridItem(item = item, modifier = Modifier.weight(1f))
                }
                // 末行不满时补空位：否则最后几个格子会被拉宽，与上面的列错位
                repeat(MORE_GRID_COLUMNS - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** 网格里的单个条目。 */
@Composable
private fun MoreGridItem(
    item: MoreItem,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .height(MORE_ITEM_HEIGHT)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = item.onClick)
            // 顶部对齐 + 固定上边距：不管文字是一行还是两行，图标都落在同一条
            // 水平线上。若用居中排列，两行文字的格子会把图标顶上去，
            // 一行文字的格子又把它压下来，整排图标就参差不齐了。
            .padding(start = 4.dp, end = 4.dp, top = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = item.label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            // 「新建无痕标签页」这类文案在四列宽度下会折成两行
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
