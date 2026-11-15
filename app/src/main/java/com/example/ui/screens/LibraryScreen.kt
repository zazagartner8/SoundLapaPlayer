package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.example.data.local.ArtistSubscriptionEntity
import com.example.data.local.PlaylistEntity
import com.example.data.local.PlaylistWithTracks
import com.example.data.local.TrackEntity
import com.example.ui.MainUiState
import com.example.ui.components.CreatePlaylistDialog
import com.example.ui.components.TrackRowItem
import com.example.ui.components.rememberSoundLapaHaptics
import com.example.ui.i18n.AppStrings
import com.example.ui.i18n.LocalAppStrings
import com.example.ui.shaders.LocalBatterySaverEnabled
import com.example.ui.shaders.TrackCoverArtwork
import com.example.ui.shaders.rememberIsAppForeground
import com.example.ui.theme.LiquidRose
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun LibraryScreen(
    state: MainUiState,
    requestedSection: Int? = null,
    onConsumeRequestedSection: () -> Unit = {},
    onCreatePlaylist: (name: String, description: String) -> Unit,
    onEditPlaylist: (PlaylistEntity) -> Unit = {},
    onDeletePlaylist: (playlistId: String) -> Unit,
    onPlayPlaylist: (PlaylistWithTracks) -> Unit,
    onPlayPlaylistShuffle: (PlaylistWithTracks) -> Unit = { onPlayPlaylist(it) },
    onPlayTrack: (TrackEntity, List<TrackEntity>?) -> Unit,
    onRemoveTrackFromPlaylist: (playlistId: String, trackId: String) -> Unit,
    onToggleLike: (TrackEntity) -> Unit,
    onOpenTrackOptions: (TrackEntity) -> Unit,
    onOpenUploadSheet: () -> Unit,
    onOpenSubscribedArtist: (ArtistSubscriptionEntity) -> Unit = {},
    onToggleUnsubscribeArtist: (ArtistSubscriptionEntity) -> Unit = {},
    onPlaylistDetailOpenChanged: (Boolean) -> Unit = {},
    bottomContentPadding: Dp,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current
    val haptics = rememberSoundLapaHaptics()
    var selectedSection by remember { mutableIntStateOf(0) }
    var openedPlaylistId by remember { mutableStateOf<String?>(null) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    var pendingDeletePlaylist by remember { mutableStateOf<PlaylistWithTracks?>(null) }
    var pendingRemoveTrack by remember { mutableStateOf<Pair<String, TrackEntity>?>(null) }

    LaunchedEffect(requestedSection) {
        if (requestedSection != null) {
            selectedSection = requestedSection
            openedPlaylistId = null
            onConsumeRequestedSection()
        }
    }

    val sectionTitles = listOf(strings.playlistsTab, strings.favoritesTab, strings.myTracksTab)
    val openedPlaylist = remember(state.playlists, openedPlaylistId) {
        state.playlists.find { it.playlist.id == openedPlaylistId }
    }
    val likedTracks = remember(state.tracks) { state.tracks.filter { it.isLiked } }
    val userTracks = remember(state.tracks) { state.tracks.filter { it.isUserUpload } }

    LaunchedEffect(openedPlaylist != null) {
        onPlaylistDetailOpenChanged(openedPlaylist != null)
    }
    DisposableEffect(Unit) {
        onDispose { onPlaylistDetailOpenChanged(false) }
    }

    if (openedPlaylist != null) {
        BackHandler { openedPlaylistId = null }
    }

    if (showCreatePlaylistDialog) {
        CreatePlaylistDialog(
            onDismiss = { showCreatePlaylistDialog = false },
            onConfirm = { name, desc ->
                haptics.confirm()
                onCreatePlaylist(name, desc)
                showCreatePlaylistDialog = false
            },
        )
    }

    pendingDeletePlaylist?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDeletePlaylist = null },
            title = { Text(strings.confirmDeleteTitle) },
            text = { Text(strings.confirmDeletePlaylistMessage(target.playlist.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        haptics.confirm()
                        onDeletePlaylist(target.playlist.id)
                        if (openedPlaylistId == target.playlist.id) {
                            openedPlaylistId = null
                        }
                        pendingDeletePlaylist = null
                    },
                ) {
                    Text(strings.deleteConfirmAction, color = LiquidRose)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeletePlaylist = null }) {
                    Text(strings.cancel)
                }
            },
        )
    }

    pendingRemoveTrack?.let { (playlistId, track) ->
        AlertDialog(
            onDismissRequest = { pendingRemoveTrack = null },
            title = { Text(strings.confirmDeleteTitle) },
            text = { Text(strings.confirmRemoveFromPlaylistMessage(track.title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        haptics.confirm()
                        onRemoveTrackFromPlaylist(playlistId, track.id)
                        pendingRemoveTrack = null
                    },
                ) {
                    Text(strings.deleteConfirmAction, color = LiquidRose)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoveTrack = null }) {
                    Text(strings.cancel)
                }
            },
        )
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        val slideEase = remember { CubicBezierEasing(0.22f, 1f, 0.36f, 1f) }
        AnimatedContent(
            targetState = openedPlaylist,
            transitionSpec = {
                if (targetState != null) {
                    (slideInHorizontally(tween(290, easing = slideEase)) { it } +
                        fadeIn(tween(200))) togetherWith
                        (slideOutHorizontally(tween(260, easing = slideEase)) { -it / 3 } +
                            fadeOut(tween(170)))
                } else {
                    (slideInHorizontally(tween(290, easing = slideEase)) { -it / 2 } +
                        fadeIn(tween(200))) togetherWith
                        (slideOutHorizontally(tween(260, easing = slideEase)) { it } +
                            fadeOut(tween(170)))
                }
            },
            contentKey = { it?.playlist?.id },
            label = "libraryPlaylistTransition",
        ) { targetPlaylist ->
            if (targetPlaylist != null) {
                PlaylistDetailView(
                    playlistWithTracks = targetPlaylist,
                    state = state,
                    onBack = { openedPlaylistId = null },
                    onPlayAll = { onPlayPlaylist(targetPlaylist) },
                    onShufflePlay = { onPlayPlaylistShuffle(targetPlaylist) },
                    onEditPlaylist = { onEditPlaylist(targetPlaylist.playlist) },
                    onDeletePlaylist = {
                        pendingDeletePlaylist = targetPlaylist
                    },
                    onPlayTrack = { track -> onPlayTrack(track, targetPlaylist.tracks) },
                    onRemoveTrack = { track ->
                        pendingRemoveTrack = targetPlaylist.playlist.id to track
                    },
                    onToggleLike = onToggleLike,
                    onOpenTrackOptions = onOpenTrackOptions,
                    bottomContentPadding = bottomContentPadding,
                )
            } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 720.dp),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 6.dp,
                    bottom = bottomContentPadding,
                ),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item(key = "library_header") {
                    Text(
                        text = strings.libraryTitle,
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                item(key = "library_tabs") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        sectionTitles.forEachIndexed { index, title ->
                            val selected = selectedSection == index
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                                    )
                                    .clickable {
                                        haptics.tick()
                                        selectedSection = index
                                    }
                                    .padding(horizontal = 16.dp, vertical = 10.dp)
                                    .testTag("library_section_$index"),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                    color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                        }
                    }
                }

                when (selectedSection) {
                    0 -> {
                        if (state.playlists.isEmpty()) {
                            item(key = "empty_playlists") {
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(24.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                    contentColor = MaterialTheme.colorScheme.onSurface,
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(24.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.QueueMusic,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(38.dp),
                                        )
                                        Text(
                                            text = strings.createFirstPlaylistTitle,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                        Text(
                                            text = strings.createFirstPlaylistHint,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        OutlinedButton(
                                            onClick = {
                                                haptics.tick()
                                                showCreatePlaylistDialog = true
                                            },
                                            shape = CircleShape,
                                        ) {
                                            Icon(
                                                Icons.Rounded.Add,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                text = strings.createPlaylistButton,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            item(key = "playlists_toolbar") {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = "${strings.playlistsTab} (${state.playlists.size})",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Surface(
                                        onClick = {
                                            haptics.tick()
                                            showCreatePlaylistDialog = true
                                        },
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
                                        modifier = Modifier.testTag("playlists_create_inline_button"),
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Add,
                                                contentDescription = strings.createPlaylistButton,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(16.dp),
                                            )
                                            Text(
                                                text = strings.newPlaylistShort,
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.onSurface,
                                            )
                                        }
                                    }
                                }
                            }
                            items(
                                items = state.playlists,
                                key = { it.playlist.id },
                                contentType = { "playlist_card" },
                            ) { pl ->
                                PlaylistRowCard(
                                    playlistWithTracks = pl,
                                    onOpen = {
                                        haptics.tick()
                                        openedPlaylistId = pl.playlist.id
                                    },
                                    onPlay = {
                                        haptics.tap()
                                        onPlayPlaylist(pl)
                                    },
                                )
                            }
                        }
                    }

                    1 -> {
                        if (likedTracks.isEmpty()) {
                            item(key = "empty_liked") {
                                EmptyLibraryNotice(strings.emptyFavorites)
                            }
                        } else {
                            items(
                                items = likedTracks,
                                key = { "liked_${it.id}" },
                                contentType = { "track_row" },
                            ) { track ->
                                val isCurrent = state.playback.currentTrackId == track.id
                                TrackRowItem(
                                    track = track,
                                    isCurrentTrack = isCurrent,
                                    isPlaying = isCurrent && state.playback.isPlaying,
                                    onPlayClick = { onPlayTrack(track, likedTracks) },
                                    onLikeClick = { onToggleLike(track) },
                                    onMoreClick = { onOpenTrackOptions(track) },
                                )
                            }
                        }
                    }

                    else -> {
                        item(key = "upload_user_tracks_cta") {
                            Button(
                                onClick = {
                                    haptics.tap()
                                    onOpenUploadSheet()
                                },
                                shape = CircleShape,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                                    .testTag("library_upload_tracks_button"),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.UploadFile,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = strings.uploadOwnTracks,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (userTracks.isEmpty()) {
                            item(key = "empty_user_tracks") {
                                EmptyLibraryNotice(strings.emptyMyTracks)
                            }
                        } else {
                            items(
                                items = userTracks,
                                key = { "my_${it.id}" },
                                contentType = { "track_row" },
                            ) { track ->
                                val isCurrent = state.playback.currentTrackId == track.id
                                TrackRowItem(
                                    track = track,
                                    isCurrentTrack = isCurrent,
                                    isPlaying = isCurrent && state.playback.isPlaying,
                                    onPlayClick = { onPlayTrack(track, userTracks) },
                                    onLikeClick = { onToggleLike(track) },
                                    onMoreClick = { onOpenTrackOptions(track) },
                                )
                            }
                        }

                        if (state.subscribedArtists.isNotEmpty()) {
                            item(key = "subscribed_artists_header") {
                                Text(
                                    text = "${strings.social.subscribedArtistsHeader} (${state.subscribedArtists.size})",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    modifier = Modifier.padding(top = 10.dp),
                                )
                            }
                            items(
                                items = state.subscribedArtists,
                                key = { "sub_${it.artistKey}" },
                                contentType = { "subscribed_artist" },
                            ) { sub ->
                                SubscribedArtistRowCard(
                                    subscription = sub,
                                    onOpen = {
                                        haptics.tick()
                                        onOpenSubscribedArtist(sub)
                                    },
                                    onToggleUnsubscribe = {
                                        haptics.tick()
                                        onToggleUnsubscribeArtist(sub)
                                    },
                                )
                            }
                        } else {
                            item(key = "empty_subscribed_artists") {
                                EmptyLibraryNotice(strings.social.emptySubscribedArtists)
                            }
                        }
                    }
                }
            }
        }
        }
    }
}

