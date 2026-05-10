package com.clawgui.android.ui

data class ChatMessage(
    val role: String,   // "user" | "assistant" | "step"
    val content: String,
    val id: Long = System.nanoTime(),
)
