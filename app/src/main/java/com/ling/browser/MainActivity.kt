package com.ling.browser

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ling.browser.data.prefs.NightMode
import com.ling.browser.ui.BrowserViewModel
import com.ling.browser.ui.screens.BookmarksScreen
import com.ling.browser.ui.screens.BrowserScreen
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

    override fun onDestroy() {
        super.onDestroy()
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
    val history by viewModel.history.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    when (route) {
        Route.Browser -> BrowserScreen(
            viewModel = viewModel,
            onOpenTabs = { push(Route.Tabs) },
            onOpenBookmarks = { push(Route.Bookmarks) },
            onOpenHistory = { push(Route.History) },
            onOpenSettings = { push(Route.Settings) },
            // 只有栈里仅剩浏览页时才接管返回键
            canHandleBack = !hasOverlay,
        )

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
            onBack = pop,
            tabsHeight = settings.tabsHeight,
            onTabsHeightChange = viewModel::setTabsHeight,
        )

        Route.Bookmarks -> BookmarksScreen(
            bookmarks = bookmarks,
            onOpen = {
                viewModel.navigate(it)
                pop()
            },
            onDelete = viewModel::deleteBookmark,
            onBack = pop,
        )

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
            onClearData = viewModel::clearAllData,
            onBack = pop,
        )
    }
}

private sealed interface Route {
    data object Browser : Route
    data object Tabs : Route
    data object Bookmarks : Route
    data object History : Route
    data object Settings : Route
}
