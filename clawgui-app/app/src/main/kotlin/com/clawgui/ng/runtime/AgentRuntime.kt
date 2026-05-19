package com.clawgui.ng.runtime

import com.clawgui.ng.data.ChatMessage
import kotlinx.coroutines.flow.Flow

/**
 * The boundary between the ng UI and whatever agent stack drives it.
 *
 * `MockAgentRuntime` (used by ChatViewModel today) stages a fake think→act
 * loop just so the UI is exercisable. Replacing it amounts to plugging in
 * `LegacyAgentRuntime` (port of the v1 client's `App.kt` + `nano.agent.AgentLoop`)
 * — see docs/PORTING.md.
 */
interface AgentRuntime {
    /**
     * Emit a user message into the runtime and stream back assistant updates.
     * Streaming updates use the same message id so the UI can splice deltas.
     */
    fun submit(sessionKey: String, text: String): Flow<RuntimeEvent>

    /** Cancel the current run. */
    fun stop()

    /** True iff the runtime is currently executing for `sessionKey`. */
    fun isRunning(sessionKey: String): Boolean
}

sealed interface RuntimeEvent {
    val sessionKey: String

    data class Thinking(override val sessionKey: String, val delta: String) : RuntimeEvent
    data class AssistantDelta(override val sessionKey: String, val messageId: String, val delta: String) : RuntimeEvent
    data class AssistantFinal(override val sessionKey: String, val message: ChatMessage) : RuntimeEvent
    data class ToolCall(override val sessionKey: String, val name: String, val args: String) : RuntimeEvent
    data class Error(override val sessionKey: String, val cause: String) : RuntimeEvent
    data class Done(override val sessionKey: String) : RuntimeEvent
    data class Stopped(override val sessionKey: String) : RuntimeEvent
}
