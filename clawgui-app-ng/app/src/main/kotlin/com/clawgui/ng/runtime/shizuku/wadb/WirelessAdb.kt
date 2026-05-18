package com.clawgui.ng.runtime.shizuku.wadb

import android.content.Context
import android.content.Intent
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Public-facing state machine for the "wireless debugging one-tap"
 * Shizuku starter. Owns no threading itself — the caller (UI / service)
 * drives transitions by invoking the suspend helpers.
 */
sealed interface WadbState {
    data object Idle : WadbState
    data object Probing : WadbState
    data object WirelessOff : WadbState
    data class WaitingForPairing(val message: String) : WadbState
    data class Pairing(val host: String, val port: Int) : WadbState
    data class Connecting(val host: String, val port: Int) : WadbState
    data class Connected(val host: String, val port: Int) : WadbState
    data class StartingShizuku(val script: String) : WadbState
    data class Done(val output: String) : WadbState
    data class Error(val why: String) : WadbState
}

/**
 * Convenience helpers UI invokes. All `suspend`, all main-safe (they
 * dispatch IO internally via AdbManager / AdbMdnsProbe).
 */
class WirelessAdb(private val ctx: Context) {

    private val adb get() = AdbManager.get(ctx)
    private val _state = MutableStateFlow<WadbState>(WadbState.Idle)
    val state: StateFlow<WadbState> = _state

    /**
     * Rolling diagnostic log surfaced in the settings page so users on
     * devices we can't `adb logcat` can still see what went wrong.
     */
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log

    internal fun dlog(line: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        _log.value = (_log.value + "$ts  $line").takeLast(60)
        android.util.Log.i("WirelessAdb", line)
    }

    /** Reset to Idle (e.g. user backed off the page). */
    fun reset() {
        _state.value = WadbState.Idle
    }

    /**
     * Full first-time flow.
     *
     * The system "Pair device with pairing code" page only broadcasts the
     * `_adb-tls-pairing._tcp` mDNS record while the page is open, and
     * Android closes it within a few seconds of switching apps. So we keep
     * the mDNS listener running for up to 60s and tell the user to leave
     * the system page open; pairing fires the moment the listener sees a
     * record (typically within ~1s of the page actually appearing).
     */
    /**
     * Step-1 of the two-step flow: start a 60s mDNS listener so we'll catch
     * the pairing service the moment Android's "Pair device with pairing
     * code" dialog opens. Updates `state` with WaitingForPairing while
     * listening. Caller separately invokes [completePairing] once it has
     * the 6-digit code from the user.
     *
     * We return the discovered endpoint via the state machine, not a
     * suspend return value, so the UI can keep idle showing the timer
     * and react to errors.
     */
    private var discovered: AdbMdnsProbe.Endpoint? = null

    suspend fun beginListenForPairing() {
        discovered = null
        dlog("begin: starting 60s mDNS pairing listener")
        _state.value = WadbState.WaitingForPairing("正在监听配对广播,请打开系统配对页")
        val pair = AdbMdnsProbe.probePairing(ctx, timeoutMs = 60_000L)
        if (pair == null) {
            dlog("mDNS pairing: TIMEOUT after 60s, no broadcast seen")
            _state.value = WadbState.Error(
                "60 秒内没监听到配对广播。请确认:无线调试已开 / 在「使用配对码配对设备」页面 / 同一 WiFi。"
            )
            return
        }
        dlog("mDNS pairing: discovered ${pair.host}:${pair.port}")
        discovered = pair
        _state.value = WadbState.WaitingForPairing("已发现 ${pair.host}:${pair.port},请在通知里输码")
    }

    /** Step-2: feed in the code, do the actual SPAKE2 pair, then connect. */
    suspend fun completePairing(pairingCode: String) {
        try {
            val pair = discovered ?: run {
                dlog("completePairing: called but no pairing endpoint discovered")
                _state.value = WadbState.Error("还没监听到配对广播,请重新开始")
                return
            }
            dlog("pair: SPAKE2 starting against ${pair.host}:${pair.port}, code length=${pairingCode.length}")
            _state.value = WadbState.Pairing(pair.host, pair.port)
            val pairResult = adb.pairAsync(pair.host, pair.port, pairingCode.trim())
            if (pairResult.isFailure) {
                val err = pairResult.exceptionOrNull()
                dlog("pair: FAILED · ${err?.javaClass?.simpleName}: ${err?.message}")
                _state.value = WadbState.Error(
                    "配对失败:${err?.message ?: "未知"}。配对码 60 秒过期 / 系统页关闭都会失败,请重试。"
                )
                return
            }
            dlog("pair: OK")
            discovered = null
            connectAndStartInternal()
        } catch (t: Throwable) {
            dlog("completePairing: UNCAUGHT ${t.javaClass.simpleName}: ${t.message}")
            _state.value = WadbState.Error("配对过程异常:${t.message ?: t.javaClass.simpleName}")
        }
    }

    /**
     * Direct pair with user-provided host / port / code — bypasses mDNS
     * entirely. This is what Shizuku's UI actually does in practice; mDNS
     * is only used for reconnect after reboots when the port changes.
     */
    suspend fun pairDirect(host: String, port: Int, code: String) {
        dlog("pairDirect: $host:$port")
        _state.value = WadbState.Pairing(host, port)
        val r = adb.pairAsync(host, port, code.trim())
        if (r.isFailure) {
            val err = r.exceptionOrNull()
            dlog("pairDirect: FAILED · ${err?.javaClass?.simpleName}: ${err?.message}")
            _state.value = WadbState.Error(
                "配对失败:${err?.message ?: "未知"}。检查 IP / 端口 / 配对码是否一致,且系统配对页仍打开。"
            )
            return
        }
        dlog("pairDirect: OK")
        connectAndStartInternal()
    }

