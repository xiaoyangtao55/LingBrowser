package com.ling.browser.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import com.ling.browser.data.repo.UserScriptEntry
import com.ling.browser.ui.components.lingTopBarColors
import com.ling.browser.ui.theme.LingIcons
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 用户脚本的管理二级页。
 *
 * P1 只做「导入 + 启停 + 删除」：脚本以页面权限运行，内置编辑器会让
 * "改一行代码"变得和"装一个脚本"一样轻，风险不对等。想改脚本请编辑
 * 本地文件后重新导入。
 *
 * 页面顶部必须写清安全边界：用户脚本能读写当前站点的 DOM 与 Cookie，
 * 不是沙箱。这不是免责声明，而是这个功能唯一容易被误用的地方。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserScriptsScreen(
    scripts: List<UserScriptEntry>,
    onImportUrl: (String) -> Unit,
    onImportLocal: (Uri) -> Unit,
    onRefresh: (String) -> Unit,
    onToggle: (id: String, enabled: Boolean) -> Unit,
    onRemove: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showAdd by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<UserScriptEntry?>(null) }

    // 脚本文件常被标成 text/plain 或 application/octet-stream，
    // 多给几种 MIME 再用 */* 兜底，避免用户"看不到自己的文件"。
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) onImportLocal(uri)
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("用户脚本") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(LingIcons.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = { showAdd = true }) { Text("添加") }
                },
                colors = lingTopBarColors(),
            )
        },
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item {
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Text(
                        text = "只导入你信任的脚本",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "用户脚本是运行在网页里的任意 JS：能读写当前站点的内容与 Cookie。" +
                            "支持 Tampermonkey / Greasemonkey 的 .user.js（@match / @include、" +
                            "document-start / document-end / document-idle）。无痕标签不会执行脚本。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (scripts.isEmpty()) {
                item {
                    EmptyState(
                        icon = LingIcons.Javascript,
                        title = "还没有用户脚本",
                        hint = "点右上角「添加」从网址或本地文件导入\n支持 .user.js 元数据格式",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 40.dp),
                    )
                }
            } else {
                item {
                    Text(
                        text = "已安装（启用后注入网页）",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(
                            start = 20.dp,
                            end = 20.dp,
                            top = 8.dp,
                            bottom = 6.dp,
                        ),
                    )
                }
                items(scripts, key = { it.id }) { entry ->
                    UserScriptRow(
                        entry = entry,
                        onRefresh = { onRefresh(entry.id) },
                        onDelete = { pendingDelete = entry },
                        onToggle = { enabled -> onToggle(entry.id, enabled) },
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 20.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    )
                }
            }
        }
    }

    if (showAdd) {
        var draft by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("添加用户脚本") },
            text = {
                Column {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        singleLine = true,
                        placeholder = { Text("https://example.com/script.user.js") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "粘贴脚本网址（http/https），也可以从本地文件导入。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        onClick = {
                            showAdd = false
                            importLauncher.launch(
                                arrayOf("text/plain", "text/javascript", "application/javascript", "*/*"),
                            )
                        },
                    ) { Text("从本地文件选择") }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onImportUrl(draft.trim())
                        showAdd = false
                    },
                    enabled = draft.isNotBlank(),
                ) { Text("导入") }
            },
            dismissButton = {
                TextButton(onClick = { showAdd = false }) { Text("取消") }
            },
        )
    }

    // 删除会连同 GM 值表一起清掉，重新导入也找不回来，必须确认。
    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这个脚本？") },
            text = {
                Text("将移除「${entry.script.name}」及其保存的脚本数据，此操作不可撤销。")
            },
            confirmButton = {
                TextButton(onClick = {
                    onRemove(entry.id)
                    pendingDelete = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

/** 单条脚本行：名称 / 版本 + 元信息 + 更新 / 删除 / 启停。 */
@Composable
private fun UserScriptRow(
    entry: UserScriptEntry,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = buildString {
                    append(entry.script.name)
                    if (entry.script.version.isNotBlank()) append("  v${entry.script.version}")
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = summarize(entry),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 本地导入的没有远端可拉，直接不显示更新按钮（而不是点了才报错）。
        if (entry.canRefresh) {
            IconButton(onClick = onRefresh) {
                Icon(
                    LingIcons.Refresh,
                    contentDescription = "更新 ${entry.script.name}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        IconButton(onClick = onDelete) {
            Icon(
                LingIcons.DeleteOutline,
                contentDescription = "删除 ${entry.script.name}",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(4.dp))
        Switch(checked = entry.enabled, onCheckedChange = onToggle)
    }
}

/**
 * 一行元信息。
 *
 * 把「规则条数 / 运行时机 / 来源 / 更新时间」都放进来：用户装完脚本最常问的
 * 就是"它到底会在哪些页面跑、有没有生效"。
 */
private fun summarize(entry: UserScriptEntry): String = buildString {
    append("${entry.script.patternLabels.size} 条匹配规则")
    append(" · ").append(
        when (entry.script.runAt) {
            com.ling.browser.web.ScriptRunAt.START -> "document-start"
            com.ling.browser.web.ScriptRunAt.END -> "document-end"
            com.ling.browser.web.ScriptRunAt.IDLE -> "document-idle"
        },
    )
    if (!entry.canRefresh) append(" · 本地文件")
    append(" · 更新于 ").append(formatTime(entry.updatedAt))
}

private fun formatTime(epochMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochMillis))
