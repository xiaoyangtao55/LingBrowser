package com.ling.browser.web

/**
 * 广告拦截的判定核心。
 *
 * ## 为什么抽成纯 Kotlin、不碰 Android
 *
 * 广告拦截发生在 [LingWebViewClient.shouldInterceptRequest]，那边有 WebView。
 * 但**判定逻辑本身**（"这个 URL 该不该拦"）只是一条字符串规则，跟 WebView 无关。
 * 抽到这里之后，过滤规则可以在本地 JVM 单测里完整覆盖 —— 留在 WebViewClient
 * 里就得构造 WebResourceRequest，等于测不了。
 *
 * ## 方案选择：域名黑名单，而不是 EasyList
 *
 * Via 这类轻量浏览器的广告拦截分两档：默认的**域名级**（拦广告/跟踪域名）
 * 和可选的**元素级**（EasyList + 隐藏选择器，体积大、更新复杂）。「翎」先做
 * 域名级，理由：
 *
 * 1. **体积**：EasyList 文本 + 解析后的规则结构，纯文本就几百 KB，
 *    接近当前 APK 的 1.4 MB，与极简定位冲突。而内嵌的域名表只有几十 KB 源码。
 * 2. **命中面**：拦截广告**子请求**（banner、脚本、跟踪像素）靠域名级规则
 *    已经能去掉绝大多数广告，元素级只是再补上"页面里残留的空位"。
 * 3. **无网络更新**：域名表随 APK 发布，不依赖在线订阅源 —— 离线也能用，
 *    也避免"订阅源挂了广告拦截就失效"的依赖。
 *
 * ## 判定算法（针对请求 URL）
 *
 * 对每个拦截请求，做三层判定，命中任意一层即拦截：
 *
 * 1. **精确域名命中**：把 URL 的 host 逐级缩短（`ad.doubleclick.net` →
 *    `doubleclick.net` → `net`），查到 [BLOCKED_DOMAINS] 里的条目即拦截。
 *    这样 `xxx.doubleclick.net` 这种子域名也能被 `doubleclick.net` 兜住。
 * 2. **路径关键字命中**：域名没拦，但 URL 的 path 里含广告路径片段
 *    （`/ads/`、`/adserver/`、`-ad-` 等）即拦截。很多站点把广告放在
 *    自家域名的 `/ads/` 目录下，域名级规则抓不到。
 * 3. **主文档保护**：以上两条只作用于**子资源**，绝不拦截主文档
 *    （`isForMainFrame`）。否则用户直接打开的广告落地页会被误杀成白屏。
 *
 * ## 一个必须挡住的坑：主文档
 *
 * `shouldInterceptRequest` 对**主文档**也会回调。若不加 `isForMainFrame` 判断，
 * 用户点开一个广告落地页（比如搜索结果里点进 `ads.example.com`）会整页白屏，
 * 而且地址栏还显示着一个正常的地址 —— 用户会以为是浏览器坏了。
 * 所以主文档永远放行。
 */
object AdBlocker {

    /**
     * 广告/跟踪域名表。键是"裸域名"（不含任何子域名前缀）。
     *
     * 分级：越靠前的越激进（大网盟 + 跟踪），越靠后的越保守
     * （国内常见广告 SDK 域名，误伤风险低）。
     */
    private val BLOCKED_DOMAINS: Set<String> = setOf(
        // ---- 国际大网盟与跟踪 ----
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "google-analytics.com",
        "googletagmanager.com",
        "googletagservices.com",
        "adservice.google.com",
        "adnxs.com",
        "adsrvr.org",
        "taboola.com",
        "outbrain.com",
        "criteo.com",
        "criteo.net",
        "rubiconproject.com",
        "openx.net",
        "pubmatic.com",
        "casalemedia.com",
        "adform.net",
        "advertising.com",
        "moatads.com",
        "scorecardresearch.com",
        "quantserve.com",
        "chartbeat.com",
        "hotjar.com",
        "mixpanel.com",
        "segment.com",
        "amplitude.com",
        "facebook.net",
        "branch.io",
        "appsflyer.com",
        "adjust.com",
        // ---- 国内常见广告 SDK ----
        "umeng.com",
        "umengcloud.com",
        "baidustatic.com",
        "bdstatic.com",
        "pos.baidu.com",
        "cpro.baidu.com",
        "tanx.com",
        "mmstat.com",
        "cnzz.com",
        "growingio.com",
        "talkingdata.net",
        "360.cn",
        "douyinstatic.com",
        "bytedance.com",
        "pangle.io",
        "snssdk.com",
    )

    /**
     * URL path 里出现这些片段即拦截。只匹配 path 段，不碰 query，
     * 避免误伤正常链接（比如 `/docs/ads-guide` 这种教学页）。
     */
    private val PATH_MARKERS: List<String> = listOf(
        "/ads/",
        "/ad/",
        "/adserver/",
        "/adserver.",
        "/advert/",
        "/advertising/",
        "/banner/",
        "/doubleclick/",
        "/pagead/",
        "/googleads/",
        "/googlesyndication/",
    )

