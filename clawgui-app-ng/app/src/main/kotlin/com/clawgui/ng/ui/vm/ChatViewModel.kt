package com.clawgui.ng.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clawgui.ng.data.ChatMessage
import com.clawgui.ng.data.ExecutionState
import com.clawgui.ng.data.ExecutionStatus
import com.clawgui.ng.data.PromptCard
import com.clawgui.ng.data.Role
import com.clawgui.ng.data.repo.SessionRepository
import com.clawgui.ng.runtime.RuntimeContainer
import com.clawgui.ng.runtime.llm.BrainRuntime
import com.clawgui.ng.runtime.llm.StreamEvent
import com.clawgui.ng.runtime.phone.AgentConfig
import com.clawgui.ng.runtime.phone.PhoneAgent
import com.clawgui.ng.runtime.phone.model.ModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * The chat surface state. Holds the live message list, current session, draft
 * input, and demo execution simulator. Real Brain/Phone agent calls will be
 * routed through the same flows once the runtime is ported.
 */
class ChatViewModel(
    private val sessions: SessionRepository = RuntimeContainer.sessions,
) : ViewModel() {

    // NOTE: Feishu inbound routing (title summarisation + auto-reply) used to
    // live here, but ChatViewModel is created/destroyed by the Compose nav
    // stack — when 设置 is showing, Home() returns early and the VM is gone.
    // That routing now lives in RuntimeContainer.observeFeishuInbound() so it
    // runs at app scope regardless of which screen is visible.

    val currentSessionKey: StateFlow<String> = sessions.currentKey

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val messages: StateFlow<List<ChatMessage>> = sessions.currentKey
        .flatMapLatest { sessions.messagesFor(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val executionStatus: StateFlow<ExecutionStatus> = RuntimeContainer.executionStatus

    val sessionsList = sessions.sessions

    private val _draft = MutableStateFlow("")
    val draft: StateFlow<String> = _draft

    private val _isExecuting = MutableStateFlow(false)
    val isExecuting: StateFlow<Boolean> = _isExecuting

    private var runJob: Job? = null

    /**
     * One PhoneAgent per session — keeps screenshot context + adapter history
     * across turns so "继续给 X 发一条" can build on what the first turn did,
     * instead of starting from a blank slate every time.
     *
     * Keyed by `(sessionKey, providerId, model)` so changing the Vision model
     * (or switching sessions) cleanly resets the loop.
     */
    private data class AgentKey(val session: String, val providerId: String, val model: String)
    private val agentCache = mutableMapOf<AgentKey, com.clawgui.ng.runtime.phone.PhoneAgent>()

    val header: StateFlow<HeaderState> = combine(
        sessions.sessions,
        sessions.currentKey,
        RuntimeContainer.settings.providers,
        RuntimeContainer.settings.activeBrain,
    ) { all, key, providers, brainId ->
        val s = all.firstOrNull { it.key == key }
        val brain = providers.firstOrNull { it.id == brainId }
        HeaderState(
            title = s?.title ?: "ClawGUI",
            modelLabel = brain?.displayName ?: "GLM-5.1",
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, HeaderState("ClawGUI", "GLM-5.1"))

    fun updateDraft(text: String) { _draft.value = text }

    fun selectSession(key: String) = sessions.selectSession(key)
    fun newSession(): String {
        // New chat → fresh agent loop. (Switching to an existing session keeps
        // its cached agent so multi-turn GUI tasks pick up where they left off.)
        val key = sessions.newSession()
        return key
    }
    fun renameSession(key: String, title: String) = sessions.rename(key, title)
    fun togglePin(key: String) = sessions.togglePin(key)
    fun deleteSession(key: String) {
        // Drop any cached agent so we don't leak the loop's screenshot context.
        agentCache.keys.filter { it.session == key }.forEach { agentCache.remove(it) }
        sessions.delete(key)
    }

    fun pickCard(card: PromptCard) { _draft.value = card.prompt }

    /**
     * Re-roll the assistant message with the given id (drops it + everything
     * after, then re-runs the turn from the preceding user message).
     */
    fun regenerate(assistantMessageId: String) {
        if (_isExecuting.value) return
        val key = sessions.currentKey.value
        val trigger = sessions.truncateForRegenerate(key, assistantMessageId) ?: return
        val placeholder = ChatMessage(
            id = "msg_" + UUID.randomUUID().toString().take(8),
            role = Role.ASSISTANT,
            content = "",
            streaming = true,
        )
        sessions.appendMessage(key, placeholder)

        _isExecuting.value = true
        runJob = viewModelScope.launch {
            val text = trigger.content
            val explicitGui = text.startsWith("/gui ") || text.startsWith("/操作 ")
            val toggleGui = RuntimeContainer.settings.guiModeEnabled.value
            if (explicitGui || toggleGui) {
                val task = if (explicitGui) text.substringAfter(' ').trim() else text
                runPhoneAgent(key, task)
            } else {
                runBrainTurn(key)
            }
            _isExecuting.value = false
        }
    }

    /**
     * If the current session still has the placeholder title ("新对话" or
     * blank), ask the Brain to produce a ≤15-char Chinese title summarising
     * the very first user message. Best-effort: any failure leaves the
     * default title alone. Fires once per session.
     */
    private val titledSessions = mutableSetOf<String>()
    private fun maybeAutoTitle(sessionKey: String, firstUserText: String) {
        if (sessionKey in titledSessions) return
        val current = sessions.sessions.value.firstOrNull { it.key == sessionKey }?.title.orEmpty()
        // Only rename our own default placeholders — never overwrite a title
        // the user manually set, or external (feishu:*) sessions.
        if (current.isNotBlank() && current != "新对话" && current != "新建对话") return
        titledSessions += sessionKey

        val brainId = RuntimeContainer.settings.activeBrain.value
        val cred = RuntimeContainer.settings.resolveCredentials(brainId)
        if (cred == null || cred.apiKey.isBlank()) return
        viewModelScope.launch {
            runCatching {
                val client = com.clawgui.ng.runtime.llm.OpenAICompatClient(
                    baseUrl = cred.baseUrl,
                    apiKey = cred.apiKey,
                    model = cred.model,
                )
                val reply = client.complete(listOf(
                    com.clawgui.ng.runtime.llm.Message(
                        "system",
                        "你的工作:用最多 15 个中文字概括用户的对话主题作为会话标题。" +
                            "只输出标题文本,不带引号、不带标点、不带前缀。",
                    ),
                    com.clawgui.ng.runtime.llm.Message("user", firstUserText),
                ))
                val title = reply.trim()
                    .removeSurrounding("\"")
                    .removeSurrounding("「", "」")
                    .removeSurrounding("『", "』")
                    .lineSequence().firstOrNull()
                    .orEmpty()
                    .take(15)
                if (title.isNotBlank()) sessions.rename(sessionKey, title)
            }
        }
    }

    fun send() {
        val text = _draft.value.trim()
        if (text.isEmpty() || _isExecuting.value) return
        _draft.value = ""

        val key = sessions.currentKey.value
        val userMsg = ChatMessage(
            id = "msg_" + UUID.randomUUID().toString().take(8),
            role = Role.USER,
            content = text,
        )
        sessions.appendMessage(key, userMsg)
        maybeAutoTitle(key, text)

        val placeholder = ChatMessage(
            id = "msg_" + UUID.randomUUID().toString().take(8),
            role = Role.ASSISTANT,
            content = "",
            streaming = true,
        )
        sessions.appendMessage(key, placeholder)

        _isExecuting.value = true
        runJob = viewModelScope.launch {
            val explicitGui = text.startsWith("/gui ") || text.startsWith("/操作 ")
            val toggleGui = RuntimeContainer.settings.guiModeEnabled.value
            if (explicitGui || toggleGui) {
                val task = if (explicitGui) text.substringAfter(' ').trim() else text
                runPhoneAgent(key, task)
            } else {
                runBrainTurn(key)
            }
            _isExecuting.value = false
        }
    }

    /**
     * Drive the PhoneAgent step-by-step. Each step's `<think>` and chosen
     * action are appended to the assistant message's `thinking` block (so the
     * user can expand it), and the dynamic island mirrors the same info.
     * The visible `content` is the running summary; on finish it becomes the
     * agent's final message.
     */
    private suspend fun runPhoneAgent(key: String, task: String) {
        // Device auth gate — either wireless-debug ADB OR Shizuku is enough.
        // We never auto-bind here: the user picks the path in 设置 → 设备控制授权
        // and any silent bind would flip the UI's "active" indicator behind
        // their back.
        val device = RuntimeContainer.device
        val wadbReady = runCatching {
            val s = com.clawgui.ng.runtime.shizuku.wadb.WirelessAdb
                .get(RuntimeContainer.appContext).state.value
            s is com.clawgui.ng.runtime.shizuku.wadb.WadbState.Done ||
                s is com.clawgui.ng.runtime.shizuku.wadb.WadbState.Connected
        }.getOrDefault(false)
        val shizukuReady = runCatching { device.isAvailable() }.getOrDefault(false)

        if (!shizukuReady && !wadbReady) {
            val msg = "无法操作手机:尚未完成设备控制授权。\n\n" +
                "请到「设置 → 设备控制授权」二选一完成:\n" +
                "• 无线调试一键启动(推荐,免电脑):打开手机的开发者选项 → 无线调试 → 配对一次即可\n" +
                "• Shizuku 模式:安装 Shizuku App,通过 ADB 或 Root 启动 Shizuku 服务后授权 ClawGUI"
            sessions.updateLastMessage(key) {
                it.copy(content = msg, streaming = false, error = "device auth not ready")
            }
            RuntimeContainer.publishExecution(
                ExecutionStatus(
                    state = ExecutionState.ERROR,
                    title = "设备控制未授权",
                    subtitle = "请到「设置 → 设备控制授权」完成任一方式",
                )
            )
            return
        }

        val visionId = RuntimeContainer.settings.activeVision.value
        val cred = RuntimeContainer.settings.resolveCredentials(visionId)
        if (cred == null || cred.apiKey.isBlank()) {
            sessions.updateLastMessage(key) {
                it.copy(
                    content = "未配置 Vision (${visionId}) 的 API Key。\n请在「设置 → AI 模型」里粘贴 Key。",
                    streaming = false,
                    error = "missing api key",
                )
            }
            return
        }

        RuntimeContainer.publishExecution(
            ExecutionStatus(
                state = ExecutionState.THINKING,
                title = "PhoneAgent 启动",
                subtitle = cred.model,
            )
        )

        // Promote the process to foreground so Android 14 doesn't throttle CPU
        // / network while the user is in another app. Without this, the agent
        // stalls until the user returns to ng and the OS releases the throttle.
        runCatching {
            com.clawgui.ng.runtime.service.AgentService.start(RuntimeContainer.appContext)
        }
        val wakeLock = acquireWakeLock()

        // Switch to ClawGUI IME for the duration of the run so the agent can
        // inject text via broadcast. We restore the original IME in finally.
        val imeCtrl = RuntimeContainer.ime
        val origIme: String? = runCatching { imeCtrl.currentIme() }.getOrNull()
        val imeReady = runCatching {
            if (!imeCtrl.isOurIMEEnabled()) {
                // Try a one-shot enable via Shizuku. On Honor MagicOS this
                // sometimes requires a manual visit to system settings; either
                // way we silently degrade to clipboard-paste typing.
                imeCtrl.enableOurIME()
            }
            val switched = imeCtrl.switchToOurIME()
            (device as? com.clawgui.ng.runtime.shizuku.DeviceController)?.preferOurIME = switched
            switched
        }.getOrDefault(false)
        android.util.Log.i("PhoneAgent", "IME switch on start: ready=$imeReady, origIme=$origIme")

        // Reuse the same PhoneAgent for follow-up turns in this session +
        // provider combination. The agent's `context` (screenshot history,
        // assistant turns) is what makes "继续做下一件事" work — the model
        // sees what it already accomplished, instead of starting blind.
        val agentKey = AgentKey(session = key, providerId = visionId, model = cred.model)
        val isFollowUp = agentCache.containsKey(agentKey)
        val agent = agentCache.getOrPut(agentKey) {
            PhoneAgent(
                device = device,
                modelConfig = ModelConfig(
                    baseUrl = cred.baseUrl,
                    apiKey = cred.apiKey,
                    modelName = cred.model,
                    lang = "cn",
                ),
                agentConfig = AgentConfig(maxSteps = Int.MAX_VALUE, lang = "cn"),
            )
        }

        // Start a trace recorder for this run (respects the user's
        // tracesEnabled toggle). Per-step screenshots are saved as JPEG.
        val recorder = if (RuntimeContainer.settings.tracesEnabled.value) {
            com.clawgui.ng.runtime.trace.TraceRecorder.start(
                ctx = RuntimeContainer.appContext,
                task = task,
                model = cred.model,
                sessionKey = key,
            )
        } else null
        agent.traceSink = if (recorder != null) {
            { idx, thinking, action, jpeg, ok, msg ->
                val actionName = action["action"] as? String ?: "?"
                val extra = describeActionInline(action)
                recorder.appendStep(
                    com.clawgui.ng.runtime.trace.TraceStep(
                        index = idx,
                        timestamp = System.currentTimeMillis(),
                        thinking = thinking,
                        actionName = actionName,
                        actionJson = "$actionName $extra".trim(),
                        resultOk = ok,
                        resultMessage = msg,
                    ),
                    jpeg,
                )
            }
        } else null

        val thinkingLog = StringBuilder()
        // No hard cap — agent runs until it finishes, you stop it, or stuck-detection bails.
        var finalMessage: String? = null
        var stepIdx = 0

        try {
            sessions.updateLastMessage(key) {
                it.copy(content = "正在分析任务并截屏…", thinking = "")
            }
            while (true) {
                stepIdx++
                // Hard timeout — if any single step (screenshot + VLM + action)
                // exceeds 5 minutes, the system has almost certainly throttled
                // us into oblivion. Bail with a clear message instead of hanging.
                val step = try {
                    kotlinx.coroutines.withTimeout(5 * 60 * 1000L) {
                        withContext(Dispatchers.IO) {
                            when {
                                stepIdx == 1 && isFollowUp -> agent.continueTask(task)
                                stepIdx == 1 -> agent.step(task)
                                else -> agent.step()
                            }
                        }
                    }
                } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                    finalMessage = "第 $stepIdx 步超过 5 分钟未完成,系统可能已限制后台运行。\n" +
                        "请到「系统设置 → 应用 → ClawGUI → 电池/省电」把 ClawGUI 设为「不限制后台活动」,然后重试。"
                    break
                }

                val actionName = step.action["action"] as? String ?: "?"
                val actionExtra = describeActionInline(step.action)
                val stepHeader = "▸ 第 $stepIdx 步 · $actionName${if (actionExtra.isNotBlank()) " · $actionExtra" else ""}"
                val thoughtBlock = if (step.thinking.isNotBlank())
                    "$stepHeader\n${step.thinking.trim()}"
                else
                    stepHeader
                if (thinkingLog.isNotEmpty()) thinkingLog.append("\n\n")
                thinkingLog.append(thoughtBlock)

                sessions.updateLastMessage(key) {
                    it.copy(
                        content = "正在执行第 $stepIdx 步:$actionName${if (actionExtra.isNotBlank()) " · $actionExtra" else ""}",
                        thinking = thinkingLog.toString(),
                    )
                }
                RuntimeContainer.publishExecution(
                    ExecutionStatus(
                        state = if (step.finished) ExecutionState.DONE else ExecutionState.ACTING,
                        title = if (step.finished) "PhoneAgent 完成" else "第 $stepIdx 步 · $actionName",
                        subtitle = actionExtra.ifBlank { step.thinking.lineSequence().firstOrNull().orEmpty().take(60) },
                        stepIndex = stepIdx - 1,
                        totalSteps = 0,    // 0 = no fixed total (open-ended run)
                        thinking = step.thinking.take(200),
                        actionJson = "$actionName $actionExtra".trim(),
                    )
                )
                if (step.finished) {
                    finalMessage = step.message
                        ?: step.action["message"] as? String
                        ?: "任务结束"
                    break
                }
            }
            // If we fall out without finalMessage, the loop was cancelled
            // (user stop / coroutine cancel). Leave finalMessage null so the
            // outer block prints "任务结束" rather than a misleading max-steps msg.
        } catch (t: Throwable) {
            finalMessage = "执行异常:${t.message ?: t::class.simpleName}"
            RuntimeContainer.publishExecution(
                ExecutionStatus(
                    state = ExecutionState.ERROR,
                    title = "PhoneAgent 出错",
                    subtitle = finalMessage!!.take(80),
                )
            )
        } finally {
            agent.cleanup()
            agent.traceSink = null
            recorder?.finalize(
                finalMessage = finalMessage ?: "任务结束",
                success = finalMessage != null && finalMessage!!.startsWith("执行异常").not(),
            )
            runCatching { wakeLock?.release() }
            // Restore the user's original IME so they aren't stuck on ours.
            runCatching {
                (device as? com.clawgui.ng.runtime.shizuku.DeviceController)?.preferOurIME = false
                if (!origIme.isNullOrBlank() && origIme != com.clawgui.ng.runtime.ime.ClawNgIME.IME_COMPONENT) {
                    imeCtrl.switchTo(origIme)
                }
            }
        }

        sessions.updateLastMessage(key) {
            it.copy(content = finalMessage ?: "任务结束", thinking = thinkingLog.toString(), streaming = false)
        }
        if (RuntimeContainer.executionStatus.value.state != ExecutionState.ERROR) {
            RuntimeContainer.publishExecution(
                ExecutionStatus(
                    state = ExecutionState.DONE,
                    title = "PhoneAgent 已完成",
                    subtitle = (finalMessage ?: "").take(80),
                )
            )
        }
        // If invoked from a Feishu chat, ship the PhoneAgent's final summary
        // back over the Feishu API so the original sender knows it's done.
        if (key.startsWith("feishu:") && !finalMessage.isNullOrBlank()) {
            val chatId = key.removePrefix("feishu:")
            val lastMsgId = RuntimeContainer.inbox.lastMessageId(key)
            runCatching {
                RuntimeContainer.feishu.reply(chatId, finalMessage!!, lastMsgId)
            }
        }
        // Bring ClawGUI to the foreground so the user is dropped back into the
        // chat with the result. The foreground service we acquired earlier
        // grants us the background-Activity-start privilege Android 12+
        // would otherwise deny.
        runCatching {
            val ctx = RuntimeContainer.appContext
            val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
            if (launch != null) {
                launch.addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                        or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
                ctx.startActivity(launch)
            }
        }
        // Let the user see the final notification for a beat before we tear
        // the service down — quick-flash "done" is invisible.
        delay(5000)
        if (RuntimeContainer.executionStatus.value.state != ExecutionState.ACTING &&
            RuntimeContainer.executionStatus.value.state != ExecutionState.THINKING) {
            RuntimeContainer.publishExecution(ExecutionStatus())   // IDLE → service stopSelf
        }
        runCatching {
            com.clawgui.ng.runtime.service.AgentService.stop(RuntimeContainer.appContext)
        }
    }

    /**
     * Hold a partial wake lock so the CPU keeps running while the screen
     * stays on (we don't dim) and our app is in the background.
     */
    private fun acquireWakeLock(): android.os.PowerManager.WakeLock? = runCatching {
        val pm = RuntimeContainer.appContext
            .getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        pm.newWakeLock(
            android.os.PowerManager.PARTIAL_WAKE_LOCK,
            "ClawGUI:PhoneAgent",
        ).apply {
            setReferenceCounted(false)
            // Cap at 10 minutes — any single agent run that lasts longer is broken anyway.
            acquire(10 * 60 * 1000L)
        }
    }.getOrNull()

    private fun describeActionInline(action: Map<String, Any?>): String {
        val name = action["action"] as? String
        return when (name) {
            "Tap", "Long Press", "Double Tap" -> (action["element"] as? List<*>)?.let { "$it" }.orEmpty()
            "Type", "Type_Name" -> "\"${(action["text"] as? String ?: "").take(30)}\""
            "Swipe" -> {
                val s = action["start"] as? List<*>
                val e = action["end"] as? List<*>
                if (s != null && e != null) "$s → $e" else ""
            }
            "Launch" -> "${action["app"]}"
            "Wait" -> "${action["duration"]}"
            else -> action["message"] as? String ?: ""
        }
    }

    fun stop() {
        runJob?.cancel(); runJob = null
        _isExecuting.value = false
        RuntimeContainer.publishExecution(
            ExecutionStatus(state = ExecutionState.STOPPED, title = "已停止")
        )
    }

    /**
     * Real Brain turn — streams from the active provider over OpenAI-compatible
     * API. Reads credentials from SettingsRepository so the user can change
     * provider/model/key without restarting.
     */
    private suspend fun runBrainTurn(key: String) {
        val brainId = RuntimeContainer.settings.activeBrain.value
        val cred = RuntimeContainer.settings.resolveCredentials(brainId)
        if (cred == null || cred.apiKey.isBlank()) {
            sessions.updateLastMessage(key) {
                it.copy(
                    content = "尚未配置 ${brainId} 的 API Key。\n\n请到「设置 → AI 模型」粘贴你的智谱 API Key,然后再次发送。",
                    streaming = false,
                    error = "missing api key",
                )
            }
            return
        }

        RuntimeContainer.publishExecution(
            ExecutionStatus(state = ExecutionState.THINKING, title = "正在思考", subtitle = cred.model)
        )

        val brain = BrainRuntime(cred)
        val history = sessions.messagesFor(key).value
            .dropLast(1)            // drop empty placeholder
        val system = "你是 ClawGUI 的对话大脑。回答简洁清楚,中文优先。当用户希望你帮助操作手机时,先做计划再行动。"
        val acc = StringBuilder()

        try {
            brain.streamReply(system, history).collect { ev ->
                when (ev) {
                    is StreamEvent.Delta -> {
                        acc.append(ev.text)
                        val current = acc.toString()
                        sessions.updateLastMessage(key) { it.copy(content = current) }
                        RuntimeContainer.publishExecution(
                            ExecutionStatus(
                                state = ExecutionState.ACTING,
                                title = "正在回复",
                                subtitle = cred.model,
                            )
                        )
                    }
                    is StreamEvent.Done -> {
                        sessions.updateLastMessage(key) { it.copy(streaming = false) }
                        RuntimeContainer.publishExecution(
                            ExecutionStatus(state = ExecutionState.DONE, title = "执行完成")
                        )
                    }
                    is StreamEvent.Error -> {
                        sessions.updateLastMessage(key) {
                            it.copy(
                                content = if (acc.isEmpty()) "请求失败:" + ev.message else acc.toString(),
                                streaming = false,
                                error = ev.message,
                            )
                        }
                        RuntimeContainer.publishExecution(
                            ExecutionStatus(state = ExecutionState.ERROR, title = "出错了", subtitle = ev.message.take(80))
                        )
                    }
                }
            }
        } catch (t: Throwable) {
            sessions.updateLastMessage(key) {
                it.copy(
                    content = if (acc.isEmpty()) "请求异常:" + (t.message ?: t::class.simpleName) else acc.toString(),
                    streaming = false,
                    error = t.message,
                )
            }
            RuntimeContainer.publishExecution(
                ExecutionStatus(state = ExecutionState.ERROR, title = "出错了",
                    subtitle = (t.message ?: t::class.simpleName ?: "").take(80))
            )
        }

        // If this conversation lives in a Feishu chat, ship the assistant
        // reply back over the Feishu API so the original sender sees it.
        if (key.startsWith("feishu:") && acc.isNotBlank()) {
            val chatId = key.removePrefix("feishu:")
            val lastMsgId = RuntimeContainer.inbox.lastMessageId(key)
            val err = RuntimeContainer.feishu.reply(chatId, acc.toString(), lastMsgId)
            if (err != null) {
                sessions.updateLastMessage(key) { it.copy(error = "未送达飞书:$err") }
            }
        }

        delay(2500)
        if (RuntimeContainer.executionStatus.value.state == ExecutionState.DONE) {
            RuntimeContainer.publishExecution(ExecutionStatus())
        }
    }
}

data class HeaderState(val title: String, val modelLabel: String)
