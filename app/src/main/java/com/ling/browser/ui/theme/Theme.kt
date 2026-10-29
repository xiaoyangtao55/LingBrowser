package com.ling.browser.ui.theme

import android.os.Build
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ling.browser.data.prefs.AccentColor

/**
 * 「翎」的形状与动效 —— Material 3 Expressive 的两个支点。
 *
 * 1. **更大的圆角**。Expressive 的形态语言把「软」推到极限：按钮是全胶囊，
 *    卡片/面板是 20~36dp 的大圆角，小控件也不再是 4dp 直角。这里把整套
 *    shape token 统一抬大一档（额外加 8dp 起步），各处组件只要读
 *    `MaterialTheme.shapes` 就自动跟上，不必逐个改。
 *
 * 2. **带回弹的弹簧**。M3E 的动效强调"有生命感"：展开/收起会让弹簧轻微过冲
 *    （dampingRatio < 1），而不是缓入缓出的线性滑动。颜色则恰恰相反 ——
 *    颜色插值不应回弹（过渡中会出现一层发灰的中间色），所以颜色一律用
 *    `animateColorAsState` + 短时长 tween，不套用这里的弹簧。
 */
val LingShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

/**
 * 全局动效参数。
 *
 * [expressive] 有轻微过冲（0.62），用于按压反馈、面板展开这类"短距离"动作，
 * 过冲一点点看起来是弹性的；[settle] 近乎临界阻尼，用于尺寸/位置变化，
 * 距离长时过冲会明显到变成抖动。
 */
object LingMotion {
    val expressive = spring<Float>(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow)
    val settle = spring<Float>(dampingRatio = 0.92f, stiffness = Spring.StiffnessMediumLow)
}

/**
 * Material You 3 配色方案。
 *
 * 动态取色（Dynamic Color）是 Material You 的核心：Android 12(API 31) 及以上
 * 直接从系统壁纸推导整套色板。低版本没有这个能力，只能退回固定配色 ——
 * 为了避免"低版本永远是同一片绿"，提供了一组预设色（见 [AccentColor]）。
 *
 * 预设只改「强调色系」（primary / secondary / tertiary 及其容器），
 * 中性色（background / surface / outline）沿用调校好的绿色方案：
 * 既保证六套配色观感统一，也避免手写六份完整色板带来的对比度错误。
 *
 * 中性色额外给出 surfaceContainer 系列 token：M3E 的分层靠「同色不同深度」，
 * 浮动工具栏、面板、卡片都读这组值。不显式给的话会落到 M3 默认的紫灰，
 * 与定制的绿灰背景拼在一起会有一层淡淡的紫味。
 */
private val LingGreen = Color(0xFF1B6C4B)
private val LingGreenLight = Color(0xFF7EDBAE)
private val LingGreenContainer = Color(0xFFA8F2CB)

internal val FallbackLight = lightColorScheme(
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
    outlineVariant = Color(0xFFBFC9C1),
    surfaceTint = LingGreen,
    surfaceDim = Color(0xFFD8DBD5),
    surfaceBright = Color(0xFFF8FAF4),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2F4EF),
    surfaceContainer = Color(0xFFECEEE9),
    surfaceContainerHigh = Color(0xFFE6E8E3),
    surfaceContainerHighest = Color(0xFFE0E3DD),
    inverseSurface = Color(0xFF2E312F),
    inverseOnSurface = Color(0xFFEFF1EC),
    inversePrimary = LingGreenLight,
)

internal val FallbackDark = darkColorScheme(
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
    outlineVariant = Color(0xFF404943),
    surfaceTint = LingGreenLight,
    surfaceDim = Color(0xFF111412),
    surfaceBright = Color(0xFF373A37),
    surfaceContainerLowest = Color(0xFF0C0F0D),
    surfaceContainerLow = Color(0xFF191C1A),
    surfaceContainer = Color(0xFF1D201E),
    surfaceContainerHigh = Color(0xFF282B28),
    surfaceContainerHighest = Color(0xFF323532),
    inverseSurface = Color(0xFFE1E3DF),
    inverseOnSurface = Color(0xFF2E312F),
    inversePrimary = LingGreen,
)

