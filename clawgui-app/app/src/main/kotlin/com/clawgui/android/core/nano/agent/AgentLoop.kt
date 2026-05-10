package com.clawgui.android.core.nano.agent

import com.clawgui.android.core.nano.bus.InboundMessage
import com.clawgui.android.core.nano.bus.MessageBus
import com.clawgui.android.core.nano.bus.OutboundMessage
import com.clawgui.android.core.nano.command.CommandContext
import com.clawgui.android.core.nano.command.CommandRouter
import com.clawgui.android.core.nano.command.registerBuiltinCommands
import com.clawgui.android.core.nano.providers.LLMProvider
import com.clawgui.android.core.nano.session.Session
import com.clawgui.android.core.nano.session.SessionManager
import com.clawgui.android.core.nano.trace.TraceRecorder
import com.clawgui.android.core.nano.trace.summarizeMessages
import com.clawgui.android.core.nano.utils.stripThink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.Instant
import java.util.logging.Level
import java.util.logging.Logger

class AgentLoop(
    val bus: MessageBus,
    val provider: LLMProvider,
    val workspace: File,
    val scope: CoroutineScope,
    model: String? = null,
    val maxIterations: Int = 40,
    val contextWindowTokens: Int = 65_536,
    sessionManager: SessionManager? = null,
    val timezone: String? = null,
    val maxConcurrentRequests: Int = 3,
    private val traceRecorderProvider: (() -> TraceRecorder?)? = null,
) {
    private val traceRecorder: TraceRecorder?
        get() = traceRecorderProvider?.invoke()

    val model: String = model ?: provider.getDefaultModel()
    val context = ContextBuilder(workspace, timezone)
    val sessions: SessionManager = sessionManager ?: SessionManager(workspace)
    val tools = com.clawgui.android.core.nano.agent.tools.ToolRegistry()
    val runner = AgentRunner(provider)
    val subagents = SubagentManager(
        provider = provider,
        workspace = workspace,
        bus = bus,
        scope = scope,
        model = this.model,
    )
    val consolidator = MemoryConsolidator(
        workspace = workspace,
        provider = provider,
        model = this.model,
        sessions = sessions,
        contextWindowTokens = contextWindowTokens,
        buildMessages = { history, currentMessage, channel, chatId ->
            context.buildMessages(history = history, currentMessage = currentMessage, channel = channel, chatId = chatId)
        },
        getToolDefinitions = { tools.getDefinitions() },
    )

    private var running = false
    private val sessionLocks = mutableMapOf<String, Channel<Unit>>()
    private val activeTasks = mutableMapOf<String, MutableList<kotlinx.coroutines.Job>>()
    private val semaphore = if (maxConcurrentRequests > 0)
        kotlinx.coroutines.sync.Semaphore(maxConcurrentRequests) else null
    private val logger = Logger.getLogger("AgentLoop")
    private var lastUsage = mapOf("prompt_tokens" to 0, "completion_tokens" to 0)

    val commands = CommandRouter()

    init {
        registerBuiltinCommands(commands)
    }

    private fun setToolContext(channel: String, chatId: String) {
        tools.get("message")?.let {
            if (it is com.clawgui.android.core.nano.agent.tools.MessageTool) it.setContext(channel, chatId)
        }
        tools.get("spawn")?.let {
            if (it is com.clawgui.android.core.nano.agent.tools.SpawnTool) it.setContext(channel, chatId)
        }
        tools.get("cron")?.let {
            if (it is com.clawgui.android.core.nano.agent.tools.CronTool) it.setContext(channel, chatId)
        }
    }

    suspend fun run() {
        running = true
        logger.info("Agent loop started")
        while (running) {
            val msg = try {
                withTimeoutOrNull(1000L) { bus.consumeInbound() }
            } catch (_: CancellationException) {
                if (!running) break
                continue
            } catch (e: Exception) {
                logger.warning("Error consuming inbound: $e")
                continue
            } ?: continue

            val raw = msg.content.trim()
            if (commands.isPriority(raw)) {
                val ctx = CommandContext(msg = msg, key = msg.sessionKey, raw = raw, loop = this)
                commands.dispatchPriority(ctx)?.let { bus.publishOutbound(it) }
                continue
            }
            val job = scope.launch { dispatch(msg) }
            activeTasks.getOrPut(msg.sessionKey) { mutableListOf() }.add(job)
            job.invokeOnCompletion { activeTasks[msg.sessionKey]?.remove(job) }
        }
    }

    private suspend fun dispatch(msg: InboundMessage) {
        val lock = sessionLocks.getOrPut(msg.sessionKey) { Channel(1) }
        com.clawgui.android.core.util.Log.i(
            "AgentLoop", msg.sessionKey,
            "dispatch start channel=${msg.channel} chat=${msg.chatId}",
        )
        val t0 = System.currentTimeMillis()
        try {
            semaphore?.acquire()
            try {
                val out = processMessage(msg)
                if (out != null) {
                    bus.publishOutbound(out)
                    com.clawgui.android.core.util.Log.i(
                        "AgentLoop", msg.sessionKey,
                        "dispatch ok (${System.currentTimeMillis() - t0}ms) → publishOutbound len=${out.content.length}",
                    )
                } else {
                    com.clawgui.android.core.util.Log.i(
                        "AgentLoop", msg.sessionKey,
                        "dispatch ok (${System.currentTimeMillis() - t0}ms) → no outbound (swallowed by tool)",
                    )
                }
            } finally {
                semaphore?.release()
            }
        } catch (_: CancellationException) {
            logger.info("Task cancelled for session ${msg.sessionKey}")
            com.clawgui.android.core.util.Log.i(
                "AgentLoop", msg.sessionKey,
                "dispatch cancelled (${System.currentTimeMillis() - t0}ms)",
            )
        } catch (e: Exception) {
            logger.log(Level.SEVERE, "Error processing message for session ${msg.sessionKey}", e)
            com.clawgui.android.core.util.Log.e(
                "AgentLoop", msg.sessionKey,
                "dispatch failed (${System.currentTimeMillis() - t0}ms)", e,
            )
            traceRecorderProvider?.invoke()?.recordError(
                eventType = "dispatch_error",
                throwable = e,
                payload = mapOf(
                    "session_key" to msg.sessionKey,
                    "channel" to msg.channel,
                    "chat_id" to msg.chatId,
                ),
            )
            bus.publishOutbound(OutboundMessage(
                channel = msg.channel, chatId = msg.chatId, content = "Sorry, I encountered an error.",
                metadata = msg.metadata + mapOf("sessionKey" to msg.sessionKey),
            ))
        }
    }

    private suspend fun processMessage(msg: InboundMessage): OutboundMessage? {
        if (msg.channel == "system") {
            val (channel, chatId) = if (":" in msg.chatId) {
                val parts = msg.chatId.split(":", limit = 2)
                parts[0] to parts[1]
            } else "cli" to msg.chatId
            val key = "$channel:$chatId"
            val session = sessions.getOrCreate(key)
            consolidator.maybeConsolidateByTokens(session)
            setToolContext(channel, chatId)
            val history = session.getHistory()
            val currentRole = if (msg.senderId == "subagent") "assistant" else "user"
            val messages = context.buildMessages(
                history = history, currentMessage = msg.content, channel = channel, chatId = chatId, currentRole = currentRole,
            )
            try {
                val (finalContent, _, allMsgs) = runAgentLoop(messages, channel, chatId)
                saveTurn(session, allMsgs, 1 + history.size)
                sessions.save(session)
                return OutboundMessage(
                    channel = channel, chatId = chatId,
                    content = finalContent ?: "Background task completed.",
                    metadata = msg.metadata + mapOf("sessionKey" to key),
                )
            } catch (e: CancellationException) {
                recordCancellation(session, msg)
                throw e
            }
        }

        val key = msg.sessionKey
        val session = sessions.getOrCreate(key)
        consolidator.maybeConsolidateByTokens(session)
        val raw = msg.content.trim()
        val ctx = CommandContext(msg = msg, key = key, raw = raw, loop = this)
        commands.dispatch(ctx)?.let { return it }

        setToolContext(msg.channel, msg.chatId)
        val history = session.getHistory()
        val initialMessages = context.buildMessages(
            history = history,
            currentMessage = msg.content,
            media = msg.media?.takeIf { it.isNotEmpty() },
            channel = msg.channel, chatId = msg.chatId,
        )
        try {
            val (finalContent, _, allMsgs) = runAgentLoop(initialMessages, msg.channel, msg.chatId)
            val content = finalContent ?: "I've completed processing but have no response to give."
            saveTurn(session, allMsgs, 1 + history.size)
            sessions.save(session)
            val messageTool = tools.get("message")
            if (messageTool is com.clawgui.android.core.nano.agent.tools.MessageTool && messageTool.sentInTurn) return null
            return OutboundMessage(
                channel = msg.channel, chatId = msg.chatId, content = content,
                metadata = msg.metadata + mapOf("sessionKey" to key),
            )
        } catch (e: CancellationException) {
            recordCancellation(session, msg)
            throw e
        }
    }

    private fun recordCancellation(session: Session, msg: InboundMessage) {
        val now = Instant.now().toString()
        session.messages.add(mapOf(
            "role" to "user",
            "content" to msg.content,
            "timestamp" to now,
        ))
        session.messages.add(mapOf(
            "role" to "assistant",
            "content" to "[Task cancelled by user — do NOT treat it as executed or reuse it as precedent.]",
            "timestamp" to now,
        ))
        session.updatedAt = System.currentTimeMillis()
        try { sessions.save(session) } catch (_: Exception) {}
    }

    private suspend fun runAgentLoop(
        initialMessages: List<Map<String, Any?>>,
        channel: String,
        chatId: String,
        onStream: (suspend (String) -> Unit)? = null,
    ): Triple<String?, List<String>, List<Map<String, Any?>>> {
        val loop = this
        // Resolve recorder once for the whole loop so all events of a single turn
        // land on the same recorder, even if the provider is re-pointed mid-flight.
        val recorder = traceRecorder
        val hook = object : AgentHook() {
            override fun wantsStreaming() = onStream != null
            override suspend fun beforeIteration(context: AgentHookContext) {
                recorder?.record(
                    eventType = "llm_request",
                    iteration = context.iteration,
                    payload = mapOf(
                        "model" to model,
                        "messages" to summarizeMessages(context.messages),
                        "tool_definitions" to tools.getDefinitions(),
                    ),
                )
            }
            override suspend fun beforeExecuteTools(context: AgentHookContext) {
                for (tc in context.toolCalls) {
                    logger.fine("Tool call: ${tc.name}(${tc.arguments})")
                }
                loop.setToolContext(channel, chatId)
                recorder?.record(
                    eventType = "tool_calls_planned",
                    iteration = context.iteration,
                    payload = mapOf(
                        "content" to context.response?.content,
                        "reasoning_content" to context.response?.reasoningContent,
                        "finish_reason" to context.response?.finishReason,
                        "usage" to context.usage,
                        "tool_calls" to context.toolCalls.map { tc ->
                            mapOf(
                                "id" to tc.id,
                                "name" to tc.name,
                                "arguments" to tc.arguments,
                            )
                        },
                    ),
                )
            }
            override suspend fun afterIteration(context: AgentHookContext) {
                if (context.toolCalls.isNotEmpty()) {
                    val toolResults = context.toolCalls.mapIndexed { idx, tc ->
                        mapOf(
                            "id" to tc.id,
                            "name" to tc.name,
                            "arguments" to tc.arguments,
                            "result" to context.toolResults.getOrNull(idx),
                            "event" to context.toolEvents.getOrNull(idx),
                        )
                    }
                    recorder?.record(
                        eventType = "tool_results",
                        iteration = context.iteration,
                        payload = mapOf(
                            "stop_reason" to context.stopReason,
                            "error" to context.error,
                            "tool_results" to toolResults,
                        ),
                    )
                } else {
                    recorder?.record(
                        eventType = "llm_response",
                        iteration = context.iteration,
                        payload = mapOf(
                            "content" to (context.finalContent ?: context.response?.content),
                            "reasoning_content" to context.response?.reasoningContent,
                            "finish_reason" to context.response?.finishReason,
                            "usage" to context.usage,
                            "stop_reason" to context.stopReason,
                            "error" to context.error,
                        ),
                    )
                }
            }
            override fun finalizeContent(context: AgentHookContext, content: String?): String? {
                if (content == null) return null
                return stripThink(content).ifEmpty { null }
            }
        }
        val result = runner.run(AgentRunSpec(
            initialMessages = initialMessages,
            tools = tools,
            model = model,
            maxIterations = maxIterations,
            hook = hook,
            errorMessage = "Sorry, I encountered an error calling the AI model.",
            concurrentTools = true,
        ))
        lastUsage = result.usage
        if (result.stopReason == "max_iterations") logger.warning("Max iterations ($maxIterations) reached")
        if (result.stopReason == "error") logger.severe("LLM returned error: ${result.finalContent?.take(200)}")
        recorder?.record(
            eventType = "agent_loop_finished",
            payload = mapOf(
                "channel" to channel,
                "chat_id" to chatId,
                "stop_reason" to result.stopReason,
                "final_content" to result.finalContent,
                "usage" to result.usage,
                "tools_used" to result.toolsUsed,
            ),
        )
        return Triple(result.finalContent, result.toolsUsed, result.messages)
    }

    private fun saveTurn(session: Session, messages: List<Map<String, Any?>>, skip: Int) {
        for (m in messages.drop(skip)) {
            val entry = m.toMutableMap()
            val role = entry["role"] as? String
            val content = entry["content"]
            if (role == "assistant" && content == null && !entry.containsKey("tool_calls")) continue
            if (role == "tool" && content is String && content.length > TOOL_RESULT_MAX_CHARS) {
                entry["content"] = content.take(TOOL_RESULT_MAX_CHARS) + "\n... (truncated)"
            }
            if (role == "user" && content is String && content.startsWith("[Runtime Context")) {
                val parts = content.split("\n\n", limit = 2)
                if (parts.size > 1 && parts[1].isNotBlank()) entry["content"] = parts[1]
                else continue
            }
            entry.putIfAbsent("timestamp", Instant.now().toString())
            session.messages.add(entry)
        }
        session.updatedAt = System.currentTimeMillis()
    }

    fun stop() {
        running = false
        logger.info("Agent loop stopping")
    }

    fun cancelSession(sessionKey: String) {
        val jobs = activeTasks[sessionKey]?.toList().orEmpty()
        com.clawgui.android.core.util.Log.i(
            "AgentLoop", sessionKey,
            "cancelSession cancelling ${jobs.size} active job(s)",
        )
        jobs.forEach { it.cancel() }
    }

    fun getLastUsage(): Map<String, Int> = lastUsage

    suspend fun processDirect(
        content: String,
        sessionKey: String = "cli:direct",
        channel: String = "cli",
        chatId: String = "direct",
    ): OutboundMessage? {
        val msg = InboundMessage(
            channel = channel,
            senderId = "user",
            chatId = chatId,
            content = content,
            sessionKeyOverride = sessionKey,
        )
        return processMessage(msg)
    }

    companion object {
        private const val TOOL_RESULT_MAX_CHARS = 16_000
    }
}
