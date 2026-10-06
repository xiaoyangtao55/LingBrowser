package com.ling.browser.ui.screens

import com.ling.browser.ui.theme.LingIcons
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.ling.browser.data.prefs.TabsHeight
import com.ling.browser.web.TabState
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 非全屏档位下面板的底色透明度。
 *
 * 取值理由：太低（< 0.6）面板上的文字会与网页内容互相干扰、读不清；
 * 太高（> 0.9）就等于不透明，"能看到后面的网页"这个设计意图就没了。
 *
 * 从 0.82 提到 0.92：实机上半屏面板里要读标签标题，0.82 时背后的
 * 网页文字会透上来干扰阅读。0.92 仍然能看出背后"有内容"，
 * 但不再和标题抢注意力 —— 可读性优先于通透感。
 */
private const val PANEL_ALPHA = 0.92f

/** 面板升起动画时长。180ms 够快，不拖沓，又能看清"从下方滑上来"。 */
private const val PANEL_SLIDE_IN_MS = 180

/** 遮罩淡入时长，略长于面板，让背景先暗下去。 */
private const val SCRIM_FADE_IN_MS = 220

/** 遮罩最暗时的黑度。0.28 能压住网页又不至于发闷。 */
private const val SCRIM_MAX_ALPHA = 0.28f

/**
 * 标签页管理页。
 *
 * 高度由 [tabsHeight] 控制，默认占屏幕下半 —— 这样切标签时仍能看到
 * 底下的网页，符合"标签页是临时面板而非独立页面"的直觉。
 *
 * 列表项显示站点 favicon 作为缩略图（不是网页截图，理由见 [TabThumbnail]）。
 * 长按列表项可上下拖动排序，见 [TabRow] 的 dragOffsetY 参数。
 *
 * 已知限制：拖拽时**没有**做边缘自动滚动。标签数量少时列表本来就不满一屏，
 * 而面板最高只占屏幕一半，需要滚动的场景很少见；实现它要额外引入一个
 * 随拖动位置变化的协程循环，复杂度与收益不成比例。等真机反馈需要再加。
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
    onMoveTab: (Int, Int) -> Unit,
    onBack: () -> Unit,
    tabsHeight: TabsHeight,
    onTabsHeightChange: (TabsHeight) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    // 拖拽状态。都用 remember 保存，跨重组存活。
    //
    // draggingIndex：被拖的那一项当前**渲染在列表中的下标**。
    // 每次跨越邻居就立即调用 onMoveTab 并同步更新它 —— 这样列表数据
    // 与视觉位置始终一致，不需要额外维护一份"影子顺序"。
    var draggingIndex by remember { mutableIntStateOf(-1) }
    var dragOffsetY by remember { mutableStateOf(0f) }
    // 列表项实测高度（含间距）。用来把手指数换算成跨过了几个项。
    var rowHeightPx by remember { mutableIntStateOf(0) }

    // 手势回调里必须读到**最新**的标签列表。
    //
    // 为什么不能直接用闭包里的 tabs：pointerInput 的 lambda 在首次组合时
    // 就被捕获，之后即使 tabs 变了它仍指向旧实例（这是 Compose 的常见陷阱）。
    // 拖拽会实时改变顺序，用旧列表算下标必然错位。
    // 用一个 remember 的持有者，每次重组写入最新值，回调再从中读。
    val tabsRef = remember { mutableStateOf(tabs) }
    tabsRef.value = tabs
    // 面板从底部升起：用 Box + align 而不是 fillMaxSize，短面板下方不会留白。
    //
    // 遮罩只压暗**面板上方**的区域，让底下的网页隐约可见（Via 的做法）：
    // 完全盖死会让用户失去"我还在那个网页上"的空间感。
    //
    // 入场动画：面板从下方滑入，遮罩同时淡入。
    // 用 Animatable 而不是 AnimatedVisibility —— 这里没有"显示/隐藏"的
    // 状态切换（TabsScreen 被加入组合树时才存在），需要的是挂载即播放，
    // LaunchedEffect 触发一次最直接。
    val slide = remember { Animatable(1f) }
    val scrimAlpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        launch { slide.animateTo(0f, tween(PANEL_SLIDE_IN_MS, easing = FastOutSlowInEasing)) }
        launch { scrimAlpha.animateTo(1f, tween(SCRIM_FADE_IN_MS, easing = LinearOutSlowInEasing)) }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
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
            // 半透明面板：能透出后方网页。用 surface 的 alpha 而不是纯色，
            // 保证 Material You 动态取色仍然生效。
            // 全屏档位下若仍然半透明，背后会透出浏览页造成视觉干扰，
            // 因此全屏时用不透明表面。
            color = if (tabsHeight == TabsHeight.FULL) {
                MaterialTheme.colorScheme.surface
            } else {
                MaterialTheme.colorScheme.surface.copy(alpha = PANEL_ALPHA)
            },
            tonalElevation = 3.dp,
            // 高度不够时内部列表自行滚动，不会把面板撑破
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(tabsHeight.fraction)
                // 面板自下而上滑入：用 graphicsLayer 平移而非改布局高度，
                // 这样不会触发重新测量，动画期间列表不会抖。
                .graphicsLayer {
                    translationY = slide.value * size.height
                }
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
                        colors = TopAppBarDefaults.topAppBarColors(
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
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(inner),
                    contentPadding = PaddingValues(
                        horizontal = 12.dp,
                        vertical = 8.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(
                        count = tabs.size,
                        key = { tabs[it].id },
                    ) { index ->
                        val tab = tabs[index]
                        val isDragging = index == draggingIndex
                        TabRow(
                            tab = tab,
                            isActive = tab.id == activeId,
                            isDragging = isDragging,
                            dragOffsetY = if (isDragging) dragOffsetY else 0f,
                            onSelect = { onSelect(tab.id) },
                            onClose = { onClose(tab.id) },
                            modifier = Modifier
                                // 记录实测行高，供手势换算使用
                                .onSizeChanged { size ->
                                    if (!isDragging && size.height > 0) {
                                        rowHeightPx = size.height
                                    }
                                }
                                // 拖拽中的项要浮在其它项之上
                                .zIndex(if (isDragging) 1f else 0f)
                                // key 里带上 tabs.size 之外**不要**带 index：
                                // 一旦带上，每次换位都会重启手势识别器，
                                // 拖动中途就会断掉。
                                .pointerInput(tab.id) {
                                    // 用 AfterLongPress 而不是普通拖拽：
                                    // 短按要保留给"点击切换标签"，长按才是排序。
                                    // 否则一滑动就会误触排序，而且列表也没法滚。
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            // 这里**必须现查**下标，不能用外面闭包
                                            // 捕获的 index：换位后列表已重排，
                                            // 闭包里的 index 是旧值，会用错起始位置。
                                            val current = tabsRef.value.indexOfFirst { it.id == tab.id }
                                            if (current >= 0) {
                                                draggingIndex = current
                                                dragOffsetY = 0f
                                            }
                                        },
                                        onDragEnd = {
                                            draggingIndex = -1
                                            dragOffsetY = 0f
                                        },
                                        onDragCancel = {
                                            draggingIndex = -1
                                            dragOffsetY = 0f
                                        },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            dragOffsetY += amount.y

                                            // 手指数跨过一整项高度就换位。
                                            // 用 rowHeightPx（实测值）而不是硬编码，
                                            // 因为标题行有无副标题会改变行高。
                                            val rowH = rowHeightPx
                                            if (rowH <= 0 || draggingIndex < 0) {
                                                return@detectDragGesturesAfterLongPress
                                            }
                                            val last = tabsRef.value.lastIndex
                                            val moved = (dragOffsetY / rowH).roundToInt()
                                            if (moved == 0) {
                                                return@detectDragGesturesAfterLongPress
                                            }
                                            val target = (draggingIndex + moved)
                                                .coerceIn(0, last)
                                            if (target != draggingIndex) {
                                                onMoveTab(draggingIndex, target)
                                                // 关键：把「已消耗的位移」扣掉，
                                                // 否则拖动会累积成连续换位而失控
                                                dragOffsetY -= (target - draggingIndex) * rowH
                                                draggingIndex = target
                                            }
                                        },
                                    )
                                },
                        )
                    }
                }
            }
        }

        // 压暗遮罩：只覆盖面板**上方**露出的网页区域。
        // 放在面板之后绘制，因此不会盖住面板本身；
        // 它让背后的网页"退到后面"，避免与面板内容抢注意力。
        // 全屏档位下没有露出的区域，就不画。
        if (tabsHeight != TabsHeight.FULL) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(1f - tabsHeight.fraction)
                    .align(Alignment.TopCenter)
                    // alpha 由动画驱动：背景先淡入，视觉上更连贯
                    .background(Color.Black.copy(alpha = SCRIM_MAX_ALPHA * scrimAlpha.value)),
            )
        }
    }
}

/** 高度档位切换：全屏 / 一半。 */
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
    isDragging: Boolean,
    dragOffsetY: Float,
    onSelect: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = when {
            isActive -> MaterialTheme.colorScheme.primaryContainer
            tab.isIncognito -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f)
            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        },
        // 拖拽中的项加阴影，视觉上"提起来"，与静止项区分开
        shadowElevation = if (isDragging) 8.dp else 0.dp,
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { translationY = dragOffsetY }
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onSelect),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TabThumbnail(tab = tab, isActive = isActive)

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (tab.isIncognito) {
                        Icon(
                            LingIcons.PrivacyTip,
                            contentDescription = "无痕",
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                    }
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
                }
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

