package com.clawgui.ng.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clawgui.ng.data.InboxEntry
import com.clawgui.ng.data.SessionSummary
import com.clawgui.ng.runtime.RuntimeContainer
import com.clawgui.ng.ui.theme.ClawCorners
import com.clawgui.ng.ui.theme.ClawTheme
import com.clawgui.ng.ui.vm.ChatViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SessionDrawer(
    vm: ChatViewModel,
    onClose: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenInboxEntry: (InboxEntry) -> Unit,
) {
    val sessions by vm.sessionsList.collectAsStateWithLifecycle()
    val current by vm.currentSessionKey.collectAsStateWithLifecycle()
    val inbox by RuntimeContainer.inbox.entries.collectAsStateWithLifecycle()

    var query by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<SessionSummary?>(null) }
    var deleteTarget by remember { mutableStateOf<SessionSummary?>(null) }

    val pinned = sessions.filter { it.pinned && it.title.contains(query, ignoreCase = true) }
    val recent = sessions.filter { !it.pinned && it.title.contains(query, ignoreCase = true) }

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier
            .fillMaxHeight()
            .width(320.dp),
    ) {
        Column(
            Modifier
                .fillMaxHeight()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            DrawerHeader()
            Spacer(Modifier.height(8.dp))
            SearchField(query) { query = it }
            Spacer(Modifier.height(8.dp))
            NewChatRow {
                vm.newSession(); onClose()
            }
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                // Inbox section is always rendered so the user can see external
                // channels are wired up even before any message arrives.
                item {
                    val unread = inbox.count { it.unread }
                    SectionHeader("收件箱", count = unread.takeIf { it > 0 })
                }
                if (inbox.isEmpty()) {
                    item { EmptyInboxRow() }
                } else {
                    // Drawer 只显示最近 5 条;更多请到对话历史 / 收件箱页查看。
                    val shown = inbox.take(5)
                    items(shown, key = { it.sessionKey + ":" + it.receivedAt }) { e ->
                        InboxRow(e) {
                            onOpenInboxEntry(e); onClose()
                        }
                    }
                    if (inbox.size > shown.size) {
                        item {
                            Text(
                                "还有 ${inbox.size - shown.size} 条…",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
                if (pinned.isNotEmpty()) {
                    item { SectionHeader("置顶") }
                    items(pinned, key = { it.key }) { s ->
                        SessionRow(
                            s, selected = s.key == current,
                            onClick = { vm.selectSession(s.key); onClose() },
                            onRename = { renameTarget = s },
                            onDelete = { deleteTarget = s },
                            onTogglePin = { vm.togglePin(s.key) },
                        )
                    }
                }
                if (recent.isNotEmpty()) {
                    item { SectionHeader("最近") }
                    items(recent, key = { it.key }) { s ->
                        SessionRow(
                            s, selected = s.key == current,
                            onClick = { vm.selectSession(s.key); onClose() },
                            onRename = { renameTarget = s },
                            onDelete = { deleteTarget = s },
                            onTogglePin = { vm.togglePin(s.key) },
                        )
                    }
                }
                if (sessions.isEmpty()) {
                    item { EmptyDrawerHint() }
                }
            }
            DrawerFooter(onSettings = { onOpenSettings(); onClose() })
        }
    }

    renameTarget?.let { target ->
        var input by remember(target.key) { mutableStateOf(target.title) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名") },
            text = {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.renameSession(target.key, input.trim().ifBlank { target.title })
                    renameTarget = null
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("取消") } },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除对话") },
            text = { Text("确定删除「${target.title}」吗?这一动作不可撤销。") },
            confirmButton = {
                TextButton(onClick = { vm.deleteSession(target.key); deleteTarget = null }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun DrawerHeader() {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(ClawTheme.extras.gradientStart),
            contentAlignment = Alignment.Center,
        ) {
            Text("C", color = Color.White, style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text("ClawGUI", style = MaterialTheme.typography.titleLarge)
            Text("Phone Agent · NG", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit) {
    Surface(
        shape = ClawCorners.capsule,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Icon(Icons.Rounded.Search, null, modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                if (value.isEmpty()) {
                    Text("搜索对话",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                }
                BasicTextField(
                    value = value,
                    onValueChange = onChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun NewChatRow(onClick: () -> Unit) {
    Surface(
        shape = ClawCorners.card,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
        ) {
            Icon(Icons.Rounded.AddCircle, null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.width(10.dp))
            Text("新建对话", style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
private fun SectionHeader(label: String, count: Int? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (count != null && count > 0) {
            Spacer(Modifier.width(6.dp))
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = CircleShape,
            ) {
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
        }
    }
}

@Composable
private fun SessionRow(
    s: SessionSummary,
    selected: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onTogglePin: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val bg = if (selected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
    else Color.Transparent
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = bg,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (s.pinned) {
                        Icon(Icons.Rounded.PushPin, null, modifier = Modifier.size(13.dp),
                            tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        text = s.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                }
                if (s.lastMessagePreview.isNotEmpty()) {
                    Text(
                        text = s.lastMessagePreview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            Text(
                text = formatRelative(s.lastUpdatedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box {
                Icon(
                    imageVector = Icons.Rounded.Edit,
                    contentDescription = "更多",
                    modifier = Modifier
                        .size(18.dp)
                        .padding(start = 8.dp)
                        .clickable { menuOpen = true },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(if (s.pinned) "取消置顶" else "置顶") },
                        onClick = { menuOpen = false; onTogglePin() },
                        leadingIcon = { Icon(Icons.Rounded.PushPin, null) },
                    )
                    DropdownMenuItem(
                        text = { Text("重命名") },
                        onClick = { menuOpen = false; onRename() },
                        leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                    )
                    DropdownMenuItem(
                        text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                        onClick = { menuOpen = false; onDelete() },
                        leadingIcon = { Icon(Icons.Rounded.Delete, null,
                            tint = MaterialTheme.colorScheme.error) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyInboxRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "暂无外部消息",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun InboxRow(e: InboxEntry, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (e.unread) MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f)
        else Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Inbox, null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(e.sender, style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                Text(e.preview, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            if (e.unread) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
    }
}

@Composable
private fun EmptyDrawerHint() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("还没有对话", style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(4.dp))
        Text("点击上方按钮开始一个新对话",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DrawerFooter(onSettings: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clickable(onClick = onSettings),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Icon(Icons.Rounded.Settings, null,
                tint = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.width(10.dp))
            Text("设置", style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

private fun formatRelative(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val delta = now - timestamp
    val minute = 60_000L
    val hour = 60 * minute
    val day = 24 * hour
    return when {
        delta < minute -> "刚刚"
        delta < hour -> "${delta / minute} 分钟前"
        delta < day -> "${delta / hour} 小时前"
        delta < 7 * day -> "${delta / day} 天前"
        else -> SimpleDateFormat("M/d", Locale.getDefault()).format(Date(timestamp))
    }
}
