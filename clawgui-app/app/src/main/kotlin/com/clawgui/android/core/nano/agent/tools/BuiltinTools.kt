package com.clawgui.android.core.nano.agent.tools

import com.clawgui.android.core.nano.agent.AgentMemory
import com.clawgui.android.core.nano.bus.MessageBus
import com.clawgui.android.core.nano.bus.OutboundMessage
import com.clawgui.android.core.nano.cron.CronService
import com.clawgui.android.core.nano.agent.SubagentManager
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Tool that sends a message back to the user via the bus. Full implementation in M4. */
class MessageTool(
    private val sendCallback: suspend (OutboundMessage) -> Unit = {},
) : com.clawgui.android.core.nano.agent.tools.Tool() {
    override val name = "message"
    override val description = "Send a message to a specific chat channel."
    override val parameters: Map<String, Any?> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "content" to mapOf("type" to "string", "description" to "Message content"),
            "channel" to mapOf("type" to "string", "description" to "Target channel"),
            "chat_id" to mapOf("type" to "string", "description" to "Target chat ID"),
            "media" to mapOf("type" to "array", "items" to mapOf("type" to "string"), "description" to "Media file paths"),
        ),
        "required" to listOf("content"),
    )

    private var currentChannel = "cli"
    private var currentChatId = "direct"
    var sentInTurn = false
        private set

    fun setContext(channel: String, chatId: String) {
        currentChannel = channel
        currentChatId = chatId
    }

    fun startTurn() { sentInTurn = false }

    override suspend fun execute(params: Map<String, Any?>): Any? {
        val content = params["content"] as? String ?: return "Error: content is required"
        val channel = params["channel"] as? String ?: currentChannel
        val chatId = params["chat_id"] as? String ?: currentChatId
        @Suppress("UNCHECKED_CAST")
        val media = params["media"] as? List<String>
        sendCallback(OutboundMessage(channel = channel, chatId = chatId, content = content, media = media ?: emptyList()))
        sentInTurn = true
        return "Message sent."
    }
}

/** Tool that spawns a background subagent. Full implementation in M4. */
class SpawnTool(
    private val manager: SubagentManager,
) : com.clawgui.android.core.nano.agent.tools.Tool() {
    override val name = "spawn"
    override val description = "Spawn a background subagent to complete a task."
    override val parameters: Map<String, Any?> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "task" to mapOf("type" to "string", "description" to "Task description"),
            "label" to mapOf("type" to "string", "description" to "Short label for the task"),
        ),
        "required" to listOf("task"),
    )

    private var currentChannel = "cli"
    private var currentChatId = "direct"

    fun setContext(channel: String, chatId: String) {
        currentChannel = channel
        currentChatId = chatId
    }

    override suspend fun execute(params: Map<String, Any?>): Any? {
        val task = params["task"] as? String ?: return "Error: task is required"
        val label = params["label"] as? String
        return manager.spawn(task, label, currentChannel, currentChatId)
    }
}

/** Tool that manages cron jobs. Full implementation in M4. */
class CronTool(
    private val cronService: CronService,
    private val defaultTimezone: String = "UTC",
) : com.clawgui.android.core.nano.agent.tools.Tool() {
    override val name = "cron"
    override val description = "Schedule recurring or one-shot agent tasks."
    override val parameters: Map<String, Any?> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "action" to mapOf("type" to "string", "enum" to listOf("add", "remove", "list", "enable", "disable")),
            "name" to mapOf("type" to "string", "description" to "Job name"),
            "schedule" to mapOf("type" to "string", "description" to "Cron expression, 'every Xs', or ISO datetime"),
            "message" to mapOf("type" to "string", "description" to "Message/task for the job"),
            "job_id" to mapOf("type" to "string", "description" to "Job ID for remove/enable/disable"),
        ),
        "required" to listOf("action"),
    )

    private var currentChannel = "cli"
    private var currentChatId = "direct"

    fun setContext(channel: String, chatId: String) {
        currentChannel = channel
        currentChatId = chatId
    }

    override suspend fun execute(params: Map<String, Any?>): Any? {
        return when (params["action"] as? String) {
            "list" -> {
                val jobs = cronService.listJobs()
                if (jobs.isEmpty()) "No scheduled jobs."
                else jobs.joinToString("\n") { "- [${it.id}] ${it.name}: next=${it.state.nextRunAtMs}" }
            }
            "remove" -> {
                val id = params["job_id"] as? String ?: return "Error: job_id required"
                if (cronService.removeJob(id)) "Job $id removed." else "Job $id not found."
            }
            "enable", "disable" -> {
                val id = params["job_id"] as? String ?: return "Error: job_id required"
                val enable = params["action"] == "enable"
                cronService.enableJob(id, enable)?.let { "Job ${it.id} ${if (enable) "enabled" else "disabled"}." } ?: "Job $id not found."
            }
            else -> "Error: Unknown cron action"
        }
    }
}

