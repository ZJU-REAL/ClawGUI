package com.clawgui.ng.runtime.trace

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Per-run trace store.
 *
 * Layout under `<filesDir>/traces/<runId>/`:
 *   meta.json                          one-line metadata
 *   step_001.json | step_001.jpg       per-step thinking/action + screenshot
 *   step_002.json | step_002.jpg
 *   ...
 *
 * The recorder is intentionally fire-and-forget on the IO dispatcher. Disk
 * write failures are logged and swallowed — never fail a step because we
 * couldn't record it.
 */
class TraceRecorder private constructor(
    val runDir: File,
    val meta: TraceMeta,
) {

    private val mutex = Any()
    private var stepCount = 0

    fun appendStep(step: TraceStep, screenshotBytes: ByteArray?) {
        synchronized(mutex) {
            stepCount++
            val idx = "%03d".format(stepCount)
            runCatching {
                File(runDir, "step_$idx.json").writeText(json.encodeToString(TraceStep.serializer(), step))
                if (screenshotBytes != null && screenshotBytes.isNotEmpty()) {
                    File(runDir, "step_$idx.jpg").writeBytes(screenshotBytes)
                }
            }
        }
    }

    fun finalize(finalMessage: String, success: Boolean) {
        synchronized(mutex) {
            val updated = meta.copy(
                endedAt = System.currentTimeMillis(),
                steps = stepCount,
                success = success,
                finalMessage = finalMessage,
            )
            runCatching {
                File(runDir, "meta.json").writeText(json.encodeToString(TraceMeta.serializer(), updated))
            }
        }
    }

    companion object {
        private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
        private val dirNameFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

        fun start(ctx: Context, task: String, model: String, sessionKey: String): TraceRecorder? = try {
            val root = File(ctx.filesDir, "traces").apply { mkdirs() }
            val ts = System.currentTimeMillis()
            val runId = dirNameFormat.format(Date(ts)) + "_" + (ts % 1000).toString().padStart(3, '0')
            val runDir = File(root, runId).apply { mkdirs() }
            val meta = TraceMeta(
                runId = runId,
                task = task,
                model = model,
                sessionKey = sessionKey,
                startedAt = ts,
                endedAt = 0L,
                steps = 0,
                success = false,
                finalMessage = "",
            )
            File(runDir, "meta.json").writeText(json.encodeToString(TraceMeta.serializer(), meta))
            TraceRecorder(runDir, meta)
        } catch (_: Throwable) { null }
    }
}

@Serializable
data class TraceMeta(
    val runId: String,
    val task: String,
    val model: String,
    val sessionKey: String,
    val startedAt: Long,
    val endedAt: Long,
    val steps: Int,
    val success: Boolean,
    val finalMessage: String,
)

@Serializable
data class TraceStep(
    val index: Int,
    val timestamp: Long,
    val thinking: String,
    val actionName: String,
    val actionJson: String,
    val resultOk: Boolean,
    val resultMessage: String? = null,
)
