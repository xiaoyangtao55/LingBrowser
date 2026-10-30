package com.ling.browser.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ling.browser.data.repo.UserScriptEntry
import com.ling.browser.ui.screens.TABS_SHEET_ALPHA
import com.ling.browser.ui.screens.TABS_SHEET_HEIGHT_FRACTION
import com.ling.browser.ui.screens.TabsScreen
import com.ling.browser.web.ConsoleEntry
import com.ling.browser.web.TabState
import com.ling.browser.web.UserScriptMenuCommand

/**
 * 浏览页的四个底部面板（标签页 / 更多 / 脚本菜单 / 页面错误）。
 *
 * 从 [com.ling.browser.ui.screens.BrowserScreen] 里拆出的纯展示层：
 * 显隐开关、面板内容与接线仍由浏览页决定，这里只负责"以面板形式呈现"。
 * 统一用 [ModalBottomSheet]：遮罩、圆角、拖动关闭与入场/退场动画都由组件提供。
 */

/** 「标签页」面板：半透明容器，透出背后的网页（见 [TabsScreen] 的说明）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TabsSheet(
    tabs: List<TabState>,
    activeId: String,
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onNewTab: () -> Unit,
    onNewIncognitoTab: () -> Unit,
    onCloseAll: () -> Unit,
    onMoveTab: (Int, Int) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // 面板是半透明的，透出背后的网页（"标签页是临时面板而非独立页面"）。
        // 曾经还有「全屏」档位：全屏时不透明、且不再透出网页，与独立页面无异，
        // 已随设置项一并移除，这里恒定用半透明容器色。
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = TABS_SHEET_ALPHA),
        dragHandle = { SheetDragHandle() },
    ) {
        TabsScreen(
            tabs = tabs,
            activeId = activeId,
            onSelect = onSelect,
            onClose = onClose,
            onNewTab = onNewTab,
            onNewIncognitoTab = onNewIncognitoTab,
            onCloseAll = onCloseAll,
            onMoveTab = onMoveTab,
            // 面板高度落在**内容**的高度上，而不是依赖 sheet 自带的"半展开"锚点。
            modifier = Modifier.fillMaxHeight(TABS_SHEET_HEIGHT_FRACTION),
        )
    }
}

/**
 * 「更多」面板：图标网格。
 *
 * 原来锚定在工具栏「更多」按钮上的下拉菜单，现在改为自底部升起的面板。
 * 11 项竖排要占满大半屏，横排 4 列后只有 3 行，面板更矮、基本不需要滚动。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MoreSheet(
    items: List<MoreItem>,
    sheetState: SheetState,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // 与底部工具栏同色，面板读起来和工具栏是同一层 chrome。
        // 网格条目自己不铺底色，因此面板自上而下只有这一种颜色 ——
        // 不会再出现「顶部一条与内容对不齐」的分色。
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        dragHandle = { SheetDragHandle() },
    ) {
        MoreGrid(
            items = items,
            modifier = Modifier
                .fillMaxWidth()
                // 小屏 / 横屏时条目可能超出一屏，留一层滚动兜底
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/**
 * 「脚本菜单」面板：当前页由 `GM_registerMenuCommand` 注册的命令。
 *
 * 命令属于具体页面实例，调用方在列表变空时收起面板（见浏览页的 LaunchedEffect）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScriptMenuSheet(
    commands: List<UserScriptMenuCommand>,
    installedScripts: List<UserScriptEntry>,
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onInvoke: (UserScriptMenuCommand) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        dragHandle = { SheetDragHandle() },
    ) {
        // 面板一出现就把焦点拿过来，accessKey 才收得到按键。
        val menuFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { menuFocus.requestFocus() } }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(menuFocus)
                .focusable()
                // 外接键盘：按 accessKey 直接触发对应命令（桌面端菜单助记键的等价物）。
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val key = event.utf16CodePoint.toChar().uppercaseChar()
                    val hit = commands.firstOrNull {
                        it.accessKey.isNotEmpty() && it.accessKey[0] == key
                    } ?: return@onPreviewKeyEvent false
                    onInvoke(hit)
                    true
                }
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
        ) {
            commands.forEach { command ->
                // 前缀脚本名：同一个页面可能装了多个脚本，只给命令名会分不清。
                val scriptName = installedScripts
                    .firstOrNull { it.id == command.scriptId }
                    ?.script?.name
                val base = if (scriptName.isNullOrBlank()) {
                    command.label
                } else {
                    "$scriptName · ${command.label}"
                }
                Text(
                    text = if (command.accessKey.isEmpty()) {
                        base
                    } else {
                        "$base（快捷键 ${command.accessKey}）"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onInvoke(command) }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                )
            }
        }
    }
}

/**
 * 「页面错误」面板：只收录 ERROR 级控制台消息。
 *
 * 导航会清空当前页的错误，调用方在列表变空时收起面板。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConsoleSheet(
    entries: List<ConsoleEntry>,
    sheetState: SheetState,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        dragHandle = { SheetDragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
        ) {
            entries.forEach { entry ->
                ConsoleErrorRow(entry)
            }
        }
    }
}

/**
 * 面板顶部的拖拽手柄。
 *
 * 不用 Material3 的 `BottomSheetDefaults.DragHandle`：它自带的底色与面板容器色
 * 不是同一个来源，实机上表现为顶部多出一条与内容对不齐的横条。这里显式画一条
 * 低对比的淡灰小横条（贴近系统手势条「小白条」的观感），且**不额外铺底色**——
 * 面板上、中、下因此同色。
 */
