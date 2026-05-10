package com.clawgui.android.platform.shizuku

import com.clawgui.android.core.ime.ClawguiIME

/**
 * 基于 Shizuku shell 的输入法管理。
 * - `ime list -a` 列出所有 IME;enabled 的会在输出里出现 "mIsEnabled=true" 或直接出现在 ime list(非 -a)里
 * - `settings get secure default_input_method` 读当前默认 IME
 * - `ime enable <id>` / `ime set <id>` 启用并切换
 *
 * 所有命令都走 DeviceController.exec,它会优先用 Shizuku shellService。
 * 无 Shizuku 时 exec 退到本机 sh,普通用户权限下 ime set 会失败 —— 调用方需自行处理。
 */
class ImeController(private val device: DeviceController) {

    private val ourId = ClawguiIME.IME_COMPONENT

    /** 当前默认输入法 id,如 "com.baidu.input/.ImeService"。读失败返回 null。 */
    fun currentIme(): String? {
        val out = device.exec("settings get secure default_input_method").trim()
        return out.takeIf { it.isNotEmpty() && it != "null" }
    }

    /** 自家 IME 是否已被系统启用(在用户启用列表里)。 */
    fun isOurIMEEnabled(): Boolean {
        val out = device.exec("ime list -s").trim()
        return out.lineSequence().any { it.trim() == ourId }
    }

    /** 自家 IME 是否就是当前默认输入法。 */
    fun isOurIMECurrent(): Boolean = currentIme() == ourId

    /** 启用自家 IME(等价于在系统设置里勾选)。需要 Shizuku/root;普通权限下会静默失败。 */
    fun enableOurIME(): Boolean {
        device.exec("ime enable $ourId")
        return isOurIMEEnabled()
    }

    /** 切到自家 IME。返回切换后是否生效。 */
    fun switchToOurIME(): Boolean {
        device.exec("ime set $ourId")
        return isOurIMECurrent()
    }

    /** 切回指定的 IME(通常是任务开始前保存的 origIme)。 */
    fun switchTo(imeId: String): Boolean {
        if (imeId.isBlank()) return false
        device.exec("ime set $imeId")
        return currentIme() == imeId
    }

    /** 让系统重置默认 IME(用于 origIme 无法切回等兜底场景)。 */
    fun reset() {
        device.exec("ime reset")
    }
}
