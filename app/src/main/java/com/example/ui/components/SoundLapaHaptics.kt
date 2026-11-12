package com.example.ui.components

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView

class SoundLapaHaptics(
    private val context: Context,
    private val view: View,
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
        pulse(durationMs = 12L, amplitude = 85, viewFallback = HapticFeedbackConstants.CLOCK_TICK)
    }

    fun tap() {
        pulse(durationMs = 20L, amplitude = 140, viewFallback = HapticFeedbackConstants.CONTEXT_CLICK)
    }

    fun confirm() {
        pulse(durationMs = 28L, amplitude = 190, viewFallback = HapticFeedbackConstants.LONG_PRESS)
    }

    private fun pulse(durationMs: Long, amplitude: Int, viewFallback: Int) {
        runCatching {
            val v = vibrator
            if (v != null && v.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createOneShot(durationMs, amplitude))
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
    return remember(context, view) {
        SoundLapaHaptics(context, view)
    }
}
