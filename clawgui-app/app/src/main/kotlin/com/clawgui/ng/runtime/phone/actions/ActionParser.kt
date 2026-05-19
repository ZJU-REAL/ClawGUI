package com.clawgui.ng.runtime.phone.actions

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.doubleOrNull

private val JSON_PARSER = Json { ignoreUnknownKeys = true }
private val TOOL_CALL_RE = Regex("""<tool_call>\s*(.*?)(?:\s*</tool_call>|$)""", setOf(RegexOption.DOT_MATCHES_ALL))
private val XML_TAG_RE = Regex("""</?(answer|think|thought)>""")
private val TEXT_MATCH_RE = Regex("""text=(["'])((?:(?!\1)[^\\]|\\.)*)(\1)""")

object ActionParser {

    fun parse(rawResponse: String): Map<String, Any?> {
        var s = rawResponse.trim()
            .replace("```python", "").replace("```", "")
            .trim('\n', '\r', '\t', ' ')
            .trimEnd('`', '\n', '\r', '\t', ' ')

        s = XML_TAG_RE.replace(s, "").trim()

        // 1. <tool_call> format (MAI-UI / QwenVL)
        if ("<tool_call>" in s) {
            TOOL_CALL_RE.find(s)?.let { m ->
                val tc = m.groupValues[1].trim()
                    .replace("\\n", "").trim()
                    .replace(Regex("</?tool_call[^>]*$"), "").trim()
                try {
                    val json = JSON_PARSER.parseToJsonElement(tc)
                    if (json is JsonObject && json.containsKey("arguments")) {
                        val args = json["arguments"]
                        if (args is JsonObject) {
                            val actionType = (args["action"] as? JsonPrimitive)?.content ?: ""
                            if (actionType in listOf("terminate", "answer")) {
                                val msg = ((args["text"] ?: args["status"]) as? JsonPrimitive)?.content ?: "completed"
                                return mapOf<String, Any?>(
                                    "_metadata" to "finish",
                                    "message" to msg,
                                )
                            }
                            val result = mutableMapOf<String, Any?>("_metadata" to "do")
                            for ((k, v) in args) result[k] = jsonElementToAny(v)
                            return result
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        // 2. JSON dict format
        if (s.startsWith("{")) {
            try {
                val json = JSON_PARSER.parseToJsonElement(s)
                if (json is JsonObject && (json.containsKey("_metadata") || json.containsKey("action"))) {
                    val result = mutableMapOf<String, Any?>()
                    for ((k, v) in json) result[k] = jsonElementToAny(v)
                    if ("_metadata" !in result) result["_metadata"] = "do"
                    return result
                }
            } catch (_: Exception) {}
        }

        // Drop any natural-language prefix the model put before the actual
        // command. Models occasionally prepend "执行操作:" / "Launch 微信:"
        // even though the prompt says no — strip everything before the
        // first `do(` / `finish(` token so the dispatch below works.
        // We use the *first* token here (not last) because <answer> blocks
        // generally hold exactly one command and any later `do(` would be
        // weird; the parser-level lastIndexOf trick lives in
        // AutoGLMAdapter.parseResponse, which has the broader response
        // context to disambiguate.
        run {
            val doIdx = s.indexOf("do(")
            val finIdx = s.indexOf("finish(")
            val firstCmd = when {
                doIdx < 0 -> finIdx
                finIdx < 0 -> doIdx
                else -> minOf(doIdx, finIdx)
            }
            if (firstCmd > 0) s = s.substring(firstCmd)
        }

        // 3. Type / Type_Name (special regex path to handle complex text)
        if (s.startsWith("do(action=\"Type\"") || s.startsWith("do(action=\"Type_Name\"") ||
            s.startsWith("do(action='Type'") || s.startsWith("do(action='Type_Name'")
        ) {
            TEXT_MATCH_RE.find(s)?.let { m ->
                val text = m.groupValues[2]
                    .replace("\\\"", "\"").replace("\\'", "'")
                    .replace("\\n", "\n").replace("\\t", "\t")
                val actionType = if ("Type_Name" in s) "Type_Name" else "Type"
                return mapOf("_metadata" to "do", "action" to actionType, "text" to text)
            }
        }

        // 4. do(...) call
        if (s.startsWith("do(") || s.startsWith("do (")) {
            val paren = s.indexOf('(')
            val args = parseCallArgs(s.substring(paren))
            return mapOf("_metadata" to "do") + args
        }

        // 5. finish(...) call
        if (s.startsWith("finish(") || s.startsWith("finish (")) {
            val paren = s.indexOf('(')
            val args = parseCallArgs(s.substring(paren))
            return mapOf("_metadata" to "finish") + args
        }

        throw IllegalArgumentException("Failed to parse action: $s")
    }

    // Exposed for unit testing
    internal fun parseCallArgs(s: String): Map<String, Any?> {
        if (!s.startsWith("(")) return emptyMap()
        val lastParen = s.lastIndexOf(')')
        val inner = if (lastParen > 0) s.substring(1, lastParen).trim() else s.substring(1).trim()
        if (inner.isEmpty()) return emptyMap()
        val pairs = mutableListOf<Pair<String, Any?>>()
        for (part in splitTopLevel(inner, ',')) {
            val eq = part.indexOf('=')
            if (eq < 0) continue
            val key = part.substring(0, eq).trim()
            val value = parseLiteral(part.substring(eq + 1).trim())
            pairs.add(key to value)
        }
        return pairs.toMap()
    }

    internal fun parseLiteral(s: String): Any? {
        val t = s.trim()
        // String literal (single or double quotes)
        if ((t.startsWith("\"") && t.endsWith("\"")) ||
            (t.startsWith("'") && t.endsWith("'"))
        ) {
            return t.substring(1, t.length - 1)
                .replace("\\\"", "\"").replace("\\'", "'")
                .replace("\\n", "\n").replace("\\t", "\t").replace("\\\\", "\\")
        }
        // List
        if (t.startsWith("[") && t.endsWith("]")) {
            val inner = t.substring(1, t.length - 1).trim()
            if (inner.isEmpty()) return emptyList<Any?>()
            return splitTopLevel(inner, ',').map { parseLiteral(it.trim()) }
        }
        return when (t) {
            "True" -> true
            "False" -> false
            "None" -> null
            else -> t.toIntOrNull() ?: t.toDoubleOrNull() ?: t
        }
    }

    private fun splitTopLevel(s: String, delimiter: Char): List<String> {
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
                c == delimiter && depth == 0 -> {
                    parts += s.substring(start, i).trim()
                    start = i + 1
                }
            }
            i++
        }
        parts += s.substring(start).trim()
        return parts
    }

    private fun jsonElementToAny(el: kotlinx.serialization.json.JsonElement): Any? = when (el) {
        is JsonNull -> null
        is JsonPrimitive -> when {
            el.isString -> el.content
            else -> el.booleanOrNull ?: el.intOrNull ?: el.longOrNull ?: el.doubleOrNull ?: el.content
        }
        is JsonArray -> el.map { jsonElementToAny(it) }
        is JsonObject -> el.mapValues { jsonElementToAny(it.value) }
    }
}
