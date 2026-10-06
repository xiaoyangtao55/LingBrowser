package com.ling.browser.web

import android.graphics.Bitmap
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
    /**
     * 站点图标，用于标签列表的缩略图。由 WebChromeClient.onReceivedIcon 填入。
     *
     * 为什么用 Bitmap 而不是截整页：
     *   整页截图要 `WebView.draw(Canvas)` 到一张全屏大小的 Bitmap，
     *   1080x2000x4B ≈ 8.6 MB/张；同时开 6 个标签就是 ~50 MB，
     *   在中低端机上随时可能 OOM。favicon 一般 16~64px，内存可忽略。
     *
     * ⚠️ 生命周期：Bitmap 是**非托管内存**，GC 只能回收 Java 对象壳，
     * 像素数据要靠 recycle() 立即归还。因此标签关闭时必须显式回收，
     * 见 [WebTabManager.closeTab] 与 [WebTabManager.recycleTab]。
     *
     * 注：曾有一个 `faviconUrl: String?` 字段（存图标地址），但它从未被
     * 任何代码读取 —— Bitmap 本身已包含 UI 需要的一切，留着只会误导，
     * 故删除。日后若要「图标加载失败再按地址重取」，再加不迟。
     */
    val favicon: Bitmap? = null,
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

    /**
     * 站点首字母，用作没有 favicon 时的占位符。
     *
     * 为什么需要占位：大量站点不提供 favicon，或图标要等页面加载完才到。
     * 空着会让列表参差不齐，统一给一个字母反而更容易扫读。
     *
     * ⚠️ 不能直接用 [UrlUtils.hostOf] —— 它在解析失败时**退回原始 URL 字符串**，
     * 于是 `about:blank` 会得到 "A"、`ling://home` 得到 "L"，
     * 看起来像站点名实则毫无意义。主页和无法解析的地址统一给一个中性符号。
     */
    val initial: String
        get() {
            // 应用内主页没有站点，直接给中性占位符
            if (UrlUtils.isHome(url)) return NEUTRAL_INITIAL
            val host = runCatching { java.net.URI(url).host }.getOrNull()
            // host 为 null 说明不是常规 http(s) 地址（about:、data:、纯文本等）
            if (host.isNullOrBlank()) return NEUTRAL_INITIAL
            // 跳过 www. 前缀：否则几乎所有站点都显示 "w"，毫无区分度
            val core = host.removePrefix("www.")
            return core.firstOrNull()?.uppercase() ?: NEUTRAL_INITIAL
        }

    companion object {
        /** 没有可用站点名时的占位符。用圆点而非字母，明确表示"无信息"。 */
        const val NEUTRAL_INITIAL = "•"
    }
}