private fun formatPlaylistDurationValue(
    tracks: List<TrackEntity>,
    appLanguage: String,
    strings: AppStrings,
): String {
    if (tracks.isEmpty()) return strings.playlistTotalDurationMinutes(0)
    val totalMinutes = ((tracks.sumOf { it.durationMs } / 60_000L).toInt()).coerceAtLeast(1)
    if (totalMinutes < 60) return strings.playlistTotalDurationMinutes(totalMinutes)
    val hours = totalMinutes / 60
    val remMins = totalMinutes % 60
    return when (appLanguage) {
        "ru" -> if (remMins > 0) "$hours ч $remMins мин" else "$hours ч"
        "it" -> if (remMins > 0) "$hours h $remMins min" else "$hours h"
        else -> if (remMins > 0) "${hours}h ${remMins}m" else "${hours}h"
    }
}

@Composable
private fun PlaylistRowCard(
    playlistWithTracks: PlaylistWithTracks,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
) {
    val strings = LocalAppStrings.current
    val context = LocalContext.current
    val accent = MaterialTheme.colorScheme.primary
    val backdropTrack = remember(playlistWithTracks.tracks) {
        playlistWithTracks.tracks.firstOrNull { !it.coverArtPath.isNullOrBlank() }
            ?: playlistWithTracks.tracks.firstOrNull()
    }
    val backdropCoverUrl = remember(playlistWithTracks.playlist.coverArtPath, backdropTrack) {
        playlistWithTracks.playlist.coverArtPath?.takeIf { it.isNotBlank() }
            ?: backdropTrack?.coverArtPath
    }
    val tracksCountLabel = remember(playlistWithTracks.tracks.size, strings) {
        strings.settingsV33.libraryPlaylistTracksCount(playlistWithTracks.tracks.size)
    }

    val shape = RoundedCornerShape(20.dp)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.40f),
                shape = shape,
            )
            .clickable(onClick = onOpen)
            .testTag("playlist_card_${playlistWithTracks.playlist.id}"),
        shape = shape,
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            // Subtle translucent cover backdrop without expensive blur
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        scaleX = 1.24f
                        scaleY = 1.24f
                        alpha = 0.24f
                    },
            ) {
                if (!backdropCoverUrl.isNullOrBlank()) {
                    SubcomposeAsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(backdropCoverUrl)
                            .allowHardware(true)
                            .size(180)
                            .crossfade(true)
                            .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else if (backdropTrack != null) {
                    TrackCoverArtwork(
                        track = backdropTrack,
                        cornerRadius = 0.dp,
                        targetSizePx = 140,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.linearGradient(
                                    listOf(accent.copy(alpha = 0.45f), accent.copy(alpha = 0.14f)),
                                ),
                            ),
                    )
                }
            }

            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.78f)),
            )

            Row(
                modifier = Modifier.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                PlaylistArtworkMosaic(
                    coverArtPath = playlistWithTracks.playlist.coverArtPath,
                    tracks = playlistWithTracks.tracks,
                    accentColor = accent,
                    modifier = Modifier.size(66.dp),
                    cornerRadius = 12.dp,
                )

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        text = playlistWithTracks.playlist.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = tracksCountLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (playlistWithTracks.tracks.isNotEmpty()) {
                    IconButton(
                        onClick = onPlay,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(accent),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = strings.playCountLabel(playlistWithTracks.tracks.size),
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistArtworkMosaic(
    coverArtPath: String?,
    tracks: List<TrackEntity>,
    accentColor: Color,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 14.dp,
) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(cornerRadius)
    val firstTrack = tracks.firstOrNull()
    val distinctArtTracks = remember(tracks) {
        tracks.filter { !it.coverArtPath.isNullOrBlank() }.distinctBy { it.coverArtPath }.take(4)
    }

    if (!coverArtPath.isNullOrBlank()) {
        SubcomposeAsyncImage(
            model = ImageRequest.Builder(context)
                .data(coverArtPath)
                .allowHardware(true)
                .size(420)
                .crossfade(true)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(shape),
            error = {
                PlaylistArtworkMosaicFallback(
                    distinctArtTracks = distinctArtTracks,
                    fallbackTrack = firstTrack,
                    accentColor = accentColor,
                    cornerRadius = cornerRadius,
                )
            },
        )
    } else {
        Box(modifier = modifier.clip(shape)) {
            PlaylistArtworkMosaicFallback(
                distinctArtTracks = distinctArtTracks,
                fallbackTrack = firstTrack,
                accentColor = accentColor,
                cornerRadius = cornerRadius,
            )
        }
    }
}

@Composable
private fun PlaylistArtworkMosaicFallback(
    distinctArtTracks: List<TrackEntity>,
    fallbackTrack: TrackEntity?,
    accentColor: Color,
    cornerRadius: Dp,
) {
    if (distinctArtTracks.size >= 4) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                TrackCoverArtwork(
                    track = distinctArtTracks[0],
                    modifier = Modifier.weight(1f).fillMaxSize(),
                    cornerRadius = 0.dp,
                    targetSizePx = 180,
                )
                TrackCoverArtwork(
                    track = distinctArtTracks[1],
                    modifier = Modifier.weight(1f).fillMaxSize(),
                    cornerRadius = 0.dp,
                    targetSizePx = 180,
                )
            }
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                TrackCoverArtwork(
                    track = distinctArtTracks[2],
                    modifier = Modifier.weight(1f).fillMaxSize(),
                    cornerRadius = 0.dp,
                    targetSizePx = 180,
                )
                TrackCoverArtwork(
                    track = distinctArtTracks[3],
                    modifier = Modifier.weight(1f).fillMaxSize(),
                    cornerRadius = 0.dp,
                    targetSizePx = 180,
                )
            }
        }
    } else if (fallbackTrack != null) {
        TrackCoverArtwork(
            track = fallbackTrack,
            modifier = Modifier.fillMaxSize(),
            cornerRadius = cornerRadius,
            targetSizePx = 320,
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        listOf(
                            accentColor.copy(alpha = 0.30f),
                            accentColor.copy(alpha = 0.12f),
                        ),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.QueueMusic,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(30.dp),
            )
        }
    }
}

