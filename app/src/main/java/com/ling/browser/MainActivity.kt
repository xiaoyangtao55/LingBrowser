package com.ling.browser

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ling.browser.data.db.DownloadEntry
import com.ling.browser.data.prefs.NightMode
import com.ling.browser.ui.BrowserViewModel
import com.ling.browser.ui.screens.AdBlockRulesScreen
import com.ling.browser.ui.screens.AdBlockSubscriptionsScreen
import com.ling.browser.ui.screens.BookmarksScreen
import com.ling.browser.ui.screens.SniffedResourcesScreen
import com.ling.browser.ui.screens.BrowserScreen
import com.ling.browser.ui.screens.DownloadsScreen
import com.ling.browser.ui.screens.HistoryScreen
import com.ling.browser.ui.screens.SettingsScreen
import com.ling.browser.ui.screens.AboutScreen
import com.ling.browser.ui.screens.AppearanceSettingsScreen
import com.ling.browser.ui.screens.BackupSettingsScreen
import com.ling.browser.ui.screens.BrowsingSettingsScreen
import com.ling.browser.ui.screens.DownloadSettingsScreen
import com.ling.browser.ui.screens.PrivacySettingsScreen
import com.ling.browser.ui.screens.SearchHomeSettingsScreen
import com.ling.browser.ui.screens.SettingsGroup
import com.ling.browser.ui.theme.LingTheme
import com.ling.browser.web.AdBlocker

/**
 * 唯一的 Activity。
 *
 * 导航用一个简单的密封类 + 状态机实现 —— 极简浏览器只有 4 个二级页面，
 * 引入 Navigation Compose 反而增加体积和心智负担。
 */
class MainActivity : ComponentActivity() {

    /**
     * 与 setContent 内部用的是**同一个** ViewModel 实例
     * （`by viewModels()` 与 `viewModel()` 共享同一个 ViewModelStore），
     * 因此 Activity 生命周期回调里也能拿到它来保存会话。
     */
    private val browserViewModel: BrowserViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // 冷启动：从其它应用「用翎打开链接 / 分享文本」进来
        handleExternalIntent(intent)

        setContent {
            val viewModel: BrowserViewModel = viewModel()
            val settings by viewModel.settings.collectAsStateWithLifecycle()

            // 夜间模式：跟随系统 / 强制开 / 强制关
            val systemDark = isSystemInDarkTheme()
            val darkTheme = when (settings.nightMode) {
                NightMode.FOLLOW_SYSTEM -> systemDark
                NightMode.ALWAYS_ON -> true
                NightMode.ALWAYS_OFF -> false
            }

            LingTheme(
                darkTheme = darkTheme,
                dynamicColor = settings.dynamicColor,
            ) {
                // 让状态栏图标颜色跟随主题
                val view = LocalContext.current
                DisposableEffect(darkTheme) {
                    val window = (view as? ComponentActivity)?.window
                    if (window != null) {
                        WindowCompat.getInsetsController(window, window.decorView)
                            .isAppearanceLightStatusBars = !darkTheme
                        WindowCompat.getInsetsController(window, window.decorView)
                            .isAppearanceLightNavigationBars = !darkTheme
                    }
                    onDispose { }
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    LingApp(viewModel = viewModel)
                }
            }
        }
    }

    /**
     * App 已在后台时又收到外部 Intent。
     *
     * manifest 里是 `launchMode="singleTask"`：系统会把 Intent 交给**已存在的**
     * 实例（走这里），而不是另起一个 —— 否则会出现两个「翎」、标签页还各自独立。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleExternalIntent(intent)
    }

    /**
     * 处理"从外部进来的 Intent"，与 manifest 里声明的两个 filter 一一对应：
     *   - `ACTION_VIEW` + http/https：其它应用「用浏览器打开」；
     *   - `ACTION_SEND` + text/plain：分享过来的文本，按搜索词处理。
     *
     * ⚠️ 这一段是**必须**有的：manifest 声明 intent-filter 只代表"系统愿意把
     * Intent 发给我们"，Activity 不读 `intent.data` 的话，用户看到的就是
     * "在别的应用选了用翎打开，翎是起来了，网页却没打开" —— 正是之前的症状。
     *
     * 冷启动（onCreate）与热启动（onNewIntent）走同一个入口：前者更早，
     * 那时 ViewModel 里可能还没有标签页，由 openExternalUrl 内部排队等 init 完成。
     */
    private fun handleExternalIntent(intent: Intent?) {
        val incoming = intent ?: return
        when (incoming.action) {
            Intent.ACTION_VIEW -> {
                val url = incoming.dataString?.trim().orEmpty()
                if (url.isNotEmpty()) browserViewModel.openExternalUrl(url)
            }
            Intent.ACTION_SEND -> if (incoming.type?.startsWith("text/") == true) {
                val text = incoming.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
                if (text.isNotEmpty()) browserViewModel.openSharedText(text)
            }
            else -> Unit
        }
    }

    /**
     * 在 [onStop] 保存会话，而不是 [onDestroy]。
     *
     * 原因：进程被系统回收时 onDestroy **不保证被调用**（这正是低内存
     * 杀后台的常见路径），而 onStop 一定会走到。用 onDestroy 会在
     * "切到别的 App 后被回收"这一最常见场景下丢掉全部标签。
     *
     * onStop 之后 Activity 可能很快销毁，因此 [BrowserViewModel.persistSession]
     * 内部用的是独立协程而不是 viewModelScope（后者会被一起取消）。
     */
    override fun onStop() {
        browserViewModel.persistSession()
        super.onStop()
    }
}

