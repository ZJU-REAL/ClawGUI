package com.clawgui.ng.runtime.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.clawgui.ng.data.ExecutionState
import com.clawgui.ng.runtime.RuntimeContainer
import com.clawgui.ng.ui.components.DynamicIsland
import com.clawgui.ng.ui.theme.ClawNgTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Dynamic-island style floating overlay. Hosts a Compose tree inside a
 * WindowManager view, anchored just below the status bar. Tap to expand,
 * drag vertically/horizontally to reposition. Auto-dismisses on Done/Error.
 */
class DynamicIslandOverlay(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var rootView: View? = null
    private var scope: CoroutineScope? = null
    private var dismissJob: Job? = null

    private val lifecycleOwner = OverlayLifecycleOwner()

    fun show() {
        if (rootView != null) return
        lifecycleOwner.start()
        val composeView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setContent {
                ClawNgTheme {
                    val status by RuntimeContainer.executionStatus.collectAsState()
                    DynamicIsland(status)
                }
            }
        }
        val params = buildParams()
        attachTouch(composeView, params)
        wm.addView(composeView, params)
        rootView = composeView

        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope!!.launch {
            RuntimeContainer.executionStatus
                .collect { status ->
                    when (status.state) {
                        ExecutionState.DONE, ExecutionState.STOPPED -> scheduleDismiss(2_400)
                        ExecutionState.ERROR -> scheduleDismiss(3_600)
                        ExecutionState.IDLE -> hide()
                        else -> dismissJob?.cancel()
                    }
                }
        }
    }

    fun hide() {
        rootView?.let {
            runCatching { wm.removeViewImmediate(it) }
        }
        rootView = null
        scope?.cancel(); scope = null
        dismissJob?.cancel(); dismissJob = null
        lifecycleOwner.stop()
    }

    private fun scheduleDismiss(delayMs: Long) {
        dismissJob?.cancel()
        dismissJob = scope?.launch {
            delay(delayMs)
            hide()
        }
    }

    private fun buildParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 32
        }
    }

    private fun attachTouch(view: View, params: WindowManager.LayoutParams) {
        var downX = 0f
        var downY = 0f
        var origY = 0
        var dragging = false
        view.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; origY = params.y; dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = (e.rawY - downY).toInt()
                    if (!dragging && (abs(dy) > 12 || abs(e.rawX - downX) > 12)) dragging = true
                    if (dragging) {
                        params.y = (origY + dy).coerceAtLeast(0)
                        wm.updateViewLayout(view, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!dragging) view.performClick()
                    true
                }
                else -> false
            }
        }
    }

}
