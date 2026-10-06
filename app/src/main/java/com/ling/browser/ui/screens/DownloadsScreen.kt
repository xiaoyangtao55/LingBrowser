package com.ling.browser.ui.screens

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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.delay

/**
 * 下载管理页。
 *
 * 列表内容来自 [DownloadEntry]（本地表 + 向系统 DownloadManager 回查的结果）。
 * DownloadManager 没有进度回调，只能轮询 —— 这里在页面可见时每秒刷新一次，
 * 离开页面立即停止，不在后台空转耗电。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    downloads: List<DownloadEntry>,
    onRefresh: () -> Unit,
    onCancel: (DownloadEntry) -> Unit,
    onRemove: (DownloadEntry, Boolean) -> Unit,
    onClearAll: (Boolean) -> Unit,
    onOpen: (DownloadEntry) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var clearDialog by remember { mutableStateOf(false) }

    // 轮询进度：只在页面可见时跑。
    // 即使没有进行中的任务也保持轮询 —— 用户可能刚好从网页触发了一个下载。
    // 每秒一次的开销极小（一次 binder 查询），换来进度条的实时感。
    LaunchedEffect(Unit) {
        while (true) {
            onRefresh()
            delay(POLL_INTERVAL_MS)
        }
    }
    // 离开页面时再刷一次，保证退出前的最终状态落库
    DisposableEffect(Unit) {
        onDispose { onRefresh() }
    }

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
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
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

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            items(downloads, key = { it.id }) { entry ->
                DownloadRow(
                    entry = entry,
                    onCancel = { onCancel(entry) },
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
    onRemove: (Boolean) -> Unit,
    onOpen: () -> Unit,
) {
    var confirmRemove by remember { mutableStateOf(false) }
    val canOpen = entry.status == DownloadStatus.SUCCESS && entry.localPath != null

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = canOpen, onClick = onOpen)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = LingIcons.Download,
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
            if (entry.isRunning) {
                IconButton(onClick = onCancel) {
                    Icon(
                        LingIcons.Close,
                        contentDescription = "取消下载",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            } else {
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

        // 进度条只在下载中显示：已完成/失败的条目不需要这条线占地方
        if (entry.isRunning) {
            Spacer(Modifier.height(8.dp))
            if (entry.totalBytes > 0) {
                LinearProgressIndicator(
                    progress = { entry.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                // 总大小未知（服务器没给 Content-Length）时用不确定进度条
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
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

/** 副标题：状态 + 大小/进度。 */
private fun subtitleOf(entry: DownloadEntry): String = when {
    entry.status == DownloadStatus.RUNNING && entry.totalBytes > 0 ->
        "${entry.status.label} ${(entry.progress * 100).toInt()}%  ·  ${formatBytes(entry.totalBytes)}"
    entry.status == DownloadStatus.SUCCESS ->
        buildString {
            append(entry.status.label)
            val size = if (entry.totalBytes > 0) entry.totalBytes else entry.downloadedBytes
            if (size > 0) append("  ·  ${formatBytes(size)}")
        }
    entry.isRunning -> entry.status.label
    else -> entry.status.label
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

/** 轮询间隔。1 秒足够跟手，又不会让 binder 查询显得频繁。 */
private const val POLL_INTERVAL_MS = 1000L
