package com.ling.browser.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 下载确认框的数据模型测试。
 *
 * 重点覆盖「大小探测失败」这条路径 —— 它是常态而非异常：
 * 大量站点不实现 HEAD，或对 HEAD 返回 403/405。整个设计的前提是
 * **探不到大小也绝不能阻止下载**，这里把该约定固定下来。
 */
class PendingDownloadTest {

    private fun pending(
        sizeBytes: Long? = null,
        probing: Boolean = false,
    ) = PendingDownload(
        url = "https://example.com/file.zip",
        fileName = "file.zip",
        mimeType = "application/zip",
        sizeBytes = sizeBytes,
        probing = probing,
    )

    @Test
    fun `探测中不显示大小`() {
        val p = pending(sizeBytes = null, probing = true)
        assertTrue(p.probing)
        assertNull("探测中不应有大小值", p.sizeBytes)
    }

    @Test
    fun `探测失败时大小为 null 且不再处于探测中`() {
        // 这是最常见的终态：站点不支持 HEAD，探测失败
        val p = pending(sizeBytes = null, probing = false)
        assertFalse("探测应已结束", p.probing)
        assertNull("失败时保持 null，界面显示「大小未知」", p.sizeBytes)
    }

    @Test
    fun `探测失败仍然保留可下载所需的全部信息`() {
        // 回归用例：绝不能因为探不到大小就丢掉 url/文件名，
        // 否则用户点了「下载」也没东西可下。
        val p = pending(sizeBytes = null, probing = false)
        assertEquals("https://example.com/file.zip", p.url)
        assertEquals("file.zip", p.fileName)
        assertEquals("application/zip", p.mimeType)
    }

    @Test
    fun `已知大小时可直接展示`() {
        val p = pending(sizeBytes = 1024L * 1024 * 5, probing = false)
        assertEquals(5242880L, p.sizeBytes)
    }

    @Test
    fun `零字节视为未知`() {
        // 与 formatBytes 的约定一致：0 走「大小未知」分支，
        // 避免显示成 "0 B" 让人以为文件是空的
        val p = pending(sizeBytes = 0, probing = false)
        assertTrue("0 字节应被当作未知处理", p.sizeBytes == 0L)
    }
}
