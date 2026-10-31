package com.ling.browser.ui

import android.app.Application
import android.os.Build
import androidx.core.content.ContextCompat
import com.ling.browser.data.db.DownloadEntry
import com.ling.browser.data.prefs.DownloadLocation
import com.ling.browser.data.prefs.DownloaderType
import com.ling.browser.data.prefs.LingSettings
import com.ling.browser.data.repo.DownloadRepository
import com.ling.browser.download.DownloadEngine
import com.ling.browser.download.DownloadFileName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 下载域对宿主（[BrowserViewModel]）的最小依赖。
 *
 * 下载需要现读设置（存到哪里、用哪个下载器）、拿应用上下文查权限，
 * 并把「去申请权限」的信号交给 Activity —— 这些都仍由宿主持有。
 */
internal interface DownloadHost {
    val app: Application
    val scope: CoroutineScope
    val settings: StateFlow<LingSettings>
    val downloads: DownloadRepository
    val downloadEngine: DownloadEngine

    fun showMessage(text: String)
}

/**
 * 下载域：确认框、大小探测、权限、队列操作与「网络限制」询问。
 *
 * 从 [BrowserViewModel] 里拆出的功能簇。真正的下载执行仍在
 * [com.ling.browser.download.DownloadEngine] / [DownloadRepository]，
 * 这里只做 UI 侧的状态与决策。
 */
internal class DownloadDelegate(private val host: DownloadHost) {

    /**
     * 「下载完成」通知点进来时要打开的任务 id。
     *
     * 由 Activity 从 Intent 取出后写入，UI 消费并调 [consumeOpenDownload] 清空；
     * 只传 id 不传路径，打开时按 id 查库，文件被删或改名都能得到准确结果。
     */
    private val _pendingOpenDownload = MutableStateFlow<Long?>(null)
    val pendingOpenDownload: StateFlow<Long?> = _pendingOpenDownload.asStateFlow()

    fun requestOpenDownload(taskId: Long) {
        _pendingOpenDownload.value = taskId
    }

    fun consumeOpenDownload() {
        _pendingOpenDownload.value = null
    }

    /**
     * 因网络限制被暂停、正等待用户决定「要不要继续」的下载任务。
     *
     * 用列表而不是单个值：网络一变可能同时停掉好几个任务，UI 按顺序逐个询问，
     * 回答一个就摘掉一个。
     */
    private val _networkPrompts = MutableStateFlow<List<DownloadEntry>>(emptyList())
    val networkPrompts: StateFlow<List<DownloadEntry>> = _networkPrompts.asStateFlow()

    /** 用户同意在当前网络下继续这个任务（只放行它，不改全局设置）。 */
    fun allowDownloadOnCurrentNetwork(taskId: Long) {
        dismissNetworkPrompt(taskId)
        host.scope.launch { host.downloadEngine.forceResume(taskId) }
    }

    /** 用户拒绝：保持暂停，等网络恢复后引擎仍会自动续传。 */
    fun keepDownloadPaused(taskId: Long) = dismissNetworkPrompt(taskId)

    private fun dismissNetworkPrompt(taskId: Long) {
        _networkPrompts.update { list -> list.filterNot { it.id == taskId } }
    }

    /**
     * 待确认的下载请求。
     *
     * 网页触发下载时不直接开始，先交给 UI 弹确认框 —— 有些页面会自动
     * 发起下载（弹窗广告、误触），静默落盘既费流量又让人措手不及。
     * 用户确认后再调 [confirmPendingDownload]。
     */
    private val _pendingDownload = MutableStateFlow<PendingDownload?>(null)
    val pendingDownload: StateFlow<PendingDownload?> = _pendingDownload.asStateFlow()

