package com.clawgui.ng.runtime.phone.model.adapters

/**
 * 所有 VLM 适配器的共同契约。
 *
 * 各家 VLM(AutoGLM / UI-TARS / Qwen-VL / MAI-UI / GUI-Owl)都在
 * prompt 风格、消息累积、响应解析上有别,但对调用方 PhoneAgent 来说需要的
 * 只有两件事:给模型组 messages、从原始响应里拎出 thinking + action 字符串。
 *
 * parseResponse 返回的 action_str 统一"归一化"成 AutoGLM 格式
 *   do(action="Tap", element=[x,y]) / finish(message="...")
 * 这样 ActionParser 一套老逻辑就能继续喂给 ActionHandler,不必每家单挪。
 */
interface ModelAdapter {
    val name: String

    /**
     * Build the messages payload for the VLM call.
     *
     * [extraUserImages] is a list of base64-encoded JPEGs the user attached
     * to the chat turn (not the device screenshot). Adapters that support
     * vision-array content blocks (AutoGLM, GLM-4.5V, Qwen-VL, ...) should
     * interleave them alongside the screenshot on the first turn so the
     * model can ground its plan on both the user's reference image *and*
     * the current screen state. Defaults to empty for adapters that ignore.
     */
    fun buildMessages(
        task: String,
        imageBase64: String,
        currentApp: String,
        context: List<Map<String, Any?>>,
        lang: String = "cn",
        extraUserImages: List<String> = emptyList(),
        /** True for step 1 of every task. The adapter uses this to emit the
         *  plan-init framing even when context isn't empty (e.g. continueTask
         *  reuses the cached PhoneAgent, so context is non-empty but the
         *  model still needs to think of this as "step 1, plan from scratch"). */
        isFirst: Boolean = false,
    ): List<Map<String, Any?>>

    fun parseResponse(response: String): Pair<String, String>

    /** Qwen-VL / GUI-Owl 会累积 "Previous actions" 文本历史,
     *  PhoneAgent 每步完成后调一次把本步 action 描述塞进去;其他适配器忽略。 */
    fun addHistory(description: String) {}

    /** 开新任务时 PhoneAgent 调用,用于清空累积历史。 */
    fun clearHistory() {}
}

/**
 * 把 VLM 模型名扔进来得到对应 adapter。沿用 AutoGLMAdapter.kt 的
 * detectModelType 关键字规则,未识别时落回 AutoGLM。
 */
fun adapterForModel(modelName: String): ModelAdapter = when (detectModelType(modelName)) {
    "uitars" -> UITarsAdapter()
    "qwenvl" -> QwenVLAdapter()
    "maiui" -> MaiUIAdapter()
    "guiowl" -> GuiOwlAdapter()
    else -> AutoGLMAdapter
}
