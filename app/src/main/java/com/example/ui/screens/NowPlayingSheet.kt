package com.example.ui.screens

import android.view.LayoutInflater
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloseFullscreen
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.OndemandVideo
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription as semanticsContentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.R
import com.example.data.local.ExplicitContentDetector
import com.example.data.local.LocalExplicitTrackIds
import com.example.data.local.TrackEntity
import com.example.data.remote.FullStreamResolver
import com.example.data.remote.LyricsLine
import com.example.data.remote.ResolvedVideoStream
import com.example.player.PlaybackUiState
import com.example.ui.LyricsUiState
import com.example.ui.MusicVideoUiState
import com.example.ui.components.ExplicitTrackBadge
import com.example.ui.components.rememberSoundLapaHaptics
import com.example.ui.formatDuration
import com.example.ui.i18n.LocalAppStrings
import com.example.ui.shaders.FloatingCoverBackdrop
import com.example.ui.shaders.LiquidGlassCard
import com.example.ui.shaders.TrackCoverArtwork
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

@Composable
fun NowPlayingSheet(
    track: TrackEntity,
    playback: PlaybackUiState,
    positionFlow: StateFlow<Long>,
    isInPlaylist: Boolean = false,
    nextTrack: TrackEntity? = null,
    queueCount: Int = 0,
    lyricsState: LyricsUiState = LyricsUiState(),
    musicVideoState: MusicVideoUiState = MusicVideoUiState(),
    initialScrollToLyrics: Boolean = false,
    onLoadLyrics: () -> Unit = {},
    onProbeMusicVideo: () -> Unit = {},
    onSeekToMs: (Long) -> Unit = {},
    onToggleMusicVideoMode: (Boolean) -> Unit = {},
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
    onOpenQueue: () -> Unit = {},
    onShareTrack: () -> Unit = {},
    onOpenArtistProfile: () -> Unit = {},
    onDragStateChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current
    val haptics = rememberSoundLapaHaptics()
    val themeAccent = MaterialTheme.colorScheme.primary
    val accent = Color(track.accentColorHex)
    val durationMs = playback.durationMs.takeIf { it > 0L } ?: track.durationMs

    var isLyricsExpanded by rememberSaveable(track.id) { mutableStateOf(initialScrollToLyrics) }
    LaunchedEffect(initialScrollToLyrics) {
        if (initialScrollToLyrics) {
            isLyricsExpanded = true
        }
    }

    BackHandler(enabled = !isLyricsExpanded, onBack = onCollapse)

    val artworkScale by animateFloatAsState(
        targetValue = if (playback.isPlaying) 1.0f else 0.94f,
        animationSpec = spring(dampingRatio = 0.72f),
        label = "artworkScale",
    )

    val stream = musicVideoState.stream
    var clipAspectRatio by remember(track.id, stream?.videoUrl) {
        val hintRatio = if (stream != null && stream.width > 0 && stream.height > 0) {
            (stream.width.toFloat() / stream.height.toFloat()).coerceIn(0.56f, 1.85f)
        } else {
            16f / 9f
        }
        mutableFloatStateOf(hintRatio)
    }

    val targetMediaAspect = if (musicVideoState.isVideoModeEnabled && stream != null) {
        clipAspectRatio
    } else {
        1f
    }
    val animatedMediaAspect by animateFloatAsState(
        targetValue = targetMediaAspect,
        animationSpec = spring(dampingRatio = 0.84f, stiffness = 320f),
        label = "mediaAspectMorph",
    )

    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val dragOffsetY = remember { Animatable(0f) }
    var isBodyNestedDragging by remember { mutableStateOf(false) }
    val dismissThresholdPx = remember(density) { with(density) { 140.dp.toPx() } }

    LaunchedEffect(track.id) {
        onLoadLyrics()
        onProbeMusicVideo()
    }

    LaunchedEffect(scrollState.isScrollInProgress, isBodyNestedDragging) {
        if (!scrollState.isScrollInProgress && isBodyNestedDragging && dragOffsetY.value > 0f) {
            isBodyNestedDragging = false
            if (dragOffsetY.value > dismissThresholdPx) {
                haptics.tick()
                onDragStateChange(false)
                onCollapse()
            } else {
                dragOffsetY.animateTo(
                    targetValue = 0f,
                    animationSpec = spring(dampingRatio = 0.82f, stiffness = 420f),
                )
                onDragStateChange(false)
            }
        }
    }

    val sheetDismissNestedScroll = remember(dismissThresholdPx) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (dragOffsetY.value > 0f && available.y != 0f) {
                    val prev = dragOffsetY.value
                    val next = (prev + available.y).coerceAtLeast(0f)
                    isBodyNestedDragging = next > 0f
                    scope.launch {
                        dragOffsetY.snapTo(next)
                        onDragStateChange(next > 4f)
                    }
                    return Offset(0f, next - prev)
                }
                if (source == NestedScrollSource.UserInput &&
                    scrollState.value == 0 &&
                    available.y > 0f
                ) {
                    val next = (dragOffsetY.value + available.y).coerceAtLeast(0f)
                    isBodyNestedDragging = true
                    scope.launch {
                        dragOffsetY.snapTo(next)
                        onDragStateChange(next > 4f)
                    }
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (source == NestedScrollSource.UserInput &&
                    scrollState.value == 0 &&
                    available.y > 0f
                ) {
                    val next = (dragOffsetY.value + available.y).coerceAtLeast(0f)
                    isBodyNestedDragging = true
                    scope.launch {
                        dragOffsetY.snapTo(next)
                        onDragStateChange(next > 4f)
                    }
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (dragOffsetY.value > 0f) {
                    isBodyNestedDragging = false
                    val shouldDismiss = dragOffsetY.value > dismissThresholdPx ||
                        (dragOffsetY.value > 28f && available.y > 800f)
                    if (shouldDismiss) {
                        haptics.tick()
                        onDragStateChange(false)
                        onCollapse()
                    } else {
                        dragOffsetY.animateTo(
                            targetValue = 0f,
                            animationSpec = spring(dampingRatio = 0.82f, stiffness = 420f),
                        )
                        onDragStateChange(false)
                    }
                    return available
                }
                return Velocity.Zero
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { onDragStateChange(false) }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                val y = dragOffsetY.value
                translationY = y
                alpha = (1f - (y / (dismissThresholdPx * 3.0f))).coerceIn(0.45f, 1f)
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            )
            .testTag("now_playing_sheet"),
    ) {
        // Looping floating track cover artwork as atmospheric backdrop
        FloatingCoverBackdrop(
            track = track,
            isPlaying = playback.isPlaying,
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 600.dp)
                    .padding(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 0.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Drag-to-dismiss handle + top bar grouped cleanly
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .draggable(
                            orientation = Orientation.Vertical,
                            state = rememberDraggableState { delta ->
                                scope.launch {
                                    val next = (dragOffsetY.value + delta).coerceAtLeast(0f)
                                    dragOffsetY.snapTo(next)
                                    onDragStateChange(next > 4f)
                                }
                            },
                            onDragStopped = { velocity ->
                                val shouldDismiss = dragOffsetY.value > dismissThresholdPx ||
                                    (dragOffsetY.value > 32f && velocity > 900f)
                                if (shouldDismiss) {
                                    haptics.tick()
                                    onDragStateChange(false)
                                    onCollapse()
                                } else {
                                    scope.launch {
                                        dragOffsetY.animateTo(
                                            targetValue = 0f,
                                            animationSpec = spring(dampingRatio = 0.82f, stiffness = 420f),
                                        )
                                        onDragStateChange(false)
                                    }
                                }
                            },
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .width(40.dp)
                                .height(4.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.38f)),
                        )
                    }

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
                                text = playback.playbackContextTitle?.takeIf { it.isNotBlank() }
                                    ?: track.albumTitle?.takeIf { it.isNotBlank() }
                                    ?: strings.singleReleaseLabel,
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

                    val showCoverClipToggle = stream != null ||
                        (musicVideoState.isVideoModeEnabled && !musicVideoState.unavailable)
                    androidx.compose.animation.AnimatedVisibility(
                        visible = showCoverClipToggle,
                        enter = fadeIn(tween(220)) + expandVertically(tween(220)),
                        exit = fadeOut(tween(180)) + shrinkVertically(tween(180)),
                        modifier = Modifier
                            .padding(top = 2.dp, bottom = 4.dp)
                            .testTag("floating_cover_clip_switcher"),
                    ) {
                        CoverClipModeSwitcher(
                            isVideoMode = musicVideoState.isVideoModeEnabled,
                            accent = themeAccent,
                            onSelectVideoMode = { enable ->
                                haptics.tick()
                                onToggleMusicVideoMode(enable)
                            },
                        )
                    }
                }

                // Scrollable Spotify-style Now Playing Body:
                // First fold ends slightly above viewport bottom so "Предпросмотр текста" visibly peeks up!
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    val peekReserved = 66.dp
                    val firstFoldHeight = (maxHeight - peekReserved).coerceAtLeast(440.dp)
                    val isVideoModeActive = musicVideoState.isVideoModeEnabled && stream != null

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .nestedScroll(sheetDismissNestedScroll)
                            .verticalScroll(scrollState),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = firstFoldHeight)
                                .padding(top = 2.dp, bottom = 10.dp),
                            verticalArrangement = Arrangement.SpaceBetween,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            // Larger Cover Art / Strictly Centered Video Clip between header and controls
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f, fill = true)
                                    .padding(vertical = 4.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                LiquidGlassCard(
                                    modifier = Modifier
                                        .heightIn(min = 220.dp, max = 356.dp)
                                        .widthIn(max = 376.dp)
                                        .aspectRatio(
                                            ratio = animatedMediaAspect,
                                            matchHeightConstraintsFirst = animatedMediaAspect < 1f,
                                        )
                                        .scale(if (musicVideoState.isVideoModeEnabled) 1f else artworkScale),
                                    shape = RoundedCornerShape(28.dp),
                                    accentTint = accent,
                                    elevation = 20.dp,
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(if (isVideoModeActive) 0.dp else 3.dp)
                                            .clip(RoundedCornerShape(if (isVideoModeActive) 28.dp else 25.dp))
                                            .background(if (isVideoModeActive) Color(0xFF09080D) else Color.Transparent),
                                    ) {
                                        if (!isVideoModeActive) {
                                            TrackCoverArtwork(
                                                track = track,
                                                modifier = Modifier.fillMaxSize(),
                                                cornerRadius = 25.dp,
                                                targetSizePx = 1080,
                                                crossfade = true,
                                            )
                                        }

                                        if (musicVideoState.isVideoModeEnabled) {
                                            when {
                                                stream != null -> {
                                                    MutedMusicVideoPlayer(
                                                        stream = stream,
                                                        trackDurationMs = durationMs,
                                                        isPlaying = playback.isPlaying,
                                                        positionFlow = positionFlow,
                                                        onAspectRatioResolved = { resolvedRatio ->
                                                            clipAspectRatio = resolvedRatio.coerceIn(0.56f, 1.85f)
                                                        },
                                                        modifier = Modifier.fillMaxSize(),
                                                    )
                                                }
                                                musicVideoState.isLoading -> {
                                                    Box(
                                                        modifier = Modifier
                                                            .fillMaxSize()
                                                            .background(Color.Black.copy(alpha = 0.56f)),
                                                        contentAlignment = Alignment.Center,
                                                    ) {
                                                        Column(
                                                            horizontalAlignment = Alignment.CenterHorizontally,
                                                            verticalArrangement = Arrangement.spacedBy(10.dp),
                                                        ) {
                                                            CircularProgressIndicator(color = Color.White)
                                                            Text(
                                                                text = strings.playerV33.clipLoadingLabel,
                                                                style = MaterialTheme.typography.labelLarge,
                                                                color = Color.White,
                                                                textAlign = TextAlign.Center,
                                                                modifier = Modifier.padding(horizontal = 20.dp),
                                                            )
                                                        }
                                                    }
                                                }
                                                musicVideoState.unavailable -> {
                                                    Box(
                                                        modifier = Modifier
                                                            .fillMaxSize()
                                                            .background(Color.Black.copy(alpha = 0.56f)),
                                                        contentAlignment = Alignment.Center,
                                                    ) {
                                                        Text(
                                                            text = strings.playerV33.clipUnavailableLabel,
                                                            style = MaterialTheme.typography.bodyMedium,
                                                            color = Color.White.copy(alpha = 0.90f),
                                                            textAlign = TextAlign.Center,
                                                            modifier = Modifier.padding(horizontal = 24.dp),
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // Spotify-height Track Info, Seekbar, Transport Controls, Queue + Share Dock
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                val explicitIds = LocalExplicitTrackIds.current
                                val isExplicitTrack = remember(track.id, track.title, track.albumTitle, track.description, explicitIds) {
                                    ExplicitContentDetector.isExplicit(track, explicitIds)
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        horizontalAlignment = Alignment.Start,
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                                        ) {
                                            if (isExplicitTrack) {
                                                ExplicitTrackBadge(onDarkSurface = true)
                                            }
                                            Text(
                                                text = track.title,
                                                style = MaterialTheme.typography.headlineSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false),
                                            )
                                        }
                                        Row(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(10.dp))
                                                .clickable {
                                                    haptics.tick()
                                                    onOpenArtistProfile()
                                                }
                                                .padding(top = 2.dp, bottom = 2.dp, end = 4.dp)
                                                .testTag("player_artist_profile_button"),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                                        ) {
                                            Text(
                                                text = track.artist,
                                                style = MaterialTheme.typography.titleMedium,
                                                color = Color.White.copy(alpha = 0.88f),
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false),
                                            )
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                                contentDescription = strings.artistProfileMenu,
                                                tint = Color.White.copy(alpha = 0.68f),
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                    }

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
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

                                SmoothPlaybackSeekBar(
                                    trackId = track.id,
                                    durationMs = durationMs,
                                    isPlaying = playback.isPlaying,
                                    positionFlow = positionFlow,
                                    onSeekFraction = onSeekFraction,
                                    onTickHaptic = haptics::tick,
                                )

                                // Compact Spotify-style Transport Controls with custom Spotify Shuffle & Repeat icons
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    SpotifyToggleControl(
                                        active = playback.shuffleEnabled,
                                        accentColor = themeAccent,
                                        testTag = "full_player_shuffle",
                                        onClick = {
                                            haptics.tick()
                                            onToggleShuffle()
                                        },
                                    ) {
                                        SpotifyShuffleIcon(
                                            tint = if (playback.shuffleEnabled) themeAccent else Color.White.copy(alpha = 0.84f),
                                            contentDescription = stringResource(R.string.cd_shuffle),
                                            modifier = Modifier.size(22.dp),
                                        )
                                    }

                                    IconButton(
                                        onClick = {
                                            haptics.tap()
                                            onPrevious()
                                        },
                                        modifier = Modifier
                                            .size(50.dp)
                                            .testTag("full_player_previous"),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.SkipPrevious,
                                            contentDescription = stringResource(R.string.cd_previous_track),
                                            tint = Color.White,
                                            modifier = Modifier.size(36.dp),
                                        )
                                    }

                                    Box(
                                        modifier = Modifier
                                            .size(66.dp)
                                            .clip(CircleShape)
                                            .background(Color.White)
                                            .border(1.dp, Color.White.copy(alpha = 0.45f), CircleShape)
                                            .testTag("full_player_play_pause"),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (playback.isBuffering) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(62.dp),
                                                strokeWidth = 2.5.dp,
                                                color = themeAccent,
                                            )
                                        }
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
                                                modifier = Modifier.size(34.dp),
                                            )
                                        }
                                    }

                                    IconButton(
                                        onClick = {
                                            haptics.tap()
                                            onNext()
                                        },
                                        modifier = Modifier
                                            .size(50.dp)
                                            .testTag("full_player_next"),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.SkipNext,
                                            contentDescription = stringResource(R.string.cd_next_track),
                                            tint = Color.White,
                                            modifier = Modifier.size(36.dp),
                                        )
                                    }

                                    val repeatActive = playback.repeatMode != Player.REPEAT_MODE_OFF
                                    SpotifyToggleControl(
                                        active = repeatActive,
                                        accentColor = themeAccent,
                                        testTag = "full_player_repeat",
                                        onClick = {
                                            haptics.tick()
                                            onCycleRepeat()
                                        },
                                    ) {
                                        SpotifyRepeatIcon(
                                            repeatMode = playback.repeatMode,
                                            tint = if (repeatActive) themeAccent else Color.White.copy(alpha = 0.84f),
                                            contentDescription = stringResource(R.string.cd_repeat),
                                            modifier = Modifier.size(22.dp),
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                NowPlayingBottomDock(
                                    nextTrack = nextTrack,
                                    queueCount = queueCount,
                                    onOpenQueue = {
                                        haptics.tick()
                                        onOpenQueue()
                                    },
                                    onShareTrack = {
                                        haptics.tick()
                                        onShareTrack()
                                    },
                                )
                            }
                        }

                        // Spotify-style Lyrics Preview Card peeking naturally at bottom edge of first fold
                        Spacer(modifier = Modifier.height(8.dp))

                        LyricsPreviewCard(
                            lyricsState = lyricsState,
                            positionFlow = positionFlow,
                            accent = themeAccent,
                            trackAccent = accent,
                            onExpand = {
                                haptics.tick()
                                isLyricsExpanded = true
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )

                        Spacer(modifier = Modifier.height(26.dp))
                    }
                }
            }
        }

        // Fullscreen Expandable Lyrics Overlay
        AnimatedVisibility(
            visible = isLyricsExpanded,
            enter = slideInVertically(
                initialOffsetY = { it / 2 },
                animationSpec = tween(260),
            ) + fadeIn(animationSpec = tween(200)),
            exit = slideOutVertically(
                targetOffsetY = { it / 2 },
                animationSpec = tween(220),
            ) + fadeOut(animationSpec = tween(160)),
        ) {
            ExpandedLyricsOverlay(
                track = track,
                durationMs = durationMs,
                isPlaying = playback.isPlaying,
                lyricsState = lyricsState,
                positionFlow = positionFlow,
                accent = themeAccent,
                trackAccent = accent,
                onSeekToMs = { ms ->
                    haptics.tick()
                    onSeekToMs(ms)
                },
                onSeekFraction = onSeekFraction,
                onTogglePlayPause = {
                    haptics.tap()
                    onTogglePlayPause()
                },
                onCollapse = {
                    haptics.tick()
                    isLyricsExpanded = false
                },
            )
        }
    }
}

