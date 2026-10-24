package com.ling.browser.data.repo

import com.ling.browser.data.db.Bookmark

/**
 * 书签备份的编解码：Netscape 书签 HTML。
 *
 * 选这个格式而不是自有 JSON，是因为它是浏览器之间搬书签的**事实标准** ——
 * Chrome / Edge / Firefox / Safari 都能直接导入，反过来也能导出成它能读的文件。
 * 用 JSON 就只能自产自销，备份的意义会缩水一半。
 *
 * 代价（必须知道，也别在界面上假装没有）：
 *  - 只保留标题、网址、**一层**文件夹与收藏时间（`ADD_DATE`，秒）；
 *  - 网站图标不在格式内，导出时丢弃 —— 导入后书签页本来就会自动补图标
 *    （见 `BookmarkRepository.urlsMissingFavicon`），不必为它塞一个非标准字段。
 *
 * 全部是纯函数：不碰 Android、不碰数据库，因此能在 JVM 单测里逐条钉住
 * （`org.json` 之类在单测里会 "not mocked" 抛异常，这也是不用它的原因之一）。
 */

/**
 * 从备份文件里读出来的一条书签。
 *
 * 刻意不复用 [Bookmark]：那个带 `id` 与 `favicon`，而导入时这两个都由本地决定
 * （id 由数据库分配、favicon 之后重新抓取），传过来只会误导调用方。
 */
data class ImportedBookmark(
    val title: String,
    val url: String,
    val folder: String?,
    val createdAt: Long,
)

/**
 * 一次导入的合并计划。
 *
 * [toAdd] 是真正要写库的；[skipped] 是被跳过的条数（库里已有同网址，或文件
 * 自身重复）。分开返回是为了能如实回报"跳过 N 条" —— 否则用户看到导入后
 * 条数对不上，会以为数据丢了。
 */
data class ImportPlan(val toAdd: List<ImportedBookmark>, val skipped: Int)

/** 备份文件的读取上限。再大就不像是书签文件了，直接放弃而不是把内存打爆。 */
const val MAX_BACKUP_BYTES = 8 * 1024 * 1024

// ------------------------------------------------------------------ 导出

/**
 * 把书签导出成 Netscape 书签 HTML。
 *
 * 未分类的书签排在根目录，其余按文件夹分组 —— 与浏览器的习惯一致，
 * 分组顺序复用 [deriveFolders]（与界面上文件夹列表同一套规则，
 * 不另算一份）。
 */
