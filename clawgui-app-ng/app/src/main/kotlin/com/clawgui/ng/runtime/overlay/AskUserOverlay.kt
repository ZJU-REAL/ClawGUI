package com.clawgui.ng.runtime.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.clawgui.ng.ui.theme.ClawNgTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Floating overlay that lets the on-device agent pause itself and ask the
 * user a clarifying question above whatever app is currently in the
 * foreground.
 *
 * Anchored near the bottom of the screen (out of the way of system gesture
 * areas + status bars) so the user can still see what the agent has done so
 * far. Modal-ish: takes focus so the IME pops, but doesn't dim the rest of
 * the screen — the user is encouraged to glance at the live app state while
 * answering.
 *
 * Singleton because we only ever want one Ask outstanding per agent.
 */
object AskUserOverlay {

    private var wm: WindowManager? = null
    private var rootView: android.view.View? = null
    private val lifecycleOwner = OverlayLifecycleOwner()
    private var scope: CoroutineScope? = null

    /** Reactive seconds-waited counter so the UI can show "已等待 32s" without
     *  the caller having to push timer ticks. */
    private val elapsedSec = MutableStateFlow(0)
    private var tickJob: Job? = null

    /**
     * Suspend until the user submits or cancels. Returns the typed answer
     * (possibly blank — caller treats blank as "use your best guess") or
     * null if the user dismissed the overlay / a Cancel button was pressed.
     */
    suspend fun askAndWait(context: Context, question: String): String? = suspendCoroutine { cont ->
        val appCtx = context.applicationContext
        hideInternal()  // safety-net: drop any stale instance before showing a new one

        elapsedSec.value = 0
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main).also { s ->
            tickJob = s.launch {
                while (true) {
                    delay(1000)
                    elapsedSec.value = elapsedSec.value + 1
                }
            }
        }
        wm = appCtx.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        lifecycleOwner.start()
        val composeView = ComposeView(appCtx).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setContent {
                ClawNgTheme {
                    val secs by elapsedSec.collectAsState()
                    AskCard(
                        question = question,
                        elapsedSec = secs,
                        onSubmit = { answer ->
                            hideInternal()
                            cont.resume(answer)
                        },
                        onCancel = {
                            hideInternal()
                            cont.resume(null)
                        },
                    )
                }
            }
        }
        rootView = composeView
        runCatching { wm?.addView(composeView, buildParams()) }
    }

    /** Drop the overlay without resuming any waiter — used when the agent
     *  itself stops (user pressed Stop, task errored) so we don't leak. */
    fun forceDismiss() {
        hideInternal()
    }

    private fun hideInternal() {
        rootView?.let { runCatching { wm?.removeViewImmediate(it) } }
        rootView = null
        tickJob?.cancel(); tickJob = null
        scope?.cancel(); scope = null
        lifecycleOwner.stop()
    }

    private fun buildParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        // FLAG_NOT_FOCUSABLE would leave the IME closed, but we want the user
        // to type immediately. Keep focus enabled; FLAG_ALT_FOCUSABLE_IM lets
        // us own input without consuming hardware keys.
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            // Lift above the gesture bar / nav bar so users can hit Submit
            // without fumbling on the system gesture handle.
            y = 96
        }
    }

    private class OverlayLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner {
        private val lifecycleRegistry = LifecycleRegistry(this)
        private val savedStateRegistryController = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle = lifecycleRegistry
        override val savedStateRegistry: SavedStateRegistry =
            savedStateRegistryController.savedStateRegistry

        fun start() {
            savedStateRegistryController.performRestore(null)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
        fun stop() {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
    }
}

@Composable
private fun AskCard(
    question: String,
    elapsedSec: Int,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var answer by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        // Slight delay so the WindowManager animation is past commit before
        // we yank the IME up — without it the keyboard sometimes opens
        // *behind* the not-yet-laid-out overlay on some OEMs.
        delay(150)
        runCatching { focus.requestFocus() }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 12.dp,
            tonalElevation = 4.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.AutoAwesome, null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "ClawGUI 想确认",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            if (elapsedSec < 30) "等你回答中…"
                            else "已等待 ${elapsedSec}s,你可以慢慢答",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    question.trim(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    BasicTextField(
                        value = answer,
                        onValueChange = { answer = it },
                        textStyle = LocalTextStyle.current.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = MaterialTheme.typography.bodyLarge.fontSize,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { onSubmit(answer) }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                            .focusRequester(focus),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onCancel) {
                        Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(4.dp))
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .padding(horizontal = 2.dp),
                    ) {
                        TextButton(onClick = { onSubmit(answer) }) {
                            Text("继续", color = Color.White)
                        }
                    }
                }
            }
        }
    }
}
