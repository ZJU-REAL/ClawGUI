package com.clawgui.android.core.nano.channels

import com.clawgui.android.core.nano.bus.InboundMessage
import com.clawgui.android.core.nano.bus.MessageBus
import com.clawgui.android.core.nano.bus.OutboundMessage

/**
 * 所有 chat channel 的基类,对齐 ClawGUI Python `nanobot/channels/base.py`。
 *
 * 子类负责:
 *  1. start() 启动(连 WS / 打开 poll / 自行持有背景任务)
 *  2. stop() 干净关闭
 *  3. send(msg) 把 agent 的回复发出去
 *  4. 外部消息进来时调 [handleInbound] 走白名单 + publishInbound 到 bus
 */
abstract class BaseChannel(protected val bus: MessageBus) {
    abstract val name: String
    open val displayName: String get() = name

    @Volatile
    protected var running: Boolean = false
    val isRunning: Boolean get() = running

    abstract suspend fun start()
    abstract suspend fun stop()
    abstract suspend fun send(msg: OutboundMessage)

    protected open fun isAllowed(senderId: String): Boolean = true

    protected suspend fun handleInbound(
        senderId: String,
        chatId: String,
        content: String,
        media: List<String> = emptyList(),
        metadata: Map<String, String> = emptyMap(),
        sessionKeyOverride: String? = null,
    ) {
        if (!isAllowed(senderId)) return
        bus.publishInbound(
            InboundMessage(
                channel = name,
                senderId = senderId,
                chatId = chatId,
                content = content,
                media = media,
                metadata = metadata,
                sessionKeyOverride = sessionKeyOverride,
            )
        )
    }
}
