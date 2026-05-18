package com.clawgui.ng.runtime.trace

import android.content.Context
import kotlinx.serialization.json.Json
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Read-side over the trace directory tree. Lists runs, lazily loads steps,
 * and packages a run into a zip for sharing.
 */
class TraceStore(private val ctx: Context) {

    private val root: File get() = File(ctx.filesDir, "traces")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Newest run first. Malformed dirs are skipped. */
    fun list(): List<TraceMeta> {
        val r = root
        if (!r.exists()) return emptyList()
        return r.listFiles { f -> f.isDirectory }
            ?.mapNotNull { dir -> readMeta(dir) }
            ?.sortedByDescending { it.startedAt }
            ?: emptyList()
    }

    fun runDir(runId: String): File = File(root, runId)

    fun readMeta(dir: File): TraceMeta? = try {
        val metaFile = File(dir, "meta.json")
        if (!metaFile.exists()) null
        else json.decodeFromString(TraceMeta.serializer(), metaFile.readText())
    } catch (_: Throwable) { null }

    fun steps(runId: String): List<TraceStep> {
        val dir = runDir(runId)
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f -> f.isFile && f.name.startsWith("step_") && f.name.endsWith(".json") }
            ?.sortedBy { it.name }
            ?.mapNotNull { f ->
                runCatching { json.decodeFromString(TraceStep.serializer(), f.readText()) }.getOrNull()
            }
            ?: emptyList()
    }

    fun screenshot(runId: String, stepIndex: Int): File? {
        val f = File(runDir(runId), "step_${"%03d".format(stepIndex)}.jpg")
        return if (f.exists()) f else null
    }

    fun delete(runId: String): Boolean = runDir(runId).deleteRecursively()

    fun clearAll(): Boolean = root.deleteRecursively().also { root.mkdirs() }

    /**
     * Package a single run into a zip placed in the app's cache so it can be
     * shared via FileProvider. Returns the zip File, or null on failure.
     */
    fun packageZip(runId: String): File? {
        val src = runDir(runId)
        if (!src.exists()) return null
        val cacheDir = File(ctx.cacheDir, "trace_exports").apply { mkdirs() }
        val out = File(cacheDir, "claw-trace-$runId.zip")
        return try {
            ZipOutputStream(out.outputStream().buffered()).use { zip ->
                src.walkTopDown().filter { it.isFile }.forEach { f ->
                    val entry = ZipEntry("$runId/" + f.relativeTo(src).path.replace(File.separatorChar, '/'))
                    zip.putNextEntry(entry)
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            out
        } catch (_: Throwable) {
            out.delete()
            null
        }
    }
}
