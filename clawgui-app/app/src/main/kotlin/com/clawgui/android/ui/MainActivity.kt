package com.clawgui.android.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.clawgui.android.App
import com.clawgui.android.platform.shizuku.DeviceController
import com.clawgui.android.ui.screens.ChatScreen
import com.clawgui.android.ui.screens.ClawDrawerContent
import com.clawgui.android.ui.screens.InboxScreen
import com.clawgui.android.ui.screens.OnboardingScreen
import com.clawgui.android.ui.screens.RenameSessionDialog
import com.clawgui.android.ui.screens.SettingsScreen
import com.clawgui.android.ui.screens.SettingsSubPage
import com.clawgui.android.ui.theme.ClawGUITheme
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

private const val TAG = "MainActivity"

/** 覆盖层页面 —— 主界面是 Chat,Settings / Inbox 作为覆盖层从上方推入。 */
enum class OverlayScreen { Settings, Inbox }

class MainActivity : ComponentActivity() {

    private lateinit var deviceController: DeviceController

    private val shizukuAvailable = mutableStateOf(false)

    private var hasSeenOnboarding = false

    // Shizuku lifecycle listeners (same pattern as roubao)
    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.d(TAG, "Shizuku binder received")
        shizukuAvailable.value = true
        if (checkShizukuPermission()) {
            Log.d(TAG, "Permission granted, binding service")
            deviceController.bindService()
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.d(TAG, "Shizuku binder dead")
        shizukuAvailable.value = false
    }

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        Log.d(TAG, "Shizuku permission result: $grantResult")
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            deviceController.bindService()
            Toast.makeText(this, "Shizuku 权限已获取", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        val nightMode = resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK
        val darkSystemBars = nightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES
        enableEdgeToEdge(
            statusBarStyle = if (darkSystemBars) {
                SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
            } else {
                SystemBarStyle.light(
                    android.graphics.Color.TRANSPARENT,
                    android.graphics.Color.TRANSPARENT,
                )
            },
            navigationBarStyle = if (darkSystemBars) {
                SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
            } else {
                SystemBarStyle.light(
                    android.graphics.Color.TRANSPARENT,
                    android.graphics.Color.TRANSPARENT,
                )
            },
        )

        deviceController = App.getInstance().deviceController

        val prefs = getSharedPreferences("clawgui_prefs", MODE_PRIVATE)
        hasSeenOnboarding = prefs.getBoolean("has_seen_onboarding", false)

        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)

        checkAndUpdateShizukuStatus()
        checkOverlayPermission()

