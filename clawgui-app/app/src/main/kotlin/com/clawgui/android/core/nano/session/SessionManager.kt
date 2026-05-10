package com.clawgui.android.core.nano.session

import com.clawgui.android.core.nano.utils.anyToJson
import com.clawgui.android.core.nano.utils.jsonToAny
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.time.Instant
import java.util.logging.Logger

private val sessionJson = Json { ignoreUnknownKeys = true }
private val logger = Logger.getLogger("SessionManager")

class Session(
    val key: String,
    val messages: MutableList<Map<String, Any?>> = mutableListOf(),
    var createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
    val metadata: MutableMap<String, Any?> = mutableMapOf(),
    var lastConsolidated: Int = 0,
) {
    fun addMessage(role: String, content: String, vararg extra: Pair<String, Any?>) {
        val msg = mutableMapOf<String, Any?>(
            "role" to role,
            "content" to content,
            "timestamp" to Instant.now().toString(),
        )
        extra.forEach { (k, v) -> msg[k] = v }
        messages.add(msg)
        updatedAt = System.currentTimeMillis()
    }

    private fun findLegalStart(msgs: List<Map<String, Any?>>): Int {
        val declared = mutableSetOf<String>()
        var start = 0
        for ((i, msg) in msgs.withIndex()) {
            when (msg["role"] as? String) {
                "assistant" -> {
                    @Suppress("UNCHECKED_CAST")
                    (msg["tool_calls"] as? List<Map<String, Any?>>)?.forEach { tc ->
                        (tc["id"] as? String)?.let { declared.add(it) }
                    }
                }
                "tool" -> {
                    val tid = msg["tool_call_id"] as? String
                    if (tid != null && tid !in declared) {
                        start = i + 1
                        declared.clear()
                        msgs.subList(start, i + 1).forEach { prev ->
                            if (prev["role"] == "assistant") {
                                @Suppress("UNCHECKED_CAST")
                                (prev["tool_calls"] as? List<Map<String, Any?>>)?.forEach { tc ->
                                    (tc["id"] as? String)?.let { declared.add(it) }
                                }
                            }
                        }
                    }
                }
            }
        }
        return start
    }

    fun getHistory(maxMessages: Int = 500): List<Map<String, Any?>> {
        var sliced = messages.drop(lastConsolidated).takeLast(maxMessages).toMutableList()
        val firstUser = sliced.indexOfFirst { it["role"] == "user" }
        if (firstUser > 0) sliced = sliced.drop(firstUser).toMutableList()
        val start = findLegalStart(sliced)
        if (start > 0) sliced = sliced.drop(start).toMutableList()
        return sliced.map { msg ->
            val entry = mutableMapOf<String, Any?>(
                "role" to msg["role"],
                "content" to (msg["content"] ?: ""),
            )
            for (key in listOf("tool_calls", "tool_call_id", "name")) {
                if (key in msg) entry[key] = msg[key]
            }
            entry
        }
    }

    fun clear() {
        messages.clear()
        lastConsolidated = 0
        updatedAt = System.currentTimeMillis()
    }
}

class SessionManager(workspaceDir: File) {
    private val sessionsDir = File(workspaceDir, "sessions").also { it.mkdirs() }
    private val cache = mutableMapOf<String, Session>()

    private fun sessionFile(key: String): File {
        val safe = key.replace(Regex("[<>:/\\\\|?*]"), "_").trim()
        return File(sessionsDir, "$safe.jsonl")
    }

    fun getOrCreate(key: String): Session {
        return cache[key] ?: (load(key) ?: Session(key = key)).also { cache[key] = it }
    }

    private fun load(key: String): Session? {
        val file = sessionFile(key)
        if (!file.exists()) return null
        return try {
            val msgs = mutableListOf<Map<String, Any?>>()
            var createdAt = System.currentTimeMillis()
            val metadata = mutableMapOf<String, Any?>()
            var lastConsolidated = 0
            file.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) continue
                    val obj = sessionJson.parseToJsonElement(trimmed)
                    if (obj is JsonObject) {
                        if ((obj["_type"] as? JsonPrimitive)?.content == "metadata") {
                            (obj["created_at"] as? JsonPrimitive)?.content?.let {
                                createdAt = Instant.parse(it).toEpochMilli()
                            }
                            lastConsolidated = (obj["last_consolidated"] as? JsonPrimitive)?.intOrNull ?: 0
                            (obj["metadata"] as? JsonObject)?.forEach { (k, v) ->
                                metadata[k] = jsonToAny(v)
                            }
                        } else {
                            @Suppress("UNCHECKED_CAST")
                            msgs.add(jsonToAny(obj) as Map<String, Any?>)
                        }
                    }
                }
            }
            Session(key = key, messages = msgs, createdAt = createdAt, metadata = metadata, lastConsolidated = lastConsolidated)
        } catch (e: Exception) {
            logger.warning("Failed to load session $key: $e")
            null
        }
    }

    fun save(session: Session) {
        try {
            sessionFile(session.key).bufferedWriter(Charsets.UTF_8).use { w ->
                val meta = buildJsonObject {
                    put("_type", "metadata")
                    put("key", session.key)
                    put("created_at", Instant.ofEpochMilli(session.createdAt).toString())
                    put("updated_at", Instant.ofEpochMilli(session.updatedAt).toString())
                    put("metadata", JsonObject(session.metadata.mapValues { anyToJson(it.value) }))
                    put("last_consolidated", session.lastConsolidated)
                }
                w.write(meta.toString()); w.newLine()
                session.messages.forEach { msg ->
                    w.write(anyToJson(msg).toString()); w.newLine()
                }
            }
            cache[session.key] = session
        } catch (e: Exception) {
            logger.warning("Failed to save session ${session.key}: $e")
        }
    }

    fun invalidate(key: String) { cache.remove(key) }

    fun delete(key: String): Boolean {
        cache.remove(key)
        val file = sessionFile(key)
        return try {
            if (file.exists()) file.delete() else true
        } catch (e: Exception) {
            logger.warning("Failed to delete session $key: $e")
            false
        }
    }

    fun listSessions(): List<Map<String, Any?>> =
        sessionsDir.listFiles { f -> f.extension == "jsonl" }
            ?.mapNotNull { file ->
                try {
                    val first = file.bufferedReader(Charsets.UTF_8).use { it.readLine() }?.trim()
                        ?: return@mapNotNull null
                    val obj = sessionJson.parseToJsonElement(first)
                    if (obj is JsonObject && (obj["_type"] as? JsonPrimitive)?.content == "metadata") {
                        val key = (obj["key"] as? JsonPrimitive)?.content ?: file.nameWithoutExtension
                        val displayName = (obj["metadata"] as? JsonObject)
                            ?.get("display_name")
                            ?.let { it as? JsonPrimitive }
                            ?.content
                        mapOf<String, Any?>(
                            "key" to key,
                            "created_at" to (obj["created_at"] as? JsonPrimitive)?.content,
                            "updated_at" to (obj["updated_at"] as? JsonPrimitive)?.content,
                            "display_name" to displayName,
                        )
                    } else null
                } catch (_: Exception) { null }
            }
            ?.sortedByDescending { it["updated_at"] as? String ?: "" }
            ?: emptyList()
}
