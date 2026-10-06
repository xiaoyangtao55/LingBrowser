package com.ling.browser.ui.screens

import com.ling.browser.ui.theme.LingIcons
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
import androidx.compose.material3.TopAppBarDefaults
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
) {
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
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
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
