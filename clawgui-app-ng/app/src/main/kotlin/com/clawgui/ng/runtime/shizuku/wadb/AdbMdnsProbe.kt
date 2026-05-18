package com.clawgui.ng.runtime.shizuku.wadb

import android.content.Context
import io.github.muntashirakon.adb.android.AdbMdns
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import kotlin.coroutines.resume

/**
 * Wrappers over the library's built-in mDNS so we can answer two product
 * questions from the UI:
 *
 *  1. "Is wireless debugging actually turned on?" — listen for any
 *     `_adb-tls-connect._tcp` advert.
 *  2. "Where's the pairing port?" — listen for `_adb-tls-pairing._tcp`
 *     (only broadcast while the user is on the "Pair device with pairing
 *     code" screen).
 */
object AdbMdnsProbe {

    data class Endpoint(val host: String, val port: Int)

    private suspend fun firstHit(ctx: Context, type: String, timeoutMs: Long): Endpoint? {
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Endpoint?> { cont ->
                lateinit var mdns: AdbMdns
                val listener = AdbMdns.OnAdbDaemonDiscoveredListener { address: InetAddress?, port: Int ->
                    if (cont.isActive && address != null && port > 0) {
                        cont.resume(Endpoint(address.hostAddress ?: return@OnAdbDaemonDiscoveredListener, port))
                        runCatching { mdns.stop() }
                    }
                }
                mdns = AdbMdns(ctx, type, listener)
                runCatching { mdns.start() }.onFailure {
                    if (cont.isActive) cont.resume(null)
                }
                cont.invokeOnCancellation { runCatching { mdns.stop() } }
            }
        }
    }

    /** Returns the live ADB-TLS connect endpoint, or null if wireless debugging is off / not on this network. */
    suspend fun probeConnect(ctx: Context, timeoutMs: Long = 4_000L): Endpoint? =
        firstHit(ctx, AdbMdns.SERVICE_TYPE_TLS_CONNECT, timeoutMs)

    /** Returns the live ADB-TLS pairing endpoint while user is on the pairing screen. */
    suspend fun probePairing(ctx: Context, timeoutMs: Long = 10_000L): Endpoint? =
        firstHit(ctx, AdbMdns.SERVICE_TYPE_TLS_PAIRING, timeoutMs)
}
