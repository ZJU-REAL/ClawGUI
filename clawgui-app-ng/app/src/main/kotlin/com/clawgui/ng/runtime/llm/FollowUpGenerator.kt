package com.clawgui.ng.runtime.llm

import com.clawgui.ng.data.ChatMessage
import com.clawgui.ng.data.FollowUp
import com.clawgui.ng.data.Role
import com.clawgui.ng.data.repo.ProviderCredentials
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Generate 2-3 "you might want to ask next" suggestions for the chat input
 * based on the latest assistant reply. Fires once per assistant turn,
 * non-blocking — failure or empty result just means no chips show.
 *
 * The prompt asks for short labels (chip text) + the full prompt that should
 * land in the input box when the user taps the chip. We do not auto-send —
 * the user always reviews / edits first.
 */
object FollowUpGenerator {

    private const val MAX_RECENT_TURNS = 4
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Best-effort. Returns empty on any failure, including missing API key,
     * malformed JSON, or network errors. Caller should treat empty == "skip".
     */
    suspend fun generate(
        creds: ProviderCredentials,
        history: List<ChatMessage>,
    ): List<FollowUp> {
        if (creds.apiKey.isBlank()) return emptyList()
        // Last assistant turn must be substantive — skip when streaming bailed
        // mid-reply or returned an error.
        val lastAssistant = history.lastOrNull { it.role == Role.ASSISTANT }
        if (lastAssistant == null || lastAssistant.content.isBlank() || lastAssistant.error != null) {
            return emptyList()
        }

        val recent = history.takeLast(MAX_RECENT_TURNS).joinToString("\n\n") { m ->
            val tag = when (m.role) {
                Role.USER -> "用户"
                Role.ASSISTANT -> "助手"
                else -> return@joinToString ""
            }
            // Don't bloat the prompt with image bytes — just note attachments.
            val suffix = if (m.attachments.isNotEmpty()) " [附了${m.attachments.size}张图]" else ""
            "$tag: ${m.content.take(800)}$suffix"
        }.trim()

        val sys = """
            你是一个聊天追问建议器。根据下面的对话历史,生成 3 条用户最可能接着想问 / 想做的事。
            每条包含两个字段:
              - label: 一个短按钮文字,≤12 字,中文,不要标点。
              - prompt: 用户点了按钮后填进输入框的完整问题或指令,1 句话,语气自然。

            严格输出 JSON 数组,不要 Markdown 代码块,不要解释:
            [{"label":"...","prompt":"..."}, ...]

            若不确定或对话已结束(比如告别语),输出空数组 []。
            建议要真正"推进"对话,避免重复用户已经问过的话。
        """.trimIndent()

        val raw = runCatching {
            val client = OpenAICompatClient(
                baseUrl = creds.baseUrl,
                apiKey = creds.apiKey,
                model = creds.model,
            )
            client.complete(listOf(
                Message("system", sys),
                Message("user", "对话历史:\n$recent\n\n输出 JSON。"),
            ))
        }.getOrNull() ?: return emptyList()

        return parse(raw)
    }

    internal fun parse(raw: String): List<FollowUp> {
        // Find the first '[' and last ']' — be lenient about leading garbage
        // (some providers prefix with `Here's the JSON: ...`).
        val start = raw.indexOf('[')
        val end = raw.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        val slice = raw.substring(start, end + 1)
        val arr: JsonArray = runCatching { json.parseToJsonElement(slice) as JsonArray }
            .getOrNull() ?: return emptyList()
        return arr.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val label = (obj["label"] as? JsonPrimitive)?.content?.trim().orEmpty()
            val prompt = (obj["prompt"] as? JsonPrimitive)?.content?.trim().orEmpty()
            if (label.isBlank() || prompt.isBlank()) return@mapNotNull null
            FollowUp(label = label.take(12), prompt = prompt.take(200))
        }.take(3)
    }
}