@Composable
private fun PlaylistDetailView(
    playlistWithTracks: PlaylistWithTracks,
    state: MainUiState,
    onBack: () -> Unit,
    onPlayAll: () -> Unit,
    onShufflePlay: () -> Unit,
    onEditPlaylist: () -> Unit,
    onDeletePlaylist: () -> Unit,
    onPlayTrack: (TrackEntity) -> Unit,
    onRemoveTrack: (TrackEntity) -> Unit,
    onToggleLike: (TrackEntity) -> Unit,
    onOpenTrackOptions: (TrackEntity) -> Unit,
    bottomContentPadding: Dp,
) {
    val strings = LocalAppStrings.current
    val haptics = rememberSoundLapaHaptics()
    val firstTrack = playlistWithTracks.tracks.firstOrNull()
    val backdropTrack = remember(playlistWithTracks.tracks) {
        playlistWithTracks.tracks.firstOrNull { !it.coverArtPath.isNullOrBlank() } ?: firstTrack
    }
    val backdropCoverUrl = remember(playlistWithTracks.playlist.coverArtPath, backdropTrack) {
        playlistWithTracks.playlist.coverArtPath?.takeIf { it.isNotBlank() }
            ?: backdropTrack?.coverArtPath
    }
    val accent = MaterialTheme.colorScheme.primary
    val heroTint = backdropTrack?.let { Color(it.accentColorHex) } ?: accent
    val heroSecondary = backdropTrack?.let { Color(it.secondaryColorHex) } ?: accent
    val backgroundColor = MaterialTheme.colorScheme.background
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val collapseRangePx = remember(density) { with(density) { 270.dp.toPx() } }
    val scrollOffsetPx by remember {
        derivedStateOf {
            if (listState.firstVisibleItemIndex == 0) {
                listState.firstVisibleItemScrollOffset.toFloat()
            } else {
                collapseRangePx
            }
        }
    }
    val bannerCollapseFraction by remember {
        derivedStateOf { (scrollOffsetPx / collapseRangePx).coerceIn(0f, 1f) }
    }
    val durationLabel = remember(playlistWithTracks.tracks, state.settings.appLanguage, strings) {
        strings.playlistDurationLabel(
            formatPlaylistDurationValue(
                tracks = playlistWithTracks.tracks,
                appLanguage = state.settings.appLanguage,
                strings = strings,
            ),
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .widthIn(max = 720.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        if (bannerCollapseFraction < 0.99f) {
            PlaylistFloatingCoverBackdrop(
                coverArtUrl = backdropCoverUrl,
                fallbackTrack = backdropTrack,
                heroAccent = heroTint,
                heroSecondary = heroSecondary,
                backgroundColor = backgroundColor,
                collapseFraction = bannerCollapseFraction,
                scrollOffsetPx = scrollOffsetPx,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(370.dp)
                    .align(Alignment.TopCenter),
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 720.dp),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 4.dp,
                bottom = bottomContentPadding,
            ),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item(key = "pl_detail_hero", contentType = "playlist_hero") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        IconButton(
                            onClick = {
                                haptics.tick()
                                onBack()
                            },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.62f)),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = strings.close,
                                tint = MaterialTheme.colorScheme.onBackground,
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            IconButton(
                                onClick = {
                                    haptics.tick()
                                    onEditPlaylist()
                                },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.62f))
                                    .testTag("edit_playlist_button"),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Edit,
                                    contentDescription = strings.playerV33.editPlaylistMenu,
                                    tint = MaterialTheme.colorScheme.onBackground,
                                )
                            }

                            IconButton(
                                onClick = {
                                    haptics.tick()
                                    onDeletePlaylist()
                                },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.62f))
                                    .testTag("delete_playlist_button"),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.DeleteOutline,
                                    contentDescription = strings.deleteTrackMenu,
                                    tint = LiquidRose,
                                )
                            }
                        }
                    }

                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            tonalElevation = 10.dp,
                            shadowElevation = 18.dp,
                            color = Color.Transparent,
                        ) {
                            PlaylistArtworkMosaic(
                                coverArtPath = playlistWithTracks.playlist.coverArtPath,
                                tracks = playlistWithTracks.tracks,
                                accentColor = accent,
                                modifier = Modifier.size(184.dp),
                                cornerRadius = 18.dp,
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = playlistWithTracks.playlist.name,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onBackground,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.testTag("playlist_detail_title"),
                            )
                            if (playlistWithTracks.playlist.description.isNotBlank()) {
                                Text(
                                    text = playlistWithTracks.playlist.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                text = durationLabel,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        if (playlistWithTracks.tracks.isNotEmpty()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                IconButton(
                                    onClick = {
                                        haptics.tap()
                                        onShufflePlay()
                                    },
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.50f))
                                        .testTag("playlist_detail_shuffle_button"),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Shuffle,
                                        contentDescription = strings.playlistShuffleLabel,
                                        tint = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(22.dp),
                                    )
                                }

                                IconButton(
                                    onClick = {
                                        haptics.tap()
                                        onPlayAll()
                                    },
                                    modifier = Modifier
                                        .size(54.dp)
                                        .clip(CircleShape)
                                        .background(accent)
                                        .testTag("playlist_detail_play_button"),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.PlayArrow,
                                        contentDescription = strings.playCountLabel(playlistWithTracks.tracks.size),
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(30.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
            }

            if (playlistWithTracks.tracks.isNotEmpty()) {
                items(
                    items = playlistWithTracks.tracks,
                    key = { "pl_${it.id}" },
                    contentType = { "playlist_track_row" },
                ) { track ->
                    val isCurrent = state.playback.currentTrackId == track.id
                    TrackRowItem(
                        track = track,
                        isCurrentTrack = isCurrent,
                        isPlaying = isCurrent && state.playback.isPlaying,
                        compact = true,
                        onPlayClick = { onPlayTrack(track) },
                        onLikeClick = { onToggleLike(track) },
                        onRemoveFromPlaylistClick = { onRemoveTrack(track) },
                        onMoreClick = { onOpenTrackOptions(track) },
                    )
                }
            } else {
                item(key = "pl_empty") {
                    EmptyLibraryNotice(strings.emptyPlaylist)
                }
            }
        }
    }
}

