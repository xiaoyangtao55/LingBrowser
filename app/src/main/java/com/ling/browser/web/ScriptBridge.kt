package com.ling.browser.web

import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * [ScriptBridge] 对宿主（[WebTabManager]）的最小依赖。
 *
 * 用户脚本的句柄、注入记录与菜单都挂在**标签的附属簿记**（[TabSession]）上，
 * 与 WebView 实例表一起由宿主持有。桥只做"注入 / 记菜单 / 广播"，
 * 生命周期（建 session、关标签整份丢弃）仍由宿主决定 —— 这样
 * "关标签 = 一次 remove" 的不变量不会被拆散。
 */
internal interface ScriptBridgeHost {
    /** 取（必要时创建）标签的附属簿记。 */
    fun session(tabId: String): TabSession

    /** 只查不建：已关闭的标签不该因为一次菜单回调又复活一份簿记。 */
    fun findSession(tabId: String): TabSession?

    /** 全部现存簿记（发布菜单、退出时摘句柄用）。 */
    val allSessions: Collection<TabSession>

    /** 当前存活的 WebView。 */
    val webViews: Map<String, LingWebView>

    /** 该标签是否无痕 —— 无痕页一律不跑用户脚本。 */
    fun isIncognito(tabId: String): Boolean
}

/**
 * 用户脚本注入与 GM 桥。
 *
 * 从 [WebTabManager] 里拆出的功能簇：document-start 注册、按时机注入、
 * 菜单登记与执行、GM 值广播、通知回推。
 *
 * 与 WebView 生命周期相关的部分（建实例、销毁、进程崩溃恢复）仍留在宿主，
 * 这里只被宿主回调驱动，因此不持有 Handler 或上下文。
 */
internal class ScriptBridge(private val host: ScriptBridgeHost) {

    /**
     * 启用中的用户脚本快照（见 [com.ling.browser.data.repo.UserScriptRepository]）。
     *
     * 与订阅域名同理：web 层不认识仓库，由 UI 层经 [applyScripts] 注入。
     * 快照里的 `valuesJson` 是 `GM_getValue` 的只读初值，注入时随包装代码一起下发。
     */
    private var scripts: List<EnabledUserScript> = emptyList()

    private val _menus = MutableStateFlow<List<UserScriptMenuCommand>>(emptyList())

    /** 当前各标签页注册的脚本菜单命令。 */
    val menus: StateFlow<List<UserScriptMenuCommand>> = _menus.asStateFlow()

    /**
     * 系统 WebView 是否支持 document-start 注入。
     *
     * 不支持时 `@run-at document-start` 的脚本降级到 [ScriptRunAt.END]
     * （首帧已提交，是可达的最早时机）。特性检查本身在老系统上可能抛异常，
     * 因此包一层 runCatching，失败按"不支持"处理。
     */
    private val documentStartSupported: Boolean = runCatching {
        WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
    }.getOrDefault(false)

    /**
     * 注入启用中的用户脚本，并重新注册 document-start。
     *
     * 由 [com.ling.browser.ui.BrowserViewModel] 订阅
     * [com.ling.browser.data.repo.UserScriptRepository.enabledScripts] 后调用：
     * 导入 / 启停 / 删除都会让那个流发新值。
     *
     * 重注册只影响**下一次导航**：当前页已经跑过的脚本不会重跑（否则改一下开关
     * 就会在页面上叠一份副作用）。
     */
    fun applyScripts(scripts: List<EnabledUserScript>) {
        this.scripts = scripts
        host.webViews.forEach { (id, wv) -> registerDocumentStart(id, wv) }
        // 脚本被停用/删除后，它注册的菜单也不该再出现在界面里。
        pruneMenus(scripts.mapTo(HashSet()) { it.id })
    }

