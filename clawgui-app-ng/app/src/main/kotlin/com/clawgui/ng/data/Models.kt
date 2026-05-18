package com.clawgui.ng.data

import kotlinx.serialization.Serializable

@Serializable
enum class Role { USER, ASSISTANT, SYSTEM, TOOL }

@Serializable
data class ChatMessage(
    val id: String,
    val role: Role,
    val content: String,
    val thinking: String? = null,
    val attachments: List<Attachment> = emptyList(),
    val toolCalls: List<ToolCallView> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val streaming: Boolean = false,
    val error: String? = null,
)

@Serializable
data class Attachment(
    val id: String,
    val kind: AttachmentKind,
    val uri: String,
    val displayName: String,
    val sizeBytes: Long = 0,
)

@Serializable
enum class AttachmentKind { IMAGE, FILE, SCREENSHOT, VOICE }

@Serializable
data class ToolCallView(
    val name: String,
    val argsPreview: String,
    val resultPreview: String? = null,
    val status: ToolStatus = ToolStatus.RUNNING,
)

@Serializable
enum class ToolStatus { RUNNING, OK, ERROR }

@Serializable
data class SessionSummary(
    val key: String,                // ui:<uuid> or feishu:<chatId>
    val title: String,
    val lastMessagePreview: String,
    val lastUpdatedAt: Long,
    val pinned: Boolean = false,
    val source: SessionSource,
    val unread: Int = 0,
)

@Serializable
enum class SessionSource { IN_APP, FEISHU, OTHER }

@Serializable
data class InboxEntry(
    val sessionKey: String,
    val sender: String,
    val title: String,
    val preview: String,
    val receivedAt: Long,
    val unread: Boolean,
    val source: SessionSource = SessionSource.FEISHU,
)

@Serializable
data class ProviderProfile(
    val id: String,
    val displayName: String,
    val kind: ProviderKind,
    val baseUrl: String,
    val model: String,
    val hasApiKey: Boolean,
    val role: ProviderRole,
)

@Serializable
enum class ProviderKind { ANTHROPIC, OPENAI_COMPAT, ZHIPU, OLLAMA }

@Serializable
enum class ProviderRole { BRAIN, VISION }

@Serializable
data class PromptCard(
    val id: String,
    val emoji: String,
    val title: String,
    val subtitle: String,
    val prompt: String,
    val accentHue: Int,   // 0..359
)

@Serializable
enum class ExecutionState { IDLE, THINKING, ACTING, DONE, ERROR, STOPPED }

@Serializable
data class ExecutionStatus(
    val state: ExecutionState = ExecutionState.IDLE,
    val title: String = "",
    val subtitle: String = "",
    val stepIndex: Int = 0,
    val totalSteps: Int = 0,
    val thinking: String? = null,
    val actionJson: String? = null,
)
