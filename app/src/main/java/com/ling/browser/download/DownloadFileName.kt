package com.ling.browser.download

import android.webkit.MimeTypeMap
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 下载文件名的解析与净化。
 *
 * 名字有三个来源，按可靠性从高到低取：
 *  1. `Content-Disposition` 响应头 —— 服务端最清楚自己给的是什么文件；
 *  2. URL 路径末段 —— 服务端没给头时最接近的选择；
 *  3. MIME 反推扩展名 + 时间戳 —— 前两者都拿不到有效后缀时（典型如
 *     `https://example.com/download.php?id=123`）的最后兜底。
 *
 * 无论来自哪一条，最终都要过 [sanitizeDownloadName]：这三个来源全都不可信
 * （文件名可能带路径分隔符、控制字符、FAT 不接受的字符），而它的结果会被
 * `File(dir, name)`、MediaStore 和删除逻辑直接使用。
 *
 * 这里是纯函数（MIME 查表通过参数注入），以便单测钉住边界。
 */
internal object DownloadFileName {

    /** 前缀都拿不到时使用的名字主干。 */
    const val DEFAULT_BASE = "download"

    /**
     * 解析最终文件名。
     *
     * @param contentDisposition 响应头的原值；null 表示服务端没给。
     * @param stamp              兜底命名用的时间戳（`yyyyMMdd-HHmmss`）。
     * @param lookup             MIME → 扩展名；测试可注入假实现。
     */
    fun resolve(
        url: String,
        mimeType: String?,
        contentDisposition: String?,
        stamp: String = nowStamp(),
        lookup: (String) -> String? = ::androidExtensionForMime,
    ): String {
        val ext = extensionForMime(mimeType, lookup)

        // 1) Content-Disposition：最高优先级。即使它给的名字没有后缀，也只补后缀，
        //    不加时间戳 —— 那是服务端明确给出的名字，不该被改得面目全非。
        val fromHeader = parseContentDispositionName(contentDisposition)
        if (fromHeader != null) {
            return withExtension(sanitizeDownloadName(fromHeader), ext)
        }

        // 2) URL 末段
        val fromUrl = nameFromUrl(url)
            ?: return withTimestamp(DEFAULT_BASE, stamp, ext)
        val clean = sanitizeDownloadName(fromUrl)
        if (hasUsableExtension(clean)) return clean

        // 3) 末段没有有效后缀（动态链接、纯 id 等）：保留主干加时间戳，
        //    这样既避免与已有文件混淆，也能带出真实的文件类型。
        val stem = clean.substringBeforeLast('.', clean).trimEnd(' ', '.')
        return withTimestamp(stem.ifBlank { DEFAULT_BASE }, stamp, ext)
    }

    /** 兜底命名用的时间戳。 */
    fun nowStamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    // ---------------------------------------------------------- Content-Disposition

    /**
     * 从 `Content-Disposition` 里取文件名。
     *
     * 优先 `filename*`（RFC 5987 的扩展参数，带字符集声明，能正确表达非 ASCII），
     * 其次 `filename`。解析不出来时返回 null，交给 URL 兜底 —— 绝不能因为
     * 服务端给了个畸形头就下载失败。
     */
    fun parseContentDispositionName(header: String?): String? {
        if (header.isNullOrBlank()) return null
        val extended = findParam(header, "filename*")
        val decodedExtended = extended?.let { decodeExtendedValue(it) }
        if (decodedExtended != null) return decodedExtended
        val plain = findParam(header, "filename") ?: return null
        return decodePlainValue(plain)
    }

    /** 按 RFC 5987 的 `charset'lang'percent-encoded` 解码。 */
    private fun decodeExtendedValue(value: String): String? {
        val first = value.indexOf('\'')
        if (first < 0) return null
        val second = value.indexOf('\'', first + 1)
        if (second < 0) return null
        val charsetName = value.substring(0, first).trim().ifBlank { "UTF-8" }
        val encoded = value.substring(second + 1).trim().trim('"')
        if (encoded.isEmpty()) return null
        val charset = runCatching { Charset.forName(charsetName) }.getOrDefault(Charsets.UTF_8)
        return fixMojibake(percentDecode(encoded, charset))
    }

