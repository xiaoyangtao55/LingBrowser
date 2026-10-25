package com.ling.browser

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.pow
import com.ling.browser.data.db.DownloadEntry
import com.ling.browser.data.prefs.NightMode
import com.ling.browser.download.DownloadService
import com.ling.browser.download.installApkFromUri
import com.ling.browser.ui.BrowserViewModel
import com.ling.browser.ui.screens.AdBlockRulesScreen
import com.ling.browser.ui.screens.AdBlockSubscriptionsScreen
import com.ling.browser.ui.screens.BookmarksScreen
import com.ling.browser.ui.screens.SniffedResourcesScreen
import com.ling.browser.ui.screens.BrowserScreen
import com.ling.browser.ui.screens.DownloadsScreen
import com.ling.browser.ui.screens.HistoryScreen
import com.ling.browser.ui.screens.UserScriptsScreen
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
        // 冷启动：点了 GM 通知
        handleUserScriptNotificationIntent(intent)

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
                accentColor = settings.accentColor,
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
        handleUserScriptNotificationIntent(intent)
    }

    /**
     * `GM_notification` 的通知点击。
     *
     * 只做 JS 回调：页面已经被导航/标签已关闭时 [BrowserViewModel] 会静默丢弃。
     * 冷启动时标签还没恢复，回调同样会丢失 —— 这是"点通知只是把应用带到前台"
     * 的可接受退化，不值得为它把回调持久化。
     */
    private fun handleUserScriptNotificationIntent(intent: Intent?) {
        val incoming = intent ?: return
        val tabId = incoming.getStringExtra(BrowserViewModel.EXTRA_SCRIPT_TAB) ?: return
        val scriptId = incoming.getStringExtra(BrowserViewModel.EXTRA_SCRIPT_ID) ?: return
        val noticeId = incoming.getStringExtra(BrowserViewModel.EXTRA_SCRIPT_NOTICE) ?: return
        val url = incoming.getStringExtra(BrowserViewModel.EXTRA_SCRIPT_NOTICE_URL)
        // 消费掉，避免 Activity 因配置变化重建时把同一次点击再回放一遍。
        incoming.removeExtra(BrowserViewModel.EXTRA_SCRIPT_TAB)
        incoming.removeExtra(BrowserViewModel.EXTRA_SCRIPT_ID)
        incoming.removeExtra(BrowserViewModel.EXTRA_SCRIPT_NOTICE)
        incoming.removeExtra(BrowserViewModel.EXTRA_SCRIPT_NOTICE_URL)
        browserViewModel.onUserScriptNotificationClick(tabId, scriptId, noticeId, url)
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
        // 点「下载完成」通知进来：把任务 id 交给界面去打开文件。
        val openDownloadId = incoming.getLongExtra(DownloadService.EXTRA_OPEN_DOWNLOAD_ID, -1L)
        if (openDownloadId > 0) {
            browserViewModel.requestOpenDownload(openDownloadId)
            incoming.removeExtra(DownloadService.EXTRA_OPEN_DOWNLOAD_ID)
        }
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
        // 与存会话同一时机：把本次运行新增的拦截数并进已落盘的总数。
        // 拦截热路径上一次盘都不写，落盘只发生在这里。
        browserViewModel.flushBlockedCount()
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
    // 返回栈：栈底始终是 Browser，只有二级页面才会入栈。
    val backStack = remember { mutableStateListOf<Route>(Route.Browser) }
    val stackRoute = backStack.last()

    // 一次正在进行的过渡。null = 没有过渡，此时只渲染栈顶。
    // 有过渡时由它决定铺哪两层；**栈在动画开始前就已经改好**，
    // 所以 hasOverlay / canHandleBack 立刻反映真实层级，不必等动画收尾。
    var transition by remember { mutableStateOf<PageTransition?>(null) }
    // 单向进度：0 = 上层完全覆盖，1 = 上层完全移出右侧。进出场共用同一个值。
    val slide = remember { Animatable(0f) }
    // 滑动层完成布局的次数。新页面真正排过一次版它才会变 —— 动画要等它，
    // 否则重页面（下载列表之类）会从屏幕中间"冒"出来。
    var placedToken by remember { mutableIntStateOf(0) }
    // 是否正在过渡（含手势驱动）。返回手势据此让路。
    var animating by remember { mutableStateOf(false) }
    // 是否正被手指驱动。回前台时若仍为真，说明手势被系统打断了。
    var gestureActive by remember { mutableStateOf(false) }
    // 退场方向：+1 = 向右退（左边缘右滑，绝大多数机型），-1 = 向左退（右边缘左滑）。
    // 只在 `graphicsLayer` 里读，改它不会触发重组，只让那一层重绘。
    var swipeDirection by remember { mutableFloatStateOf(1f) }
    // 过渡串行化：连点两次「设置」时后一次排队，避免两段动画抢同一份状态。
    val navMutex = remember { Mutex() }
    // 每段过渡的序号。反向承接时，旧段的 `finally` 会晚于新段执行；
    // 只有"自己仍是最新一段"才允许清过渡状态，否则会把接手方铺好的场景抹掉。
    var navSeq by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    /** 进出场共用同一份弹簧，避免两条路径的时长/手感不一致。 */
    val slideSpec = remember {
        spring<Float>(
            stiffness = SPRING_STIFFNESS,
            dampingRatio = Spring.DampingRatioNoBouncy,
            // 默认 0.01 会让尾帧"啪"地吸到位
            visibilityThreshold = 0.0005f,
        )
    }

    /**
     * 播一段过渡。[commit] 在动画开始前改栈，返回过渡结束后停在栈顶的路由。
     *
     * 收尾（清过渡 + 栈已改好）都在**不挂起**的同一段里完成，同帧生效，
     * 因此不会出现"旧页已卸、新页未上"的空白帧。
     */
    fun runTransition(forward: Boolean, commit: () -> Route) {
        scope.launch {
            navMutex.withLock {
                // 上一段可能正被"反向承接"：它的锁已经放了、动画却还在跑。
                // 必须等它彻底停下再改场景，否则两段会抢同一个 slide。
                snapshotFlow { animating }.first { !it }
                val seq = ++navSeq
                val from = backStack.last()
                val to = commit()
                // 点击返回/进入固定向右，先复位方向，避免上一次右边缘手势的 -1 残留
                swipeDirection = 1f
                transition = PageTransition(
                    under = (if (forward) from else to).takeIf { it != Route.Browser },
                    over = if (forward) to else from,
                    forward = forward,
                )
                animating = true
                try {
                    slide.snapTo(if (forward) 1f else 0f)
                    // ★ 等首帧真实布局完成再起动画
                    val placed = placedToken
                    withTimeoutOrNull(WAIT_PLACED_MILLIS) {
                        snapshotFlow { placedToken }.first { it != placed }
                    }
                    // 等布局期间可能已经被"反向承接"（返回键/网页打开）。
                    // 此时驱动权归对方，本段不能再抢，否则会把折返动画打断成瞬移。
                    if (navSeq != seq) return@withLock
                    slide.animateTo(
                        targetValue = if (forward) 0f else 1f,
                        animationSpec = slideSpec,
                    )
                } catch (_: CancellationException) {
                    // 驱动权被反向承接那段抢走（Animatable 的互斥量会取消前一段）。
                    // 场景与目标都由它负责，这里不清任何状态；只有本协程自己被
                    // 销毁时 ensureActive 才会照常抛出。
                    currentCoroutineContext().ensureActive()
                } finally {
                    if (navSeq == seq) {
                        transition = null
                        animating = false
                    }
                }
            }
        }
    }

    /**
     * 反向承接：正在播「进入」时按返回，不重铺场景，让同一张卡片原路倒回去。
     *
     * 能这么做的原因：进入与返回的**场景完全相同**
     * （进入是 over = to / under = from，返回是 over = from / under = to），
     * 所以这里既不换层也不 snapTo —— 从当前进度接着往回弹，观感是"滑到一半折返"。
     * 新的 `animateTo` 会通过 Animatable 的互斥量接管驱动权，旧段自动被取消。
     */
    fun reverseExit() {
        // 抢在取消生效之前作废旧段的收尾权，否则旧段的 finally 会清掉这里铺的场景
        val seq = ++navSeq
        scope.launch {
            try {
                slide.animateTo(targetValue = 1f, animationSpec = slideSpec)
            } finally {
                if (navSeq == seq) {
                    transition = null
                    animating = false
                }
            }
        }
    }

    val push: (Route) -> Unit = { route ->
        runTransition(forward = true) {
            backStack.add(route)
            route
        }
    }
    val pop: () -> Unit = {
        if (backStack.size > 1) {
            val scene = transition
            // 反向承接：这段是「进入」、还在跑，且栈顶正是那张卡片 → 直接倒放。
            // 否则（连续返回、手势退场中、三层以上栈）照旧排队，交给 runTransition。
            if (animating && scene != null && scene.forward && backStack.last() == scene.over) {
                backStack.removeAt(backStack.lastIndex)
                reverseExit()
            } else {
                runTransition(forward = false) {
                    backStack.removeAt(backStack.lastIndex)
                    backStack.last()
                }
            }
        }
    }

    // 返回键的归属，仍由两处**显式**划分，不依赖处理器注册顺序：
    //
    //   - 栈里有二级页面时：这里 enabled，接管返回（并带系统预测性返回预览）；
    //     同时传给 BrowserScreen 的 canHandleBack=false，浏览页不参与竞争。
    //   - 只有浏览页时：enabled=false 自动让位，返回交给浏览页
    //     （网页历史 → 退出确认）。
    //
    // 为什么不靠"后注册的优先"：那个顺序取决于组合树结构，一旦结构调整
    // （例如把 BrowserScreen 提到 when 之外）就会静默失效 ——
    // 而这正是"设置页按返回却直接退出应用"的成因。
    val hasOverlay = backStack.size > 1
    PredictiveBackHandler(enabled = hasOverlay) { progress ->
        // 守卫 1：上一次过渡还没收尾 → 把整个手势吃掉，不碰正在动的场景。
        if (animating) {
            progress.collect()
            return@PredictiveBackHandler
        }
        val top = backStack.last()
        val under = backStack.getOrNull(backStack.lastIndex - 1)
        val seq = ++navSeq
        transition = PageTransition(
            under = under?.takeIf { it != Route.Browser },
            over = top,
            forward = false,
        )
        animating = true
        gestureActive = true
        slide.snapTo(0f)
        var committed = false
        // 收尾统一走 `animateTo(1f)`，不再区分"有没有收到进度事件"。
        //
        // ① 预测性返回：手势通常停在 0.9 左右，这里只是把最后一点补完
        //    （参考实现里 `onDisappear` 的 animateTo(0) 就是干这个），不会跳帧；
        // ② 非预测性返回：`PredictiveBackHandler` 只会把 channel 关闭、**一个进度事件
        //    都不发**（三键导航、ROM 把侧滑合成为返回键、系统没开预览）。此时这里就是
        //    整段退场动画 —— 早先无条件 `snapTo(1f)` 会让卡片瞬间跳到屏幕外，
        //    表现正是"返回没有动画"。
        var firstEvent = true
        try {
            // 跟手：手势进度直接 snapTo 到同一个 Animatable，不用 animateTo ——
            // 后者会和手指抢驱动权，拖到一半松手就跳变。
            progress.collect { event ->
                if (firstEvent) {
                    firstEvent = false
                    // 从右边缘往左滑时页面应该往左退，方向反了会像"被弹回去"
                    swipeDirection =
                        if (event.swipeEdge == BackEventCompat.EDGE_RIGHT) -1f else 1f
                }
                slide.snapTo(event.progress.coerceIn(0f, 1f))
            }
            committed = true
            // 一旦提交，返回就已经生效，退场必须走完。
            // ⚠️ 这里必须 NonCancellable：刚松手时立刻起第二段手势（或系统重发
            // onBackStarted），Compose 会 cancel 本段协程 —— 若退场动画被中途打断，
            // 就会落进下面的 catch 把页面**推回原位**，可栈已经弹了，视觉上先是
            // 「关于」滑回来、再闪成「设置」，看起来像多返回了一次。
            withContext(NonCancellable) {
                slide.animateTo(targetValue = 1f, animationSpec = slideSpec)
            }
        } catch (_: CancellationException) {
            // 守卫 2：只有**还没提交**的取消才是真的取消（第二根手指、切后台…），
            // 这时推回原状、不弹栈。已提交的取消不归这里管（见上）。
            if (!committed) {
                withContext(NonCancellable) {
                    slide.animateTo(targetValue = 0f, animationSpec = slideSpec)
                }
            }
        } finally {
            val isOwner = navSeq == seq
            if (isOwner) {
                transition = null
                animating = false
            }
            gestureActive = false
            // 只弹「本段动画出去的那一页」：
            //   - 自己已不是最新一段（被反向承接/新过渡接手）→ 弹栈的责任在接手方；
            //   - 栈顶已经不是 [top]（别处已经弹过）→ 再 removeAt 只会多弹一层。
            // 早先无条件 `removeAt(lastIndex)` 正是"关于→设置→关于→设置→主页"里
            // 多退一层、退错页面的来源。
            if (committed && isOwner && backStack.size > 1 && backStack.last() == top) {
                backStack.removeAt(backStack.lastIndex)
            }
            // 复位成默认方向：下一段过渡（push / 点击返回）必须向右
            swipeDirection = 1f
        }
    }

    // 守卫 3：手势进行中切后台，系统会停发动画帧，页面可能永久停在半屏。
    // 回到前台若"手指驱动"标记还在，说明手势已被打断，直接归位。
    val lifecycleOwner = context as? LifecycleOwner
    DisposableEffect(lifecycleOwner) {
        if (lifecycleOwner == null) return@DisposableEffect onDispose { }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && gestureActive) {
                scope.launch {
                    slide.snapTo(0f)
                    transition = null
                    gestureActive = false
                    animating = false
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

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
        if (showBrowser > 0 && backStack.size > 1) {
            // 回到浏览页属于"返回"：上层页面按返回动画整体向右滑出。
            runTransition(forward = false) {
                backStack.clear()
                backStack.add(Route.Browser)
                Route.Browser
            }
        }
    }

    // 点「下载完成」通知：回到 App 并直接打开那个文件。
    val pendingOpenDownload by viewModel.pendingOpenDownload.collectAsStateWithLifecycle()
    LaunchedEffect(pendingOpenDownload) {
        val taskId = pendingOpenDownload ?: return@LaunchedEffect
        // 先消费，避免重组或旋转后再打开一次。
        viewModel.consumeOpenDownload()
        // 冷启动时列表可能还没从库里读回来，短暂等它出现；等不到就按已删除处理。
        val entry = viewModel.downloads.value.firstOrNull { it.id == taskId }
            ?: withTimeoutOrNull(1500) {
                viewModel.downloads.first { list -> list.any { it.id == taskId } }
                    .first { it.id == taskId }
            }
        if (entry == null) {
            viewModel.showMessage("下载记录已不存在")
            return@LaunchedEffect
        }
        val error = try {
            openDownloadedFile(context, entry)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            "打开失败：" + (e.message ?: e.javaClass.simpleName)
        }
        error?.let(viewModel::showMessage)
    }

    // 网络限制拦截：引擎已把任务停在「已暂停」，这里问一句要不要为它放行。
    // 放在 LingApp 顶层而不是下载列表里 —— 下载可能是在浏览网页时触发的，
    // 用户当时根本不在下载页。
    val networkPrompts by viewModel.networkPrompts.collectAsStateWithLifecycle()
    networkPrompts.firstOrNull()?.let { blocked ->
        AlertDialog(
            onDismissRequest = { viewModel.keepDownloadPaused(blocked.id) },
            title = { Text("网络受限") },
            text = {
                Text(
                    "当前网络不满足「${settings.downloadNetwork.label}」的限制，" +
                        "下载已暂停。继续下载“${blocked.fileName}”可能产生流量费用，" +
                        "是否继续？",
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.allowDownloadOnCurrentNetwork(blocked.id) }) {
                    Text("继续下载")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.keepDownloadPaused(blocked.id) }) {
                    Text("保持暂停")
                }
            },
        )
    }

    // ── 二级页面过渡：显式双层 ────────────────────────────────────────────
    // 不用 AnimatedContent：它何时回收"退场"的旧内容不受我们控制，实测盖压
    // 动画进行到一半旧页就被卸载，左侧露出下层（曾出现"设置列表整块消失"）。
    // 改成自己管两层，行为完全可预期：过渡期间两层**同时在组合里**，
    // 旧页必然自始至终在场，不依赖任何库内部的回收时机。
    //
    // 下层整组（浏览页 + 静态底）吃同一份变换：两者都是全屏、同中心，
    // 分别施加相同缩放与位移，视觉上等价于"整组一起退后"。
    val underModifier = if (transition == null) {
        Modifier
    } else {
        Modifier.graphicsLayer {
            val enter = 1f - slide.value
            val scale = UNDER_MIN_SCALE + (1f - UNDER_MIN_SCALE) * slide.value
            scaleX = scale
            scaleY = scale
            // 方向跟随手势边缘：从右边缘往左滑时，下层要往右让
            translationX = -UNDER_SHIFT * enter * size.width * swipeDirection
        }
    }

    // 所有上层界面（书签 / 历史 / 设置 / 嗅探…）都是**叠加层**而非独立页面：
    // BrowserScreen 必须一直挂载着，否则 when 一切换它就被卸载，
    // DisposableEffect 会 detachHost 把 WebView 摘下来 —— 上层背后就成了空白。
    //
    // 注：「标签页」与「更多」两个面板现在由 BrowserScreen 内部的
    // ModalBottomSheet 承载，不再经过这里的路由。
    Box(modifier = Modifier.fillMaxSize()) {
        // 卡片背后的底色：下层缩小时露出的那条边用它，而不是直接露窗口背景
        // （那会在深浅主题切换时闪一下）。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.16f)),
        )

        // 底层：浏览页常驻。上层叠加时它不再处理返回键。
        //
        // ⚠️ 背景色必须画在 `underModifier` **之后**（即图层内部）：这样它跟着
        // 卡片一起缩放位移，只在卡片缩小后露出的那条边上才看得见下面的底色。
        // 若把它放在图层外，它会一直铺满全屏、把底色整个盖掉，过渡就没有层次了。
        // 同时这块不透明背景也保证地址栏的 60% 半透明底色始终叠在同一层颜色上
        // —— 否则它会直接叠在 scrim 上，静止时就无端发灰。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(underModifier)
                .background(MaterialTheme.colorScheme.background),
        ) {
            BrowserScreen(
                viewModel = viewModel,
                onOpenBookmarks = { push(Route.Bookmarks) },
                onOpenHistory = { push(Route.History) },
                onOpenDownloads = { push(Route.Downloads) },
                onOpenSettings = { push(Route.Settings) },
                onOpenSniff = { push(Route.SniffedResources) },
                canHandleBack = !hasOverlay,
            )
        }

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
                    onOpen = { entry ->
                        // 打开失败时明确说一声，否则用户只觉得"点了没反应"。
                        // 打开前有一步"等文件真正可读"，所以放协程里做。
                        scope.launch {
                            val error = try {
                                openDownloadedFile(context, entry)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                // 任何意料之外的异常都要变成可见提示，不能静默吞掉。
                                "打开失败：" + (e.message ?: e.javaClass.simpleName)
                            }
                            error?.let(viewModel::showMessage)
                        }
                    },
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
                    onSelectCustomEngine = viewModel::selectCustomSearchEngine,
                    onAddSearchEngine = viewModel::addSearchEngine,
                    onRemoveSearchEngine = viewModel::removeSearchEngine,
                    onHomepage = viewModel::setCustomHomepage,
                    onHomeBackground = viewModel::setHomeBackground,
                    onHomeBackgroundImage = viewModel::setHomeBackgroundImageUri,
                    onBack = pop,
                )

                Route.SettingsAppearance -> AppearanceSettingsScreen(
                    settings = settings,
                    onNightMode = viewModel::setNightMode,
                    onDynamicColor = viewModel::setDynamicColor,
                    onAccentColor = viewModel::setAccentColor,
                    onReaderFontSize = viewModel::setReaderFontSize,
                    onRestoreSession = viewModel::setRestoreSession,
                    onBack = pop,
                )

                Route.SettingsBrowsing -> {
                    val subs by viewModel.blockSubscriptions.collectAsStateWithLifecycle()
                    val blocked by viewModel.blockedCount.collectAsStateWithLifecycle()
                    val scripts by viewModel.userScripts.collectAsStateWithLifecycle()
                    BrowsingSettingsScreen(
                        settings = settings,
                        onForceDark = viewModel::setForceDark,
                        onBlockImages = viewModel::setBlockImages,
                        onJavaScript = viewModel::setJavaScript,
                        onAdBlock = viewModel::setAdBlockEnabled,
                        onOpenAdBlockRules = { push(Route.AdBlockRules) },
                        blockSubscriptionCount = subs.size,
                        onOpenBlockSubscriptions = { push(Route.AdBlockSubscriptions) },
                        blockedCount = blocked,
                        onResetBlockedCount = viewModel::resetBlockedCount,
                        userScriptCount = scripts.size,
                        onOpenUserScripts = { push(Route.UserScripts) },
                        onOpenLinksExternal = viewModel::setOpenLinksInExternalApp,
                        onCustomUserAgent = viewModel::setCustomUserAgent,
                        onBack = pop,
                    )
                }

                Route.SettingsDownload -> DownloadSettingsScreen(
                    settings = settings,
                    onDownloaderType = viewModel::setDownloaderType,
                    onDownloadLocation = viewModel::setDownloadLocation,
                    onDownloadNetwork = viewModel::setDownloadNetwork,
                    onBack = pop,
                )

                Route.SettingsPrivacy -> PrivacySettingsScreen(
                    onClearData = viewModel::clearBrowsingData,
                    onBack = pop,
                )

                Route.SettingsBackup -> BackupSettingsScreen(
                    onExport = viewModel::exportBookmarks,
                    onExportHistory = viewModel::exportHistory,
                    onImport = viewModel::importBookmarks,
                    onImportHistory = viewModel::importHistory,
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

                Route.UserScripts -> {
                    val scripts by viewModel.userScripts.collectAsStateWithLifecycle()
                    UserScriptsScreen(
                        scripts = scripts,
                        onImportUrl = viewModel::importUserScriptFromUrl,
                        onImportLocal = viewModel::importUserScriptFromLocal,
                        onRefresh = viewModel::refreshUserScript,
                        onToggle = viewModel::setUserScriptEnabled,
                        onRemove = viewModel::removeUserScript,
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

        // 没有过渡时只铺栈顶；过渡期间铺 transition 指定的两层。
        // 两者都吃 underModifier，因此下层的缩放/位移始终一致。
        val pageTransition = transition
        val underRoute = if (pageTransition != null) {
            pageTransition.under
        } else {
            stackRoute.takeIf { it != Route.Browser }
        }
        val overRoute = pageTransition?.over

        // 静态底：无过渡时它就是栈顶页；过渡期间它是"下页"。
        underRoute?.let { route ->
            key(route) {
                Box(modifier = Modifier.fillMaxSize().zIndex(1f).then(underModifier)) {
                    renderRoute(route)
                }
            }
        }

        // 滑动上层：卡片。translationX / 缩放 / 圆角 / 阴影都从同一个 slide 派生，
        // 且只在 graphicsLayer 里读 —— 每帧不触发任何重组。
        overRoute?.let { route ->
            key(route) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(2f)
                        // 新页面排过一次版才放行动画（见 runTransition）
                        .onGloballyPositioned { placedToken++ }
                        .graphicsLayer {
                            val enter = 1f - slide.value
                            translationX = slide.value * size.width * swipeDirection
                            val scale = CARD_MIN_SCALE + (1f - CARD_MIN_SCALE) * enter
                            scaleX = scale
                            scaleY = scale
                            // 圆角用 8 次曲线（参考实现）：进场时这张卡片一直保持圆角，
                            // 直到快要贴合才迅速收平；退场时则一开始就重新长出圆角。
                            // 线性插值会让页面"刚动就变卡片"，显廉价。
                            shape = RoundedCornerShape(
                                CARD_RADIUS.toPx() * (1f - enter.pow(8)),
                            )
                            clip = true
                            shadowElevation = CARD_SHADOW.toPx() * slide.value
                        },
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
    data object UserScripts : Route
    data object SniffedResources : Route
}

/**
 * 一次正在进行的二级页面过渡。
 *
 * [under] 是压在下面的一层（`null` 表示浏览页 —— 它常驻，不需要额外渲染）；
 * [over] 是从右侧滑动的卡片。栈在过渡开始时就已经改好，这个对象只描述"画什么"。
 */
private data class PageTransition(
    val under: Route?,
    val over: Route,
    /**
     * true = 上层正滑入（进入）；false = 滑出（返回）。
     * 反向承接靠它判断：只有"进入"才允许被返回直接倒放。
     */
    val forward: Boolean,
)

/** 过渡弹簧刚度。越大越快：420 大约 0.28s 走完主要行程，300 会明显偏"拖"。 */
private const val SPRING_STIFFNESS = 420f

/** 等滑动层首帧布局的上限：正常一帧就够，这里只是防止永远等下去。 */
private const val WAIT_PLACED_MILLIS = 120L

/** 卡片进场起始缩放（0.92 → 1）。 */
private const val CARD_MIN_SCALE = 0.92f

/** 卡片起始圆角，随进度收拢到 0。 */
private val CARD_RADIUS = 20.dp

/** 卡片阴影高度，随进度收拢到 0。 */
private val CARD_SHADOW = 20.dp

/** 下层整组的最小缩放（1 → 0.90）。太浅（0.96）时几乎看不出"退后一层"。 */
private const val UNDER_MIN_SCALE = 0.90f

/** 下层整组左移比例。与缩放一起构成"退后一层"的观感。 */
private const val UNDER_SHIFT = 0.06f

/**
 * 用系统「打开方式」打开已下载的文件。
 *
 * 必须走 FileProvider：Android 7 起直接传 file:// 给别的应用会抛
 * FileUriExposedException。这里用既有的 `${applicationId}.fileprovider`
 * （见 AndroidManifest），它已声明了 files-path/downloads、external-files-path
 * 与公共 Download 目录。
 *
 * 三条路径都要覆盖，缺一条就会表现为"点了没反应"：
 *  1. `content://` —— 公共下载目录（Android 10+ 走 MediaStore），**不能**再交给
 *     FileProvider（它只收文件路径），原样递给系统；
 *  2. 文件路径 —— 应用私有目录 / 旧版公共目录，走 FileProvider；
 *  3. 记录里没有路径或路径已失效 —— 按文件名在几个下载目录与 MediaStore 里找回。
 *     早期版本写库时没存 localPath，这类老记录只能靠这一步。
 *
 * 返回 null 表示已成功拉起；否则返回给用户看的失败原因。
 */
private suspend fun openDownloadedFile(
    context: android.content.Context,
    entry: DownloadEntry,
): String? {
    val uri = resolveOpenUri(context, entry).getOrElse {
        return it.message ?: "无法打开这个文件"
    }

    val mime = mimeTypeFor(context, entry, uri)
    // SUCCESS 落库与文件真正可读之间有很窄的窗口：MediaStore 条目刚清掉
    // IS_PENDING、或旧版公共目录刚 close 时，目标应用可能读到 0 字节并报
    // "文件损坏"。这里只在这个窗口内等（见 [awaitNonEmpty]），正常条目
    // 探测一次就返回，不做无条件 sleep。
    awaitNonEmpty(context, uri, entry.downloadedBytes.takeIf { it > 0 } ?: entry.totalBytes)

    // APK 单独走一条路：用 PackageInstaller 会话直接交给系统安装器。
    //
    // 不走 ACTION_VIEW 的 apk MIME：各家 ROM 对 `application/vnd.android.
    // package-archive` 的注册不一致，部分机型根本没有应用认领它 —— 结果
    // 要么选择框里全是别的应用（没有安装器），要么点下去毫无反应。
    // 会话方式由系统自己弹安装界面，不依赖 MIME 匹配、也不依赖 URI 授权。
    if (mime == APK_MIME && installApkFromUri(context, uri)) return null

    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        grantRead(uri, entry.fileName)
    }

    return try {
        context.startActivity(intent)
        null
    } catch (e: android.content.ActivityNotFoundException) {
        // 系统里没有任何应用认领这个类型（常见于 .bin / 冷门后缀）。
        // 退一步弹分享面板：比"点了没反应"或一句 toast 更有用，
        // 也绕开了部分 ROM 上 ACTION_VIEW 授权不生效的问题。
        if (shareChooser(context, uri, mime, entry.fileName)) null
        else "没有可以打开这个文件的应用"
    } catch (e: SecurityException) {
        "没有权限打开这个文件"
    }
}

/**
 * 给 Intent 挂上读权限。
 *
 * 只加 FLAG 在部分定制系统（老 MIUI 等）上会被目标应用忽略，把 URI 同时放进
 * [android.content.ClipData] 才稳定 —— 系统会连同 clip 里的 URI 一起授权。
 */
private fun android.content.Intent.grantRead(
    uri: android.net.Uri,
    label: String,
) {
    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    clipData = android.content.ClipData.newRawUri(label, uri)
}

/** 兜底：弹系统分享面板，让用户挑一个能处理该文件的应用。 */
private fun shareChooser(
    context: android.content.Context,
    uri: android.net.Uri,
    mime: String,
    label: String,
): Boolean {
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = mime
        putExtra(android.content.Intent.EXTRA_STREAM, uri)
        grantRead(uri, label)
    }
    val chooser = android.content.Intent.createChooser(send, "打开方式").apply {
        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return runCatching { context.startActivity(chooser) }.isSuccess
}

/**
 * 等到文件确实有内容再发 Intent（只处理"刚完成、还是 0 字节"的窄窗口）。
 *
 * 记录里的期望字节数未知时直接跳过 —— 否则真正的 0 字节文件（比如空日志）
 * 每次打开都会白等一轮。
 */
private suspend fun awaitNonEmpty(
    context: android.content.Context,
    uri: android.net.Uri,
    expectedBytes: Long,
) {
    if (expectedBytes <= 0) return
    if ((sizeOf(context, uri) ?: return) > 0) return
    repeat(READY_POLL_COUNT) {
        kotlinx.coroutines.delay(READY_POLL_MILLIS)
        if ((sizeOf(context, uri) ?: return) > 0) return
    }
}

/** 文件当前长度；拿不到（权限、provider 不支持）时返回 null。 */
private fun sizeOf(context: android.content.Context, uri: android.net.Uri): Long? =
    when (uri.scheme) {
        "file" -> uri.path?.let { path ->
            java.io.File(path).takeIf { it.exists() }?.length()
        }
        "content" -> runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
        }.getOrNull()?.takeIf { it >= 0 }
        else -> null
    }

/**
 * 找出该下载条目对应的可分享 URI。
 *
 * 先用记录里的 [DownloadEntry.localPath]；它为空或指向的文件已经不存在时，
 * 依次到应用私有下载目录、旧版公共下载目录、MediaStore 里按文件名找。
 * 全部落空才判定"文件已不存在"。
 */
private fun resolveOpenUri(
    context: android.content.Context,
    entry: DownloadEntry,
): Result<android.net.Uri> {
    val recorded = entry.localPath
    if (!recorded.isNullOrBlank()) {
        if (recorded.startsWith("content://")) {
            return Result.success(android.net.Uri.parse(recorded))
        }
        val file = java.io.File(recorded)
        if (file.exists()) return shareUri(context, file)
    }

    val name = entry.fileName.takeIf { it.isNotBlank() }
    if (name != null) {
        downloadDirs(context).firstNotNullOfOrNull { dir ->
            java.io.File(dir, name).takeIf { it.exists() }
        }?.let { return shareUri(context, it) }

        mediaStoreDownloadUri(context, name)?.let { return Result.success(it) }
    }

    // 提示里带上文件名：同一个页面里点错行时，用户一眼能看出点的是哪一条
    return Result.failure(
        IllegalStateException("文件已不存在，可能被删除了" + if (name != null) "（$name）" else ""),
    )
}

/** 文件路径 → FileProvider 的 content URI；不在已声明的目录里时给出原因。 */
private fun shareUri(
    context: android.content.Context,
    file: java.io.File,
): Result<android.net.Uri> = runCatching {
    androidx.core.content.FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
    )
}.recoverCatching {
    throw IllegalStateException("这个文件所在的位置无法共享给其它应用")
}

