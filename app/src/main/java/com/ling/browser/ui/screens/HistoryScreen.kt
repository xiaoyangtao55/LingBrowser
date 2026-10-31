package com.ling.browser.ui.screens

import com.ling.browser.ui.components.lingTopBarColors
import com.ling.browser.ui.theme.LingIcons
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ling.browser.data.db.HistoryEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 浏览历史页，按日期分组展示。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    history: List<HistoryEntry>,
    onOpen: (String) -> Unit,
    onDelete: (Long) -> Unit,
    onClearAll: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** 进入页面时请求补一批网站图标（由 ViewModel 后台抓取）。 */
    onNeedIcons: () -> Unit = {},
) {
    // 只在首次进入时触发一次。每次重组都调会让 ViewModel 反复派发抓取，
    // 而一批抓完仓库会 refresh、列表变化又会重组 —— 等于自己喂自己。
    LaunchedEffect(Unit) { onNeedIcons() }
    var confirmClear by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("历史记录") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(LingIcons.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (history.isNotEmpty()) {
                        TextButton(onClick = { confirmClear = true }) { Text("清空") }
                    }
                },
                colors = lingTopBarColors(),
            )
        },
    ) { inner ->
        if (history.isEmpty()) {
            EmptyState(
                icon = LingIcons.History,
                title = "还没有浏览记录",
                hint = "无痕模式下的访问不会被记录",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner),
            )
            return@Scaffold
        }

        // 按「今天 / 昨天 / 更早」分组
        val grouped = history.groupBy { dayLabel(it.visitedAt) }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            grouped.forEach { (label, entries) ->
                item(key = "header-$label") {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(
                            start = 20.dp,
                            end = 20.dp,
                            top = 16.dp,
                            bottom = 6.dp,
                        ),
                    )
                }
                items(entries, key = { it.id }) { entry ->
                    ListItemRow(
                        title = entry.title,
                        subtitle = timeLabel(entry.visitedAt) + " · " + entry.url,
                        onClick = { onOpen(entry.url) },
                        leading = { HistoryIcon(entry = entry) },
                        trailing = {
                            IconButton(onClick = { onDelete(entry.id) }) {
                                Icon(
                                     LingIcons.DeleteOutline,
                                    contentDescription = "删除",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        },
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 20.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    )
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空浏览历史？") },
            text = { Text("此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClearAll()
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            },
        )
    }
}

/**
 * 历史记录的网站图标；没有图标时退回站点首字母方块。
 *
 * 解码结果按 `(id, 字节长度)` 缓存：同一张图只解码一次，而不是每次重组
 * 都解一遍（滚动时那会明显掉帧）。用 id 参与 key 是因为 ByteArray 每次
 * 从库里读出来都是新对象，只按它做 key 会反复失效。
 *
 * 大多数历史条目**不会有图标**：只有最近 50 条里被抓取过的才有。
 * 首字母兜底让列表保持齐整，也还能区分站点。
 */
@Composable
private fun HistoryIcon(entry: HistoryEntry) {
    val bytes = entry.favicon
    val image = remember(entry.id, bytes?.size) {
        // 空数组表示"试过但失败了"，当作没有图标
        bytes?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
    }

    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.10f)),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null && !image.isRecycled) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
        } else {
            Text(
                text = siteInitial(entry.url),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 站点首字母。与 [com.ling.browser.web.TabState.initial] 同一套规则：
 * 去掉 `www.` 前缀、解析不出 host 时给中性圆点。
 *
 * 历史里的地址都是 record() 过滤过的 http(s)，理论上不会走到兜底分支，
 * 但脏数据（导入的历史）可能带别的前缀，所以仍然要兜。
 */
private fun siteInitial(url: String): String {
    val host = runCatching { java.net.URI(url).host }.getOrNull()
    if (host.isNullOrBlank()) return "•"
    return host.removePrefix("www.").firstOrNull()?.uppercase() ?: "•"
}

private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

private fun dayLabel(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val oneDay = 24 * 60 * 60 * 1000L
    return when {
        isSameDay(timestamp, now) -> "今天"
        isSameDay(timestamp, now - oneDay) -> "昨天"
        else -> dayFormat.format(Date(timestamp))
    }
}

private fun isSameDay(a: Long, b: Long): Boolean =
    dayFormat.format(Date(a)) == dayFormat.format(Date(b))

private fun timeLabel(timestamp: Long): String = timeFormat.format(Date(timestamp))
