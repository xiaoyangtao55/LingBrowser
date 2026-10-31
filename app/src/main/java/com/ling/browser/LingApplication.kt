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

    /**
     * WebView 实例池的内存压力回调（由 WebTabManager 注册，见 [onTrimMemory]）。
     *
     * 用可空 lambda 而不是直接持有 WebTabManager：Application 是进程级的，
     * 而 WebTabManager 的生命周期跟着 Activity。UI 层 attach/detach 时
     * 设置或清空这里，避免 Application 长期持有一个已销毁的 Activity 的引用。
     */
    var onTrimMemoryListener: ((level: Int) -> Unit)? = null

    /**
     * 系统内存压力回调。
     *
     * 这里只做转发，判定与动作都在 WebTabManager.updateCacheLimit 里 ——
     * 它知道实例表长什么样，Application 不该知道。
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        onTrimMemoryListener?.invoke(level)
    }

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
