package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.data.local.PlaylistWithTracks
import com.example.data.local.TrackEntity
import com.example.ui.MainUiState
import com.example.ui.components.AudiusConnectDialog
import com.example.ui.components.CreatePlaylistDialog
import com.example.ui.components.TrackRowItem
import com.example.ui.components.rememberSoundLapaHaptics
import com.example.ui.i18n.LocalAppStrings
import com.example.ui.shaders.TrackCoverArtwork
import com.example.ui.theme.LiquidRose

@Composable
fun LibraryScreen(
    state: MainUiState,
    onCreatePlaylist: (name: String, description: String) -> Unit,
    onDeletePlaylist: (playlistId: String) -> Unit,
    onPlayPlaylist: (PlaylistWithTracks) -> Unit,
    onPlayTrack: (TrackEntity, List<TrackEntity>?) -> Unit,
    onRemoveTrackFromPlaylist: (playlistId: String, trackId: String) -> Unit,
    onToggleLike: (TrackEntity) -> Unit,
    onOpenTrackOptions: (TrackEntity) -> Unit,
    onSyncAudiusAccount: (String) -> Unit,
    onDisconnectAudius: () -> Unit,
    bottomContentPadding: Dp,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current
    val haptics = rememberSoundLapaHaptics()
    var selectedSection by remember { mutableIntStateOf(0) }
    var openedPlaylistId by remember { mutableStateOf<String?>(null) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    var showAudiusDialog by remember { mutableStateOf(false) }

    val sectionTitles = listOf(strings.playlistsTab, strings.favoritesTab, strings.myTracksTab)
    val openedPlaylist = remember(state.playlists, openedPlaylistId) {
        state.playlists.find { it.playlist.id == openedPlaylistId }
    }
    val likedTracks = remember(state.tracks) { state.tracks.filter { it.isLiked } }
    val userTracks = remember(state.tracks) { state.tracks.filter { it.isUserUpload } }

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

    if (showAudiusDialog) {
        AudiusConnectDialog(
            currentHandle = state.settings.audiusHandle,
            isSyncing = state.audius.isSyncingAccount,
            statusMessage = state.audius.statusMessage,
            onDismiss = { showAudiusDialog = false },
            onSync = onSyncAudiusAccount,
            onDisconnect = onDisconnectAudius,
        )
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        if (openedPlaylist != null) {
            PlaylistDetailView(
                playlistWithTracks = openedPlaylist,
                state = state,
                onBack = { openedPlaylistId = null },
                onPlayAll = { onPlayPlaylist(openedPlaylist) },
                onDeletePlaylist = {
                    onDeletePlaylist(openedPlaylist.playlist.id)
                    openedPlaylistId = null
                },
                onPlayTrack = { track -> onPlayTrack(track, openedPlaylist.tracks) },
                onRemoveTrack = { trackId ->
                    onRemoveTrackFromPlaylist(openedPlaylist.playlist.id, trackId)
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = strings.libraryTitle,
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { showAudiusDialog = true },
                                shape = CircleShape,
                                modifier = Modifier.testTag("library_audius_button"),
                            ) {
                                Icon(Icons.Rounded.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(state.settings.audiusHandle?.let { "@$it" } ?: "Audius")
                            }
                            Button(
                                onClick = { showCreatePlaylistDialog = true },
                                shape = CircleShape,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                ),
                                modifier = Modifier.testTag("create_playlist_button"),
                            ) {
                                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(strings.newPlaylistShort)
                            }
                        }
                    }
                }

                item(key = "library_tabs") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        sectionTitles.forEachIndexed { index, title ->
                            val selected = selectedSection == index
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(CircleShape)
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                                    )
                                    .clickable { selectedSection = index }
                                    .padding(vertical = 10.dp)
                                    .testTag("library_section_$index"),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
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
    val firstTrack = playlistWithTracks.tracks.firstOrNull()

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .clickable(onClick = onOpen)
            .testTag("playlist_card_${playlistWithTracks.playlist.id}"),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (firstTrack != null) {
                TrackCoverArtwork(
                    track = firstTrack,
                    modifier = Modifier.size(56.dp),
                    cornerRadius = 16.dp,
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(accent.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.QueueMusic,
                        contentDescription = null,
                        tint = accent,
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = playlistWithTracks.playlist.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = strings.tracksCountLabel(playlistWithTracks.tracks.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (playlistWithTracks.tracks.isNotEmpty()) {
                IconButton(
                    onClick = onPlay,
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(accent),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = strings.playCountLabel(playlistWithTracks.tracks.size),
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaylistDetailView(
    playlistWithTracks: PlaylistWithTracks,
    state: MainUiState,
    onBack: () -> Unit,
    onPlayAll: () -> Unit,
    onDeletePlaylist: () -> Unit,
    onPlayTrack: (TrackEntity) -> Unit,
    onRemoveTrack: (String) -> Unit,
    onToggleLike: (TrackEntity) -> Unit,
    onOpenTrackOptions: (TrackEntity) -> Unit,
    bottomContentPadding: Dp,
) {
    val strings = LocalAppStrings.current
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
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "pl_detail_header") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = strings.close)
                    }
                    Column {
                        Text(
                            text = playlistWithTracks.playlist.name,
                            style = MaterialTheme.typography.titleLarge,
                        )
                        if (playlistWithTracks.playlist.description.isNotBlank()) {
                            Text(
                                text = playlistWithTracks.playlist.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                IconButton(onClick = onDeletePlaylist) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteOutline,
                        contentDescription = strings.deleteTrackMenu,
                        tint = LiquidRose,
                    )
                }
            }
        }

        if (playlistWithTracks.tracks.isNotEmpty()) {
            item(key = "pl_play_all") {
                Button(
                    onClick = onPlayAll,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(strings.playCountLabel(playlistWithTracks.tracks.size))
                }
            }

            items(playlistWithTracks.tracks, key = { "pl_${it.id}" }) { track ->
                val isCurrent = state.playback.currentTrackId == track.id
                Column {
                    TrackRowItem(
                        track = track,
                        isCurrentTrack = isCurrent,
                        isPlaying = isCurrent && state.playback.isPlaying,
                        onPlayClick = { onPlayTrack(track) },
                        onLikeClick = { onToggleLike(track) },
                        onMoreClick = { onOpenTrackOptions(track) },
                    )
                    TextButton(
                        onClick = { onRemoveTrack(track.id) },
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text(strings.removeFromPlaylist, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
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
