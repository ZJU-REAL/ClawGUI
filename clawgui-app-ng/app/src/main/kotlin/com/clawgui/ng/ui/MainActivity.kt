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

    if (showSettings) {
        SettingsScreen(onClose = { showSettings = false })
        return
    }

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

    if (showModelSheet) {
        ModelPickerSheet(onDismiss = { showModelSheet = false })
    }
}
