package com.clawgui.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.clawgui.android.ui.ChatMessage

@Composable
fun ChatScreen(
    shizukuAvailable: Boolean,
    isExecuting: Boolean,
    isReadOnly: Boolean,
    canStopActive: Boolean,
    messages: List<ChatMessage>,
    onExecute: (String) -> Unit,
    onStop: () -> Unit,
) {
    var input by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue())
    }
    val isDarkTheme = isSystemInDarkTheme()
    val listState = rememberLazyListState()
    val canSend = input.text.isNotBlank() && shizukuAvailable && !isExecuting
    val listBottomPadding = if (isReadOnly) 12.dp else 110.dp

    fun submitInput() {
        val trimmed = input.text.trim()
        if (trimmed.isBlank()) return
        onExecute(trimmed)
        input = TextFieldValue()
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!shizukuAvailable) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.72f),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(RoundedCornerShape(99.dp))
                                .background(MaterialTheme.colorScheme.error),
                        )
                        Text(
                            text = "Shizuku 未连接",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            } else {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f),
                ) {
                    Text(
                        text = "完整控制已启用",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }

            if (isReadOnly) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = if (canStopActive) {
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.88f)
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f)
                    },
                ) {
                    Text(
                        text = if (canStopActive) {
                            "外部对话执行中，此处不可发送消息"
                        } else {
                            "来自外部 channel 的对话，仅可查看"
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        color = if (canStopActive) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        },
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(top = 12.dp, bottom = listBottomPadding),
            ) {
                if (messages.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillParentMaxWidth()
                                .padding(top = 92.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                "///",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.headlineMedium,
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "告诉 AI 你想做什么",
                                color = MaterialTheme.colorScheme.onBackground,
                                style = MaterialTheme.typography.titleMedium,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "试试 \"打开微信 发消息给小王\"",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
                items(messages, key = { it.id }) { msg ->
                    MessageBubble(msg)
                }
                if (isExecuting) {
                    item {
                        Row(
                            modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                            )
                            Text(
                                "正在执行…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            when {
                isReadOnly && canStopActive -> Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth(),
                ) {
                    ReadOnlyFooter(canStopActive = true, onStop = onStop)
                }
                isReadOnly -> Unit
                else -> Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(start = 16.dp, top = 22.dp, end = 16.dp, bottom = 32.dp),
                    shape = RoundedCornerShape(26.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    tonalElevation = 0.dp,
                    shadowElevation = 16.dp,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (isDarkTheme) {
                                    Modifier.border(
                                        width = 1.dp,
                                        color = Color.White.copy(alpha = 0.12f),
                                        shape = RoundedCornerShape(26.dp),
                                    )
                                } else {
                                    Modifier
                                }
                            ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 14.dp, top = 4.dp, end = 6.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            BasicTextField(
                                value = input,
                                onValueChange = { input = it },
                                modifier = Modifier.weight(1f),
                                enabled = !isExecuting,
                                maxLines = 4,
                                textStyle = MaterialTheme.typography.bodyMedium.merge(
                                    TextStyle(color = MaterialTheme.colorScheme.onSurface)
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                    imeAction = ImeAction.Send,
                                ),
                                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                                    onSend = { submitInput() },
                                ),
                                decorationBox = { innerTextField ->
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 8.dp, vertical = 10.dp),
                                        contentAlignment = Alignment.CenterStart,
                                    ) {
                                        if (input.text.isEmpty()) {
                                            Text(
                                                "输入指令…",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f),
                                            )
                                        }
                                        innerTextField()
                                    }
                                },
                            )
                            if (isExecuting) {
                                FilledTonalButton(
                                    onClick = onStop,
                                    shape = RoundedCornerShape(18.dp),
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.96f),
                                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                    ),
                                    modifier = Modifier.height(40.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                ) {
                                    Text("停止")
                                }
                            } else {
                                Button(
                                    onClick = ::submitInput,
                                    enabled = canSend,
                                    modifier = Modifier.size(40.dp),
                                    contentPadding = PaddingValues(0.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.96f),
                                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
                                    ),
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.ArrowUpward,
                                        contentDescription = "发送",
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadOnlyFooter(canStopActive: Boolean, onStop: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        shape = RoundedCornerShape(18.dp),
        color = if (canStopActive) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = if (canStopActive) "外部对话执行中,此处不可发送消息"
                    else "来自外部 channel 的对话,仅可查看",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = if (canStopActive) MaterialTheme.colorScheme.onErrorContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (canStopActive) {
                Button(
                    onClick = onStop,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("停止当前任务")
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(msg: ChatMessage) {
    when (msg.role) {
        "user" -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Surface(
                shape = RoundedCornerShape(22.dp, 8.dp, 22.dp, 22.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.widthIn(max = 300.dp),
            ) {
                Text(
                    text = msg.content,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        "step" -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(vertical = 1.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Text(
                    text = msg.content,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        else -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start,
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp, 22.dp, 22.dp, 22.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.widthIn(max = 300.dp),
            ) {
                Text(
                    text = msg.content,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}