/** 文件可能落地的几个目录，顺序即查找优先级。 */
private fun downloadDirs(context: android.content.Context): List<java.io.File> = listOfNotNull(
    // 内建下载器默认落点：/Android/data/<pkg>/files/Download
    context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS),
    // 外部存储不可用时的兜底落点（见 DownloadDestination.resolvePrivate）
    java.io.File(context.filesDir, "downloads"),
    // Android 9 及以下的公共下载目录
    android.os.Environment.getExternalStoragePublicDirectory(
        android.os.Environment.DIRECTORY_DOWNLOADS,
    ),
)

/**
 * 在 MediaStore 的公共下载集合里按文件名找条目（Android 10+）。
 *
 * 这个兜底专治"文件明明在公共下载目录、记录里却没存路径"——那类条目既不能
 * 用 `File.exists()` 判断（分区存储下路径不可见），也不能走 FileProvider。
 */
private fun mediaStoreDownloadUri(
    context: android.content.Context,
    fileName: String,
): android.net.Uri? {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return null
    // 同名文件可能有多条记录（重新下载、别的应用也下过同一个名字），
    // 按 DATE_ADDED 倒序取最新的一条，否则会打开一个旧文件。
    // 逐个卷查：外置 SD 卡上的下载不在 VOLUME_EXTERNAL_PRIMARY 里。
    val volumes = runCatching {
        android.provider.MediaStore.getExternalVolumeNames(context).toList()
    }.getOrNull().orEmpty().ifEmpty {
        listOf(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
    }
    for (volume in volumes) {
        val collection = android.provider.MediaStore.Downloads.getContentUri(volume)
        val uri = queryLatestDownload(context, collection, fileName)
        if (uri != null) return uri
    }
    return null
}

private fun queryLatestDownload(
    context: android.content.Context,
    collection: android.net.Uri,
    fileName: String,
): android.net.Uri? = runCatching {
    context.contentResolver.query(
        collection,
        arrayOf(android.provider.MediaStore.Downloads._ID),
        "${android.provider.MediaStore.Downloads.DISPLAY_NAME} = ?",
        arrayOf(fileName),
        "${android.provider.MediaStore.Downloads.DATE_ADDED} DESC",
    )?.use { c ->
        if (c.moveToFirst()) android.net.Uri.withAppendedPath(collection, c.getLong(0).toString())
        else null
    }
}.getOrNull()

/**
 * 推断打开文件时该用的 MIME。
 *
 * 优先级：
 *  1. **可信后缀**（[MIME_BY_EXTENSION]）—— APK 必须压过记录值：服务器常把
 *     安装包标成 `application/octet-stream` 或干脆标错，而 `MimeTypeMap` 在
 *     部分 ROM 上查不到 `apk`，两条路都会退成全通配 MIME，结果选择框里
 *     全是认领通配的应用、安装器反而缺席；
 *  2. 下载时记录的类型（去掉 `; charset=` 之类参数，否则 `setType` 会拿到非法值）；
 *  3. `content://` 问 ContentResolver；
 *  4. 按后缀查 `MimeTypeMap`；
 *  5. 都推不出才用全通配。
 *
 * `application/octet-stream` 等于"不知道"，不能直接拿去 ACTION_VIEW ——
 * 几乎没有应用认领这个类型，表现就是点了没反应。
 */
private fun mimeTypeFor(
    context: android.content.Context,
    entry: DownloadEntry,
    uri: android.net.Uri,
): String {
    val ext = entry.fileName.substringAfterLast('.', "").lowercase()
    MIME_BY_EXTENSION[ext]?.let { return it }

    val recorded = entry.mimeType
        ?.substringBefore(';')
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it !in GENERIC_MIME_TYPES }
    if (recorded != null) return recorded

    if (uri.scheme == "content") {
        runCatching { context.contentResolver.getType(uri) }
            .getOrNull()
            ?.substringBefore(';')
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it !in GENERIC_MIME_TYPES }
            ?.let { return it }
    }

    // 后缀从文件名取，不能从 URI 取：content:// 的最后一段是数字 ID
    if (ext.isNotEmpty()) {
        android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(ext)
            ?.let { return it }
    }
    return "*/*"
}

/** 打开安装包时使用的标准 MIME。 */
private const val APK_MIME = "application/vnd.android.package-archive"

/**
 * 扩展名 → 权威 MIME。
 *
 * 只收"记录值不可信、且系统查表也可能失败"的几个：`apk` 是必须精确的，
 * `apks` / `xapk` 本质是 zip（交给安装器只会报"解析失败"），按压缩包处理。
 */
private val MIME_BY_EXTENSION = mapOf(
    "apk" to APK_MIME,
    "apks" to "application/zip",
    "xapk" to "application/zip",
)

/** 这些类型等于"不知道真实类型"，不能拿去匹配应用。 */
private val GENERIC_MIME_TYPES = setOf(
    "*/*",
    "application/octet-stream",
    "binary/octet-stream",
    "application/x-download",
    "application/unknown",
)

/** 等待文件可读的轮询次数与间隔（合计约 500ms）。 */
private const val READY_POLL_COUNT = 20
private const val READY_POLL_MILLIS = 25L
