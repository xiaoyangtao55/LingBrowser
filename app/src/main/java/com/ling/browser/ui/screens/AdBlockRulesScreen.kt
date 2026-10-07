package com.ling.browser.ui.screens

import com.ling.browser.ui.theme.LingIcons
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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

/**
 * 广告拦截的自定义规则二级页。
 *
 * 规则是**域名级**（裸域名，如 `ad.example.com`），与内置黑名单同等语义：
 * 命中即拦截，且任意深度子域名被兜住。
 *
 * 这里只做三件事：展示已有规则、删除、新增。新增时调用方（ViewModel）
 * 会用 [com.ling.browser.web.AdBlocker.normalizeRule] 清洗输入并返回是否成功，
 * 失败（非法域名）就地提示，不关对话框。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdBlockRulesScreen(
    rules: Set<String>,
    onAdd: (String) -> String?,
    onRemove: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showAdd by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("自定义规则") },
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
        val sorted = rules.sorted()
        if (sorted.isEmpty()) {
            EmptyState(
                icon = LingIcons.Block,
                title = "还没有自定义规则",
                hint = "点右上角「添加」，输入要拦截的域名\n例如 ad.example.com",
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
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            item {
                Text(
                    text = "拦截域名（含其所有子域名）",
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
            items(sorted, key = { it }) { rule ->
                ListItemRow(
                    title = rule,
                    subtitle = "拦截该域名及其全部子域名",
                    onClick = { },
                    trailing = {
                        IconButton(onClick = { onRemove(rule) }) {
                            Icon(
                                LingIcons.DeleteOutline,
                                contentDescription = "删除 $rule",
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

    if (showAdd) {
        var draft by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }

        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("添加拦截规则") },
            text = {
                Column {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = {
                            draft = it
                            error = null
                        },
                        singleLine = true,
                        placeholder = { Text("ad.example.com") },
                        modifier = Modifier.fillMaxWidth(),
                        isError = error != null,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = error
                            ?: "输入裸域名（不含 http:// 和路径），" +
                            "会拦截该域名及其全部子域名。",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (error != null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (draft.isBlank()) {
                        error = "请输入域名"
                    } else {
                        val err = onAdd(draft.trim())
                        if (err == null) {
                            showAdd = false
                        } else {
                            error = err
                        }
                    }
                }) { Text("添加") }
            },
            dismissButton = {
                TextButton(onClick = { showAdd = false }) { Text("取消") }
            },
        )
    }
}
