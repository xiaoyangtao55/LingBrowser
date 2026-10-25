package com.ling.browser.web

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset

/**
 * 一次 `GM_xmlhttpRequest` 的原生执行。
 *
 * 为什么不复用 WebView 的请求栈：`GM_xmlhttpRequest` 的语义就是**绕过页面
 * 同源限制**（脚本可以请求任意域），而 WebView 的 `fetch` 会受 CORS 约束。
 * 因此这里用 [HttpURLConnection] 直接发，Cookie 从 [android.webkit.CookieManager]
 * 取（与页面共享登录态），与 Tampermonkey 的行为一致。
 *
 * 实例同时充当"取消句柄"：[abort] 断开底层连接，让阻塞在读写上的调用立刻
 * 抛 [IOException]。
 */
internal class ScriptHttpRequest(
    val url: String,
    val method: String,
    val headers: Map<String, String>,
    val body: ByteArray?,
    val timeoutMs: Int,
    val cookie: String?,
    /** 读取响应体时的进度回调（`received` / `total`，total 未知时为 -1）。 */
    private val onProgress: ((received: Long, total: Long) -> Unit)? = null,
) {
    @Volatile
    private var connection: HttpURLConnection? = null

    /** 中止请求。可以重复调用。 */
    fun abort() {
        runCatching { connection?.disconnect() }
    }

    fun execute(): ScriptHttpResponse {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = true
            // 用户脚本通常自己带缓存/去重逻辑，这里不做二次缓存。
            useCaches = false
            setRequestProperty("User-Agent", USER_AGENT)
        }
        connection = conn
        try {
            for ((k, v) in headers) {
                // 受限头（Host/Content-Length 等）由 HttpURLConnection 自己管，
                // 设置会抛 IllegalArgumentException；单个头失败不该让整个请求失败。
                runCatching { conn.setRequestProperty(k, v) }
            }
            if (headers.keys.none { it.equals("Cookie", ignoreCase = true) }) {
                cookie?.takeIf { it.isNotBlank() }?.let { conn.setRequestProperty("Cookie", it) }
            }
            if (body != null && method != "GET" && method != "HEAD") {
                conn.doOutput = true
                conn.outputStream.use { it.write(body) }
            }

            val status = conn.responseCode
            val stream = if (status >= 400) conn.errorStream else conn.inputStream
            // contentLengthLong 是 API 24+；脚本响应另有体积上限，
            // 用 int 版（API 1）换算即可，也省去版本分支。
            val total = conn.contentLength.toLong()
            val bytes = stream?.let { input ->
                input.use { readLimited(it, total) }
            } ?: ByteArray(0)

            val contentType = conn.contentType.orEmpty()
            return ScriptHttpResponse(
                status = status,
                statusText = conn.responseMessage.orEmpty(),
                finalUrl = conn.url.toString(),
                headers = formatHeaders(conn),
                body = decode(bytes, charsetOf(contentType)),
                raw = bytes,
                setCookies = conn.headerFields.entries
                    .firstOrNull { it.key.equals("Set-Cookie", true) }
                    ?.value.orEmpty(),
            )
        } finally {
            connection = null
            runCatching { conn.disconnect() }
        }
    }

    private fun readLimited(input: InputStream, total: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (out.size() + n > MAX_RESPONSE_BYTES) {
                throw ScriptHttpTooLargeException(MAX_RESPONSE_BYTES)
            }
            out.write(buffer, 0, n)
            // 真实字节进度：脚本常拿它画下载进度条，报"总大小未知"比不报更好。
            onProgress?.invoke(out.size().toLong(), total)
        }
        return out.toByteArray()
    }

    private fun formatHeaders(conn: HttpURLConnection): String = buildString {
        for ((name, values) in conn.headerFields) {
            if (name == null) continue
            for (value in values) {
                append(name).append(": ").append(value).append("\r\n")
            }
        }
    }

    private fun charsetOf(contentType: String): Charset {
        val name = CHARSET.find(contentType)?.groupValues?.get(1) ?: return Charsets.UTF_8
        return runCatching { Charset.forName(name) }.getOrDefault(Charsets.UTF_8)
    }

    private fun decode(bytes: ByteArray, charset: Charset): String =
        runCatching { String(bytes, charset) }.getOrDefault(String(bytes, Charsets.UTF_8))

    private companion object {
        /** 单个响应的上限。脚本多半解析 JSON / HTML，2 MiB 已经足够宽。 */
        const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024

        val CHARSET = Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE)

        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
    }
}

internal data class ScriptHttpResponse(
    val status: Int,
    val statusText: String,
    val finalUrl: String,
    val headers: String,
    val body: String,
    /** 原始响应字节，`arraybuffer` / `blob` 响应类型要按 base64 交回页面。 */
    val raw: ByteArray,
    val setCookies: List<String>,
)

/** 响应体超过 [ScriptHttpRequest] 的上限。 */
internal class ScriptHttpTooLargeException(limit: Int) :
    IOException("响应超过 $limit 字节")
