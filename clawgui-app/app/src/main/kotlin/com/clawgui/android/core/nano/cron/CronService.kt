package com.clawgui.android.core.nano.cron

import com.cronutils.model.CronType
import com.cronutils.model.definition.CronDefinitionBuilder
import com.cronutils.model.time.ExecutionTime
import com.cronutils.parser.CronParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger

private val cronJson = Json { ignoreUnknownKeys = true; prettyPrint = true }
private val logger = Logger.getLogger("CronService")

private fun nowMs(): Long = System.currentTimeMillis()

private fun computeNextRun(schedule: CronSchedule, fromMs: Long): Long? = when (schedule.kind) {
    "at" -> schedule.atMs?.takeIf { it > fromMs }
    "every" -> schedule.everyMs?.takeIf { it > 0 }?.let { fromMs + it }
    "cron" -> schedule.expr?.let { expr ->
        try {
            val def = CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX)
            val cron = CronParser(def).parse(expr)
            val tz = if (schedule.tz != null) ZoneId.of(schedule.tz) else ZoneId.systemDefault()
            val now = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(fromMs), tz)
            ExecutionTime.forCron(cron).nextExecution(now).map { it.toInstant().toEpochMilli() }.orElse(null)
        } catch (_: Exception) { null }
    }
    else -> null
}

