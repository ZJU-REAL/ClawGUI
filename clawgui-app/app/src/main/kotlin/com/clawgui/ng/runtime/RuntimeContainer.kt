package com.clawgui.ng.runtime

import android.content.Context
import com.clawgui.ng.data.ExecutionStatus
import com.clawgui.ng.data.SettingsStore
import com.clawgui.ng.data.repo.InboxRepository
import com.clawgui.ng.data.repo.SessionRepository
import com.clawgui.ng.data.repo.SettingsRepository
import com.clawgui.ng.runtime.shizuku.DeviceController
import com.clawgui.ng.runtime.shizuku.ImeController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Lightweight service-locator. Replaces the App.kt monolith from the v1 client.
 * Each repo is constructed once; future runtime port will inject a Brain agent
 * and Phone agent here and bridge their state into the same flows.
 */
object RuntimeContainer {
    lateinit var appContext: Context
        private set

    lateinit var sessions: SessionRepository
        private set
    lateinit var settings: SettingsRepository
        private set
    lateinit var inbox: InboxRepository
        private set
    lateinit var device: DeviceController
        private set
    lateinit var ime: ImeController
        private set
    lateinit var feishu: com.clawgui.ng.runtime.feishu.FeishuChannel
        private set
    lateinit var traces: com.clawgui.ng.runtime.trace.TraceStore
        private set

    private val _executionStatus = MutableStateFlow(ExecutionStatus())
    val executionStatus: StateFlow<ExecutionStatus> = _executionStatus

    /**
     * Snapshot of the live PhoneAgent run for the floating overlay. Null
     * when no run is active — the overlay collapses / hides. ChatViewModel
     * publishes a new value every step.
     */
    data class AgentLiveSnapshot(
        val plan: com.clawgui.ng.data.Plan?,
        val trace: List<com.clawgui.ng.data.StepRecord>,
        val streaming: Boolean,
        /** When non-null the agent is mid-Ask and the overlay should render
         *  an input field for the user. Submitting fires [onAskAnswer]. */
        val askQuestion: String? = null,
        val onAskAnswer: ((String?) -> Unit)? = null,
    )
    private val _agentLive = MutableStateFlow<AgentLiveSnapshot?>(null)
    val agentLive: StateFlow<AgentLiveSnapshot?> = _agentLive
    fun publishAgentLive(snapshot: AgentLiveSnapshot?) { _agentLive.value = snapshot }

    data class FeishuInbound(val sessionKey: String, val text: String, val messageId: String?)
    /** Bus for inbound Feishu messages — ChatViewModel listens to drive auto-reply / title summarisation. */
    val feishuInbound = kotlinx.coroutines.flow.MutableSharedFlow<FeishuInbound>(extraBufferCapacity = 16)

