package com.clawgui.ng.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.clawgui.ng.runtime.RuntimeContainer
import com.clawgui.ng.runtime.trace.TraceMeta
import com.clawgui.ng.runtime.trace.TraceStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Trace list page (settings → 运行记录 → 全部).
 *
 * Renders newest first. Tap → detail; long-press not bound to anything yet.
 */
@Composable
fun TraceListScreen(onOpen: (TraceMeta) -> Unit, onClose: () -> Unit) {
    // Use the cached snapshot from the warmer so the first frame already
    // has data — no flash of empty state, no jank from scanning disk on
    // the composition path.
    val runs = TracesCache.runs.collectAsStateWithLifecycle().value
    LaunchedEffect(Unit) { TracesCache.refresh() }
    var confirmClear by remember { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(Modifier.fillMaxSize()) {
            TraceHeader(title = "运行轨迹", onBack = onClose, action = {
                if (runs.isNotEmpty()) {
                    TextButton(onClick = { confirmClear = true }) { Text("清空") }
                }
            })
            if (runs.isEmpty()) {
                EmptyState("还没有运行轨迹\n操作手机后会自动留下截图 + 思考 + 动作的完整轨迹。")
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(runs, key = { it.runId }) { meta ->
                        TraceListItem(meta, onClick = { onOpen(meta) })
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空所有运行记录?") },
            text = { Text("会删除所有保存在本机的截图与轨迹文件,无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    RuntimeContainer.traces.clearAll()
                    TracesCache.refresh()
                    confirmClear = false
                }) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
        )
    }
}

@Composable
fun TraceDetailScreen(runId: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val meta by produceState<TraceMeta?>(initialValue = null, runId) {
        value = withContext(Dispatchers.IO) {
            RuntimeContainer.traces.readMeta(RuntimeContainer.traces.runDir(runId))
        }
    }
    val steps by produceState<List<TraceStep>>(initialValue = emptyList(), runId) {
        value = withContext(Dispatchers.IO) { RuntimeContainer.traces.steps(runId) }
    }
    var sharing by remember { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(Modifier.fillMaxSize()) {
            TraceHeader(title = "轨迹详情", onBack = onBack, action = {
                Row {
                    TextButton(
                        enabled = !sharing,
                        onClick = {
                            sharing = true
                            shareRun(ctx, runId) { sharing = false }
                        },
                    ) {
                        Icon(Icons.Rounded.Share, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (sharing) "打包中…" else "导出")
                    }
                }
            })

            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { meta?.let { TraceMetaHeader(it) } }
                items(steps, key = { it.index }) { step ->
                    StepCard(runId, step)
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun TraceHeader(
    title: String,
    onBack: () -> Unit,
    action: @Composable () -> Unit = {},
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.ArrowBack, "返回", modifier = Modifier.size(20.dp)) }
        Spacer(Modifier.width(12.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
        action()
    }
}

@Composable
private fun EmptyState(msg: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            msg,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TraceListItem(meta: TraceMeta, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(14.dp),
        ) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(
                        if (meta.success) com.clawgui.ng.ui.theme.ClawColors.Success
                        else MaterialTheme.colorScheme.outline,
                    )
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    meta.task.ifBlank { meta.runId },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "${formatTime(meta.startedAt)} · ${meta.steps} 步 · ${meta.model}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TraceMetaHeader(meta: TraceMeta) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(meta.task.ifBlank { "未命名任务" }, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "${formatTime(meta.startedAt)} → ${if (meta.endedAt > 0) formatTime(meta.endedAt) else "未完成"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "${meta.steps} 步 · ${meta.model} · ${if (meta.success) "✅ 成功" else "⚠️ 未成功"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (meta.finalMessage.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(meta.finalMessage, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun StepCard(runId: String, step: TraceStep) {
    var expanded by remember { mutableStateOf(false) }
    val shotFile = remember(runId, step.index) {
        RuntimeContainer.traces.screenshot(runId, step.index)
    }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            if (shotFile != null) {
                AsyncImage(
                    model = shotFile,
                    contentDescription = null,
                    modifier = Modifier
                        .width(72.dp)
                        .heightIn(min = 96.dp, max = 144.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${step.index}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        step.actionName,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (!step.resultOk) {
                        Spacer(Modifier.width(6.dp))
                        Text("⚠", color = MaterialTheme.colorScheme.error)
                    }
                }
                Text(
                    step.actionJson,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) 6 else 2,
                )
                if (expanded && step.thinking.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surface,
                    ) {
                        Text(
                            step.thinking,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                }
                if (expanded && !step.resultMessage.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        step.resultMessage,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (step.resultOk) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String =
    if (ms <= 0) "—"
    else SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ms))

/**
 * Process-wide cache of the trace list so opening the page doesn't have to
 * wait on `listFiles` + JSON parsing every time. The TracesPage entry tile
 * calls `refresh()` ahead of time so by the time the user taps "查看" the
 * data is usually already in memory.
 */
internal object TracesCache {
    private val _runs = kotlinx.coroutines.flow.MutableStateFlow<List<TraceMeta>>(emptyList())
    val runs: kotlinx.coroutines.flow.StateFlow<List<TraceMeta>> = _runs

    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )
    @Volatile private var inflight: kotlinx.coroutines.Job? = null

    fun refresh() {
        if (inflight?.isActive == true) return
        inflight = scope.launch {
            runCatching { _runs.value = RuntimeContainer.traces.list() }
        }
    }
}

private fun shareRun(ctx: android.content.Context, runId: String, onDone: () -> Unit) {
    Thread {
        runCatching {
            val zip = RuntimeContainer.traces.packageZip(runId) ?: error("打包失败")
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", zip)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "ClawGUI 运行轨迹 · $runId")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(intent, "导出轨迹到…").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(chooser)
        }
        onDone()
    }.start()
}
