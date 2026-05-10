package com.clawgui.android.ui.screens

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.clawgui.android.App
import com.clawgui.android.core.ime.ClawguiIME
import com.clawgui.android.core.nano.providers.PROVIDERS
import com.clawgui.android.core.nano.providers.findProviderByName
import com.clawgui.android.core.phone.model.adapters.detectModelType
import com.clawgui.android.platform.shizuku.ImeController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class SettingsSubPage(val displayName: String) {
    AiModels("AI 模型"),
    Channels("Channels"),
    Ime("ClawGUI 输入法"),
    Shizuku("Shizuku"),
    Traces("Trace 记录"),
    About("关于"),
}

@Composable
fun SettingsScreen(
    subPage: SettingsSubPage?,
    onSubPageChange: (SettingsSubPage?) -> Unit,
    onDirtyChange: (Boolean) -> Unit,
    onSaved: () -> Unit,
    shizukuAvailable: Boolean,
    shizukuPrivilegeLevel: String,
) {
    when (subPage) {
        null -> SettingsIndex(
            onPick = onSubPageChange,
            shizukuAvailable = shizukuAvailable,
            shizukuPrivilegeLevel = shizukuPrivilegeLevel,
        )
        SettingsSubPage.AiModels -> AiModelsSubPage(onDirtyChange, onSaved)
        SettingsSubPage.Channels -> ChannelsSubPage(onDirtyChange, onSaved)
        SettingsSubPage.Ime -> ImeSubPage(shizukuAvailable)
        SettingsSubPage.Shizuku -> ShizukuSubPage(shizukuAvailable, shizukuPrivilegeLevel)
        SettingsSubPage.Traces -> TracesSubPage(onDirtyChange, onSaved)
        SettingsSubPage.About -> AboutSubPage()
    }
}

// ---------- Main index ----------

@Composable
private fun SettingsIndex(
    onPick: (SettingsSubPage) -> Unit,
    shizukuAvailable: Boolean,
    shizukuPrivilegeLevel: String,
) {
    val app = App.getInstance()
    val store = app.settingsStore

    // IME 状态只在 Index 顶层读一次作为摘要,详情页自己再读。
    var imeActive by remember { mutableStateOf(false) }
    var imeEnabled by remember { mutableStateOf(false) }
    LaunchedEffect(shizukuAvailable) {
        val ctrl = ImeController(app.deviceController)
        withContext(Dispatchers.IO) {
            imeEnabled = ctrl.isOurIMEEnabled()
            imeActive = ctrl.isOurIMECurrent()
        }
    }

    val brainDisplay = findProviderByName(store.brainProvider)?.displayName ?: store.brainProvider
    val vlmDisplay = findProviderByName(store.vlmProvider)?.displayName ?: store.vlmProvider
    val primaryItems = listOf(
        SettingsIndexItem(
            icon = Icons.Filled.Psychology,
            title = SettingsSubPage.AiModels.displayName,
            supporting = "Brain: $brainDisplay  ·  VLM: $vlmDisplay",
            onClick = { onPick(SettingsSubPage.AiModels) },
        ),
        SettingsIndexItem(
            icon = Icons.Filled.Chat,
            title = SettingsSubPage.Channels.displayName,
            supporting = if (store.feishuEnabled) "飞书已启用" else "未启用",
            onClick = { onPick(SettingsSubPage.Channels) },
        ),
        SettingsIndexItem(
            icon = Icons.Filled.Keyboard,
            title = SettingsSubPage.Ime.displayName,
            supporting = when {
                imeActive -> "已启用并设为默认"
                imeEnabled -> "已启用,非默认"
                else -> "未启用"
            },
            onClick = { onPick(SettingsSubPage.Ime) },
        ),
        SettingsIndexItem(
            icon = Icons.Filled.Shield,
            title = SettingsSubPage.Shizuku.displayName,
            supporting = if (shizukuAvailable) "已连接 ($shizukuPrivilegeLevel)" else "未连接",
            onClick = { onPick(SettingsSubPage.Shizuku) },
        ),
    )
    val miscItems = listOf(
        SettingsIndexItem(
            icon = Icons.Filled.Description,
            title = SettingsSubPage.Traces.displayName,
            supporting = if (store.traceEnabled) "已开启 · 最多 ${store.maxSteps} 步" else "已关闭",
            onClick = { onPick(SettingsSubPage.Traces) },
        ),
        SettingsIndexItem(
            icon = Icons.Filled.Info,
            title = SettingsSubPage.About.displayName,
            supporting = "版本 0.1.0",
            onClick = { onPick(SettingsSubPage.About) },
        ),
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
            tonalElevation = 0.dp,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "设置中心",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "模型、输入法、连接能力和 Trace 记录都可以在这里统一管理。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SettingsIndexSection(
            title = "核心功能",
            items = primaryItems,
        )

        SettingsIndexSection(
            title = "更多",
            items = miscItems,
        )
    }
}

