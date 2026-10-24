package com.ling.browser.data.repo

import com.ling.browser.data.db.HistoryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 历史备份编解码测试。
 *
 * 历史导出以前只能"看"，加上导入以后就是真正的备份格式，因此必须钉住：
 *  - 往返一致：时间、标题、网址、次数都对得上；
 *  - 误选文件不导垃圾：普通网页里的表格链接不能变成浏览记录；
 *  - 伪协议拒绝：`javascript:` 不能混进历史列表（点一下就跑代码）；
 *  - 合并导入：只新增，重复的如实统计成"跳过"。
 */
class HistoryExportTest {

    private fun entry(
        title: String,
        url: String,
        visitedAt: Long = 1_700_000_000_000L,
        visitCount: Int = 1,
    ) = HistoryEntry(title = title, url = url, visitedAt = visitedAt, visitCount = visitCount)

    // ------------------------------------------------------- 往返一致

    @Test
    fun `导出再导入后时间_标题_网址_次数都不变`() {
        val source = listOf(
            entry("示例", "https://example.com", visitedAt = 1_600_000_000_000L, visitCount = 3),
            entry("新闻", "https://news.example/1", visitedAt = 1_610_000_000_000L, visitCount = 1),
        )

        val parsed = parseHistoryHtml(toHistoryHtml(source))

        // 时间精度只到秒（导出格式如此），用例里用整秒的时间戳，
        // 直接比较即可；按 url 排序避免顺序差异被误判成内容差异。
        val expected = source.sortedBy { it.url }
            .map { it.title to it.url to it.visitedAt to it.visitCount }
        val actual = parsed.sortedBy { it.url }
            .map { it.title to it.url to it.visitedAt to it.visitCount }
        assertEquals(expected, actual)
    }

    @Test
    fun `标题里的 html 特殊字符能转义再还原`() {
        val tricky = "A & B <c> \"d\" & 'e'"
        val parsed = parseHistoryHtml(toHistoryHtml(listOf(entry(tricky, "https://t.example"))))
        assertEquals(tricky, parsed.single().title)
    }

    // ------------------------------------------------------- 误选文件

    @Test
    fun `普通网页的表格链接不会被当成历史`() {
        // 只有两列（没有次数列），不符合本应用的导出格式
        val html = """
            <table>
              <tr><td>名称</td><td><a href="https://evil.example">点我</a></td></tr>
            </table>
        """.trimIndent()
        assertTrue(parseHistoryHtml(html).isEmpty())
    }

    @Test
    fun `伪协议链接被拒绝`() {
        val html = """
            <table>
              <tr>
                <td>2026-01-01 00:00:00</td>
                <td><a href="javascript:alert(1)">x</a></td>
                <td>1</td>
              </tr>
            </table>
        """.trimIndent()
        assertTrue(parseHistoryHtml(html).isEmpty())
    }

    @Test
    fun `同一网址在文件内只保留第一条`() {
        val source = listOf(
            entry("第一条", "https://dup.example", visitCount = 2),
            entry("第二条", "https://dup.example", visitCount = 5),
        )
        val parsed = parseHistoryHtml(toHistoryHtml(source))
        assertEquals(1, parsed.size)
        assertEquals("第一条", parsed.single().title)
        assertEquals(2, parsed.single().visitCount)
    }

    @Test
    fun `时间列无法解析时仍保留条目`() {
        val html = """
            <table>
              <tr>
                <td>不是时间</td>
                <td><a href="https://time.example">x</a></td>
                <td>1</td>
              </tr>
            </table>
        """.trimIndent()
        val parsed = parseHistoryHtml(html)
        assertEquals(1, parsed.size)
        assertTrue("应回落到导入时刻", parsed.single().visitedAt > 0)
    }

    @Test
    fun `次数缺失或非法时该行被跳过`() {
        val html = """
            <table>
              <tr>
                <td>2026-01-01 00:00:00</td>
                <td><a href="https://bad.example">x</a></td>
                <td>many</td>
              </tr>
            </table>
        """.trimIndent()
        assertTrue(parseHistoryHtml(html).isEmpty())
    }

    // ------------------------------------------------------- 合并计划

    @Test
    fun `导入计划跳过库里已有的网址`() {
        val parsed = listOf(
            ImportedHistory("新", "https://new.example", 1L, 1),
            ImportedHistory("旧", "https://old.example", 2L, 1),
        )
        val plan = planHistoryImport(setOf("https://old.example"), parsed)
        assertEquals(1, plan.toAdd.size)
        assertEquals(1, plan.skipped)
        assertEquals("https://new.example", plan.toAdd.single().url)
    }

    @Test
    fun `导入计划也跳过文件内的重复项`() {
        val parsed = listOf(
            ImportedHistory("一", "https://dup.example", 1L, 1),
            ImportedHistory("二", "https://dup.example", 2L, 1),
        )
        val plan = planHistoryImport(emptySet(), parsed)
        assertEquals(1, plan.toAdd.size)
        assertEquals(1, plan.skipped)
        assertFalse(plan.toAdd.any { it.title == "二" })
    }
}
