package com.clawgui.android.core.phone.model.adapters

import com.clawgui.android.core.phone.config.prompts.PromptsUitars

/**
 * Doubao-1.5-UI-TARS 适配器。
 *
 * 输出格式:
 * ```
 * Thought: ...
 * Action: click(point='<point>500 600</point>')
 * ```
 *
 * 解析出来的 action 字符串统一翻译成 AutoGLM 的 `do(action=..)` / `finish(message=..)`
 * 形式,让现有 ActionParser / ActionHandler 通吃。
 */
class UITarsAdapter : ModelAdapter {

    override val name: String = "uitars"

    override fun buildMessages(
        task: String,
        imageBase64: String,
        currentApp: String,
        context: List<Map<String, Any?>>,
        lang: String,
    ): List<Map<String, Any?>> {
        val messages = context.toMutableList()
        if (messages.isEmpty()) {
            val sys = PromptsUitars.systemPrompt(task, lang)
            messages.add(
                mapOf(
                    "role" to "user",
                    "content" to listOf(
                        mapOf("type" to "text", "text" to "$sys\n\nTask: $task"),
                        mapOf(
                            "type" to "image_url",
                            "image_url" to mapOf("url" to "data:image/png;base64,$imageBase64"),
                        ),
                    ),
                )
            )
        } else {
            messages.add(
                mapOf(
                    "role" to "user",
                    "content" to listOf(
                        mapOf(
                            "type" to "image_url",
                            "image_url" to mapOf("url" to "data:image/png;base64,$imageBase64"),
                        ),
                    ),
                )
            )
        }
        return messages
    }

    override fun parseResponse(response: String): Pair<String, String> {
        var thinking = ""
        var rawAction = ""
        for (rawLine in response.trim().split('\n')) {
            val line = rawLine.trim()
            if (line.startsWith("Thought:")) thinking = line.removePrefix("Thought:").trim()
            else if (line.startsWith("Action:")) rawAction = line.removePrefix("Action:").trim()
        }
        if (rawAction.isEmpty()) {
            rawAction = extractFirstCall(response) ?: ""
        }
        val normalized = normalize(rawAction)
        return thinking to normalized
    }

    /** 在多行文本里找到第一个 `name(...)` 调用(括号配对),截取整段。 */
    private fun extractFirstCall(text: String): String? {
        val re = Regex("""(click|long_press|type|scroll|open_app|drag|press_home|press_back|finished|wait)\s*\(""")
        val m = re.find(text) ?: return null
        val start = m.range.first
        var depth = 0
        for (i in start until text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }

    /** 把 UI-TARS 原生 action 文本翻译成 AutoGLM `do(...)` / `finish(...)` 格式。 */
    internal fun normalize(action: String): String {
        val s = action.trim()
        if (s.isEmpty()) return ""
        val head = s.substringBefore('(').trim()
        val args = parseKwArgs(s)
        return when (head) {
            "click" -> {
                val (x, y) = pointArg(args["point"]) ?: return "do(action=\"Tap\")"
                "do(action=\"Tap\", element=[$x, $y])"
            }
            "long_press" -> {
                val (x, y) = pointArg(args["point"]) ?: return "do(action=\"Long Press\")"
                "do(action=\"Long Press\", element=[$x, $y])"
            }
            "type" -> {
                val content = (args["content"] ?: "").toString()
                "do(action=\"Type\", text=${quote(content)})"
            }
            "scroll" -> {
                val (x, y) = pointArg(args["point"]) ?: (500 to 500)
                val dir = (args["direction"] ?: "down").toString().lowercase()
                val (ex, ey) = scrollEndpoint(x, y, dir)
                "do(action=\"Swipe\", start=[$x, $y], end=[$ex, $ey])"
            }
            "open_app" -> {
                val app = (args["app_name"] ?: args["app"] ?: "").toString()
                "do(action=\"Launch\", app=${quote(app)})"
            }
            "drag" -> {
                val start = pointArg(args["start_point"]) ?: (500 to 500)
                val end = pointArg(args["end_point"]) ?: (500 to 500)
                "do(action=\"Swipe\", start=[${start.first}, ${start.second}], end=[${end.first}, ${end.second}])"
            }
            "press_home" -> "do(action=\"Home\")"
            "press_back" -> "do(action=\"Back\")"
            "wait" -> {
                val t = (args["time"] ?: "1").toString()
                "do(action=\"Wait\", duration=\"$t seconds\")"
            }
            "finished" -> {
                val content = (args["content"] ?: "").toString()
                "finish(message=${quote(content)})"
            }
            else -> s // 未识别的,原样交给 ActionParser 碰运气
        }
    }

    /** 解析 `foo(k1='v1', k2="v2", k3=[100, 200])` 的参数 —— UI-TARS 的 point
     *  是 `'<point>x y</point>'` 格式的字符串,先按 kw=literal 抽出来。 */
    private fun parseKwArgs(call: String): Map<String, String> {
        val open = call.indexOf('(')
        val close = call.lastIndexOf(')')
        if (open < 0 || close < open) return emptyMap()
        val inner = call.substring(open + 1, close).trim()
        if (inner.isEmpty()) return emptyMap()
        val parts = splitTopLevel(inner)
        val out = mutableMapOf<String, String>()
        for (p in parts) {
            val eq = p.indexOf('=')
            if (eq < 0) continue
            val k = p.substring(0, eq).trim()
            var v = p.substring(eq + 1).trim()
            if ((v.startsWith("'") && v.endsWith("'")) || (v.startsWith("\"") && v.endsWith("\""))) {
                v = v.substring(1, v.length - 1)
                v = v.replace("\\'", "'").replace("\\\"", "\"")
                    .replace("\\n", "\n").replace("\\t", "\t")
            }
            out[k] = v
        }
        return out
    }

    private fun splitTopLevel(s: String): List<String> {
        val parts = mutableListOf<String>()
        var depth = 0
        var inString = false
        var stringChar = ' '
        var start = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val escaped = i > 0 && s[i - 1] == '\\'
            when {
                inString -> if (c == stringChar && !escaped) inString = false
                c == '"' || c == '\'' -> { inString = true; stringChar = c }
                c == '[' || c == '(' -> depth++
                c == ']' || c == ')' -> if (depth > 0) depth--
                c == ',' && depth == 0 -> {
                    parts += s.substring(start, i).trim()
                    start = i + 1
                }
            }
            i++
        }
        parts += s.substring(start).trim()
        return parts
    }

    /** 从 `'<point>500 600</point>'` 或 `'[500, 600]'` 抽出 (x, y)。 */
    private fun pointArg(raw: String?): Pair<Int, Int>? {
        if (raw.isNullOrBlank()) return null
        val re = Regex("""(-?\d+)""")
        val nums = re.findAll(raw).map { it.value.toInt() }.toList()
        if (nums.size < 2) return null
        return nums[0] to nums[1]
    }

    private fun scrollEndpoint(x: Int, y: Int, dir: String): Pair<Int, Int> = when (dir) {
        "up" -> x to (y - 300).coerceAtLeast(0)
        "down" -> x to (y + 300).coerceAtMost(999)
        "left" -> (x - 300).coerceAtLeast(0) to y
        "right" -> (x + 300).coerceAtMost(999) to y
        else -> x to y
    }

    private fun quote(s: String): String {
        val escaped = s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\t", "\\t")
        return "\"$escaped\""
    }
}
