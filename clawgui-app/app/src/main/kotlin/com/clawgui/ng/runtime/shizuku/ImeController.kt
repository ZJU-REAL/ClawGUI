package com.clawgui.ng.runtime.shizuku

import com.clawgui.ng.runtime.ime.ClawNgIME

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

    private val ourId = ClawNgIME.IME_COMPONENT

    /** 当前默认输入法 id,如 "com.baidu.input/.ImeService"。读失败返回 null。 */
    fun currentIme(): String? {
        val out = device.exec("settings get secure default_input_method").trim()
        return out.takeIf { it.isNotEmpty() && it != "null" }
    }

    /**
     * 自家 IME 是否已被系统启用(在用户的启用列表里)。
     *
     * 优先走 Android Framework 的 `InputMethodManager.getEnabledInputMethodList()` —
     * 这是个无需特权的标准 API,只要 App 进程在前台就能拿到真实状态。
     * shell 命令 `ime list -s` 作为 fallback:有些 ROM(MIUI)在后台调用
     * Framework API 会返回空,这时再退回 shell;没有 Shizuku/wadb 时 shell
     * 也会失败,只能两个都信不过 → 报 false,引导用户去 Android 系统设置勾选。
     */
    fun isOurIMEEnabled(): Boolean {
        val ourComponent = android.content.ComponentName.unflattenFromString(ourId)
        if (ourComponent != null) {
            val frameworkHit = runCatching {
                val imm = com.clawgui.ng.runtime.RuntimeContainer.appContext
                    .getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                imm.enabledInputMethodList.any { info ->
                    info.component == ourComponent ||
                        info.id == ourId ||
                        // Some ROMs report the id without the leading dot-prefix
                        info.id.endsWith(ourComponent.className)
                }
            }.getOrDefault(false)
            if (frameworkHit) return true
        }
        // Fallback to shell — only useful when wadb / Shizuku is granted.
        val out = runCatching { device.exec("ime list -s").trim() }.getOrDefault("")
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
