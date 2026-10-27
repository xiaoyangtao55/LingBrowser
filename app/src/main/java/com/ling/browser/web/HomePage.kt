package com.ling.browser.web

/**
 * 内置主页的**逻辑地址**。
 *
 * 主页已改为原生 Compose 渲染（见 `ui/screens/HomeScreen.kt`），WebView 里不再
 * 承载任何主页文档 —— 这里只剩一个身份常量，用于：
 *  - 地址栏显示：停在该地址上时地址栏为空；
 *  - 标签状态：[TabState.url] 用它表示"这个标签正在显示主页"；
 *  - 会话快照：恢复时靠它决定要不要回到主页。
 *
 * 为什么仍用自定义 scheme 而不是 about: ——  WebView 只认识 about:blank，
 * 早期版本把 about:home 直接交给它，结果走 onReceivedError 白屏（见
 * `HomePageTest` 对负数用例的说明）。
 */
object HomePage {

    /** 内置主页的逻辑地址（用于地址栏判断与展示）。 */
    const val URL = "ling://home"

    /** 兼容旧值：早期版本用的是 about:home，旧会话快照里可能还留着。 */
    private const val LEGACY_URL = "about:home"

    /** 该 URL 是否指向内置主页。 */
    fun isHomeUrl(url: String?): Boolean {
        val u = url?.trim().orEmpty()
        return u.isEmpty() || u == URL || u == LEGACY_URL
    }
}
