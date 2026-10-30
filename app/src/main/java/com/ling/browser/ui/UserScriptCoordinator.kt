package com.ling.browser.ui

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.webkit.CookieManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.ling.browser.MainActivity
import com.ling.browser.R
import com.ling.browser.data.db.DownloadStatus
import com.ling.browser.data.repo.DownloadRepository
import com.ling.browser.data.repo.ScriptImportResult
import com.ling.browser.data.repo.ScriptPreview
import com.ling.browser.data.repo.UserScriptRepository
import com.ling.browser.data.repo.ValueWrite
import com.ling.browser.download.ConflictAction
import com.ling.browser.web.ScriptHttpRequest
import com.ling.browser.web.ScriptHttpResponse
import com.ling.browser.web.ScriptHttpTooLargeException
import com.ling.browser.web.TabState
import com.ling.browser.web.connectAllowed
import com.ling.browser.web.jsString
import com.ling.browser.web.scriptMarker
import java.io.ByteArrayOutputStream
import java.net.SocketTimeoutException
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 用户脚本域对宿主（[BrowserViewModel]）的最小依赖。
 *
 * 脚本的安装、GM 桥、菜单、通知与下载都要落到标签页与 UI 提示上，但
 * 这些能力（收发标签、地址栏同步、持久化仓库）都由 ViewModel 持有，
 * 因此只按需暴露几样事实与副作用。
 */
internal interface UserScriptHost {
    val app: Application
    val scope: CoroutineScope
    val userScripts: UserScriptRepository
    val downloads: DownloadRepository
    val tabs: StateFlow<List<TabState>>

    fun newTab(url: String, incognito: Boolean, select: Boolean, position: Int?): TabState
    fun closeTab(id: String)
    fun evalInTab(id: String, js: String)

    fun registerUserScriptMenu(
        tabId: String,
        scriptId: String,
        commandId: String,
        label: String,
        accessKey: String,
    )

    fun unregisterUserScriptMenu(tabId: String, scriptId: String, commandId: String)
    fun invokeUserScriptMenu(tabId: String, scriptId: String, commandId: String)
    fun broadcastUserScriptValue(
        scriptId: String,
        key: String,
        oldValue: Any?,
        newValue: Any?,
        exceptTabId: String?,
    )

    fun dispatchUserScriptNotificationClick(tabId: String, scriptId: String, notificationId: String)
    fun dispatchUserScriptNotificationDone(tabId: String, scriptId: String, notificationId: String)

    /** 借既有的权限申请通道（Android 13+ 通知权限）。 */
    fun requestNotificationPermission()

    /** 下载前按设置判断是否需要通知权限，需要就发申请信号。 */
    fun maybeRequestNotificationPermission()

    /** 一次性提示消息。 */
    fun showMessage(text: String)

    /** 把地址栏文本拉回当前页面（取消安装链接后调用）。 */
    fun syncAddressFromActiveTab()
}

/**
 * 用户脚本域：安装/启停/删除、GM 桥（值表、跨页同步、通知、跨域请求、下载）、
 * 脚本菜单与安装确认。
 *
 * 从 [BrowserViewModel] 里拆出的功能簇。标签页本身仍由宿主管理，
 * 这里只在回调里转调；「关标签 = 丢弃该标签的 GM 簿记」这一不变量
 * 由宿主在 `onTabClosed` 里调用 [onTabClosed] 维持。
 */
internal class UserScriptCoordinator(private val host: UserScriptHost) {

    /**
     * 待确认的脚本安装链接（`.user.js`）。
     *
     * 链接本身不加载，先由 UI 弹确认框；确认后走 [importFromUrl]，
     * 与在管理页里粘贴网址是**同一条**导入路径（同样有大小/条数上限与解析校验）。
     *
     * [PendingUserScript.preview] 是弹窗期间顺带拉回来的元数据：用户最关心的
     * 是"它会跑在哪些网站"，只给文件名等于让人盲签。
     */
    private val _pendingInstall = MutableStateFlow<PendingUserScript?>(null)
    val pendingInstall: StateFlow<PendingUserScript?> = _pendingInstall.asStateFlow()

    /**
     * `GM_download({ saveAs: true })` 等待用户挑保存位置。
     *
     * 非 null 时 UI 拉起系统文件选择器（`ACTION_CREATE_DOCUMENT`），结果通过
     * [completeSaveAs] 回传。同一时刻只允许一个：选择器是模态的，
     * 叠两个请求会分不清结果属于谁。
     */
    private val _saveAs = MutableStateFlow<ScriptSaveAsRequest?>(null)
    val saveAs: StateFlow<ScriptSaveAsRequest?> = _saveAs.asStateFlow()

    // ------------------------------------------------------------ 安装 / 管理

    /**
     * 从网址导入用户脚本。
     *
     * 导入会拉网络、解析元数据头，结果只能经提示消息回报 ——
     * 静默完成会让用户以为"点了没反应"。
     */
    fun importFromUrl(url: String) = host.scope.launch {
        reportImport(host.userScripts.importFromUrl(url))
    }

    /** 从本地文件导入。用 SAF 选文件，**不需要**存储权限。 */
    fun importFromLocal(uri: Uri) = host.scope.launch {
        reportImport(host.userScripts.importFromLocal(uri))
    }

