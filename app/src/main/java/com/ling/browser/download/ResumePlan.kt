package com.ling.browser.download

/**
 * 断点续传决策：根据本地已下载字节数与服务器响应，算出本次写入的起点。
 *
 * 刻意抽成**纯函数**（不含任何 Android 依赖）以便单测：续传的边界条件
 * （服务器不支持 Range、文件其实已下完、Content-Length 缺失等）在真机上
 * 很难稳定复现，但它们恰恰是最容易出错、也最该被钉死的地方。
 *
 * 调用约定：只有当本地已有字节（`existing > 0`）时才会带上 `Range` 请求头，
 * 因此这里把「服务器返回 206」视为「接受续传」，把「返回 200」视为
 * 「忽略 Range / 文件已变」——后者必须从头重写，否则会与旧字节拼出脏文件。
 */
internal data class ResumePlan(
    /** true = 从 [start] 处追加写入；false = 从头重写（截断本地已有部分）。 */
    val append: Boolean,
    /** 本次写入的起始偏移。 */
    val start: Long,
    /** 该文件的最终总字节数；无法确定时为 0。 */
    val total: Long,
    /** 本地已有字节是否已覆盖完整文件（无需再下载）。 */
    val alreadyComplete: Boolean,
)

/**
 * 计算续传方案。
 *
 * @param existing      本地目标上已有的真实字节数（`Target.length()`）。
 * @param responseCode  HTTP 状态码。
 * @param contentLength 响应体声明的长度；未知时为负数。
 */
internal fun planResume(
    existing: Long,
    responseCode: Int,
    contentLength: Long,
): ResumePlan {
    // 416 Range Not Satisfiable：请求的区间超出了资源范围。最常见的成因是
    // 本地字节已经覆盖（甚至超过）整个文件 —— 上一轮其实已下完，直接判完成，
    // 避免再发起一次注定失败的下载。
    if (responseCode == HTTP_RANGE_NOT_SATISFIABLE && existing > 0) {
        return ResumePlan(append = false, start = 0, total = existing, alreadyComplete = true)
    }

    // 206 Partial Content：服务器接受 Range，从 existing 处续写。
    if (responseCode == HTTP_PARTIAL_CONTENT && existing > 0) {
        val total = if (contentLength > 0) existing + contentLength else 0L
        return ResumePlan(append = true, start = existing, total = total, alreadyComplete = false)
    }

    // 其余情况（200、或不带 Range 的普通请求）：从头重写。
    // 200 出现在续传场景时说明服务器忽略了 Range，此时若仍追加会和已有
    // 字节拼成"前半段旧 + 后半段新"的脏文件。
    val total = if (contentLength > 0) contentLength else 0L
    return ResumePlan(append = false, start = 0, total = total, alreadyComplete = false)
}

private const val HTTP_PARTIAL_CONTENT = 206
private const val HTTP_RANGE_NOT_SATISFIABLE = 416

/** `Content-Range: bytes 1000-1999/10000`；总长也可能是 `*`（未知）。 */
private val CONTENT_RANGE_PATTERN =
    Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""", RegexOption.IGNORE_CASE)

/**
 * 取 `Content-Range` 的区间起点。
 *
 * 分块下载必须校验它：服务器如果无视我们请求的 Range 从 0 开始给数据，
 * 直接往块的偏移写就会把别的块覆盖掉。格式不认识时返回 null（不做强校验）。
 */
internal fun parseContentRangeStart(header: String?): Long? =
    header?.let { CONTENT_RANGE_PATTERN.find(it)?.groupValues?.get(1)?.toLongOrNull() }

/** 取 `Content-Range` 的文件总大小；`*` 或格式不认识时返回 null。 */
internal fun parseContentRangeTotal(header: String?): Long? =
    header?.let { CONTENT_RANGE_PATTERN.find(it)?.groupValues?.get(3)?.toLongOrNull() }
