package com.ling.browser.ui.screens

import com.ling.browser.ui.theme.LingIcons
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.ling.browser.web.SniffResult

/**
 * 资源嗅探结果页。
 *
 * 列出当前页面里嗅探到的媒体资源（视频/音频/图片/可下载文件），
 * 每条提供两个动作：
 *   - 点击行 → 在新标签页打开该资源（看图/看视频/听音频）
 *   - 尾部「下载」→ 直接入队下载
 *
 * 结果来自 [com.ling.browser.ui.BrowserViewModel.sniffResults]，
 * 是进入本页前触发嗅探得到的一次性快照，本页只读不写。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SniffedResourcesScreen(
    result: SniffResult,
    onOpen: (String) -> Unit,
    onDownload: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("资源嗅探") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(LingIcons.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { inner ->
        when (result) {
            is SniffResult.Error -> {
                EmptyState(
                    icon = LingIcons.Movie,
                    title = "嗅探失败",
                    hint = "请返回后重试",
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(inner),
                )
                return@Scaffold
            }
            is SniffResult.Ok -> Unit
        }

        val resources = (result as SniffResult.Ok).resources
        if (resources.isEmpty()) {
            EmptyState(
                icon = LingIcons.Movie,
                title = "没有可嗅探的资源",
                hint = "这页没有视频、音频、图片或可下载的文件",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner),
            )
            return@Scaffold
        }

        // 按类型分组：视频 → 音频 → 图片 → 下载，组内保持原始顺序
        val order = mapOf(
            SniffResult.Resource.Type.VIDEO to 0,
            SniffResult.Resource.Type.AUDIO to 1,
            SniffResult.Resource.Type.IMAGE to 2,
            SniffResult.Resource.Type.DOWNLOAD to 3,
        )
        val sorted = resources.sortedBy { order[it.type] ?: 4 }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            item {
                Text(
                    text = "共 ${resources.size} 个资源",
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
            items(sorted, key = { it.url + it.type }) { res ->
                ListItemRow(
                    title = res.title.ifBlank { res.url },
                    subtitle = typeLabel(res.type) + " · " + res.url,
                    onClick = { onOpen(res.url) },
                    leading = {
                        Icon(
                            typeIcon(res.type),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp),
                        )
                    },
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { onOpen(res.url) }) {
                                Icon(
                                    LingIcons.Public,
                                    contentDescription = "打开",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            Spacer(Modifier.width(4.dp))
                            IconButton(onClick = { onDownload(res.url) }) {
                                Icon(
                                    LingIcons.Download,
                                    contentDescription = "下载",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
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

private fun typeLabel(type: SniffResult.Resource.Type): String = when (type) {
    SniffResult.Resource.Type.VIDEO -> "视频"
    SniffResult.Resource.Type.AUDIO -> "音频"
    SniffResult.Resource.Type.IMAGE -> "图片"
    SniffResult.Resource.Type.DOWNLOAD -> "文件"
}

private fun typeIcon(type: SniffResult.Resource.Type): ImageVector = when (type) {
    SniffResult.Resource.Type.VIDEO,
    SniffResult.Resource.Type.AUDIO -> LingIcons.Movie
    SniffResult.Resource.Type.IMAGE -> LingIcons.Image
    SniffResult.Resource.Type.DOWNLOAD -> LingIcons.Download
}
