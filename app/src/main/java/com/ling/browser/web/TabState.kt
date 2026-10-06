package com.ling.browser.web

import com.ling.browser.util.UrlUtils
import java.util.UUID

/**
 * 一个标签页的完整 UI 状态。
 *
 * 注意：真正的 [android.webkit.WebView] 实例不放在这里 —— 它由 [WebTabManager]
 * 持有并复用（WebView 创建成本高，不能每帧重建）。这里只镜像界面需要的状态。
 */
data class TabState(
    val id: String = UUID.randomUUID().toString(),
    val url: String = UrlUtils.HOME_URL,
    val title: String = "",
    val faviconUrl: String? = null,
    val isLoading: Boolean = false,
    /** 0..100，仅在 [isLoading] 为真时有意义。 */
    val progress: Int = 0,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val isIncognito: Boolean = false,
    /** 页面加载失败时的错误描述，非空表示展示错误页。 */
    val errorText: String? = null,
) {
    /** 列表中展示的标题，未取到标题时退回主机名。 */
    val displayTitle: String
        get() = title.ifBlank {
            if (UrlUtils.isHome(url)) "新标签页" else UrlUtils.hostOf(url)
        }

    val displayUrl: String
        get() = if (UrlUtils.isHome(url)) "" else UrlUtils.prettify(url)
}
