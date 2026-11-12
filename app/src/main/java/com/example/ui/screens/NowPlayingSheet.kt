package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import com.example.R
import com.example.data.local.TrackEntity
import com.example.player.PlaybackUiState
import com.example.ui.components.rememberSoundLapaHaptics
import com.example.ui.formatDuration
import com.example.ui.i18n.LocalAppStrings
import com.example.ui.shaders.FloatingCoverBackdrop
import com.example.ui.shaders.LiquidGlassCard
import com.example.ui.shaders.TrackCoverArtwork
import com.example.ui.shaders.liquidShaderTransition
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlin.math.abs

@Composable
fun NowPlayingSheet(
    track: TrackEntity,
    playback: PlaybackUiState,
    positionFlow: StateFlow<Long>,
    isInPlaylist: Boolean = false,
    onCollapse: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeekFraction: (Float) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleLike: () -> Unit,
    onOpenTrackOptions: () -> Unit,
    onAddToPlaylist: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onCollapse)

    val strings = LocalAppStrings.current
    val haptics = rememberSoundLapaHaptics()
    val themeAccent = MaterialTheme.colorScheme.primary
    val accent = Color(track.accentColorHex)
    val durationMs = playback.durationMs.takeIf { it > 0L } ?: track.durationMs

    val artworkScale by animateFloatAsState(
        targetValue = if (playback.isPlaying) 1.0f else 0.92f,
        animationSpec = spring(dampingRatio = 0.72f),
        label = "artworkScale",
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .liquidShaderTransition(playback.transitionCounter),
    ) {
        // Looping floating track cover artwork as atmospheric backdrop
        FloatingCoverBackdrop(
            track = track,
            isPlaying = playback.isPlaying,
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .widthIn(max = 600.dp)
                .align(Alignment.TopCenter)
                .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Top bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconButton(
                    onClick = {
                        haptics.tick()
                        onCollapse()
                    },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("collapse_player_button"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.cd_collapse_player),
                        tint = Color.White,
                        modifier = Modifier.size(30.dp),
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = strings.nowPlayingHeader,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.72f),
                        maxLines = 1,
                    )
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                IconButton(
                    onClick = {
                        haptics.tick()
                        onOpenTrackOptions()
                    },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("player_more_button"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MoreVert,
                        contentDescription = stringResource(R.string.cd_more_options),
                        tint = Color.White,
                    )
                }
            }

            // Cover Art centered above controls (auto-scales smoothly on any screen height)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                LiquidGlassCard(
                    modifier = Modifier
                        .sizeIn(maxWidth = 284.dp, maxHeight = 284.dp)
                        .aspectRatio(1f)
                        .scale(artworkScale),
                    shape = RoundedCornerShape(30.dp),
                    accentTint = accent,
                    elevation = 18.dp,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(7.dp),
                    ) {
                        TrackCoverArtwork(
                            track = track,
                            modifier = Modifier.fillMaxSize(),
                            cornerRadius = 24.dp,
                            targetSizePx = 640,
                        )
                    }
                }
            }

            // Track Info & Controls Block positioned at Spotify's transport height
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            text = track.title,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = track.artist,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White.copy(alpha = 0.80f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                haptics.tick()
                                onAddToPlaylist()
                            },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("player_spotify_add_button"),
                        ) {
                            Icon(
                                imageVector = if (isInPlaylist) Icons.Rounded.CheckCircle else Icons.Rounded.AddCircleOutline,
                                contentDescription = strings.addToPlaylistMenu,
                                tint = if (isInPlaylist) themeAccent else Color.White.copy(alpha = 0.90f),
                                modifier = Modifier.size(26.dp),
                            )
                        }

                        IconButton(
                            onClick = {
                                haptics.confirm()
                                onToggleLike()
                            },
                            modifier = Modifier.minimumInteractiveComponentSize(),
                        ) {
                            Icon(
                                imageVector = if (track.isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                contentDescription = stringResource(R.string.cd_like_track),
                                tint = if (track.isLiked) themeAccent else Color.White,
                                modifier = Modifier.size(26.dp),
                            )
                        }
                    }
                }

                // Smooth 60fps Material Design 3 Slider Progress Bar (isolated from full-sheet recomposition)
                SmoothPlaybackSeekBar(
                    trackId = track.id,
                    durationMs = durationMs,
                    isPlaying = playback.isPlaying,
                    positionFlow = positionFlow,
                    onSeekFraction = onSeekFraction,
                    onTickHaptic = haptics::tick,
                )

                // Spotify-style Transport Controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    SpotifyToggleControl(
                        active = playback.shuffleEnabled,
                        accentColor = themeAccent,
                        onClick = {
                            haptics.tick()
                            onToggleShuffle()
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Shuffle,
                            contentDescription = stringResource(R.string.cd_shuffle),
                            tint = if (playback.shuffleEnabled) themeAccent else Color.White.copy(alpha = 0.80f),
                            modifier = Modifier.size(24.dp),
                        )
                    }

                    IconButton(
                        onClick = {
                            haptics.tap()
                            onPrevious()
                        },
                        modifier = Modifier.size(52.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.SkipPrevious,
                            contentDescription = stringResource(R.string.cd_previous_track),
                            tint = Color.White,
                            modifier = Modifier.size(38.dp),
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(68.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                            .border(1.dp, Color.White.copy(alpha = 0.4f), CircleShape)
                            .testTag("full_player_play_pause"),
                        contentAlignment = Alignment.Center,
                    ) {
                        IconButton(
                            onClick = {
                                haptics.tap()
                                onTogglePlayPause()
                            },
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            Icon(
                                imageVector = if (playback.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = stringResource(R.string.cd_play_pause),
                                tint = Color(0xFF0E0D12),
                                modifier = Modifier.size(36.dp),
                            )
                        }
                    }

                    IconButton(
                        onClick = {
                            haptics.tap()
                            onNext()
                        },
                        modifier = Modifier.size(52.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.SkipNext,
                            contentDescription = stringResource(R.string.cd_next_track),
                            tint = Color.White,
                            modifier = Modifier.size(38.dp),
                        )
                    }

                    val repeatActive = playback.repeatMode != Player.REPEAT_MODE_OFF
                    val repeatIcon = if (playback.repeatMode == Player.REPEAT_MODE_ONE) {
                        Icons.Rounded.RepeatOne
                    } else {
                        Icons.Rounded.Repeat
                    }
                    SpotifyToggleControl(
                        active = repeatActive,
                        accentColor = themeAccent,
                        onClick = {
                            haptics.tick()
                            onCycleRepeat()
                        },
                    ) {
                        Icon(
                            imageVector = repeatIcon,
                            contentDescription = stringResource(R.string.cd_repeat),
                            tint = if (repeatActive) themeAccent else Color.White.copy(alpha = 0.80f),
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(62.dp))
        }
    }
}

@Composable
private fun SmoothPlaybackSeekBar(
    trackId: String,
    durationMs: Long,
    isPlaying: Boolean,
    positionFlow: StateFlow<Long>,
    onSeekFraction: (Float) -> Unit,
    onTickHaptic: () -> Unit,
) {
    val basePositionMs by positionFlow.collectAsStateWithLifecycle()
    var isScrubbing by remember(trackId) { mutableStateOf(false) }
    var scrubFraction by remember(trackId) { mutableFloatStateOf(0f) }
    val animFraction = remember(trackId) {
        val init = if (durationMs > 0L) (basePositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
        Animatable(if (init.isFinite()) init else 0f)
    }

    LaunchedEffect(trackId, basePositionMs, durationMs, isPlaying, isScrubbing) {
        if (isScrubbing) return@LaunchedEffect
        val target = if (durationMs > 0L) {
            (basePositionMs.toFloat() / durationMs.toFloat())
                .let { if (it.isFinite()) it.coerceIn(0f, 1f) else 0f }
        } else {
            0f
        }
        val delta = abs(target - animFraction.value)
        if (!isPlaying || delta > 0.04f || target < animFraction.value) {
            animFraction.snapTo(target)
        } else {
            animFraction.animateTo(
                targetValue = target,
                animationSpec = tween(durationMillis = 190, easing = LinearEasing),
            )
        }
    }

    val displayFraction = if (isScrubbing) {
        if (scrubFraction.isFinite()) scrubFraction.coerceIn(0f, 1f) else 0f
    } else {
        animFraction.value.let { if (it.isFinite()) it.coerceIn(0f, 1f) else 0f }
    }

    val displaySecond by remember(durationMs) {
        derivedStateOf {
            val frac = if (isScrubbing) scrubFraction else animFraction.value
            val safeFrac = if (frac.isFinite()) frac.coerceIn(0f, 1f) else 0f
            ((durationMs * safeFrac).toLong().coerceAtLeast(0L) / 1000L)
        }
    }
    val formattedCurrentTime = remember(displaySecond) { formatDuration(displaySecond * 1000L) }
    val formattedDuration = remember(durationMs) { formatDuration(durationMs) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = displayFraction,
            valueRange = 0f..1f,
            onValueChange = { value ->
                isScrubbing = true
                scrubFraction = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
            },
            onValueChangeFinished = {
                onTickHaptic()
                val target = if (scrubFraction.isFinite()) scrubFraction.coerceIn(0f, 1f) else 0f
                isScrubbing = false
                onSeekFraction(target)
            },
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color.White.copy(alpha = 0.28f),
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("player_material_slider"),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formattedCurrentTime,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.80f),
            )
            Text(
                text = formattedDuration,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.80f),
            )
        }
    }
}

@Composable
private fun SpotifyToggleControl(
    active: Boolean,
    accentColor: Color,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier.minimumInteractiveComponentSize(),
        ) {
            icon()
        }
        Box(
            modifier = Modifier
                .size(4.dp)
                .clip(CircleShape)
                .background(if (active) accentColor else Color.Transparent),
        )
    }
}
