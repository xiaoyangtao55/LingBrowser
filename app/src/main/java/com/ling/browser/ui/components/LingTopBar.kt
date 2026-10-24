package com.ling.browser.ui.components

import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 全局统一的标题栏配色。
 *
 * ## 为什么标题栏要「透明」
 *
 * Material3 的 `TopAppBar` 内部对容器色做了一次颜色动画（见 Material3 `AppBar.kt`）：
 *
 * ```kotlin
 * val appBarContainerColor = animateColorAsState(
 *     targetColor,
 *     animationSpec = MotionSchemeKeyTokens.DefaultEffects.value(),
 * )
 * ```
 *
 * `MotionSchemeKeyTokens.DefaultEffects` 是 `spring(dampingRatio = 1f, stiffness = 1600)`，
 * 因此标题栏底色在明暗切换时会有约 200~500ms 的渐变；而标题栏下方内容区来自
 * `Scaffold` 的 `containerColor`（即 `MaterialTheme.colorScheme.background`），
 * `Surface` 自身**不做**颜色动画，是瞬时切换的。
 *
 * 结果就是：切换夜间模式时，下方列表「啪」地一下换色，标题栏却还在渐变，
 * 两者时间点明显不一致。
 *
 * ## 为什么透明是安全的
 *
 * 1. 本项目所有色板下 `surface == background`：
 *    - 静态色板 `FallbackLight` / `FallbackDark` 中两者取值相同；
 *    - Material You 动态色板中 `background` 与 `surface` 同为
 *      `neutralVariant98`（浅色）/ `neutralVariant6`（深色）；
 *    - Android 14+（API 34）分支改读系统色，两者的系统色定义同为
 *      `system_surface_light` / `system_background_light`
 *      （浅色 `#FAF8FE`、深色 `#121318`），取值一致。
 *    所以标题栏透出 Scaffold 的 `background` 与原先画 `surface` 视觉完全等价。
 * 2. `containerColor` 恒为 `Color.Transparent` 时，`animateColorAsState` 的目标值
 *    在明暗切换前后**不变**，动画根本不会启动，标题栏区域随 Scaffold 背景瞬时换色，
 *    与下方列表严格同帧。
 * 3. 不需要复刻 M3 的 windowInsets / 高度 / 排版，零布局风险。
 */
@Composable
fun lingTopBarColors(): TopAppBarColors = TopAppBarDefaults.topAppBarColors(
    containerColor = Color.Transparent,
    scrolledContainerColor = Color.Transparent,
)
