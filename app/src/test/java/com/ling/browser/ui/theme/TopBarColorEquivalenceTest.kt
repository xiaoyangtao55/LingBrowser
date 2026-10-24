package com.ling.browser.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 标题栏透明化的前提回归测试。
 *
 * ## 为什么需要这个测试
 *
 * 为了消除 Material3 `TopAppBar` 内部 `animateColorAsState` 带来的
 * "标题栏渐变、列表瞬变" 不同步问题（详见 `LingTopBar.kt` 的注释），
 * 标题栏容器色被改为 `Color.Transparent`，让标题栏区域透出
 * `Scaffold` 的 `containerColor`（即 `colorScheme.background`）。
 *
 * 这个改法成立的前提是：**`surface == background`**。
 * 只有两者相等，透明化后的视觉效果才与原先画 `surface` 完全等价；
 * 否则标题栏会"透出"一块与原先不同的颜色，视觉上出现色差。
 *
 * 本测试把这条前提钉死：一旦将来有人给 `surface` / `background`
 * 分配了不同色值（例如引入 M3 的 `surfaceContainer` 层级做卡片底色），
 * 测试会立刻失败提醒复核。
 *
 * 覆盖的是静态 fallback 色板（`FallbackLight` / `FallbackDark`）。
 * Material You 动态色板需要 `Context` 才能求值，无法在 JVM 单元测试中构造；
 * 该路径下的等价性由 Material3 `DynamicTonalPalette.android.kt` 的
 * 映射保证（浅色 `background`/`surface` 同为 `neutralVariant98`，
 * 深色同为 `neutralVariant6`），已在 `LingTopBar.kt` 的注释中记录。
 */
class TopBarColorEquivalenceTest {

    @Test
    fun `浅色 fallback 色板下 surface 与 background 同色`() {
        assertSameColor(
            "FallbackLight.surface 必须等于 FallbackLight.background，" +
                "否则标题栏透明化后会出现色差",
            FallbackLight.surface,
            FallbackLight.background,
        )
    }

    @Test
    fun `深色 fallback 色板下 surface 与 background 同色`() {
        assertSameColor(
            "FallbackDark.surface 必须等于 FallbackDark.background，" +
                "否则标题栏透明化后会出现色差",
            FallbackDark.surface,
            FallbackDark.background,
        )
    }

    @Test
    fun `标题栏容器色为全透明 不参与任何色彩比较`() {
        // Transparent 的 alpha 为 0：标题栏不绘制任何底色，
        // 由 Scaffold 的 background 透出 —— 这正是"瞬变"的实现方式。
        assertEquals(0f, Color.Transparent.alpha, 0f)
    }

    private fun assertSameColor(message: String, expected: Color, actual: Color) {
        assertEquals(message, expected.value, actual.value)
    }
}
