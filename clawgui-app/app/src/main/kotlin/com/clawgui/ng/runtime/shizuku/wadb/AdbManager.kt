package com.clawgui.ng.runtime.shizuku.wadb

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.PrivateKey
import java.security.cert.Certificate

/**
 * Thin singleton over libadb-android's `AbsAdbConnectionManager`. Holds the
 * device-wide ADB connection used by the wireless-debugging bootstrap.
 *
 * - `pair(...)` performs SPAKE2 + TLS pairing with the system-provided code.
 * - `autoConnect(...)` discovers the live `_adb-tls-connect._tcp` service
 *   via mDNS and authenticates with our persisted private key/cert.
 * - `execShell(cmd)` opens a `shell:` stream and returns the merged output.
 */
class AdbManager private constructor(
    private val ctx: Context,
    private val keys: AdbKeyStore,
) : AbsAdbConnectionManager() {

    init {
        setApi(Build.VERSION.SDK_INT)
        setThrowOnUnauthorised(false)
    }

    override fun getPrivateKey(): PrivateKey = keys.privateKey
    override fun getCertificate(): Certificate = keys.certificate
    override fun getDeviceName(): String = "ClawGUI-NG"

    suspend fun pairAsync(host: String, port: Int, code: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(code.length == 6 && code.all { it.isDigit() }) {
                    "配对码必须是 6 位数字"
                }
                // Force-touch SslUtils so customConscrypt flag flips to true
                // BEFORE pair() builds its TLS context. Some Android 14 ROMs
                // resolve org.conscrypt.OpenSSLProvider lazily and miss it on
                // the first call.
                runCatching {
                    val cls = Class.forName("org.conscrypt.OpenSSLProvider")
                    val provider = cls.getDeclaredConstructor().newInstance() as java.security.Provider
                    val sslCtx = javax.net.ssl.SSLContext.getInstance("TLSv1.3", provider)
                    sslCtx.init(null, null, java.security.SecureRandom())
                    android.util.Log.i("AdbManager", "Conscrypt OpenSSLProvider OK: ${provider.name}")
                }.onFailure {
                    android.util.Log.w("AdbManager", "Conscrypt OpenSSLProvider load failed", it)
                }
                val ok = super.pair(host, port, code)
                check(ok) { "配对握手失败,请确认配对码与设置页面一致" }
            }
        }

    /** Discover + connect (mDNS lookup + TLS auth). Returns true on success. */
    suspend fun autoConnectAsync(timeoutMs: Long = 10_000L): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val ok = super.autoConnect(ctx, timeoutMs)
                check(ok) { "未发现可连接的无线调试端口,请确认开关已打开" }
            }
        }

    /**
     * Blocking version meant to be called *from a worker thread*. The
     * suspending wrapper [execShell] is preferred when the caller is in a
     * coroutine. DO NOT call this from the main thread — it'll ANR while
     * the ADB stream drains.
     */
    fun execShellBlocking(cmd: String, timeoutMs: Long = 15_000L): String {
        val stream: AdbStream = openStream("shell:$cmd")
        try {
            val sb = StringBuilder()
            val buf = ByteArray(4096)
            val deadline = System.currentTimeMillis() + timeoutMs
            stream.openInputStream().use { ins ->
                while (System.currentTimeMillis() < deadline) {
                    val n = try { ins.read(buf) } catch (_: java.io.IOException) { -1 }
                    if (n < 0) break
                    sb.append(String(buf, 0, n, Charsets.UTF_8))
                }
            }
            return sb.toString()
        } finally {
            runCatching { stream.close() }
        }
    }

    suspend fun execShell(cmd: String, timeoutMs: Long = 15_000L): String =
        withContext(Dispatchers.IO) {
            val stream: AdbStream = openStream("shell:$cmd")
            try {
                val sb = StringBuilder()
                val buf = ByteArray(4096)
                val deadline = System.currentTimeMillis() + timeoutMs
                stream.openInputStream().use { ins ->
                    while (System.currentTimeMillis() < deadline) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        sb.append(String(buf, 0, n, Charsets.UTF_8))
                    }
                }
                sb.toString()
            } finally {
                runCatching { stream.close() }
            }
        }

    /** Detach the cached connection. Next call re-discovers + auths. */
    suspend fun disconnectQuietly() = withContext(Dispatchers.IO) {
        runCatching { disconnect() }
    }

    companion object {
        @Volatile private var inst: AdbManager? = null
        fun get(ctx: Context): AdbManager = inst ?: synchronized(this) {
            inst ?: AdbManager(ctx.applicationContext, AdbKeyStore(ctx.applicationContext))
                .also { inst = it }
        }
    }
}
