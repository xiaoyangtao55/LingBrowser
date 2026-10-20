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
 * 3. **离线可用**：内置表随 APK 发布，不订阅任何源也能用；订阅（见下）是
 *    可选的增强 —— 不可用时只是回到内置表，不会让广告拦截整个失效。
 *
 * ## 域名规则从哪来
 *
 * 共三份，语义完全一致（命中即拦，且任意深度子域名被兜住）：
 *
 *  1. [BLOCKED_DOMAINS]：APK 内置的**精简表**。开箱即用，但广告域名是天天
 *     在长的，光靠内置表只能等发版；
 *  2. **用户自定义规则**（设置里手输，见 [normalizeRule]）；
 *  3. **订阅名单**（导入 Hosts / AdGuard domains-only 的网址或本地文件，
 *     见 [BlockRules]）。
 *
 * 后两份都由调用方**现读传入**，本对象不持有它们 —— 一份订阅名单可能有
 * 十万条，每次请求都合并一遍会白白分配一堆集合（拦截发生在
 * `shouldInterceptRequest` 里，那是每个子资源都要跑一次的热路径）。
 *
 * ## 判定算法（针对请求 URL）
 *
 * 对每个拦截请求，做三层判定，命中任意一层即拦截：
 *
 * 1. **域名命中**：把 URL 的 host 逐级缩短（`ad.doubleclick.net` →
 *    `doubleclick.net` → `net`），查到上面三份规则里任一命中即拦截。
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
     * 内置精简表。键是"裸域名"（不含任何子域名前缀），也可以是多段的具体
     * host（如 `union.360.cn`）—— 后者更精确，只拦那个子域名而不会波及整站。
     *
     * 选表原则：只收**广告/跟踪专用域名**。像 `360.cn` 这种整个站点都算
     * 广告公司的域名要慎收（它会连带拦掉 `www.360.cn`、`dl.360.cn` 等正常
     * 入口），所以宁可写成具体子域，也不图省事写个裸域名。
     *
     * 这份表只是"开箱能用"的起点，不是完整名单 —— 大名单走订阅（见
     * [BlockRules]）。
     */
    private val BLOCKED_DOMAINS: Set<String> = setOf(
        // ---- 国际网盟 ----
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "google-analytics.com",
        "googletagmanager.com",
        "googletagservices.com",
        "adservice.google.com",
        "pagead2.googlesyndication.com",
        "amazon-adsystem.com",
        "assoc-amazon.com",
        "adnxs.com",
        "appnexus.com",
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
        "adition.com",
        "smartadserver.com",
        "teads.tv",
        "bidswitch.net",
        "33across.com",
        "sharethrough.com",
        "sovrn.com",
        "indexexchange.com",
        "media.net",
        "undertone.com",
        "adroll.com",
        "tribalfusion.com",
        "yieldmo.com",
        "spotxchange.com",
        "tremorhub.com",
        "gumgum.com",
        "connatix.com",
        "sonobi.com",
        "triplelift.com",
        "improvedigital.com",
        "loopme.me",
        "sizmek.com",
        "moatads.com",
        "adsafeprotected.com",
        "doubleverify.com",
        "integralads.com",
        "pixalate.net",
        "adloox.com",
        // ---- 跟踪 / 统计 / 行为分析 ----
        "scorecardresearch.com",
        "quantserve.com",
        "chartbeat.com",
        "hotjar.com",
        "mouseflow.com",
        "fullstory.com",
        "crazyegg.com",
        "inspectlet.com",
        "mixpanel.com",
        "segment.com",
        "amplitude.com",
        "heapanalytics.com",
        "kissmetrics.com",
        "statcounter.com",
        "optimizely.com",
        "vwo.com",
        "abtasty.com",
        "contentsquare.net",
        "exelator.com",
        "addthis.com",
        // ---- 移动广告 SDK ----
        "facebook.net",
        "branch.io",
        "appsflyer.com",
        "adjust.com",
        "adcolony.com",
        "applovin.com",
        "vungle.com",
        "inmobi.com",
        "mopub.com",
        "startapp.com",
        "tapjoy.com",
        "chartboost.com",
        "flurry.com",
        "kochava.com",
        "unityads.unity3d.com",
        "adswizz.com",
        "bidvertiser.com",
        "propellerads.com",
        "popads.net",
        "popcash.net",
        "adcash.com",
        "exoclick.com",
        "juicyads.com",
        "trafficjunky.com",
        "hilltopads.net",
        "adsterra.com",
        // ---- 国内广告平台 / 联盟 ----
        "umeng.com",
        "umengcloud.com",
        "baidustatic.com",
        "bdstatic.com",
        "pos.baidu.com",
        "cpro.baidu.com",
        "mobads.baidu.com",
        "mobads-logs.baidu.com",
        "union.baidu.com",
        "tanx.com",
        "alimama.com",
        "simba.taobao.com",
        "s.click.taobao.com",
        "uland.taobao.com",
        "union-click.jd.com",
        "gdt.qq.com",
        "e.qq.com",
        "adsview.qq.com",
        "pgdt.qq.com",
        "l.qq.com",
        "adview.com",
        "adsmogo.com",
        "domob.cn",
        "domob.com.cn",
        "youmi.net",
        "admaster.com.cn",
        "miaozhen.com",
        "mmstat.com",
        "cnzz.com",
        "growingio.com",
        "talkingdata.net",
        "getui.net",
        "igexin.com",
        "jpush.cn",
        "mob.com",
        "sinaads.cn",
        "union.360.cn",
        // ---- 国内内容平台广告位 ----
        "douyinstatic.com",
        "bytedance.com",
        "pangle.io",
        "snssdk.com",
        "pangolin-sdk-toutiao.com",
        "ad.toutiao.com",
    )

    /**
     * 内置表的条数。
     *
     * 供订阅页展示"开箱自带多少条"，让用户明白**不导入任何订阅也能用** ——
     * 否则他们只看得到订阅列表，会以为不订阅就等于没有广告拦截。
     */
    val builtinCount: Int
        get() = BLOCKED_DOMAINS.size

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
