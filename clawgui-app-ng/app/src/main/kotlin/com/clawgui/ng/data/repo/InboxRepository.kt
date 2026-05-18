package com.clawgui.ng.data.repo

import com.clawgui.ng.data.InboxEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * In-memory inbox. Channel runtimes (FeishuChannel, future Slack/DM bridges)
 * call [appendInbound] when a new external message arrives. Entries are
 * keyed by `(sessionKey, messageId)` so duplicates from reconnect storms
 * don't multiply.
 */
class InboxRepository {

    private val _entries = MutableStateFlow<List<InboxEntry>>(emptyList())
    val entries: StateFlow<List<InboxEntry>> = _entries

    /** Latest Feishu messageId per chat — used by FeishuChannel.reply for in-thread replies. */
    private val _lastMessageIdByChat = MutableStateFlow<Map<String, String>>(emptyMap())

    fun lastMessageId(sessionKey: String): String? = _lastMessageIdByChat.value[sessionKey]

    fun appendInbound(entry: InboxEntry, messageId: String? = null) {
        _entries.update { current ->
            // Most-recent first; cap to avoid unbounded growth.
            (listOf(entry) + current.filterNot {
                it.sessionKey == entry.sessionKey && it.receivedAt == entry.receivedAt
            }).take(200)
        }
        if (messageId != null) {
            _lastMessageIdByChat.update { it + (entry.sessionKey to messageId) }
        }
    }

    fun markRead(sessionKey: String) {
        _entries.update { list ->
            list.map { if (it.sessionKey == sessionKey) it.copy(unread = false) else it }
        }
    }

    fun clear() {
        _entries.value = emptyList()
        _lastMessageIdByChat.value = emptyMap()
    }

    fun unreadCount(): Int = _entries.value.count { it.unread }
}
