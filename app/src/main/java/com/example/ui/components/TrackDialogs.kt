package com.example.ui.components

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.OndemandVideo
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.data.local.PlaylistEntity
import com.example.data.local.PlaylistMetadataHelper
import com.example.data.local.PlaylistWithTracks
import com.example.data.local.TrackEntity
import com.example.ui.MainUiState
import com.example.ui.StagedUploadDraft
import com.example.ui.formatBytesMb
import com.example.ui.formatDuration
import com.example.ui.i18n.LocalAppStrings
import com.example.ui.i18n.filterDisplayLabel
import com.example.ui.shaders.LocalDataSaverEnabled
import com.example.ui.shaders.TrackCoverArtwork
import com.example.ui.theme.LiquidCyan
import com.example.ui.theme.LiquidMint
import com.example.ui.theme.expressivePressScale
import com.example.ui.theme.resolveCoverImageData
import com.example.ui.theme.LiquidRose

@Composable
fun EditTrackDialog(
    track: TrackEntity,
    onDismiss: () -> Unit,
    onSave: (
        newTitle: String,
        newArtist: String,
        newAlbumTitle: String?,
        newGenre: String,
        newCoverUri: Uri?,
        clearCover: Boolean,
    ) -> Unit,
) {
    BackHandler(onBack = onDismiss)

    val strings = LocalAppStrings.current
    var title by remember(track.id) { mutableStateOf(track.title) }
    var artist by remember(track.id) { mutableStateOf(track.artist) }
    var albumTitle by remember(track.id) { mutableStateOf(track.albumTitle.orEmpty()) }
    var genre by remember(track.id) { mutableStateOf(track.genre) }
    var pickedCoverUri by remember(track.id) { mutableStateOf<Uri?>(null) }
    var clearCover by remember(track.id) { mutableStateOf(false) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            pickedCoverUri = uri
            clearCover = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurface,
        title = { Text(strings.editTrackTitle) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .clickable {
                                photoPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                )
                            }
                            .testTag("edit_cover_preview"),
                    ) {
                        if (pickedCoverUri != null && !clearCover) {
                            AsyncImage(
                                model = pickedCoverUri,
                                contentDescription = strings.changeCover,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.matchParentSize(),
                            )
                        } else if (!clearCover) {
                            TrackCoverArtwork(
                                track = track,
                                modifier = Modifier.matchParentSize(),
                                cornerRadius = 18.dp,
                            )
                        } else {
                            TrackCoverArtwork(
                                track = track.copy(coverArtPath = null),
                                modifier = Modifier.matchParentSize(),
                                cornerRadius = 18.dp,
                            )
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedButton(
                            onClick = {
                                photoPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                )
                            },
                            shape = CircleShape,
                            modifier = Modifier.testTag("pick_cover_button"),
                        ) {
                            Icon(Icons.Rounded.Image, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(strings.changeCover)
                        }
                        if (track.coverArtPath != null || pickedCoverUri != null) {
                            TextButton(
                                onClick = {
                                    pickedCoverUri = null
                                    clearCover = true
                                },
                            ) {
                                Text(strings.resetCover, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(strings.titleLabel) },
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("edit_title_input"),
                )

                OutlinedTextField(
                    value = artist,
                    onValueChange = { artist = it },
                    label = { Text(strings.artistLabel) },
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("edit_artist_input"),
                )

                OutlinedTextField(
                    value = albumTitle,
                    onValueChange = { albumTitle = it },
                    label = { Text(strings.playerV33.albumTitleLabel) },
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("edit_album_input"),
                )

                OutlinedTextField(
                    value = genre,
                    onValueChange = { genre = it },
                    label = { Text(strings.genreLabel) },
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("edit_genre_input"),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        title,
                        artist,
                        albumTitle.trim().takeIf { it.isNotEmpty() },
                        genre.trim().ifEmpty { track.genre },
                        pickedCoverUri,
                        clearCover,
                    )
                },
                enabled = title.isNotBlank() && artist.isNotBlank(),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                modifier = Modifier.testTag("save_track_edit_button"),
            ) {
                Text(strings.save)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.cancel)
            }
        },
    )
}

