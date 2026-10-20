package com.ling.browser.data.repo

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.ling.browser.web.BlockRules
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 一条订阅（或一次本地导入）名单。
 *
 * [count] 是**归一化去重后**的域名条数 —— 一份 20 万行的 Hosts 文件解析出来
 * 可能只有几千个唯一域名，用户看到"导入 20 万行、生效 3000 条"会以为坏了，
 * 所以这里存的是真正生效的条数，[lines] / [skipped] 另行展示。
 */
data class BlockSubscription(
    /** 稳定主键：网址订阅按网址哈希（同一网址重复导入 = 更新，不重复建条目）。 */
    val id: String,
    val name: String,
    /**
     * 订阅网址。本地导入的名单为空串 —— 我们**不**持久化 SAF 的 content://
     * 地址：那条地址的读权限只在本次会话内有效，留着它只会在下次"更新"时
     * 抛一个权限异常。本地名单想更新就重新选一次文件。
     */
    val url: String,
    val enabled: Boolean,
    /** 最近一次导入/更新的时间（epoch millis）。 */
    val updatedAt: Long,
    /** 生效域名条数（去重后）。 */
    val count: Int,
    /** 名单总行数（含注释、空行、被跳过的规则）。 */
    val lines: Int,
    /** 被静默跳过的行数（元素规则、例外、路径规则、脏数据）。 */
    val skipped: Int,
    /** 是否因超出条数上限被截断。 */
    val truncated: Boolean,
) {
    /** 能不能在线更新：只有网址订阅可以，本地导入的不行（见 [url] 说明）。 */
    val canRefresh: Boolean
        get() = url.startsWith("http://", ignoreCase = true) ||
            url.startsWith("https://", ignoreCase = true)
}

/** 一次导入/更新的结果。失败原因直接给人类可读的短句，供 UI 直接展示。 */
sealed interface ImportResult {
    data class Ok(val subscription: BlockSubscription) : ImportResult
    data class Failed(val reason: String) : ImportResult
}

/**
 * 广告拦截订阅名单的存储层。
 *
 * ## 为什么要有它
 *
 * 域名表从"APK 内置"升级成"可订阅"之后，内置表只承担"开箱能用"，
 * 真正的大名单（十几万条）由用户从网址或本地文件导入。那些名单太大，
 * 塞不进 DataStore（Preferences 只该存小标量），因此落到应用私有目录：
 *
 * ```
 * files/adblock/<id>.meta      # 元信息（名称、网址、启停、条数…）
 * files/adblock/<id>.domains   # 域名列表，一行一个
 * ```
 *
 * 一个订阅拆成两个文件而不是合一：元信息要频繁改（启停、更新条数），
 * 域名列表基本只在导入时写一次 —— 合并的话每次点开关都要重写十几万行。
 *
 * ## 内存里的两份缓存
 *
 * - [domainCache]：id -> 域名集合。导入时顺手存下，之后不再重复解析文件；
 * - [_enabledDomains]：所有**启用中**订阅的并集，就是交给
 *   [com.ling.browser.web.AdBlocker] 查表的那一份。
 *
 * 拦截发生在 `shouldInterceptRequest`（每个子资源都跑一次）的热路径上，
 * 那里绝不能现场合并集合 —— 十几万条每次请求并一遍，代价无法接受。
 * 所以这里在**改动时**算好并集，热路径只做集合查询。
 *
 * ## 文件与网络 IO 一律在 IO 调度
 *
 * 导入要读网络、写十几万行；启动时要读回全部名单。这些都在 [Dispatchers.IO]
 * 上执行，绝不在主线程碰磁盘。所有写操作串在 [lock] 上：用户连点两次"导入"
 * 不会让两份名单互相踩写。
 */
