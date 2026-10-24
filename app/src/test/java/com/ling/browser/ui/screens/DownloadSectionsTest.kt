package com.ling.browser.ui.screens

import com.ling.browser.data.db.DownloadEntry
import com.ling.browser.ui.theme.LingIcons
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 下载列表纯逻辑的单元测试：时间分段与文件类型图标。
 *
 * 分段边界以**本地零点**为准，测试里用 [Calendar] 构造固定时刻，避免依赖
 * 跑测试的机器的当前时间。
 */
class DownloadSectionsTest {

    private fun local(year: Int, month: Int, day: Int, hour: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month, day, hour, 0, 0)
        }.timeInMillis

    private fun entry(id: Long, name: String, createdAt: Long, mime: String? = null) =
        DownloadEntry(
            id = id,
            downloadId = id,
            fileName = name,
            url = "https://example.com/$name",
            mimeType = mime,
            createdAt = createdAt,
        )

    @Test
    fun `今天昨天与更早按本地零点划分`() {
        val now = local(2026, Calendar.MARCH, 15, 12)
        assertEquals(
            DownloadTimeGroup.TODAY,
            downloadTimeGroup(local(2026, Calendar.MARCH, 15, 0), now),
        )
        assertEquals(
            DownloadTimeGroup.YESTERDAY,
            downloadTimeGroup(local(2026, Calendar.MARCH, 14, 23), now),
        )
        assertEquals(
            DownloadTimeGroup.LAST_7_DAYS,
            downloadTimeGroup(local(2026, Calendar.MARCH, 10, 9), now),
        )
        assertEquals(
            DownloadTimeGroup.LAST_30_DAYS,
            downloadTimeGroup(local(2026, Calendar.FEBRUARY, 20, 9), now),
        )
        assertEquals(
            DownloadTimeGroup.EARLIER,
            downloadTimeGroup(local(2026, Calendar.JANUARY, 1, 9), now),
        )
    }

    @Test
    fun `分段按新到旧排列且组内降序`() {
        val now = local(2026, Calendar.MARCH, 15, 12)
        // 故意把输入顺序打乱：分段函数必须自己排好。
        val downloads = listOf(
            entry(1, "old.zip", local(2026, Calendar.JANUARY, 1, 9)),
            entry(2, "today-early.apk", local(2026, Calendar.MARCH, 15, 1)),
            entry(3, "today-late.apk", local(2026, Calendar.MARCH, 15, 11)),
            entry(4, "yesterday.apk", local(2026, Calendar.MARCH, 14, 20)),
        )

        val sections = downloadSections(downloads, now)

        assertEquals(
            listOf(DownloadTimeGroup.TODAY, DownloadTimeGroup.YESTERDAY, DownloadTimeGroup.EARLIER),
            sections.map { it.group },
        )
        assertEquals(
            listOf("today-late.apk", "today-early.apk"),
            sections.first().entries.map { it.fileName },
        )
    }

    @Test
    fun `常用扩展名映射到对应图标`() {
        assertSame(LingIcons.FileImage, fileIconFor("photo.JPG"))
        assertSame(LingIcons.FileVideo, fileIconFor("clip.mp4"))
        assertSame(LingIcons.FileAudio, fileIconFor("song.flac"))
        assertSame(LingIcons.FileArchive, fileIconFor("pkg.tar.gz"))
        assertSame(LingIcons.FileApk, fileIconFor("app.apk"))
        assertSame(LingIcons.FilePdf, fileIconFor("manual.pdf"))
        assertSame(LingIcons.FileDoc, fileIconFor("report.docx"))
        assertSame(LingIcons.FileSheet, fileIconFor("data.xlsx"))
        assertSame(LingIcons.FileSlide, fileIconFor("deck.pptx"))
        assertSame(LingIcons.FileCode, fileIconFor("main.kt"))
        assertSame(LingIcons.Article, fileIconFor("notes.md"))
    }

    @Test
    fun `扩展名不认识时退回 MIME 再退回兜底`() {
        // 服务器经常给 application/octet-stream，此时 MIME 帮不上忙。
        assertSame(LingIcons.FileImage, fileIconFor("blob", "image/png"))
        assertSame(LingIcons.FilePdf, fileIconFor("blob", "application/pdf"))
        assertSame(
            LingIcons.FileArchive,
            fileIconFor("blob", "application/x-compressed"),
        )
        assertSame(
            LingIcons.FileUnknown,
            fileIconFor("blob", "application/octet-stream"),
        )
        assertSame(LingIcons.FileUnknown, fileIconFor("noextension"))
    }
}
