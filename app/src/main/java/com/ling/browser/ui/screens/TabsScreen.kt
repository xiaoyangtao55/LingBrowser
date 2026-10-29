package com.ling.browser.ui.screens

import com.ling.browser.ui.theme.LingIcons
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.ling.browser.web.TabState
import kotlin.math.roundToInt

/**
 * 标签页面板底色的透明度。
 *
 * 取值理由：太低（< 0.6）面板上的文字会与网页内容互相干扰、读不清；
 * 太高（> 0.9）就等于不透明，"能看到后面的网页"这个设计意图就没了。
 *
 * 0.92 是实机调过的值：半屏面板里要读标签标题，更透时背后的网页文字会透上来
 * 干扰阅读；0.92 仍能看出背后"有内容"，但不再和标题抢注意力 ——
 * 可读性优先于通透感。
 */
internal const val TABS_SHEET_ALPHA = 0.92f

/**
 * 标签页面板占屏幕的高度比例。
 *
 * 固定一半：既能扫到若干个标签，又能透出后面的网页，符合"标签页是临时面板
 * 而非独立页面"的直觉。
 *
 * 曾经还有「全屏」一档（更早还有 1/4）：全屏时面板不再透出网页，与一个独立
 * 页面无异，还要额外做不透明回退，收益不抵复杂度，已随设置项一并移除。
 */
internal const val TABS_SHEET_HEIGHT_FRACTION = 0.5f

/**
 * 标签页 BottomSheet 的**内容**。
 *
 * 面板容器（ModalBottomSheet）本身由 [BrowserScreen][com.ling.browser.ui.screens.BrowserScreen]
 * 创建：遮罩、圆角、拖动关闭、升起/落下动画都由 BottomSheet 组件提供，
 * 这里只负责摆列表与底部动作，不再自带一整套叠加层。
 *
 * 列表项显示站点 favicon 作为缩略图（不是网页截图，理由见 [TabThumbnail]）。
 * 长按列表项可上下拖动排序，见 [TabRow] 的 dragOffsetY 参数。
 *
 * 已知限制：拖拽时**没有**做边缘自动滚动。标签数量少时列表本来就不满一屏，
 * 而面板最高只占屏幕一半，需要滚动的场景很少见；实现它要额外引入一个
 * 随拖动位置变化的协程循环，复杂度与收益不成比例。等真机反馈需要再加。
 */
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

    Column(modifier = modifier.fillMaxWidth()) {
        // ---- 头部：标题 + 关闭全部 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "标签页 (${tabs.size})",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            if (tabs.size > 1) {
                TextButton(onClick = onCloseAll) { Text("关闭全部") }
            }
        }

        // ---- 列表区：占满头部与底部动作之间的空间 ----
        Box(modifier = Modifier.weight(1f)) {
            if (tabs.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
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
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
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

        // ---- 底部动作：新建 / 无痕 ----
        // 这里不再自带底色。半屏档位下面板是半透明的，若再铺一层不透明的
        // surfaceContainer，底部就会与面板上方分成两种颜色，半透明的设计也
        // 在这里断掉。两个胶囊按钮自己有容器色，不需要额外衬底。
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
        shape = RoundedCornerShape(28.dp),
        color = container,
        modifier = modifier
            .clip(RoundedCornerShape(28.dp))
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
        // 卡片圆角抬到 24dp：M3E 的卡片比 M3 更圆，标签条读起来像一枚枚软卡片。
        shape = RoundedCornerShape(24.dp),
        color = when {
            isActive -> MaterialTheme.colorScheme.primaryContainer
            tab.isIncognito -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f)
            else -> MaterialTheme.colorScheme.surfaceContainerHigh
        },
        // 拖拽中的项加阴影，视觉上"提起来"，与静止项区分开；
        // 当前标签也给一点投影，让"正在看的那一个"浮在最上面。
        shadowElevation = when {
            isDragging -> 8.dp
            isActive -> 2.dp
            else -> 0.dp
        },
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { translationY = dragOffsetY }
            .clip(RoundedCornerShape(24.dp))
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
    val size = 40.dp
    val shape = RoundedCornerShape(14.dp)
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
