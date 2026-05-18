package com.clawgui.ng.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * ZJU-blue palette (浙大蓝). Primary is ZJU校徽蓝 `#003F88`; the secondary blue
 * `#1E5BFF` doubles as the "Claw" accent for chips and CTAs. Surfaces sit on a
 * cool neutral axis tinted very slightly blue to keep the brand feel even in
 * white areas. A muted ZJU gold (`#FFC72C` family) is reserved for highlights
 * (status dots, badges) but never used as a primary background.
 */
object ClawColors {
    // ZJU primary blue
    val Blue05 = Color(0xFF001025)
    val Blue10 = Color(0xFF001A3D)
    val Blue15 = Color(0xFF002558)
    val Blue20 = Color(0xFF003172)
    val Blue30 = Color(0xFF003F88)   // ZJU校徽蓝 — primary
    val Blue40 = Color(0xFF1450A8)
    val Blue50 = Color(0xFF2E66C9)
    val Blue60 = Color(0xFF4F82E0)
    val Blue70 = Color(0xFF7DA4F0)
    val Blue80 = Color(0xFFAEC6F8)
    val Blue90 = Color(0xFFD7E2FA)
    val Blue95 = Color(0xFFE8EFFC)
    val Blue99 = Color(0xFFF6F8FD)

    // Secondary accent — vivid sky blue, used for chips / links / focused states
    val Sky30 = Color(0xFF0E2B7A)
    val Sky40 = Color(0xFF1A40B8)
    val Sky50 = Color(0xFF1E5BFF)    // accent
    val Sky70 = Color(0xFF7AA0FF)
    val Sky90 = Color(0xFFD8E2FF)

    // Cool warm-neutral (very slight blue cast on the neutrals so it doesn't read pure gray)
    val Neutral05 = Color(0xFF0E1218)
    val Neutral10 = Color(0xFF161B22)
    val Neutral15 = Color(0xFF1F242C)
    val Neutral20 = Color(0xFF282D36)
    val Neutral30 = Color(0xFF3D4350)
    val Neutral40 = Color(0xFF565E6E)
    val Neutral50 = Color(0xFF747B8B)
    val Neutral60 = Color(0xFF939BAB)
    val Neutral80 = Color(0xFFCED4DE)
    val Neutral90 = Color(0xFFE6EAF1)
    val Neutral95 = Color(0xFFF1F3F8)
    val Neutral99 = Color(0xFFFAFBFD)

    // ZJU gold highlight
    val Gold50 = Color(0xFFFFC72C)
    val Gold80 = Color(0xFFFFE8A8)

    // Status
    val Success = Color(0xFF1F9E5C)
    val SuccessContainer = Color(0xFFCDEEDC)
    val Warning = Color(0xFFE2A30A)
    val WarningContainer = Color(0xFFFCEFCB)
    val Error = Color(0xFFD9504E)
    val ErrorContainer = Color(0xFFFFDAD8)
}
