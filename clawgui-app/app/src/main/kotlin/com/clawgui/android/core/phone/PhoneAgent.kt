package com.clawgui.android.core.phone

import com.clawgui.android.core.phone.actions.ActionHandler
import com.clawgui.android.core.phone.actions.ActionParser
import com.clawgui.android.core.phone.memory.MemoryStore
import com.clawgui.android.core.phone.model.ModelClient
import com.clawgui.android.core.phone.model.ModelConfig
import com.clawgui.android.core.phone.model.adapters.MessageBuilder
import com.clawgui.android.core.phone.platform.DeviceInterface
import java.io.File
import java.util.Base64
import java.util.logging.Level
import java.util.logging.Logger

data class AgentConfig(
    val maxSteps: Int = 100,
    val lang: String = "cn",
    val enableMemory: Boolean = true,
    val memoryDir: String = "memory_db",
    val userId: String = "default",
)

data class StepResult(
    val success: Boolean,
    val finished: Boolean,
    val action: Map<String, Any?>,
    val thinking: String,
    val message: String? = null,
)

class PhoneAgent(
    private val device: DeviceInterface,
    private val modelConfig: ModelConfig = ModelConfig(),
    private val agentConfig: AgentConfig = AgentConfig(),
    private val confirmationCallback: (suspend (String) -> Boolean)? = null,
    private val takeoverCallback: (suspend (String) -> Unit)? = null,
    workspaceDir: File? = null,
) {
    private val modelClient = ModelClient(modelConfig)
    private val actionHandler = ActionHandler(device, confirmationCallback, takeoverCallback)
    private val memoryStore: MemoryStore? = if (agentConfig.enableMemory && workspaceDir != null) {
        try {
            MemoryStore(File(workspaceDir, agentConfig.memoryDir))
        } catch (e: Exception) {
            logger.log(Level.WARNING, "MemoryStore init failed", e)
            null
        }
    } else null

    private val context = mutableListOf<Map<String, Any?>>()
    private var stepCount = 0
    private var currentTask = ""

    suspend fun run(task: String): String {
        context.clear()
        stepCount = 0
        currentTask = task
        modelClient.adapter.clearHistory()

        var result = executeStep(task, isFirst = true)
        if (result.finished) return result.message ?: "Task completed"

        while (stepCount < agentConfig.maxSteps) {
            result = executeStep(isFirst = false)
            if (result.finished) return result.message ?: "Task completed"
        }
        return "Max steps reached"
    }

    suspend fun step(task: String? = null): StepResult {
        val isFirst = context.isEmpty()
        require(!isFirst || task != null) { "Task is required for the first step" }
        return executeStep(task, isFirst)
    }

    fun reset() {
        context.clear()
        stepCount = 0
        modelClient.adapter.clearHistory()
    }

    private suspend fun executeStep(
        userPrompt: String? = null,
        isFirst: Boolean = false,
    ): StepResult {
        stepCount++

        val screenshotBytes = device.screenshot()
        val imageBase64 = screenshotBytes?.let { Base64.getEncoder().encodeToString(it) } ?: ""
        val currentApp = try { device.exec("dumpsys activity activities | grep mResumedActivity | head -1").trim() } catch (_: Exception) { "unknown" }

        val messages = modelClient.adapter.buildMessages(
            task = userPrompt ?: currentTask,
            imageBase64 = imageBase64,
            currentApp = currentApp,
            context = context,
            lang = agentConfig.lang,
        )
        context.clear()
        context.addAll(messages)

        val response = try {
            modelClient.request(context)
        } catch (e: Exception) {
            logger.log(Level.WARNING, "Model request failed", e)
            return StepResult(success = false, finished = true, action = mapOf("_metadata" to "finish"), thinking = "", message = "Model error: ${e.message}")
        }

        val thinking = response.thinking
        val actionStr = response.action

        val action = try {
            ActionParser.parse(actionStr)
        } catch (_: Exception) {
            mapOf("_metadata" to "finish", "message" to actionStr)
        }

        // Remove image from last user message to save context
        if (context.isNotEmpty()) {
            context[context.size - 1] = MessageBuilder.removeImagesFromMessage(context.last())
        }

        val actionResult = actionHandler.execute(action)

        val assistantContent = "<think>$thinking</think><answer>$actionStr</answer>"
        context.add(MessageBuilder.createAssistantMessage(assistantContent))
        modelClient.adapter.addHistory(describeStep(action, thinking))

        val finished = action["_metadata"] == "finish" || actionResult.shouldFinish
        if (finished) actionHandler.finalRestore()
        return StepResult(
            success = actionResult.success,
            finished = finished,
            action = action,
            thinking = thinking,
            message = actionResult.message ?: action["message"] as? String,
        )
    }

    /** 给 Qwen-VL / GUI-Owl 这类每轮重构 messages 的 adapter 累加"上一步做了什么"
     *  用于注入下一轮 user message。其他 adapter 忽略。 */
    private fun describeStep(action: Map<String, Any?>, thinking: String): String {
        val name = action["action"] as? String
        val suffix = when (name) {
            "Tap", "Long Press" -> (action["element"] as? List<*>)?.let { "at $it" } ?: ""
            "Type", "Type_Name" -> "\"${action["text"]}\""
            "Launch" -> "${action["app"]}"
            else -> ""
        }
        val headline = if (thinking.isNotBlank()) thinking else (name ?: "step")
        return if (suffix.isNotBlank()) "$headline ($name $suffix)" else headline
    }

    val currentContext: List<Map<String, Any?>> get() = context.toList()
    val currentStepCount: Int get() = stepCount

    /** 外部兜底:循环异常退出 / 达到 max_steps 时调用,确保剪贴板被还原。 */
    fun cleanup() {
        actionHandler.finalRestore()
    }

    companion object {
        private val logger = Logger.getLogger("PhoneAgent")
    }
}
