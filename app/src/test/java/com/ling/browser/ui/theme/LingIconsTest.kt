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
 * ## 历史
 *
 * 早期版本图标是**手绘描边**的，用 `arcTo` + 大弧标志画圆弧。当起点与终点
 * 恰好是圆的直径两端时，圆弧会退化成半圆饼，描边后视觉上是"实心色块"——
 * 刷新图标被画成水桶、锁梁被画成实心矩形。当时的测试因此固化了
 * 「不使用 arcTo」「描边图标不应有填充」「坐标必须在 24x24 内」等约定。
 *
 * ## 现在
 *
 * 图标已全部换成 **Material Symbols Outlined 官方图标**，由
 * `tools/import_material_icons.py` 从 Android VectorDrawable 自动转换。
 * 于是：
 *   - 全部改为**填充**（原来"描边不应有填充"的用例已失去意义）；
 *   - 视口从 24 改为官方的 **960**（"坐标在 24 内"同样失效）；
 *   - `arcTo` 依然不用 —— 官方源文件里只有 M/L/Q/Z，转换器会拒绝其它命令。
 *
 * 保留下来的是真正有价值的约定：不用 arcTo、每个图标有实际路径、
 * 视口一致、必须走统一的 materialIcon 构建入口。
 */
class LingIconsTest {

    /** 官方图标原始网格是 960x960。 */
    private val viewport = 960f

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

    /** 把图标的全部路径节点压成一个可比对的字符串指纹。 */
    private fun pathSignature(icon: ImageVector): String =
        pathNodes(icon).joinToString("|") { it.toString() }

