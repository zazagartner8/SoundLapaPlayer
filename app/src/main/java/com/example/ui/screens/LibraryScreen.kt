package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.example.data.local.PlaylistWithTracks
import com.example.data.local.TrackEntity
import com.example.data.remote.isTrackPlayableOffline
import com.example.ui.MainUiState
import com.example.ui.components.CreatePlaylistDialog
import com.example.ui.components.TrackRowItem
import com.example.ui.components.rememberSoundLapaHaptics
import com.example.ui.i18n.LocalAppStrings
import com.example.ui.shaders.TrackCoverArtwork
import com.example.ui.theme.LiquidRose

@Composable
fun LibraryScreen(
    state: MainUiState,
    requestedSection: Int? = null,
    onConsumeRequestedSection: () -> Unit = {},
    onCreatePlaylist: (name: String, description: String) -> Unit,
    onDeletePlaylist: (playlistId: String) -> Unit,
    onPlayPlaylist: (PlaylistWithTracks) -> Unit,
    onPlayPlaylistShuffle: (PlaylistWithTracks) -> Unit = { onPlayPlaylist(it) },
    onPlayTrack: (TrackEntity, List<TrackEntity>?) -> Unit,
    onRemoveTrackFromPlaylist: (playlistId: String, trackId: String) -> Unit,
    onToggleLike: (TrackEntity) -> Unit,
    onOpenTrackOptions: (TrackEntity) -> Unit,
    onOpenUploadSheet: () -> Unit,
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
    val cachedOfflineTracks = remember(state.tracks) {
        state.tracks.filter { !it.isUserUpload && isTrackPlayableOffline(it) }
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
                        if (userTracks.isEmpty() && cachedOfflineTracks.isEmpty()) {
                            item(key = "empty_user_tracks") {
                                EmptyLibraryNotice(strings.emptyMyTracks)
                            }
                        } else {
                            val offlineQueue = userTracks + cachedOfflineTracks
                            if (userTracks.isNotEmpty()) {
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
                                        onPlayClick = { onPlayTrack(track, offlineQueue) },
                                        onLikeClick = { onToggleLike(track) },
                                        onMoreClick = { onOpenTrackOptions(track) },
                                    )
                                }
                            }
                            if (cachedOfflineTracks.isNotEmpty()) {
                                item(key = "cached_offline_header") {
                                    Text(
                                        text = strings.offlineCachedTracksHeader,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onBackground,
                                        modifier = Modifier.padding(top = 8.dp),
                                    )
                                }
                                items(
                                    items = cachedOfflineTracks,
                                    key = { "cached_${it.id}" },
                                    contentType = { "track_row" },
                                ) { track ->
                                    val isCurrent = state.playback.currentTrackId == track.id
                                    TrackRowItem(
                                        track = track,
                                        isCurrentTrack = isCurrent,
                                        isPlaying = isCurrent && state.playback.isPlaying,
                                        onPlayClick = { onPlayTrack(track, offlineQueue) },
                                        onLikeClick = { onToggleLike(track) },
                                        onMoreClick = { onOpenTrackOptions(track) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun PlaylistRowCard(
    playlistWithTracks: PlaylistWithTracks,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
) {
    val strings = LocalAppStrings.current
    val accent = MaterialTheme.colorScheme.primary
    val totalMinutes = remember(playlistWithTracks.tracks) {
        ((playlistWithTracks.tracks.sumOf { it.durationMs } / 60_000L).toInt()).coerceAtLeast(
            if (playlistWithTracks.tracks.isNotEmpty()) 1 else 0,
        )
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onOpen)
            .testTag("playlist_card_${playlistWithTracks.playlist.id}"),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.44f),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
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
                val metaText = if (playlistWithTracks.tracks.isNotEmpty()) {
                    "${strings.tracksCountLabel(playlistWithTracks.tracks.size)} • ${strings.playlistTotalDurationMinutes(totalMinutes)}"
                } else {
                    strings.tracksCountLabel(0)
                }
                Text(
                    text = metaText,
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
    val accent = MaterialTheme.colorScheme.primary
    val heroTint = firstTrack?.let { Color(it.accentColorHex) } ?: accent
    val totalMinutes = remember(playlistWithTracks.tracks) {
        ((playlistWithTracks.tracks.sumOf { it.durationMs } / 60_000L).toInt()).coerceAtLeast(
            if (playlistWithTracks.tracks.isNotEmpty()) 1 else 0,
        )
    }

    LazyColumn(
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
        item(key = "pl_detail_hero") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                heroTint.copy(alpha = 0.24f),
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f),
                                Color.Transparent,
                            ),
                        ),
                    )
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
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
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = strings.close,
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
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                                .testTag("delete_playlist_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.DeleteOutline,
                                contentDescription = strings.deleteTrackMenu,
                                tint = LiquidRose,
                            )
                        }
                    }

                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            tonalElevation = 10.dp,
                            shadowElevation = 14.dp,
                            color = Color.Transparent,
                        ) {
                            PlaylistArtworkMosaic(
                                coverArtPath = playlistWithTracks.playlist.coverArtPath,
                                tracks = playlistWithTracks.tracks,
                                accentColor = accent,
                                modifier = Modifier.size(176.dp),
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
                                text = if (playlistWithTracks.tracks.isNotEmpty()) {
                                    "${strings.tracksCountLabel(playlistWithTracks.tracks.size)} • ${strings.playlistTotalDurationMinutes(totalMinutes)}"
                                } else {
                                    strings.tracksCountLabel(0)
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        if (playlistWithTracks.tracks.isNotEmpty()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                IconButton(
                                    onClick = {
                                        haptics.tap()
                                        onShufflePlay()
                                    },
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("playlist_detail_shuffle_button"),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Shuffle,
                                        contentDescription = strings.playlistShuffleLabel,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(24.dp),
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
            }
            Spacer(modifier = Modifier.height(6.dp))
        }

        if (playlistWithTracks.tracks.isNotEmpty()) {
            items(playlistWithTracks.tracks, key = { "pl_${it.id}" }) { track ->
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
