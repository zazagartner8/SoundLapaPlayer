package com.example.ui.shaders

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.data.local.TrackEntity
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private const val LIQUID_AURORA_AGSL = """
    uniform float2 uResolution;
    uniform float uTime;
    uniform float uEnergy;
    uniform float uTransition;
    layout(color) uniform half4 uBaseColor;
    layout(color) uniform half4 uColorA;
    layout(color) uniform half4 uColorB;

    half4 main(float2 fragCoord) {
        float2 uv = fragCoord / max(uResolution, float2(1.0, 1.0));
        float aspect = uResolution.x / max(uResolution.y, 1.0);
        float2 p = (uv - 0.5) * float2(aspect, 1.0);

        float t = uTime * (0.45 + 0.35 * uEnergy);

        // Liquid wave interference fields
        float w1 = sin(p.x * 3.1 + t * 1.1 + cos(p.y * 2.7 - t * 0.8));
        float w2 = cos(p.y * 3.6 - t * 0.9 + sin(p.x * 2.2 + t * 1.3));
        float w3 = sin((p.x + p.y) * 2.8 + t * 0.7);
        float liquid = (w1 + w2 + w3) / 3.0;

        // Moving luminous liquid orbs
        float2 c1 = float2(0.32 * sin(t * 0.7), 0.28 * cos(t * 0.55));
        float2 c2 = float2(-0.34 * cos(t * 0.6), 0.32 * sin(t * 0.8));
        float d1 = exp(-3.4 * length(p - c1));
        float d2 = exp(-3.8 * length(p - c2));

        float mixA = clamp(0.45 + 0.35 * liquid + d1 * 0.65, 0.0, 1.0);
        float mixB = clamp(0.35 - 0.28 * liquid + d2 * 0.70, 0.0, 1.0);

        half3 col = mix(uBaseColor.rgb, uColorA.rgb, half(mixA * (0.48 + 0.22 * uEnergy)));
        col = mix(col, uColorB.rgb, half(mixB * (0.42 + 0.18 * uEnergy)));

        // Subtle refractive specular highlight ribbons
        float caustic = pow(clamp(1.0 - abs(w1 - w2), 0.0, 1.0), 4.0) * (0.12 + 0.10 * uEnergy);
        col += half3(caustic);

        return half4(col, 1.0);
    }
"""

@Composable
fun rememberIsAppForeground(): Boolean {
    val lifecycleOwner = LocalLifecycleOwner.current
    var isForeground by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, _ ->
            isForeground = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return isForeground
}

@Composable
fun FloatingCoverBackdrop(
    track: TrackEntity,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    val isForeground = rememberIsAppForeground()
    val phase = if (isForeground) {
        val infiniteTransition = rememberInfiniteTransition(label = "floatingCoverLoop")
        val animatedPhase by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = (2.0 * PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 18_000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "coverLoopPhase",
        )
        animatedPhase
    } else {
        0f
    }
    val motionEnergy by animateFloatAsState(
        targetValue = if (isPlaying && isForeground) 1.0f else 0.35f,
        animationSpec = tween(durationMillis = 500),
        label = "coverMotionEnergy",
    )

    val artworkKey = track.id to track.coverArtPath
    val artworkSnapshot = remember(artworkKey) { track }
    val scrimBrush = remember {
        Brush.verticalGradient(
            colors = listOf(
                Color.Black.copy(alpha = 0.18f),
                Color.Black.copy(alpha = 0.26f),
                Color.Black.copy(alpha = 0.48f),
            ),
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0A090F)),
    ) {
        Crossfade(
            targetState = artworkSnapshot,
            animationSpec = tween(durationMillis = 450),
            label = "floatingCoverCrossfade",
        ) { targetTrack ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val ampX = size.width * 0.08f * motionEnergy
                        val ampY = size.height * 0.08f * motionEnergy
                        val driftX = sin(phase) * ampX + cos(phase * 2f) * (ampX * 0.35f)
                        val driftY = cos(phase) * ampY + sin(phase * 2f) * (ampY * 0.35f)
                        val scale = 1.48f + 0.09f * sin(phase) * motionEnergy
                        scaleX = scale
                        scaleY = scale
                        translationX = driftX
                        translationY = driftY
                        rotationZ = sin(phase) * 4.0f * motionEnergy
                        alpha = 0.40f
                    }
                    .blur(
                        radius = 26.dp,
                        edgeTreatment = BlurredEdgeTreatment.Rectangle,
                    ),
            ) {
                TrackCoverArtwork(
                    track = targetTrack,
                    cornerRadius = 0.dp,
                    targetSizePx = 280,
                    crossfade = true,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // Readability scrim over the floating cover art
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(scrimBrush),
        )
    }
}

