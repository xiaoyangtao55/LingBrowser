package com.ling.browser.ui.screens

import com.ling.browser.ui.components.lingTopBarColors
import com.ling.browser.ui.theme.LingIcons
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ling.browser.data.db.DownloadEntry
import com.ling.browser.data.db.DownloadStatus
import com.ling.browser.data.repo.DownloadSource
import com.ling.browser.data.repo.source

/**
 * 下载管理页。
 *
 * 列表内容来自内建下载引擎（[DownloadEntry]），进度是推送式的实时更新，
 * 不再需要轮询系统 DownloadManager。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    downloads: List<DownloadEntry>,
    onRefresh: () -> Unit,
    onCancel: (DownloadEntry) -> Unit,
    onPause: (DownloadEntry) -> Unit,
    onResume: (DownloadEntry) -> Unit,
    onRemove: (DownloadEntry, Boolean) -> Unit,
    onClearAll: (Boolean) -> Unit,
    onOpen: (DownloadEntry) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var clearDialog by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("下载") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(LingIcons.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (downloads.isNotEmpty()) {
                        TextButton(onClick = { clearDialog = true }) { Text("清空") }
                    }
                },
                colors = lingTopBarColors(),
            )
        },
    ) { inner ->
        if (downloads.isEmpty()) {
            EmptyState(
                icon = LingIcons.Download,
                title = "还没有下载",
                hint = "网页里的下载链接会出现在这里",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner),
            )
            return@Scaffold
        }

        // 时间分段（今天 / 昨天 / 最近 7 天 / …），组内新 → 旧。
        // downloads 在下载中会高频变化，但分段只依赖 createdAt，重算成本可忽略。
        val sections = remember(downloads) { downloadSections(downloads) }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            sections.forEach { section ->
                item(key = "section:${section.group.name}") {
                    Text(
                        text = section.group.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
                    )
                }
                items(section.entries, key = { it.id }) { entry ->
                    DownloadRow(
                        entry = entry,
                        onCancel = { onCancel(entry) },
                        onPause = { onPause(entry) },
                        onResume = { onResume(entry) },
                        onRemove = { deleteFile -> onRemove(entry, deleteFile) },
                        onOpen = { onOpen(entry) },
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 20.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    )
                }
            }
        }
    }

    if (clearDialog) {
        AlertDialog(
            onDismissRequest = { clearDialog = false },
            title = { Text("清空下载记录？") },
            text = { Text("已下载的文件不会删除，只清除这里的列表。") },
            confirmButton = {
                TextButton(onClick = {
                    onClearAll(false)
                    clearDialog = false
                }) { Text("仅清记录") }
            },
            dismissButton = {
                // 同时删文件是破坏性操作，放在次要位置并写清后果
                TextButton(onClick = {
                    onClearAll(true)
                    clearDialog = false
                }) { Text("连同文件删除") }
            },
        )
    }
}

/** 单条下载。 */
@Composable
private fun DownloadRow(
    entry: DownloadEntry,
    onCancel: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRemove: (Boolean) -> Unit,
    onOpen: () -> Unit,
) {
    var confirmRemove by remember { mutableStateOf(false) }
    // 只要「已完成」就允许点击。
    //
    // 这里刻意**不再**要求 localPath 非空：早期版本写库时没存路径、或记录来自
    // 系统下载器还没回查，都会出现「状态已完成但没有路径」。以前把 localPath
    // 当作可点击的前提，结果是点击连涟漪都没有 —— 用户只看到"点了没反应"，
    // 也不知道是文件没了还是功能坏了。现在统一交给打开逻辑，由它按文件名兜底
    // 查找，找不到再明确提示。
    val canOpen = entry.status == DownloadStatus.SUCCESS
    // 三种「进行中」都要操作按钮：
    //   RUNNING        → 暂停 + 取消
    //   PAUSED         → 恢复 + 取消（用户主动停的）
    //   WAITING_NETWORK→ 取消（恢复网络会自己续，不需要用户点）
    val hasActiveControls = entry.status == DownloadStatus.RUNNING ||
        entry.status == DownloadStatus.PAUSED ||
        entry.status == DownloadStatus.WAITING_NETWORK

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = canOpen, onClick = onOpen)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = fileIconFor(entry.fileName, entry.mimeType),
                contentDescription = null,
                tint = when (entry.status) {
                    DownloadStatus.SUCCESS -> MaterialTheme.colorScheme.primary
                    DownloadStatus.FAILED -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.fileName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitleOf(entry),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (entry.status == DownloadStatus.FAILED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            // 系统下载器（DownloadManager）不支持应用内暂停/恢复，
            // 只提供「取消」；内建引擎才显示暂停/恢复按钮。
            val isBuiltIn = entry.source == DownloadSource.BUILT_IN
            when (entry.status) {
                DownloadStatus.RUNNING -> {
                    if (isBuiltIn) {
                        IconButton(onClick = onPause) {
                            Icon(
                                LingIcons.Pause,
                                contentDescription = "暂停",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    IconButton(onClick = onCancel) {
                        Icon(
                            LingIcons.Close,
                            contentDescription = "取消并删除文件",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                DownloadStatus.WAITING_NETWORK -> {
                    // 等网络时**不提供「恢复」按钮**：网络回来会自动继续，
                    // 这里再放一个可点按钮反而让用户以为要手动干预。
                    // 只保留取消（有人就是想彻底不下这个文件）。
                    IconButton(onClick = onCancel) {
                        Icon(
                            LingIcons.Close,
                            contentDescription = "取消并删除文件",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                DownloadStatus.PAUSED -> {
                    if (isBuiltIn) {
                        IconButton(onClick = onResume) {
                            Icon(
                                LingIcons.PlayArrow,
                                contentDescription = "恢复",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    IconButton(onClick = onCancel) {
                        Icon(
                            LingIcons.Close,
                            contentDescription = "取消并删除文件",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                else -> {
                    IconButton(onClick = { confirmRemove = true }) {
                        Icon(
                            LingIcons.DeleteOutline,
                            contentDescription = "删除记录",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }

        // 进度条在下载中或已暂停时显示：暂停时让用户看到已下载的部分
        if (hasActiveControls) {
            Spacer(Modifier.height(8.dp))
            when {
                // 等网络：用**不确定态**（来回跑的条）表达「在推进、但暂时没在动」。
                // 用确定态的定格条会让人以为卡死了，而这其实是一个会自愈的状态。
                entry.status == DownloadStatus.WAITING_NETWORK ->
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

                entry.totalBytes > 0 -> LinearProgressIndicator(
                    progress = { entry.progress },
                    modifier = Modifier.fillMaxWidth(),
                )

                // 总大小未知（服务器没给 Content-Length）时用不确定进度条
                else -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }

        if (confirmRemove) {
            AlertDialog(
                onDismissRequest = { confirmRemove = false },
                title = { Text("删除这条记录？") },
                text = { Text(entry.fileName) },
                confirmButton = {
                    TextButton(onClick = {
                        // 默认只删记录不删文件 —— 文件是用户的，误删代价高
                        onRemove(false)
                        confirmRemove = false
                    }) { Text("仅删记录") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        onRemove(true)
                        confirmRemove = false
                    }) { Text("连同文件删除") }
                },
            )
        }
    }
}

/** 副标题：状态 + 大小/进度/速度/剩余时间。 */
private fun subtitleOf(entry: DownloadEntry): String = when {
    entry.status == DownloadStatus.RUNNING -> runningSubtitle(entry)
    // 等网络时补一句「已下多少」：用户最关心的是进度有没有丢，
    // 而不是当前有没有在传。
    entry.status == DownloadStatus.WAITING_NETWORK -> buildString {
        append(entry.status.label)
        if (entry.totalBytes > 0) {
            append(" ${(entry.progress * 100).toInt()}%  ·  ")
            append("已下载 ${formatBytes(entry.downloadedBytes)}")
        } else if (entry.downloadedBytes > 0) {
            append("  ·  已下载 ${formatBytes(entry.downloadedBytes)}")
        }
    }
    entry.status == DownloadStatus.PAUSED && entry.totalBytes > 0 ->
        "${entry.status.label} ${(entry.progress * 100).toInt()}%  ·  ${formatBytes(entry.totalBytes)}"
    entry.status == DownloadStatus.SUCCESS ->
        buildString {
            append(entry.status.label)
            val size = if (entry.totalBytes > 0) entry.totalBytes else entry.downloadedBytes
            if (size > 0) append("  ·  ${formatBytes(size)}")
        }
    else -> entry.status.label
}

/**
 * 下载中的副标题：进度 + 速度 + 剩余时间。
 *
 * 速度/剩余时间依赖引擎推送的 [DownloadEntry.speedBytesPerSec]；刚开始下载
 * 的头几百毫秒还没采样到速度，或服务器不给 Content-Length 时，对应片段会被
 * 自动省略，而不是显示 "0 B/s"。
 */
private fun runningSubtitle(entry: DownloadEntry): String = buildString {
    append(entry.status.label)
    if (entry.totalBytes > 0) {
        append(" ${(entry.progress * 100).toInt()}%  ·  ${formatBytes(entry.totalBytes)}")
    } else if (entry.downloadedBytes > 0) {
        append("  ·  ${formatBytes(entry.downloadedBytes)}")
    }
    val speed = formatSpeed(entry.speedBytesPerSec)
    if (speed.isNotEmpty()) append("  ·  $speed")
    entry.etaSeconds?.let { append("  ·  剩余 ${formatDuration(it)}") }
}

/**
 * 下载速度。
 *
 * 复用体积口径再加 "/s"，不另立一套单位 —— 否则列表里会出现
 * "1.0 MB / 1048576 B/s" 这种对不上的观感。速度为 0（未采样到）时返回空串，
 * 由调用方决定是否显示。
 */
internal fun formatSpeed(bytesPerSec: Long): String =
    if (bytesPerSec <= 0) "" else formatBytes(bytesPerSec) + "/s"

/**
 * 剩余时间：秒 → `mm:ss`，超过一小时用 `h:mm:ss`。
 *
 * 负数（算不出剩余量）返回 "--"，与界面上的占位口径一致。
 */
internal fun formatDuration(seconds: Long): String {
    if (seconds < 0) return "--"
    val h = seconds / 3600
    val m = seconds % 3600 / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

/**
 * 人类可读的体积。
 *
 * 按 1024 进制而不是 1000：下载大小在系统各处都按 KiB/MiB 显示，
 * 用 1000 会和系统下载列表对不上，用户会以为文件被改了。
 */
internal fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var idx = 0
    while (value >= 1024 && idx < units.lastIndex) {
        value /= 1024
        idx++
    }
    // 小于 10 时保留一位小数，否则整数即可，避免 "1023.9 KB" 这种噪音
    return if (value < 10) "%.1f %s".format(value, units[idx])
    else "%.0f %s".format(value, units[idx])
}
