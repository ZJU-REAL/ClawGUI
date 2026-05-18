package com.clawgui.ng.runtime.phone.memory

import kotlinx.datetime.Clock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

@Serializable
enum class MemoryType {
    @SerialName("user_preference") USER_PREFERENCE,
    @SerialName("contact") CONTACT,
    @SerialName("task_pattern") TASK_PATTERN,
    @SerialName("app_usage") APP_USAGE,
    @SerialName("task_history") TASK_HISTORY,
    @SerialName("user_correction") USER_CORRECTION,
    @SerialName("general") GENERAL,
    @SerialName("contact_app_binding") CONTACT_APP_BINDING,
}

@Serializable
data class Memory(
    val id: String,
    val content: String,
    @SerialName("memory_type") val memoryType: MemoryType,
    @SerialName("created_at") val createdAt: String = Clock.System.now().toString(),
    @SerialName("last_accessed") val lastAccessed: String = Clock.System.now().toString(),
    @SerialName("access_count") val accessCount: Int = 1,
    val importance: Float = 0.5f,
    val metadata: Map<String, String> = emptyMap(),
    val embedding: List<Float>? = null,
) {
    fun withUpdatedAccess(): Memory = copy(
        lastAccessed = Clock.System.now().toString(),
        accessCount = accessCount + 1,
        importance = minOf(1.0f, importance + 0.05f),
    )
}

class SimpleEmbedder(private val dim: Int = 128) {

    fun encode(text: String): List<Float> {
        val emb = FloatArray(dim)
        val lower = text.lowercase()
        for ((i, char) in lower.withIndex()) {
            val idx = char.code % dim
            emb[idx] += 1.0f / (i + 1)
        }
        for (i in 0 until text.length - 1) {
            val bigram = text.substring(i, i + 2)
            val idx = ((bigram.hashCode() % dim) + dim) % dim
            emb[idx] += 0.5f
        }
        val norm = kotlin.math.sqrt(emb.sumOf { it.toDouble() * it.toDouble() }.toFloat())
        if (norm > 0f) {
            for (i in emb.indices) emb[i] /= norm
        }
        return emb.toList()
    }
}

class MemoryStore(
    private val storageDir: File,
    private val embeddingDim: Int = 128,
    private val similarityThreshold: Float = 0.85f,
    private val maxMemories: Int = 10000,
) {
    private val memories: MutableMap<String, Memory> = mutableMapOf()
    private val embedder = SimpleEmbedder(embeddingDim)
    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }

    init {
        storageDir.mkdirs()
        loadMemories()
    }

    fun add(
        content: String,
        memoryType: MemoryType,
        metadata: Map<String, String> = emptyMap(),
        importance: Float = 0.5f,
    ): Memory {
        val embedding = embedder.encode(content)
        val existing = findSimilar(embedding, memoryType)
        if (existing != null) {
            val updated = existing.withUpdatedAccess().copy(
                metadata = existing.metadata + metadata,
            )
            memories[existing.id] = updated
            saveMemories()
            return updated
        }

        val memory = Memory(
            id = generateId(content, memoryType),
            content = content,
            memoryType = memoryType,
            metadata = metadata,
            importance = importance,
            embedding = embedding,
        )
        memories[memory.id] = memory
        enforceMemoryLimit()
        saveMemories()
        return memory
    }

    fun search(
        query: String,
        memoryTypes: List<MemoryType>? = null,
        topK: Int = 5,
        minImportance: Float = 0.0f,
    ): List<Memory> {
        val queryEmbedding = embedder.encode(query)
        val scored = memories.values
            .filter { memoryTypes == null || it.memoryType in memoryTypes }
            .filter { it.importance >= minImportance }
            .filter { it.embedding != null }
            .map { memory ->
                val sim = cosineSimilarity(queryEmbedding, memory.embedding!!)
                memory to (sim * 0.7f + memory.importance * 0.3f)
            }
            .sortedByDescending { it.second }
            .take(topK)
            .map { it.first }

        val updated = scored.map { it.withUpdatedAccess() }
        for (m in updated) memories[m.id] = m
        if (updated.isNotEmpty()) saveMemories()
        return updated
    }

    fun getByType(memoryType: MemoryType, limit: Int = 10): List<Memory> =
        memories.values
            .filter { it.memoryType == memoryType }
            .sortedByDescending { it.importance }
            .take(limit)

    fun getRecent(limit: Int = 10): List<Memory> =
        memories.values.sortedByDescending { it.lastAccessed }.take(limit)

    fun delete(memoryId: String): Boolean {
        if (memoryId !in memories) return false
        memories.remove(memoryId)
        saveMemories()
        return true
    }

    fun clear(memoryType: MemoryType? = null) {
        if (memoryType != null) {
            memories.entries.removeAll { it.value.memoryType == memoryType }
        } else {
            memories.clear()
        }
        saveMemories()
    }

    fun getStats(): Map<String, Any> {
        val byType = memories.values
            .groupBy { it.memoryType.name.lowercase() }
            .mapValues { it.value.size }
        return mapOf(
            "total_memories" to memories.size,
            "by_type" to byType,
            "storage_dir" to storageDir.absolutePath,
        )
    }

    fun exportMemories(): List<Memory> = memories.values.toList()

    fun importMemories(items: List<Memory>) {
        for (item in items) {
            val withEmb = if (item.embedding != null) item
            else item.copy(embedding = embedder.encode(item.content))
            memories[item.id] = withEmb
        }
        saveMemories()
    }

    private fun findSimilar(embedding: List<Float>, memoryType: MemoryType?): Memory? =
        memories.values
            .filter { memoryType == null || it.memoryType == memoryType }
            .filter { it.embedding != null }
            .firstOrNull { cosineSimilarity(embedding, it.embedding!!) >= similarityThreshold }

    private fun generateId(content: String, memoryType: MemoryType): String {
        val input = "$content:${memoryType.name}:${Clock.System.now()}"
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    private fun cosineSimilarity(a: List<Float>, b: List<Float>): Float {
        var dot = 0.0f; var normA = 0.0f; var normB = 0.0f
        for (i in a.indices) {
            dot += a[i] * b[i]; normA += a[i] * a[i]; normB += b[i] * b[i]
        }
        return dot / (kotlin.math.sqrt((normA * normB).toDouble()).toFloat() + 1e-8f)
    }

    private fun enforceMemoryLimit() {
        if (memories.size <= maxMemories) return
        val keepIds = memories.values
            .sortedWith(compareByDescending<Memory> { it.importance }.thenByDescending { it.lastAccessed })
            .take(maxMemories)
            .map { it.id }
            .toSet()
        memories.keys.retainAll(keepIds)
    }

    private fun saveMemories() {
        val file = File(storageDir, "memories_meta.json")
        file.writeText(json.encodeToString(memories.values.toList()))
    }

    private fun loadMemories() {
        val file = File(storageDir, "memories_meta.json")
        if (!file.exists()) return
        val list: List<Memory> = json.decodeFromString(file.readText())
        for (memory in list) {
            memories[memory.id] = if (memory.embedding == null) {
                memory.copy(embedding = embedder.encode(memory.content))
            } else memory
        }
    }
}