@Composable
private fun SettingsIndexSection(
    title: String,
    items: List<SettingsIndexItem>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
            ) {
                items.forEachIndexed { index, item ->
                    SettingsIndexRow(
                        icon = item.icon,
                        title = item.title,
                        supporting = item.supporting,
                        onClick = item.onClick,
                    )
                    if (index != items.lastIndex) {
                        Divider(
                            modifier = Modifier.padding(start = 66.dp, end = 8.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsIndexRow(
    icon: ImageVector,
    title: String,
    supporting: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(settingsIconContainerColor()),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = settingsIconTintColor(),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                supporting,
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

@Composable
private fun settingsIconContainerColor(): Color {
    return if (isSystemInDarkTheme()) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f)
    } else {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
    }
}

@Composable
private fun settingsIconTintColor(): Color {
    return if (isSystemInDarkTheme()) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.primary
    }
}

private data class SettingsIndexItem(
    val icon: ImageVector,
    val title: String,
    val supporting: String,
    val onClick: () -> Unit,
)

// ---------- Sub-page scaffold ----------

@Composable
private fun SubPageScaffold(
    showRestartHint: Boolean,
    dirty: Boolean,
    onSave: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (showRestartHint) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(
                    "修改后需重启 app 才会生效。",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
        content()
        Button(
            onClick = onSave,
            enabled = dirty,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (dirty) "保存" else "无变更")
        }
    }
}

// ---------- AI Models sub-page ----------

@Composable
private fun AiModelsSubPage(
    onDirtyChange: (Boolean) -> Unit,
    onSaved: () -> Unit,
) {
    val app = App.getInstance()
    val store = app.settingsStore

    var brainProvider by remember { mutableStateOf(store.brainProvider) }
    var brainKey by remember { mutableStateOf(store.providerApiKey(store.brainProvider)) }
    var brainBase by remember { mutableStateOf(store.providerApiBase(store.brainProvider)) }
    var brainModel by remember {
        mutableStateOf(
            store.brainProviderModel(store.brainProvider).ifBlank {
                findProviderByName(store.brainProvider)?.defaultModelHint ?: ""
            }
        )
    }
    var vlmProvider by remember { mutableStateOf(store.vlmProvider) }
    var vlmKey by remember { mutableStateOf(store.providerApiKey(store.vlmProvider)) }
    var vlmBase by remember { mutableStateOf(store.providerApiBase(store.vlmProvider)) }
    var vlmModel by remember {
        mutableStateOf(
            store.vlmProviderModel(store.vlmProvider).ifBlank {
                if (store.vlmProvider == "zhipu") "autoglm-phone" else ""
            }
        )
    }
    var showBrainKey by remember { mutableStateOf(false) }
    var showVlmKey by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(false) }

    fun markDirty() {
        if (!dirty) {
            dirty = true
            onDirtyChange(true)
        }
    }

    fun switchBrain(newProvider: String) {
        brainProvider = newProvider
        brainKey = store.providerApiKey(newProvider)
        brainBase = store.providerApiBase(newProvider)
        brainModel = store.brainProviderModel(newProvider)
            .ifBlank { findProviderByName(newProvider)?.defaultModelHint ?: "" }
        markDirty()
    }

    fun switchVlm(newProvider: String) {
        vlmProvider = newProvider
        vlmKey = store.providerApiKey(newProvider)
        vlmBase = store.providerApiBase(newProvider)
        vlmModel = store.vlmProviderModel(newProvider)
            .ifBlank { if (newProvider == "zhipu") "autoglm-phone" else "" }
        markDirty()
    }

    fun save() {
        store.brainProvider = brainProvider
        store.setProviderApiKey(brainProvider, brainKey.trim())
        store.setProviderApiBase(brainProvider, brainBase.trim())
        store.setBrainProviderModel(brainProvider, brainModel.trim())

        store.vlmProvider = vlmProvider
        store.setProviderApiKey(vlmProvider, vlmKey.trim())
        store.setProviderApiBase(vlmProvider, vlmBase.trim())
        store.setVlmProviderModel(vlmProvider, vlmModel.trim())

        onSaved()
    }

    SubPageScaffold(showRestartHint = true, dirty = dirty, onSave = ::save) {
        ProviderCard(
            title = "Brain 模型(对话 / 工具调度)",
            selectedProvider = brainProvider,
            onSelectProvider = ::switchBrain,
            apiKey = brainKey,
            onApiKeyChange = { brainKey = it; markDirty() },
            apiBase = brainBase,
            onApiBaseChange = { brainBase = it; markDirty() },
            model = brainModel,
            onModelChange = { brainModel = it; markDirty() },
            showKey = showBrainKey,
            onToggleShowKey = { showBrainKey = !showBrainKey },
            modelPlaceholder = findProviderByName(brainProvider)?.defaultModelHint ?: "",
            modelPresets = emptyList(),
        )
        ProviderCard(
            title = "VLM 模型(GUI 执行)",
            // Provider 下拉走的是 Brain 的 15 家 registry,但 VLM 只对下面 5 家
            // 模型家族做了 prompt/响应解析的 adapter(core/phone/model/adapters/*Adapter.kt),
            // 其他模型会 fallback 到 AutoGLM 模板 —— 不崩但解析效果不保证。
            subtitle = "已适配:AutoGLM / UI-TARS / Qwen-VL / MAI-UI / GUI-Owl。选用其他模型将按 AutoGLM 模板解析,效果不保证。",
            selectedProvider = vlmProvider,
            onSelectProvider = ::switchVlm,
            apiKey = vlmKey,
            onApiKeyChange = { vlmKey = it; markDirty() },
            apiBase = vlmBase,
            onApiBaseChange = { vlmBase = it; markDirty() },
            model = vlmModel,
            onModelChange = { vlmModel = it; markDirty() },
            showKey = showVlmKey,
            onToggleShowKey = { showVlmKey = !showVlmKey },
            modelPlaceholder = if (vlmProvider == "zhipu") "autoglm-phone"
                else findProviderByName(vlmProvider)?.defaultModelHint ?: "",
            modelPresets = VLM_MODEL_PRESETS,
            vlmAdapterWhitelist = true,
        )
    }
}

// ---------- Channels sub-page ----------

@Composable
private fun ChannelsSubPage(
    onDirtyChange: (Boolean) -> Unit,
    onSaved: () -> Unit,
) {
    val app = App.getInstance()
    val store = app.settingsStore

    var feishuEnabled by remember { mutableStateOf(store.feishuEnabled) }
    var feishuAppId by remember { mutableStateOf(store.feishuAppId) }
    var feishuAppSecret by remember { mutableStateOf(store.feishuAppSecret) }
    var feishuAllowAll by remember { mutableStateOf(store.feishuAllowAll) }
    var feishuAllowedOpenIds by remember { mutableStateOf(store.feishuAllowedOpenIds.joinToString(", ")) }
    var showFeishuSecret by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(false) }

    fun markDirty() {
        if (!dirty) {
            dirty = true
            onDirtyChange(true)
        }
    }

    fun save() {
        store.feishuEnabled = feishuEnabled
        store.feishuAppId = feishuAppId.trim()
        store.feishuAppSecret = feishuAppSecret.trim()
        store.feishuAllowAll = feishuAllowAll
        store.feishuAllowedOpenIds = feishuAllowedOpenIds
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        onSaved()
    }

    SubPageScaffold(showRestartHint = true, dirty = dirty, onSave = ::save) {
        Text(
            "配置指南见项目 docs/feishu-setup.md。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            shape = MaterialTheme.shapes.large,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("飞书 Channel", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "通过飞书自建应用 WS 长连接接收消息,机器人回复回原 thread。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = feishuEnabled,
                        onCheckedChange = { feishuEnabled = it; markDirty() },
                    )
                }

                OutlinedTextField(
                    value = feishuAppId,
                    onValueChange = { feishuAppId = it; markDirty() },
                    label = { Text("App ID") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                OutlinedTextField(
                    value = feishuAppSecret,
                    onValueChange = { feishuAppSecret = it; markDirty() },
                    label = { Text("App Secret") },
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (showFeishuSecret) VisualTransformation.None
                    else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { showFeishuSecret = !showFeishuSecret }) {
                            Text(if (showFeishuSecret) "隐藏" else "显示")
                        }
                    },
                    singleLine = true,
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("允许所有 open_id", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (feishuAllowAll) "任何人 at 机器人都能触发任务(谨慎)"
                            else "仅下方列表中的 open_id 能触发任务",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = feishuAllowAll,
                        onCheckedChange = { feishuAllowAll = it; markDirty() },
                    )
                }

                OutlinedTextField(
                    value = feishuAllowedOpenIds,
                    onValueChange = { feishuAllowedOpenIds = it; markDirty() },
                    label = { Text("允许的 open_id(逗号分隔)") },
                    placeholder = { Text("ou_xxxxxxxx, ou_yyyyyyyy") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !feishuAllowAll,
                )
            }
        }
    }
}

