package com.clawgui.android.core.nano.channels

import com.clawgui.android.core.nano.bus.MessageBus
import com.clawgui.android.core.nano.bus.OutboundMessage
import com.clawgui.android.core.nano.trace.TraceRecorder

/**
 * App 侧一组钩子,把 InAppChannel 从 App 的 StateFlow/ChatMessage 字段解耦。
 * App implements 这个接口,InAppChannel.send 通过它更新 UI 状态。
 */
interface InAppBridge {
    /** 取出并移除对应 sessionKey 的 pending turn。返回 null = 没有这笔 turn(e.g. 已被 cancel 清除)。 */
    fun takePendingTurn(sessionKey: String): PendingTurn?

    /** 如果 `activeTurnRecorder` 仍指向 [expected],就把它清零(避免悬挂引用)。 */
    fun clearActiveRecorderIf(expected: TraceRecorder)

    fun isCurrentSession(sessionKey: String): Boolean

    fun appendAssistantMessage(text: String)

    /**
     * 释放指定 sessionKey 的执行 slot(_isExecuting / _activeRunningSessionKey),
     * 并根据 text 决定 _executionStatus 走 Done 还是 Error。
     * 内部实现会用 sessionKey 匹配 `_activeRunningSessionKey` 避免误释放。
     */
    fun releaseExecution(sessionKey: String, text: String)

    fun ensureDisplayName(sessionKey: String)

    fun bumpSessionsVersion()
}

/**
 * In-app chat UI 的 channel 实现,阶段 1 `App.handleInAppOutbound` 的逻辑原样搬到 `send`。
 * start/stop 是空的 —— channel 本身没连任何东西,由 UI 驱动。
 */
class InAppChannel(
    bus: MessageBus,
    private val bridge: InAppBridge,
) : BaseChannel(bus) {
    override val name = "in_app"
    override val displayName = "应用内对话"

    override suspend fun start() {
        running = true
    }

    override suspend fun stop() {
        running = false
    }

    override suspend fun send(msg: OutboundMessage) {
        val sessionKey = msg.metadata["sessionKey"] ?: run {
            com.clawgui.android.core.util.Log.w(
                "InAppChannel", null, "send dropped: missing sessionKey in metadata",
            )
            return
        }
        val t0 = System.currentTimeMillis()
        com.clawgui.android.core.util.Log.i(
            "InAppChannel", sessionKey, "send start (len=${msg.content.length})",
        )
        val turn = bridge.takePendingTurn(sessionKey) ?: run {
            com.clawgui.android.core.util.Log.w(
                "InAppChannel", sessionKey,
                "send dropped: no pending turn (已被 cancel 或 stopSession 清掉?)",
            )
            return
        }
        bridge.clearActiveRecorderIf(turn.trace)
        val text = msg.content.ifBlank { "(无回复)" }
        turn.trace.record(
            eventType = "assistant_output",
            payload = mapOf("content" to text),
        )
        val isCurrent = bridge.isCurrentSession(sessionKey)
        if (isCurrent) {
            bridge.appendAssistantMessage(text)
        }
        bridge.releaseExecution(sessionKey, text)
        bridge.ensureDisplayName(sessionKey)
        bridge.bumpSessionsVersion()
        com.clawgui.android.core.util.Log.i(
            "InAppChannel", sessionKey,
            "send done (${System.currentTimeMillis() - t0}ms, isCurrent=$isCurrent)",
        )
    }
}
