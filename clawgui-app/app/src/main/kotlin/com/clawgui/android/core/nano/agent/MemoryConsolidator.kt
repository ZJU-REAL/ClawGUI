package com.clawgui.android.core.nano.agent

import com.clawgui.android.core.nano.providers.LLMProvider
import com.clawgui.android.core.nano.session.Session
import com.clawgui.android.core.nano.session.SessionManager
import com.clawgui.android.core.nano.utils.estimateMessageTokens
import com.clawgui.android.core.nano.utils.estimatePromptTokens
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.logging.Logger

class MemoryConsolidator(
    workspace: File,
    private val provider: LLMProvider,
    private val model: String,
    private val sessions: SessionManager,
    private val contextWindowTokens: Int,
    private val buildMessages: (
        history: List<Map<String, Any?>>,
        currentMessage: String,
        channel: String,
        chatId: String,
    ) -> List<Map<String, Any?>>,
    private val getToolDefinitions: () -> List<Map<String, Any?>>,
    private val maxCompletionTokens: Int = 4096,
) {
    val memory = AgentMemory(workspace)
    private val locks = HashMap<String, Mutex>()
    private val logger = Logger.getLogger("MemoryConsolidator")

    companion object {
        private const val SAFETY_BUFFER = 1024
        private const val MAX_CONSOLIDATION_ROUNDS = 5
    }

    private fun getLock(sessionKey: String): Mutex = synchronized(locks) {
        locks.getOrPut(sessionKey) { Mutex() }
    }

    suspend fun consolidateMessages(messages: List<Map<String, Any?>>): Boolean =
        memory.consolidate(messages, provider, model)

    fun pickConsolidationBoundary(session: Session, tokensToRemove: Int): Pair<Int, Int>? {
        val start = session.lastConsolidated
        if (start >= session.messages.size || tokensToRemove <= 0) return null

        var removedTokens = 0
        var lastBoundary: Pair<Int, Int>? = null
        for (idx in start until session.messages.size) {
            val message = session.messages[idx]
            if (idx > start && message["role"] == "user") {
                lastBoundary = idx to removedTokens
                if (removedTokens >= tokensToRemove) return lastBoundary
            }
            removedTokens += estimateMessageTokens(message)
        }
        return lastBoundary
    }

    fun estimateSessionPromptTokens(session: Session): Int {
        val history = session.getHistory()
        val (channel, chatId) = if (":" in session.key) {
            val parts = session.key.split(":", limit = 2)
            parts[0] to parts[1]
        } else "cli" to "direct"
        val probeMessages = buildMessages(history, "[token-probe]", channel, chatId)
        return estimatePromptTokens(probeMessages, getToolDefinitions())
    }

    suspend fun maybeConsolidateByTokens(session: Session) {
        if (session.messages.isEmpty() || contextWindowTokens <= 0) return
        getLock(session.key).withLock {
            val budget = contextWindowTokens - maxCompletionTokens - SAFETY_BUFFER
            val target = budget / 2
            var estimated = estimateSessionPromptTokens(session)
            if (estimated <= 0 || estimated < budget) return@withLock

            for (round in 0 until MAX_CONSOLIDATION_ROUNDS) {
                if (estimated <= target) return@withLock
                val boundary = pickConsolidationBoundary(session, maxOf(1, estimated - target))
                    ?: return@withLock
                val chunk = session.messages.subList(session.lastConsolidated, boundary.first).toList()
                if (chunk.isEmpty()) return@withLock
                logger.info("Token consolidation round $round for ${session.key}: $estimated/$contextWindowTokens chunk=${chunk.size}")
                if (!consolidateMessages(chunk)) return@withLock
                session.lastConsolidated = boundary.first
                sessions.save(session)
                estimated = estimateSessionPromptTokens(session)
                if (estimated <= 0) return@withLock
            }
        }
    }
}