/**
 * 标签缩略图：站点 favicon；没有图标时退回首字母方块。
 *
 * 为什么不用网页截图：整页截图要把 WebView 画到一张全屏 Bitmap，
 * 1080x2000x4B ≈ 8.6 MB/张，6 个标签就是 ~50 MB，中低端机上随时 OOM；
 * 而且离屏的 WebView 内容不保证完整，截出来常常是空白。
 * favicon 只有几十像素，一眼能认出站点，符合轻量定位。
 *
 * ⚠️ 渲染前必须检查 isRecycled：标签关闭时我们会主动 recycle favicon
 * 释放像素内存，而 Compose 可能还拿着同一个 Bitmap 引用再画一帧，
 * 此时直接绘制会抛 "Canvas: trying to use a recycled bitmap"。
 */
@Composable
private fun TabThumbnail(tab: TabState, isActive: Boolean) {
    val size = 36.dp
    val shape = RoundedCornerShape(10.dp)
    val favicon = tab.favicon

    Box(
        modifier = Modifier
            .size(size)
            .clip(shape)
            .background(
                if (isActive) {
                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.10f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.10f)
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        when {
            favicon != null && !favicon.isRecycled -> {
                Image(
                    bitmap = favicon.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                )
            }
            tab.isIncognito -> {
                Icon(
                    LingIcons.PrivacyTip,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.size(18.dp),
                )
            }
            else -> {
                // 首字母占位。用固定字重与字号，避免不同机型字体度量差异
                // 让方块里的字母大小不一。
                Text(
                    text = tab.initial,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isActive) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}
