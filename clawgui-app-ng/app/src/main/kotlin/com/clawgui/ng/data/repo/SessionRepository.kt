package com.clawgui.ng.data.repo

import com.clawgui.ng.data.ChatMessage
import com.clawgui.ng.data.Role
import com.clawgui.ng.data.SessionSource
import com.clawgui.ng.data.SessionSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

/**
 * In-memory session store with hooks for the future runtime port (Brain agent
 * persisted SessionManager will plug in here). Keeps the UI fully exercisable
 * without the heavy runtime.
 */
class SessionRepository {

    private val _sessions = MutableStateFlow<List<SessionSummary>>(seedSessions())
    val sessions: StateFlow<List<SessionSummary>> = _sessions

    private val messageStore = mutableMapOf<String, MutableStateFlow<List<ChatMessage>>>()

    private val _currentKey = MutableStateFlow(_sessions.value.first().key)
    val currentKey: StateFlow<String> = _currentKey

    fun messagesFor(key: String): StateFlow<List<ChatMessage>> =
        messageStore.getOrPut(key) { MutableStateFlow(seedMessages(key)) }

    fun selectSession(key: String) {
        _currentKey.value = key
    }

    fun newSession(title: String = "新对话"): String {
        val key = "ui:" + UUID.randomUUID().toString().take(8)
        val summary = SessionSummary(
            key = key,
            title = title,
            lastMessagePreview = "",
            lastUpdatedAt = System.currentTimeMillis(),
            pinned = false,
            source = SessionSource.IN_APP,
        )
        _sessions.update { listOf(summary) + it }
        messageStore[key] = MutableStateFlow(emptyList())
        _currentKey.value = key
        return key
    }

    /**
     * Ensure a session exists for an externally-keyed chat (Feishu / Slack /
     * future). Idempotent; returns the same key on subsequent calls. Does
     * NOT change the user's current selection.
     */
    fun ensureExternalSession(
        key: String,
        defaultTitle: String,
        source: SessionSource,
    ): SessionSummary {
        val existing = _sessions.value.firstOrNull { it.key == key }
        if (existing != null) return existing
        val summary = SessionSummary(
            key = key,
            title = defaultTitle,
            lastMessagePreview = "",
            lastUpdatedAt = System.currentTimeMillis(),
            pinned = false,
            source = source,
        )
        _sessions.update { listOf(summary) + it }
        messageStore[key] = MutableStateFlow(emptyList())
        return summary
    }

    fun rename(key: String, newTitle: String) {
        _sessions.update { list ->
            list.map { if (it.key == key) it.copy(title = newTitle) else it }
        }
    }

    fun togglePin(key: String) {
        _sessions.update { list ->
            list.map { if (it.key == key) it.copy(pinned = !it.pinned) else it }
        }
    }

    fun delete(key: String) {
        _sessions.update { list -> list.filterNot { it.key == key } }
        messageStore.remove(key)
        if (_currentKey.value == key) {
            _currentKey.value = _sessions.value.firstOrNull()?.key ?: newSession()
        }
    }

    /**
     * Append a message and bump the summary preview.
     */
    fun appendMessage(key: String, msg: ChatMessage) {
        val store = messagesFor(key) as MutableStateFlow<List<ChatMessage>>
        store.update { it + msg }
        _sessions.update { list ->
            list.map {
                if (it.key == key) it.copy(
                    lastMessagePreview = msg.content.take(60),
                    lastUpdatedAt = System.currentTimeMillis(),
                ) else it
            }
        }
    }

    /**
     * Replace the last message (used for streaming updates).
     */
    /**
     * Drop the assistant turn whose id is [assistantId], **and everything
     * after it**. Returns the user message that triggered it (the latest
     * USER message preceding the dropped assistant), or null if the id
     * isn't an assistant or no triggering user exists.
     *
     * Used by "regenerate" so the user can re-roll any historical reply,
     * not just the most recent one.
     */
    fun truncateForRegenerate(key: String, assistantId: String): ChatMessage? {
        val store = messagesFor(key) as MutableStateFlow<List<ChatMessage>>
        var triggerUser: ChatMessage? = null
        store.update { list ->
            val idx = list.indexOfFirst { it.id == assistantId }
            if (idx < 0) return@update list
            val target = list[idx]
            if (target.role != com.clawgui.ng.data.Role.ASSISTANT) return@update list
            val before = list.subList(0, idx)
            triggerUser = before.lastOrNull { it.role == com.clawgui.ng.data.Role.USER }
            before.toList()
        }
        return triggerUser
    }

    /** Pop the trailing assistant turn (used for "regenerate"). Returns the
     *  user message that triggered it, or null if there isn't one. */
    fun popLastAssistantTurn(key: String): ChatMessage? {
        val store = messagesFor(key) as MutableStateFlow<List<ChatMessage>>
        var triggerUser: ChatMessage? = null
        store.update { list ->
            if (list.isEmpty()) return@update list
            val last = list.last()
            if (last.role != com.clawgui.ng.data.Role.ASSISTANT) return@update list
            val withoutAssistant = list.dropLast(1)
            triggerUser = withoutAssistant.lastOrNull { it.role == com.clawgui.ng.data.Role.USER }
            withoutAssistant
        }
        return triggerUser
    }

    fun updateLastMessage(key: String, transform: (ChatMessage) -> ChatMessage) {
        val store = messagesFor(key) as MutableStateFlow<List<ChatMessage>>
        store.update { list ->
            if (list.isEmpty()) list else list.dropLast(1) + transform(list.last())
        }
    }

    private fun seedSessions(): List<SessionSummary> {
        // Start with one empty session so the user lands on the discover-cards
        // page on first launch — same surface they get when tapping "新对话".
        val now = System.currentTimeMillis()
        return listOf(
            SessionSummary(
                key = "ui:welcome",
                title = "新对话",
                lastMessagePreview = "",
                lastUpdatedAt = now,
                pinned = false,
                source = SessionSource.IN_APP,
            ),
        )
    }

    private fun seedMessages(key: String): List<ChatMessage> = emptyList()
}
