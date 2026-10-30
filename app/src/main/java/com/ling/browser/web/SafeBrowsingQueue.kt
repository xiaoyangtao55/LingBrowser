package com.ling.browser.web

import android.net.Uri
import android.os.Build
import android.webkit.SafeBrowsingResponse
import android.webkit.WebViewClient

/**
 * Safe Browsing 命中的裁决队列。
 *
 * 与证书错误同一套路：同一时刻只弹一个，其余 FIFO 排队；队列上限很小 ——
 * 主框架命中本来就是低频事件，超出时直接退回安全页，不能让 callback 悬空
 * （悬空等于 WebView 一直等在那里）。
 *
 * 从 [WebTabManager] 拆出后不依赖标签簿记：Safe Browsing 的裁决结果不需要
 * 写进 TabState，只需兑现 callback 与驱动 UI 弹窗。
 */
internal class SafeBrowsingQueue {

    /** 尚未裁决的命中（FIFO，兜底用）。 */
    private val queue = ArrayDeque<Pending>()

    /** 当前正在弹窗的那一条；null 表示没有弹窗。 */
    private var shown: Pending? = null

    /** 弹窗回调；参数为 null 表示关掉弹窗。 */
    var onPrompt: ((SafeBrowsingPrompt?) -> Unit)? = null

    private class Pending(
        val prompt: SafeBrowsingPrompt,
        val callback: SafeBrowsingResponse,
    )

    /**
     * Safe Browsing 命中：排队弹窗，由用户决定继续还是返回。
     *
     * 队列上限很小 —— 主框架命中本来就是低频事件；超出时直接退回安全页，
     * 不能让 callback 悬空（悬空等于 WebView 一直等在那里）。
     */
    fun handle(
        tabId: String,
        url: String,
        mainFrame: Boolean,
        threatType: Int,
        callback: SafeBrowsingResponse,
    ) {
        // SafeBrowsingResponse 及其方法都是 API 27+；低版本根本不会触发
        // onSafeBrowsingHit。显式守卫让 Lint 能证明边界，也避免误调用时
        // 触发 NoSuchMethodError。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return
        if (queue.size >= MAX_PENDING) {
            callback.backToSafety(true)
            return
        }
        val host = runCatching { Uri.parse(url).host }.getOrNull().orEmpty()
        queue.addLast(Pending(SafeBrowsingPrompt(tabId, host, threatLabel(threatType), mainFrame), callback))
        showNext()
    }

    /** 用户对 Safe Browsing 警告的选择：true = 继续访问，false = 返回安全页。 */
    fun resolve(proceed: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return
        val item = shown ?: return
        shown = null
        if (proceed) item.callback.proceed(false) else item.callback.backToSafety(true)
        showNext()
    }

    /** UI 销毁时把待裁决项全部按「返回安全页」兑现，避免 callback 悬空。 */
    fun cancelAll() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return
        val items = ArrayList<Pending>()
        shown?.let { items.add(it) }
        shown = null
        while (true) {
            items.add(queue.removeFirstOrNull() ?: break)
        }
        for (item in items) item.callback.backToSafety(true)
        onPrompt?.invoke(null)
    }

    /** 关标签时只清理该标签的待裁决项。 */
    fun cancelFor(tabId: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return
        shown?.takeIf { it.prompt.tabId == tabId }?.let {
            it.callback.backToSafety(true)
            shown = null
            showNext()
        }
        val iterator = queue.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            if (item.prompt.tabId == tabId) {
                item.callback.backToSafety(true)
                iterator.remove()
            }
        }
    }

    private fun showNext() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return
        if (shown == null) {
            while (true) {
                val next = queue.removeFirstOrNull() ?: break
                if (onPrompt == null) {
                    // UI 还没接线：没人能回答，直接退回安全页。
                    next.callback.backToSafety(true)
                    continue
                }
                shown = next
                break
            }
        }
        onPrompt?.invoke(shown?.prompt)
    }

    private fun threatLabel(threatType: Int): String = when (threatType) {
        WebViewClient.SAFE_BROWSING_THREAT_MALWARE -> "恶意软件"
        WebViewClient.SAFE_BROWSING_THREAT_PHISHING -> "钓鱼网站"
        WebViewClient.SAFE_BROWSING_THREAT_UNWANTED_SOFTWARE -> "有害软件"
        // SAFE_BROWSING_THREAT_BILLING（值为 4）在部分编译 SDK 里没有公开常量。
        4 -> "可能有害的应用"
        else -> "危险网站"
    }

    private companion object {
        /** 待裁决上限：低频事件，超过就直接退回安全页。 */
        private const val MAX_PENDING = 4
    }
}
