package com.ling.browser.web

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 页面控制台的一条错误记录（只收 ERROR 级别，用于「页面错误」面板）。 */
data class ConsoleEntry(
    val tabId: String,
    val message: String,
    val source: String,
    val line: Int,
)

/**
 * 页面控制台错误的收集与发布。
 *
 * 只做**内存**留存（每页最多 [MAX_PER_TAB] 条），新页导航即清空：
 * 它用于让用户看见"页面/脚本报了什么错"，不是调试器，不需要持久化。
 *
 * 从 [WebTabManager] 里拆出的独立簿记：数据按 tabId 分桶、对外只暴露
 * 打平后的快照，与 WebView 实例、标签状态都没有耦合。
 */
internal class ConsoleErrorStore {

    private val _errors = MutableStateFlow<List<ConsoleEntry>>(emptyList())

    /** 各标签页收集到的错误，供「页面错误」面板展示。 */
    val errors: StateFlow<List<ConsoleEntry>> = _errors.asStateFlow()

    /** tabId -> 该标签的 ERROR 消息。标签关闭时整桶丢弃，不逐条 remove。 */
    private val byTab = HashMap<String, MutableList<ConsoleEntry>>()

    /**
     * 记录一条 ERROR 级控制台消息。
     *
     * 同一个循环里反复报同一条错误（同消息、同行号）时只留第一条，避免刷屏。
     */
    fun record(tabId: String, message: String, source: String, line: Int) {
        if (message.isBlank()) return
        val list = byTab.getOrPut(tabId) { mutableListOf() }
        val last = list.lastOrNull()
        if (last != null && last.message == message && last.line == line) return
        list.add(ConsoleEntry(tabId, message.take(MAX_MESSAGE_CHARS), source, line))
        while (list.size > MAX_PER_TAB) list.removeAt(0)
        publish()
    }

    /** 清空某标签页的控制台错误（主框架导航 / 关闭标签时调用）。 */
    fun clear(tabId: String) {
        // 连桶一起丢掉：标签已经关了就没有再记的可能，
        // 留着空表只会让 byTab 随打开过的标签数无限增长。
        if (byTab.remove(tabId) == null) return
        publish()
    }

    /** 清空全部（UI 销毁时调用）。 */
    fun clearAll() {
        if (byTab.isEmpty()) return
        byTab.clear()
        publish()
    }

    private fun publish() {
        _errors.value = byTab.values.flatten()
    }

    private companion object {
        /** 每个标签页保留的错误条数。 */
        const val MAX_PER_TAB = 30

        /** 单条错误的展示长度，避免长堆栈把面板撑爆。 */
        const val MAX_MESSAGE_CHARS = 500
    }
}
