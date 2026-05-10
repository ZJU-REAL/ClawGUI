package com.clawgui.android.core.phone.actions

import com.clawgui.android.core.phone.config.Apps
import com.clawgui.android.core.phone.config.InstalledApps
import com.clawgui.android.core.phone.config.TimingSettings
import com.clawgui.android.core.phone.platform.DeviceInterface
import kotlinx.coroutines.delay

data class ActionResult(
    val success: Boolean,
    val shouldFinish: Boolean,
    val message: String? = null,
    val requiresConfirmation: Boolean = false,
)

class ActionHandler(
    private val device: DeviceInterface,
    private val confirmationCallback: (suspend (String) -> Boolean)? = null,
    private val takeoverCallback: (suspend (String) -> Unit)? = null,
) {
    // Type 动作用剪贴板粘贴,会覆盖用户真正想"复制→粘贴"的内容。
    // 首次 Type 前保存一次现场剪贴板,下一次非 Type 动作前惰性还原。
    private var savedClipboard: String? = null
    private var dirtyFromType: Boolean = false

    suspend fun execute(action: Map<String, Any?>): ActionResult {
        val metadata = action["_metadata"]
        val actionName = action["action"] as? String

        if (dirtyFromType && actionName != "Type" && actionName != "Type_Name") {
            restoreClipboard()
        }

        if (metadata == "finish") {
            // finish 前也还原(进入了 restoreClipboard 分支就不会重复)
            return ActionResult(
                success = true,
                shouldFinish = true,
                message = action["message"] as? String,
            )
        }

        if (metadata != "do") {
            return ActionResult(false, true, "Unknown action type: $metadata")
        }

        return when (actionName) {
            "Launch" -> handleLaunch(action)
            "Tap" -> handleTap(action)
            "Type", "Type_Name" -> handleType(action)
            "Swipe" -> handleSwipe(action)
            "Back" -> handleBack()
            "Home" -> handleHome()
            "Double Tap" -> handleDoubleTap(action)
            "Long Press" -> handleLongPress(action)
            "Wait" -> handleWait(action)
            "Take_over" -> handleTakeover(action)
            "Note", "Call_API", "Interact" -> ActionResult(true, false)
            else -> ActionResult(false, false, "Unknown action: $actionName")
        }
    }

    /** Task 收尾时调,把剪贴板恢复到任务开始前的状态。 */
    fun finalRestore() {
        if (dirtyFromType) restoreClipboard()
    }

    private fun restoreClipboard() {
        savedClipboard?.let { device.setClipboardText(it) }
        savedClipboard = null
        dirtyFromType = false
    }

    private fun convertCoords(element: List<*>): Pair<Int, Int> {
        val flat = if (element.size == 1 && element[0] is List<*>) element[0] as List<*> else element
        val relX: Double
        val relY: Double
        if (flat.size >= 4) {
            val x1 = (flat[0] as? Number)?.toDouble() ?: 500.0
            val y1 = (flat[1] as? Number)?.toDouble() ?: 500.0
            val x2 = (flat[2] as? Number)?.toDouble() ?: 500.0
            val y2 = (flat[3] as? Number)?.toDouble() ?: 500.0
            relX = (x1 + x2) / 2.0
            relY = (y1 + y2) / 2.0
        } else if (flat.size >= 2) {
            relX = (flat[0] as? Number)?.toDouble() ?: 500.0
            relY = (flat[1] as? Number)?.toDouble() ?: 500.0
        } else {
            relX = 500.0; relY = 500.0
        }
        val x = (relX / 1000.0 * device.screenWidth).toInt()
        val y = (relY / 1000.0 * device.screenHeight).toInt()
        return x to y
    }

    private suspend fun handleLaunch(action: Map<String, Any?>): ActionResult {
        val appName = action["app"] as? String
            ?: return ActionResult(false, false, "No app name specified")
        val pkg = Apps.getPackageName(appName)
            ?: InstalledApps.findPackage(appName)
            ?: appName
        val ok = device.launchApp(pkg)
        delay((TimingSettings.config.device.defaultLaunchDelay * 1000).toLong())
        return if (ok) ActionResult(true, false)
        else ActionResult(false, false, "App not found: $appName")
    }

    private suspend fun handleTap(action: Map<String, Any?>): ActionResult {
        @Suppress("UNCHECKED_CAST")
        val element = (action["element"] as? List<*>)
            ?: return ActionResult(false, false, "No element coordinates")

        if ("message" in action) {
            val msg = action["message"] as? String ?: ""
            val proceed = confirmationCallback?.invoke(msg) ?: true
            if (!proceed) return ActionResult(false, true, "User cancelled sensitive operation")
        }

        val (x, y) = convertCoords(element)
        device.tap(x, y)
        delay((TimingSettings.config.device.defaultTapDelay * 1000).toLong())
        return ActionResult(true, false)
    }

    private suspend fun handleType(action: Map<String, Any?>): ActionResult {
        val text = action["text"] as? String ?: ""
        val cfg = TimingSettings.config.action

        // 首次 Type 前保存现场剪贴板(后续重复 Type 不覆盖保存值)
        if (!dirtyFromType) {
            savedClipboard = device.getClipboardText()
            dirtyFromType = true
        }

        delay((cfg.keyboardSwitchDelay * 1000).toLong())
        device.clearText()
        delay((cfg.textClearDelay * 1000).toLong())
        device.typeText(text)
        delay((cfg.textInputDelay * 1000).toLong())

        return ActionResult(true, false)
    }

    private suspend fun handleSwipe(action: Map<String, Any?>): ActionResult {
        @Suppress("UNCHECKED_CAST")
        val start = action["start"] as? List<*>
            ?: return ActionResult(false, false, "Missing swipe start")
        @Suppress("UNCHECKED_CAST")
        val end = action["end"] as? List<*>
            ?: return ActionResult(false, false, "Missing swipe end")

        val (x1, y1) = convertCoords(start)
        val (x2, y2) = convertCoords(end)
        device.swipe(x1, y1, x2, y2)
        delay((TimingSettings.config.device.defaultSwipeDelay * 1000).toLong())
        return ActionResult(true, false)
    }

    private suspend fun handleBack(): ActionResult {
        device.back()
        delay((TimingSettings.config.device.defaultBackDelay * 1000).toLong())
        return ActionResult(true, false)
    }

    private suspend fun handleHome(): ActionResult {
        device.home()
        delay((TimingSettings.config.device.defaultHomeDelay * 1000).toLong())
        return ActionResult(true, false)
    }

    private suspend fun handleDoubleTap(action: Map<String, Any?>): ActionResult {
        @Suppress("UNCHECKED_CAST")
        val element = (action["element"] as? List<*>)
            ?: return ActionResult(false, false, "No element coordinates")
        val (x, y) = convertCoords(element)
        device.doubleTap(x, y)
        delay((TimingSettings.config.device.defaultDoubleTapDelay * 1000).toLong())
        return ActionResult(true, false)
    }

    private suspend fun handleLongPress(action: Map<String, Any?>): ActionResult {
        @Suppress("UNCHECKED_CAST")
        val element = (action["element"] as? List<*>)
            ?: return ActionResult(false, false, "No element coordinates")
        val (x, y) = convertCoords(element)
        device.longPress(x, y)
        delay((TimingSettings.config.device.defaultLongPressDelay * 1000).toLong())
        return ActionResult(true, false)
    }

    private suspend fun handleWait(action: Map<String, Any?>): ActionResult {
        val durationStr = action["duration"] as? String ?: "1 seconds"
        val secs = durationStr.replace("seconds", "").trim().toDoubleOrNull() ?: 1.0
        delay((secs * 1000).toLong())
        return ActionResult(true, false)
    }

    private suspend fun handleTakeover(action: Map<String, Any?>): ActionResult {
        val message = action["message"] as? String ?: "User intervention required"
        takeoverCallback?.invoke(message)
        return ActionResult(true, false)
    }
}
