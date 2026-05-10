package com.clawgui.android.core.phone.model.adapters

import com.clawgui.android.core.phone.config.PromptsZh
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object MessageBuilder {

    fun createSystemMessage(content: String): Map<String, Any?> =
        mapOf("role" to "system", "content" to content)

    fun createUserMessage(text: String, imageBase64: String? = null): Map<String, Any?> {
        val content = mutableListOf<Map<String, Any?>>()
        if (imageBase64 != null) {
            content.add(mapOf(
                "type" to "image_url",
                "image_url" to mapOf("url" to "data:image/png;base64,$imageBase64"),
            ))
        }
        content.add(mapOf("type" to "text", "text" to text))
        return mapOf("role" to "user", "content" to content)
    }

    fun createAssistantMessage(content: String): Map<String, Any?> =
        mapOf("role" to "assistant", "content" to content)

    fun buildScreenInfo(currentApp: String): String =
        buildJsonObject { put("current_app", currentApp) }.toString()

    fun removeImagesFromMessage(message: Map<String, Any?>): Map<String, Any?> {
        val content = message["content"]
        if (content is List<*>) {
            val filtered = content.filterIsInstance<Map<*, *>>().filter { it["type"] == "text" }
            return message + mapOf("content" to filtered)
        }
        return message
    }
}

object AutoGLMAdapter : ModelAdapter {

    override val name: String = "autoglm"

    override fun parseResponse(response: String): Pair<String, String> {
        if ("finish(message=" in response) {
            val parts = response.split("finish(message=", limit = 2)
            return parts[0].trim() to "finish(message=${parts[1]}"
        }
        if ("do(action=" in response) {
            val parts = response.split("do(action=", limit = 2)
            return parts[0].trim() to "do(action=${parts[1]}"
        }
        if ("<answer>" in response) {
            val parts = response.split("<answer>", limit = 2)
            val thinking = parts[0]
                .replace("<think>", "").replace("</think>", "").trim()
            val action = parts[1].replace("</answer>", "").trim()
            return thinking to action
        }
        return "" to response
    }

    override fun buildMessages(
        task: String,
        imageBase64: String,
        currentApp: String,
        context: List<Map<String, Any?>>,
        lang: String,
    ): List<Map<String, Any?>> {
        val messages = context.toMutableList()
        if (messages.isEmpty()) {
            messages.add(MessageBuilder.createSystemMessage(PromptsZh.getSystemPrompt()))
            val screenInfo = MessageBuilder.buildScreenInfo(currentApp)
            messages.add(MessageBuilder.createUserMessage("$task\n\n$screenInfo", imageBase64))
        } else {
            val screenInfo = MessageBuilder.buildScreenInfo(currentApp)
            messages.add(MessageBuilder.createUserMessage("** Screen Info **\n\n$screenInfo", imageBase64))
        }
        return messages
    }
}

fun detectModelType(modelName: String): String {
    val lower = modelName.lowercase()
    return when {
        Regex("gui[-_]?owl|guiowl").containsMatchIn(lower) -> "guiowl"
        Regex("ui[-_]?tars|tars|doubao.*ui|seed").containsMatchIn(lower) -> "uitars"
        Regex("qwen.*vl|qwen2\\.?5.*vl|qwen3.*vl|qwen3\\.?5").containsMatchIn(lower) -> "qwenvl"
        Regex("mai[-_]?ui|mai[-_]?mobile").containsMatchIn(lower) -> "maiui"
        else -> "autoglm"
    }
}
