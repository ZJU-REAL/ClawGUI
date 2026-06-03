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

    /** Images the user has attached but not yet sent. Cleared on send / cancel.
     *  Capped at MAX_ATTACHMENTS to keep the upload + VLM token cost bounded. */
    private val _pendingAttachments = MutableStateFlow<List<com.clawgui.ng.data.Attachment>>(emptyList())
    val pendingAttachments: StateFlow<List<com.clawgui.ng.data.Attachment>> = _pendingAttachments

    private val _isExecuting = MutableStateFlow(false)
    val isExecuting: StateFlow<Boolean> = _isExecuting

    private var runJob: Job? = null

    // ── Voice recording state ───────────────────────────────────────────

    enum class VoiceState { IDLE, RECORDING, TRANSCRIBING }

    private val _voiceState = MutableStateFlow(VoiceState.IDLE)
    val voiceState: StateFlow<VoiceState> = _voiceState

    private var audioRecorder: com.clawgui.ng.runtime.media.AudioRecorder? = null
    private var recordingJob: Job? = null

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

    // ── Voice input ─────────────────────────────────────────────────────

    /**
     * Toggle voice recording. First call starts recording, second call stops
     * and transcribes. Handles permission externally — caller must ensure
     * RECORD_AUDIO is granted before invoking.
     */
    fun toggleVoice() {
        when (_voiceState.value) {
            VoiceState.IDLE -> startRecording()
            VoiceState.RECORDING -> stopAndTranscribe()
            VoiceState.TRANSCRIBING -> {} // ignore taps while transcribing
        }
    }

    private fun startRecording() {
        val recorder = com.clawgui.ng.runtime.media.AudioRecorder(RuntimeContainer.appContext)
        audioRecorder = recorder
        _voiceState.value = VoiceState.RECORDING
        recordingJob = viewModelScope.launch {
            recorder.start()
        }
    }

    private fun stopAndTranscribe() {
        val file = audioRecorder?.stop() ?: return
        _voiceState.value = VoiceState.TRANSCRIBING

        viewModelScope.launch {
            val settings = RuntimeContainer.settings
            val apiKey = settings.sttApiKey()
            if (apiKey.isBlank()) {
                _voiceState.value = VoiceState.IDLE
                _draft.value = "[语音识别未配置 — 请到「设置 → 语音识别」填写 API Key]"
                return@launch
            }

            // Wait for recorder coroutine to finish writing WAV.
            recordingJob?.join()

            val client = com.clawgui.ng.runtime.stt.SttClient(
                baseUrl = settings.sttBaseUrl.value,
                apiKey = apiKey,
                model = settings.sttModel.value,
            )
            val text = try {
                withContext(Dispatchers.IO) {
                    client.transcribe(
                        audioFile = file,
                        language = settings.sttLanguage.value,
                    )
                }
            } catch (e: Throwable) {
                android.util.Log.w("Voice", "STT failed", e)
                "[识别失败: ${e.message?.take(60)}]"
            } finally {
                file.delete()
            }
            _draft.value = (_draft.value + text).trim()
            _voiceState.value = VoiceState.IDLE
        }
    }

    fun cancelVoice() {
        audioRecorder?.stop()
        recordingJob?.cancel()
        recordingJob = null
        audioRecorder = null
        _voiceState.value = VoiceState.IDLE
    }

    /**
     * Persist a freshly-picked image into the app sandbox and stage it as a
     * pending attachment. Silently no-ops past [MAX_ATTACHMENTS] (UI grays the
     * picker out at that point). Runs the disk copy on IO.
     */
    fun attachImage(uri: android.net.Uri) {
        if (_pendingAttachments.value.size >= MAX_ATTACHMENTS) return
        viewModelScope.launch {
            val path = withContext(Dispatchers.IO) {
                com.clawgui.ng.runtime.media.AttachmentStore.saveImage(
                    RuntimeContainer.appContext, uri
                )
            } ?: return@launch
            val display = uri.lastPathSegment?.substringAfterLast('/')?.take(40) ?: "图片"
            val att = com.clawgui.ng.data.Attachment(
                id = "att_" + UUID.randomUUID().toString().take(8),
                kind = com.clawgui.ng.data.AttachmentKind.IMAGE,
                uri = path,
                displayName = display,
                sizeBytes = java.io.File(path).length(),
            )
            _pendingAttachments.value = _pendingAttachments.value + att
        }
    }

    fun removePendingAttachment(id: String) {
        val keep = mutableListOf<com.clawgui.ng.data.Attachment>()
        _pendingAttachments.value.forEach {
            if (it.id == id) {
                runCatching { com.clawgui.ng.runtime.media.AttachmentStore.delete(it.uri) }
            } else keep += it
        }
        _pendingAttachments.value = keep
    }

    fun clearPendingAttachments() {
        // Drop staged files from disk too — they were only valid for the
        // not-yet-sent draft and would otherwise leak.
        _pendingAttachments.value.forEach {
            runCatching { com.clawgui.ng.runtime.media.AttachmentStore.delete(it.uri) }
        }
        _pendingAttachments.value = emptyList()
    }

    companion object {
        const val MAX_ATTACHMENTS = 3
    }

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
            val userImages = encodeAttachmentsForVlm(trigger.attachments)
            if (explicitGui || toggleGui) {
                val task = if (explicitGui) text.substringAfter(' ').trim() else text
                runPhoneAgent(key, task, userImages)
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
        val staged = _pendingAttachments.value
        // Allow sending image-only ("describe this") with empty text.
        if ((text.isEmpty() && staged.isEmpty()) || _isExecuting.value) return
        _draft.value = ""
        // Move staged → message; don't run clearPendingAttachments() — that
        // would delete the underlying files we just attached.
        _pendingAttachments.value = emptyList()

        val key = sessions.currentKey.value
        val userMsg = ChatMessage(
            id = "msg_" + UUID.randomUUID().toString().take(8),
            role = Role.USER,
            content = text,
            attachments = staged,
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
            val userImages = encodeAttachmentsForVlm(staged)
            if (explicitGui || toggleGui) {
                val task = if (explicitGui) text.substringAfter(' ').trim() else text
                runPhoneAgent(key, task, userImages)
            } else {
                runBrainTurn(key)
            }
            _isExecuting.value = false
        }
    }

    /**
     * Drop a follow-up's full prompt text into the input box. Doesn't auto-send
     * — the user reviews / edits first. Tapped from the chip strip rendered
     * under the most recent assistant message.
     */
    fun pickFollowUp(prompt: String) {
        _draft.value = prompt
    }

    /**
     * Kick a best-effort follow-up-suggestion generation after an assistant
     * turn lands. Non-blocking, runs on the VM scope. Failures are silent —
     * if it doesn't return anything the chip row simply doesn't render.
     *
     * Captures the current last-assistant message id so a race against the
     * next user turn can't mis-patch a different message.
     */
    private fun scheduleFollowUps(key: String) {
        val targetId = sessions.messagesFor(key).value
            .lastOrNull { it.role == Role.ASSISTANT }
            ?.id ?: return
        viewModelScope.launch {
            val brainId = RuntimeContainer.settings.activeBrain.value
            val cred = RuntimeContainer.settings.resolveCredentials(brainId) ?: return@launch
            val history = sessions.messagesFor(key).value
            val suggestions = withContext(Dispatchers.IO) {
                runCatching {
                    com.clawgui.ng.runtime.llm.FollowUpGenerator.generate(cred, history)
                }.getOrNull().orEmpty()
            }
            if (suggestions.isNotEmpty()) {
                sessions.updateMessageById(key, targetId) { it.copy(followUps = suggestions) }
            }
        }
    }

    private suspend fun encodeAttachmentsForVlm(
        atts: List<com.clawgui.ng.data.Attachment>,
    ): List<String> = withContext(Dispatchers.IO) {
        atts
            .filter { it.kind == com.clawgui.ng.data.AttachmentKind.IMAGE }
            .mapNotNull { att ->
                com.clawgui.ng.runtime.media.AttachmentStore.readBytes(att.uri)
                    ?.let { java.util.Base64.getEncoder().encodeToString(it) }
            }
    }

    /**
     * Generic two-stage preprocessing for image-bearing GUI tasks. Runs once
     * before the PhoneAgent loop starts and returns an augmented task string.
     *
     * Stage A — Brain-side image understanding via [ImageInsight]. Best
     * effort: on any failure the task still falls through, just without
     * insights.
     *
     * Stage B — Publish each attachment to the system gallery
     * ([MediaStoreExporter]) so the agent can pick them through the host
     * app's normal photo picker (Moments composer, file picker, …).
     *
     * The augmented task text reaches the VLM as plain prompt — the VLM
     * never sees the actual images.
     */
    private suspend fun preprocessAttachmentsForAgent(
        originalTask: String,
        attachments: List<com.clawgui.ng.data.Attachment>,
        imagesBase64: List<String>,
        cred: com.clawgui.ng.data.repo.ProviderCredentials,
        progressKey: String,
    ): String {
        // Reflect progress in the chat bubble so the user knows what's happening
        // while we wait on a VLM round-trip.
        sessions.updateLastMessage(progressKey) {
            it.copy(content = "正在读取图片内容…")
        }
        RuntimeContainer.publishExecution(
            ExecutionStatus(state = ExecutionState.THINKING, title = "读取图片内容")
        )

        // — Stage A: Brain reads images.
        val brainId = RuntimeContainer.settings.activeBrain.value
        val brainCred = RuntimeContainer.settings.resolveCredentials(brainId)
        val insightCred = com.clawgui.ng.runtime.llm.ImageInsight.pickVisionCreds(brainCred, cred)
        val insight = if (insightCred != null) {
            withContext(Dispatchers.IO) {
                runCatching {
                    com.clawgui.ng.runtime.llm.ImageInsight.analyse(
                        creds = insightCred,
                        userTask = originalTask,
                        imagesBase64 = imagesBase64,
                    )
                }.getOrNull() ?: com.clawgui.ng.runtime.llm.ImageInsight.Result(emptyList(), "")
            }
        } else com.clawgui.ng.runtime.llm.ImageInsight.Result(emptyList(), "")

        // — Stage B: Publish to gallery so any host app's picker can find them.
        val exported: List<com.clawgui.ng.runtime.media.MediaStoreExporter.Exported> =
            withContext(Dispatchers.IO) {
                attachments.mapIndexedNotNull { idx, att ->
                    com.clawgui.ng.runtime.media.MediaStoreExporter.exportToGallery(
                        RuntimeContainer.appContext,
                        java.io.File(att.uri),
                        displayLabel = "clawgui_${idx + 1}",
                    )
                }
            }

        // — Compose augmented task text.
        val sb = StringBuilder()
        sb.append(originalTask.trim())
        sb.append("\n\n# 任务上下文(由本机预处理生成)")
        sb.append("\n- 用户已经把 ${attachments.size} 张图片放进了本机相册。")
        if (exported.isNotEmpty()) {
            sb.append("\n  路径:Pictures/ClawGUI/")
            sb.append("\n  文件名:${exported.joinToString("、") { it.displayName }}")
            sb.append("\n  也是相册里最新的 ${exported.size} 张照片(按拍摄时间倒序排在最前)。")
        }
        if (insight.perImageSummary.isNotEmpty()) {
            sb.append("\n- 图片内容摘要(已由 Brain 看过,你看不到原图,只能基于这段文字):")
            insight.perImageSummary.forEachIndexed { i, s ->
                sb.append("\n    图${i + 1}: $s")
            }
        }
        if (insight.extractable.isNotBlank()) {
            sb.append("\n- 任务隐含需要的文字(若用户没另外指定,这就是你应当填入的内容):")
            sb.append("\n    ").append(insight.extractable)
        }
        sb.append("\n\n继续按系统提示的格式执行任务。")
        return sb.toString()
    }

    /**
     * Drive the PhoneAgent step-by-step. Each step's `<think>` and chosen
     * action are appended to the assistant message's `thinking` block (so the
     * user can expand it), and the dynamic island mirrors the same info.
     * The visible `content` is the running summary; on finish it becomes the
     * agent's final message.
     *
     * Attachments are **inputs to the task**, not VLM reference images. The
     * VLM only ever sees the live phone screen — handing it the user's photos
     * directly tends to stall vision-action models trained on a single-frame
     * prompt (AutoGLM-Phone in particular). Instead:
     *
     *   1. Brain (or the Vision provider as fallback) summarises each image
     *      and extracts whatever ready-to-use text the task implies (caption,
     *      OCR, scanned URL, etc.) — see [ImageInsight].
     *   2. The attachments are published into the system gallery via
     *      [MediaStoreExporter] so the agent can pick them through whatever
     *      app the task involves (Moments composer, file picker, etc.).
     *   3. The original `task` string is augmented with the insight + the
     *      list of exported filenames so the VLM operates on text alone.
     *
     * This keeps the design generic: post-to-Moments, send-via-WeChat,
     * scan-QR, transcribe-menu and "describe this picture then act on it"
     * all flow through the same pre-processing — no per-scenario branching.
     */
    private suspend fun runPhoneAgent(key: String, taskInput: String, userImages: List<String> = emptyList()) {
        var task = taskInput
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

        // ── Attachment pre-processing (only when user attached images) ──────
        // Grab the actual Attachment entries from the chat message so we have
        // both file paths (for gallery export) and base64 (for ImageInsight)
        // — `userImages` from the caller only carries the base64 bytes.
        val lastUserAtts = sessions.messagesFor(key).value
            .lastOrNull { it.role == Role.USER }
            ?.attachments
            .orEmpty()
            .filter { it.kind == com.clawgui.ng.data.AttachmentKind.IMAGE }
        if (lastUserAtts.isNotEmpty()) {
            task = preprocessAttachmentsForAgent(
                originalTask = task,
                attachments = lastUserAtts,
                imagesBase64 = userImages,
                cred = cred,
                progressKey = key,
            )
        }

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
        // Running plan: mutated by each step's PlanProtocol ops, then snapshot
        // into the assistant message so the chat UI renders it live. Starts
        // null and stays null if the model never emits an `<plan>` block —
        // chat falls back to plain-text bubble + thinking panel only.
        var runningPlan: com.clawgui.ng.data.Plan? = null
        // Running action trace: one StepRecord per PhoneAgent step. Snapshot
        // into the assistant message every iteration so the chat-side
        // ActionTraceList ticks in real time.
        val trace = mutableListOf<com.clawgui.ng.data.StepRecord>()
        // No hard cap — agent runs until it finishes, you stop it, or stuck-detection bails.
        var finalMessage: String? = null
        var stepIdx = 0

        try {
            sessions.updateLastMessage(key) {
                it.copy(content = "正在规划任务…", thinking = "")
            }
            while (true) {
                stepIdx++
                // Hard timeout — if any single step (screenshot + VLM + action)
                // exceeds 5 minutes, the system has almost certainly throttled
                // us into oblivion. Bail with a clear message instead of hanging.
                val step = try {
                    kotlinx.coroutines.withTimeout(5 * 60 * 1000L) {
                        withContext(Dispatchers.IO) {
                            // VLM only sees the live screen — never the user's
                            // input images. They've already been turned into
                            // text context by preprocessAttachmentsForAgent.
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

                // Apply this step's plan ops if any. Best-effort: malformed
                // JSON / missing block ⇒ plan stays as it was.
                runningPlan = com.clawgui.ng.runtime.phone.PlanProtocol.apply(
                    previous = runningPlan,
                    body = step.planOps,
                    stepIndex = stepIdx,
                )

                trace.add(com.clawgui.ng.data.StepRecord(
                    stepIndex = stepIdx,
                    actionName = actionName,
                    actionExtra = actionExtra,
                    thinkingPreview = step.thinking
                        .lineSequence()
                        .firstOrNull { it.isNotBlank() }
                        .orEmpty()
                        .take(120),
                    success = step.success,
                    inProgress = false,
                ))

                sessions.updateLastMessage(key) {
                    it.copy(
                        content = "正在执行第 $stepIdx 步:$actionName${if (actionExtra.isNotBlank()) " · $actionExtra" else ""}",
                        thinking = thinkingLog.toString(),
                        plan = runningPlan,
                        actionTrace = trace.toList(),
                    )
                }
                RuntimeContainer.publishAgentLive(
                    RuntimeContainer.AgentLiveSnapshot(
                        plan = runningPlan,
                        trace = trace.toList(),
                        streaming = !step.finished,
                    )
                )
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

                // ── Ask-the-user mid-task ──────────────────────────────────
                // Model emitted `do(action="Ask", question="...")`. Surface a
                // floating overlay above whatever app is on top, suspend the
                // loop until the user types an answer (or cancels), then
                // inject the answer back into the agent context and resume.
                if (actionName == "Ask") {
                    val question = (step.action["question"] as? String)
                        ?: step.message
                        ?: "请补充信息"
                    sessions.updateLastMessage(key) {
                        it.copy(content = "🙋 已问你:$question\n等你回复中…", thinking = thinkingLog.toString())
                    }
                    RuntimeContainer.publishExecution(
                        ExecutionStatus(
                            state = ExecutionState.THINKING,
                            title = "等待用户回答",
                            subtitle = question.take(80),
                        )
                    )
                    // Publish the question through the overlay's live
                    // snapshot — the floating panel renders an inline input
                    // box and submits back via the onAskAnswer callback.
                    // Suspend on a CompletableDeferred so the loop pauses
                    // until the user submits or cancels.
                    val deferred =
                        kotlinx.coroutines.CompletableDeferred<String?>()
                    RuntimeContainer.publishAgentLive(
                        RuntimeContainer.AgentLiveSnapshot(
                            plan = runningPlan,
                            trace = trace.toList(),
                            streaming = false,
                            askQuestion = question,
                            onAskAnswer = { deferred.complete(it) },
                        )
                    )
                    val answer = deferred.await()
                    // Clear the ask state — overlay drops the input UI but
                    // stays visible for the next step.
                    RuntimeContainer.publishAgentLive(
                        RuntimeContainer.AgentLiveSnapshot(
                            plan = runningPlan,
                            trace = trace.toList(),
                            streaming = true,
                        )
                    )
                    if (answer == null) {
                        finalMessage = "已被用户取消(没回答 Ask)"
                        break
                    }
                    agent.injectUserAnswer(question, answer)
                    // Persist the user's answer into the chat thinking log so
                    // the trace + post-mortem viewer shows what was asked /
                    // answered, not just an opaque "Ask" step.
                    thinkingLog.append("\n\n👤 用户回答:$answer")
                    sessions.updateLastMessage(key) {
                        it.copy(content = "用户已回答,继续执行…", thinking = thinkingLog.toString())
                    }
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
            // Always return the user to the ClawGUI chat — success, error,
            // cancel, all paths. The finally block guarantees it even when
            // the coroutine was cancelled mid-step (user pressed Stop). The
            // foreground service we acquired up top grants background
            // activity-start on Android 12+ ROMs that honour spec; Honor /
            // MagicOS still ignores it sometimes, in which case the
            // execution-state notification's content-intent is the fallback.
            runCatching { bringClawGuiToFront() }
            // Drop the live overlay snapshot — overlay collapses to small
            // pill (or hides entirely) once the run is over.
            RuntimeContainer.publishAgentLive(null)
        }

        sessions.updateLastMessage(key) {
            it.copy(
                content = finalMessage ?: "任务结束",
                thinking = thinkingLog.toString(),
                plan = runningPlan,
                actionTrace = trace.toList(),
                streaming = false,
            )
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
        scheduleFollowUps(key)
        // If invoked from a Feishu chat, ship the PhoneAgent's final summary
        // back over the Feishu API so the original sender knows it's done.
        if (key.startsWith("feishu:") && !finalMessage.isNullOrBlank()) {
            val chatId = key.removePrefix("feishu:")
            val lastMsgId = RuntimeContainer.inbox.lastMessageId(key)
            runCatching {
                RuntimeContainer.feishu.reply(chatId, finalMessage!!, lastMsgId)
            }
            // Optional image reply. The text is always sent above; this
            // attaches a screenshot (final state) or a composite long
            // image of every step's screenshot depending on the user's
            // setting. COMPOSITE silently downgrades to FINAL_ONLY if
            // traces weren't recorded for this run.
            val mode = runCatching { RuntimeContainer.settings.feishuReplyImageMode.value }
                .getOrDefault(com.clawgui.ng.data.repo.FeishuReplyImageMode.FINAL_ONLY)
            android.util.Log.i("ChatVM", "feishu image reply: mode=$mode runDir=${recorder?.runDir}")
            if (mode != com.clawgui.ng.data.repo.FeishuReplyImageMode.OFF) {
                try {
                    val runDir = recorder?.runDir
                    val labels = trace.map { rec ->
                        rec.actionName + if (rec.actionExtra.isNotBlank()) " · ${rec.actionExtra.take(24)}" else ""
                    }
                    val bytes = withContext(Dispatchers.IO) {
                        val direct = when (mode) {
                            com.clawgui.ng.data.repo.FeishuReplyImageMode.COMPOSITE ->
                                com.clawgui.ng.runtime.feishu.ReplyImageBuilder.compositeLong(runDir, labels)
                                    ?: com.clawgui.ng.runtime.feishu.ReplyImageBuilder.finalShot(runDir)
                            com.clawgui.ng.data.repo.FeishuReplyImageMode.FINAL_ONLY ->
                                com.clawgui.ng.runtime.feishu.ReplyImageBuilder.finalShot(runDir)
                            else -> null
                        }
                        // If trace-based path didn't yield bytes (e.g. user
                        // had trace recording off), grab a live screenshot
                        // right now so the user still gets *something*.
                        direct ?: run {
                            android.util.Log.i("ChatVM",
                                "trace produced no bytes — falling back to live device.screenshot()")
                            val raw = runCatching { device.screenshot() }.getOrNull()
                            raw?.let {
                                com.clawgui.ng.runtime.phone.util.ScreenshotCompressor.compress(
                                    it,
                                    com.clawgui.ng.runtime.phone.util.ScreenshotCompressor.Quality.MEDIUM,
                                )
                            }
                        }
                    }
                    android.util.Log.i("ChatVM", "feishu image bytes=${bytes?.size ?: 0}")
                    if (bytes != null && bytes.isNotEmpty()) {
                        val err = RuntimeContainer.feishu.replyImage(chatId, bytes, lastMsgId)
                        if (err != null) {
                            android.util.Log.w("ChatVM", "feishu replyImage failed: $err")
                            // Surface the failure both in the chat bubble's
                            // error slot and on the Feishu debug-log surface
                            // so the user can see it without logcat.
                            sessions.updateLastMessage(key) {
                                it.copy(error = "飞书回图失败:$err")
                            }
                            runCatching { RuntimeContainer.feishu.appendDebug("replyImage 失败: $err") }
                        } else {
                            android.util.Log.i("ChatVM", "feishu replyImage OK (${bytes.size} bytes)")
                        }
                    } else {
                        android.util.Log.w("ChatVM",
                            "feishu image reply skipped: no bytes available (runDir=$runDir)")
                        sessions.updateLastMessage(key) {
                            it.copy(error = "飞书回图未发出:本机没生成图片(检查「记录运行轨迹」是否开启)")
                        }
                    }
                } catch (t: Throwable) {
                    android.util.Log.w("ChatVM", "feishu image reply threw", t)
                }
            }
        }
        // (Bring-to-front happens unconditionally inside the finally above
        // so we don't miss the cancellation / error paths.)
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

    /**
     * Foreground the ClawGUI MainActivity so the user lands back on the chat
     * after the agent finishes / errors / is cancelled. Belt-and-braces:
     *   1. Direct startActivity with NEW_TASK + REORDER_TO_FRONT — works on
     *      stock Android 12+ thanks to the foreground service.
     *   2. PendingIntent.send() fallback — survives some OEM
     *      background-activity-start restrictions (Honor MagicOS, MIUI)
     *      that ignore the direct path even with FGS.
     */
    private fun bringClawGuiToFront() {
        val ctx = RuntimeContainer.appContext
        val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
            ?: return
        launch.addFlags(
            android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP,
        )
        runCatching { ctx.startActivity(launch) }
        runCatching {
            val pi = android.app.PendingIntent.getActivity(
                ctx, 0, launch,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    android.app.PendingIntent.FLAG_IMMUTABLE,
            )
            pi.send()
        }
    }

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
        // Clear the live-snapshot so the overlay hides; the inline Ask
        // input (if any) goes away with it.
        RuntimeContainer.publishAgentLive(null)
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
        val brainCred = RuntimeContainer.settings.resolveCredentials(brainId)
        if (brainCred == null || brainCred.apiKey.isBlank()) {
            sessions.updateLastMessage(key) {
                it.copy(
                    content = "尚未配置 ${brainId} 的 API Key。\n\n请到「设置 → AI 模型」粘贴你的智谱 API Key,然后再次发送。",
                    streaming = false,
                    error = "missing api key",
                )
            }
            return
        }

        // If the latest user message has images but the configured Brain can't
        // see, fall back to the active Vision provider for this turn (it's an
        // OpenAI-compatible VLM and answers free-form prompts fine when not
        // looking at a phone screenshot). User → 设置 → AI 模型 controls both.
        val historyFull = sessions.messagesFor(key).value.dropLast(1)
        val lastUser = historyFull.lastOrNull { it.role == Role.USER }
        val hasUserImages = lastUser?.attachments?.any {
            it.kind == com.clawgui.ng.data.AttachmentKind.IMAGE
        } == true

        val (cred, fellBack) = if (hasUserImages && !brainCred.supportsVision) {
            val visionId = RuntimeContainer.settings.activeVision.value
            val visionCred = RuntimeContainer.settings.resolveCredentials(visionId)
            if (visionCred != null && visionCred.apiKey.isNotBlank() && visionCred.supportsVision) {
                android.util.Log.i("ChatVM", "Brain $brainId can't see images — falling back to $visionId for this turn")
                visionCred to true
            } else {
                brainCred to false
            }
        } else {
            brainCred to false
        }

        RuntimeContainer.publishExecution(
            ExecutionStatus(
                state = ExecutionState.THINKING,
                title = if (fellBack) "调用视觉模型" else "正在思考",
                subtitle = cred.model,
            )
        )

        val brain = BrainRuntime(cred)
        val history = historyFull
        val baseSystem = "你是 ClawGUI 的对话大脑。回答简洁清楚,中文优先。当用户希望你帮助操作手机时,先做计划再行动。"
        val system = if (fellBack) {
            "$baseSystem\n用户附了图片在最后一条消息里,请按用户要求基于图片内容作答。"
        } else baseSystem
        val acc = StringBuilder()
        val thinkAcc = StringBuilder()
        // For models that inline reasoning via <think>...</think> tags inside
        // the content stream, this incrementally peels them out and routes
        // them to `thinking` instead. Models that surface reasoning_content
        // as a sibling SSE field deliver it via StreamEvent.ReasoningDelta
        // and bypass the splitter entirely.
        val splitter = com.clawgui.ng.runtime.llm.ThinkTagSplitter()

        try {
            brain.streamReply(system, history).collect { ev ->
                when (ev) {
                    is StreamEvent.ReasoningDelta -> {
                        thinkAcc.append(ev.text)
                        val current = thinkAcc.toString()
                        sessions.updateLastMessage(key) { it.copy(thinking = current) }
                        RuntimeContainer.publishExecution(
                            ExecutionStatus(
                                state = ExecutionState.THINKING,
                                title = "正在思考",
                                subtitle = cred.model,
                                thinking = current.takeLast(120),
                            )
                        )
                    }
                    is StreamEvent.Delta -> {
                        val out = splitter.push(ev.text)
                        if (out.thinking.isNotEmpty()) thinkAcc.append(out.thinking)
                        if (out.content.isNotEmpty()) acc.append(out.content)
                        if (!out.isEmpty()) {
                            val curContent = acc.toString()
                            val curThink = thinkAcc.toString().takeIf { it.isNotEmpty() }
                            sessions.updateLastMessage(key) {
                                it.copy(content = curContent, thinking = curThink)
                            }
                        }
                        RuntimeContainer.publishExecution(
                            ExecutionStatus(
                                state = ExecutionState.ACTING,
                                title = "正在回复",
                                subtitle = cred.model,
                            )
                        )
                    }
                    is StreamEvent.Done -> {
                        val tail = splitter.finish()
                        if (tail.thinking.isNotEmpty()) thinkAcc.append(tail.thinking)
                        if (tail.content.isNotEmpty()) acc.append(tail.content)
                        sessions.updateLastMessage(key) {
                            it.copy(
                                content = acc.toString(),
                                thinking = thinkAcc.toString().takeIf { s -> s.isNotEmpty() },
                                streaming = false,
                            )
                        }
                        RuntimeContainer.publishExecution(
                            ExecutionStatus(state = ExecutionState.DONE, title = "执行完成")
                        )
                        scheduleFollowUps(key)
                    }
                    is StreamEvent.Error -> {
                        sessions.updateLastMessage(key) {
                            it.copy(
                                content = if (acc.isEmpty()) "请求失败:" + ev.message else acc.toString(),
                                thinking = thinkAcc.toString().takeIf { s -> s.isNotEmpty() },
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
