package com.ling.browser.ui.components

import com.ling.browser.ui.theme.LingIcons
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ling.browser.ui.Suggestion
import com.ling.browser.ui.SuggestionKind
import com.ling.browser.web.SecurityState

/**
 * 地址栏 + 联想下拉。
 *
 * 交互对齐主流浏览器：失焦时显示当前 URL（只读外观），聚焦后进入编辑态并全选，
 * 联想结果按「书签 → 历史」排序展示。
 *
 * 右侧依次是「刷新/停止」和「清除」两个动作位 —— 刷新放在地址栏内是
 * 因为它是最高频的操作，放在底部工具栏会与后退/标签页争夺注意力。
 */
@Composable
fun AddressBar(
    text: String,
    isEditing: Boolean,
    isLoading: Boolean,
    /** 加载进度 0..100，传给进度条；不确定态（进度未知）传 null。 */
    progress: Int?,
    /** 当前页面的连接安全状态，用于展示「不安全 / 部分不安全 / 证书错误」标识。 */
    security: SecurityState,
    suggestions: List<Suggestion>,
    onTextChange: (String) -> Unit,
    onFocusChange: (Boolean) -> Unit,
    /** 地址栏交出焦点后调用，用于把焦点还给网页内容。 */
    onReleaseFocus: () -> Unit,
    onSubmit: (String) -> Unit,
    onSuggestionClick: (Suggestion) -> Unit,
    onReloadOrStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    // 地址栏整体的"点一下进编辑态"，不留水波纹（保持干净的观感）
    val tapInteraction = remember { MutableInteractionSource() }

    // 编辑态内部维护 TextFieldValue，以便聚焦时把光标全选
    var field by remember { mutableStateOf(TextFieldValue(text)) }

    LaunchedEffect(text, isEditing) {
        if (!isEditing) {
            field = TextFieldValue(text, TextRange(text.length))
        } else if (field.text != text) {
            field = TextFieldValue(text, TextRange(0, text.length))
        }
    }

    LaunchedEffect(isEditing) {
        if (isEditing) {
            runCatching { focusRequester.requestFocus() }
        }
    }

    /**
     * 提交后必须**真的**把焦点交还给网页。
     *
     * 曾经两次都没修对，记下来避免再走弯路：
     *
     * 第一版只把 `addressEditing` 置 false 并 `keyboard?.hide()` ——
     * 光标继续闪，网页输入框唤不起输入法。
     *
     * 第二版加了 `focusManager.clearFocus()`，光标确实消失了，
     * **但网页输入框依然唤不起输入法**（真机实测）。原因是：
     * Compose 释放焦点并不会让焦点自动回到 WebView —— 焦点只是"消失了"，
     * 没有任何 View 持有它。用户点网页输入框时，IME 认为没有可输入的焦点，
     * 键盘就不弹。
     *
     * 所以必须**显式**把焦点交给 WebView（[onReleaseFocus] ->
     * `WebTabManager.focusWebContent()`），四步缺一不可。
     */
    val releaseFocus: () -> Unit = {
        focusManager.clearFocus()
        keyboard?.hide()
        onFocusChange(false)
        // 关键一步：把焦点还回去，否则焦点悬空、网页唤不起输入法
        onReleaseFocus()
    }

    // 编辑态把容器提亮一档：M3E 里"正在操作"的表面用更浅的容器色表示，
    // 用户能感到自己"抬起了"这块区域。颜色用 tween 而不是弹簧 ——
    // 颜色回弹会在过渡中露出一层发灰的中间色。编辑态同时加一点投影，
    // 让这颗胶囊在键盘弹出时明确地浮在内容之上。
    val containerColor by animateColorAsState(
        targetValue = if (isEditing) {
            MaterialTheme.colorScheme.surfaceContainerHighest
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        animationSpec = tween(durationMillis = 220),
        label = "addressBarContainer",
    )

    Column(modifier = modifier.fillMaxWidth()) {
        Surface(
            // 全胶囊：M3E 的主搜索框就是一颗药丸，两端是半圆。
            shape = CircleShape,
            color = containerColor,
            tonalElevation = 0.dp,
            shadowElevation = if (isEditing) 4.dp else 0.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .padding(start = 16.dp, end = 5.dp)
                        // 非编辑态：整条地址栏都可点（不只是文字那一小块），
                        // 点一下进编辑态并全选 URL。
                        // 编辑态不加这个点击 —— 否则点哪儿都会被当成"重新进编辑态"，
                        // 输入框自己的光标定位就没了。
                        .then(
                            if (isEditing) {
                                Modifier
                            } else {
                                Modifier.clickable(
                                    interactionSource = tapInteraction,
                                    indication = null,
                                ) { onFocusChange(true) }
                            }
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 左侧：连接安全标识 / 搜索图标。
                    //
                    // 只要不是"安全"，就必须给出**醒目的提示**（no-entry 图标 + error
                    // 配色的文字），而不是继续挂一把代表安全的锁 —— 否则等于在骗用户
                    // "这个页面是安全的"。三种不安全的成因不同，文案也不同：
                    //   明文 HTTP → 不安全；HTTPS 混入 HTTP 子资源 → 部分不安全；
                    //   证书校验失败 → 证书错误。
                    // 编辑态不给标识（此时用户自己在输入或改地址），显示搜索图标。
                    val insecureLabel = if (isEditing) {
                        null
                    } else {
                        when (security) {
                            SecurityState.SECURE -> null
                            SecurityState.INSECURE_HTTP -> "不安全"
                            SecurityState.INSECURE_MIXED -> "部分不安全"
                            SecurityState.INSECURE_CERT -> "证书错误"
                        }
                    }
                    val insecure = insecureLabel != null
                    Icon(
                        imageVector = when {
                            isEditing -> LingIcons.Search
                            insecure -> LingIcons.Block
                            else -> LingIcons.Lock
                        },
                        contentDescription = insecureLabel,
                        tint = if (insecure) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(16.dp),
                    )
                    if (insecureLabel != null) {
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = insecureLabel,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 1,
                        )
                    }
                    Spacer(Modifier.width(8.dp))

                    Box(modifier = Modifier.weight(1f)) {
                        // ⚠️ 编辑态才挂输入框，非编辑态是**只读文本**。
                        //
                        // 以前这里常驻一个 BasicTextField，非编辑态把"网页标题"喂给它显示，
                        // 于是"点到文字"会被输入框自己吃掉：它把点击当成**移动光标**，
                        // 回调 onValueChange 带出来的是标题，把刚切过来的 URL 又覆盖回去 ——
                        // 表现为"点文字编辑的是标题，点旁边空白才编辑 URL"。
                        // 只读文本没有这个问题，也正好符合本组件的设计（失焦时是只读外观）。
                        if (isEditing) {
                            BasicTextField(
                                value = field,
                                onValueChange = {
                                    field = it
                                    onTextChange(it.text)
                                },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                    color = MaterialTheme.colorScheme.onSurface,
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                                keyboardActions = KeyboardActions(
                                    onGo = {
                                        onSubmit(field.text)
                                        releaseFocus()
                                    }
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(focusRequester)
                                    // 让 ViewModel 的编辑态跟随**真实的**焦点，
                                    // 而不是只在手动调用时同步。没有这个观察者时，
                                    // onFocusChange 参数其实从未被调用过。
                                    .onFocusChanged { onFocusChange(it.isFocused) },
                            )
                        } else {
                            Text(
                                text = text.ifEmpty { "搜索或输入网址" },
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (text.isEmpty()) {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    // 右侧：刷新/停止（常驻）。
                    // 装在**圆形实底**按钮里：M3E 的图标按钮默认带可见容器，
                    // 不再是一个悬空的图标 —— 悬空图标在自定义背景上容易"消失"。
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(
                                if (isLoading) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHighest
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        IconButton(
                            onClick = onReloadOrStop,
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(
                                imageVector = if (isLoading) LingIcons.Close else LingIcons.Refresh,
                                contentDescription = if (isLoading) "停止" else "刷新",
                                tint = if (isLoading) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }

                    // 右侧：清除（仅编辑态）
                    AnimatedVisibility(visible = isEditing) {
                        IconButton(
                            onClick = {
                                field = TextFieldValue("")
                                onTextChange("")
                            },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                LingIcons.Close,
                                contentDescription = "清除",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }

                // 加载进度条贴在地址栏下沿（叠在胶囊内部，不改变高度）。
                AddressBarProgress(isLoading = isLoading, progress = progress)
            }
        }

        // 联想结果
        AnimatedVisibility(
            visible = isEditing && suggestions.isNotEmpty(),
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
                shadowElevation = 6.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
            ) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    suggestions.forEachIndexed { index, s ->
                        if (index > 0) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 48.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                            )
                        }
                        SuggestionRow(
                            suggestion = s,
                            onClick = {
                                // 点联想项与回车等价，同样要真的还回焦点，
                                // 否则键盘收起后光标还在闪、网页仍拿不到焦点
                                onSuggestionClick(s)
                                releaseFocus()
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 地址栏底部的加载进度条。
 *
 * 单独抽出来是因为它要 `align(BottomCenter)` 叠在 Box 里，而外层还有
 * `Column` 的隐式接收者 —— 直接写在 Box 里时 `AnimatedVisibility` 会被解析成
 * ColumnScope 的扩展，报"implicit receiver 不可用"。放进一个 BoxScope 扩展
 * 函数，重载解析就只剩顶层那一个。
 *
 * 视觉上是一条两端圆的 3dp 细条、左右内缩 22dp：沿着胶囊的圆弧走，
 * 而不是横贯整条地址栏把圆角切掉。有确定进度（>0）时走确定态，
 * 否则回退不确定态（WebView 刚触发加载时 progress 还是 0）。
 */
@Composable
private fun BoxScope.AddressBarProgress(isLoading: Boolean, progress: Int?) {
    AnimatedVisibility(
        visible = isLoading,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.align(Alignment.BottomCenter),
    ) {
        val p = progress
        val barModifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 4.dp)
            .height(3.dp)
            .clip(CircleShape)
        if (p != null && p > 0) {
            LinearProgressIndicator(
                progress = { p / 100f },
                modifier = barModifier,
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
            )
        } else {
            LinearProgressIndicator(
                modifier = barModifier,
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
            )
        }
    }
}

@Composable
private fun SuggestionRow(suggestion: Suggestion, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = when (suggestion.kind) {
                SuggestionKind.BOOKMARK -> LingIcons.BookmarkBorder
                SuggestionKind.HISTORY -> LingIcons.History
            },
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = suggestion.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = suggestion.url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