class ReadMemoryTool(workspaceDir: File) : Tool() {
    override val name = "read_memory"
    override val description =
        "Read durable user facts from MEMORY.md and/or recent entries from HISTORY.md. " +
            "Use when the user asks about past preferences, identity, or information that may only live in stored memory."
    override val parameters: Map<String, Any?> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "source" to mapOf(
                "type" to "string",
                "enum" to listOf("memory", "history", "both"),
                "description" to "Which source to read. Defaults to memory.",
            ),
            "query" to mapOf(
                "type" to "string",
                "description" to "Optional keyword filter. Paragraphs / entries containing this substring (case-insensitive) are returned.",
            ),
            "limit" to mapOf(
                "type" to "integer",
                "minimum" to 1,
                "maximum" to 20,
                "description" to "Max history entries to return. Defaults to 5.",
            ),
        ),
    )

    private val memory = AgentMemory(workspaceDir)

    override suspend fun execute(params: Map<String, Any?>): Any? {
        val source = (params["source"] as? String)?.lowercase() ?: "memory"
        val query = (params["query"] as? String)?.trim().orEmpty()
        val limit = ((params["limit"] as? Number)?.toInt() ?: 5).coerceIn(1, 20)

        val parts = mutableListOf<String>()
        if (source == "memory" || source == "both") {
            val selected = filterParagraphs(memory.readLongTerm().trim(), query)
            parts += when {
                selected.isBlank() && query.isNotBlank() -> "Long-term Memory:\n(no match for \"$query\")"
                selected.isBlank() -> "Long-term Memory:\n(empty)"
                else -> "Long-term Memory:\n$selected"
            }
        }

        if (source == "history" || source == "both") {
            val entries = memory.readRecentHistoryEntries(limit = 50)
                .let { if (query.isBlank()) it else it.filter { e -> e.lowercase().contains(query.lowercase()) } }
                .takeLast(limit)
            parts += when {
                entries.isEmpty() && query.isNotBlank() -> "Recent History:\n(no match for \"$query\")"
                entries.isEmpty() -> "Recent History:\n(empty)"
                else -> buildString {
                    appendLine("Recent History:")
                    entries.forEachIndexed { i, e -> appendLine("${i + 1}. $e") }
                }.trimEnd()
            }
        }

        return parts.joinToString("\n\n").ifBlank { "No stored memory found." }
    }

    private fun filterParagraphs(text: String, query: String): String {
        if (text.isBlank()) return ""
        if (query.isBlank()) return text
        val needle = query.lowercase()
        return text.split(Regex("""\n\s*\n"""))
            .map { it.trimEnd() }
            .filter { it.lowercase().contains(needle) }
            .joinToString("\n\n")
            .trim()
    }
}

class WriteMemoryTool(workspaceDir: File) : Tool() {
    override val name = "write_memory"
    override val description =
        "Persist a durable note to MEMORY.md and/or append a timestamped entry to HISTORY.md. " +
            "Use when the user asks you to remember something or when you've learned a stable fact worth keeping across sessions."
    override val parameters: Map<String, Any?> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "memory_note" to mapOf(
                "type" to "string",
                "description" to "A durable fact or preference to append to MEMORY.md. One self-contained note per call.",
            ),
            "history_entry" to mapOf(
                "type" to "string",
                "description" to "A short event summary to append to HISTORY.md. Timestamp prefix added automatically if missing.",
            ),
        ),
    )

    private val memory = AgentMemory(workspaceDir)

    override suspend fun execute(params: Map<String, Any?>): Any? {
        val memoryNote = (params["memory_note"] as? String)?.trim().orEmpty()
        val historyEntry = (params["history_entry"] as? String)?.trim().orEmpty()
        if (memoryNote.isBlank() && historyEntry.isBlank()) {
            return "Error: memory_note or history_entry is required"
        }

        val results = mutableListOf<String>()
        if (memoryNote.isNotBlank()) {
            val current = memory.readLongTerm().trimEnd()
            val updated = mergeLongTermMemory(current, memoryNote)
            if (updated != current) {
                memory.writeLongTerm(updated)
                results += "Long-term memory updated."
            } else {
                results += "Long-term memory already contained that note."
            }
        }

        if (historyEntry.isNotBlank()) {
            memory.appendHistory(normalizeHistoryEntry(historyEntry))
            results += "History entry appended."
        }

        return results.joinToString(" ")
    }

    private fun mergeLongTermMemory(current: String, note: String): String {
        val normalizedNote = note.trim()
        if (current.isBlank()) return normalizedNote
        if (containsExactNote(current, normalizedNote)) return current
        return if (normalizedNote.contains('\n')) {
            "$current\n\n$normalizedNote"
        } else {
            "$current\n- $normalizedNote"
        }
    }

    private fun containsExactNote(current: String, note: String): Boolean {
        val needle = stripBullet(note).lowercase()
        if (needle.isEmpty()) return true
        val lineMatch = current.lines().any { stripBullet(it.trim()).lowercase() == needle }
        if (lineMatch) return true
        if (note.contains('\n')) {
            val paraMatch = current.split(Regex("""\n\s*\n""")).any { it.trim().lowercase() == note.trim().lowercase() }
            if (paraMatch) return true
        }
        return false
    }

    private fun stripBullet(s: String): String {
        val trimmed = s.trimStart()
        return when {
            trimmed.startsWith("- ") -> trimmed.removePrefix("- ").trim()
            trimmed.startsWith("* ") -> trimmed.removePrefix("* ").trim()
            else -> trimmed
        }
    }

    private fun normalizeHistoryEntry(entry: String): String {
        val trimmed = entry.trim()
        if (HISTORY_TS_PREFIX.containsMatchIn(trimmed)) return trimmed
        val timestamp = LocalDateTime.now().format(HISTORY_TS)
        return "[$timestamp] $trimmed"
    }

    private companion object {
        private val HISTORY_TS: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        private val HISTORY_TS_PREFIX = Regex("""^\[\d{4}-\d{2}-\d{2}""")
    }
}