    /** Legacy convenience for single-button flow — keep for now in case any old UI calls it. */
    suspend fun pairAndStart(pairingCode: String) {
        _state.value = WadbState.WaitingForPairing("正在监听配对广播…")
        val pair = AdbMdnsProbe.probePairing(ctx, timeoutMs = 60_000L)
            ?: run {
                _state.value = WadbState.Error(
                    "60 秒内没发现配对广播。请保持系统「使用配对码配对设备」页面在屏幕上后再点。"
                )
                return
            }
        _state.value = WadbState.Pairing(pair.host, pair.port)
        val pairResult = adb.pairAsync(pair.host, pair.port, pairingCode.trim())
        if (pairResult.isFailure) {
            _state.value = WadbState.Error("配对失败:${pairResult.exceptionOrNull()?.message ?: "未知"}。")
            return
        }
        connectAndStartInternal()
    }

    /**
     * Subsequent runs (and post-reboot reconnects). Uses persisted key —
     * pairing is unnecessary as long as the phone hasn't revoked the cert.
     */
    suspend fun connectAndStart() {
        try {
            _state.value = WadbState.Probing
            dlog("reconnect: probing _adb-tls-connect._tcp (8s)")
            // Some OEMs (notably HONOR/Huawei MagicOS) only broadcast the
            // connect service while the user is actively in the Wireless
            // Debugging system page; the moment you leave it the port may
            // get closed even though the master toggle remained on. So we
            // wait longer and give a precise hint on miss.
            val connect = AdbMdnsProbe.probeConnect(ctx, timeoutMs = 8_000L)
            if (connect == null) {
                dlog("reconnect: probe MISS — no _adb-tls-connect._tcp broadcast")
                _state.value = WadbState.Error(
                    "未发现 connect 端口。请到 系统 → 开发者选项 → 无线调试 页面,**停留在那里几秒钟** —— 部分机型只在你进入该页面时才广播连接端口。然后再点本按钮。"
                )
                return
            }
            dlog("reconnect: probe HIT ${connect.host}:${connect.port}")
            connectAndStartInternal()
        } catch (t: Throwable) {
            dlog("reconnect: UNCAUGHT ${t.javaClass.simpleName}: ${t.message}")
            _state.value = WadbState.Error("重连异常:${t.message ?: t.javaClass.simpleName}")
        }
    }

    /**
     * Connect over wireless ADB. By default we just confirm shell-level
     * access — the ADB session itself can run `input tap`, `screencap`,
     * `am start` etc. directly, so there's no need to also start Shizuku.
     *
     * If [alsoStartShizuku] is true (legacy users with the Shizuku App
     * installed), we additionally `sh libshizuku.so` so their existing
     * Shizuku-based plugins keep working.
     */
    private suspend fun connectAndStartInternal(alsoStartShizuku: Boolean = false) {
        try {
            dlog("connect: starting autoConnect (mDNS-based, 10s timeout)")
            _state.value = WadbState.Connecting("", 0)
            val r = adb.autoConnectAsync(timeoutMs = 10_000)
            if (r.isFailure) {
                val why = r.exceptionOrNull()?.message ?: "未知"
                dlog("connect: FAILED · $why")
                _state.value = WadbState.Error(
                    when {
                        why.contains("unauthorised", true) || why.contains("auth", true) ->
                            "无线调试授权已被撤销,需要重新配对。"
                        why.contains("未发现", true) ->
                            "找不到 connect 端口。请确认无线调试总开关打开后,**不要切走**系统页 — 部分 OEM 切走会立刻关闭服务。"
                        else -> "连接失败:$why"
                    }
                )
                return
            }
            dlog("connect: OK")
            _state.value = WadbState.Connected("", 0)
        } catch (t: Throwable) {
            dlog("connect: UNCAUGHT ${t.javaClass.simpleName}: ${t.message}")
            _state.value = WadbState.Error("连接异常:${t.message ?: t.javaClass.simpleName}")
            return
        }

        if (!alsoStartShizuku) {
            _state.value = WadbState.Done("无线调试已就绪,ClawGUI 可直接通过 ADB 通道操作手机")
            return
        }

        val script = try {
            ShizukuBootstrap.resolveStartScript(ctx)
        } catch (_: ShizukuBootstrap.NotInstalled) {
            _state.value = WadbState.Done("无线调试已就绪(未安装 Shizuku App,跳过)")
            return
        } catch (t: Throwable) {
            _state.value = WadbState.Done("无线调试已就绪(Shizuku 启动跳过:${t.message})")
            return
        }
        _state.value = WadbState.StartingShizuku(script)
        val out = runCatching { ShizukuBootstrap.startServer(adb, ctx) }
            .getOrElse { "Shizuku 启动失败:${it.message}" }
        _state.value = WadbState.Done(out.trim().ifBlank { "无线调试已就绪 + Shizuku 启动命令已发送" })
    }

    companion object {
        @Volatile private var inst: WirelessAdb? = null
        fun get(ctx: Context): WirelessAdb = inst ?: synchronized(this) {
            inst ?: WirelessAdb(ctx.applicationContext).also { inst = it }
        }

        /** Convenience for UI: open the system page that exposes the pairing code. */
        fun openWirelessDebuggingSettings(ctx: Context) {
            val direct = Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (direct.resolveActivity(ctx.packageManager) != null) {
                runCatching { ctx.startActivity(direct) }
                return
            }
            runCatching {
                ctx.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }
}
