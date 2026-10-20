package com.ling.browser.web

/**
 * 订阅名单的解析。
 *
 * 名单从"APK 内置"升级成"可订阅"之后，各家写法的差异就成了必须处理的事。
 * 这里把三种常见写法**归一成同一种东西 —— 裸域名集合**：
 *
 *  1. **Hosts 格式**：`0.0.0.0 ad.example.com` / `127.0.0.1 mm.admob.com`。
 *     只取第二个字段（域名），IP 字段直接扔掉 —— 我们不做 DNS 劫持，
 *     语义只是"见到这个域名的请求就拦"。域名在前、IP 在后的写法很少见，
 *     但真出现时整行会解析失败，宁可漏拦也不能把 IP 当域名存进去。
 *  2. **AdGuard domains-only**：一行一个裸域名 `example.org`。
 *  3. **AdGuard 域名规则**：`||example.org^`（去掉 `||` 与 `^` 后同上）。
 *
 * ## 一个必须说清的语义差别
 *
 * `||example.org^` 在 AdGuard 里表示"example.org 及其全部子域名"，与我们的
 * 匹配方式天然一致（见 [AdBlocker] 的 host 逐级缩短查表）。而 **domains-only
 * 的裸域名在 AdGuard 里只拦该域名本身**，收进来之后会连带拦掉它的子域名 ——
 * 匹配面变宽了。这是刻意保留的：内置表就是这个语义，若为 domains-only 单独
 * 做一套"精确匹配"，就会出现"订阅来的规则和手输规则行为不一样"这种更难解释
 * 的现象。
 *
 * ## 有意不支持的写法
 *
 * 路径规则（`/ads/…`）、元素隐藏（`##`）、例外白名单（`@@`）都会**跳过**并计入
 * [ParseResult.skipped]：前两者需要一套完全不同的匹配引擎；后者意味着"能临时
 * 放行某个域名"，那是另一个功能（且会与内置表打架）。静默丢掉而不是报错，
 * 因为一份大名单里混着各种写法是常态 —— 但条数要如实回报，否则用户看到
 * "导入了 11 万条却只生效一点"会以为功能坏了。
 *
 * 全是纯函数，不碰 Android API，因此能在本地 JVM 里把各种脏输入盖全。
 */
object BlockRules {

    /**
     * 单个订阅源的读取上限。
     *
     * 定 8 MiB：一份 11 万条的 domains-only 名单（anti-AD）约 2 MB，
     * 留 4 倍余量；再大就该怀疑这不是一份域名名单了。
     */
    const val MAX_RULE_BYTES = 8 * 1024 * 1024

    /**
     * 规则条数上限。
     *
     * 11 万条域名在内存里约 5～6 MB，再多就该考虑换存储结构而不是继续
     * 往里塞 —— 更重要的是防止一份畸形名单（比如重复上百万行）把内存吃光。
     */
    const val MAX_RULES = 200_000

    /** 解析结果。条数一律如实回报，UI 才能说清"导入了多少、丢了多少"。 */
    data class ParseResult(
        /** 归一化后的裸域名集合（已去重）。 */
        val domains: Set<String>,
        /** 文件总行数（含空行与注释行），用来算"这份文件到底多大"。 */
        val lines: Int,
        /** 被跳过的行数：注释之外的非域名写法（路径规则、元素规则、例外、脏数据）。 */
        val skipped: Int,
        /** 是否因为超出 [MAX_RULES] 被截断。 */
        val truncated: Boolean,
    )

    /**
     * 元素隐藏类规则的标记：`##`（隐藏）、`#@#`（取消隐藏）、`#?#`（CSS 判断）、
     * `#$#`（snippet）、`#%#`（JS 注入）。
     *
     * ⚠️ 这个判断**必须在剥行内注释之前**：`example.com##.ad` 是一条第
     * **元素级**规则，若先按 `#` 截断就会得到 `example.com` —— 一个"把整个
     * 站点封死"的域名规则。这是最容易埋进去的一颗雷。
     */
    private val COSMETIC_MARKER = Regex("""#(?:@|\?|[$%])?#""")

    /** 字段分隔：Hosts 文件里空格与制表符混用是常态。 */
    private val WHITESPACE = Regex("""\s+""")

