package com.ling.browser.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 下载数据模型的纯逻辑测试。
 *
 * 只测不依赖 Android 框架的部分：体积格式化、进度计算、状态判定。
 * DownloadManager 的查询需要设备，不在单测覆盖范围内。
 */
class DownloadTest {

    @Test
    fun `进度按已下载与总大小计算`() {
        val e = DownloadEntry(
            downloadId = 1,
            fileName = "a.zip",
            url = "https://e.com/a.zip",
            totalBytes = 1000,
            downloadedBytes = 250,
        )
        assertEquals(0.25f, e.progress, 0.001f)
    }

    @Test
    fun `总大小未知时进度为零而不是崩溃`() {
        // 服务器不给 Content-Length 时系统返回 -1 或 0，除法会炸
        val unknown = DownloadEntry(
            downloadId = 1, fileName = "a", url = "u", totalBytes = 0, downloadedBytes = 500,
        )
        assertEquals(0f, unknown.progress, 0.001f)

        val negative = unknown.copy(totalBytes = -1)
        assertEquals(0f, negative.progress, 0.001f)
    }

    @Test
    fun `进度不会超过百分之百`() {
        // 系统偶有回报已下载略大于总大小的情况，UI 上进度条会溢出
        val e = DownloadEntry(
            downloadId = 1, fileName = "a", url = "u",
            totalBytes = 100, downloadedBytes = 130,
        )
        assertEquals(1f, e.progress, 0.001f)
    }

    @Test
    fun `只有等待中与下载中算进行中`() {
        assertTrue(statusOf(DownloadStatus.PENDING).isRunning)
        assertTrue(statusOf(DownloadStatus.RUNNING).isRunning)
        // 暂停的不能算"进行中"，否则列表会一直显示取消按钮
        assertFalse(statusOf(DownloadStatus.PAUSED).isRunning)
        assertFalse(statusOf(DownloadStatus.SUCCESS).isRunning)
        assertFalse(statusOf(DownloadStatus.FAILED).isRunning)
    }

    @Test
    fun `状态枚举名可用于持久化往返`() {
        // 数据库存的是枚举名，改名会让老数据读不出来
        DownloadStatus.entries.forEach { s ->
            assertEquals(s, DownloadStatus.valueOf(s.name))
            assertTrue("${s.name} 应有中文标签", s.label.isNotBlank())
        }
    }

    @Test
    fun `状态枚举数量变化时提醒同步测试`() {
        // 6 = PENDING / RUNNING / PAUSED / WAITING_NETWORK / SUCCESS / FAILED。
        // 新增状态时这里会失败，提醒同步检查 UI 的 when 分支是否都覆盖到。
        assertEquals(6, DownloadStatus.entries.size)
    }

    @Test
    fun `等网络状态不算用户主动暂停`() {
        // isRunning 只认 PENDING / RUNNING —— WAITING_NETWORK 与 PAUSED 都
        // 不占传输槽位。但 UI 要区分它们：前者会自愈，后者要用户点恢复。
        assertFalse(DownloadStatus.WAITING_NETWORK.name.let { n ->
            DownloadEntry(downloadId = 1, fileName = "a", url = "u", status = DownloadStatus.valueOf(n)).isRunning
        })
        assertEquals("等待网络", DownloadStatus.WAITING_NETWORK.label)
    }

    // ------------------------------------------------------------ 体积格式化

    @Test
    fun `小体积按字节显示`() {
        assertEquals("0 B", format(0))
        assertEquals("512 B", format(512))
        assertEquals("1023 B", format(1023))
    }

    @Test
    fun `按 1024 进制换算`() {
        // 用 1000 会和系统下载列表对不上，用户会以为文件被改了
        assertEquals("1.0 KB", format(1024))
        assertEquals("1.0 MB", format(1024L * 1024))
        assertEquals("1.0 GB", format(1024L * 1024 * 1024))
    }

    @Test
    fun `大于十的单位不显示小数`() {
        // "1023.9 KB" 这种读起来是噪音，取整更清爽
        assertEquals("100 KB", format(1024L * 100))
        assertEquals("1.5 MB", format(1024L * 1536))
    }

    @Test
    fun `超过上限单位不会越界`() {
        // 1024 TB 时 units 只有到 TB，索引不能溢出
        val huge = 1024L * 1024 * 1024 * 1024 * 1024
        val text = format(huge)
        assertTrue("超大体积应仍以 TB 结尾，实际：$text", text.endsWith("TB"))
    }

    // ------------------------------------------------------------ 剩余时间估算

    @Test
    fun `剩余时间按剩余字节与速度计算`() {
        val e = DownloadEntry(
            downloadId = 1, fileName = "a", url = "u",
            status = DownloadStatus.RUNNING,
            totalBytes = 10_000_000, downloadedBytes = 2_000_000, speedBytesPerSec = 1_000_000,
        )
        // (10MB - 2MB) / 1MB per s = 8s
        assertEquals(8L, e.etaSeconds)
    }

    @Test
    fun `速度或总大小未知时算不出剩余时间`() {
        // 头几百毫秒还没采样到速度
        val noSpeed = DownloadEntry(
            downloadId = 1, fileName = "a", url = "u",
            status = DownloadStatus.RUNNING,
            totalBytes = 1000, downloadedBytes = 100, speedBytesPerSec = 0,
        )
        assertNull(noSpeed.etaSeconds)

        // 服务器不给 Content-Length
        val noTotal = noSpeed.copy(speedBytesPerSec = 100, totalBytes = 0)
        assertNull(noTotal.etaSeconds)
    }

    @Test
    fun `非下载中状态没有剩余时间`() {
        // 暂停时速度已归零，界面不该继续倒计时
        val paused = DownloadEntry(
            downloadId = 1, fileName = "a", url = "u",
            status = DownloadStatus.PAUSED,
            totalBytes = 1000, downloadedBytes = 100, speedBytesPerSec = 100,
        )
        assertNull(paused.etaSeconds)
    }

    @Test
    fun `已下完时剩余时间为零而不是负数`() {
        // 下载收尾瞬间已下载可能等于甚至略大于总大小
        val done = DownloadEntry(
            downloadId = 1, fileName = "a", url = "u",
            status = DownloadStatus.RUNNING,
            totalBytes = 1000, downloadedBytes = 1000, speedBytesPerSec = 100,
        )
        assertEquals(0L, done.etaSeconds)

        val over = done.copy(downloadedBytes = 1200)
        assertEquals(0L, over.etaSeconds)
    }

    private fun statusOf(status: DownloadStatus) = DownloadEntry(
        downloadId = 1, fileName = "a", url = "u", status = status,
    )
}

/**
 * 复制一份格式化实现用于测试。
 *
 * 原因：`formatBytes` 定义在 DownloadsScreen.kt，属于 UI 模块；
 * 单测直接引用会连带拉起 Compose 依赖。这里用同一份逻辑做纯函数验证，
 * 并通过 tools/check_download_bytes.py 静态保证两处实现一致。
 */
private fun format(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var idx = 0
    while (value >= 1024 && idx < units.lastIndex) {
        value /= 1024
        idx++
    }
    return if (value < 10) "%.1f %s".format(value, units[idx])
    else "%.0f %s".format(value, units[idx])
}
