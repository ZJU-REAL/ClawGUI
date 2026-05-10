package com.clawgui.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.clawgui.android.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 外部 channel 收件流 —— 读 `workspace/external_inbox.jsonl`,倒序展示。
 * 点击一条跳到对应的 session(`feishu:<chatId>` 形式)并切到 ChatScreen。
 */
@Composable
fun InboxScreen(onPicked: (String) -> Unit) {
    val app = App.getInstance()
    val version by app.externalInbox.version.collectAsState(initial = 0L)

    var entries by remember { mutableStateOf<List<Map<String, Any?>>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(version) {
        loading = true
        entries = withContext(Dispatchers.IO) {
            try { app.externalInbox.readRecent(200).asReversed() } catch (_: Exception) { emptyList() }
        }
        loading = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            entries.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            "暂无外部消息",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "飞书或其他外部 channel 的消息会显示在这里。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.26f),
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                "外部会话",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                "这里展示来自外部 channel 的最近消息，点开后会跳转到对应会话。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                items(
                    entries,
                    key = { (it["ts_ms"] as? Number)?.toLong()?.toString() + (it["content"] ?: "") },
                ) { entry ->
                    InboxRow(
                        entry = entry,
                        onClick = {
                            val channel = entry["channel"] as? String ?: return@InboxRow
                            val chatId = entry["chat_id"] as? String ?: return@InboxRow
                            val sessionKey = "$channel:$chatId"
                            if (app.switchSession(sessionKey)) onPicked(sessionKey)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun InboxRow(
    entry: Map<String, Any?>,
    onClick: () -> Unit,
) {
    val direction = entry["direction"] as? String ?: "?"
    val channel = entry["channel"] as? String ?: "?"
    val chatId = (entry["chat_id"] as? String).orEmpty()
    val content = (entry["content"] as? String).orEmpty()
    val tsMs = (entry["ts_ms"] as? Number)?.toLong() ?: 0L

    val isIn = direction == "in"
    val directionLabel = if (isIn) "收到" else "发出"
    val directionColor = if (isIn) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.tertiary
    }
    val directionContainer = if (isIn) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
    } else {
        MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.72f)
    }
    val title = when (channel.lowercase(Locale.getDefault())) {
        "feishu" -> "飞书会话"
        else -> "${channel.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }} 会话"
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = chatId.take(42),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = directionContainer,
                ) {
                    Text(
                        text = directionLabel,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = directionColor,
                    )
                }
            }

            Text(
                text = content.ifBlank { "(无内容)" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = channel.uppercase(Locale.getDefault()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "·",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = formatTs(tsMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "点按查看",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun formatTs(ms: Long): String =
    if (ms <= 0L) "—"
    else SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