    /**
     * 给一个 WebView 注册全部 document-start 脚本。
     *
     * `addDocumentStartJavaScript` 是按 WebView 注册、对**后续每次导航**生效的，
     * 因此这里先摘掉旧句柄再按最新快照重注册。无痕标签整体跳过：脚本可能把
     * 数据经 GM 存储写进磁盘，那与"不留痕迹"直接冲突。
     */
    fun registerDocumentStart(tabId: String, wv: LingWebView) {
        val ses = host.session(tabId)
        ses.scriptHandlers.forEach { handler -> runCatching { handler.remove() } }
        ses.scriptHandlers.clear()
        if (!documentStartSupported) return

        val handlers = mutableListOf<ScriptHandler>()
        // 浏览器级"新标签"桥：与用户脚本无关，每个 WebView 都要挂。
        runCatching {
            handlers += WebViewCompat.addDocumentStartJavaScript(
                wv,
                TAB_LINK_JS,
                setOf("*"),
            )
        }
        if (scripts.isEmpty() || host.isIncognito(tabId)) {
            if (handlers.isNotEmpty()) ses.scriptHandlers.addAll(handlers)
            return
        }

        for (entry in scripts) {
            if (entry.script.runAt != ScriptRunAt.START) continue
            val origins = entry.script.allowedOrigins
            if (origins.isEmpty()) continue
            runCatching {
                handlers += WebViewCompat.addDocumentStartJavaScript(
                    wv,
                    scriptWrapper(entry.script, entry.id, entry.valuesJson, entry.requireCode, entry.resourcesJson),
                    origins,
                )
            }
        }
        if (handlers.isNotEmpty()) ses.scriptHandlers.addAll(handlers)
    }

    /**
     * 在某个时机把匹配当前 URL 的脚本注入页面。
     *
     * 每个"脚本 + 时机 + URL"只注入一次：`onPageCommitVisible` 与
     * `onPageFinished` 都可能对同一页触发多次，不设闸门会让脚本重复执行。
     * 闸门在 [onPageStarted] 清零（新一次主框架导航）。
     */
    fun injectScripts(
        tabId: String,
        wv: LingWebView,
        url: String,
        runAt: ScriptRunAt,
    ) {
        if (scripts.isEmpty()) return
        if (host.isIncognito(tabId)) return

        val injected = host.session(tabId).injectedScripts
        for (entry in scripts) {
            val actual = entry.script.runAt
            val due = actual == runAt ||
                // document-start 的脚本在 END 再兜一次：注册是按 WebView 提前做的，
                // 脚本在页面打开之后才导入、或注册被系统静默拒绝时，注册就不会生效，
                // 而 END 之外的时机不会补跑，表现为"脚本凭空消失"。
                // 是否真的需要补，由下面页面里的注入标记决定。
                (actual == ScriptRunAt.START && runAt == ScriptRunAt.END)
            if (!due) continue
            if (!entry.script.matches(url)) continue
            if (!injected.add("${entry.id}|$runAt|$url")) continue
            val code = scriptWrapper(
                entry.script,
                entry.id,
                entry.valuesJson,
                entry.requireCode,
                entry.resourcesJson,
            )
            // document-start 不可用时 START 直接降级到 END，无需探测；
            // 可用时先问页面"这份脚本跑过了吗"，避免同一页重复执行。
            if (actual == ScriptRunAt.START && documentStartSupported) {
                val probe = "!!(window.__lingGMInjected&&window.__lingGMInjected[${jsString(scriptMarker(entry.id))}])"
                wv.evaluateJavascript(probe) { seen ->
                    if (seen != "true") wv.evaluateJavascript(code, null)
                }
            } else {
                wv.evaluateJavascript(code, null)
            }
        }
    }

    /**
     * 新一次主框架导航：清空"本页已注入"闸门与旧文档的菜单命令。
     *
     * 闸门必须在这里清（不能放到 onPageFinished）—— 那会把同页的第二次回调
     * 当成新页；菜单的回调函数绑在旧文档上，导航后必然失效。
     */
    fun onPageStarted(tabId: String) {
        host.findSession(tabId)?.injectedScripts?.clear()
        clearMenus(tabId)
    }

