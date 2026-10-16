package com.ling.browser.data

import android.content.Context
import com.ling.browser.data.db.LingDatabase
import com.ling.browser.data.prefs.LingSettings
import com.ling.browser.data.prefs.SettingsStore
import com.ling.browser.data.repo.BookmarkRepository
import com.ling.browser.data.repo.DownloadRepository
import com.ling.browser.data.repo.HistoryRepository
import com.ling.browser.data.repo.SessionRepository
import kotlinx.coroutines.flow.first
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

    val bookmarks: BookmarkRepository = BookmarkRepository(database)

    val history: HistoryRepository = HistoryRepository(database)

    val downloads: DownloadRepository = DownloadRepository(appContext, database)

    val session: SessionRepository = SessionRepository(database)
}
