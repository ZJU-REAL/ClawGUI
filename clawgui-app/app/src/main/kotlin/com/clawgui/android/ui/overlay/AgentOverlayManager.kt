package com.clawgui.android.ui.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.clawgui.android.R

/**
 * Overlay window that survives when the user navigates to another app.
 * Uses TYPE_APPLICATION_OVERLAY + SYSTEM_ALERT_WINDOW. Host view is pure XML
 * (no ComposeView) to avoid ViewTreeOwner complications.
 *
 * Tap (press-release < slop / < 250ms) 在 Running 态循环切换 Mini → Compact →
 * Expanded → Mini 三个展示级别;拖动按位移阈值分离。Expanded 态会放宽宽度
 * 并短暂允许 focus(仅为 ScrollView 滚动需要)。Done/Error 永远停在 Compact。
 */
class AgentOverlayManager(private val context: Context) {

    private val wm: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val prefs: SharedPreferences =
        context.getSharedPreferences("clawgui_overlay", Context.MODE_PRIVATE)

    private val mainHandler = Handler(Looper.getMainLooper())

    private var rootView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var displayLevel: DisplayLevel = DisplayLevel.Mini
    private var lastState: OverlayState? = null

    private val touchSlop: Int =
        ViewConfiguration.get(context).scaledTouchSlop

    fun canShow(): Boolean = Settings.canDrawOverlays(context)

    @SuppressLint("ClickableViewAccessibility")
    fun show(initial: OverlayState) {
        if (!canShow()) return
        if (rootView != null) {
            update(initial)
            return
        }

        val inflater = android.view.LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.overlay_agent_status, null)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            collapsedFlags(),
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = prefs.getInt(KEY_X, DEFAULT_X)
        lp.y = prefs.getInt(KEY_Y, DEFAULT_Y)

        attachTouchHandler(view, lp)

