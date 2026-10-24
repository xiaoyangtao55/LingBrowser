package com.ling.browser.ui.screens

import androidx.compose.ui.graphics.vector.ImageVector
import com.ling.browser.data.db.DownloadEntry
import com.ling.browser.ui.theme.LingIcons
import java.util.Calendar
import java.util.Locale

/**
 * 下载列表的纯逻辑部分：**按时间分段** + **按文件类型选图标**。
 *
 * 刻意与 Compose 无关（只返回 [ImageVector] 与数据），这样能直接在 JVM 上单测，
 * 不必启动界面。时间分段依赖本地时区，[now] 参数留给测试注入固定时刻。
 */

/** 时间分段。枚举顺序就是列表展示顺序（新 → 旧）。 */
internal enum class DownloadTimeGroup(val label: String) {
    TODAY("今天"),
    YESTERDAY("昨天"),
    LAST_7_DAYS("最近 7 天"),
    LAST_30_DAYS("最近 30 天"),
    EARLIER("更早"),
}

/** 一个分段及其下的条目（内部已按时间新 → 旧排好）。 */
internal data class DownloadSection(
    val group: DownloadTimeGroup,
    val entries: List<DownloadEntry>,
)

/**
 * 把下载记录按「今天 / 昨天 / 最近 7 天 / 最近 30 天 / 更早」分组。
 *
 * 空分组直接省略；组内按 [DownloadEntry.createdAt] 降序，保证「新到旧」。
 * 边界以**本地零点**为准，而不是「当前时刻往前推 N×24 小时」——
 * 后者在跨时区/夏令时下会出现「昨天下午被算进今天」的错位。
 */
internal fun downloadSections(
    downloads: List<DownloadEntry>,
    now: Long = System.currentTimeMillis(),
): List<DownloadSection> {
    val grouped = downloads.groupBy { downloadTimeGroup(it.createdAt, now) }
    return DownloadTimeGroup.values().mapNotNull { group ->
        val entries = grouped[group] ?: return@mapNotNull null
        DownloadSection(group, entries.sortedByDescending { it.createdAt })
    }
}

/** 单条记录落在哪个时间段。 */
internal fun downloadTimeGroup(createdAt: Long, now: Long = System.currentTimeMillis()): DownloadTimeGroup {
    val today = startOfDay(now)
    val yesterday = startOfDay(shiftDays(now, -1))
    return when {
        // 时钟偏差导致的"未来"记录也归到最近一段，避免掉进"更早"的反直觉位置。
        createdAt >= today -> DownloadTimeGroup.TODAY
        createdAt >= yesterday -> DownloadTimeGroup.YESTERDAY
        createdAt >= startOfDay(shiftDays(now, -7)) -> DownloadTimeGroup.LAST_7_DAYS
        createdAt >= startOfDay(shiftDays(now, -30)) -> DownloadTimeGroup.LAST_30_DAYS
        else -> DownloadTimeGroup.EARLIER
    }
}

/**
 * 文件类型 → 列表前置图标。
 *
 * 先看扩展名（服务器给的 MIME 经常是 `application/octet-stream`，不可靠），
 * 没有扩展名或扩展名不认识时再看 MIME。两者都判断不出就返回兜底图标 ——
 * 宁可显示"未知文件"，也不要把安装包画成图片。
 */
internal fun fileIconFor(fileName: String, mimeType: String? = null): ImageVector {
    val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return when (ext) {
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "heic", "heif", "avif", "ico" ->
            LingIcons.FileImage
        "mp4", "mkv", "avi", "mov", "webm", "m4v", "flv", "3gp", "ts" ->
            LingIcons.FileVideo
        "mp3", "flac", "wav", "m4a", "aac", "ogg", "opus", "wma", "amr", "mid" ->
            LingIcons.FileAudio
        "zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "zst", "lz4", "cab", "iso" ->
            LingIcons.FileArchive
        "apk", "xapk", "apks", "aab" -> LingIcons.FileApk
        "pdf" -> LingIcons.FilePdf
        "doc", "docx", "rtf", "odt", "pages" -> LingIcons.FileDoc
        "xls", "xlsx", "csv", "tsv", "ods", "numbers" -> LingIcons.FileSheet
        "ppt", "pptx", "odp", "key" -> LingIcons.FileSlide
        "txt", "md", "markdown", "log", "ini", "cfg", "conf", "yaml", "yml", "toml" ->
            LingIcons.Article
        "js", "mjs", "cjs", "ts", "jsx", "tsx", "json", "xml", "html", "htm", "css", "scss",
        "less", "py", "java", "kt", "kts", "c", "h", "cpp", "hpp", "cs", "go", "rs", "rb",
        "php", "sh", "bash", "swift", "lua", "sql", "vue", "svelte" -> LingIcons.FileCode
        else -> fileIconForMime(mimeType)
    }
}

private fun fileIconForMime(mimeType: String?): ImageVector {
    val type = mimeType?.lowercase(Locale.ROOT).orEmpty()
    return when {
        type.startsWith("image/") -> LingIcons.FileImage
        type.startsWith("video/") -> LingIcons.FileVideo
        type.startsWith("audio/") -> LingIcons.FileAudio
        type.startsWith("text/") -> LingIcons.Article
        type == "application/pdf" -> LingIcons.FilePdf
        type.contains("android.package") -> LingIcons.FileApk
        type.contains("zip") || type.contains("compressed") || type.contains("x-rar") ||
            type.contains("7z") -> LingIcons.FileArchive
        type.contains("word") -> LingIcons.FileDoc
        type.contains("sheet") || type.contains("excel") -> LingIcons.FileSheet
        type.contains("presentation") || type.contains("powerpoint") -> LingIcons.FileSlide
        type.startsWith("application/json") || type.startsWith("application/xml") -> LingIcons.FileCode
        else -> LingIcons.FileUnknown
    }
}

/** 本地时区的当天零点。 */
private fun startOfDay(millis: Long): Long = Calendar.getInstance().apply {
    timeInMillis = millis
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

/** 往前/往后挪 N 天；用日历加天数而不是减固定毫秒数，跨夏令时才不会错一小时。 */
private fun shiftDays(millis: Long, days: Int): Long = Calendar.getInstance().apply {
    timeInMillis = millis
    add(Calendar.DAY_OF_YEAR, days)
}.timeInMillis
