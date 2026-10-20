package com.ling.browser.web

import android.webkit.WebView

/**
 * 长按网页内容时命中的目标，供上下文菜单使用。
 *
 * 为什么需要它：现代 Chromium WebView **不再自带**链接/图片的长按菜单
 * （那是旧版 WebKit 的行为），长按只会进入文字选择。浏览器要对「长按链接」
 * 给出"新标签页打开 / 复制链接地址"这类动作，只能自己实现菜单 ——
 * 这个类就是 WebView 的命中结果与 UI 菜单之间的传输结构。
 *
 * 刻意只保留菜单真正需要的字段（地址），不带 `HitTestResult` 本身：
 * 那个对象绑定在 WebView 实例上，一旦标签被回收就成了悬空引用。
 */
data class HitTarget(
    val kind: Kind,
    /** 链接地址（[Kind.IMAGE] 与纯图片命中时为空）。 */
    val url: String? = null,
    /** 图片地址（[Kind.LINK] 时为空）。 */
    val imageUrl: String? = null,
    /**
     * 长按发生的位置，**窗口坐标**（像素）。
     *
     * 菜单要贴着手指弹出而不是从屏幕底部整块升起，就得知道手指在哪。
     * 由 [LingWebView] 用 `getLocationInWindow() + 触摸点` 算出 —— 这两个
     * 都是窗口坐标系，与 Compose `PopupPositionProvider` 的约定一致。
     *
     * 默认 0：单元测试只关心命中类型与地址，不关心位置。
     */
    val anchorX: Int = 0,
    val anchorY: Int = 0,
) {
    enum class Kind {
        /** 纯链接。 */
        LINK,

        /** 纯图片。 */
        IMAGE,

        /**
         * 图片外面套着链接（`<a><img></a>`）。
         *
         * 这种情况下 WebView 的命中结果只能稳定给出其中一个地址：靠上的
         * 命中类型给图，靠下的给链接。因此菜单按"有什么给什么"来渲染 ——
         * 两个动作都提供，缺地址的那一项就不出现。
         */
        LINK_IMAGE,
    }

    val hasLink: Boolean
        get() = !url.isNullOrBlank()

    val hasImage: Boolean
        get() = !imageUrl.isNullOrBlank()
}

/**
 * 把 `WebView.HitTestResult` 的类型 + `getExtra()` 翻译成 [HitTarget]。
 *
 * 参数刻意拆成 `(type, extra)` 两个原始值而不是直接收 `HitTestResult`：
 * 那个对象只能由 WebView 现场创建、在 JVM 单测里造不出来。拆开之后
 * 这段映射是**纯函数**，可以逐类型回归（见 HitTargetTest）。
 *
 * 返回 null 表示"不处理，交给 WebView 自己的默认行为"：
 *  - [WebView.HitTestResult.EDIT_TEXT_TYPE]：输入框/正文。WebView 的长按在这里
 *    是**文字选择**（选中后出复制/粘贴气泡），我们抢走事件会把选择功能弄坏；
 *  - 电话 / 邮箱 / 地图：这些由 `LingWebViewClient` 的 URI 分流处理，
 *    长按菜单重复一份只会让两条路径的判断逐渐不一致。
 */
// ANCHOR_TYPE / IMAGE_ANCHOR_TYPE 在新版 SDK 里被标为废弃（建议改用 SRC_* 系列），
// 但老 WebView 的命中结果仍会返回这两个值；不处理它们，长按就只在部分机型上有效。
@Suppress("DEPRECATION")
internal fun hitTargetOf(type: Int, extra: String?): HitTarget? {
    val value = extra?.takeIf { it.isNotBlank() } ?: return null
    return when (type) {
        WebView.HitTestResult.ANCHOR_TYPE,
        WebView.HitTestResult.SRC_ANCHOR_TYPE,
        -> HitTarget(HitTarget.Kind.LINK, url = value)

        WebView.HitTestResult.IMAGE_TYPE ->
            HitTarget(HitTarget.Kind.IMAGE, imageUrl = value)

        // <a><img></a>：Chromium 的命中结果里只有图上那条地址是可靠的，
        // 所以这里只带 imageUrl，链接动作在菜单里自然缺席（见 Kind 说明）。
        WebView.HitTestResult.IMAGE_ANCHOR_TYPE,
        WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE,
        -> HitTarget(HitTarget.Kind.LINK_IMAGE, imageUrl = value)

        else -> null
    }
}
