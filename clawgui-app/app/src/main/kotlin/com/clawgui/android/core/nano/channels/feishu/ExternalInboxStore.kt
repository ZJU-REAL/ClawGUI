package com.clawgui.android.core.nano.channels.feishu

import com.clawgui.android.core.nano.utils.anyToJson
import com.clawgui.android.core.nano.utils.jsonToAny
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * 外部 channel 的入/出站消息 jsonl 日志,InboxScreen 读这个文件倒序展示。
 * **不是会话历史** —— 会话历史仍归 SessionManager 管。
 *
 * 文件格式:每行一条 JSON,`direction` 为 `in` / `out`。
 */
class ExternalInboxStore(workspaceDir: File) {
    private val file = File(workspaceDir, "external_inbox.jsonl").also { it.parentFile?.mkdirs() }
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    private val _version = MutableStateFlow(0L)
    /** 每次 append 后 +1,InboxScreen 观察这个触发重新读文件。 */
    val version: StateFlow<Long> = _version.asStateFlow()

    suspend fun appendInbound(
        channel: String,
        chatId: String,
        senderId: String,
        content: String,
        messageId: String?,
    ) = append(
        mapOf(
            "direction" to "in",
            "ts_ms" to System.currentTimeMillis(),
            "channel" to channel,
            "chat_id" to chatId,
            "sender_id" to senderId,
            "content" to content,
            "message_id" to messageId,
        )
    )

    suspend fun appendOutbound(
        channel: String,
        chatId: String,
        content: String,
        metadata: Map<String, String>,
    ) = append(
        mapOf(
            "direction" to "out",
            "ts_ms" to System.currentTimeMillis(),
            "channel" to channel,
            "chat_id" to chatId,
            "content" to content,
            "metadata" to metadata,
        )
    )

    suspend fun readRecent(limit: Int = 200): List<Map<String, Any?>> = mutex.withLock {
        if (!file.exists()) return emptyList()
        file.readLines()
            .takeLast(limit)
            .mapNotNull { line ->
                runCatching {
                    @Suppress("UNCHECKED_CAST")
                    jsonToAny(json.parseToJsonElement(line)) as? Map<String, Any?>
                }.getOrNull()
            }
    }

    private suspend fun append(entry: Map<String, Any?>) {
        mutex.withLock {
            val line = (anyToJson(entry) as JsonObject).toString()
            file.appendText("$line\n")
        }
        _version.value = _version.value + 1
    }
}