    // ⚠️ 这是一份**手工维护**的清单。加了新图标就必须在这里补一行，
    // 否则"数量"断言会对不上 —— 但反过来说，这里漏登记时数量断言会失败，
    // 算是被动地被守住了。
    // 注意别重复登记：BookmarkBorder 曾经出现过两次，把总数虚高了一个。
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
        "Bookmark" to LingIcons.Bookmark,
        "BookmarkAdd" to LingIcons.BookmarkAdd,
        "History" to LingIcons.History,
        "DeleteOutline" to LingIcons.DeleteOutline,
        "DeleteSweep" to LingIcons.DeleteSweep,
        "Search" to LingIcons.Search,
        "Download" to LingIcons.Download,
        "Folder" to LingIcons.Folder,
        "FolderOpen" to LingIcons.FolderOpen,
        "Lock" to LingIcons.Lock,
        "Public" to LingIcons.Public,
        "Nightlight" to LingIcons.Nightlight,
        "AutoAwesome" to LingIcons.AutoAwesome,
        "Javascript" to LingIcons.Javascript,
        "Image" to LingIcons.Image,
        "PrivacyTip" to LingIcons.PrivacyTip,
        "DesktopWindows" to LingIcons.DesktopWindows,
        "Settings" to LingIcons.Settings,
        "Article" to LingIcons.Article,
        "Block" to LingIcons.Block,
    )

    @Test
    fun `图标集完整`() {
        assertEquals("图标数量发生变化时请同步更新本测试", 29, allIcons.size)
    }

    @Test
    fun `三个书签图标的形状互不相同`() {
        // 用途不同就必须长得不同，否则用户分不出哪个是动作、哪个是状态：
        //   BookmarkBorder 空心书签      —— 未收藏
        //   Bookmark       实心书签      —— 已收藏
        //   BookmarkAdd    空心书签+加号 —— "添加书签"动作
        //
        // 这里直接比对 pathData 字符串：三者只要有任何两个相同，
        // 就意味着某个场景的图标用错了（历史上"添加书签"就误用过
        // 与书签入口完全相同的纯 bookmark）。
        val sigs = listOf(
            "BookmarkBorder" to LingIcons.BookmarkBorder,
            "Bookmark" to LingIcons.Bookmark,
            "BookmarkAdd" to LingIcons.BookmarkAdd,
        ).map { (name, icon) -> name to pathSignature(icon) }
        assertEquals(
            "三个书签图标必须两两不同，实际出现了重复：$sigs",
            3,
            sigs.map { it.second }.toSet().size,
        )
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
    fun `所有坐标都在 960 官方网格内`() {
        // Material Symbols 的原始网格是 960x960，内容应落在 120..840 的
        // 安全边距内（见 tools/synth_missing_icons.py 的规格说明）。
        // 这里放宽到 0..960 只查"越界"，因为个别官方图标（如 menu 的
        // 圆角）会略微贴近边界。真正被裁切的坐标才会超出整个网格。
        val problems = mutableListOf<String>()
        allIcons.forEach { (name, icon) ->
            pathNodes(icon).forEach { node ->
                node.javaClass.declaredFields
                    .filter { it.type == Float::class.javaPrimitiveType }
                    .forEach { f ->
                        f.isAccessible = true
                        val v = f.getFloat(node)
                        if (v < 0f || v > viewport) {
                            problems += "$name.${node::class.simpleName}.${f.name}=$v"
                        }
                    }
            }
        }
        assertTrue("坐标越界（应落在 0..960）：$problems", problems.isEmpty())
    }

    @Test
    fun `刷新图标保留了圆环所需的曲线`() {
        // 官方 refresh 是"圆环 + 箭头"，圆环靠 QuadTo 曲线表达。
        // 若转换时把 Q 命令丢掉或误当直线处理，圆环会退化成多边形/方块，
        // 所以这里要求它既有足够多的曲线，也有实际线段。
        val nodes = pathNodes(LingIcons.Refresh)
        val quads = nodes.count { it is PathNode.QuadTo }
        val lines = nodes.count { it is PathNode.LineTo }
        assertTrue("刷新图标的曲线过少（$quads），圆环可能已退化", quads >= 8)
        assertTrue("刷新图标应同时有直线构成箭头（$lines）", lines >= 1)
    }

    @Test
    fun `图标为填充样式`() {
        // 换成官方 Material Symbols 后，全部是填充路径（颜色由 Compose 的
        // Icon(tint=...) 覆盖）。若某个图标还是透明填充，会完全看不见。
        // 这条与旧版"描边图标不应有填充"恰好相反 —— 设计换了，测试也要换。
        val problems = mutableListOf<String>()
        allIcons.forEach { (name, icon) ->
            icon.root.forEach { child ->
                if (child is VectorPath) {
                    val fill = child.fill
                    if (fill !is SolidColor || fill.value.alpha == 0f) {
                        problems += name
                    }
                }
            }
        }
        assertTrue("以下图标没有可见填充：$problems", problems.isEmpty())
    }

    @Test
    fun `所有图标使用统一的 960 视口`() {
        // 视口不一致会导致同尺寸图标看起来大小不一。
        // 官方导出全部是 960，转换时若误改会立刻暴露。
        val problems = mutableListOf<String>()
        allIcons.forEach { (name, icon) ->
            if (icon.viewportWidth != viewport || icon.viewportHeight != viewport) {
                problems += "$name=${icon.viewportWidth}x${icon.viewportHeight}"
            }
        }
        assertTrue("视口不是 960x960：$problems", problems.isEmpty())
    }

    @Test
    fun `所有图标默认尺寸为 24dp`() {
        // 渲染尺寸与视口解耦：960 视口 + 24dp 默认尺寸。
        val problems = mutableListOf<String>()
        allIcons.forEach { (name, icon) ->
            if (icon.defaultWidth.value != 24f || icon.defaultHeight.value != 24f) {
                problems += "$name=${icon.defaultWidth}x${icon.defaultHeight}"
            }
        }
        assertTrue("默认尺寸不是 24dp：$problems", problems.isEmpty())
    }

    @Test
    fun `已收藏与未收藏的图标形状不同`() {
        // 收藏状态靠「实心 vs 空心」表达，而不是靠有没有加号。
        //
        // 这两个图标来自官方的不同源文件：
        //   BookmarkBorder <- bookmark             （空心，内部有挖空轮廓）
        //   Bookmark       <- bookmark_added_fill  （实心，无内部挖空）
        //
        // 极易搞错的是：`bookmark_added`（不带 _fill）其实是**空心**版，
        // 只比 bookmark 多一个加号，在 24dp 下两种状态几乎分不出来。
        // 这条用例把这个区分固定下来。
        val hollow = pathNodes(LingIcons.BookmarkBorder)
        val filled = pathNodes(LingIcons.Bookmark)

        assertTrue("未收藏图标应有路径", hollow.isNotEmpty())
        assertTrue("已收藏图标应有路径", filled.isNotEmpty())

        // 空心版带内部挖空轮廓，坐标数明显更多
        fun ptCount(nodes: List<PathNode>) = nodes.sumOf { node ->
            node.javaClass.declaredFields
                .filter { it.type == Float::class.javaPrimitiveType }
                .size
        }
        val hollowPts = ptCount(hollow)
        val filledPts = ptCount(filled)
        assertTrue(
            "实心版($filledPts 个坐标)应比空心版($hollowPts 个坐标)简单，"
                + "若相反说明两者可能被互换了",
            filledPts < hollowPts,
        )
    }
}
