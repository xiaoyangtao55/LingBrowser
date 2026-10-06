package com.ling.browser.util

import com.ling.browser.data.prefs.SearchEngine
import java.net.URLEncoder

/**
 * 地址栏输入的解析：区分「网址」与「搜索词」。
 *
 * 规则参照主流浏览器的直觉行为：
 *  - 显式带 scheme（http/https/file/about/…）→ 直接当 URL；
 *  - 形如 `example.com` / `192.168.1.1:8080` 的裸域名或 IP → 补 https://；
 *  - 其余（含空格、中文、无点串）→ 走搜索引擎。
 */
object UrlUtils {

    // 带 // 的完整 scheme，如 https://、ftp://
    private val SCHEME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")
    // 不带 // 的特殊 scheme，如 about:blank、data:text/html,...、mailto:a@b.com
    // 注意不能把 `C:\...` 这类 Windows 盘符误判为 scheme（要求 scheme 至少 2 个字母）
    private val OPAQUE_SCHEME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]+:(?!//)")
    // Windows 本地路径，如 C:\Users\x.html 或 C:/Users/x.html
    private val WINDOWS_PATH_REGEX = Regex("^[A-Za-z]:[\\\\/]")
    private val IPV4_REGEX = Regex("^\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?$")
    // 裸域名：含点、无空格，顶级域为 2 位以上字母
    private val BARE_DOMAIN_REGEX = Regex(
        "^[^\\s/?#@]+\\.[a-zA-Z]{2,}(?::\\d+)?([/?#].*)?$"
    )
    // 中文/国际化域名（IDN），例如「百度.com」「例子.中国」
    private val IDN_REGEX = Regex(
        "^[^\\s/?#@]+\\.[\\u4e00-\\u9fa5]{2,}(?::\\d+)?([/?#].*)?$"
    )

    /** 判断输入是否已经是完整 URL。 */
    fun isUrl(input: String): Boolean {
        val s = input.trim()
        if (s.isEmpty() || s.contains(' ')) return false
        // 含中文但不像域名（无点）时仍按搜索处理
        return SCHEME_REGEX.containsMatchIn(s) ||
            OPAQUE_SCHEME_REGEX.containsMatchIn(s) ||
            WINDOWS_PATH_REGEX.containsMatchIn(s) ||
            IPV4_REGEX.matches(s) ||
            BARE_DOMAIN_REGEX.matches(s) ||
            IDN_REGEX.matches(s)
    }

    /**
     * 把地址栏输入解析为可加载的 URL。
     * @param input 用户输入
     * @param engine 未命中 URL 时使用的搜索引擎
     */
    fun toUrl(input: String, engine: SearchEngine): String {
        val s = input.trim()
        if (s.isEmpty()) return engine.homepage

        return when {
            // 顺序很重要：本地路径必须先于裸域名判断，
            // 否则 C:\Users\a.html 会被 .html 当成顶级域而误判为网址。
            WINDOWS_PATH_REGEX.containsMatchIn(s) -> "file://$s"
            s.startsWith("/") -> "file://$s"
            SCHEME_REGEX.containsMatchIn(s) -> s
            OPAQUE_SCHEME_REGEX.containsMatchIn(s) -> s
            IPV4_REGEX.matches(s) -> "http://$s"
            BARE_DOMAIN_REGEX.matches(s) -> "https://$s"
            IDN_REGEX.matches(s) -> "https://$s"
            else -> searchUrl(s, engine)
        }
    }

    /** 构造搜索 URL。 */
    fun searchUrl(query: String, engine: SearchEngine): String =
        engine.queryUrl.format(URLEncoder.encode(query, "UTF-8"))

    /**
     * 地址栏展示用的「精简」形式：去掉 scheme 与结尾斜杠，让地址栏更清爽。
     */
    fun prettify(url: String): String = url
        .removePrefix("https://")
        .removePrefix("http://")
        .removePrefix("www.")
        .let { if (it.endsWith("/") && it.count { c -> c == '/' } == 1) it.dropLast(1) else it }

    /** 取主机名用于列表展示，失败时退回原串。 */
    fun hostOf(url: String): String = runCatching {
        java.net.URI(url).host ?: url
    }.getOrDefault(url)

    /**
     * 是否是应用内主页（内置新建标签页）。
     * 判断逻辑收敛到 [com.ling.browser.web.HomePage.isHomeUrl]，避免两处规则漂移。
     */
    fun isHome(url: String): Boolean =
        com.ling.browser.web.HomePage.isHomeUrl(url)

    /** 内置主页地址。真实定义在 [com.ling.browser.web.HomePage.URL]。 */
    const val HOME_URL = com.ling.browser.web.HomePage.URL
}
