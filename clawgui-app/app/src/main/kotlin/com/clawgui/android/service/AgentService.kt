package com.clawgui.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.clawgui.android.App
import com.clawgui.android.ExecutionStatus
import com.clawgui.android.ui.overlay.AgentOverlayManager
import com.clawgui.android.ui.overlay.OverlayState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the cross-app execution status overlay
 * and subscribes to App.executionStatus. Auto-stops shortly after entering
 * Done/Error state.
 */
class AgentService : Service() {

    companion object {
        private const val CHANNEL_ID = "clawgui_agent"
        private const val NOTIFICATION_ID = 1001
        private const val ERROR_AUTO_DISMISS_MS = 5_000L
        private const val DONE_AUTO_DISMISS_MS = 3_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var overlay: AgentOverlayManager? = null
    private var tickJob: Job? = null
    private var dismissJob: Job? = null
    private var statusJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        overlay = AgentOverlayManager(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("启动中…"))

        // startForegroundService 在服务已运行时仍会再次触发 onStartCommand,
        // 不加守卫每次 acquireSlot 都会叠一个 collectLatest,N 次之后主线程每次
        // 状态变化要跑 N 次 handleStatus,Done 切换延迟随 N 累积。
        if (statusJob?.isActive != true) {
            statusJob = scope.launch {
                App.getInstance().executionStatus.collectLatest { status ->
                    handleStatus(status)
                }
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        statusJob?.cancel()
        tickJob?.cancel()
        dismissJob?.cancel()
        overlay?.hide()
        overlay = null
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun handleStatus(status: ExecutionStatus) {
        tickJob?.cancel()
        dismissJob?.cancel()
        when (status) {
            is ExecutionStatus.Running -> {
                overlay?.show(
                    OverlayState.Running(
                        text = formatRunning(status, 0),
                        thinking = status.thinking,
                        actionJson = status.actionJson,
                    )
                )
                updateNotification("执行中…")
                tickJob = scope.launch {
                    while (isActive) {
                        val elapsed = (System.currentTimeMillis() - status.startMs) / 1000
                        overlay?.update(
                            OverlayState.Running(
                                text = formatRunning(status, elapsed),
                                thinking = status.thinking,
                                actionJson = status.actionJson,
                            )
                        )
                        delay(1000)
                    }
                }
            }
            is ExecutionStatus.Error -> {
                overlay?.show(OverlayState.Error("出错: ${status.message}".take(48)))
                updateNotification("出错: ${status.message.take(48)}")
                dismissJob = scope.launch {
                    delay(ERROR_AUTO_DISMISS_MS)
                    App.getInstance().markStatusIdle()
                }
            }
            is ExecutionStatus.Done -> {
                overlay?.show(OverlayState.Done("完成"))
                updateNotification("完成")
                dismissJob = scope.launch {
                    delay(DONE_AUTO_DISMISS_MS)
                    App.getInstance().markStatusIdle()
                }
            }
            is ExecutionStatus.Stopped -> {
                overlay?.show(OverlayState.Stopped(status.message.take(48)))
                updateNotification(status.message.take(48))
                dismissJob = scope.launch {
                    delay(DONE_AUTO_DISMISS_MS)
                    App.getInstance().markStatusIdle()
                }
            }
            is ExecutionStatus.Idle -> {
                overlay?.hide()
                stopSelf()
            }
        }
    }

    private fun formatRunning(running: ExecutionStatus.Running, elapsedSec: Long): String {
        val prefix = if (elapsedSec > 0) "${elapsedSec}s · " else ""
        return "$prefix${running.stageHint}"
    }

    private fun updateNotification(message: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(message))
    }

    private fun buildNotification(message: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ClawGUI")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Agent 执行",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "ClawGUI agent 执行任务时保持运行"
        }
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }
}
