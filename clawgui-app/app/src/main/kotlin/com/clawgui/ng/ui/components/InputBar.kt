package com.clawgui.ng.ui.components

import androidx.compose.animation.AnimatedContent
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.clawgui.ng.ui.theme.ClawCorners
import com.clawgui.ng.ui.theme.ClawTheme

/**
 * Doubao-style multi-function composer.
 *
 *   ┌─────────────────────────────────────────────────┐
 *   │  ⊕   │ placeholder text…           │  📷 🎤  │
 *   └─────────────────────────────────────────────────┘
 *      attach    text field                  voice/cam
 *   ─── feature pills row ──
 *      [Model] [Web] [Vision]                  [send/stop]
 */
@Composable
fun ChatInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    isExecuting: Boolean,
    modelLabel: String,
    guiMode: Boolean,
    onGuiModeChange: (Boolean) -> Unit,
    onAttach: () -> Unit,
    onVoice: () -> Unit,
    onCamera: () -> Unit,
    onModel: () -> Unit,
    voiceState: com.clawgui.ng.ui.vm.ChatViewModel.VoiceState = com.clawgui.ng.ui.vm.ChatViewModel.VoiceState.IDLE,
    modifier: Modifier = Modifier,
) {
    val canSend = value.isNotBlank() && !isExecuting
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 0.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                shape = ClawCorners.inputPill,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                tonalElevation = 0.dp,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                ) {
                    IconCircleButton(Icons.Rounded.Add, "附件", onClick = onAttach, tonal = false)
                    Spacer(Modifier.width(2.dp))
                    Box(Modifier.weight(1f)) {
                        BasicTextField(
                            value = value,
                            onValueChange = onValueChange,
                            textStyle = LocalTextStyle.current.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = MaterialTheme.typography.bodyLarge.fontSize,
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 44.dp, max = 160.dp)
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            maxLines = 6,
                        )
                    }
                    Spacer(Modifier.width(2.dp))
                    if (value.isBlank() && !isExecuting) {
                        IconCircleButton(Icons.Rounded.CameraAlt, "拍照", onClick = onCamera, tonal = false)
                        when (voiceState) {
                            com.clawgui.ng.ui.vm.ChatViewModel.VoiceState.RECORDING -> {
                                IconCircleButton(
                                    Icons.Rounded.GraphicEq,
                                    "正在录音…点击结束",
                                    onClick = onVoice,
                                    tonal = true,
                                )
                            }
                            com.clawgui.ng.ui.vm.ChatViewModel.VoiceState.TRANSCRIBING -> {
                                Box(
                                    modifier = Modifier.size(40.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    androidx.compose.material3.CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                    )
                                }
                            }
                            else -> {
                                IconCircleButton(Icons.Rounded.Mic, "语音", onClick = onVoice, tonal = false)
                            }
                        }
                    }
                    SendButton(canSend, isExecuting, onSend, onStop)
                }
            }

            // Feature pill row — model picker + GUI mode toggle
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 4.dp),
            ) {
                FeaturePill(modelLabel, primary = true, onClick = onModel)
                GuiTogglePill(active = guiMode, onClick = { onGuiModeChange(!guiMode) })
            }
        }
    }
}

@Composable
private fun GuiTogglePill(active: Boolean, onClick: () -> Unit) {
    val extras = ClawTheme.extras
    val bg = if (active) extras.gradientStart else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (active) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        shape = ClawCorners.capsule,
        color = bg,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(
                "操作手机",
                style = MaterialTheme.typography.labelMedium,
                color = fg,
            )
            if (active) {
                Spacer(Modifier.width(4.dp))
                Text("·开", style = MaterialTheme.typography.labelMedium, color = fg)
            }
        }
    }
}

@Composable
private fun SendButton(
    canSend: Boolean,
    isExecuting: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val extras = ClawTheme.extras
    val bg = when {
        isExecuting -> MaterialTheme.colorScheme.errorContainer
        canSend -> extras.gradientStart
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val fg = when {
        isExecuting -> MaterialTheme.colorScheme.onErrorContainer
        canSend -> Color.White
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        color = bg,
        shape = CircleShape,
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(enabled = canSend || isExecuting) {
                if (isExecuting) onStop() else onSend()
            },
    ) {
        AnimatedContent(
            targetState = isExecuting,
            transitionSpec = {
                (fadeIn() + scaleIn(initialScale = 0.7f)) togetherWith
                        (fadeOut() + scaleOut(targetScale = 0.7f))
            },
            label = "send",
        ) { executing ->
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (executing) Icons.Rounded.Stop else Icons.Rounded.ArrowUpward,
                    contentDescription = if (executing) "停止" else "发送",
                    tint = fg,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun IconCircleButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    tonal: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (tonal) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun FeaturePill(label: String, primary: Boolean, onClick: () -> Unit) {
    val bg = if (primary) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (primary) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        shape = ClawCorners.capsule,
        color = bg,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = fg,
            )
        }
    }
}
