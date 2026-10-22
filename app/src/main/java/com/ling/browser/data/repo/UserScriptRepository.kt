package com.ling.browser.data.repo

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.ling.browser.web.EnabledUserScript
import com.ling.browser.web.UserScript
import com.ling.browser.web.UserScriptParser
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
import org.json.JSONObject

/**
 * 一份已安装的用户脚本。
 *
 * [script] 是解析结果（匹配规则 + 正文），[enabled] 等是**用户态**，不属于
 * 脚本作者的元数据，因此另存一份 `<id>.meta` —— 更新脚本时不该把用户的
 * 启停状态覆盖掉。
 */
data class UserScriptEntry(
    /** 稳定主键：网址导入按网址哈希，本地导入按随机值。 */
    val id: String,
    val script: UserScript,
    val enabled: Boolean,
    /** 来源网址；本地导入为空串（不持久化 SAF 临时授权，见 [BlockSubscription.url]）。 */
    val sourceUrl: String,
    val updatedAt: Long,
) {
    val canRefresh: Boolean
        get() = sourceUrl.startsWith("http://", ignoreCase = true) ||
            sourceUrl.startsWith("https://", ignoreCase = true)
}

/** 一次导入/更新的结果。失败原因是给 UI 直接展示的短句。 */
sealed interface ScriptImportResult {
    data class Ok(val entry: UserScriptEntry) : ScriptImportResult
    data class Failed(val reason: String) : ScriptImportResult
}

/**
 * 用户脚本的存储层。
 *
 * ## 目录结构
 *
 * ```
 * files/userscripts/<id>.user.js   # 原始源码（含元数据头，可直接导出/回灌）
 * files/userscripts/<id>.require.js # @require 依赖的源码（导入时下载，按顺序拼接）
 * files/userscripts/<id>.resources  # @resource 资源（JSON：名称 -> mime + base64）
 * files/userscripts/<id>.meta      # 启用状态、来源网址、更新时间
 * files/userscripts/<id>.values    # GM_setValue 的键值快照（JSON）
 * ```
 *
 * 源码**原样保存**而不是存解析后的字段：脚本的元数据头是作者契约的一部分，
 * 原样保留才能让"导出 → 分享 → 别人导入"这一圈不丢信息。启用状态则相反，
 * 必须单独存，否则更新一份脚本会把用户关掉的状态又打开。
 *
 * ## 值快照为什么在内存里也留一份
 *
 * `GM_getValue` 是同步语义，而 Android 的 JS 桥只能异步。因此注入时要把
 * 整个键值表当 JSON 一次性塞进页面（见 [EnabledUserScript.valuesJson]）。
 * 那份 JSON 必须**同步可得**，不能在每个页面加载时去读盘 —— 所以在内存里
 * 维护一份镜像，写盘异步进行。
 *
 * 体积与条数都有硬上限（[MAX_BYTES] / [MAX_SCRIPTS]）：用户脚本是整段 JS，
 * 不设上限的话一个几 MB 的脚本会让每次页面加载都卡一下。
 */
