package com.clawgui.ng.runtime.llm

import com.clawgui.ng.data.AttachmentKind
import com.clawgui.ng.data.ChatMessage
import com.clawgui.ng.data.Role
import com.clawgui.ng.data.repo.ProviderCredentials
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Brain runtime — single entry-point the ChatViewModel calls. Picks a client
 * based on credentials' kind and streams reply tokens. The contract is just
 * tokens (no thinking/tool calls yet); higher-level event types are layered
 * on top by AgentRuntime when PhoneAgent is wired in.
 *
 * Multimodal: when a USER message in history carries IMAGE attachments and
 * the provider is marked `supportsVision`, the images are encoded as base64
 * and sent alongside the text in OpenAI vision-content-array form. Providers
 * without vision drop the images silently and only the text reaches the API
 * (gated by the caller).
 */
class BrainRuntime(private val credentials: ProviderCredentials) {

    fun streamReply(systemPrompt: String, history: List<ChatMessage>): Flow<StreamEvent> = flow {
        if (credentials.apiKey.isBlank()) {
            emit(StreamEvent.Error("没有配置 ${credentials.kind.name} 的 API Key。请在「设置 → AI 模型」里粘贴 Key。"))
            return@flow
        }
        val client = OpenAICompatClient(
            baseUrl = credentials.baseUrl,
            apiKey = credentials.apiKey,
            model = credentials.model,
        )
        val messages = buildList {
            if (systemPrompt.isNotBlank()) add(Message("system", systemPrompt))
            history.forEach { m ->
                val role = when (m.role) {
                    Role.USER -> "user"
                    Role.ASSISTANT -> "assistant"
                    Role.SYSTEM -> "system"
                    Role.TOOL -> "tool"
                }
                val msg = toMessage(role, m) ?: return@forEach
                add(msg)
            }
        }
        client.stream(messages).collect { emit(it) }
    }

    /**
     * Convert a stored [ChatMessage] into an API-bound [Message]. Returns
     * null when the message has no content at all (placeholder rows we should
     * skip). User images are encoded only when the provider supports vision —
     * for text-only providers we send the text alone so chat history stays
     * coherent.
     */
    private fun toMessage(role: String, m: ChatMessage): Message? {
        val images = if (role == "user" && credentials.supportsVision) {
            m.attachments
                .filter { it.kind == AttachmentKind.IMAGE }
                .mapNotNull { att ->
                    com.clawgui.ng.runtime.media.AttachmentStore.readBytes(att.uri)
                        ?.let { java.util.Base64.getEncoder().encodeToString(it) }
                }
        } else emptyList()

        val hasText = m.content.isNotBlank()
        if (!hasText && images.isEmpty()) return null

        if (images.isEmpty()) return Message(role, m.content)

        // Vision turn — interleave text + images. Text first so the model
        // grounds before scanning the picture.
        val parts = buildList {
            if (hasText) add(ContentPart.Text(m.content))
            images.forEach { add(ContentPart.ImageBase64(it)) }
        }
        return Message(role, parts)
    }
}
