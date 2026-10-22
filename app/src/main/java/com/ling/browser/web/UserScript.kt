package com.ling.browser.web

/**
 * 用户脚本（Tampermonkey / Greasemonkey 风格）。
 *
 * 这一层只做**纯函数**的事：解析 `==UserScript==` 元数据头、把 `@match` /
 * `@include` 编译成可复用正则、生成注入用的包装代码。不碰 Android API，
 * 因此能直接在 JVM 上单测（与 [BlockRules] 同一套路）。
 *
 * ## 为什么不用 `@match` 原样做字符串包含判断
 *
 * Chrome 的 match pattern 有明确语义，靠 `contains` 会错：
 * `*.example.com` 这种通配子域的写法 **不包含裸域** `example.com`；
 * 同时 scheme / host 大小写不敏感、端口不参与匹配、path 大小写敏感。
 * 这些都在 [canonicalUrl] + [UrlMatcher] 里逐条落实。
 */
enum class ScriptRunAt {
    /** 页面自己的脚本执行之前（需要 `DOCUMENT_START_SCRIPT` 能力）。 */
    START,

    /** 文档已提交、首帧可绘制（最接近 DOMContentLoaded）。 */
    END,

    /** `onPageFinished`，资源加载完成之后。 */
    IDLE,
}

/**
 * 编译后的 URL 匹配规则。
 *
 * 内部存的是**整串正则**，同时供 Kotlin 侧匹配与 JS 侧守卫使用 ——
 * 两边断言同一份正则源，避免"原生认为匹配、页面里又判断一次"出现分歧。
 */
class UrlMatcher internal constructor(
    private val regex: Regex,
    /** 可交给 WebView 的 origin 白名单；null 表示规则形状推不出（整串通配）。 */
    internal val originRules: List<String>?,
) {
    /** 正则源（含 `^…$`）。注入时用 `new RegExp(…)` 复用同一份。 */
    internal val source: String get() = regex.pattern

    fun matches(url: String): Boolean {
        // 非 http(s)（about:blank、data:、ling://）一律不匹配：用户脚本只跑在真实网页上。
        val canonical = canonicalUrl(url) ?: return false
        return regex.matches(canonical)
    }
}

/**
 * 解析后的脚本。
 *
 * [code] 是**去掉元数据头之后**的正文：头部整段是 `//` 注释，留着不影响执行，
 * 但每次注入都白传几百字节，且会在调试器里掩盖真正的脚本起点。
 */
data class UserScript(
    val name: String,
    val namespace: String,
    val version: String,
    val description: String,
    val runAt: ScriptRunAt,
    val noFrames: Boolean,
    val grants: Set<String>,
    /** `@match` + `@include`，任一命中即运行。 */
    val includes: List<UrlMatcher>,
    val excludes: List<UrlMatcher>,
    /** 展示用的原始规则文本（`@match` / `@include` 的原样值）。 */
    val patternLabels: List<String>,
    val code: String,
    /**
     * `@require` 的依赖地址（仅 http/https，已去重）。
     *
     * 这里只保留**地址**：真正的下载发生在仓储层（导入/更新时），下载结果
     * 与脚本一起落盘（见 [com.ling.browser.data.repo.UserScriptRepository]）。
     * 注入时不再联网，否则每次页面加载都要等一串网络请求。
     */
    val requireUrls: List<String> = emptyList(),
    /**
     * `@resource 名称 地址` 的映射。
     *
     * 与 [requireUrls] 一样只留地址，内容由仓储层在导入时抓取并缓存；
     * 注入时随脚本一次性带进页面（`GM_getResourceText` 是同步语义）。
     */
    val resources: Map<String, String> = emptyMap(),
    /**
     * `@connect` 的目标白名单（域名 / `self` / `*`）。
     *
     * 只有**声明了**才校验：Tampermonkey 在缺失时会弹窗询问用户，我们没有
     * 逐请求的确认 UI，静默拒绝会让大量老脚本直接失效。声明了就按它执行，
     * 这至少让作者的安全意图生效（见 `BrowserViewModel.startScriptRequest`）。
     */
    val connects: Set<String> = emptySet(),
) {
    /** 没有任何规则时不匹配任何页面 —— 与 Tampermonkey 语义一致。 */
    fun matches(url: String): Boolean =
        includes.any { it.matches(url) } && excludes.none { it.matches(url) }

    /**
     * 注册 document-start 时用的 origin 白名单。
     *
     * `addDocumentStartJavaScript` 只能按 **origin** 收敛（没有 path 维度），
     * 因此这里尽量把规则收缩到真实域名，减少"每个页面都塞一份脚本"的开销；
     * 推不出来时退回 `*`，最终是否正确仍由注入代码里的 URL 守卫兜底。
     */
    val allowedOrigins: Set<String>
        get() {
            if (includes.isEmpty()) return emptySet()
            val out = HashSet<String>()
            for (matcher in includes) {
                val rules = matcher.originRules ?: return ALL_ORIGINS
                out.addAll(rules)
            }
            return out
        }
}