    /**
     * 是否拦截该请求。
     *
     * @param url 完整 URL。
     * @param isMainFrame 是否为主文档请求。主文档**永不拦截**。
     * @param customDomains 用户自定义的域名规则（裸域名，如 `ad.example.com`）。
     *   与内置 [BLOCKED_DOMAINS] 同等语义：命中即拦截，且任意深度子域名被兜住。
     *   传空集合时只走内置规则。
     * @return true 表示拦截（返回空响应），false 放行。
     */
    fun shouldBlock(
        url: String?,
        isMainFrame: Boolean,
        customDomains: Set<String> = emptySet(),
    ): Boolean {
        // 主文档保护：广告落地页本身要能打开，否则用户点广告结果整页白屏。
        if (isMainFrame) return false
        if (url.isNullOrBlank()) return false

        val scheme = UrlScheme.schemeOf(url)
        // 只拦 http(s)：data:/blob:/file: 等内联资源不该被这套规则碰。
        if (scheme != "http" && scheme != "https") return false

        val host = hostOf(url) ?: return false
        if (matchesDomain(host, customDomains)) return true

        val path = pathOf(url)
        return PATH_MARKERS.any { path.contains(it) }
    }

    /**
     * 域名命中：把 host 逐级缩短查表（内置表 + 用户规则）。
     *
     * `a.b.doubleclick.net` → `b.doubleclick.net` → `doubleclick.net` → 命中。
     * 这样任意深度子域名都被 `doubleclick.net` 兜住。
     */
    private fun matchesDomain(host: String, customDomains: Set<String>): Boolean {
        val parts = host.split('.')
        // 从最长的后缀开始收缩：先查完整 host，再去掉最左段，直到剩两段。
        for (i in parts.indices) {
            val candidate = parts.drop(i).joinToString(".")
            if (candidate in BLOCKED_DOMAINS || candidate in customDomains) return true
            // 只剩两段（如 "example.com"）后再缩就没意义了
            if (parts.size - i <= 2) break
        }
        return false
    }

    /**
     * 归一化用户输入的域名规则：去空白、去协议前缀、去端口、去 path、转小写。
     *
     * 用户可能粘贴 `https://ad.example.com/path?x=1`，但我们只想要
     * `ad.example.com`。抽成纯函数供 UI 在**保存前**校验/清洗，
     * 也供单测覆盖各种脏输入。
     *
     * 返回 null 表示输入不构成合法域名（如空串、`这不是域名`）。
     */
    fun normalizeRule(input: String): String? {
        val s = input.trim()
        if (s.isEmpty()) return null
        // 去掉协议前缀
        val noScheme = s.substringAfter("://", s)
        // 取 host 部分（去 path/query/fragment/端口/userinfo）
        val host = hostOf(noScheme) ?: return null
        // 域名至少要含一个点（www.example.com / example.com），
        // 单段（localhost、纯数字 IP 的一部分）没有拦截意义且容易误伤。
        if (!host.contains('.')) return null
        return host
    }

    /** 从 URL 里取 host（小写、去端口）。失败返回 null。 */
    internal fun hostOf(url: String): String? {
        var rest = url.substringAfter("://", url)
        // 去掉 path / query / fragment
        val cut = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        if (cut >= 0) rest = rest.substring(0, cut)
        // 先去掉 userinfo（user:pass@host）：必须在去端口**之前**，
        // 否则 userinfo 里的冒号会被误当成端口分隔符。
        val at = rest.lastIndexOf('@')
        if (at >= 0) rest = rest.substring(at + 1)
        // 再去掉端口（最后一个冒号之后）。
        // 用 lastIndexOf 而不是 indexOf：IPv6 字面量里含冒号，
        // 但 host 形如 [::1]:8080 时端口是最后一个冒号后的部分。
        val colon = rest.lastIndexOf(':')
        if (colon >= 0) rest = rest.substring(0, colon)
        val host = rest.lowercase().trim()
        return host.ifBlank { null }
    }

    /** 从 URL 里取 path（小写，保留开头斜杠，不含 query/fragment）。 */
    internal fun pathOf(url: String): String {
        // 先取"authority + path"部分，再截 authority 之后的内容（含前导斜杠）。
        val afterScheme = url.substringAfter("://", url)
        // 找到第一个 '/'，它就是 path 的起点（authority 结束处）。
        val slash = afterScheme.indexOf('/')
        val afterHost = if (slash >= 0) afterScheme.substring(slash) else ""
        val cut = afterHost.indexOfFirst { it == '?' || it == '#' }
        val path = if (cut >= 0) afterHost.substring(0, cut) else afterHost
        return path.lowercase()
    }
}
