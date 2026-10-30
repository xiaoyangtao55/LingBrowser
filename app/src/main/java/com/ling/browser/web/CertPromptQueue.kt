package com.ling.browser.web

import android.webkit.SslErrorHandler

/**
 * 证书错误的裁决队列。
 *
 * 一个页面可能同时触发多个（子资源）握手失败，而 [SslErrorHandler] 必须逐个
 * 兑现 —— 弹窗同一时刻只显示一个，其余 FIFO 排队；超过 [MAX_PENDING] 的直接
 * 取消，避免坏页面把队列撑爆、挂满永不兑现的 handler。
 *
 * 从 [WebTabManager] 拆出：队列只关心"排队 / 弹窗 / 兑现"，
 * 标签白名单（[TabSession.certExceptions]）与主框架错误状态仍由宿主读写。
 */
internal class CertPromptQueue(
    /** 取（必要时创建）标签簿记，用于写入"本次不再询问"的白名单。 */
    private val sessionOf: (String) -> TabSession,
    /** 只查不建：已关闭的标签不该因为一次握手失败又复活一份簿记。 */
    private val findSession: (String) -> TabSession?,
    /** 主框架放行：地址栏继续标「证书错误」。 */
    private val onMainFrameInsecure: (tabId: String) -> Unit,
    /** 主框架被拒绝：落证书错误页，而不是留一句泛化的加载失败。 */
    private val onMainFrameBlocked: (tabId: String, reason: String) -> Unit,
) {
    /** 尚未裁决的错误（FIFO）。 */
    private val queue = ArrayDeque<Pending>()

    /** 当前正在弹窗的那一条；null 表示没有弹窗。 */
    private var shown: Pending? = null

    /**
     * 弹窗回调：参数为 null 表示"当前没有待裁决项，关掉弹窗"。
     *
     * 用可空参数而不是两个回调，是为了让 UI 只维护一个状态源。
     */
    var onPrompt: ((CertPrompt?) -> Unit)? = null

    /** 队列里的一条：等待用户裁决的 handler 与展示信息。 */
    private class Pending(
        val tabId: String,
        val prompt: CertPrompt,
        val handler: SslErrorHandler,
    )

    /**
     * 网页证书校验失败：已放行过的主机直接 `proceed()`，否则排队弹窗。
     *
     * 主框架与子资源统一处理 —— 子资源静默取消会让页面"看起来正常但缺内容"。
     */
    fun handle(
        tabId: String,
        host: String,
        reason: String,
        mainFrame: Boolean,
        handler: SslErrorHandler,
    ) {
        if (host.isBlank()) {
            handler.cancel()
            return
        }
        if (findSession(tabId)?.certExceptions?.contains(host) == true) {
            handler.proceed()
            // 放行 ≠ 安全：地址栏继续标「证书错误」。
            if (mainFrame) onMainFrameInsecure(tabId)
            return
        }
        // 坏页面可能一口气触发几十个子资源失败；超出队列上限的直接拒绝，
        // 不能让每个都挂一个永不兑现的 handler。
        if (queue.size >= MAX_PENDING) {
            handler.cancel()
            return
        }
        queue.addLast(Pending(tabId, CertPrompt(host, reason, mainFrame), handler))
        showNext()
    }

    /**
     * 用户对待裁决证书错误的选择。
     *
     * [remember] 对应 Via 的「本次浏览过程中不再询问」：把主机加入本标签的
     * 白名单，之后同主机不再弹窗（关标签失效，不落盘）。
     */
    fun resolve(proceed: Boolean, remember: Boolean) {
        val item = shown ?: return
        shown = null
        if (proceed) {
            if (remember) sessionOf(item.tabId).certExceptions.add(item.prompt.host)
            item.handler.proceed()
            if (item.prompt.mainFrame) onMainFrameInsecure(item.tabId)
        } else {
            item.handler.cancel()
            if (item.prompt.mainFrame) onMainFrameBlocked(item.tabId, item.prompt.reason)
        }
        showNext()
    }

    /**
     * 把队列里所有待裁决项按拒绝处理并关掉弹窗。
     *
     * 用于 UI 销毁：这些 handler 已经没人能回答，留着会让 TLS 握手永远悬空
     * （页面停在半加载状态）。
     */
    fun cancelAll() {
        val items = ArrayList<Pending>()
        shown?.let { items.add(it) }
        shown = null
        while (true) {
            items.add(queue.removeFirstOrNull() ?: break)
        }
        for (item in items) {
            item.handler.cancel()
            if (item.prompt.mainFrame) onMainFrameBlocked(item.tabId, item.prompt.reason)
        }
        onPrompt?.invoke(null)
    }

    /** 关标签时清理该标签的待裁决项（其余标签的继续保留）。 */
    fun cancelFor(tabId: String) {
        shown?.takeIf { it.tabId == tabId }?.let {
            it.handler.cancel()
            shown = null
            showNext()
        }
        val iterator = queue.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            if (item.tabId == tabId) {
                item.handler.cancel()
                iterator.remove()
            }
        }
    }

    /** 队列里挑一条展示；没有待裁决项时向 UI 发 null 关掉弹窗。 */
    private fun showNext() {
        if (shown == null) {
            while (true) {
                val next = queue.removeFirstOrNull() ?: break
                if (onPrompt == null) {
                    // UI 还没接线：这条没人能回答，只能拒绝。
                    next.handler.cancel()
                    if (next.prompt.mainFrame) onMainFrameBlocked(next.tabId, next.prompt.reason)
                    continue
                }
                shown = next
                break
            }
        }
        onPrompt?.invoke(shown?.prompt)
    }

    private companion object {
        /** 排队上限：子资源也弹窗后，坏页面会连续上报。 */
        private const val MAX_PENDING = 8
    }
}
