package com.clawgui.ng.runtime.phone.model.adapters

import com.clawgui.ng.runtime.phone.config.PromptsZh
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
                "image_url" to mapOf("url" to "data:image/jpeg;base64,$imageBase64"),
            ))
        }
        content.add(mapOf("type" to "text", "text" to text))
        return mapOf("role" to "user", "content" to content)
    }

    fun createAssistantMessage(content: String): Map<String, Any?> =
        mapOf("role" to "assistant", "content" to content)

    fun buildScreenInfo(currentApp: String, stepIndex: Int? = null): String =
        buildJsonObject {
            put("current_app", currentApp)
            if (stepIndex != null) put("step", stepIndex)
        }.toString()

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
        // step index = number of prior assistant turns already in context.
        val stepIndex = context.count { it["role"] == "assistant" } + 1
        if (messages.isEmpty()) {
            messages.add(MessageBuilder.createSystemMessage(PromptsZh.getSystemPrompt()))
            val intro = """
                用户任务(原话):$task

                请严格按系统提示的格式输出 `<think>...</think><answer>...</answer>`。

                **首步特别要求**:`<think>` 的第 0 节"任务规约"必须显式拆出 4 行:
                  目标 (Goal): ...
                  终止条件 (Done When): ...
                  禁止条件 (Don't): ...
                  角色 (Role): 我代表用户向 ... 发送/操作,不替任何他人发声。
                没有显式的 Done When 之前,不要进入第 1 节。

                屏幕信息:${MessageBuilder.buildScreenInfo(currentApp, stepIndex)}
            """.trimIndent()
            messages.add(MessageBuilder.createUserMessage(intro, imageBase64))
        } else {
            val screenInfo = MessageBuilder.buildScreenInfo(currentApp, stepIndex)
            val body = """
                ** 当前屏幕(第 $stepIndex 步)**
                $screenInfo

                请按系统提示的格式继续。`<think>` 第 0 节"任务规约"照抄首步写过的 4 行,第 1 节用规约里的 Done When 严格判断是否真的命中。
            """.trimIndent()
            messages.add(MessageBuilder.createUserMessage(body, imageBase64))
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
