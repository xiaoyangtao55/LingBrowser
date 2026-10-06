package com.ling.browser.ui.components

import com.ling.browser.ui.theme.LingIcons
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ling.browser.ui.Suggestion
import com.ling.browser.ui.SuggestionKind

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
    isSecure: Boolean,
    suggestions: List<Suggestion>,
    onTextChange: (String) -> Unit,
    onFocusChange: (Boolean) -> Unit,
    onSubmit: (String) -> Unit,
    onSuggestionClick: (Suggestion) -> Unit,
    onReloadOrStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

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

    Column(modifier = modifier.fillMaxWidth()) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .padding(start = 12.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 左侧：安全标识 / 搜索图标
                    Icon(
                        imageVector = if (isEditing) LingIcons.Search  else LingIcons.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))

                    Box(modifier = Modifier.weight(1f)) {
                        if (field.text.isEmpty() && !isEditing) {
                            Text(
                                text = "搜索或输入网址",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                maxLines = 1,
                            )
                        }
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
                                    keyboard?.hide()
                                }
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester),
                        )
                    }

                    // 右侧：刷新/停止（常驻）
                    IconButton(
                        onClick = onReloadOrStop,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = if (isLoading) LingIcons.Close else LingIcons.Refresh,
                            contentDescription = if (isLoading) "停止" else "刷新",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
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

                // 加载进度条贴在地址栏下沿
                AnimatedVisibility(
                    visible = isLoading,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0f),
                    )
                }
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
                        SuggestionRow(suggestion = s, onClick = { onSuggestionClick(s) })
                    }
                }
            }
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
