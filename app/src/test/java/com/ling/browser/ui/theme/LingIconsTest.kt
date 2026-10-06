package com.ling.browser.ui.theme

import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图标几何的回归测试。
 *
 * 背景：早期版本用 `arcTo` + 大弧标志画圆弧，当起点与终点恰好是圆的
 * 直径两端时，圆弧会退化成半圆饼，描边后视觉上变成一个"实心色块"——
 * 刷新图标因此被画成了水桶、锁梁被画成了实心矩形。
 *
 * 现在统一约定：**不使用 arcTo**，圆弧一律用 arcPolyline 折线近似。
 * 下面的用例把这个约定固化下来，防止以后改图标时又退回去。
 */
class LingIconsTest {

    /** 取出图标里所有描边路径的 PathNode 列表。 */
    private fun pathNodes(icon: ImageVector): List<PathNode> {
        val nodes = mutableListOf<PathNode>()
        fun visit(group: VectorGroup) {
            group.forEach { child ->
                when (child) {
                    is VectorPath -> nodes += child.pathData
                    is VectorGroup -> visit(child)
                }
            }
        }
        icon.root.forEach { child ->
            when (child) {
                is VectorPath -> nodes += child.pathData
                is VectorGroup -> visit(child)
            }
        }
        return nodes
    }

    private val allIcons: Map<String, ImageVector> = mapOf(
        "ArrowBack" to LingIcons.ArrowBack,
        "ArrowForward" to LingIcons.ArrowForward,
        "Close" to LingIcons.Close,
        "Refresh" to LingIcons.Refresh,
        "Home" to LingIcons.Home,
        "Layers" to LingIcons.Layers,
        "MoreVert" to LingIcons.MoreVert,
        "Add" to LingIcons.Add,
        "BookmarkBorder" to LingIcons.BookmarkBorder,
        "History" to LingIcons.History,
        "DeleteOutline" to LingIcons.DeleteOutline,
        "Search" to LingIcons.Search,
        "Lock" to LingIcons.Lock,
        "Public" to LingIcons.Public,
        "Nightlight" to LingIcons.Nightlight,
        "AutoAwesome" to LingIcons.AutoAwesome,
        "Javascript" to LingIcons.Javascript,
        "Image" to LingIcons.Image,
        "DeleteSweep" to LingIcons.DeleteSweep,
        "PrivacyTip" to LingIcons.PrivacyTip,
        "DesktopWindows" to LingIcons.DesktopWindows,
        "Settings" to LingIcons.Settings,
        "Download" to LingIcons.Download,
        "Folder" to LingIcons.Folder,
        "FolderOpen" to LingIcons.FolderOpen,
    )

    @Test
    fun `图标集完整`() {
        assertEquals("图标数量发生变化时请同步更新本测试", 25, allIcons.size)
    }

    @Test
    fun `没有任何图标使用 arcTo`() {
        // ArcTo 是踩过坑的指令：直径端点 + largeArc=true 会退化成实心半圆。
        val offenders = allIcons.filter { (_, icon) ->
            pathNodes(icon).any { it is PathNode.ArcTo }
        }.keys
        assertTrue(
            "以下图标仍在使用 arcTo，请改用 arcPolyline：$offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `每个图标都有实际路径数据`() {
        allIcons.forEach { (name, icon) ->
            val nodes = pathNodes(icon)
            assertTrue("$name 没有任何路径节点", nodes.isNotEmpty())
            // 只有 MoveTo 等于画不出东西
            assertTrue(
                "$name 只有 MoveTo，不会渲染出任何线条",
                nodes.any { it !is PathNode.MoveTo },
            )
        }
    }

    @Test
    fun `每个图标都以绘制指令开头`() {
        allIcons.forEach { (name, icon) ->
            val nodes = pathNodes(icon)
            assertTrue(
                "$name 的第一条指令应为 MoveTo，否则路径起点不确定",
                nodes.first() is PathNode.MoveTo,
            )
        }
    }

    @Test
    fun `所有坐标都在 24x24 视口内`() {
        // 越界坐标会被裁切，图标看起来像被切掉一块。
        // 允许 1 个单位的容差：描边线宽 1.8，边缘图标可以略微出界。
        val tolerance = 1.0f
        val problems = mutableListOf<String>()
        allIcons.forEach { (name, icon) ->
            pathNodes(icon).forEach { node ->
                node.javaClass.declaredFields
                    .filter { it.type == Float::class.javaPrimitiveType }
                    .forEach { f ->
                        f.isAccessible = true
                        val v = f.getFloat(node)
                        if (v < -tolerance || v > 24f + tolerance) {
                            problems += "$name.${node::class.simpleName}.${f.name}=$v"
                        }
                    }
            }
        }
        assertTrue("坐标越界：$problems", problems.isEmpty())
    }

    @Test
    fun `刷新图标包含圆弧折线而非退化的闭合块`() {
        // 刷新图标用约 24 段折线近似 300° 圆环，再加箭头 2 段，共 25~26 条 LineTo。
        // 若退化成"用几段直线/一条 arcTo 凑出来"，线段数会明显偏少。
        val lineTos = pathNodes(LingIcons.Refresh).count { it is PathNode.LineTo }
        assertTrue(
            "刷新图标的线段数过少（$lineTos），圆环可能已退化",
            lineTos >= 20,
        )
        assertTrue(
            "刷新图标的线段数过多（$lineTos），可能误加了多余的折线",
            lineTos <= 60,
        )
    }

    @Test
    fun `描边图标不应使用填充`() {
        // 描边图标若误用了填充，会渲染成一坨实心色块。
        val stroked = setOf(
            "ArrowBack", "ArrowForward", "Close", "Refresh", "Home", "Layers", "Add",
            "BookmarkBorder", "History", "DeleteOutline", "Search", "Lock", "Public",
            "Nightlight", "Javascript", "Image", "DeleteSweep", "PrivacyTip",
            "DesktopWindows", "Settings",
        )
        val problems = mutableListOf<String>()
        allIcons.filterKeys { it in stroked }.forEach { (name, icon) ->
            icon.root.forEach { child ->
                if (child is VectorPath) {
                    val fill = child.fill
                    if (fill !is SolidColor || fill.value.alpha != 0f) {
                        problems += name
                    }
                }
            }
        }
        assertTrue("以下描边图标带有填充：$problems", problems.isEmpty())
    }
}
