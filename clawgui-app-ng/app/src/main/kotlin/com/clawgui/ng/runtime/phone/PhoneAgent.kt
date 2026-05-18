package com.clawgui.ng.runtime.phone

import com.clawgui.ng.runtime.phone.actions.ActionHandler
import com.clawgui.ng.runtime.phone.actions.ActionParser
import com.clawgui.ng.runtime.phone.memory.MemoryStore
import com.clawgui.ng.runtime.phone.model.ModelClient
import com.clawgui.ng.runtime.phone.model.ModelConfig
import com.clawgui.ng.runtime.phone.model.adapters.MessageBuilder
import com.clawgui.ng.runtime.device.DeviceInterface
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
    var currentTask: String = ""
        private set

    /** Optional sink: invoked with (stepIndex, thinking, action, jpegBytes?, success, message?). */
    var traceSink: ((Int, String, Map<String, Any?>, ByteArray?, Boolean, String?) -> Unit)? = null

    // Stuck-detection sliding window. Each entry is the fingerprint of one
    // step's chosen action. If the last `WINDOW` fingerprints collapse to a
    // single value we conclude the model is stuck and force a finish.
    private val recentActions: ArrayDeque<String> = ArrayDeque()
    private var consecutiveFailures = 0
    private var consecutiveWaits = 0

    suspend fun run(task: String): String {
        context.clear()
        stepCount = 0
        recentActions.clear()
        consecutiveFailures = 0
        consecutiveWaits = 0
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

    /**
     * Start a follow-up task on top of an existing conversation.
     *
     * Unlike `run(task)` which clears state, this:
     *  1. Resets the per-task budget (stepCount, stuck windows) so the loop
     *     can use up to maxSteps again — without this, a long previous task
     *     would leave 0 budget for the follow-up.
     *  2. Injects the new task as a synthetic user message into the live
     *     `context`, so the model sees the whole history *plus* the new
     *     instruction (instead of having `task=` swallowed silently because
     *     it's no longer the first step).
     *  3. Returns the first step's result; the caller's outer loop keeps
     *     iterating with bare `step()` after.
     */
    suspend fun continueTask(task: String): StepResult {
        currentTask = task
        stepCount = 0
        recentActions.clear()
        consecutiveFailures = 0
        consecutiveWaits = 0
        // Append the follow-up as a fresh user turn so adapters that key off
        // the last user message (AutoGLM, Qwen-VL, etc.) re-anchor on it.
        context.add(mapOf(
            "role" to "user",
            "content" to listOf(mapOf("type" to "text", "text" to "** 后续任务 **\n$task")),
        ))
        modelClient.adapter.addHistory("[用户追加] $task")
        return executeStep(userPrompt = task, isFirst = false)
    }

    fun reset() {
        context.clear()
        stepCount = 0
        recentActions.clear()
        consecutiveFailures = 0
        consecutiveWaits = 0
        modelClient.adapter.clearHistory()
    }

    private suspend fun executeStep(
        userPrompt: String? = null,
        isFirst: Boolean = false,
    ): StepResult {
        stepCount++

        val screenshotBytes = device.screenshot()
        val compressed = screenshotBytes?.let { raw ->
            val q = try {
                com.clawgui.ng.runtime.RuntimeContainer.settings.screenshotQuality.value
            } catch (_: Throwable) {
                com.clawgui.ng.runtime.phone.util.ScreenshotCompressor.Quality.MEDIUM
            }
            com.clawgui.ng.runtime.phone.util.ScreenshotCompressor.compress(raw, q)
        }
        val imageBase64 = compressed?.let { Base64.getEncoder().encodeToString(it) } ?: ""
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

        // Emit trace event before stuck detection so a forced finish still
        // gets recorded.
        runCatching {
            traceSink?.invoke(stepCount, thinking, action, compressed, actionResult.success, actionResult.message)
        }

        val assistantContent = "<think>$thinking</think><answer>$actionStr</answer>"
        context.add(MessageBuilder.createAssistantMessage(assistantContent))
        modelClient.adapter.addHistory(describeStep(action, thinking))

        // Surface failure messages back to the model so it can correct.
        // Without this the model never learns its action was rejected and
        // repeats the same hallucinated action forever.
        if (!actionResult.success && !actionResult.message.isNullOrBlank()) {
            context.add(mapOf(
                "role" to "system",
                "content" to "上一步执行未生效:${actionResult.message}",
            ))
            modelClient.adapter.addHistory("[执行反馈] ${actionResult.message}")
        }

        // ── Stuck detection ─────────────────────────────────────────────
        val fingerprint = actionFingerprint(action)
        recentActions.addLast(fingerprint)
        while (recentActions.size > STUCK_WINDOW) recentActions.removeFirst()

        val actionName = action["action"] as? String
        // Track repeated Waits — model spamming wait-loops with no progress.
        consecutiveWaits = if (actionName == "Wait") consecutiveWaits + 1 else 0
        // Track consecutive failures from ActionHandler.
        consecutiveFailures = if (!actionResult.success) consecutiveFailures + 1 else 0

        val (stuck, stuckReason) = checkStuck()
        if (stuck) {
            actionHandler.finalRestore()
            logger.warning("PhoneAgent forced finish: $stuckReason")
            return StepResult(
                success = false,
                finished = true,
                action = mapOf("_metadata" to "finish", "message" to stuckReason),
                thinking = thinking,
                message = stuckReason,
            )
        }

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

    /**
     * A canonical string for an action that ignores cosmetic differences
     * (timestamps, coord jitter at the same target) so we can detect loops.
     */
    private fun actionFingerprint(action: Map<String, Any?>): String {
        val name = action["action"] as? String ?: "?"
        val extra = when (name) {
            "Tap", "Long Press", "Double Tap" -> {
                // Round coords to nearest 20-unit bucket so "tapping the same button" hashes equal.
                val el = action["element"] as? List<*> ?: return name
                el.joinToString(",") { v ->
                    val n = (v as? Number)?.toInt() ?: 0
                    (n / 20 * 20).toString()
                }
            }
            "Swipe" -> {
                val s = action["start"] as? List<*>
                val e = action["end"] as? List<*>
                (s?.joinToString(",") ?: "") + "->" + (e?.joinToString(",") ?: "")
            }
            "Type", "Type_Name" -> "text=${(action["text"] as? String).orEmpty().take(40)}"
            "Launch" -> "app=${action["app"]}"
            "Wait" -> "wait=${action["duration"]}"
            else -> action.toString().take(80)
        }
        return "$name|$extra"
    }

    private fun checkStuck(): Pair<Boolean, String> {
        // Same exact action repeated for the whole window → likely stuck.
        // BUT: Swipe and Wait are *legitimately* repeated for long-running
        // tasks ("刷 5 分钟抖音" = many swipes; "等加载完" = many waits).
        // We only bail when the fingerprint repeating is one of the
        // "should-cause-state-change" actions — Tap / Type / Launch / Back.
        if (recentActions.size >= STUCK_WINDOW && recentActions.toSet().size == 1) {
            val name = recentActions.last().substringBefore('|')
            val benign = name == "Swipe" || name == "Wait" || name == "Note"
            if (!benign) {
                return true to "智能体连续 ${STUCK_WINDOW} 步都在重复同一个 $name 动作,屏幕仍未变化,已自动停止。"
            }
        }
        if (consecutiveWaits >= MAX_CONSECUTIVE_WAITS) {
            return true to "智能体连续 $consecutiveWaits 次只在等待,页面长时间无变化,已自动停止。"
        }
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            return true to "智能体连续 $consecutiveFailures 步执行失败,已自动停止。常见原因:模型输出了未支持的动作 / 坐标超界 / 应用未响应。"
        }
        return false to ""
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
        private const val STUCK_WINDOW = 4              // identical action this many turns in a row → bail
        private const val MAX_CONSECUTIVE_WAITS = 5     // 5 Waits with no progress → bail
        private const val MAX_CONSECUTIVE_FAILURES = 4  // 4 unknown/failed actions in a row → bail
    }
}
