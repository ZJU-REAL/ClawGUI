package com.clawgui.ng.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.clawgui.ng.data.PromptCard
import com.clawgui.ng.ui.theme.ClawCorners

@Composable
fun PromptCardGrid(
    cards: List<PromptCard>,
    onPick: (PromptCard) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier.padding(horizontal = 16.dp),
    ) {
        items(cards) { card ->
            PromptCardCell(card, onPick)
        }
    }
}

@Composable
private fun PromptCardCell(card: PromptCard, onPick: (PromptCard) -> Unit) {
    val tint = hslToColor(card.accentHue, 0.62f, 0.62f)
    val tintSoft = hslToColor(card.accentHue, 0.55f, 0.92f)
    val brush = Brush.linearGradient(listOf(tintSoft, MaterialTheme.colorScheme.surface))
    Surface(
        shape = ClawCorners.card,
        color = Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .clip(ClawCorners.card)
            .background(brush)
            .clickable { onPick(card) },
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(tint.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(card.emoji, style = MaterialTheme.typography.titleMedium)
            }
            androidx.compose.foundation.layout.Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = card.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = card.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Minimal HSL→RGB without depending on android.graphics.Color so previews work.
 */
private fun hslToColor(h: Int, s: Float, l: Float): Color {
    val hh = (h % 360 + 360) % 360 / 360f
    val q = if (l < 0.5f) l * (1 + s) else l + s - l * s
    val p = 2 * l - q
    fun hue(t0: Float): Float {
        var t = t0
        if (t < 0) t += 1f
        if (t > 1) t -= 1f
        return when {
            t < 1f / 6 -> p + (q - p) * 6 * t
            t < 1f / 2 -> q
            t < 2f / 3 -> p + (q - p) * (2f / 3 - t) * 6
            else -> p
        }
    }
    val r = hue(hh + 1f / 3)
    val g = hue(hh)
    val b = hue(hh - 1f / 3)
    return Color(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
}
