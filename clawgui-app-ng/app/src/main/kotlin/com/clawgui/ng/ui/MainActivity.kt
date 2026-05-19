package com.clawgui.ng.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.clawgui.ng.ui.screens.ChatScreen
import com.clawgui.ng.ui.screens.ModelPickerSheet
import com.clawgui.ng.ui.screens.OnboardingScreen
import com.clawgui.ng.ui.screens.SessionDrawer
import com.clawgui.ng.ui.screens.SettingsScreen
import com.clawgui.ng.ui.theme.ClawNgTheme
import com.clawgui.ng.ui.vm.ChatViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val notifLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* result not actionable — system already remembers it */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        maybeAskNotifications()
        maybeAskOverlayPermission()
        setContent {
            ClawNgTheme {
                Surface(
                    color = MaterialTheme.colorScheme.background,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    AppRoot()
                }
            }
        }
    }

    /** Ask once for POST_NOTIFICATIONS on Android 13+. Silently no-op when already granted. */
    private fun maybeAskNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            runCatching { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
    }

    /**
     * Ask once for SYSTEM_ALERT_WINDOW. Without it the agent's floating
     * Plan + Trace panel can't render above other apps. Android requires
     * the user to grant this through the settings page — not a runtime
     * permission — so we hop them straight there. Silently no-op when
     * already granted; we don't pester users who refused before.
     */
    private fun maybeAskOverlayPermission() {
        if (android.provider.Settings.canDrawOverlays(this)) return
        val askedKey = "asked_overlay_perm_v1"
        val prefs = getSharedPreferences("clawng_settings", MODE_PRIVATE)
        if (prefs.getBoolean(askedKey, false)) return
        prefs.edit().putBoolean(askedKey, true).apply()
        runCatching {
            val intent = android.content.Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName"),
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }
    }
}

@Composable
private fun AppRoot() {
    val seen by com.clawgui.ng.runtime.RuntimeContainer.settings.seenOnboarding
        .collectAsStateWithLifecycle()
    if (!seen) {
        OnboardingScreen(
            onFinish = { com.clawgui.ng.runtime.RuntimeContainer.settings.markOnboardingSeen() }
        )
        return
    }
    Home()
}

@Composable
private fun Home() {
    val vm: ChatViewModel = viewModel()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showModelSheet by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = showSettings) { showSettings = false }
    BackHandler(enabled = !showSettings && drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    // Keep chat composed *underneath* settings instead of tearing it down +
    // rebuilding it every time the user toggles between the two screens.
    // The old `if (showSettings) { ...; return }` pattern unmounted the entire
    // ChatScreen subtree (drawer, dynamic island, markdown messages, every
    // `collectAsStateWithLifecycle`) on each transition — that's where the
    // ~1s "open settings" delay came from.
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                SessionDrawer(
                    vm = vm,
                    onClose = { scope.launch { drawerState.close() } },
                    onOpenSettings = { showSettings = true },
                    onOpenInboxEntry = { entry ->
                        vm.selectSession(entry.sessionKey)
                        com.clawgui.ng.runtime.RuntimeContainer.inbox.markRead(entry.sessionKey)
                    },
                )
            },
        ) {
            ChatScreen(
                vm = vm,
                onOpenDrawer = { scope.launch { drawerState.open() } },
                onOpenSettings = { showSettings = true },
                onOpenModelPicker = { showModelSheet = true },
            )
        }

        if (showSettings) {
            SettingsScreen(onClose = { showSettings = false })
        }
    }

    if (showModelSheet) {
        ModelPickerSheet(onDismiss = { showModelSheet = false })
    }
}