/** 在绿色中性色之上换一套强调色。 */
private fun scheme(
    dark: Boolean,
    primary: Color,
    onPrimary: Color,
    primaryContainer: Color,
    onPrimaryContainer: Color,
    secondary: Color,
    secondaryContainer: Color,
    onSecondaryContainer: Color,
    tertiary: Color,
    tertiaryContainer: Color,
    onTertiaryContainer: Color,
): ColorScheme = (if (dark) FallbackDark else FallbackLight).copy(
    primary = primary,
    onPrimary = onPrimary,
    primaryContainer = primaryContainer,
    onPrimaryContainer = onPrimaryContainer,
    secondary = secondary,
    secondaryContainer = secondaryContainer,
    onSecondaryContainer = onSecondaryContainer,
    tertiary = tertiary,
    tertiaryContainer = tertiaryContainer,
    onTertiaryContainer = onTertiaryContainer,
)

private val BlueLight = scheme(
    dark = false,
    primary = Color(0xFF0B57D0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E3FD),
    onPrimaryContainer = Color(0xFF041E49),
    secondary = Color(0xFF545F71),
    secondaryContainer = Color(0xFFD8E2F1),
    onSecondaryContainer = Color(0xFF111C2B),
    tertiary = Color(0xFF00639B),
    tertiaryContainer = Color(0xFFC2E7FF),
    onTertiaryContainer = Color(0xFF001D33),
)

private val BlueDark = scheme(
    dark = true,
    primary = Color(0xFFA8C7FA),
    onPrimary = Color(0xFF062E6F),
    primaryContainer = Color(0xFF0842A0),
    onPrimaryContainer = Color(0xFFD3E3FD),
    secondary = Color(0xFFBCC7DC),
    secondaryContainer = Color(0xFF3C4758),
    onSecondaryContainer = Color(0xFFD8E2F1),
    tertiary = Color(0xFF7FCFFF),
    tertiaryContainer = Color(0xFF004A77),
    onTertiaryContainer = Color(0xFFC2E7FF),
)

private val PurpleLight = scheme(
    dark = false,
    primary = Color(0xFF6750A4),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEADDFF),
    onPrimaryContainer = Color(0xFF21005D),
    secondary = Color(0xFF625B71),
    secondaryContainer = Color(0xFFE8DEF8),
    onSecondaryContainer = Color(0xFF1D192B),
    tertiary = Color(0xFF7D5260),
    tertiaryContainer = Color(0xFFFFD8E4),
    onTertiaryContainer = Color(0xFF31111D),
)

private val PurpleDark = scheme(
    dark = true,
    primary = Color(0xFFD0BCFF),
    onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378B),
    onPrimaryContainer = Color(0xFFEADDFF),
    secondary = Color(0xFFCCC2DC),
    secondaryContainer = Color(0xFF49454F),
    onSecondaryContainer = Color(0xFFE8DEF8),
    tertiary = Color(0xFFEFB8C8),
    tertiaryContainer = Color(0xFF633B48),
    onTertiaryContainer = Color(0xFFFFD8E4),
)

private val RoseLight = scheme(
    dark = false,
    primary = Color(0xFFB32446),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD9DE),
    onPrimaryContainer = Color(0xFF40000D),
    secondary = Color(0xFF77565B),
    secondaryContainer = Color(0xFFF2DDE0),
    onSecondaryContainer = Color(0xFF2B1518),
    tertiary = Color(0xFF7C5635),
    tertiaryContainer = Color(0xFFFFDCC1),
    onTertiaryContainer = Color(0xFF2D1600),
)

