package com.clawgui.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ClawGUILightColors = lightColorScheme(
    primary = Color(0xFF1C47C7),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE4FF),
    onPrimaryContainer = Color(0xFF00174A),

    secondary = Color(0xFF58607A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDEE4F3),
    onSecondaryContainer = Color(0xFF151B2C),

    tertiary = Color(0xFF5168B8),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD9E2FF),
    onTertiaryContainer = Color(0xFF111D49),

    background = Color(0xFFF5F7FC),
    onBackground = Color(0xFF171C2B),
    surface = Color(0xFFF9FAFF),
    onSurface = Color(0xFF171C2B),
    surfaceVariant = Color(0xFFE3E8F5),
    onSurfaceVariant = Color(0xFF5C6378),

    outline = Color(0xFF7B839C),
    outlineVariant = Color(0xFFC7CFDF),

    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

// 整体转向克莱因蓝气质:
// 强调色接近 Klein Blue, 容器与背景降饱和，避免整屏高对比蓝造成压迫感。
private val ClawGUIDarkColors = darkColorScheme(
    primary = Color(0xFF8AA2FF),
    onPrimary = Color(0xFF00258A),
    primaryContainer = Color(0xFF0D2F9F),
    onPrimaryContainer = Color(0xFFDCE4FF),

    secondary = Color(0xFFC1C8DD),
    onSecondary = Color(0xFF2A3042),
    secondaryContainer = Color(0xFF3C4358),
    onSecondaryContainer = Color(0xFFDDE4F8),

    tertiary = Color(0xFFB6C4FF),
    onTertiary = Color(0xFF1D2B66),
    tertiaryContainer = Color(0xFF374A8D),
    onTertiaryContainer = Color(0xFFDDE4FF),

    background = Color(0xFF111522),
    onBackground = Color(0xFFE4E8F5),
    surface = Color(0xFF171C2B),
    onSurface = Color(0xFFE4E8F5),
    surfaceVariant = Color(0xFF252B3A),
    onSurfaceVariant = Color(0xFFBCC4D8),

    outline = Color(0xFF8A93A8),
    outlineVariant = Color(0xFF3B4356),

    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

@Composable
fun ClawGUITheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) ClawGUIDarkColors else ClawGUILightColors,
        typography = ClawGUITypography,
        shapes = ClawGUIShapes,
        content = content
    )
}
