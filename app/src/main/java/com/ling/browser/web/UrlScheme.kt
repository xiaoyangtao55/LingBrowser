package com.ling.browser.web

/**
 * 地址协议判定。
 *
 * 抽成独立对象而不是留在 [WebTabManager] 里的理由：这段逻辑是
 * **纯粹的字符串判断**，却又是几类线上问题的关键防线 ——
 * 最典型的是 `net::ERR_UNKNOWN_URL_SCHEME`：App 内嵌页用自定义协议
 * 做内部跳转（`zhihu://answers/123`），把它交给 WebView 必然白屏。
 *
 * 留在 WebTabManager 里就得靠构造 WebView/Context 才能测，实际上等于测不了；
 * 抽出来之后可以直接跑单元测试，把各种协议一次覆盖干净。
 */
object UrlScheme {

    /**
     * WebView 能自行加载的协议。
     *
     * 与 [LingWebViewClient] 的 `handleUri` 允许集合保持一致：
     * 那边决定"要不要交给系统"，这边决定"能不能自己加载"。
     * 两边不一致就会出现两种相反的问题：
     *   - 这边放行、那边也放行，但 WebView 其实不认识 → 白屏
     *   - 这边能加载、那边却交给系统 → 无故切出去又切回来
     */
    private val NAVIGABLE = setOf(
        "http", "https", "file", "about", "data", "blob", "ling",
    )

    /**
     * 该地址能否交给 WebView 加载。
     *
     * 自己解析 scheme 而不用 `Uri.parse`：后者在本地 JVM 单元测试里
     * 是空壳（抛 `Stub!`），会让这段判定**完全无法测试** ——
     * 而它恰恰是最需要测试的部分之一。
     *
     * 解析规则按 RFC 3986：scheme 是第一个 `:` 之前的部分，
     * 且只能由字母开头、后跟字母/数字/`+`/`-`/`.` 组成。
     * 不满足就不认为有 scheme（例如 `这不是地址`、`://`），返回 false。
     */
    fun isNavigable(url: String): Boolean {
        val scheme = schemeOf(url) ?: return false
        return scheme in NAVIGABLE
    }

    /**
     * `shouldOverrideUrlLoading` 该不该**接管**这次导航。
     *
     * 返回 true 表示"我处理了，WebView 别管"；false 表示"交给 WebView 加载"。
     *
     * 这里最容易犯的错是把外部处理的结果直接当返回值：
     * `else -> onExternalScheme(uri)`。系统没有能处理该协议的应用时它返回
     * false，WebView 于是去加载 `zhihu://...`，直接得到
     * `net::ERR_UNKNOWN_URL_SCHEME`；若页面反复发起该导航，就会**一直闪**。
     *
     * 正确语义是：非可加载协议**一律接管** —— 系统能打开就打开，
     * 打不开就安静地什么也不做。后者远好于渲染一个必然失败的错误页。
     */
    fun shouldTakeOver(url: String): Boolean = !isNavigable(url)

    /**
     * 取出小写的 scheme，取不到返回 null。
     *
     * 必须小写归一：`Uri.parse` 与浏览器地址栏都保留用户输入的大小写，
     * `HTTPS://` 是合法的，直接按原样比较会误判成"不可加载"。
     */
    private fun schemeOf(url: String): String? {
        val trimmed = url.trim()
        val colon = trimmed.indexOf(':')
        // 没有冒号，或冒号在首字符（如 `://`），都视为无 scheme
        if (colon <= 0) return null
        val raw = trimmed.substring(0, colon)
        // RFC 3986: ALPHA *( ALPHA / DIGIT / "+" / "-" / "." )
        if (!raw[0].isLetter()) return null
        for (c in raw) {
            if (!c.isLetterOrDigit() && c != '+' && c != '-' && c != '.') return null
        }
        return raw.lowercase()
    }
}