/**
 * 交给 [WebTabManager] 注入的一份脚本快照。
 *
 * [valuesJson] 是 `GM_getValue` 的**只读快照**（键值对 JSON）。为什么不是同步桥：
 * Android 侧的 JS 桥只能异步通信，而 Tampermonkey 的 `GM_getValue` 是同步的。
 * 折中为"进页面时一次性注入全部值"，写入则异步落盘 —— 写入对**当前页**立即
 * 生效（脚本内存里的对象已更新），对后续页面靠下一次快照带过去。
 */
data class EnabledUserScript(
    val id: String,
    val script: UserScript,
    val valuesJson: String,
    /** 导入时缓存的 `@require` 源码（无依赖时为空串）。 */
    val requireCode: String = "",
    /**
     * 导入时缓存的 `@resource` 内容，形如
     * `{"名称":{"m":"mime","b":"base64"}}`。
     *
     * 用 base64 而不是直接存文本：资源可能是图片/字体，`GM_getResourceURL`
     * 需要拼成 `data:` URL 给 CSS 用。
     */
    val resourcesJson: String = "{}",
)

/** 元数据头起止标记（去掉 `//` 与空白后比较）。 */
private const val HEADER_BEGIN = "==UserScript=="
private const val HEADER_END = "==/UserScript=="

private val ALL_ORIGINS: Set<String> = setOf("*")

/**
 * `==UserScript==` 元数据头解析器。
 *
 * 只做"解析"，不判断"能不能装"：解析失败返回 null；没有匹配规则属于
 * 合法但无用的脚本，由导入方决定是否拒绝并给出原因（见仓库层）。
 */
object UserScriptParser {

