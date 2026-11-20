package com.example.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.i18n.LocalAppStrings
import com.example.ui.theme.LocalReducedMotion
import com.example.ui.theme.SoundLapMotion
import kotlin.math.roundToInt

enum class StartupBootStage {
    INITIALIZING,
    HYDRATING_FEED,
    WARMING_COVERS,
    READY,
}

@Immutable
data class StartupProgressState(
    val progress: Float = 0.08f,
    val stage: StartupBootStage = StartupBootStage.INITIALIZING,
    val loadedCovers: Int = 0,
    val totalCovers: Int = 0,
) {
    val percentInt: Int
        get() = (progress.coerceIn(0f, 1f) * 100f).roundToInt()
}

/**
 * Minimalist Material Design 3 startup screen featuring the official SoundLap app logo
 * centered inside a determinate circular progress ring with expressive spring motion.
 */
@Composable
fun StartupLoadingOverlay(
    state: StartupProgressState,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current
    val colorScheme = MaterialTheme.colorScheme
    val reducedMotion = LocalReducedMotion.current

    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }

    val entranceScale by animateFloatAsState(
        targetValue = if (entered) 1f else 0.86f,
        animationSpec = SoundLapMotion.spatialDefault(),
        label = "startup_entrance_scale",
    )
    val entranceAlpha by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = SoundLapMotion.effectsDefault(),
        label = "startup_entrance_alpha",
    )

    val animatedProgress by animateFloatAsState(
        targetValue = state.progress.coerceIn(0.04f, 1f),
        animationSpec = SoundLapMotion.spatialGentle(),
        label = "m3_startup_ring_progress",
    )
    val displayPercent = (animatedProgress * 100f).roundToInt().coerceIn(0, 100)

    val logoPulseScale = if (reducedMotion) {
        1f
    } else {
        val pulseTransition = rememberInfiniteTransition(label = "startup_logo_pulse")
        val pulse by pulseTransition.animateFloat(
            initialValue = 0.97f,
            targetValue = 1.03f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1500),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "startup_logo_pulse_scale",
        )
        pulse
    }

    val stageLabel = when (state.stage) {
        StartupBootStage.INITIALIZING -> strings.startup.stageLocalInit
        StartupBootStage.HYDRATING_FEED -> strings.startup.stageFeedHydration
        StartupBootStage.WARMING_COVERS -> if (state.totalCovers > 0) {
            strings.startup.coversStatus(state.loadedCovers, state.totalCovers)
        } else {
            strings.startup.stageFeedHydration
        }
        StartupBootStage.READY -> strings.startup.readyStatus
    }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag("startup_loading_overlay"),
        color = colorScheme.surface,
        contentColor = colorScheme.onSurface,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .padding(horizontal = 32.dp)
                    .graphicsLayer {
                        scaleX = entranceScale
                        scaleY = entranceScale
                        alpha = entranceAlpha
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    modifier = Modifier.size(124.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("startup_loading_progress"),
                        color = colorScheme.primary,
                        trackColor = colorScheme.surfaceContainerHighest,
                        strokeWidth = 5.dp,
                        strokeCap = StrokeCap.Round,
                    )

                    Box(
                        modifier = Modifier
                            .size(92.dp)
                            .graphicsLayer {
                                scaleX = logoPulseScale
                                scaleY = logoPulseScale
                            }
                            .clip(CircleShape)
                            .background(colorScheme.surfaceContainerHigh),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_soundlapa_foreground_img),
                            contentDescription = "SoundLap",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(CircleShape)
                                .testTag("startup_app_logo"),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "SoundLap",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colorScheme.onSurface,
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "$displayPercent%",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontFeatureSettings = "tnum",
                    ),
                    fontWeight = FontWeight.Bold,
                    color = colorScheme.primary,
                    modifier = Modifier.testTag("startup_loading_percent"),
                )

                Spacer(modifier = Modifier.height(6.dp))

                AnimatedContent(
                    targetState = stageLabel,
                    transitionSpec = {
                        fadeIn(animationSpec = SoundLapMotion.effectsFast()) togetherWith
                            fadeOut(animationSpec = SoundLapMotion.effectsFast())
                    },
                    label = "startup_stage_label",
                ) { label ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.testTag("startup_loading_stage"),
                    )
                }
            }
        }
    }
}
