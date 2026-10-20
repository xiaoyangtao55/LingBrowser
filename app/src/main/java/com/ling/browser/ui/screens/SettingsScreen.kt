package com.ling.browser.ui.screens

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.lazy.LazyListScope
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
import com.ling.browser.data.prefs.ReaderFontSize
import com.ling.browser.data.prefs.SearchEngine
import com.ling.browser.ui.theme.LingIcons

/**
 * 设置首页列出的分组。
 *
 * 每个分组点进去是一个独立的子页面。设置项变多以后，全部堆在一页里得一直滚，
 * 拆成「总览 + 分组子页」更好定位 —— 这也是 Android 设置的通行结构。
 * 子页仍然是设置的一部分，所以都留在本文件里，不像书签/历史那样另立文件。
 */
enum class SettingsGroup(val title: String) {
    SEARCH_HOME("搜索与主页"),
    APPEARANCE("外观"),
    BROWSING("浏览"),
    DOWNLOAD("下载"),
    PRIVACY("隐私"),
    BACKUP("备份与恢复"),
    ABOUT("关于"),
}

/**
 * 设置首页：只列分组，点进去才是该分组的具体设置项。
 */
@Composable
fun SettingsScreen(
    settings: LingSettings,
    onOpenGroup: (SettingsGroup) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsPageScaffold(
        title = "设置",
        onBack = onBack,
        modifier = modifier,
        content = {
            item {
                GroupEntryRow(
                    icon = LingIcons.Search,
                    title = SettingsGroup.SEARCH_HOME.title,
                    subtitle = if (settings.customHomepage.isBlank()) {
                        "${settings.searchEngine.label} · 内置主页"
                    } else {
                        "${settings.searchEngine.label} · ${settings.customHomepage}"
                    },
                    onClick = { onOpenGroup(SettingsGroup.SEARCH_HOME) },
                )
            }
            item {
                GroupEntryRow(
                    icon = LingIcons.AutoAwesome,
                    title = SettingsGroup.APPEARANCE.title,
                    subtitle = "${nightModeLabel(settings.nightMode)} · " +
                        "字号${settings.readerFontSize.label}",
                    onClick = { onOpenGroup(SettingsGroup.APPEARANCE) },
                )
            }
            item {
                GroupEntryRow(
                    icon = LingIcons.Public,
                    title = SettingsGroup.BROWSING.title,
                    subtitle = "JavaScript、无图模式、广告拦截",
                    onClick = { onOpenGroup(SettingsGroup.BROWSING) },
                )
            }
            item {
                GroupEntryRow(
                    icon = LingIcons.Download,
                    title = SettingsGroup.DOWNLOAD.title,
                    subtitle = "${settings.downloaderType.label} · " +
                        "${downloadLocationShortLabel(settings.downloadLocation)}",
                    onClick = { onOpenGroup(SettingsGroup.DOWNLOAD) },
                )
            }
            item {
                GroupEntryRow(
                    icon = LingIcons.PrivacyTip,
                    title = SettingsGroup.PRIVACY.title,
                    subtitle = "清除缓存、Cookie 与历史记录",
                    onClick = { onOpenGroup(SettingsGroup.PRIVACY) },
                )
            }
            item {
                GroupEntryRow(
                    icon = LingIcons.Bookmark,
                    title = SettingsGroup.BACKUP.title,
                    subtitle = "导出书签备份，或从其它浏览器导入",
                    onClick = { onOpenGroup(SettingsGroup.BACKUP) },
                )
            }
            item {
                GroupEntryRow(
                    icon = LingIcons.Article,
                    title = SettingsGroup.ABOUT.title,
                    subtitle = "版本、WebView 内核与开源许可",
                    onClick = { onOpenGroup(SettingsGroup.ABOUT) },
                )
            }
        },
    )
}

/**
 * 「搜索与主页」子页。
 */