/**
 * 顶层导航宿主。
 *
 * 二级页面用一个显式的返回栈 [backStack] 承载：
 * 早期版本只有 `var route by remember`，系统返回键完全没有接入 ——
 * 结果在设置/书签/历史页按返回会直接退出应用，而不是回到浏览页。
 * 现在返回键先弹栈，栈空时才交给 [BrowserScreen] 处理（网页历史 → 退出确认）。
 */
@Composable
private fun LingApp(viewModel: BrowserViewModel) {
    val context = LocalContext.current
    // 返回栈：栈底始终是 Browser，只有二级页面才会入栈
    val backStack = remember { mutableStateListOf<Route>(Route.Browser) }
    // 当前栈顶路由。作为下面过渡状态机的触发键：它一变就重算谁当静态底、
    // 谁做水平位移。
    val stackRoute = backStack.last()

    // 出入栈方向，供过渡动画判断滑动朝向：
    // 前进（push）新页面自右滑入，返回（pop）当前页面整体向右滑出。
    // 不能只凭目标路由推断 —— 同一个路由既可能前进也可能返回
    // （设置 → 广告拦截规则 → 返回设置），只有 push/pop 知道真实方向。
    var navForward by remember { mutableStateOf(true) }

    // 统一的入栈/出栈
    val push: (Route) -> Unit = {
        navForward = true
        backStack.add(it)
    }
    val pop: () -> Unit = {
        if (backStack.size > 1) {
            navForward = false
            backStack.removeAt(backStack.lastIndex)
        }
    }

    // 返回键的归属，由下面两处**显式**划分，不依赖 BackHandler 的注册顺序：
    //
    //   - 有二级页面时：本处理器 enabled=true，接管返回，弹出栈顶；
    //     同时传给 BrowserScreen 的 canHandleBack=false，浏览页不参与竞争。
    //   - 只有浏览页时：本处理器 enabled=false 自动让位，返回交给浏览页
    //     （网页历史 → 退出确认）。
    //
    // 为什么不靠"后注册的优先"：那个顺序取决于组合树结构，一旦结构调整
    // （例如把 BrowserScreen 提到 when 之外）就会静默失效 ——
    // 而这正是"设置页按返回却直接退出应用"的成因。
    val hasOverlay = backStack.size > 1
    BackHandler(enabled = hasOverlay) { pop() }

    // 存储权限申请器（仅 Android 9 及以下选公共目录时用到）。
    // 结果回传给 ViewModel，由它决定继续下载还是提示用户。
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> viewModel.onStoragePermissionResult(granted) }

    val requestStoragePermission by viewModel.requestStoragePermission.collectAsStateWithLifecycle()
    LaunchedEffect(requestStoragePermission) {
        if (requestStoragePermission > 0) {
            storagePermissionLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    // 通知权限申请器（Android 13+，内建下载器展示进度通知时需要）。
    // 结果不影响下载本身，无需回传处理。
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* 授权与否都不改变下载流程，授权后通知会自动出现 */ }

    val requestNotificationPermission by viewModel.requestNotificationPermission.collectAsStateWithLifecycle()
    LaunchedEffect(requestNotificationPermission) {
        if (requestNotificationPermission > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
    val bookmarkFolders by viewModel.bookmarkFolders.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    // 一次性提示（书签增删、阅读模式失败原因、不支持的链接…）。
    // ⚠️ 这里以前是**缺的**：ViewModel 里所有 `_message.value = …` 都没有消费方，
    // 提示被静默丢弃，用户只看到"点了没反应"。修外部链接时要靠它给出反馈，
    // 顺手把这条通路补上（`consumeMessage()` 本来就为此准备着）。
    val message by viewModel.message.collectAsStateWithLifecycle()
    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        viewModel.consumeMessage()
    }

    // 从其它应用「用翎打开链接」进来时把界面拉回浏览页：
    // 用户可能正停在设置/书签页，链接虽然在后台标签里打开了，
    // 屏幕上却还是二级页面 —— 看起来依旧像"点了没反应"。
    val showBrowser by viewModel.showBrowser.collectAsStateWithLifecycle()
    LaunchedEffect(showBrowser) {
        if (showBrowser > 0) {
            // 回到浏览页属于"返回"：让上层页面按返回动画向右滑出。
            navForward = false
            backStack.clear()
            backStack.add(Route.Browser)
        }
    }

    // ── 二级页面过渡：显式双层栈 ──────────────────────────────────────────
    // 不用 AnimatedContent：它何时回收"退场"的旧内容不受我们控制，实测盖压
    // 动画进行到一半旧页就被卸载，左侧露出下层（曾出现"设置列表整块消失"）。
    // 改成自己管两层，行为完全可预期：
    //
    //   baseRoute  —— 静态底。整段过渡原地不动、保持不透明。
    //   slideRoute —— 上层。带水平位移：push 从右滑入（1→0），pop 向右滑出（0→1）。
    //
    // 过渡时长内两层**同时在组合里**，旧页因此必然自始至终在场，不依赖任何
    // 库内部的回收时机；动画结束时只留 baseRoute（即当前栈顶）。
    var baseRoute by remember { mutableStateOf<Route?>(null) }
    var slideRoute by remember { mutableStateOf<Route?>(null) }
    val slide = remember { Animatable(0f) }
    // 上一次的栈顶，用来判断 push/pop 时谁当静态底、谁做位移。
    var lastRoute by remember { mutableStateOf<Route?>(null) }

    LaunchedEffect(stackRoute) {
        val previous = lastRoute
        lastRoute = stackRoute
        if (previous == null || previous == stackRoute) return@LaunchedEffect

        if (navForward) {
            // 前进：旧栈顶沉作静态底，新页自右侧滑入盖压上来。
            baseRoute = if (previous == Route.Browser) null else previous
            slideRoute = stackRoute
            slide.snapTo(1f)
            slide.animateTo(0f, animationSpec = tween(300))
            // 新页已盖满全屏，这时才把旧页卸掉、新页转为静态底。
            slideRoute = null
            baseRoute = if (stackRoute == Route.Browser) null else stackRoute
        } else {
            // 返回：新栈顶（下层页）作静态底，旧栈顶整体向右滑出。
            baseRoute = if (stackRoute == Route.Browser) null else stackRoute
            slideRoute = previous
            slide.snapTo(0f)
            slide.animateTo(1f, animationSpec = tween(300))
            slideRoute = null
        }
    }

    // 所有上层界面（书签 / 历史 / 设置 / 嗅探…）都是**叠加层**而非独立页面：
    // BrowserScreen 必须一直挂载着，否则 when 一切换它就被卸载，
    // DisposableEffect 会 detachHost 把 WebView 摘下来 —— 上层背后就成了空白。
    //
    // 注：「标签页」与「更多」两个面板现在由 BrowserScreen 内部的
    // ModalBottomSheet 承载，不再经过这里的路由。
    Box(modifier = Modifier.fillMaxSize()) {
        // 底层：浏览页常驻。上层叠加时它不再处理返回键。
        BrowserScreen(
            viewModel = viewModel,
            onOpenBookmarks = { push(Route.Bookmarks) },
            onOpenHistory = { push(Route.History) },
            onOpenDownloads = { push(Route.Downloads) },
            onOpenSettings = { push(Route.Settings) },
            onOpenSniff = { push(Route.SniffedResources) },
            canHandleBack = !hasOverlay,
        )

        // 各二级页面的内容渲染。抽成局部 lambda，供下面"静态底"和"滑动上层"
        // 两个插槽复用 —— 过渡期间两页同时在组合里，各占一层，因此都能画出来。
        val renderRoute: @Composable (Route) -> Unit = { route ->
            when (route) {
                Route.Browser -> Unit

                Route.Bookmarks -> {
                    // 进书签页时补一次图标。放在这里而不是 setContent 里：
                    // 图标是进入这个页面才有意义的后台工作，没必要每次
                    // 重建组合都触发一遍。
                    LaunchedEffect(Unit) { viewModel.fetchMissingFavicons() }
                    BookmarksScreen(
                        bookmarks = bookmarks,
                        folders = bookmarkFolders,
                        onOpen = {
                            viewModel.navigate(it)
                            pop()
                        },
                        onRename = viewModel::renameBookmark,
                        onDelete = viewModel::deleteBookmark,
                        onMoveToFolder = viewModel::moveBookmarkToFolder,
                        onRenameFolder = viewModel::renameBookmarkFolder,
                        onDeleteFolder = viewModel::deleteBookmarkFolder,
                        onBack = pop,
                    )
                }

                Route.History -> HistoryScreen(
                    history = history,
                    onOpen = {
                        viewModel.navigate(it)
                        pop()
                    },
                    onDelete = viewModel::deleteHistory,
                    onClearAll = viewModel::clearHistory,
                    onBack = pop,
                )

                Route.Downloads -> DownloadsScreen(
                    downloads = downloads,
                    onRefresh = viewModel::refreshDownloads,
                    onCancel = viewModel::cancelDownload,
                    onPause = viewModel::pauseDownload,
                    onResume = viewModel::resumeDownload,
                    onRemove = { entry, deleteFile -> viewModel.removeDownload(entry, deleteFile) },
                    onClearAll = viewModel::clearDownloads,
                    onOpen = { entry -> openDownloadedFile(context, entry) },
                    onBack = pop,
                )

                // 设置首页只列分组，具体设置项在各自的子页里。
                Route.Settings -> SettingsScreen(
                    settings = settings,
                    onOpenGroup = { group ->
                        push(
                            when (group) {
                                SettingsGroup.SEARCH_HOME -> Route.SettingsSearchHome
                                SettingsGroup.APPEARANCE -> Route.SettingsAppearance
                                SettingsGroup.BROWSING -> Route.SettingsBrowsing
                                SettingsGroup.DOWNLOAD -> Route.SettingsDownload
                                SettingsGroup.PRIVACY -> Route.SettingsPrivacy
                                SettingsGroup.BACKUP -> Route.SettingsBackup
                                SettingsGroup.ABOUT -> Route.About
                            },
                        )
                    },
                    onBack = pop,
                )

                Route.SettingsSearchHome -> SearchHomeSettingsScreen(
                    settings = settings,
                    onSearchEngine = viewModel::setSearchEngine,
                    onHomepage = viewModel::setCustomHomepage,
                    onHomeBackground = viewModel::setHomeBackground,
                    onHomeBackgroundImage = viewModel::setHomeBackgroundImageUri,
                    onBack = pop,
                )

                Route.SettingsAppearance -> AppearanceSettingsScreen(
                    settings = settings,
                    onNightMode = viewModel::setNightMode,
                    onDynamicColor = viewModel::setDynamicColor,
                    onReaderFontSize = viewModel::setReaderFontSize,
                    onRestoreSession = viewModel::setRestoreSession,
                    onBack = pop,
                )

                Route.SettingsBrowsing -> {
                    val subs by viewModel.blockSubscriptions.collectAsStateWithLifecycle()
                    BrowsingSettingsScreen(
                        settings = settings,
                        onForceDark = viewModel::setForceDark,
                        onBlockImages = viewModel::setBlockImages,
                        onJavaScript = viewModel::setJavaScript,
                        onAdBlock = viewModel::setAdBlockEnabled,
                        onOpenAdBlockRules = { push(Route.AdBlockRules) },
                        blockSubscriptionCount = subs.size,
                        onOpenBlockSubscriptions = { push(Route.AdBlockSubscriptions) },
                        onOpenLinksExternal = viewModel::setOpenLinksInExternalApp,
                        onBack = pop,
                    )
                }

                Route.SettingsDownload -> DownloadSettingsScreen(
                    settings = settings,
                    onDownloaderType = viewModel::setDownloaderType,
                    onDownloadLocation = viewModel::setDownloadLocation,
                    onBack = pop,
                )

                Route.SettingsPrivacy -> PrivacySettingsScreen(
                    onClearData = viewModel::clearAllData,
                    onBack = pop,
                )

                Route.SettingsBackup -> BackupSettingsScreen(
                    onExport = viewModel::exportBookmarks,
                    onImport = viewModel::importBookmarks,
                    onBack = pop,
                )

                Route.About -> AboutScreen(onBack = pop)

                Route.AdBlockRules -> AdBlockRulesScreen(
                    rules = settings.adBlockRules,
                    onAdd = viewModel::addAdBlockRule,
                    onRemove = viewModel::removeAdBlockRule,
                    onBack = pop,
                )

                Route.AdBlockSubscriptions -> {
                    val subs by viewModel.blockSubscriptions.collectAsStateWithLifecycle()
                    AdBlockSubscriptionsScreen(
                        subscriptions = subs,
                        // 内置表条数是编译期常量（见 AdBlocker.BLOCKED_DOMAINS），
                        // 不必经 ViewModel 绕一圈。
                        builtinCount = AdBlocker.builtinCount,
                        onImportUrl = viewModel::importBlockSubscriptionFromUrl,
                        onImportLocal = viewModel::importBlockSubscriptionFromLocal,
                        onRefresh = viewModel::refreshBlockSubscription,
                        onToggle = viewModel::setBlockSubscriptionEnabled,
                        onRemove = viewModel::removeBlockSubscription,
                        onBack = pop,
                    )
                }

                Route.SniffedResources -> {
                    val sniff by viewModel.sniffResults.collectAsStateWithLifecycle()
                    SniffedResourcesScreen(
                        result = sniff,
                        // 打开资源后自动回浏览页：新标签页已就位，留在嗅探页
                        // 没有意义，用户还得手动按返回 —— 多一步操作。
                        onOpen = { url ->
                            viewModel.openInNewTab(url)
                            pop()
                        },
                        onDownload = viewModel::downloadResource,
                        onBack = pop,
                    )
                }
            }
        }

        // 静态底：整段过渡原地不动、保持不透明。
        // 只画当前需要的那一页 —— 过渡期间它是"旧页"，无过渡时它就是栈顶页。
        baseRoute?.let { route ->
            key(route) {
                Box(modifier = Modifier.fillMaxSize().zIndex(1f)) {
                    renderRoute(route)
                }
            }
        }

        // 滑动上层：translationX 由 slide 驱动 —— push 时 1→0（自右向左盖压上来），
        // pop 时 0→1（整体向右滑出）。全程不透明，不做任何交叉淡化。
        slideRoute?.let { route ->
            key(route) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(2f)
                        .graphicsLayer { translationX = slide.value * size.width },
                ) {
                    renderRoute(route)
                }
            }
        }
    }
}

