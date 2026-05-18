package com.clawgui.ng.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightScheme = lightColorScheme(
    primary = ClawColors.Blue30,                 // ZJU校徽蓝
    onPrimary = Color.White,
    primaryContainer = ClawColors.Blue90,
    onPrimaryContainer = ClawColors.Blue15,

    secondary = ClawColors.Neutral30,
    onSecondary = Color.White,
    secondaryContainer = ClawColors.Neutral90,
    onSecondaryContainer = ClawColors.Neutral15,

    tertiary = ClawColors.Sky50,                 // accent
    onTertiary = Color.White,
    tertiaryContainer = ClawColors.Sky90,
    onTertiaryContainer = ClawColors.Sky30,

    error = ClawColors.Error,
    onError = Color.White,
    errorContainer = ClawColors.ErrorContainer,
    onErrorContainer = ClawColors.Neutral10,

    background = ClawColors.Neutral99,
    onBackground = ClawColors.Neutral10,

    surface = ClawColors.Neutral99,
    onSurface = ClawColors.Neutral10,
    surfaceVariant = ClawColors.Neutral95,
    onSurfaceVariant = ClawColors.Neutral40,

    surfaceTint = ClawColors.Blue30,
    inverseSurface = ClawColors.Neutral15,
    inverseOnSurface = ClawColors.Neutral95,
    outline = ClawColors.Neutral60,
    outlineVariant = ClawColors.Neutral90,
)

private val DarkScheme = darkColorScheme(
    primary = ClawColors.Blue70,
    onPrimary = ClawColors.Blue15,
    primaryContainer = ClawColors.Blue30,
    onPrimaryContainer = ClawColors.Blue90,

    secondary = ClawColors.Neutral80,
    onSecondary = ClawColors.Neutral15,
    secondaryContainer = ClawColors.Neutral20,
    onSecondaryContainer = ClawColors.Neutral90,

    tertiary = ClawColors.Sky70,
    onTertiary = ClawColors.Sky30,
    tertiaryContainer = ClawColors.Sky40,
    onTertiaryContainer = ClawColors.Sky90,

    error = Color(0xFFFFB4AC),
    onError = Color(0xFF690004),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = ClawColors.ErrorContainer,

    background = ClawColors.Neutral05,
    onBackground = ClawColors.Neutral95,

    surface = ClawColors.Neutral05,
    onSurface = ClawColors.Neutral95,
    surfaceVariant = ClawColors.Neutral15,
    onSurfaceVariant = ClawColors.Neutral80,

    surfaceTint = ClawColors.Blue70,
    inverseSurface = ClawColors.Neutral95,
    inverseOnSurface = ClawColors.Neutral15,
    outline = ClawColors.Neutral40,
    outlineVariant = ClawColors.Neutral20,
)

/**
 * Extra brand tokens not covered by Material3 ColorScheme.
 */
data class ClawExtraColors(
    val userBubble: Color,
    val userBubbleContent: Color,
    val assistantBubble: Color,
    val assistantBubbleContent: Color,
    val thinkingChip: Color,
    val thinkingChipContent: Color,
    val accentSoft: Color,
    val gradientStart: Color,
    val gradientEnd: Color,
    val success: Color,
    val successContainer: Color,
    val warning: Color,
    val warningContainer: Color,
)

private val LightExtras = ClawExtraColors(
    userBubble = ClawColors.Blue30,
    userBubbleContent = Color.White,
    assistantBubble = ClawColors.Neutral95,
    assistantBubbleContent = ClawColors.Neutral10,
    thinkingChip = ClawColors.Blue95,
    thinkingChipContent = ClawColors.Blue30,
    accentSoft = ClawColors.Blue90,
    gradientStart = ClawColors.Blue30,
    gradientEnd = ClawColors.Sky50,
    success = ClawColors.Success,
    successContainer = ClawColors.SuccessContainer,
    warning = ClawColors.Warning,
    warningContainer = ClawColors.WarningContainer,
)

private val DarkExtras = ClawExtraColors(
    userBubble = ClawColors.Blue40,
    userBubbleContent = Color.White,
    assistantBubble = ClawColors.Neutral15,
    assistantBubbleContent = ClawColors.Neutral95,
    thinkingChip = ClawColors.Neutral20,
    thinkingChipContent = ClawColors.Blue80,
    accentSoft = ClawColors.Blue20,
    gradientStart = ClawColors.Blue40,
    gradientEnd = ClawColors.Sky50,
    success = ClawColors.Success,
    successContainer = Color(0xFF18432D),
    warning = ClawColors.Warning,
    warningContainer = Color(0xFF52380C),
)

val LocalClawExtras = staticCompositionLocalOf { LightExtras }

@Composable
fun ClawNgTheme(
    darkTheme: Boolean = resolveDarkTheme(),
    content: @Composable () -> Unit,
) {
    val scheme = if (darkTheme) DarkScheme else LightScheme
    val extras = if (darkTheme) DarkExtras else LightExtras

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            // ComposeView may be hosted inside Activity / Service / IME / overlay.
            // Only Activities expose a styled window — guard before touching it.
            val activity = view.context.findActivity()
            val window = activity?.window
            if (window != null) {
                @Suppress("DEPRECATION")
                window.statusBarColor = Color.Transparent.toArgb()
                @Suppress("DEPRECATION")
                window.navigationBarColor = Color.Transparent.toArgb()
                val insets = WindowCompat.getInsetsController(window, view)
                insets.isAppearanceLightStatusBars = scheme.background.luminance() > 0.5f
                insets.isAppearanceLightNavigationBars = scheme.background.luminance() > 0.5f
            }
        }
    }

    CompositionLocalProvider(LocalClawExtras provides extras) {
        MaterialTheme(
            colorScheme = scheme,
            typography = ClawTypography,
            shapes = ClawShapes,
            content = content,
        )
    }
}

object ClawTheme {
    val extras: ClawExtraColors
        @Composable get() = LocalClawExtras.current
}

/**
 * Map the user's Appearance preference to a darkTheme boolean. Falls back to
 * the system setting in SYSTEM mode (and during early startup before
 * RuntimeContainer.settings exists, so the first frame of the splash doesn't
 * crash on init order).
 */
@Composable
private fun resolveDarkTheme(): Boolean {
    val systemDark = isSystemInDarkTheme()
    val pref = runCatching {
        com.clawgui.ng.runtime.RuntimeContainer.settings.appearance
    }.getOrNull() ?: return systemDark
    val mode by pref.collectAsStateWithLifecycle()
    return when (mode) {
        com.clawgui.ng.data.repo.Appearance.LIGHT -> false
        com.clawgui.ng.data.repo.Appearance.DARK -> true
        com.clawgui.ng.data.repo.Appearance.SYSTEM -> systemDark
    }
}

/**
 * Unwrap a Context until we find the hosting Activity, or null if the
 * ComposeView is hosted in a Service / IME / overlay window.
 */
internal fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
