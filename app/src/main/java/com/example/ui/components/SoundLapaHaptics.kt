package com.example.ui.components

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import kotlin.math.roundToInt

@Immutable
data class HapticConfig(
    val enabled: Boolean = true,
    val intensityPercent: Int = 70,
)

val LocalHapticsConfig = staticCompositionLocalOf { HapticConfig() }

class SoundLapaHaptics(
    private val context: Context,
    private val view: View,
    private val config: HapticConfig = HapticConfig(),
) {
    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    fun tick() {
        pulse(
            baseDurationMs = 14L,
            baseAmplitude = 120,
            primitiveId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                VibrationEffect.Composition.PRIMITIVE_LOW_TICK
            } else 0,
            viewFallback = HapticFeedbackConstants.CLOCK_TICK,
        )
    }

    fun tap() {
        pulse(
            baseDurationMs = 24L,
            baseAmplitude = 185,
            primitiveId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                VibrationEffect.Composition.PRIMITIVE_CLICK
            } else 0,
            viewFallback = HapticFeedbackConstants.CONTEXT_CLICK,
        )
    }

    fun confirm() {
        pulse(
            baseDurationMs = 36L,
            baseAmplitude = 245,
            primitiveId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                VibrationEffect.Composition.PRIMITIVE_CLICK
            } else 0,
            viewFallback = HapticFeedbackConstants.LONG_PRESS,
        )
    }

    fun previewIntensity(percent: Int) {
        val normalized = (percent.coerceIn(10, 100) - 10) / 90f // 0f at 10%, 1f at 100%
        val primitiveScale = 0.08f + 0.92f * normalized
        val scaledAmp = (20f + 235f * normalized).roundToInt().coerceIn(16, 255)
        val scaledDur = (5f + 38f * normalized).roundToInt().toLong().coerceIn(4L, 45L)
        vibrateNow(
            durationMs = scaledDur,
            amplitude = scaledAmp,
            primitiveId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                VibrationEffect.Composition.PRIMITIVE_CLICK
            } else 0,
            primitiveScale = primitiveScale,
            viewFallback = HapticFeedbackConstants.CONTEXT_CLICK,
        )
    }

    private fun pulse(
        baseDurationMs: Long,
        baseAmplitude: Int,
        primitiveId: Int,
        viewFallback: Int,
    ) {
        if (!config.enabled) return
        val normalized = (config.intensityPercent.coerceIn(10, 100) - 10) / 90f
        val primitiveScale = 0.08f + 0.92f * normalized
        val scaledAmp = (baseAmplitude * (0.12f + 0.88f * normalized)).roundToInt().coerceIn(16, 255)
        val scaledDur = (4f + (baseDurationMs - 4f) * (0.12f + 1.15f * normalized))
            .roundToInt().toLong().coerceIn(4L, 46L)
        vibrateNow(
            durationMs = scaledDur,
            amplitude = scaledAmp,
            primitiveId = primitiveId,
            primitiveScale = primitiveScale,
            viewFallback = viewFallback,
        )
    }

    private fun vibrateNow(
        durationMs: Long,
        amplitude: Int,
        primitiveId: Int,
        primitiveScale: Float,
        viewFallback: Int,
    ) {
        runCatching {
            val v = vibrator
            if (v != null && v.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    primitiveId != 0 &&
                    v.areAllPrimitivesSupported(primitiveId)
                ) {
                    val effect = VibrationEffect.startComposition()
                        .addPrimitive(primitiveId, primitiveScale.coerceIn(0.05f, 1.0f))
                        .compose()
                    v.vibrate(effect)
                    return@runCatching
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val effectiveAmp = if (v.hasAmplitudeControl()) {
                        amplitude.coerceIn(1, 255)
                    } else {
                        VibrationEffect.DEFAULT_AMPLITUDE
                    }
                    v.vibrate(VibrationEffect.createOneShot(durationMs, effectiveAmp))
                } else {
                    @Suppress("DEPRECATION")
                    v.vibrate(durationMs)
                }
            } else {
                view.performHapticFeedback(viewFallback)
            }
        }
    }
}

@Composable
fun rememberSoundLapaHaptics(): SoundLapaHaptics {
    val context = LocalContext.current
    val view = LocalView.current
    val config = LocalHapticsConfig.current
    return remember(context, view, config) {
        SoundLapaHaptics(context, view, config)
    }
}
