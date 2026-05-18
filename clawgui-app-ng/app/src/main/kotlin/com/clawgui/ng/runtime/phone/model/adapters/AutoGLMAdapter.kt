package com.clawgui.ng.runtime.phone.model.adapters

import com.clawgui.ng.runtime.phone.config.PromptsZh
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object MessageBuilder {

    fun createSystemMessage(content: String): Map<String, Any?> =
        mapOf("role" to "system", "content" to content)

    fun createUserMessage(
        text: String,
        imageBase64: String? = null,
        extraUserImages: List<String> = emptyList(),
    ): Map<String, Any?> {
        val content = mutableListOf<Map<String, Any?>>()
        if (imageBase64 != null) {
            content.add(mapOf(
                "type" to "image_url",
                "image_url" to mapOf("url" to "data:image/jpeg;base64,$imageBase64"),
            ))
        }
        // User-supplied reference images carry a `#user-ref` URL fragment so
        // removeImagesFromMessage() can strip the heavy per-step screenshot
        // while keeping the user's reference around for the whole task.
        extraUserImages.forEach { ref ->
            content.add(mapOf(
                "type" to "image_url",
                "image_url" to mapOf("url" to "data:image/jpeg;base64,$ref#user-ref"),
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
            val filtered = content.filterIsInstance<Map<*, *>>().filter { part ->
                if (part["type"] != "image_url") return@filter true
                // Keep user-supplied reference images across steps so the
                // model can keep grounding on them; drop only screenshots.
                val url = (part["image_url"] as? Map<*, *>)?.get("url") as? String
                url != null && url.endsWith("#user-ref")
            }
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
        extraUserImages: List<String>,
    ): List<Map<String, Any?>> {
        val messages = context.toMutableList()
        // step index = number of prior assistant turns already in context.
        val stepIndex = context.count { it["role"] == "assistant" } + 1
        if (messages.isEmpty()) {
            messages.add(MessageBuilder.createSystemMessage(PromptsZh.getSystemPrompt()))
            val refHint = if (extraUserImages.isNotEmpty()) {
                "\n用户随任务附带了 ${extraUserImages.size} 张参考图(显示在屏幕截图之后),需要把它们当作任务的输入材料(比如要发布的图片、要识别的内容)。\n"
            } else ""
            val intro = """
                用户任务(原话):$task
                $refHint
                请严格按系统提示的格式输出 `<think>...</think><answer>...</answer>`。

                **当前屏幕是 ClawGUI 自己(驾驶舱),不是任务目标。**第一步只做规划,不要操作 ClawGUI。

                `<think>` 必须包含:
                  第 0 节 任务规约 4 行:目标 / 终止条件 / 禁止条件 / 角色。
                  第 3 节 完整计划:列出 2-6 步要做什么(例:Launch 微信 → 搜联系人 → 进聊天 → 发消息 → finish)。
                  第 4 节 进度:`已完成 0/N`(N 是计划步数)。
                  其余小节按系统提示走。

                `<answer>` 这一步**只能**是下列之一:
                  - `do(action="Launch", app="目标App名")` ← 绝大多数情况
                  - `do(action="Home")` ← 仅当任务就是用桌面 / 系统设置
                  - `do(action="Ask", question="...")` ← 任务关键信息缺失
                  - `finish(message="...")` ← 任务不需要操作手机就能答复
                **禁止**首步出现 Tap / Swipe / Type / Back / Wait —— 它们对着 ClawGUI 截图毫无意义。

                屏幕信息:${MessageBuilder.buildScreenInfo(currentApp, stepIndex)}
            """.trimIndent()
            messages.add(MessageBuilder.createUserMessage(intro, imageBase64, extraUserImages))
        } else {
            val screenInfo = MessageBuilder.buildScreenInfo(currentApp, stepIndex)
            val body = """
                ** 当前屏幕(第 $stepIndex 步)**
                $screenInfo

                请按系统提示的格式继续。`<think>` 第 0 节"任务规约"照抄首步写过的 4 行,第 1 节用规约里的 Done When 严格判断是否真的命中。
            """.trimIndent()
            // Subsequent steps: never re-ship the user-ref images (they're
            // already preserved in context by removeImagesFromMessage's
            // user-ref carve-out), just the new screenshot + the prompt text.
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
