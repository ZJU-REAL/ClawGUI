package com.clawgui.android.platform.shizuku

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.clawgui.android.IShellService
import com.clawgui.android.core.ime.ClawguiIME
import com.clawgui.android.core.phone.platform.DeviceInterface
import com.clawgui.android.service.ShellService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Device controller — executes shell commands via Shizuku UserService.
 * Core Shizuku binding, UID detection, screenshot chmod workaround, and
 * clipboard-based Chinese input all borrowed from roubao (MIT).
 * openApp() simplified: no AppScanner dependency, uses built-in map + direct package fallback.
 */
class DeviceController(private val context: Context? = null) : DeviceInterface {

    companion object {
        private const val SCREENSHOT_PATH = "/data/local/tmp/clawgui_screen.png"
    }

    private var cachedScreenSize: Pair<Int, Int>? = null
    private fun getCachedScreenSize(): Pair<Int, Int> =
        cachedScreenSize ?: getScreenSize().also { cachedScreenSize = it }

    override val screenWidth: Int get() = getCachedScreenSize().first
    override val screenHeight: Int get() = getCachedScreenSize().second

    private var shellService: IShellService? = null
    private var serviceBound = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val clipboardManager: ClipboardManager? by lazy {
        context?.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    }

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(
            "com.clawgui.android",
            ShellService::class.java.name
        )
    )
        .daemon(false)
        .processNameSuffix("shell")
        .debuggable(true)
        .version(1)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            shellService = IShellService.Stub.asInterface(service)
            serviceBound = true
            println("[DeviceController] ShellService connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            shellService = null
            serviceBound = false
            println("[DeviceController] ShellService disconnected")
        }
    }

    fun bindService() {
        if (!isShizukuAvailable()) {
            println("[DeviceController] Shizuku not available")
            return
        }
        try {
            Shizuku.bindUserService(userServiceArgs, serviceConnection)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun unbindService() {
        try {
            Shizuku.unbindUserService(userServiceArgs, serviceConnection, true)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    fun isAvailable(): Boolean = serviceBound && shellService != null

    enum class ShizukuPrivilegeLevel { NONE, ADB, ROOT }

    fun getShizukuPrivilegeLevel(): ShizukuPrivilegeLevel {
        if (!isAvailable()) return ShizukuPrivilegeLevel.NONE
        return try {
            val uid = Shizuku.getUid()
            when (uid) {
                0 -> ShizukuPrivilegeLevel.ROOT
                else -> ShizukuPrivilegeLevel.ADB
            }
        } catch (e: Exception) {
            e.printStackTrace()
            ShizukuPrivilegeLevel.NONE
        }
    }

    private fun execLocal(command: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readText()
            process.waitFor()
            reader.close()
            output
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    override fun exec(command: String): String {
        return try {
            shellService?.exec(command) ?: execLocal(command)
        } catch (e: Exception) {
            e.printStackTrace()
            execLocal(command)
        }
    }

    override fun tap(x: Int, y: Int) {
        exec("input tap $x $y")
    }

    override fun longPress(x: Int, y: Int) = longPressMs(x, y, 1000)

    fun longPressMs(x: Int, y: Int, durationMs: Int = 1000) {
        exec("input swipe $x $y $x $y $durationMs")
    }

    override fun doubleTap(x: Int, y: Int) {
        exec("input tap $x $y && input tap $x $y")
    }

    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int) = swipeMs(x1, y1, x2, y2, 500)

    fun swipeMs(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 500) {
        exec("input swipe $x1 $y1 $x2 $y2 $durationMs")
    }

    /**
     * 外部(如 GUITool 任务开始)临时把 IME 切到自家后置 true,使 typeText 走 IME broadcast。
     * 任务结束回滚前须置回 false,避免下次任务在未切 IME 时还误走 broadcast 分支。
     */
    @Volatile var preferOurIME: Boolean = false

    /** Input text; prefers our IME broadcast, falls back to clipboard+paste / input text. */
    override fun typeText(text: String) {
        if (preferOurIME && text.isNotEmpty()) {
            if (typeViaOurIME(text)) return
            // broadcast 可能失败(如 IME 未绑定到当前 EditText),退回老路径
        }
        val hasNonAscii = text.any { it.code > 127 }
        if (hasNonAscii) {
            typeViaClipboard(text)
        } else {
            val escaped = text.replace("'", "'\\''")
            exec("input text '$escaped'")
        }
    }

    override fun clearText() {
        if (preferOurIME) {
            exec("am broadcast -a ${ClawguiIME.ACTION_CLEAR_TEXT} -p com.clawgui.android")
            return
        }
        exec("input keyevent 123")  // KEYCODE_MOVE_END
        val backspaces = (1..200).joinToString(" ") { "67" }
        exec("input keyevent $backspaces")
    }

    /**
     * 通过 broadcast 通知自家 IME 把文本 commit 到当前聚焦的 EditText。
     * 成功与否无法直接探测,这里用 am broadcast 的返回是否含 "Broadcast completed" 近似判断。
     * `-p com.clawgui.android` 限制只发给自家 receiver,避免误触发其他应用。
     */
    private fun typeViaOurIME(text: String): Boolean {
        val escaped = text.replace("\\", "\\\\").replace("\"", "\\\"")
        val out = exec(
            "am broadcast -a ${ClawguiIME.ACTION_INPUT_TEXT} " +
                "-p com.clawgui.android " +
                "--es ${ClawguiIME.EXTRA_TEXT} \"$escaped\""
        )
        return out.contains("Broadcast completed")
    }

    override fun launchApp(packageName: String): Boolean {
        val result = exec("monkey -p $packageName -c android.intent.category.LAUNCHER 1 2>&1")
        return "Events injected: 1" in result
    }

    override suspend fun screenshot(): ByteArray? {
        val result = screenshotWithFallback()
        if (result.isFallback && result.isSensitive) return null
        return try {
            val bos = ByteArrayOutputStream()
            result.bitmap.compress(Bitmap.CompressFormat.PNG, 90, bos)
            bos.toByteArray()
        } catch (_: Exception) { null }
    }

    /**
     * Chinese input via Android ClipboardManager + KEYCODE_PASTE (279).
     * Must set clipboard on main thread; waits via CountDownLatch.
     * Borrowed from roubao.
     */
    private fun typeViaClipboard(text: String) {
        println("[DeviceController] Clipboard input: $text")

        if (clipboardManager != null) {
            val ok = doSetClipboardText(text)
            if (!ok) {
                println("[DeviceController] Clipboard timeout/failure")
                return
            }
            Thread.sleep(200)
            exec("input keyevent 279")
            return
        }

        // Fallback: ADBKeyboard broadcast
        val escaped = text.replace("\"", "\\\"")
        exec("am broadcast -a ADB_INPUT_TEXT --es msg \"$escaped\"")
    }

    override fun getClipboardText(): String? {
        val cm = clipboardManager ?: return null
        val latch = CountDownLatch(1)
        var result: String? = null
        mainHandler.post {
            try {
                val clip = cm.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    result = clip.getItemAt(0).text?.toString()
                }
            } catch (e: Exception) {
                println("[DeviceController] getPrimaryClip failed: ${e.message}")
            } finally {
                latch.countDown()
            }
        }
        return if (latch.await(1, TimeUnit.SECONDS)) result else null
    }

    override fun setClipboardText(text: String) {
        doSetClipboardText(text)
    }

    private fun doSetClipboardText(text: CharSequence): Boolean {
        val cm = clipboardManager ?: return false
        val latch = CountDownLatch(1)
        var ok = false
        mainHandler.post {
            try {
                val clip = ClipData.newPlainText("clawgui_input", text)
                cm.setPrimaryClip(clip)
                ok = true
            } catch (e: Exception) {
                println("[DeviceController] setPrimaryClip failed: ${e.message}")
            } finally {
                latch.countDown()
            }
        }
        val awaited = latch.await(1, TimeUnit.SECONDS)
        return awaited && ok
    }

    override fun back() { exec("input keyevent 4") }
    override fun home() { exec("input keyevent 3") }
    fun enter() = exec("input keyevent 66")

    private var cacheDir: File? = null

    fun setCacheDir(dir: File) {
        cacheDir = dir
    }

    data class ScreenshotResult(
        val bitmap: Bitmap,
        val isSensitive: Boolean = false,
        val isFallback: Boolean = false
    )

    /**
     * Takes a screenshot via `screencap` + `chmod 666`, then reads via BitmapFactory.
     * Falls back to black bitmap if the screen is blocked (sensitive page protection).
     * Borrowed from roubao.
     */
    suspend fun screenshotWithFallback(): ScreenshotResult = withContext(Dispatchers.IO) {
        try {
            val output = exec("screencap -p $SCREENSHOT_PATH && chmod 666 $SCREENSHOT_PATH")
            delay(500)

            if (output.contains("Status: -1") || output.contains("Failed") || output.contains("error")) {
                println("[DeviceController] Screenshot blocked (sensitive screen)")
                return@withContext createFallbackScreenshot(isSensitive = true)
            }

            val file = File(SCREENSHOT_PATH)
            if (file.exists() && file.canRead() && file.length() > 0) {
                val bitmap = BitmapFactory.decodeFile(SCREENSHOT_PATH)
                if (bitmap != null) {
                    return@withContext ScreenshotResult(bitmap)
                }
            }

            // Fallback: read via shell cat (needed when file permissions don't allow direct read)
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat $SCREENSHOT_PATH"))
            val bytes = process.inputStream.readBytes()
            process.waitFor()

            if (bytes.isNotEmpty()) {
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap != null) {
                    return@withContext ScreenshotResult(bitmap)
                }
            }

            createFallbackScreenshot(isSensitive = false)
        } catch (e: Exception) {
            e.printStackTrace()
            createFallbackScreenshot(isSensitive = false)
        }
    }

    private fun createFallbackScreenshot(isSensitive: Boolean): ScreenshotResult {
        val (width, height) = getScreenSize()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return ScreenshotResult(bitmap = bitmap, isSensitive = isSensitive, isFallback = true)
    }

    fun getScreenSize(): Pair<Int, Int> {
        val output = exec("wm size")
        val match = Regex("(\\d+)x(\\d+)").find(output)
        val (physicalWidth, physicalHeight) = if (match != null) {
            val (w, h) = match.destructured
            Pair(w.toInt(), h.toInt())
        } else {
            Pair(1080, 2400)
        }
        val orientation = getScreenOrientation()
        return if (orientation == 1 || orientation == 3) {
            Pair(physicalHeight, physicalWidth)
        } else {
            Pair(physicalWidth, physicalHeight)
        }
    }

    private fun getScreenOrientation(): Int {
        val output = exec("dumpsys window displays | grep mCurrentOrientation")
        val match = Regex("mCurrentOrientation=(\\d)").find(output)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    fun openApp(appNameOrPackage: String) {
        val packageMap = mapOf(
            "settings" to "com.android.settings",
            "设置" to "com.android.settings",
            "chrome" to "com.android.chrome",
            "浏览器" to "com.android.browser",
            "camera" to "com.android.camera",
            "相机" to "com.android.camera",
            "phone" to "com.android.dialer",
            "电话" to "com.android.dialer",
            "contacts" to "com.android.contacts",
            "联系人" to "com.android.contacts",
            "messages" to "com.android.mms",
            "短信" to "com.android.mms",
            "gallery" to "com.android.gallery3d",
            "相册" to "com.android.gallery3d",
            "clock" to "com.android.deskclock",
            "时钟" to "com.android.deskclock",
            "calculator" to "com.android.calculator2",
            "计算器" to "com.android.calculator2",
            "calendar" to "com.android.calendar",
            "日历" to "com.android.calendar",
            "files" to "com.android.documentsui",
            "文件" to "com.android.documentsui",
            "wechat" to "com.tencent.mm",
            "微信" to "com.tencent.mm",
            "weibo" to "com.sina.weibo",
            "微博" to "com.sina.weibo",
            "alipay" to "com.eg.android.AlipayGphone",
            "支付宝" to "com.eg.android.AlipayGphone",
            "taobao" to "com.taobao.taobao",
            "淘宝" to "com.taobao.taobao",
            "jd" to "com.jingdong.app.mall",
            "京东" to "com.jingdong.app.mall",
            "douyin" to "com.ss.android.ugc.aweme",
            "抖音" to "com.ss.android.ugc.aweme",
            "bilibili" to "tv.danmaku.bili",
            "哔哩哔哩" to "tv.danmaku.bili",
            "map" to "com.amap.android.maps",
            "地图" to "com.amap.android.maps",
            "高德" to "com.amap.android.maps",
            "meituan" to "com.sankuai.meituan",
            "美团" to "com.sankuai.meituan",
            "eleme" to "me.ele",
            "饿了么" to "me.ele"
        )

        val lowerName = appNameOrPackage.lowercase().trim()
        val finalPackage = when {
            appNameOrPackage.contains(".") -> appNameOrPackage
            packageMap.containsKey(lowerName) -> packageMap[lowerName]!!
            packageMap.containsKey(appNameOrPackage) -> packageMap[appNameOrPackage]!!
            else -> {
                println("[DeviceController] App not found in map: $appNameOrPackage, trying directly")
                appNameOrPackage
            }
        }

        val result = exec("monkey -p $finalPackage -c android.intent.category.LAUNCHER 1 2>/dev/null")
        println("[DeviceController] openApp: $appNameOrPackage -> $finalPackage, result: $result")
    }

    fun openDeepLink(uri: String) {
        exec("am start -a android.intent.action.VIEW -d \"$uri\"")
    }

    fun openIntent(action: String, data: String? = null) {
        val cmd = buildString {
            append("am start -a $action")
            if (data != null) append(" -d \"$data\"")
        }
        exec(cmd)
    }
}
