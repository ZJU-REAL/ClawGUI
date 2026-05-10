package com.clawgui.android.core.nano.trace

import com.clawgui.android.core.nano.utils.anyToJson
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.logging.Logger

class TraceRecorder private constructor(
    private val workspaceDir: File,
    val traceId: String,
    val sessionKey: String,
    val turnId: String,
    val channel: String,
    val chatId: String,
    private val enabled: Boolean,
) {
    private val logger = Logger.getLogger("TraceRecorder")
    private val lock = Any()
    private val traceDate = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString()
    private val traceDir = File(workspaceDir, "traces/$traceDate").also { if (enabled) it.mkdirs() }
    private val artifactDir = File(traceDir, "artifacts/$traceId").also { if (enabled) it.mkdirs() }
    private val traceFile = File(traceDir, "$traceId.jsonl")

    init {
        record(
            eventType = "trace_started",
            payload = mapOf(
                "workspace" to workspaceDir.absolutePath,
                "artifacts_dir" to artifactDir.absolutePath,
            ),
        )
    }

    fun isEnabled(): Boolean = enabled

    fun record(
        eventType: String,
        payload: Map<String, Any?> = emptyMap(),
        iteration: Int? = null,
    ) {
        if (!enabled) return
        val line = buildJsonObject {
            put("trace_id", traceId)
            put("session_key", sessionKey)
            put("turn_id", turnId)
            put("channel", channel)
            put("chat_id", chatId)
            put("event_type", eventType)
            put("timestamp", Instant.now().toString())
            if (iteration != null) put("iteration", iteration)
            put("payload", anyToJson(sanitize(payload)))
        }.toString()

        synchronized(lock) {
            try {
                traceFile.appendText(line + "\n", Charsets.UTF_8)
            } catch (e: Exception) {
                logger.warning("Failed to append trace event $eventType: $e")
            }
        }
    }

    fun recordError(
        eventType: String,
        throwable: Throwable,
        payload: Map<String, Any?> = emptyMap(),
        iteration: Int? = null,
    ) {
        record(
            eventType = eventType,
            payload = payload + mapOf(
                "error_type" to (throwable::class.qualifiedName ?: throwable::class.simpleName),
                "error_message" to (throwable.message ?: throwable.toString()),
            ),
            iteration = iteration,
        )
    }

    fun saveArtifact(name: String, bytes: ByteArray): String? {
        if (!enabled) return null
        val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val file = File(artifactDir, safeName)
        return try {
            file.writeBytes(bytes)
            file.absolutePath
        } catch (e: Exception) {
            logger.warning("Failed to save trace artifact $name: $e")
            null
        }
    }

    private fun sanitize(value: Any?, depth: Int = 0): Any? {
        if (depth >= 6) return "[depth-truncated]"
        return when (value) {
            null -> null
            is String -> truncateString(value)
            is ByteArray -> "[binary:${value.size} bytes]"
            is Map<*, *> -> value.entries.associate { (k, v) ->
                k.toString() to sanitize(v, depth + 1)
            }
            is List<*> -> value.take(50).map { sanitize(it, depth + 1) }
            is Array<*> -> value.take(50).map { sanitize(it, depth + 1) }
            else -> value
        }
    }

    private fun truncateString(value: String, maxChars: Int = 4_000): String {
        if (value.length <= maxChars) return value
        return value.take(maxChars) + "... [truncated ${value.length - maxChars} chars]"
    }

    companion object {
        fun create(
            workspaceDir: File,
            sessionKey: String,
            turnId: String,
            channel: String,
            chatId: String,
            enabled: Boolean,
        ): TraceRecorder {
            val traceId = "${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
            return TraceRecorder(
                workspaceDir = workspaceDir,
                traceId = traceId,
                sessionKey = sessionKey,
                turnId = turnId,
                channel = channel,
                chatId = chatId,
                enabled = enabled,
            )
        }
    }
}
