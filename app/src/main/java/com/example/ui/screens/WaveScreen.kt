package com.example.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.WaveUiState
import com.example.ui.components.rememberSoundLapaHaptics
import com.example.ui.i18n.LocalAppStrings
import com.example.ui.shaders.LocalBatterySaverEnabled
import com.example.ui.shaders.TrackCoverArtwork
import com.example.ui.shaders.rememberIsAppForeground
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private data class WaveChipOption(
    val key: String,
    val label: String,
)

@Composable
fun WaveScreen(
    state: WaveUiState,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSelectMood: (String) -> Unit,
    onSelectGenre: (String) -> Unit,
    onSelectLanguage: (String) -> Unit,
    onSelectVocals: (String) -> Unit,
    onResetToTaste: () -> Unit,
    bottomContentPadding: Dp,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current
    val haptics = rememberSoundLapaHaptics()
    val themeAccent = MaterialTheme.colorScheme.primary
    val secondaryGlow = state.currentWaveTrack?.let { Color(it.secondaryColorHex) }
        ?: MaterialTheme.colorScheme.tertiary
    val orbPrimary = state.currentWaveTrack?.takeIf { state.isWaveActive }?.let { Color(it.accentColorHex) }
        ?: themeAccent

    val moodOptions = remember(strings) {
        listOf(
            WaveChipOption("auto", strings.wave.moodAuto),
            WaveChipOption("energetic", strings.wave.moodEnergetic),
            WaveChipOption("chill", strings.wave.moodChill),
            WaveChipOption("sad", strings.wave.moodSad),
            WaveChipOption("focus", strings.wave.moodFocus),
        )
    }
    val genreOptions = remember(strings) {
        listOf(
            WaveChipOption("auto", strings.wave.genreAuto),
            WaveChipOption("hiphop", strings.filterHipHop),
            WaveChipOption("electronic", strings.filterElectronic),
            WaveChipOption("pop", strings.filterPop),
            WaveChipOption("rock", strings.filterRock),
            WaveChipOption("lofi", strings.filterLoFi),
            WaveChipOption("ambient", strings.filterAmbient),
            WaveChipOption("indie", strings.filterIndie),
        )
    }
    val languageOptions = remember(strings) {
        listOf(
            WaveChipOption("auto", strings.wave.langAuto),
            WaveChipOption("ru", strings.wave.langRussian),
            WaveChipOption("en", strings.wave.langForeign),
        )
    }
    val vocalOptions = remember(strings) {
        listOf(
            WaveChipOption("all", strings.wave.vocalsAll),
            WaveChipOption("with_vocals", strings.wave.vocalsWithWords),
            WaveChipOption("instrumental", strings.wave.vocalsInstrumental),
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 10.dp, bottom = bottomContentPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 640.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Header row: My Wave title + By-taste reset button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = strings.wave.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (state.config.hasCustomized) {
                            strings.wave.subtitleCustom
                        } else {
                            strings.wave.subtitlePersonal
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (state.config.hasCustomized) {
                    Surface(
                        onClick = {
                            haptics.tick()
                            onResetToTaste()
                        },
                        shape = CircleShape,
                        color = orbPrimary.copy(alpha = 0.16f),
                        modifier = Modifier
                            .heightIn(min = 40.dp)
                            .testTag("wave_reset_taste_button"),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AutoAwesome,
                                contentDescription = null,
                                tint = orbPrimary,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = strings.wave.resetToTaste,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = orbPrimary,
                            )
                        }
                    }
                }
            }

            // Centered Pulsing Wave Sphere Orb
            WavePulsingSphereOrb(
                isPlaying = state.isPlaying,
                isLoading = state.isLoading,
                primaryColor = orbPrimary,
                secondaryColor = secondaryGlow,
                onTogglePlayPause = {
                    haptics.tap()
                    onTogglePlayPause()
                },
            )

            // Active track preview pill + Skip button
            val previewTrack = state.currentWaveTrack
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        color = orbPrimary.copy(alpha = if (state.isWaveActive) 0.42f else 0.16f),
                        shape = RoundedCornerShape(20.dp),
                    )
                    .testTag("wave_active_track_card"),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (previewTrack != null) {
                        TrackCoverArtwork(
                            track = previewTrack,
                            modifier = Modifier.size(46.dp),
                            cornerRadius = 12.dp,
                            targetSizePx = 192,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (state.isLoading) strings.wave.loadingTracks else previewTrack.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = previewTrack.artist,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    } else {
                        Text(
                            text = if (state.isLoading) strings.wave.loadingTracks else strings.wave.playWaveButton,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    IconButton(
                        onClick = {
                            haptics.tap()
                            onSkipNext()
                        },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(orbPrimary.copy(alpha = 0.18f))
                            .testTag("wave_skip_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.SkipNext,
                            contentDescription = strings.wave.skipTrackButton,
                            tint = orbPrimary,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                }
            }

            // Filter sections below the orb
            WaveFilterGroupRow(
                header = strings.wave.moodHeader,
                groupKey = "mood",
                options = moodOptions,
                selectedKey = state.config.mood,
                accent = orbPrimary,
                onSelect = { key ->
                    haptics.tick()
                    onSelectMood(key)
                },
            )

            WaveFilterGroupRow(
                header = strings.wave.genreHeader,
                groupKey = "genre",
                options = genreOptions,
                selectedKey = state.config.genre,
                accent = orbPrimary,
                onSelect = { key ->
                    haptics.tick()
                    onSelectGenre(key)
                },
            )

            WaveFilterGroupRow(
                header = strings.wave.languageHeader,
                groupKey = "language",
                options = languageOptions,
                selectedKey = state.config.language,
                accent = orbPrimary,
                onSelect = { key ->
                    haptics.tick()
                    onSelectLanguage(key)
                },
            )

            WaveFilterGroupRow(
                header = strings.wave.vocalsHeader,
                groupKey = "vocals",
                options = vocalOptions,
                selectedKey = state.config.vocals,
                accent = orbPrimary,
                onSelect = { key ->
                    haptics.tick()
                    onSelectVocals(key)
                },
            )

            Spacer(modifier = Modifier.height(6.dp))
        }
    }
}

