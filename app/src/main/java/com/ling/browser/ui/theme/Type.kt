package com.ling.browser.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 「翎」的排版：直接沿用 Material 3 默认度量，只把标题略微收紧，
 * 让地址栏与列表在手机上更紧凑 —— 呼应「极简」的定位。
 */
val LingTypography = Typography().let { base ->
    Typography(
        displayLarge = base.displayLarge,
        displayMedium = base.displayMedium,
        displaySmall = base.displaySmall,
        headlineLarge = base.headlineLarge,
        headlineMedium = base.headlineMedium,
        headlineSmall = base.headlineSmall,
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Medium),
        titleSmall = base.titleSmall,
        bodyLarge = base.bodyLarge,
        bodyMedium = base.bodyMedium,
        bodySmall = base.bodySmall.copy(fontFamily = FontFamily.Default),
        labelLarge = base.labelLarge,
        labelMedium = base.labelMedium,
        labelSmall = base.labelSmall,
    )
}

/** 地址栏等宽字体场景（例如显示 URL 时）统一使用这个样式。 */
val UrlTextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 13.sp,
)
