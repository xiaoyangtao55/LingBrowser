package com.ling.browser.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分块下载计划的纯逻辑测试。
 *
 * 重点钉住的是「块边界不能重叠、不能越界」和「进度能正确续算」——
 * 这两件事错了就是静默写坏文件，真机上极难发现。
 */
class ChunkPlanTest {

    @Test
    fun `小文件不启用分块`() {
        assertNull(ChunkPlan.of(ChunkPlan.MIN_TOTAL_BYTES - 1, "/tmp/a"))
    }

    @Test
    fun `块区间连续且恰好覆盖整个文件`() {
        val total = 10_000_000L
        val plan = ChunkPlan.of(total, "/tmp/a")!!

        assertEquals(ChunkPlan.CHUNK_COUNT, plan.chunkCount)
        assertEquals(0L, plan.chunkStart(0))
        var covered = 0L
        for (i in 0 until plan.chunkCount) {
            covered += plan.chunkLength(i)
            // 上一块的终点紧接下一块的起点，既不留空洞也不重叠
            if (i > 0) assertEquals(plan.chunkStart(i), plan.chunkStart(i - 1) + plan.chunkLength(i - 1))
        }
        assertEquals(total, covered)
    }

    @Test
    fun `最后一块吸收除不尽的余数`() {
        val total = ChunkPlan.MIN_TOTAL_BYTES + 1
        val plan = ChunkPlan.of(total, "/tmp/a")!!

        // 未除尽时 chunkSize 向上取整，只有最后一块会短一点
        assertTrue(plan.chunkLength(plan.chunkCount - 1) <= plan.chunkSize)
        assertEquals(total, (0 until plan.chunkCount).sumOf { plan.chunkLength(it) })
    }

    @Test
    fun `未写满时不算完成`() {
        val plan = ChunkPlan.of(ChunkPlan.MIN_TOTAL_BYTES, "/tmp/a")!!
        assertFalse(plan.isComplete())

        for (i in 0 until plan.chunkCount) plan.written[i] = plan.chunkLength(i)
        assertTrue(plan.isComplete())
        assertEquals(ChunkPlan.MIN_TOTAL_BYTES, plan.downloaded())
    }

    @Test
    fun `按块进度累加而不是看文件长度`() {
        val total = 12_000_000L
        val plan = ChunkPlan.of(total, "/tmp/a")!!
        // 只有第 0 块写了一小段：文件可能已经被稀疏写到很远的位置，
        // 但真实进度必须只算这一小段
        plan.written[0] = 1024

        assertEquals(1024L, plan.downloaded())
        assertFalse(plan.isComplete())
    }

    @Test
    fun `解析 Content-Range 的起点与总长`() {
        val header = "bytes 1000-1999/10000"
        assertEquals(1000L, parseContentRangeStart(header))
        assertEquals(10000L, parseContentRangeTotal(header))
    }

    @Test
    fun `总长未知时只取起点`() {
        val header = "bytes 0-0/*"
        assertEquals(0L, parseContentRangeStart(header))
        assertNull(parseContentRangeTotal(header))
    }

    @Test
    fun `无法识别的 Content-Range 不做强校验`() {
        assertNull(parseContentRangeStart(null))
        assertNull(parseContentRangeStart("items 0-0/10"))
        assertNull(parseContentRangeTotal("garbage"))
    }
}
