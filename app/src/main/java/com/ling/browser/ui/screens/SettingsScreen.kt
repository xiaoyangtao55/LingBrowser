package com.ling.browser.ui.screens

import android.content.Intent
import com.ling.browser.ui.theme.LingIcons
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ling.browser.data.prefs.DownloadLocation
import com.ling.browser.data.prefs.DownloaderType
import com.ling.browser.data.prefs.HomeBackground
import com.ling.browser.data.prefs.LingSettings
import com.ling.browser.data.prefs.NightMode
import com.ling.browser.data.prefs.SearchEngine
import com.ling.browser.data.prefs.ReaderFontSize
import com.ling.browser.data.prefs.TabsHeight

/**
 * 设置页。沿用 Material You 的列表结构：分组标题 + 开关/单选行。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: LingSettings,
    onSearchEngine: (SearchEngine) -> Unit,
    onHomepage: (String) -> Unit,
    onHomeBackground: (HomeBackground) -> Unit,
    onHomeBackgroundImage: (String) -> Unit,
    onNightMode: (NightMode) -> Unit,
    onDesktopMode: (Boolean) -> Unit,
    onDynamicColor: (Boolean) -> Unit,
    onForceDark: (Boolean) -> Unit,
    onBlockImages: (Boolean) -> Unit,
    onJavaScript: (Boolean) -> Unit,
    onAdBlock: (Boolean) -> Unit,
    onOpenAdBlockRules: () -> Unit,
    onTabsHeight: (TabsHeight) -> Unit,
    onReaderFontSize: (ReaderFontSize) -> Unit,
    onOpenLinksExternal: (Boolean) -> Unit,
    onRestoreSession: (Boolean) -> Unit,
    onDownloaderType: (DownloaderType) -> Unit,
    onDownloadLocation: (DownloadLocation) -> Unit,
    onClearData: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showEngineDialog by remember { mutableStateOf(false) }
    var showHomepageDialog by remember { mutableStateOf(false) }
    var showHomeBackgroundDialog by remember { mutableStateOf(false) }
    var showNightModeDialog by remember { mutableStateOf(false) }
    var showDownloaderTypeDialog by remember { mutableStateOf(false) }
    var showDownloadLocationDialog by remember { mutableStateOf(false) }

    // 选主页背景图。用 OpenDocument 而不是 GetContent：OpenDocument 支持持久化
    // 读权限，选一次后重启 App 仍读得到；GetContent 的授权只在本次会话有效。
    val context = LocalContext.current
    val pickBackgroundImage = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            // 尽力申请持久化读权限。部分 ROM 的相册 provider 不支持持久授权，
            // 申请失败顶多是"重启后可能读不到"，不该因此中断用户操作，故吞掉异常。
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            onHomeBackgroundImage(uri.toString())
            // 记下 URI 后再切到「自定义图片」，避免出现"模式是自定义、却没有图"的空档。
            onHomeBackground(HomeBackground.CUSTOM)
        }
        // 用户取消时什么都不改：保持原模式，不会把主页切成一个没有图的"自定义"。
    }
    var showTabsHeightDialog by remember { mutableStateOf(false) }
    var showReaderFontDialog by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(LingIcons.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            // ---------------- 搜索与主页 ----------------
            item { SectionHeader("搜索与主页") }
            item {
                SettingRow(
                    icon = LingIcons.Search,
                    title = "搜索引擎",
                    subtitle = settings.searchEngine.label,
                    onClick = { showEngineDialog = true },
                )
            }
            item {
                SettingRow(
                    icon = LingIcons.Home,
                    title = "自定义主页",
                    subtitle = settings.customHomepage.ifBlank { "内置主页（默认）" },
                    onClick = { showHomepageDialog = true },
                )
            }
            item {
                SettingRow(
                    icon = LingIcons.Layers,
                    title = "主页背景",
                    // 副标题带上"自定义图片"有没有选图，否则用户会看到
                    // "自定义图片"却不知道到底设没设 —— 空图时给出明确提示。
                    subtitle = when (settings.homeBackground) {
                        HomeBackground.SOLID -> "纯色"
                        HomeBackground.CUSTOM -> if (settings.homeBackgroundImageUri.isBlank()) {
                            "自定义图片（未选择）"
                        } else {
                            "自定义图片"
                        }
                    },
                    onClick = { showHomeBackgroundDialog = true },
                )
            }

            // ---------------- 外观 ----------------
            item { SectionHeader("外观") }
            item {
                SettingRow(
                    icon = LingIcons.Nightlight,
                    title = "夜间模式",
                    subtitle = when (settings.nightMode) {
                        NightMode.FOLLOW_SYSTEM -> "跟随系统"
                        NightMode.ALWAYS_ON -> "始终开启"
                        NightMode.ALWAYS_OFF -> "始终关闭"
                    },
                    onClick = { showNightModeDialog = true },
                )
            }
            item {
                SwitchRow(
                    icon = LingIcons.AutoAwesome,
                    title = "Material You 动态取色",
                    subtitle = "Android 12+ 从壁纸提取配色",
                    checked = settings.dynamicColor,
                    onCheckedChange = onDynamicColor,
                )
            }
            item {
                SettingRow(
                    icon = LingIcons.Layers,
                    title = "标签页面板高度",
                    subtitle = settings.tabsHeight.label,
                    onClick = { showTabsHeightDialog = true },
                )
            }
            item {
                SettingRow(
                    icon = LingIcons.Article,
                    title = "阅读模式字号",
                    subtitle = settings.readerFontSize.label,
                    onClick = { showReaderFontDialog = true },
                )
            }
            item {
                SwitchRow(
                    icon = LingIcons.History,
                    title = "恢复上次浏览页面",
                    // 说清"无痕不受影响"，免得用户以为关掉它连无痕也会被保存
                    subtitle = "启动时重建上次的标签页（无痕标签从不保存）",
                    checked = settings.restoreSession,
                    onCheckedChange = onRestoreSession,
                )
            }

            // ---------------- 浏览 ----------------
            item { SectionHeader("浏览") }
            item {
                SwitchRow(
                    icon = LingIcons.Nightlight,
                    title = "网页跟随夜间模式",
                    subtitle = "夜间模式开启时，网页内容也反色暗化",
                    checked = settings.forceDarkWebPages,
                    onCheckedChange = onForceDark,
                )
            }
            item {
                SwitchRow(
                    icon = LingIcons.Javascript,
                    title = "启用 JavaScript",
                    subtitle = "关闭可提升速度并减少干扰",
                    checked = settings.javaScriptEnabled,
                    onCheckedChange = onJavaScript,
                )
            }
            item {
                SwitchRow(
                    icon = LingIcons.Image,
                    title = "无图模式",
                    subtitle = "不加载图片，省流量",
                    checked = settings.blockImages,
                    onCheckedChange = onBlockImages,
                )
            }
            item {
                SwitchRow(
                    icon = LingIcons.Block,
                    title = "广告拦截",
                    // 说清是域名级，避免用户以为能挡所有广告
                    subtitle = "拦截常见广告与跟踪域名（域名级，不含元素级规则）",
                    checked = settings.adBlockEnabled,
                    onCheckedChange = onAdBlock,
                )
            }
            item {
                SettingRow(
                    icon = LingIcons.Block,
                    title = "自定义规则",
                    subtitle = if (settings.adBlockRules.isEmpty()) {
                        "添加要额外拦截的域名"
                    } else {
                        "${settings.adBlockRules.size} 条自定义域名规则"
                    },
                    onClick = onOpenAdBlockRules,
                )
            }
            item {
                SwitchRow(
                    icon = LingIcons.Public,
                    title = "用外部 App 打开链接",
                    // 默认关的原因要写清楚，否则用户会以为是漏做的功能
                    subtitle = "遇到 zhihu://、weixin:// 等链接时尝试拉起对应 App；" +
                        "关掉则忽略这类链接（推荐：本机没装该 App 时更稳定）",
                    checked = settings.openLinksInExternalApp,
                    onCheckedChange = onOpenLinksExternal,
                )
            }

            // ---------------- 下载 ----------------
            item { SectionHeader("下载") }
            item {
                SettingRow(
                    icon = LingIcons.Download,
                    title = "下载器",
                    subtitle = settings.downloaderType.label,
                    onClick = { showDownloaderTypeDialog = true },
                )
            }
            item {
                SettingRow(
                    icon = LingIcons.Folder,
                    title = "保存位置",
                    subtitle = when (settings.downloadLocation) {
                        DownloadLocation.APP_PRIVATE ->
                            "应用私有目录（无需存储权限，卸载时清理）"
                        DownloadLocation.PUBLIC_DOWNLOADS ->
                            "公共下载目录（其它应用可见，Android 9 及以下需存储权限）"
                    },
                    onClick = { showDownloadLocationDialog = true },
                )
            }

            // ---------------- 隐私 ----------------
            item { SectionHeader("隐私") }
            item {
                SettingRow(
                    icon = LingIcons.DeleteSweep,
                    title = "清除浏览数据",
                    subtitle = "缓存、Cookie 与历史记录",
                    onClick = { confirmClear = true },
                )
            }

            // ---------------- 关于 ----------------
            item { SectionHeader("关于") }
            item {
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Text(
                        text = "翎 · 极简浏览器",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "版本 1.1.0 · com.ling.browser",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "轻巧、干净、无推送",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    // ---- 搜索引擎选择 ----
    if (showEngineDialog) {
        AlertDialog(
            onDismissRequest = { showEngineDialog = false },
            title = { Text("选择搜索引擎") },
            text = {
                Column {
                    SearchEngine.entries.forEach { engine ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSearchEngine(engine)
                                    showEngineDialog = false
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = settings.searchEngine == engine,
                                onClick = {
                                    onSearchEngine(engine)
                                    showEngineDialog = false
                                },
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(engine.label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showEngineDialog = false }) { Text("完成") }
            },
        )
    }

    // ---- 夜间模式选择 ----
    if (showNightModeDialog) {
        AlertDialog(
            onDismissRequest = { showNightModeDialog = false },
            title = { Text("夜间模式") },
            text = {
                Column {
                    NightMode.entries.forEach { mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onNightMode(mode)
                                    showNightModeDialog = false
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = settings.nightMode == mode,
                                onClick = {
                                    onNightMode(mode)
                                    showNightModeDialog = false
                                },
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                when (mode) {
                                    NightMode.FOLLOW_SYSTEM -> "跟随系统"
                                    NightMode.ALWAYS_ON -> "始终开启"
                                    NightMode.ALWAYS_OFF -> "始终关闭"
                                },
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showNightModeDialog = false }) { Text("完成") }
            },
        )
    }

    // ---- 自定义主页 ----
    if (showHomepageDialog) {
        var draft by remember { mutableStateOf(settings.customHomepage) }
        AlertDialog(
            onDismissRequest = { showHomepageDialog = false },
            title = { Text("自定义主页") },
            text = {
                Column {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        singleLine = true,
                        placeholder = { Text("https://example.com") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "留空则使用「翎」的内置主页。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onHomepage(draft)
                    showHomepageDialog = false
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showHomepageDialog = false }) { Text("取消") }
            },
        )
    }

    // ---- 主页背景 ----
    if (showHomeBackgroundDialog) {
        // 选中某一档并关闭弹窗。选「自定义图片」但还没选过图时，直接拉系统
        // 选择器 —— 否则会切到一个"自定义"却没有图的空档（背景层只能退回
        // 主题色，用户看到的是"没生效"）。
        val select: (HomeBackground) -> Unit = { mode ->
            showHomeBackgroundDialog = false
            if (mode == HomeBackground.CUSTOM && settings.homeBackgroundImageUri.isBlank()) {
                pickBackgroundImage.launch(arrayOf("image/*"))
            } else {
                onHomeBackground(mode)
            }
        }
        AlertDialog(
            onDismissRequest = { showHomeBackgroundDialog = false },
            title = { Text("主页背景") },
            text = {
                Column {
                    HomeBackground.entries.forEach { mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { select(mode) }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = settings.homeBackground == mode,
                                onClick = { select(mode) },
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(mode.label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }

                    // 选/换自定义背景图。选完会自动切到「自定义图片」，
                    // 用户不必再回来点一次选项。
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showHomeBackgroundDialog = false
                                pickBackgroundImage.launch(arrayOf("image/*"))
                            }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = LingIcons.Image,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.width(16.dp))
                        Text(
                            text = if (settings.homeBackgroundImageUri.isBlank()) {
                                "选择图片…"
                            } else {
                                "更换图片…"
                            },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }

                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "「自定义图片」模式下，主页会透出背景；" +
                            "顶部有渐变遮罩，保证时钟与搜索框在浅色背景上也清晰。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showHomeBackgroundDialog = false }) { Text("完成") }
            },
        )
    }

    // ---- 标签页面板高度 ----
    if (showTabsHeightDialog) {
        AlertDialog(
            onDismissRequest = { showTabsHeightDialog = false },
            title = { Text("标签页面板高度") },
            text = {
                Column {
                    TabsHeight.entries.forEach { h ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onTabsHeight(h)
                                    showTabsHeightDialog = false
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = settings.tabsHeight == h,
                                onClick = {
                                    onTabsHeight(h)
                                    showTabsHeightDialog = false
                                },
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "${h.label}（${(h.fraction * 100).toInt()}%）",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showTabsHeightDialog = false }) { Text("完成") }
            },
        )
    }

    // ---- 阅读模式字号 ----
    if (showReaderFontDialog) {
        AlertDialog(
            onDismissRequest = { showReaderFontDialog = false },
            title = { Text("阅读模式字号") },
            text = {
                Column {
                    // 直接复用 ReaderPage.FontSize 的档位定义，
                    // 不再另立一份 —— 两处定义迟早会不同步。
                    ReaderFontSize.entries.forEach { s ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onReaderFontSize(s)
                                    showReaderFontDialog = false
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = settings.readerFontSize == s,
                                onClick = {
                                    onReaderFontSize(s)
                                    showReaderFontDialog = false
                                },
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                // 带上像素值，让用户知道"大"到底多大；
                                // 光有"小/中/大"三个字没有参照。
                                text = "${s.label}（${s.px}px）",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showReaderFontDialog = false }) { Text("完成") }
            },
        )
    }

    // ---- 下载器类型选择 ----
    if (showDownloaderTypeDialog) {
        AlertDialog(
            onDismissRequest = { showDownloaderTypeDialog = false },
            title = { Text("下载器") },
            text = {
                Column {
                    DownloaderType.entries.forEach { type ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onDownloaderType(type)
                                    showDownloaderTypeDialog = false
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = settings.downloaderType == type,
                                onClick = {
                                    onDownloaderType(type)
                                    showDownloaderTypeDialog = false
                                },
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(type.label, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    text = when (type) {
                                        DownloaderType.BUILT_IN ->
                                            "实时进度、暂停/恢复、断点续传"
                                        DownloaderType.SYSTEM ->
                                            "稳定省心，进度靠轮询，不支持应用内暂停"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDownloaderTypeDialog = false }) { Text("完成") }
            },
        )
    }

    // ---- 下载保存位置选择 ----
    if (showDownloadLocationDialog) {
        AlertDialog(
            onDismissRequest = { showDownloadLocationDialog = false },
            title = { Text("保存位置") },
            text = {
                Column {
                    DownloadLocation.entries.forEach { loc ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onDownloadLocation(loc)
                                    showDownloadLocationDialog = false
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = settings.downloadLocation == loc,
                                onClick = {
                                    onDownloadLocation(loc)
                                    showDownloadLocationDialog = false
                                },
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(loc.label, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    text = when (loc) {
                                        DownloadLocation.APP_PRIVATE ->
                                            "无需存储权限，卸载时随应用一起清理"
                                        DownloadLocation.PUBLIC_DOWNLOADS ->
                                            "其它应用可见；Android 10+ 无需权限，" +
                                                "Android 9 及以下需存储权限"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDownloadLocationDialog = false }) { Text("完成") }
            },
        )
    }

    // ---- 清除数据确认 ----
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清除浏览数据？") },
            text = { Text("将清除缓存、Cookie 与全部历史记录，且不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClearData()
                }) { Text("清除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 6.dp),
    )
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    HorizontalDivider(
        modifier = Modifier.padding(start = 58.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
    )
}

@Composable
private fun SwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
    HorizontalDivider(
        modifier = Modifier.padding(start = 58.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
    )
}
