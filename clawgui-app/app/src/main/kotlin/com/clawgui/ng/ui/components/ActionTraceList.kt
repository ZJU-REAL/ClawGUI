package com.clawgui.ng.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.clawgui.ng.data.StepRecord

/**
 * Live trace of PhoneAgent steps. Each row = one StepRecord. Status icon
 * left, action name + extras center, optional "▾" to peek the model's
 * one-line thinking for that step.
 *
 * Behaviour:
 *  - Default expanded while [streaming] = true so the user watches steps
 *    tick in. Auto-collapses to a "已执行 N 步 ▸" summary line once the run
 *    ends, with one tap to re-expand for review.
 *  - The most recent row gets a subtle highlight; while streaming the
 *    *next* (not-yet-arrived) step is shown as a spinner placeholder so the
 *    list never feels frozen between LLM round-trips.
 *  - Per-row thinking preview folds in/out independently — peek what the
 *    model was reasoning about without unfolding 12 of them at once.
 */
@Composable
fun ActionTraceList(
    trace: List<StepRecord>,
    streaming: Boolean,
    modifier: Modifier = Modifier,
) {
    if (trace.isEmpty() && !streaming) return
    var expanded by remember { mutableStateOf(true) }
    androidx.compose.runtime.LaunchedEffect(streaming) {
        // Auto-collapse once the run finishes — keep historical bubbles tidy.
        if (!streaming) expanded = false
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(),
    ) {
        Column(
            modifier = Modifier
                .clickable { expanded = !expanded }
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Timeline, null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "执行轨迹",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (streaming) "${trace.size} 步,进行中…"
                    else "${trace.size} 步",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(6.dp))
                Icon(
                    if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column {
                    Spacer(Modifier.height(8.dp))
                    trace.forEach { rec ->
                        TraceRow(rec, isHead = rec.stepIndex == trace.last().stepIndex && !streaming)
                    }
                    if (streaming) {
                        TracePendingRow(nextIndex = (trace.lastOrNull()?.stepIndex ?: 0) + 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun TraceRow(rec: StepRecord, isHead: Boolean) {
    var peeked by remember { mutableStateOf(false) }
    val hasPreview = rec.thinkingPreview.isNotBlank()
    val bg = if (isHead) MaterialTheme.colorScheme.primary.copy(alpha = 0.06f)
             else Color.Transparent

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = bg,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp)
            .clickable(enabled = hasPreview) { peeked = !peeked },
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusBadge(success = rec.success)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "${rec.stepIndex}.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = rec.actionName,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (rec.actionExtra.isNotBlank()) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "· ${rec.actionExtra}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                } else Spacer(Modifier.weight(1f))
                if (hasPreview) {
                    val rot = if (peeked) 90f else 0f
                    Icon(
                        Icons.Rounded.ChevronRight,
                        null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(16.dp)
                            .rotate(rot),
                    )
                }
            }
            AnimatedVisibility(
                visible = peeked && hasPreview,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Text(
                    text = rec.thinkingPreview,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 28.dp, top = 4.dp, end = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun TracePendingRow(nextIndex: Int) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            CircularProgressIndicator(
                strokeWidth = 1.6.dp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = "$nextIndex. 思考中…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatusBadge(success: Boolean) {
    val tint = if (success) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
    Box(
        Modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (success) Icons.Rounded.Check else Icons.Rounded.Close,
            null,
            tint = tint,
            modifier = Modifier.size(12.dp),
        )
    }
}