@Composable
fun SearchHomeSettingsScreen(
    settings: LingSettings,
    onSearchEngine: (SearchEngine) -> Unit,
    onHomepage: (String) -> Unit,
    onHomeBackground: (HomeBackground) -> Unit,
    onHomeBackgroundImage: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showEngineDialog by remember { mutableStateOf(false) }
    var showHomepageDialog by remember { mutableStateOf(false) }
    var showHomeBackgroundDialog by remember { mutableStateOf(false) }

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

    SettingsPageScaffold(
        title = SettingsGroup.SEARCH_HOME.title,
        onBack = onBack,
        modifier = modifier,
        content = {
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
        },
    )

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
}

/**
 * 「外观」子页。
 */
@Composable
fun AppearanceSettingsScreen(
    settings: LingSettings,
    onNightMode: (NightMode) -> Unit,
    onDynamicColor: (Boolean) -> Unit,
    onReaderFontSize: (ReaderFontSize) -> Unit,
    onRestoreSession: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showNightModeDialog by remember { mutableStateOf(false) }
    var showReaderFontDialog by remember { mutableStateOf(false) }

    SettingsPageScaffold(
        title = SettingsGroup.APPEARANCE.title,
        onBack = onBack,
        modifier = modifier,
        content = {
            item {
                SettingRow(
                    icon = LingIcons.Nightlight,
                    title = "夜间模式",
                    subtitle = nightModeLabel(settings.nightMode),
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
        },
    )

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
                                text = nightModeLabel(mode),
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
}

/**
 * 「浏览」子页。
 */
@Composable
fun BrowsingSettingsScreen(
    settings: LingSettings,
    onForceDark: (Boolean) -> Unit,
    onJavaScript: (Boolean) -> Unit,
    onBlockImages: (Boolean) -> Unit,
    onAdBlock: (Boolean) -> Unit,
    onOpenAdBlockRules: () -> Unit,
    /** 已导入的订阅名单份数，用来给出"有没有订阅"的一眼判据。 */
    blockSubscriptionCount: Int,
    onOpenBlockSubscriptions: () -> Unit,
    /** 累计拦下的广告/跟踪请求数（跨重启累加）。 */
    blockedCount: Long,
    onResetBlockedCount: () -> Unit,
    onOpenLinksExternal: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsPageScaffold(
        title = SettingsGroup.BROWSING.title,
        onBack = onBack,
        modifier = modifier,
        content = {
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
                SettingRow(
                    icon = LingIcons.Public,
                    title = "订阅名单",
                    // 说清"内置保底 + 订阅增强"，避免用户以为不订阅就没有拦截
                    subtitle = if (blockSubscriptionCount == 0) {
                        "内置规则保底；从网址或本地导入 Hosts / 域名名单可增强"
                    } else {
                        "已导入 $blockSubscriptionCount 份名单，可导入、更新或停用"
                    },
                    onClick = onOpenBlockSubscriptions,
                )
            }
            item {
                SettingRow(
                    icon = LingIcons.Block,
                    title = "已拦截广告",
                    // 说明是"累计"并提示可清零；否则用户会以为这个数只会涨、清不掉
                    subtitle = if (blockedCount == 0L) {
                        "累计拦截广告/跟踪请求数（点按可清零）"
                    } else {
                        "累计已拦下 $blockedCount 个广告/跟踪请求，点按清零"
                    },
                    onClick = onResetBlockedCount,
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
        },
    )
}

/**
 * 「下载」子页。
 */
@Composable
fun DownloadSettingsScreen(
    settings: LingSettings,
    onDownloaderType: (DownloaderType) -> Unit,
    onDownloadLocation: (DownloadLocation) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDownloaderTypeDialog by remember { mutableStateOf(false) }
    var showDownloadLocationDialog by remember { mutableStateOf(false) }

    SettingsPageScaffold(
        title = SettingsGroup.DOWNLOAD.title,
        onBack = onBack,
        modifier = modifier,
        content = {
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
        },
    )

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
}

/**
 * 「隐私」子页。
 */
@Composable
fun PrivacySettingsScreen(
    onClearData: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmClear by remember { mutableStateOf(false) }

    SettingsPageScaffold(
        title = SettingsGroup.PRIVACY.title,
        onBack = onBack,
        modifier = modifier,
        content = {
            item {
                SettingRow(
                    icon = LingIcons.DeleteSweep,
                    title = "清除浏览数据",
                    subtitle = "缓存、Cookie 与历史记录",
                    onClick = { confirmClear = true },
                )
            }
        },
    )

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

/**
 * 「备份与恢复」子页。
 *
 * 只做书签的导入/导出 —— 用 Netscape 书签 HTML（浏览器之间搬书签的事实标准），
 * 不用自有格式：导出的文件在 Chrome / Edge / Firefox / Safari 里都能直接导入，
 * 反过来它们导出的文件这里也读得懂，备份才有意义。
 *
 * 选文件都用系统选择器（SAF），因此**不需要存储权限**，
 * 用户选哪儿就写哪儿 / 读哪儿。
 */
@Composable
fun BackupSettingsScreen(
    onExport: (Uri) -> Unit,
    onImport: (Uri) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 导出：CreateDocument 让用户选保存位置并起名，默认名 bookmarks.html。
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/html"),
    ) { uri ->
        // 用户取消时 uri 为 null，什么都不做（不会产生空文件）。
        if (uri != null) onExport(uri)
    }

    // 导入：OpenDocument 让用户挑一个已有的书签文件。
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) onImport(uri)
    }

    SettingsPageScaffold(
        title = SettingsGroup.BACKUP.title,
        onBack = onBack,
        modifier = modifier,
        content = {
            item {
                SettingRow(
                    icon = LingIcons.Article,
                    title = "导出书签",
                    subtitle = "全部书签保存为一个 HTML 文件，可在电脑或其它浏览器打开",
                    onClick = { exportLauncher.launch("bookmarks.html") },
                )
            }
            item {
                SettingRow(
                    icon = LingIcons.FolderOpen,
                    title = "导入书签",
                    subtitle = "读取其它浏览器导出的 HTML 文件，重复的网址会自动跳过",
                    // 多给几个 MIME：不同来源的书签文件有的被标成 text/html，
                    // 有的被标成 octet-stream，只认一种会让用户"看不到自己的文件"。
                    // 末尾的 */* 作为兜底，宁可多显示几个文件，也别让该选的选不中。
                    onClick = {
                        importLauncher.launch(
                            arrayOf(
                                "text/html",
                                "application/xhtml+xml",
                                "text/plain",
                                "*/*",
                            ),
                        )
                    },
                )
            }
            item {
                Text(
                    // 把两个关键承诺写清楚：导入是合并、UI 不会丢书签；
                    // 图标不在格式里，导入后会由书签页自动重建。
                    text = "导入采用合并方式：只新增、不删除，库里已有的网址会跳过。" +
                        "网站图标不在备份文件里，导入后打开书签页会自动补齐。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
        },
    )
}

/**
 * 设置各页共用的外壳：居中标题栏 + 返回 + 可滚动内容区。
 *
 * `content` 给默认值只是为了让调用点能用尾随 lambda ——
 * check_calls 会把"必填但没在括号里具名传入"的参数判成漏传，
 * 尾随 lambda 抓不到；给个默认值即可绕开。每个调用点仍会实打实提供内容。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsPageScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit = {},
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(title) },
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
            content = content,
        )
    }
}

/** 设置首页的分组入口：图标 + 标题 + 摘要 + 右箭头，明确"可点进去"。 */
@Composable
private fun GroupEntryRow(
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
        Spacer(Modifier.width(8.dp))
        Icon(
            imageVector = LingIcons.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
    HorizontalDivider(
        modifier = Modifier.padding(start = 58.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
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

/** 夜间模式的中文名。放在一处，避免总览摘要与选择弹窗各写一遍。 */
private fun nightModeLabel(mode: NightMode): String = when (mode) {
    NightMode.FOLLOW_SYSTEM -> "跟随系统"
    NightMode.ALWAYS_ON -> "始终开启"
    NightMode.ALWAYS_OFF -> "始终关闭"
}

/** 保存位置的短名，供设置首页摘要使用（完整说明在「下载」子页里）。 */
private fun downloadLocationShortLabel(loc: DownloadLocation): String = when (loc) {
    DownloadLocation.APP_PRIVATE -> "应用私有目录"
    DownloadLocation.PUBLIC_DOWNLOADS -> "公共下载目录"
}
