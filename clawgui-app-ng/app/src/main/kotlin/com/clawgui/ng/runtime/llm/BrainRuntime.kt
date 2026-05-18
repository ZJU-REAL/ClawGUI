package com.clawgui.ng.runtime.llm

import com.clawgui.ng.data.ChatMessage
import com.clawgui.ng.data.Role
import com.clawgui.ng.data.repo.ProviderCredentials
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow

/**
 * Brain runtime — single entry-point the ChatViewModel calls. Picks a client
 * based on credentials' kind and streams reply tokens. The contract is just
 * tokens (no thinking/tool calls yet); higher-level event types are layered
 * on top by AgentRuntime when PhoneAgent is wired in.
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
                if (m.content.isNotBlank()) add(Message(role, m.content))
            }
        }
        client.stream(messages).collect { emit(it) }
    }
}
