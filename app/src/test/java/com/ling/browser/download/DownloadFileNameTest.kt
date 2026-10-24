package com.ling.browser.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文件名三级优先级解析的单元测试。
 *
 * 三条来源的正确性各自钉住，另外还要钉住「降级」：服务端给了畸形头、
 * URL 是动态直链、MIME 说不出类型时，都必须退到下一档而不是报错或留空。
 *
 * MIME 查表通过 [lookup] 注入，避免单测里碰 Android 的 [android.webkit.MimeTypeMap]。
 */
class DownloadFileNameTest {

    private val stamp = "20260101-120000"

    private fun lookup(mime: String): String? = when (mime) {
        "application/pdf" -> "pdf"
        "image/jpeg" -> "jpg"
        "application/zip" -> "zip"
        else -> null
    }

    private fun resolve(url: String, mime: String? = null, cd: String? = null): String =
        DownloadFileName.resolve(url, mime, cd, stamp = stamp, lookup = ::lookup)

    // ---------------------------------------------------------- 1) Content-Disposition

    @Test
    fun `filename star 优先于 filename 且能解出中文`() {
        val header = "attachment; filename=\"fallback.txt\"; filename*=UTF-8''%E6%8A%BD%E5%8F%96.pdf"
        assertEquals("抽取.pdf", resolve("https://example.com/get?id=1", null, header))
    }

    @Test
    fun `filename star 省略字符集时按 UTF-8`() {
        val header = "attachment; filename*=''%E6%B5%8B%E8%AF%95.txt"
        assertEquals("测试.txt", resolve("https://example.com/get", null, header))
    }

    @Test
    fun `普通 filename 里的 percent encoding 也会解`() {
        val header = "attachment; filename=\"report%20final.pdf\""
        assertEquals("report final.pdf", resolve("https://example.com/get", null, header))
    }

    @Test
    fun `按 ISO-8859-1 读出来的 UTF-8 乱码会还原`() {
        // 服务端把「café.txt」的 UTF-8 字节直接写进头里，HTTP 层按 ISO-8859-1 解出「cafÃ©.txt」
        val header = "attachment; filename=\"caf\u00c3\u00a9.txt\""
        assertEquals("caf\u00e9.txt", resolve("https://example.com/get", null, header))
    }

    @Test
    fun `服务端名字缺后缀时按 MIME 补`() {
        val header = "attachment; filename=\"report\""
        assertEquals("report.pdf", resolve("https://example.com/get", "application/pdf", header))
    }

    @Test
    fun `头部名字带路径穿越时只留末段`() {
        val header = "attachment; filename=\"../../etc/passwd\""
        assertEquals("passwd.pdf", resolve("https://example.com/get", "application/pdf", header))
    }

    @Test
    fun `头部名字已有有效后缀就原样保留不加时间戳`() {
        val header = "attachment; filename=\"nested.jpg\""
        assertEquals("nested.jpg", resolve("https://example.com/get", "application/pdf", header))
    }

    @Test
    fun `解析转义引号`() {
        assertEquals(
            "a\"b.txt",
            DownloadFileName.parseContentDispositionName("attachment; filename=\"a\\\"b.txt\""),
        )
    }

    @Test
    fun `畸形头直接降级而不是报错`() {
        assertNull(DownloadFileName.parseContentDispositionName("attachment; filename="))
        assertNull(DownloadFileName.parseContentDispositionName("attachment"))
        assertNull(DownloadFileName.parseContentDispositionName(null))
        // 解析不出名字时整条链路仍要给出可用的名字
        assertEquals("get_$stamp.pdf", resolve("https://example.com/get", "application/pdf", "attachment"))
    }

    // ---------------------------------------------------------- 2) URL 末段

    @Test
    fun `URL 末段优先于 MIME 反推`() {
        assertEquals(
            "report.zip",
            resolve("https://example.com/files/report.zip?token=abc", "application/pdf"),
        )
    }

    @Test
    fun `URL 末段的 percent encoding 会解`() {
        assertEquals(
            "中文.pdf",
            resolve("https://example.com/%E4%B8%AD%E6%96%87.pdf"),
        )
    }

    @Test
    fun `fragment 与 query 不参与取名字`() {
        assertEquals("report.pdf", DownloadFileName.nameFromUrl("https://x/y/report.pdf#page=2"))
        assertNull(DownloadFileName.nameFromUrl("https://example.com/"))
        assertNull(DownloadFileName.nameFromUrl("https://example.com"))
    }

    // ---------------------------------------------------------- 3) 时间戳兜底

    @Test
    fun `动态后缀（php）走时间戳兜底并补 MIME 后缀`() {
        assertEquals(
            "download_$stamp.pdf",
            resolve("https://example.com/download.php?id=123", "application/pdf"),
        )
    }

    @Test
    fun `动态后缀（html）且无 MIME 时不带后缀`() {
        assertEquals(
            "view_$stamp",
            resolve("https://example.com/view.html"),
        )
    }

    @Test
    fun `空路径走时间戳兜底`() {
        assertEquals(
            "download_$stamp.jpg",
            resolve("https://example.com/", "image/jpeg"),
        )
    }

    @Test
    fun `octet-stream 推不出后缀`() {
        assertEquals(
            "download_$stamp",
            resolve("https://example.com/download?id=1", "application/octet-stream"),
        )
    }

    // ---------------------------------------------------------- 后缀判定

    @Test
    fun `后缀判定只看像不像真后缀`() {
        assertTrue(DownloadFileName.hasUsableExtension("a.pdf"))
        assertTrue(DownloadFileName.hasUsableExtension("a.tar.gz"))
        assertFalse(DownloadFileName.hasUsableExtension("download.php"))
        assertFalse(DownloadFileName.hasUsableExtension("index.html"))
        assertFalse(DownloadFileName.hasUsableExtension("noext"))
        assertFalse(DownloadFileName.hasUsableExtension(".hidden"))
        assertFalse(DownloadFileName.hasUsableExtension("trailing."))
        assertFalse(DownloadFileName.hasUsableExtension("a.中文"))
    }
}
