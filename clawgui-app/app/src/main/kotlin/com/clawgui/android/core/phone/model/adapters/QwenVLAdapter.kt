package com.clawgui.android.core.phone.model.adapters

import com.clawgui.android.core.phone.config.prompts.PromptsQwenVL

/**
 * Qwen2.5-VL / Qwen3-VL 适配器(官方 tool_call 格式)。
 *
 * 特点:每轮重构完整 messages(不累积),user message 包含任务指令 +
 * 操作历史 + 当前截图。操作历史通过 `addHistory(desc)` 外部累加,
 * adapter 自己持历史清单。
 */
class QwenVLAdapter : ModelAdapter {

    override val name: String = "qwenvl"

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
    ): List<Map<String, Any?>> {
        // Qwen-VL 每轮重构,不沿用 context
        val messages = mutableListOf<Map<String, Any?>>()
        messages.add(
            mapOf(
                "role" to "system",
                // Some OpenAI-compatible VLM endpoints reject array content on
                // system messages. Keep multimodal blocks only on user messages.
                "content" to PromptsQwenVL.systemPrompt(lang),
            )
        )
        val userQuery = PromptsQwenVL.userQuery(task, history)
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
        // Thought
        var thinking = ""
        for (rawLine in response.trim().split('\n')) {
            val line = rawLine.trim()
            if (line.startsWith("Thought:")) {
                thinking = line.removePrefix("Thought:").trim()
                break
            }
        }

        val toolCall = Regex("""<tool_call>\s*(.*?)\s*</tool_call>""", RegexOption.DOT_MATCHES_ALL)
            .find(response)?.groupValues?.get(1)?.trim()
        if (toolCall != null) return thinking to ToolCallNormalizer.normalize(toolCall)

        // fallback: Action: <...> 行
        for (rawLine in response.trim().split('\n')) {
            val line = rawLine.trim()
            if (line.startsWith("Action:")) return thinking to line.removePrefix("Action:").trim()
        }
        return thinking to ""
    }
}