        mainHandler.post {
            try {
                wm.addView(view, lp)
                rootView = view
                layoutParams = lp
                displayLevel = DisplayLevel.Mini
                applyState(view, initial)
            } catch (_: Exception) {
                rootView = null
                layoutParams = null
            }
        }
    }

    fun update(state: OverlayState) {
        val view = rootView ?: return
        mainHandler.post { applyState(view, state) }
    }

    fun hide() {
        val view = rootView ?: return
        mainHandler.post {
            try { wm.removeViewImmediate(view) } catch (_: Exception) {}
            rootView = null
            layoutParams = null
            displayLevel = DisplayLevel.Mini
            lastState = null
        }
    }

    private fun applyState(view: View, state: OverlayState) {
        // 等值跳过 —— handleStatus 里 show/update 一次任务周期内会被调几十次
        // (每步 VLM 一次 + tickJob 每秒一次 + AgentService 可能的多重订阅),
        // 大多数都是同内容,不拦就每条都往 Handler 塞 applyState + updateViewLayout,
        // 撞上主线程拥堵时 Done 切换会被排在一长串 Running 后面,肉眼感觉"迟迟不绿"。
        if (state == lastState) return
        lastState = state
        val spinner = view.findViewById<ProgressBar>(R.id.overlay_spinner)
        val icon = view.findViewById<TextView>(R.id.overlay_icon)
        val text = view.findViewById<TextView>(R.id.overlay_text)
        val thinkingView = view.findViewById<TextView>(R.id.overlay_thinking)
        val actionView = view.findViewById<TextView>(R.id.overlay_action)

        when (state) {
            is OverlayState.Running -> {
                spinner.visibility = View.VISIBLE
                icon.visibility = View.GONE
                text.text = state.text
                thinkingView.text = state.thinking?.takeIf { it.isNotBlank() } ?: "(等待数据)"
                actionView.text = state.actionJson?.takeIf { it.isNotBlank() } ?: "(等待数据)"
                // Running 保留当前 displayLevel(用户主动切到哪就留在哪)
            }
            is OverlayState.Done -> {
                spinner.visibility = View.GONE
                icon.visibility = View.VISIBLE
                icon.text = "✓"
                text.text = state.text
                // Done/Error 必须显示文字 + ✓/✗,不兼容 Mini 圆形;强制 Compact
                displayLevel = DisplayLevel.Compact
            }
            is OverlayState.Error -> {
                spinner.visibility = View.GONE
                icon.visibility = View.VISIBLE
                icon.text = "✗"
                text.text = state.text
                displayLevel = DisplayLevel.Compact
            }
            is OverlayState.Stopped -> {
                spinner.visibility = View.GONE
                icon.visibility = View.VISIBLE
                icon.text = "■"
                text.text = state.text
                displayLevel = DisplayLevel.Compact
            }
        }
        applyDisplayLevel(view)
    }

    private fun applyDisplayLevel(view: View) {
        val lp = layoutParams ?: return
        val root = view.findViewById<LinearLayout>(R.id.overlay_root)
        val mini = view.findViewById<FrameLayout>(R.id.overlay_mini_container)
        val header = view.findViewById<LinearLayout>(R.id.overlay_header)
        val expanded = view.findViewById<ScrollView>(R.id.overlay_expanded)

        when (displayLevel) {
            DisplayLevel.Mini -> {
                mini.visibility = View.VISIBLE
                header.visibility = View.GONE
                expanded.visibility = View.GONE
                // Mini 自带圆形背景,root 背景切透明避免外层又套方形
                root.setBackgroundResource(0)
                lp.width = WindowManager.LayoutParams.WRAP_CONTENT
                lp.height = WindowManager.LayoutParams.WRAP_CONTENT
                lp.flags = collapsedFlags()
            }
            DisplayLevel.Compact -> {
                mini.visibility = View.GONE
                header.visibility = View.VISIBLE
                expanded.visibility = View.GONE
                root.setBackgroundResource(backgroundResFor(lastState))
                lp.width = WindowManager.LayoutParams.WRAP_CONTENT
                lp.height = WindowManager.LayoutParams.WRAP_CONTENT
                lp.flags = collapsedFlags()
            }
            DisplayLevel.Expanded -> {
                mini.visibility = View.GONE
                header.visibility = View.VISIBLE
                expanded.visibility = View.VISIBLE
                root.setBackgroundResource(backgroundResFor(lastState))
                val metrics = DisplayMetrics().also { wm.defaultDisplay.getMetrics(it) }
                val density = metrics.density
                val maxByScreen = metrics.widthPixels - (40 * density).toInt()
                val preferred = (360 * density).toInt()
                lp.width = minOf(preferred, maxByScreen).coerceAtLeast((200 * density).toInt())
                lp.height = WindowManager.LayoutParams.WRAP_CONTENT
                lp.flags = expandedFlags()
                // 防止展开后溢出屏幕右边
                if (lp.x + lp.width > metrics.widthPixels) {
                    lp.x = (metrics.widthPixels - lp.width - (8 * density).toInt()).coerceAtLeast(0)
                }
            }
        }
        try { wm.updateViewLayout(view, lp) } catch (_: Exception) {}
    }

    private fun backgroundResFor(state: OverlayState?): Int = when (state) {
        is OverlayState.Done -> R.drawable.overlay_bg_done
        is OverlayState.Error -> R.drawable.overlay_bg_error
        is OverlayState.Stopped -> R.drawable.overlay_bg_stopped
        else -> R.drawable.overlay_bg_running
    }

    private fun cycleDisplayLevel() {
        val view = rootView ?: return
        // 仅 Running 态参与循环 —— Done/Error 没有详情可看
        if (lastState !is OverlayState.Running) return
        displayLevel = when (displayLevel) {
            DisplayLevel.Mini -> DisplayLevel.Compact
            DisplayLevel.Compact -> DisplayLevel.Expanded
            DisplayLevel.Expanded -> DisplayLevel.Mini
        }
        applyDisplayLevel(view)
    }

    private fun collapsedFlags(): Int =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

    private fun expandedFlags(): Int =
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

    @SuppressLint("ClickableViewAccessibility")
    private fun attachTouchHandler(view: View, lp: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        var downTs = 0L
        var dragging = false

        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = lp.x
                    initialY = lp.y
                    touchX = event.rawX
                    touchY = event.rawY
                    downTs = System.currentTimeMillis()
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    if (!dragging && (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop)) {
                        dragging = true
                    }
                    if (dragging) {
                        lp.x = (initialX + dx).toInt()
                        lp.y = (initialY + dy).toInt()
                        try { wm.updateViewLayout(v, lp) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dt = System.currentTimeMillis() - downTs
                    if (!dragging && dt < TAP_MAX_MS) {
                        cycleDisplayLevel()
                    } else if (dragging) {
                        prefs.edit().putInt(KEY_X, lp.x).putInt(KEY_Y, lp.y).apply()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        prefs.edit().putInt(KEY_X, lp.x).putInt(KEY_Y, lp.y).apply()
                    }
                    true
                }
                else -> false
            }
        }
    }

    companion object {
        private const val KEY_X = "overlay_x"
        private const val KEY_Y = "overlay_y"
        private const val DEFAULT_X = 40
        private const val DEFAULT_Y = 120
        private const val TAP_MAX_MS = 250L
    }
}

private enum class DisplayLevel { Mini, Compact, Expanded }

sealed interface OverlayState {
    data class Running(
        val text: String,
        val thinking: String? = null,
        val actionJson: String? = null,
    ) : OverlayState
    data class Done(val text: String) : OverlayState
    data class Error(val text: String) : OverlayState
    data class Stopped(val text: String) : OverlayState
}