private sealed interface Route {
    data object Browser : Route
    data object Bookmarks : Route
    data object History : Route
    data object Downloads : Route
    data object Settings : Route
    // 设置分组子页：每个分组一个二级页面
    data object SettingsSearchHome : Route
    data object SettingsAppearance : Route
    data object SettingsBrowsing : Route
    data object SettingsDownload : Route
    data object SettingsPrivacy : Route
    data object SettingsBackup : Route
    data object About : Route
    data object AdBlockRules : Route
    data object AdBlockSubscriptions : Route
    data object SniffedResources : Route
}

/**
 * 用系统「打开方式」打开已下载的文件。
 *
 * 必须走 FileProvider：Android 7 起直接传 file:// 给别的应用会抛
 * FileUriExposedException。这里用既有的 `${applicationId}.fileprovider`
 * （见 AndroidManifest），它已声明了 files-path/downloads 目录。
 *
 * 取不到能处理该类型的应用（没有 MIME 且系统也猜不出）时返回 false，
 * 由调用方决定是否提示。
 */
private fun openDownloadedFile(context: android.content.Context, entry: DownloadEntry): Boolean {
    val path = entry.localPath ?: return false
    val file = java.io.File(path)
    if (!file.exists()) return false

    return runCatching {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, entry.mimeType ?: "*/*")
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    }.getOrDefault(false)
}
