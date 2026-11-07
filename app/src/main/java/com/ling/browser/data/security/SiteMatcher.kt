package com.ling.browser.data.security

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 站点归并规则：**HTTPS + eTLD+1 精确相等**。
 *
 * 这是密码库的安全底线，三条都不能松：
 *
 *  1. **只接受 HTTPS**。HTTP 页面上的表单可能被中间人篡改，保存和填充
 *     密码都必须拒绝。
 *  2. **用 Public Suffix List 求 eTLD+1**，而不是"取最后两段"或子串匹配。
 *     直接取最后两段会把 `foo.co.uk` 和 `bar.co.uk` 归成同一个 `co.uk`，
 *     等于让任意两个英国站点共享密码；子串匹配则会让
 *     `evil-example.com` 命中 `example.com`。OkHttp 已内置 PSL，直接复用，
 *     不额外引入依赖。
 *  3. **IP / localhost / 无公共后缀的主机一律不存**。这些"站点"没有可信的
 *     归属边界，宁可不提供保存，也不能猜。
 */
object SiteMatcher {

    /**
     * 从完整 URL 求站点标识；不是 HTTPS 或无法确定归属时返回 null。
     */
    fun siteFromUrl(url: String): String? {
        val parsed = url.trim().toHttpUrlOrNull() ?: return null
        if (!parsed.isHttps) return null
        return registrableDomain(parsed)
    }

    /**
     * 把用户输入或捕获到的站点归一成 eTLD+1。
     *
     * 接受 `example.com`、`https://example.com/login` 两种写法；
     * 显式写了 `http://` 的输入会被拒绝，而不是偷偷升级成 HTTPS ——
     * 用户应该明确知道密码属于哪个安全站点。
     */
    fun normalizeSite(input: String): String? {
        val raw = input.trim()
        if (raw.isEmpty()) return null
        val withScheme = if ("://" in raw) raw else "https://$raw"
        return siteFromUrl(withScheme)
    }

    /**
     * OkHttp 的 PSL 查询：`www.example.com` -> `example.com`，
     * `a.b.example.co.uk` -> `example.co.uk`。
     *
     * 对 IP 字面量返回 null；对 PSL 里查不到的后缀也返回 null ——
     * 此时无法确认归属，按"不可保存"处理。
     */
    private fun registrableDomain(url: HttpUrl): String? =
        runCatching { url.topPrivateDomain() }.getOrNull()?.lowercase()
}
