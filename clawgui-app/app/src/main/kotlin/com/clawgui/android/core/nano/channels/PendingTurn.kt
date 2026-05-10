package com.clawgui.android.core.nano.channels

import com.clawgui.android.core.nano.trace.TraceRecorder

/**
 * In-app turn 上下文,由 App.sendInstruction 登记、InAppChannel.send 消费。
 * 提到顶层类(而非嵌在 App 里)是为了让 channels 包不反向依赖 App。
 */
data class PendingTurn(
    val startMs: Long,
    val trace: TraceRecorder,
    val uiSessionKey: String,
)
