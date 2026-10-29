package com.ling.browser.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ling.browser.ui.theme.LingIcons
import com.ling.browser.ui.theme.LingMotion

/**
 * 底部工具栏。
 *
 * 放 5 个动作：后退 / 前进 / 主页 / 标签页 / 更多。
 * 刷新已经上移到地址栏右侧（最高频操作，放在地址栏更顺手）；
 * 电脑模式、夜间模式、书签、设置等低频操作收进「更多」底下。
 *
 * 「更多」与「标签页」都用 **BottomSheet** 呈现（见 BrowserScreen 的
 * ModalBottomSheet）：面板自屏幕底部升起，单手可及、条目更大更好点，
 * 比锚定式下拉菜单更贴合 Material 3 的交互范式。
 * 因此这里只把点击转出去，不再自己持有菜单锚点与展开状态。
 *
 * 底部手势条（小白条）：系统栏内边距由**工具栏自己**吃，而不是在 [Surface]
 * 外面留。这样工具栏的底色一路铺到屏幕最下沿，只有按钮内容被顶到手势条之上
 * —— 否则手势区会露出页面背景色，工具栏与屏幕底部之间裂开一条缝。
 *
 * 形态是**贴边通栏**：底色铺满整条下沿，与页面内容齐平接缝，而不是浮在
 * 内容之上的胶囊。
 */
@Composable
fun BottomToolbar(
    canGoBack: Boolean,
    canGoForward: Boolean,
    tabCount: Int,
    isIncognito: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onHome: () -> Unit,
    onTabs: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 内边距在内、高度在外：Surface 高度 = 52dp + 手势条高度，
                // 底色铺满，按钮行仍保持 52dp。
                .navigationBarsPadding()
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
            ToolbarButton(
                icon = LingIcons.MoreVert,
                label = "更多",
                enabled = true,
                onClick = onMore,
            )
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
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // 按压时轻微"捏一下"：M3E 的触感反馈来自形变，而不是只有水波纹。
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        animationSpec = LingMotion.expressive,
        label = "toolbarButtonScale",
    )

    val tint = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
        incognito -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = Modifier
            .size(46.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(14.dp))
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
        // 标签数角标：只在多于 1 个标签时出现。M3E 的徽标是贴边的小胶囊，
        // 数字超过 9 会自动变成两位数，所以用 padding 让它横向撑开。
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
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }
    }
}

/** 无痕模式下的顶部提示条。 */
@Composable
fun IncognitoBanner(modifier: Modifier = Modifier) {
    ModeBanner(
        icon = LingIcons.PrivacyTip,
        text = "无痕模式 · 不记录历史与 Cookie",
        container = MaterialTheme.colorScheme.tertiaryContainer,
        content = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = modifier,
    )
}

/** 电脑模式提示条。 */
@Composable
fun DesktopModeBanner(modifier: Modifier = Modifier) {
    ModeBanner(
        icon = LingIcons.DesktopWindows,
        text = "电脑模式 · 已使用桌面版 UA",
        container = MaterialTheme.colorScheme.secondaryContainer,
        content = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier,
    )
}

/**
 * 模式提示条。
 *
 * 从贴边通栏改成**内缩胶囊**：与底部工具栏同一套"悬浮"语言，
 * 左右各留 12dp，四角 16dp。这样提示条不再是地址栏下方的一条色带，
 * 而是独立的一枚小标签，和悬浮工具栏形成上下呼应。
 */
@Composable
private fun ModeBanner(
    icon: ImageVector,
    text: String,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = container,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = content,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = "  $text",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = content,
                )
            }
        }
    }
}