    /** 重新拉取网址脚本。本地导入的没有远端，仓库会拒绝并给出原因。 */
    fun refresh(id: String) = host.scope.launch {
        reportImport(host.userScripts.refresh(id), verb = "更新")
    }

    /** 启用 / 停用。停用只是不再注入，脚本源码与值表都保留。 */
    fun setEnabled(id: String, enabled: Boolean) = host.scope.launch {
        host.userScripts.setEnabled(id, enabled)
    }

    /** 删除脚本（连同源码、元信息与 GM 值表）。 */
    fun remove(id: String) = host.scope.launch { host.userScripts.remove(id) }

    private fun reportImport(result: ScriptImportResult, verb: String = "导入") {
        host.showMessage(
            when (result) {
                is ScriptImportResult.Ok -> buildString {
                    val entry = result.entry
                    append("已$verb「${entry.script.name}」")
                    if (entry.script.version.isNotBlank()) append(" v${entry.script.version}")
                    append("，匹配 ${entry.script.patternLabels.size} 条规则")
                }
                is ScriptImportResult.Failed -> "${verb}失败：${result.reason}"
            },
        )
    }

    /**
     * 弹安装确认，并在后台把元数据头拉回来。
     *
     * 拉取是并行的：确认框立刻出现，拿到 `@match` 后再回填。回填前要核对
     * 弹窗仍停在同一个地址 —— 用户可能已经取消、或者又点了另一个链接。
     */
    fun promptInstall(url: String) {
        _pendingInstall.value = PendingUserScript(url)
        host.scope.launch {
            val preview = runCatching { host.userScripts.preview(url) }
                .getOrElse { ScriptPreview(error = it.message ?: "读取脚本信息失败") }
            val current = _pendingInstall.value ?: return@launch
            if (current.url != url) return@launch
            _pendingInstall.value = current.copy(loading = false, preview = preview)
        }
    }

    /** 确认安装待确认的脚本链接。 */
    fun confirmInstall() {
        val pending = _pendingInstall.value ?: return
        _pendingInstall.value = null
        importFromUrl(pending.url)
    }

    /**
     * 放弃安装。
     *
     * 链接可能来自地址栏提交或页面点击，导航都已被取消 —— 取消后要把地址栏
     * 文本拉回当前页面，否则它会一直显示那个永远不会加载的 `.user.js` 地址。
     */
    fun dismissInstall() {
        if (_pendingInstall.value == null) return
        _pendingInstall.value = null
        host.syncAddressFromActiveTab()
    }

    // ------------------------------------------------------------ GM 桥

    /** GM_xmlhttpRequest 的在途请求：只有存着 tabId / 脚本 id 才能把结果送回页面。 */
    private class RunningRequest(
        val tabId: String,
        val scriptId: String,
        val job: Job,
        val request: ScriptHttpRequest,
    )

    private val scriptRequests = HashMap<String, RunningRequest>()

    /**
     * 一个由脚本打开的标签。
     *
     * [originTabId] 是发起 `GM_openInTab` 的那一页 —— `onclose` 回调要送回那里，
     * 因为回调函数只存在于那个页面的 JS 环境里。`close()` 与 `onclose` 都只认
     * 原生自己登记的 requestId，页面拿 id 关不掉别人的标签。
     */
    private class ScriptTabHandle(
        val tabId: String,
        val originTabId: String,
        val scriptId: String,
        /** 脚本是否声明了 `onclose`；没有就不用留着簿记等关闭。 */
        val notifyClose: Boolean,
    )

    /** `GM_openInTab`：requestId -> 打开出去的标签。 */
    private val scriptTabs = LinkedHashMap<String, ScriptTabHandle>()

    /** `GM_download`：requestId -> 下载任务，用于 abort 与终态去重。 */
    private class ScriptDownloadHandle(
        val id: Long,
        val tabId: String,
        val scriptId: String,
    )

    private val scriptDownloads = HashMap<String, ScriptDownloadHandle>()

    /**
     * `GM_getTab` / `GM_saveTab` 的**按标签**内存态：tabId -> 数据。
     *
     * 与 `GM_setValue` 不同，它不落盘、也不跨标签广播：同一标签页里的所有脚本
     * 共享这一份，标签关掉就随之丢弃（见 [onTabClosed]）。
     */
    private val scriptTabData = HashMap<String, JSONObject>()

    companion object {
        /** 同时进行的脚本请求上限：跨域请求不受 CORS 约束，需要自己兜一道。 */
        private const val MAX_SCRIPT_REQUESTS = 8
        private const val DEFAULT_SCRIPT_TIMEOUT_MS = 30_000
        private const val MIN_SCRIPT_TIMEOUT_MS = 1_000
        private const val MAX_SCRIPT_TIMEOUT_MS = 120_000

        /** `GM_openInTab` 记下的标签数上限，避免脚本无限开标签把表撑大。 */
        private const val MAX_SCRIPT_TABS = 32

        /** `GM_xmlhttpRequest` 进度事件的最小间隔（字节），避免逐块刷屏。 */
        private const val PROGRESS_STEP_BYTES = 64L * 1024L

        /** `GM_notification` 的通知渠道与点击回传用的 extras。 */
        const val SCRIPT_CHANNEL_ID = "userscript"
        const val SCRIPT_SILENT_CHANNEL_ID = "userscript_silent"
        const val EXTRA_SCRIPT_TAB = "ling.gm.tab"
        const val EXTRA_SCRIPT_ID = "ling.gm.script"
        const val EXTRA_SCRIPT_NOTICE = "ling.gm.notice"
        const val EXTRA_SCRIPT_NOTICE_URL = "ling.gm.notice.url"

        /** 通知大图的上限：超过就放弃，免得为一条通知冒 OOM 风险。 */
        private const val MAX_NOTIFICATION_ICON_BYTES = 2 * 1024 * 1024
    }