@Composable
private fun PlaylistFloatingCoverBackdrop(
    coverArtUrl: String?,
    fallbackTrack: TrackEntity?,
    heroAccent: Color,
    heroSecondary: Color,
    backgroundColor: Color,
    collapseFraction: Float,
    scrollOffsetPx: Float,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val isForeground = rememberIsAppForeground()
    val batterySaver = LocalBatterySaverEnabled.current
    val phase = if (!batterySaver && isForeground && collapseFraction < 0.99f) {
        val infiniteTransition = rememberInfiniteTransition(label = "playlistBannerFloat")
        val animatedPhase by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = (2.0 * PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 16_000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "playlistBannerPhase",
        )
        animatedPhase
    } else {
        0f
    }

    val visibleAlpha = (1f - collapseFraction).coerceIn(0f, 1f)
    val fadeScrim = remember(backgroundColor) {
        Brush.verticalGradient(
            colors = listOf(
                backgroundColor.copy(alpha = 0.14f),
                backgroundColor.copy(alpha = 0.44f),
                backgroundColor,
            ),
        )
    }

    Box(
        modifier = modifier
            .graphicsLayer {
                alpha = visibleAlpha
                translationY = -scrollOffsetPx * 0.36f
            }
            .clip(RoundedCornerShape(0.dp))
            .testTag("playlist_floating_backdrop"),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val ampX = size.width * 0.06f
                    val ampY = size.height * 0.07f
                    translationX = sin(phase) * ampX
                    translationY = cos(phase) * ampY
                    val scale = 1.44f + 0.08f * sin(phase)
                    scaleX = scale
                    scaleY = scale
                    rotationZ = sin(phase) * 3.5f
                    alpha = 0.46f
                }
                .let { base ->
                    if (batterySaver) base
                    else base.blur(
                        radius = 26.dp,
                        edgeTreatment = BlurredEdgeTreatment.Rectangle,
                    )
                },
        ) {
            if (!coverArtUrl.isNullOrBlank()) {
                SubcomposeAsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(coverArtUrl)
                        .allowHardware(true)
                        .size(320)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (fallbackTrack != null) {
                TrackCoverArtwork(
                    track = fallbackTrack,
                    cornerRadius = 0.dp,
                    targetSizePx = 280,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                listOf(heroAccent.copy(alpha = 0.36f), heroSecondary.copy(alpha = 0.22f)),
                            ),
                        ),
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(fadeScrim),
        )
    }
}

