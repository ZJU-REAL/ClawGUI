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
 * Lightweight service-locator. Replaces the App.kt monolith from clawgui-app.
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
                if (settings.feishuAutoReply.value) {
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