class BlockListRepository(
    context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val appContext = context.applicationContext

    /** 仓库自在的协程作用域：初始加载不需要任何调用方记得去触发。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 写操作串行化，避免并发导入互相踩写同一批文件。 */
    private val lock = Mutex()

    private val dir: File
        get() = File(appContext.filesDir, DIR)

    /** 内存里的权威副本：id -> 订阅。 */
    private val entries = LinkedHashMap<String, BlockSubscription>()

    /** id -> 已解析的域名集合（只在本类内部使用）。 */
    private val domainCache = HashMap<String, Set<String>>()

    private val _subscriptions = MutableStateFlow<List<BlockSubscription>>(emptyList())

    /** 全部订阅，按名称排序（顺序稳定，界面不会因文件枚举顺序而跳动）。 */
    val subscriptions: StateFlow<List<BlockSubscription>> = _subscriptions.asStateFlow()

    private val _enabledDomains = MutableStateFlow<Set<String>>(emptySet())

    /**
     * 所有启用中订阅的域名并集。
     *
     * 这是交给 [com.ling.browser.web.AdBlocker] 与内置表、自定义规则一起查表的那一份。
     * 只在导入/启停/删除时重算，热路径直接读它。
     */
    val enabledDomains: StateFlow<Set<String>> = _enabledDomains.asStateFlow()

    init {
        // 启动即读回磁盘上已有的名单。异步进行，不阻塞 Application 初始化。
        scope.launch { lock.withLock { loadFromDisk() } }
    }

    // ---------------------------------------------------------------- 导入 / 更新

    /**
     * 从网址导入（或更新）一份名单。
     *
     * 同一个网址重复导入会**命中同一条**（id 由网址哈希得出），因此用户
     * 拉一次新版本不会在列表里堆出两条同名订阅。
     */
    suspend fun importFromUrl(rawUrl: String): ImportResult = withContext(Dispatchers.IO) {
        lock.withLock {
            val url = rawUrl.trim()
            if (!url.startsWith("http://", ignoreCase = true) &&
                !url.startsWith("https://", ignoreCase = true)
            ) {
                return@withLock ImportResult.Failed("只支持 http/https 地址")
            }
            val text = try {
                fetch(url)
            } catch (e: RuleTooLarge) {
                return@withLock ImportResult.Failed("名单超过 8 MiB，已放弃导入")
            } catch (e: Exception) {
                return@withLock ImportResult.Failed(describe(e))
            }
            persistParsed(id = urlSourceId(url), name = nameForUrl(url), url = url, text = text)
        }
    }

    /**
     * 从本地文件导入。
     *
     * 每次都是一条**新**订阅（id 随机）：本地文件没有稳定标识，按内容或
     * 文件名去重都不可靠，硬做去重反而会把用户两次导入的不同版本合成一条。
     */
    suspend fun importFromLocal(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        lock.withLock {
            val text = try {
                // 走 decode 而不是裸 readCapped：本地文件同样可能是 gzip 的，
                // 顺带把字节解成文本，与网址订阅共用同一条解码路径。
                decode(inputStreamOf(uri))
            } catch (e: RuleTooLarge) {
                return@withLock ImportResult.Failed("文件超过 8 MiB，已放弃导入")
            } catch (e: Exception) {
                return@withLock ImportResult.Failed(describe(e))
            }
            persistParsed(
                id = localSourceId(),
                name = displayNameOf(uri) ?: "本地文件",
                url = "",
                text = text,
            )
        }
    }

    /** 重新拉取网址订阅。本地导入的名单不支持在线更新（见 [BlockSubscription.url]）。 */
    suspend fun refresh(id: String): ImportResult = withContext(Dispatchers.IO) {
        lock.withLock {
            val sub = entries[id] ?: return@withLock ImportResult.Failed("订阅不存在")
            if (!sub.canRefresh) return@withLock ImportResult.Failed("本地导入的名单无法在线更新")
            val text = try {
                fetch(sub.url)
            } catch (e: RuleTooLarge) {
                return@withLock ImportResult.Failed("名单超过 8 MiB，已放弃更新")
            } catch (e: Exception) {
                return@withLock ImportResult.Failed(describe(e))
            }
            val parsed = BlockRules.parse(text)
            if (parsed.domains.isEmpty()) {
                return@withLock ImportResult.Failed("没有解析出任何域名")
            }
            val updated = writeEntry(
                id = id,
                name = sub.name,
                url = sub.url,
                parsed = parsed,
                enabled = sub.enabled,
            )
            publish()
            ImportResult.Ok(updated)
        }
    }

    // ---------------------------------------------------------------- 启停 / 删除

    suspend fun setEnabled(id: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        lock.withLock {
            val current = entries[id]
            if (current == null || current.enabled == enabled) return@withLock
            val next = current.copy(enabled = enabled)
            entries[id] = next
            // 只改元信息那一行：域名列表不动（见类注释里"为什么拆两个文件"）。
            writeMeta(next)
            publish()
        }
    }

    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            if (entries.remove(id) == null) return@withLock
            domainCache.remove(id)
            File(dir, id + DOMAINS_SUFFIX).delete()
            File(dir, id + META_SUFFIX).delete()
            publish()
        }
    }

    // ---------------------------------------------------------------- 内部：解析落盘

    /**
     * 解析文本 -> 落盘 -> 更新内存。
     *
     * 解析出 0 条一律视为失败：那基本意味着用户选错了文件（选成图片、
     * 选成 HTML 页面），而不是"这份名单刚好是空的"。如实报错比留一条
     * "0 条域名的订阅"更可控。
     */
    private fun persistParsed(
        id: String,
        name: String,
        url: String,
        text: String,
    ): ImportResult {
        val parsed = BlockRules.parse(text)
        if (parsed.domains.isEmpty()) return ImportResult.Failed("没有解析出任何域名")
        // 已有同 id 的订阅保留它的启停状态：更新名单不该顺手把用户关掉的订阅打开。
        val enabled = entries[id]?.enabled ?: true
        val sub = writeEntry(id, name, url, parsed, enabled)
        publish()
        return ImportResult.Ok(sub)
    }

    private fun writeEntry(
        id: String,
        name: String,
        url: String,
        parsed: BlockRules.ParseResult,
        enabled: Boolean,
    ): BlockSubscription {
        val sub = BlockSubscription(
            id = id,
            name = name,
            url = url,
            enabled = enabled,
            updatedAt = clock(),
            count = parsed.domains.size,
            lines = parsed.lines,
            skipped = parsed.skipped,
            truncated = parsed.truncated,
        )
        dir.mkdirs()
        // 域名一行一个、按字典序写：同样的名单重复导入得到同样的文件，便于排查。
        File(dir, id + DOMAINS_SUFFIX)
            .writeText(parsed.domains.sorted().joinToString("\n"), Charsets.UTF_8)
        writeMeta(sub)
        domainCache[id] = parsed.domains
        entries[id] = sub
        return sub
    }

    private fun writeMeta(sub: BlockSubscription) {
        File(dir, sub.id + META_SUFFIX).writeText(encodeMeta(sub), Charsets.UTF_8)
    }

    // ---------------------------------------------------------------- 内部：读回磁盘

    private fun loadFromDisk() {
        val d = dir
        if (!d.isDirectory) {
            publish()
            return
        }
        val metas = d.listFiles { f -> f.isFile && f.name.endsWith(META_SUFFIX) } ?: return
        entries.clear()
        domainCache.clear()
        for (metaFile in metas) {
            val id = metaFile.name.removeSuffix(META_SUFFIX)
            val sub = decodeMeta(id, metaFile.readText()) ?: continue
            val domainsFile = File(d, id + DOMAINS_SUFFIX)
            if (!domainsFile.isFile) continue
            domainCache[id] = readDomains(domainsFile)
            entries[id] = sub
        }
        publish()
    }

    private fun readDomains(file: File): Set<String> = try {
        file.readLines().filterTo(HashSet()) { it.isNotBlank() }
    } catch (_: Exception) {
        emptySet()
    }

    /** 把内存副本发布到两个 StateFlow：订阅列表 + 启用域名并集。 */
    private fun publish() {
        _subscriptions.value = entries.values.sortedBy { it.name.lowercase() }
        val union = HashSet<String>()
        for (sub in entries.values) {
            if (!sub.enabled) continue
            domainCache[sub.id]?.let { union.addAll(it) }
        }
        _enabledDomains.value = union
    }

    // ---------------------------------------------------------------- 内部：网络

    /**
     * 拉取名单位文本。
     *
     * 只发 GET，不设 `Accept-Encoding` —— 默认就不会协商压缩，服务器回的内容
     * 是明文。但仍有 CDN 会**无条件**发 gzip（见 [decode] 的魔数兜底）。
     */
    private fun fetch(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            // 有些站点对陌生 UA 直接 403
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            return decode(conn.inputStream)
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /**
     * 字节 -> 文本，顺带兜住 gzip。
     *
     * 判据用**魔数**（`1f 8b`）而不是 `Content-Encoding` 头：后者可能因为
     * 多层代理/重定向而丢失或与实际内容不符，而魔数不会骗人。
     */
    private fun decode(stream: InputStream): String {
        val bytes = readCapped(stream)
        val plain = if (
            bytes.size >= 2 &&
            bytes[0] == GZIP_MAGIC_0 &&
            bytes[1] == GZIP_MAGIC_1
        ) {
            readCapped(GZIPInputStream(ByteArrayInputStream(bytes)))
        } else {
            bytes
        }
        // 域名名单是纯 ASCII，但注释里可能有中文 —— 按 UTF-8 解，坏字节会被替换符吞掉。
        return String(plain, Charsets.UTF_8)
    }

    private fun inputStreamOf(uri: Uri): InputStream =
        appContext.contentResolver.openInputStream(uri)
            ?: throw IOException("无法读取所选文件")

    /**
     * 边读边卡上限。
     *
     * 不能"先整个读进内存再看多大"——那正是要防的事（见 [BlockRules.MAX_RULE_BYTES]）。
     */
    private fun readCapped(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        input.use { ins ->
            while (true) {
                val read = ins.read(buffer)
                if (read < 0) break
                total += read
                if (total > BlockRules.MAX_RULE_BYTES) throw RuleTooLarge()
                out.write(buffer, 0, read)
            }
        }
        return out.toByteArray()
    }

    // ---------------------------------------------------------------- 内部：杂项

    /**
     * 网址对应的展示名。
     *
     * 取 `主机名 / 末段路径`，例如
     * `https://anti-ad.net/premium/domains.txt` -> `anti-ad.net / domains`。
     * 只给主机名用户分不清"这份和那份有什么区别"，带上文件名才有辨识度。
     */
    private fun nameForUrl(url: String): String {
        val afterScheme = url.substringAfter("://")
        val host = afterScheme.substringBefore('/').substringBefore('?')
        val last = afterScheme.substringAfterLast('/', "")
            .substringBefore('?')
            .substringBeforeLast('.')
        val label = if (last.isNotBlank() && last != host) "$host / $last" else host
        return label.take(60).ifBlank { "订阅名单" }
    }

    private fun urlSourceId(url: String): String = "u" + sha1(url).take(16)

    private fun localSourceId(): String = "f" + UUID.randomUUID().toString().replace("-", "").take(16)

    private fun displayNameOf(uri: Uri): String? = runCatching {
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0)?.trim()?.takeIf { it.isNotEmpty() } else null
            }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    private fun sha1(value: String): String = try {
        MessageDigest.getInstance("SHA-1")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    } catch (_: Exception) {
        // 理论上不会发生（SHA-1 是 JDK 必备算法）；退化成 hashCode 也比崩溃强。
        Integer.toHexString(value.hashCode())
    }

    /** 失败原因转成人能看懂的一句话。分不出类型时退回异常自带的 message。 */
    private fun describe(e: Exception): String = when (e) {
        is IOException -> e.message?.takeIf { it.isNotBlank() } ?: "网络不可用"
        else -> e.message?.takeIf { it.isNotBlank() } ?: "导入失败"
    }

    // ---------------------------------------------------------------- 元信息编解码

    private fun encodeMeta(sub: BlockSubscription): String = buildString {
        append("name=").append(oneLine(sub.name)).append('\n')
        append("url=").append(oneLine(sub.url)).append('\n')
        append("enabled=").append(if (sub.enabled) 1 else 0).append('\n')
        append("updated=").append(sub.updatedAt).append('\n')
        append("count=").append(sub.count).append('\n')
        append("lines=").append(sub.lines).append('\n')
        append("skipped=").append(sub.skipped).append('\n')
        append("truncated=").append(if (sub.truncated) 1 else 0).append('\n')
    }

    /**
     * 解码元信息。
     *
     * 键值用**第一个** `=` 分割、值保留其后的全部内容 —— 订阅网址的 query
     * 里常带 `=`（`?a=b`），用 `split('=')` 会把网址切碎。
     *
     * 缺 `updated` 视为损坏（它没有合理的默认值，也可能是被截断的半个文件），
     * 直接跳过这条：宁可少一条订阅，也不要把半截数据当成正常名单。
     */
    private fun decodeMeta(id: String, text: String): BlockSubscription? {
        val map = HashMap<String, String>()
        for (line in text.lineSequence()) {
            if (line.isBlank()) continue
            val key = line.substringBefore('=').trim()
            if (key.isEmpty()) continue
            map[key] = line.substringAfter('=', "")
        }
        val updated = map["updated"]?.toLongOrNull() ?: return null
        return BlockSubscription(
            id = id,
            name = map["name"].orEmpty().ifBlank { "订阅名单" },
            url = map["url"].orEmpty(),
            enabled = map["enabled"] != "0",
            updatedAt = updated,
            count = map["count"]?.toIntOrNull() ?: 0,
            lines = map["lines"]?.toIntOrNull() ?: 0,
            skipped = map["skipped"]?.toIntOrNull() ?: 0,
            truncated = map["truncated"] == "1",
        )
    }

    /** 元信息是逐行格式，值里的换行会把一行拆成两行，写入前必须压平。 */
    private fun oneLine(value: String): String = value.replace('\n', ' ').replace('\r', ' ').trim()

    /** 超过读取上限。单独一个类型，才能与普通 IO 失败分开报错。 */
    private class RuleTooLarge : IOException("名单超过上限")

    companion object {
        /** 名单存放目录（应用私有，无需任何存储权限）。 */
        private const val DIR = "adblock"
        private const val META_SUFFIX = ".meta"
        private const val DOMAINS_SUFFIX = ".domains"

        /** 网络超时。名单可能几 MB，读超时给宽一点。 */
        private const val TIMEOUT_MS = 15_000

        /** gzip 魔数。 */
        private const val GZIP_MAGIC_0: Byte = 0x1f
        private const val GZIP_MAGIC_1: Byte = 0x8b.toByte()

        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
    }
}
