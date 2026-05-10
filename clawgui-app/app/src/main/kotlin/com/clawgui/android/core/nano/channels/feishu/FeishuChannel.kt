package com.clawgui.android.core.nano.channels.feishu

import com.clawgui.android.core.nano.bus.MessageBus
import com.clawgui.android.core.nano.bus.OutboundMessage
import com.clawgui.android.core.nano.channels.BaseChannel
import com.lark.oapi.Client
import com.lark.oapi.event.EventDispatcher
import com.lark.oapi.service.im.ImService
import com.lark.oapi.service.im.v1.model.CreateMessageReq
import com.lark.oapi.service.im.v1.model.CreateMessageReqBody
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1
import com.lark.oapi.service.im.v1.model.ReplyMessageReq
import com.lark.oapi.service.im.v1.model.ReplyMessageReqBody
import com.lark.oapi.ws.Client as WsClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.json.JSONObject
import java.util.logging.Logger

/**
 * 飞书 channel —— oapi-sdk 2.4.16 WS 长连 + REST 回复。
 * 收到用户消息后 publishInbound 进 bus;收到 outbound 时优先 reply 回原 thread
 * (metadata 带 feishu_message_id),拿不到 messageId 时 fallback 到 create。
 */
class FeishuChannel(
    bus: MessageBus,
    private val cfg: FeishuConfig,
    private val scope: CoroutineScope,
    private val inbox: ExternalInboxStore,
    // 白名单通过、进入 AgentLoop 之前回调 —— App 借此占用执行 slot 驱动悬浮球,并开 trace。
    private val onInboundAccepted: ((sessionKey: String, content: String) -> Unit)? = null,
    // send() 完成或异常后回调 —— App 借此释放执行 slot。error 非空表示发送失败,text 保持原 content。
    private val onOutboundSent: ((sessionKey: String, text: String, error: String?) -> Unit)? = null,
) : BaseChannel(bus) {

    override val name: String = "feishu"
    override val displayName: String = "飞书"

    private val logger = Logger.getLogger("FeishuChannel")

    @Volatile private var restClient: Client? = null
    @Volatile private var wsClient: WsClient? = null
    private var wsJob: Job? = null

    override suspend fun start() = withContext(Dispatchers.IO) {
        if (running) return@withContext
        if (!cfg.isUsable) {
            logger.warning("FeishuChannel start skipped: missing appId/appSecret")
            return@withContext
        }
        try {
            restClient = Client.newBuilder(cfg.appId, cfg.appSecret).build()
            val dispatcher = EventDispatcher.Builder("", "")
                .onP2MessageReceiveV1(object : ImService.P2MessageReceiveV1Handler() {
                    override fun handle(data: P2MessageReceiveV1) {
                        onIncomingEvent(data)
                    }
                })
                .build()
            val client = WsClient.Builder(cfg.appId, cfg.appSecret)
                .eventHandler(dispatcher)
                .build()
            wsClient = client
            running = true
            // wsClient.start() 会阻塞,放到单独协程跑,让 channel.start() 及时返回
            wsJob = scope.launch(Dispatchers.IO) {
                try {
                    client.start()
                } catch (e: Throwable) {
                    logger.warning("Feishu WS loop ended: ${e.message}")
                }
            }
            logger.info("FeishuChannel started")
        } catch (e: Throwable) {
            logger.warning("FeishuChannel start failed: ${e.message}")
            running = false
            restClient = null
            wsClient = null
        }
    }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        running = false
        // oapi-sdk WsClient 没公开 stop 方法,靠 cancel WS 协程 + 丢引用让 SDK 内部
        // 线程池随进程/下次 start 自然回收。前后台切换场景下 start 会 new 一个新的 WsClient。
        wsJob?.cancel()
        wsJob = null
        wsClient = null
        restClient = null
        logger.info("FeishuChannel stopped")
    }

    override suspend fun send(msg: OutboundMessage) = withContext(Dispatchers.IO) {
        val sessionKey = "$name:${msg.chatId}"
        val client = restClient ?: run {
            logger.warning("send skipped: rest client not ready")
            onOutboundSent?.invoke(sessionKey, msg.content, "rest client not ready")
            return@withContext
        }
        val content = buildTextJson(msg.content.ifBlank { "(无回复)" })
        val messageId = msg.metadata["feishu_message_id"]

        // reply/create 是 oapi-sdk 的同步 OkHttp 调用,没有内置超时;网络卡住整个协程会无限阻塞,
        // 过去这会让 onOutboundSent 永不触发、overlay 一直转。withTimeoutOrNull 兜底 30 秒。
        val (ok, errDetail) = try {
            val resp = withTimeoutOrNull(SEND_TIMEOUT_MS) {
                if (!messageId.isNullOrBlank()) {
                    client.im().message().reply(
                        ReplyMessageReq.newBuilder()
                            .messageId(messageId)
                            .replyMessageReqBody(
                                ReplyMessageReqBody.newBuilder()
                                    .content(content)
                                    .msgType("text")
                                    .build()
                            )
                            .build()
                    )
                } else {
                    client.im().message().create(
                        CreateMessageReq.newBuilder()
                            .receiveIdType("chat_id")
                            .createMessageReqBody(
                                CreateMessageReqBody.newBuilder()
                                    .receiveId(msg.chatId)
                                    .content(content)
                                    .msgType("text")
                                    .build()
                            )
                            .build()
                    )
                }
            }
            when {
                resp == null -> false to "timeout after ${SEND_TIMEOUT_MS}ms"
                !resp.success() -> false to "feishu api error: ${resp.error}"
                else -> true to null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warning("feishu send exception: ${e.message}")
            false to (e.message ?: e::class.simpleName ?: "unknown")
        }

        if (ok) {
            inbox.appendOutbound(name, msg.chatId, msg.content, msg.metadata)
            onOutboundSent?.invoke(sessionKey, msg.content, null)
        } else {
            // 失败时不写 inbox(别假装发出去了);text 仍传原 content 方便 trace 留痕
            logger.warning("feishu send failed: $errDetail")
            onOutboundSent?.invoke(sessionKey, msg.content, errDetail)
        }
    }

    override fun isAllowed(senderId: String): Boolean =
        if (cfg.allowAll) true else senderId in cfg.allowedOpenIds

    private fun onIncomingEvent(data: P2MessageReceiveV1) {
        val msg = data.event?.message ?: return
        val senderOpenId = data.event?.sender?.senderId?.openId ?: return
        val chatId = msg.chatId ?: return
        val messageId = msg.messageId
        val msgType = msg.messageType ?: ""
        if (msgType != "text") return
        val text = try {
            JSONObject(msg.content ?: "{}").optString("text", "")
        } catch (_: Exception) { "" }
        if (text.isBlank()) return
        // 白名单提前到这里,放行后才占 slot;handleInbound 内部仍会再校验一次做 defense-in-depth。
        if (!isAllowed(senderOpenId)) return

        scope.launch {
            try { inbox.appendInbound(name, chatId, senderOpenId, text, messageId) } catch (_: Exception) {}
            onInboundAccepted?.invoke("$name:$chatId", text)
            handleInbound(
                senderId = senderOpenId,
                chatId = chatId,
                content = text,
                metadata = if (messageId != null) mapOf("feishu_message_id" to messageId) else emptyMap(),
            )
        }
    }

    private fun buildTextJson(text: String): String =
        buildJsonObject { put("text", text) }.toString()

    companion object {
        private const val SEND_TIMEOUT_MS = 30_000L
    }
}