    /**
     * 「去申请存储权限」的信号，值只增不减。
     *
     * 当用户选择公共下载目录且系统是 Android 9 及以下时，下载前需要
     * `WRITE_EXTERNAL_STORAGE` 运行时权限。权限申请必须由 Activity 发起，
     * 因此这里只发信号，UI（MainActivity）收到后用
     * ActivityResultContracts.RequestPermission 拉起系统弹窗，结果再通过
     * [onStoragePermissionResult] 回传。
     *
     * 用递增计数而不是 Boolean：连续两次下载都要各触发一次（Boolean 第二次变化不了）。
     */
    private val _requestStoragePermission = MutableStateFlow(0)
    val requestStoragePermission: StateFlow<Int> = _requestStoragePermission.asStateFlow()

    /**
     * 「去申请通知权限」的信号，值只增不减（理由同上）。
     *
     * 内建下载器靠前台服务的常驻通知展示进度；Android 13 起没有
     * `POST_NOTIFICATIONS` 授权时，通知栏不会出现任何东西 —— 前台服务照跑，
     * 用户却什么都看不到。申请同样必须由 Activity 发起，这里发信号，
     * 授权结果不影响下载，因此无需回传处理。
     */
    private val _requestNotificationPermission = MutableStateFlow(0)
    val requestNotificationPermission: StateFlow<Int> = _requestNotificationPermission.asStateFlow()

    fun requestNotificationPermissionSignal() {
        _requestNotificationPermission.update { it + 1 }
    }

    /**
     * 等待存储权限结果的下载请求。
     *
     * 用户点确认后若发现需要权限，先把请求存在这里，权限结果回来后
     * 再决定是真正入队还是提示失败。期间 [_pendingDownload] 已被清空，
     * 避免确认框残留。
     */
    private var pendingAfterPermission: PendingDownload? = null

    init {
        // 因网络限制被引擎暂停的任务：排队交给 UI 弹窗逐个征求同意
        host.scope.launch {
            host.downloadEngine.networkBlocked.collect { entry ->
                _networkPrompts.update { list ->
                    if (list.any { it.id == entry.id }) list else list + entry
                }
            }
        }
        // 冷启动时被接上的下载：给一条提示。
        //
        // 用户视角是「上次下载到一半，App 被系统杀了」—— 他并不知道那些
        // 字节还在磁盘上、还能续传。所以要在打开 App 时明确告诉他「已接着下」，
        // 否则他很可能直接取消重下，白费之前的流量。
        host.scope.launch {
            host.downloadEngine.resumedOnLaunch.collect { names ->
                if (names.isEmpty()) return@collect
                val text = if (names.size == 1) {
                    "正在继续上次未完成的下载：${names.first()}"
                } else {
                    "正在继续 ${names.size} 个未完成的下载"
                }
                host.showMessage(text)
                // 展示完立刻清空：StateFlow 会保留最新值，不清的话每次
                // 重组/重进页面都会再弹一次同样的提示。
                host.downloadEngine.consumeResumedOnLaunch()
            }
        }
    }

    /**
     * 网页请求下载：先摆出确认框，并尽力探测文件大小。
     *
     * 大小探测是「尽力而为」：发一个 HEAD 请求读 Content-Length。
     * 很多站点不返回该响应头，或对 HEAD 直接 403/405，因此失败时
     * 界面显示「大小未知」而不是报错 —— 不能因为探不到大小就不让下载。
     */
    fun requestDownload(url: String, mimeType: String?, fileName: String? = null) {
        // 明确给出名字（脚本 / 调用方指定）时不允许再被响应头改掉。
        val nameExplicit = fileName != null
        val name = fileName ?: DownloadFileName.resolve(url, mimeType, contentDisposition = null)
        _pendingDownload.value = PendingDownload(
            url = url,
            fileName = name,
            mimeType = mimeType,
            sizeBytes = null,
            probing = true,
            nameExplicit = nameExplicit,
        )

        host.scope.launch {
            val info = probeDownloadInfo(url)
            // HEAD 里若带了 Content-Disposition，就用服务端的名字替换推断出来的名字。
            // 只在名字不是用户/脚本指定时才替换。
            val resolved = if (!nameExplicit && info?.contentDisposition != null) {
                DownloadFileName.resolve(url, mimeType, info.contentDisposition)
            } else {
                name
            }
            _pendingDownload.update { cur ->
                if (cur?.url == url) {
                    cur.copy(fileName = resolved, sizeBytes = info?.sizeBytes, probing = false)
                } else {
                    cur
                }
            }
        }
    }

