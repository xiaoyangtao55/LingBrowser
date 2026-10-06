package com.ling.browser

import android.app.Application
import com.ling.browser.data.AppContainer

/**
 * 「翎」浏览器 Application。
 *
 * 极简设计下这里只做两件事：
 *  1. 持有全局依赖容器 [AppContainer]（Room 数据库 + DataStore 设置）；
 *  2. 保证 WebView 在多进程场景下使用统一的数据目录。
 */
class LingApplication : Application() {

    /** 全局依赖容器，在 Ui 层通过 `LocalContext.current.applicationContext as LingApplication` 取用。 */
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        // 多进程场景下必须显式指定 WebView 数据目录后缀，否则 WebView 会崩溃。
        // 「翎」当前是单进程，但显式设置可避免后续加多进程时踩坑。
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            runCatching {
                android.webkit.WebView.setDataDirectorySuffix("ling")
            }
        }
    }
}
