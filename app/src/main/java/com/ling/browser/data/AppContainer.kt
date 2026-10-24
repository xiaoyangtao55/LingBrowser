package com.ling.browser.data

import android.content.Context
import com.ling.browser.data.db.LingDatabase
import com.ling.browser.data.prefs.LingSettings
import com.ling.browser.data.prefs.SettingsStore
import com.ling.browser.data.repo.BlockListRepository
import com.ling.browser.data.repo.BookmarkRepository
import com.ling.browser.data.repo.DownloadRepository
import com.ling.browser.data.repo.HistoryRepository
import com.ling.browser.data.repo.SessionRepository
import com.ling.browser.data.repo.UserScriptRepository
import com.ling.browser.download.DownloadEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * 极简手工依赖容器。
 *
 * 规模还不到引入 Hilt/Koin 的程度，一个 lateinit 容器就够了 —— 与「翎」轻量的取向一致。
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val database: LingDatabase = LingDatabase(appContext)

    val settings: SettingsStore = SettingsStore(appContext)

    /**
     * 冷启动的**第一份**设置，同步读出来。
     *
     * 为什么要同步读：设置以 `Flow` 暴露，UI 层用 `stateIn(...)` 时必须给一个初值。
     * 若拿 [LingSettings] 的默认值当这个初值，用户设过的"强制深色 / 关闭动态取色"
     * 要等 DataStore 异步读完才生效 —— 屏幕上就是先按默认配色画一帧、再把整页
     * 翻成真实配色（冷启动的"跳色"，深色用户尤其明显）。这里在
     * `Application.onCreate` 阶段一次性读出来当初值，首帧即最终配色。
     *
     * 代价：主线程一次磁盘读（几十毫秒量级，且只此一次 —— 这份数据本来也会在
     * 首帧之后立刻被读到）。对"打开就该看到主页"的浏览器来说，用这点时间换
     * 首帧的确定性是划算的。
     *
     * 顺带修正了一处竞态：[com.ling.browser.ui.BrowserViewModel] 启动时要读
     * `settings.value.restoreSession` 决定是否恢复会话，此前那个值可能是默认值
     * 而非用户真实设置（取决于 DataStore 读完与否）。
     */
    val initialSettings: LingSettings = runBlocking { settings.settings.first() }

    /**
     * 当前设置的热缓存。
     *
     * 下载仓库在 `enqueue` 时需要同步知道「用哪个下载器、存到哪里」，
     * 不能等 Flow 异步发射。这里订阅设置流，把最新值存在 StateFlow 里，
     * 仓库通过 [settingsProvider] 直接读 `.value`，零等待。
     */
    private val latestSettings = MutableStateFlow(initialSettings)

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            settings.settings.collect { latestSettings.value = it }
        }
    }

    val bookmarks: BookmarkRepository = BookmarkRepository(database)

    val history: HistoryRepository = HistoryRepository(database)

    /** 内建下载引擎：真正执行下载、进度推送、断点续传。 */
    val downloadEngine: DownloadEngine = DownloadEngine(
        appContext,
        database,
        initialSettings.downloadNetwork,
    )

    init {
        // 网络限制变更即时下发：从「不限网络」收紧时立刻暂停越界任务，
        // 放宽时把此前自动暂停的任务续起来（不等下一次网络事件）。
        // 直接由设置流驱动而不用 latestSettings 快照，避免两次收集之间的时序竞争。
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            settings.settings
                .map { it.downloadNetwork }
                .distinctUntilChanged()
                .collect { downloadEngine.onNetworkPolicyChanged(it) }
        }
    }

    val downloads: DownloadRepository = DownloadRepository(
        appContext, database, downloadEngine,
        settingsProvider = { latestSettings.value },
    )

    val session: SessionRepository = SessionRepository(database)

    /**
     * 广告拦截的订阅名单。
     *
     * 域名表从"APK 内置"升级成"可订阅"之后，大名单（十几万条）由用户从网址
     * 或本地文件导入 —— 那些文件太大，塞不进 DataStore，因此落应用私有目录、
     * 由这个仓库自己管（见 [BlockListRepository]）。
     *
     * 构造即读回磁盘，`enabledDomains` 是启用中订阅的并集，供 WebView 拦截 lambda 查表。
     */
    val blockList: BlockListRepository = BlockListRepository(appContext)

    /**
     * 用户脚本的存储与启停。
     *
     * 与订阅名单同理：脚本源码整段落在应用私有目录（`files/userscripts`），
     * 不塞 DataStore。仓库构造即异步读回，`enabledScripts` 是启用中脚本的
     * 快照（含 GM 值初值），供 WebView 在 document-start / 页面就绪时注入。
     */
    val userScripts: UserScriptRepository = UserScriptRepository(appContext)
}