    /**
     * 解普通 `filename=`。
     *
     * 常见两类脏数据：
     *  - 服务端把 `%E6%8A%A5` 这类 percent-encoding 塞进了不带 `*` 的参数；
     *  - 直接写 UTF-8 原始字节，HTTP 头按 ISO-8859-1 读出来就是乱码。
     * 两种都在这里兜住。
     */
    private fun decodePlainValue(value: String): String? {
        val unquoted = value.trim().removeSurrounding("\"").replace("\\\"", "\"")
        if (unquoted.isEmpty()) return null
        val decoded = if (unquoted.contains('%')) {
            runCatching { percentDecode(unquoted, Charsets.UTF_8) }.getOrDefault(unquoted)
        } else {
            unquoted
        }
        return fixMojibake(decoded)
    }

    /** 从 `Content-Disposition` 里取出某个参数的值（去掉引号）。 */
    private fun findParam(header: String, name: String): String? {
        val pattern = Regex(
            """(?:^|;)\s*${Regex.escape(name)}\s*=\s*(?:"((?:\\.|[^"\\])*)"|([^;]*))""",
            RegexOption.IGNORE_CASE,
        )
        val match = pattern.find(header) ?: return null
        val value = match.groups[1]?.value
            ?: match.groups[2]?.value?.trim()
            ?: return null
        return value.replace("\\\"", "\"").takeIf { it.isNotEmpty() }
    }

    // ---------------------------------------------------------- URL

    /** 从 URL 路径末段取文件名（已做 percent-decoding）。没有路径时返回 null。 */
    fun nameFromUrl(url: String): String? {
        val withoutQuery = url.substringBefore('#').substringBefore('?')
        val afterAuthority = withoutQuery.substringAfter("://", withoutQuery)
        val path = afterAuthority.substringAfter('/', "")
        val tail = path.trimEnd('/').substringAfterLast('/')
        if (tail.isEmpty()) return null
        val decoded = if (tail.contains('%')) {
            runCatching { percentDecode(tail, Charsets.UTF_8) }.getOrDefault(tail)
        } else {
            tail
        }
        return decoded.takeIf { it.isNotBlank() }
    }

    // ---------------------------------------------------------- 扩展名

    /**
     * 后缀是否“像真的文件后缀”。
     *
     * 只看有没有点是不够的：`download.php`、`get.ashx` 这类是动态脚本，
     * 真正的类型由响应决定。把它们判为无效，才能走到 MIME 反推那一档。
     */
    fun hasUsableExtension(name: String): Boolean {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return false
        val ext = name.substring(dot + 1)
        if (ext.length !in 1..8) return false
        if (!ext.all { it.isAsciiAlphanumeric() }) return false
        return ext.lowercase(Locale.ROOT) !in DYNAMIC_SUFFIXES
    }

