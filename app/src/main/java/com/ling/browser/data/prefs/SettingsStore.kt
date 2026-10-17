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
 * 内置主页的背景模式。
 *
 * 移植自 OnyxBrowser 的起始页背景设计，但做了轻量化取舍：
 *  - [SOLID]  纯色：用主题背景色（默认，观感与旧版一致）；
 *  - [CUSTOM] 自定义图片：在原生的主题色打底之上再叠用户选的照片。
 *
 * 两档都**由原生背景层在 WebView 之下供色**，主页 HTML 自身恒定透明 ——
 * 这个枚举只决定"画什么"，不存在"谁来画"的分支（见
 * `com.ling.browser.ui.components.HomeBackgroundLayer`）。
 *
 * 为什么不做 Onyx 那套"窗口透明 + SurfaceFlinger 透传"：那需要把整个 App 的窗口改成
 * 半透明（`PixelFormat.TRANSLUCENT` + `FLAG_SHOW_WALLPAPER`）并让最外层 Surface 也透明，
 * 会牵连每一个二级页面；对「翎」这种轻量目标来说代价过大。这里改为在主页这一层
 * 直接画背景位图 —— 效果一致，且不影响其它界面。
 *
 * 曾经还有一档「系统壁纸」：用 `WallpaperManager.getDrawable()` 取系统壁纸位图来画。
 * 但该接口在部分 ROM 上受存储权限/厂商定制影响拿不到位图，只能静默退化成一块纯色底，
 * 收益低且行为不可预期，已移除。历史值 `"wallpaper"` 由 [fromKey] 兜底成 [SOLID]。
 */
enum class HomeBackground(val key: String, val label: String) {
    SOLID("solid", "纯色"),
    CUSTOM("custom", "自定义图片");

    companion object {
        /**
         * 从持久化的 key 还原。
         *
         * 兜底写法与其它枚举一致：老版本/脏数据里可能出现未知的 key
         * （例如已移除的 `"wallpaper"`），直接 valueOf 会抛异常导致启动闪退，
         * 这里退回默认 [SOLID]。
         */
        fun fromKey(key: String?): HomeBackground =
            entries.firstOrNull { it.key == key } ?: SOLID
    }
}

/** 搜索引擎。 */
enum class SearchEngine(val label: String, val queryUrl: String, val homepage: String) {
    BAIDU("百度", "https://www.baidu.com/s?wd=%s", "https://m.baidu.com"),
    BING("必应", "https://www.bing.com/search?q=%s", "https://cn.bing.com"),
    GOOGLE("Google", "https://www.google.com/search?q=%s", "https://www.google.com"),
    DUCKDUCKGO("DuckDuckGo", "https://duckduckgo.com/?q=%s", "https://duckduckgo.com"),
}

/**
 * 下载器类型。
 *
 *  - [BUILT_IN] 内建下载器：自己掌握下载过程，支持实时进度、暂停/恢复、断点续传；
 *  - [SYSTEM]   系统下载器：交给 Android DownloadManager，稳定省心但进度靠轮询、
 *               不支持应用内暂停。
 *
 * 默认 [BUILT_IN]：这是「翎」自己实现的能力，用户装上来就能用上，
 * 不必先去设置里翻。需要系统下载器特性的用户可以切回去。
 */
enum class DownloaderType(val label: String) {
    BUILT_IN("内建下载器"),
    SYSTEM("系统下载器"),
}

/**
 * 下载保存位置。
 *
 *  - [APP_PRIVATE]       应用私有目录（Android/data/<pkg>/files/Download）：
 *                        无需任何存储权限，卸载时随之清理；
 *  - [PUBLIC_DOWNLOADS]  公共下载目录（/storage/emulated/0/Download）：
 *                        其它应用也能看到，需要存储权限（Android 9 及以下运行时申请，
 *                        Android 10+ 走 MediaStore，无需权限）。
 *
 * 默认 [APP_PRIVATE]：零权限、零打扰，符合「翎」轻量取向。
 * 需要把文件分享给其它 App 的用户可以切到公共目录。
 */
