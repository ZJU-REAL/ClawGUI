package com.clawgui.ng.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween

/**
 * Motion tokens — soft, springy curves like Doubao/MIUI hyper-os.
 */
object ClawMotion {
    val EmphasizedEase = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
    val StandardEase = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
    val DecelerateEase = CubicBezierEasing(0.0f, 0.0f, 0.2f, 1.0f)

    const val DurationShort = 180
    const val DurationMedium = 260
    const val DurationLong = 420

    fun <T> shortEmphasized() = tween<T>(DurationShort, easing = EmphasizedEase)
    fun <T> mediumEmphasized() = tween<T>(DurationMedium, easing = EmphasizedEase)
    fun <T> longEmphasized() = tween<T>(DurationLong, easing = EmphasizedEase)
}