class CronService(
    private val storeFile: File,
    private val onJob: (suspend (CronJob) -> Unit)? = null,
) {
    private var store = CronStore()
    private var lastMtime = 0L
    private var timerJob: Job? = null
    private var running = false

    private fun loadStore(): CronStore {
        if (storeFile.exists()) {
            val mtime = storeFile.lastModified()
            if (mtime != lastMtime) {
                store = try {
                    cronJson.decodeFromString(CronStore.serializer(), storeFile.readText(Charsets.UTF_8))
                } catch (e: Exception) {
                    logger.warning("Failed to load cron store: $e")
                    CronStore()
                }
                lastMtime = mtime
            }
        }
        return store
    }

    private fun saveStore() {
        storeFile.parentFile?.mkdirs()
        storeFile.writeText(cronJson.encodeToString(store), Charsets.UTF_8)
        lastMtime = storeFile.lastModified()
    }

    fun start(scope: CoroutineScope) {
        running = true
        loadStore()
        recomputeNextRuns()
        saveStore()
        armTimer(scope)
        logger.info("Cron service started with ${store.jobs.size} jobs")
    }

    fun stop() {
        running = false
        timerJob?.cancel()
        timerJob = null
    }

    private fun recomputeNextRuns() {
        val now = nowMs()
        store = store.copy(jobs = store.jobs.map { job ->
            if (job.enabled) job.copy(state = job.state.copy(nextRunAtMs = computeNextRun(job.schedule, now)))
            else job
        })
    }

    private fun nextWakeMs(): Long? =
        store.jobs.filter { it.enabled }.mapNotNull { it.state.nextRunAtMs }.minOrNull()

    private fun armTimer(scope: CoroutineScope) {
        timerJob?.cancel()
        val nextWake = nextWakeMs() ?: return
        if (!running) return
        val delayMs = maxOf(0L, nextWake - nowMs())
        timerJob = scope.launch {
            try {
                delay(delayMs)
                if (running) onTimer(scope)
            } catch (_: CancellationException) { /* stop requested */ }
        }
    }

    private suspend fun onTimer(scope: CoroutineScope) {
        loadStore()
        val now = nowMs()
        val dueJobs = store.jobs.filter { it.enabled && it.state.nextRunAtMs != null && now >= it.state.nextRunAtMs!! }
        dueJobs.forEach { executeJob(it) }
        saveStore()
        armTimer(scope)
    }

    private suspend fun executeJob(job: CronJob) {
        val startMs = nowMs()
        logger.info("Cron: executing job '${job.name}' (${job.id})")
        var status = "ok"
        var errorMsg: String? = null
        try {
            onJob?.invoke(job)
        } catch (e: Exception) {
            status = "error"
            errorMsg = e.toString()
            logger.log(Level.WARNING, "Cron: job '${job.name}' failed", e)
        }
        val endMs = nowMs()
        val record = CronRunRecord(runAtMs = startMs, status = status, durationMs = endMs - startMs, error = errorMsg)
        val newHistory = (job.state.runHistory + record).takeLast(MAX_RUN_HISTORY)
        val nextRun = if (job.schedule.kind == "at") null
                      else computeNextRun(job.schedule, nowMs())
        val newState = job.state.copy(
            lastRunAtMs = startMs,
            lastStatus = status,
            lastError = errorMsg,
            nextRunAtMs = nextRun,
            runHistory = newHistory,
        )
        val newEnabled = if (job.schedule.kind == "at") false else job.enabled
        store = store.copy(jobs = store.jobs.map { j ->
            if (j.id == job.id) {
                val updated = j.copy(state = newState, enabled = newEnabled, updatedAtMs = endMs)
                if (job.schedule.kind == "at" && job.deleteAfterRun) null else updated
            } else j
        }.filterNotNull())
    }

    // ── Public API ─────────────────────────────────────────────────────────

    fun listJobs(includeDisabled: Boolean = false): List<CronJob> {
        loadStore()
        return store.jobs
            .let { if (includeDisabled) it else it.filter { j -> j.enabled } }
            .sortedBy { it.state.nextRunAtMs ?: Long.MAX_VALUE }
    }

    fun addJob(
        name: String,
        schedule: CronSchedule,
        message: String,
        deliver: Boolean = false,
        channel: String? = null,
        to: String? = null,
        deleteAfterRun: Boolean = false,
        scope: CoroutineScope? = null,
    ): CronJob {
        loadStore()
        val now = nowMs()
        val job = CronJob(
            id = UUID.randomUUID().toString().take(8),
            name = name,
            enabled = true,
            schedule = schedule,
            payload = CronPayload(kind = "agent_turn", message = message, deliver = deliver, channel = channel, to = to),
            state = CronJobState(nextRunAtMs = computeNextRun(schedule, now)),
            createdAtMs = now,
            updatedAtMs = now,
            deleteAfterRun = deleteAfterRun,
        )
        store = store.copy(jobs = store.jobs + job)
        saveStore()
        if (scope != null) armTimer(scope)
        logger.info("Cron: added job '$name' (${job.id})")
        return job
    }

    fun removeJob(jobId: String, scope: CoroutineScope? = null): Boolean {
        loadStore()
        val before = store.jobs.size
        store = store.copy(jobs = store.jobs.filter { it.id != jobId })
        if (store.jobs.size < before) {
            saveStore()
            if (scope != null) armTimer(scope)
            logger.info("Cron: removed job $jobId")
            return true
        }
        return false
    }

    fun enableJob(jobId: String, enabled: Boolean = true, scope: CoroutineScope? = null): CronJob? {
        loadStore()
        var found: CronJob? = null
        store = store.copy(jobs = store.jobs.map { j ->
            if (j.id == jobId) {
                val next = if (enabled) computeNextRun(j.schedule, nowMs()) else null
                j.copy(enabled = enabled, updatedAtMs = nowMs(), state = j.state.copy(nextRunAtMs = next))
                    .also { found = it }
            } else j
        })
        if (found != null) {
            saveStore()
            if (scope != null) armTimer(scope)
        }
        return found
    }

    suspend fun runJob(jobId: String, force: Boolean = false, scope: CoroutineScope? = null): Boolean {
        loadStore()
        val job = store.jobs.find { it.id == jobId } ?: return false
        if (!force && !job.enabled) return false
        executeJob(job)
        saveStore()
        if (scope != null) armTimer(scope)
        return true
    }

    fun getJob(jobId: String): CronJob? {
        loadStore()
        return store.jobs.find { it.id == jobId }
    }

    fun status(): Map<String, Any?> {
        loadStore()
        return mapOf(
            "enabled" to running,
            "jobs" to store.jobs.size,
            "next_wake_at_ms" to nextWakeMs(),
        )
    }

    companion object {
        private const val MAX_RUN_HISTORY = 20
    }
}
