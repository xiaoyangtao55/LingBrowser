package com.ling.browser.download

import com.ling.browser.data.db.DownloadEntry
import com.ling.browser.data.db.DownloadStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `liveTasks()` 是「服务要不要继续活着」的唯一判据。
 *
 * 关键回归点：**等待网络的任务必须算活任务**。曾经它不是，于是所有任务
 * 进入 WAITING_NETWORK 后服务收到空集合、撤掉通知并退出，而引擎的
 * serviceRunning 仍是 true；网络恢复后引擎自动续传却不会再启动服务，
 * 后台进程随时被回收 —— 表现为「回家连上 Wi-Fi 下载又断了，通知也没了」。
 */
class LiveTasksTest {

    private fun entry(
        id: Long,
        status: DownloadStatus,
        builtIn: Boolean = true,
    ) = DownloadEntry(
        id = id,
        // downloadId == id 表示内建引擎来源（见 DownloadEntry.source 的约定）
        downloadId = if (builtIn) id else id + 1000,
        fileName = "f$id",
        url = "https://e.com/f$id",
        status = status,
    )

    @Test
    fun `等待网络的任务仍需要服务`() {
        val waiting = entry(1, DownloadStatus.WAITING_NETWORK)
        val live = listOf(waiting).liveTasks()
        assertEquals("等待网络的下载必须留在通知里", listOf(waiting), live)
    }

    @Test
    fun `内建暂停任务仍需要服务以便通知栏恢复`() {
        val paused = entry(1, DownloadStatus.PAUSED)
        assertTrue(listOf(paused).liveTasks().contains(paused))
    }

    @Test
    fun `系统暂停任务不占用服务`() {
        val systemPaused = entry(1, DownloadStatus.PAUSED, builtIn = false)
        assertTrue(listOf(systemPaused).liveTasks().isEmpty())
    }

    @Test
    fun `完成与失败的任务不需要服务`() {
        val done = listOf(
            entry(1, DownloadStatus.SUCCESS),
            entry(2, DownloadStatus.FAILED),
        )
        assertTrue(done.liveTasks().isEmpty())
    }
}
