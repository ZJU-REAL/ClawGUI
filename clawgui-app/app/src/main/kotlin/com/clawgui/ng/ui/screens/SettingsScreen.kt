package com.clawgui.ng.ui.screens

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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clawgui.ng.data.ProviderProfile
import com.clawgui.ng.data.ProviderRole
import com.clawgui.ng.runtime.RuntimeContainer
import com.clawgui.ng.data.repo.Appearance
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.launch

/**
 * Set to true by TracesPage when it has navigated into its list/detail
 * sub-views — SettingsScreen then suppresses its own toolbar so we don't
 * stack two title bars.
 */
private val tracesHasOwnHeader = androidx.compose.runtime.mutableStateOf(false)

/**
 * Run `block` every time the host Activity reaches RESUMED — used to refresh
 * status cards (IME / battery / Shizuku) after the user returns from a
 * system settings page without making them tap "重新检测".
 */
@Composable
private fun OnResume(block: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) block()
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
}

@Composable
fun SettingsScreen(onClose: () -> Unit) {
    var page by remember { mutableStateOf<SettingsPage>(SettingsPage.Home) }
    // Sub-screens that bring their own header (e.g. TraceListScreen + Detail
    // have list/export/back baked in) should suppress ours to avoid a double
    // title bar.
    val showOwnHeader = page is SettingsPage.Traces && tracesHasOwnHeader.value

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars),
        ) {
            if (!showOwnHeader) SettingsHeader(
                title = page.title,
                onBack = {
                    if (page is SettingsPage.Home) onClose()
                    else page = SettingsPage.Home
                },
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.navigationBars),
            ) {
                when (page) {
                    SettingsPage.Home -> SettingsHomePage(onNavigate = { page = it })
                    SettingsPage.AiModels -> AiModelsPage()
                    SettingsPage.Channels -> ChannelsPage()
                    SettingsPage.DeviceAuth -> DeviceAuthPage()
                    SettingsPage.Ime -> ImePage()
                    SettingsPage.Traces -> TracesPage()
                    SettingsPage.Performance -> PerformancePage()
                    SettingsPage.Notification -> NotificationPage()
                    SettingsPage.Overlay -> OverlayPage()
                    SettingsPage.Appearance -> AppearancePage()
                    SettingsPage.About -> AboutPage()
                }
            }
        }
    }
}

private sealed class SettingsPage(val title: String) {
    data object Home : SettingsPage("设置")
    data object AiModels : SettingsPage("AI 模型")
    data object Channels : SettingsPage("外部通道")
    data object DeviceAuth : SettingsPage("设备控制授权")
    data object Ime : SettingsPage("ClawGUI 输入法")
    data object Traces : SettingsPage("运行记录")
    data object Performance : SettingsPage("性能 · 截图压缩")
    data object Notification : SettingsPage("通知")
    data object Overlay : SettingsPage("悬浮面板")
    data object Appearance : SettingsPage("外观")
    data object About : SettingsPage("关于")
}

@Composable
private fun SettingsHeader(title: String, onBack: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.ArrowBack, "返回", modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall)
    }
}

@Composable
private fun SettingsHomePage(onNavigate: (SettingsPage) -> Unit) {
    val items = listOf(
        SettingsRowSpec("AI 模型", "大脑与视觉模型、API Key", Icons.Rounded.SmartToy, SettingsPage.AiModels),
        SettingsRowSpec("外部通道", "飞书等外部接入", Icons.Rounded.Hub, SettingsPage.Channels),
        SettingsRowSpec("设备控制授权", "无线调试 / Shizuku 二选一", Icons.Rounded.VerifiedUser, SettingsPage.DeviceAuth),
        SettingsRowSpec("ClawGUI 输入法", "允许 Agent 输入文字", Icons.Rounded.Keyboard, SettingsPage.Ime),
        SettingsRowSpec("性能 · 截图压缩", "调节上传给 VLM 的截图大小", Icons.Rounded.Speed, SettingsPage.Performance),
        SettingsRowSpec("通知", "Agent 执行时的通知与横幅", Icons.Rounded.Notifications, SettingsPage.Notification),
        SettingsRowSpec("悬浮面板", "执行任务时的浮动 Plan + Trace 卡片", Icons.Rounded.OpenInNew, SettingsPage.Overlay),
        SettingsRowSpec("运行记录", "保留每次执行轨迹", Icons.Rounded.Article, SettingsPage.Traces),
        SettingsRowSpec("外观", "主题与强调色", Icons.Rounded.Palette, SettingsPage.Appearance),
        SettingsRowSpec("关于", "版本、协议、项目", Icons.Rounded.Info, SettingsPage.About),
    )
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ProfileHeader()
        SettingsCardGroup(items.subList(0, 2), onNavigate)
        SettingsCardGroup(items.subList(2, 4), onNavigate)
        SettingsCardGroup(items.subList(4, items.size), onNavigate)
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun ProfileHeader() {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(16.dp),
        ) {
            Box(
                Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.AutoAwesome, null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text("ClawGUI", style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text("v0.2.0 · NG", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f))
            }
        }
    }
}

private data class SettingsRowSpec(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val target: SettingsPage,
)

