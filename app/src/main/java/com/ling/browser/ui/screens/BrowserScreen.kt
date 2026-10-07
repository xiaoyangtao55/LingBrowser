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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
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
import com.ling.browser.ui.components.IncognitoBanner
import com.ling.browser.util.UrlUtils

/**
 * 浏览器主界面：地址栏 + WebView 容器 + 底部工具栏。
 *
 * WebView 的宿主用一个 [FrameLayout]，由 [BrowserViewModel.tabManager] 负责挂载/切换实例。
 * 这样切标签只是换 View，不会重建 WebView。
 *
 * 布局分工：
 *  - 地址栏右侧常驻「刷新/停止」——最高频操作，放在触手可及处；
 *  - 底部工具栏只留 5 个动作，低频项收进「更多」（菜单锚定在按钮自身，对齐右侧）；
 *  - 内置主页由 WebView 直接渲染（见 [com.ling.browser.web.HomePage]），不再是覆盖层，
 *    因此主页也能正常参与前进/后退历史。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel,
    onOpenTabs: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit,
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
    // 主页的快捷入口来自书签，必须在这里无条件收集 ——
    // 放进 when 分支里会形成条件式 composable 调用，破坏重组时的槽位稳定性。
    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
    val pendingDownload by viewModel.pendingDownload.collectAsStateWithLifecycle()
    // 阅读模式是否开启。必须 collect（而不是直接读 viewModel.readerActive），
    // 否则进入/退出后菜单文案不会刷新。
    val readerActive by viewModel.readerActive.collectAsStateWithLifecycle()

    var showMoreMenu by remember { mutableStateOf(false) }
    var exitConfirm by remember { mutableStateOf(false) }

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

    // 把 Material You 配色与书签快捷入口同步给内置主页，并重绘正在显示的主页。
    // 这样切换深浅色或增删书签后，主页会立即跟着变。
    val colorScheme = MaterialTheme.colorScheme
    LaunchedEffect(colorScheme, bookmarks) {
        viewModel.applyHomeTheme(
            background = colorScheme.background,
            onBackground = colorScheme.onBackground,
            primary = colorScheme.primary,
            onPrimary = colorScheme.onPrimary,
            primaryContainer = colorScheme.primaryContainer,
            onPrimaryContainer = colorScheme.onPrimaryContainer,
            dark = colorScheme.background.luminance() < 0.5f,
            links = bookmarks.map { it.title.ifBlank { it.url } to it.url },
        )
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
        Box(modifier = Modifier.weight(1f)) {
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
        BottomToolbar(
            canGoBack = activeTab?.canGoBack == true,
            canGoForward = activeTab?.canGoForward == true,
            tabCount = tabs.size,
            isIncognito = activeTab?.isIncognito == true,
            moreExpanded = showMoreMenu,
            onBack = { viewModel.goBack() },
            onForward = { viewModel.goForward() },
            onHome = { viewModel.goHome() },
            onTabs = onOpenTabs,
            onMoreToggle = { showMoreMenu = !showMoreMenu },
            onMoreDismiss = { showMoreMenu = false },
        ) {
            // 菜单内容由工具栏内的「更多」按钮锚定，因此对齐在右侧
            DropdownMenuItem(
                text = { Text(if (isBookmarked) "取消收藏" else "添加书签") },
                leadingIcon = {
                    // 三种图标各司其职，不要混用：
                    //   未收藏 -> bookmark              空心书签
                    //   已收藏 -> bookmark_added_fill   **实心**书签（收好了）
                    //   动  作 -> bookmark_add          空心书签 + 加号
                    //
                    // 本项是一个**动作**（添加/取消），所以未收藏时用带加号的
                    // bookmark_add；否则它和下面那个「书签」入口图标完全相同
                    // （都只是空心书签），用户分不出哪个是动作、哪个是跳转。
                    // 已收藏时用实心图标，一眼看出当前状态。
                    Icon(
                        when {
                            isBookmarked -> LingIcons.Bookmark
                            else -> LingIcons.BookmarkAdd
                        },
                        contentDescription = null,
                    )
                },
                onClick = {
                    viewModel.toggleBookmark()
                    showMoreMenu = false
                },
            )
            DropdownMenuItem(
                text = { Text("书签") },
                leadingIcon = { Icon(LingIcons.BookmarkBorder, contentDescription = null) },
                onClick = {
                    onOpenBookmarks()
                    showMoreMenu = false
                },
            )
            DropdownMenuItem(
                text = { Text("历史记录") },
                leadingIcon = { Icon(LingIcons.History, contentDescription = null) },
                onClick = {
                    onOpenHistory()
                    showMoreMenu = false
                },
            )
            DropdownMenuItem(
                text = { Text("下载") },
                leadingIcon = { Icon(LingIcons.Download, contentDescription = null) },
                onClick = {
                    onOpenDownloads()
                    showMoreMenu = false
                },
            )
            DropdownMenuItem(
                text = { Text("新建无痕标签页") },
                leadingIcon = { Icon(LingIcons.PrivacyTip, contentDescription = null) },
                onClick = {
                    viewModel.newIncognitoTab()
                    showMoreMenu = false
                },
            )
            DropdownMenuItem(
                text = {
                    Text(if (settings.desktopMode) "关闭电脑模式" else "开启电脑模式")
                },
                leadingIcon = { Icon(LingIcons.DesktopWindows, contentDescription = null) },
                onClick = {
                    viewModel.setDesktopMode(!settings.desktopMode)
                    showMoreMenu = false
                },
            )
            DropdownMenuItem(
                text = {
                    Text(
                        when (settings.nightMode) {
                            NightMode.FOLLOW_SYSTEM -> "夜间模式：跟随系统"
                            NightMode.ALWAYS_ON -> "夜间模式：已开启"
                            NightMode.ALWAYS_OFF -> "夜间模式：已关闭"
                        }
                    )
                },
                leadingIcon = { Icon(LingIcons.Nightlight, contentDescription = null) },
                onClick = {
                    viewModel.setNightMode(
                        when (settings.nightMode) {
                            NightMode.FOLLOW_SYSTEM -> NightMode.ALWAYS_ON
                            NightMode.ALWAYS_ON -> NightMode.ALWAYS_OFF
                            NightMode.ALWAYS_OFF -> NightMode.FOLLOW_SYSTEM
                        }
                    )
                    showMoreMenu = false
                },
            )
            // 阅读模式。文案随状态切换，让用户一眼看出点下去是进还是出
            // （与上面的电脑模式同一种写法）。
            DropdownMenuItem(
                text = {
                    Text(if (readerActive) "退出阅读模式" else "阅读模式")
                },
                leadingIcon = { Icon(LingIcons.Article, contentDescription = null) },
                onClick = {
                    viewModel.toggleReaderMode()
                    showMoreMenu = false
                },
            )
            DropdownMenuItem(
                text = { Text("设置") },
                leadingIcon = { Icon(LingIcons.Settings, contentDescription = null) },
                onClick = {
                    onOpenSettings()
                    showMoreMenu = false
                },
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
