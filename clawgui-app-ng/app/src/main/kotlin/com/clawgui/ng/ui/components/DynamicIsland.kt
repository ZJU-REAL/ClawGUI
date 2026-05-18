package com.clawgui.ng.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.clawgui.ng.data.ExecutionState
import com.clawgui.ng.data.ExecutionStatus
import com.clawgui.ng.ui.theme.ClawCorners
import com.clawgui.ng.ui.theme.ClawTheme

/**
 * iOS dynamic-island inspired status capsule. Hosted in a system overlay
 * window, this is the user's anchor while the agent runs across other apps.
 */
@Composable
fun DynamicIsland(status: ExecutionStatus) {
    if (status.state == ExecutionState.IDLE) return
    var expanded by remember { mutableStateOf(false) }

    val tone = islandTone(status.state)
    val bg = tone.background
    val fg = tone.foreground
    val accent = tone.accent

    Surface(
        color = Color.Transparent,
        modifier = Modifier
            .padding(top = 8.dp)
            .shadow(elevation = 14.dp, shape = RoundedCornerShape(28.dp), clip = false)
            .clip(RoundedCornerShape(28.dp))
            .background(bg)
            .animateContentSize()
            .clickable { expanded = !expanded },
    ) {
        if (expanded) ExpandedIsland(status, fg, accent)
        else CapsuleIsland(status, fg, accent)
    }
}

@Composable
private fun CapsuleIsland(status: ExecutionStatus, fg: Color, accent: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .widthIn(min = 180.dp, max = 320.dp)
            .heightIn(min = 36.dp)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        StateIndicator(status.state, accent)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = status.title.ifBlank { defaultTitle(status.state) },
                style = MaterialTheme.typography.labelLarge,
                color = fg,
                maxLines = 1,
            )
            if (status.subtitle.isNotBlank()) {
                Text(
                    text = status.subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = fg.copy(alpha = 0.78f),
                    maxLines = 1,
                )
            }
        }
        if (status.totalSteps > 0) {
            Text(
                text = "${status.stepIndex + 1}/${status.totalSteps}",
                style = MaterialTheme.typography.labelSmall,
                color = fg.copy(alpha = 0.8f),
            )
        }
    }
}

@Composable
private fun ExpandedIsland(status: ExecutionStatus, fg: Color, accent: Color) {
    Column(
        modifier = Modifier
            .widthIn(min = 280.dp, max = 360.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StateIndicator(status.state, accent)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    status.title.ifBlank { defaultTitle(status.state) },
                    style = MaterialTheme.typography.titleSmall,
                    color = fg,
                )
                if (status.subtitle.isNotBlank()) {
                    Text(
                        status.subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = fg.copy(alpha = 0.75f),
                    )
                }
            }
            if (status.totalSteps > 0) {
                Surface(
                    shape = ClawCorners.capsule,
                    color = fg.copy(alpha = 0.12f),
                ) {
                    Text(
                        "step ${status.stepIndex + 1}/${status.totalSteps}",
                        style = MaterialTheme.typography.labelSmall,
                        color = fg,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
        }

        AnimatedVisibility(visible = !status.thinking.isNullOrBlank()) {
            Column(Modifier.padding(top = 10.dp)) {
                Text("thinking", style = MaterialTheme.typography.labelSmall,
                    color = fg.copy(alpha = 0.6f))
                val scroll = rememberScrollState()
                Text(
                    text = status.thinking ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = fg,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .heightIn(max = 96.dp)
                        .verticalScroll(scroll),
                )
            }
        }
        AnimatedVisibility(visible = !status.actionJson.isNullOrBlank()) {
            Column(Modifier.padding(top = 10.dp)) {
                Text("action", style = MaterialTheme.typography.labelSmall,
                    color = fg.copy(alpha = 0.6f))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = fg.copy(alpha = 0.08f),
                    modifier = Modifier.padding(top = 4.dp).fillMaxWidth(),
                ) {
                    Text(
                        text = status.actionJson ?: "",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = fg,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun StateIndicator(state: ExecutionState, accent: Color) {
    Box(
        Modifier
            .size(22.dp)
            .clip(RoundedCornerShape(50))
            .background(accent.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            ExecutionState.THINKING, ExecutionState.ACTING -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    color = accent,
                    strokeWidth = 2.dp,
                )
            }
            ExecutionState.DONE -> Text("✓", style = MaterialTheme.typography.labelLarge, color = accent)
            ExecutionState.ERROR -> Text("!", style = MaterialTheme.typography.labelLarge, color = accent)
            ExecutionState.STOPPED -> Text("■", style = MaterialTheme.typography.labelSmall, color = accent)
            ExecutionState.IDLE -> Unit
        }
    }
}

private fun defaultTitle(state: ExecutionState) = when (state) {
    ExecutionState.THINKING -> "正在思考"
    ExecutionState.ACTING -> "正在执行"
    ExecutionState.DONE -> "执行完成"
    ExecutionState.ERROR -> "遇到问题"
    ExecutionState.STOPPED -> "已停止"
    ExecutionState.IDLE -> ""
}

private data class IslandTone(val background: Color, val foreground: Color, val accent: Color)

@Composable
private fun islandTone(state: ExecutionState): IslandTone {
    val extras = ClawTheme.extras
    return when (state) {
        ExecutionState.THINKING, ExecutionState.ACTING ->
            IslandTone(Color(0xFF14110F), Color.White, extras.gradientStart)
        ExecutionState.DONE -> IslandTone(Color(0xFF0F1D14), Color(0xFFEFFFF5), extras.success)
        ExecutionState.ERROR -> IslandTone(Color(0xFF1F1212), Color(0xFFFFE8E6), MaterialTheme.colorScheme.error)
        ExecutionState.STOPPED -> IslandTone(Color(0xFF1A1916), Color(0xFFE9E2D8), MaterialTheme.colorScheme.outline)
        ExecutionState.IDLE -> IslandTone(Color.Transparent, Color.Transparent, Color.Transparent)
    }
}