@Composable
private fun SubscribedArtistRowCard(
    subscription: ArtistSubscriptionEntity,
    onOpen: () -> Unit,
    onToggleUnsubscribe: () -> Unit,
) {
    val strings = LocalAppStrings.current
    val context = LocalContext.current
    val accent = MaterialTheme.colorScheme.primary

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f), RoundedCornerShape(20.dp))
            .clickable(onClick = onOpen)
            .testTag("subscribed_artist_card_${subscription.artistKey}"),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.50f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                if (!subscription.avatarUrl.isNullOrBlank()) {
                    SubcomposeAsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(subscription.avatarUrl)
                            .size(160)
                            .crossfade(true)
                            .build(),
                        contentDescription = subscription.artistName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                        error = {
                            Icon(
                                imageVector = Icons.Rounded.Person,
                                contentDescription = null,
                                tint = accent,
                                modifier = Modifier.size(24.dp),
                            )
                        },
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Person,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = subscription.artistName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = strings.social.subscribedArtist,
                    style = MaterialTheme.typography.bodySmall,
                    color = accent,
                )
            }

            OutlinedButton(
                onClick = onToggleUnsubscribe,
                shape = CircleShape,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.NotificationsActive,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = strings.social.subscribedArtist,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun EmptyLibraryNotice(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(20.dp),
        )
    }
}
