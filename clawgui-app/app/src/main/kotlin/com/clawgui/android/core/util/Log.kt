package com.clawgui.android.core.util

import android.util.Log as AndroidLog

/**
 * 统一日志出口。所有 tag 自带 `ClawGUI/` 前缀,同事排查时一条命令能捞全:
 * ```
 * adb logcat -d | grep ClawGUI
 * ```
 * 或 app 内"设置 → 关于 → 诊断 → 导出诊断日志"。
 *
 * 每条日志强制带 `sessionKey`(可为 null),跨线程/跨 channel 时能对上同一笔 turn。
 *
 * 诊断模式:
 *  - 默认关:只走 [i] / [w] / [e] 的关键节点
 *  - 打开后:调用点用 `if (Log.diag) Log.i(...)` 显式守卫记细节(例如 VLM 每步)
 *  - 开关通过 [setDiagEnabled] 在 App 启动和用户切换时刷新,运行时可变
 */
object Log {
    private const val TAG_PREFIX = "ClawGUI/"

    @Volatile
    private var diagEnabled: Boolean = false

    val diag: Boolean get() = diagEnabled

    fun setDiagEnabled(enabled: Boolean) {
        diagEnabled = enabled
        // 用 android.util.Log 直接打,不递归走自身避免初始化顺序
        AndroidLog.i("${TAG_PREFIX}Log", "diagnostic mode = $enabled")
    }

    fun i(tag: String, key: String?, msg: String) {
        AndroidLog.i(TAG_PREFIX + tag, format(key, msg))
    }

    fun w(tag: String, key: String?, msg: String, t: Throwable? = null) {
        if (t == null) AndroidLog.w(TAG_PREFIX + tag, format(key, msg))
        else AndroidLog.w(TAG_PREFIX + tag, format(key, msg), t)
    }

    fun e(tag: String, key: String?, msg: String, t: Throwable? = null) {
        if (t == null) AndroidLog.e(TAG_PREFIX + tag, format(key, msg))
        else AndroidLog.e(TAG_PREFIX + tag, format(key, msg), t)
    }

    private fun format(key: String?, msg: String): String =
        if (key.isNullOrBlank()) msg else "[$key] $msg"
}
