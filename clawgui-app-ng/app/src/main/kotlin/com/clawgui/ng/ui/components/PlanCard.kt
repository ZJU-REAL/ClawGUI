package com.clawgui.ng.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Circle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.RemoveCircle
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.clawgui.ng.data.Plan
import com.clawgui.ng.data.PlanItem
import com.clawgui.ng.data.PlanItemStatus

/**
 * Structured-plan card embedded in PhoneAgent assistant bubbles. Renders
 * each PlanItem with its current status icon, animates status transitions
 * (icon fades + scales), and pulses the IN_PROGRESS row. Collapses to a
 * "done X/N" summary line once the run finishes.
 */
@Composable
fun PlanCard(plan: Plan, modifier: Modifier = Modifier) {
    val finished = plan.items.all {
        it.status == PlanItemStatus.DONE || it.status == PlanItemStatus.SKIPPED ||
            it.status == PlanItemStatus.FAILED
    }
    var expanded by remember { mutableStateOf(true) }
    // Auto-collapse once the run is over so historical bubbles stay compact.
    // The user can re-expand manually if they want to scroll back through it.
    androidx.compose.runtime.LaunchedEffect(finished) {
        if (finished) expanded = false
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
                    Icons.Rounded.Checklist, null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "任务计划",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "${plan.doneCount}/${plan.totalCount} 完成",
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
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                plan.items.forEachIndexed { i, item ->
                    PlanRow(item, isLast = i == plan.items.lastIndex)
                }
            }
        }
    }
}

@Composable
private fun PlanRow(item: PlanItem, isLast: Boolean) {
    val active = item.status == PlanItemStatus.IN_PROGRESS
    val bg = if (active)
        MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
    else
        Color.Transparent
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = bg,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = if (isLast) 0.dp else 4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            // AnimatedContent so status transitions fade+scale the icon
            // instead of snapping.
            AnimatedContent(
                targetState = item.status,
                transitionSpec = {
                    (fadeIn(tween(200)) + scaleIn(initialScale = 0.6f, animationSpec = tween(200)))
                        .togetherWith(fadeOut(tween(160)) + scaleOut(targetScale = 0.6f, animationSpec = tween(160)))
                },
                label = "status",
            ) { st ->
                StatusIcon(st)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = when (item.status) {
                        PlanItemStatus.DONE -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        PlanItemStatus.SKIPPED -> MaterialTheme.colorScheme.onSurfaceVariant
                        PlanItemStatus.FAILED -> MaterialTheme.colorScheme.error
                        PlanItemStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                    textDecoration = if (item.status == PlanItemStatus.SKIPPED)
                        TextDecoration.LineThrough else TextDecoration.None,
                )
                val sub = item.note ?: item.detail
                if (!sub.isNullOrBlank()) {
                    Text(
                        text = sub,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusIcon(status: PlanItemStatus) {
    val (icon, tint) = when (status) {
        PlanItemStatus.PENDING -> Icons.Rounded.Circle to MaterialTheme.colorScheme.outline
        PlanItemStatus.IN_PROGRESS -> Icons.Rounded.PlayCircle to MaterialTheme.colorScheme.primary
        PlanItemStatus.DONE -> Icons.Rounded.CheckCircle to MaterialTheme.colorScheme.tertiary
        PlanItemStatus.SKIPPED -> Icons.Rounded.RemoveCircle to MaterialTheme.colorScheme.outline
        PlanItemStatus.FAILED -> Icons.Rounded.Cancel to MaterialTheme.colorScheme.error
        PlanItemStatus.BLOCKED -> Icons.Rounded.Pause to MaterialTheme.colorScheme.primary
    }
    if (status == PlanItemStatus.IN_PROGRESS) {
        // Subtle pulse so the user's eye snaps to the active row.
        val transition = rememberInfiniteTransition(label = "pulse")
        val alpha by transition.animateFloat(
            initialValue = 0.55f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(900), repeatMode = RepeatMode.Reverse),
            label = "alpha",
        )
        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = status.name,
                tint = tint.copy(alpha = alpha),
                modifier = Modifier.size(18.dp),
            )
        }
    } else {
        Icon(
            imageVector = icon,
            contentDescription = status.name,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
    }
}