// ---------- IME sub-page ----------

@Composable
private fun ImeSubPage(shizukuAvailable: Boolean) {
    val app = App.getInstance()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var imeRefreshVersion by remember { mutableStateOf(0) }
    var imeCurrent by remember { mutableStateOf<String?>(null) }
    var imeEnabled by remember { mutableStateOf(false) }
    var imeActive by remember { mutableStateOf(false) }

    LaunchedEffect(imeRefreshVersion, shizukuAvailable) {
        val ctrl = ImeController(app.deviceController)
        withContext(Dispatchers.IO) {
            imeCurrent = ctrl.currentIme()
            imeEnabled = ctrl.isOurIMEEnabled()
            imeActive = ctrl.isOurIMECurrent()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            shape = MaterialTheme.shapes.large,
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "状态",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { imeRefreshVersion += 1 }) { Text("刷新") }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = when {
                        imeActive -> "已启用并设为默认 ✓"
                        imeEnabled -> "已启用,但当前默认是其他输入法"
                        else -> "未启用 —— 中文输入会回退到剪贴板粘贴"
                    },
                    color = if (imeActive) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "当前默认输入法:${imeCurrent ?: "(未知)"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = shizukuAvailable && !imeEnabled,
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                val ok = ImeController(app.deviceController).enableOurIME()
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(
                                        context,
                                        if (ok) "已启用,可以回到聊天页测试" else "启用失败,请用系统设置手动启用",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                    imeRefreshVersion += 1
                                }
                            }
                        },
                    ) { Text(if (imeEnabled) "已启用" else "一键启用") }
                    OutlinedButton(onClick = {
                        try {
                            val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                        } catch (_: Exception) {
                            Toast.makeText(context, "无法打开系统输入法设置", Toast.LENGTH_SHORT).show()
                        }
                    }) { Text("系统设置") }
                }
            }
        }

        Text(
            "无 Shizuku 时请在系统「语言和输入法」里勾选 ClawGUI,并设为默认。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "组件 id:${ClawguiIME.IME_COMPONENT}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------- Shizuku sub-page ----------

@Composable
private fun ShizukuSubPage(
    shizukuAvailable: Boolean,
    shizukuPrivilegeLevel: String,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            shape = MaterialTheme.shapes.large,
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("连接状态", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (shizukuAvailable) "已连接" else "未连接",
                    style = MaterialTheme.typography.headlineSmall,
                    color = if (shizukuAvailable) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
                )
                if (shizukuAvailable) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "特权级别:$shizukuPrivilegeLevel",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Text(
            "Shizuku 是 ClawGUI 执行 GUI 自动化的核心依赖,提供 ADB 或 ROOT 级别的设备控制能力。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            "未连接时,请打开 Shizuku app 启动服务并授权本 app。具体方式参考 Shizuku 官方文档。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------- Traces sub-page ----------

@Composable
private fun TracesSubPage(
    onDirtyChange: (Boolean) -> Unit,
    onSaved: () -> Unit,
) {
    val app = App.getInstance()
    val store = app.settingsStore
    val scope = rememberCoroutineScope()

    var traceEnabled by remember { mutableStateOf(store.traceEnabled) }
    var maxSteps by remember { mutableStateOf(store.maxSteps.toString()) }
    var dirty by remember { mutableStateOf(false) }

    var traceFiles by remember { mutableStateOf<List<TraceFileItem>>(emptyList()) }
    var traceLoading by remember { mutableStateOf(true) }
    var traceRefreshVersion by remember { mutableStateOf(0) }
    var selectedTrace by remember { mutableStateOf<TracePreview?>(null) }

    LaunchedEffect(traceRefreshVersion) {
        traceLoading = true
        traceFiles = withContext(Dispatchers.IO) {
            loadTraceFiles(File(app.workspaceDir, "traces"))
        }
        traceLoading = false
    }

    fun markDirty() {
        if (!dirty) {
            dirty = true
            onDirtyChange(true)
        }
    }

    fun save() {
        store.traceEnabled = traceEnabled
        store.maxSteps = maxSteps.toIntOrNull()?.coerceIn(1, 200) ?: 20
        traceRefreshVersion += 1
        onSaved()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            shape = MaterialTheme.shapes.large,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Trace 记录", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (traceEnabled) "开启后,每轮对话会保存模型、工具和 GUI 轨迹。"
                        else "关闭后,新任务不会继续写入 trace 文件。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = traceEnabled,
                    onCheckedChange = { traceEnabled = it; markDirty() },
                )
            }
        }

        OutlinedTextField(
            value = maxSteps,
            onValueChange = { maxSteps = it; markDirty() },
            label = { Text("最大步骤数 (1-200)") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
        )

        Button(
            onClick = ::save,
            enabled = dirty,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (dirty) "保存" else "无变更")
        }

        Divider()

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("最近 Trace", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    File(app.workspaceDir, "traces").absolutePath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { traceRefreshVersion += 1 }) { Text("刷新") }
        }

        when {
            traceLoading -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            traceFiles.isEmpty() -> {
                Text(
                    "还没有 trace 文件。发送一条消息后再回来刷新看看。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            else -> {
                traceFiles.forEach { item ->
                    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(item.displayName, style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "${item.dayLabel} · ${formatFileSize(item.sizeBytes)} · ${item.modifiedLabel}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (item.artifactCount > 0) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "附件 ${item.artifactCount} 个",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            TextButton(
                                contentPadding = PaddingValues(0.dp),
                                onClick = {
                                    scope.launch {
                                        selectedTrace = withContext(Dispatchers.IO) {
                                            TracePreview(
                                                title = item.displayName,
                                                path = item.path,
                                                content = loadTraceContent(File(item.path)),
                                            )
                                        }
                                    }
                                },
                            ) {
                                Text("查看内容")
                            }
                        }
                    }
                }
            }
        }
    }

    selectedTrace?.let { preview ->
        AlertDialog(
            onDismissRequest = { selectedTrace = null },
            confirmButton = {
                TextButton(onClick = { selectedTrace = null }) { Text("关闭") }
            },
            title = { Text(preview.title) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        preview.path,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SelectionContainer {
                        Text(
                            preview.content,
                            modifier = Modifier.verticalScroll(rememberScrollState()),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
        )
    }
}

// ---------- About sub-page ----------

@Composable
private fun AboutSubPage() {
    val app = App.getInstance()
    val store = app.settingsStore
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var diagEnabled by remember { mutableStateOf(store.diagnosticMode) }
    var exporting by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Text(
            "ClawGUI",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            "版本 0.1.0",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "基于 ClawGUI-Agent 移植的 Android 客户端,支持对话、GUI 自动化以及飞书 channel。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(Modifier.height(24.dp))

        // ---- 诊断卡(开发/调试阶段用,上线前可从 UI 撤掉;见 docs/diagnostics.md)----
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = MaterialTheme.shapes.large,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("诊断", style = MaterialTheme.typography.titleMedium)
                Text(
                    "复现 bug 后导出日志,可直接分享给开发者。开启\"诊断模式\"会多打一些细节日志(不影响功能,略费电)。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("诊断模式", modifier = Modifier.weight(1f))
                    Switch(
                        checked = diagEnabled,
                        onCheckedChange = { checked ->
                            diagEnabled = checked
                            store.diagnosticMode = checked
                            com.clawgui.android.core.util.Log.setDiagEnabled(checked)
                        },
                    )
                }

                Button(
                    onClick = {
                        if (exporting) return@Button
                        exporting = true
                        scope.launch {
                            try {
                                val file = withContext(Dispatchers.IO) {
                                    com.clawgui.android.core.util.LogExporter.exportToFile(app.workspaceDir)
                                }
                                Toast.makeText(
                                    context,
                                    "已导出 ${file.name}(${file.length() / 1024} KB)",
                                    Toast.LENGTH_SHORT,
                                ).show()
                                val intent = com.clawgui.android.core.util.LogExporter
                                    .buildShareIntent(context, file)
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                Toast.makeText(
                                    context,
                                    "导出失败: ${e.message ?: e::class.simpleName}",
                                    Toast.LENGTH_LONG,
                                ).show()
                            } finally {
                                exporting = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !exporting,
                ) {
                    Text(if (exporting) "导出中…" else "导出诊断日志")
                }

                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val n = withContext(Dispatchers.IO) {
                                com.clawgui.android.core.util.LogExporter.clearOldLogs(app.workspaceDir)
                            }
                            Toast.makeText(context, "已清理 $n 个旧日志文件", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("清空旧日志")
                }
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

// ---------- Private helpers ----------

private val VLM_MODEL_PRESETS: List<Pair<String, String>> = listOf(
    "AutoGLM" to "autoglm-phone",
    "UI-TARS" to "doubao-seed-1-6-ui-tars-250428",
    "Qwen3-VL" to "qwen3-vl-72b-instruct",
    "Qwen2.5-VL" to "qwen2.5-vl-72b-instruct",
    "MAI-UI" to "mai-ui-1.5",
    "GUI-Owl" to "gui-owl-7b",
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ProviderCard(
    title: String,
    selectedProvider: String,
    onSelectProvider: (String) -> Unit,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    apiBase: String,
    onApiBaseChange: (String) -> Unit,
    model: String,
    onModelChange: (String) -> Unit,
    showKey: Boolean,
    onToggleShowKey: () -> Unit,
    modelPlaceholder: String,
    modelPresets: List<Pair<String, String>> = emptyList(),
    subtitle: String? = null,
    /** 非空表示"该角色有 adapter 白名单",model 不命中时在输入框下方给警告。 */
    vlmAdapterWhitelist: Boolean = false,
) {
    val spec = findProviderByName(selectedProvider) ?: PROVIDERS.first()
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded },
            ) {
                OutlinedTextField(
                    value = spec.displayName,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Provider") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    PROVIDERS.forEach { p ->
                        DropdownMenuItem(
                            text = { Text(p.displayName) },
                            onClick = {
                                expanded = false
                                if (p.name != selectedProvider) onSelectProvider(p.name)
                            },
                        )
                    }
                }
            }

            val modelUnsupported = vlmAdapterWhitelist &&
                model.isNotBlank() &&
                detectModelType(model) == "autoglm" &&
                !Regex("autoglm", RegexOption.IGNORE_CASE).containsMatchIn(model)
            OutlinedTextField(
                value = model,
                onValueChange = onModelChange,
                label = { Text("Model") },
                placeholder = { if (modelPlaceholder.isNotBlank()) Text(modelPlaceholder) },
                supportingText = if (modelUnsupported) {
                    { Text("实验性:未匹配已适配的 VLM,将按 AutoGLM 模板解析") }
                } else null,
                isError = modelUnsupported,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            if (modelPresets.isNotEmpty()) {
                Text(
                    "常用预设(点击填入):",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    modelPresets.forEach { (label, value) ->
                        AssistChip(
                            onClick = { onModelChange(value) },
                            label = { Text(label) },
                        )
                    }
                }
            }

            OutlinedTextField(
                value = apiKey,
                onValueChange = onApiKeyChange,
                label = { Text("API Key") },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (showKey) VisualTransformation.None
                else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = onToggleShowKey) {
                        Text(if (showKey) "隐藏" else "显示")
                    }
                },
                singleLine = true,
            )

            OutlinedTextField(
                value = apiBase,
                onValueChange = onApiBaseChange,
                label = { Text("API Base URL") },
                placeholder = { Text(spec.defaultApiBase ?: "") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            if (spec.isGateway) {
                Text(
                    "网关型,可转发多种上游模型;Model 填具体模型名即可。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private data class TraceFileItem(
    val displayName: String,
    val path: String,
    val sizeBytes: Long,
    val modifiedLabel: String,
    val dayLabel: String,
    val artifactCount: Int,
)

private data class TracePreview(
    val title: String,
    val path: String,
    val content: String,
)

private fun loadTraceFiles(root: File): List<TraceFileItem> {
    if (!root.exists()) return emptyList()
    return root.walkTopDown()
        .maxDepth(2)
        .filter { it.isFile && it.extension == "jsonl" }
        .map { file ->
            val dayLabel = file.parentFile?.name ?: "unknown"
            val artifactDir = File(file.parentFile, "artifacts/${file.nameWithoutExtension}")
            TraceFileItem(
                displayName = file.name,
                path = file.absolutePath,
                sizeBytes = file.length(),
                modifiedLabel = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(file.lastModified())),
                dayLabel = dayLabel,
                artifactCount = artifactDir.listFiles()?.size ?: 0,
            )
        }
        .sortedByDescending { it.modifiedLabel }
        .take(12)
        .toList()
}

private fun loadTraceContent(file: File): String {
    if (!file.exists()) return "文件不存在"
    val raw = file.readText(Charsets.UTF_8)
    return if (raw.length <= 20_000) raw else raw.take(20_000) + "\n\n... [内容过长，已截断]"
}

private fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "${bytes} B"
    if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024f)
    return String.format(Locale.US, "%.1f MB", bytes / 1024f / 1024f)
}
