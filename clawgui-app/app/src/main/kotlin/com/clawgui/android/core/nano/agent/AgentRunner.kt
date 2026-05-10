package com.clawgui.android.core.nano.agent

import com.clawgui.android.core.nano.agent.tools.ToolRegistry
import com.clawgui.android.core.nano.providers.LLMProvider
import com.clawgui.android.core.nano.providers.ToolCallRequest
import com.clawgui.android.core.nano.utils.buildAssistantMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

private const val DEFAULT_MAX_ITER_MSG =
    "I reached the maximum number of tool call iterations ({max_iterations}) " +
        "without completing the task. You can try breaking the task into smaller steps."
private const val DEFAULT_ERROR_MSG = "Sorry, I encountered an error calling the AI model."

data class AgentRunSpec(
    val initialMessages: List<Map<String, Any?>>,
    val tools: ToolRegistry,
    val model: String,
    val maxIterations: Int,
    val temperature: Float? = null,
    val maxTokens: Int? = null,
    val reasoningEffort: String? = null,
    val hook: AgentHook? = null,
    val errorMessage: String? = DEFAULT_ERROR_MSG,
    val maxIterationsMessage: String? = null,
    val concurrentTools: Boolean = false,
    val failOnToolError: Boolean = false,
)

data class AgentRunResult(
    val finalContent: String?,
    val messages: List<Map<String, Any?>>,
    val toolsUsed: List<String> = emptyList(),
    val usage: Map<String, Int> = emptyMap(),
    val stopReason: String = "completed",
    val error: String? = null,
    val toolEvents: List<Map<String, String>> = emptyList(),
)

class AgentRunner(private val provider: LLMProvider) {

    suspend fun run(spec: AgentRunSpec): AgentRunResult {
        val hook = spec.hook ?: AgentHook()
        val messages = spec.initialMessages.toMutableList()
        val toolsUsed = mutableListOf<String>()
        val toolEvents = mutableListOf<Map<String, String>>()
        var usage = mapOf("prompt_tokens" to 0, "completion_tokens" to 0)
        var finalContent: String? = null
        var error: String? = null
        var stopReason = "max_iterations"

        for (iteration in 0 until spec.maxIterations) {
            val context = AgentHookContext(iteration = iteration, messages = messages)
            hook.beforeIteration(context)

            val response = if (hook.wantsStreaming()) {
                provider.chatStreamWithRetry(
                    messages = messages,
                    tools = spec.tools.getDefinitions(),
                    model = spec.model,
                    temperature = spec.temperature ?: provider.generation.temperature,
                    maxTokens = spec.maxTokens ?: provider.generation.maxTokens,
                    reasoningEffort = spec.reasoningEffort ?: provider.generation.reasoningEffort,
                    onContentDelta = { delta -> hook.onStream(context, delta) },
                )
            } else {
                provider.chatWithRetry(
                    messages = messages,
                    tools = spec.tools.getDefinitions(),
                    model = spec.model,
                    temperature = spec.temperature ?: provider.generation.temperature,
                    maxTokens = spec.maxTokens ?: provider.generation.maxTokens,
                    reasoningEffort = spec.reasoningEffort ?: provider.generation.reasoningEffort,
                )
            }

            val rawUsage = response.usage
            usage = mapOf(
                "prompt_tokens" to (rawUsage["prompt_tokens"] ?: 0),
                "completion_tokens" to (rawUsage["completion_tokens"] ?: 0),
            )
            context.response = response
            context.usage = usage
            context.toolCalls = response.toolCalls

            if (response.hasToolCalls) {
                if (hook.wantsStreaming()) hook.onStreamEnd(context, resuming = true)
                messages.add(buildAssistantMessage(
                    content = response.content ?: "",
                    toolCalls = response.toolCalls.map { it.toOpenAiToolCall() },
                    reasoningContent = response.reasoningContent,
                ))
                toolsUsed.addAll(response.toolCalls.map { it.name })
                hook.beforeExecuteTools(context)

                val (results, newEvents, fatalError) = executeTools(spec, response.toolCalls)
                toolEvents.addAll(newEvents)
                context.toolResults = results
                context.toolEvents = newEvents
                if (fatalError != null) {
                    error = "Error: ${fatalError::class.simpleName}: $fatalError"
                    stopReason = "tool_error"
                    context.error = error
                    context.stopReason = stopReason
                    hook.afterIteration(context)
                    break
                }
                response.toolCalls.zip(results).forEach { (tc, result) ->
                    messages.add(mapOf(
                        "role" to "tool",
                        "tool_call_id" to tc.id,
                        "name" to tc.name,
                        "content" to result,
                    ))
                }
                hook.afterIteration(context)
                continue
            }

            if (hook.wantsStreaming()) hook.onStreamEnd(context, resuming = false)
            val clean = hook.finalizeContent(context, response.content)

            if (response.finishReason == "error") {
                finalContent = clean ?: spec.errorMessage ?: DEFAULT_ERROR_MSG
                stopReason = "error"
                error = finalContent
                context.finalContent = finalContent
                context.error = error
                context.stopReason = stopReason
                hook.afterIteration(context)
                break
            }

            messages.add(buildAssistantMessage(clean, reasoningContent = response.reasoningContent))
            finalContent = clean
            stopReason = "completed"
            context.finalContent = finalContent
            context.stopReason = stopReason
            hook.afterIteration(context)
            break
        }

        if (stopReason == "max_iterations") {
            val template = spec.maxIterationsMessage ?: DEFAULT_MAX_ITER_MSG
            finalContent = template.replace("{max_iterations}", spec.maxIterations.toString())
        }

        return AgentRunResult(
            finalContent = finalContent,
            messages = messages,
            toolsUsed = toolsUsed,
            usage = usage,
            stopReason = stopReason,
            error = error,
            toolEvents = toolEvents,
        )
    }

    private suspend fun executeTools(
        spec: AgentRunSpec,
        toolCalls: List<ToolCallRequest>,
    ): Triple<List<Any?>, List<Map<String, String>>, Throwable?> {
        val rawResults = if (spec.concurrentTools) {
            coroutineScope {
                toolCalls.map { tc -> async { runTool(spec, tc) } }
                    .map { it.await() }
            }
        } else {
            toolCalls.map { runTool(spec, it) }
        }
        val results = rawResults.map { it.first }
        val events = rawResults.map { it.second }
        val fatal = rawResults.mapNotNull { it.third }.firstOrNull()
        return Triple(results, events, fatal)
    }

    private suspend fun runTool(
        spec: AgentRunSpec,
        toolCall: ToolCallRequest,
    ): Triple<Any?, Map<String, String>, Throwable?> {
        return try {
            val result = spec.tools.execute(toolCall.name, toolCall.arguments)
            val detail = when {
                result == null -> "(empty)"
                else -> result.toString().replace("\n", " ").trim()
                    .let { if (it.isEmpty()) "(empty)" else if (it.length > 120) it.take(120) + "..." else it }
            }
            Triple(result, mapOf(
                "name" to toolCall.name,
                "status" to if (result is String && result.startsWith("Error")) "error" else "ok",
                "detail" to detail,
            ), null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = "Error: ${e::class.simpleName}: $e"
            val event = mapOf("name" to toolCall.name, "status" to "error", "detail" to e.toString())
            if (spec.failOnToolError) Triple(msg, event, e)
            else Triple(msg, event, null)
        }
    }
}
