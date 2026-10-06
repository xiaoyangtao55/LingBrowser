package com.ling.browser.ui.components

import com.ling.browser.ui.theme.LingIcons
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 底部工具栏。
 *
 * 放 5 个动作：后退 / 前进 / 主页 / 标签页 / 更多。
 * 刷新已经上移到地址栏右侧（最高频操作，放在地址栏更顺手）；
 * 电脑模式、夜间模式、书签、设置等低频操作收进「更多」菜单 ——
 * 一排塞太多图标在手机上会挤成一团，也违背极简的初衷。
 *
 * 注意 [moreExpanded] 与 [onMoreDismiss] 由调用方持有：
 * 下拉菜单必须锚定在「更多」按钮自己身上，否则会跑到屏幕左边缘。
 */
@Composable
fun BottomToolbar(
    canGoBack: Boolean,
    canGoForward: Boolean,
    tabCount: Int,
    isIncognito: Boolean,
    moreExpanded: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onHome: () -> Unit,
    onTabs: () -> Unit,
    onMoreToggle: () -> Unit,
    onMoreDismiss: () -> Unit,
    // modifier 必须排在 moreContent **之前**：Kotlin 的尾随 lambda 只会绑定到
    // 最后一个形参，若 Modifier 排在最后，调用方的 `BottomToolbar(...) { 菜单项 }`
    // 会被当成 modifier 传进去（报 "actual type is Function0<Unit>, but Modifier
    // was expected"），而 moreContent 反而漏传。这也是 Compose 的既有约定：
    // modifier 是第一个可选参数，插槽 lambda 排最后。
    modifier: Modifier = Modifier,
    moreContent: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            ToolbarButton(
                icon = LingIcons.ArrowBack,
                label = "后退",
                enabled = canGoBack,
                onClick = onBack,
            )
            ToolbarButton(
                icon = LingIcons.ArrowForward,
                label = "前进",
                enabled = canGoForward,
                onClick = onForward,
            )
            ToolbarButton(
                icon = LingIcons.Home,
                label = "主页",
                enabled = true,
                onClick = onHome,
            )
            ToolbarButton(
                icon = LingIcons.Layers,
                label = "标签页",
                enabled = true,
                badge = tabCount.takeIf { it > 1 }?.toString(),
                incognito = isIncognito,
                onClick = onTabs,
            )

            // 「更多」按钮自带下拉菜单锚点，菜单因此对齐在右侧按钮下方
            Box {
                ToolbarButton(
                    icon = LingIcons.MoreVert,
                    label = "更多",
                    enabled = true,
                    onClick = onMoreToggle,
                )
                DropdownMenu(
                    expanded = moreExpanded,
                    onDismissRequest = onMoreDismiss,
                    content = moreContent,
                )
            }
        }
    }
}

@Composable
private fun ToolbarButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    badge: String? = null,
    incognito: Boolean = false,
) {
    val tint = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
        incognito -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
        // 标签数角标：只在多于 1 个标签时出现
        if (badge != null) {
            Surface(
                shape = CircleShape,
                color = if (incognito) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.primary
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-6).dp, y = 6.dp),
            ) {
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (incognito) {
                        MaterialTheme.colorScheme.onTertiary
                    } else {
                        MaterialTheme.colorScheme.onPrimary
                    },
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
}

/** 无痕模式下的顶部提示条。 */
@Composable
fun IncognitoBanner(modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = LingIcons.PrivacyTip,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = "  无痕模式 · 不记录历史与 Cookie",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}

/** 电脑模式提示条。 */
@Composable
fun DesktopModeBanner(modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = LingIcons.DesktopWindows,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = "  电脑模式 · 已使用桌面版 UA",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}
