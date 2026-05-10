package com.clawgui.android.core.phone.platform

interface DeviceInterface {

    fun tap(x: Int, y: Int)
    fun doubleTap(x: Int, y: Int)
    fun longPress(x: Int, y: Int)
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int)
    fun back()
    fun home()
    fun typeText(text: String)
    fun clearText()
    fun launchApp(packageName: String): Boolean
    fun exec(command: String): String
    suspend fun screenshot(): ByteArray?

    fun getClipboardText(): String?
    fun setClipboardText(text: String)

    val screenWidth: Int
    val screenHeight: Int
}
