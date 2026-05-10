package com.clawgui.android.core.nano.agent

import com.clawgui.android.core.nano.providers.LLMResponse
import com.clawgui.android.core.nano.providers.ToolCallRequest

data class AgentHookContext(
    val iteration: Int,
    val messages: MutableList<Map<String, Any?>>,
    var response: LLMResponse? = null,
    var usage: Map<String, Int> = emptyMap(),
    var toolCalls: List<ToolCallRequest> = emptyList(),
    var toolResults: List<Any?> = emptyList(),
    var toolEvents: List<Map<String, String>> = emptyList(),
    var finalContent: String? = null,
    var stopReason: String? = null,
    var error: String? = null,
)

open class AgentHook {

    open fun wantsStreaming(): Boolean = false

    open suspend fun beforeIteration(context: AgentHookContext) {}

    open suspend fun onStream(context: AgentHookContext, delta: String) {}

    open suspend fun onStreamEnd(context: AgentHookContext, resuming: Boolean) {}

    open suspend fun beforeExecuteTools(context: AgentHookContext) {}

    open suspend fun afterIteration(context: AgentHookContext) {}

    open fun finalizeContent(context: AgentHookContext, content: String?): String? = content
}
