package com.clawgui.ng.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.clawgui.ng.R

@Composable
fun AssistantAvatar(size: Int = 28) {
    // ZJU-blue ring with the launcher C-mark inside.
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(androidx.compose.ui.graphics.Color(0xFF003F88)),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = "ClawGUI",
            modifier = Modifier.size((size * 1.05f).dp),
        )
    }
}

@Composable
fun InitialAvatar(text: String, size: Int = 32) {
    val ch = text.take(1).ifBlank { "U" }
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = ch,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}
