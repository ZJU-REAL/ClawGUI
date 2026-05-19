package com.clawgui.ng.runtime.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Circle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.clawgui.ng.data.Plan
import com.clawgui.ng.data.PlanItemStatus
import com.clawgui.ng.data.StepRecord
import com.clawgui.ng.runtime.RuntimeContainer
import com.clawgui.ng.ui.theme.ClawNgTheme
import kotlin.math.abs

/**
 * Floating, semi-transparent panel that mirrors the agent's live plan +
 * action trace above other apps. Lifts the run-time progress info up off
 * the ClawGUI chat screen so the user can keep watching the agent work
 * even while they're inside Weibo / WeChat / wherever it's driving.
 *
 * Behaviour:
 *  - Listens to `RuntimeContainer.agentLive`. Non-null snapshot → show;
 *    null → hide.
 *  - Anchored to the right side of the screen, vertically centered;
 *    user can drag it anywhere.
 *  - Two layouts: collapsed (compact pill ☑ N/M) and expanded (plan
 *    list + recent trace tail). Tap the pill / card header to toggle.
 *  - Tap × to dismiss for the rest of this run; comes back next run.
 */
class AgentLiveOverlay(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var rootView: View? = null
    // LifecycleRegistry is one-shot — once it transitions to ON_DESTROY it
    // refuses to come back to RESUMED, which means a hide → show cycle
    // breaks the Compose host. Allocate a fresh owner per show() so the
    // state machine starts clean every time.
    private var lifecycleOwner: OverlayLifecycleOwner? = null

    fun show() {
        if (rootView != null) return
        val owner = OverlayLifecycleOwner().also { it.start() }
        lifecycleOwner = owner
        val composeView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent {
                ClawNgTheme {
                    val snap by RuntimeContainer.agentLive.collectAsState()
                    AgentLivePanel(snapshot = snap, onDismiss = { hide() })
                }
            }
        }
        val params = buildParams()
        attachDrag(composeView, params)
        runCatching { wm.addView(composeView, params) }
        rootView = composeView
        // AgentService owns the show/hide policy via the agentLive flow.
    }

    fun hide() {
        rootView?.let { runCatching { wm.removeViewImmediate(it) } }
        rootView = null
        lifecycleOwner?.stop()
        lifecycleOwner = null
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
            // NOT_FOCUSABLE so the host app's IME / hardware keys keep working;
            // NOT_TOUCH_MODAL so taps outside our card pass through to the app.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            // Lift below the status bar / dynamic island.
            x = 12
            y = 160
        }
    }

    private fun attachDrag(view: View, params: WindowManager.LayoutParams) {
        var downX = 0f
        var downY = 0f
        var origX = 0
        var origY = 0
        var dragging = false
        view.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    origX = params.x; origY = params.y
                    dragging = false
                    false  // let Compose see the click for header tap-to-toggle
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt()
                    val dy = (e.rawY - downY).toInt()
                    if (!dragging && (abs(dx) > 12 || abs(dy) > 12)) dragging = true
                    if (dragging) {
                        // gravity is TOP|END, so positive x moves *left* from the
                        // right edge. We invert dx for natural drag direction.
                        params.x = (origX - dx).coerceAtLeast(0)
                        params.y = (origY + dy).coerceAtLeast(0)
                        runCatching { wm.updateViewLayout(view, params) }
                        true
                    } else false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging
                else -> false
            }
        }
    }
}

