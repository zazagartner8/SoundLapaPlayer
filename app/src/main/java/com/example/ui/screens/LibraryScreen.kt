package com.example.ui.screens

import android.net.Uri
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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.data.local.ArtistSubscriptionEntity
import com.example.data.local.PlaylistEntity
import com.example.data.local.PlaylistMetadataHelper
import com.example.data.local.PlaylistWithTracks
import com.example.data.local.TrackEntity
import com.example.data.remote.FullStreamResolver
import com.example.ui.MainUiState
import com.example.ui.PlaylistSortMode
import com.example.ui.StatsBackupUiState
import com.example.ui.components.CreatePlaylistDialog
import com.example.ui.components.ListeningStatsSection
import com.example.ui.components.TrackRowItem
import com.example.ui.components.rememberSoundLapaHaptics
import com.example.ui.i18n.AppStrings
import com.example.ui.i18n.LocalAppStrings
import com.example.ui.shaders.LocalBatterySaverEnabled
import com.example.ui.shaders.LocalDataSaverEnabled
import com.example.ui.shaders.TrackCoverArtwork
import com.example.ui.shaders.rememberIsAppForeground
import com.example.ui.theme.LiquidRose
import com.example.ui.theme.expressivePressScale
import com.example.ui.theme.resolveCoverImageData
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    state: MainUiState,
    requestedSection: Int? = null,
    onConsumeRequestedSection: () -> Unit = {},
    onCreatePlaylist: (name: String, description: String, coverUri: Uri?) -> Unit,
    onEditPlaylist: (PlaylistEntity) -> Unit = {},
    onDeletePlaylist: (playlistId: String) -> Unit,
    onTogglePinPlaylist: (PlaylistEntity) -> Unit = {},
    onChangePlaylistSortMode: (playlistId: String, PlaylistSortMode) -> Unit = { _, _ -> },
    onPlayPlaylist: (PlaylistWithTracks) -> Unit,
    onPlayPlaylistShuffle: (PlaylistWithTracks) -> Unit = { onPlayPlaylist(it) },
    onPlayTrack: (TrackEntity, List<TrackEntity>?, String?) -> Unit,
    onToggleLike: (TrackEntity) -> Unit,
    onOpenTrackOptions: (TrackEntity) -> Unit,
    onOpenPlaylistTrackOptions: (track: TrackEntity, playlistId: String, canMoveUp: Boolean, canMoveDown: Boolean) -> Unit = { track, _, _, _ ->
        onOpenTrackOptions(track)
    },
    onOpenUploadSheet: () -> Unit,
    onOpenImportPlaylistDialog: () -> Unit = {},
    onOpenSubscribedArtist: (ArtistSubscriptionEntity) -> Unit = {},
    onToggleUnsubscribeArtist: (ArtistSubscriptionEntity) -> Unit = {},
    onPlaylistDetailOpenChanged: (Boolean) -> Unit = {},
    statsBackupState: StatsBackupUiState = StatsBackupUiState(),
    onRestoreStatsFromUri: (Uri) -> Unit = {},
    onClearStatsRestoreStatus: () -> Unit = {},
    bottomContentPadding: Dp,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current
    val haptics = rememberSoundLapaHaptics()
    val libraryListState = rememberLazyListState()
    var selectedSection by remember { mutableIntStateOf(0) }
    var openedPlaylistId by remember { mutableStateOf<String?>(null) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    var actionsMenuPlaylist by remember { mutableStateOf<PlaylistWithTracks?>(null) }
    var pendingDeletePlaylist by remember { mutableStateOf<PlaylistWithTracks?>(null) }

    val currentOnPlayPlaylist by rememberUpdatedState(onPlayPlaylist)
    val currentOnPlayTrack by rememberUpdatedState(onPlayTrack)
    val currentOnToggleLike by rememberUpdatedState(onToggleLike)
    val currentOnOpenTrackOptions by rememberUpdatedState(onOpenTrackOptions)
    val currentOnOpenSubscribedArtist by rememberUpdatedState(onOpenSubscribedArtist)
    val currentOnToggleUnsubscribeArtist by rememberUpdatedState(onToggleUnsubscribeArtist)

    LaunchedEffect(requestedSection) {
        if (requestedSection != null) {
            selectedSection = requestedSection.coerceIn(0, 4)
            openedPlaylistId = null
            onConsumeRequestedSection()
        }
    }

    // Tabs: 0=Playlists, 1=Liked, 2=Subscriptions (left of Downloads), 3=Downloads/MyTracks, 4=Stats
    val sectionTitles = listOf(
        strings.playlistsTab,
        strings.favoritesTab,
        strings.v34.subscriptionsTab,
        strings.myTracksTab,
        strings.stats.tabTitle,
    )
    val openedPlaylist = remember(state.playlists, openedPlaylistId) {
        state.playlists.find { it.playlist.id == openedPlaylistId }
    }
    val likedTracks = remember(state.tracks) { state.tracks.filter { it.isLiked } }
    val userTracks = remember(state.tracks) { state.tracks.filter { it.isUserUpload } }
    val tracksById = remember(state.tracks) { state.tracks.associateBy { it.id } }
    val latestLikedTracks by rememberUpdatedState(likedTracks)
    val latestUserTracks by rememberUpdatedState(userTracks)

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
            onConfirm = { name, desc, coverUri ->
                haptics.confirm()
                onCreatePlaylist(name, desc, coverUri)
                showCreatePlaylistDialog = false
            },
        )
    }

    actionsMenuPlaylist?.let { pl ->
        val accent = MaterialTheme.colorScheme.primary
        ModalBottomSheet(
            onDismissRequest = { actionsMenuPlaylist = null },
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp)
                    .testTag("playlist_actions_sheet"),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = pl.playlist.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = strings.v34.playlistActionsTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))

                if (pl.tracks.isNotEmpty()) {
                    PlaylistActionRow(
                        icon = Icons.Rounded.PlayArrow,
                        label = strings.v34.playPlaylistAction,
                        tint = accent,
                        testTag = "playlist_action_play",
                        onClick = {
                            actionsMenuPlaylist = null
                            haptics.tap()
                            onPlayPlaylist(pl)
                        },
                    )
                    PlaylistActionRow(
                        icon = Icons.Rounded.Shuffle,
                        label = strings.playlistShuffleLabel,
                        testTag = "playlist_action_shuffle",
                        onClick = {
                            actionsMenuPlaylist = null
                            haptics.tap()
                            onPlayPlaylistShuffle(pl)
                        },
                    )
                }

                PlaylistActionRow(
                    icon = Icons.Rounded.PushPin,
                    label = if (pl.playlist.isPinned) strings.v34.unpinPlaylistAction else strings.v34.pinPlaylistAction,
                    tint = if (pl.playlist.isPinned) accent else MaterialTheme.colorScheme.onSurface,
                    testTag = "playlist_action_pin",
                    onClick = {
                        actionsMenuPlaylist = null
                        haptics.confirm()
                        onTogglePinPlaylist(pl.playlist)
                    },
                )
                PlaylistActionRow(
                    icon = Icons.Rounded.Edit,
                    label = strings.playerV33.editPlaylistMenu,
                    testTag = "playlist_action_edit",
                    onClick = {
                        actionsMenuPlaylist = null
                        haptics.tick()
                        onEditPlaylist(pl.playlist)
                    },
                )
                PlaylistActionRow(
                    icon = Icons.Rounded.DeleteOutline,
                    label = strings.deleteTrackMenu,
                    tint = LiquidRose,
                    testTag = "playlist_action_delete",
                    onClick = {
                        actionsMenuPlaylist = null
                        haptics.tick()
                        pendingDeletePlaylist = pl
                    },
                )
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    pendingDeletePlaylist?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDeletePlaylist = null },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurface,
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
                    onTogglePinPlaylist = { onTogglePinPlaylist(targetPlaylist.playlist) },
                    onDeletePlaylist = {
                        pendingDeletePlaylist = targetPlaylist
                    },
                    onChangeSortMode = { sortMode ->
                        onChangePlaylistSortMode(targetPlaylist.playlist.id, sortMode)
                    },
                    onPlayTrack = { track, queue ->
                        onPlayTrack(track, queue, targetPlaylist.playlist.name)
                    },
                    onToggleLike = onToggleLike,
                    onOpenPlaylistTrackOptions = { track, canMoveUp, canMoveDown ->
                        onOpenPlaylistTrackOptions(track, targetPlaylist.playlist.id, canMoveUp, canMoveDown)
                    },
                    bottomContentPadding = bottomContentPadding,
                )
            } else {
            LazyColumn(
                state = libraryListState,
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
                item(key = "library_header", contentType = "header") {
                    Text(
                        text = strings.libraryTitle,
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                item(key = "library_tabs", contentType = "section_tabs") {
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
                                            modifier = Modifier.testTag("empty_create_playlist_button"),
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
                                        Button(
                                            onClick = {
                                                haptics.tick()
                                                onOpenImportPlaylistDialog()
                                            },
                                            shape = CircleShape,
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = MaterialTheme.colorScheme.primary,
                                                contentColor = MaterialTheme.colorScheme.onPrimary,
                                            ),
                                            modifier = Modifier.testTag("library_import_playlist_button"),
                                        ) {
                                            Icon(
                                                Icons.Rounded.Download,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp),
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(strings.v34.importPlaylistButton)
                                        }
                                    }
                                }
                            }
                        } else {
                            item(key = "playlists_toolbar") {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        text = "${strings.playlistsTab} (${state.playlists.size})",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Surface(
                                            onClick = {
                                                haptics.tick()
                                                onOpenImportPlaylistDialog()
                                            },
                                            shape = CircleShape,
                                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
                                            contentColor = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.testTag("playlists_import_inline_button"),
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.Download,
                                                    contentDescription = strings.v34.importPlaylistButton,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(15.dp),
                                                )
                                                Text(
                                                    text = strings.v34.importPlaylistShort,
                                                    style = MaterialTheme.typography.labelMedium,
                                                    fontWeight = FontWeight.Medium,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                    maxLines = 1,
                                                    softWrap = false,
                                                )
                                            }
                                        }
                                        Surface(
                                            onClick = {
                                                haptics.tick()
                                                showCreatePlaylistDialog = true
                                            },
                                            shape = CircleShape,
                                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
                                            contentColor = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.testTag("playlists_create_inline_button"),
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
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
                                                    maxLines = 1,
                                                    softWrap = false,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            items(
                                items = state.playlists,
                                key = { it.playlist.id },
                                contentType = { "playlist_card" },
                            ) { pl ->
                                val latestPl by rememberUpdatedState(pl)
                                val onOpenPl = remember(pl.playlist.id) {
                                    {
                                        haptics.tick()
                                        openedPlaylistId = latestPl.playlist.id
                                    }
                                }
                                val onLongPressPl = remember(pl.playlist.id) {
                                    {
                                        haptics.confirm()
                                        actionsMenuPlaylist = latestPl
                                    }
                                }
                                val onPlayPl = remember(pl.playlist.id) {
                                    {
                                        haptics.tap()
                                        currentOnPlayPlaylist(latestPl)
                                    }
                                }
                                PlaylistRowCard(
                                    playlistWithTracks = pl,
                                    onOpen = onOpenPl,
                                    onLongPress = onLongPressPl,
                                    onPlay = onPlayPl,
                                    modifier = Modifier.animateItem(),
                                )
                            }
                        }
                    }

                    1 -> {
                        if (likedTracks.isEmpty()) {
                            item(key = "empty_liked", contentType = "empty_notice") {
                                EmptyLibraryNotice(strings.emptyFavorites)
                            }
                        } else {
                            items(
                                items = likedTracks,
                                key = { "liked_${it.id}" },
                                contentType = { "track_row" },
                            ) { track ->
                                val isCurrent = state.playback.currentTrackId == track.id
                                val latestTrack by rememberUpdatedState(track)
                                val onPlayClick = remember(track.id) {
                                    {
                                        haptics.tap()
                                        currentOnPlayTrack(latestTrack, latestLikedTracks, strings.v34.playbackContextLiked)
                                    }
                                }
                                val onLikeClick = remember(track.id) {
                                    {
                                        haptics.confirm()
                                        currentOnToggleLike(latestTrack)
                                    }
                                }
                                val onMoreClick = remember(track.id) {
                                    {
                                        haptics.tick()
                                        currentOnOpenTrackOptions(latestTrack)
                                    }
                                }
                                TrackRowItem(
                                    track = track,
                                    isCurrentTrack = isCurrent,
                                    isPlaying = isCurrent && state.playback.isPlaying,
                                    onPlayClick = onPlayClick,
                                    onLikeClick = onLikeClick,
                                    onMoreClick = onMoreClick,
                                    modifier = Modifier.animateItem(),
                                )
                            }
                        }
                    }

                    2 -> {
                        if (state.subscribedArtists.isNotEmpty()) {
                            item(key = "subscribed_artists_header", contentType = "section_header") {
                                Text(
                                    text = "${strings.social.subscribedArtistsHeader} (${state.subscribedArtists.size})",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                            items(
                                items = state.subscribedArtists,
                                key = { "sub_${it.artistKey}" },
                                contentType = { "subscribed_artist" },
                            ) { sub ->
                                val latestSub by rememberUpdatedState(sub)
                                val onOpenSub = remember(sub.artistKey) {
                                    {
                                        haptics.tick()
                                        currentOnOpenSubscribedArtist(latestSub)
                                    }
                                }
                                val onUnsub = remember(sub.artistKey) {
                                    {
                                        haptics.tick()
                                        currentOnToggleUnsubscribeArtist(latestSub)
                                    }
                                }
                                SubscribedArtistRowCard(
                                    subscription = sub,
                                    onOpen = onOpenSub,
                                    onToggleUnsubscribe = onUnsub,
                                )
                            }
                        } else {
                            item(key = "empty_subscribed_artists", contentType = "empty_notice") {
                                EmptyLibraryNotice(strings.social.emptySubscribedArtists)
                            }
                        }
                    }

                    3 -> {
                        item(key = "upload_user_tracks_cta", contentType = "upload_cta") {
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
                            item(key = "empty_user_tracks", contentType = "empty_notice") {
                                EmptyLibraryNotice(strings.emptyMyTracks)
                            }
                        } else {
                            items(
                                items = userTracks,
                                key = { "my_${it.id}" },
                                contentType = { "track_row" },
                            ) { track ->
                                val isCurrent = state.playback.currentTrackId == track.id
                                val latestTrack by rememberUpdatedState(track)
                                val onPlayClick = remember(track.id) {
                                    {
                                        haptics.tap()
                                        currentOnPlayTrack(latestTrack, latestUserTracks, strings.v34.playbackContextMyTracks)
                                    }
                                }
                                val onLikeClick = remember(track.id) {
                                    {
                                        haptics.confirm()
                                        currentOnToggleLike(latestTrack)
                                    }
                                }
                                val onMoreClick = remember(track.id) {
                                    {
                                        haptics.tick()
                                        currentOnOpenTrackOptions(latestTrack)
                                    }
                                }
                                TrackRowItem(
                                    track = track,
                                    isCurrentTrack = isCurrent,
                                    isPlaying = isCurrent && state.playback.isPlaying,
                                    onPlayClick = onPlayClick,
                                    onLikeClick = onLikeClick,
                                    onMoreClick = onMoreClick,
                                )
                            }
                        }
                    }

                    else -> {
                        item(key = "library_listening_stats", contentType = "listening_stats") {
                            ListeningStatsSection(
                                events = state.settings.listeningEvents,
                                fallbackHistory = state.settings.listeningHistory,
                                tracksById = tracksById,
                                accent = MaterialTheme.colorScheme.primary,
                                backupState = statsBackupState,
                                onRestoreBackupUri = onRestoreStatsFromUri,
                                onClearRestoreStatus = onClearStatsRestoreStatus,
                                onPlayTrack = { track -> onPlayTrack(track, null, strings.stats.sectionTitle) },
                            )
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
private fun PlaylistActionRow(
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
            .padding(horizontal = 8.dp, vertical = 13.dp)
            .then(if (testTag.isNotEmpty()) Modifier.testTag(testTag) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint)
        Text(text = label, style = MaterialTheme.typography.bodyLarge, color = tint)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistRowCard(
    playlistWithTracks: PlaylistWithTracks,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
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
    val parsedDescription = remember(playlistWithTracks.playlist.description) {
        PlaylistMetadataHelper.parse(playlistWithTracks.playlist.description)
    }
    val tracksCountLabel = remember(playlistWithTracks.tracks.size, parsedDescription.importSource, strings) {
        val countPart = strings.settingsV33.libraryPlaylistTracksCount(playlistWithTracks.tracks.size)
        val source = parsedDescription.importSource
        if (source != null) {
            "${strings.stats.playlistImportSourceLabel(source)} • $countPart"
        } else {
            countPart
        }
    }

    val dataSaver = LocalDataSaverEnabled.current
    val shape = remember { RoundedCornerShape(20.dp) }
    val backdropModel = remember(backdropCoverUrl, dataSaver) {
        val data = resolveCoverImageData(backdropCoverUrl, dataSaver = dataSaver) ?: return@remember null
        ImageRequest.Builder(context)
            .data(data)
            .allowHardware(true)
            .size(160)
            .crossfade(false)
            .build()
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .expressivePressScale(pressedScale = 0.975f)
            .clip(shape)
            .combinedClickable(
                onClick = onOpen,
                onLongClick = onLongPress,
            )
            .testTag("playlist_card_${playlistWithTracks.playlist.id}"),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.50f),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            // Lightweight translucent cover backdrop (zero SubcomposeAsyncImage / border overhead for 60fps scroll)
            if (backdropModel != null) {
                AsyncImage(
                    model = backdropModel,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .matchParentSize()
                        .graphicsLayer { alpha = 0.16f },
                )
            }

            Row(
                modifier = Modifier.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                PlaylistArtworkMosaic(
                    coverArtPath = playlistWithTracks.playlist.coverArtPath,
                    tracks = playlistWithTracks.tracks,
                    accentColor = accent,
                    modifier = Modifier.size(64.dp),
                    cornerRadius = 12.dp,
                )

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (playlistWithTracks.playlist.isPinned) {
                            Icon(
                                imageVector = Icons.Rounded.PushPin,
                                contentDescription = strings.v34.pinnedBadgeLabel,
                                tint = accent,
                                modifier = Modifier.size(15.dp),
                            )
                        }
                        Text(
                            text = playlistWithTracks.playlist.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
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
                            .expressivePressScale(pressedScale = 0.90f)
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
    val dataSaver = LocalDataSaverEnabled.current
    val shape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
    val firstTrack = remember(tracks) {
        tracks.firstOrNull { !it.coverArtPath.isNullOrBlank() } ?: tracks.firstOrNull()
    }
    val distinctArtTracks = remember(tracks) {
        tracks.filter { !it.coverArtPath.isNullOrBlank() }.distinctBy { it.coverArtPath }.take(4)
    }

    var useFallbackUrl by remember(coverArtPath) { mutableStateOf(false) }
    var customCoverFailed by remember(coverArtPath) { mutableStateOf(false) }

    val resolvedData = remember(coverArtPath, dataSaver, useFallbackUrl, customCoverFailed) {
        if (customCoverFailed) null
        else resolveCoverImageData(coverArtPath, dataSaver = dataSaver, useFallbackUrl = useFallbackUrl)
    }

    val imageModel = remember(resolvedData, dataSaver) {
        val data = resolvedData ?: return@remember null
        ImageRequest.Builder(context)
            .data(data)
            .allowHardware(true)
            .size(if (dataSaver) 240 else 360)
            .crossfade(140)
            .build()
    }

    Box(modifier = modifier.clip(shape)) {
        // Always keep fallback mosaic/gradient underneath so covers never flash blank while loading
        PlaylistArtworkMosaicFallback(
            distinctArtTracks = distinctArtTracks,
            fallbackTrack = firstTrack,
            accentColor = accentColor,
            cornerRadius = cornerRadius,
        )

        if (imageModel != null && !customCoverFailed) {
            AsyncImage(
                model = imageModel,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onError = {
                    val hasUrlFallback = !useFallbackUrl &&
                        !coverArtPath.isNullOrBlank() &&
                        (coverArtPath.startsWith("http://", ignoreCase = true) ||
                            coverArtPath.startsWith("https://", ignoreCase = true)) &&
                        FullStreamResolver.fallbackArtworkUrl(
                            FullStreamResolver.upgradeArtworkUrl(coverArtPath, dataSaver = dataSaver) ?: coverArtPath,
                        ) != null
                    if (hasUrlFallback) {
                        useFallbackUrl = true
                    } else {
                        customCoverFailed = true
                    }
                },
                modifier = Modifier.fillMaxSize(),
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

private fun sortModeLabel(sortMode: PlaylistSortMode, strings: AppStrings): String = when (sortMode) {
    PlaylistSortMode.CUSTOM -> strings.v34.sortCustom
    PlaylistSortMode.TITLE_ASC -> strings.v34.sortTitleAsc
    PlaylistSortMode.ADDED_DESC -> strings.v34.sortAddedDesc
    PlaylistSortMode.DURATION_DESC -> strings.v34.sortDurationDesc
    PlaylistSortMode.ARTIST_ASC -> strings.v34.sortArtistAsc
    PlaylistSortMode.ALBUM_ASC -> strings.v34.sortAlbumAsc
}

@Composable
private fun PlaylistDetailView(
    playlistWithTracks: PlaylistWithTracks,
    state: MainUiState,
    onBack: () -> Unit,
    onPlayAll: () -> Unit,
    onShufflePlay: () -> Unit,
    onEditPlaylist: () -> Unit,
    onTogglePinPlaylist: () -> Unit,
    onDeletePlaylist: () -> Unit,
    onChangeSortMode: (PlaylistSortMode) -> Unit,
    onPlayTrack: (TrackEntity, List<TrackEntity>) -> Unit,
    onToggleLike: (TrackEntity) -> Unit,
    onOpenPlaylistTrackOptions: (TrackEntity, Boolean, Boolean) -> Unit,
    bottomContentPadding: Dp,
) {
    val strings = LocalAppStrings.current
    val haptics = rememberSoundLapaHaptics()
    val sortMode = remember(playlistWithTracks.playlist.sortMode) {
        PlaylistSortMode.fromRaw(playlistWithTracks.playlist.sortMode)
    }
    val sortedTracks = remember(playlistWithTracks.tracks, sortMode) {
        when (sortMode) {
            PlaylistSortMode.CUSTOM -> playlistWithTracks.tracks
            PlaylistSortMode.TITLE_ASC -> playlistWithTracks.tracks.sortedBy { it.title.lowercase() }
            PlaylistSortMode.ADDED_DESC -> playlistWithTracks.tracks.asReversed()
            PlaylistSortMode.DURATION_DESC -> playlistWithTracks.tracks.sortedByDescending { it.durationMs }
            PlaylistSortMode.ARTIST_ASC -> playlistWithTracks.tracks.sortedBy { it.artist.lowercase() }
            PlaylistSortMode.ALBUM_ASC -> playlistWithTracks.tracks.sortedBy { (it.albumTitle ?: "").lowercase() }
        }
    }
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
    val collapseRangePx = remember(density) { with(density) { 290.dp.toPx() } }
    val showBanner by remember(collapseRangePx) {
        derivedStateOf {
            val offset = if (listState.firstVisibleItemIndex == 0) {
                listState.firstVisibleItemScrollOffset.toFloat()
            } else {
                collapseRangePx
            }
            (offset / collapseRangePx).coerceIn(0f, 1f) < 0.99f
        }
    }
    val scrollOffsetProvider = remember(listState, collapseRangePx) {
        {
            if (listState.firstVisibleItemIndex == 0) {
                listState.firstVisibleItemScrollOffset.toFloat()
            } else {
                collapseRangePx
            }
        }
    }
    val collapseFractionProvider = remember(listState, collapseRangePx) {
        {
            val offset = if (listState.firstVisibleItemIndex == 0) {
                listState.firstVisibleItemScrollOffset.toFloat()
            } else {
                collapseRangePx
            }
            (offset / collapseRangePx).coerceIn(0f, 1f)
        }
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

    val parsedDescription = remember(playlistWithTracks.playlist.description) {
        PlaylistMetadataHelper.parse(playlistWithTracks.playlist.description)
    }
    val detailTracksCountLabel = remember(playlistWithTracks.tracks.size, strings) {
        strings.settingsV33.libraryPlaylistTracksCount(playlistWithTracks.tracks.size)
    }
    val currentOnPlayTrack by rememberUpdatedState(onPlayTrack)
    val currentOnToggleLike by rememberUpdatedState(onToggleLike)
    val currentOnOpenPlaylistTrackOptions by rememberUpdatedState(onOpenPlaylistTrackOptions)
    val latestSortedTracks by rememberUpdatedState(sortedTracks)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .widthIn(max = 720.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        if (showBanner) {
            PlaylistFloatingCoverBackdrop(
                coverArtUrl = backdropCoverUrl,
                fallbackTrack = backdropTrack,
                heroAccent = heroTint,
                heroSecondary = heroSecondary,
                backgroundColor = backgroundColor,
                collapseFractionProvider = collapseFractionProvider,
                scrollOffsetProvider = scrollOffsetProvider,
                isFastScrollingProvider = { listState.isScrollInProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(430.dp)
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
                        .statusBarsPadding()
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
                                    haptics.confirm()
                                    onTogglePinPlaylist()
                                },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.62f))
                                    .testTag("pin_playlist_detail_button"),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.PushPin,
                                    contentDescription = if (playlistWithTracks.playlist.isPinned) {
                                        strings.v34.unpinPlaylistAction
                                    } else {
                                        strings.v34.pinPlaylistAction
                                    },
                                    tint = if (playlistWithTracks.playlist.isPinned) accent else MaterialTheme.colorScheme.onBackground,
                                )
                            }

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
                            contentColor = MaterialTheme.colorScheme.onSurface,
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
                            if (parsedDescription.userDescription.isNotBlank()) {
                                Text(
                                    text = parsedDescription.userDescription,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.testTag("playlist_detail_user_description"),
                                )
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                parsedDescription.importSource?.let { source ->
                                    Box(
                                        modifier = Modifier
                                            .clip(CircleShape)
                                            .background(accent.copy(alpha = 0.16f))
                                            .padding(horizontal = 9.dp, vertical = 3.dp)
                                            .testTag("playlist_import_source_badge"),
                                    ) {
                                        Text(
                                            text = strings.stats.playlistImportSourceLabel(source),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = accent,
                                            maxLines = 1,
                                        )
                                    }
                                }
                                Text(
                                    text = detailTracksCountLabel,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.testTag("playlist_detail_tracks_count"),
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

                    if (playlistWithTracks.tracks.size > 1) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            item(key = "sort_label_icon") {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier.padding(end = 2.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Sort,
                                        contentDescription = strings.v34.sortLabel,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Text(
                                        text = strings.v34.sortLabel,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            items(PlaylistSortMode.entries, key = { it.raw }) { mode ->
                                val isSelected = mode == sortMode
                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        haptics.tick()
                                        onChangeSortMode(mode)
                                    },
                                    label = {
                                        Text(
                                            text = sortModeLabel(mode, strings),
                                            style = MaterialTheme.typography.labelMedium,
                                            maxLines = 1,
                                        )
                                    },
                                    shape = CircleShape,
                                    border = null,
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = accent,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                                        labelColor = MaterialTheme.colorScheme.onSurface,
                                    ),
                                    modifier = Modifier.testTag("playlist_sort_chip_${mode.raw}"),
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
            }

            if (sortedTracks.isNotEmpty()) {
                itemsIndexed(
                    items = sortedTracks,
                    key = { _, tr -> "pl_${tr.id}" },
                    contentType = { _, _ -> "playlist_track_row" },
                ) { index, track ->
                    val isCurrent = state.playback.currentTrackId == track.id
                    val canMoveUp = sortMode == PlaylistSortMode.CUSTOM && index > 0
                    val canMoveDown = sortMode == PlaylistSortMode.CUSTOM && index < sortedTracks.lastIndex
                    val latestTrack by rememberUpdatedState(track)
                    val latestCanMoveUp by rememberUpdatedState(canMoveUp)
                    val latestCanMoveDown by rememberUpdatedState(canMoveDown)
                    val onPlayClick = remember(track.id) {
                        {
                            haptics.tap()
                            currentOnPlayTrack(latestTrack, latestSortedTracks)
                        }
                    }
                    val onLikeClick = remember(track.id) {
                        {
                            haptics.confirm()
                            currentOnToggleLike(latestTrack)
                        }
                    }
                    val onMoreClick = remember(track.id) {
                        {
                            haptics.tick()
                            currentOnOpenPlaylistTrackOptions(latestTrack, latestCanMoveUp, latestCanMoveDown)
                        }
                    }
                    TrackRowItem(
                        track = track,
                        isCurrentTrack = isCurrent,
                        isPlaying = isCurrent && state.playback.isPlaying,
                        compact = true,
                        onPlayClick = onPlayClick,
                        onLikeClick = onLikeClick,
                        onMoreClick = onMoreClick,
                    )
                }
            } else {
                item(key = "pl_empty", contentType = "empty_notice") {
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
    collapseFractionProvider: () -> Float,
    scrollOffsetProvider: () -> Float,
    isFastScrollingProvider: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val isForeground = rememberIsAppForeground()
    val isUiVisible = com.example.ui.shaders.LocalIsMainUiVisible.current
    val batterySaver = LocalBatterySaverEnabled.current
    val isFastScrolling by remember(isFastScrollingProvider) {
        derivedStateOf { isFastScrollingProvider() }
    }
    var lastPhase by remember { mutableFloatStateOf(0f) }
    val phase = if (!batterySaver && isForeground && isUiVisible && !isFastScrolling) {
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
        lastPhase = animatedPhase
        animatedPhase
    } else {
        lastPhase
    }

    val fadeScrim = remember(backgroundColor) {
        Brush.verticalGradient(
            0.0f to backgroundColor.copy(alpha = 0.08f),
            0.45f to backgroundColor.copy(alpha = 0.36f),
            0.78f to backgroundColor.copy(alpha = 0.86f),
            1.0f to backgroundColor,
        )
    }

    Box(
        modifier = modifier
            .graphicsLayer {
                alpha = (1f - collapseFractionProvider()).coerceIn(0f, 1f)
                translationY = -scrollOffsetProvider() * 0.36f
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
            val resolvedBackdropData = remember(coverArtUrl) {
                resolveCoverImageData(coverArtUrl, dataSaver = true)
            }
            var backdropFailed by remember(coverArtUrl) { mutableStateOf(false) }
            val backdropRequest = remember(resolvedBackdropData, backdropFailed) {
                if (backdropFailed || resolvedBackdropData == null) null
                else ImageRequest.Builder(context)
                    .data(resolvedBackdropData)
                    .allowHardware(true)
                    .size(160)
                    .crossfade(false)
                    .build()
            }
            if (backdropRequest != null) {
                AsyncImage(
                    model = backdropRequest,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    onError = { backdropFailed = true },
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (fallbackTrack != null) {
                TrackCoverArtwork(
                    track = fallbackTrack,
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
        contentColor = MaterialTheme.colorScheme.onSurface,
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
                var avatarLoadFailed by remember(subscription.avatarUrl) { mutableStateOf(false) }
                if (!subscription.avatarUrl.isNullOrBlank() && !avatarLoadFailed) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(subscription.avatarUrl)
                            .size(160)
                            .allowHardware(true)
                            .crossfade(false)
                            .build(),
                        contentDescription = subscription.artistName,
                        contentScale = ContentScale.Crop,
                        onError = { avatarLoadFailed = true },
                        modifier = Modifier.fillMaxSize(),
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