        setContent {
            ClawGUITheme {
                var onboardingDone by remember { mutableStateOf(hasSeenOnboarding) }

                if (!onboardingDone) {
                    OnboardingScreen(
                        onComplete = {
                            getSharedPreferences("clawgui_prefs", MODE_PRIVATE)
                                .edit().putBoolean("has_seen_onboarding", true).apply()
                            onboardingDone = true
                        },
                        onRequestShizuku = { requestShizukuPermission() }
                    )
                } else {
                    MainApp()
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
    @Composable
    fun MainApp() {
        val drawerState = rememberDrawerState(DrawerValue.Closed)
        val scope = rememberCoroutineScope()

        var overlay by remember { mutableStateOf<OverlayScreen?>(null) }
        var settingsSubPage by remember { mutableStateOf<SettingsSubPage?>(null) }
        var settingsDirty by remember { mutableStateOf(false) }
        var showDiscardDialog by remember { mutableStateOf(false) }
        var showRenameDialog by remember { mutableStateOf(false) }

        val shizuku by shizukuAvailable
        val app = App.getInstance()
        val messages by app.chatMessages.collectAsState(initial = emptyList())
        val agentExecuting by app.isExecuting.collectAsState(initial = false)
        val sessionName by app.currentSessionDisplayName.collectAsState(initial = "新会话")
        val sessionKey by app.currentSessionKey.collectAsState(initial = "")
        val activeRunningKey by app.activeRunningSessionKey.collectAsState(initial = null)
        val isReadOnlySession = sessionKey.isNotEmpty() && !sessionKey.startsWith("ui:")
        val canStopActiveSession = activeRunningKey != null && activeRunningKey == sessionKey

        val inSettingsSubPage = overlay == OverlayScreen.Settings && settingsSubPage != null

        val tryBackFromSubPage: () -> Unit = {
            if (settingsDirty) {
                showDiscardDialog = true
            } else {
                settingsSubPage = null
            }
        }

        // Back 优先级:drawer 开 → 关 drawer;Settings 子页 → dirty check 回 Settings 索引;overlay → 关 overlay 回 Chat。
        BackHandler(enabled = drawerState.isOpen) {
            scope.launch { drawerState.close() }
        }
        BackHandler(enabled = !drawerState.isOpen && inSettingsSubPage) {
            tryBackFromSubPage()
        }
        BackHandler(
            enabled = !drawerState.isOpen && overlay != null && !inSettingsSubPage,
        ) {
            overlay = null
            settingsSubPage = null
            settingsDirty = false
        }

        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = overlay == null,
            drawerContent = {
                ClawDrawerContent(
                    onNewSession = {
                        scope.launch { drawerState.close() }
                        if (!app.newSession()) {
                            Toast.makeText(this@MainActivity, "任务执行中,请先停止", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onOpenInbox = {
                        scope.launch { drawerState.close() }
                        overlay = OverlayScreen.Inbox
                    },
                    onOpenSettings = {
                        scope.launch { drawerState.close() }
                        overlay = OverlayScreen.Settings
                    },
                    onPickSession = {
                        scope.launch { drawerState.close() }
                    },
                )
            },
        ) {
            when (overlay) {
                null -> ChatScaffold(
                    sessionName = sessionName,
                    messages = messages,
                    agentExecuting = agentExecuting,
                    isReadOnly = isReadOnlySession,
                    canStopActive = canStopActiveSession,
                    shizukuReady = shizuku && checkShizukuPermission(),
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    onRenameRequest = { showRenameDialog = true },
                    onExecute = { app.sendInstruction(it) },
                    onStop = { app.stopInstruction() },
                )
                OverlayScreen.Settings -> SettingsScaffold(
                    subPage = settingsSubPage,
                    dirty = settingsDirty,
                    onBack = {
                        if (settingsSubPage != null) {
                            tryBackFromSubPage()
                        } else {
                            overlay = null
                        }
                    },
                    onSubPageChange = { new ->
                        settingsDirty = false
                        settingsSubPage = new
                    },
                    onDirtyChange = { settingsDirty = it },
                    onSaved = {
                        settingsDirty = false
                        settingsSubPage = null
                    },
                    shizukuReady = shizuku && checkShizukuPermission(),
                    shizukuPrivilegeLabel = if (shizuku) {
                        when (deviceController.getShizukuPrivilegeLevel()) {
                            DeviceController.ShizukuPrivilegeLevel.ROOT -> "ROOT"
                            DeviceController.ShizukuPrivilegeLevel.ADB -> "ADB"
                            DeviceController.ShizukuPrivilegeLevel.NONE -> "NONE"
                        }
                    } else "NONE",
                )
                OverlayScreen.Inbox -> InboxScaffold(
                    onBack = { overlay = null },
                    onPicked = { overlay = null },
                )
            }
        }

        if (showDiscardDialog) {
            AlertDialog(
                onDismissRequest = { showDiscardDialog = false },
                title = { Text("放弃未保存的修改?") },
                text = { Text("当前子页有修改尚未保存,返回会丢弃这些修改。") },
                confirmButton = {
                    TextButton(onClick = {
                        showDiscardDialog = false
                        settingsDirty = false
                        settingsSubPage = null
                    }) {
                        Text("放弃", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDiscardDialog = false }) {
                        Text("取消")
                    }
                },
            )
        }

        if (showRenameDialog) {
            RenameSessionDialog(
                currentName = sessionName,
                onDismiss = { showRenameDialog = false },
                onConfirm = { newName ->
                    showRenameDialog = false
                    if (!app.renameSession(sessionKey, newName)) {
                        Toast.makeText(this@MainActivity, "改名失败", Toast.LENGTH_SHORT).show()
                    }
                },
            )
        }
    }

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
    @Composable
    private fun ChatScaffold(
        sessionName: String,
        messages: List<ChatMessage>,
        agentExecuting: Boolean,
        isReadOnly: Boolean,
        canStopActive: Boolean,
        shizukuReady: Boolean,
        onOpenDrawer: () -> Unit,
        onRenameRequest: () -> Unit,
        onExecute: (String) -> Unit,
        onStop: () -> Unit,
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0),
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = sessionName,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onOpenDrawer) {
                            Icon(
                                imageVector = Icons.Filled.Menu,
                                contentDescription = "打开菜单",
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            enabled = messages.isNotEmpty(),
                            onClick = onRenameRequest,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Edit,
                                contentDescription = "重命名会话",
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
            },
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding),
            ) {
                ChatScreen(
                    shizukuAvailable = shizukuReady,
                    isExecuting = agentExecuting,
                    isReadOnly = isReadOnly,
                    canStopActive = canStopActive,
                    messages = messages,
                    onExecute = onExecute,
                    onStop = onStop,
                )
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
    @Composable
    private fun SettingsScaffold(
        subPage: SettingsSubPage?,
        dirty: Boolean,
        onBack: () -> Unit,
        onSubPageChange: (SettingsSubPage?) -> Unit,
        onDirtyChange: (Boolean) -> Unit,
        onSaved: () -> Unit,
        shizukuReady: Boolean,
        shizukuPrivilegeLabel: String,
    ) {
        @Suppress("UNUSED_PARAMETER") val _unused = dirty // 保留参数供将来 scaffold 内联 dirty 提示
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0),
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = subPage?.displayName ?: "设置",
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.Filled.ArrowBack,
                                contentDescription = "返回",
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
            },
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .imePadding(),
            ) {
                SettingsScreen(
                    subPage = subPage,
                    onSubPageChange = onSubPageChange,
                    onDirtyChange = onDirtyChange,
                    onSaved = onSaved,
                    shizukuAvailable = shizukuReady,
                    shizukuPrivilegeLevel = shizukuPrivilegeLabel,
                )
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
    @Composable
    private fun InboxScaffold(
        onBack: () -> Unit,
        onPicked: (String) -> Unit,
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0),
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = "外部会话",
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.Filled.ArrowBack,
                                contentDescription = "返回",
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
            },
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding),
            ) {
                InboxScreen(onPicked = onPicked)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        deviceController.unbindService()
    }

