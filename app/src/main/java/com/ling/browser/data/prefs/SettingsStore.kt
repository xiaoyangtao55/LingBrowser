package com.ling.browser.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
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
 * 只有两档。原先还有个 1/4 档，但配合半透明面板后，1/4 的高度连两三个
 * 标签都显示不全，列表一滚动就失去"总览"的意义 —— 面板太矮时反而不如
 * 直接看网页，所以去掉了。
 *
 * 默认 [HALF]：既能扫到若干个标签，又能透出后面的网页，
 * 符合"标签页是临时面板而非独立页面"的直觉。
 */
enum class TabsHeight(val label: String, val fraction: Float) {
    FULL("全屏", 1.0f),
    HALF("一半", 0.5f),
}

/** 搜索引擎。 */
enum class SearchEngine(val label: String, val queryUrl: String, val homepage: String) {
    BAIDU("百度", "https://www.baidu.com/s?wd=%s", "https://m.baidu.com"),
    BING("必应", "https://www.bing.com/search?q=%s", "https://cn.bing.com"),
    GOOGLE("Google", "https://www.google.com/search?q=%s", "https://www.google.com"),
    DUCKDUCKGO("DuckDuckGo", "https://duckduckgo.com/?q=%s", "https://duckduckgo.com"),
}

/**
 * 阅读模式的字号档位。
 *
 * 直接复用 [com.ling.browser.web.ReaderPage.FontSize]，不再另立一份定义 ——
 * 两处各存一份迟早会不同步（这边加了档位、那边没加），
 * 表现为"设置里选了新的，阅读视图却没变化"，很难查。
 */
