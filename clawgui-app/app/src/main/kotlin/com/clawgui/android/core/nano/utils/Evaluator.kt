package com.clawgui.android.core.nano.utils

import com.clawgui.android.core.nano.providers.LLMProvider
import java.util.logging.Level
import java.util.logging.Logger

private val logger: Logger = Logger.getLogger("Evaluator")

private val EVALUATE_TOOL: List<Map<String, Any?>> = listOf(
    mapOf(
        "type" to "function",
        "function" to mapOf(
            "name" to "evaluate_notification",
            "description" to "Decide whether the user should be notified about this background task result.",
            "parameters" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "should_notify" to mapOf(
                        "type" to "boolean",
                        "description" to "true = result contains actionable/important info the user should see; false = routine or empty, safe to suppress",
                    ),
                    "reason" to mapOf(
                        "type" to "string",
                        "description" to "One-sentence reason for the decision",
                    ),
                ),
                "required" to listOf("should_notify"),
            ),
        ),
    )
)

private const val SYSTEM_PROMPT = "You are a notification gate for a background agent. " +
    "You will be given the original task and the agent's response. " +
    "Call the evaluate_notification tool to decide whether the user should be notified.\n\n" +
    "Notify when the response contains actionable information, errors, completed deliverables, " +
    "or anything the user explicitly asked to be reminded about.\n\n" +
    "Suppress when the response is a routine status check with nothing new, a confirmation " +
    "that everything is normal, or essentially empty."

suspend fun evaluateResponse(
    response: String,
    taskContext: String,
    provider: LLMProvider,
    model: String,
): Boolean {
    return try {
        val llmResponse = provider.chatWithRetry(
            messages = listOf(
                mapOf("role" to "system", "content" to SYSTEM_PROMPT),
                mapOf(
                    "role" to "user",
                    "content" to "## Original task\n$taskContext\n\n## Agent response\n$response",
                ),
            ),
            tools = EVALUATE_TOOL,
            model = model,
            maxTokens = 256,
            temperature = 0.0f,
        )

        if (!llmResponse.hasToolCalls) {
            logger.warning("evaluateResponse: no tool call returned, defaulting to notify")
            return true
        }

        val args = llmResponse.toolCalls[0].arguments
        val shouldNotify = args["should_notify"] as? Boolean ?: true
        val reason = args["reason"] as? String ?: ""
        logger.info("evaluateResponse: should_notify=$shouldNotify, reason=$reason")
        shouldNotify
    } catch (e: Exception) {
        logger.log(Level.SEVERE, "evaluateResponse failed, defaulting to notify", e)
        true
    }
}
