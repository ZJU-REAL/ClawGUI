package com.clawgui.ng.runtime.llm

import com.clawgui.ng.data.repo.ProviderCredentials

/**
 * One-shot "look at the user's images, summarise + extract" call. Used by
 * the GUI-task path so the on-device VLM never receives the user's reference
 * images directly (some VLM endpoints — notably AutoGLM-Phone — appear to
 * stall when handed more than the single screen frame they were trained on).
 *
 * The output is plain text the caller can paste into the next agent prompt:
 *
 *   # 图片上下文(由 Brain 预先分析)
 *   - 图 1 内容: ...
 *   - 可直接使用的文字: ...
 *
 * The prompt is intentionally generic — we don't ask Brain "is this a
 * Moments post" or any other scenario-specific framing. Brain just describes
 * the image and, given the user's task, decides whether there's text it can
 * confidently extract or compose. Task-specific behaviour emerges naturally
 * (a "post these photos to Moments" task gets a caption; a "scan this QR"
 * task gets the URL; a "transcribe the menu" task gets the menu items).
 */
object ImageInsight {

    data class Result(
        val perImageSummary: List<String>,
        /** Optional ready-to-use text Brain composed (caption / OCR / etc.). Blank if not applicable. */
        val extractable: String,
    ) {
        fun isEmpty(): Boolean = perImageSummary.isEmpty() && extractable.isBlank()
    }

    /**
     * Pick the credentials that can see images: Brain first if it supports
     * vision, otherwise the active Vision provider (already used as a Brain
     * fallback in chat). Returns null when nothing on-device can do it.
     */
    fun pickVisionCreds(brain: ProviderCredentials?, vision: ProviderCredentials?): ProviderCredentials? {
        if (brain != null && brain.apiKey.isNotBlank() && brain.supportsVision) return brain
        if (vision != null && vision.apiKey.isNotBlank() && vision.supportsVision) return vision
        return null
    }

    /**
     * Analyse [imagesBase64] in the context of [userTask] and return what
     * the agent should be told. Returns an empty [Result] (so the caller
     * keeps moving) on any failure — the agent still works without insights.
     */
    suspend fun analyse(
        creds: ProviderCredentials,
        userTask: String,
        imagesBase64: List<String>,
    ): Result {
        if (imagesBase64.isEmpty()) return Result(emptyList(), "")
        val client = OpenAICompatClient(
            baseUrl = creds.baseUrl,
            apiKey = creds.apiKey,
            model = creds.model,
        )
        val sys = """
            你是一个图像理解助手,与一个手机操作 Agent 配合工作。Agent 不会看到这些图片,
            但会按用户的任务在手机上执行操作。请基于用户给出的图片输出 Agent 完成任务所需要的信息。

            严格按下面格式输出,每张图各占一行:
            图1: <≤40 字的内容摘要>
            图2: <≤40 字的内容摘要>
            ...

            最后再追加一行(始终保留 "可用文本:" 前缀,可以留空):
            可用文本: <若任务隐含需要一段文字(配文、文案、识别出的码、菜单、URL、人名等),把它直接写在这里。否则留空>

            禁止输出多余的客套、解释、Markdown。
        """.trimIndent()

        val userParts = buildList {
            add(ContentPart.Text("用户任务:$userTask"))
            imagesBase64.forEach { add(ContentPart.ImageBase64(it)) }
        }

        val raw = runCatching {
            client.complete(listOf(
                Message("system", sys),
                Message("user", userParts),
            ))
        }.getOrNull() ?: return Result(emptyList(), "")

        return parse(raw)
    }

    internal fun parse(raw: String): Result {
        val summaries = mutableListOf<String>()
        var extract = ""
        raw.lineSequence().forEach { line ->
            val trimmed = line.trim()
            // 图N: ... — accept full-width or ASCII colon
            val sumMatch = Regex("""^图\s*\d+\s*[::]\s*(.*)""").find(trimmed)
            if (sumMatch != null) {
                summaries += sumMatch.groupValues[1].trim()
                return@forEach
            }
            val extractMatch = Regex("""^可用文本\s*[::]\s*(.*)""").find(trimmed)
            if (extractMatch != null) {
                extract = extractMatch.groupValues[1].trim()
            }
        }
        return Result(summaries, extract)
    }
}
