package com.ling.browser.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ling.browser.ui.theme.LingIcons
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 内置主页。
 *
 * 内容只有**一个时钟 + 一个搜索框**（与旧版 HTML 主页的取舍一致），
 * 但改由原生 Compose 渲染，WebView 那边只留一个 `about:blank` 占位
 * （见 `WebTabManager.loadHome`）。这样做的原因：
 *  - 主页不再需要 `loadDataWithBaseURL` 造一个"假文档"，省掉地址栏/返回栈里
 *    那一整套地址归一化；
 *  - 颜色直接读 `MaterialTheme.colorScheme`，不必再把配色转成 CSS 十六进制；
 *  - 时钟由 Compose 自己走，不再靠原生每分钟往 WebView 里注入文本。
 *
 * 背景**不在这里画**：纯色/自定义图片两档都由 [com.ling.browser.ui.components.HomeBackgroundLayer]
 * 在 WebView 之下供色，本层保持透明。WebView 在主页时也是透明底
 * （见 `WebTabManager.loadHome`），因此背景能完整透出来。
 */
@Composable
fun HomeScreen(
    /** 提交搜索框内容。是网址还是搜索词由上层用同一套 UrlUtils 判定。 */
    onSearch: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var query by remember { mutableStateOf("") }

    val submit: () -> Unit = {
        val text = query.trim()
        // 空输入不提交：与旧版表单一致（`ling://search?q=` 会被判成空查询丢掉）。
        if (text.isNotEmpty()) {
            query = ""
            focusManager.clearFocus()
            keyboard?.hide()
            onSearch(text)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            // 点空白处收起输入法。indication = null：整屏点一下不该闪一片涟漪。
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                focusManager.clearFocus()
                keyboard?.hide()
            }
            .padding(horizontal = 24.dp, vertical = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            HomeClock()
            // 搜索框：M3E 的 search bar —— 全胶囊、浅色容器、带投影、
            // 左侧一个搜索图标。宽度上限略放大到 360dp（旧版 320dp），
            // 大屏上更舒展，小屏仍跟着父布局缩窄。
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 0.dp,
                shadowElevation = 4.dp,
                modifier = Modifier
                    .padding(top = 28.dp)
                    .widthIn(max = 360.dp)
                    .fillMaxWidth()
                    .height(56.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = LingIcons.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { submit() }),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (query.isEmpty()) {
                                    Text(
                                        text = "搜索或输入网址",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                            .copy(alpha = 0.7f),
                                        maxLines = 1,
                                    )
                                }
                                inner()
                            }
                        },
                    )
                }
            }
        }
    }
}

/**
 * 主页时钟（`HH:mm`）。
 *
 * 对齐到**下一个整分**再刷新，而不是固定 sleep 60s：后者会让显示的时间
 * 比真实时间慢将近一整分钟，效果上等于时钟一直慢一拍（旧版 HTML 主页的
 * 原生驱动定时器也是这么做的，这里只是把驱动方换成了 Compose）。
 */
@Composable
private fun HomeClock(modifier: Modifier = Modifier) {
    val format = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    var text by remember { mutableStateOf(format.format(Date())) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(CLOCK_PERIOD_MS - System.currentTimeMillis() % CLOCK_PERIOD_MS)
            text = format.format(Date())
        }
    }
    Text(
        text = text,
        style = TextStyle(
            fontSize = 68.sp,
            // M3E 的大字号不是"细"，而是"有分量"：从 Light 提到 W400，
            // 同时把字距收紧到 -1sp，大字号才不会显得松垮。
            fontWeight = FontWeight.W400,
            lineHeight = 68.sp,
            // 等宽数字：分钟跳动时各位不会左右抖。
            fontFeatureSettings = "tnum",
            letterSpacing = (-1).sp,
            color = MaterialTheme.colorScheme.onBackground,
            // 自定义图片背景上文字可能与图片颜色重合，压一层深色投影保证可读；
            // 纯色背景上只呈现轻微立体感。
            shadow = Shadow(
                color = Color.Black.copy(alpha = 0.35f),
                offset = Offset(0f, 4f),
                blurRadius = 8f,
            ),
            textAlign = TextAlign.Center,
        ),
        modifier = modifier,
    )
}

/** 时钟刷新周期：一整分，与显示格式 `HH:mm` 的精度一致。 */
private const val CLOCK_PERIOD_MS = 60_000L
