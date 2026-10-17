package com.ling.browser.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 断点续传决策的纯逻辑测试。
 *
 * 覆盖的都是真机上难以稳定复现、却最易出错的边界：服务器不支持 Range、
 * 忽略 Range 仍返回 200、文件其实已下完（416）、Content-Length 缺失。
 */
class ResumePlanTest {

    @Test
    fun `首次下载无本地字节时从头开始`() {
        val plan = planResume(existing = 0, responseCode = 200, contentLength = 4096)
        assertFalse(plan.append)
        assertEquals(0L, plan.start)
        assertEquals(4096L, plan.total)
        assertFalse(plan.alreadyComplete)
    }

    @Test
    fun `服务器返回 206 时从已下载处续传`() {
        val plan = planResume(existing = 500, responseCode = 206, contentLength = 1500)
        assertTrue(plan.append)
        assertEquals(500L, plan.start)
        // 总大小 = 已有 + 本次剩余（206 的 Content-Length 只算区间长度）
        assertEquals(2000L, plan.total)
        assertFalse(plan.alreadyComplete)
    }

    @Test
    fun `服务器忽略 Range 返回 200 时必须从头重写`() {
        // 关键回归：若此时仍追加，文件会变成"旧前半段 + 新后半段"的脏数据
        val plan = planResume(existing = 500, responseCode = 200, contentLength = 2000)
        assertFalse(plan.append)
        assertEquals(0L, plan.start)
        assertEquals(2000L, plan.total)
        assertFalse(plan.alreadyComplete)
    }

    @Test
    fun `416 且已有字节视为已下完`() {
        // 上一轮其实已经下完，续传时请求的区间超出资源范围
        val plan = planResume(existing = 2000, responseCode = 416, contentLength = 0)
        assertTrue(plan.alreadyComplete)
        assertFalse(plan.append)
    }

    @Test
    fun `416 但本地没有字节时不算完成`() {
        // 没有任何本地字节却收到 416 属于异常，交给正常失败路径处理
        val plan = planResume(existing = 0, responseCode = 416, contentLength = 0)
        assertFalse(plan.alreadyComplete)
    }

    @Test
    fun `Content-Length 缺失时总大小记为零`() {
        val resume = planResume(existing = 800, responseCode = 206, contentLength = -1)
        assertTrue(resume.append)
        assertEquals(800L, resume.start)
        assertEquals(0L, resume.total)

        val fresh = planResume(existing = 0, responseCode = 200, contentLength = -1)
        assertFalse(fresh.append)
        assertEquals(0L, fresh.total)
    }

    @Test
    fun `206 但没有本地字节时按重写处理`() {
        // 未发 Range 的情况下服务器不该返回 206；真出现也只能从头写，避免错位
        val plan = planResume(existing = 0, responseCode = 206, contentLength = 1024)
        assertFalse(plan.append)
        assertEquals(0L, plan.start)
        assertEquals(1024L, plan.total)
    }
}
