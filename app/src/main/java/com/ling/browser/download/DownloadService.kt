package com.ling.browser.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.ling.browser.MainActivity
import com.ling.browser.R
import com.ling.browser.data.db.DownloadEntry
import com.ling.browser.data.db.DownloadStatus
import com.ling.browser.data.repo.DownloadRepository
import com.ling.browser.data.repo.supportsPauseControl
import com.ling.browser.ui.screens.formatDuration
import com.ling.browser.ui.screens.formatSpeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 下载前台服务。
 *
 * 唯一职责：下载进行中时把进程抬到前台优先级，避免被系统杀掉导致断流，
 * 并在通知栏展示进度与「暂停 / 继续 / 取消」开关。真正的下载逻辑在
 * [DownloadEngine]，服务只做保活 + 通知 + 转发按钮点击。
 *
 * 引擎通过 [start] / [stop] 控制服务生命周期：还有需要照看的任务就 startForeground，
 * 全部结束（见 [liveTasks]）就 stopSelf。
 */
class DownloadService : Service() {

    private lateinit var engine: DownloadEngine
    private lateinit var repo: DownloadRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var collectJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        val container = (application as com.ling.browser.LingApplication).container
        engine = container.downloadEngine
        repo = container.downloads
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification(engine.downloads.value.liveTasks()))
        collectJob = scope.launch {
            engine.downloads.collectLatest { updateNotification() }
        }
    }

    /**
     * 通知栏按钮点击走这里。
     *
     * 用 `PendingIntent.getService` 把动作发回本服务，再交给 [DownloadRepository]
     * 分发 —— 仓库层才知道每条记录是内建引擎还是系统下载器，暂停 / 取消的
     * 语义因此统一，不必在服务里再判断一遍来源。
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val taskId = intent?.getLongExtra(EXTRA_TASK_ID, -1L) ?: -1L
        if (taskId > 0) {
            when (intent?.action) {
                ACTION_PAUSE -> runAction { entryOf(taskId)?.let { repo.pause(it) } }
                ACTION_RESUME -> runAction { entryOf(taskId)?.let { repo.resume(it) } }
                ACTION_CANCEL -> runAction { entryOf(taskId)?.let { repo.cancel(it) } }
            }
        }
        return START_STICKY
    }

    /**
     * 执行一个通知栏动作，随后立刻按引擎最新状态刷新通知。
     *
     * 刷新不能只靠 [engine.downloads] 的流发射：那条路径的时序不受按钮控制，
     * 一旦错过（例如按下暂停后状态已变、但通知仍停在上一次构建结果），
     * 就会出现「实际已暂停，通知却还是暂停按钮」。动作执行完主动刷一次，
     * 与流发射互不依赖，谁先到都不影响最终结果。
     *
     * 动作要写库（改状态 / 删记录），放在 [Dispatchers.IO] 上执行。
     */
    private fun runAction(block: suspend () -> Unit) {
        scope.launch(Dispatchers.IO) {
            block()
            withContext(Dispatchers.Main) { updateNotification() }
        }
    }

    /**
     * 按引擎当前状态刷新前台通知；没有需要照看的任务时撤掉通知并退出。
     */
    private fun updateNotification() {
        val live = engine.downloads.value.liveTasks()
        if (live.isEmpty()) {
            // 先撤前台通知再退出。若反过来（先 notify 再 stopSelf），那条同 ID 的
            // 通知已经被降级成普通通知，stopSelf 不会回收它 —— 表现为
            // 「取消下载后通知残留成『等待中』」。
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(live))
    }

    private fun entryOf(taskId: Long): DownloadEntry? =
        engine.downloads.value.firstOrNull { it.id == taskId }

    override fun onDestroy() {
        collectJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // -------------------------------------------------------------- 通知

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "下载",
                NotificationManager.IMPORTANCE_LOW, // 低重要度：不响铃、不弹出，只在通知栏常驻
            ).apply {
                description = "文件下载进度"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    /**
     * 构建下载通知。
     *
     * 多个下载同时进行时只展示聚合信息（N 个任务），避免通知栏被刷爆；
     * 单个下载时展示文件名和进度百分比，并带上下载速度、预计剩余时间，
     * 以及「暂停 / 继续 / 取消」按钮。
     */
    private fun buildNotification(live: List<DownloadEntry>): Notification {
        val title: String
        val text: String
        val progress: Int
        val indeterminate: Boolean

        when {
            live.isEmpty() -> {
                title = "下载"
                text = "等待中"
                progress = 0
                indeterminate = true
            }
            live.size == 1 -> {
                val d = live.first()
                title = d.fileName
                text = if (d.status == DownloadStatus.PAUSED) "已暂停" else singleLine(d)
                progress = if (d.totalBytes > 0) (d.progress * 100).toInt() else 0
                // 暂停时不显示不确定进度条（它就是停的）
                indeterminate = d.status != DownloadStatus.PAUSED && d.totalBytes <= 0
            }
            else -> {
                val running = live.filter { it.isRunning }
                val pausedCount = live.size - running.size
                val totalDone = running.sumOf { it.downloadedBytes }
                val totalSize = running.sumOf { it.totalBytes }
                title = "${live.size} 个任务"
                text = buildString {
                    if (running.isEmpty()) {
                        append("全部已暂停")
                    } else {
                        append("${running.size} 个下载中")
                        if (totalSize > 0) append(" · ${(totalDone * 100 / totalSize).toInt()}%")
                        val speed = formatSpeed(running.sumOf { it.speedBytesPerSec })
                        if (speed.isNotEmpty()) append(" · ").append(speed)
                    }
                    if (pausedCount > 0) append(" · ${pausedCount} 个已暂停")
                }
                progress = if (totalSize > 0) (totalDone * 100 / totalSize).toInt() else 0
                indeterminate = running.isNotEmpty() && totalSize <= 0
            }
        }

        // 点击通知打开应用
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_download)
            .setContentIntent(pi)
            .setOngoing(true)
            .setProgress(100, progress, indeterminate)

        // 只有单条任务时才加按钮：聚合通知里按钮指向哪条是含糊的。
        // 且只对内建下载显示暂停/继续 —— 系统 DownloadManager 无应用内暂停能力。
        if (live.size == 1 && live.first().supportsPauseControl) {
            val d = live.first()
            if (d.status == DownloadStatus.PAUSED) {
                builder.addAction(
                    android.R.drawable.ic_media_play, "继续",
                    actionIntent(d.id, ACTION_RESUME, REQ_RESUME),
                )
            } else {
                builder.addAction(
                    android.R.drawable.ic_media_pause, "暂停",
                    actionIntent(d.id, ACTION_PAUSE, REQ_PAUSE),
                )
            }
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel, "取消",
                actionIntent(d.id, ACTION_CANCEL, REQ_CANCEL),
            )
        }

        return builder.build()
    }

    /** 单条下载的副标题：等待中 / 百分比 · 速度 · 剩余时间（缺失的部分自动省略）。 */
    private fun singleLine(d: DownloadEntry): String {
        if (d.status == DownloadStatus.PENDING) return "等待中"
        val speed = formatSpeed(d.speedBytesPerSec)
        if (d.totalBytes <= 0) {
            // 总大小未知时只能报"下载中"，有速度就顺带报一下
            return if (speed.isEmpty()) "下载中" else "下载中 · $speed"
        }
        return buildString {
            append("${(d.progress * 100).toInt()}%")
            if (speed.isNotEmpty()) append(" · ").append(speed)
            d.etaSeconds?.let { append(" · 剩余 ").append(formatDuration(it)) }
        }
    }

    /**
     * 把通知栏按钮的点击包成发回本服务的 PendingIntent。
     *
     * requestCode 必须区分「动作 × 任务」：若共用同一个 requestCode，
     * 不同按钮的 PendingIntent 会互相覆盖（`FLAG_UPDATE_CURRENT` 的语义），
     * 表现为点「取消」却触发了「暂停」。
     */
    private fun actionIntent(taskId: Long, action: String, base: Int): PendingIntent {
        val intent = Intent(this, DownloadService::class.java).apply {
            this.action = action
            putExtra(EXTRA_TASK_ID, taskId)
        }
        val requestCode = base * 1000 + (taskId % 1000).toInt()
        return PendingIntent.getService(
            this, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private const val CHANNEL_ID = "download"
        private const val NOTIFICATION_ID = 1001

        private const val EXTRA_TASK_ID = "com.ling.browser.extra.TASK_ID"
        private const val ACTION_PAUSE = "com.ling.browser.action.PAUSE"
        private const val ACTION_RESUME = "com.ling.browser.action.RESUME"
        private const val ACTION_CANCEL = "com.ling.browser.action.CANCEL"
        private const val REQ_PAUSE = 1
        private const val REQ_RESUME = 2
        private const val REQ_CANCEL = 3

        fun start(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DownloadService::class.java))
        }
    }
}

/**
 * 服务需要「照看」的任务集合：进行中的（PENDING / RUNNING），以及已暂停但
 * 能从通知栏点「继续」恢复的内建下载。
 *
 * 这个集合是否为空，就是服务是否该退出的唯一判据 —— 引擎的
 * `stopServiceIfIdle()` 与服务的 `collectJob` 共用它，避免两处条件写歪。
 *
 * 系统 DownloadManager 的暂停态不纳入：应用内没法恢复它，留着一个永远
 * 恢复不了的前台通知只会白耗电。
 */
internal fun Collection<DownloadEntry>.liveTasks(): List<DownloadEntry> =
    filter { it.isRunning || (it.status == DownloadStatus.PAUSED && it.supportsPauseControl) }