    /**
     * 处理页面里 GM 垫片发来的消息。
     *
     * 消息是页面 JS 构造的，因此**不可信**：消息里的 `s` 是脚本 id 的摘要，
     * 只能按摘要反查"存在且启用"的脚本（见 [scriptMarker]），查不到直接丢弃。
     * 非法 JSON 同样丢弃。写入只进该脚本自己的值文件，不碰任何其它状态。
     */
    fun handleMessage(tabId: String, raw: String) {
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return
        val marker = obj.optString("s")
        if (marker.isEmpty()) return
        // 桥对象对整个页面可见，消息本身不可信：摘要必须对得上一个存在且启用的脚本。
        // 这一道同时挡住"已停用脚本继续开标签 / 读剪贴板 / 发通知"。
        val scriptId = host.userScripts.enabledScripts.value
            .firstOrNull { scriptMarker(it.id) == marker }?.id ?: return
        when (obj.optString("t")) {
            "set" -> {
                val key = obj.optString("k")
                val value = obj.opt("v")
                host.scope.launch {
                    val result = host.userScripts.setValue(scriptId, key, value)
                    if (result is ValueWrite.Changed) {
                        // 其它标签页里的同名脚本要同步看到新值（remote=true）。
                        host.broadcastUserScriptValue(scriptId, key, result.old, value, tabId)
                    }
                }
            }
            "open" -> openScriptTab(tabId, scriptId, obj)
            "tabClose" -> scriptTabs.remove(obj.optString("id"))?.let { host.closeTab(it.tabId) }
            // `window.close()`：只关脚本自己开出来的标签。用户手动打开的标签
            // 在浏览器里也不允许脚本关掉，这里同样拒绝。
            "winClose" -> if (scriptTabs.values.any { it.tabId == tabId }) {
                host.closeTab(tabId)
            }
            "tabGet" -> deliverScriptEvent(
                tabId, scriptId, obj.optString("id"), "tab",
                scriptTabData[tabId] ?: JSONObject(),
            )
            "tabSave" -> {
                scriptTabData[tabId] = obj.optJSONObject("v") ?: JSONObject()
            }
            "tabsGet" -> {
                // 按标签 id 分表，键就是其它标签能看到的标识。
                val all = JSONObject()
                for (t in host.tabs.value) {
                    all.put(t.id, scriptTabData[t.id] ?: JSONObject())
                }
                deliverScriptEvent(tabId, scriptId, obj.optString("id"), "tab", all)
            }
            "del" -> {
                val key = obj.optString("k")
                host.scope.launch {
                    val result = host.userScripts.deleteValue(scriptId, key)
                    if (result is ValueWrite.Changed) {
                        host.broadcastUserScriptValue(scriptId, key, result.old, null, tabId)
                    }
                }
            }
            "clip" -> {
                val text = obj.optString("v")
                if (text.isNotEmpty()) {
                    // type 缺省是纯文本；'html' / 'text/html' 走富文本剪贴板。
                    val type = obj.optString("ty", "text")
                    val html = type.equals("html", true) || type.contains("html", true)
                    // 消息来自 UI 线程（WebMessageListener 的契约），可以直接碰剪贴板。
                    runCatching {
                        val ctx = host.app
                        val clip = if (html) {
                            ClipData.newHtmlText("翎 · 用户脚本", text, text)
                        } else {
                            ClipData.newPlainText("翎 · 用户脚本", text)
                        }
                        ctx.getSystemService(ClipboardManager::class.java)
                            ?.setPrimaryClip(clip)
                    }
                }
            }
            "menu" -> host.registerUserScriptMenu(
                tabId,
                scriptId,
                obj.optString("id"),
                obj.optString("n"),
                obj.optString("ak"),
            )
            "menuDel" -> host.unregisterUserScriptMenu(tabId, scriptId, obj.optString("id"))
            "notice" -> postNotification(tabId, scriptId, obj)
            "xhr" -> startScriptRequest(tabId, scriptId, obj)
            "xa" -> abortScriptRequest(obj.optString("id"))
            "dl" -> startScriptDownload(tabId, scriptId, obj)
            "dlAbort" -> abortScriptDownload(obj.optString("id"))
        }
    }

    /**
     * 触发一条 `GM_registerMenuCommand`。
     *
     * 命令是否还属于当前页由 [com.ling.browser.web.WebTabManager] 二次校验：
     * UI 拿到列表到用户点击之间可能已经导航，那里查不到命令就直接放弃。
     */
    fun invokeMenu(tabId: String, scriptId: String, commandId: String) {
        host.invokeUserScriptMenu(tabId, scriptId, commandId)
    }

