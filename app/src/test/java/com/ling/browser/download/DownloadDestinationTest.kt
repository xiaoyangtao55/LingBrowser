package com.ling.browser.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 文件名净化的单元测试。
 *
 * 下载文件名来自用户脚本 / 服务器响应头，都不可信。这里钉住的是"越界删"的
 * 边界：`resolve` / `uniqueName` / `deleteExisting` / `exists` 都吃这个名字，
 * 只要最后一段之外的内容还能漏进来，`File(dir, name)` 就会写到目录外。
 */
class DownloadDestinationTest {

    @Test
    fun `只保留最后一段，路径分隔符两种写法都吃`() {
        assertEquals("x.txt", DownloadDestination.sanitizeFileName("../../files/x.txt"))
        assertEquals("x.txt", DownloadDestination.sanitizeFileName("..\\..\\files\\x.txt"))
        assertEquals("x.txt", DownloadDestination.sanitizeFileName("/etc/passwd/x.txt"))
        // 绝对路径前缀同样被剥掉，剩下的必须是纯文件名。
        assertFalse(DownloadDestination.sanitizeFileName("/data/local/x.txt").contains('/'))
    }

    @Test
    fun `目录本身与控制字符退回默认名`() {
        assertEquals("download", DownloadDestination.sanitizeFileName("."))
        assertEquals("download", DownloadDestination.sanitizeFileName(".."))
        assertEquals("download", DownloadDestination.sanitizeFileName("../"))
        assertEquals("download", DownloadDestination.sanitizeFileName("   "))
        // NUL 会让部分文件系统在此截断，必须剔掉而不是当成普通字符。
        assertEquals("ab.txt", DownloadDestination.sanitizeFileName("a\u0000b.txt"))
    }

    @Test
    fun `超长名字截断到上限`() {
        val result = DownloadDestination.sanitizeFileName("a".repeat(500) + ".txt")
        assertEquals(120, result.length)
    }
}
