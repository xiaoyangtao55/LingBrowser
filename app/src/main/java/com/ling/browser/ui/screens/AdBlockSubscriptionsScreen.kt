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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ling.browser.data.repo.BlockSubscription
import com.ling.browser.ui.theme.LingIcons
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 广告拦截的**订阅名单**二级页。
 *
 * 域名表从"APK 内置"升级成"可订阅"之后：内置表只负责开箱能用（条数很少），
 * 真正的大名单由用户从**网址**或**本地文件**导入 —— 两边都支持 Hosts 与
 * AdGuard 域名格式（解析在 [com.ling.browser.web.BlockRules]）。
 *
 * 这一页做四件事：列出现有订阅、启停、更新（仅网址订阅）、删除，以及导入。
 * 每条订阅的元信息（条数 / 跳过行数 / 是否截断 / 更新时间）都如实展示：
 * 一份大名单解析后条数远小于文件行数，不解释清楚用户会以为功能坏了。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdBlockSubscriptionsScreen(
    subscriptions: List<BlockSubscription>,
    /** 内置表条数，展示"开箱自带多少条"（见 [com.ling.browser.web.AdBlocker.builtinCount]）。 */
    builtinCount: Int,
    onImportUrl: (String) -> Unit,
    onImportLocal: (Uri) -> Unit,
    onRefresh: (String) -> Unit,
    onToggle: (id: String, enabled: Boolean) -> Unit,
    onRemove: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showAdd by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<BlockSubscription?>(null) }

    // 本地导入：OpenDocument 让用户挑一个已有的名单文件。
    // 多给几个 MIME：不同来源的名单有的被标成 text/plain，有的被标成 octet-stream，
    // 只认一种会让用户"看不到自己的文件"。末尾 */* 兜底。
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) onImportLocal(uri)
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("订阅名单") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(LingIcons.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = { showAdd = true }) { Text("添加") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
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
                SectionHint(
                    title = "内置规则 $builtinCount 条（随 App 发布）",
                    hint = "内置表只保证开箱能用，广告域名天天在长。导入订阅可获得更完整的名单：" +
                        "支持 Hosts 格式（0.0.0.0 ad.example.com）与 AdGuard 域名格式" +
                        "（||ad.example.com^ 或一行一个裸域名），二者可混排。",
                )
            }

            if (subscriptions.isEmpty()) {
                item {
                    EmptyState(
                        icon = LingIcons.Block,
                        title = "还没有订阅名单",
                        hint = "点右上角「添加」从网址或本地文件导入\n支持 Hosts 与 AdGuard 域名格式",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 40.dp),
                    )
                }
            } else {
                item {
                    Text(
                        text = "订阅名单（启用后参与拦截）",
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
                items(subscriptions, key = { it.id }) { sub ->
                    SubscriptionRow(
                        subscription = sub,
                        onRefresh = { onRefresh(sub.id) },
                        onDelete = { pendingDelete = sub },
                        onToggle = { enabled -> onToggle(sub.id, enabled) },
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
            title = { Text("添加订阅名单") },
            text = {
                Column {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        singleLine = true,
                        placeholder = { Text("https://example.com/hosts.txt") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "粘贴订阅网址（http/https）。也可以从本地文件导入。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        onClick = {
                            showAdd = false
                            importLauncher.launch(arrayOf("text/plain", "text/*", "*/*"))
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

    // 删除是不可逆的（本地导入的名单删掉就得重新选一次文件），因此要确认。
    pendingDelete?.let { sub ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这份订阅？") },
            text = { Text("将移除「${sub.name}」及其已导入的 ${sub.count} 条域名。") },
            confirmButton = {
                TextButton(onClick = {
                    onRemove(sub.id)
                    pendingDelete = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

/** 单条订阅行：名称 + 元信息 + 更新 / 删除 / 启停。 */
@Composable
private fun SubscriptionRow(
    subscription: BlockSubscription,
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
                text = subscription.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = summarize(subscription),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 只有网址订阅能在线更新；本地导入的名单没有稳定的远端，点了也没用 ——
        // 干脆不显示这个按钮（而不是点了才报错）。
        if (subscription.canRefresh) {
            IconButton(onClick = onRefresh) {
                Icon(
                    LingIcons.Refresh,
                    contentDescription = "更新 ${subscription.name}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        IconButton(onClick = onDelete) {
            Icon(
                LingIcons.DeleteOutline,
                contentDescription = "删除 ${subscription.name}",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(4.dp))
        Switch(checked = subscription.enabled, onCheckedChange = onToggle)
    }
}

/**
 * 一行元信息。
 *
 * 把"生效条数 / 跳过行数 / 是否截断 / 来源 / 更新时间"都塞进来，是为了回答
 * 用户最容易产生的疑问："我导入了 20 万行的文件，怎么只显示 3000 条？"
 */
private fun summarize(subscription: BlockSubscription): String = buildString {
    append("${subscription.count} 条域名")
    if (subscription.skipped > 0) append(" · 跳过 ${subscription.skipped} 行")
    if (subscription.truncated) append(" · 已达上限")
    if (!subscription.canRefresh) append(" · 本地文件")
    append(" · 更新于 ")
    append(formatTime(subscription.updatedAt))
}

private fun formatTime(epochMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochMillis))

/** 订阅页顶部说明块：内置表条数 + 支持的格式。 */
@Composable
private fun SectionHint(title: String, hint: String) {
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