fun toNetscapeHtml(bookmarks: List<Bookmark>): String {
    val sb = StringBuilder(256 + bookmarks.size * 96)
    sb.append("<!DOCTYPE NETSCAPE-Bookmark-file-1>\n")
    // 声明编码，否则中文标题在别的浏览器里可能变成乱码
    sb.append("<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">\n")
    sb.append("<TITLE>Bookmarks</TITLE>\n")
    sb.append("<H1>Bookmarks</H1>\n")
    sb.append("<DL><p>\n")

    bookmarks.filter { it.folder.isNullOrBlank() }.forEach { sb.append(entryLine(it, 1)) }

    for (folder in deriveFolders(bookmarks)) {
        // 与 deriveFolders 同样的口径：trim 后再比对，否则「 新闻 」会分成两组
        val inFolder = bookmarks.filter { it.folder?.trim() == folder }
        if (inFolder.isEmpty()) continue
        // 文件夹时间取其中最晚的一条：它比"导出时刻"更有意义，也让同样的
        // 书签每次导出得到同样的文件（可对比、可 diff）。
        val stamp = inFolder.maxOf { it.createdAt } / 1000
        sb.append("    <DT><H3 ADD_DATE=\"").append(stamp).append("\">")
            .append(escapeHtml(folder)).append("</H3>\n")
        sb.append("    <DL><p>\n")
        inFolder.forEach { sb.append(entryLine(it, 2)) }
        sb.append("    </DL><p>\n")
    }

    sb.append("</DL><p>\n")
    return sb.toString()
}

private fun entryLine(bookmark: Bookmark, depth: Int): String =
    "    ".repeat(depth) +
        "<DT><A HREF=\"" + escapeHtml(bookmark.url) + "\"" +
        " ADD_DATE=\"" + (bookmark.createdAt / 1000) + "\">" +
        escapeHtml(bookmark.title) + "</A>\n"

// ------------------------------------------------------------------ 导入

/**
 * 解析 Netscape 书签 HTML。
 *
 * 用一个正则**按先后顺序**扫过整个文件，而不是分几趟去找：一条书签属于哪个
 * 文件夹，取决于它前面出现过哪些 `<H3>` 与 `<DL>`，分开扫就把这个顺序丢了。
 *
 * 宽容度刻意放大（都是真实文件的常见形态）：
 *  - 不要求 `<DT>` 前缀，大小写不敏感；
 *  - `href` 支持双引号、单引号、甚至不带引号；
 *  - 标签没闭合、乱序、`</DL>` 多出来 —— 一律不抛异常，能读多少算多少。
 *
 * 只支持**一层**文件夹（与数据库模型一致）：遇嵌套就把最内层的名字拿来用。
 */
fun parseNetscapeHtml(html: String): List<ImportedBookmark> {
    val out = ArrayList<ImportedBookmark>()
    // 每个 <DL> 对应的文件夹名（null = 根目录那一层）
    val stack = ArrayDeque<String?>()
    // 刚遇到的 <H3> 名字，等它后面的 <DL> 来认领
    var pending: String? = null

    // 按组序号取值，不用 m.groups["name"]：命名组访问走 Matcher.start(String)，
    // 需要 API 26，而 minSdk 是 23。组号与 TOKEN 里的命名组一一对应。
    for (m in TOKEN.findAll(html)) {
        when {
            m.groups[GROUP_FOLDER] != null ->
                pending = decodeHtml(m.groups[GROUP_FOLDER]!!.value).trim()
            // 有 attrs 组的只可能是 <A>；<DL> 那一路的命名组全是 null
            m.groups[GROUP_ATTRS] != null -> {
                val attrs = m.groups[GROUP_ATTRS]!!.value
                val url = attrOf(attrs, "href")?.let(::decodeHtml)?.trim().orEmpty()
                if (!isImportableUrl(url)) continue
                val title = decodeHtml(m.groups[GROUP_TITLE]?.value.orEmpty()).trim()
                val createdAt = attrOf(attrs, "add_date")?.toLongOrNull()?.times(1000)
                    ?: System.currentTimeMillis()
                out.add(
                    ImportedBookmark(
                        title = title.ifEmpty { url },
                        url = url,
                        folder = stack.lastOrNull { !it.isNullOrBlank() },
                        createdAt = createdAt,
                    ),
                )
            }
            m.value.startsWith("</") -> if (stack.isNotEmpty()) stack.removeLast()
            else -> {
                // 剩下的只有 <DL> 开标签
                stack.addLast(pending)
                pending = null
            }
        }
    }
    return out
}

/**
 * 这个网址能不能作为书签导入。
 *
 * `javascript:` / `vbscript:` / `data:` 一律拒绝：它们本身就是要执行的代码，
 * 混进书签列表等于在本地埋一个钓鱼/XSS 入口（点一下就跑起来）。
 * 其余协议（含 `mailto:` 之类）放行 —— 不能因为少见就悄悄丢掉用户的收藏。
 */
internal fun isImportableUrl(url: String): Boolean {
    if (url.isEmpty()) return false
    val lower = url.lowercase()
    return !(lower.startsWith("javascript:") ||
        lower.startsWith("vbscript:") ||
        lower.startsWith("data:"))
}

/**
 * 计划一次合并导入：库里已有同网址的、以及文件内部重复的，全部跳过。
 *
 * 用"跳过"而不是"覆盖/清空"：书签是用户自己攒的，任何"导入顺手删掉几条"
 * 的行为都不可原谅。返回 [ImportPlan]，把[ImportPlan.skipped]如实报给用户。
 */
fun planImport(existingUrls: Set<String>, parsed: List<ImportedBookmark>): ImportPlan {
    val seen = existingUrls.toMutableSet()
    val toAdd = ArrayList<ImportedBookmark>(parsed.size)
    var skipped = 0
    for (entry in parsed) {
        if (!seen.add(entry.url)) {
            skipped++
            continue
        }
        toAdd.add(entry)
    }
    return ImportPlan(toAdd, skipped)
}

// ------------------------------------------------------------------ 词法

/**
 * 一个正则同时认四种记号：文件夹标题、`<DL>` 进出、书签条目。
 *
 * 左到右尝试分支，`</dl>` 必须排在 `<dl` 之前（否则在 `</dl>` 的位置上
 * `<dl\b` 会先失败、但那样白跑一趟；顺序写对更省心）。
 */
private val TOKEN = Regex(
    """<h3\b[^>]*>(?<folder>.*?)</h3>""" +
        """|</dl\s*>""" +
        """|<dl\b[^>]*>""" +
        """|<a\b(?<attrs>[^>]*)>(?<title>.*?)</a>""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)

/** [TOKEN] 里命名组对应的序号，供 minSdk 23 下按序号安全取值。 */
private const val GROUP_FOLDER = 1
private const val GROUP_ATTRS = 2
private const val GROUP_TITLE = 3

/** 标签属性：值可以是双引号、单引号或不加引号。 */
private val ATTR = Regex(
    """([a-z_:][-a-z0-9_:.]*)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+))""",
    RegexOption.IGNORE_CASE,
)

internal fun attrOf(attrs: String, name: String): String? =
    ATTR.findAll(attrs)
        .firstOrNull { it.groupValues[1].equals(name, ignoreCase = true) }
        ?.let { m ->
            m.groupValues[2].ifEmpty { m.groupValues[3].ifEmpty { m.groupValues[4] } }
        }

private val ENTITY = Regex("&(amp|lt|gt|quot|apos|#\\d+|#x[0-9a-fA-F]+);")

/** 导出时转义。只动这 4 个字符，别的一律原样保留（避免把中文或 emoji 弄坏）。 */
private fun escapeHtml(raw: String): String {
    if (raw.none { it == '&' || it == '<' || it == '>' || it == '"' }) return raw
    val sb = StringBuilder(raw.length + 16)
    for (c in raw) {
        when (c) {
            '&' -> sb.append("&amp;")
            '<' -> sb.append("&lt;")
            '>' -> sb.append("&gt;")
            '"' -> sb.append("&quot;")
            else -> sb.append(c)
        }
    }
    return sb.toString()
}

/**
 * 导入时还原实体。
 *
 * 数字实体（`&#39;` / `&#x27;`）也要认：别的浏览器导出的撇号就是这种写法，
 * 只认 5 个具名实体会让标题里冒出一串 `&#39;`。
 */
internal fun decodeHtml(raw: String): String {
    if ('&' !in raw) return raw
    return ENTITY.replace(raw) { m ->
        when (val entity = m.groupValues[1]) {
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            "quot" -> "\""
            "apos" -> "'"
            else -> decodeNumericEntity(entity) ?: m.value
        }
    }
}

private fun decodeNumericEntity(entity: String): String? {
    val code = runCatching {
        if (entity.startsWith("#x", ignoreCase = true)) {
            entity.substring(2).toInt(16)
        } else {
            entity.substring(1).toInt()
        }
    }.getOrNull() ?: return null
    if (code <= 0 || code > 0x10FFFF) return null
    return String(Character.toChars(code))
}
