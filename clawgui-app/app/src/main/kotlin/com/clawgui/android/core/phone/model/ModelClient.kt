package com.clawgui.android.core.phone.model

import com.clawgui.android.core.phone.model.adapters.ModelAdapter
import com.clawgui.android.core.phone.model.adapters.adapterForModel
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
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    suspend fun request(messages: List<Map<String, Any?>>): ModelResponse = withContext(Dispatchers.IO) {
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
                val body = try { response?.body?.string()?.take(500) } catch (_: Exception) { null }
                val code = response?.code
                errorMsg = when {
                    body != null -> "HTTP $code: $body"
                    t != null -> t.message ?: t.javaClass.simpleName
                    else -> "Unknown SSE failure"
                }
                latch.countDown()
            }
        }

        val source = EventSources.createFactory(httpClient).newEventSource(req, listener)
        latch.await(120, TimeUnit.SECONDS)
        source.cancel()

        if (errorMsg != null) throw Exception("API request failed: $errorMsg")
        if (rawContent.isEmpty()) throw Exception("Empty response from API — check API key and endpoint")

        val totalTime = (System.currentTimeMillis() - startMs) / 1000f
        val raw = rawContent.toString()
        val (thinking, action) = adapter.parseResponse(raw)

        ModelResponse(
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
        return com.clawgui.android.core.nano.utils.anyToJson(root).toString()
    }
}
