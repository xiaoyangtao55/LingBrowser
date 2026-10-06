package com.ling.browser.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ling.browser.web.HomePage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "ling_settings")

/** 夜间模式策略。 */
enum class NightMode { FOLLOW_SYSTEM, ALWAYS_ON, ALWAYS_OFF }

/**
 * 标签页管理页的高度档位。
 *
 * Via 把标签页做成可上拉的面板，这里简化为三档固定高度：
 * 默认 [QUARTER]（只占屏幕下 1/4），既能扫到标签列表，
 * 又不会把正在浏览的页面完全盖住。
 */
enum class TabsHeight(val label: String, val fraction: Float) {
    FULL("全屏", 1.0f),
    HALF("一半", 0.5f),
    QUARTER("四分之一", 0.25f),
}

/** 搜索引擎。 */
enum class SearchEngine(val label: String, val queryUrl: String, val homepage: String) {
    BAIDU("百度", "https://www.baidu.com/s?wd=%s", "https://m.baidu.com"),
    BING("必应", "https://www.bing.com/search?q=%s", "https://cn.bing.com"),
    GOOGLE("Google", "https://www.google.com/search?q=%s", "https://www.google.com"),
    DUCKDUCKGO("DuckDuckGo", "https://duckduckgo.com/?q=%s", "https://duckduckgo.com"),
}

/** 桌面模式（电脑版）UA。 */
const val UA_MOBILE = ""

/**
 * 用户自定义桌面 UA —— 与 Via 一样，桌面模式使用 iOS Safari 的 UA，
 * 它比 Android Chrome 的 UA 更容易触发站点的桌面版布局。
 */
const val UA_DESKTOP =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 " +
        "(KHTML, like Gecko) Version/17.0 Safari/605.1.15"

data class LingSettings(
    val searchEngine: SearchEngine = SearchEngine.BAIDU,
    val customHomepage: String = "",
    val nightMode: NightMode = NightMode.FOLLOW_SYSTEM,
    val desktopMode: Boolean = false,
    val dynamicColor: Boolean = true,
    val incognito: Boolean = false,
    /** 网页夜间模式：对页面注入反色/暗化 CSS。 */
    val forceDarkWebPages: Boolean = false,
    /** 无图模式：省流。 */
    val blockImages: Boolean = false,
    /** 是否启用 JavaScript。 */
    val javaScriptEnabled: Boolean = true,
    /** 标签页管理页的高度档位，默认只占下 1/4。 */
    val tabsHeight: TabsHeight = TabsHeight.QUARTER,
) {
    /**
     * 用户实际要打开的主页。
     *
     * 留空则用「翎」的内置主页 —— 之前这里回退到搜索引擎首页，
     * 导致新建标签页直接打开百度，而不是应用自己的主页。
     */
    val homepage: String
        get() = customHomepage.ifBlank { HomePage.URL }

    val userAgent: String
        get() = if (desktopMode) UA_DESKTOP else UA_MOBILE
}

/**
 * 基于 DataStore 的设置存储。所有偏好以 Flow 暴露，UI 层 collect 即自动刷新。
 */
class SettingsStore(private val context: Context) {

    private object Keys {
        val SEARCH_ENGINE = stringPreferencesKey("search_engine")
        val CUSTOM_HOMEPAGE = stringPreferencesKey("custom_homepage")
        val NIGHT_MODE = stringPreferencesKey("night_mode")
        val DESKTOP_MODE = booleanPreferencesKey("desktop_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val FORCE_DARK = booleanPreferencesKey("force_dark_web")
        val BLOCK_IMAGES = booleanPreferencesKey("block_images")
        val JS_ENABLED = booleanPreferencesKey("js_enabled")
        val TABS_HEIGHT = stringPreferencesKey("tabs_height")
        val TAB_COUNT = intPreferencesKey("tab_count")
    }

    val settings: Flow<LingSettings> = context.dataStore.data.map { p ->
        LingSettings(
            searchEngine = p[Keys.SEARCH_ENGINE]
                ?.let { runCatching { SearchEngine.valueOf(it) }.getOrNull() }
                ?: SearchEngine.BAIDU,
            customHomepage = p[Keys.CUSTOM_HOMEPAGE] ?: "",
            nightMode = p[Keys.NIGHT_MODE]
                ?.let { runCatching { NightMode.valueOf(it) }.getOrNull() }
                ?: NightMode.FOLLOW_SYSTEM,
            desktopMode = p[Keys.DESKTOP_MODE] ?: false,
            dynamicColor = p[Keys.DYNAMIC_COLOR] ?: true,
            forceDarkWebPages = p[Keys.FORCE_DARK] ?: false,
            blockImages = p[Keys.BLOCK_IMAGES] ?: false,
            javaScriptEnabled = p[Keys.JS_ENABLED] ?: true,
            tabsHeight = p[Keys.TABS_HEIGHT]
                ?.let { runCatching { TabsHeight.valueOf(it) }.getOrNull() }
                ?: TabsHeight.QUARTER,
        )
    }

    suspend fun setSearchEngine(engine: SearchEngine) =
        context.dataStore.edit { it[Keys.SEARCH_ENGINE] = engine.name }

    suspend fun setCustomHomepage(url: String) =
        context.dataStore.edit { it[Keys.CUSTOM_HOMEPAGE] = url.trim() }

    suspend fun setNightMode(mode: NightMode) =
        context.dataStore.edit { it[Keys.NIGHT_MODE] = mode.name }

    suspend fun setDesktopMode(enabled: Boolean) =
        context.dataStore.edit { it[Keys.DESKTOP_MODE] = enabled }

    suspend fun setDynamicColor(enabled: Boolean) =
        context.dataStore.edit { it[Keys.DYNAMIC_COLOR] = enabled }

    suspend fun setForceDarkWebPages(enabled: Boolean) =
        context.dataStore.edit { it[Keys.FORCE_DARK] = enabled }

    suspend fun setBlockImages(enabled: Boolean) =
        context.dataStore.edit { it[Keys.BLOCK_IMAGES] = enabled }

    suspend fun setJavaScriptEnabled(enabled: Boolean) =
        context.dataStore.edit { it[Keys.JS_ENABLED] = enabled }

    suspend fun setTabsHeight(height: TabsHeight) =
        context.dataStore.edit { it[Keys.TABS_HEIGHT] = height.name }

    suspend fun setTabCount(count: Int) =
        context.dataStore.edit { it[Keys.TAB_COUNT] = count }
}
