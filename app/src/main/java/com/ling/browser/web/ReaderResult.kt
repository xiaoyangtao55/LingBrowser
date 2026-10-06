package com.ling.browser.web

/**
 * 正文提取结果。
 *
 * 把 JS 返回的 JSON 解析成类型化对象，好处是**可以在单元测试里断言** ——
 * 直接传字符串给界面的话，"没找到正文"与"脚本出错"就分不开了。
 */
sealed interface ReaderResult {

    /** 提取成功。[html] 是正文片段，[textLength] 用于判断内容是否够充实。 */
    data class Ok(
        val title: String,
        val site: String,
        val url: String,
        val html: String,
        val textLength: Int,
    ) : ReaderResult

    /**
     * 页面不适合阅读模式（首页、图片站、视频页都属此类）。
     *
     * 这不是错误：用户点了阅读模式但没有正文可读，给一句温和的提示即可。
     * [reason] 保留原始原因便于排查，不直接展示给用户。
     */
    data class NoContent(val reason: String) : ReaderResult

    /** 脚本自身出错。[reason] 用于排查。 */
    data class Error(val reason: String) : ReaderResult

    companion object {
        /**
         * 解析 `evaluateJavascript` 回调拿到的字符串。
         *
         * 需要处理的输入形态比想象中多，都会在真机上遇到：
         *  - `null`：页面还没加载完 / JS 被设置关掉了 —— 最常见
         *  - 带引号的 JSON 字符串：`evaluateJavascript` 返回的是 JS 值，
         *    字符串类型会**再套一层引号并转义**
         *  - 空串 `""`：JS 执行了但没有 return
         *  - 真正的解析失败
         *
         * 任何一种都不能抛异常：这是 UI 路径上的调用，崩了就是用户点一下闪退。
         */
        fun parse(raw: String?): ReaderResult {
            if (raw == null || raw.isBlank() || raw == "null") {
                return Error("页面尚未就绪")
            }
            val json = unquote(raw)
            val body = json.trim()
            if (!body.startsWith("{")) return Error("返回值不是对象")

            val status = field(body, "status").orEmpty()
            return when (status) {
                ReaderExtractorJs.Status.OK -> {
                    val html = field(body, "html").orEmpty()
                    // status 说 ok 但正文是空的 —— 不能只信状态码，
                    // 否则会渲染出一个只有标题的空页面，看起来像 bug。
                    if (html.isBlank()) {
                        NoContent("empty_html")
                    } else {
                        Ok(
                            title = field(body, "title").orEmpty().trim(),
                            site = field(body, "site").orEmpty().trim(),
                            url = field(body, "url").orEmpty(),
                            html = html,
                            textLength = field(body, "textLength")?.toIntOrNull() ?: 0,
                        )
                    }
                }
                ReaderExtractorJs.Status.NO_CONTENT ->
                    NoContent(field(body, "reason").orEmpty())
                else ->
                    Error(field(body, "reason").orEmpty().ifBlank { "unknown" })
            }
        }

        /**
         * 取出 `"key":"value"` 的字符串值，正确处理转义。
         *
         * 为什么不用 `org.json.JSONObject`：它在**本地 JVM 单元测试里是空壳**，
         * 任何方法调用都抛 `Stub!` —— 也就是说这部分逻辑将完全无法测试，
         * 而这恰恰是最容易出错、也最需要测试的地方（字符串转义）。
         * 自己解析只用几十行，还能少引一个依赖。
         *
         * 只支持本场景需要的形态：顶层扁平对象、字符串与数字值。
         * 正文 HTML 里的引号与换行**必定是转义过的**（JSON 规范要求），
         * 因此按转义规则逐字符扫描是安全的。
         */
        internal fun field(json: String, key: String): String? {
            val needle = "\"$key\""
            var i = json.indexOf(needle)
            if (i < 0) return null
            i += needle.length
            // 跳过冒号与空白
            while (i < json.length && json[i] != ':') i++
            if (i >= json.length) return null
            i++
            while (i < json.length && json[i].isWhitespace()) i++
            if (i >= json.length) return null

            // 非字符串值（数字、true/false/null）直接读到分隔符
            if (json[i] != '"') {
                val start = i
                while (i < json.length && json[i] != ',' && json[i] != '}') i++
                val raw = json.substring(start, i).trim()
                return raw.ifEmpty { null }
            }

            i++ // 跳过开引号
            val sb = StringBuilder()
            while (i < json.length) {
                val c = json[i]
                when {
                    c == '\\' && i + 1 < json.length -> {
                        when (val esc = json[i + 1]) {
                            '"' -> { sb.append('"'); i += 2 }
                            '\\' -> { sb.append('\\'); i += 2 }
                            '/' -> { sb.append('/'); i += 2 }
                            'n' -> { sb.append('\n'); i += 2 }
                            'r' -> { sb.append('\r'); i += 2 }
                            't' -> { sb.append('\t'); i += 2 }
                            'b' -> { sb.append('\b'); i += 2 }
                            'f' -> { sb.append('\u000C'); i += 2 }
                            'u' -> {
                                // \uXXXX：转不出合法值就原样保留，
                                // 宁可显示得奇怪也不要丢掉正文
                                val hex = json.substring(i + 2, minOf(i + 6, json.length))
                                val code = hex.toIntOrNull(16)
                                if (code != null && hex.length == 4) {
                                    sb.append(code.toChar())
                                    i += 6
                                } else {
                                    sb.append(esc); i += 2
                                }
                            }
                            else -> { sb.append(esc); i += 2 }
                        }
                    }
                    c == '"' -> return sb.toString()
                    else -> { sb.append(c); i++ }
                }
            }
            // 引号没闭合：返回已解析部分，总比丢掉整篇正文好
            return sb.toString()
        }

        /**
         * 去掉 `evaluateJavascript` 给字符串值套上的那层引号。
         *
         * 原生回调拿到的是 JSON 编码的 JS 值：脚本 return 一个字符串，
         * 回调收到的形如 `"{\"status\":\"ok\"}"`（首尾带引号、内部转义）。
         * 直接解析会失败。
         *
         * 只在**确实被引号包裹**时才处理：有些 WebView 实现直接给裸 JSON，
         * 无条件剥引号会把 `{"a":1}` 误伤。
         */
        private fun unquote(raw: String): String {
            val t = raw.trim()
            if (t.length < 2 || !t.startsWith("\"") || !t.endsWith("\"")) return t
            return field("{\"v\":$t}", "v") ?: t
        }
    }
}
