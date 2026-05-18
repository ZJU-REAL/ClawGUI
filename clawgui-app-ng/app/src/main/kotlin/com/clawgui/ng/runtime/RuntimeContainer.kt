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
