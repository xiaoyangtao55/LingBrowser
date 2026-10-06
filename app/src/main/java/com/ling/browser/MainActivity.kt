package com.ling.browser

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ling.browser.data.db.DownloadEntry
import com.ling.browser.data.prefs.NightMode
import com.ling.browser.ui.BrowserViewModel
import com.ling.browser.ui.screens.BookmarksScreen
import com.ling.browser.ui.screens.BrowserScreen
import com.ling.browser.ui.screens.DownloadsScreen
import com.ling.browser.ui.screens.HistoryScreen
import com.ling.browser.ui.screens.SettingsScreen
import com.ling.browser.ui.screens.TabsScreen
import com.ling.browser.ui.theme.LingTheme

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
    val route = backStack.last()

    // 统一的入栈/出栈
    val push: (Route) -> Unit = { backStack.add(it) }
    val pop: () -> Unit = {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
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

    val tabs by viewModel.tabs.collectAsStateWithLifecycle()
    val activeId by viewModel.activeId.collectAsStateWithLifecycle()
    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
    val bookmarkFolders by viewModel.bookmarkFolders.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    // 标签页面板是**叠加层**而非独立页面：BrowserScreen 必须一直挂载着，
    // 否则 when 一切换它就被卸载，DisposableEffect 会 detachHost 把 WebView
    // 摘下来 —— 面板背后就成了空白，自然"透"不出网页。
    Box(modifier = Modifier.fillMaxSize()) {
        // 底层：浏览页常驻。叠加层打开时它不再处理返回键。
        BrowserScreen(
            viewModel = viewModel,
            onOpenTabs = { push(Route.Tabs) },
            onOpenBookmarks = { push(Route.Bookmarks) },
            onOpenHistory = { push(Route.History) },
            onOpenDownloads = { push(Route.Downloads) },
            onOpenSettings = { push(Route.Settings) },
            canHandleBack = !hasOverlay,
        )

        // 上层：二级页面。不带遮罩的页面（书签/历史/设置）自己是不透明的，
        // 会盖住底层；只有标签页面板故意留出透明区域。
        when (route) {
            Route.Browser -> Unit

            Route.Tabs -> TabsScreen(
                tabs = tabs,
                activeId = activeId,
                onSelect = {
                    viewModel.switchTab(it)
                    pop()
                },
                onClose = viewModel::closeTab,
                onNewTab = {
                    viewModel.newTab()
                    pop()
                },
                onNewIncognitoTab = {
                    viewModel.newIncognitoTab()
                    pop()
                },
                onCloseAll = { tabs.forEach { viewModel.closeTab(it.id) } },
                onMoveTab = viewModel::moveTab,
                onBack = pop,
                tabsHeight = settings.tabsHeight,
                onTabsHeightChange = viewModel::setTabsHeight,
            )

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
                onRemove = { entry, deleteFile -> viewModel.removeDownload(entry, deleteFile) },
                onClearAll = viewModel::clearDownloads,
                onOpen = { entry -> openDownloadedFile(context, entry) },
                onBack = pop,
            )

            Route.Settings -> SettingsScreen(
                settings = settings,
                onSearchEngine = viewModel::setSearchEngine,
                onHomepage = viewModel::setCustomHomepage,
                onNightMode = viewModel::setNightMode,
                onDesktopMode = viewModel::setDesktopMode,
                onDynamicColor = viewModel::setDynamicColor,
                onForceDark = viewModel::setForceDark,
                onBlockImages = viewModel::setBlockImages,
                onJavaScript = viewModel::setJavaScript,
                onTabsHeight = viewModel::setTabsHeight,
                onReaderFontSize = viewModel::setReaderFontSize,
                onRestoreSession = viewModel::setRestoreSession,
                onClearData = viewModel::clearAllData,
                onBack = pop,
            )
        }
    }
}

private sealed interface Route {
    data object Browser : Route
    data object Tabs : Route
    data object Bookmarks : Route
    data object History : Route
    data object Downloads : Route
    data object Settings : Route
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