private val RoseDark = scheme(
    dark = true,
    primary = Color(0xFFFFB1C0),
    onPrimary = Color(0xFF66002C),
    primaryContainer = Color(0xFF8F0F3C),
    onPrimaryContainer = Color(0xFFFFD9DE),
    secondary = Color(0xFFE3BDC3),
    secondaryContainer = Color(0xFF5B3F44),
    onSecondaryContainer = Color(0xFFFFD9DE),
    tertiary = Color(0xFFEFBD94),
    tertiaryContainer = Color(0xFF5C3F22),
    onTertiaryContainer = Color(0xFFFFDCC1),
)

private val OrangeLight = scheme(
    dark = false,
    primary = Color(0xFF9A4600),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDCC2),
    onPrimaryContainer = Color(0xFF301400),
    secondary = Color(0xFF75584A),
    secondaryContainer = Color(0xFFF5DED2),
    onSecondaryContainer = Color(0xFF2C160C),
    tertiary = Color(0xFF5B6236),
    tertiaryContainer = Color(0xFFDFE6B6),
    onTertiaryContainer = Color(0xFF1A1D00),
)

private val OrangeDark = scheme(
    dark = true,
    primary = Color(0xFFFFB77C),
    onPrimary = Color(0xFF542100),
    primaryContainer = Color(0xFF783200),
    onPrimaryContainer = Color(0xFFFFDCC2),
    secondary = Color(0xFFE3BFAC),
    secondaryContainer = Color(0xFF5B4134),
    onSecondaryContainer = Color(0xFFFFDCC2),
    tertiary = Color(0xFFC3CA9A),
    tertiaryContainer = Color(0xFF434A1E),
    onTertiaryContainer = Color(0xFFDFE6B6),
)

private val TealLight = scheme(
    dark = false,
    primary = Color(0xFF006A60),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF9EF2E6),
    onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF4A635F),
    secondaryContainer = Color(0xFFCCE8E2),
    onSecondaryContainer = Color(0xFF051F1C),
    tertiary = Color(0xFF416179),
    tertiaryContainer = Color(0xFFC4E7FF),
    onTertiaryContainer = Color(0xFF001E2E),
)

private val TealDark = scheme(
    dark = true,
    primary = Color(0xFF82D5C9),
    onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF005048),
    onPrimaryContainer = Color(0xFF9EF2E6),
    secondary = Color(0xFFB1CCC6),
    secondaryContainer = Color(0xFF324B47),
    onSecondaryContainer = Color(0xFFCCE8E2),
    tertiary = Color(0xFFA9CBE7),
    tertiaryContainer = Color(0xFF284A60),
    onTertiaryContainer = Color(0xFFC4E7FF),
)

/** 取某个预设的完整配色；动态取色不可用时走这里。 */
private fun schemeFor(accent: AccentColor, dark: Boolean): ColorScheme = when (accent) {
    AccentColor.GREEN -> if (dark) FallbackDark else FallbackLight
    AccentColor.BLUE -> if (dark) BlueDark else BlueLight
    AccentColor.PURPLE -> if (dark) PurpleDark else PurpleLight
    AccentColor.ROSE -> if (dark) RoseDark else RoseLight
    AccentColor.ORANGE -> if (dark) OrangeDark else OrangeLight
    AccentColor.TEAL -> if (dark) TealDark else TealLight
}

/** 设置页色块用的代表色。固定取浅色方案的主色：深浅背景下都能看清。 */
fun accentSwatchColor(accent: AccentColor): Color = schemeFor(accent, dark = false).primary

/**
 * @param darkTheme 是否使用深色配色。由调用方根据「跟随系统 / 强制开 / 强制关」决定。
 * @param accentColor 动态取色不可用或关闭时使用的预设配色。
 */
@Composable
fun LingTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /** 是否启用 Material You 动态取色（仅 Android 12+ 生效）。 */
    dynamicColor: Boolean = true,
    accentColor: AccentColor = AccentColor.GREEN,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        else -> schemeFor(accentColor, darkTheme)
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = LingTypography,
        shapes = LingShapes,
        content = content,
    )
}
