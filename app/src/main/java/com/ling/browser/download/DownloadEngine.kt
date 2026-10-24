package com.ling.browser.download

import android.content.ContentValues
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.webkit.CookieManager
import android.webkit.WebSettings
import com.ling.browser.data.db.DownloadEntry
import com.ling.browser.data.db.DownloadStatus
import com.ling.browser.data.db.LingDatabase
import com.ling.browser.data.db.downloadValues
import com.ling.browser.data.db.toDownload
import com.ling.browser.data.prefs.DownloadLocation
import com.ling.browser.data.prefs.DownloadNetwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * 内建下载引擎。
 *
 * 设计目标：替代系统 DownloadManager，自己掌握下载过程，换取
 *   - 实时进度回调（不用轮询）；
 *   - 真正可控的暂停 / 恢复 / 断点续传；
 *   - 失败自动重试。
 *
 * 职责边界：
 *   - 网络与落盘：本类（OkHttp + RandomAccessFile）；
 *   - 持久化：[LingDatabase] 的 downloads 表；
 *   - 进程保活与通知栏：[DownloadService]，由本类按需拉起 / 停止。
 *
 * 线程模型：所有对内存状态 [tasks] 的读写都走 [stateMutex]，
 * 下载协程在 [Dispatchers.IO] 上跑，状态变更后更新 [_downloads] 并写库。
 */
