package com.clawgui.ng.runtime.phone.model.adapters

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * Qwen-VL / MAI-UI / GUI-Owl 都走 `<tool_call>{"name":"mobile_use","arguments":{...}}</tool_call>`
 * 或直接的 `{"action":"click","coordinate":[x,y]}` 裸 JSON。
 *
 * 这里把它翻译成 AutoGLM 风格的 `do(action="Tap", element=[x,y])`
 * / `finish(message="...")`,让现有 ActionParser 不动一行。
 */
object ToolCallNormalizer {

    private val JSON_PARSER = Json { ignoreUnknownKeys = true; isLenient = true }

    fun normalize(tcBody: String): String {
        // tcBody 可能是 `{"name":"mobile_use","arguments":{...}}` 或仅 arguments 裸对象
        val cleaned = tcBody.replace("\\n", "").trim()
            .replace(Regex("""</?tool_call[^>]*$"""), "")
            .trim()
        if (cleaned.isEmpty()) return ""

        val args: JsonObject = try {
            val parsed = JSON_PARSER.parseToJsonElement(cleaned) as? JsonObject ?: return cleaned
            (parsed["arguments"] as? JsonObject) ?: parsed
        } catch (_: Exception) {
            return cleaned
        }

        val action = (args["action"] as? JsonPrimitive)?.content ?: return cleaned
        return when (action) {
            "click" -> {
                val (x, y) = pointArg(args["coordinate"]) ?: return "do(action=\"Tap\")"
                "do(action=\"Tap\", element=[$x, $y])"
            }
            "long_press" -> {
                val (x, y) = pointArg(args["coordinate"]) ?: return "do(action=\"Long Press\")"
                "do(action=\"Long Press\", element=[$x, $y])"
            }
            "type" -> {
                val text = (args["text"] as? JsonPrimitive)?.content ?: ""
                "do(action=\"Type\", text=${quote(text)})"
            }
            "swipe" -> {
                val start = pointArg(args["coordinate"]) ?: (500 to 500)
                val endExplicit = pointArg(args["coordinate2"])
                val (ex, ey) = endExplicit ?: swipeEndpoint(
                    start.first,
                    start.second,
                    (args["direction"] as? JsonPrimitive)?.content ?: "down",
                )
                "do(action=\"Swipe\", start=[${start.first}, ${start.second}], end=[$ex, $ey])"
            }
            "drag" -> {
                val s = pointArg(args["start_coordinate"]) ?: (500 to 500)
                val e = pointArg(args["end_coordinate"]) ?: (500 to 500)
                "do(action=\"Swipe\", start=[${s.first}, ${s.second}], end=[${e.first}, ${e.second}])"
            }
            "open", "open_app" -> {
                val app = (args["app_name"] as? JsonPrimitive)?.content
                    ?: (args["text"] as? JsonPrimitive)?.content ?: ""
                "do(action=\"Launch\", app=${quote(app)})"
            }
            "system_button" -> {
                val btn = (args["button"] as? JsonPrimitive)?.content?.lowercase() ?: "back"
                when (btn) {
                    "home" -> "do(action=\"Home\")"
                    "back" -> "do(action=\"Back\")"
                    "enter" -> "do(action=\"Type\", text=\"\\n\")"
                    else -> "do(action=\"Back\")"
                }
            }
            "key" -> {
                val text = (args["text"] as? JsonPrimitive)?.content?.lowercase() ?: ""
                when {
                    "home" in text -> "do(action=\"Home\")"
                    "back" in text -> "do(action=\"Back\")"
                    else -> "do(action=\"Wait\", duration=\"1 seconds\")"
                }
            }
            "wait" -> {
                val t = (args["time"] as? JsonPrimitive)?.intOrNull ?: 1
                "do(action=\"Wait\", duration=\"$t seconds\")"
            }
            "terminate" -> {
                val status = (args["status"] as? JsonPrimitive)?.content ?: "success"
                "finish(message=${quote(status)})"
            }
            "answer" -> {
                val text = (args["text"] as? JsonPrimitive)?.content ?: ""
                "finish(message=${quote(text)})"
            }
            "interact" -> {
                val text = (args["text"] as? JsonPrimitive)?.content ?: "User intervention required"
                "do(action=\"Take_over\", message=${quote(text)})"
            }
            else -> cleaned
        }
    }

    private fun pointArg(el: JsonElement?): Pair<Int, Int>? {
        if (el !is JsonArray || el.size < 2) return null
        val x = (el[0] as? JsonPrimitive)?.intOrNull ?: return null
        val y = (el[1] as? JsonPrimitive)?.intOrNull ?: return null
        return x to y
    }

    private fun swipeEndpoint(x: Int, y: Int, dir: String): Pair<Int, Int> = when (dir.lowercase()) {
        "up" -> x to (y - 300).coerceAtLeast(0)
        "down" -> x to (y + 300).coerceAtMost(999)
        "left" -> (x - 300).coerceAtLeast(0) to y
        "right" -> (x + 300).coerceAtMost(999) to y
        else -> x to (y + 300).coerceAtMost(999)
    }

    private fun quote(s: String): String {
        val escaped = s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\t", "\\t")
        return "\"$escaped\""
    }
}
