package com.clawgui.ng.runtime.phone.model.adapters

import com.clawgui.ng.runtime.phone.config.prompts.PromptsMaiUI

/**
 * MAI-UI 适配器。
 *
 * 输出格式:
 * ```
 * <thinking>...</thinking>
 * <tool_call>{"name":"mobile_use","arguments":{"action":"click","coordinate":[x,y]}}</tool_call>
 * ```
 *
 * 首轮消息:system(纯字符串) + user(text:任务) + user(image:截图)
 * 后续:accumulate 时由上层把 assistant 文本塞进 context,本 adapter 只补新 user(image)。
 */
class MaiUIAdapter : ModelAdapter {

    override val name: String = "maiui"

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
        val messages = context.toMutableList()
        if (messages.isEmpty()) {
            messages.add(mapOf("role" to "system", "content" to PromptsMaiUI.systemPrompt(lang)))
            messages.add(
                mapOf(
                    "role" to "user",
                    "content" to listOf(mapOf("type" to "text", "text" to task)),
                )
            )
            messages.add(
                mapOf(
                    "role" to "user",
                    "content" to listOf(
                        mapOf(
                            "type" to "image_url",
                            "image_url" to mapOf("url" to "data:image/jpeg;base64,$imageBase64"),
                        ),
                    ),
                )
            )
        } else {
            messages.add(
                mapOf(
                    "role" to "user",
                    "content" to listOf(
                        mapOf(
                            "type" to "image_url",
                            "image_url" to mapOf("url" to "data:image/jpeg;base64,$imageBase64"),
                        ),
                    ),
                )
            )
        }
        return messages
    }

    override fun parseResponse(response: String): Pair<String, String> {
        // 兼容 deepseek-thinking 风格 </think>
        var text = response
        if ("</think>" in text && "</thinking>" !in text) {
            text = text.replace("</think>", "</thinking>")
            if ("<thinking>" !in text) text = "<thinking>$text"
        }

        val thinking = Regex("""<thinking>(.*?)</thinking>""", RegexOption.DOT_MATCHES_ALL)
            .find(text)?.groupValues?.get(1)?.trim()
            ?.replace("```html", "")?.replace("```", "")?.trim()
            ?: ""

        val toolCall = Regex("""<tool_call>\s*(.*?)(?:</tool_call>|$)""", RegexOption.DOT_MATCHES_ALL)
            .find(text)?.groupValues?.get(1)?.trim()
            ?: return thinking to ""

        val normalized = ToolCallNormalizer.normalize(toolCall)
        return thinking to normalized
    }
}
