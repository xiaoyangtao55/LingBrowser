package com.ling.browser.web

/**
 * 页面的连接安全状态 —— 地址栏据此展示安全标识。
 *
 * 刻意区分三种「不安全」，因为成因与危险级别完全不同：
 *  - [INSECURE_HTTP]：主框架就是明文 HTTP，整页裸奔；
 *  - [INSECURE_MIXED]：主框架是 HTTPS，但页面里混进了 HTTP 子资源（混合内容），
 *    只有一部分内容没被加密保护；
 *  - [INSECURE_CERT]：HTTPS 证书校验没过（过期 / 域名不匹配 / 不受信 CA），
 *    连接已被阻断 —— 最危险，绝不能放行。
 *
 * 状态由 [WebTabManager] 在页面生命周期里维护：主框架每次开始加载先按协议
 * 落到基础态（[baseForUrl]），加载过程中再被混合内容 / 证书错误回调**升级**。
 */
enum class SecurityState {
    /** HTTPS 且未发现混合内容与证书问题。 */
    SECURE,

    /** 主框架为明文 HTTP。 */
    INSECURE_HTTP,

    /** HTTPS 页面混入了 HTTP 子资源（混合内容）。 */
    INSECURE_MIXED,

    /** 证书有问题，连接已被阻断。 */
    INSECURE_CERT,
    ;

    /** 是否需要在地址栏给出醒目的不安全标识。 */
    val isInsecure: Boolean get() = this != SECURE

    companion object {
        /**
         * 只按 URL **协议**判定的基础状态，供后续回调再升级。
         *
         * 只认 `http://` —— https、内部页（`ling://`）、`about:`/`data:` 都算安全基础态。
         * 混合内容与证书错误无法从 URL 看出来，由回调另行升级。
         */
        fun baseForUrl(url: String): SecurityState =
            if (url.startsWith("http://", ignoreCase = true)) INSECURE_HTTP else SECURE
    }
}