@Composable
private fun SettingsCardGroup(rows: List<SettingsRowSpec>, onNavigate: (SettingsPage) -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(vertical = 4.dp)) {
            rows.forEachIndexed { i, row ->
                SettingsRow(row, onClick = { onNavigate(row.target) })
                if (i != rows.lastIndex) {
                    Spacer(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .padding(start = 60.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsRow(row: SettingsRowSpec, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(row.icon, null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(row.title, style = MaterialTheme.typography.titleMedium)
            Text(row.subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Rounded.ChevronRight, null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun AiModelsPage() {
    val providers by RuntimeContainer.settings.providers.collectAsStateWithLifecycle()
    val activeBrain by RuntimeContainer.settings.activeBrain.collectAsStateWithLifecycle()
    val activeVision by RuntimeContainer.settings.activeVision.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<ProviderProfile?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { SectionLabel("Brain · 大脑模型(用于对话)") }
        items(providers.filter { it.role == ProviderRole.BRAIN }) {
            ProviderRow(
                it,
                active = it.id == activeBrain,
                onActivate = { RuntimeContainer.settings.setActiveBrain(it.id) },
                onEdit = { editing = it },
            )
        }
        item { SectionLabel("Vision · 视觉模型(用于看屏幕)") }
        items(providers.filter { it.role == ProviderRole.VISION }) {
            ProviderRow(
                it,
                active = it.id == activeVision,
                onActivate = { RuntimeContainer.settings.setActiveVision(it.id) },
                onEdit = { editing = it },
            )
        }
        item { Spacer(Modifier.height(40.dp)) }
    }

    editing?.let { p ->
        ProviderEditDialog(
            profile = p,
            onDismiss = { editing = null },
            onSave = { key, base, model ->
                if (key != null) RuntimeContainer.settings.setProviderApiKey(p.id, key)
                if (base.isNotBlank()) RuntimeContainer.settings.setProviderBaseUrl(p.id, base)
                if (model.isNotBlank()) RuntimeContainer.settings.setProviderModel(p.id, model)
                editing = null
            },
        )
    }
}

@Composable
private fun ProviderRow(p: ProviderProfile, active: Boolean, onActivate: () -> Unit, onEdit: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = if (active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(14.dp),
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (active) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline)
                    .clickable(onClick = onActivate)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).clickable(onClick = onActivate)) {
                Text(p.displayName, style = MaterialTheme.typography.titleMedium)
                Text("${p.model} · ${p.baseUrl}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1)
            }
            Surface(
                shape = CircleShape,
                color = if (p.hasApiKey) MaterialTheme.colorScheme.tertiaryContainer
                else MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.clickable(onClick = onEdit),
            ) {
                Text(
                    if (p.hasApiKey) "编辑 Key" else "粘贴 Key",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (p.hasApiKey) MaterialTheme.colorScheme.onTertiaryContainer
                    else MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun ProviderEditDialog(
    profile: ProviderProfile,
    onDismiss: () -> Unit,
    onSave: (key: String?, base: String, model: String) -> Unit,
) {
    var apiKey by remember(profile.id) { mutableStateOf("") }
    var baseUrl by remember(profile.id) { mutableStateOf(profile.baseUrl) }
    var model by remember(profile.id) { mutableStateOf(profile.model) }
    var showKey by remember { mutableStateOf(false) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("配置 ${profile.displayName}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (profile.hasApiKey) "API Key 已保存(粘贴新值会覆盖)"
                    else "粘贴 API Key 后保存即可生效",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                androidx.compose.material3.OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it.trim() },
                    label = { Text("API Key") },
                    singleLine = true,
                    visualTransformation = if (showKey)
                        androidx.compose.ui.text.input.VisualTransformation.None
                    else
                        androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    trailingIcon = {
                        androidx.compose.material3.TextButton(
                            onClick = { showKey = !showKey }
                        ) { Text(if (showKey) "隐藏" else "显示") }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                androidx.compose.material3.OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                androidx.compose.material3.OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("Model") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                onSave(apiKey.takeIf { it.isNotBlank() }, baseUrl, model)
            }) { Text("保存") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun ChannelsPage() {
    val enabled by RuntimeContainer.settings.feishuEnabled.collectAsStateWithLifecycle()
    val appId by RuntimeContainer.settings.feishuAppId.collectAsStateWithLifecycle()
    val secretSet by RuntimeContainer.settings.feishuAppSecretSet.collectAsStateWithLifecycle()
    val botName by RuntimeContainer.settings.feishuBotName.collectAsStateWithLifecycle()
    val allowedIds by RuntimeContainer.settings.feishuAllowedOpenIds.collectAsStateWithLifecycle()
    val allowAll by RuntimeContainer.settings.feishuAllowAll.collectAsStateWithLifecycle()
    val autoReply by RuntimeContainer.settings.feishuAutoReply.collectAsStateWithLifecycle()
    val runAsGui by RuntimeContainer.settings.feishuRunAsGuiTask.collectAsStateWithLifecycle()
    val channelState by RuntimeContainer.feishu.state.collectAsStateWithLifecycle()
    val lastError by RuntimeContainer.feishu.lastError.collectAsStateWithLifecycle()
    val feishuLog by RuntimeContainer.feishu.log.collectAsStateWithLifecycle()

    Column(
        Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SectionLabel("飞书 Bot 连接状态")
        val (stateText, stateTone) = when (channelState) {
            com.clawgui.ng.runtime.feishu.FeishuChannel.State.Running -> "已连接 — 正在接收消息" to StatusTone.Ok
            com.clawgui.ng.runtime.feishu.FeishuChannel.State.Starting -> "正在连接…" to StatusTone.Warning
            com.clawgui.ng.runtime.feishu.FeishuChannel.State.Failed ->
                "连接失败:${lastError ?: "未知错误"}" to StatusTone.Error
            com.clawgui.ng.runtime.feishu.FeishuChannel.State.Stopped -> "已关闭" to StatusTone.Warning
        }
        StatusCard("Feishu Bot", stateText, stateTone)
        SettingsToggleRow(
            title = "启用 Feishu Bot",
            subtitle = "WebSocket 长连接接收消息;关闭即断开",
            checked = enabled,
            onCheckedChange = RuntimeContainer.settings::setFeishuEnabled,
        )

        SectionLabel("凭据")
        var appIdInput by remember(appId) { mutableStateOf(appId) }
        androidx.compose.material3.OutlinedTextField(
            value = appIdInput,
            onValueChange = { appIdInput = it.trim() },
            label = { Text("App ID") },
            placeholder = { Text("cli_xxxxxxxxxxxx") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        androidx.compose.material3.TextButton(
            onClick = { RuntimeContainer.settings.setFeishuAppId(appIdInput) },
            enabled = appIdInput != appId,
            modifier = Modifier.align(androidx.compose.ui.Alignment.End),
        ) { Text("保存 App ID") }

        var secretInput by remember { mutableStateOf("") }
        var showSecret by remember { mutableStateOf(false) }
        androidx.compose.material3.OutlinedTextField(
            value = secretInput,
            onValueChange = { secretInput = it.trim() },
            label = { Text(if (secretSet) "App Secret(已保存,粘贴新值会覆盖)" else "App Secret") },
            singleLine = true,
            visualTransformation = if (showSecret)
                androidx.compose.ui.text.input.VisualTransformation.None
            else
                androidx.compose.ui.text.input.PasswordVisualTransformation(),
            trailingIcon = {
                androidx.compose.material3.TextButton(onClick = { showSecret = !showSecret }) {
                    Text(if (showSecret) "隐藏" else "显示")
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        androidx.compose.material3.TextButton(
            onClick = {
                if (secretInput.isNotBlank()) {
                    RuntimeContainer.settings.setFeishuAppSecret(secretInput)
                    secretInput = ""
                }
            },
            enabled = secretInput.isNotBlank(),
            modifier = Modifier.align(androidx.compose.ui.Alignment.End),
        ) { Text("保存 App Secret") }

        var botInput by remember(botName) { mutableStateOf(botName) }
        androidx.compose.material3.OutlinedTextField(
            value = botInput,
            onValueChange = { botInput = it },
            label = { Text("Bot 名称") },
            placeholder = { Text("ClawGUI") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        androidx.compose.material3.TextButton(
            onClick = { RuntimeContainer.settings.setFeishuBotName(botInput) },
            enabled = botInput != botName,
            modifier = Modifier.align(androidx.compose.ui.Alignment.End),
        ) { Text("保存 Bot 名称") }

        SectionLabel("白名单")
        SettingsToggleRow(
            title = "对所有用户开放",
            subtitle = "关闭后只接收下方 open_id 列表里成员的消息(更安全)",
            checked = allowAll,
            onCheckedChange = RuntimeContainer.settings::setFeishuAllowAll,
        )
        var idsInput by remember(allowedIds) { mutableStateOf(allowedIds) }
        androidx.compose.material3.OutlinedTextField(
            value = idsInput,
            onValueChange = { idsInput = it },
            label = { Text("允许的 open_id(用逗号 / 换行分隔)") },
            placeholder = { Text("ou_xxx, ou_yyy") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
        )
        androidx.compose.material3.TextButton(
            onClick = { RuntimeContainer.settings.setFeishuAllowedOpenIds(idsInput) },
            enabled = idsInput != allowedIds,
            modifier = Modifier.align(androidx.compose.ui.Alignment.End),
        ) { Text("保存白名单") }

        SectionLabel("行为")
        SettingsToggleRow(
            title = "把消息当 GUI 任务执行",
            subtitle = "(推荐)bot 收到消息后让 PhoneAgent 直接在本机执行,完成后回结果 + 截图。需要先完成「设备控制授权」。",
            checked = runAsGui,
            onCheckedChange = RuntimeContainer.settings::setFeishuRunAsGuiTask,
        )
        SettingsToggleRow(
            title = "自动用 Brain 文本回复",
            subtitle = "GUI 任务执行失败 / 未授权时的兜底:让 Brain 生成一段文字回复。两者都关 = bot 不响应。",
            checked = autoReply,
            onCheckedChange = RuntimeContainer.settings::setFeishuAutoReply,
        )

        SectionLabel("PhoneAgent 完成后回图")
        val replyImageMode by RuntimeContainer.settings.feishuReplyImageMode.collectAsStateWithLifecycle()
        com.clawgui.ng.data.repo.FeishuReplyImageMode.values().forEach { mode ->
            val (label, sub) = when (mode) {
                com.clawgui.ng.data.repo.FeishuReplyImageMode.OFF ->
                    "关闭" to "只发文字结果,不附图"
                com.clawgui.ng.data.repo.FeishuReplyImageMode.FINAL_ONLY ->
                    "最终截屏" to "任务结束附 1 张最终状态截屏(推荐)"
                com.clawgui.ng.data.repo.FeishuReplyImageMode.COMPOSITE ->
                    "全过程长图" to "拼接每一步截屏成一张长图(需要开启「记录运行轨迹」)"
            }
            SettingsChoiceRow(
                label = "$label · $sub",
                selected = mode == replyImageMode,
                onClick = { RuntimeContainer.settings.setFeishuReplyImageMode(mode) },
            )
        }

        InfoCard(
            "接入步骤\n" +
                "1. 在飞书开放平台创建「企业自建应用」\n" +
                "2. 「事件与回调 → 长连接」选择 WebSocket 模式\n" +
                "3. 订阅事件 `im.message.receive_v1`\n" +
                "4. 把应用的 App ID / App Secret 粘到上面 → 保存\n" +
                "5. 在「应用功能 → 机器人」启用 Bot\n" +
                "6. 回到这里打开「启用 Feishu Bot」开关"
        )

        SectionLabel("调试日志")
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(12.dp)) {
                if (feishuLog.isEmpty()) {
                    Text(
                        "暂无日志。@ Bot 发条消息后这里会显示实时事件流。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    feishuLog.takeLast(20).forEach { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.labelSmall
                                .copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
        InfoCard(
            "如果发完消息日志里没有 `RX:` 那一行,说明 ng 根本没收到事件 —— 多半是飞书后台:\n" +
                "• 没把「事件订阅」模式切到「长连接」\n" +
                "• 没勾选事件 `im.message.receive_v1`\n" +
                "• App ID / Secret 填错"
        )
    }
}

/**
 * 设备控制授权页 ——「无线调试」和「Shizuku」两条路并列,任一就绪即可。
 *
 * Interaction model:
 * - 顶部一张总状态卡:三态(已授权·via X / 配置中 / 未授权)
 * - 下方两张方式卡(无线调试 + Shizuku),默认都展开
 * - 一旦某条路 ready,**另一张卡自动折叠**成单行 "已通过 X 授权,无需配置",
 *   留 [切换到此方式] 让用户主动展开,避免误改
 * - 生效那张卡:绿色细边 + "当前生效" 角标 + 卡底一行小字 [断开此方式] 让用户主动退授权
 * - 不互相 deauthorize:两边都可以同时活着(option B),PhoneAgent 优先使用 Shizuku
 *   (Shizuku 通道延迟低、无网络依赖),wadb 作热备
 */
@Composable
private fun DeviceAuthPage() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // Shizuku side
    val shizukuInstalled = remember { mutableStateOf(false) }
    val shizukuBound = remember { mutableStateOf(false) }

    // Wadb side
    val wadb = remember { com.clawgui.ng.runtime.shizuku.wadb.WirelessAdb.get(ctx) }
    val wadbState by wadb.state.collectAsStateWithLifecycle()
    val wadbReady = wadbState is com.clawgui.ng.runtime.shizuku.wadb.WadbState.Done ||
        wadbState is com.clawgui.ng.runtime.shizuku.wadb.WadbState.Connected

    fun refresh() {
        shizukuInstalled.value = runCatching { RuntimeContainer.device.isShizukuAvailable() }.getOrDefault(false)
        shizukuBound.value = runCatching { RuntimeContainer.device.isAvailable() }.getOrDefault(false)
    }
    LaunchedEffect(Unit) { refresh() }
    LaunchedEffect(wadbState) { refresh() }
    OnResume { refresh() }

    // Pending switch confirmation (set when user taps "切换到此方式" on the
    // inactive card while the other one is currently active).
    var pendingSwitch: ActivePath? by remember { mutableStateOf(null) }

    val activePath: ActivePath = when {
        // Both somehow live (race) — keep whichever was active *first*; the
        // safety-net effect below will close the duplicate.
        shizukuBound.value && !wadbReady -> ActivePath.Shizuku
        wadbReady && !shizukuBound.value -> ActivePath.Wadb
        shizukuBound.value && wadbReady -> ActivePath.Shizuku // we'll tear down wadb
        else -> ActivePath.None
    }

    // Safety net: if both paths somehow ended up live at the same time (e.g.
    // wadb finished pairing while Shizuku binder also reconnected on its own),
    // tear down wadb to enforce the "二选一" invariant. We prefer keeping
    // Shizuku because PhoneAgent prefers it and its disconnect is reversible.
    LaunchedEffect(shizukuBound.value, wadbReady) {
        if (shizukuBound.value && wadbReady) {
            android.util.Log.i("DeviceAuth", "both paths live — auto-closing wadb")
            runCatching {
                com.clawgui.ng.runtime.shizuku.wadb.AdbManager.get(ctx).disconnectQuietly()
            }
            wadb.reset()
        }
    }

    val overallText = when (activePath) {
        ActivePath.Shizuku -> "已授权 · 通过 Shizuku"
        ActivePath.Wadb -> "已授权 · 通过无线调试"
        ActivePath.None -> when {
            wadbState is com.clawgui.ng.runtime.shizuku.wadb.WadbState.Probing ||
                wadbState is com.clawgui.ng.runtime.shizuku.wadb.WadbState.Pairing ||
                wadbState is com.clawgui.ng.runtime.shizuku.wadb.WadbState.Connecting ||
                wadbState is com.clawgui.ng.runtime.shizuku.wadb.WadbState.WaitingForPairing ||
                wadbState is com.clawgui.ng.runtime.shizuku.wadb.WadbState.StartingShizuku ->
                "配置中…"
            else -> "尚未授权 — 任选一种方式即可"
        }
    }
    val overallTone = when (activePath) {
        ActivePath.None -> if (overallText == "配置中…") StatusTone.Warning else StatusTone.Error
        else -> StatusTone.Ok
    }

    Column(
        Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StatusCard(label = "设备控制状态", value = overallText, tone = overallTone)

        if (activePath == ActivePath.None) {
            InfoCard(
                "ClawGUI 操作手机需要 ADB 级权限,两种方式 二选一:\n" +
                    "• 无线调试 — 推荐 · 免电脑、手机本机一次性配对\n" +
                    "• Shizuku — 你已经在用 Shizuku App 时直接复用\n" +
                    "启用一种后,另一种会自动关闭。"
            )
        }

        WirelessAdbCard(
            wadb = wadb,
            active = activePath == ActivePath.Wadb,
            // 当前其它一方在用 → 折叠
            collapsedBecauseOther = activePath == ActivePath.Shizuku,
            // 点 "切换到此方式" 时若另一边在用,要弹确认框
            onRequestSwitchHere = {
                if (activePath == ActivePath.Shizuku) pendingSwitch = ActivePath.Wadb
            },
        )

        ShizukuCard(
            installed = shizukuInstalled.value,
            bound = shizukuBound.value,
            active = activePath == ActivePath.Shizuku,
            collapsedBecauseOther = activePath == ActivePath.Wadb,
            onRequestSwitchHere = {
                if (activePath == ActivePath.Wadb) pendingSwitch = ActivePath.Shizuku
            },
            onRefresh = { refresh() },
        )
    }

    // —— 切换确认弹窗 ——
    pendingSwitch?.let { target ->
        val (targetName, currentName) = when (target) {
            ActivePath.Wadb -> "无线调试" to "Shizuku"
            ActivePath.Shizuku -> "Shizuku" to "无线调试"
            ActivePath.None -> return@let
        }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingSwitch = null },
            title = { Text("切换到 $targetName?") },
            text = {
                Text(
                    "目前 ClawGUI 通过 $currentName 控制设备。切换到 $targetName 会先断开 $currentName," +
                        "然后再开始 $targetName 的配置。\n\n确定要切换吗?",
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    pendingSwitch = null
                    scope.launch {
                        when (target) {
                            ActivePath.Wadb -> {
                                // 断 Shizuku
                                runCatching { RuntimeContainer.device.unbindService() }
                                refresh()
                                // wadb 不直接 connect(用户可能需要先在手机里打开开关),
                                // 留在展开态让用户走 ① / ② / ③。
                            }
                            ActivePath.Shizuku -> {
                                // 断 wadb
                                runCatching {
                                    com.clawgui.ng.runtime.shizuku.wadb.AdbManager.get(ctx).disconnectQuietly()
                                }
                                wadb.reset()
                            }
                            ActivePath.None -> {}
                        }
                    }
                }) { Text("切换") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { pendingSwitch = null }) {
                    Text("取消")
                }
            },
        )
    }
}

private enum class ActivePath { None, Wadb, Shizuku }

/**
 * 一张方式卡的统一外壳:折叠态显示 "已通过 XX 授权,无需配置 [切换到此方式]";
 * 展开态由 [content] 自行渲染。生效时套绿色细边 + "当前生效" 角标。
 */
@Composable
private fun AuthMethodCard(
    title: String,
    badge: String?,
    active: Boolean,
    /** 另一种方式正在使用 → 折叠成单行 + 切换按钮(点击触发确认弹窗) */
    collapsedBecauseOther: Boolean,
    collapsedMessage: String,
    onRequestSwitchHere: () -> Unit,
    content: @Composable () -> Unit,
) {
    val borderColor = if (active) MaterialTheme.colorScheme.tertiary
    else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        border = androidx.compose.foundation.BorderStroke(
            width = if (active) 1.5.dp else 1.dp,
            color = borderColor,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f))
                if (active) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                    ) {
                        Text("当前生效",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                    }
                } else if (badge != null) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    ) {
                        Text(badge,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                    }
                }
            }

            if (collapsedBecauseOther) {
                Spacer(Modifier.height(8.dp))
                Text(collapsedMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.TextButton(
                    onClick = onRequestSwitchHere,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                ) { Text("切换到此方式") }
            } else {
                Spacer(Modifier.height(12.dp))
                content()
            }
        }
    }
}

@Composable
private fun WirelessAdbCard(
    wadb: com.clawgui.ng.runtime.shizuku.wadb.WirelessAdb,
    active: Boolean,
    collapsedBecauseOther: Boolean,
    onRequestSwitchHere: () -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val state by wadb.state.collectAsStateWithLifecycle()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var pairing by remember { mutableStateOf(false) }

    val running = state is com.clawgui.ng.runtime.shizuku.wadb.WadbState.Probing ||
        state is com.clawgui.ng.runtime.shizuku.wadb.WadbState.Pairing ||
        state is com.clawgui.ng.runtime.shizuku.wadb.WadbState.Connecting ||
        state is com.clawgui.ng.runtime.shizuku.wadb.WadbState.StartingShizuku ||
        state is com.clawgui.ng.runtime.shizuku.wadb.WadbState.WaitingForPairing

    AuthMethodCard(
        title = "无线调试",
        badge = "推荐 · 无需电脑",
        active = active,
        collapsedBecauseOther = collapsedBecauseOther,
        collapsedMessage = "当前由 Shizuku 控制设备。切换到无线调试会先断开 Shizuku。",
        onRequestSwitchHere = onRequestSwitchHere,
    ) {
        // —— 子状态(只在展开时显示)——
        val (stateText, stateTone) = when (val s = state) {
            com.clawgui.ng.runtime.shizuku.wadb.WadbState.Idle -> "未启动" to StatusTone.Warning
            com.clawgui.ng.runtime.shizuku.wadb.WadbState.Probing -> "正在发现端口…" to StatusTone.Warning
            com.clawgui.ng.runtime.shizuku.wadb.WadbState.WirelessOff -> "无线调试未打开" to StatusTone.Warning
            is com.clawgui.ng.runtime.shizuku.wadb.WadbState.Pairing -> "正在配对 ${s.host}:${s.port}…" to StatusTone.Warning
            is com.clawgui.ng.runtime.shizuku.wadb.WadbState.Connecting -> "正在连接…" to StatusTone.Warning
            is com.clawgui.ng.runtime.shizuku.wadb.WadbState.Connected -> "已建立 ADB 连接 ✓" to StatusTone.Ok
            is com.clawgui.ng.runtime.shizuku.wadb.WadbState.StartingShizuku -> "已连接 · 同时启动 Shizuku 中…" to StatusTone.Warning
            is com.clawgui.ng.runtime.shizuku.wadb.WadbState.Done -> "已就绪 ✓" to StatusTone.Ok
            is com.clawgui.ng.runtime.shizuku.wadb.WadbState.Error -> s.why to StatusTone.Error
            is com.clawgui.ng.runtime.shizuku.wadb.WadbState.WaitingForPairing -> s.message to StatusTone.Warning
        }

        SubStatusRow(text = stateText, tone = stateTone)
        Spacer(Modifier.height(10.dp))

        if (active) {
            // 已生效 — 只露 [重新配对][断开] 二级动作 + 折叠的详细说明
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                androidx.compose.material3.OutlinedButton(
                    onClick = { scope.launch { wadb.connectAndStart() } },
                    enabled = !running,
                    modifier = Modifier.weight(1f),
                ) { Text("重新连接") }
                androidx.compose.material3.TextButton(
                    onClick = {
                        scope.launch {
                            com.clawgui.ng.runtime.shizuku.wadb.AdbManager.get(ctx).disconnectQuietly()
                            wadb.reset()
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("断开此方式") }
            }
        } else {
            androidx.compose.material3.Button(
                onClick = {
                    com.clawgui.ng.runtime.shizuku.wadb.WirelessAdb.openWirelessDebuggingSettings(ctx)
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("① 打开「无线调试」系统页") }

            Spacer(Modifier.height(6.dp))
            androidx.compose.material3.OutlinedButton(
                onClick = { if (!running) scope.launch { wadb.connectAndStart() } },
                enabled = !running,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("② 之前已配对过 — 直接连接") }

            Spacer(Modifier.height(6.dp))
            androidx.compose.material3.Button(
                onClick = {
                    if (!pairing) {
                        pairing = true
                        scope.launch {
                            com.clawgui.ng.runtime.shizuku.wadb.WadbPairingNotifier
                                .startPairingFlow(ctx)
                            pairing = false
                        }
                    }
                },
                enabled = !pairing && !running,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("③ 开始配对(通过系统通知输码)") }

            Spacer(Modifier.height(8.dp))
            Text(
                "首次:打开总开关 → ③ 开始配对 → 系统页选「使用配对码配对设备」→ 下拉通知栏在 ClawGUI 通知里填 6 位码。\n" +
                    "之后:②直连。需要手机与路由器同一 WiFi。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // 调试日志(只在展开时露)
        val log by wadb.log.collectAsStateWithLifecycle()
        if (log.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(10.dp)) {
                    log.takeLast(10).forEach { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ShizukuCard(
    installed: Boolean,
    bound: Boolean,
    active: Boolean,
    collapsedBecauseOther: Boolean,
    onRequestSwitchHere: () -> Unit,
    onRefresh: () -> Unit,
) {
    var connecting by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    AuthMethodCard(
        title = "Shizuku",
        badge = if (installed) "已检测到 App" else null,
        active = active,
        collapsedBecauseOther = collapsedBecauseOther,
        collapsedMessage = "当前由无线调试控制设备。切换到 Shizuku 会先断开无线调试。",
        onRequestSwitchHere = onRequestSwitchHere,
    ) {
        val subText = when {
            bound -> "已绑定 ClawGUI ✓"
            installed -> "Shizuku App 已装,但 ClawGUI 还没拿到授权"
            else -> "未检测到 Shizuku App"
        }
        val subTone = when {
            bound -> StatusTone.Ok
            installed -> StatusTone.Warning
            else -> StatusTone.Error
        }
        SubStatusRow(text = subText, tone = subTone)
        Spacer(Modifier.height(10.dp))

        if (active) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                androidx.compose.material3.OutlinedButton(
                    onClick = {
                        scope.launch {
                            connecting = true
                            runCatching { RuntimeContainer.device.bindService() }
                            pollShizukuReady(timeoutMs = 3000L)
                            onRefresh()
                            connecting = false
                        }
                    },
                    enabled = !connecting,
                    modifier = Modifier.weight(1f),
                ) { Text(if (connecting) "检测中…" else "重新检测") }
                androidx.compose.material3.TextButton(
                    onClick = {
                        runCatching { RuntimeContainer.device.unbindService() }
                        onRefresh()
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("断开此方式") }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "彻底吊销授权请到 Shizuku App → 管理应用 → ClawGUI → 撤销。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            androidx.compose.material3.Button(
                onClick = {
                    if (connecting) return@Button
                    scope.launch {
                        connecting = true
                        runCatching { RuntimeContainer.device.bindService() }
                        pollShizukuReady(timeoutMs = 3000L)
                        onRefresh()
                        connecting = false
                    }
                },
                enabled = !connecting,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (connecting) "正在连接…" else "绑定 Shizuku 服务 / 重新检测") }
            Spacer(Modifier.height(8.dp))
            Text(
                "经典流程:1) 装并启动 Shizuku App  2) 用 ADB / 免 Root 启动其服务  3) 回来点上面按钮 → 在弹窗中授权。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Small inline status line (dot + text), used inside method cards. */
@Composable
private fun SubStatusRow(text: String, tone: StatusTone) {
    val color = when (tone) {
        StatusTone.Ok -> MaterialTheme.colorScheme.tertiary
        StatusTone.Warning -> MaterialTheme.colorScheme.primary
        StatusTone.Error -> MaterialTheme.colorScheme.error
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

/** Poll until Shizuku service binds (or timeout). */
private suspend fun pollShizukuReady(timeoutMs: Long): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (RuntimeContainer.device.isAvailable()) return true
        kotlinx.coroutines.delay(200)
    }
    return RuntimeContainer.device.isAvailable()
}

@Composable
private fun ImePage() {
    var status by remember { mutableStateOf(probeIme()) }
    OnResume { status = probeIme() }
    Column(
        Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StatusCard(
            label = "ClawGUI 输入法",
            value = status,
            // Enabled alone is enough — Agent switches to it before
            // typing and switches back when done, so the "selected"
            // state isn't something the user needs to manage.
            tone = if (status.startsWith("已启用")) StatusTone.Ok else StatusTone.Warning,
        )
        androidx.compose.material3.Button(
            onClick = {
                val intent = android.content.Intent(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { RuntimeContainer.appContext.startActivity(intent) }
                status = probeIme()
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("打开系统输入法设置,勾选「ClawGUI Input」") }
        androidx.compose.material3.TextButton(
            onClick = { status = probeIme() },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("重新检测状态") }
        InfoCard(
            "只需在系统输入法设置里**启用**「ClawGUI Input」。" +
                "执行任务时 Agent 会自动把输入法切到 ClawGUI,任务结束自动切回你的常用输入法 —— 你不用手动切。\n\n" +
                "如果系统输入法列表里看不到「ClawGUI Input」,先卸载重装本应用。"
        )
    }
}

private fun probeIme(): String = runCatching {
    val ime = RuntimeContainer.ime
    val enabled = runCatching { ime.isOurIMEEnabled() }.getOrDefault(false)
    if (enabled) "已启用(执行任务时 Agent 会自动切换)"
    else "未启用 — 请到系统输入法设置勾选"
}.getOrElse { "无法检测(${it.message ?: "error"})" }

@Composable
private fun TracesPage() {
    val enabled by RuntimeContainer.settings.tracesEnabled.collectAsStateWithLifecycle()
    // Sub-navigation entirely local to this page: settings → traces panel
    //   = (list)  → tap a run → (detail of that runId).
    var view by remember { mutableStateOf<TracesView>(TracesView.Panel) }
    androidx.compose.runtime.DisposableEffect(view) {
        tracesHasOwnHeader.value = view !is TracesView.Panel
        onDispose { tracesHasOwnHeader.value = false }
    }
    // Warm the trace list cache the moment the user opens this page so the
    // "查看所有运行轨迹" tap doesn't have to wait on disk I/O — by the time
    // they tap, the data is already in TracesCache.
    LaunchedEffect(Unit) { TracesCache.refresh() }

    when (val v = view) {
        TracesView.Panel -> Column(
            Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SettingsToggleRow(
                title = "记录运行轨迹",
                subtitle = "保存每次执行的截图、思考、动作",
                checked = enabled,
                onCheckedChange = RuntimeContainer.settings::setTracesEnabled,
            )
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.fillMaxWidth()
                    .clickable { view = TracesView.List },
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("查看所有运行轨迹", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "可逐步回看截图、思考、动作,并导出为压缩包分享",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(Icons.Rounded.ChevronRight, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            InfoCard("轨迹保存到应用沙盒,关掉「记录运行轨迹」后旧轨迹仍保留。点上方可查看 / 导出 / 清空。")
        }
        TracesView.List -> TraceListScreen(
            onOpen = { meta -> view = TracesView.Detail(meta.runId) },
            onClose = { view = TracesView.Panel },
        )
        is TracesView.Detail -> TraceDetailScreen(
            runId = v.runId,
            onBack = { view = TracesView.List },
        )
    }
}

private sealed class TracesView {
    data object Panel : TracesView()
    data object List : TracesView()
    data class Detail(val runId: String) : TracesView()
}

@Composable
private fun PerformancePage() {
    val current by RuntimeContainer.settings.screenshotQuality.collectAsStateWithLifecycle()
    Column(
        Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        InfoCard(
            "Agent 每一步都会把当前屏幕发给 VLM。\n" +
                "原图(1200×2664)每张 ~1.5 MB,上传慢 → 卡顿。\n" +
                "压缩后只有原图 5-15%,识别精度基本不掉。\n\n" +
                "默认「中 · 1200px」适合大多数任务。文字密集或小按钮场景再调高。"
        )
        SectionLabel("截图质量")
        com.clawgui.ng.runtime.phone.util.ScreenshotCompressor.Quality.values().forEach { q ->
            SettingsChoiceRow(
                label = q.label,
                selected = q == current,
                onClick = { RuntimeContainer.settings.setScreenshotQuality(q) },
            )
        }

        Spacer(Modifier.height(8.dp))
        SectionLabel("后台运行")
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val batteryWhitelisted = remember { mutableStateOf(isBatteryUnrestricted(ctx)) }
        OnResume { batteryWhitelisted.value = isBatteryUnrestricted(ctx) }
        StatusCard(
            label = "电池不限制",
            value = if (batteryWhitelisted.value) "已加入白名单" else "未加入(可能被系统冻结)",
            tone = if (batteryWhitelisted.value) StatusTone.Ok else StatusTone.Warning,
        )
        androidx.compose.material3.Button(
            onClick = {
                runCatching {
                    @Suppress("BatteryLife")
                    val intent = android.content.Intent(
                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        android.net.Uri.parse("package:${ctx.packageName}"),
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                }
                batteryWhitelisted.value = isBatteryUnrestricted(ctx)
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("① 请求加入电池白名单") }
        androidx.compose.material3.OutlinedButton(
            onClick = {
                runCatching {
                    val intent = android.content.Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:${ctx.packageName}"),
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("② 打开应用信息 · 手动设「不限制后台活动」") }
        InfoCard(
            "荣耀 / 华为机型即使前台服务在跑也会冻结后台 App。\n" +
                "如果 PhoneAgent 切换到目标 App 后卡住不动,先尝试 ①;\n" +
                "无效再 ② → 「省电与电池 → 启动管理 / 应用启动管理 → ClawGUI」全打开。"
        )
    }
}

private fun isBatteryUnrestricted(ctx: android.content.Context): Boolean = try {
    val pm = ctx.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
    pm.isIgnoringBatteryOptimizations(ctx.packageName)
} catch (_: Throwable) {
    false
}

@Composable
private fun NotificationPage() {
    val enabled by RuntimeContainer.settings.notifyEnabled.collectAsStateWithLifecycle()
    val headsUp by RuntimeContainer.settings.notifyHeadsUp.collectAsStateWithLifecycle()
    val verbose by RuntimeContainer.settings.notifyVerbose.collectAsStateWithLifecycle()
    val notifyEach by RuntimeContainer.settings.notifyEachStep.collectAsStateWithLifecycle()
    Column(
        Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SettingsToggleRow(
            title = "启用通知",
            subtitle = "关闭后只保留系统必需的最小通知",
            checked = enabled,
            onCheckedChange = RuntimeContainer.settings::setNotifyEnabled,
        )
        SettingsToggleRow(
            title = "横幅弹出",
            subtitle = "从屏顶滑下的 heads-up 横幅",
            checked = headsUp,
            onCheckedChange = RuntimeContainer.settings::setNotifyHeadsUp,
        )
        SettingsToggleRow(
            title = "详细内容",
            subtitle = "通知里展示思考过程;关闭后只显示动作摘要",
            checked = verbose,
            onCheckedChange = RuntimeContainer.settings::setNotifyVerbose,
        )
        SettingsToggleRow(
            title = "每一步都发独立通知",
            subtitle = "下拉通知栏看完整执行轨迹(可能在长任务时很吵)",
            checked = notifyEach,
            onCheckedChange = RuntimeContainer.settings::setNotifyEachStep,
        )
        InfoCard("如果系统拦截了 ClawGUI 的通知,请到系统设置 → 应用 → ClawGUI → 通知,确认 channel 「Agent 执行状态」「Agent 单步动作」都打开。")
    }
}

@Composable
private fun OverlayPage() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val enabled by RuntimeContainer.settings.overlayEnabled.collectAsStateWithLifecycle()
    val alphaPct by RuntimeContainer.settings.overlayAlphaPct.collectAsStateWithLifecycle()
    val granted = remember { mutableStateOf(android.provider.Settings.canDrawOverlays(ctx)) }
    OnResume { granted.value = android.provider.Settings.canDrawOverlays(ctx) }

    Column(
        Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StatusCard(
            label = "悬浮窗权限",
            value = if (granted.value) "已授予" else "尚未授予(开关无效)",
            tone = if (granted.value) StatusTone.Ok else StatusTone.Error,
        )
        if (!granted.value) {
            androidx.compose.material3.Button(
                onClick = {
                    runCatching {
                        val intent = android.content.Intent(
                            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:${ctx.packageName}"),
                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        ctx.startActivity(intent)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("打开系统设置授予权限") }
        }
        SettingsToggleRow(
            title = "启用悬浮面板",
            subtitle = "PhoneAgent 执行时,在其它 App 之上显示任务计划 + 执行轨迹",
            checked = enabled,
            onCheckedChange = RuntimeContainer.settings::setOverlayEnabled,
        )
        SectionLabel("透明度 ${alphaPct}%")
        androidx.compose.material3.Slider(
            value = alphaPct.toFloat(),
            onValueChange = { RuntimeContainer.settings.setOverlayAlphaPct(it.toInt()) },
            valueRange = 40f..100f,
            steps = 11,
            modifier = Modifier.fillMaxWidth(),
        )
        InfoCard(
            "悬浮面板会在 PhoneAgent 启动任务后自动出现,显示当前 Plan 和最近几步动作,任务结束自动隐藏。\n" +
                "拖头部可移动位置,点 × 可在当前任务内手动隐藏。\n" +
                "当 Agent 提出 Ask 时,悬浮面板里会直接出输入框,无需切回 ClawGUI。"
        )
    }
}

@Composable
private fun AppearancePage() {
    val current by RuntimeContainer.settings.appearance.collectAsStateWithLifecycle()
    Column(
        Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SectionLabel("主题")
        Appearance.values().forEach { mode ->
            SettingsChoiceRow(
                label = when (mode) {
                    Appearance.LIGHT -> "浅色"
                    Appearance.DARK -> "深色"
                    Appearance.SYSTEM -> "跟随系统"
                },
                selected = mode == current,
                onClick = { RuntimeContainer.settings.setAppearance(mode) },
            )
        }
    }
}

@Composable
private fun AboutPage() {
    Column(
        Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(20.dp)) {
                Text("ClawGUI · NG", style = MaterialTheme.typography.headlineSmall)
                Text("版本 0.2.0", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Text("用于研究与个人使用的 Phone GUI Agent 客户端。",
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
        InfoCard("开源协议 Apache-2.0 · 仓库:github.com/ZJU-REAL/ClawGUI")
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
    )
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(),
            )
        }
    }
}

@Composable
private fun SettingsChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Box(
                Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(if (selected) MaterialTheme.colorScheme.primary
                    else Color.Transparent)
                    .padding(2.dp)
            )
            Spacer(Modifier.width(12.dp))
            Text(label, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun InfoCard(text: String) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
    }
}

private enum class StatusTone { Ok, Warning, Error }

@Composable
private fun StatusCard(label: String, value: String, tone: StatusTone) {
    val color = when (tone) {
        StatusTone.Ok -> MaterialTheme.colorScheme.tertiary
        StatusTone.Warning -> MaterialTheme.colorScheme.primary
        StatusTone.Error -> MaterialTheme.colorScheme.error
    }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(20.dp),
        ) {
            Box(
                Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleMedium)
                Text(value, style = MaterialTheme.typography.bodyMedium,
                    color = color)
            }
        }
    }
}
