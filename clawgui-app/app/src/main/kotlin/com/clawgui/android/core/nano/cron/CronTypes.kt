package com.clawgui.android.core.nano.cron

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CronSchedule(
    val kind: String,              // "at", "every", "cron"
    @SerialName("at_ms") val atMs: Long? = null,
    @SerialName("every_ms") val everyMs: Long? = null,
    val expr: String? = null,      // cron expression e.g. "0 9 * * *"
    val tz: String? = null,
)

@Serializable
data class CronPayload(
    val kind: String = "agent_turn",  // "system_event" | "agent_turn"
    val message: String = "",
    val deliver: Boolean = false,
    val channel: String? = null,
    val to: String? = null,
)

@Serializable
data class CronRunRecord(
    @SerialName("run_at_ms") val runAtMs: Long,
    val status: String,             // "ok" | "error" | "skipped"
    @SerialName("duration_ms") val durationMs: Long = 0,
    val error: String? = null,
)

@Serializable
data class CronJobState(
    @SerialName("next_run_at_ms") val nextRunAtMs: Long? = null,
    @SerialName("last_run_at_ms") val lastRunAtMs: Long? = null,
    @SerialName("last_status") val lastStatus: String? = null,
    @SerialName("last_error") val lastError: String? = null,
    @SerialName("run_history") val runHistory: List<CronRunRecord> = emptyList(),
)

@Serializable
data class CronJob(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val schedule: CronSchedule = CronSchedule(kind = "every"),
    val payload: CronPayload = CronPayload(),
    val state: CronJobState = CronJobState(),
    @SerialName("created_at_ms") val createdAtMs: Long = 0,
    @SerialName("updated_at_ms") val updatedAtMs: Long = 0,
    @SerialName("delete_after_run") val deleteAfterRun: Boolean = false,
)

@Serializable
data class CronStore(
    val version: Int = 1,
    val jobs: List<CronJob> = emptyList(),
)