    /**
     * 解析一份订阅内容。
     *
     * @param text 名单原文（Hosts / domains-only / 域名规则混排都行）。
     * @param maxRules 条数上限，超出即截断并在结果里标出。
     */
    fun parse(text: String, maxRules: Int = MAX_RULES): ParseResult {
        val domains = HashSet<String>()
        var lines = 0
        var skipped = 0
        var truncated = false

        // BOM 必须显式去掉：`\uFEFF` 不是空白字符，String.trim() 动不了它，
        // 留着会让首行的注释判断（`line[0] == '#'`）失效。
        for (rawLine in text.removePrefix(BOM).lineSequence()) {
            lines++
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            val first = line[0]
            // 注释：`#`（Hosts）、`!`（ABP/AdGuard）、`[`（[Adblock Plus 2.0] 头）、`;`
            if (first == '#' || first == '!' || first == '[' || first == ';') continue
            // `//` 也算注释：有些名单用它写说明，而域名不可能以 `//` 开头
            if (line.startsWith("//")) continue
            // 例外白名单（`@@||example.com^`）：我们不支持放行规则
            if (line.startsWith("@@")) {
                skipped++
                continue
            }
            // 元素隐藏等规则：必须在剥 `#` 注释之前判断，否则会误当成域名
            if (COSMETIC_MARKER.containsMatchIn(line)) {
                skipped++
                continue
            }

            // 行内注释与 ABP 修饰符（`$third-party` 等）。域名里不可能出现这三个字符
            var body = line
                .substringBefore('#')
                .substringBefore('!')
                .substringBefore('$')
                .trim()
            // 路径规则（`/ads/*`、`||x.com/path`）需要另一套引擎；这里跳过而不是
            // 截到域名 —— 否则一条"只拦某路径"的规则会变成封死整个站点。
            if (body.isEmpty() || body.contains('/')) {
                skipped++
                continue
            }

            // `||domain^` -> `domain`
            if (body.startsWith("||")) body = body.removePrefix("||")
            body = body.trim('|', '^').trim()

            // 两个及以上字段 = Hosts 格式，第二个字段才是域名
            val fields = body.split(WHITESPACE)
            val candidate = if (fields.size >= 2) fields[1] else fields[0]

            val domain = normalizeDomain(candidate)
            if (domain == null) {
                skipped++
                continue
            }

            if (domains.size >= maxRules) {
                truncated = true
                skipped++
                continue
            }
            domains.add(domain)
        }

        return ParseResult(domains, lines, skipped, truncated)
    }

    /**
     * 把单个候选字段归一化成裸域名，失败返回 null。
     *
     * 这里承担的是"这一行到底是不是域名"的全部判断，所以宁可严一点：
     * 漏掉一条规则的代价是漏拦一个广告，误收一条的代价可能是**整站打不开**。
     */
    internal fun normalizeDomain(raw: String): String? {
        var s = raw.trim().lowercase()
        if (s.isEmpty()) return null

        // `*.example.com`：我们的匹配本来就连带兜住子域名，去掉 `*.` 即可
        while (s.startsWith("*.")) s = s.removePrefix("*.")
        // 前导点 `.example.com` 在 domains-only 名单里表示"含子域名"，同理去掉；
        // 末尾的点（FQDN 写法 `example.com.`）也一并去掉
        s = s.trimStart('.').trimEnd('.')

        // 端口：Hosts 名单里偶有 `example.com:8080`
        s = s.substringBefore(':')

        if (s.isEmpty() || s.length > 253 || !s.contains('.')) return null
        // 纯数字与点：IPv4、以及 Hosts 文件里的 `0.0.0.0` 占位行，都不是域名
        if (s.all { it.isDigit() || it == '.' }) return null

        for (label in s.split('.')) {
            if (label.isEmpty() || label.length > 63) return null
            if (label.startsWith('-') || label.endsWith('-')) return null
            // 只认 ASCII：WebView 给出的 host 本身就是 punycode（`xn--…`），
            // 收下 Unicode 域名只会得到一条永远匹配不上的死规则
            if (!label.all { it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }) {
                return null
            }
        }
        return s
    }

    private const val BOM = "\uFEFF"
}