@Composable
private fun SpotifyShuffleIcon(
    tint: Color,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier.semantics {
            semanticsContentDescription = contentDescription
        },
    ) {
        val w = size.width
        val h = size.height
        val stroke = 2.dp.toPx()

        // Crossing curve 1: bottom-left -> top-right
        val p1 = Path().apply {
            moveTo(w * 0.10f, h * 0.72f)
            lineTo(w * 0.30f, h * 0.72f)
            cubicTo(w * 0.45f, h * 0.72f, w * 0.55f, h * 0.28f, w * 0.70f, h * 0.28f)
            lineTo(w * 0.88f, h * 0.28f)
        }
        drawPath(
            path = p1,
            color = tint,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )

        // Crossing curve 2 (segmented around intersection for Spotify's clean break)
        val p2a = Path().apply {
            moveTo(w * 0.10f, h * 0.28f)
            lineTo(w * 0.30f, h * 0.28f)
            lineTo(w * 0.41f, h * 0.40f)
        }
        drawPath(
            path = p2a,
            color = tint,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        val p2b = Path().apply {
            moveTo(w * 0.59f, h * 0.60f)
            lineTo(w * 0.70f, h * 0.72f)
            lineTo(w * 0.88f, h * 0.72f)
        }
        drawPath(
            path = p2b,
            color = tint,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )

        // Arrowheads
        val arrowTop = Path().apply {
            moveTo(w * 0.76f, h * 0.15f)
            lineTo(w * 0.90f, h * 0.28f)
            lineTo(w * 0.76f, h * 0.41f)
        }
        drawPath(
            path = arrowTop,
            color = tint,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        val arrowBottom = Path().apply {
            moveTo(w * 0.76f, h * 0.59f)
            lineTo(w * 0.90f, h * 0.72f)
            lineTo(w * 0.76f, h * 0.85f)
        }
        drawPath(
            path = arrowBottom,
            color = tint,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

@Composable
private fun SpotifyRepeatIcon(
    repeatMode: Int,
    tint: Color,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val isRepeatOne = repeatMode == Player.REPEAT_MODE_ONE
    Canvas(
        modifier = modifier.semantics {
            semanticsContentDescription = contentDescription
        },
    ) {
        val w = size.width
        val h = size.height
        val stroke = 2.dp.toPx()

        // Top loop: left-middle -> up -> right -> arrowhead
        val topLoop = Path().apply {
            moveTo(w * 0.14f, h * 0.52f)
            lineTo(w * 0.14f, h * 0.34f)
            cubicTo(w * 0.14f, h * 0.24f, w * 0.21f, h * 0.22f, w * 0.30f, h * 0.22f)
            lineTo(w * 0.84f, h * 0.22f)
        }
        drawPath(
            path = topLoop,
            color = tint,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        val topArrow = Path().apply {
            moveTo(w * 0.72f, h * 0.10f)
            lineTo(w * 0.86f, h * 0.22f)
            lineTo(w * 0.72f, h * 0.34f)
        }
        drawPath(
            path = topArrow,
            color = tint,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )

        // Bottom loop: right-middle -> down -> left -> arrowhead
        val bottomLoop = Path().apply {
            moveTo(w * 0.86f, h * 0.48f)
            lineTo(w * 0.86f, h * 0.66f)
            cubicTo(w * 0.86f, h * 0.76f, w * 0.79f, h * 0.78f, w * 0.70f, h * 0.78f)
            lineTo(w * 0.16f, h * 0.78f)
        }
        drawPath(
            path = bottomLoop,
            color = tint,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        val bottomArrow = Path().apply {
            moveTo(w * 0.28f, h * 0.66f)
            lineTo(w * 0.14f, h * 0.78f)
            lineTo(w * 0.28f, h * 0.90f)
        }
        drawPath(
            path = bottomArrow,
            color = tint,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )

        if (isRepeatOne) {
            val onePath = Path().apply {
                moveTo(w * 0.46f, h * 0.42f)
                lineTo(w * 0.52f, h * 0.36f)
                lineTo(w * 0.52f, h * 0.64f)
            }
            drawPath(
                path = onePath,
                color = tint,
                style = Stroke(width = stroke * 0.95f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
    }
}

@Composable
private fun LyricsPreviewCard(
    lyricsState: LyricsUiState,
    positionFlow: StateFlow<Long>,
    accent: Color,
    @Suppress("UNUSED_PARAMETER") trackAccent: Color,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current
    val positionMs by positionFlow.collectAsStateWithLifecycle()
    val isLightAccent = accent.luminance() > 0.62f
    val fgColor = if (isLightAccent) Color(0xFF111018) else Color.White

    val activeLineIndex = remember(lyricsState.syncedLines, positionMs) {
        val lines = lyricsState.syncedLines
        if (lines.isEmpty()) -1
        else lines.indexOfLast { it.timeMs <= positionMs + 250L }.coerceAtLeast(0)
    }

    val previewText = remember(lyricsState, activeLineIndex, strings) {
        when {
            lyricsState.isLoading -> strings.playerV33.lyricsLoadingLabel
            lyricsState.syncedLines.isNotEmpty() -> {
                val safeIdx = activeLineIndex.coerceIn(0, lyricsState.syncedLines.lastIndex)
                val firstLine = lyricsState.syncedLines[safeIdx].text.ifBlank { "♪" }
                val secondLine = lyricsState.syncedLines.getOrNull(safeIdx + 1)?.text
                if (!secondLine.isNullOrBlank()) "$firstLine\n$secondLine" else firstLine
            }
            !lyricsState.plainText.isNullOrBlank() -> {
                lyricsState.plainText.lines().map { it.trim() }.filter { it.isNotEmpty() }.take(2).joinToString("\n")
            }
            else -> strings.playerV33.lyricsUnavailableLabel
        }
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        accent.copy(alpha = 0.78f),
                        accent.copy(alpha = 0.46f),
                    ),
                ),
            )
            .background(Color.Black.copy(alpha = if (isLightAccent) 0.08f else 0.24f))
            .clickable(onClick = onExpand)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .testTag("player_lyrics_preview_card"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Lyrics,
                    contentDescription = null,
                    tint = fgColor,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = strings.playerV33.lyricsSectionTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = fgColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(fgColor.copy(alpha = 0.16f))
                    .clickable(onClick = onExpand)
                    .padding(horizontal = 12.dp, vertical = 7.dp)
                    .testTag("lyrics_expand_button"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    text = strings.playerV33.showLyricsButton,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = fgColor,
                    maxLines = 1,
                    softWrap = false,
                )
                Icon(
                    imageVector = Icons.Rounded.OpenInFull,
                    contentDescription = strings.playerV33.lyricsExpandCd,
                    tint = fgColor.copy(alpha = 0.92f),
                    modifier = Modifier.size(14.dp),
                )
            }
        }

        if (lyricsState.isLoading) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(
                    color = fgColor,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = previewText,
                    style = MaterialTheme.typography.titleMedium,
                    color = fgColor.copy(alpha = 0.88f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            Text(
                text = previewText,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                lineHeight = 24.sp,
                color = if (lyricsState.syncedLines.isNotEmpty() || !lyricsState.plainText.isNullOrBlank()) {
                    fgColor
                } else {
                    fgColor.copy(alpha = 0.72f)
                },
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ExpandedLyricsOverlay(
    track: TrackEntity,
    durationMs: Long,
    isPlaying: Boolean,
    lyricsState: LyricsUiState,
    positionFlow: StateFlow<Long>,
    accent: Color,
    @Suppress("UNUSED_PARAMETER") trackAccent: Color,
    onSeekToMs: (Long) -> Unit,
    onSeekFraction: (Float) -> Unit,
    onTogglePlayPause: () -> Unit,
    onCollapse: () -> Unit,
) {
    BackHandler(onBack = onCollapse)

    val strings = LocalAppStrings.current
    val haptics = rememberSoundLapaHaptics()
    val positionMs by positionFlow.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    val activeLineIndex = remember(lyricsState.syncedLines, positionMs) {
        val lines = lyricsState.syncedLines
        if (lines.isEmpty()) -1
        else lines.indexOfLast { it.timeMs <= positionMs + 250L }.coerceAtLeast(0)
    }

    LaunchedEffect(activeLineIndex) {
        if (activeLineIndex >= 0 && lyricsState.syncedLines.isNotEmpty()) {
            val viewportH = listState.layoutInfo.viewportSize.height
            val focusOffset = if (viewportH > 0) -(viewportH / 3) else -200
            listState.animateScrollToItem(
                index = activeLineIndex,
                scrollOffset = focusOffset,
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xF60A090F))
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        accent.copy(alpha = 0.36f),
                        accent.copy(alpha = 0.16f),
                        Color(0x00000000),
                    ),
                ),
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            )
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .testTag("expanded_lyrics_overlay"),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 600.dp)
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // Top bar with track title + collapse button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = track.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.78f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                IconButton(
                    onClick = onCollapse,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.12f))
                        .testTag("lyrics_collapse_button"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CloseFullscreen,
                        contentDescription = strings.playerV33.lyricsCollapseCd,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            if (lyricsState.isSynced && lyricsState.syncedLines.isNotEmpty()) {
                Text(
                    text = strings.playerV33.lyricsTapHint,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.62f),
                    modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
                )
            }

            // Full scrollable lyrics body
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                when {
                    lyricsState.isLoading -> {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator(color = accent)
                            Text(
                                text = strings.playerV33.lyricsLoadingLabel,
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color.White.copy(alpha = 0.85f),
                            )
                        }
                    }
                    lyricsState.syncedLines.isNotEmpty() -> {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(top = 56.dp, bottom = 84.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            itemsIndexed(lyricsState.syncedLines) { index, line: LyricsLine ->
                                val isActive = index == activeLineIndex
                                val distance = abs(index - activeLineIndex)
                                val targetScale = when {
                                    isActive -> 1.05f
                                    distance == 1 -> 0.96f
                                    else -> 0.92f
                                }
                                val animatedScale by animateFloatAsState(
                                    targetValue = targetScale,
                                    animationSpec = spring(dampingRatio = 0.72f, stiffness = 340f),
                                    label = "lyricLineScale_$index",
                                )
                                val glowStrength by animateFloatAsState(
                                    targetValue = if (isActive) 1f else 0f,
                                    animationSpec = tween(durationMillis = 260),
                                    label = "lyricGlow_$index",
                                )
                                val targetTextColor = when {
                                    isActive -> Color.White
                                    index < activeLineIndex -> Color.White.copy(alpha = 0.34f)
                                    distance == 1 -> Color.White.copy(alpha = 0.64f)
                                    else -> Color.White.copy(alpha = 0.46f)
                                }
                                val animatedTextColor by animateColorAsState(
                                    targetValue = targetTextColor,
                                    animationSpec = tween(durationMillis = 240),
                                    label = "lyricTextColor_$index",
                                )

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(18.dp))
                                        .background(
                                            Brush.horizontalGradient(
                                                colors = listOf(
                                                    accent.copy(alpha = 0.28f * glowStrength),
                                                    Color.White.copy(alpha = 0.10f * glowStrength),
                                                    Color.Transparent,
                                                ),
                                            ),
                                        )
                                        .clickable { onSeekToMs(line.timeMs) }
                                        .padding(horizontal = 14.dp, vertical = 12.dp)
                                        .testTag("lyrics_line_$index"),
                                ) {
                                    Text(
                                        text = line.text.ifBlank { "♪" },
                                        style = MaterialTheme.typography.titleLarge.copy(
                                            shadow = if (glowStrength > 0.05f) {
                                                Shadow(
                                                    color = accent.copy(alpha = 0.68f * glowStrength),
                                                    blurRadius = 16f * glowStrength,
                                                )
                                            } else {
                                                null
                                            },
                                        ),
                                        lineHeight = 30.sp,
                                        fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.SemiBold,
                                        color = animatedTextColor,
                                        modifier = Modifier.graphicsLayer {
                                            transformOrigin = TransformOrigin(0f, 0.5f)
                                            scaleX = animatedScale
                                            scaleY = animatedScale
                                        },
                                    )
                                }
                            }
                        }
                    }
                    !lyricsState.plainText.isNullOrBlank() -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(vertical = 16.dp),
                        ) {
                            Text(
                                text = lyricsState.plainText,
                                style = MaterialTheme.typography.titleMedium,
                                lineHeight = 28.sp,
                                color = Color.White.copy(alpha = 0.90f),
                            )
                        }
                    }
                    else -> {
                        Text(
                            text = strings.playerV33.lyricsUnavailableLabel,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White.copy(alpha = 0.68f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(24.dp),
                        )
                    }
                }
            }

            // Bottom mini progress + Play/Pause control inside expanded lyrics
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SmoothPlaybackSeekBar(
                    trackId = track.id,
                    durationMs = durationMs,
                    isPlaying = isPlaying,
                    positionFlow = positionFlow,
                    onSeekFraction = onSeekFraction,
                    onTickHaptic = haptics::tick,
                )

                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .testTag("lyrics_play_pause"),
                    contentAlignment = Alignment.Center,
                ) {
                    IconButton(
                        onClick = onTogglePlayPause,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = stringResource(R.string.cd_play_pause),
                            tint = Color(0xFF0E0D12),
                            modifier = Modifier.size(32.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NowPlayingBottomDock(
    nextTrack: TrackEntity?,
    queueCount: Int,
    onOpenQueue: () -> Unit,
    onShareTrack: () -> Unit,
) {
    val strings = LocalAppStrings.current
    val pillShape = RoundedCornerShape(18.dp)
    val glassBg = Color.White.copy(alpha = 0.11f)
    val glassBorder = Color.White.copy(alpha = 0.16f)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .clip(pillShape)
                .background(glassBg)
                .border(1.dp, glassBorder, pillShape)
                .clickable(onClick = onOpenQueue)
                .padding(horizontal = 10.dp, vertical = 7.dp)
                .testTag("player_queue_pill"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (nextTrack != null) {
                TrackCoverArtwork(
                    track = nextTrack,
                    modifier = Modifier.size(32.dp),
                    cornerRadius = 10.dp,
                    targetSizePx = 140,
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.QueueMusic,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.88f),
                    modifier = Modifier.size(20.dp),
                )
            }
            Text(
                text = if (nextTrack != null) {
                    strings.nextInQueueLabel(nextTrack.title)
                } else {
                    strings.queueButtonLabel(queueCount)
                },
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = Color.White.copy(alpha = 0.92f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.Rounded.QueueMusic,
                contentDescription = strings.queueSheetTitle,
                tint = Color.White.copy(alpha = 0.72f),
                modifier = Modifier.size(18.dp),
            )
        }

        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(glassBg)
                .border(1.dp, glassBorder, CircleShape)
                .clickable(onClick = onShareTrack)
                .testTag("player_share_button"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Share,
                contentDescription = strings.shareTrackAction,
                tint = Color.White.copy(alpha = 0.90f),
                modifier = Modifier.size(20.dp),
            )
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
    val scope = rememberCoroutineScope()
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
        ContinuousPlaybackSlider(
            value = displayFraction,
            onValueChange = { next ->
                isScrubbing = true
                scrubFraction = if (next.isFinite()) next.coerceIn(0f, 1f) else 0f
            },
            onValueChangeFinished = {
                onTickHaptic()
                val target = if (scrubFraction.isFinite()) scrubFraction.coerceIn(0f, 1f) else 0f
                scope.launch {
                    animFraction.snapTo(target)
                    isScrubbing = false
                    onSeekFraction(target)
                }
            },
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
                fontWeight = if (isScrubbing) FontWeight.Bold else FontWeight.Medium,
                color = if (isScrubbing) Color.White else Color.White.copy(alpha = 0.80f),
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
private fun ContinuousPlaybackSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    val safeValue = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
    val primary = MaterialTheme.colorScheme.primary
    Slider(
        value = safeValue,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        colors = SliderDefaults.colors(
            thumbColor = primary,
            activeTrackColor = primary,
            inactiveTrackColor = Color.White.copy(alpha = 0.28f),
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("player_material_slider"),
    )
}

@Composable
private fun CoverClipModeSwitcher(
    isVideoMode: Boolean,
    accent: Color,
    onSelectVideoMode: (Boolean) -> Unit,
) {
    val strings = LocalAppStrings.current
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.52f))
            .border(1.dp, Color.White.copy(alpha = 0.24f), CircleShape)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ModeSegmentPill(
            selected = !isVideoMode,
            icon = Icons.Rounded.Image,
            label = strings.playerV33.coverToggleLabel,
            accent = accent,
            testTag = "player_cover_mode_button",
            onClick = { onSelectVideoMode(false) },
        )
        ModeSegmentPill(
            selected = isVideoMode,
            icon = Icons.Rounded.OndemandVideo,
            label = strings.playerV33.clipToggleLabel,
            accent = accent,
            testTag = "player_clip_mode_button",
            onClick = { onSelectVideoMode(true) },
        )
    }
}

@Composable
private fun ModeSegmentPill(
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    accent: Color,
    testTag: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .heightIn(min = 34.dp)
            .clip(CircleShape)
            .background(if (selected) accent else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = Color.White,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun MutedMusicVideoPlayer(
    stream: ResolvedVideoStream,
    trackDurationMs: Long,
    isPlaying: Boolean,
    positionFlow: StateFlow<Long>,
    onAspectRatioResolved: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val audioPositionMs by positionFlow.collectAsStateWithLifecycle()
    val latestIsPlaying by rememberUpdatedState(isPlaying)
    val latestTrackDurationMs by rememberUpdatedState(trackDurationMs)
    var firstFrameRendered by remember(stream.videoUrl) { mutableStateOf(false) }
    var lastVideoSeekAtMs by remember(stream.videoUrl) { mutableLongStateOf(0L) }
    var lastAppliedSpeed by remember(stream.videoUrl) { mutableFloatStateOf(1f) }
    val videoAlpha by animateFloatAsState(
        targetValue = if (firstFrameRendered) 1f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "videoFadeIn",
    )

    val exoPlayer = remember(stream.videoUrl) {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(4_000, 16_000, 120, 320)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(12_000)
            .apply {
                if (stream.headers.isNotEmpty()) {
                    setDefaultRequestProperties(stream.headers)
                }
            }
        ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            .build()
            .apply {
                setSeekParameters(SeekParameters.CLOSEST_SYNC)
                setMediaItem(MediaItem.fromUri(stream.videoUrl))
                repeatMode = Player.REPEAT_MODE_ONE
                volume = 0f
                playWhenReady = latestIsPlaying
                prepare()
            }
    }

    fun syncPlayerPositionAndRate(
        audioPosMs: Long,
        forceImmediateThresholdMs: Long = 450L,
    ) {
        if (exoPlayer.playbackState != Player.STATE_READY) return
        val clipDurationMs = exoPlayer.duration.takeIf { it > 0L } ?: stream.durationMs
        if (clipDurationMs <= 0L) return

        val desiredSpeed = FullStreamResolver.computeSynchronizedVideoPlaybackSpeed(
            audioDurationMs = latestTrackDurationMs,
            clipDurationMs = clipDurationMs,
        )
        if (abs(desiredSpeed - lastAppliedSpeed) > 0.008f) {
            lastAppliedSpeed = desiredSpeed
            exoPlayer.playbackParameters = PlaybackParameters(desiredSpeed)
        }

        val target = FullStreamResolver.computeSynchronizedVideoPositionMs(
            audioPositionMs = audioPosMs,
            audioDurationMs = latestTrackDurationMs,
            clipDurationMs = clipDurationMs,
        )
        val drift = abs(exoPlayer.currentPosition - target)
        val now = System.currentTimeMillis()
        val largeJump = drift > 900L
        if (largeJump || (drift > forceImmediateThresholdMs && (now - lastVideoSeekAtMs) > 400L)) {
            lastVideoSeekAtMs = now
            exoPlayer.seekTo(target)
        }
    }

    // Strict pause / resume lockstep + instant resync on play state toggle
    LaunchedEffect(isPlaying, exoPlayer) {
        exoPlayer.playWhenReady = isPlaying
        if (isPlaying) {
            exoPlayer.play()
        } else {
            exoPlayer.pause()
        }
        syncPlayerPositionAndRate(positionFlow.value, forceImmediateThresholdMs = 180L)
    }

    LaunchedEffect(audioPositionMs, trackDurationMs, isPlaying, exoPlayer) {
        if (!isPlaying) return@LaunchedEffect
        syncPlayerPositionAndRate(audioPositionMs, forceImmediateThresholdMs = 450L)
    }

    DisposableEffect(lifecycleOwner, exoPlayer) {
        val playerListener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    val ratio = (videoSize.width * videoSize.pixelWidthHeightRatio) / videoSize.height.toFloat()
                    onAspectRatioResolved(ratio.coerceIn(0.56f, 1.85f))
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    exoPlayer.playWhenReady = latestIsPlaying
                    if (latestIsPlaying) exoPlayer.play() else exoPlayer.pause()
                    syncPlayerPositionAndRate(positionFlow.value, forceImmediateThresholdMs = 220L)
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                syncPlayerPositionAndRate(positionFlow.value, forceImmediateThresholdMs = 300L)
            }

            override fun onRenderedFirstFrame() {
                firstFrameRendered = true
            }
        }
        exoPlayer.addListener(playerListener)

        val lifecycleObserver = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    exoPlayer.playWhenReady = latestIsPlaying
                    if (latestIsPlaying) {
                        exoPlayer.play()
                    } else {
                        exoPlayer.pause()
                    }
                    syncPlayerPositionAndRate(positionFlow.value, forceImmediateThresholdMs = 200L)
                }
                Lifecycle.Event.ON_STOP -> {
                    exoPlayer.playWhenReady = false
                    exoPlayer.pause()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)

        onDispose {
            exoPlayer.removeListener(playerListener)
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            exoPlayer.release()
        }
    }

    AndroidView(
        factory = { ctx ->
            (LayoutInflater.from(ctx).inflate(R.layout.video_player_texture, null, false) as PlayerView).apply {
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                player = exoPlayer
            }
        },
        update = { view ->
            if (view.resizeMode != AspectRatioFrameLayout.RESIZE_MODE_ZOOM) {
                view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            }
            if (view.player !== exoPlayer) {
                view.player = exoPlayer
            }
        },
        modifier = modifier.alpha(videoAlpha),
    )
}

@Composable
private fun SpotifyToggleControl(
    active: Boolean,
    accentColor: Color,
    testTag: String,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag(testTag),
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
