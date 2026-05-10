package com.clawgui.android.core.nano.bus

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

data class InboundMessage(
    val channel: String,
    val senderId: String,
    val chatId: String,
    val content: String,
    val timestamp: Instant = Clock.System.now(),
    val media: List<String> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
    val sessionKeyOverride: String? = null,
) {
    val sessionKey: String get() = sessionKeyOverride ?: "$channel:$chatId"
}

data class OutboundMessage(
    val channel: String,
    val chatId: String,
    val content: String,
    val replyTo: String? = null,
    val media: List<String> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
)
