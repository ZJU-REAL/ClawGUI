package com.clawgui.android.core.nano.utils

import com.knuddels.jtokkit.Encodings
import com.knuddels.jtokkit.api.EncodingType
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

private val tokenRegistry by lazy { Encodings.newDefaultEncodingRegistry() }
private val cl100kEncoding by lazy { tokenRegistry.getEncoding(EncodingType.CL100K_BASE) }

private val THINK_RE = Regex("""<think>[\s\S]*?</think>""")
private val THINK_OPEN_RE = Regex("""<think>[\s\S]*$""")
private val UNSAFE_CHARS_RE = Regex("""[<>:"/\\|?*]""")

fun stripThink(text: String): String {
    var result = THINK_RE.replace(text, "")
    result = THINK_OPEN_RE.replace(result, "")
    return result.trim()
}

fun detectImageMime(data: ByteArray): String? {
    if (data.size >= 8 && data.sliceArray(0..7).contentEquals(
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        )
    ) return "image/png"
    if (data.size >= 3 && data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte() && data[2] == 0xFF.toByte())
        return "image/jpeg"
    if (data.size >= 6 && (data.sliceArray(0..5).contentEquals("GIF87a".toByteArray()) ||
            data.sliceArray(0..5).contentEquals("GIF89a".toByteArray()))
    ) return "image/gif"
    if (data.size >= 12 && data.sliceArray(0..3).contentEquals("RIFF".toByteArray()) &&
        data.sliceArray(8..11).contentEquals("WEBP".toByteArray())
    ) return "image/webp"
    return null
}

fun buildImageContentBlocks(raw: ByteArray, mime: String, path: String, label: String): List<Map<String, Any?>> {
    val b64 = java.util.Base64.getEncoder().encodeToString(raw)
    return listOf(
        mapOf(
            "type" to "image_url",
            "image_url" to mapOf("url" to "data:$mime;base64,$b64"),
            "_meta" to mapOf("path" to path),
        ),
        mapOf("type" to "text", "text" to label),
    )
}

fun timestamp(): String = Clock.System.now().toString()

fun currentTimeStr(timezone: String? = null): String {
    val tz = if (timezone != null) {
        try { TimeZone.of(timezone) } catch (_: Exception) { TimeZone.currentSystemDefault() }
    } else TimeZone.currentSystemDefault()
    val now = Clock.System.now().toLocalDateTime(tz)
    val weekdays = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
    val weekday = weekdays[now.dayOfWeek.isoDayNumber - 1]
    val tzName = timezone ?: tz.id
    return "%04d-%02d-%02d %02d:%02d (%s) (%s)".format(
        now.year, now.monthNumber, now.dayOfMonth,
        now.hour, now.minute, weekday, tzName,
    )
}

fun safeFilename(name: String): String = UNSAFE_CHARS_RE.replace(name, "_").trim()

fun splitMessage(content: String, maxLen: Int = 2000): List<String> {
    if (content.isEmpty()) return emptyList()
    if (content.length <= maxLen) return listOf(content)
    val chunks = mutableListOf<String>()
    var remaining = content
    while (remaining.isNotEmpty()) {
        if (remaining.length <= maxLen) { chunks.add(remaining); break }
        val cut = remaining.substring(0, maxLen)
        var pos = cut.lastIndexOf('\n').takeIf { it > 0 }
            ?: cut.lastIndexOf(' ').takeIf { it > 0 }
            ?: maxLen
        chunks.add(remaining.substring(0, pos))
        remaining = remaining.substring(pos).trimStart()
    }
    return chunks
}

fun buildAssistantMessage(
    content: String?,
    toolCalls: List<Map<String, Any?>>? = null,
    reasoningContent: String? = null,
): Map<String, Any?> {
    val msg = mutableMapOf<String, Any?>("role" to "assistant", "content" to content)
    if (!toolCalls.isNullOrEmpty()) msg["tool_calls"] = toolCalls
    if (reasoningContent != null) msg["reasoning_content"] = reasoningContent
    return msg
}

