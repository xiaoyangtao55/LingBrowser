package com.ling.browser.web

/**
 * 一次等待用户裁决的表单重提交。
 *
 * POST 结果页刷新时，WebView 不知道该不该把表单数据再发一遍（本地缓存里没有
 * 请求体），默认行为是**什么都不做** —— 表现为"刷新没反应"。这里与 Chrome
 * 对齐：弹窗问一次，用户确认才重新提交。
 */
data class FormResubmitPrompt(val host: String)

/**
 * POST 结果页「是否重新提交」的裁决队列。
 *
 * `onFormResubmission` 给的两个开关必须**发且只发一个**，否则 WebView 的加载
 * 会卡在等待状态。与证书错误、Safe Browsing 同理：同一时刻只弹一个，
 * 处理完再弹下一个。
 *
 * 用两个 lambda 而不是 `android.os.Message`：队列本身只关心"兑现哪一个"，
 * 不必认识 Android 消息机制，也就能在纯 JVM 单测里覆盖排队规则。
 */
internal class FormResubmitQueue {

    /** 当前在弹窗的那一条；null 表示没有弹窗。 */
    private var shown: Pending? = null

    /** FIFO：网页可能连续触发多次刷新。 */
    private val pending = ArrayDeque<Pending>()

    /** 弹窗回调；参数为 null 表示关掉弹窗。 */
    var onPrompt: ((FormResubmitPrompt?) -> Unit)? = null

    /**
     * 收到一次表单重提交请求。
     *
     * 队列已满时直接按「不重新提交」兑现 —— 这本来就是低频事件，
     * 超出上限说明页面在异常地连环刷新，没必要为它再攒一条。
     * UI 还没接线时同样立即兑现，否则加载会一直悬着。
     */
    fun handle(
        tabId: String,
        host: String,
        onResend: () -> Unit,
        onDontResend: () -> Unit,
    ) {
        if (pending.size >= MAX_PENDING) {
            onDontResend()
            return
        }
        pending.addLast(Pending(tabId, FormResubmitPrompt(host), onResend, onDontResend))
        showNext()
    }

    /** 用户的选择：true = 重新提交，false = 放弃。 */
    fun resolve(resend: Boolean) {
        val item = shown ?: return
        shown = null
        if (resend) item.onResend() else item.onDontResend()
        showNext()
    }

    /** UI 销毁时把待裁决项全部按「不重新提交」兑现。 */
    fun cancelAll() {
        shown?.let { it.onDontResend() }
        shown = null
        while (true) {
            val item = pending.removeFirstOrNull() ?: break
            item.onDontResend()
        }
        onPrompt?.invoke(null)
    }

    /** 关标签时清理该标签的待裁决项。 */
    fun cancelFor(tabId: String) {
        if (shown?.tabId == tabId) {
            shown?.onDontResend()
            shown = null
            showNext()
        }
        val iterator = pending.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            if (item.tabId == tabId) {
                item.onDontResend()
                iterator.remove()
            }
        }
    }

    /** 挑一条展示；全部处理完时向 UI 发 null 关掉弹窗。 */
    private fun showNext() {
        if (shown == null) {
            while (true) {
                val next = pending.removeFirstOrNull() ?: break
                // 没人接弹窗（UI 还没接线 / 已销毁）：立即兑现，别让加载悬着。
                if (onPrompt == null) {
                    next.onDontResend()
                    continue
                }
                shown = next
                break
            }
        }
        onPrompt?.invoke(shown?.prompt)
    }

    private class Pending(
        val tabId: String,
        val prompt: FormResubmitPrompt,
        val onResend: () -> Unit,
        val onDontResend: () -> Unit,
    )

    private companion object {
        /** 待裁决上限。超过的直接按「不重新提交」处理。 */
        const val MAX_PENDING = 4
    }
}