    /** 由 MIME 反推扩展名；`octet-stream` 这类给不出有效类型时返回 null。 */
    fun extensionForMime(mimeType: String?, lookup: (String) -> String?): String? {
        val mime = mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
        if (mime.isNullOrEmpty()) return null
        if (mime == "application/octet-stream" || mime == "binary/octet-stream") return null
        return lookup(mime)?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotEmpty() && it.all { c -> c.isAsciiAlphanumeric() } }
    }

    /** 补扩展名；已有有效后缀时原样返回。 */
    private fun withExtension(name: String, ext: String?): String {
        if (hasUsableExtension(name)) return name
        if (ext == null) return name
        val stem = name.substringBeforeLast('.', name).trimEnd(' ', '.')
        return (stem.ifBlank { DEFAULT_BASE }) + "." + ext
    }

    /** `主干_时间戳.扩展名`。 */
    private fun withTimestamp(stem: String, stamp: String, ext: String?): String {
        val base = "${stem}_$stamp"
        return if (ext == null) base else "$base.$ext"
    }

    // ---------------------------------------------------------- 解码工具

    /** 逐字节还原 percent-encoding；非转义字符按声明字符集编码后混入。 */
    private fun percentDecode(input: String, charset: Charset): String {
        val out = ByteArrayOutputStream(input.length)
        var i = 0
        while (i < input.length) {
            val c = input[i]
            if (c == '%' && i + 2 < input.length) {
                val hex = input.substring(i + 1, i + 3).toIntOrNull(16)
                if (hex != null) {
                    out.write(hex)
                    i += 3
                    continue
                }
            }
            // 按码点处理，避免把代理对拆坏（emoji 文件名）
            val codePoint = input.codePointAt(i)
            out.write(String(Character.toChars(codePoint)).toByteArray(charset))
            i += Character.charCount(codePoint)
        }
        return out.toString(charset.name())
    }

    /**
     * 修复「UTF-8 字节被当成 ISO-8859-1」的乱码。
     *
     * 只在全部字符都落在 0x80–0xFF 时才尝试：已经正确解码的中文、emoji 等
     * 转回字节会失真，必须原样保留。
     */
    private fun fixMojibake(text: String): String {
        if (!text.any { it.code in 0x80..0xFF }) return text
        if (text.any { it.code > 0xFF }) return text
        val decoded = String(text.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
        return if (decoded.contains('\uFFFD')) text else decoded
    }

    private fun Char.isAsciiAlphanumeric(): Boolean =
        this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

    /** HTTP 层的扩展参数名用的是 `*`，正则里已经转义。 */
    private val DYNAMIC_SUFFIXES = setOf(
        "php", "php3", "php4", "php5", "phtml",
        "asp", "aspx", "ashx", "asmx",
        "jsp", "jspx", "do", "action",
        "cgi", "pl", "cfm",
        "htm", "html", "shtml", "xhtml",
    )
}

/** Android 的 MIME → 扩展名查表。 */
private fun androidExtensionForMime(mime: String): String? =
    runCatching { MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) }.getOrNull()

/**
 * 把任意来源的文件名收敛成**单一、合法、可落盘**的文件名。
 *
 * 规则：
 *  - 只保留最后一段（`/` 与 `\` 都算分隔符），杜绝路径穿越；
 *  - 剔掉控制字符与 FAT/Windows 不接受的 `:*?"<>|`；
 *  - 统一 NFC，避免「看起来一样、实际不同」的重名；
 *  - 去掉结尾的空格与点（FAT 会静默丢弃，导致名字与实际不符）；
 *  - 截断时保留扩展名，避免超长名把类型也一起砍掉。
 */
internal fun sanitizeDownloadName(name: String): String {
    val base = name.replace('\\', '/').substringAfterLast('/')
    val cleaned = buildString(base.length) {
        for (ch in base) {
            if (ch < ' ' || ch == '\u007f') continue
            if (ch in ILLEGAL_NAME_CHARS) continue
            append(ch)
        }
    }.trim()
    val normalized = Normalizer.normalize(cleaned, Normalizer.Form.NFC)
    val trimmed = normalized.trimEnd(' ', '.')
    if (trimmed.isEmpty() || trimmed.all { it == '.' }) return DownloadFileName.DEFAULT_BASE
    return truncateKeepingExtension(trimmed)
}

private fun truncateKeepingExtension(name: String): String {
    if (name.length <= MAX_FILE_NAME_LENGTH) return name
    val dot = name.lastIndexOf('.')
    if (dot > 0) {
        val ext = name.substring(dot)
        if (ext.length in 2..17) {
            val keep = (MAX_FILE_NAME_LENGTH - ext.length).coerceAtLeast(1)
            return name.substring(0, dot).take(keep).trimEnd(' ', '.') + ext
        }
    }
    return name.take(MAX_FILE_NAME_LENGTH).trimEnd(' ', '.')
}

/** 非法字符集合。`/` 与 `\` 在取末段时已处理，这里一并兜住。 */
private val ILLEGAL_NAME_CHARS = "\\/:*?\"<>|".toSet()

/** 单个文件名的长度上限。多数文件系统单段上限 255 字节，这里给 UTF-8 多字节留余量。 */
internal const val MAX_FILE_NAME_LENGTH = 120