@Composable
fun EditPlaylistDialog(
    playlist: PlaylistEntity,
    fallbackTracks: List<TrackEntity> = emptyList(),
    onDismiss: () -> Unit,
    onSave: (newName: String, newDescription: String, newCoverUri: Uri?, clearCover: Boolean) -> Unit,
) {
    BackHandler(onBack = onDismiss)

    val strings = LocalAppStrings.current
    val accent = MaterialTheme.colorScheme.primary
    var name by remember(playlist.id) { mutableStateOf(playlist.name) }
    var description by remember(playlist.id, playlist.description) {
        mutableStateOf(PlaylistMetadataHelper.parse(playlist.description).userDescription)
    }
    var pickedCoverUri by remember(playlist.id) { mutableStateOf<Uri?>(null) }
    var clearCover by remember(playlist.id) { mutableStateOf(false) }

    val fallbackTrack = remember(fallbackTracks) {
        fallbackTracks.firstOrNull { !it.coverArtPath.isNullOrBlank() } ?: fallbackTracks.firstOrNull()
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            pickedCoverUri = uri
            clearCover = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurface,
        title = { Text(strings.playerV33.editPlaylistTitle) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val editContext = LocalContext.current
                    val dataSaver = LocalDataSaverEnabled.current
                    var useExistingFallback by remember(playlist.coverArtPath) { mutableStateOf(false) }
                    var existingLoadFailed by remember(playlist.coverArtPath) { mutableStateOf(false) }
                    val existingCoverRequest = remember(playlist.coverArtPath, dataSaver, useExistingFallback) {
                        val resolved = resolveCoverImageData(
                            rawPath = playlist.coverArtPath,
                            dataSaver = dataSaver,
                            useFallbackUrl = useExistingFallback,
                        ) ?: return@remember null
                        ImageRequest.Builder(editContext)
                            .data(resolved)
                            .allowHardware(true)
                            .size(288)
                            .crossfade(false)
                            .build()
                    }
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .expressivePressScale(pressedScale = 0.94f)
                            .clip(RoundedCornerShape(18.dp))
                            .background(accent.copy(alpha = 0.16f))
                            .clickable {
                                photoPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                )
                            }
                            .testTag("edit_playlist_cover_preview"),
                        contentAlignment = Alignment.Center,
                    ) {
                        // Always render fallback behind custom cover so invalid/loading covers never flash blank
                        if (fallbackTrack != null) {
                            TrackCoverArtwork(
                                track = fallbackTrack,
                                modifier = Modifier.matchParentSize(),
                                cornerRadius = 18.dp,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.QueueMusic,
                                contentDescription = null,
                                tint = accent,
                                modifier = Modifier.size(30.dp),
                            )
                        }

                        if (pickedCoverUri != null && !clearCover) {
                            AsyncImage(
                                model = pickedCoverUri,
                                contentDescription = strings.changeCover,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.matchParentSize(),
                            )
                        } else if (!clearCover && existingCoverRequest != null && !existingLoadFailed) {
                            AsyncImage(
                                model = existingCoverRequest,
                                contentDescription = strings.changeCover,
                                contentScale = ContentScale.Crop,
                                onError = {
                                    if (!useExistingFallback &&
                                        playlist.coverArtPath?.startsWith("http", ignoreCase = true) == true
                                    ) {
                                        useExistingFallback = true
                                    } else {
                                        existingLoadFailed = true
                                    }
                                },
                                modifier = Modifier.matchParentSize(),
                            )
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedButton(
                            onClick = {
                                photoPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                )
                            },
                            shape = CircleShape,
                            modifier = Modifier.testTag("pick_playlist_cover_button"),
                        ) {
                            Icon(Icons.Rounded.Image, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(strings.changeCover)
                        }
                        if (!playlist.coverArtPath.isNullOrBlank() || pickedCoverUri != null) {
                            TextButton(
                                onClick = {
                                    pickedCoverUri = null
                                    clearCover = true
                                },
                            ) {
                                Text(strings.resetCover, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(strings.playlistNameLabel) },
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("edit_playlist_name_input"),
                )

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(strings.descriptionOptionalLabel) },
                    maxLines = 3,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("edit_playlist_description_input"),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(name, description, pickedCoverUri, clearCover) },
                enabled = name.isNotBlank(),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = accent,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                modifier = Modifier.testTag("save_playlist_edit_button"),
            ) {
                Text(strings.save)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.cancel)
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackOptionsBottomSheet(
    track: TrackEntity,
    onDismiss: () -> Unit,
    onEditClick: (() -> Unit)?,
    onAddToPlaylistClick: () -> Unit,
    onDeleteClick: (() -> Unit)?,
    onOpenArtistProfile: (() -> Unit)? = null,
    onPlayNextClick: (() -> Unit)? = null,
    onAddToQueueClick: (() -> Unit)? = null,
    onOpenAlbumClick: (() -> Unit)? = null,
    onLyricsClick: (() -> Unit)? = null,
    onWatchClipClick: (() -> Unit)? = null,
    onShareClick: (() -> Unit)? = null,
    onMoveUpInPlaylistClick: (() -> Unit)? = null,
    onMoveDownInPlaylistClick: (() -> Unit)? = null,
    onRemoveFromPlaylistClick: (() -> Unit)? = null,
) {
    val strings = LocalAppStrings.current
    val haptics = rememberSoundLapaHaptics()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(bottom = 8.dp),
            ) {
                TrackCoverArtwork(
                    track = track,
                    modifier = Modifier.size(48.dp),
                    cornerRadius = 14.dp,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = buildString {
                            append(track.artist)
                            track.albumTitle?.takeIf { it.isNotBlank() }?.let {
                                append(" • ")
                                append(it)
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(bottom = 4.dp))

            if (onPlayNextClick != null) {
                SheetActionRow(
                    icon = Icons.Rounded.PlaylistPlay,
                    label = strings.playerV33.playNextMenu,
                    onClick = {
                        haptics.tick()
                        onDismiss()
                        onPlayNextClick()
                    },
                    testTag = "sheet_play_next",
                )
            }

            if (onAddToQueueClick != null) {
                SheetActionRow(
                    icon = Icons.Rounded.QueueMusic,
                    label = strings.playerV33.addToQueueMenu,
                    onClick = {
                        haptics.tick()
                        onDismiss()
                        onAddToQueueClick()
                    },
                    testTag = "sheet_add_to_queue",
                )
            }

            SheetActionRow(
                icon = Icons.Rounded.PlaylistAdd,
                label = strings.addToPlaylistMenu,
                onClick = {
                    haptics.tick()
                    onDismiss()
                    onAddToPlaylistClick()
                },
                testTag = "sheet_add_to_playlist",
            )

            if (onEditClick != null) {
                SheetActionRow(
                    icon = Icons.Rounded.Edit,
                    label = strings.editTrackMenu,
                    onClick = {
                        haptics.tick()
                        onDismiss()
                        onEditClick()
                    },
                    testTag = "sheet_edit_track",
                )
            }

            if (onOpenArtistProfile != null) {
                SheetActionRow(
                    icon = Icons.Rounded.Person,
                    label = strings.artistProfileMenu,
                    onClick = {
                        haptics.tick()
                        onDismiss()
                        onOpenArtistProfile()
                    },
                    testTag = "sheet_artist_profile",
                )
            }

            if (onOpenAlbumClick != null) {
                SheetActionRow(
                    icon = Icons.Rounded.Album,
                    label = strings.playerV33.openAlbumMenu,
                    onClick = {
                        haptics.tick()
                        onDismiss()
                        onOpenAlbumClick()
                    },
                    testTag = "sheet_open_album",
                )
            }

            if (onLyricsClick != null) {
                SheetActionRow(
                    icon = Icons.Rounded.Lyrics,
                    label = strings.playerV33.lyricsMenu,
                    onClick = {
                        haptics.tick()
                        onDismiss()
                        onLyricsClick()
                    },
                    testTag = "sheet_lyrics",
                )
            }

            if (onWatchClipClick != null) {
                SheetActionRow(
                    icon = Icons.Rounded.OndemandVideo,
                    label = strings.playerV33.clipMenu,
                    onClick = {
                        haptics.tick()
                        onDismiss()
                        onWatchClipClick()
                    },
                    testTag = "sheet_watch_clip",
                )
            }

            if (onShareClick != null) {
                SheetActionRow(
                    icon = Icons.Rounded.Share,
                    label = strings.shareTrackAction,
                    onClick = {
                        haptics.tick()
                        onDismiss()
                        onShareClick()
                    },
                    testTag = "sheet_share_track",
                )
            }

            if (onMoveUpInPlaylistClick != null) {
                SheetActionRow(
                    icon = Icons.Rounded.ArrowUpward,
                    label = strings.v34.moveUpInPlaylistMenu,
                    onClick = {
                        haptics.tick()
                        onDismiss()
                        onMoveUpInPlaylistClick()
                    },
                    testTag = "sheet_move_up_playlist",
                )
            }

            if (onMoveDownInPlaylistClick != null) {
                SheetActionRow(
                    icon = Icons.Rounded.ArrowDownward,
                    label = strings.v34.moveDownInPlaylistMenu,
                    onClick = {
                        haptics.tick()
                        onDismiss()
                        onMoveDownInPlaylistClick()
                    },
                    testTag = "sheet_move_down_playlist",
                )
            }

            if (onRemoveFromPlaylistClick != null) {
                SheetActionRow(
                    icon = Icons.Rounded.DeleteOutline,
                    label = strings.removeFromPlaylist,
                    tint = LiquidRose,
                    onClick = {
                        haptics.tick()
                        onDismiss()
                        onRemoveFromPlaylistClick()
                    },
                    testTag = "sheet_remove_from_playlist",
                )
            }

            if (onDeleteClick != null) {
                SheetActionRow(
                    icon = Icons.Rounded.DeleteOutline,
                    label = strings.deleteTrackMenu,
                    tint = LiquidRose,
                    onClick = {
                        onDismiss()
                        onDeleteClick()
                    },
                    testTag = "sheet_delete_track",
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SheetActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    testTag: String = "",
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 14.dp)
            .then(if (testTag.isNotEmpty()) Modifier.testTag(testTag) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint)
        Text(text = label, style = MaterialTheme.typography.bodyLarge, color = tint)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistBottomSheet(
    track: TrackEntity,
    playlists: List<PlaylistWithTracks>,
    onDismiss: () -> Unit,
    onSelectPlaylist: (playlistId: String) -> Unit,
    onRemoveFromPlaylist: (playlistId: String) -> Unit,
    onCreateNewPlaylist: (name: String, description: String, coverUri: Uri?) -> Unit,
) {
    val strings = LocalAppStrings.current
    val haptics = rememberSoundLapaHaptics()
    var showCreateDialog by remember { mutableStateOf(false) }

    if (showCreateDialog) {
        CreatePlaylistDialog(
            onDismiss = { showCreateDialog = false },
            onConfirm = { name, desc, coverUri ->
                showCreateDialog = false
                onCreateNewPlaylist(name, desc, coverUri)
            },
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = strings.addToPlaylistTitle(track.title),
                style = MaterialTheme.typography.titleMedium,
            )

            OutlinedButton(
                onClick = {
                    haptics.tick()
                    showCreateDialog = true
                },
                shape = CircleShape,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(strings.createNewPlaylistOption)
            }

            if (playlists.isEmpty()) {
                Text(
                    text = strings.noPlaylistsYet,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            } else {
                playlists.forEach { item ->
                    val alreadyAdded = item.tracks.any { it.id == track.id }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                if (alreadyAdded) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                                else Color.Transparent,
                            )
                            .clickable {
                                if (alreadyAdded) {
                                    haptics.tick()
                                    onRemoveFromPlaylist(item.playlist.id)
                                } else {
                                    haptics.confirm()
                                    onSelectPlaylist(item.playlist.id)
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 12.dp)
                            .testTag("playlist_toggle_${item.playlist.id}"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = item.playlist.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = strings.tracksCountLabel(item.tracks.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = if (alreadyAdded) strings.removeLabel else strings.addLabel,
                                style = MaterialTheme.typography.labelLarge,
                                color = if (alreadyAdded) LiquidRose else MaterialTheme.colorScheme.primary,
                            )
                            Icon(
                                imageVector = if (alreadyAdded) Icons.Rounded.CheckCircle else Icons.Rounded.AddCircleOutline,
                                contentDescription = if (alreadyAdded) strings.removeLabel else strings.addLabel,
                                tint = if (alreadyAdded) LiquidMint else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                }
            }

            Button(
                onClick = {
                    haptics.tick()
                    onDismiss()
                },
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("playlist_sheet_done_button"),
            ) {
                Text(strings.doneLabel)
            }

            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
fun CreatePlaylistDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, description: String, coverUri: Uri?) -> Unit,
) {
    BackHandler(onBack = onDismiss)
    val strings = LocalAppStrings.current
    val accent = MaterialTheme.colorScheme.primary
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var pickedCoverUri by remember { mutableStateOf<Uri?>(null) }

    val coverPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            pickedCoverUri = uri
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurface,
        title = { Text(strings.newPlaylistDialogTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(66.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(accent.copy(alpha = 0.16f))
                            .clickable {
                                coverPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                )
                            }
                            .testTag("create_playlist_cover_preview"),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (pickedCoverUri != null) {
                            AsyncImage(
                                model = pickedCoverUri,
                                contentDescription = strings.v34.coverSelectedLabel,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.matchParentSize(),
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Image,
                                contentDescription = strings.v34.pickCoverOptionalButton,
                                tint = accent,
                                modifier = Modifier.size(28.dp),
                            )
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedButton(
                            onClick = {
                                coverPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                )
                            },
                            shape = CircleShape,
                            modifier = Modifier.testTag("create_playlist_pick_cover_button"),
                        ) {
                            Icon(Icons.Rounded.Image, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(strings.v34.pickCoverOptionalButton)
                        }
                        if (pickedCoverUri != null) {
                            TextButton(onClick = { pickedCoverUri = null }) {
                                Text(strings.resetCover, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(strings.playlistNameLabel) },
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("playlist_name_input"),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(strings.descriptionOptionalLabel) },
                    maxLines = 2,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name, description, pickedCoverUri) },
                enabled = name.isNotBlank(),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = accent,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                modifier = Modifier.testTag("confirm_create_playlist_button"),
            ) {
                Text(strings.createLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.cancel)
            }
        },
    )
}

@Composable
fun ImportPlaylistModalDialog(
    isImporting: Boolean,
    statusMessage: String?,
    onImportFromInput: (String, String?) -> Unit,
    onImportFromFile: (Uri, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    val strings = LocalAppStrings.current
    val haptics = rememberSoundLapaHaptics()
    val accent = MaterialTheme.colorScheme.primary
    var importTextInput by remember { mutableStateOf("") }
    var importPlaylistName by remember { mutableStateOf("") }

    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            onImportFromFile(uri, importPlaylistName.takeIf { it.isNotBlank() })
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurface,
        title = { Text(strings.v34.importPlaylistDialogTitle) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = strings.settingsImportHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = importTextInput,
                    onValueChange = { importTextInput = it },
                    label = { Text(strings.settingsImportInputLabel) },
                    placeholder = { Text(strings.settingsImportInputPlaceholder) },
                    minLines = 2,
                    maxLines = 4,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("dialog_import_playlist_input"),
                )
                OutlinedTextField(
                    value = importPlaylistName,
                    onValueChange = { importPlaylistName = it },
                    label = { Text(strings.settingsImportPlaylistNameLabel) },
                    placeholder = { Text(strings.settingsImportPlaylistNamePlaceholder) },
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("dialog_import_playlist_name"),
                )
                statusMessage?.let { msg ->
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (msg.startsWith("✓")) LiquidMint else LiquidRose,
                    )
                }
                OutlinedButton(
                    onClick = {
                        haptics.tick()
                        fileLauncher.launch(
                            arrayOf(
                                "text/csv",
                                "text/comma-separated-values",
                                "text/tab-separated-values",
                                "text/plain",
                                "application/json",
                                "application/vnd.ms-excel",
                                "audio/x-mpegurl",
                                "application/vnd.apple.mpegurl",
                                "audio/mpegurl",
                            ),
                        )
                    },
                    enabled = !isImporting,
                    shape = CircleShape,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("dialog_import_playlist_file_button"),
                ) {
                    Icon(Icons.Rounded.UploadFile, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(strings.settingsImportFromFileButton)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    haptics.confirm()
                    onImportFromInput(importTextInput, importPlaylistName.takeIf { it.isNotBlank() })
                },
                enabled = importTextInput.isNotBlank() && !isImporting,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = accent,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                modifier = Modifier.testTag("dialog_import_playlist_submit"),
            ) {
                if (isImporting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(strings.settingsImportingLabel)
                } else {
                    Text(strings.settingsImportFromLinkButton)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.close)
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UploadTracksBottomSheet(
    state: MainUiState,
    palettes: List<Pair<Long, Long>>,
    onDismiss: () -> Unit,
    onStageAudioFiles: (List<Uri>) -> Unit,
    onUpdateDraft: (title: String?, artist: String?, genre: String?, description: String?, paletteIndex: Int?) -> Unit,
    onPublishDraft: () -> Unit,
    onCancelDraft: () -> Unit,
    onSetAutoUploadFolder: (Uri, String?) -> Unit,
    onSyncNow: () -> Unit,
) {
    val strings = LocalAppStrings.current
    val context = LocalContext.current

    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { uri ->
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
            }
            onStageAudioFiles(uris)
        }
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { treeUri ->
        if (treeUri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            val folderName = DocumentFile.fromTreeUri(context, treeUri)?.name
            onSetAutoUploadFolder(treeUri, folderName)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = strings.uploadOwnTracks,
                style = MaterialTheme.typography.titleLarge,
            )

            state.upload.statusMessage?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = LiquidMint,
                )
            }

            val draft = state.upload.stagedDraft
            if (draft != null) {
                StagedUploadDraftEditor(
                    draft = draft,
                    palettes = palettes,
                    isImporting = state.upload.isImporting,
                    onUpdateDraft = onUpdateDraft,
                    onPublish = {
                        onPublishDraft()
                        onDismiss()
                    },
                    onCancel = onCancelDraft,
                )
            } else {
                Button(
                    onClick = { audioPickerLauncher.launch(arrayOf("audio/*")) },
                    enabled = !state.upload.isImporting,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("sheet_pick_audio_button"),
                ) {
                    if (state.upload.isImporting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(strings.uploadingLabel)
                    } else {
                        Icon(Icons.Rounded.AudioFile, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(strings.pickAudioFiles)
                    }
                }

                HorizontalDivider()

                Text(
                    text = strings.autoUploadFolderTitle,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = state.settings.autoUploadFolderName
                        ?.let { strings.folderLinkedPrefix(it) }
                        ?: strings.autoUploadFolderHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = { folderPickerLauncher.launch(null) },
                        shape = CircleShape,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(strings.pickFolderButton)
                    }

                    if (state.settings.autoUploadTreeUri != null) {
                        Button(
                            onClick = onSyncNow,
                            enabled = !state.upload.isSyncingFolder,
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(containerColor = LiquidCyan, contentColor = Color.Black),
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Rounded.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(strings.syncButton)
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun StagedUploadDraftEditor(
    draft: StagedUploadDraft,
    palettes: List<Pair<Long, Long>>,
    isImporting: Boolean,
    onUpdateDraft: (title: String?, artist: String?, genre: String?, description: String?, paletteIndex: Int?) -> Unit,
    onPublish: () -> Unit,
    onCancel: () -> Unit,
) {
    val strings = LocalAppStrings.current
    val genres = listOf("Мои треки", "Хип-хоп", "Электроника", "Поп", "Рок", "Lo-Fi", "Синтвейв", "Инди", "Амбиент")
    val activePalette = palettes[draft.paletteIndex % palettes.size]

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = strings.durationSizeLabel(
                formatDuration(draft.preview.durationMs),
                formatBytesMb(draft.preview.fileSizeBytes),
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = draft.title,
            onValueChange = { onUpdateDraft(it, null, null, null, null) },
            label = { Text(strings.titleLabel) },
            singleLine = true,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = draft.artist,
            onValueChange = { onUpdateDraft(null, it, null, null, null) },
            label = { Text(strings.artistLabel) },
            singleLine = true,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(genres) { genre ->
                val selected = draft.genre == genre
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(
                            if (selected) Color(activePalette.first).copy(alpha = 0.25f)
                            else MaterialTheme.colorScheme.surfaceVariant,
                        )
                        .border(
                            1.dp,
                            if (selected) Color(activePalette.first) else Color.Transparent,
                            CircleShape,
                        )
                        .clickable { onUpdateDraft(null, null, genre, null, null) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = filterDisplayLabel(genre, strings),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            palettes.forEachIndexed { idx, pair ->
                val selected = draft.paletteIndex == idx
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(Color(pair.first), Color(pair.second))))
                        .border(
                            width = if (selected) 2.5.dp else 1.dp,
                            color = if (selected) Color.White else Color.Transparent,
                            shape = CircleShape,
                        )
                        .clickable { onUpdateDraft(null, null, null, null, idx) },
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                onClick = onCancel,
                shape = CircleShape,
                modifier = Modifier.weight(1f),
            ) {
                Text(strings.cancel)
            }
            Button(
                onClick = onPublish,
                enabled = !isImporting && draft.title.isNotBlank(),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = Color(activePalette.first)),
                modifier = Modifier.weight(1f),
            ) {
                Text(strings.addButton)
            }
        }
    }
}

@Composable
fun AudiusConnectDialog(
    currentHandle: String?,
    isSyncing: Boolean,
    statusMessage: String?,
    onDismiss: () -> Unit,
    onSync: (String) -> Unit,
    onDisconnect: () -> Unit,
) {
    val strings = LocalAppStrings.current
    var handleInput by remember(currentHandle) { mutableStateOf(currentHandle.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurface,
        title = { Text(strings.settingsAudiusSection) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = strings.settingsAudiusHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = handleInput,
                    onValueChange = { handleInput = it },
                    label = { Text(strings.settingsAudiusHandleLabel) },
                    placeholder = { Text(strings.settingsAudiusHandlePlaceholder) },
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("dialog_audius_handle_input"),
                )
                statusMessage?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = LiquidCyan,
                    )
                }
                if (currentHandle != null) {
                    TextButton(onClick = onDisconnect) {
                        Text("${strings.settingsAudiusDisconnect} @$currentHandle", color = LiquidRose)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSync(handleInput) },
                enabled = handleInput.isNotBlank() && !isSyncing,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                modifier = Modifier.testTag("dialog_audius_sync_button"),
            ) {
                if (isSyncing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(strings.settingsAudiusSync)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.close)
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueBottomSheet(
    queueTracks: List<TrackEntity>,
    currentTrackId: String?,
    onSelectTrack: (TrackEntity) -> Unit,
    onRemoveFromQueue: (String) -> Unit = {},
    onMoveQueueItem: (Int, Int) -> Unit = { _, _ -> },
    onClearUpcomingQueue: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    val strings = LocalAppStrings.current
    val v35 = strings.v35
    val haptics = rememberSoundLapaHaptics()
    val accent = MaterialTheme.colorScheme.primary

    val currentIndex = remember(queueTracks, currentTrackId) {
        if (currentTrackId.isNullOrBlank()) -1
        else queueTracks.indexOfFirst { it.id == currentTrackId }
    }
    val currentTrack = remember(queueTracks, currentIndex) {
        if (currentIndex in queueTracks.indices) queueTracks[currentIndex] else null
    }
    val upcomingEntries = remember(queueTracks, currentIndex) {
        if (currentIndex >= 0) {
            queueTracks.mapIndexedNotNull { idx, item ->
                if (idx > currentIndex) idx to item else null
            }
        } else {
            queueTracks.mapIndexed { idx, item -> idx to item }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = strings.queueSheetTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = strings.tracksCountLabel(queueTracks.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (upcomingEntries.isNotEmpty()) {
                    TextButton(
                        onClick = {
                            haptics.tick()
                            onClearUpcomingQueue()
                        },
                        modifier = Modifier.testTag("queue_clear_upcoming_button"),
                    ) {
                        Text(
                            text = v35.queueClearUpcomingButton,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }

            if (currentTrack != null) {
                Text(
                    text = v35.queueNowPlayingSection,
                    style = MaterialTheme.typography.labelMedium,
                    color = accent,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(accent.copy(alpha = 0.14f))
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                        .testTag("queue_current_${currentTrack.id}"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TrackCoverArtwork(
                        track = currentTrack,
                        modifier = Modifier.size(50.dp),
                        cornerRadius = 12.dp,
                        targetSizePx = 200,
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = currentTrack.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = accent,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${currentTrack.artist} • ${formatDuration(currentTrack.durationMs)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Icon(
                        imageVector = Icons.Rounded.GraphicEq,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }

            Text(
                text = v35.queueUpNextSection,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )

            if (upcomingEntries.isEmpty()) {
                Text(
                    text = v35.queueEmptyUpcomingHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(vertical = 12.dp)
                        .testTag("queue_empty_upcoming_hint"),
                )
            } else {
                val firstUpcomingQueueIdx = upcomingEntries.first().first
                val lastUpcomingQueueIdx = upcomingEntries.last().first

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                        .testTag("queue_upcoming_list"),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(
                        items = upcomingEntries,
                        key = { (_, item) -> item.id },
                        contentType = { "queue_upcoming_row" },
                    ) { (queueIdx, item) ->
                        val canMoveUp = queueIdx > firstUpcomingQueueIdx
                        val canMoveDown = queueIdx < lastUpcomingQueueIdx
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .animateItem()
                                .expressivePressScale(pressedScale = 0.98f)
                                .clip(RoundedCornerShape(16.dp))
                                .clickable {
                                    haptics.tap()
                                    onSelectTrack(item)
                                    onDismiss()
                                }
                                .padding(start = 8.dp, end = 2.dp, top = 6.dp, bottom = 6.dp)
                                .testTag("queue_item_${item.id}"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            TrackCoverArtwork(
                                track = item,
                                modifier = Modifier.size(46.dp),
                                cornerRadius = 12.dp,
                                targetSizePx = 180,
                            )
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    text = item.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "${item.artist} • ${formatDuration(item.durationMs)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                IconButton(
                                    onClick = {
                                        haptics.tick()
                                        onMoveQueueItem(queueIdx, queueIdx - 1)
                                    },
                                    enabled = canMoveUp,
                                    modifier = Modifier.testTag("queue_move_up_${item.id}"),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.KeyboardArrowUp,
                                        contentDescription = v35.queueMoveUpCd,
                                        tint = if (canMoveUp) {
                                            MaterialTheme.colorScheme.onSurface
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                                        },
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        haptics.tick()
                                        onMoveQueueItem(queueIdx, queueIdx + 1)
                                    },
                                    enabled = canMoveDown,
                                    modifier = Modifier.testTag("queue_move_down_${item.id}"),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.KeyboardArrowDown,
                                        contentDescription = v35.queueMoveDownCd,
                                        tint = if (canMoveDown) {
                                            MaterialTheme.colorScheme.onSurface
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                                        },
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        haptics.tick()
                                        onRemoveFromQueue(item.id)
                                    },
                                    modifier = Modifier.testTag("queue_remove_${item.id}"),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = v35.queueRemoveCd,
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
        }
    }
}