    /**
     * 通知点击后回到页面触发 `details.onclick`。
     *
     * [MainActivity] 从 PendingIntent 的 extras 里读出三个 id 后调用这里。
     * 标签页已经关闭或页面已导航时静默丢弃 —— 回调函数早就没了。
     */
    fun onNotificationClick(
        tabId: String,
        scriptId: String,
        notificationId: String,
        url: String? = null,
    ) {
        // details.url 表示"点通知就打开这个地址"；没有 url 才是纯回调。
        if (!url.isNullOrBlank()) {
            val scheme = runCatching { Uri.parse(url).scheme?.lowercase(Locale.ROOT) }.getOrNull()
            if (scheme == "http" || scheme == "https") {
                val incognito = host.tabs.value.firstOrNull { it.id == tabId }?.isIncognito == true
                host.newTab(url, incognito, select = true, position = null)
            }
        }
        host.dispatchUserScriptNotificationClick(tabId, scriptId, notificationId)
        host.dispatchUserScriptNotificationDone(tabId, scriptId, notificationId)
    }

    /**
     * 脚本开的标签被关闭：把 `onclose` 送回发起页，并清掉簿记。
     *
     * 脚本自己调 `close()` 时映射已被提前摘除，这里查不到 —— 符合 Tampermonkey
     * 的语义（自己关掉不算"被动关闭"）。GM_getTab 的数据只在该标签存活期间有意义。
     */
    fun onTabClosed(closedTabId: String) {
        val stale = scriptTabs.filterValues { it.tabId == closedTabId }
        if (stale.isEmpty()) {
            // GM_getTab 的数据只在该标签存活期间有意义。
            scriptTabData.remove(closedTabId)
            return
        }
        for ((requestId, handle) in stale) {
            scriptTabs.remove(requestId)
            if (handle.notifyClose) {
                deliverScriptEvent(
                    handle.originTabId,
                    handle.scriptId,
                    requestId,
                    "close",
                    JSONObject().put("tabId", closedTabId),
                )
            }
        }
        scriptTabData.remove(closedTabId)
    }

    /**
     * `GM_openInTab`：按 options 打开新标签。
     *
     * `active=false` 时只建标签、开始加载，不切过去（后台打开）。
     * 页面给的 id 由原生记下来，只用于 `handle.close()` 与 onload 回调 ——
     * 映射表之外的值一律忽略，避免成为"任意页面关任意标签"的入口。
     */
    private fun openScriptTab(tabId: String, scriptId: String, details: JSONObject) {
        val requestId = details.optString("id")
        val url = details.optString("u")
        val scheme = runCatching { Uri.parse(url).scheme?.lowercase(Locale.ROOT) }.getOrNull()
        if (scheme != "http" && scheme != "https") {
            if (requestId.isNotEmpty()) {
                deliverScriptEvent(
                    tabId, scriptId, requestId, "error",
                    scriptFailurePayload("只支持 http(s) 地址"),
                )
            }
            return
        }
        // insert：0 = 末尾（默认），1 = 当前标签之后，>1 按 1 起算的位置。
        val insert = details.optInt("ins", 0)
        val originIndex = host.tabs.value.indexOfFirst { it.id == tabId }
        val position = when {
            insert == 1 && originIndex >= 0 -> originIndex + 1
            insert > 1 -> insert - 1
            else -> null
        }
        val tab = host.newTab(
            url = url,
            incognito = details.optBoolean("inc", false),
            select = details.optBoolean("active", true),
            position = position,
        )
        if (requestId.isEmpty()) return
        scriptTabs[requestId] = ScriptTabHandle(
            tabId = tab.id,
            originTabId = tabId,
            scriptId = scriptId,
            notifyClose = details.optBoolean("oc", false),
        )
        while (scriptTabs.size > MAX_SCRIPT_TABS) scriptTabs.remove(scriptTabs.keys.first())
        deliverScriptEvent(tabId, scriptId, requestId, "load", JSONObject().put("tabId", tab.id))
    }

