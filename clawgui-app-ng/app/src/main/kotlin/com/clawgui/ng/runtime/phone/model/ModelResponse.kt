package com.clawgui.ng.runtime.phone.model

data class ModelResponse(
    val thinking: String,
    val action: String,
    val rawContent: String,
    val timeToFirstToken: Float? = null,
    val timeToThinkingEnd: Float? = null,
    val totalTime: Float? = null,
)