    private fun checkShizukuPermission(): Boolean {
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    private fun checkAndUpdateShizukuStatus() {
        try {
            val alive = Shizuku.pingBinder()
            shizukuAvailable.value = alive
            if (alive) {
                if (checkShizukuPermission()) {
                    deviceController.bindService()
                } else {
                    requestShizukuPermission()
                }
            }
        } catch (e: Exception) {
            shizukuAvailable.value = false
        }
    }

    private fun checkOverlayPermission() {
        if (Settings.canDrawOverlays(this)) return
        Toast.makeText(
            this,
            "请授权悬浮窗权限以在其他 App 中查看 ClawGUI 执行状态",
            Toast.LENGTH_LONG,
        ).show()
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            )
            startActivity(intent)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            } catch (_: Exception) {}
        }
    }

    private fun requestShizukuPermission() {
        try {
            if (!Shizuku.pingBinder()) {
                Toast.makeText(this, "请先启动 Shizuku App", Toast.LENGTH_SHORT).show()
                return
            }
            if (Shizuku.isPreV11()) {
                Toast.makeText(this, "Shizuku 版本过低", Toast.LENGTH_SHORT).show()
                return
            }
            if (checkShizukuPermission()) {
                shizukuAvailable.value = true
                deviceController.bindService()
                return
            }
            Shizuku.requestPermission(0)
        } catch (e: Exception) {
            Toast.makeText(this, "请先启动 Shizuku App", Toast.LENGTH_SHORT).show()
        }
    }

}
