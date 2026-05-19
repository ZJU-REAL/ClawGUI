package com.clawgui.ng.runtime.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.clawgui.ng.data.ExecutionState
import com.clawgui.ng.runtime.RuntimeContainer
import com.clawgui.ng.runtime.overlay.DynamicIslandOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the process alive while the agent runs.
 *
 * Android 14+ requires:
 *  1. `startForeground` MUST pass a `FOREGROUND_SERVICE_TYPE_*` matching one
 *     declared in the manifest, or it throws
 *     `MissingForegroundServiceTypeException`. We use SPECIAL_USE because the
 *     agent doesn't fit `mediaPlayback` / `dataSync` / etc.
 *  2. The user must have approved POST_NOTIFICATIONS (we degrade gracefully
 *     — the service still starts; the notification just isn't shown).
 *
 * The overlay window is only requested when `SYSTEM_ALERT_WINDOW` has been
 * granted; otherwise we run without it instead of crashing.
 */
class AgentService : Service() {

    private var overlay: DynamicIslandOverlay? = null
    private var livePanel: com.clawgui.ng.runtime.overlay.AgentLiveOverlay? = null
    private var scope: CoroutineScope? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        try {
            startForegroundCompat()
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground failed", t)
            stopSelf()
            return
        }

        // Overlay needs SYSTEM_ALERT_WINDOW; if it's not granted we silently
        // skip the floating island rather than crash.
        if (canDrawOverlays(this)) {
            try {
                overlay = DynamicIslandOverlay(this).also { it.show() }
            } catch (t: Throwable) {
                Log.w(TAG, "overlay show failed", t)
                overlay = null
            }
            try {
                livePanel = com.clawgui.ng.runtime.overlay.AgentLiveOverlay(this).also { it.show() }
            } catch (t: Throwable) {
                Log.w(TAG, "live-panel overlay show failed", t)
                livePanel = null
            }
        }

        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope!!.launch {
            var lastNotifiedStep = -1
            RuntimeContainer.executionStatus.collect { status ->
                val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                val enabled = runCatching { RuntimeContainer.settings.notifyEnabled.value }
                    .getOrDefault(true)

                if (enabled) {
                    runCatching { nm.notify(NOTIFICATION_ID, buildStatusNotification(status)) }
                } else {
                    // Master switch off — keep only the ongoing notif required
                    // by Android for the FGS; remove the rich variant.
                    runCatching { nm.cancel(NOTIFICATION_ID) }
                }

                val notifyEach = runCatching { RuntimeContainer.settings.notifyEachStep.value }
                    .getOrDefault(false)
                val isActive = status.state == ExecutionState.THINKING ||
                    status.state == ExecutionState.ACTING
                if (enabled && notifyEach && isActive && status.stepIndex != lastNotifiedStep) {
                    lastNotifiedStep = status.stepIndex
                    runCatching {
                        nm.notify(
                            STEP_NOTIFICATION_BASE_ID + status.stepIndex,
                            buildStepNotification(status),
                        )
                    }
                }
                if (status.state == ExecutionState.IDLE) {
                    if (lastNotifiedStep >= 0) {
                        runCatching {
                            for (i in 0..lastNotifiedStep + 1) nm.cancel(STEP_NOTIFICATION_BASE_ID + i)
                        }
                    }
                    stopSelf()
                }
            }
        }
    }

    /**
     * Per-step heads-up. Goes through a dedicated HIGH channel so every step
     * pops a banner like a new IM, but with sound/vibration suppressed at the
     * channel level so it never feels noisy when the agent is in a long
     * Swipe loop ("刷 5 分钟抖音").
     */
    private fun buildStepNotification(status: com.clawgui.ng.data.ExecutionStatus): Notification {
        val stepNo = status.stepIndex + 1
        val action = status.actionJson?.takeIf { it.isNotBlank() } ?: status.subtitle.ifBlank { "执行中" }
        val headsUp = runCatching { RuntimeContainer.settings.notifyHeadsUp.value }
            .getOrDefault(true)
        val verbose = runCatching { RuntimeContainer.settings.notifyVerbose.value }
            .getOrDefault(true)

        val bigBody = if (verbose) {
            buildString {
                if (!status.thinking.isNullOrBlank()) append("💭 ").append(status.thinking).append("\n\n")
                append("▶ ").append(action)
            }
        } else {
            action
        }

        val openIntent = android.app.PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            } ?: Intent(),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, STEP_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setColor(0xFF003F88.toInt())
            .setColorized(true)
            .setContentTitle("第 $stepNo 步")
            .setContentText(action.take(80))
            .setContentIntent(openIntent)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigBody))
            .setGroup(STEP_GROUP)
            // headsUp off → setOnlyAlertOnce(true) suppresses re-pop banners.
            .setOnlyAlertOnce(!headsUp)
            .setPriority(if (headsUp) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setDefaults(0)
            .setAutoCancel(true)
            .setTimeoutAfter(15_000)
            .build()
    }

    private fun startForegroundCompat() {
        val notif = buildStatusNotification(RuntimeContainer.executionStatus.value)
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                // Android 14+: type required; manifest declares specialUse.
                startForeground(
                    NOTIFICATION_ID, notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                // Older platforms accept the same constant (value 0x40000000)
                // but if the device doesn't recognise it, fall back to plain.
                try {
                    startForeground(
                        NOTIFICATION_ID, notif,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                    )
                } catch (_: Throwable) {
                    startForeground(NOTIFICATION_ID, notif)
                }
            }
            else -> startForeground(NOTIFICATION_ID, notif)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // User tapped "停止" on the notification. Cancel any running agent
            // by flipping the execution state — ChatViewModel.stop() observes
            // this and tears the rest down.
            RuntimeContainer.publishExecution(
                com.clawgui.ng.data.ExecutionStatus(
                    state = ExecutionState.STOPPED,
                    title = "已停止",
                )
            )
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { overlay?.hide() }
        overlay = null
        runCatching { livePanel?.hide() }
        livePanel = null
        scope?.cancel(); scope = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildStatusNotification(status: com.clawgui.ng.data.ExecutionStatus): Notification {
        val title = when (status.state) {
            ExecutionState.THINKING -> "🧠 ClawGUI 正在思考"
            ExecutionState.ACTING -> "✋ ClawGUI 正在操作手机"
            ExecutionState.DONE -> "✅ ClawGUI 已完成"
            ExecutionState.ERROR -> "⚠️ ClawGUI 遇到问题"
            ExecutionState.STOPPED -> "■ ClawGUI 已停止"
            ExecutionState.IDLE -> "ClawGUI"
        }

        val stepInfo = if (status.totalSteps > 0)
            "第 ${status.stepIndex + 1}/${status.totalSteps} 步"
        else ""

        val shortBody = listOf(stepInfo, status.subtitle.take(60))
            .filter { it.isNotBlank() }
            .joinToString(" · ")
            .ifBlank {
                when (status.state) {
                    ExecutionState.THINKING -> "正在分析屏幕…"
                    ExecutionState.ACTING -> "正在执行下一步动作"
                    ExecutionState.DONE -> "任务结束"
                    ExecutionState.ERROR -> "遇到问题,点击查看"
                    ExecutionState.STOPPED -> "你停止了任务"
                    ExecutionState.IDLE -> "待命中"
                }
            }

        val verbose = runCatching { RuntimeContainer.settings.notifyVerbose.value }
            .getOrDefault(true)
        val bigText = buildString {
            if (stepInfo.isNotBlank()) append(stepInfo).append("\n")
            if (status.subtitle.isNotBlank()) append(status.subtitle).append("\n\n")
            if (verbose && !status.thinking.isNullOrBlank()) {
                append("💭 ").append(status.thinking!!.take(300)).append("\n")
            }
            if (!status.actionJson.isNullOrBlank()) {
                append("▶ ").append(status.actionJson!!.take(120))
            }
        }.trim()

        val openIntent = android.app.PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            } ?: Intent(),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = android.app.PendingIntent.getService(
            this, 1,
            Intent(this, AgentService::class.java).setAction(ACTION_STOP),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )

        val terminal = status.state == ExecutionState.DONE ||
                status.state == ExecutionState.ERROR ||
                status.state == ExecutionState.STOPPED
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(
                when (status.state) {
                    ExecutionState.DONE -> android.R.drawable.stat_sys_download_done
                    ExecutionState.ERROR -> android.R.drawable.stat_notify_error
                    ExecutionState.STOPPED -> android.R.drawable.ic_media_pause
                    else -> android.R.drawable.stat_notify_sync_noanim
                }
            )
            .setColor(0xFF003F88.toInt())
            .setColorized(true)
            .setContentTitle(title)
            .setContentText(shortBody)
            .setContentIntent(openIntent)
            // Auto-cancel only after the task is over; running notifications
            // should stay until we tear them down ourselves.
            .setAutoCancel(terminal)
            // Terminal states should heads-up so the user notices "done";
            // running states stay quiet (we already pinged on start).
            .setOnlyAlertOnce(!terminal)
            .setOngoing(status.state == ExecutionState.THINKING || status.state == ExecutionState.ACTING)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())
            .setCategory(
                if (terminal) NotificationCompat.CATEGORY_STATUS
                else NotificationCompat.CATEGORY_PROGRESS
            )
            .setPriority(
                if (terminal) NotificationCompat.PRIORITY_HIGH
                else NotificationCompat.PRIORITY_DEFAULT
            )
            .setDefaults(if (terminal) NotificationCompat.DEFAULT_ALL else 0)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText.ifBlank { shortBody }))

        // Progress bar for active states
        if (status.state == ExecutionState.THINKING || status.state == ExecutionState.ACTING) {
            if (status.totalSteps > 0) {
                builder.setProgress(status.totalSteps, status.stepIndex + 1, false)
            } else {
                builder.setProgress(0, 0, true)
            }
            builder.addAction(
                android.R.drawable.ic_media_pause, "停止", stopIntent,
            )
        }
        builder.addAction(android.R.drawable.ic_menu_view, "打开 ClawGUI", openIntent)

        return builder.build()
    }

    companion object {
        private const val TAG = "AgentService"
        const val CHANNEL_ID = "clawgui_agent_v3"           // running summary
        const val STEP_CHANNEL_ID = "clawgui_agent_steps"   // per-step heads-up banners
        const val NOTIFICATION_ID = 1001
        const val STEP_NOTIFICATION_BASE_ID = 2000          // per-step notifs are 2000+stepIndex
        const val STEP_GROUP = "clawgui_steps"
        const val ACTION_STOP = "com.clawgui.ng.agent.STOP"

        fun start(context: Context) {
            val intent = Intent(context, AgentService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { Log.e(TAG, "start failed", it) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, AgentService::class.java)) }
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = context.getSystemService(NotificationManager::class.java)
                // Best-effort cleanup of old channels (importance is locked at create time
                // so we have to recreate when we change tier).
                runCatching { nm.deleteNotificationChannel("clawgui_agent") }
                runCatching { nm.deleteNotificationChannel("clawgui_agent_v2") }
                if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                    val channel = NotificationChannel(
                        CHANNEL_ID,
                        "Agent 执行状态",
                        NotificationManager.IMPORTANCE_HIGH,
                    ).apply {
                        description = "显示 PhoneAgent 的实时思考、动作与完成结果"
                        setShowBadge(true)
                        enableVibration(true)
                        enableLights(true)
                    }
                    nm.createNotificationChannel(channel)
                }
                if (nm.getNotificationChannel(STEP_CHANNEL_ID) == null) {
                    val stepChannel = NotificationChannel(
                        STEP_CHANNEL_ID,
                        "Agent 单步动作",
                        NotificationManager.IMPORTANCE_HIGH,
                    ).apply {
                        description = "每一步 Tap / Swipe / Type 动作的即时横幅"
                        setShowBadge(false)
                        enableVibration(false)
                        enableLights(false)
                        setSound(null, null)              // silent — purely visual
                    }
                    nm.createNotificationChannel(stepChannel)
                }
            }
        }

        private fun canDrawOverlays(context: Context): Boolean = try {
            android.provider.Settings.canDrawOverlays(context)
        } catch (_: Throwable) {
            false
        }
    }
}
