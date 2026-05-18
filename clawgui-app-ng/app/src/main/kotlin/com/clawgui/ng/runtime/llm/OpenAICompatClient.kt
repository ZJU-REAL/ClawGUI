package com.clawgui.ng.runtime.llm

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Skinny client for OpenAI-compatible chat APIs — works against:
 *   • 智谱 (open.bigmodel.cn/api/paas/v4)
 *   • OpenAI proper
 *   • Ollama, vLLM, anything that talks /chat/completions
 *
 * Supports both non-streaming (return a single LLMResponse) and SSE streaming
 * (emit incremental deltas as Flow<StreamEvent>).
 */
class OpenAICompatClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val sseFactory = EventSources.createFactory(http)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Non-streaming completion. */
    suspend fun complete(messages: List<Message>): String =
        suspendCancellableCoroutine { cont ->
            val request = buildRequest(messages, stream = false)
            val call = http.newCall(request)
            cont.invokeOnCancellation { runCatching { call.cancel() } }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                    cont.resumeWithException(e)
                }
                override fun onResponse(call: okhttp3.Call, response: Response) {
                    response.use { resp ->
                        val body = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful) {
                            cont.resumeWithException(
                                LLMException("HTTP ${resp.code}: ${body.take(400)}")
                            )
                            return
                        }
                        val text = runCatching {
                            json.parseToJsonElement(body).jsonObject["choices"]
                                ?.jsonArray?.firstOrNull()?.jsonObject
                                ?.get("message")?.jsonObject
                                ?.get("content")?.jsonPrimitive?.content
                        }.getOrNull().orEmpty()
                        cont.resume(text)
                    }
                }
            })
        }

    /** SSE streaming completion — each text delta surfaces as Delta. */
    fun stream(messages: List<Message>): Flow<StreamEvent> = callbackFlow {
        val request = buildRequest(messages, stream = true)
        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data == "[DONE]") {
                    trySend(StreamEvent.Done)
                    close()
                    return
                }
                runCatching {
                    val obj = json.parseToJsonElement(data).jsonObject
                    val choice = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                    val delta = choice?.get("delta")?.jsonObject
                    val content = delta?.get("content")?.jsonPrimitive?.content
                    if (!content.isNullOrEmpty()) {
                        trySend(StreamEvent.Delta(content))
                    }
                    val finish = choice?.get("finish_reason")?.jsonPrimitive?.content
                    if (!finish.isNullOrBlank()) {
                        trySend(StreamEvent.Done)
                        close()
                    }
                }
            }
            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val msg = t?.message ?: "HTTP ${response?.code}: ${response?.body?.string()?.take(400)}"
                trySend(StreamEvent.Error(msg))
                close()
            }
            override fun onClosed(eventSource: EventSource) { close() }
        }
        val src = sseFactory.newEventSource(request, listener)
        awaitClose { src.cancel() }
    }

    private fun buildRequest(messages: List<Message>, stream: Boolean): Request {
        val payload: JsonObject = buildJsonObject {
            put("model", model)
            put("stream", stream)
            put("messages", buildJsonArray {
                messages.forEach { m -> add(m.toJson()) }
            })
        }
        val base = baseUrl.trimEnd('/')
        val url = if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
        return Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", if (stream) "text/event-stream" else "application/json")
            .post(json.encodeToString(JsonObject.serializer(), payload).toRequestBody(JSON_MEDIA))
            .build()
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}

/** A chat message understood by both the OpenAI- and ZhiPu-compatible APIs. */
data class Message(val role: String, val content: String) {
    fun toJson(): JsonObject = buildJsonObject {
        put("role", role)
        put("content", content)
    }
}

sealed interface StreamEvent {
    data class Delta(val text: String) : StreamEvent
    data object Done : StreamEvent
    data class Error(val message: String) : StreamEvent
}

class LLMException(message: String) : RuntimeException(message)
