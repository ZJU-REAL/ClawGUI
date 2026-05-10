package com.clawgui.android.platform.http

import com.clawgui.android.core.nano.providers.LLMProvider
import com.clawgui.android.core.nano.providers.LLMResponse
import com.clawgui.android.core.nano.providers.ToolCallRequest
import com.clawgui.android.core.nano.utils.jsonToAny
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import java.util.logging.Logger

private val JSON_CT = "application/json; charset=utf-8".toMediaType()
private val jsonParser = Json { ignoreUnknownKeys = true }
private val logger = Logger.getLogger("OpenAICompatClient")

private fun shortId(): String {
    val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    return (1..9).map { chars.random() }.joinToString("")
}

private fun repairAndParseArgs(raw: Any?): Map<String, Any?> = when (raw) {
    null -> emptyMap()
    is Map<*, *> -> @Suppress("UNCHECKED_CAST") (raw as Map<String, Any?>)
    is String -> {
        if (raw.isBlank()) emptyMap()
        else try {
            @Suppress("UNCHECKED_CAST")
            (jsonToAny(jsonParser.parseToJsonElement(raw)) as? Map<String, Any?>) ?: emptyMap()
        } catch (_: Exception) { emptyMap() }
    }
    else -> emptyMap()
}

class OpenAICompatClient(
    apiKey: String?,
    apiBase: String?,
    private val defaultModel: String = "glm-4v-plus",
) : LLMProvider(apiKey, apiBase) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val effectiveBase = (apiBase ?: "https://open.bigmodel.cn/api/paas/v4/")
        .trimEnd('/')

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
        val body = buildRequestBody(messages, tools, model ?: defaultModel, maxTokens, temperature, toolChoice, stream = false)
        val req = Request.Builder()
            .url("$effectiveBase/chat/completions")
            .addHeader("Authorization", "Bearer ${apiKey ?: ""}")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody(JSON_CT))
            .build()
        try {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val errBody = resp.body?.string() ?: ""
                    return@withContext LLMResponse(content = "Error: HTTP ${resp.code}: $errBody", finishReason = "error")
                }
                parseNonStreamResponse(resp.body?.string() ?: "")
            }
        } catch (e: Exception) {
            logger.log(Level.WARNING, "chat() failed", e)
            LLMResponse(content = "Error calling LLM: ${e.message}", finishReason = "error")
        }
    }

    override suspend fun chatStream(
        messages: List<Map<String, Any?>>,
        tools: List<Map<String, Any?>>?,
        model: String?,
        maxTokens: Int,
        temperature: Float,
        reasoningEffort: String?,
        toolChoice: Any?,
        onContentDelta: (suspend (String) -> Unit)?,
    ): LLMResponse = withContext(Dispatchers.IO) {
        val body = buildRequestBody(messages, tools, model ?: defaultModel, maxTokens, temperature, toolChoice, stream = true)
        val req = Request.Builder()
            .url("$effectiveBase/chat/completions")
            .addHeader("Authorization", "Bearer ${apiKey ?: ""}")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .post(body.toRequestBody(JSON_CT))
            .build()

        val chunks = mutableListOf<String>()
        val latch = CountDownLatch(1)
        var sseError: String? = null

        val listener = object : EventSourceListener() {
            override fun onEvent(source: EventSource, id: String?, type: String?, data: String) {
                if (data == "[DONE]") { latch.countDown(); return }
                chunks.add(data)
                // Fire onContentDelta synchronously from the SSE reader thread;
                // callback is non-blocking (just appends to UI buffer)
                try {
                    val delta = extractDeltaContent(data)
                    if (delta.isNotEmpty() && onContentDelta != null) {
                        kotlinx.coroutines.runBlocking { onContentDelta(delta) }
                    }
                } catch (_: Exception) {}
            }
            override fun onClosed(source: EventSource) { latch.countDown() }
            override fun onFailure(source: EventSource, t: Throwable?, response: okhttp3.Response?) {
                sseError = t?.message ?: response?.let { "HTTP ${it.code}" }
                latch.countDown()
            }
        }
        val source = EventSources.createFactory(httpClient).newEventSource(req, listener)
        latch.await(120, TimeUnit.SECONDS)
        source.cancel()

        if (sseError != null) {
            return@withContext LLMResponse(content = "Error: SSE stream failed: $sseError", finishReason = "error")
        }
        parseStreamChunks(chunks)
    }

    private fun extractDeltaContent(sseData: String): String {
        return try {
            val obj = jsonParser.parseToJsonElement(sseData) as? JsonObject ?: return ""
            val choices = (obj["choices"] as? JsonArray) ?: return ""
            val choice0 = (choices.firstOrNull() as? JsonObject) ?: return ""
            val delta = (choice0["delta"] as? JsonObject) ?: return ""
            (delta["content"] as? JsonPrimitive)?.content ?: ""
        } catch (_: Exception) { "" }
    }

    private fun parseNonStreamResponse(json: String): LLMResponse {
        return try {
            val obj = jsonParser.parseToJsonElement(json) as? JsonObject
                ?: return LLMResponse(content = "Error: invalid JSON response", finishReason = "error")
            val choices = (obj["choices"] as? JsonArray) ?: return LLMResponse(content = "Error: empty choices", finishReason = "error")
            val choice0 = (choices.firstOrNull() as? JsonObject) ?: return LLMResponse(content = "Error: empty choices", finishReason = "error")
            val message = (choice0["message"] as? JsonObject) ?: return LLMResponse(content = "", finishReason = "stop")
            val content = (message["content"] as? JsonPrimitive)?.content
            val finishReason = (choice0["finish_reason"] as? JsonPrimitive)?.content ?: "stop"
            val reasoningContent = (message["reasoning_content"] as? JsonPrimitive)?.content
            val toolCalls = parseToolCallsFromMessage(message)
            val usage = parseUsage(obj)
            LLMResponse(
                content = content,
                toolCalls = toolCalls,
                finishReason = finishReason,
                usage = usage,
                reasoningContent = reasoningContent,
            )
        } catch (e: Exception) {
            LLMResponse(content = "Error parsing response: ${e.message}", finishReason = "error")
        }
    }

    private fun parseStreamChunks(chunks: List<String>): LLMResponse {
        val contentParts = mutableListOf<String>()
        val tcBufs = mutableMapOf<Int, MutableMap<String, Any?>>()
        var finishReason = "stop"
        val usage = mutableMapOf<String, Int>()

        for (chunk in chunks) {
            try {
                val obj = jsonParser.parseToJsonElement(chunk) as? JsonObject ?: continue
                val choices = obj["choices"] as? JsonArray
                if (choices == null) {
                    // usage-only chunk
                    parseUsage(obj).forEach { (k, v) -> usage[k] = v }
                    continue
                }
                val choice0 = (choices.firstOrNull() as? JsonObject) ?: continue
                (choice0["finish_reason"] as? JsonPrimitive)?.content?.let {
                    if (it != "null") finishReason = it
                }
                val delta = (choice0["delta"] as? JsonObject) ?: continue
                (delta["content"] as? JsonPrimitive)?.content?.let { contentParts.add(it) }
                (delta["tool_calls"] as? JsonArray)?.forEachIndexed { idx, tcEl ->
                    val tc = tcEl as? JsonObject ?: return@forEachIndexed
                    val i = (tc["index"] as? JsonPrimitive)?.content?.toIntOrNull() ?: idx
                    val buf = tcBufs.getOrPut(i) {
                        mutableMapOf("id" to "", "name" to "", "arguments" to "")
                    }
                    (tc["id"] as? JsonPrimitive)?.content?.let { if (it.isNotEmpty()) buf["id"] = it }
                    (tc["function"] as? JsonObject)?.let { fn ->
                        (fn["name"] as? JsonPrimitive)?.content?.let { if (it.isNotEmpty()) buf["name"] = it }
                        (fn["arguments"] as? JsonPrimitive)?.content?.let { buf["arguments"] = (buf["arguments"] as? String ?: "") + it }
                    }
                }
            } catch (_: Exception) {}
        }

        val toolCalls = tcBufs.values.mapNotNull { buf ->
            val name = buf["name"] as? String ?: return@mapNotNull null
            if (name.isEmpty()) return@mapNotNull null
            ToolCallRequest(
                id = (buf["id"] as? String)?.takeIf { it.isNotEmpty() } ?: shortId(),
                name = name,
                arguments = repairAndParseArgs(buf["arguments"]),
            )
        }
        return LLMResponse(
            content = contentParts.joinToString("").ifEmpty { null },
            toolCalls = toolCalls,
            finishReason = finishReason,
            usage = usage,
        )
    }

    private fun parseToolCallsFromMessage(message: JsonObject): List<ToolCallRequest> {
        val rawTcs = message["tool_calls"] as? JsonArray ?: return emptyList()
        return rawTcs.mapNotNull { tcEl ->
            val tc = tcEl as? JsonObject ?: return@mapNotNull null
            val fn = (tc["function"] as? JsonObject) ?: return@mapNotNull null
            val name = (fn["name"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val argsRaw = (fn["arguments"] as? JsonPrimitive)?.content ?: "{}"
            ToolCallRequest(
                id = (tc["id"] as? JsonPrimitive)?.content ?: shortId(),
                name = name,
                arguments = repairAndParseArgs(argsRaw),
            )
        }
    }

    private fun parseUsage(obj: JsonObject): Map<String, Int> {
        val u = (obj["usage"] as? JsonObject) ?: return emptyMap()
        return mapOf(
            "prompt_tokens" to ((u["prompt_tokens"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0),
            "completion_tokens" to ((u["completion_tokens"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0),
        )
    }

    private fun buildRequestBody(
        messages: List<Map<String, Any?>>,
        tools: List<Map<String, Any?>>?,
        model: String,
        maxTokens: Int,
        temperature: Float,
        toolChoice: Any?,
        stream: Boolean,
    ): String {
        val root = mutableMapOf<String, Any?>(
            "model" to model,
            "messages" to messages,
            "max_tokens" to maxTokens,
            "temperature" to temperature,
            "stream" to stream,
        )
        if (!tools.isNullOrEmpty()) {
            root["tools"] = tools
            root["tool_choice"] = toolChoice ?: "auto"
        }
        if (stream) root["stream_options"] = mapOf("include_usage" to true)
        return com.clawgui.android.core.nano.utils.anyToJson(root).toString()
    }
}
