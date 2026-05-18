package com.clawgui.ng.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clawgui.ng.data.ProviderProfile
import com.clawgui.ng.data.ProviderRole
import com.clawgui.ng.runtime.RuntimeContainer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerSheet(onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val providers by RuntimeContainer.settings.providers.collectAsStateWithLifecycle()
    val activeBrain by RuntimeContainer.settings.activeBrain.collectAsStateWithLifecycle()
    val activeVision by RuntimeContainer.settings.activeVision.collectAsStateWithLifecycle()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text("选择模型", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.size(16.dp))

            Text(
                "Brain · 对话大脑",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(8.dp))
            providers.filter { it.role == ProviderRole.BRAIN }.forEach { p ->
                Row(
                    Modifier.padding(vertical = 4.dp).fillMaxWidth(),
                ) {
                    ChoiceCard(p, active = p.id == activeBrain) {
                        RuntimeContainer.settings.setActiveBrain(p.id); onDismiss()
                    }
                }
            }
            Spacer(Modifier.size(20.dp))
            Text(
                "Vision · 屏幕理解",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(8.dp))
            providers.filter { it.role == ProviderRole.VISION }.forEach { p ->
                Row(
                    Modifier.padding(vertical = 4.dp).fillMaxWidth(),
                ) {
                    ChoiceCard(p, active = p.id == activeVision) {
                        RuntimeContainer.settings.setActiveVision(p.id); onDismiss()
                    }
                }
            }
            Spacer(Modifier.size(20.dp))
        }
    }
}

@Composable
private fun ChoiceCard(p: ProviderProfile, active: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = if (active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
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
                    .background(if (active) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(p.displayName, style = MaterialTheme.typography.titleMedium)
                Text(p.model, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
