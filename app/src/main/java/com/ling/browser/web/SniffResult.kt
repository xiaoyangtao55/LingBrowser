package com.ling.browser.web

/**
 * 资源嗅探结果。
 *
 * 把 JS 返回的 JSON 解析成类型化对象，好处与 [ReaderResult] 相同：
 * 可以在单元测试里断言，"没嗅到任何东西"与"脚本出错"能分开处理。
 *
 * 嗅探结果里有一条资源数组，每条是 `{ type, url, title }`。因为
 * `org.json` 在本地 JVM 单测里是空壳（抛 `Stub!`），这里继续手写
 * 一个只解析本场景所需形态的迷你解析器（顶层扁平对象 + 数组 + 三个字段）。
 */
sealed interface SniffResult {

    /**
     * 嗅探成功。[resources] 可能为空 —— 空表示"这页没有可嗅探的媒体"，
     * 不是错误，UI 应温和提示而不是报错。
     */
    data class Ok(val resources: List<Resource>) : SniffResult

    /** 脚本自身出错。[reason] 用于排查。 */
    data class Error(val reason: String) : SniffResult

    /** 一条嗅探到的资源。 */
    data class Resource(
        val type: Type,
        val url: String,
        val title: String,
    ) {
        enum class Type { VIDEO, AUDIO, IMAGE, DOWNLOAD }
    }

    companion object {

        fun parse(raw: String?): SniffResult {
            if (raw == null || raw.isBlank() || raw == "null") {
                return Error("页面尚未就绪")
            }
            val json = unquote(raw).trim()
            if (!json.startsWith("{")) return Error("返回值不是对象")

            val status = field(json, "status").orEmpty()
            return when (status) {
                ResourceSnifferJs.Status.OK -> {
                    val arr = arrayField(json, "resources")
                        ?: return Error("缺少 resources 字段")
                    Ok(arr.mapNotNull { parseResource(it) })
                }
                else -> Error(field(json, "reason").orEmpty().ifBlank { "unknown" })
            }
        }

        /**
         * 解析 `{ type, url, title }` 对象为 [Resource]。
         *
         * type 无法识别或 url 缺失时返回 null（跳过该条）——
         * 一条坏数据不该毁掉整份结果。
         */
        private fun parseResource(obj: String): Resource? {
            val url = field(obj, "url")?.trim().orEmpty()
            if (url.isEmpty()) return null
            val type = field(obj, "type")?.trim()?.lowercase().orEmpty()
            val t = when (type) {
                "video" -> Resource.Type.VIDEO
                "audio" -> Resource.Type.AUDIO
                "image" -> Resource.Type.IMAGE
                "download" -> Resource.Type.DOWNLOAD
                else -> return null
            }
            return Resource(t, url, field(obj, "title")?.trim().orEmpty())
        }

        /**
         * 取出 `"key":[...]` 的数组字面量（不含首尾方括号）。
         *
         * 只支持本场景形态：数组元素是 `{...}` 对象，元素之间用逗号分隔。
         * 对象内部不会有嵌套数组/对象（字段都是字符串），因此按
         * "花括号配对"切分是安全的。
         */
        internal fun arrayField(json: String, key: String): List<String>? {
            val needle = "\"$key\""
            var i = json.indexOf(needle)
            if (i < 0) return null
            i += needle.length
            while (i < json.length && json[i] != ':') i++
            if (i >= json.length) return null
            i++
            while (i < json.length && json[i].isWhitespace()) i++
            if (i >= json.length || json[i] != '[') return null
            i++ // 跳过 '['
            val items = mutableListOf<String>()
            var closed = false
            while (i < json.length) {
                while (i < json.length && json[i].isWhitespace()) i++
                if (i >= json.length) break
                if (json[i] == ']') { closed = true; break }
                // 期望一个 '{'
                if (json[i] != '{') return null
                val start = i
                var depth = 0
                while (i < json.length) {
                    val c = json[i]
                    if (c == '{') depth++
                    else if (c == '}') {
                        depth--
                        if (depth == 0) { i++; break }
                    }
                    i++
                }
                if (depth != 0) return null // 括号不配对
                items += json.substring(start, i)
                while (i < json.length && json[i].isWhitespace()) i++
                if (i < json.length && json[i] == ',') i++
            }
            // 走到字符串末尾还没遇到 ']'，说明数组没闭合 —— 是坏数据
            if (!closed) return null
            return items
        }

        /**
         * 取出 `"key":"value"` 的字符串值。与 [ReaderResult.field] 同一套
         * 转义规则，但这里单独维护一份：两个解析器面向不同的 JSON 形态，
         * 强行复用会让"改动一处影响另一处"的耦合高于省下的几十行代码。
         */
        internal fun field(json: String, key: String): String? {
            val needle = "\"$key\""
            var i = json.indexOf(needle)
            if (i < 0) return null
            i += needle.length
            while (i < json.length && json[i] != ':') i++
            if (i >= json.length) return null
            i++
            while (i < json.length && json[i].isWhitespace()) i++
            if (i >= json.length) return null

            if (json[i] != '"') {
                val start = i
                while (i < json.length && json[i] != ',' && json[i] != '}') i++
                return json.substring(start, i).trim().ifEmpty { null }
            }

            i++
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
                                val hex = json.substring(i + 2, minOf(i + 6, json.length))
                                val code = hex.toIntOrNull(16)
                                if (code != null && hex.length == 4) {
                                    sb.append(code.toChar()); i += 6
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
            return sb.toString()
        }

        /**
         * 去掉 `evaluateJavascript` 给字符串值套上的那层引号。
         * 逻辑与 [ReaderResult] 相同：只在确实被引号包裹时才处理。
         */
        private fun unquote(raw: String): String {
            val t = raw.trim()
            if (t.length < 2 || !t.startsWith("\"") || !t.endsWith("\"")) return t
            return field("{\"v\":$t}", "v") ?: t
        }
    }
}