    /**
     * `GM_notification`：发一条系统通知。
     *
     * 权限被拒时退化成 Toast：什么提示都没有比"少了一条通知"更难排查。
     * 通知点击走 [MainActivity]（singleTask），由 Intent extras 找回标签与脚本。
     */
    private fun postNotification(tabId: String, scriptId: String, details: JSONObject) {
        val notificationId = details.optString("id").takeIf { it.isNotEmpty() } ?: return
        val entry = host.userScripts.enabledScripts.value.firstOrNull { it.id == scriptId }
            ?: return
        val title = details.optString("title").ifBlank { entry.script.name }
        val text = details.optString("text")
        val ctx = host.app

        val permitted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            androidx.core.content.ContextCompat.checkSelfPermission(
                ctx,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!permitted) {
            // 借既有的权限申请通道：用户授权后，脚本下一次通知就能正常显示。
            host.requestNotificationPermission()
            Toast.makeText(ctx, listOf(title, text).filter { it.isNotBlank() }.joinToString("："), Toast.LENGTH_LONG).show()
            return
        }

        ensureChannel(ctx)
        // 通知 id 只在单个脚本内自增，不同脚本会撞成同一个 "n1"；
        // 拼上脚本 id 再取哈希，才能让 PendingIntent 与通知槽互不覆盖。
        val notifyId = "$scriptId|$notificationId".hashCode()
        val clickUrl = details.optString("u")
        val intent = Intent(ctx, MainActivity::class.java).apply {
            putExtra(EXTRA_SCRIPT_TAB, tabId)
            putExtra(EXTRA_SCRIPT_ID, scriptId)
            putExtra(EXTRA_SCRIPT_NOTICE, notificationId)
            if (clickUrl.isNotBlank()) putExtra(EXTRA_SCRIPT_NOTICE_URL, clickUrl)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pending = PendingIntent.getActivity(
            ctx,
            notifyId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // 静默必须换一个低优先级渠道：Android 8+ 的提示音由渠道决定，
        // 单靠 setSilent 在默认渠道上压不住声音。
        val silent = details.optBoolean("silent", false)
        val channel = if (silent) SCRIPT_SILENT_CHANNEL_ID else SCRIPT_CHANNEL_ID
        val builder = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(pending)
        // timeout：到点自动收起，脚本不用自己记着删。
        details.optLong("to", 0L).takeIf { it > 0 }?.let { builder.setTimeoutAfter(it) }
        if (silent) builder.setSilent(true)

        fun show(b: NotificationCompat.Builder) {
            runCatching {
                NotificationManagerCompat.from(ctx).notify(notifyId, b.build())
            }.onFailure {
                Toast.makeText(ctx, "$title：$text", Toast.LENGTH_LONG).show()
            }
        }
        show(builder)

        // image 是网络地址（少数脚本用 data: URL）：先发文字版，图解码好再原地替换，
        // 避免网络慢时整条通知都不出现。
        val imageUrl = details.optString("img")
        if (imageUrl.isNotBlank()) {
            host.scope.launch {
                val icon = loadNotificationImage(imageUrl) ?: return@launch
                show(builder.setLargeIcon(icon))
            }
        }
    }

    /**
     * 拉取 `GM_notification` 的图片。
     *
     * 上限 2 MiB：解码整张图很吃内存，不值得为一条通知冒 OOM 的险；
     * 超限就当没有图，通知照常显示。
     */
    private suspend fun loadNotificationImage(raw: String): android.graphics.Bitmap? =
        withContext(Dispatchers.IO) {
            runCatching {
                val bytes = if (raw.startsWith("data:", ignoreCase = true)) {
                    val comma = raw.indexOf(',')
                    if (comma < 0) return@runCatching null
                    android.util.Base64.decode(raw.substring(comma + 1), android.util.Base64.DEFAULT)
                } else {
                    val conn = java.net.URL(raw).openConnection()
                    conn.connectTimeout = 5_000
                    conn.readTimeout = 5_000
                    conn.getInputStream().use { input ->
                        val out = ByteArrayOutputStream()
                        val buf = ByteArray(8192)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            if (out.size() + n > MAX_NOTIFICATION_ICON_BYTES) return@use null
                            out.write(buf, 0, n)
                        }
                        out.toByteArray()
                    }
                } ?: return@runCatching null
                if (bytes.size > MAX_NOTIFICATION_ICON_BYTES) return@runCatching null
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
        }

    private fun ensureChannel(ctx: Application) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(SCRIPT_CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    SCRIPT_CHANNEL_ID,
                    "用户脚本通知",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "由 GM_notification 发出的通知"
                },
            )
        }
        // 静默通知单独一个低优先级渠道：IMPORTANCE_LOW 才真的不响铃、不弹横幅。
        if (nm.getNotificationChannel(SCRIPT_SILENT_CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    SCRIPT_SILENT_CHANNEL_ID,
                    "用户脚本静默通知",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "GM_notification({ silent: true }) 发出的通知"
                    setSound(null, null)
                    enableVibration(false)
                },
            )
        }
    }

    /**
     * 发起一次 `GM_xmlhttpRequest`。
     *
     * 与页面自己的 `fetch` 的关键差别是不受 CORS 限制，因此这里**必须**先确认
     * 脚本仍然存在且启用 —— 桥对象暴露给整个页面，不能让任意网页借它发跨域请求。
     * Cookie 从 [CookieManager] 取，与页面共享登录态（Tampermonkey 默认行为）。
     */
    private fun startScriptRequest(tabId: String, scriptId: String, details: JSONObject) {
        val requestId = details.optString("id").takeIf { it.isNotEmpty() } ?: return
        val script = host.userScripts.enabledScripts.value.firstOrNull { it.id == scriptId }
            ?: return
        // 当前标签正在展示的页面。GM 桥对整个页面可见，光有脚本身份还不够：
        // 页面可以在自己的上下文里冒用该脚本发消息。
        val pageUrl = host.tabs.value.firstOrNull { it.id == tabId }?.url

        fun fail(message: String) {
            deliverScriptEvent(tabId, scriptId, requestId, "error", scriptFailurePayload(message))
        }

        // ★ 脚本必须**匹配当前页面**才允许借用它的身份发请求。
        // 少了这道校验，任意网页只要拿到某脚本的身份，就能让原生侧带着用户在该
        // 目标站点的真实 Cookie 发起跨域请求，响应再回传页面 —— 等同于浏览器
        // 层面的 CSRF + 凭据窃取。有了它，页面最多只能借用"本来就该在这个站点
        // 运行"的脚本身份，跨站冒充被挡住。
        if (pageUrl == null || !script.script.matches(pageUrl)) {
            fail("脚本未匹配当前页面，拒绝发起请求")
            return
        }

        if (scriptRequests.size >= MAX_SCRIPT_REQUESTS) {
            fail("并发请求过多")
            return
        }
        val url = details.optString("u")
        val uri = Uri.parse(url)
        val scheme = runCatching { uri.scheme?.lowercase(Locale.ROOT) }.getOrNull()
        if (scheme != "http" && scheme != "https") {
            fail("只支持 http(s) 请求")
            return
        }
        // @connect 是作者声明的目标白名单。没有声明时放行（Tampermonkey 会弹窗
        // 询问，我们没有逐请求确认 UI，静默拒绝会让老脚本全部失效）。
        val targetHost = uri.host?.lowercase(Locale.ROOT)
        val pageHost = pageUrl
            ?.let { runCatching { Uri.parse(it).host?.lowercase(Locale.ROOT) }.getOrNull() }
        if (!connectAllowed(script.script.connects, targetHost, pageHost)) {
            fail("@connect 未授权访问 $targetHost")
            return
        }

        val method = details.optString("m", "GET").uppercase(Locale.ROOT)
        val headers = LinkedHashMap<String, String>()
        details.optJSONObject("h")?.let { h ->
            for (key in h.keys()) headers[key] = h.optString(key)
        }
        val rawData = details.opt("d")
        val hasBody = rawData != null && rawData !== JSONObject.NULL
        // 对象/数组形式的数据按 JSON 发送，与 Tampermonkey 的处理一致。
        val isJsonBody = hasBody && rawData !is String
        if (isJsonBody && headers.keys.none { it.equals("Content-Type", ignoreCase = true) }) {
            headers["Content-Type"] = "application/json; charset=utf-8"
        }
        val body = when {
            !hasBody -> null
            rawData is String -> rawData.toByteArray(Charsets.UTF_8)
            else -> rawData.toString().toByteArray(Charsets.UTF_8)
        }
        val timeout = details.optInt("to", DEFAULT_SCRIPT_TIMEOUT_MS)
            .coerceIn(MIN_SCRIPT_TIMEOUT_MS, MAX_SCRIPT_TIMEOUT_MS)
        val responseType = details.optString("rt")

        // 已在 UI 线程（WebMessageListener 回调），CookieManager 可以安全调用。
        val cookie = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
        // 进度从 IO 线程来，evalInTab 必须在主线程：这里按 64 KiB 节流后再切线程，
        // 避免 2 MiB 响应把 dispatch 队列刷爆。
        var lastReported = 0L
        val request = ScriptHttpRequest(url, method, headers, body, timeout, cookie) { received, total ->
            if (received - lastReported >= PROGRESS_STEP_BYTES || received == total) {
                lastReported = received
                host.scope.launch(Dispatchers.Main) {
                    if (scriptRequests[requestId] != null) {
                        deliverScriptEvent(
                            tabId,
                            scriptId,
                            requestId,
                            "progress",
                            JSONObject()
                                .put("loaded", received)
                                .put("total", total)
                                .put("lengthComputable", total > 0),
                        )
                    }
                }
            }
        }

        val job = host.scope.launch {
            try {
                val response = withContext(Dispatchers.IO) { request.execute() }
                withContext(Dispatchers.Main) {
                    // 脚本请求拿到的 Set-Cookie 也要落回 WebView 的 CookieStore，
                    // 否则"用 GM_xhr 登录、页面自己刷新"这类流程会丢会话。
                    for (value in response.setCookies) {
                        runCatching { CookieManager.getInstance().setCookie(response.finalUrl, value) }
                    }
                    if (scriptRequests[requestId] != null) {
                        deliverScriptEvent(
                            tabId,
                            scriptId,
                            requestId,
                            "load",
                            scriptResponsePayload(response, responseType),
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ScriptHttpTooLargeException) {
                if (scriptRequests[requestId] != null) fail("响应体超过 2 MiB")
            } catch (e: SocketTimeoutException) {
                if (scriptRequests[requestId] != null) {
                    deliverScriptEvent(
                        tabId,
                        scriptId,
                        requestId,
                        "timeout",
                        scriptFailurePayload("请求超时"),
                    )
                }
            } catch (e: Exception) {
                if (scriptRequests[requestId] != null) fail(e.message ?: "请求失败")
            } finally {
                scriptRequests.remove(requestId)
            }
        }
        scriptRequests[requestId] = RunningRequest(tabId, scriptId, job, request)
    }

    /** 中止在途请求：先断开 socket，再取消协程，最后把 abort 事件交给脚本。 */
    private fun abortScriptRequest(requestId: String) {
        val running = scriptRequests.remove(requestId) ?: return
        running.request.abort()
        running.job.cancel()
        deliverScriptEvent(
            running.tabId,
            running.scriptId,
            requestId,
            "abort",
            scriptFailurePayload("已中止"),
        )
    }

    private fun scriptFailurePayload(message: String): JSONObject = JSONObject().apply {
        put("readyState", 4)
        put("status", 0)
        put("statusText", "")
        put("finalUrl", "")
        put("responseHeaders", "")
        put("responseText", "")
        put("error", message)
    }

    /**
     * `GM_download`：直接进下载队列，不再弹浏览器自己的确认框。
     *
     * 与 Tampermonkey 对齐：脚本已经明确表达了下载意图，再确认一次只会打断自动化。
     * 文件名只取最后一段，避免 `../` 之类的路径穿越。
     */
    private fun startScriptDownload(tabId: String, scriptId: String, details: JSONObject) {
        val requestId = details.optString("id").takeIf { it.isNotEmpty() } ?: return
        fun fail(message: String) {
            deliverScriptEvent(tabId, scriptId, requestId, "error", scriptFailurePayload(message))
        }
        // 与 GM_xmlhttpRequest 同理：下载请求同样带 Cookie，必须验证脚本身份
        // 与当前页面匹配，不能只看"脚本存在且启用"。
        val pageUrl = host.tabs.value.firstOrNull { it.id == tabId }?.url
        val script = host.userScripts.enabledScripts.value.firstOrNull { it.id == scriptId }
        if (pageUrl == null || script == null || !script.script.matches(pageUrl)) {
            fail("脚本未匹配当前页面，拒绝下载")
            return
        }
        val url = details.optString("u")
        val scheme = runCatching { Uri.parse(url).scheme?.lowercase(Locale.ROOT) }.getOrNull()
        if (scheme != "http" && scheme != "https") {
            fail("只支持 http(s) 下载")
            return
        }
        val fileName = details.optString("n")
            .replace('\\', '/')
            .substringAfterLast('/')
            .trim()
            .takeIf { it.isNotEmpty() }
        val headers = LinkedHashMap<String, String>()
        details.optJSONObject("h")?.let { h ->
            for (key in h.keys()) headers[key] = h.optString(key)
        }
        // Tampermonkey 的缺省是 uniquify；'prompt' 在移动端也按 uniquify 处理
        // （不弹"是否覆盖"打断自动化，也绝不静默覆盖用户文件）。
        val conflict = when (details.optString("ca").lowercase(Locale.ROOT)) {
            "overwrite" -> ConflictAction.OVERWRITE
            else -> ConflictAction.UNIQUE
        }
        val request = ScriptSaveAsRequest(
            requestId = requestId,
            url = url,
            fileName = fileName,
            tabId = tabId,
            scriptId = scriptId,
            headers = headers,
            conflict = conflict,
        )
        if (details.optBoolean("sa", false)) {
            // saveAs：先让用户挑位置，拿到 URI 再从 completeSaveAs 入队。
            if (_saveAs.value != null) {
                fail("已有保存请求进行中")
                return
            }
            _saveAs.value = request
            return
        }
        enqueueScriptDownload(request, targetUri = null)
    }

    /**
     * 系统文件选择器返回后继续入队；[uri] 为 null 表示用户取消。
     *
     * 取消也要回 `onerror`：脚本那边正 await 这个下载，不回调就会一直挂着。
     */
    fun completeSaveAs(uri: Uri?) {
        val request = _saveAs.value ?: return
        _saveAs.value = null
        if (uri == null) {
            deliverScriptEvent(
                request.tabId, request.scriptId, request.requestId, "error",
                scriptFailurePayload("已取消保存"),
            )
            return
        }
        enqueueScriptDownload(request, targetUri = uri.toString())
    }

    /** 把一次 `GM_download` 交给下载队列，并开始跟踪它的生命周期。 */
    private fun enqueueScriptDownload(request: ScriptSaveAsRequest, targetUri: String?) {
        host.maybeRequestNotificationPermission()
        host.scope.launch {
            val id = withContext(Dispatchers.IO) {
                host.downloads.enqueue(
                    request.url, request.fileName, null, request.headers,
                    targetUri, request.conflict,
                )
            } ?: run {
                deliverScriptEvent(
                    request.tabId, request.scriptId, request.requestId, "error",
                    scriptFailurePayload("无法下载该文件"),
                )
                return@launch
            }
            scriptDownloads[request.requestId] = ScriptDownloadHandle(id, request.tabId, request.scriptId)
            trackScriptDownload(request.requestId, id, request.url, request.tabId, request.scriptId)
        }
    }

    /**
     * 跟踪一次 `GM_download` 的生命周期。
     *
     * [com.ling.browser.data.repo.DownloadRepository.downloads] 已经汇总了内建引擎与
     * 系统下载器的进度，这里只用轮询把「进度 / 成功 / 失败」翻译成页面里的回调：
     *  - 成功：`onload`（此时文件真的下完了，而不是入队成功）；
     *  - 失败或被取消：`onerror`；
     *  - 进行中：`onprogress`。
     */
    private suspend fun trackScriptDownload(
        requestId: String,
        downloadId: Long,
        url: String,
        tabId: String,
        scriptId: String,
    ) {
        var lastBytes = -1L
        var missingSince = 0L
        var ticks = 0
        while (true) {
            // 已被 abort：映射被摘掉，静默退出（abort 自己发过回调了）。
            if (scriptDownloads[requestId]?.id != downloadId) return
            // 系统下载器的进度靠回查，隔几秒刷一次即可，没必要每秒查全表。
            if (ticks++ % 3 == 1) {
                withContext(Dispatchers.IO) { host.downloads.refresh() }
            }
            val entry = host.downloads.downloads.value.firstOrNull { it.downloadId == downloadId }
            if (entry == null) {
                // 刚入队时流里可能还没这条；连续 3 秒都找不到才当作用户在下载列表里删掉了。
                val now = System.currentTimeMillis()
                if (missingSince == 0L) {
                    missingSince = now
                } else if (now - missingSince > 3_000) {
                    scriptDownloads.remove(requestId)
                    deliverScriptEvent(
                        tabId, scriptId, requestId, "error",
                        scriptFailurePayload("下载已取消"),
                    )
                    return
                }
            } else {
                missingSince = 0L
                when (entry.status) {
                    DownloadStatus.SUCCESS -> {
                        scriptDownloads.remove(requestId)
                        // 补一条终态进度：小文件常在两次轮询之间就下完了，
                        // 只发 load 会让脚本的进度条永远停在 0。Tampermonkey 的
                        // onprogress 末次也是 loaded === total。
                        val total = entry.totalBytes
                        if (total > 0 && lastBytes < total) {
                            deliverScriptEvent(
                                tabId, scriptId, requestId, "progress",
                                JSONObject()
                                    .put("loaded", total)
                                    .put("total", total)
                                    .put("lengthComputable", true),
                            )
                        }
                        deliverScriptEvent(
                            tabId, scriptId, requestId, "load",
                            JSONObject()
                                .put("finalUrl", url)
                                .put("downloadId", downloadId.toString())
                                .put("name", entry.fileName),
                        )
                        return
                    }
                    DownloadStatus.FAILED -> {
                        scriptDownloads.remove(requestId)
                        deliverScriptEvent(
                            tabId, scriptId, requestId, "error",
                            scriptFailurePayload("下载失败"),
                        )
                        return
                    }
                    else -> {
                        val loaded = entry.downloadedBytes
                        if (loaded != lastBytes) {
                            if (lastBytes < 0 || loaded - lastBytes >= PROGRESS_STEP_BYTES || loaded == entry.totalBytes) {
                                lastBytes = loaded
                                deliverScriptEvent(
                                    tabId, scriptId, requestId, "progress",
                                    JSONObject()
                                        .put("loaded", loaded)
                                        .put("total", entry.totalBytes)
                                        .put("lengthComputable", entry.totalBytes > 0),
                                )
                            }
                        }
                    }
                }
            }
            delay(1_000)
        }
    }

    /**
     * `GM_download` 返回句柄的 `abort()`。
     *
     * 先把映射摘掉再去取消：跟踪协程看到映射没了就会退出，避免它再补一条 error
     * 与这里的回调重复。
     */
    private fun abortScriptDownload(requestId: String) {
        val handle = scriptDownloads.remove(requestId) ?: return
        host.scope.launch {
            val entry = host.downloads.downloads.value
                .firstOrNull { it.downloadId == handle.id }
            if (entry != null) {
                withContext(Dispatchers.IO) { host.downloads.cancel(entry) }
            }
            deliverScriptEvent(
                handle.tabId, handle.scriptId, requestId, "error",
                scriptFailurePayload("已取消"),
            )
        }
    }

    private fun scriptResponsePayload(
        response: ScriptHttpResponse,
        responseType: String,
    ): JSONObject = JSONObject().apply {
        put("readyState", 4)
        put("status", response.status)
        put("statusText", response.statusText)
        put("finalUrl", response.finalUrl)
        put("responseHeaders", response.headers)
        put("responseText", response.body)
        put("responseType", responseType)
        // 二进制响应体只能以 base64 过桥；垫片里再解回 ArrayBuffer / Blob。
        if (responseType == "arraybuffer" || responseType == "blob") {
            put(
                "responseBase64",
                Base64.encodeToString(response.raw, Base64.NO_WRAP),
            )
        }
    }

    /**
     * 把事件投递回页面。
     *
     * 走 `JSON.parse(字符串字面量)` 而不是直接把 JSON 拼进代码：响应体里可能出现
     * 引号、反斜杠甚至 U+2028，只有先按 JS 字符串转义再解析才不会被当成代码。
     */
    private fun deliverScriptEvent(
        tabId: String,
        scriptId: String,
        requestId: String,
        event: String,
        payload: JSONObject,
    ) {
        val js = "window.__lingGMDispatch&&window.__lingGMDispatch(" +
            jsString(scriptMarker(scriptId)) + "," +
            jsString(requestId) + "," +
            jsString(event) + ",JSON.parse(" +
            jsString(payload.toString()) + "))"
        host.evalInTab(tabId, js)
    }
}

/**
 * 待确认的用户脚本安装。
 *
 * [preview] 在弹窗打开后异步拉取：确认框不该等网络才出现，但"会跑在哪些
 * 网站"必须在用户点安装之前看到。拉取失败（[ScriptPreview.error]）只影响
 * 展示，不改变确认/安装按钮本身的行为。
 */
data class PendingUserScript(
    val url: String,
    val loading: Boolean = true,
    val preview: ScriptPreview? = null,
)

/**
 * 一次等待用户挑保存位置的 `GM_download({ saveAs: true })`。
 *
 * [conflict] 在用户挑完位置后仍要带进队列：SAF 目标由用户确认，
 * 但下载记录、abort、onprogress 都还走同一条队列。
 */
data class ScriptSaveAsRequest(
    val requestId: String,
    val url: String,
    val fileName: String?,
    val tabId: String,
    val scriptId: String,
    val headers: Map<String, String>,
    val conflict: ConflictAction,
)
