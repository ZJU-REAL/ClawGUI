package com.clawgui.android

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.clawgui.android.core.nano.agent.AgentLoop
import com.clawgui.android.core.nano.agent.tools.GUITool
import com.clawgui.android.core.nano.agent.tools.ReadMemoryTool
import com.clawgui.android.core.nano.agent.tools.WriteMemoryTool
import com.clawgui.android.core.nano.bus.InboundMessage
import com.clawgui.android.core.nano.bus.MessageBus
import com.clawgui.android.core.nano.channels.ChannelDispatcher
import com.clawgui.android.core.nano.channels.InAppBridge
import com.clawgui.android.core.nano.channels.InAppChannel
import com.clawgui.android.core.nano.channels.PendingTurn
import com.clawgui.android.core.nano.channels.feishu.ExternalInboxStore
import com.clawgui.android.core.nano.channels.feishu.FeishuChannel
import com.clawgui.android.core.nano.session.Session
import com.clawgui.android.core.nano.session.SessionManager
import com.clawgui.android.core.nano.trace.TraceRecorder
import com.clawgui.android.core.phone.config.InstalledApps
import com.clawgui.android.platform.SettingsStore
import com.clawgui.android.platform.shizuku.DeviceController
import com.clawgui.android.service.AgentService
import com.clawgui.android.ui.ChatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import java.io.File
import java.util.concurrent.ConcurrentHashMap

sealed interface ExecutionStatus {
    data object Idle : ExecutionStatus
    data class Running(
        val startMs: Long,
        val stageHint: String,
        val thinking: String? = null,
        val actionJson: String? = null,
    ) : ExecutionStatus
    data class Error(val message: String) : ExecutionStatus
    data class Stopped(val message: String) : ExecutionStatus
    data object Done : ExecutionStatus
}

class App : Application(), InAppBridge {

    lateinit var deviceController: DeviceController
        private set
    lateinit var settingsStore: SettingsStore
        private set
    lateinit var workspaceDir: File
        private set
    lateinit var sessions: SessionManager
        private set
    lateinit var externalInbox: ExternalInboxStore
        private set

    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()

    private val _isExecuting = MutableStateFlow(false)
    val isExecuting: StateFlow<Boolean> = _isExecuting.asStateFlow()

    private val _executionStatus = MutableStateFlow<ExecutionStatus>(ExecutionStatus.Idle)
    val executionStatus: StateFlow<ExecutionStatus> = _executionStatus.asStateFlow()

    // 当前占用悬浮球/设备的 session key。null = 空闲。单槽,MVP 只跑一个。
    private val _activeRunningSessionKey = MutableStateFlow<String?>(null)
    val activeRunningSessionKey: StateFlow<String?> = _activeRunningSessionKey.asStateFlow()

    private val _currentSessionKey = MutableStateFlow("ui:${System.currentTimeMillis()}")
    val currentSessionKey: StateFlow<String> = _currentSessionKey.asStateFlow()

    private val _currentSessionDisplayName = MutableStateFlow(DEFAULT_NEW_NAME)
    val currentSessionDisplayName: StateFlow<String> = _currentSessionDisplayName.asStateFlow()

    private val _sessionsVersion = MutableStateFlow(0L)
    val sessionsVersion: StateFlow<Long> = _sessionsVersion.asStateFlow()

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var agentLoop: AgentLoop
    private lateinit var dispatcher: ChannelDispatcher
    private lateinit var inAppChannel: InAppChannel
    private var feishuChannel: FeishuChannel? = null

    private val pendingTurns = ConcurrentHashMap<String, PendingTurn>()

    // Dynamic trace recorder for the currently-running in_app turn. AgentLoop
    // and GUITool read it via their traceRecorderProvider lambdas. Stage 1 only
    // runs one in_app turn at a time (App._isExecuting guards this), so a single
    // volatile slot is enough.
    @Volatile
    private var activeTurnRecorder: TraceRecorder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this

        workspaceDir = File(filesDir, "workspace").also { it.mkdirs() }
        settingsStore = SettingsStore(this)
        // 诊断开关进程级常驻,Log 对象读这个标志决定是否写细节
        com.clawgui.android.core.util.Log.setDiagEnabled(settingsStore.diagnosticMode)
        sessions = SessionManager(workspaceDir)
        externalInbox = ExternalInboxStore(workspaceDir)