fun estimatePromptTokens(
    messages: List<Map<String, Any?>>,
    tools: List<Map<String, Any?>>? = null,
): Int {
    return try {
        val parts = mutableListOf<String>()
        for (msg in messages) {
            when (val content = msg["content"]) {
                is String -> if (content.isNotEmpty()) parts.add(content)
                is List<*> -> for (part in content) {
                    if (part is Map<*, *> && part["type"] == "text") {
                        val txt = part["text"] as? String
                        if (!txt.isNullOrEmpty()) parts.add(txt)
                    }
                }
            }
            val tc = msg["tool_calls"]
            if (tc != null) parts.add(tc.toString())
            val rc = msg["reasoning_content"] as? String
            if (!rc.isNullOrEmpty()) parts.add(rc)
            for (key in listOf("name", "tool_call_id")) {
                val v = msg[key] as? String
                if (!v.isNullOrEmpty()) parts.add(v)
            }
        }
        if (tools != null) parts.add(tools.toString())
        val text = parts.joinToString("\n")
        val perMsgOverhead = messages.size * 4
        cl100kEncoding.countTokensOrdinary(text) + perMsgOverhead
    } catch (_: Exception) {
        0
    }
}

fun estimateMessageTokens(message: Map<String, Any?>): Int {
    return try {
        val parts = mutableListOf<String>()
        when (val content = message["content"]) {
            is String -> parts.add(content)
            is List<*> -> for (part in content) {
                if (part is Map<*, *> && part["type"] == "text") {
                    val txt = part["text"] as? String
                    if (!txt.isNullOrEmpty()) parts.add(txt)
                } else if (part != null) {
                    parts.add(part.toString())
                }
            }
            null -> {}
            else -> parts.add(content.toString())
        }
        for (key in listOf("name", "tool_call_id")) {
            val v = message[key] as? String
            if (!v.isNullOrEmpty()) parts.add(v)
        }
        if (message["tool_calls"] != null) parts.add(message["tool_calls"].toString())
        val rc = message["reasoning_content"] as? String
        if (!rc.isNullOrEmpty()) parts.add(rc)
        val payload = parts.joinToString("\n")
        if (payload.isEmpty()) return 4
        maxOf(4, cl100kEncoding.countTokensOrdinary(payload) + 4)
    } catch (_: Exception) {
        maxOf(4, (message["content"]?.toString()?.length ?: 0) / 4 + 4)
    }
}

fun buildStatusContent(
    version: String,
    model: String,
    startTimeMs: Long,
    lastUsage: Map<String, Int>,
    contextWindowTokens: Int,
    sessionMsgCount: Int,
    contextTokensEstimate: Int,
): String {
    val uptimeS = ((System.currentTimeMillis() - startTimeMs) / 1000).toInt()
    val uptime = if (uptimeS >= 3600) "${uptimeS / 3600}h ${(uptimeS % 3600) / 60}m"
    else "${uptimeS / 60}m ${uptimeS % 60}s"
    val lastIn = lastUsage["prompt_tokens"] ?: 0
    val lastOut = lastUsage["completion_tokens"] ?: 0
    val ctxTotal = maxOf(contextWindowTokens, 0)
    val ctxPct = if (ctxTotal > 0) (contextTokensEstimate * 100 / ctxTotal) else 0
    val ctxUsed = if (contextTokensEstimate >= 1000) "${contextTokensEstimate / 1000}k" else "$contextTokensEstimate"
    val ctxTotalStr = if (ctxTotal > 0) "${ctxTotal / 1024}k" else "n/a"
    return listOf(
        "nanobot v$version",
        "Model: $model",
        "Tokens: $lastIn in / $lastOut out",
        "Context: $ctxUsed/$ctxTotalStr ($ctxPct%)",
        "Session: $sessionMsgCount messages",
        "Uptime: $uptime",
    ).joinToString("\n")
}
