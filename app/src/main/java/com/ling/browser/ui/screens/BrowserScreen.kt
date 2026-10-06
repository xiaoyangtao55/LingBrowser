package com.ling.browser.ui.screens

import com.ling.browser.ui.theme.LingIcons
import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ling.browser.data.prefs.NightMode
import com.ling.browser.ui.BrowserViewModel
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
        }
    }

    DisposableEffect(host) {
        viewModel.tabManager.attachHost(host)
        onDispose { viewModel.tabManager.detachHost(host) }
    }

    // 外部协议（tel: / mailto: / intent: …）交给系统
    LaunchedEffect(Unit) {
        viewModel.tabManager.onExternalUri = { uri ->
            runCatching {
                context.startActivity(
                    android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                true
            }.getOrDefault(false)
        }
        viewModel.tabManager.onDownloadRequested = { url, _ ->
            viewModel.showMessage("开始下载：${UrlUtils.hostOf(url)}")
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
                    Icon(LingIcons.BookmarkBorder, contentDescription = null)
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

/** 加载失败页。 */
@Composable
private fun ErrorScreen(
    message: String,
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
