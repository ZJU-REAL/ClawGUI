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
    /** 紧跟本条 assistant 回复的建议追问,只在最后一条 assistant 消息下方渲染。 */
    val followUps: List<FollowUp> = emptyList(),
    /** PhoneAgent 制定的多步骤计划。null = 该消息没有计划(普通对话);
     *  非 null 时 UI 会在 bubble 里渲染计划卡。状态变化时整张 Plan 替换。 */
    val plan: Plan? = null,
    /** PhoneAgent 每步的动作记录,UI 用来渲染执行轨迹时间轴。 */
    val actionTrace: List<StepRecord> = emptyList(),
)

@Serializable
data class StepRecord(
    val stepIndex: Int,
    /** Action name like "Tap" / "Launch" / "Type" / "finish". */
    val actionName: String,
    /** Inline detail (coords / text / app name) for compact one-line display. */
    val actionExtra: String = "",
    /** First non-blank line of the model's <think> for this step — 1-line preview. */
    val thinkingPreview: String = "",
    val success: Boolean = true,
    /** True while the action is mid-execution; UI shows a spinner instead of ✓/✗. */
    val inProgress: Boolean = false,
)

@Serializable
data class Plan(
    val items: List<PlanItem>,
    /** 当前推进项的 id,UI 用来高亮 + 脉冲;null = 全部未开始或全部结束。 */
    val activeItemId: String? = null,
) {
    val doneCount: Int get() = items.count { it.status == PlanItemStatus.DONE }
    val totalCount: Int get() = items.size
}

@Serializable
data class PlanItem(
    val id: String,
    val title: String,
    val detail: String? = null,
    val status: PlanItemStatus = PlanItemStatus.PENDING,
    /** 完成 / 失败 / 跳过的原因。显示在 item 下方一行小字。 */
    val note: String? = null,
    /** 哪一步把这个 item 变到当前状态 — 用来排"最新变更"动画。 */
    val updatedAtStep: Int = 0,
)

@Serializable
enum class PlanItemStatus {
    PENDING,        // 还没轮到
    IN_PROGRESS,    // 正在做
    DONE,           // 完成
    SKIPPED,        // 跳过(用户改主意 / 不需要做 / 别的步骤已经达成)
    FAILED,         // 失败(明确知道做不到,note 写原因)
    BLOCKED,        // 卡住等用户(Ask / Take_over / 等回复)
}

@Serializable
data class FollowUp(
    /** Short chip label shown to the user (≤12 chars). */
    val label: String,
    /** Full text dropped into the draft input when the chip is tapped. */
    val prompt: String,
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
