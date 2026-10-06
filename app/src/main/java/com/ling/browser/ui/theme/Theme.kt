package com.ling.browser.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Material You 3 配色方案。
 *
 * 动态取色（Dynamic Color）是 Material You 的核心：Android 12(API 31) 及以上
 * 直接从系统壁纸推导整套色板。低版本回退到手工调校的「翎」品牌绿。
 */
private val LingGreen = Color(0xFF1B6C4B)
private val LingGreenLight = Color(0xFF7EDBAE)
private val LingGreenContainer = Color(0xFFA8F2CB)

private val FallbackLight = lightColorScheme(
    primary = LingGreen,
    onPrimary = Color.White,
    primaryContainer = LingGreenContainer,
    onPrimaryContainer = Color(0xFF00210F),
    secondary = Color(0xFF4E6355),
    secondaryContainer = Color(0xFFD0E8D7),
    onSecondaryContainer = Color(0xFF0B1F14),
    tertiary = Color(0xFF3A6470),
    tertiaryContainer = Color(0xFFBEEAF8),
    onTertiaryContainer = Color(0xFF001F27),
    background = Color(0xFFFBFDF8),
    onBackground = Color(0xFF191C1A),
    surface = Color(0xFFFBFDF8),
    onSurface = Color(0xFF191C1A),
    surfaceVariant = Color(0xFFDCE5DC),
    onSurfaceVariant = Color(0xFF404943),
    outline = Color(0xFF707973),
)

private val FallbackDark = darkColorScheme(
    primary = LingGreenLight,
    onPrimary = Color(0xFF00391F),
    primaryContainer = Color(0xFF005231),
    onPrimaryContainer = LingGreenContainer,
    secondary = Color(0xFFB4CCBB),
    secondaryContainer = Color(0xFF364B3E),
    onSecondaryContainer = Color(0xFFD0E8D7),
    tertiary = Color(0xFFA2CDDB),
    tertiaryContainer = Color(0xFF204C58),
    onTertiaryContainer = Color(0xFFBEEAF8),
    background = Color(0xFF111412),
    onBackground = Color(0xFFE1E3DF),
    surface = Color(0xFF111412),
    onSurface = Color(0xFFE1E3DF),
    surfaceVariant = Color(0xFF404943),
    onSurfaceVariant = Color(0xFFC0C9C1),
    outline = Color(0xFF8A938C),
)

/**
 * @param darkTheme 是否使用深色配色。由调用方根据「跟随系统 / 强制开 / 强制关」决定。
 */
@Composable
fun LingTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /** 是否启用 Material You 动态取色（仅 Android 12+ 生效）。 */
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> FallbackDark
        else -> FallbackLight
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = LingTypography,
        content = content,
    )
}
