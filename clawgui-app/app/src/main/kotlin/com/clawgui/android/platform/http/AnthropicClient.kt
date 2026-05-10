package com.clawgui.android.platform.http

import com.clawgui.android.core.nano.providers.LLMProvider
import com.clawgui.android.core.nano.providers.LLMResponse
import com.clawgui.android.core.nano.providers.ToolCallRequest
import com.clawgui.android.core.nano.utils.anyToJson
import com.clawgui.android.core.nano.utils.jsonToAny
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import java.util.logging.Logger

private val JSON_CT = "application/json; charset=utf-8".toMediaType()
private val jsonParser = Json { ignoreUnknownKeys = true }
private val logger = Logger.getLogger("AnthropicClient")
private const val ANTHROPIC_VERSION = "2023-06-01"

/**
 * Minimal Anthropic Messages API client (port of Python anthropic_provider.py
 * keeping the pieces we actually need on Android: text + tool_use + thinking
 * blocks, image_url → base64/url source, system-message extraction, merge
 * consecutive same-role messages).
 *
 * Streaming currently falls back to non-streaming via the base class — the UI
 * agent loop doesn't depend on per-token delta for Brain stage.
 */
class AnthropicClient(
    apiKey: String?,
    apiBase: String?,
    private val defaultModel: String = "claude-sonnet-4-5",
) : LLMProvider(apiKey, apiBase) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    private val messagesUrl: String = run {
        val base = (apiBase ?: "https://api.anthropic.com").trimEnd('/')
        if (base.endsWith("/v1")) "$base/messages" else "$base/v1/messages"
    }

    override fun getDefaultModel(): String = defaultModel

    override suspend fun chat(
        messages: List<Map<String, Any?>>,
        tools: List<Map<String, Any?>>?,
        model: String?,
        maxTokens: Int,
        temperature: Float,
        reasoningEffort: String?,
        toolChoice: Any?,
    ): LLMResponse = withContext(Dispatchers.IO) {
        val body = buildRequestBody(
            messages = messages,
            tools = tools,
            model = stripPrefix(model ?: defaultModel),
            maxTokens = maxTokens,
            temperature = temperature,
            reasoningEffort = reasoningEffort,
            toolChoice = toolChoice,
        )
        val req = Request.Builder()
            .url(messagesUrl)
            .addHeader("x-api-key", apiKey ?: "")
            .addHeader("anthropic-version", ANTHROPIC_VERSION)
            .addHeader("content-type", "application/json")
            .post(body.toRequestBody(JSON_CT))
            .build()
        try {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val errBody = resp.body?.string() ?: ""
                    return@withContext LLMResponse(
                        content = "Error: HTTP ${resp.code}: $errBody",
                        finishReason = "error",
                    )
                }
                parseResponse(resp.body?.string() ?: "")
            }
        } catch (e: Exception) {
            logger.log(Level.WARNING, "Anthropic chat() failed", e)
            LLMResponse(content = "Error calling LLM: ${e.message}", finishReason = "error")
        }
    }

    private fun stripPrefix(model: String): String =
        if (model.startsWith("anthropic/")) model.removePrefix("anthropic/") else model

    // ------------------------------------------------------------------
    // Request body build
    // ------------------------------------------------------------------

    private fun buildRequestBody(
        messages: List<Map<String, Any?>>,
        tools: List<Map<String, Any?>>?,
        model: String,
        maxTokens: Int,
        temperature: Float,
        reasoningEffort: String?,
        toolChoice: Any?,
    ): String {
        val (system, anthropicMessages) = convertMessages(messages)
        val anthropicTools = convertTools(tools)
        val thinkingEnabled = !reasoningEffort.isNullOrBlank()

        val root = mutableMapOf<String, Any?>(
            "model" to model,
            "messages" to anthropicMessages,
            "max_tokens" to maxTokens.coerceAtLeast(1),
        )
        if (system != null) root["system"] = system

        if (thinkingEnabled) {
            val budget = when (reasoningEffort!!.lowercase()) {
                "low" -> 1024
                "medium" -> 4096
                "high" -> maxOf(8192, maxTokens)
                else -> 4096
            }
            root["thinking"] = mapOf("type" to "enabled", "budget_tokens" to budget)
            root["max_tokens"] = maxOf(maxTokens, budget + 4096)
            root["temperature"] = 1.0
        } else {
            root["temperature"] = temperature
        }

        if (!anthropicTools.isNullOrEmpty()) {
            root["tools"] = anthropicTools
            convertToolChoice(toolChoice, thinkingEnabled)?.let { root["tool_choice"] = it }
        }

        return anyToJson(root).toString()
    }

    // OpenAI-shape messages → (system, anthropic messages).
    // system = plain string if any system messages exist, else null.
    private fun convertMessages(
        messages: List<Map<String, Any?>>,
    ): Pair<String?, List<Map<String, Any?>>> {
        var system: String? = null
        val raw = mutableListOf<MutableMap<String, Any?>>()

        for (msg in messages) {
            val role = msg["role"] as? String ?: continue
            val content = msg["content"]

            when (role) {
                "system" -> {
                    system = appendSystem(system, stringifyContent(content))
                }
                "tool" -> {
                    val block = toolResultBlock(msg)
                    val last = raw.lastOrNull()
                    if (last != null && last["role"] == "user") {
                        appendBlockToUser(last, block)
                    } else {
                        raw.add(
                            mutableMapOf(
                                "role" to "user",
                                "content" to mutableListOf<Map<String, Any?>>(block),
                            )
                        )
                    }
                }
                "assistant" -> {
                    raw.add(
                        mutableMapOf(
                            "role" to "assistant",
                            "content" to assistantBlocks(msg),
                        )
                    )
                }
                "user" -> {
                    raw.add(
                        mutableMapOf(
                            "role" to "user",
                            "content" to convertUserContent(content),
                        )
                    )
                }
            }
        }

        return Pair(system?.takeIf { it.isNotBlank() }, mergeConsecutive(raw))
    }

    private fun appendSystem(existing: String?, add: String): String {
        if (add.isBlank()) return existing ?: ""
        if (existing.isNullOrBlank()) return add
        return "$existing\n\n$add"
    }

    private fun stringifyContent(content: Any?): String = when (content) {
        null -> ""
        is String -> content
        is List<*> -> content.joinToString("\n") { el ->
            if (el is Map<*, *>) (el["text"] as? String) ?: ""
            else el?.toString() ?: ""
        }
        else -> content.toString()
    }

    private fun toolResultBlock(msg: Map<String, Any?>): Map<String, Any?> {
        val rawContent = msg["content"]
        val block = mutableMapOf<String, Any?>(
            "type" to "tool_result",
            "tool_use_id" to (msg["tool_call_id"] as? String ?: ""),
        )
        block["content"] = when (rawContent) {
            is String, is List<*> -> rawContent
            null -> ""
            else -> rawContent.toString()
        }
        return block
    }

    @Suppress("UNCHECKED_CAST")
    private fun appendBlockToUser(userMsg: MutableMap<String, Any?>, block: Map<String, Any?>) {
        val prev = userMsg["content"]
        if (prev is MutableList<*>) {
            (prev as MutableList<Any?>).add(block)
            return
        }
        if (prev is List<*>) {
            val merged = mutableListOf<Any?>()
            merged.addAll(prev)
            merged.add(block)
            userMsg["content"] = merged
            return
        }
        val text = prev?.toString() ?: ""
        userMsg["content"] = mutableListOf<Map<String, Any?>>(
            mapOf("type" to "text", "text" to text),
            block,
        )
    }

    private fun assistantBlocks(msg: Map<String, Any?>): List<Map<String, Any?>> {
        val blocks = mutableListOf<Map<String, Any?>>()
        val content = msg["content"]

        // replay stored thinking blocks if present
        (msg["thinking_blocks"] as? List<*>)?.forEach { tb ->
            val t = tb as? Map<*, *> ?: return@forEach
            if (t["type"] == "thinking") {
                blocks.add(
                    mapOf(
                        "type" to "thinking",
                        "thinking" to (t["thinking"] as? String ?: ""),
                        "signature" to (t["signature"] as? String ?: ""),
                    )
                )
            }
        }

        if (content is String && content.isNotEmpty()) {
            blocks.add(mapOf("type" to "text", "text" to content))
        } else if (content is List<*>) {
            for (item in content) {
                if (item is Map<*, *>) {
                    @Suppress("UNCHECKED_CAST")
                    blocks.add(item as Map<String, Any?>)
                } else if (item != null) {
                    blocks.add(mapOf("type" to "text", "text" to item.toString()))
                }
            }
        }

        (msg["tool_calls"] as? List<*>)?.forEach { tcEl ->
            val tc = tcEl as? Map<*, *> ?: return@forEach
            val fn = tc["function"] as? Map<*, *> ?: return@forEach
            val name = fn["name"] as? String ?: return@forEach
            val args: Map<String, Any?> = when (val a = fn["arguments"]) {
                null -> emptyMap()
                is Map<*, *> -> @Suppress("UNCHECKED_CAST") (a as Map<String, Any?>)
                is String -> try {
                    if (a.isBlank()) emptyMap()
                    else @Suppress("UNCHECKED_CAST")
                    ((jsonToAny(jsonParser.parseToJsonElement(a)) as? Map<String, Any?>) ?: emptyMap())
                } catch (_: Exception) { emptyMap() }
                else -> emptyMap()
            }
            blocks.add(
                mapOf(
                    "type" to "tool_use",
                    "id" to (tc["id"] as? String ?: genToolId()),
                    "name" to name,
                    "input" to args,
                )
            )
        }

        return if (blocks.isEmpty()) listOf(mapOf("type" to "text", "text" to "")) else blocks
    }

    private fun convertUserContent(content: Any?): Any {
        if (content == null) return "(empty)"
        if (content is String) return content.ifEmpty { "(empty)" }
        if (content !is List<*>) return content.toString()

        val result = mutableListOf<Map<String, Any?>>()
        for (item in content) {
            if (item !is Map<*, *>) {
                if (item != null) result.add(mapOf("type" to "text", "text" to item.toString()))
                continue
            }
            if (item["type"] == "image_url") {
                val converted = convertImageBlock(item)
                if (converted != null) result.add(converted)
                continue
            }
            @Suppress("UNCHECKED_CAST")
            result.add(item as Map<String, Any?>)
        }
        return if (result.isEmpty()) "(empty)" else result
    }

    private fun convertImageBlock(block: Map<*, *>): Map<String, Any?>? {
        val imageUrl = block["image_url"] as? Map<*, *> ?: return null
        val url = imageUrl["url"] as? String ?: return null
        if (url.isBlank()) return null

        val dataUrlRegex = Regex("^data:(image/[A-Za-z0-9.+-]+);base64,(.+)$", RegexOption.DOT_MATCHES_ALL)
        val match = dataUrlRegex.matchEntire(url)
        if (match != null) {
            return mapOf(
                "type" to "image",
                "source" to mapOf(
                    "type" to "base64",
                    "media_type" to match.groupValues[1],
                    "data" to match.groupValues[2],
                ),
            )
        }
        return mapOf(
            "type" to "image",
            "source" to mapOf("type" to "url", "url" to url),
        )
    }

    private fun mergeConsecutive(
        msgs: List<MutableMap<String, Any?>>,
    ): List<Map<String, Any?>> {
        val merged = mutableListOf<MutableMap<String, Any?>>()
        for (msg in msgs) {
            val last = merged.lastOrNull()
            if (last != null && last["role"] == msg["role"]) {
                val prev = last["content"]
                val cur = msg["content"]
                val prevList = toBlockList(prev)
                val curList = toBlockList(cur)
                prevList.addAll(curList)
                last["content"] = prevList
            } else {
                merged.add(msg)
            }
        }
        return merged
    }

    private fun toBlockList(c: Any?): MutableList<Any?> = when (c) {
        is String -> mutableListOf<Any?>(mapOf("type" to "text", "text" to c))
        is List<*> -> c.toMutableList()
        null -> mutableListOf<Any?>(mapOf("type" to "text", "text" to ""))
        else -> mutableListOf<Any?>(mapOf("type" to "text", "text" to c.toString()))
    }

    // ------------------------------------------------------------------
    // Tool conversion
    // ------------------------------------------------------------------

    private fun convertTools(tools: List<Map<String, Any?>>?): List<Map<String, Any?>>? {
        if (tools.isNullOrEmpty()) return null
        return tools.map { tool ->
            @Suppress("UNCHECKED_CAST")
            val func = (tool["function"] as? Map<String, Any?>) ?: tool
            val entry = mutableMapOf<String, Any?>(
                "name" to (func["name"] as? String ?: ""),
                "input_schema" to (func["parameters"]
                    ?: mapOf("type" to "object", "properties" to emptyMap<String, Any?>())),
            )
            (func["description"] as? String)?.takeIf { it.isNotBlank() }?.let { entry["description"] = it }
            entry
        }
    }

    private fun convertToolChoice(toolChoice: Any?, thinkingEnabled: Boolean): Map<String, Any?>? {
        if (thinkingEnabled) return mapOf("type" to "auto")
        return when (toolChoice) {
            null, "auto" -> mapOf("type" to "auto")
            "required" -> mapOf("type" to "any")
            "none" -> null
            is Map<*, *> -> {
                val fn = toolChoice["function"] as? Map<*, *>
                val name = fn?.get("name") as? String
                if (!name.isNullOrBlank()) mapOf("type" to "tool", "name" to name)
                else mapOf("type" to "auto")
            }
            else -> mapOf("type" to "auto")
        }
    }

    // ------------------------------------------------------------------
    // Response parse
    // ------------------------------------------------------------------

    private fun parseResponse(json: String): LLMResponse {
        return try {
            val obj = jsonParser.parseToJsonElement(json) as? JsonObject
                ?: return LLMResponse(content = "Error: invalid JSON response", finishReason = "error")

            // API-level error
            (obj["error"] as? JsonObject)?.let { err ->
                val msg = (err["message"] as? JsonPrimitive)?.content ?: err.toString()
                return LLMResponse(content = "Error: $msg", finishReason = "error")
            }

            val contentArr = (obj["content"] as? JsonArray)
                ?: return LLMResponse(content = "", finishReason = "stop")

            val textParts = mutableListOf<String>()
            val toolCalls = mutableListOf<ToolCallRequest>()
            val reasoningParts = mutableListOf<String>()

            for (el in contentArr) {
                val block = el as? JsonObject ?: continue
                when ((block["type"] as? JsonPrimitive)?.content) {
                    "text" -> (block["text"] as? JsonPrimitive)?.content?.let { textParts.add(it) }
                    "tool_use" -> {
                        val id = (block["id"] as? JsonPrimitive)?.content ?: genToolId()
                        val name = (block["name"] as? JsonPrimitive)?.content ?: continue
                        val input = block["input"]
                        @Suppress("UNCHECKED_CAST")
                        val args = (jsonToAny(input ?: JsonObject(emptyMap())) as? Map<String, Any?>)
                            ?: emptyMap()
                        toolCalls.add(ToolCallRequest(id = id, name = name, arguments = args))
                    }
                    "thinking" -> (block["thinking"] as? JsonPrimitive)?.content?.let { reasoningParts.add(it) }
                }
            }

            val stopReason = (obj["stop_reason"] as? JsonPrimitive)?.content
            val finish = when (stopReason) {
                "tool_use" -> "tool_calls"
                "end_turn" -> "stop"
                "max_tokens" -> "length"
                else -> stopReason ?: "stop"
            }

            val usageObj = obj["usage"] as? JsonObject
            val usage = if (usageObj != null) {
                val input = (usageObj["input_tokens"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
                val output = (usageObj["output_tokens"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
                mapOf("prompt_tokens" to input, "completion_tokens" to output)
            } else emptyMap()

            LLMResponse(
                content = textParts.joinToString("").ifEmpty { null },
                toolCalls = toolCalls,
                finishReason = finish,
                usage = usage,
                reasoningContent = reasoningParts.joinToString("\n").ifEmpty { null },
            )
        } catch (e: Exception) {
            LLMResponse(content = "Error parsing response: ${e.message}", finishReason = "error")
        }
    }

    private fun genToolId(): String {
        val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        return "toolu_" + (1..22).map { chars.random() }.joinToString("")
    }
}
