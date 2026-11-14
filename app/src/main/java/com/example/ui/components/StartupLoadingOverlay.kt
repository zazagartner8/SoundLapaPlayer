package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.ui.i18n.LocalAppStrings
import com.example.ui.theme.LiquidCyan
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun StartupLoadingOverlay(
    loadedCoversCount: Int,
    totalCoversCount: Int,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current
    val accent = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.tertiary

    val infiniteTransition = rememberInfiniteTransition(label = "m3_startup_loader")
    val orbitAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "orbit_angle",
    )
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.88f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse_scale",
    )

    val targetFraction = when {
        totalCoversCount > 0 -> (0.25f + 0.75f * (loadedCoversCount.toFloat() / totalCoversCount.toFloat()))
            .coerceIn(0.25f, 1f)
        else -> 0.45f
    }
    val smoothProgress by animateFloatAsState(
        targetValue = targetFraction,
        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
        label = "startup_progress",
    )

    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag("startup_loading_overlay"),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .padding(horizontal = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                // Expressive Material Design 3 geometric halo + spinner (no app icon)
                Box(
                    modifier = Modifier.size(116.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val radius = size.minDimension * 0.42f * pulseScale
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    accent.copy(alpha = 0.24f),
                                    secondary.copy(alpha = 0.10f),
                                    Color.Transparent,
                                ),
                            ),
                            radius = size.minDimension * 0.5f,
                        )
                        rotate(orbitAngle) {
                            drawArc(
                                brush = Brush.sweepGradient(
                                    colors = listOf(
                                        accent.copy(alpha = 0.05f),
                                        accent,
                                        LiquidCyan,
                                        accent.copy(alpha = 0.10f),
                                    ),
                                ),
                                startAngle = 0f,
                                sweepAngle = 250f,
                                useCenter = false,
                                style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round),
                            )
                        }
                        val dotAngleRad = Math.toRadians((orbitAngle + 250.0))
                        val dotCenter = Offset(
                            x = center.x + (size.minDimension * 0.47f) * cos(dotAngleRad).toFloat(),
                            y = center.y + (size.minDimension * 0.47f) * sin(dotAngleRad).toFloat(),
                        )
                        drawCircle(
                            color = LiquidCyan,
                            radius = 3.5.dp.toPx(),
                            center = dotCenter,
                        )
                        drawCircle(
                            color = accent.copy(alpha = 0.12f),
                            radius = radius,
                        )
                    }

                    CircularProgressIndicator(
                        modifier = Modifier.size(54.dp),
                        color = accent,
                        trackColor = accent.copy(alpha = 0.16f),
                        strokeWidth = 4.5.dp,
                        strokeCap = StrokeCap.Round,
                    )
                }

                Spacer(modifier = Modifier.height(26.dp))

                Text(
                    text = "SoundLapa",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )

                Spacer(modifier = Modifier.height(8.dp))

                val statusText = when {
                    totalCoversCount > 0 && loadedCoversCount < totalCoversCount ->
                        strings.startupLoadingCoversStatus(loadedCoversCount, totalCoversCount)
                    totalCoversCount > 0 && loadedCoversCount >= totalCoversCount ->
                        strings.startupLoadingReadyStatus
                    else -> strings.startupLoadingTitle
                }
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(18.dp))

                LinearProgressIndicator(
                    progress = { smoothProgress },
                    color = accent,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    strokeCap = StrokeCap.Round,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(CircleShape)
                        .testTag("startup_loading_progress"),
                )
            }
        }
    }
}