@Composable
private fun AgentLivePanel(
    snapshot: RuntimeContainer.AgentLiveSnapshot?,
    onDismiss: () -> Unit,
) {
    if (snapshot == null) return
    var expanded by remember { mutableStateOf(true) }
    val plan = snapshot.plan
    val trace = snapshot.trace

    // Detect dark theme so we pick a neutral panel color instead of letting
    // Material3's tonal-elevation overlay tint everything blue in dark
    // mode. We use a fixed near-black / near-white with deliberate alpha
    // so the host app shows through.
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val panelColor = if (dark)
        androidx.compose.ui.graphics.Color(0xCC121417)   // ~80% opaque charcoal
    else
        androidx.compose.ui.graphics.Color(0xD9FFFFFF)   // ~85% opaque white
    val onPanel = if (dark)
        androidx.compose.ui.graphics.Color(0xFFE6E8EC)
    else
        androidx.compose.ui.graphics.Color(0xFF1A1C1E)

    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.material3.LocalContentColor provides onPanel,
    ) {
        Box(modifier = Modifier.padding(8.dp)) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = panelColor,
                shadowElevation = 8.dp,
                tonalElevation = 0.dp,
                modifier = Modifier.widthIn(min = 220.dp, max = 320.dp),
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Header(
                    plan = plan,
                    streaming = snapshot.streaming,
                    expanded = expanded,
                    onToggle = { expanded = !expanded },
                    onDismiss = onDismiss,
                )
                if (expanded) {
                    if (plan != null && plan.items.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        PlanMini(plan)
                    }
                    if (trace.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        TraceMini(trace, streaming = snapshot.streaming)
                    }
                }
                }   // Column
            }       // Surface
        }           // Box
    }               // CompositionLocalProvider
}

@Composable
private fun Header(
    plan: Plan?,
    streaming: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onDismiss: () -> Unit,
) {
    val done = plan?.doneCount ?: 0
    val total = plan?.totalCount ?: 0
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
    ) {
        Box(
            Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            if (streaming) {
                CircularProgressIndicator(
                    strokeWidth = 1.6.dp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp),
                )
            } else {
                Icon(
                    Icons.Rounded.AutoAwesome, null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "ClawGUI",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (total > 0) {
                Text(
                    "$done/$total · ${if (streaming) "执行中" else "已完成"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    if (streaming) "正在执行…" else "已停止",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(
            if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
            null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(4.dp))
        Box(
            Modifier
                .size(22.dp)
                .clip(CircleShape)
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Close, "关闭",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
private fun PlanMini(plan: Plan) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Rounded.Checklist, null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(12.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text("任务计划",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.height(4.dp))
    Column {
        plan.items.forEach { item ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 1.dp),
            ) {
                MiniStatusIcon(item.status)
                Spacer(Modifier.width(6.dp))
                Text(
                    item.title,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontWeight = if (item.status == PlanItemStatus.IN_PROGRESS)
                            FontWeight.SemiBold else FontWeight.Normal,
                    ),
                    color = when (item.status) {
                        PlanItemStatus.DONE -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        PlanItemStatus.FAILED -> MaterialTheme.colorScheme.error
                        PlanItemStatus.SKIPPED -> MaterialTheme.colorScheme.onSurfaceVariant
                        PlanItemStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun MiniStatusIcon(status: PlanItemStatus) {
    val (icon, tint) = when (status) {
        PlanItemStatus.PENDING -> Icons.Rounded.Circle to MaterialTheme.colorScheme.outline
        PlanItemStatus.IN_PROGRESS -> Icons.Rounded.PlayCircle to MaterialTheme.colorScheme.primary
        PlanItemStatus.DONE -> Icons.Rounded.Check to MaterialTheme.colorScheme.tertiary
        PlanItemStatus.SKIPPED -> Icons.Rounded.Circle to MaterialTheme.colorScheme.outline
        PlanItemStatus.FAILED -> Icons.Rounded.Close to MaterialTheme.colorScheme.error
        PlanItemStatus.BLOCKED -> Icons.Rounded.Pause to MaterialTheme.colorScheme.primary
    }
    Icon(icon, status.name, tint = tint, modifier = Modifier.size(12.dp))
}

@Composable
private fun TraceMini(trace: List<StepRecord>, streaming: Boolean) {
    // Show only the last 4 steps so the panel stays compact and we don't
    // burn the user's screen real estate. They can open ClawGUI to see
    // the full timeline.
    val tail = trace.takeLast(4)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Rounded.Timeline, null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(12.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            "最近 ${tail.size} 步" + if (trace.size > tail.size) " · 共 ${trace.size}" else "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(4.dp))
    Column {
        tail.forEach { rec ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 1.dp),
            ) {
                Text(
                    "${rec.stepIndex}.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    rec.actionName + if (rec.actionExtra.isNotBlank()) " · ${rec.actionExtra}" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (streaming) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 2.dp),
            ) {
                CircularProgressIndicator(
                    strokeWidth = 1.4.dp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(10.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "思考中…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
