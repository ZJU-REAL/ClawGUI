package com.clawgui.ng.runtime.ime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.widget.LinearLayout
import android.widget.TextView

/**
 * ClawGUI IME.
 *
 * Goals (in priority order):
 *  1. **Survive every OEM** — pure View hierarchy, no Compose. Some Honor /
 *     MIUI builds choke on ComposeView inside an InputMethodService and the
 *     resulting "IME doesn't work" looks identical to "IME unselected".
 *  2. **Don't try to be a real keyboard** — render a small banner so the user
 *     visually confirms ClawGUI is the active IME; the agent injects text
 *     over broadcasts.
 *  3. **Be a deterministic injection target** — three broadcast actions
 *     (commit / clear / keycode), all guarded for null InputConnection.
 */
class ClawNgIME : InputMethodService() {

    private var receiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter().apply {
            addAction(ACTION_INPUT_TEXT)
            addAction(ACTION_CLEAR_TEXT)
            addAction(ACTION_KEYCODE)
        }
        receiver = Receiver()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(receiver, filter)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "registerReceiver failed", t)
        }
    }

    override fun onDestroy() {
        receiver?.let {
            runCatching { unregisterReceiver(it) }
        }
        receiver = null
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        // A tiny banner so the user knows ClawGUI is on; not a real keyboard.
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#003F88"))
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            addView(TextView(this@ClawNgIME).apply {
                text = "ClawGUI 输入法 · Agent 控制中"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                gravity = Gravity.CENTER
            })
            addView(TextView(this@ClawNgIME).apply {
                text = "如需正常打字请切回系统输入法"
                setTextColor(Color.parseColor("#CCDDEEFF"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, 0)
            })
        }
    }

    /**
     * Hide the candidates strip — we have no candidates.
     */
    override fun onEvaluateInputViewShown(): Boolean = true

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private inner class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val ic: InputConnection = currentInputConnection ?: run {
                Log.w(TAG, "ignored ${intent.action} — no InputConnection")
                return
            }
            when (intent.action) {
                ACTION_INPUT_TEXT -> {
                    val text = intent.getStringExtra(EXTRA_TEXT) ?: return
                    ic.commitText(text, 1)
                }
                ACTION_CLEAR_TEXT -> {
                    val req = ExtractedTextRequest().apply {
                        hintMaxChars = 100_000
                        hintMaxLines = 10_000
                    }
                    val et = ic.getExtractedText(req, 0)
                    if (et?.text != null) {
                        val before = ic.getTextBeforeCursor(et.text.length, 0)
                        val after = ic.getTextAfterCursor(et.text.length, 0)
                        if (before != null && after != null) {
                            ic.deleteSurroundingText(before.length, after.length)
                            return
                        }
                    }
                    // Fallback: select-all then commit empty
                    ic.performContextMenuAction(android.R.id.selectAll)
                    ic.commitText("", 1)
                }
                ACTION_KEYCODE -> {
                    val code = intent.getIntExtra(EXTRA_KEYCODE, KeyEvent.KEYCODE_UNKNOWN)
                    if (code != KeyEvent.KEYCODE_UNKNOWN) {
                        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "ClawNgIME"

        const val IME_COMPONENT = "com.clawgui.ng/.runtime.ime.ClawNgIME"
        const val ACTION_INPUT_TEXT = "com.clawgui.ng.IME_INPUT_TEXT"
        const val ACTION_CLEAR_TEXT = "com.clawgui.ng.IME_CLEAR_TEXT"
        const val ACTION_KEYCODE = "com.clawgui.ng.IME_KEYCODE"
        const val EXTRA_TEXT = "text"
        const val EXTRA_KEYCODE = "keycode"
    }
}