    /**
     * 渲染进程崩溃：document-start 的注册句柄随实例失效。
     *
     * 只摘句柄与清注入记录，**保留整份 session** —— 标签本身还在，
     * 重建 WebView 后菜单/证书放行等簿记应当继续有效。
     */
    fun releaseRendererHandles(tabId: String) {
        val ses = host.findSession(tabId) ?: return
        ses.scriptHandlers.forEach { handler -> runCatching { handler.remove() } }
        ses.scriptHandlers.clear()
        ses.injectedScripts.clear()
    }

    /** 退出时摘掉所有 document-start 句柄（随后宿主整份丢弃 session）。 */
    fun releaseAllHandlers() {
        host.allSessions.forEach { ses ->
            ses.scriptHandlers.forEach { handler -> runCatching { handler.remove() } }
        }
    }

    // ---------------------------------------------------------------- 脚本菜单 / 跨页值同步

    /** 页面里 `GM_registerMenuCommand` 注册一条命令。 */
    fun registerMenu(
        tabId: String,
        scriptId: String,
        commandId: String,
        label: String,
        accessKey: String = "",
    ) {
        if (commandId.isEmpty()) return
        val clean = label.trim().ifEmpty { "未命名命令" }.take(MAX_MENU_LABEL)
        // 只接受单个字母/数字：accessKey 在桌面端是菜单的键盘助记键，这里存下来
        // 供「脚本菜单」面板展示并支持外接键盘直接触发。
        val key = accessKey.trim().take(1).uppercase()
            .takeIf { it.isNotEmpty() && it[0].isLetterOrDigit() } ?: ""
        host.session(tabId).menus[menuKey(scriptId, commandId)] =
            UserScriptMenuCommand(tabId, scriptId, commandId, clean, key)
        publishMenus()
    }

    /** 页面里 `GM_unregisterMenuCommand` 移除一条命令。 */
    fun unregisterMenu(tabId: String, scriptId: String, commandId: String) {
        val map = host.findSession(tabId)?.menus ?: return
        if (map.remove(menuKey(scriptId, commandId)) == null) return
        publishMenus()
    }

    /** 主框架导航 / 关标签时清空该页的命令：回调函数已经随旧文档销毁。 */
    fun clearMenus(tabId: String) {
        val menus = host.findSession(tabId)?.menus ?: return
        menus.clear()
        publishMenus()
    }

    /** 脚本被停用或删除时，摘掉它留下的命令。 */
    fun pruneMenus(enabledIds: Set<String>) {
        var changed = false
        for (s in host.allSessions) {
            if (s.menus.values.removeAll { it.scriptId !in enabledIds }) changed = true
        }
        if (changed) publishMenus()
    }

    /**
     * 执行一条菜单命令。
     *
     * 只查原生侧登记过的命令，再把命令 id 交给页面自己的分发器 —— 原生从不
     * 拼接脚本体，避免把任意字符串送进 `evaluateJavascript`。
     */
    fun invokeMenu(tabId: String, scriptId: String, commandId: String): Boolean {
        val registered = host.findSession(tabId)?.menus?.containsKey(menuKey(scriptId, commandId)) == true
        val wv = host.webViews[tabId]
        if (!registered || wv == null) return false
        wv.evaluateJavascript(
            "window.__lingGMMenu&&window.__lingGMMenu(${jsString(scriptMarker(scriptId))},${jsString(commandId)})",
            null,
        )
        return true
    }