    /** 用户确认下载。 */
    fun confirmPendingDownload() {
        val req = _pendingDownload.value ?: return
        _pendingDownload.value = null

        // 内建下载器的进度靠常驻通知呈现，Android 13+ 需要通知权限。
        // 不阻塞下载：弹窗与下载并行，授权后通知随即出现。
        maybeRequestNotificationPermission()

        // 公共下载目录在 Android 9 及以下需要 WRITE_EXTERNAL_STORAGE 运行时权限。
        // 应用私有目录与 Android 10+ 的 MediaStore 路径都不需要权限，直接入队。
        val loc = host.settings.value.downloadLocation
        val needPermission = loc == DownloadLocation.PUBLIC_DOWNLOADS &&
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

        if (needPermission) {
            val granted = checkSelfHasStoragePermission()
            if (!granted) {
                pendingAfterPermission = req
                _requestStoragePermission.update { it + 1 }
                return
            }
        }
        enqueueDownload(req)
    }

    /**
     * 存储权限申请结果回调。
     *
     * 由 MainActivity 在权限弹窗返回后调用。
     *  - 已授权：继续之前挂起的下载；
     *  - 拒绝：提示用户无法下载到公共目录，可在设置里切到私有目录。
     */
    fun onStoragePermissionResult(granted: Boolean) {
        val req = pendingAfterPermission ?: return
        pendingAfterPermission = null
        if (granted) {
            enqueueDownload(req)
        } else {
            host.showMessage("没有存储权限，无法下载到公共目录。可在设置里选择「应用私有目录」。")
        }
    }

    /**
     * 真正把下载交给仓库。
     *
     * 抽出成单独方法：权限申请前后都要走这一步，避免复制粘贴。
     * 注意参数顺序是 (url, fileName, mimeType)，与 [com.ling.browser.data.repo.DownloadRepository.enqueue] 一致。
     */
    private fun enqueueDownload(req: PendingDownload) {
        host.scope.launch {
            val id = host.downloads.enqueue(
                req.url,
                req.fileName,
                req.mimeType,
                explicitName = req.nameExplicit,
            )
            host.showMessage(if (id != null) "已开始下载" else "无法下载该文件")
        }
    }

    /** 同步检查应用是否已持有 WRITE_EXTERNAL_STORAGE。 */
    private fun checkSelfHasStoragePermission(): Boolean =
        ContextCompat.checkSelfPermission(
            host.app,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /**
     * 内建下载器下载时申请通知权限（Android 13+）。
     *
     * 系统下载器由 DownloadManager 自己发通知，与我们的通知权限无关，故跳过。
     * 已授权或系统版本不需要时也不打扰用户。
     */
    fun maybeRequestNotificationPermission() {
        if (host.settings.value.downloaderType != DownloaderType.BUILT_IN) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            host.app,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) _requestNotificationPermission.update { it + 1 }
    }

    /** 用户取消下载。 */
    fun dismissPendingDownload() {
        _pendingDownload.value = null
    }