class UserScriptRepository(
    context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val appContext = context.applicationContext

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val lock = Mutex()

    private val dir: File
        get() = File(appContext.filesDir, DIR)

    private val entries = LinkedHashMap<String, UserScriptEntry>()

    /** id -> GM 值的镜像（同步读，见类注释）。 */
    private val values = HashMap<String, JSONObject>()

    /**
     * id -> `@require` 源码。
     *
     * 与值表同理放内存：`addDocumentStartJavaScript` / `evaluateJavascript`
     * 都是同步拼字符串，没有空间在那儿等磁盘。依赖在导入时下载一次，
     * 之后每次注入只读这份缓存，不再联网。
     */
    private val requires = HashMap<String, String>()

    /**
     * id -> `@resource` 资源表（`名称 -> {mime, base64}`）。
     *
     * 同样放内存：`GM_getResourceText/URL` 要同步返回，注入时直接内联进脚本。
     */
    private val resources = HashMap<String, JSONObject>()

    private val _scripts = MutableStateFlow<List<UserScriptEntry>>(emptyList())
    val scripts: StateFlow<List<UserScriptEntry>> = _scripts.asStateFlow()

    private val _enabledScripts = MutableStateFlow<List<EnabledUserScript>>(emptyList())

    /** 启用中的脚本 + 值快照，供 [com.ling.browser.web.WebTabManager] 直接注入。 */
    val enabledScripts: StateFlow<List<EnabledUserScript>> = _enabledScripts.asStateFlow()

    init {
        scope.launch {
            lock.withLock { loadFromDisk() }
        }
    }

    // ---------------------------------------------------------------- 导入 / 更新

    /** 从网址导入。同一网址重复导入 = 更新，不会在列表里堆两条。 */
    suspend fun importFromUrl(rawUrl: String): ScriptImportResult = withContext(Dispatchers.IO) {
        lock.withLock {
            val url = rawUrl.trim()
            if (!url.startsWith("http://", ignoreCase = true) &&
                !url.startsWith("https://", ignoreCase = true)
            ) {
                return@withLock ScriptImportResult.Failed("只支持 http/https 地址")
            }
            val id = urlSourceId(url)
            if (entries.size >= MAX_SCRIPTS && !entries.containsKey(id)) {
                return@withLock ScriptImportResult.Failed("最多支持 $MAX_SCRIPTS 个用户脚本，请先删除一些")
            }
            val text = try {
                fetch(url)
            } catch (e: TooLarge) {
                return@withLock ScriptImportResult.Failed("脚本超过 $maxSizeLabel，已放弃导入")
            } catch (e: Exception) {
                return@withLock ScriptImportResult.Failed(describe(e))
            }
            persist(id = id, url = url, text = text)
        }
    }

    /**
     * 从本地文件导入。
     *
     * 每次都是**新**脚本（随机 id）：本地文件没有稳定标识，按内容或文件名
     * 去重都不可靠，硬做反而会把两次导入的不同版本合成一条。
     */
    suspend fun importFromLocal(uri: Uri): ScriptImportResult = withContext(Dispatchers.IO) {
        lock.withLock {
            if (entries.size >= MAX_SCRIPTS) {
                return@withLock ScriptImportResult.Failed("最多支持 $MAX_SCRIPTS 个用户脚本，请先删除一些")
            }
            val text = try {
                decode(inputStreamOf(uri))
            } catch (e: TooLarge) {
                return@withLock ScriptImportResult.Failed("脚本超过 $maxSizeLabel，已放弃导入")
            } catch (e: Exception) {
                return@withLock ScriptImportResult.Failed(describe(e))
            }
            persist(id = "f" + UUID.randomUUID().toString().replace("-", "").take(16), url = "", text = text)
        }
    }

    /** 重新拉取网址脚本。本地导入的没有远端，如实拒绝而不是静默不动。 */
    suspend fun refresh(id: String): ScriptImportResult = withContext(Dispatchers.IO) {
        lock.withLock {
            val current = entries[id] ?: return@withLock ScriptImportResult.Failed("脚本不存在")
            if (!current.canRefresh) {
                return@withLock ScriptImportResult.Failed("本地导入的脚本无法在线更新")
            }
            val text = try {
                fetch(current.sourceUrl)
            } catch (e: TooLarge) {
                return@withLock ScriptImportResult.Failed("脚本超过 $maxSizeLabel，已放弃更新")
            } catch (e: Exception) {
                return@withLock ScriptImportResult.Failed(describe(e))
            }
            persist(id = id, url = current.sourceUrl, text = text)
        }
    }

    /**
     * 解析 -> 落盘 -> 发布。
     *
     * 已存在的 id 保留原启用状态：更新脚本不该顺手把用户关掉的脚本打开。
     */
    private fun persist(id: String, url: String, text: String): ScriptImportResult {
        val script = UserScriptParser.parse(text)
            ?: return ScriptImportResult.Failed("不是有效的用户脚本：缺少 ==UserScript== 元数据头")
        if (script.includes.isEmpty()) {
            return ScriptImportResult.Failed("没有 @match / @include 规则，脚本不会在任何页面运行")
        }
        // 依赖在导入时就解析掉：宁可现在明确失败，也不要装上一个运行到
        // 第一行就 ReferenceError 的脚本（用户只会看到"这脚本不工作"）。
        val requireText = try {
            fetchRequires(script.requireUrls)
        } catch (e: RequireFailure) {
            return ScriptImportResult.Failed(e.reason)
        }
        // 资源同理：脚本里 `GM_getResourceText` 拿不到内容时的报错很难懂，
        // 不如在导入阶段就明确失败。
        val resourcesJson = try {
            fetchResources(script.resources)
        } catch (e: ResourceFailure) {
            return ScriptImportResult.Failed(e.reason)
        }
        val entry = UserScriptEntry(
            id = id,
            script = script,
            enabled = entries[id]?.enabled ?: true,
            sourceUrl = url,
            updatedAt = clock(),
        )
        dir.mkdirs()
        File(dir, id + SOURCE_SUFFIX).writeText(text, Charsets.UTF_8)
        if (requireText.isEmpty()) {
            File(dir, id + REQUIRE_SUFFIX).delete()
        } else {
            File(dir, id + REQUIRE_SUFFIX).writeText(requireText, Charsets.UTF_8)
        }
        requires[id] = requireText
        if (resourcesJson.length() == 0) {
            File(dir, id + RESOURCES_SUFFIX).delete()
        } else {
            File(dir, id + RESOURCES_SUFFIX).writeText(resourcesJson.toString(), Charsets.UTF_8)
        }
        resources[id] = resourcesJson
        writeMeta(entry)
        entries[id] = entry
        publish()
        return ScriptImportResult.Ok(entry)
    }

    // ---------------------------------------------------------------- 启停 / 删除

    suspend fun setEnabled(id: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        lock.withLock {
            val current = entries[id] ?: return@withLock
            if (current.enabled == enabled) return@withLock
            val next = current.copy(enabled = enabled)
            entries[id] = next
            writeMeta(next)
            publish()
        }
    }

    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            if (entries.remove(id) == null) return@withLock
            values.remove(id)
            requires.remove(id)
            resources.remove(id)
            File(dir, id + SOURCE_SUFFIX).delete()
            File(dir, id + REQUIRE_SUFFIX).delete()
            File(dir, id + RESOURCES_SUFFIX).delete()
            File(dir, id + META_SUFFIX).delete()
            File(dir, id + VALUES_SUFFIX).delete()
            publish()
        }
    }

    // ---------------------------------------------------------------- GM 存储

    /**
     * 写入一个 `GM_setValue` 的键值。
     *
     * 调用方（[com.ling.browser.ui.BrowserViewModel]）已经校验过来源脚本，
     * 这里再挡一次：脚本必须存在且处于启用状态 —— 停用的脚本不该还能写盘。
     * 值大小也设上限，避免脚本把几十 MB 塞进一个键里。
     */
    suspend fun setValue(id: String, key: String, value: Any?) = withContext(Dispatchers.IO) {
        if (key.isEmpty() || key.length > MAX_KEY_LENGTH) return@withContext
        lock.withLock {
            val entry = entries[id] ?: return@withLock
            if (!entry.enabled) return@withLock
            val obj = values.getOrPut(id) { JSONObject() }
            obj.put(key, value ?: JSONObject.NULL)
            if (obj.toString().length > MAX_VALUES_BYTES) return@withLock
            writeValues(id, obj)
            publish()
        }
    }

    /**
     * 删除一个 `GM_deleteValue` 的键。
     *
     * 与 [setValue] 同样校验脚本启用状态；键不存在时直接返回，不重写文件。
     * 删空后仍保留一个 `{}` 值文件，而不是删掉 —— 下一次 `GM_getValue`
     * 从空表读默认值即可，少一条"文件在不在"的分支。
     */
    suspend fun deleteValue(id: String, key: String) = withContext(Dispatchers.IO) {
        if (key.isEmpty() || key.length > MAX_KEY_LENGTH) return@withContext
        lock.withLock {
            val entry = entries[id] ?: return@withLock
            if (!entry.enabled) return@withLock
            val obj = values[id] ?: return@withLock
            if (!obj.has(key)) return@withLock
            obj.remove(key)
            writeValues(id, obj)
            publish()
        }
    }

    // ---------------------------------------------------------------- 内部：读回磁盘

    private fun loadFromDisk() {
        val d = dir
        if (!d.isDirectory) {
            publish()
            return
        }
        val sources = d.listFiles { f -> f.isFile && f.name.endsWith(SOURCE_SUFFIX) } ?: return
        entries.clear()
        values.clear()
        requires.clear()
        resources.clear()
        for (file in sources) {
            val id = file.name.removeSuffix(SOURCE_SUFFIX)
            val text = runCatching { file.readText() }.getOrNull() ?: continue
            val script = UserScriptParser.parse(text) ?: continue
            val meta = decodeMeta(runCatching { File(d, id + META_SUFFIX).readText() }.getOrNull())
            entries[id] = UserScriptEntry(
                id = id,
                script = script,
                enabled = meta?.enabled ?: true,
                sourceUrl = meta?.url.orEmpty(),
                updatedAt = meta?.updated ?: 0L,
            )
            runCatching {
                File(d, id + REQUIRE_SUFFIX).readText().takeIf { it.isNotBlank() }?.let {
                    requires[id] = it
                }
            }
            runCatching {
                File(d, id + VALUES_SUFFIX).readText().takeIf { it.isNotBlank() }?.let {
                    values[id] = JSONObject(it)
                }
            }
            runCatching {
                File(d, id + RESOURCES_SUFFIX).readText().takeIf { it.isNotBlank() }?.let {
                    resources[id] = JSONObject(it)
                }
            }
        }
        publish()
    }

    /** 把内存副本发布到两个 StateFlow：列表 + 启用中快照。 */
    private fun publish() {
        _scripts.value = entries.values.sortedBy { it.script.name.lowercase() }
        _enabledScripts.value = entries.values
            .filter { it.enabled }
            .map {
                EnabledUserScript(
                    id = it.id,
                    script = it.script,
                    valuesJson = values[it.id]?.toString() ?: "{}",
                    requireCode = requires[it.id].orEmpty(),
                    resourcesJson = resources[it.id]?.toString() ?: "{}",
                )
            }
    }

    // ---------------------------------------------------------------- 内部：网络 / 解码

    /** 拉取脚本文本。与 [BlockListRepository.fetch] 同理，用魔数兜住无条件的 gzip。 */
    private fun fetch(url: String, maxBytes: Int = MAX_BYTES): String {
        val conn = openConnection(url)
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            return decode(conn.inputStream, maxBytes)
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private fun openConnection(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
        }

    /**
     * 下载并拼接全部 `@require`。
     *
     * 顺序必须与元数据头一致：后一个依赖可能依赖前一个（例如插件扩展 jQuery）。
     * 任何一个失败都整体失败 —— 少一个依赖的脚本通常跑不起来，静默装上只是
     * 把错误推迟到用户眼前。
     */
    private fun fetchRequires(urls: List<String>): String {
        if (urls.isEmpty()) return ""
        val builder = StringBuilder()
        var total = 0
        for (url in urls) {
            val code = try {
                fetch(url, MAX_REQUIRE_BYTES)
            } catch (e: TooLarge) {
                throw RequireFailure("依赖超过 ${sizeLabel(MAX_REQUIRE_BYTES)}：$url")
            } catch (e: Exception) {
                throw RequireFailure("依赖下载失败（${describe(e)}）：$url")
            }
            total += code.toByteArray(Charsets.UTF_8).size
            if (total > MAX_REQUIRE_BYTES) {
                throw RequireFailure("脚本依赖总大小超过 ${sizeLabel(MAX_REQUIRE_BYTES)}")
            }
            if (builder.isNotEmpty()) builder.append("\n;\n")
            builder.append(code).append('\n')
        }
        return builder.toString().trimEnd()
    }

    private fun decode(stream: InputStream, maxBytes: Int = MAX_BYTES): String {
        val bytes = readCapped(stream, maxBytes)
        return String(gunzipIfNeeded(bytes, maxBytes), Charsets.UTF_8)
    }

    /**
     * 抓一份 `@resource`：保留原始字节（可能是图片/字体），并记下 MIME。
     *
     * MIME 去掉 charset 等参数：`data:` URL 里带参数虽然合法，但部分站点的
     * CSS 解析器会挑刺，只留主类型更稳。
     */
    private fun fetchResource(url: String): Pair<String, ByteArray> {
        val conn = openConnection(url)
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            val mime = conn.contentType
                ?.substringBefore(';')
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: "application/octet-stream"
            val bytes = readCapped(conn.inputStream, MAX_RESOURCE_BYTES)
            return mime to gunzipIfNeeded(bytes, MAX_RESOURCE_BYTES)
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /**
     * 下载全部 `@resource`，编码成 `{"名称":{"m":mime,"b":base64}}`。
     *
     * 为什么用 base64 而不是统一按文本存：`GM_getResourceURL` 要给出 `data:`
     * URL，二进制资源（图标、字体）必须原样保留。文本资源由页面侧用
     * `TextDecoder` 解回字符串。
     */
    private fun fetchResources(urls: Map<String, String>): JSONObject {
        if (urls.isEmpty()) return JSONObject()
        val out = JSONObject()
        var total = 0
        for ((name, url) in urls) {
            val (mime, bytes) = try {
                fetchResource(url)
            } catch (e: TooLarge) {
                throw ResourceFailure("资源超过 ${sizeLabel(MAX_RESOURCE_BYTES)}：$url")
            } catch (e: Exception) {
                throw ResourceFailure("资源下载失败：$url（${describe(e)}）")
            }
            total += bytes.size
            if (total > MAX_RESOURCE_TOTAL_BYTES) {
                throw ResourceFailure("脚本资源总大小超过 ${sizeLabel(MAX_RESOURCE_TOTAL_BYTES)}")
            }
            out.put(
                name,
                JSONObject()
                    .put("m", mime)
                    .put("b", Base64.encodeToString(bytes, Base64.NO_WRAP)),
            )
        }
        return out
    }

    /**
     * 无条件 gzip 兜底。
     *
     * 少数服务器不管 `Accept-Encoding` 都回 gzip（见 [BlockListRepository]），
     * 靠魔数判断比信任响应头可靠。
     */
    private fun gunzipIfNeeded(bytes: ByteArray, maxBytes: Int): ByteArray =
        if (bytes.size >= 2 && bytes[0] == GZIP_MAGIC_0 && bytes[1] == GZIP_MAGIC_1) {
            readCapped(GZIPInputStream(ByteArrayInputStream(bytes)), maxBytes)
        } else {
            bytes
        }

    private fun inputStreamOf(uri: Uri): InputStream =
        appContext.contentResolver.openInputStream(uri)
            ?: throw IOException("无法读取所选文件")

    /** 边读边卡上限，避免"先整个读进内存再看多大"。 */
    private fun readCapped(input: InputStream, maxBytes: Int = MAX_BYTES): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        input.use { ins ->
            while (true) {
                val read = ins.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) throw TooLarge()
                out.write(buffer, 0, read)
            }
        }
        return out.toByteArray()
    }

    // ---------------------------------------------------------------- 内部：元信息

    private fun writeMeta(entry: UserScriptEntry) {
        val text = buildString {
            append("enabled=").append(if (entry.enabled) 1 else 0).append('\n')
            append("url=").append(oneLine(entry.sourceUrl)).append('\n')
            append("updated=").append(entry.updatedAt).append('\n')
        }
        File(dir, entry.id + META_SUFFIX).writeText(text, Charsets.UTF_8)
    }

    private fun writeValues(id: String, obj: JSONObject) {
        runCatching {
            File(dir, id + VALUES_SUFFIX).writeText(obj.toString(), Charsets.UTF_8)
        }
    }

    private data class Meta(val enabled: Boolean, val url: String, val updated: Long)

    /** 元信息损坏（可能被截断）时返回 null，由调用方退回默认值。 */
    private fun decodeMeta(text: String?): Meta? {
        if (text == null) return null
        val map = HashMap<String, String>()
        for (line in text.lineSequence()) {
            if (line.isBlank()) continue
            val key = line.substringBefore('=').trim()
            if (key.isEmpty()) continue
            map[key] = line.substringAfter('=', "")
        }
        return Meta(
            enabled = map["enabled"] != "0",
            url = map["url"].orEmpty(),
            updated = map["updated"]?.toLongOrNull() ?: 0L,
        )
    }

    private fun oneLine(value: String): String =
        value.replace('\n', ' ').replace('\r', ' ').trim()

    // ---------------------------------------------------------------- 内部：杂项

    private fun urlSourceId(url: String): String = "u" + sha1(url).take(16)

    private fun sha1(value: String): String = try {
        MessageDigest.getInstance("SHA-1")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    } catch (_: Exception) {
        Integer.toHexString(value.hashCode())
    }

    private fun describe(e: Exception): String = when (e) {
        is IOException -> e.message?.takeIf { it.isNotBlank() } ?: "网络不可用"
        else -> e.message?.takeIf { it.isNotBlank() } ?: "导入失败"
    }

    /** 超过读取上限。单独一个类型，才能与普通 IO 失败分开报错。 */
    private class TooLarge : IOException("脚本超过上限")

    /** `@require` 下载失败。message 直接作为导入失败原因展示。 */
    private class RequireFailure(val reason: String) : IOException(reason)

    /** `@resource` 下载/体积失败，同样直接展示给用户。 */
    private class ResourceFailure(val reason: String) : IOException(reason)

    companion object {
        /** 存放目录（应用私有，无需存储权限）。 */
        private const val DIR = "userscripts"
        private const val SOURCE_SUFFIX = ".user.js"
        private const val REQUIRE_SUFFIX = ".require.js"
        private const val RESOURCES_SUFFIX = ".resources"
        private const val META_SUFFIX = ".meta"
        private const val VALUES_SUFFIX = ".values"

        /**
         * 单条脚本上限。
         *
         * 整段 JS 进内存再注入，太大就会拖慢每次页面加载；1 MiB 是给带内嵌
         * 字典/规则的脚本留的余量，再大就该考虑把数据放到 `@require` 里了。
         */
        const val MAX_BYTES = 1024 * 1024

        /** 上限的人类可读写法（整 MiB 时写成 MiB，避免出现 "1024 KiB"）。 */
        private val maxSizeLabel: String
            get() = sizeLabel(MAX_BYTES)

        /**
         * 全部 `@require` 的合计上限。
         *
         * 依赖与正文一起注入，所以它直接叠加到每页开销上；2 MiB 足够容纳
         * jQuery + 几个常见插件，又不至于让单页注入变成几 MB 的字符串。
         */
        private const val MAX_REQUIRE_BYTES = 2 * 1024 * 1024

        /**
         * 单个 `@resource` 与全部资源的合计上限。
         *
         * 资源最终以 base64 内联进每次注入，体积会再涨约 1/3；因此合计上限
         * 比 `@require` 更紧，避免一个带字体/大图的脚本把每个页面都拖慢。
         */
        private const val MAX_RESOURCE_BYTES = 2 * 1024 * 1024
        private const val MAX_RESOURCE_TOTAL_BYTES = 4 * 1024 * 1024

        private fun sizeLabel(bytes: Int): String = if (bytes % (1024 * 1024) == 0) {
            "${bytes / (1024 * 1024)} MiB"
        } else {
            "${bytes / 1024} KiB"
        }

        /** 条数上限。document-start 是按条注册的，条数直接决定每页的注入开销。 */
        const val MAX_SCRIPTS = 50

        private const val MAX_KEY_LENGTH = 256

        /** 单个脚本的值表上限（序列化后字符数）。 */
        private const val MAX_VALUES_BYTES = 32 * 1024

        private const val TIMEOUT_MS = 15_000

        private const val GZIP_MAGIC_0: Byte = 0x1f
        private const val GZIP_MAGIC_1: Byte = 0x8b.toByte()

        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
    }
}
