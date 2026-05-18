package com.clawgui.ng.runtime.phone.model

import com.clawgui.ng.runtime.phone.model.adapters.ModelAdapter
import com.clawgui.ng.runtime.phone.model.adapters.adapterForModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private val JSON_CT = "application/json; charset=utf-8".toMediaType()

data class ModelConfig(
    val baseUrl: String = "https://open.bigmodel.cn/api/paas/v4",
    val apiKey: String = "",
    val modelName: String = "glm-4v-plus",
    val maxTokens: Int = 3000,
    val temperature: Float = 0.0f,
    val lang: String = "cn",
)

class ModelClient(private val config: ModelConfig = ModelConfig()) {

    /** 根据配置里的模型名选对应的 VLM 适配器;由 PhoneAgent 读取。 */
    val adapter: ModelAdapter = adapterForModel(config.modelName)

    private val httpClient = OkHttpClient.Builder()
        // VLM with screenshot upload — first token can take 30-90s on cold paths.
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)          // disable read-idle timeout, latch governs total wait
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .pingInterval(20, TimeUnit.SECONDS)             // keep SSE connection alive across NATs
        .build()

    /**
     * Issue one chat-completions request. Streams via SSE, accumulates the
     * `content` deltas, and decodes the model's `<think>/<answer>` output.
     *
     * Failures are wrapped into [Exception] with a human-readable label —
     * timeout / network / HTTP — so the caller can surface it. We retry the
     * whole request once on transient failures (timeout, connection drop).
     */
    suspend fun request(messages: List<Map<String, Any?>>): ModelResponse = withContext(Dispatchers.IO) {
        var lastError: String? = null
        repeat(2) { attempt ->
            try {
                return@withContext doRequest(messages)
            } catch (t: Throwable) {
                lastError = t.message ?: t.javaClass.simpleName
                // Only retry on transient classes; HTTP 4xx is fatal.
                val transient = lastError!!.contains("timeout", ignoreCase = true) ||
                        lastError!!.contains("reset", ignoreCase = true) ||
                        lastError!!.contains("closed", ignoreCase = true) ||
                        lastError!!.contains("Connection", ignoreCase = true) ||
                        lastError!!.contains("Empty response", ignoreCase = true)
                if (!transient || attempt == 1) throw t
            }
        }
        throw Exception(lastError ?: "Unknown API failure")
    }

    private fun doRequest(messages: List<Map<String, Any?>>): ModelResponse {
        val startMs = System.currentTimeMillis()
        val body = buildBody(messages)
        val req = Request.Builder()
            .url("${config.baseUrl.trimEnd('/')}/chat/completions")
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .post(body.toRequestBody(JSON_CT))
            .build()

        val rawContent = StringBuilder()
        var timeToFirstToken: Float? = null
        var errorMsg: String? = null
        val latch = CountDownLatch(1)

        val listener = object : EventSourceListener() {
            override fun onEvent(source: EventSource, id: String?, type: String?, data: String) {
                if (data == "[DONE]") { latch.countDown(); return }
                try {
                    val delta = extractDelta(data) ?: return
                    if (timeToFirstToken == null) {
                        timeToFirstToken = (System.currentTimeMillis() - startMs) / 1000f
                    }
                    rawContent.append(delta)
                } catch (_: Exception) {}
            }
            override fun onClosed(source: EventSource) { latch.countDown() }
            override fun onFailure(source: EventSource, t: Throwable?, response: okhttp3.Response?) {
                val respBody = try { response?.body?.string()?.take(500) } catch (_: Exception) { null }
                val code = response?.code
                errorMsg = when {
                    code != null && code in 400..599 -> "HTTP $code: ${respBody.orEmpty()}"
                    t is java.net.SocketTimeoutException -> "请求超时(${t.message ?: ""}) — 智谱响应慢或网络不稳,稍后再试"
                    t is java.net.UnknownHostException -> "无法解析域名:${t.message ?: ""}"
                    t is java.io.IOException -> "网络异常:${t.message ?: t.javaClass.simpleName}"
                    t != null -> "${t.javaClass.simpleName}: ${t.message ?: ""}"
                    else -> "未知 SSE 失败"
                }
                latch.countDown()
            }
        }

        val source = EventSources.createFactory(httpClient).newEventSource(req, listener)
        // Wait up to 240s — that's plenty for VLM + screenshot on a slow link.
        val finished = latch.await(240, TimeUnit.SECONDS)
        source.cancel()

        if (!finished && errorMsg == null) {
            throw Exception("请求超时(等待 240 秒未完成),请检查网络或换网络试试")
        }
        if (errorMsg != null) throw Exception("API request failed: $errorMsg")
        if (rawContent.isEmpty()) throw Exception("Empty response from API — 检查 API Key 是否正确、是否有该模型权限")

        val totalTime = (System.currentTimeMillis() - startMs) / 1000f
        val raw = rawContent.toString()
        val (thinking, action) = adapter.parseResponse(raw)

        return ModelResponse(
            thinking = thinking,
            action = action,
            rawContent = raw,
            timeToFirstToken = timeToFirstToken,
            totalTime = totalTime,
        )
    }

    private fun extractDelta(sseData: String): String? {
        val obj = try {
            kotlinx.serialization.json.Json.parseToJsonElement(sseData) as? kotlinx.serialization.json.JsonObject
        } catch (_: Exception) { null } ?: return null
        val choices = (obj["choices"] as? kotlinx.serialization.json.JsonArray) ?: return null
        val choice0 = (choices.firstOrNull() as? kotlinx.serialization.json.JsonObject) ?: return null
        val delta = (choice0["delta"] as? kotlinx.serialization.json.JsonObject) ?: return null
        return (delta["content"] as? kotlinx.serialization.json.JsonPrimitive)?.content
    }

    private fun buildBody(messages: List<Map<String, Any?>>): String {
        val root = mapOf(
            "model" to config.modelName,
            "messages" to messages,
            "max_tokens" to config.maxTokens,
            "temperature" to config.temperature,
            "stream" to true,
            "stream_options" to mapOf("include_usage" to true),
        )
        return com.clawgui.ng.runtime.agent.utils.anyToJson(root).toString()
    }
}