@Composable
private fun SheetDragHandle() {
    Box(
        modifier = Modifier
            .padding(top = 16.dp, bottom = 12.dp)
            .size(width = 40.dp, height = 4.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = SHEET_HANDLE_ALPHA)),
    )
}

/**
 * 手柄的透明度。
 *
 * 取 onSurface 的透明度分量：浅色主题下是淡灰、深色下是淡白，与系统手势条
 * 跟随明暗的行为一致。0.35 是「看得出、但不抢眼」的取值。
 */
private const val SHEET_HANDLE_ALPHA = 0.35f

/**
 * 「页面错误」面板的一行。
 *
 * 只可能是 ERROR 级消息（见 `WebTabManager.onConsoleMessage`）：正文等宽字体
 * 便于对齐缩进，来源与行号放次行小字，长消息按原样换行不截断。
 */
@Composable
private fun ConsoleErrorRow(entry: ConsoleEntry) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Text(
            text = entry.message,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.error,
        )
        if (entry.source.isNotBlank() || entry.line > 0) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = listOfNotNull(
                    entry.source.takeIf { it.isNotBlank() },
                    entry.line.takeIf { it > 0 }?.let { "第 $it 行" },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 「更多」面板的一个条目。
 *
 * 收成数据而不是逐条散写：网格要按「每行几个」分组，散写就分不出组了。
 */
internal data class MoreItem(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit,
)

/** 「更多」面板的列数：四列在手机上每格约 80dp，图标 + 两行文字刚好放得下。 */
private const val MORE_GRID_COLUMNS = 4

/** 格子高度：上边距 10dp + 图标容器 44dp + 间距 8dp + 最多两行文字，末行留余量。 */
private val MORE_ITEM_HEIGHT = 96.dp

/**
 * 「更多」面板的图标网格：每行 [MORE_GRID_COLUMNS] 个，图标在上、文字在下。
 *
 * 用固定列数的 Row 分组，而不是 LazyVerticalGrid：总共只有 3 行，惰性化没有
 * 收益，反而会在 BottomSheet 里再嵌一层可滚动容器，与面板自身的拖动 / 收起
 * 手势抢事件。
 */
@Composable
private fun MoreGrid(
    items: List<MoreItem>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items.chunked(MORE_GRID_COLUMNS).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                row.forEach { item ->
                    MoreGridItem(item = item, modifier = Modifier.weight(1f))
                }
                // 末行不满时补空位：否则最后几个格子会被拉宽，与上面的列错位
                repeat(MORE_GRID_COLUMNS - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** 网格里的单个条目。 */
@Composable
private fun MoreGridItem(
    item: MoreItem,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .height(MORE_ITEM_HEIGHT)
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = item.onClick)
            // 顶部对齐 + 固定上边距：不管文字是一行还是两行，图标都落在同一条
            // 水平线上。若用居中排列，两行文字的格子会把图标顶上去，
            // 一行文字的格子又把它压下来，整排图标就参差不齐了。
            .padding(start = 4.dp, end = 4.dp, top = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // M3E 的图标一律带容器：一枚浅色圆底把图标从背景上"托"起来，
        // 比裸图标更贴合 Expressive 的颗粒感，也让一排图标更容易对齐。
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = item.label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            // 「新建无痕标签页」这类文案在四列宽度下会折成两行
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