@Composable
fun LiquidShaderBackdrop(
    primaryColor: Color,
    secondaryColor: Color,
    isPlaying: Boolean,
    transitionKey: Int,
    modifier: Modifier = Modifier,
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val baseColor = MaterialTheme.colorScheme.background

    val animPrimary by animateColorAsState(
        targetValue = primaryColor,
        animationSpec = tween(durationMillis = 750),
        label = "shaderPrimary",
    )
    val animSecondary by animateColorAsState(
        targetValue = secondaryColor,
        animationSpec = tween(durationMillis = 750),
        label = "shaderSecondary",
    )
    val energy by animateFloatAsState(
        targetValue = if (isPlaying) 1.0f else 0.25f,
        animationSpec = tween(durationMillis = 600),
        label = "shaderEnergy",
    )

    val transitionPulse = remember { Animatable(0f) }
    LaunchedEffect(transitionKey) {
        if (transitionKey > 0) {
            transitionPulse.snapTo(1f)
            transitionPulse.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 850, easing = FastOutSlowInEasing),
            )
        }
    }

    val isForeground = rememberIsAppForeground()
    val timeSec = if (isForeground) {
        val infiniteTransition = rememberInfiniteTransition(label = "liquidTime")
        val animatedTimeSec by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 62.8318f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 48_000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "timeSec",
        )
        animatedTimeSec
    } else {
        0f
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        LiquidAuroraAgslCanvas(
            baseColor = baseColor,
            colorA = if (isDark) animPrimary else animPrimary.copy(alpha = 0.55f),
            colorB = if (isDark) animSecondary else animSecondary.copy(alpha = 0.50f),
            timeSec = timeSec,
            energy = energy,
            transition = transitionPulse.value,
            modifier = modifier,
        )
    } else {
        FallbackAuroraCanvas(
            baseColor = baseColor,
            colorA = animPrimary,
            colorB = animSecondary,
            timeSec = timeSec,
            energy = energy,
            isDark = isDark,
            modifier = modifier,
        )
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun LiquidAuroraAgslCanvas(
    baseColor: Color,
    colorA: Color,
    colorB: Color,
    timeSec: Float,
    energy: Float,
    transition: Float,
    modifier: Modifier = Modifier,
) {
    val shader = remember { RuntimeShader(LIQUID_AURORA_AGSL) }
    val brush = remember(shader) { ShaderBrush(shader) }

    Canvas(modifier = modifier.fillMaxSize()) {
        shader.setFloatUniform("uResolution", size.width, size.height)
        shader.setFloatUniform("uTime", timeSec)
        shader.setFloatUniform("uEnergy", energy)
        shader.setFloatUniform("uTransition", transition)
        shader.setColorUniform("uBaseColor", baseColor.toArgb())
        shader.setColorUniform("uColorA", colorA.toArgb())
        shader.setColorUniform("uColorB", colorB.toArgb())
        drawRect(brush = brush)
    }
}

@Composable
private fun FallbackAuroraCanvas(
    baseColor: Color,
    colorA: Color,
    colorB: Color,
    timeSec: Float,
    energy: Float,
    isDark: Boolean,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier
            .fillMaxSize()
            .blur(48.dp),
    ) {
        drawRect(color = baseColor)
        val orb1 = Offset(
            x = size.width * (0.5f + 0.28f * sin(timeSec * 0.6f)),
            y = size.height * (0.28f + 0.16f * cos(timeSec * 0.45f)),
        )
        val orb2 = Offset(
            x = size.width * (0.48f - 0.26f * cos(timeSec * 0.5f)),
            y = size.height * (0.68f + 0.18f * sin(timeSec * 0.55f)),
        )
        val alphaBoost = if (isDark) 0.48f else 0.28f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    colorA.copy(alpha = alphaBoost + 0.16f * energy),
                    Color.Transparent,
                ),
                center = orb1,
                radius = size.maxDimension * 0.62f,
            ),
            center = orb1,
            radius = size.maxDimension * 0.62f,
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    colorB.copy(alpha = alphaBoost + 0.14f * energy),
                    Color.Transparent,
                ),
                center = orb2,
                radius = size.maxDimension * 0.58f,
            ),
            center = orb2,
            radius = size.maxDimension * 0.58f,
        )
    }
}

@Composable
fun LiquidGlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(30.dp),
    accentTint: Color = MaterialTheme.colorScheme.primary,
    elevation: Dp = 10.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f

    val fillBrush = remember(isDark, accentTint) {
        if (isDark) {
            Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.15f),
                    accentTint.copy(alpha = 0.07f),
                    Color.White.copy(alpha = 0.05f),
                ),
            )
        } else {
            Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.86f),
                    accentTint.copy(alpha = 0.08f),
                    Color.White.copy(alpha = 0.62f),
                ),
            )
        }
    }

    val specularBorderBrush = remember(isDark, accentTint) {
        if (isDark) {
            Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.42f),
                    Color.White.copy(alpha = 0.14f),
                    accentTint.copy(alpha = 0.26f),
                ),
            )
        } else {
            Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.95f),
                    Color(0x2816131A),
                    accentTint.copy(alpha = 0.30f),
                ),
            )
        }
    }

    Surface(
        modifier = modifier
            .clip(shape)
            .then(
                if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
            ),
        shape = shape,
        color = Color.Transparent,
        tonalElevation = elevation,
        shadowElevation = if (isDark) 0.dp else 4.dp,
    ) {
        Box(
            modifier = Modifier
                .background(fillBrush, shape)
                .border(BorderStroke(1.dp, specularBorderBrush), shape),
            content = content,
        )
    }
}
