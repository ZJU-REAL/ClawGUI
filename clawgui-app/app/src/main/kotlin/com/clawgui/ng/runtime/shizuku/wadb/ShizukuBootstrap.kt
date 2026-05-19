package com.clawgui.ng.runtime.shizuku.wadb

import android.content.Context
import android.content.pm.PackageManager

/**
 * Drive the Shizuku server up over an already-authenticated ADB shell.
 *
 * Shizuku's official "start" script ships as `libshizuku.so` inside the
 * Shizuku Manager APK's native library dir (the .so trick makes the system
 * extract + chmod +x on install, even on apps without root). We resolve its
 * concrete path at runtime via PackageManager because the `/data/app/~~…/`
 * directory name rotates on every Shizuku update.
 */
object ShizukuBootstrap {

    const val PKG = "moe.shizuku.privileged.api"

    class NotInstalled : Exception("Shizuku App 未安装")
    class FallbackPathRequired(msg: String) : Exception(msg)

    /** `…/lib/<abi>/libshizuku.so` for the installed Shizuku APK. */
    fun resolveStartScript(ctx: Context): String {
        val ai = try {
            ctx.packageManager.getApplicationInfo(PKG, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            throw NotInstalled()
        }
        val dir = ai.nativeLibraryDir
            ?: throw FallbackPathRequired("Shizuku App 未释放原生库")
        return "$dir/libshizuku.so"
    }

    /**
     * Try to start the server. Falls back to `/data/local/tmp/` copy on
     * MIUI/HONOR ROMs that mount nativeLibraryDir as noexec.
     */
    suspend fun startServer(adb: AdbManager, ctx: Context): String {
        val script = resolveStartScript(ctx)
        val direct = adb.execShell("sh '$script'", timeoutMs = 20_000)
        if (!direct.contains("Permission denied", ignoreCase = true) &&
            !direct.contains("not executable", ignoreCase = true)
        ) {
            return direct
        }
        // Fallback: copy → chmod → exec from a writable tmpdir.
        val tmp = "/data/local/tmp/clawgui_shizuku_start.sh"
        val cp = adb.execShell("cp '$script' '$tmp' && chmod 700 '$tmp' && echo OK_COPY", 8_000)
        if (!cp.contains("OK_COPY")) return "$direct\n[fallback copy failed]\n$cp"
        return adb.execShell("sh '$tmp'", timeoutMs = 20_000)
    }
}
