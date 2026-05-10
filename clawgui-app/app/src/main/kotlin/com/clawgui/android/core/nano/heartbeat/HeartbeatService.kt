package com.clawgui.android.core.nano.heartbeat

import com.clawgui.android.core.nano.providers.LLMProvider
import com.clawgui.android.core.nano.utils.currentTimeStr
import com.clawgui.android.core.nano.utils.evaluateResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger

private val HEARTBEAT_TOOL: List<Map<String, Any?>> = listOf(
    mapOf(
        "type" to "function",
        "function" to mapOf(
            "name" to "heartbeat",
            "description" to "Report heartbeat decision after reviewing tasks.",
            "parameters" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "action" to mapOf(
                        "type" to "string",
                        "enum" to listOf("skip", "run"),
                        "description" to "skip = nothing to do, run = has active tasks",
                    ),
                    "tasks" to mapOf(
                        "type" to "string",
                        "description" to "Natural-language summary of active tasks (required for run)",
                    ),
                ),
                "required" to listOf("action"),
            ),
        ),
    )
)

class HeartbeatService(
    private val workspace: File,
    private val provider: LLMProvider,
    private val model: String,
    private val onExecute: (suspend (String) -> String?)? = null,
    private val onNotify: (suspend (String) -> Unit)? = null,
    private val intervalS: Long = 30 * 60,
    private val enabled: Boolean = true,
    private val timezone: String? = null,
) {
    private val heartbeatFile = File(workspace, "HEARTBEAT.md")
    private var running = false
    private var loopJob: Job? = null
    private val logger = Logger.getLogger("HeartbeatService")

    fun start(scope: CoroutineScope) {
        if (!enabled) { logger.info("Heartbeat disabled"); return }
        if (running) { logger.warning("Heartbeat already running"); return }
        running = true
        loopJob = scope.launch { runLoop() }
        logger.info("Heartbeat started (every ${intervalS}s)")
    }

    fun stop() {
        running = false
        loopJob?.cancel()
        loopJob = null
    }

    private suspend fun runLoop() {
        while (running) {
            try {
                delay(intervalS * 1000L)
                if (running) tick()
            } catch (_: CancellationException) {
                break
            } catch (e: Exception) {
                logger.log(Level.WARNING, "Heartbeat error", e)
            }
        }
    }

    private suspend fun tick() {
        val content = readHeartbeatFile() ?: run {
            logger.fine("Heartbeat: HEARTBEAT.md missing or empty")
            return
        }
        logger.info("Heartbeat: checking for tasks...")
        try {
            val (action, tasks) = decide(content)
            if (action != "run") {
                logger.info("Heartbeat: OK (nothing to report)")
                return
            }
            logger.info("Heartbeat: tasks found, executing...")
            val response = onExecute?.invoke(tasks) ?: return
            if (response.isNotEmpty()) {
                val shouldNotify = evaluateResponse(response, tasks, provider, model)
                if (shouldNotify) {
                    logger.info("Heartbeat: completed, delivering response")
                    onNotify?.invoke(response)
                } else {
                    logger.info("Heartbeat: silenced by post-run evaluation")
                }
            }
        } catch (e: Exception) {
            logger.log(Level.SEVERE, "Heartbeat execution failed", e)
        }
    }

    private suspend fun decide(content: String): Pair<String, String> {
        val response = provider.chatWithRetry(
            messages = listOf(
                mapOf("role" to "system", "content" to "You are a heartbeat agent. Call the heartbeat tool to report your decision."),
                mapOf("role" to "user", "content" to
                    "Current Time: ${currentTimeStr(timezone)}\n\nReview the following HEARTBEAT.md and decide whether there are active tasks.\n\n$content"),
            ),
            tools = HEARTBEAT_TOOL,
            model = model,
        )
        if (!response.hasToolCalls) return "skip" to ""
        val args = response.toolCalls[0].arguments
        return (args["action"] as? String ?: "skip") to (args["tasks"] as? String ?: "")
    }

    private fun readHeartbeatFile(): String? =
        if (heartbeatFile.exists()) runCatching { heartbeatFile.readText(Charsets.UTF_8).ifEmpty { null } }.getOrNull()
        else null

    suspend fun triggerNow(): String? {
        val content = readHeartbeatFile() ?: return null
        val (action, tasks) = decide(content)
        if (action != "run") return null
        return onExecute?.invoke(tasks)
    }
}
