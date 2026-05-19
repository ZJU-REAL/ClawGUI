package com.clawgui.ng.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clawgui.ng.data.DefaultPromptCards
import com.clawgui.ng.ui.components.ChatInputBar
import com.clawgui.ng.ui.components.ChatTopBar
import com.clawgui.ng.ui.components.MessageBubble
import com.clawgui.ng.ui.components.PromptCardGrid
import com.clawgui.ng.ui.vm.ChatViewModel

@Composable
fun ChatScreen(
    vm: ChatViewModel,
    onOpenDrawer: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenModelPicker: () -> Unit,
) {
    val messages by vm.messages.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val isExec by vm.isExecuting.collectAsStateWithLifecycle()
    val header by vm.header.collectAsStateWithLifecycle()
    val guiMode by com.clawgui.ng.runtime.RuntimeContainer.settings.guiModeEnabled
        .collectAsStateWithLifecycle()
    val pending by vm.pendingAttachments.collectAsStateWithLifecycle()
    var showAttachSheet by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }
    LaunchedEffect(messages.lastOrNull()?.content?.length) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column(Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
                ChatTopBar(
                    title = header.title,
                    modelLabel = header.modelLabel,
                    onMenuClick = onOpenDrawer,
                    onNewClick = { vm.newSession() },
                    onModelClick = onOpenModelPicker,
                )
            }
        },
        bottomBar = {
            Column(
                Modifier
                    .imePadding()
                    .windowInsetsPadding(WindowInsets.navigationBars),
            ) {
                com.clawgui.ng.ui.components.PendingAttachmentsRow(
                    attachments = pending,
                    onRemove = vm::removePendingAttachment,
                )
                ChatInputBar(
                    value = draft,
                    onValueChange = vm::updateDraft,
                    onSend = vm::send,
                    onStop = vm::stop,
                    isExecuting = isExec,
                    modelLabel = header.modelLabel,
                    guiMode = guiMode,
                    onGuiModeChange = {
                        com.clawgui.ng.runtime.RuntimeContainer.settings.setGuiModeEnabled(it)
                    },
                    // Both `+` and the inline 📷 button open the same sheet —
                    // the sheet itself splits camera vs. gallery so a single
                    // entry point keeps the discovery model simple.
                    onAttach = { showAttachSheet = true },
                    onVoice = { /* TODO speech rec */ },
                    onCamera = { showAttachSheet = true },
                    onModel = onOpenModelPicker,
                )
            }
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (messages.isEmpty()) {
                EmptyDiscoverState(onPick = vm::pickCard)
            } else {
                // Only the last assistant message gets follow-up chips; older
                // bubbles staying chip-free keeps the chat clean as you scroll up.
                val latestAssistantId = messages.lastOrNull {
                    it.role == com.clawgui.ng.data.Role.ASSISTANT
                }?.id
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    item { Spacer(Modifier.height(8.dp)) }
                    items(messages, key = { it.id }) { msg ->
                        MessageBubble(
                            message = msg,
                            onRegenerate = { vm.regenerate(msg.id) },
                            showFollowUps = msg.id == latestAssistantId,
                            onPickFollowUp = vm::pickFollowUp,
                        )
                    }
                    item { Spacer(Modifier.height(12.dp)) }
                }
            }
        }
    }

    if (showAttachSheet) {
        com.clawgui.ng.ui.components.AttachmentPickerSheet(
            onPicked = { uri -> vm.attachImage(uri) },
            onDismiss = { showAttachSheet = false },
        )
    }
}

@Composable
private fun EmptyDiscoverState(onPick: (com.clawgui.ng.data.PromptCard) -> Unit) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(36.dp))
        Text(
            text = "Hi, 我是 ClawGUI ✨",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "挑一张卡片快速开始,或直接告诉我你想做什么。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
        Spacer(Modifier.height(20.dp))
        PromptCardGrid(
            cards = DefaultPromptCards.list,
            onPick = onPick,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
