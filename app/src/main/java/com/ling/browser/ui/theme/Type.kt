package com.ling.browser.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 「翎」的排版 —— Material 3 Expressive 取向。
 *
 * 与标准 M3 相比，Expressive 的字阶更" assertive"：
 *  - 标题整体**加粗两档**（标题 W600/W700），层级靠字重区分而不是靠字号拉开；
 *  - 大字号的字距略收紧（负 letterSpacing），大字号才不显得松散；
 *  - 小字号的字距略放开（label 类），保证胶囊按钮 / 角标上的文字仍然清楚。
 *
 * 度量（字号 / 行高）沿用 M3 默认值：浏览器界面的信息密度不能被排版吃掉，
 * 只调字重与字距，是"风格变化"与"可读性"之间最稳的折中。
 */
val LingTypography = Typography().let { base ->
    Typography(
        displayLarge = base.displayLarge.copy(
            fontWeight = FontWeight.W500,
            letterSpacing = (-0.5).sp,
        ),
        displayMedium = base.displayMedium.copy(
            fontWeight = FontWeight.W500,
            letterSpacing = (-0.5).sp,
        ),
        displaySmall = base.displaySmall.copy(
            fontWeight = FontWeight.W500,
            letterSpacing = (-0.25).sp,
        ),
        headlineLarge = base.headlineLarge.copy(
            fontWeight = FontWeight.W600,
            letterSpacing = (-0.25).sp,
        ),
        headlineMedium = base.headlineMedium.copy(
            fontWeight = FontWeight.W600,
            letterSpacing = (-0.25).sp,
        ),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.W600),
        titleLarge = base.titleLarge.copy(
            fontWeight = FontWeight.W700,
            letterSpacing = (-0.25).sp,
        ),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.W600),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.W600),
        bodyLarge = base.bodyLarge,
        bodyMedium = base.bodyMedium,
        bodySmall = base.bodySmall.copy(fontFamily = FontFamily.Default),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.W600),
        labelMedium = base.labelMedium.copy(
            fontWeight = FontWeight.W500,
            letterSpacing = 0.25.sp,
        ),
        labelSmall = base.labelSmall.copy(
            fontWeight = FontWeight.W500,
            letterSpacing = 0.5.sp,
        ),
    )
}

/** 地址栏等宽字体场景（例如显示 URL 时）统一使用这个样式。 */
val UrlTextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 13.sp,
)