        Shizuku.addRequestPermissionResultListener(PERMISSION_RESULT_LISTENER)

        deviceController = DeviceController(this)
        deviceController.setCacheDir(cacheDir)

        appScope.launch { InstalledApps.init(this@App) }

        loadInitialSession()
        initAgentLoop()
        initChannels()
    }

    private fun initAgentLoop() {
        agentLoop = AgentLoop(
            bus = MessageBus(),
            provider = settingsStore.buildBrainProvider(),
            workspace = workspaceDir,
            scope = appScope,
            sessionManager = sessions,
            traceRecorderProvider = { activeTurnRecorder },
        ).also {
            it.tools.register(
                GUITool(
                    device = deviceController,
                    modelConfig = settingsStore.buildVlmConfig(),
                    defaultMaxSteps = settingsStore.maxSteps,
                    workspaceDir = workspaceDir,
                    onProgress = { step, hint, thinking, actionJson ->
                        val running = _executionStatus.value as? ExecutionStatus.Running
                        val startMs = running?.startMs ?: System.currentTimeMillis()
                        _executionStatus.value = ExecutionStatus.Running(
                            startMs = startMs,
                            stageHint = if (step == 0) hint else "步骤 $step · $hint",
                            thinking = thinking,
                            actionJson = actionJson,
                        )
                    },
                    traceRecorderProvider = { activeTurnRecorder },
                )
            )
            it.tools.register(ReadMemoryTool(workspaceDir))
            it.tools.register(WriteMemoryTool(workspaceDir))
        }
        appScope.launch { agentLoop.run() }
    }

    private fun initChannels() {
        dispatcher = ChannelDispatcher(agentLoop.bus, appScope)
        inAppChannel = InAppChannel(agentLoop.bus, bridge = this)
        dispatcher.register(inAppChannel)

        if (settingsStore.feishuEnabled) {
            val cfg = settingsStore.buildFeishuConfig()
            if (cfg.isUsable) {
                feishuChannel = FeishuChannel(
                    bus = agentLoop.bus,
                    cfg = cfg,
                    scope = appScope,
                    inbox = externalInbox,
                    onInboundAccepted = { sessionKey, content ->
                        // 只有真拿到 slot(没被别的 session 占)才开 trace,和 in-app 路径对称。
                        if (acquireSlot(sessionKey, "[飞书] 规划中…")) {
                            val chatId = sessionKey.removePrefix("feishu:")
                            val startMs = System.currentTimeMillis()
                            val recorder = TraceRecorder.create(
                                workspaceDir = workspaceDir,
                                sessionKey = sessionKey,
                                turnId = "turn_$startMs",
                                channel = "feishu",
                                chatId = chatId,
                                enabled = settingsStore.traceEnabled,
                            ).also {
                                it.record(
                                    eventType = "user_input",
                                    payload = mapOf(
                                        "instruction" to content,
                                        "started_at_ms" to startMs,
                                        "source" to "feishu",
                                    ),
                                )
                            }
                            pendingTurns[sessionKey] = PendingTurn(startMs, recorder, uiSessionKey = sessionKey)
                            activeTurnRecorder = recorder
                        }
                    },
                    onOutboundSent = { sessionKey, text, error ->
                        // 先结 trace,再 releaseSlot。顺序不影响正确性,但日志更好看
                        pendingTurns.remove(sessionKey)?.let { turn ->
                            turn.trace.record(
                                eventType = if (error == null) "assistant_output" else "feishu_send_failed",
                                payload = if (error == null)
                                    mapOf("content" to text, "channel" to "feishu")
                                else
                                    mapOf("content" to text, "error" to error, "channel" to "feishu"),
                            )
                            if (activeTurnRecorder === turn.trace) activeTurnRecorder = null
                        }
                        val statusText = if (error == null) text else "Error: 飞书发送失败 — $error"
                        releaseSlot(sessionKey, statusText)
                        ensureDisplayNameInternal(sessionKey)
                    },
                ).also { dispatcher.register(it) }
            }
        }

        // Outbound 消费循环进程级常开,各 channel 的长连只跟前后台切。
        // 前者停掉会让 in-app turn 的结果卡在 bus 里落不了幕。
        dispatcher.startDispatch()

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                dispatcher.startChannels()
            }
            override fun onStop(owner: LifecycleOwner) {
                appScope.launch { dispatcher.stopChannels() }
            }
        })
    }

    override fun onTerminate() {
        super.onTerminate()
        Shizuku.removeRequestPermissionResultListener(PERMISSION_RESULT_LISTENER)
    }

    fun sendInstruction(instruction: String) {
        if (_isExecuting.value) return
        appendMessage(ChatMessage("user", instruction))

        val sessionKey = _currentSessionKey.value
        if (!acquireSlot(sessionKey, "大脑规划中…")) return
        val startMs = (_executionStatus.value as? ExecutionStatus.Running)?.startMs
            ?: System.currentTimeMillis()

        val traceRecorder = TraceRecorder.create(
            workspaceDir = workspaceDir,
            sessionKey = sessionKey,
            turnId = "turn_$startMs",
            channel = "in_app",
            chatId = "default",
            enabled = settingsStore.traceEnabled,
        ).also {
            it.record(
                eventType = "user_input",
                payload = mapOf(
                    "instruction" to instruction,
                    "started_at_ms" to startMs,
                ),
            )
        }

        // Early-exit checks: fail fast on the UI thread without going through the bus.
        if (settingsStore.apiKey.isEmpty()) {
            traceRecorder.record(
                eventType = "app_turn_early_exit",
                payload = mapOf("reason" to "missing_api_key"),
            )
            appendMessage(ChatMessage("assistant", "请先在设置中填写 API Key。"))
            releaseSlot(sessionKey, "Error: 请先填写 API Key")
            return
        }
        if (!deviceController.isAvailable()) {
            traceRecorder.record(
                eventType = "app_turn_early_exit",
                payload = mapOf("reason" to "shizuku_unavailable"),
            )
            appendMessage(ChatMessage("assistant", "Shizuku 未连接,请先连接 Shizuku 并授权。"))
            releaseSlot(sessionKey, "Error: Shizuku 未连接")
            return
        }

        pendingTurns[sessionKey] = PendingTurn(
            startMs = startMs,
            trace = traceRecorder,
            uiSessionKey = sessionKey,
        )
        activeTurnRecorder = traceRecorder

        val inbound = InboundMessage(
            channel = "in_app",
            senderId = "user",
            chatId = "default",
            content = instruction,
            sessionKeyOverride = sessionKey,
        )
        appScope.launch { agentLoop.bus.publishInbound(inbound) }
    }

    fun stopInstruction() {
        // 优先停当前活跃那一个(可能是 in-app 或外部);没有则兜底到当前 UI session。
        val key = _activeRunningSessionKey.value ?: _currentSessionKey.value
        stopSession(key)
    }

    // ------------------------------------------------------------------
    // InAppBridge — 被 InAppChannel.send 调,原 handleInAppOutbound 的行为拆成这几个方法
    // ------------------------------------------------------------------

    override fun takePendingTurn(sessionKey: String): PendingTurn? = pendingTurns.remove(sessionKey)

    override fun clearActiveRecorderIf(expected: TraceRecorder) {
        if (activeTurnRecorder === expected) activeTurnRecorder = null
    }

    override fun isCurrentSession(sessionKey: String): Boolean =
        sessionKey == _currentSessionKey.value

    override fun appendAssistantMessage(text: String) {
        appendMessage(ChatMessage("assistant", text))
    }

    override fun releaseExecution(sessionKey: String, text: String) {
        releaseSlot(sessionKey, text)
    }

    override fun ensureDisplayName(sessionKey: String) {
        ensureDisplayNameInternal(sessionKey)
    }

    override fun bumpSessionsVersion() {
        _sessionsVersion.value = _sessionsVersion.value + 1
    }

    // ------------------------------------------------------------------

    fun markStatusIdle() {
        _executionStatus.value = ExecutionStatus.Idle
    }

    // ------------------------------------------------------------------
    // 执行 slot 原语:统一 in-app 和外部 channel 的启动/结束路径。
    // 成功 acquire 的 caller 必须配对 release。acquireSlot 在已被占用时静默失败
    // 返回 false —— 后来者的消息仍由 bus/AgentLoop 正常排队执行,只是不覆盖悬浮球。
    // ------------------------------------------------------------------

    private fun acquireSlot(sessionKey: String, stageHint: String): Boolean {
        if (_activeRunningSessionKey.value != null) {
            com.clawgui.android.core.util.Log.i(
                "Slot", sessionKey,
                "acquire skipped — already held by ${_activeRunningSessionKey.value}",
            )
            return false
        }
        _activeRunningSessionKey.value = sessionKey
        _isExecuting.value = true
        _executionStatus.value = ExecutionStatus.Running(
            startMs = System.currentTimeMillis(),
            stageHint = stageHint,
        )
        startAgentService()
        com.clawgui.android.core.util.Log.i("Slot", sessionKey, "acquired (hint=$stageHint)")
        return true
    }

    private fun releaseSlot(sessionKey: String, resultText: String) {
        if (_activeRunningSessionKey.value != sessionKey) {
            com.clawgui.android.core.util.Log.i(
                "Slot", sessionKey,
                "release skipped — holder=${_activeRunningSessionKey.value}",
            )
            return
        }
        _activeRunningSessionKey.value = null
        _isExecuting.value = false
        val isError = looksLikeError(resultText)
        _executionStatus.value = if (isError) {
            ExecutionStatus.Error(resultText.take(120))
        } else {
            ExecutionStatus.Done
        }
        com.clawgui.android.core.util.Log.i(
            "Slot", sessionKey,
            "released as ${if (isError) "Error" else "Done"} (text=${resultText.take(60)})",
        )
    }

    /**
     * 按 sessionKey 停掉任务,可用于 in-app 和外部 session。会 cancel AgentLoop 上的
     * 任务并走 releaseSlot 让悬浮球落幕。停下的 UI 提示走 Error("已停止") 而不是 Done。
     */
    fun stopSession(sessionKey: String) {
        com.clawgui.android.core.util.Log.i("Slot", sessionKey, "stopSession requested by user")
        agentLoop.cancelSession(sessionKey)
        pendingTurns.remove(sessionKey)?.let { turn ->
            turn.trace.record(
                eventType = "app_turn_cancelled",
                payload = mapOf("reason" to "user_cancelled"),
            )
            if (activeTurnRecorder === turn.trace) activeTurnRecorder = null
        }
        if (sessionKey.startsWith("ui:") && sessionKey == _currentSessionKey.value) {
            appendMessage(ChatMessage("assistant", "[已停止]"))
        }
        val wasActive = _activeRunningSessionKey.value == sessionKey
        releaseSlot(sessionKey, "已停止")
        if (wasActive) {
            // 覆盖 releaseSlot 里 Done 的默认行为 —— 用户主动停是中性结束而不是 Error,
            // 悬浮球/通知显示 ■ 已停止,不走红色"出错:"文案避免被当作 bug 误报
            _executionStatus.value = ExecutionStatus.Stopped("已停止")
        }
        ensureDisplayNameInternal(sessionKey)
        bumpSessionsVersion()
    }

    fun newSession(): Boolean {
        if (_isExecuting.value) return false
        _currentSessionKey.value = "ui:${System.currentTimeMillis()}"
        _currentSessionDisplayName.value = DEFAULT_NEW_NAME
        _chatMessages.value = emptyList()
        return true
    }

    fun switchSession(key: String): Boolean {
        // 允许执行中切换 view —— 只改显示不影响在途任务(任务自带 sessionKey)。
        // 用户需要切到外部 session 查看/停止时走这条路径。
        if (key == _currentSessionKey.value) return true
        val session = try { sessions.getOrCreate(key) } catch (_: Exception) { return false }
        _currentSessionKey.value = key
        _currentSessionDisplayName.value = sessionDisplayName(session)
        _chatMessages.value = sessionToChatMessages(session)
        return true
    }

    fun deleteSession(key: String): Boolean {
        if (_isExecuting.value) return false
        val isCurrent = key == _currentSessionKey.value
        if (isCurrent) {
            _currentSessionKey.value = "ui:${System.currentTimeMillis()}"
            _currentSessionDisplayName.value = DEFAULT_NEW_NAME
            _chatMessages.value = emptyList()
        }
        val ok = sessions.delete(key)
        bumpSessionsVersion()
        return ok
    }

    private fun loadInitialSession() {
        try {
            val list = sessions.listSessions()
            val firstKey = list.firstOrNull()?.get("key") as? String
            if (!firstKey.isNullOrBlank()) {
                val session = sessions.getOrCreate(firstKey)
                _currentSessionKey.value = firstKey
                _currentSessionDisplayName.value = sessionDisplayName(session)
                _chatMessages.value = sessionToChatMessages(session)
            }
        } catch (_: Exception) { /* keep blank new session */ }
    }

    private fun ensureDisplayNameInternal(key: String) {
        try {
            val session = sessions.getOrCreate(key)
            if (session.messages.isEmpty()) return
            val existing = session.metadata["display_name"] as? String
            if (existing.isNullOrBlank()) {
                session.metadata["display_name"] = deriveDefaultDisplayName(session)
                sessions.save(session)
            }
            if (_currentSessionKey.value == key) {
                _currentSessionDisplayName.value = sessionDisplayName(session)
            }
        } catch (_: Exception) {}
    }

    private fun sessionDisplayName(session: Session): String {
        val fromMeta = session.metadata["display_name"] as? String
        if (!fromMeta.isNullOrBlank()) return fromMeta
        return deriveDefaultDisplayName(session)
    }

    private fun deriveDefaultDisplayName(session: Session): String {
        val first = session.messages.firstOrNull { it["role"] == "user" }
        val content = (first?.get("content") as? String)?.trim()?.replace(Regex("\\s+"), " ")
        return if (!content.isNullOrBlank()) content.take(MAX_DISPLAY_NAME_LEN)
               else DEFAULT_NEW_NAME
    }

    /**
     * 手动改会话名字。
     * - newName 空 → 清掉 metadata,回到自动派生(第一条 user 消息)
     * - 非空 → trim + 压平空白 + 截 MAX_DISPLAY_NAME_LEN,写入 metadata 并 save
     * 不管 isExecuting —— 改名是纯本地 metadata 操作,执行中也能改。
     */
    fun renameSession(key: String, newName: String): Boolean {
        return try {
            val session = sessions.getOrCreate(key)
            val cleaned = newName.trim().replace(Regex("\\s+"), " ").take(MAX_DISPLAY_NAME_LEN)
            if (cleaned.isEmpty()) {
                session.metadata.remove("display_name")
            } else {
                session.metadata["display_name"] = cleaned
            }
            sessions.save(session)
            if (_currentSessionKey.value == key) {
                _currentSessionDisplayName.value = sessionDisplayName(session)
            }
            bumpSessionsVersion()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun sessionToChatMessages(session: Session): List<ChatMessage> {
        val out = mutableListOf<ChatMessage>()
        for (msg in session.messages) {
            val role = msg["role"] as? String ?: continue
            val content = msg["content"] as? String ?: continue
            if (content.isBlank()) continue
            when (role) {
                "user" -> out.add(ChatMessage("user", content))
                "assistant" -> {
                    if (!msg.containsKey("tool_calls")) out.add(ChatMessage("assistant", content))
                }
            }
        }
        return out
    }

    private fun startAgentService() {
        val intent = Intent(this, AgentService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (_: Exception) {}
    }

    private fun looksLikeError(content: String): Boolean {
        val trimmed = content.trimStart()
        return trimmed.startsWith("Error:", ignoreCase = true) ||
            trimmed.startsWith("Sorry, I encountered", ignoreCase = true) ||
            trimmed.startsWith("执行出错", ignoreCase = true)
    }

    private fun appendMessage(msg: ChatMessage) {
        _chatMessages.value = _chatMessages.value + msg
    }

    companion object {
        private const val DEFAULT_NEW_NAME = "新会话"
        private const val MAX_DISPLAY_NAME_LEN = 20

        @Volatile
        private var instance: App? = null

        fun getInstance(): App =
            instance ?: throw IllegalStateException("App not initialized")

        private val PERMISSION_RESULT_LISTENER =
            Shizuku.OnRequestPermissionResultListener { _, grantResult ->
                val granted = grantResult == PackageManager.PERMISSION_GRANTED
                println("[Shizuku] Permission result: $granted")
            }
    }
}
