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
    /** Raw JSON body of the `<plan>` block this step emitted (null if absent).
     *  ChatViewModel hands it to [PlanProtocol.apply] to advance the
     *  decorative plan card shown in the chat bubble. */
    val planOps: String? = null,
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
    private var consecutiveParseFailures = 0

    /**
     * Run a task to completion. [userImages] is an optional list of base64-
     * encoded JPEGs the user attached to the chat turn (e.g. "post this
     * photo to Weibo with a caption") — they are sent to the VLM **once** on
     * the first step alongside the screenshot, then preserved in `context`
     * for the rest of the loop so the model can keep referring back to them
     * without us re-shipping them every step.
     */
    suspend fun run(task: String, userImages: List<String> = emptyList()): String {
        context.clear()
        stepCount = 0
        recentActions.clear()
        consecutiveFailures = 0
        consecutiveWaits = 0
        consecutiveParseFailures = 0
        currentTask = task
        modelClient.adapter.clearHistory()

        var result = executeStep(task, isFirst = true, userImages = userImages)
        if (result.finished) return result.message ?: "Task completed"

        while (stepCount < agentConfig.maxSteps) {
            result = executeStep(isFirst = false)
            if (result.finished) return result.message ?: "Task completed"
        }
        return "Max steps reached"
    }

    suspend fun step(task: String? = null, userImages: List<String> = emptyList()): StepResult {
        val isFirst = context.isEmpty()
        require(!isFirst || task != null) { "Task is required for the first step" }
        return executeStep(task, isFirst, userImages)
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
    suspend fun continueTask(task: String, userImages: List<String> = emptyList()): StepResult {
        currentTask = task
        stepCount = 0
        recentActions.clear()
        consecutiveFailures = 0
        consecutiveWaits = 0
        consecutiveParseFailures = 0
        // Append the follow-up as a fresh user turn so adapters that key off
        // the last user message (AutoGLM, Qwen-VL, etc.) re-anchor on it.
        // Note we do NOT inline userImages here — executeStep's buildMessages
        // gets to interleave them with the next screenshot in one go.
        context.add(mapOf(
            "role" to "user",
            "content" to listOf(mapOf("type" to "text", "text" to "** 后续任务 **\n$task")),
        ))
        modelClient.adapter.addHistory("[用户追加] $task")
        return executeStep(userPrompt = task, isFirst = false, userImages = userImages)
    }

    fun reset() {
        context.clear()
        stepCount = 0
        recentActions.clear()
        consecutiveFailures = 0
        consecutiveWaits = 0
        consecutiveParseFailures = 0
        modelClient.adapter.clearHistory()
    }

    /**
     * Feed back a user-typed answer in response to the model's most recent
     * `do(action="Ask", question=...)` step. The next `step()` call will see
     * the answer as a system feedback turn and is expected to proceed with
     * the original task. Doesn't reset stuck counters — Ask isn't progress
     * the model deserves credit for.
     *
     * We use a system role (not user) on purpose: a user role would re-anchor
     * many adapters onto a new task, which would cause the model to think the
     * user is re-issuing the whole job from scratch.
     */
    fun injectUserAnswer(question: String, answer: String) {
        val safeAnswer = answer.ifBlank { "(用户没填,自己判断后继续)" }
        context.add(mapOf(
            "role" to "system",
            "content" to "你上一步 Ask 的问题:「${question.trim()}」\n用户回答:$safeAnswer\n请基于这个回答继续原任务,不要重复提问。",
        ))
        modelClient.adapter.addHistory("[用户回答 Ask] $safeAnswer")
    }

    private suspend fun executeStep(
        userPrompt: String? = null,
        isFirst: Boolean = false,
        userImages: List<String> = emptyList(),
    ): StepResult {
        stepCount++

        // Step 1 is pure planning — no screen actions yet, so we skip the
        // screenshot entirely. Saves a slow Shizuku / wadb roundtrip on the
        // first turn AND prevents the model from being distracted by
        // ClawGUI's own UI (which it would otherwise try to describe / poke
        // at). From step 2 onwards we're inside the target app, so we
        // resume normal capture.
        val compressed: ByteArray? = if (isFirst) null else {
            val screenshotBytes = device.screenshot()
            screenshotBytes?.let { raw ->
                val q = try {
                    com.clawgui.ng.runtime.RuntimeContainer.settings.screenshotQuality.value
                } catch (_: Throwable) {
                    com.clawgui.ng.runtime.phone.util.ScreenshotCompressor.Quality.MEDIUM
                }
                com.clawgui.ng.runtime.phone.util.ScreenshotCompressor.compress(raw, q)
            }
        }
        val imageBase64 = compressed?.let { Base64.getEncoder().encodeToString(it) } ?: ""
        val currentApp = if (isFirst) "(尚未启动目标 App)" else
            try { device.exec("dumpsys activity activities | grep mResumedActivity | head -1").trim() } catch (_: Exception) { "unknown" }

        val messages = modelClient.adapter.buildMessages(
            task = userPrompt ?: currentTask,
            imageBase64 = imageBase64,
            currentApp = currentApp,
            context = context,
            lang = agentConfig.lang,
            extraUserImages = userImages,
        )
        context.clear()
        context.addAll(messages)

        val response = try {
            modelClient.request(context)
        } catch (e: Exception) {
            logger.log(Level.WARNING, "Model request failed", e)
            return StepResult(success = false, finished = true, action = mapOf("_metadata" to "finish"), thinking = "", message = "Model error: ${e.message}")
        }

        // Plan ops live in a separate `<plan>...</plan>` block. Extract them
        // off the raw response *before* we strip them out of `thinking` so
        // the action parser doesn't see them.
        val planOps = PlanProtocol.extract(response.rawContent)
        val thinking = PlanProtocol.stripBlock(response.thinking)
        val actionStr = response.action

        // Parse failure recovery: don't immediately finish — give the model
        // one more shot by surfacing the error as a system feedback message
        // and forcing a no-op Wait so the loop spins one more step. Bail
        // only if two parses fail back-to-back; otherwise transient model
        // formatting glitches end every long task at step 1.
        val action = try {
            val parsed = ActionParser.parse(actionStr)
            consecutiveParseFailures = 0
            parsed
        } catch (e: Exception) {
            consecutiveParseFailures++
            if (consecutiveParseFailures >= 2) {
                mapOf(
                    "_metadata" to "finish",
                    "message" to "连续 2 步无法解析模型输出。最近一次原文:${actionStr.take(200)}",
                )
            } else {
                // Tell the model what went wrong via a synthetic system
                // turn injected later (look for the surface message below);
                // for this step we emit a 1-second Wait so the loop
                // continues and the model can retry.
                context.add(mapOf(
                    "role" to "system",
                    "content" to "上一步 `<answer>` 解析失败:${e.message ?: "格式不对"}。请重新按规定输出三块,`<answer>` 里只能是一行合法的 `do(action=\"...\", ...)` 或 `finish(message=\"...\")`,不要加任何前缀或解释。",
                ))
                modelClient.adapter.addHistory("[解析失败] 重写 <answer>")
                mapOf("_metadata" to "do", "action" to "Wait", "duration" to "1 seconds")
            }
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
                planOps = planOps,
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
            planOps = planOps,
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
