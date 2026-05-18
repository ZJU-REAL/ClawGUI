package com.clawgui.ng.runtime.shizuku.wadb

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Drives the wireless-debugging pairing handshake **without making the user
 * leave ClawGUI**. Flow:
 *
 *   1. start a 60s mDNS listener for `_adb-tls-pairing._tcp`
 *   2. jump to the system Wireless Debugging settings (where the user taps
 *      "Pair device with pairing code" to see the 6-digit code)
 *   3. post a notification with a RemoteInput so the user can type the code
 *      from the notification shade — no need to leave the system page
 *   4. when the user hits "Send", a BroadcastReceiver pulls the code,
 *      pairs against the discovered endpoint, then connects.
 */
object WadbPairingNotifier {

    const val CHANNEL_ID = "wadb_pairing"
    const val NOTIF_ID = 0x7ad
    const val ACTION_SUBMIT = "com.clawgui.ng.wadb.PAIRING_SUBMIT"
    const val ACTION_CANCEL = "com.clawgui.ng.wadb.PAIRING_CANCEL"
    const val INPUT_KEY = "pairing_code"

    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun startPairingFlow(ctx: Context) {
        ensureChannel(ctx)
        val wadb = WirelessAdb.get(ctx)

        // 1) jump to the system Wireless Debugging settings — user will tap
        //    "Pair device with pairing code" themselves
        WirelessAdb.openWirelessDebuggingSettings(ctx)

        // 2) kick off mDNS listener — fires for up to 60s. The listener
        //    flips state to WaitingForPairing(host:port) once it sees the
        //    advertisement, then we observe that and post the notification.
        val listenerJob = bg.launch { wadb.beginListenForPairing() }

        // 3) observe state — only post the RemoteInput notification *after*
        //    mDNS has actually discovered the pairing endpoint. Posting it
        //    earlier was a footgun: user typed the code in but `discovered`
        //    was still null.
        val observer = bg.launch {
            wadb.state.first { s ->
                s is WadbState.WaitingForPairing && s.message.startsWith("已发现")
            }
            postPromptNotification(ctx)
        }

        listenerJob.join()
        observer.cancel()
        // If we got here without anyone hitting "send" the listener
        // either succeeded (handled by PairingReceiver) or timed out
        // (dismiss the notification, if any).
        dismissNotification(ctx)
    }

    private fun postPromptNotification(ctx: Context) {
        val remoteInput = RemoteInput.Builder(INPUT_KEY)
            .setLabel("6 位配对码")
            .build()

        val submitIntent = Intent(ctx, PairingReceiver::class.java).apply {
            action = ACTION_SUBMIT
            setPackage(ctx.packageName)
        }
        val submitPI = PendingIntent.getBroadcast(
            ctx, 1, submitIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

        val cancelIntent = Intent(ctx, PairingReceiver::class.java).apply {
            action = ACTION_CANCEL
            setPackage(ctx.packageName)
        }
        val cancelPI = PendingIntent.getBroadcast(
            ctx, 2, cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val sendAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            "确认配对",
            submitPI,
        ).addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(false)
            .build()

        val cancelAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_close_clear_cancel,
            "取消",
            cancelPI,
        ).build()

        val notif = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setColor(0xFF003F88.toInt())
            .setContentTitle("输入无线调试配对码")
            .setContentText("展开后填 6 位数字")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(sendAction)
            .addAction(cancelAction)
            .build()

        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, notif)
    }

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, "无线调试配对",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply { description = "在通知里输入配对码,完成 ADB 配对" }
            )
        }
    }

    /** Drop the notification (called by receiver after success/cancel). */
    fun dismissNotification(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
    }

    /** Exposed so the receiver can launch on the same supervised scope. */
    internal val pairingScope: CoroutineScope get() = bg
}

class PairingReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            WadbPairingNotifier.ACTION_SUBMIT -> {
                val code = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(WadbPairingNotifier.INPUT_KEY)
                    ?.toString()
                    ?.trim()
                    ?.filter { it.isDigit() }
                    .orEmpty()
                if (code.length != 6) {
                    // Re-post the notification so the user can fix the input.
                    WadbPairingNotifier.dismissNotification(ctx)
                    return
                }
                val pendingResult = goAsync()
                WadbPairingNotifier.pairingScope.launch(Dispatchers.IO) {
                    try {
                        WirelessAdb.get(ctx).completePairing(code)
                    } catch (t: Throwable) {
                        // Swallow anything — receiver can't propagate exceptions
                        // back to the user safely, and any uncaught throwable here
                        // brings the whole app down.
                        runCatching { WirelessAdb.get(ctx).dlog("PairingReceiver: ${t.javaClass.simpleName}: ${t.message}") }
                        android.util.Log.e("PairingReceiver", "completePairing crashed", t)
                    } finally {
                        runCatching { WadbPairingNotifier.dismissNotification(ctx) }
                        runCatching { pendingResult.finish() }
                    }
                }
            }
            WadbPairingNotifier.ACTION_CANCEL -> {
                WadbPairingNotifier.dismissNotification(ctx)
                WirelessAdb.get(ctx).reset()
            }
        }
    }
}
