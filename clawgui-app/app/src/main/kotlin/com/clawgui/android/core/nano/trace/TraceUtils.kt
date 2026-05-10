package com.clawgui.android.core.nano.trace

fun summarizeMessages(messages: List<Map<String, Any?>>, maxMessages: Int = 24): List<Map<String, Any?>> {
    return messages.takeLast(maxMessages).map { msg ->
        val summarized = mutableMapOf<String, Any?>()
        for (key in listOf("role", "content", "tool_calls", "tool_call_id", "name", "reasoning_content")) {
            if (key in msg) summarized[key] = msg[key]
        }
        summarized
    }
}
