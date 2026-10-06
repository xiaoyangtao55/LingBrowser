package com.ling.browser.data.repo

import com.ling.browser.data.db.Bookmark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文件夹名推导测试。
 *
 * 文件夹**不单独建表**，而是从"哪些书签归属于它"派生出来。
 * 这样天然不会出现"文件夹表里有个空壳、点进去什么都没有"的幽灵文件夹，
 * 但推导规则本身有几个容易写错的边界，用测试钉住。
 */
class BookmarkFolderTest {

    private var nextId = 1L
    private fun bm(folder: String?) = Bookmark(
        id = nextId++,
        title = "t",
        url = "https://example.com/$nextId",
        folder = folder,
    )

    // ------------------------------------------------------- 基本行为

    @Test
    fun `只有被引用的文件夹才会出现`() {
        val folders = deriveFolders(listOf(bm("新闻"), bm(null), bm("工具")))
        assertEquals(listOf("工具", "新闻"), folders)
    }

    @Test
    fun `空书签列表推导出空文件夹列表`() {
        assertTrue(deriveFolders(emptyList()).isEmpty())
    }

    @Test
    fun `全部未分类时没有文件夹`() {
        assertTrue(deriveFolders(listOf(bm(null), bm(null))).isEmpty())
    }

    // ------------------------------------------------------- 去重

    @Test
    fun `同名文件夹只出现一次`() {
        assertEquals(listOf("新闻"), deriveFolders(listOf(bm("新闻"), bm("新闻"), bm("新闻"))))
    }

    @Test
    fun `两侧空白被忽略，带空格与不带空格视为同一个`() {
        // 不 trim 的话用户会看到两个看起来完全一样的条目
        val folders = deriveFolders(listOf(bm("新闻"), bm("  新闻  ")))
        assertEquals(listOf("新闻"), folders)
    }

    @Test
    fun `空串与纯空白不算文件夹`() {
        // 空串是脏数据（早期 insert 可能写过），必须当成"未分类"
        assertTrue(deriveFolders(listOf(bm(""), bm("   "), bm(null))).isEmpty())
    }

    @Test
    fun `大小写不同视为不同文件夹`() {
        // 刻意不合并：英文用户可能真想要两个不同的文件夹。
        // 中文场景无影响。
        val folders = deriveFolders(listOf(bm("News"), bm("news")))
        assertEquals(listOf("News", "news"), folders)
    }

    // ------------------------------------------------------- 顺序

    @Test
    fun `按字典序排列且忽略大小写`() {
        // 用 CASE_INSENSITIVE_ORDER：否则 'Z'(90) 会排在 'a'(97) 前面，
        // 用户看到的是"Z 开头的挤在 a 开头前面"这种莫名其妙的分组
        val folders = deriveFolders(listOf(bm("banana"), bm("Apple"), bm("cherry")))
        assertEquals(listOf("Apple", "banana", "cherry"), folders)
    }

    @Test
    fun `顺序与书签插入顺序无关`() {
        val a = deriveFolders(listOf(bm("乙"), bm("甲")))
        val b = deriveFolders(listOf(bm("甲"), bm("乙")))
        assertEquals("同样的集合必须得到同样的顺序，否则 UI 每次刷新都会跳", a, b)
    }

    // ------------------------------------------------------- 与筛选的一致性

    @Test
    fun `推导出的每个文件夹都至少有一个书签`() {
        // 这就是"不建 folders 表"的意义：不存在空文件夹
        val list = listOf(bm("有"), bm(null), bm("也有"))
        deriveFolders(list).forEach { f ->
            assertTrue(
                "文件夹「$f」必须至少有一条书签，否则就是幽灵文件夹",
                list.any { it.folder == f },
            )
        }
    }

    @Test
    fun `删除最后一个书签后该文件夹消失`() {
        val before = listOf(bm("临时"), bm("保留"))
        // 按码位比较：临 U+4E34 < 保 U+4FDD
        assertEquals(listOf("临时", "保留"), deriveFolders(before))
        val after = before.filterNot { it.folder == "临时" }
        assertEquals(listOf("保留"), deriveFolders(after))
    }

    @Test
    fun `重命名文件夹等价于改归属，旧名不再出现`() {
        val list = mutableListOf(bm("旧名"), bm("旧名"), bm("别的"))
        val renamed = list.map { if (it.folder == "旧名") it.copy(folder = "新名") else it }
        val folders = deriveFolders(renamed)
        assertEquals(listOf("别的", "新名"), folders)
        assertTrue("旧名不该残留", "旧名" !in folders)
    }

    @Test
    fun `解散文件夹后书签回到未分类且文件夹消失`() {
        // deleteFolder 的实现是 renameFolder(folder, null)
        val list = listOf(bm("解散我"), bm(null))
        val dissolved = list.map { if (it.folder == "解散我") it.copy(folder = null) else it }
        assertTrue(deriveFolders(dissolved).isEmpty())
        assertEquals("书签本身不能被删掉，只是退回未分类", 2, dissolved.size)
    }

    // ------------------------------------------------------- 内部空格

    @Test
    fun `名字内部的空格保留`() {
        assertEquals(listOf("我的 收藏"), deriveFolders(listOf(bm("我的 收藏"))))
    }
}