    fun publishExecution(status: ExecutionStatus) {
        _executionStatus.value = status
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        // Only the cheap, must-be-synchronous setup happens here. Anything
        // that hits disk, PackageManager, or network goes to a background
        // coroutine so cold-launch reaches setContent() in <50ms.
        sessions = SessionRepository()
        settings = SettingsRepository(SettingsStore(appContext))
        inbox = InboxRepository()
        device = DeviceController(appContext)
        ime = ImeController(device)
        feishu = com.clawgui.ng.runtime.feishu.FeishuChannel(
            inbox = inbox,
            sessions = sessions,
            onInbound = { sessionKey, text, messageId ->
                feishuInbound.tryEmit(FeishuInbound(sessionKey, text, messageId))
            },
        )
        traces = com.clawgui.ng.runtime.trace.TraceStore(appContext)

        val bg = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
        )
        bg.launch {
            // PackageManager scan — couple-hundred ms on phones with lots of apps.
            runCatching { com.clawgui.ng.runtime.phone.config.InstalledApps.init(appContext) }
        }
        // Feishu lifecycle observer runs its own IO coroutine internally.
        observeFeishuLifecycle()
        // Inbound Feishu messages drive title summarisation + (optional)
        // auto-reply. This used to live inside ChatViewModel, but because the
        // VM is created/destroyed by the Compose nav stack (Home() returns
        // early before ChatViewModel exists when 设置 is visible), messages
        // arriving while the user is on any settings page got dropped.
        // Hoisting it to RuntimeContainer makes it app-scoped → always on.
        observeFeishuInbound()
    }

    /** Sessions that already had their title auto-summarised — fires once each. */
    private val titledSessions = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private fun observeFeishuInbound() {
        val scope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
        )
        scope.launch {
            feishuInbound.collect { ev ->
                runCatching { maybeAutoTitleApp(ev.sessionKey, ev.text) }
                // Two paths:
                //   feishuRunAsGuiTask = true  → drive PhoneAgent end-to-end
                //                                (the bot actually does the
                //                                 task on the phone and ships
                //                                 final screenshot back).
                //   feishuAutoReply = true  → fall back to a plain Brain
                //                              text reply (legacy behaviour).
                // GUI takes precedence; if it fires we don't also Brain-reply.
                val ranGui = settings.feishuRunAsGuiTask.value &&
                    runCatching { runFeishuAsGuiTask(ev) }.getOrDefault(false)
                if (!ranGui && settings.feishuAutoReply.value) {
                    runCatching { answerFeishuInboundApp(ev) }
                }
            }
        }
    }

    private suspend fun maybeAutoTitleApp(sessionKey: String, firstUserText: String) {
        if (sessionKey in titledSessions) return
        val current = sessions.sessions.value.firstOrNull { it.key == sessionKey }?.title.orEmpty()
        if (current.isNotBlank() && current != "新对话" && current != "新建对话") return
        titledSessions += sessionKey

        val brainId = settings.activeBrain.value
        val cred = settings.resolveCredentials(brainId) ?: return
        if (cred.apiKey.isBlank()) return
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

    /**
     * Drive a full PhoneAgent run for an inbound Feishu message. Ships text
     * + image replies back to the same chat thread. Returns true when the
     * GUI path actually executed (so the caller skips the legacy Brain
     * text reply); false means we bailed early (no Vision credentials,
     * device unauthorised, …) and the caller should fall back.
     *
     * Lean compared to ChatViewModel.runPhoneAgent: no IME juggling, no
     * floating overlay, no return-to-foreground, no plan/trace UI binding.
     * Those are for the in-app chat experience. The bot just needs to
     * 1) run the task, 2) say what happened, 3) attach screenshot.
     */
    private suspend fun runFeishuAsGuiTask(ev: FeishuInbound): Boolean {
        val chatId = ev.sessionKey.removePrefix("feishu:")
        val visionId = settings.activeVision.value
        val cred = settings.resolveCredentials(visionId)
        if (cred == null || cred.apiKey.isBlank()) {
            android.util.Log.w("FeishuGui", "vision creds missing — skip GUI run")
            return false
        }

        // Authorisation gate: need Shizuku or wadb up. Skip silently when
        // not ready — falling back to Brain auto-reply is still useful.
        val wadbReady = runCatching {
            val s = com.clawgui.ng.runtime.shizuku.wadb.WirelessAdb
                .get(appContext).state.value
            s is com.clawgui.ng.runtime.shizuku.wadb.WadbState.Done ||
                s is com.clawgui.ng.runtime.shizuku.wadb.WadbState.Connected
        }.getOrDefault(false)
        val shizukuReady = runCatching { device.isAvailable() }.getOrDefault(false)
        if (!wadbReady && !shizukuReady) {
            runCatching {
                feishu.reply(
                    chatId,
                    "收到任务,但 ClawGUI 未授权设备控制(无线调试 / Shizuku 未连)。" +
                        "请打开 ClawGUI → 设置 → 设备控制授权,完成任一方式后再试。",
                    ev.messageId,
                )
            }
            return true   // we *did* respond, just not by running the task
        }

        // Promote the process so Android 14 doesn't throttle the LLM
        // request mid-task when the user isn't looking at ClawGUI.
        runCatching {
            com.clawgui.ng.runtime.service.AgentService.start(appContext)
        }

        // Replace the placeholder assistant turn (which observeFeishuInbound
        // didn't create — we own the full session lifecycle here).
        val placeholder = com.clawgui.ng.data.ChatMessage(
            id = "msg_" + java.util.UUID.randomUUID().toString().take(8),
            role = com.clawgui.ng.data.Role.ASSISTANT,
            content = "正在执行任务…",
            streaming = true,
        )
        sessions.appendMessage(ev.sessionKey, placeholder)

        val agent = com.clawgui.ng.runtime.phone.PhoneAgent(
            device = device,
            modelConfig = com.clawgui.ng.runtime.phone.model.ModelConfig(
                baseUrl = cred.baseUrl,
                apiKey = cred.apiKey,
                modelName = cred.model,
                lang = "cn",
            ),
            agentConfig = com.clawgui.ng.runtime.phone.AgentConfig(
                maxSteps = 60,
                lang = "cn",
            ),
        )
        val recorder = if (settings.tracesEnabled.value) {
            com.clawgui.ng.runtime.trace.TraceRecorder.start(
                ctx = appContext, task = ev.text, model = cred.model,
                sessionKey = ev.sessionKey,
            )
        } else null
        if (recorder != null) {
            agent.traceSink = { idx, thinking, action, jpeg, ok, msg ->
                val actionName = action["action"] as? String ?: "?"
                recorder.appendStep(
                    com.clawgui.ng.runtime.trace.TraceStep(
                        index = idx, timestamp = System.currentTimeMillis(),
                        thinking = thinking, actionName = actionName,
                        actionJson = actionName, resultOk = ok, resultMessage = msg,
                    ),
                    jpeg,
                )
            }
        }

        val finalText: String = try {
            agent.run(ev.text)
        } catch (t: Throwable) {
            "执行异常:${t.message ?: t::class.simpleName}"
        } finally {
            recorder?.finalize(finalMessage = "", success = true)
            agent.cleanup()
        }

        sessions.updateLastMessage(ev.sessionKey) {
            it.copy(content = finalText, streaming = false)
        }

        // Text reply.
        runCatching { feishu.reply(chatId, finalText, ev.messageId) }

        // Image reply (mode + fallback identical to ChatViewModel path).
        runCatching {
            val mode = settings.feishuReplyImageMode.value
            android.util.Log.i("FeishuGui",
                "image mode=$mode recorderRunDir=${recorder?.runDir}")
            if (mode != com.clawgui.ng.data.repo.FeishuReplyImageMode.OFF) {
                val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val direct = when (mode) {
                        com.clawgui.ng.data.repo.FeishuReplyImageMode.COMPOSITE ->
                            com.clawgui.ng.runtime.feishu.ReplyImageBuilder
                                .compositeLong(recorder?.runDir, emptyList())
                                ?: com.clawgui.ng.runtime.feishu.ReplyImageBuilder
                                    .finalShot(recorder?.runDir)
                        com.clawgui.ng.data.repo.FeishuReplyImageMode.FINAL_ONLY ->
                            com.clawgui.ng.runtime.feishu.ReplyImageBuilder
                                .finalShot(recorder?.runDir)
                        else -> null
                    }
                    direct ?: run {
                        val raw = runCatching { device.screenshot() }.getOrNull()
                        raw?.let {
                            com.clawgui.ng.runtime.phone.util.ScreenshotCompressor.compress(
                                it,
                                com.clawgui.ng.runtime.phone.util.ScreenshotCompressor.Quality.MEDIUM,
                            )
                        }
                    }
                }
                android.util.Log.i("FeishuGui",
                    "image bytes=${bytes?.size ?: 0}")
                if (bytes != null && bytes.isNotEmpty()) {
                    val err = feishu.replyImage(chatId, bytes, ev.messageId)
                    if (err != null) {
                        android.util.Log.w("FeishuGui", "replyImage failed: $err")
                        feishu.appendDebug("Feishu replyImage 失败: $err")
                    } else {
                        feishu.appendDebug(
                            "Feishu replyImage OK (${bytes.size / 1024} KB)"
                        )
                    }
                } else {
                    feishu.appendDebug("Feishu replyImage 跳过:没生成图")
                }
            }
        }
        return true
    }

    private suspend fun answerFeishuInboundApp(ev: FeishuInbound) {
        val brainId = settings.activeBrain.value
        val cred = settings.resolveCredentials(brainId) ?: return
        if (cred.apiKey.isBlank()) return

        val placeholder = com.clawgui.ng.data.ChatMessage(
            id = "msg_" + java.util.UUID.randomUUID().toString().take(8),
            role = com.clawgui.ng.data.Role.ASSISTANT,
            content = "",
            streaming = true,
        )
        sessions.appendMessage(ev.sessionKey, placeholder)

        val brain = com.clawgui.ng.runtime.llm.BrainRuntime(cred)
        val history = sessions.messagesFor(ev.sessionKey).value.dropLast(1)
        val system = "你是 ClawGUI,正在通过飞书与用户对话。简洁、礼貌、中文优先。"
        val acc = StringBuilder()
        try {
            brain.streamReply(system, history).collect { evt ->
                when (evt) {
                    is com.clawgui.ng.runtime.llm.StreamEvent.Delta -> {
                        acc.append(evt.text)
                        val current = acc.toString()
                        sessions.updateLastMessage(ev.sessionKey) { it.copy(content = current) }
                    }
                    // Reasoning trace isn't useful in a Feishu auto-reply —
                    // the bot's job is to ship a clean message back, not show
                    // its work. Drop on the floor.
                    is com.clawgui.ng.runtime.llm.StreamEvent.ReasoningDelta -> {}
                    is com.clawgui.ng.runtime.llm.StreamEvent.Done -> {
                        sessions.updateLastMessage(ev.sessionKey) { it.copy(streaming = false) }
                    }
                    is com.clawgui.ng.runtime.llm.StreamEvent.Error -> {
                        sessions.updateLastMessage(ev.sessionKey) {
                            it.copy(content = "回复生成失败:${evt.message}", streaming = false, error = evt.message)
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            sessions.updateLastMessage(ev.sessionKey) {
                it.copy(content = "回复生成异常:${t.message}", streaming = false, error = t.message)
            }
            return
        }

        val chatId = ev.sessionKey.removePrefix("feishu:")
        val finalReply = acc.toString().trim()
        if (finalReply.isNotBlank()) {
            val err = feishu.reply(chatId, finalReply, ev.messageId)
            if (err != null) {
                sessions.updateLastMessage(ev.sessionKey) { it.copy(error = "已生成但未送达:$err") }
            }
        }
    }

    private fun observeFeishuLifecycle() {
        val scope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
        )
        scope.launch {
            kotlinx.coroutines.flow.combine(
                settings.feishuEnabled,
                settings.feishuAppId,
                settings.feishuAppSecretSet,
                settings.feishuBotName,
            ) { enabled, _, _, _ -> enabled }
                .collect { enabled ->
                    if (enabled) {
                        val cfg = com.clawgui.ng.runtime.feishu.FeishuChannel.Config(
                            appId = settings.feishuAppId.value,
                            appSecret = settings.feishuAppSecret(),
                            botName = settings.feishuBotName.value,
                            allowedOpenIds = settings.feishuAllowedOpenIds.value
                                .split(',', ';', '\n', ' ')
                                .map { it.trim() }
                                .filter { it.isNotBlank() },
                            allowAll = settings.feishuAllowAll.value,
                        )
                        feishu.stop()
                        feishu.start(cfg)
                    } else {
                        feishu.stop()
                    }
                }
        }
    }
}
