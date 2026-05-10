package com.clawgui.android.core.nano.agent.tools

import com.clawgui.android.core.nano.agent.AgentMemory
import com.clawgui.android.core.nano.trace.TraceRecorder
import com.clawgui.android.core.nano.utils.anyToJson
import com.clawgui.android.core.nano.utils.buildImageContentBlocks
import com.clawgui.android.core.nano.utils.detectImageMime
import com.clawgui.android.core.phone.AgentConfig
import com.clawgui.android.core.phone.PhoneAgent
import com.clawgui.android.core.phone.StepResult
import com.clawgui.android.core.phone.model.ModelConfig
import com.clawgui.android.core.phone.platform.DeviceInterface
import com.clawgui.android.platform.shizuku.DeviceController
import com.clawgui.android.platform.shizuku.ImeController
import kotlinx.coroutines.sync.Mutex
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.logging.Logger

class GUITool(
    private val device: DeviceInterface,
    private val modelConfig: ModelConfig,
    private val defaultMaxSteps: Int = 50,
    private val lang: String = "cn",
    private val workspaceDir: File? = null,
    private val onProgress: ((step: Int, hint: String, thinking: String?, actionJson: String?) -> Unit)? = null,
    private val traceRecorderProvider: (() -> TraceRecorder?)? = null,
) : Tool() {

    private val traceRecorder: TraceRecorder?
        get() = traceRecorderProvider?.invoke()
    override val name = "gui_execute"
    override val description = """Execute a GUI automation task on this Android device via VLM screen understanding.

Use ONLY when the user explicitly requested a phone action (tap a button, send a message, open an app, fill a form, etc.). Do NOT use for greetings, questions, self-introductions, or general conversation — reply with plain text directly in those cases.

Describe the task in natural language and the tool will use a vision-language model to understand the phone screen, then perform actions (tap, swipe, type text, launch app, back, home, etc.) in a loop until the task is completed.

Examples:
  - "Open WeChat and send a message to Zhang San saying I will be late"
  - "Search for Bluetooth headphones on Taobao and add to cart"
  - "Open Settings and turn on Wi-Fi""""
    override val parameters: Map<String, Any?> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "task" to mapOf(
                "type" to "string",
                "description" to "Natural language description of the GUI task to perform.",
            ),
            "max_steps" to mapOf(
                "type" to "integer",
                "description" to "Maximum action steps (default: $defaultMaxSteps).",
                "minimum" to 1,
                "maximum" to 200,
            ),
        ),
        "required" to listOf("task"),
    )

    private val logger = Logger.getLogger("GUITool")

    override suspend fun execute(params: Map<String, Any?>): Any? {
        val task = params["task"] as? String ?: return "Error: task is required"
        val maxSteps = (params["max_steps"] as? Number)?.toInt() ?: defaultMaxSteps
        // Resolve once per invocation so the same recorder is used across the task's
        // start / steps / artifact-save / finish events even if the provider later
        // points at a different turn.
        val recorder = traceRecorder

        // Global device mutex: only one GUI task runs at a time across all channels.
        // A second request (e.g. app UI busy + Feishu message arrives) fast-fails and
        // the agent passes the error back as a tool result.
        if (!deviceMutex.tryLock()) {
            logger.warning("GUI task rejected: device busy")
            recorder?.record(
                eventType = "gui_task_rejected",
                payload = mapOf("reason" to "device_busy", "task" to task),
            )
            return "Error: 设备正在处理其他任务,请稍后再试。"
        }

        logger.info("GUI task starting: max_steps=$maxSteps, task=${task.take(80)}")
        recorder?.record(
            eventType = "gui_task_started",
            payload = mapOf(
                "task" to task,
                "max_steps" to maxSteps,
            ),
        )

        return try {
            try {
                val agentConfig = AgentConfig(maxSteps = maxSteps, lang = lang)
                val agent = PhoneAgent(
                    device = device,
                    modelConfig = modelConfig,
                    agentConfig = agentConfig,
                    workspaceDir = workspaceDir,
                )

                // 任务执行期间切到自家 IME,保证中文/Unicode 输入不污染用户剪贴板。
                val imeSwap = activateOurIMEIfPossible()
                try {
                onProgress?.invoke(0, "VLM 规划中…", null, null)
                var result = agent.step(task)
                recordStep(recorder, agent.currentStepCount, result)
                reportProgress(agent.currentStepCount, result)
                while (!result.finished && agent.currentStepCount < maxSteps) {
                    onProgress?.invoke(agent.currentStepCount, "VLM 规划中…", result.thinking, encodeAction(result.action))
                    result = agent.step()
                    recordStep(recorder, agent.currentStepCount, result)
                    reportProgress(agent.currentStepCount, result)
                }
                val finalMessage = result.message ?: if (result.finished) "Task completed" else "Max steps reached"
                logger.info("GUI task completed: ${finalMessage.take(120)}")
                recorder?.record(
                    eventType = "gui_task_finished",
                    payload = mapOf(
                        "finished" to result.finished,
                        "success" to result.success,
                        "steps" to agent.currentStepCount,
                        "message" to finalMessage,
                    ),
                )
                if (result.finished && result.success) {
                    appendActionHistory(task, finalMessage)
                }

                val screenshotBytes: ByteArray? = try { device.screenshot() } catch (_: Exception) { null }
                if (screenshotBytes != null && screenshotBytes.isNotEmpty()) {
                    val artifactPath = recorder?.saveArtifact("gui_final.png", screenshotBytes)
                    recorder?.record(
                        eventType = "gui_artifact_saved",
                        payload = mapOf(
                            "kind" to "final_screenshot",
                            "path" to artifactPath,
                            "size_bytes" to screenshotBytes.size,
                        ),
                    )
                    val mime = detectImageMime(screenshotBytes) ?: "image/png"
                    buildImageContentBlocks(
                        screenshotBytes, mime, "gui_final.png",
                        "[GUI Result] $finalMessage\n\nFinal screenshot captured.",
                    )
                } else {
                    finalMessage
                }
                } finally {
                    // 兜底:无论循环正常结束、异常、cancel,都还原剪贴板
                    agent.cleanup()
                    imeSwap?.restore()
                }
            } catch (e: Exception) {
                logger.warning("GUI task failed: ${e.message}")
                recorder?.recordError("gui_task_failed", e, payload = mapOf("task" to task))
                "Error executing GUI task: ${e.message}"
            }
        } finally {
            deviceMutex.unlock()
        }
    }

    companion object {
        /** 全局 GUI 设备互斥:同一时刻只能跑一个 GUI 任务,第二个请求 tryLock 失败快速返回。 */
        private val deviceMutex = Mutex()
        private val HISTORY_TS: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }

    private fun reportProgress(step: Int, result: StepResult) {
        val cb = onProgress ?: return
        cb.invoke(step, describeAction(result.action), result.thinking, encodeAction(result.action))
    }

    private fun encodeAction(action: Map<String, Any?>): String? =
        try { anyToJson(action).toString() } catch (_: Exception) { null }

    private fun recordStep(recorder: TraceRecorder?, step: Int, result: StepResult) {
        recorder?.record(
            eventType = "gui_step",
            iteration = step,
            payload = mapOf(
                "step" to step,
                "success" to result.success,
                "finished" to result.finished,
                "thinking" to result.thinking,
                "message" to result.message,
                "action" to result.action,
            ),
        )
    }

    private fun appendActionHistory(task: String, finalMessage: String) {
        val dir = workspaceDir ?: return
        val entry = "[${LocalDateTime.now().format(HISTORY_TS)}] GUI action completed. Task: ${oneLine(task)}. Result: ${oneLine(finalMessage)}"
        try {
            AgentMemory(dir).appendHistory(entry)
        } catch (e: Exception) {
            logger.warning("Failed to append GUI action history: ${e.message}")
        }
    }

    private fun oneLine(value: String, maxChars: Int = 500): String {
        val text = value.replace(Regex("""\s+"""), " ").trim()
        return if (text.length <= maxChars) text else text.take(maxChars) + "..."
    }

    private fun describeAction(action: Map<String, Any?>): String {
        val meta = action["_metadata"]
        if (meta == "finish") {
            val msg = (action["message"] as? String)?.takeIf { it.isNotBlank() }
            return msg?.let { "完成: ${it.take(20)}" } ?: "完成"
        }
        val name = action["action"] as? String
        return when (name) {
            "Launch" -> {
                val app = action["app"] as? String
                if (!app.isNullOrBlank()) "打开 $app" else "打开应用"
            }
            "Tap" -> "点击屏幕"
            "Double Tap" -> "双击屏幕"
            "Long Press" -> "长按屏幕"
            "Type", "Type_Name" -> {
                val t = (action["text"] as? String)?.take(12) ?: ""
                if (t.isNotEmpty()) "输入「$t」" else "输入文字"
            }
            "Swipe" -> "滑动"
            "Back" -> "返回"
            "Home" -> "回到主页"
            "Wait" -> "等待"
            "Take_over" -> "等待用户操作"
            else -> name ?: "思考中"
        }
    }

    private class ImeSwap(
        private val controller: ImeController,
        private val origIme: String?,
        private val device: DeviceController,
    ) {
        fun restore() {
            device.preferOurIME = false
            if (!origIme.isNullOrBlank() && origIme != controller.currentIme()) {
                controller.switchTo(origIme)
            }
        }
    }

    /**
     * 只在 DeviceController + Shizuku + 自家 IME 已启用 的情况下切换。任何一项不满足就安静返回 null,
     * typeText 会退回旧的剪贴板 / input text 路径,同时保留 ActionHandler 的剪贴板现场保存。
     */
    private fun activateOurIMEIfPossible(): ImeSwap? {
        val dc = device as? DeviceController ?: return null
        if (!dc.isAvailable()) return null
        val controller = ImeController(dc)
        if (!controller.isOurIMEEnabled()) {
            logger.info("Our IME not enabled; falling back to clipboard input.")
            return null
        }
        val orig = controller.currentIme()
        if (!controller.switchToOurIME()) {
            logger.warning("ime set to our IME failed; falling back.")
            return null
        }
        dc.preferOurIME = true
        logger.info("Switched IME to ours; original was: $orig")
        return ImeSwap(controller, orig, dc)
    }
}
