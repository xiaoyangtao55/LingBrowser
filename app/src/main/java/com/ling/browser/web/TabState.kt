package com.ling.browser.web

import android.graphics.Bitmap
import com.ling.browser.data.db.TabSnapshot
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
     * 地址栏在**非编辑态**显示什么。
     *
     * 加载完成后显示**网页标题**（一眼看出这是什么页面），而不是一长串网址；
     * 但两种情况必须退回网址：
     *  1. **正在加载**：此时 `title` 还是**上一个页面**的（WebView 要等解析到
     *     `<title>` 才更新），显示它就像"点了没反应"；
     *  2. **没有标题**：少数页面不给 `<title>`（或解析失败）。
     *
     * 主页返回空串 —— 地址栏在主页上就该是空的，方便直接输入。
     * 聚焦进入编辑态时会切成真实网址（见 BrowserViewModel.onAddressFocusChanged），
     * 所以显示标题不影响改地址。
     */
    val addressBarText: String
        get() {
            if (UrlUtils.isHome(url)) return ""
            if (isLoading) return url
            return title.trim().ifEmpty { url }
        }

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

/**
 * 转成可持久化的快照。[position] 由调用方按列表顺序给出。
 *
 * 不放在 `TabState` 内部做成成员函数，是为了让 `TabState`（纯 UI 状态）
 * 不反向依赖 `data.db` 包 —— 依赖方向应始终是 UI -> 数据。
 */
internal fun TabState.toSnapshot(position: Int, readerOriginalUrl: String? = null) = TabSnapshot(
    id = id,
    // 存**对外地址**：主页是 ling://home（能识别），阅读视图换成原文地址 ——
    // 存合成的 ling://reader 的话，重启恢复只能得到错误页（WebView 不认识它，
    // 正文缓存又不在磁盘上）。见 InternalPage.externalUrl。
    url = InternalPage.externalUrl(url, readerOriginalUrl) ?: url,
    title = title,
    position = position,
    isIncognito = isIncognito,
)