enum class DownloadLocation(val label: String) {
    APP_PRIVATE("应用私有目录"),
    PUBLIC_DOWNLOADS("公共下载目录"),
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
    /**
     * 内置主页的背景模式。
     *
     * 默认 [HomeBackground.SOLID]：保持旧版行为（主页用主题色自绘背景），
     * 新用户不主动去设置里改时不会感知到这次改动。
     */
    val homeBackground: HomeBackground = HomeBackground.SOLID,
    /**
     * 自定义主页背景图片的 `content://` URI（仅在 [homeBackground] 为
     * [HomeBackground.CUSTOM] 时有意义）。
     *
     * 存 URI 字符串而不是位图：位图动辄几十 MB，塞进 DataStore 既不合规范
     * （Preferences 只该存小标量），也会让每次读设置都反序列化一大坨数据。
     * 这里只在真正需要画背景时按 URI 去解码，并且解码时会做降采样（见
     * `HomeBackgroundLayer`），避免大图直接 OOM。
     */
    val homeBackgroundImageUri: String = "",
    val nightMode: NightMode = NightMode.FOLLOW_SYSTEM,
    val desktopMode: Boolean = false,
    val dynamicColor: Boolean = true,
    val incognito: Boolean = false,
    /**
     * 网页内容是否跟随夜间模式暗化。
     *
     * 默认**开**：夜间模式是"整套变暗"（界面 + 网页内容一起），
     * 而不是只暗界面、网页还白底闪眼。关掉它表示"我只要深色界面，
     * 网页内容保持原样"（比如想读原色的图文排版）。
     *
     * 真正的暗化条件是 `forceDarkWebPages && 夜间模式已解析为深色`，
     * 由 Web 层在运行时求值，本字段只存用户的意图开关。
     */
    val forceDarkWebPages: Boolean = true,
    /** 无图模式：省流。 */
    val blockImages: Boolean = false,
    /** 是否启用 JavaScript。 */
    val javaScriptEnabled: Boolean = true,
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
    /** 下载器类型，默认内建下载器。 */
    val downloaderType: DownloaderType = DownloaderType.BUILT_IN,
    /** 下载保存位置，默认应用私有目录（无需存储权限）。 */
    val downloadLocation: DownloadLocation = DownloadLocation.APP_PRIVATE,
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
        val HOME_BACKGROUND = stringPreferencesKey("home_background")
        val HOME_BACKGROUND_URI = stringPreferencesKey("home_background_uri")
        val NIGHT_MODE = stringPreferencesKey("night_mode")
        val DESKTOP_MODE = booleanPreferencesKey("desktop_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val FORCE_DARK = booleanPreferencesKey("force_dark_web")
        val BLOCK_IMAGES = booleanPreferencesKey("block_images")
        val JS_ENABLED = booleanPreferencesKey("js_enabled")
        val TAB_COUNT = intPreferencesKey("tab_count")
        val RESTORE_SESSION = booleanPreferencesKey("restore_session")
        val READER_FONT_SIZE = stringPreferencesKey("reader_font_size")
        val OPEN_LINKS_EXTERNAL = booleanPreferencesKey("open_links_external")
        val AD_BLOCK = booleanPreferencesKey("ad_block")
        val AD_BLOCK_RULES = stringSetPreferencesKey("ad_block_rules")
        val DOWNLOADER_TYPE = stringPreferencesKey("downloader_type")
        val DOWNLOAD_LOCATION = stringPreferencesKey("download_location")
    }

    val settings: Flow<LingSettings> = context.dataStore.data.map { p ->
        LingSettings(
            searchEngine = p[Keys.SEARCH_ENGINE]
                ?.let { runCatching { SearchEngine.valueOf(it) }.getOrNull() }
                ?: SearchEngine.BING,
            customHomepage = p[Keys.CUSTOM_HOMEPAGE] ?: "",
            // fromKey 内部兜底：未知 key 退回 SOLID，不会因脏数据闪退。
            homeBackground = HomeBackground.fromKey(p[Keys.HOME_BACKGROUND]),
            homeBackgroundImageUri = p[Keys.HOME_BACKGROUND_URI] ?: "",
            nightMode = p[Keys.NIGHT_MODE]
                ?.let { runCatching { NightMode.valueOf(it) }.getOrNull() }
                ?: NightMode.FOLLOW_SYSTEM,
            desktopMode = p[Keys.DESKTOP_MODE] ?: false,
            dynamicColor = p[Keys.DYNAMIC_COLOR] ?: true,
            forceDarkWebPages = p[Keys.FORCE_DARK] ?: true,
            blockImages = p[Keys.BLOCK_IMAGES] ?: false,
            javaScriptEnabled = p[Keys.JS_ENABLED] ?: true,
            restoreSession = p[Keys.RESTORE_SESSION] ?: true,
            openLinksInExternalApp = p[Keys.OPEN_LINKS_EXTERNAL] ?: false,
            adBlockEnabled = p[Keys.AD_BLOCK] ?: true,
            adBlockRules = p[Keys.AD_BLOCK_RULES] ?: emptySet(),
            // 枚举名入库、读回时用 runCatching 兜底：万一将来删掉某个档位，
            // 老用户不会因为 valueOf 抛异常而闪退。
            readerFontSize = p[Keys.READER_FONT_SIZE]
                ?.let { runCatching { ReaderFontSize.valueOf(it) }.getOrNull() }
                ?: ReaderFontSize.MEDIUM,
            downloaderType = p[Keys.DOWNLOADER_TYPE]
                ?.let { runCatching { DownloaderType.valueOf(it) }.getOrNull() }
                ?: DownloaderType.BUILT_IN,
            downloadLocation = p[Keys.DOWNLOAD_LOCATION]
                ?.let { runCatching { DownloadLocation.valueOf(it) }.getOrNull() }
                ?: DownloadLocation.APP_PRIVATE,
        )
    }

    suspend fun setSearchEngine(engine: SearchEngine) =
        context.dataStore.edit { it[Keys.SEARCH_ENGINE] = engine.name }

    suspend fun setCustomHomepage(url: String) =
        context.dataStore.edit { it[Keys.CUSTOM_HOMEPAGE] = url.trim() }

    suspend fun setHomeBackground(mode: HomeBackground) =
        context.dataStore.edit { it[Keys.HOME_BACKGROUND] = mode.key }

    /**
     * 保存自定义主页背景图片的 URI。
     *
     * 只存字符串。持久化读权限（`takePersistableUriPermission`）由**取图的那一侧**
     * （设置页的 ActivityResult 回调）负责申请 —— 这里不做，因为它需要 Activity/Context
     * 与 Intent flag，属于 UI 职责；存储层只忠实落库。
     */
    suspend fun setHomeBackgroundImageUri(uri: String) =
        context.dataStore.edit { it[Keys.HOME_BACKGROUND_URI] = uri.trim() }

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

    suspend fun setDownloaderType(type: DownloaderType) =
        context.dataStore.edit { it[Keys.DOWNLOADER_TYPE] = type.name }

    suspend fun setDownloadLocation(location: DownloadLocation) =
        context.dataStore.edit { it[Keys.DOWNLOAD_LOCATION] = location.name }

    suspend fun setTabCount(count: Int) =
        context.dataStore.edit { it[Keys.TAB_COUNT] = count }
}
