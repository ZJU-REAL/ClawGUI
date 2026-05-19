package com.clawgui.ng.runtime.feishu

import com.clawgui.ng.data.InboxEntry
import com.clawgui.ng.data.SessionSource
import com.clawgui.ng.data.repo.InboxRepository
import com.lark.oapi.Client
import com.lark.oapi.event.EventDispatcher
import com.lark.oapi.service.im.ImService
import com.lark.oapi.service.im.v1.model.CreateMessageReq
import com.lark.oapi.service.im.v1.model.CreateMessageReqBody
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1
import com.lark.oapi.service.im.v1.model.ReplyMessageReq
import com.lark.oapi.service.im.v1.model.ReplyMessageReqBody
import com.lark.oapi.ws.Client as WsClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.json.JSONObject
import java.util.logging.Logger

/**
 * Slim Feishu (Lark) integration for ng.
 *
 * Receives:  WebSocket long-polled events via oapi-sdk → emits new
 *            [InboxEntry] into [InboxRepository] so the user sees them in
 *            the drawer inbox.
 *
 * Sends:    REST reply (preferred — replies to thread) or create (fallback
 *            when we never saw the message). Both via oapi-sdk synchronous
 *            client, wrapped in withTimeoutOrNull so a hung network can't
 *            deadlock the coroutine.
 *
 * Lifecycle: `start()` / `stop()` are idempotent; SettingsRepository
 *            observes the enable flag and calls them.
 */
