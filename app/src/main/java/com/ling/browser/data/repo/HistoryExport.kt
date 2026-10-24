package com.ling.browser.data.repo

import com.ling.browser.data.db.HistoryEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把历史记录导出成 HTML，以及把同一格式读回来。
 *
 * 用 HTML 而不是 CSV：标题里常有逗号、引号、换行，CSV 需要一整套转义规则，
 * 而 HTML 天然能容纳任意文本，且导出后可以直接在浏览器里点开条目。
 *
 * 读写共用同一个表结构（时间 / 标题链接 / 次数），所以导出文件可以再导入回来 ——
 * 备份格式如果只能导出不能导入，就只是"看一眼的报表"，不是备份。
 */

/** 从历史备份文件里读出来的一条记录。 */
data class ImportedHistory(
    val title: String,
    val url: String,
    val visitedAt: Long,
    val visitCount: Int,
)

/**
 * 一次历史导入的合并计划。
 *
 * [toAdd] 是真正要写库的；[skipped] 是库里已有同网址、或文件自身重复而跳过的条数。
 * 分开返回才能如实回报"跳过 N 条"，否则用户看到导入后条数对不上会以为丢了数据。
 */
data class HistoryImportPlan(val toAdd: List<ImportedHistory>, val skipped: Int)

// ------------------------------------------------------------------ 导出

fun toHistoryHtml(entries: List<HistoryEntry>): String {
    val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    val rows = entries.joinToString("\n") { entry ->
        "      <tr>" +
            "<td>${format.format(Date(entry.visitedAt))}</td>" +
            "<td><a href=\"${escapeHtml(entry.url)}\">${escapeHtml(entry.title)}</a></td>" +
            "<td>${entry.visitCount}</td>" +
            "</tr>"
    }
    return """
        |<!DOCTYPE html>
        |<html lang="zh-CN">
        |<head>
        |  <meta charset="utf-8">
        |  <title>浏览历史</title>
        |  <style>
        |    body { font-family: sans-serif; margin: 24px; }
        |    table { border-collapse: collapse; width: 100%; }
        |    th, td { border: 1px solid #ddd; padding: 6px 10px; text-align: left; }
        |    th { background: #f5f5f5; }
        |  </style>
        |</head>
        |<body>
        |  <h1>浏览历史</h1>
        |  <p>共 ${entries.size} 条</p>
        |  <table>
        |    <thead>
        |      <tr><th>访问时间</th><th>标题 / 网址</th><th>次数</th></tr>
        |    </thead>
        |    <tbody>
        |$rows
        |    </tbody>
        |  </table>
        |</body>
        |</html>
        |""".trimMargin()
}

// ------------------------------------------------------------------ 导入

/**
 * 解析本应用导出的历史 HTML。
 *
 * 刻意只认「三列 + 一条链接」的行：
 *  - 不认 `<a>` 单独出现（普通网页里到处是链接），避免用户误选一个网页文件后
 *    把整页链接当成浏览历史导进来；
 *  - 时间列解析失败时用导入时刻兜底 —— 用户手工改过时间格式不该导致整条消失；
 *  - 次数列不是整数的行直接跳过，宁可少导一条，也不把乱七八糟的表格当成历史。
 *
 * 宽容度与书签导入一致：大小写不敏感、属性可带单双引号、空白随意。
 * 同一网址在文件内出现多次时只保留第一条（与库里的唯一索引行为一致）。
 */
fun parseHistoryHtml(html: String): List<ImportedHistory> {
    val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).apply {
        isLenient = false
    }
    val out = ArrayList<ImportedHistory>()
    val seen = HashSet<String>()

    for (row in ROW.findAll(html)) {
        val cells = CELL.findAll(row.groupValues[GROUP_ROW])
            .map { it.groupValues[1] }
            .toList()
        // 时间 + 标题链接 + 次数，至少三列才像我们的格式
        if (cells.size < 3) continue
        val count = decodeHtml(cells.last()).trim().toIntOrNull()?.coerceAtLeast(1) ?: continue
        val link = LINK.find(row.groupValues[GROUP_ROW]) ?: continue
        val url = attrOf(link.groupValues[GROUP_ATTRS], "href")
            ?.let(::decodeHtml)
            ?.trim()
            .orEmpty()
        if (!isImportableUrl(url)) continue
        if (!seen.add(url)) continue

        val title = decodeHtml(link.groupValues[GROUP_TITLE]).trim().ifEmpty { url }
        val visitedAt = runCatching {
            format.parse(decodeHtml(cells.first()).trim())?.time
        }.getOrNull() ?: System.currentTimeMillis()
        out.add(ImportedHistory(title, url, visitedAt, count))
    }
    return out
}

/**
 * 计划一次合并导入：库里已有同网址的、以及文件内部重复的，全部跳过。
 *
 * 与书签导入同一原则：只新增、不覆盖。历史记录不像书签那样"丢一条就心疼"，
 * 但"导入顺手删掉用户原有记录"同样不可接受。
 */
fun planHistoryImport(
    existingUrls: Set<String>,
    parsed: List<ImportedHistory>,
): HistoryImportPlan {
    val seen = existingUrls.toMutableSet()
    val toAdd = ArrayList<ImportedHistory>(parsed.size)
    var skipped = 0
    for (entry in parsed) {
        if (!seen.add(entry.url)) {
            skipped++
            continue
        }
        toAdd.add(entry)
    }
    return HistoryImportPlan(toAdd, skipped)
}

// ------------------------------------------------------------------ 词法

private val ROW = Regex(
    """<tr\b[^>]*>(.*?)</tr>""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
private val CELL = Regex(
    """<td\b[^>]*>(.*?)</td>""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
private val LINK = Regex(
    """<a\b([^>]*)>(.*?)</a>""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)

/** 命名组在 minSdk 23 下不能按名字取，统一用序号。 */
private const val GROUP_ROW = 1
private const val GROUP_ATTRS = 1
private const val GROUP_TITLE = 2

/** HTML 文本转义；不转义时标题里的 `&`、`<` 会把页面结构冲坏。 */
private fun escapeHtml(text: String): String = buildString(text.length) {
    for (ch in text) {
        when (ch) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(ch)
        }
    }
}