@Composable
private fun WavePulsingSphereOrb(
    isPlaying: Boolean,
    isLoading: Boolean,
    primaryColor: Color,
    secondaryColor: Color,
    onTogglePlayPause: () -> Unit,
) {
    val strings = LocalAppStrings.current
    val batterySaver = LocalBatterySaverEnabled.current
    val isForeground = rememberIsAppForeground()
    val animateOrb = !batterySaver && isForeground

    val pulseTransition = rememberInfiniteTransition(label = "waveOrb")
    val rawBreath by pulseTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (isPlaying) 1900 else 3200,
                easing = FastOutSlowInEasing,
            ),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "waveBreath",
    )
    val rawOrbit by pulseTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (isPlaying) 5400 else 9600,
                easing = LinearEasing,
            ),
            repeatMode = RepeatMode.Restart,
        ),
        label = "waveOrbit",
    )

    val breath = if (animateOrb) rawBreath else 0.35f
    val orbit = if (animateOrb) rawOrbit else 0f
    val activeScale by animateFloatAsState(
        targetValue = if (isPlaying) 1.06f else 0.96f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 240f),
        label = "waveActiveScale",
    )

    Box(
        modifier = Modifier
            .size(236.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onTogglePlayPause,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val baseRadius = size.minDimension * 0.31f * activeScale

            // Outer ambient pulse halo
            val outerRadius = baseRadius * (1.28f + breath * if (isPlaying) 0.24f else 0.12f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        primaryColor.copy(alpha = if (isPlaying) 0.38f else 0.22f),
                        secondaryColor.copy(alpha = if (isPlaying) 0.16f else 0.08f),
                        Color.Transparent,
                    ),
                    center = center,
                    radius = outerRadius,
                ),
                radius = outerRadius,
                center = center,
            )

            // Harmonic wave ring
            val ringRadius = baseRadius * (1.08f + breath * if (isPlaying) 0.14f else 0.06f)
            drawCircle(
                color = primaryColor.copy(alpha = 0.34f + breath * 0.22f),
                radius = ringRadius,
                center = center,
                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
            )

            // Orbiting energy highlight center inside sphere
            val orbDriftX = cos(orbit) * (baseRadius * 0.22f)
            val orbDriftY = sin(orbit * 1.3f) * (baseRadius * 0.18f)
            val highlightCenter = Offset(center.x + orbDriftX, center.y + orbDriftY)

            // Core glowing sphere
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.90f),
                        primaryColor.copy(alpha = 0.95f),
                        secondaryColor.copy(alpha = 0.85f),
                        primaryColor.copy(alpha = 0.55f),
                    ),
                    center = highlightCenter,
                    radius = baseRadius * 1.15f,
                ),
                radius = baseRadius,
                center = center,
            )
        }

        Box(
            modifier = Modifier
                .size(76.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.22f))
                .border(1.5.dp, Color.White.copy(alpha = 0.55f), CircleShape)
                .clickable(onClick = onTogglePlayPause)
                .testTag("wave_orb_play_pause"),
            contentAlignment = Alignment.Center,
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(64.dp),
                )
            }
            Icon(
                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (isPlaying) strings.wave.pauseWaveButton else strings.wave.playWaveButton,
                tint = Color.White,
                modifier = Modifier.size(40.dp),
            )
        }
    }
}

@Composable
private fun WaveFilterGroupRow(
    header: String,
    groupKey: String,
    options: List<WaveChipOption>,
    selectedKey: String,
    accent: Color,
    onSelect: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = header,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { option ->
                val selected = option.key == selectedKey
                Surface(
                    onClick = { onSelect(option.key) },
                    shape = CircleShape,
                    color = if (selected) {
                        accent
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
                    },
                    contentColor = if (selected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("wave_filter_${groupKey}_${option.key}"),
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = option.label,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
        }
    }
}
