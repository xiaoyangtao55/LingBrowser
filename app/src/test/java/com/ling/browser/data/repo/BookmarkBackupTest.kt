package com.ling.browser.data.repo

import com.ling.browser.data.db.Bookmark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 书签备份编解码测试。
 *
 * 这几件事一旦错，用户看到的是"备份文件打得开、但书签少了几条/标题成了乱码"，
 * 手边又没有原数据可对照，所以必须逐条钉住：
 *  - 往返一致：导出的文件再读回来，标题/网址/文件夹/时间都对得上；
 *  - 伪协议拒绝：`javascript:` 之类混进书签等于埋了个点击即执行的入口；
 *  - 合并导入：只新增、不覆盖，重复的如实统计成"跳过"。
 */
class BookmarkBackupTest {

    private fun bm(
        title: String,
        url: String,
        folder: String? = null,
        createdAt: Long = 1_700_000_000_000L,
    ) = Bookmark(id = 0, title = title, url = url, folder = folder, createdAt = createdAt)

    // ------------------------------------------------------- 往返一致

    @Test
    fun `导出再导入后标题_网址_文件夹_时间都不变`() {
        val source = listOf(
            bm("根目录", "https://root.example", folder = null, createdAt = 1_600_000_000_000L),
            bm("新闻一", "https://news.example/1", folder = "新闻", createdAt = 1_610_000_000_000L),
            bm("新闻二", "https://news.example/2", folder = "新闻", createdAt = 1_620_000_000_000L),
            bm("工具", "https://tool.example", folder = "工具", createdAt = 1_630_000_000_000L),
        )

        val parsed = parseNetscapeHtml(toNetscapeHtml(source))

        // 时间精度是秒（格式只到秒），比较时先截断，否则自己跟自己过不去。
        // 按 url 排序对齐两侧顺序 —— 导出会把未分类排在前面、文件夹分组在后，
        // 解析也是按文件先后返回，直接比顺序会把"排序不同"误判成"内容不同"。
        val expected = source.sortedBy { it.url }
            .map { it.title to it.url to it.folder to (it.createdAt / 1000) }
        val actual = parsed.sortedBy { it.url }
            .map { it.title to it.url to it.folder to (it.createdAt / 1000) }
        assertEquals(expected, actual)
    }

    @Test
    fun `中文与 emoji 标题原样保留`() {
        val source = listOf(bm("央视新闻 📺", "https://tv.example"))
        val parsed = parseNetscapeHtml(toNetscapeHtml(source))
        assertEquals("央视新闻 📺", parsed.single().title)
    }

    @Test
    fun `标题里的 html 特殊字符能转义再还原`() {
        // 不转义的话，标题里的 < > " 会让标签提前闭合，解析出来的网址就错了
        val tricky = "A & B <c> \"d\" & 'e'"
        val source = listOf(bm(tricky, "https://amp.example/?a=1&b=2"))
        val parsed = parseNetscapeHtml(toNetscapeHtml(source))
        assertEquals(tricky, parsed.single().title)
        assertEquals("https://amp.example/?a=1&b=2", parsed.single().url)
    }

    // ------------------------------------------------------- 伪协议拒绝

    @Test
    fun `javascript 与 data 伪协议被拒绝导入`() {
        val html = """
            <DL><p>
            <DT><A HREF="https://ok.example">正常</A>
            <DT><A HREF="javascript:alert(1)">脚本</A>
            <DT><A HREF="data:text/html,&lt;script&gt;">内联页</A>
            <DT><A HREF="vbscript:msgbox(1)">老 IE 脚本</A>
            </DL><p>
        """.trimIndent()

        val parsed = parseNetscapeHtml(html)
        assertEquals(listOf("https://ok.example"), parsed.map { it.url })
    }

    @Test
    fun `伪协议判断忽略大小写`() {
        // 浏览器/手工写出来的可能是 JavaScript: 这种大小写
        val html = """<DL><p><DT><A HREF="JavaScript:alert(1)">x</A></DL><p>"""
        assertTrue(parseNetscapeHtml(html).isEmpty())
    }