    /**
     * 把 GM 值的变更广播给**其它**标签页。
     *
     * 只发消息不落盘：[com.ling.browser.data.repo.UserScriptRepository] 已经写过了。
     * 发起页自己不需要广播 —— 它的 `V` 与监听器在 `GM_setValue` 里已同步更新。
     */
    fun broadcastValue(
        scriptId: String,
        key: String,
        oldValue: Any?,
        newValue: Any?,
        exceptTabId: String?,
    ) {
        val views = host.webViews
        if (views.isEmpty()) return
        val js = "window.__lingGMValue&&window.__lingGMValue(" +
            "${jsString(scriptMarker(scriptId))},${jsString(key)},${jsLiteral(oldValue)},${jsLiteral(newValue)},true)"
        for ((tabId, wv) in views) {
            if (tabId == exceptTabId) continue
            if (host.isIncognito(tabId)) continue
            wv.evaluateJavascript(js, null)
        }
    }

    /** 通知点击回传页面里的 `onclick`（详情见 [com.ling.browser.ui.BrowserViewModel.postUserScriptNotification]）。 */
    fun dispatchNotificationClick(tabId: String, scriptId: String, notificationId: String) {
        val wv = host.webViews[tabId] ?: return
        wv.evaluateJavascript(
            "window.__lingGMNotice&&window.__lingGMNotice(${jsString(scriptMarker(scriptId))},${jsString(notificationId)},'click')",
            null,
        )
    }

    /**
     * 通知被点击后回传 `ondone`。
     *
     * Tampermonkey 里 `ondone` 在通知关闭时触发；Android 无法可靠监听"划掉通知"，
     * 因此在点击（必然伴随通知收起）时补一次 `done=true` —— 覆盖绝大多数用法。
     */
    fun dispatchNotificationDone(tabId: String, scriptId: String, notificationId: String) {
        val wv = host.webViews[tabId] ?: return
        wv.evaluateJavascript(
            "window.__lingGMNotice&&window.__lingGMNotice(${jsString(scriptMarker(scriptId))},${jsString(notificationId)},'done')",
            null,
        )
    }

    /** 把各标签的菜单摊平成 UI 需要的列表（顺序稳定，便于 Compose diff）。 */
    fun publishMenus() {
        _menus.value = host.allSessions
            .mapNotNull { it.menus.takeIf { m -> m.isNotEmpty() } }
            .flatMap { it.values }
            .sortedWith(compareBy({ it.tabId }, { it.scriptId }))
    }

    private fun menuKey(scriptId: String, commandId: String): String = "$scriptId|$commandId"

    /** null / JSON null 都按 JS 的 `undefined` 传，与 Tampermonkey 的删除语义一致。 */
    private fun jsLiteral(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "undefined"
        is String -> jsString(value)
        is Number, is Boolean -> value.toString()
        else -> runCatching { JSONObject.wrap(value)?.toString() }.getOrNull() ?: "undefined"
    }

    private companion object {
        /**
         * 页面里拦截 `target="_blank"` 点击的脚本。
         *
         * 只拦**可信点击**（`isTrusted`，合成事件不算）与 http(s) 链接，
         * 并且四个修饰键都没按 —— 与桌面浏览器"普通左键打开新标签"一致。
         * 桥不存在时直接放行，让页面保持原生的（无反应）行为，不至于
         * 因为宿主缺能力而把链接彻底拦死。
         */
        private val TAB_LINK_JS = """
            (function () {
              if (window.__lingTabLink) return;
              window.__lingTabLink = 1;
              document.addEventListener('click', function (e) {
                if (!e.isTrusted || e.button !== 0 || e.defaultPrevented) return;
                if (e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
                var node = e.target;
                var a = node && node.closest ? node.closest('a[target]') : null;
                if (!a) return;
                if ((a.target || '').toLowerCase() !== '_blank') return;
                var href = a.href || '';
                if (!/^https?:/i.test(href)) return;
                var bridge = window.LingTab;
                if (!bridge) return;
                e.preventDefault();
                try { bridge.postMessage(href); } catch (err) {}
              }, true);
            })();
        """.trimIndent()

        /** 菜单文案上限：脚本可能塞进整段说明，界面行撑不下。 */
        private const val MAX_MENU_LABEL = 60
    }
}