    fun parse(source: String): UserScript? {
        val text = source.removePrefix("\uFEFF")
        val lines = text.lines()
        val begin = lines.indexOfFirst { isMarker(it, HEADER_BEGIN) }
        if (begin < 0) return null
        val end = lines.indices.firstOrNull { it > begin && isMarker(lines[it], HEADER_END) }
            ?: return null

        val meta = LinkedHashMap<String, MutableList<String>>()
        for (line in lines.subList(begin + 1, end)) {
            val body = line.trim().removePrefix("//").trim()
            if (!body.startsWith("@")) continue
            val token = body.substring(1)
            val key = token.substringBefore(' ').substringBefore('\t').trim()
            if (key.isEmpty()) continue
            // 按**原文大小写**切值，再把键统一成小写：用 lowercased 的键去切
            // 原串会切不出东西（@Name 这类写法并不罕见）。
            val value = token.substring(key.length).trim()
            meta.getOrPut(key.lowercase()) { mutableListOf() }.add(value)
        }

        val matches = meta["match"].orEmpty()
        val includeGlobs = meta["include"].orEmpty()
        val excludes = meta["exclude"].orEmpty()

        val includes = ArrayList<UrlMatcher>()
        val labels = ArrayList<String>()
        // @match 与 @include 语法不同：前者是 match pattern，后者是整串通配符。
        for (pattern in matches) {
            if (pattern.isBlank()) continue
            val matcher = compileMatchPattern(pattern.trim()) ?: continue
            includes.add(matcher)
            labels.add(pattern)
        }
        for (pattern in includeGlobs) {
            if (pattern.isBlank()) continue
            val matcher = compilePattern(pattern, allowRegex = true) ?: continue
            includes.add(matcher)
            labels.add(pattern)
        }
        val excludeMatchers = excludes.mapNotNull { compilePattern(it, allowRegex = true) }

        val runAt = when (meta["run-at"]?.lastOrNull()?.lowercase()) {
            "document-start" -> ScriptRunAt.START
            "document-end", "document-body" -> ScriptRunAt.END
            else -> ScriptRunAt.IDLE
        }

        val grants = meta["grant"].orEmpty()
            .map { it.substringBefore(' ').trim() }
            .filter { it.isNotEmpty() && !it.equals("none", ignoreCase = true) }
            .toSet()

        // 只接受 http/https：下载发生在导入时，其他 scheme（file:// 等）
        // 在 Android 上拿不到，留着只会让脚本在运行时 ReferenceError。
        val requireUrls = meta["require"].orEmpty()
            .map { it.trim() }
            .filter { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
            .distinct()

        // @resource 的格式是 `<名称> <地址>`，名称里不允许空格。
        val resources = LinkedHashMap<String, String>()
        for (line in meta["resource"].orEmpty()) {
            val name = line.substringBefore(' ').substringBefore('\t').trim()
            val url = line.substring(name.length).trim()
            if (name.isEmpty() || url.isEmpty()) continue
            if (!url.startsWith("http://", ignoreCase = true) &&
                !url.startsWith("https://", ignoreCase = true)
            ) {
                continue
            }
            resources[name] = url
        }

        // @connect 允许一行写多个（逗号/空格分隔），域名大小写不敏感。
        val connects = meta["connect"].orEmpty()
            .flatMap { it.split(',', ' ', '\t') }
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()

        // 正文：丢掉元数据头那一段。其余内容（许可证注释、'use strict' 等）原样保留。
        val code = lines.filterIndexed { index, _ -> index < begin || index > end }
            .joinToString("\n")
            .trimStart('\n', '\r')

        return UserScript(
            name = meta["name"]?.lastOrNull()?.takeIf { it.isNotBlank() } ?: "未命名脚本",
            namespace = meta["namespace"]?.lastOrNull().orEmpty(),
            version = meta["version"]?.lastOrNull().orEmpty(),
            description = meta["description"]?.lastOrNull().orEmpty(),
            runAt = runAt,
            noFrames = meta.containsKey("noframes"),
            grants = grants,
            includes = includes,
            excludes = excludeMatchers,
            patternLabels = labels,
            code = code,
            requireUrls = requireUrls,
            resources = resources,
            connects = connects,
        )
    }

    private fun isMarker(line: String, marker: String): Boolean =
        line.trim().removePrefix("//").trim() == marker

    /**
     * 编译一条 `@include` / `@exclude` 规则。
     *
     * 它们是整串通配符，也兼容写成 `/regex/` 的形式（Tampermonkey 允许）。
     * 编译失败的规则直接跳过 —— 一条坏规则不该让整份脚本不可用。
     */
    private fun compilePattern(pattern: String, allowRegex: Boolean): UrlMatcher? {
        val trimmed = pattern.trim()
        if (trimmed.isEmpty()) return null
        if (allowRegex && trimmed.length > 2 && trimmed.startsWith("/") && trimmed.endsWith("/")) {
            return runCatching {
                val body = trimmed.substring(1, trimmed.length - 1)
                UrlMatcher(Regex("^(?:$body)$"), originRules = null)
            }.getOrNull()
        }
        if (trimmed.contains("://") || trimmed.startsWith("*://")) {
            return compileMatchPattern(trimmed)
        }
        // 没有 scheme 的裸 glob（少见）：按整串通配处理。
        // 必须自带 ^…$：Kotlin 的 Regex.matches 是整串匹配，而注入侧用的是
        // JS 的 RegExp.test（子串搜索），不加锚点两边行为会不一致。
        return runCatching {
            UrlMatcher(Regex("^${globSource(trimmed)}$"), originRules = null)
        }.getOrNull()
    }

    /**
     * Chrome match pattern：`<scheme>://<host><path>`。
     *
     * 生成的整串正则额外允许一个可选的 query：Chrome 只匹配 pathname，
     * 而我们的正则跑在"path + query"上，不加这一段会让
     * `@match https://a.com/page` 在 `/page?x=1` 上失效。
     */
    private fun compileMatchPattern(pattern: String): UrlMatcher? {
        val schemeEnd = pattern.indexOf("://")
        if (schemeEnd <= 0) return null
        val scheme = pattern.substring(0, schemeEnd).lowercase()
        if (scheme != "*" && scheme != "http" && scheme != "https") return null

        val rest = pattern.substring(schemeEnd + 3)
        val hostEnd = rest.indexOf('/')
        val rawHost = if (hostEnd >= 0) rest.substring(0, hostEnd) else rest
        val path = if (hostEnd >= 0) rest.substring(hostEnd) else "/*"
        // 端口不参与匹配：与 Chrome 一致，也让我们对 `:8080` 这类地址不至于漏判。
        val host = rawHost.substringBefore(':').lowercase()
        if (host.isEmpty()) return null

        val schemeSource = if (scheme == "*") "(?:https?)" else literal(scheme)
        val hostSource = when {
            host == "*" -> "[^/]+"
            // `*.example.com`：至少一级子域，**不**包含裸域。
            host.startsWith("*.") -> "[^/]+\\." + literal(host.substring(2))
            else -> literal(host)
        }
        val source = "^$schemeSource://$hostSource${globSource(path)}(?:\\?.*)?$"
        val regex = runCatching { Regex(source) }.getOrNull() ?: return null

        val origins = if (host == "*") {
            null
        } else {
            val schemes = if (scheme == "*") listOf("http", "https") else listOf(scheme)
            schemes.map { "$it://$host" }
        }
        return UrlMatcher(regex, origins)
    }
}

/** 通配符转正则片段：`*` 匹配任意字符，其余按字面量转义。 */
private fun globSource(glob: String): String = buildString {
    for (ch in glob) {
        if (ch == '*') append(".*") else append(literal(ch))
    }
}

/** 正则元字符转义。不用 [Regex.escape]：它生成 `\Q…\E`，JS 的 RegExp 不认。 */
private fun literal(ch: Char): String = when (ch) {
    '.', '\\', '+', '*', '?', '^', '$', '(', ')', '[', ']', '{', '}', '|', '/', '-' ->
        "\\" + ch
    else -> ch.toString()
}

private fun literal(value: String): String = buildString {
    for (ch in value.lowercase()) append(literal(ch))
}

/**
 * 把 URL 归一成用于匹配的形态。
 *
 * `scheme://host[:port]path?query#fragment` → `scheme://hostpath?query`
 *
 * - scheme / host 转小写（RFC 规定不区分大小写，Chrome 的 match pattern 亦然）
 * - 去掉端口：match pattern 不支持端口，Chrome 匹配时也忽略
 * - 去掉 fragment：它不参与任何规则
 * - 只接受 http(s)：内部页与 `data:` 不属于用户脚本的管辖范围
 */
internal fun canonicalUrl(url: String): String? {
    val hasHttp = url.startsWith("http://", ignoreCase = true)
    val hasHttps = url.startsWith("https://", ignoreCase = true)
    if (!hasHttp && !hasHttps) return null

    val schemeEnd = url.indexOf("://")
    if (schemeEnd <= 0) return null
    val scheme = url.substring(0, schemeEnd).lowercase()

    val rest = url.substring(schemeEnd + 3).substringBefore('#')
    val queryIndex = rest.indexOf('?')
    val query = if (queryIndex >= 0) rest.substring(queryIndex) else ""
    val beforeQuery = if (queryIndex >= 0) rest.substring(0, queryIndex) else rest

    val slash = beforeQuery.indexOf('/')
    val authority = (if (slash >= 0) beforeQuery.substring(0, slash) else beforeQuery)
        .substringAfterLast('@')
    val path = if (slash >= 0) beforeQuery.substring(slash) else "/"

    // IPv6 字面量形如 [::1]，按方括号整体取，不能按冒号切。
    val host = if (authority.startsWith("[")) {
        authority.substringBefore(']') + "]"
    } else {
        authority.substringBefore(':')
    }.lowercase()
    if (host.isEmpty()) return null

    return "$scheme://$host$path$query"
}

/**
 * 是否是用户脚本安装链接。
 *
 * 只看 **path** 是否以 `.user.js` 结尾：Greasyfork / OpenUserJS 的安装地址
 * 常带 `?version=…` 之类的查询串，路径判定不受影响。也刻意不看域名 ——
 * 任何站点都能托管脚本，装不装交给用户决定。
 */
internal fun isUserScriptUrl(url: String): Boolean {
    val lower = url.lowercase()
    if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
    val path = lower.substringBefore('?').substringBefore('#')
    return path.endsWith(".user.js")
}

/**
 * 生成注入到页面的包装代码。
 *
 * 结构固定为 IIFE，顺序是：协议守卫 → URL 匹配 → @noframes → GM 垫片 →
 * `@require` 依赖 → 脚本正文。
 * 包 IIFE 是必须的：用户脚本常用裸 `var` / 函数声明，不隔离会与页面及我们
 * 自己的夜间模式、资源嗅探注入互相污染全局。
 *
 * 这里的内联守卫与 Kotlin 侧 [UserScript.matches] 用**同一份正则源**，
 * 因此不会出现"原生放行、页面里又拒绝"的分歧。
 *
 * [requireCode] 是导入时下载并缓存的依赖源码（已按 `@require` 顺序拼接）；
 * 放在 GM 垫片之后，脚本与依赖都能拿到 `GM_*`，与 Tampermonkey 行为一致。
 */
internal fun scriptWrapper(
    script: UserScript,
    scriptId: String,
    valuesJson: String,
    requireCode: String = "",
    resourcesJson: String = "{}",
): String {
    // 没有匹配规则 = 永远不该运行。生成一段空脚本而不是 `var inc=;` 这种语法错误。
    if (script.includes.isEmpty()) return "(function(){})();"
    return buildString {
        append("(function(){try{")
        append("var u=location;")
        append("if(u.protocol!=='http:'&&u.protocol!=='https:')return;")
        append("var href=u.protocol+'//'+u.hostname.toLowerCase()+u.pathname+u.search;")
        append("var inc=")
        append(script.includes.joinToString("||") { "new RegExp(${jsString(it.source)}).test(href)" })
        append(";if(!inc)return;")
        if (script.excludes.isNotEmpty()) {
            append("var exc=")
            append(script.excludes.joinToString("||") { "new RegExp(${jsString(it.source)}).test(href)" })
            append(";if(exc)return;")
        }
        if (script.noFrames) append("if(window.top!==window.self)return;")
        append("var V=").append(valuesJson.takeIf { it.isNotBlank() } ?: "{}").append(";")
        append("var RES=").append(resourcesJson.takeIf { it.isNotBlank() } ?: "{}").append(";")
        append(gmPrelude(script, scriptId))
        append("\n")
        // 依赖必须在脚本正文之前：Greasyfork 上大量脚本直接使用 jQuery/$。
        // 与正文同处一个 IIFE，依赖里声明的顶层 var 才能被脚本看到。
        if (requireCode.isNotBlank()) {
            append(requireCode)
            append("\n;\n")
        }
        append(script.code)
        append("\n}catch(e){try{console.error('[翎] 用户脚本执行失败：'+e)}catch(_){}}})();")
    }
}

/**
 * GM_* 垫片。
 *
 * 按 `@grant` 发放能力：
 * - `GM_addStyle` / `GM_addElement` / `GM_log` 是纯前端实现，无条件提供
 *   （不少脚本不写 @grant 也在用，缺了会直接 ReferenceError）；
 * - `GM_info` / `unsafeWindow` 在所有用户脚本里都应存在，同样无条件提供；
 * - `GM_getValue` / `GM_setValue` / `GM_deleteValue` / `GM_listValues`
 *   与 `GM_openInTab` / `GM_setClipboard` 涉及原生桥，必须显式 @grant。
 *
 * 写入走 `window.LingGM.postMessage`（见 [WebTabManager] 的桥注册）。桥对象可能
 * 在低版本 WebView 上不存在，此时静默丢弃而不是抛错 —— 脚本不该因为一个
 * 可选能力缺失而整体崩掉。
 *
 * `GM_info` 只填脚本自身元数据与脚本管理器名：Tampermonkey 的 `GM_info` 还有
 * 运行环境字段，但脚本实际只读 `script.name/version` 这几项，多填反而更容易
 * 让脚本走 Tampermonkey 专属分支。
 */
private fun gmPrelude(script: UserScript, scriptId: String): String {
    fun granted(name: String): Boolean =
        script.grants.contains(name) || script.grants.contains("GM.*") || script.grants.contains("*")

    val storageGranted = granted("GM_getValue") || granted("GM_setValue") ||
        granted("GM_deleteValue") || granted("GM_listValues")

    return buildString {
        append("var SID=").append(jsString(scriptId)).append(";")
        append("function post(m){try{m.s=SID;if(window.LingGM)window.LingGM.postMessage(JSON.stringify(m))}catch(e){}}")
        // 原生 → 页面的回调分发：按脚本 id 分表，避免多个脚本的请求 id 互相撞车。
        // 定义在 window 上并做幂等判断：同一页可能注入多个脚本，只需一份分发器。
        append("var CB={};window.__lingGMCB=window.__lingGMCB||{};window.__lingGMCB[SID]=CB;")
        append("window.__lingGMDispatch=window.__lingGMDispatch||function(s,i,e,p){")
        append("var m=window.__lingGMCB&&window.__lingGMCB[s];")
        append("if(m&&m[i]){try{m[i](e,p)}catch(x){}}}")
        append("function GM_log(){try{console.log.apply(console,['[翎]'].concat([].slice.call(arguments)))}catch(e){}}")
        append("function GM_addStyle(css){var s=document.createElement('style');s.textContent=css;")
        append("(document.head||document.documentElement).appendChild(s);return s}")
        append("function GM_addElement(a,b,c){var p,t,at;")
        append("if(typeof a==='string'){p=document.head||document.documentElement;t=a;at=b||{}}")
        append("else{p=a;t=b;at=c||{}}")
        append("var e=document.createElement(t);")
        append("for(var k in at){var v=at[k];")
        append("if(k==='textContent')e.textContent=v;")
        append("else if(typeof v==='function'&&k.slice(0,2)==='on')e.addEventListener(k.slice(2),v);")
        append("else e.setAttribute(k,v)}")
        append("if(p&&p.appendChild)p.appendChild(e);return e}")
        append("var unsafeWindow=window;")
        append("var GM_info={script:{name:").append(jsString(script.name))
        append(",namespace:").append(jsString(script.namespace))
        append(",version:").append(jsString(script.version))
        append(",description:").append(jsString(script.description))
        append(",grant:[").append(script.grants.joinToString(",") { jsString(it) }).append("]}")
        append(",scriptHandler:'LingBrowser',version:'1.0'};")
        if (storageGranted) {
            append("var VL={},VLn=0;")
            append("function fire(k,o,v,r){for(var i in VL){var e=VL[i];if(e.k===k){try{e.f(k,o,v,r)}catch(x){}}}}")
            append("function GM_addValueChangeListener(k,f){var id='l'+(++VLn);VL[id]={k:String(k),f:f};return id}")
            append("function GM_removeValueChangeListener(id){try{delete VL[String(id)]}catch(e){}}")
            // 原生广播按脚本 id 分表：同页可能注入多个脚本，各自的 V 必须分开更新。
            append("window.__lingGMValueMap=window.__lingGMValueMap||{};")
            append("window.__lingGMValueMap[SID]=function(k,o,v,r){V[k]=v;fire(k,o,v,r)};")
            append("window.__lingGMValue=window.__lingGMValue||function(s,k,o,v,r){")
            append("var f=window.__lingGMValueMap&&window.__lingGMValueMap[s];if(f)f(k,o,v,r)};")
            append("function GM_getValue(k,d){return Object.prototype.hasOwnProperty.call(V,k)?V[k]:d}")
            append("function GM_setValue(k,v){var o=V[k];V[k]=v;fire(k,o,v,false);post({t:'set',k:String(k),v:v})}")
            append("function GM_deleteValue(k){k=String(k);var o=V[k];try{delete V[k]}catch(e){}")
            append("fire(k,o,undefined,false);post({t:'del',k:k})}")
            append("function GM_listValues(){return Object.keys(V)}")
        }
        // 菜单与通知不按 grant 收敛：不少脚本不写 @grant 也在用，缺了会直接
        // ReferenceError；两者都只是转发一个消息，没有额外的系统能力。
        append("var MC={},MCn=0;")
        append("function GM_registerMenuCommand(n,f){var id='m'+(++MCn);MC[id]=f;")
        append("post({t:'menu',id:id,n:String(n)});return id}")
        append("function GM_unregisterMenuCommand(id){id=String(id);try{delete MC[id]}catch(e){}")
        append("post({t:'menuDel',id:id})}")
        append("window.__lingGMMenuMap=window.__lingGMMenuMap||{};window.__lingGMMenuMap[SID]=MC;")
        append("window.__lingGMMenu=window.__lingGMMenu||function(s,id){")
        append("var m=window.__lingGMMenuMap&&window.__lingGMMenuMap[s];var f=m&&m[id];")
        append("if(f){try{f()}catch(e){try{console.error(e)}catch(_){}}}};")
        append("var NT={},NTn=0;")
        append("window.__lingGMNoticeMap=window.__lingGMNoticeMap||{};window.__lingGMNoticeMap[SID]=NT;")
        append("window.__lingGMNotice=window.__lingGMNotice||function(s,id){")
        append("var m=window.__lingGMNoticeMap&&window.__lingGMNoticeMap[s];var f=m&&m[id];")
        append("if(f){try{f()}catch(e){}}};")
        append("function GM_notification(d,t,img,oc){")
        append("if(typeof d==='string'){d={text:d,title:t,onclick:typeof img==='function'?img:oc}}")
        append("d=d||{};var id='n'+(++NTn);")
        append("if(typeof d.onclick==='function')NT[id]=d.onclick;")
        append("post({t:'notice',id:id,title:String(d.title||''),text:String(d.text||d.body||''),u:String(d.url||'')});")
        append("return id}")
        if (granted("GM_openInTab")) {
            append("function GM_openInTab(u){post({t:'open',u:String(u)})}")
        }
        if (granted("GM_setClipboard")) {
            append("function GM_setClipboard(t){post({t:'clip',v:String(t)})}")
        }
        if (granted("GM_getResourceText") || granted("GM_getResourceURL")) {
            // 资源在导入时已经抓成 base64 并随注入带进来，这里纯前端解码即可。
            // TextDecoder 不可用时退回 atob（非 UTF-8 文本会乱码，但不至于抛错）。
            append("function GM_getResourceText(n){var r=RES[n];if(!r)return null;")
            append("try{return new TextDecoder().decode(Uint8Array.from(atob(r.b),")
            append("function(c){return c.charCodeAt(0)}))}catch(e){try{return atob(r.b)}catch(x){return null}}}")
            append("function GM_getResourceURL(n){var r=RES[n];")
            append("return r?('data:'+r.m+';base64,'+r.b):null}")
        }
        if (granted("GM_xmlhttpRequest")) {
            append("function GM_xmlhttpRequest(d){d=d||{};")
            append("var i='r'+Date.now().toString(36)+Math.random().toString(36).slice(2);")
            append("CB[i]=function(e,p){")
            append("if(e==='load'){if(d.responseType==='json'){try{p.response=JSON.parse(p.responseText)}catch(x){p.response=null}}")
            append("if(d.onreadystatechange){try{d.onreadystatechange(p)}catch(x){}}if(d.onload){d.onload(p)}}")
            append("else if(e==='error'){if(d.onreadystatechange){try{d.onreadystatechange(p)}catch(x){}}")
            append("if(d.onerror){d.onerror(p)}}")
            append("else if(e==='timeout'){if(d.ontimeout){d.ontimeout(p)}}")
            append("else if(e==='abort'){if(d.onabort){d.onabort(p)}}")
            append("else if(e==='progress'){if(d.onprogress){d.onprogress(p)}}};")
            append("post({t:'xhr',id:i,m:String(d.method||'GET').toUpperCase(),u:String(d.url||''),")
            append("h:d.headers||{},d:typeof d.data==='undefined'?null:d.data,to:d.timeout||0,rt:d.responseType||''});")
            append("return{abort:function(){post({t:'xa',id:i})}}}")
        }
    }
}

/** 转成 JS 字符串字面量（JSON 转义 + U+2028/2029，后者在旧引擎里会断行）。 */
internal fun jsString(value: String): String = buildString {
    append('"')
    for (ch in value) {
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '\u2028' -> append("\\u2028")
            '\u2029' -> append("\\u2029")
            else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
        }
    }
    append('"')
}

/**
 * 判断 `GM_xmlhttpRequest` 的目标主机是否在脚本声明的 `@connect` 白名单里。
 *
 * 与 Tampermonkey 的语义对齐：
 *  - 空白名单视为不限制（老脚本大量缺失 `@connect`，逐请求确认又太吵）；
 *  - `*` 放行一切；
 *  - `self` 只允许访问当前页所在主机；
 *  - `*.example.com` / `.example.com` 与裸 `example.com` 都表示该域及其子域。
 */
internal fun connectAllowed(connects: Set<String>, targetHost: String?, pageHost: String?): Boolean {
    if (connects.isEmpty() || "*" in connects) return true
    val host = targetHost ?: return false
    for (raw in connects) {
        if (raw == "self") {
            if (pageHost != null && host == pageHost) return true
            continue
        }
        val domain = raw.removePrefix("*.").removePrefix(".")
        if (domain.isEmpty()) continue
        if (host == domain || host.endsWith(".$domain")) return true
    }
    return false
}
