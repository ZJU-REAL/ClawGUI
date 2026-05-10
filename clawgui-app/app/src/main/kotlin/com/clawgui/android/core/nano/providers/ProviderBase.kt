package com.clawgui.android.core.nano.providers

import com.clawgui.android.core.nano.utils.anyToJson
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class ToolCallRequest(
    val id: String,
    val name: String,
    val arguments: Map<String, Any?>,
    val extraContent: Map<String, Any?>? = null,
) {
    fun toOpenAiToolCall(): Map<String, Any?> {
        val args = try {
            anyToJson(arguments).toString()
        } catch (_: Exception) {
            "{}"
        }
        val result = mutableMapOf<String, Any?>(
            "id" to id,
            "type" to "function",
            "function" to mapOf("name" to name, "arguments" to args),
        )
        if (extraContent != null) result["extra_content"] = extraContent
        return result
    }
}

data class LLMResponse(
    val content: String?,
    val toolCalls: List<ToolCallRequest> = emptyList(),
    val finishReason: String = "stop",
    val usage: Map<String, Int> = emptyMap(),
    val reasoningContent: String? = null,
) {
    val hasToolCalls: Boolean get() = toolCalls.isNotEmpty()
}

data class GenerationSettings(
    val temperature: Float = 0.7f,
    val maxTokens: Int = 4096,
    val reasoningEffort: String? = null,
)

abstract class LLMProvider(
    val apiKey: String? = null,
    val apiBase: String? = null,
) {
    var generation: GenerationSettings = GenerationSettings()

    abstract suspend fun chat(
        messages: List<Map<String, Any?>>,
        tools: List<Map<String, Any?>>? = null,
        model: String? = null,
        maxTokens: Int = 4096,
        temperature: Float = 0.7f,
        reasoningEffort: String? = null,
        toolChoice: Any? = null,
    ): LLMResponse

    open suspend fun chatStream(
        messages: List<Map<String, Any?>>,
        tools: List<Map<String, Any?>>? = null,
        model: String? = null,
        maxTokens: Int = 4096,
        temperature: Float = 0.7f,
        reasoningEffort: String? = null,
        toolChoice: Any? = null,
        onContentDelta: (suspend (String) -> Unit)? = null,
    ): LLMResponse {
        val response = chat(messages, tools, model, maxTokens, temperature, reasoningEffort, toolChoice)
        if (onContentDelta != null && response.content != null) {
            onContentDelta(response.content)
        }
        return response
    }

    abstract fun getDefaultModel(): String

    companion object {
        val TRANSIENT_ERROR_MARKERS = listOf(
            "429", "rate limit", "500", "502", "503", "504",
            "overloaded", "timeout", "timed out", "connection",
            "server error", "temporarily unavailable",
        )

        fun isTransientError(content: String?): Boolean {
            val lower = (content ?: "").lowercase()
            return TRANSIENT_ERROR_MARKERS.any { it in lower }
        }

        val RETRY_DELAYS_MS = longArrayOf(1000L, 2000L, 4000L)
    }

    suspend fun chatWithRetry(
        messages: List<Map<String, Any?>>,
        tools: List<Map<String, Any?>>? = null,
        model: String? = null,
        maxTokens: Int = generation.maxTokens,
        temperature: Float = generation.temperature,
        reasoningEffort: String? = generation.reasoningEffort,
        toolChoice: Any? = null,
    ): LLMResponse {
        for (delay in RETRY_DELAYS_MS) {
            val response = safeChat(messages, tools, model, maxTokens, temperature, reasoningEffort, toolChoice)
            if (response.finishReason != "error") return response
            if (!isTransientError(response.content)) return response
            kotlinx.coroutines.delay(delay)
        }
        return safeChat(messages, tools, model, maxTokens, temperature, reasoningEffort, toolChoice)
    }

    suspend fun chatStreamWithRetry(
        messages: List<Map<String, Any?>>,
        tools: List<Map<String, Any?>>? = null,
        model: String? = null,
        maxTokens: Int = generation.maxTokens,
        temperature: Float = generation.temperature,
        reasoningEffort: String? = generation.reasoningEffort,
        toolChoice: Any? = null,
        onContentDelta: (suspend (String) -> Unit)? = null,
    ): LLMResponse {
        for (delay in RETRY_DELAYS_MS) {
            val response = safeChatStream(messages, tools, model, maxTokens, temperature, reasoningEffort, toolChoice, onContentDelta)
            if (response.finishReason != "error") return response
            if (!isTransientError(response.content)) return response
            kotlinx.coroutines.delay(delay)
        }
        return safeChatStream(messages, tools, model, maxTokens, temperature, reasoningEffort, toolChoice, onContentDelta)
    }

    private suspend fun safeChat(
        messages: List<Map<String, Any?>>,
        tools: List<Map<String, Any?>>?,
        model: String?,
        maxTokens: Int,
        temperature: Float,
        reasoningEffort: String?,
        toolChoice: Any?,
    ): LLMResponse = try {
        chat(messages, tools, model, maxTokens, temperature, reasoningEffort, toolChoice)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        LLMResponse(content = "Error calling LLM: ${e.message}", finishReason = "error")
    }

    private suspend fun safeChatStream(
        messages: List<Map<String, Any?>>,
        tools: List<Map<String, Any?>>?,
        model: String?,
        maxTokens: Int,
        temperature: Float,
        reasoningEffort: String?,
        toolChoice: Any?,
        onContentDelta: (suspend (String) -> Unit)?,
    ): LLMResponse = try {
        chatStream(messages, tools, model, maxTokens, temperature, reasoningEffort, toolChoice, onContentDelta)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        LLMResponse(content = "Error calling LLM: ${e.message}", finishReason = "error")
    }
}
