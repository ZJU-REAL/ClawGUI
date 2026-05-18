package com.clawgui.ng.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Refresh
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import android.widget.Toast
import androidx.compose.ui.unit.dp
import com.clawgui.ng.data.ChatMessage
import com.clawgui.ng.data.Role
import com.clawgui.ng.ui.theme.ClawCorners
import com.clawgui.ng.ui.theme.ClawTheme

@Composable
fun MessageBubble(
    message: ChatMessage,
    onRegenerate: () -> Unit,
    showFollowUps: Boolean = false,
    onPickFollowUp: (String) -> Unit = {},
) {
    when (message.role) {
        Role.USER -> UserBubble(message)
        Role.ASSISTANT -> AssistantBubble(
            m = message,
            onRegenerate = onRegenerate,
            showFollowUps = showFollowUps,
            onPickFollowUp = onPickFollowUp,
        )
        Role.SYSTEM, Role.TOOL -> SystemNote(message)
    }
}

@Composable
private fun UserBubble(m: ChatMessage) {
    val extras = ClawTheme.extras
    val brush = Brush.linearGradient(listOf(extras.gradientStart, extras.gradientEnd))
    Row(
        Modifier.fillMaxWidth().padding(start = 56.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        Column(horizontalAlignment = Alignment.End) {
            // Image attachments are rendered above the text in a tight grid so
            // multi-image turns stay visually grouped with the message they
            // belong to (rather than floating loose like Telegram).
            val images = m.attachments.filter { it.kind == com.clawgui.ng.data.AttachmentKind.IMAGE }
            if (images.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    images.take(3).forEach { att ->
                        coil.compose.AsyncImage(
                            model = java.io.File(att.uri),
                            contentDescription = att.displayName,
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier
                                .size(120.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                        )
                    }
                }
                if (m.content.isNotBlank()) Spacer(Modifier.height(6.dp))
            }
            if (m.content.isNotBlank()) {
                Surface(
                    shape = ClawCorners.bubbleUser,
                    color = androidx.compose.ui.graphics.Color.Transparent,
                    modifier = Modifier
                        .widthIn(max = 320.dp)
                        .clip(ClawCorners.bubbleUser)
                        .background(brush)
                ) {
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(
                            text = m.content,
                            color = extras.userBubbleContent,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AssistantBubble(
    m: ChatMessage,
    onRegenerate: () -> Unit,
    showFollowUps: Boolean,
    onPickFollowUp: (String) -> Unit,
) {
    val extras = ClawTheme.extras
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 56.dp, top = 6.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        AssistantAvatar(size = 30)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            if (!m.thinking.isNullOrBlank()) {
                ThinkingPanel(m.thinking, streaming = m.streaming && m.content.isBlank())
                Spacer(Modifier.height(6.dp))
            }
            Surface(
                shape = ClawCorners.bubbleAssistant,
                color = extras.assistantBubble,
                tonalElevation = 0.dp,
            ) {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        if (m.content.isBlank() && m.streaming) {
                            TypingDots()
                        } else {
                            MarkdownText(
                                text = m.content,
                                color = extras.assistantBubbleContent,
                                accent = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                        if (!m.error.isNullOrBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = m.error,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
            if (!m.streaming && m.content.isNotBlank()) {
                Row(
                    Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ActionChip(icon = Icons.Rounded.Refresh, label = "重新生成", onClick = onRegenerate)
                }
            }
            // Follow-up suggestions sit below the regenerate row and only
            // render on the latest assistant turn — older messages keep
            // their chips invisible so scrolling back doesn't get noisy.
            if (showFollowUps && !m.streaming && m.followUps.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                FollowUpChipsRow(
                    items = m.followUps,
                    onPick = onPickFollowUp,
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun FollowUpChipsRow(
    items: List<com.clawgui.ng.data.FollowUp>,
    onPick: (String) -> Unit,
) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items.forEach { fu ->
            FollowUpChip(label = fu.label, onClick = { onPick(fu.prompt) })
        }
    }
}

@Composable
private fun FollowUpChip(label: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun ThinkingPanel(text: String, streaming: Boolean) {
    var open by remember { mutableStateOf(false) }
    val extras = ClawTheme.extras
    val rotation by animateFloatAsState(targetValue = if (open) 180f else 0f, label = "rot")
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = extras.thinkingChip,
        modifier = Modifier
            .wrapContentHeight()
            .clickable { open = !open },
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.AutoAwesome,
                    contentDescription = null,
                    tint = extras.thinkingChipContent,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (streaming) "思考中…" else "思考过程",
                    style = MaterialTheme.typography.labelLarge,
                    color = extras.thinkingChipContent,
                )
                Spacer(Modifier.weight(1f))
                Icon(
                    imageVector = Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = extras.thinkingChipContent,
                    modifier = Modifier.size(16.dp).rotate(rotation),
                )
            }
            AnimatedVisibility(
                visible = open,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = extras.thinkingChipContent,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun TypingDots() {
    val transition = rememberInfiniteTransition(label = "dots")
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val anim by transition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(600, delayMillis = i * 120),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "alpha$i",
            )
            Box(
                Modifier
                    .padding(end = 4.dp)
                    .size(7.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = anim)),
            )
        }
    }
}

@Composable
private fun ActionChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(4.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SystemNote(m: ChatMessage) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 32.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            shape = RoundedCornerShape(50),
        ) {
            Text(
                text = m.content,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}
