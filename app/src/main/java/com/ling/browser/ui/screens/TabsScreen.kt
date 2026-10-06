package com.ling.browser.ui.screens

import com.ling.browser.ui.theme.LingIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ling.browser.data.prefs.TabsHeight
import com.ling.browser.web.TabState

/**
 * 标签页管理页。
 *
 * 高度由 [tabsHeight] 控制，默认只占屏幕下 1/4 —— 这样切标签时仍能看到
 * 底下的网页，符合"标签页是临时面板而非独立页面"的直觉。
 * 用列表而非网格：手机上列表更容易扫读标题，也省去缩略图带来的内存开销。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabsScreen(
    tabs: List<TabState>,
    activeId: String,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onNewTab: () -> Unit,
    onNewIncognitoTab: () -> Unit,
    onCloseAll: () -> Unit,
    onBack: () -> Unit,
    tabsHeight: TabsHeight,
    onTabsHeightChange: (TabsHeight) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 面板从底部升起：用 Box + align 而不是 fillMaxSize，短面板下方不会留白
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.32f))
            // 点击面板外的遮罩也可关闭
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onBack,
            ),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            shape = if (tabsHeight == TabsHeight.FULL) {
                RectangleShape
            } else {
                RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
            },
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            // 高度不够时内部列表自行滚动，不会把面板撑破
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(tabsHeight.fraction)
                // 面板自身拦截点击，避免穿透到遮罩把面板关掉
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
        ) {
            Scaffold(
                containerColor = Color.Transparent,
                topBar = {
                    CenterAlignedTopAppBar(
                        title = { Text("标签页 (${tabs.size})") },
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(LingIcons.Close, contentDescription = "关闭")
                            }
                        },
                        actions = {
                            // 高度档位切换
                            HeightToggle(
                                current = tabsHeight,
                                onChange = onTabsHeightChange,
                            )
                            if (tabs.size > 1) {
                                TextButton(onClick = onCloseAll) { Text("关闭全部") }
                            }
                        },
                        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                            containerColor = Color.Transparent,
                        ),
                    )
                },
                bottomBar = {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            PillButton(
                                icon = LingIcons.Add,
                                label = "新建标签页",
                                container = MaterialTheme.colorScheme.primaryContainer,
                                content = MaterialTheme.colorScheme.onPrimaryContainer,
                                onClick = onNewTab,
                                modifier = Modifier.weight(1f),
                            )
                            PillButton(
                                icon = LingIcons.PrivacyTip,
                                label = "无痕",
                                container = MaterialTheme.colorScheme.tertiaryContainer,
                                content = MaterialTheme.colorScheme.onTertiaryContainer,
                                onClick = onNewIncognitoTab,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                },
            ) { inner ->
                if (tabs.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(inner),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                LingIcons.Layers,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(40.dp),
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "没有打开的标签页",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    return@Scaffold
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(inner),
                    contentPadding = PaddingValues(
                        horizontal = 12.dp,
                        vertical = 8.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(tabs, key = { it.id }) { tab ->
                        TabRow(
                            tab = tab,
                            isActive = tab.id == activeId,
                            onSelect = { onSelect(tab.id) },
                            onClose = { onClose(tab.id) },
                        )
                    }
                }
            }
        }
    }
}

/** 高度档位切换：全屏 / 一半 / 四分之一。 */
@Composable
private fun HeightToggle(
    current: TabsHeight,
    onChange: (TabsHeight) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        TabsHeight.entries.forEach { h ->
            val selected = h == current
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (selected) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    Color.Transparent
                },
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onChange(h) },
            ) {
                Text(
                    text = h.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                )
            }
        }
    }
}

/** 底部胶囊按钮。 */
@Composable
private fun PillButton(
    icon: ImageVector,
    label: String,
    container: Color,
    content: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = container,
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = content,
            )
        }
    }
}

@Composable
private fun TabRow(
    tab: TabState,
    isActive: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = when {
            isActive -> MaterialTheme.colorScheme.primaryContainer
            tab.isIncognito -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f)
            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        },
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onSelect),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (tab.isIncognito) {
                Icon(
                     LingIcons.PrivacyTip,
                    contentDescription = "无痕",
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = tab.displayTitle,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isActive) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (tab.displayUrl.isNotEmpty()) {
                    Text(
                        text = tab.displayUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isActive) {
                            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onClose) {
                Icon(
                     LingIcons.Close,
                    contentDescription = "关闭标签页",
                    tint = if (isActive) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