class FeishuChannel(
    private val inbox: InboxRepository,
    private val sessions: com.clawgui.ng.data.repo.SessionRepository,
    private val onInbound: ((sessionKey: String, text: String, messageId: String?) -> Unit)? = null,
) {

    data class Config(
        val appId: String,
        val appSecret: String,
        val botName: String,
        val allowedOpenIds: List<String>,
        val allowAll: Boolean,
    ) {
        val isUsable: Boolean get() = appId.isNotBlank() && appSecret.isNotBlank()
    }

    enum class State { Stopped, Starting, Running, Failed }

    private val _state = MutableStateFlow(State.Stopped)
    val state: StateFlow<State> = _state

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    /**
     * Rolling 60-entry diagnostic log surfaced to the Channels settings page
     * so the user can debug without `adb logcat`. Each entry is one line.
     */
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log

    private fun dlog(line: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        val entry = "$ts  $line"
        logger.info(entry)
        _log.value = (_log.value + entry).takeLast(60)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var restClient: Client? = null
    @Volatile private var wsClient: WsClient? = null
    private var wsJob: Job? = null
    @Volatile private var config: Config? = null

    suspend fun start(cfg: Config) = withContext(Dispatchers.IO) {
        if (_state.value == State.Running || _state.value == State.Starting) return@withContext
        if (!cfg.isUsable) {
            _state.value = State.Failed
            _lastError.value = "缺少 App ID 或 App Secret"
            return@withContext
        }
        _state.value = State.Starting
        _lastError.value = null
        config = cfg
        try {
            restClient = Client.newBuilder(cfg.appId, cfg.appSecret).build()
            val dispatcher = EventDispatcher.Builder("", "")
                .onP2MessageReceiveV1(object : ImService.P2MessageReceiveV1Handler() {
                    override fun handle(data: P2MessageReceiveV1) {
                        runCatching { onIncomingEvent(data) }
                            .onFailure { logger.warning("onIncoming threw: ${it.message}") }
                    }
                })
                .build()
            val client = WsClient.Builder(cfg.appId, cfg.appSecret)
                .eventHandler(dispatcher)
                .build()
            wsClient = client
            _state.value = State.Running
            wsJob = scope.launch(Dispatchers.IO) {
                try {
                    client.start()
                } catch (e: Throwable) {
                    dlog("Feishu WS loop ended: ${e.message}")
                    _state.value = State.Failed
                    _lastError.value = e.message ?: e.javaClass.simpleName
                }
            }
            dlog("FeishuChannel started")
        } catch (e: Throwable) {
            dlog("FeishuChannel start failed: ${e.message}")
            _state.value = State.Failed
            _lastError.value = e.message ?: e.javaClass.simpleName
            restClient = null
            wsClient = null
        }
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        wsJob?.cancel()
        wsJob = null
        wsClient = null
        restClient = null
        _state.value = State.Stopped
        _lastError.value = null
        dlog("FeishuChannel stopped")
    }

    fun shutdown() {
        runCatching { wsJob?.cancel() }
        scope.cancel()
        wsClient = null
        restClient = null
        _state.value = State.Stopped
    }

    /**
     * Send a reply. If [messageId] is provided we reply in-thread; otherwise
     * we create a new top-level message to [chatId]. Returns null on success,
     * an error string otherwise.
     */
    suspend fun reply(chatId: String, text: String, messageId: String? = null): String? =
        withContext(Dispatchers.IO) {
            val client = restClient ?: return@withContext "Feishu 客户端未启动"
            val content = buildJsonObject { put("text", text.ifBlank { "(无回复)" }) }.toString()
            try {
                val resp = withTimeoutOrNull(SEND_TIMEOUT_MS) {
                    if (!messageId.isNullOrBlank()) {
                        client.im().message().reply(
                            ReplyMessageReq.newBuilder()
                                .messageId(messageId)
                                .replyMessageReqBody(
                                    ReplyMessageReqBody.newBuilder()
                                        .content(content).msgType("text").build()
                                )
                                .build()
                        )
                    } else {
                        client.im().message().create(
                            CreateMessageReq.newBuilder()
                                .receiveIdType("chat_id")
                                .createMessageReqBody(
                                    CreateMessageReqBody.newBuilder()
                                        .receiveId(chatId)
                                        .content(content).msgType("text").build()
                                )
                                .build()
                        )
                    }
                }
                when {
                    resp == null -> "请求超时(${SEND_TIMEOUT_MS}ms)"
                    !resp.success() -> "飞书 API 错误:${resp.error}"
                    else -> null
                }
            } catch (e: Throwable) {
                e.message ?: e::class.simpleName ?: "未知错误"
            }
        }

    private fun isAllowed(senderOpenId: String): Boolean {
        val cfg = config ?: return false
        return cfg.allowAll || senderOpenId in cfg.allowedOpenIds
    }

    private fun onIncomingEvent(data: P2MessageReceiveV1) {
        val event = data.event ?: run {
            dlog("Feishu: event is null"); return
        }
        val msg = event.message ?: run {
            dlog("Feishu: message is null"); return
        }
        val sender = event.sender?.senderId?.openId ?: event.sender?.senderId?.unionId
        val chatId = msg.chatId
        val messageId = msg.messageId
        val msgType = msg.messageType ?: "?"
        val chatType = msg.chatType ?: "?"
        dlog("Feishu RX: type=$msgType chatType=$chatType chatId=$chatId from=$sender msgId=$messageId")

        if (chatId.isNullOrBlank() || sender.isNullOrBlank()) {
            dlog("Feishu drop: missing chatId/sender")
            return
        }

        // Extract a plain-text preview from whatever the message type is.
        val text = extractText(msg.content, msgType)
        if (text.isBlank()) {
            dlog("Feishu drop: empty text after extracting $msgType content")
            return
        }

        // In group chats, only react when our bot is actually mentioned —
        // otherwise we'd flood the inbox with everyone's chatter. P2P always
        // passes. We detect "@me" by scanning the mentions array.
        val mentionedMe = (msg.mentions?.any { m ->
            // SDK returns at_user_id strings like "@_user_1"; align with sender keys it later resolves.
            m.key?.isNotBlank() == true || m.id?.openId?.isNotBlank() == true
        }) ?: false
        val isGroup = chatType == "group"
        if (isGroup && !mentionedMe) {
            dlog("Feishu drop: group msg without @ mention")
            return
        }

        if (!isAllowed(sender)) {
            dlog("Feishu drop: sender $sender not in allowlist (allowAll=${config?.allowAll})")
            return
        }
        val sessionKey = "feishu:$chatId"
        // 1. Make sure a session exists so tapping the inbox row actually shows
        //    the conversation. Title starts generic; ChatViewModel will let
        //    Brain auto-summarise once we get the first message in.
        sessions.ensureExternalSession(
            key = sessionKey,
            defaultTitle = if (isGroup) "群消息 · 飞书" else "飞书消息",
            source = SessionSource.FEISHU,
        )
        // 2. Append the incoming text as a USER turn in that session so the
        //    chat surface renders it in the bubble layout.
        sessions.appendMessage(
            sessionKey,
            com.clawgui.ng.data.ChatMessage(
                id = "feishu_" + (messageId ?: System.currentTimeMillis().toString()),
                role = com.clawgui.ng.data.Role.USER,
                content = text,
            ),
        )
        // 3. Inbox preview for the drawer.
        inbox.appendInbound(
            entry = InboxEntry(
                sessionKey = sessionKey,
                sender = sender,
                title = if (isGroup) "群消息 · 飞书" else "飞书消息",
                preview = text.take(120),
                receivedAt = System.currentTimeMillis(),
                unread = true,
                source = SessionSource.FEISHU,
            ),
            messageId = messageId,
        )
        dlog("Feishu: queued + appended to $sessionKey")

        // 4. Hand off to the ChatViewModel side so it can summarise the title
        //    + (optionally) auto-reply with Brain output via reply().
        runCatching { onInbound?.invoke(sessionKey, text, messageId) }
    }

    /**
     * Pull a plain-text representation from `msg.content` regardless of
     * message type. Feishu encodes everything as JSON; layout differs by
     * type.
     */
    private fun extractText(rawContent: String?, type: String): String {
        if (rawContent.isNullOrBlank()) return ""
        return try {
            val obj = JSONObject(rawContent)
            when (type) {
                "text" -> obj.optString("text").trim()
                    .removePrefix("@_user_1 ").trim()     // strip leading @mention token
                "post" -> {
                    // post = list of paragraphs, each a list of {tag, text}
                    val title = obj.optString("title")
                    val zh = obj.optJSONObject("zh_cn") ?: obj.optJSONObject("en_us")
                    val sb = StringBuilder()
                    if (title.isNotBlank()) sb.append(title).append('\n')
                    val content = zh?.optJSONArray("content")
                    if (content != null) {
                        for (i in 0 until content.length()) {
                            val para = content.optJSONArray(i) ?: continue
                            for (j in 0 until para.length()) {
                                val seg = para.optJSONObject(j) ?: continue
                                val segText = seg.optString("text")
                                if (segText.isNotBlank()) sb.append(segText)
                            }
                            sb.append('\n')
                        }
                    }
                    sb.toString().trim()
                }
                "image" -> "[图片]"
                "file" -> "[文件] ${obj.optString("file_name")}".trim()
                "audio" -> "[语音] ${obj.optInt("duration", 0)}s"
                "media", "video" -> "[视频]"
                "sticker" -> "[表情]"
                "interactive" -> obj.optJSONObject("header")?.optJSONObject("title")?.optString("content").orEmpty().ifBlank { "[卡片]" }
                "share_chat", "share_user" -> "[分享]"
                else -> rawContent.take(120)
            }
        } catch (e: Exception) {
            dlog("Feishu extractText failed for type=$type: ${e.message}")
            rawContent.take(120)
        }
    }

    companion object {
        private val logger = Logger.getLogger("FeishuChannel")
        private const val SEND_TIMEOUT_MS = 30_000L
    }
}