class DownloadEngine(
    private val context: Context,
    private val db: LingDatabase,
    /** 初始网络限制策略；之后由设置层通过 [onNetworkPolicyChanged] 推送新值。 */
    initialNetwork: DownloadNetwork = DownloadNetwork.ALL,
) {
    private val appContext = context.applicationContext

    /** 当前网络限制策略。切换后立即重算：越界的任务暂停，放宽后自动续传。 */
    private val networkPolicy = MutableStateFlow(initialNetwork)

    /** 引擎自己的协程作用域：随进程存活，下载任务挂在这里。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            // 连接超时短一些：连不上趁早报错，别让用户傻等
            .connectTimeout(15, TimeUnit.SECONDS)
            // 读超时用「每字节超时」语义（OkHttp 默认），大文件慢速下载不会被误杀
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            // 带上浏览器里的 Cookie，否则需要登录态的文件（网盘、论坛附件）会直接 403。
            //
            // 这里刻意用 CookieJar 而不是把 `Cookie` 头钉死在请求上：CookieJar 会在
            // **每一跳**（含 301/302 重定向）按当前 URL 重新解析，所以跨域跳转时
            // 不会把原站的 Cookie 捎给第三方 —— 固定请求头做不到这点，会泄露凭据。
            .cookieJar(webViewCookieJar)
            .build()
    }

    /** 把 WebView 的 Cookie 按请求 URL 实时解析成 OkHttp 的 Cookie。 */
    private val webViewCookieJar = object : CookieJar {
        /** 只借浏览器的 Cookie 用，不回写：下载不该反过来改登录态。 */
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val header = runCatching {
                CookieManager.getInstance().getCookie(url.toString())
            }.getOrNull().orEmpty()
            if (header.isEmpty()) return emptyList()
            return runCatching {
                Cookie.parseAll(url, Headers.headersOf("Cookie", header))
            }.getOrDefault(emptyList())
        }
    }

    /**
     * 兜底 UA：下载请求不是 WebView 发的，不带 UA 会被一部分站点按"爬虫"拒绝。
     *
     * 优先用设置里的自定义 UA（与浏览页面保持一致），为空时用系统 WebView 的默认值。
     */
    private val defaultUserAgent: String by lazy {
        runCatching { WebSettings.getDefaultUserAgent(appContext) }
            .getOrDefault("Mozilla/5.0 (Linux; Android) LingBrowser")
    }

    /** taskId -> 内存中的任务状态。写库前的单一真相源。 */
    private val tasks = MutableStateFlow<Map<Long, DownloadEntry>>(emptyMap())

    /** taskId -> 下载协程的 Job，用于暂停 / 取消。 */
    private val jobs = mutableMapOf<Long, Job>()

    /**
     * 因网络限制被**自动**暂停的 taskId。
     *
     * 必须与用户主动暂停区分开：网络恢复后只续传这里面的任务，不能替用户
     * 「擅自继续」他手动停掉的那批。用户主动暂停/继续/删除时同步清理。
     */
    private val autoPaused = mutableSetOf<Long>()

    /** 并发槽位：同时最多 [MAX_CONCURRENT_DOWNLOADS] 个任务真正在传输，其余排队。 */
    private val downloadSlots = Semaphore(MAX_CONCURRENT_DOWNLOADS)

    /**
     * 用户明确同意「在当前网络下继续」的任务。
     *
     * 网络限制是默认策略，不该变成硬墙：用户看到弹窗确认后，这个任务就不再受
     * 策略约束，直到它结束。只记内存 —— 进程重启后重新征求一次意见更稳妥。
     */
    private val networkOverrides = mutableSetOf<Long>()

    /**
     * 「因网络限制被暂停」的事件流，供 UI 弹窗征求用户意见。
     *
     * 用 SharedFlow 而非 StateFlow：这是一次性提醒，没有订阅者时丢掉也不影响
     * 任务状态（它本来就已经安全地停在 PAUSED），不需要回放。
     */
    private val _networkBlocked = MutableSharedFlow<DownloadEntry>(extraBufferCapacity = 16)
    val networkBlocked: SharedFlow<DownloadEntry> = _networkBlocked.asSharedFlow()

    private val connectivityManager: ConnectivityManager? =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private var networkMonitorRegistered = false

    /**
     * 默认网络的连通性监听。
     *
     * 只在「默认网络」上挂：判断依据是当前主网络是否满足策略，与其它并存网络无关。
     * `onAvailable` / `onLost` / `onCapabilitiesChanged` 都归一到 [syncNetworkState]，
     * 因为它读的是即时状态而不是事件本身，重复触发不会产生副作用。
     */
    private val defaultNetworkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = onNetworkEvent()
        override fun onLost(network: Network) = onNetworkEvent()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
            onNetworkEvent()
    }

    /**
     * taskId -> 调用方附加的请求头（目前只有用户脚本的 `GM_download` 会用）。
     *
     * 只存在内存里：进程被杀后恢复下载会丢掉这些头，与"下载任务本身也不重启"
     * 的行为一致；为它加一列数据库迁移不值得。
     */
    private val taskHeaders = mutableMapOf<Long, Map<String, String>>()

    /**
     * taskId -> 本次下载使用的 UA。
     *
     * 与 [taskHeaders] 一样只存在内存：UA 只影响首字母请求，进程被杀后续传
     * 会退回默认 UA，对已开始的传输没有影响。
     */
    private val taskUserAgents = mutableMapOf<Long, String>()

    /** taskId -> SAF 选定的目标 URI（`GM_download` 的 `saveAs`）。与请求头一样只存内存。 */
    private val taskUris = mutableMapOf<Long, String>()

    /** 需要在开写前删掉同名旧文件的任务（`conflictAction: 'overwrite'`）。 */
    private val taskOverwrite = mutableSetOf<Long>()

    /**
     * taskId -> 上一次进度采样的快照，用于估算速度。
     *
     * 只在 [writeBody] 写盘期间读写（同一协程），因此不需要 [stateMutex] 保护。
     * 每次开始写盘前会清掉旧快照，避免重试/续传时把中间停顿算进平均速度。
     */
    private val speedSamples = mutableMapOf<Long, SpeedSample>()

    /** 一次进度采样：[bytes] 已写字节、[time] 采样时刻、[speed] 平滑后的速度。 */
    private class SpeedSample(var bytes: Long, var time: Long, var speed: Long)

    private val stateMutex = Mutex()

    private val _downloads = MutableStateFlow<List<DownloadEntry>>(emptyList())
    val downloads: StateFlow<List<DownloadEntry>> = _downloads.asStateFlow()

    init {
        // 启动时把库里的记录载入内存；进行中的先落成「已暂停」。
        // 进程被杀后不可能还有任务在跑，直接标失败会让已下载的部分白白浪费，
        // 所以统一退回 PAUSED，再自动续传（见下）—— 这才是「崩溃后接着下」。
        val saved = queryAll()
        val interrupted = saved.filter { it.isRunning }.map { it.id }
        tasks.value = saved.map { entry ->
            if (entry.isRunning) entry.copy(status = DownloadStatus.PAUSED) else entry
        }.associateBy { it.id }
        publish()
        registerNetworkMonitor()
        if (interrupted.isNotEmpty()) {
            scope.launch {
                // 逐个走公开的 resume：它会重新登记任务、按网络策略决定立即开跑
                // 还是先停着等网，与用户点「继续」完全同一条路径。
                interrupted.forEach { resume(it) }
            }
        }
    }

    // -------------------------------------------------------------- 公开 API

    /**
     * 发起一次下载。
     *
     * 先写库拿到自增 ID（= taskId），再启动下载协程。
     * 返回任务 id；null 表示 URL 不合法或写库失败。
     *
     * @param targetUri 用户脚本 `saveAs` 选定的 SAF 文档；给出时忽略 [location]。
     * @param conflict 同名文件的处理策略（`GM_download` 的 `conflictAction`）；
     *   `UNIQUE` 在入队时改名，`OVERWRITE` 在开写前删掉旧文件。
     * @param userAgent 本次下载使用的 UA；为空时退回引擎默认值。带上页面同款 UA
     *   能避免一部分站点把下载请求当成爬虫。
     */
    suspend fun enqueue(
        url: String,
        fileName: String?,
        mimeType: String?,
        location: DownloadLocation,
        headers: Map<String, String> = emptyMap(),
        targetUri: String? = null,
        conflict: ConflictAction? = null,
        userAgent: String? = null,
    ): Long? = withContext(Dispatchers.IO) {
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                return@withContext null
            }
            val requested = fileName?.takeIf { it.isNotBlank() }
                ?: url.substringAfterLast('/').substringBefore('?').takeIf { it.isNotBlank() }
                ?: "download"
            // 选了 SAF 目标时文件名由用户决定，不再做唯一化。
            val name = if (targetUri == null && conflict == ConflictAction.UNIQUE) {
                DownloadDestination.uniqueName(appContext, requested, location)
            } else {
                requested
            }

            val id = insertTask(
                fileName = name,
                url = url,
                mimeType = mimeType,
                status = DownloadStatus.PENDING,
                location = location,
            ) ?: return@withContext null

            if (headers.isNotEmpty()) taskHeaders[id] = headers
            userAgent?.takeIf { it.isNotBlank() }?.let { taskUserAgents[id] = it }
            if (targetUri != null) taskUris[id] = targetUri
            if (targetUri == null && conflict == ConflictAction.OVERWRITE) taskOverwrite.add(id)
            val entry = DownloadEntry(
                id = id,
                downloadId = id,
                fileName = name,
                url = url,
                mimeType = mimeType,
                status = DownloadStatus.PENDING,
                downloadLocation = location,
                createdAt = System.currentTimeMillis(),
            )
            stateMutex.withLock {
                tasks.update { it + (id to entry) }
            }
            publish()
            startDownload(id)
            id
        }

    /** 暂停：取消下载协程，保留已下载部分，状态置 PAUSED。 */
    suspend fun pause(taskId: Long) {
        stateMutex.withLock {
            // 用户主动暂停：退出「自动暂停」集合，否则下一次网络事件会把任务又拉起来
            autoPaused.remove(taskId)
            jobs.remove(taskId)?.cancel()
            tasks.value[taskId]?.let { cur ->
                if (cur.isRunning) {
                    val paused = cur.copy(status = DownloadStatus.PAUSED, speedBytesPerSec = 0L)
                    tasks.update { it + (taskId to paused) }
                    persist(paused)
                }
            }
        }
        publish()
        stopServiceIfIdle()
    }

    /** 恢复：重新启动下载协程，会自动用 Range 续传。 */
    suspend fun resume(taskId: Long) {
        val entry = tasks.value[taskId] ?: return
        if (entry.status == DownloadStatus.RUNNING) return
        // 先置回 PENDING 再启动：服务按「PENDING/RUNNING 都算进行中」判断是否该退出，
        // 若任务还停在 PAUSED，服务刚被拉起，collectJob 的首个发射就会判定「没有
        // 进行中的任务」而立刻 stopSelf，通知一闪而过 —— 这正是
        // 「暂停后再继续，通知消失」的根因。
        stateMutex.withLock {
            // 用户已明确要求继续，不再算作「因网络自动暂停」
            autoPaused.remove(taskId)
            tasks.value[taskId]?.let { cur ->
                val pending = cur.copy(status = DownloadStatus.PENDING, speedBytesPerSec = 0L)
                tasks.update { it + (taskId to pending) }
                persist(pending)
            }
        }
        publish()
        startDownload(taskId)
    }

    /**
     * 取消/删除一个任务：停掉下载协程，删掉记录，可选连文件一起删。
     *
     * 「取消未完成任务」与「删除记录」共用这一条路径 —— 用户点取消时期望的是
     * 「不下了，也别留下半截文件」，而不是看到一个「失败」条目继续躺着。
     */
    suspend fun remove(taskId: Long, deleteFile: Boolean) {
        stateMutex.withLock {
            jobs.remove(taskId)?.cancel()
            val entry = tasks.value[taskId]
            if (deleteFile) entry?.let { deleteTarget(it) }
            db.writableDatabase.delete("downloads", "id = ?", arrayOf(taskId.toString()))
            tasks.update { it - taskId }
            speedSamples.remove(taskId)
            taskHeaders.remove(taskId)
            taskUserAgents.remove(taskId)
            taskUris.remove(taskId)
            taskOverwrite.remove(taskId)
            autoPaused.remove(taskId)
            networkOverrides.remove(taskId)
        }
        publish()
        stopServiceIfIdle()
    }

    suspend fun clearAll(deleteFiles: Boolean) {
        stateMutex.withLock {
            jobs.values.forEach { it.cancel() }
            jobs.clear()
            if (deleteFiles) {
                tasks.value.values.forEach { e -> deleteTarget(e) }
            }
            db.writableDatabase.delete("downloads", null, null)
            tasks.value = emptyMap()
            speedSamples.clear()
            taskHeaders.clear()
            taskUserAgents.clear()
            taskUris.clear()
            taskOverwrite.clear()
            autoPaused.clear()
            networkOverrides.clear()
        }
        publish()
        stopServiceIfIdle()
    }

    /** 根据 localPath 是文件路径还是 content:// URI 选择删除方式。 */
    private fun deleteTarget(entry: DownloadEntry) {
        val path = entry.localPath ?: return
        if (path.startsWith("content://")) {
            runCatching {
                android.net.Uri.parse(path)?.let { uri ->
                    appContext.contentResolver.delete(uri, null, null)
                }
            }
        } else {
            runCatching { java.io.File(path).delete() }
        }
    }

    /** 兼容旧的轮询接口：引擎是推送式的，这里只需把内存状态重发一次。 */
    fun refresh() = publish()

    /**
     * 用户在弹窗里点了「继续下载」：给这个任务放行，并立刻恢复。
     *
     * 放行只针对这一个任务（不动全局设置），一直有效到它完成或被删除 ——
     * 用户既然已经愿意为这次下载花流量，中途再按网络类型反复打断就没有意义。
     */
    suspend fun forceResume(taskId: Long) {
        stateMutex.withLock { networkOverrides.add(taskId) }
        resume(taskId)
    }

    /**
     * 设置层推送新的网络限制策略。
     *
     * 切换后立即重算当前网络：收紧时把越界的任务停下（记入 [autoPaused]），
     * 放宽时把此前自动暂停的任务续起来。传入相同值直接忽略。
     */
    fun onNetworkPolicyChanged(network: DownloadNetwork) {
        if (networkPolicy.value == network) return
        networkPolicy.value = network
        scope.launch { syncNetworkState() }
    }

    /**
     * 用系统下载器的条目覆盖内存中对应的记录。
     *
     * 内建引擎只管自己创建的条目；系统 DownloadManager 的条目由仓库层
     * 回查后通过这个方法塞进同一个 [tasks] 映射，使 [downloads] 流能
     * 同时展示两种来源的下载。以 `id` 为主键合并，不影响内建条目。
     */
    fun overwriteSystemEntries(entries: List<DownloadEntry>) {
        if (entries.isEmpty()) return
        val map = entries.associateBy { it.id }
        stateMutex.tryLock().also { locked ->
            if (locked) {
                try {
                    tasks.update { it + map }
                } finally {
                    stateMutex.unlock()
                }
                publish()
            }
        }
    }

    // -------------------------------------------------------------- 下载核心

    /**
     * 启动一个下载协程。
     *
     * 两层闸门，顺序不能反：
     *   1. 网络策略：不达标直接停在 PAUSED，**不占并发名额** —— 否则一批
     *      受限任务会把槽位全占满，网络恢复后真正能跑的任务反而排不上；
     *   2. 并发上限：超出 [MAX_CONCURRENT_DOWNLOADS] 的任务在 Semaphore 上排队，
     *      属于「已入队但还没开始传」，状态保持 PENDING。
     *
     * 用 `LAZY` 启动并先登记 Job 再 `start()`：协程可能瞬间结束（例如网络不达标），
     * 若它先跑完、自己把自己从 [jobs] 里摘掉，随后这里再写入的就是一个已完成的
     * Job —— 用户点「暂停」会 cancel 到幽灵对象。先登记可彻底避免这个窗口。
     */
    private suspend fun startDownload(taskId: Long) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val self = currentCoroutineContext()[Job]
            try {
                if (!isNetworkAllowed(taskId)) {
                    markNetworkPaused(taskId)
                    return@launch
                }
                downloadSlots.acquire()
                try {
                    // 排队期间网络可能已经变了，拿到槽位后再确认一次
                    if (!isNetworkAllowed(taskId)) {
                        markNetworkPaused(taskId)
                        return@launch
                    }
                    runDownload(taskId)
                } finally {
                    downloadSlots.release()
                }
            } finally {
                // 只摘自己这一条：暂停后立刻继续会先取消旧 Job、再登记新 Job，
                // 无条件 remove 会把新 Job 的记录抹掉，让下一次「暂停」按不到
                // 真正在跑的任务。
                stateMutex.withLock {
                    if (jobs[taskId] === self) jobs.remove(taskId)
                }
            }
        }
        stateMutex.withLock { jobs[taskId] = job }
        ensureServiceStarted()
        job.start()
    }

    /**
     * 单次下载执行体。
     *
     * 流程：
     *   1. 读本地已下载字节数（断点续传的起点）；
     *   2. 发 GET，若已有部分字节则带 Range 头；
     *   3. 206 → 追加写；200 → 从头写；其他 → 失败；
     *   4. 流式写盘，定期刷进度到内存 + 库；
     *   5. 写完 → SUCCESS；IO 异常 → 重试（最多 [MAX_RETRIES] 次）。
     */
    private suspend fun runDownload(taskId: Long) {
        var retries = 0
        while (true) {
            val entry = tasks.value[taskId] ?: return
            // 用户可能在重试间隙取消了
            if (entry.status == DownloadStatus.FAILED || entry.status == DownloadStatus.SUCCESS) {
                return
            }
            // 网络策略已不满足（例如从 Wi-Fi 切到计费流量）：转入自动暂停，
            // 等网络恢复后续传。若继续重试，只会把任务耗成 FAILED。
            if (!isNetworkAllowed(taskId)) {
                markNetworkPaused(taskId)
                return
            }
            try {
                executeDownload(taskId, entry)
                return
            } catch (e: java.util.concurrent.CancellationException) {
                // 协程被取消（暂停 / 取消 / 移除），不重试
                throw e
            } catch (_: Exception) {
                retries++
                if (retries > MAX_RETRIES) {
                    stateMutex.withLock {
                        tasks.value[taskId]?.let { cur ->
                            val failed = cur.copy(status = DownloadStatus.FAILED)
                            tasks.update { it + (taskId to failed) }
                            persist(failed)
                        }
                    }
                    publish()
                    stopServiceIfIdle()
                    return
                }
                // 指数退避：1s, 2s, 4s...
                delay(1000L * (1L shl (retries - 1)))
            }
        }
    }

    private suspend fun executeDownload(taskId: Long, entry: DownloadEntry) {
        val safUri = taskUris[taskId]
        val target = if (safUri != null) {
            // saveAs：写用户通过系统选择器指定的文档，不参与续传。
            DownloadDestination.resolveUri(appContext, android.net.Uri.parse(safUri))
        } else {
            // overwrite：先把同名旧文件删掉，否则它的字节数会被当成续传起点。
            if (taskOverwrite.remove(taskId)) {
                DownloadDestination.deleteExisting(
                    appContext, entry.fileName, entry.downloadLocation,
                )
            }
            // 用下载条目里记录的位置解析目标。续传时把 entry.localPath 也传进去 ——
            // 若上次落在 MediaStore（公共目录），它会复用同一个 URI，而不是仅凭
            // 文件名重建（未完成的 MediaStore 条目对查询不可见，靠文件名找不到）。
            DownloadDestination.resolve(
                appContext, entry.fileName, entry.downloadLocation, entry.localPath,
            ) ?: throw java.io.IOException("无法创建下载目标（请检查存储权限）")
        }
        downloadTo(taskId, entry, target)
    }

    /** 把一次请求写进已解析好的目标；成功路径统一在这里收尾。 */
    private suspend fun downloadTo(
        taskId: Long,
        entry: DownloadEntry,
        target: DownloadDestination.Target,
    ) {
        // 续传起点取**目标上真实的字节数**，而不是库里的进度：进度是节流写库的，
        // 回退用它当起点会和真实文件错位，追加时拼出脏数据。
        val existing = target.length()

        val request = Request.Builder().url(entry.url).apply {
            // 脚本给的请求头（如 Referer / Authorization）；Range 由引擎自己算，不让脚本覆盖。
            // Cookie 也不让脚本覆盖：凭据统一来自 CookieManager，按域收窄，避免脚本
            // 把任意 Cookie 头带出去（跨域重定向时尤其危险）。
            taskHeaders[taskId]?.forEach { (key, value) ->
                val lower = key.lowercase()
                if (lower != "range" && lower != "cookie" && lower != "user-agent") {
                    header(key, value)
                }
            }
            // UA：脚本显式指定优先，其次本次任务带的页面 UA，最后退回默认值。
            val explicitUa = taskHeaders[taskId]?.entries
                ?.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value
            header(
                "User-Agent",
                explicitUa ?: taskUserAgents[taskId] ?: defaultUserAgent,
            )
            if (existing > 0) header("Range", "bytes=$existing-")
        }.build()

        client.newCall(request).execute().use { response ->
            val contentLength = response.body?.contentLength() ?: -1L
            val plan = planResume(existing, response.code, contentLength)

            if (plan.alreadyComplete) {
                // 服务器 416：本地字节已覆盖整个文件，直接判定完成。
                markSuccess(taskId, target)
                return
            }
            if (!response.isSuccessful) {
                throw java.io.IOException("HTTP ${response.code}")
            }
            if (response.body == null) throw java.io.IOException("empty body")

            // 更新状态为 RUNNING。
            // 用 plan 的追加/起点/total，而不是裸现有字节数：plan 已经处理过
            // 「服务器不支持 Range 必须从头重写」这类分支。
            stateMutex.withLock {
                val running = entry.copy(
                    status = DownloadStatus.RUNNING,
                    totalBytes = plan.total,
                    downloadedBytes = plan.start,
                    localPath = target.displayPath(),
                )
                tasks.update { it + (taskId to running) }
                persist(running)
            }
            publish()

            // 丢掉上一次的采样快照：重试或续传之间有停顿，拿旧时间戳算速度会偏小
            speedSamples.remove(taskId)
            writeBody(
                taskId = taskId,
                response = response,
                target = target,
                append = plan.append,
                total = plan.total,
                start = plan.start,
            )
        }

        // 写盘完成 → 成功
        markSuccess(taskId, target)
    }

    /**
     * 把任务标记为完成：落定目标（提交 MediaStore 条目）并写库。
     *
     * 抽出成独立方法是因为成功路径有两处入口 —— 正常写完，以及服务器返回
     * 416 表示本地已覆盖全文件 —— 两者都要做同一套收尾。
     */
    private suspend fun markSuccess(taskId: Long, target: DownloadDestination.Target) {
        target.finalize()
        // 任务已完成，附加请求头没有保留价值（重试/续传才会用到）。
        taskHeaders.remove(taskId)
        taskUserAgents.remove(taskId)
        taskUris.remove(taskId)
        taskOverwrite.remove(taskId)
        networkOverrides.remove(taskId)
        var done: DownloadEntry? = null
        stateMutex.withLock {
            tasks.value[taskId]?.let { cur ->
                val success = cur.copy(
                    status = DownloadStatus.SUCCESS,
                    downloadedBytes = if (cur.totalBytes > 0) cur.totalBytes else target.length(),
                    localPath = target.displayPath(),
                )
                tasks.update { it + (taskId to success) }
                persist(success)
                done = success
            }
        }
        publish()
        // 完成提示放在 publish 之后：此时记录已是 SUCCESS，点击通知去查库能拿到
        // 正确的 localPath。
        done?.let { DownloadService.notifyComplete(appContext, it) }
        stopServiceIfIdle()
    }

    /**
     * 把响应体流式写入目标，边写边更新进度。
     *
     * 用 [java.io.OutputStream] 而非 [java.io.RandomAccessFile]：MediaStore 目标
     * 只能通过 ContentResolver 拿到 OutputStream，统一用 OutputStream
     * 让两种目标走同一条写入路径。追加模式由 [Target.outputStream] 的
     * append 参数控制（文件目标用 FileOutputStream(append=true)，
     * MediaStore 目标用 openFileDescriptor(..., "wa")）。
     *
     * 进度节流：每 [PROGRESS_INTERVAL_BYTES] 字节或每 300ms 才更新一次 StateFlow + 写库，
     * 避免高频小文件把数据库打爆。
     */
    private suspend fun writeBody(
        taskId: Long,
        response: Response,
        target: DownloadDestination.Target,
        append: Boolean,
        total: Long,
        start: Long,
    ) {
        val body = response.body ?: return
        target.outputStream(append).use { out ->
            body.byteStream().use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                var read: Int
                var written = start
                // 字节进度与时间戳必须分开记：早先只有 lastPublish 一个变量，
                // 它存的是**字节数**，却被拿去和 now 做差（now - lastPublish），
                // 于是"每 300ms 一次"的节流永远成立 —— 实际变成每读一个 8 KiB
                // 缓冲就刷一次进度，速度采样被拖到毫秒级窗口里算，界面上的
                // 速度和「剩余时间」跟着乱跳。
                var lastPublishBytes = start
                var lastPublishAt = 0L
                while (input.read(buffer).also { read = it } != -1) {
                    // 每次写之前显式检查取消：网络读与写盘都是阻塞调用，
                    // 协程的取消不会自动打断它们。少了这一步，「暂停」只能等
                    // 整个文件下完才生效，紧接着的「恢复」又会起第二个协程
                    // 写同一个文件 —— 表现为"暂停再恢复就重下"。
                    // 抛 CancellationException 后，两层 use 会关闭流并 flush，
                    // 已下载的部分得以保留，供续传。
                    currentCoroutineContext().ensureActive()
                    out.write(buffer, 0, read)
                    written += read
                    val now = System.currentTimeMillis()
                    if (written - lastPublishBytes >= PROGRESS_INTERVAL_BYTES ||
                        now - lastPublishAt >= PROGRESS_INTERVAL_MS
                    ) {
                        lastPublishBytes = written
                        lastPublishAt = now
                        val speed = measureSpeed(taskId, written, now)
                        stateMutex.withLock {
                            tasks.value[taskId]?.let { cur ->
                                val updated = cur.copy(
                                    downloadedBytes = written,
                                    totalBytes = if (total > 0) total else cur.totalBytes,
                                    speedBytesPerSec = speed,
                                )
                                tasks.update { it + (taskId to updated) }
                                persist(updated)
                            }
                        }
                        publish()
                    }
                }
            }
            // 关闭前把页缓存刷到磁盘：写完立刻会标记 SUCCESS，用户可能在几十
            // 毫秒内就点开文件。不 sync 时个别文件系统/目标应用会先看到 0 字节，
            // 表现成"文件损坏"。失败不影响下载结果，只是少一层持久化保证。
            out.flush()
            runCatching { (out as? java.io.FileOutputStream)?.fd?.sync() }
        }
    }

    /**
     * 依据两次进度采样估算瞬时速度（字节/秒）。
     *
     * 单次「字节差 / 时间差」抖动很大（网络分片、磁盘刷盘都会造成忽快忽慢），
     * 所以用指数滑动平均（新样本占 1/4 权重）把它磨平，界面上的数字才不会
     * 每秒乱跳。首次采样没有参照，返回 0（界面此时不显示速度）。
     *
     * 采样窗口还必须够长：进度是按「每 256 KiB 或每 300ms」刷的，快网下
     * 几百 KB 只要几十毫秒就读完，窗口内算出的"瞬时速度"是分片级的抖动
     * （同一秒里能从 2 MB/s 跳到 500 KB/s）。窗口不足 [SPEED_SAMPLE_MIN_MS]
     * 时直接沿用上一次的速度，既不产生新噪声，也让「剩余时间」在两个采样
     * 点之间随已下载字节平滑下降，而不是跟着抖动上下弹。
     */
    private fun measureSpeed(taskId: Long, bytes: Long, now: Long): Long {
        val prev = speedSamples[taskId]
        if (prev == null) {
            speedSamples[taskId] = SpeedSample(bytes, now, 0)
            return 0
        }
        val deltaMs = now - prev.time
        // 窗口太短：不推进采样点，等下次累够时间再算，
        // 这样窗口始终覆盖自上个**有效**采样以来的全部字节。
        if (deltaMs < SPEED_SAMPLE_MIN_MS) return prev.speed
        val deltaBytes = bytes - prev.bytes
        prev.bytes = bytes
        prev.time = now
        // 时间或字节没推进（同一毫秒内的极小文件）时保持上一次的速度
        if (deltaBytes <= 0) return prev.speed
        val instant = deltaBytes * 1000L / deltaMs
        prev.speed = if (prev.speed <= 0L) instant else (prev.speed * 3 + instant) / 4
        return prev.speed
    }

    // -------------------------------------------------------------- 网络限制

    /**
     * 当前网络是否满足 [networkPolicy]。
     *
     * 判定以「系统当前默认网络」为准：Wi-Fi 与移动数据并存时，实际出流量走的是
     * 默认网络，按它判断才与用户账单一致。拿不到网络能力时（极少数 ROM 或权限
     * 受限）一律放行 —— 宁可能下完，也不要因为读不到状态把下载永久卡住。
     *
     * 传 [taskId] 时先看它是否已被用户单独放行（见 [forceResume]）。
     */
    private fun isNetworkAllowed(taskId: Long? = null): Boolean {
        if (taskId != null && networkOverrides.contains(taskId)) return true
        val policy = networkPolicy.value
        if (policy == DownloadNetwork.ALL) return true
        val cm = connectivityManager ?: return true
        val caps = runCatching { cm.getNetworkCapabilities(cm.activeNetwork) }.getOrNull()
            ?: return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        return when (policy) {
            DownloadNetwork.ALL -> true
            DownloadNetwork.UNMETERED ->
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            DownloadNetwork.WIFI_ONLY ->
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        }
    }

    private fun onNetworkEvent() {
        scope.launch { syncNetworkState() }
    }

    /** 网络（或策略）变化后的统一入口：满足条件就续传，不满足就自动暂停。 */
    private suspend fun syncNetworkState() {
        if (isNetworkAllowed()) resumeAutoPaused() else pauseAutoPaused()
    }

    /** 把当前在跑/排队中的任务全部停下，并记入 [autoPaused]。 */
    private suspend fun pauseAutoPaused() {
        val changed = mutableListOf<DownloadEntry>()
        stateMutex.withLock {
            // 已放行的任务不受影响：用户已经为此买过单了
            tasks.value.values
                .filter { it.isRunning && it.id !in networkOverrides }
                .forEach { cur ->
                    autoPaused.add(cur.id)
                    // 排队中的任务同样要停：它们的协程还挂在 Semaphore 上，
                    // 网络不达标时没必要继续占位等。
                    jobs.remove(cur.id)?.cancel()
                    val paused = cur.copy(status = DownloadStatus.PAUSED, speedBytesPerSec = 0L)
                    tasks.update { it + (cur.id to paused) }
                    persist(paused)
                    changed.add(paused)
                }
        }
        if (changed.isNotEmpty()) {
            publish()
            changed.forEach { _networkBlocked.tryEmit(it) }
        }
    }

    /** 网络恢复：只续传「因网络自动暂停」的任务，不碰用户手动暂停的。 */
    private suspend fun resumeAutoPaused() {
        val ids = stateMutex.withLock { autoPaused.toList().also { autoPaused.clear() } }
        ids.forEach { id ->
            val entry = tasks.value[id] ?: return@forEach
            if (entry.status == DownloadStatus.PAUSED) resume(id)
        }
    }

    /**
     * 单个任务因网络策略不达标而暂停。
     *
     * 与 [pauseAutoPaused] 的区别是它由任务自己的协程调用（此时 [jobs] 里
     * 可能还是自己），所以不能去 cancel Job —— 只落状态、进 [autoPaused]。
     */
    private suspend fun markNetworkPaused(taskId: Long) {
        var blocked: DownloadEntry? = null
        stateMutex.withLock {
            tasks.value[taskId]?.let { cur ->
                if (cur.isRunning) {
                    val paused = cur.copy(status = DownloadStatus.PAUSED, speedBytesPerSec = 0L)
                    tasks.update { it + (taskId to paused) }
                    persist(paused)
                    blocked = paused
                }
                autoPaused.add(taskId)
            }
        }
        publish()
        // 交给 UI 弹窗：这里只是「按策略停了」，用户点继续就放行（见 forceResume）。
        blocked?.let { _networkBlocked.tryEmit(it) }
    }

    private fun registerNetworkMonitor() {
        if (networkMonitorRegistered) return
        val cm = connectivityManager ?: return
        networkMonitorRegistered = runCatching {
            cm.registerDefaultNetworkCallback(defaultNetworkCallback)
        }.isSuccess
    }

    // -------------------------------------------------------------- 服务保活

    private var serviceRunning = false

    private fun ensureServiceStarted() {
        if (serviceRunning) return
        serviceRunning = true
        DownloadService.start(appContext)
    }

    private fun stopServiceIfIdle() {
        // 用 liveTasks 而非「== RUNNING」：
        //  - 任务入队后到真正开始写盘之间有一段 PENDING 窗口（建立连接、等响应），
        //    若只认 RUNNING，服务可能刚起来就因「没有进行中的任务」而自杀；
        //  - 已暂停的内建任务同样算「需要服务」：它还要从通知栏点「继续」，
        //    服务一撤通知栏就没了，暂停后就没法在通知栏恢复。
        if (tasks.value.values.liveTasks().isEmpty()) {
            serviceRunning = false
            DownloadService.stop(appContext)
        }
    }

    // -------------------------------------------------------------- 持久化

    private fun publish() {
        _downloads.value = tasks.value.values.sortedByDescending { it.createdAt }
    }

    private fun insertTask(
        fileName: String,
        url: String,
        mimeType: String?,
        status: DownloadStatus,
        location: DownloadLocation,
    ): Long? = runCatching {
        val rowId = db.writableDatabase.insertWithOnConflict(
            "downloads",
            null,
            downloadValues(
                // 占位：插入后立即用 rowId 回填，避免两条记录同为 0 触发唯一索引冲突
                downloadId = 0L,
                fileName = fileName,
                url = url,
                totalBytes = 0,
                downloadedBytes = 0,
                mimeType = mimeType,
                status = status,
                localPath = null,
                downloadLocation = location,
                createdAt = System.currentTimeMillis(),
            ),
            android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
        )
        if (rowId > 0) {
            // download_id 统一等于主键 id：引擎用它做 taskId，仓库层也用它定位
            db.writableDatabase.execSQL(
                "UPDATE downloads SET download_id = id WHERE id = ?",
                arrayOf(rowId),
            )
        }
        rowId
    }.getOrNull()?.takeIf { it > 0 }

    private fun persist(entry: DownloadEntry) {
        runCatching {
            val values = ContentValues().apply {
                put("download_id", entry.id)
                put("file_name", entry.fileName)
                put("url", entry.url)
                put("total_bytes", entry.totalBytes)
                put("downloaded_bytes", entry.downloadedBytes)
                if (entry.mimeType == null) putNull("mime_type") else put("mime_type", entry.mimeType)
                put("status", entry.status.name)
                if (entry.localPath == null) putNull("local_path") else put("local_path", entry.localPath)
                put("download_location", entry.downloadLocation.name)
                put("created_at", entry.createdAt)
            }
            db.writableDatabase.update(
                "downloads",
                values,
                "id = ?",
                arrayOf(entry.id.toString()),
            )
        }
    }

    private fun queryAll(): List<DownloadEntry> = runCatching {
        db.readableDatabase.rawQuery(
            "SELECT * FROM downloads ORDER BY created_at DESC",
            null,
        ).use { c -> buildList { while (c.moveToNext()) add(c.toDownload()) } }
    }.getOrDefault(emptyList())

    companion object {
        /** 失败重试上限（不含首次）。3 次对临时网络抖动足够，再多意义不大。 */
        private const val MAX_RETRIES = 3

        /**
         * 同时真正在传输的任务数上限。
         *
         * 超过这个数就排队（状态保持 PENDING）。3 是对移动网络与磁盘都比较友好的值：
         * 再多容易被服务端限速，也会让每个任务的进度慢到像卡住。
         */
        private const val MAX_CONCURRENT_DOWNLOADS = 3

        /** 流式写盘缓冲区：8 KiB，与 OkHttp 内部一致。 */
        private const val BUFFER_SIZE = 8 * 1024

        /** 进度刷盘间隔：256 KiB 写一次库，避免高频 IO。 */
        private const val PROGRESS_INTERVAL_BYTES = 256L * 1024

        /** 进度刷盘时间间隔：300ms，兼顾实时感与性能。 */
        private const val PROGRESS_INTERVAL_MS = 300L

        /**
         * 速度采样的最短窗口：300ms。
         *
         * 比这更短的窗口算出的"瞬时速度"只是网络分片级抖动，磨平它反而会
         * 让平均速度滞后。窗口不足时沿用上次速度，界面上的速度与剩余时间
         * 就不会在两个采样点之间上下弹。
         */
        private const val SPEED_SAMPLE_MIN_MS = PROGRESS_INTERVAL_MS
    }
}
