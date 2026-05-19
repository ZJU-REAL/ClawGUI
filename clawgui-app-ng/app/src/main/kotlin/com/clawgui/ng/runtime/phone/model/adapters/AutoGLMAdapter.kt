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
        // The XML-style structured form is the contract — try it first.
        // Use the *last* `<answer>` so any earlier ones the model quoted as
        // examples inside <think> can't shadow the real action; pick the
        // *first* `</answer>` after it so trailing junk (extra `<plan>`,
        // stray tokens, repeated answer blocks) doesn't get glued to the
        // action string.
        val openIdx = response.lastIndexOf("<answer>")
        if (openIdx >= 0) {
            val afterOpen = openIdx + "<answer>".length
            val closeIdx = response.indexOf("</answer>", afterOpen)
            val rawAction = if (closeIdx > 0)
                response.substring(afterOpen, closeIdx)
            else
                response.substring(afterOpen)
            val rawThink = response.substring(0, openIdx)
                .replace("<think>", "").replace("</think>", "").trim()
            val thinking = cleanThinking(rawThink)
            return thinking to sanitizeActionString(rawAction)
        }
        // Fallbacks for models that drop the XML wrappers entirely. Use the
        // *last* occurrence so any quoted examples in the lead-in can't
        // shadow the real chosen action.
        val finishIdx = response.lastIndexOf("finish(message=")
        if (finishIdx >= 0) {
            return cleanThinking(response.substring(0, finishIdx).trim()) to
                sanitizeActionString(response.substring(finishIdx))
        }
        val doIdx = response.lastIndexOf("do(action=")
        if (doIdx >= 0) {
            return cleanThinking(response.substring(0, doIdx).trim()) to
                sanitizeActionString(response.substring(doIdx))
        }
        // Model went fully free-form — no XML, no command. Don't pretend
        // there's an action. Surface a synthetic finish so the loop bails
        // immediately with the rambling as the visible message, instead of
        // looping while ActionParser fails over and over.
        val cleaned = cleanThinking(response).take(400).ifBlank { "模型没有按要求的格式输出。" }
        return cleaned to "finish(message=\"模型未按格式输出。原文摘要:${cleaned.take(120)}\")"
    }

    /**
     * Normalise a raw `<answer>` body before ActionParser sees it. Handles
     * the common ways model output strays from the contract:
     *   - Markdown code fences (```python ... ```)
     *   - Stray <plan>/<answer> tags glued on at the end
     *   - Extra wrapping quotes ("do(...)")
     *   - Smart / full-width punctuation that ActionParser doesn't grok
     *     (Chinese brackets 【】 「」, smart quotes “”‘’, full-width =,)
     *   - Leading natural-language prefix before the actual command
     *   - Trailing junk after the matching close paren
     */
    private fun sanitizeActionString(raw: String): String {
        var s = raw.trim()
            // Code fences
            .replace("```python", "").replace("```kotlin", "").replace("```json", "").replace("```", "")
            // Stray XML wrappers
            .replace("</answer>", "").replace("<answer>", "")
            .replace(Regex("<plan>.*?</plan>", RegexOption.DOT_MATCHES_ALL), "")
            .replace("<plan>", "").replace("</plan>", "")
            .replace("<think>", "").replace("</think>", "")
            // Full-width / smart punctuation → ASCII equivalents.
            // Chars below are full-width / curly variants, NOT their ASCII twins.
            .replace('\u3010', '[').replace('\u3011', ']')           // 【】
            .replace('\uFF08', '(').replace('\uFF09', ')')           // ()
            .replace('\u300C', '"').replace('\u300D', '"')           // 「」
            .replace('\u201C', '"').replace('\u201D', '"')           // “”
            .replace('\u2018', '\'').replace('\u2019', '\'')         // ‘’
            .replace('\uFF1D', '=').replace('\uFF0C', ',')           // =,
            .trim()
        // Strip extra wrapping quotes the model occasionally adds.
        if ((s.startsWith("\"") && s.endsWith("\"")) ||
            (s.startsWith("'") && s.endsWith("'"))
        ) {
            s = s.substring(1, s.length - 1).trim()
        }
        // Drop leading natural-language preamble before the command token.
        val doIdx = s.indexOf("do(")
        val finIdx = s.indexOf("finish(")
        val firstCmd = when {
            doIdx < 0 -> finIdx
            finIdx < 0 -> doIdx
            else -> minOf(doIdx, finIdx)
        }
        if (firstCmd > 0) s = s.substring(firstCmd)
        // Drop trailing junk after the *matching* close paren of the first
        // top-level call. We balance depth respecting string literals so
        // commas/parens inside text="..." don't trip us up.
        if (s.startsWith("do(") || s.startsWith("finish(")) {
            val openParen = s.indexOf('(')
            var depth = 0
            var inStr = false
            var strChar = ' '
            for (i in openParen until s.length) {
                val c = s[i]
                if (inStr) {
                    if (c == strChar && (i == 0 || s[i - 1] != '\\')) inStr = false
                } else when (c) {
                    '"', '\'' -> { inStr = true; strChar = c }
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) { s = s.substring(0, i + 1); break }
                    }
                }
            }
        }
        return s.trim()
    }

    /**
     * Strip the prompt-echo lines a free-rambling model tends to lead with
     * (e.g. "任务:..." / "用户任务(原话)..."). These come from the model
     * literally copying the user message back into its response. Showing
     * them inside the chat bubble's thinking panel is noisy + makes it
     * look like the agent is stuck on step 1.
     */
    private fun cleanThinking(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        val skipPrefixes = listOf(
            "任务:", "任务 :", "用户任务", "User task", "任务原话",
        )
        // Drop leading lines that start with any of the prefixes above.
        val lines = trimmed.lines().dropWhile { line ->
            val s = line.trim()
            s.isEmpty() || skipPrefixes.any { s.startsWith(it) }
        }
        // Also strip any lingering <plan>...</plan> block so it doesn't show
        // up in the chat panel. PlanProtocol.stripBlock handles this at the
        // PhoneAgent layer too, but doing it here keeps every consumer
        // clean by default.
        return lines.joinToString("\n")
            .replace(Regex("<plan>.*?</plan>", RegexOption.DOT_MATCHES_ALL), "")
            .trim()
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
        val messages = context.toMutableList()
        // step index = number of prior assistant turns already in context.
        val stepIndex = context.count { it["role"] == "assistant" } + 1
        if (isFirst) {
            // Don't wipe out historic context — adapter.addHistory tracks
            // cross-task memory we want preserved. But if the cached
            // context lacks the system prompt (fresh agent), inject it.
            if (messages.none { it["role"] == "system" }) {
                messages.add(0, MessageBuilder.createSystemMessage(PromptsZh.getSystemPrompt()))
            }
            val refHint = if (extraUserImages.isNotEmpty()) {
                "\n用户随任务附带了 ${extraUserImages.size} 张参考图(显示在屏幕截图之后),需要把它们当作任务的输入材料(比如要发布的图片、要识别的内容)。\n"
            } else ""
            val intro = """
                任务:$task
                $refHint
                这是第一步,只做规划。按格式严格输出:

                <think>一两句话说理解到的目标 + 起手做什么</think>
                <plan>{"ops":[{"op":"init","items":[{"id":"...","title":"..."}, ...]},{"op":"update","id":"<第一项>","status":"IN_PROGRESS"}]}</plan>
                <answer>do(action="Launch", app="...") 或 do(action="Home") 或 do(action="Ask", question="...") 或 finish(message="...")</answer>

                直接以 `<think>` 开头,不要写任何引子。
            """.trimIndent()
            messages.add(MessageBuilder.createUserMessage(intro, null, extraUserImages))
        } else {
            val screenInfo = MessageBuilder.buildScreenInfo(currentApp, stepIndex)
            val body = """
                第 $stepIndex 步。屏幕信息:$screenInfo

                按格式严格输出三块,直接以 `<think>` 开头:
                <think>看到啥 + 这一步做什么(≤4 行)</think>
                <plan>{"ops":[{"op":"update","id":"<上一项>","status":"DONE"},{"op":"update","id":"<下一项>","status":"IN_PROGRESS"}]}</plan>
                <answer>一条 do(...) 或 finish(...)</answer>
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
