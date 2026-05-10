package com.clawgui.android.ui.screens

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.clawgui.android.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 左抽屉内容:
 *   新建对话 → 外部会话 → (分隔) → 历史会话列表 → (分隔) → 底部"ClawGUI · 设置 ›"
 *
 * - 会话列表只显示 ui:* 前缀(应用内),外部 feishu:* 走单独入口避免混淆
 * - 长按会话时,在当前条目上方弹出轻量操作卡片:重命名 / 删除
 * - onOpenInbox / onOpenSettings / onPicked / onNewSession 都由 MainActivity 注入,
 *   由上层统一负责"关抽屉 + 切 overlay"
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ClawDrawerContent(
    onNewSession: () -> Unit,
    onOpenInbox: () -> Unit,
    onOpenSettings: () -> Unit,
    onPickSession: (String) -> Unit,
) {
    val app = App.getInstance()
    val sessionsVersion by app.sessionsVersion.collectAsState(initial = 0L)
    val currentKey by app.currentSessionKey.collectAsState(initial = "")
    val isExecuting by app.isExecuting.collectAsState(initial = false)
    val isDarkTheme = isSystemInDarkTheme()
    val context = LocalContext.current

    var sessions by remember { mutableStateOf<List<Map<String, Any?>>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var rootSize by remember { mutableStateOf(IntSize.Zero) }
    var pendingMenu by remember { mutableStateOf<SessionActionMenuTarget?>(null) }
    var pendingRename by remember { mutableStateOf<Map<String, Any?>?>(null) }
    var pendingDelete by remember { mutableStateOf<Map<String, Any?>?>(null) }

    LaunchedEffect(sessionsVersion) {
        loading = true
        sessions = withContext(Dispatchers.IO) {
            try {
                app.sessions.listSessions().filter {
                    (it["key"] as? String)?.startsWith("ui:") == true
                }
            } catch (_: Exception) {
                emptyList()
            }
        }
        loading = false
        pendingMenu = null
    }

    ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.surface,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { rootSize = it.size },
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Spacer(Modifier.height(16.dp))

                FilledTonalButton(
                    onClick = onNewSession,
                    enabled = !isExecuting,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("新建对话")
                }

                Spacer(Modifier.height(12.dp))

                NavigationDrawerItem(
                    icon = { Icon(Icons.Outlined.Mail, contentDescription = null) },
                    label = { Text("外部会话") },
                    selected = false,
                    onClick = onOpenInbox,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )

                Divider(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )

                Text(
                    "历史会话",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 4.dp),
                )

                Box(modifier = Modifier.weight(1f)) {
                    when {
                        loading -> Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }

                        sessions.isEmpty() -> Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "暂无会话",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }

                        else -> LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(sessions, key = { (it["key"] as? String) ?: System.nanoTime().toString() }) { session ->
                                val key = session["key"] as? String ?: return@items
                                val updatedAt = formatDrawerUpdatedAt(session["updated_at"] as? String)
                                val displayName = (session["display_name"] as? String)?.takeIf { it.isNotBlank() }
                                    ?: updatedAt
                                    ?: key
                                val selected = key == currentKey

                                SessionDrawerRow(
                                    displayName = displayName,
                                    subtitle = updatedAt?.let { "最近更新 $it" },
                                    selected = selected,
                                    onClick = {
                                        pendingMenu = null
                                        if (app.switchSession(key)) {
                                            onPickSession(key)
                                        } else {
                                            Toast.makeText(context, "打开会话失败", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    onLongClick = { bounds ->
                                        pendingMenu = SessionActionMenuTarget(
                                            session = session,
                                            anchorBounds = bounds,
                                        )
                                    },
                                )
                            }
                        }
                    }
                }

                Divider()

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenSettings() }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(
                                if (isDarkTheme) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f)
                                } else {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
                                }
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.AutoAwesome,
                            contentDescription = null,
                            tint = if (isDarkTheme) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "ClawGUI",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            "版本 0.1.0 · 设置",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        imageVector = Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            pendingMenu?.let { target ->
                SessionActionMenu(
                    target = target,
                    rootSize = rootSize,
                    onDismiss = { pendingMenu = null },
                    onRename = {
                        pendingMenu = null
                        pendingRename = target.session
                    },
                    onDelete = {
                        pendingMenu = null
                        pendingDelete = target.session
                    },
                )
            }
        }
    }

    pendingRename?.let { target ->
        val targetKey = target["key"] as? String
        val currentName = (target["display_name"] as? String).orEmpty()
        RenameSessionDialog(
            currentName = currentName,
            onDismiss = { pendingRename = null },
            onConfirm = { newName ->
                pendingRename = null
                if (targetKey != null) {
                    if (!app.renameSession(targetKey, newName)) {
                        Toast.makeText(context, "改名失败", Toast.LENGTH_SHORT).show()
                    }
                }
            },
        )
    }

    pendingDelete?.let { target ->
        val targetKey = target["key"] as? String
        val targetName = (target["display_name"] as? String)
            ?: formatDrawerUpdatedAt(target["updated_at"] as? String)
            ?: targetKey
            ?: "?"
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除会话") },
            text = { Text("确定删除「$targetName」?该会话的全部历史都会从手机中移除,无法恢复。") },
            confirmButton = {
                TextButton(
                    enabled = !isExecuting,
                    onClick = {
                        pendingDelete = null
                        if (targetKey != null) {
                            if (!app.deleteSession(targetKey)) {
                                Toast.makeText(context, "任务执行中,请先停止", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionDrawerRow(
    displayName: String,
    subtitle: String?,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (Rect) -> Unit,
) {
    val bg = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
    }
    val fg = if (selected) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val subFg = if (selected) {
        MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.72f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    var bounds by remember { mutableStateOf(Rect.Zero) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { bounds = it.boundsInRoot() }
            .clip(MaterialTheme.shapes.medium)
            .background(bg)
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    if (bounds != Rect.Zero) {
                        onLongClick(bounds)
                    }
                },
            )
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (selected) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                    ) {
                        Text(
                            text = "当前",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            subtitle?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = subFg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SessionActionMenu(
    target: SessionActionMenuTarget,
    rootSize: IntSize,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val horizontalMarginPx = with(density) { 12.dp.roundToPx() }
    val verticalGapPx = with(density) { 8.dp.roundToPx() }
    val maxX = (rootSize.width - menuSize.width - horizontalMarginPx).coerceAtLeast(horizontalMarginPx)
    val maxY = (rootSize.height - menuSize.height - horizontalMarginPx).coerceAtLeast(horizontalMarginPx)
    val offsetX = target.anchorBounds.left.roundToInt().coerceIn(horizontalMarginPx, maxX)
    val preferredY = target.anchorBounds.top.roundToInt() - menuSize.height - verticalGapPx
    val offsetY = preferredY.coerceIn(horizontalMarginPx, maxY)
    val targetKey = target.session["key"] as? String
    val targetName = (target.session["display_name"] as? String)?.takeIf { it.isNotBlank() }
        ?: formatDrawerUpdatedAt(target.session["updated_at"] as? String)
        ?: targetKey
        ?: "?"
    val updatedAt = formatDrawerUpdatedAt(target.session["updated_at"] as? String)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(targetKey) {
                detectTapGestures(onTap = { onDismiss() })
            },
    ) {
        Surface(
            modifier = Modifier
                .offset { IntOffset(offsetX, offsetY) }
                .widthIn(min = 196.dp, max = 236.dp)
                .onGloballyPositioned { menuSize = it.size },
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
            tonalElevation = 4.dp,
            shadowElevation = 12.dp,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    tonalElevation = 0.dp,
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            text = "会话操作",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = targetName,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        updatedAt?.let {
                            Text(
                                text = "最近更新 $it",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                SessionActionItem(
                    icon = {
                        Icon(
                            Icons.Outlined.Edit,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    label = "重命名",
                    description = "修改这条会话的显示名称",
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    iconContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    onClick = onRename,
                )
                Spacer(Modifier.height(6.dp))
                SessionActionItem(
                    icon = {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    },
                    label = "删除",
                    description = "移除整段历史记录，无法恢复",
                    labelColor = MaterialTheme.colorScheme.error,
                    descriptionColor = MaterialTheme.colorScheme.error.copy(alpha = 0.82f),
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
                    iconContainerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                    onClick = onDelete,
                )
            }
        }
    }
}

@Composable
private fun SessionActionItem(
    icon: @Composable () -> Unit,
    label: String,
    description: String,
    labelColor: Color = MaterialTheme.colorScheme.onSurface,
    descriptionColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
    iconContainerColor: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(containerColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(iconContainerColor),
            contentAlignment = Alignment.Center,
        ) {
            icon()
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = labelColor,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = descriptionColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private data class SessionActionMenuTarget(
    val session: Map<String, Any?>,
    val anchorBounds: Rect,
)

private fun formatDrawerUpdatedAt(iso: String?): String? {
    if (iso.isNullOrBlank()) return null
    return iso.take(16).replace("T", " ")
}
