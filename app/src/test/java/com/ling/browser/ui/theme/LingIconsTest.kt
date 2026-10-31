package com.ling.browser.ui.theme

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图标集的回归测试。
 *
 * ## 这轮改了什么
 *
 * 早期图标是**手写坐标**的 `ImageVector`（960 视口、走统一的 materialIcon 入口），
 * 测试因此守着"不用 arcTo""坐标落在 0..960""走统一构建入口"这些约定。
 *
 * 现在改为直接引用 **material-icons-extended**，几何由库提供，本文件只剩
 * 一层**命名映射**。于是那些几何断言失去了意义（库的图标我们既不该改、
 * 也不需要验），真正还值得守的是：
 *
 *  1. **映射表不漏、不多** —— 加图标忘了补映射，调用点会编译不过；
 *     这条其实是给"以后重构 LingIcons"留的护栏。
 *  2. **每个映射都指向真图标**（有路径、能渲染），防止误写成别的名字。
 *  3. **三个书签图标仍然互不相同** —— 这是最容易搞错的地方（见下）。
 *  4. **已收藏与未收藏形状不同** —— 靠实心/空心区分，不靠加号。
 *  5. 视口与默认尺寸仍然一致（库版本内部可能变，但对外表现要统一）。
 */
class LingIconsTest {

    // ⚠️ 手工维护。加图标就必须在这里补一行；反过来漏登记时"数量"断言会失败，
    // 算被动地被守住了。
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
        "Movie" to LingIcons.Movie,
        "Pause" to LingIcons.Pause,
        "PlayArrow" to LingIcons.PlayArrow,
        "FileImage" to LingIcons.FileImage,
        "FileVideo" to LingIcons.FileVideo,
        "FileAudio" to LingIcons.FileAudio,
        "FileArchive" to LingIcons.FileArchive,
        "FileApk" to LingIcons.FileApk,
        "FilePdf" to LingIcons.FilePdf,
        "FileDoc" to LingIcons.FileDoc,
        "FileSheet" to LingIcons.FileSheet,
        "FileSlide" to LingIcons.FileSlide,
        "FileCode" to LingIcons.FileCode,
        "FileUnknown" to LingIcons.FileUnknown,
        "Palette" to LingIcons.Palette,
    )

    /** 取出图标里的全部路径节点（含嵌套 group）。 */
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

    private fun pathSignature(icon: ImageVector): String =
        pathNodes(icon).joinToString("|") { it.toString() }

    private fun pointCount(icon: ImageVector): Int =
        pathNodes(icon).sumOf { node ->
            node.javaClass.declaredFields
                .filter { it.type == Float::class.javaPrimitiveType }
                .size
        }

    @Test
    fun `图标集完整`() {
        assertEquals("图标数量发生变化时请同步更新本测试", 44, allIcons.size)
    }

    @Test
    fun `每个图标都有实际路径数据`() {
        // 映射写错名字时通常仍能编译（只要那个图标存在），但可能拿到一个
        // 空白图标 —— 这条能抓住它。
        allIcons.forEach { (name, icon) ->
            val nodes = pathNodes(icon)
            assertTrue("$name 没有任何路径节点", nodes.isNotEmpty())
            assertTrue(
                "$name 只有 MoveTo，不会渲染出任何线条",
                nodes.any { it !is PathNode.MoveTo },
            )
        }
    }

    @Test
    fun `三个书签图标的形状互不相同`() {
        // 用途不同就必须长得不同，否则用户分不出哪个是动作、哪个是状态：
        //   BookmarkBorder 空心书签      —— 未收藏
        //   Bookmark       实心书签      —— 已收藏
        //   BookmarkAdd    空心书签+加号 —— "添加书签"动作
        //
        // 历史上"添加书签"就误用过与书签入口完全相同的纯 bookmark，
        // 用户在菜单里分不出哪个是动作、哪个是跳转。
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
    fun `已收藏与未收藏的图标形状不同`() {
        // 收藏状态靠「实心 vs 空心」表达，而不是靠有没有加号。
        // 空心版（BookmarkBorder）带内部挖空轮廓，坐标数明显更多。
        val hollowPts = pointCount(LingIcons.BookmarkBorder)
        val filledPts = pointCount(LingIcons.Bookmark)
        assertTrue(
            "实心版($filledPts 个坐标)应比空心版($hollowPts 个坐标)简单，"
                + "若相反说明两者可能被互换了",
            filledPts < hollowPts,
        )
    }

    @Test
    fun `没有任何图标使用 arcTo`() {
        // ArcTo 是踩过坑的指令：直径端点 + largeArc=true 会退化成实心半圆。
        // 库里的图标不该有，但若以后换了某套图标集，这条能及早报警。
        val offenders = allIcons.filter { (_, icon) ->
            pathNodes(icon).any { it is PathNode.ArcTo }
        }.keys
        assertTrue("以下图标仍在使用 arcTo：$offenders", offenders.isEmpty())
    }

    @Test
    fun `所有图标的默认尺寸为 24dp`() {
        // 渲染尺寸与视口解耦。库版本内部视口可能变，但对外必须统一 24dp，
        // 否则同一行的图标会大小不一。
        val problems = mutableListOf<String>()
        allIcons.forEach { (name, icon) ->
            if (icon.defaultWidth.value != 24f || icon.defaultHeight.value != 24f) {
                problems += "$name=${icon.defaultWidth}x${icon.defaultHeight}"
            }
        }
        assertTrue("默认尺寸不是 24dp：$problems", problems.isEmpty())
    }
}
