package com.ling.browser.data

import android.content.Context
import com.ling.browser.data.db.LingDatabase
import com.ling.browser.data.prefs.SettingsStore
import com.ling.browser.data.repo.BookmarkRepository
import com.ling.browser.data.repo.DownloadRepository
import com.ling.browser.data.repo.HistoryRepository

/**
 * 极简手工依赖容器。
 *
 * 规模还不到引入 Hilt/Koin 的程度，一个 lateinit 容器就够了 —— 与「翎」轻量的取向一致。
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val database: LingDatabase = LingDatabase(appContext)

    val settings: SettingsStore = SettingsStore(appContext)

    val bookmarks: BookmarkRepository = BookmarkRepository(database)

    val history: HistoryRepository = HistoryRepository(database)

    val downloads: DownloadRepository = DownloadRepository(appContext, database)
}
