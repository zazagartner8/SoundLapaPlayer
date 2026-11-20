package com.example.ui.theme

import android.net.Uri
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import com.example.data.remote.FullStreamResolver
import java.io.File

/**
 * When true (e.g. Battery Saver is enabled), expressive motion uses tighter, shorter springs
 * and suppresses decorative ambient motion while preserving crisp interaction feedback.
 */
val LocalReducedMotion = staticCompositionLocalOf { false }

/**
 * Centralized Material 3 Expressive spring physics tokens for SoundLap.
 * Spatial springs govern position/scale/size motion; Effects springs govern alpha/color.
 */
object SoundLapMotion {
    fun <T> spatialFast() = spring<T>(
        dampingRatio = 0.78f,
        stiffness = 620f,
    )

    fun <T> spatialDefault() = spring<T>(
        dampingRatio = 0.76f,
        stiffness = 380f,
    )

    fun <T> spatialBouncy() = spring<T>(
        dampingRatio = 0.66f,
        stiffness = 460f,
    )

    fun <T> spatialGentle() = spring<T>(
        dampingRatio = 0.84f,
        stiffness = 280f,
    )

    fun <T> effectsFast() = spring<T>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = 900f,
    )

    fun <T> effectsDefault() = spring<T>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = 520f,
    )
}

/**
 * GPU-only tactile press scale micro-interaction.
 * Runs exclusively on the graphicsLayer draw phase without triggering layout passes or recompositions.
 */
fun Modifier.expressivePressScale(
    pressedScale: Float = 0.965f,
    enabled: Boolean = true,
): Modifier = composed {
    if (!enabled) return@composed this
    val reducedMotion = LocalReducedMotion.current
    if (reducedMotion) return@composed this

    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) pressedScale else 1f,
        animationSpec = if (isPressed) {
            tween(durationMillis = 85)
        } else {
            SoundLapMotion.spatialBouncy()
        },
        label = "expressive_press_scale",
    )

    this
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    awaitFirstDown(requireUnconsumed = false)
                    isPressed = true
                    waitForUpOrCancellation()
                    isPressed = false
                }
            }
        }
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
}

/**
 * Unified cover artwork data resolver for Coil ImageRequest.
 * Handles remote http(s) URLs (with artwork URL quality upgrade & optional fallback),
 * content:// and file:// URIs, and local filesystem paths (verifying existence to prevent blank squares).
 */
fun resolveCoverImageData(
    rawPath: String?,
    dataSaver: Boolean = false,
    useFallbackUrl: Boolean = false,
): Any? {
    val clean = rawPath?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (clean.startsWith("http://", ignoreCase = true) || clean.startsWith("https://", ignoreCase = true)) {
        val upgraded = FullStreamResolver.upgradeArtworkUrl(clean, dataSaver = dataSaver) ?: clean
        return if (useFallbackUrl) {
            FullStreamResolver.fallbackArtworkUrl(upgraded) ?: clean
        } else {
            upgraded
        }
    }
    if (clean.startsWith("content://", ignoreCase = true) || clean.startsWith("android.resource://", ignoreCase = true)) {
        return runCatching { Uri.parse(clean) }.getOrNull()
    }
    if (clean.startsWith("file://", ignoreCase = true)) {
        val parsedPath = runCatching { Uri.parse(clean).path }.getOrNull()
        val file = if (!parsedPath.isNullOrBlank()) File(parsedPath) else null
        return if (file != null && file.exists() && file.length() > 0L) file else null
    }
    val localFile = File(clean)
    return if (localFile.exists() && localFile.length() > 0L) localFile else null
}
