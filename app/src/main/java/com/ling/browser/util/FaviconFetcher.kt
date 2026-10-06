package com.ling.browser.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 抓取并压缩网站图标。
 *
 * 为什么不用 `WebView` 的 `onReceivedIcon`：书签往往是在**很久以后**
 * 才需要显示图标的，那时页面早已关闭，拿不到那个回调。也没有系统级的
 * 图标服务可用。所以只能按 URL 自己去取 `/favicon.ico`。
 *
 * 三条硬约束（都是为省流量和避免卡顿）：
 *  1. **只跟随标准位置** `/favicon.ico` 以及主页 HTML 里的 `<link rel=icon>`。
 *     不做完整 HTML 解析 —— 为了一个 16px 图标引入解析器不划算。
 *  2. **限制大小**：最多读 [MAX_BYTES]，超了直接放弃。有些站点会返回
 *     几十 MB 的图片，不拦会把内存打爆。
 *  3. **必须压缩后再存**：原始 PNG 动辄几十 KB，一张 96px 的 WebP
 *     只要几 KB。书签可能上百条，不压缩数据库会明显膨胀。
 *
 * 全程 `runCatching`：图标是**锦上添花**的东西，任何失败都只是没有图标，
 * 绝不该影响书签本身的可用性。
 */
object FaviconFetcher {

    /** 单张图标的读取上限。超过就放弃 —— 正常图标远小于这个值。 */
    private const val MAX_BYTES = 512 * 1024
    private const val CONNECT_TIMEOUT_MS = 6_000
    private const val READ_TIMEOUT_MS = 6_000

    /** 存库前缩放到的最长边。列表里最大显示约 24dp，96px 足够清晰。 */
    private const val TARGET_SIZE = 96

    /** 压缩质量。WebP 有损在这个尺寸下几乎看不出差别。 */
    private const val QUALITY = 80

    /**
     * 取回 [pageUrl] 对应站点的图标并压缩成字节。
     *
     * 返回 null 表示没取到（网络失败、站点没有图标、格式不支持、
     * 或体积超限）。调用方据此决定是否重试。
     */
    suspend fun fetch(pageUrl: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val site = originOf(pageUrl) ?: return@runCatching null
            // 先试标准位置；没有再试解析主页里的 link 标签
            val direct = download("$site/favicon.ico")
            val raw = direct ?: findIconInHtml(site)?.let { download(it) } ?: return@runCatching null
            val bitmap = decode(raw) ?: return@runCatching null
            compress(bitmap)
        }.getOrNull()
    }

    /**
     * 从 URL 取出 `scheme://host[:port]`。非 http(s) 返回 null。
     *
     * 端口处理有个容易忽略的点：`URL.getPort()` 在 URL 里**显式写了**
     * `:443` 时返回 443，而不是 -1（只有完全没写才是 -1）。所以要拿
     * `getDefaultPort()` 比一下，否则 `https://a.com:443/x` 会拼出
     * `https://a.com:443/favicon.ico` —— 功能上能用，但地址不规范，
     * 某些站点的重定向规则会因此对不上。
     */
    internal fun originOf(url: String): String? = runCatching {
        val u = URL(url)
        if (u.protocol != "http" && u.protocol != "https") return null
        if (u.host.isNullOrBlank()) return null
        val port = if (u.port == -1 || u.port == u.defaultPort) "" else ":${u.port}"
        "${u.protocol}://${u.host}$port"
    }.getOrNull()

    private fun download(url: String): ByteArray? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            // 有些站点对没有 UA 的请求直接返回 403
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) LingBrowser")
            setRequestProperty("Accept", "image/*,*/*;q=0.8")
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return@runCatching null
            // Content-Length 已经超限就根本不用读了
            if (conn.contentLengthLong > MAX_BYTES) return@runCatching null

            conn.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    total += n
                    // 边读边数：Content-Length 可能是 -1（分块传输），
                    // 只信它的话会被超大响应打爆内存
                    if (total > MAX_BYTES) return@runCatching null
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    /**
     * 从主页 HTML 里找 `<link rel="...icon..." href="...">`。
     *
     * 刻意用正则而不是 HTML 解析器：只为拿一个图标地址引入 jsoup
     * 会让 APK 变大，而"翎"的取向是极简。这个正则覆盖绝大多数写法，
     * 漏掉的站点只是没有图标，不影响功能。
     */
    private fun findIconInHtml(site: String): String? = runCatching {
        val html = downloadText(site) ?: return@runCatching null
        val re = Regex(
            """<link[^>]*\brel\s*=\s*["'][^"']*icon[^"']*["'][^>]*>""",
            RegexOption.IGNORE_CASE,
        )
        val hrefRe = Regex("""\bhref\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        re.findAll(html).firstNotNullOfOrNull { m ->
            hrefRe.find(m.value)?.groupValues?.get(1)
        }?.let { resolve(site, it.trim()) }
    }.getOrNull()

    private fun downloadText(url: String): String? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) LingBrowser")
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return@runCatching null
            // 只读开头一段：link 标签总在 <head> 里，没必要下整个页面
            conn.inputStream.use { input ->
                val buf = ByteArray(64 * 1024)
                val n = input.read(buf)
                if (n <= 0) null else String(buf, 0, n, Charsets.ISO_8859_1)
            }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    /** 把可能是相对路径的 href 拼成绝对地址。 */
    internal fun resolve(base: String, href: String): String? = when {
        href.startsWith("http://") || href.startsWith("https://") -> href
        href.startsWith("//") -> "https:$href"
        href.startsWith("/") -> "$base$href"
        href.isBlank() -> null
        else -> "$base/$href"
    }

    private fun decode(raw: ByteArray): Bitmap? = runCatching {
        // 先只读尺寸，避免为一个超大图真的分配像素
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return@runCatching null

        var sample = 1
        while (opts.outWidth / (sample * 2) >= TARGET_SIZE &&
            opts.outHeight / (sample * 2) >= TARGET_SIZE
        ) {
            sample *= 2
        }
        BitmapFactory.decodeByteArray(
            raw, 0, raw.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }.getOrNull()

    private fun compress(src: Bitmap): ByteArray? = runCatching {
        val scaled = scaleDown(src)
        val out = ByteArrayOutputStream()
        // WebP 有损在这个尺寸下与 PNG 几乎无差别，体积却小一个量级。
        // minSdk 23 起 WebP 有损编码就可用（API 14+ 支持解码）。
        @Suppress("DEPRECATION")
        scaled.compress(Bitmap.CompressFormat.WEBP, QUALITY, out)
        if (scaled !== src) scaled.recycle()
        out.toByteArray()
    }.getOrNull()?.also {
        // 源图用完即回收：这是本对象内部临时创建的，不存在共享引用
        if (!src.isRecycled) src.recycle()
    }

    private fun scaleDown(src: Bitmap): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= TARGET_SIZE) return src
        val ratio = TARGET_SIZE.toFloat() / longest
        val w = (src.width * ratio).toInt().coerceAtLeast(1)
        val h = (src.height * ratio).toInt().coerceAtLeast(1)
        return runCatching { Bitmap.createScaledBitmap(src, w, h, true) }.getOrDefault(src)
    }
}