    @Test
    fun `mailto 之类少见协议不被丢掉`() {
        // 不能因为少见就悄悄删掉用户的收藏
        val html = """<DL><p><DT><A HREF="mailto:a@b.com">写信</A></DL><p>"""
        assertEquals("mailto:a@b.com", parseNetscapeHtml(html).single().url)
    }

    // ------------------------------------------------------- 合并计划

    @Test
    fun `已在库中的网址被跳过并计数`() {
        val parsed = listOf(
            ImportedBookmark("A", "https://a.example", null, 1L),
            ImportedBookmark("B", "https://b.example", null, 2L),
        )
        val plan = planImport(setOf("https://a.example"), parsed)
        assertEquals(listOf("https://b.example"), plan.toAdd.map { it.url })
        assertEquals(1, plan.skipped)
    }

    @Test
    fun `文件内部重复的网址也只留一条`() {
        val parsed = listOf(
            ImportedBookmark("A1", "https://a.example", null, 1L),
            ImportedBookmark("A2", "https://a.example", "别的文件夹", 2L),
        )
        val plan = planImport(emptySet(), parsed)
        assertEquals(1, plan.toAdd.size)
        // 先出现的保留，后一条算跳过
        assertEquals("A1", plan.toAdd.single().title)
        assertEquals(1, plan.skipped)
    }

    @Test
    fun `空输入不产生新增也不崩`() {
        val plan = planImport(emptySet(), emptyList())
        assertTrue(plan.toAdd.isEmpty())
        assertEquals(0, plan.skipped)
    }

    // ------------------------------------------------------- 解析宽容度

    @Test
    fun `单引号与不带引号的 href 都能读`() {
        val html = """
            <DL><p>
            <DT><A HREF='https://single.example'>单引号</A>
            <DT><A HREF=https://bare.example>无引号</A>
            </DL><p>
        """.trimIndent()
        assertEquals(
            setOf("https://single.example", "https://bare.example"),
            parseNetscapeHtml(html).map { it.url }.toSet(),
        )
    }

    @Test
    fun `嵌套文件夹取最内层名字`() {
        val html = """
            <DL><p>
            <DT><H3>外层</H3>
            <DL><p>
            <DT><H3>内层</H3>
            <DL><p>
            <DT><A HREF="https://deep.example">深</A>
            </DL><p>
            </DL><p>
            </DL><p>
        """.trimIndent()
        assertEquals("内层", parseNetscapeHtml(html).single().folder)
    }

    @Test
    fun `标签残缺或多出闭合都不抛异常`() {
        // 真实文件里常见：缺 </DL>、多一个 </DL>、缺 <DT>
        val html = """<DL><p><A HREF="https://a.example">A</A></DL><p></DL><p>"""
        assertEquals(listOf("https://a.example"), parseNetscapeHtml(html).map { it.url })
    }

    @Test
    fun `标题为空时回退成网址`() {
        // 空标题的书签在列表里会是一行空白，用户根本不知道那是什么
        val html = """<DL><p><DT><A HREF="https://empty.example"></A></DL><p>"""
        assertEquals("https://empty.example", parseNetscapeHtml(html).single().title)
    }

    @Test
    fun `数字实体也能还原`() {
        val html = """<DL><p><DT><A HREF="https://e.example">it&#39;s &#x4e2d;文</A></DL><p>"""
        assertEquals("it's 中文", parseNetscapeHtml(html).single().title)
    }

    @Test
    fun `不是书签文件的内容读出来是空的而不是乱码`() {
        assertTrue(parseNetscapeHtml("<html><body>hello</body></html>").isEmpty())
        assertTrue(parseNetscapeHtml("").isEmpty())
    }

    @Test
    fun `未分类书签导出后读回来仍是未分类`() {
        val parsed = parseNetscapeHtml(toNetscapeHtml(listOf(bm("随手存", "https://x.example"))))
        assertEquals(null, parsed.single().folder)
    }

    // ------------------------------------------------------- 上限

    @Test
    fun `备份上限是一个合理的小尺寸`() {
        // 8 MiB：再大就不像书签文件了，继续读只会把内存打爆
        assertEquals(8 * 1024 * 1024, MAX_BACKUP_BYTES)
        assertFalse(MAX_BACKUP_BYTES > 64 * 1024 * 1024)
    }
}
