package com.clawgui.ng.runtime.phone.model.adapters

import com.clawgui.ng.runtime.phone.config.prompts.PromptsGuiOwl

/**
 * GUI-Owl 适配器(mPLUG/GUI-Owl-7B/32B/1.5)。
 *
 * 与 Qwen-VL 类似每轮重构,但 system prompt 是纯字符串(不是 content array),
 * 响应格式是 `Action: <desc>\n<tool_call>{...}</tool_call>`。
 */
class GuiOwlAdapter : ModelAdapter {

    override val name: String = "guiowl"

    private val history = mutableListOf<String>()

    override fun addHistory(description: String) {
        if (description.isNotBlank()) history += description
    }

    override fun clearHistory() {
        history.clear()
    }

    override fun buildMessages(
        task: String,
        imageBase64: String,
        currentApp: String,
        context: List<Map<String, Any?>>,
        lang: String,
        extraUserImages: List<String>,
        isFirst: Boolean,
    ): List<Map<String, Any?>> {
        @Suppress("UNUSED_VARIABLE") val ignoredRefs = extraUserImages
        val messages = mutableListOf<Map<String, Any?>>()
        messages.add(mapOf("role" to "system", "content" to PromptsGuiOwl.systemPrompt(lang)))
        val userQuery = PromptsGuiOwl.userQuery(task, history, lang)
        messages.add(
            mapOf(
                "role" to "user",
                "content" to listOf(
                    mapOf("type" to "text", "text" to userQuery),
                    mapOf(
                        "type" to "image_url",
                        "image_url" to mapOf("url" to "data:image/jpeg;base64,$imageBase64"),
                    ),
                ),
            )
        )
        return messages
    }

    override fun parseResponse(response: String): Pair<String, String> {
        var thinking = ""
        for (rawLine in response.trim().split('\n')) {
            val line = rawLine.trim()
            if (line.startsWith("Action:")) {
                thinking = line.removePrefix("Action:").trim()
                break
            }
        }

        val toolCall = Regex("""<tool_call>\s*(.*?)\s*</tool_call>""", RegexOption.DOT_MATCHES_ALL)
            .find(response)?.groupValues?.get(1)?.trim()
        if (toolCall != null) return thinking to ToolCallNormalizer.normalize(toolCall)

        // Fallback: 旧格式 ### Action ### { ... }
        val actionMatch = Regex("""###\s*Action\s*###\s*(.*?)(?=###\s*Description\s*###|$)""", RegexOption.DOT_MATCHES_ALL)
            .find(response)?.groupValues?.get(1)?.trim()
            ?.replace("```", "")?.replace("json", "")?.trim()
        if (actionMatch != null) return thinking to ToolCallNormalizer.normalize(actionMatch)

        return thinking to ""
    }
}
