package com.clawgui.android.core.nano.agent

import com.clawgui.android.core.nano.providers.LLMProvider
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger

private val logger: Logger = Logger.getLogger("AgentMemory")

private val SAVE_MEMORY_TOOL: List<Map<String, Any?>> = listOf(
    mapOf(
        "type" to "function",
        "function" to mapOf(
            "name" to "save_memory",
            "description" to "Save the memory consolidation result to persistent storage.",
            "parameters" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "history_entry" to mapOf(
                        "type" to "string",
                        "description" to "A paragraph summarizing key events/decisions/topics. Start with [YYYY-MM-DD HH:MM]. Include detail useful for grep search.",
                    ),
                    "memory_update" to mapOf(
                        "type" to "string",
                        "description" to "Full updated long-term memory as markdown. Include all existing facts plus new ones. Return unchanged if nothing new.",
                    ),
                ),
                "required" to listOf("history_entry", "memory_update"),
            ),
        ),
    )
)

class AgentMemory(workspaceDir: File) {

    private val memoryDir = File(workspaceDir, "memory").also { it.mkdirs() }
    private val memoryFile = File(memoryDir, "MEMORY.md")
    private val historyFile = File(memoryDir, "HISTORY.md")

    fun readLongTerm(): String =
        if (memoryFile.exists()) memoryFile.readText(Charsets.UTF_8) else ""

    fun readHistory(): String =
        if (historyFile.exists()) historyFile.readText(Charsets.UTF_8) else ""

    fun readRecentHistoryEntries(limit: Int = 10): List<String> {
        if (limit <= 0) return emptyList()
        return readHistory()
            .split(Regex("""\n\s*\n"""))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .takeLast(limit)
    }

    fun writeLongTerm(content: String) {
        memoryFile.writeText(content, Charsets.UTF_8)
    }

    fun appendHistory(entry: String) {
        historyFile.appendText(entry.trimEnd() + "\n\n", Charsets.UTF_8)
    }

    fun getMemoryContext(): String {
        val longTerm = readLongTerm()
        return if (longTerm.isNotEmpty()) {
            "## Long-term Memory\n$longTerm"
        } else {
            ""
        }
    }

    suspend fun consolidate(
        messages: List<Map<String, Any?>>,
        provider: LLMProvider,
        model: String,
    ): Boolean {
        if (messages.isEmpty()) return true

        val currentMemory = readLongTerm()
        val formatted = formatMessages(messages)
        val prompt = """Process this conversation and call the save_memory tool with your consolidation.

## Current Long-term Memory
${currentMemory.ifEmpty { "(empty)" }}

## Conversation to Process
$formatted"""

        val chatMessages = listOf(
            mapOf("role" to "system", "content" to "You are a memory consolidation agent. Call the save_memory tool with your consolidation of the conversation."),
            mapOf("role" to "user", "content" to prompt),
        )

        return try {
            val forced = mapOf("type" to "function", "function" to mapOf("name" to "save_memory"))
            val response = provider.chatWithRetry(
                messages = chatMessages,
                tools = SAVE_MEMORY_TOOL,
                model = model,
                toolChoice = forced,
            )

            if (!response.hasToolCalls) {
                // Try without forced tool_choice if provider doesn't support it
                val fallback = provider.chatWithRetry(
                    messages = chatMessages,
                    tools = SAVE_MEMORY_TOOL,
                    model = model,
                )
                if (!fallback.hasToolCalls) {
                    rawArchive(messages)
                    return false
                }
                applyConsolidation(fallback.toolCalls[0].arguments)
                return true
            }

            applyConsolidation(response.toolCalls[0].arguments)
            true
        } catch (e: Exception) {
            logger.log(Level.SEVERE, "consolidate failed, raw archiving", e)
            rawArchive(messages)
            false
        }
    }

    private fun applyConsolidation(args: Map<String, Any?>) {
        val historyEntry = args["history_entry"] as? String
        val memoryUpdate = args["memory_update"] as? String
        if (!historyEntry.isNullOrEmpty()) appendHistory(historyEntry)
        if (!memoryUpdate.isNullOrEmpty()) writeLongTerm(memoryUpdate)
    }

    private fun rawArchive(messages: List<Map<String, Any?>>) {
        val text = formatMessages(messages)
        if (text.isNotEmpty()) appendHistory("[raw archive]\n$text")
    }

    private fun formatMessages(messages: List<Map<String, Any?>>): String =
        messages.mapNotNull { msg ->
            val content = msg["content"]?.toString() ?: return@mapNotNull null
            if (content.isEmpty()) return@mapNotNull null
            val role = msg["role"]?.toString()?.uppercase() ?: "?"
            val timestamp = msg["timestamp"]?.toString()?.take(16) ?: "?"
            "[$timestamp] $role: $content"
        }.joinToString("\n")
}