    /**
     * 尽力获取远端文件信息（大小 + Content-Disposition）。
     *
     * 失败返回 null（界面显示「大小未知」）。
     * 用 HEAD 而非 GET：只取响应头，不下载正文，代价极小。
     * 不用 DownloadManager 预检是因为它无法「只探测不下载」。
     */
    private suspend fun probeDownloadInfo(url: String): ProbeInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = PROBE_TIMEOUT_MS
                readTimeout = PROBE_TIMEOUT_MS
                // 有些站点对无 UA 的请求直接拒绝
                setRequestProperty("User-Agent", USER_AGENT)
                instanceFollowRedirects = true
            }
            try {
                val disposition = conn.getHeaderField("Content-Disposition")
                // 部分站点不实现 HEAD，回退到 Range 请求只取 1 字节
                if (conn.responseCode !in 200..299) {
                    return@runCatching probeWithRange(url)
                }
                // 不用 getContentLengthLong：它需要 API 24，而 minSdk 是 23。
                // 直接解析响应头等价，且对分块传输同样返回 -1。
                val len = conn.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
                if (len > 0) {
                    ProbeInfo(len, disposition)
                } else {
                    val fallback = probeWithRange(url)
                    ProbeInfo(fallback?.sizeBytes, disposition ?: fallback?.contentDisposition)
                }
            } finally {
                runCatching { conn.disconnect() }
            }
        }.getOrNull()
    }

    /** HEAD 不可用时的退路：Range: bytes=0-0，从 Content-Range 里读总大小。 */
    private fun probeWithRange(url: String): ProbeInfo? = runCatching {
        val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = PROBE_TIMEOUT_MS
            readTimeout = PROBE_TIMEOUT_MS
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Range", "bytes=0-0")
        }
        try {
            // Content-Range: bytes 0-0/12345 → 取斜杠后的总大小
            val range = conn.getHeaderField("Content-Range")
            ProbeInfo(
                sizeBytes = range?.substringAfterLast('/')?.trim()?.toLongOrNull()?.takeIf { it > 0 },
                contentDisposition = conn.getHeaderField("Content-Disposition"),
            )
        } finally {
            runCatching { conn.disconnect() }
        }
    }.getOrNull()

    /** HEAD 探测的结果；两项都可能拿不到。 */
    private data class ProbeInfo(
        val sizeBytes: Long?,
        val contentDisposition: String?,
    )

    fun cancelDownload(entry: DownloadEntry) {
        host.scope.launch {
            host.downloads.cancel(entry)
            host.showMessage("已取消下载并删除文件")
        }
    }

    /** 暂停下载（保留已下载部分，可后续恢复）。 */
    fun pauseDownload(entry: DownloadEntry) {
        host.scope.launch { host.downloads.pause(entry) }
    }

    /** 恢复已暂停的下载（自动用 Range 续传）。 */
    fun resumeDownload(entry: DownloadEntry) {
        host.scope.launch { host.downloads.resume(entry) }
    }

    fun removeDownload(entry: DownloadEntry, deleteFile: Boolean = false) {
        host.scope.launch { host.downloads.remove(entry, deleteFile) }
    }

    fun clearDownloads(deleteFiles: Boolean = false) {
        host.scope.launch {
            host.downloads.clearAll(deleteFiles)
            host.showMessage(if (deleteFiles) "已清除全部下载与文件" else "已清除下载记录")
        }
    }

    /**
     * 刷新下载进度。
     *
     * DownloadManager 没有进度回调，只能轮询。由下载列表页在可见时
     * 每秒调用一次；页面离开即停止，不在后台空转。
     */
    fun refreshDownloads() = host.downloads.refresh()
}

/**
 * 等待用户确认的下载。
 *
 * [sizeBytes] 为 null 表示探测失败或还没探完，界面据此显示「大小未知」
 * 或「正在获取大小」。绝不因为探不到大小就阻止下载 —— 那会误伤一大批
 * 不支持 HEAD 的站点。
 */
data class PendingDownload(
    val url: String,
    val fileName: String,
    val mimeType: String?,
    val sizeBytes: Long?,
    val probing: Boolean,
    /** 名字是否由脚本 / 调用方明确指定；false 表示是推断来的，允许被响应头纠正。 */
    val nameExplicit: Boolean = false,
)

/** 大小探测超时。设短一些：探测只是锦上添花，不该让确认框卡着不显示。 */
private const val PROBE_TIMEOUT_MS = 4000

/**
 * 探测大小用的 UA。
 *
 * 用桌面版 UA 而不是应用自己的名字：部分站点对陌生 UA 返回 403，
 * 而桌面 UA 的通过率最高，这里只关心响应头，不影响后续真实下载。
 */
private const val USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0 Safari/537.36"
