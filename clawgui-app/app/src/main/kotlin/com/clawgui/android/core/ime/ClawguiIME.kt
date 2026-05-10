package com.clawgui.android.core.ime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.widget.TextView

/**
 * 自家输入法服务。对 ADBKeyboard 的思路做精简:
 * - 不暴露独立的用户键盘,inputView 只是一个占位 TextView,提示正在被 agent 控制。
 * - 只支持 agent 真正需要的三类 broadcast:文本提交 / 清空 / 单 keyevent。
 */
class ClawguiIME : InputMethodService() {

    companion object {
        const val ACTION_INPUT_TEXT = "com.clawgui.android.IME_INPUT_TEXT"
        const val ACTION_CLEAR_TEXT = "com.clawgui.android.IME_CLEAR_TEXT"
        const val ACTION_KEYCODE = "com.clawgui.android.IME_KEYCODE"

        const val EXTRA_TEXT = "text"
        const val EXTRA_CODE = "code"

        const val IME_COMPONENT = "com.clawgui.android/.core.ime.ClawguiIME"
    }

    private var receiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter().apply {
            addAction(ACTION_INPUT_TEXT)
            addAction(ACTION_CLEAR_TEXT)
            addAction(ACTION_KEYCODE)
        }
        receiver = Receiver()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }

    override fun onCreateInputView(): View {
        return TextView(this).apply {
            text = "ClawGUI Keyboard (active)"
            setPadding(32, 48, 32, 48)
        }
    }

    override fun onDestroy() {
        receiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        receiver = null
        super.onDestroy()
    }

    private inner class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val ic: InputConnection = currentInputConnection ?: return
            when (intent.action) {
                ACTION_INPUT_TEXT -> {
                    val text = intent.getStringExtra(EXTRA_TEXT) ?: return
                    ic.commitText(text, 1)
                }
                ACTION_CLEAR_TEXT -> {
                    val req = ExtractedTextRequest().apply {
                        hintMaxChars = 100000
                        hintMaxLines = 10000
                    }
                    val et = ic.getExtractedText(req, 0)
                    if (et != null && et.text != null) {
                        val before = ic.getTextBeforeCursor(et.text.length, 0)
                        val after = ic.getTextAfterCursor(et.text.length, 0)
                        if (before != null && after != null) {
                            ic.deleteSurroundingText(before.length, after.length)
                            return
                        }
                    }
                    ic.performContextMenuAction(android.R.id.selectAll)
                    ic.commitText("", 1)
                }
                ACTION_KEYCODE -> {
                    val code = intent.getIntExtra(EXTRA_CODE, -1)
                    if (code != -1) {
                        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
                    }
                }
            }
        }
    }
}
