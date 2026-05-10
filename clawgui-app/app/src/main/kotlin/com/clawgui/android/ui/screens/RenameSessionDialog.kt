package com.clawgui.android.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

private const val MAX_DISPLAY_NAME_LEN = 20

/**
 * 会话改名对话框。
 * - 初值 = 当前标题,空字符串保存 = 清 metadata 回到自动派生(第一条 user 消息)
 * - 硬截 20 字符,单行
 */
@Composable
fun RenameSessionDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(currentName.take(MAX_DISPLAY_NAME_LEN)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名会话") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(MAX_DISPLAY_NAME_LEN) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(
                        "留空恢复默认",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                supportingText = {
                    Text(
                        "${text.length} / $MAX_DISPLAY_NAME_LEN",
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
