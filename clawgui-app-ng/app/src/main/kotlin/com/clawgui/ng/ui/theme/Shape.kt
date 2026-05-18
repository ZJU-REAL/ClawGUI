package com.clawgui.ng.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Doubao-style: corners are large and friendly. Bubbles use 22dp, cards 20dp,
 * input pill 28dp. Tokens echo Material 3 but biased upward.
 */
val ClawShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

object ClawCorners {
    val pill = RoundedCornerShape(percent = 50)
    val card = RoundedCornerShape(20.dp)
    val bubbleUser = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = 22.dp, bottomEnd = 6.dp)
    val bubbleAssistant = RoundedCornerShape(topStart = 6.dp, topEnd = 22.dp, bottomStart = 22.dp, bottomEnd = 22.dp)
    val inputPill = RoundedCornerShape(28.dp)
    val capsule = RoundedCornerShape(50)
}