typealias ReaderFontSize = com.ling.browser.web.ReaderPage.FontSize

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
    /**
     * 默认搜索引擎。
     *
     * 选必应而不是百度：必应对「翎」这类轻量浏览器更友好 ——
     * 无强制登录、无广告跳转中间页，且 https 首页体积小、首屏快。
     */
    val searchEngine: SearchEngine = SearchEngine.BING,
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
    /** 标签页管理页的高度档位，默认占下半屏。 */
    val tabsHeight: TabsHeight = TabsHeight.HALF,
    /**
     * 启动时是否恢复上次的标签页。
     *
     * 默认**开**：用户开着几个网页切走再回来，期望看到原样，
     * 这是浏览器的主流行为。关掉后每次启动都是一个干净的主页。
     *
     * 注意：无痕标签无论此项如何都**不会**被保存或恢复，
     * 见 [com.ling.browser.data.db.TabSnapshot.isPersistable]。
     */
    val restoreSession: Boolean = true,
    /**
     * 是否把非 http(s) 链接交给外部 App 打开。
     *
     * 默认**关**。关掉时这类导航被静默吃掉（什么都不发生），
     * 打开时才尝试 `ACTION_VIEW` 拉起系统应用。
     *
     * 为什么默认关：App 内嵌页（知乎、微博等）会用自定义协议做
     * **内部跳转**，而本机往往没装对应 App。此时"交给系统"只会失败，
     * 失败后又容易连带把 WebView 的导航状态搞乱（真机日志里表现为
     * 渲染进程被拆掉重建、页面卡在中间态）。默认关掉就等于绕开
     * 整条转发路径 —— 对绝大多数国内站点来说，这些链接本来也不是
     * 用户想点的。
     *
     * 需要跳到外部 App 的用户可以在设置里打开，行为与主流浏览器一致。
     */
    val openLinksInExternalApp: Boolean = false,
    /**
     * 是否启用广告拦截（域名级）。
     *
     * 默认**开**：这是浏览器的基础能力，多数用户期望默认就拦广告。
     * 关掉后全部放行，供"某站点因误拦而异常"时临时排查用。
     *
     * 目前只做域名级拦截（拦广告/跟踪域名与路径），不做元素级
     * （EasyList + 隐藏选择器）—— 见 [com.ling.browser.web.AdBlocker]
     * 关于体积与更新成本的取舍。
     */
    val adBlockEnabled: Boolean = true,
    /**
     * 用户自定义的广告拦截域名规则（裸域名，如 `ad.example.com`）。
     *
     * 与内置黑名单同等语义：命中即拦截，且任意深度子域名被兜住。
     * 空集合表示只用内置规则。
     *
     * 存 `Set<String>` 而非逗号分隔字符串：规则本质是无序集合，
     * 用集合能天然去重、避免"同一规则出现两次"；DataStore 的
     * stringSetPreferencesKey 直接支持。
     */
    val adBlockRules: Set<String> = emptySet(),
    /**
     * 阅读模式的字号档位。
     *
     * 存的是档位而不是像素值：字号需要在**阅读视图里**改，而那边是
     * WebView（HTML），需要重新生成页面。存档位可以让"恢复默认"
     * 有个明确目标，也避免用户把字号调到一个荒谬的值后无法回到常规。
     */
    val readerFontSize: ReaderFontSize = ReaderFontSize.MEDIUM,
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
        val RESTORE_SESSION = booleanPreferencesKey("restore_session")
        val READER_FONT_SIZE = stringPreferencesKey("reader_font_size")
        val OPEN_LINKS_EXTERNAL = booleanPreferencesKey("open_links_external")
        val AD_BLOCK = booleanPreferencesKey("ad_block")
        val AD_BLOCK_RULES = stringSetPreferencesKey("ad_block_rules")
    }

    val settings: Flow<LingSettings> = context.dataStore.data.map { p ->
        LingSettings(
            searchEngine = p[Keys.SEARCH_ENGINE]
                ?.let { runCatching { SearchEngine.valueOf(it) }.getOrNull() }
                ?: SearchEngine.BING,
            customHomepage = p[Keys.CUSTOM_HOMEPAGE] ?: "",
            nightMode = p[Keys.NIGHT_MODE]
                ?.let { runCatching { NightMode.valueOf(it) }.getOrNull() }
                ?: NightMode.FOLLOW_SYSTEM,
            desktopMode = p[Keys.DESKTOP_MODE] ?: false,
            dynamicColor = p[Keys.DYNAMIC_COLOR] ?: true,
            forceDarkWebPages = p[Keys.FORCE_DARK] ?: false,
            blockImages = p[Keys.BLOCK_IMAGES] ?: false,
            javaScriptEnabled = p[Keys.JS_ENABLED] ?: true,
            // valueOf 用 runCatching 兜底：老版本存过 "QUARTER"，
            // 该档位已移除，valueOf 会抛 IllegalArgumentException，
            // 这里捕获后回退到默认值，用户不会因为升级而闪退。
            tabsHeight = p[Keys.TABS_HEIGHT]
                ?.let { runCatching { TabsHeight.valueOf(it) }.getOrNull() }
                ?: TabsHeight.HALF,
            restoreSession = p[Keys.RESTORE_SESSION] ?: true,
            openLinksInExternalApp = p[Keys.OPEN_LINKS_EXTERNAL] ?: false,
            adBlockEnabled = p[Keys.AD_BLOCK] ?: true,
            adBlockRules = p[Keys.AD_BLOCK_RULES] ?: emptySet(),
            // 与 tabsHeight 同理：用 runCatching 兜底，
            // 万一将来删掉某个档位，老用户不会因为 valueOf 抛异常而闪退。
            readerFontSize = p[Keys.READER_FONT_SIZE]
                ?.let { runCatching { ReaderFontSize.valueOf(it) }.getOrNull() }
                ?: ReaderFontSize.MEDIUM,
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

    suspend fun setRestoreSession(enabled: Boolean) =
        context.dataStore.edit { it[Keys.RESTORE_SESSION] = enabled }

    suspend fun setReaderFontSize(size: ReaderFontSize) =
        context.dataStore.edit { it[Keys.READER_FONT_SIZE] = size.name }

    suspend fun setOpenLinksInExternalApp(enabled: Boolean) =
        context.dataStore.edit { it[Keys.OPEN_LINKS_EXTERNAL] = enabled }

    suspend fun setAdBlockEnabled(enabled: Boolean) =
        context.dataStore.edit { it[Keys.AD_BLOCK] = enabled }

    /**
     * 保存自定义广告拦截规则。
     *
     * 规则在**进 UI 前**就该被 [com.ling.browser.web.AdBlocker.normalizeRule]
     * 清洗过，这里只做存储。但为防万一（未来某个调用点漏清洗），
     * 仍在这里兜底过滤一次空串/纯空白，避免脏数据落库。
     */
    suspend fun setAdBlockRules(rules: Set<String>) =
        context.dataStore.edit { it[Keys.AD_BLOCK_RULES] = rules.filter { r -> r.isNotBlank() }.toSet() }

    suspend fun setTabCount(count: Int) =
        context.dataStore.edit { it[Keys.TAB_COUNT] = count }
}
