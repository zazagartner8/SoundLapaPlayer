package com.example.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.data.local.TrackEntity
import com.example.ui.components.AddToPlaylistBottomSheet
import com.example.ui.components.EditTrackDialog
import com.example.ui.components.FloatingMiniPlayer
import com.example.ui.components.LiquidBottomNavBar
import com.example.ui.components.LiquidNavigationRail
import com.example.ui.components.NetworkReconnectedBanner
import com.example.ui.components.OfflineNetworkErrorBanner
import com.example.ui.components.QueueBottomSheet
import com.example.ui.components.SystemUtilityTopBar
import com.example.ui.components.TrackOptionsBottomSheet
import com.example.ui.components.UploadTracksBottomSheet
import com.example.ui.components.rememberSoundLapaHaptics
import com.example.ui.i18n.LocalAppStrings
import com.example.ui.i18n.stringsForLanguage
import com.example.ui.screens.AboutScreen
import com.example.ui.screens.ArtistProfileScreen
import com.example.ui.screens.LibraryScreen
import com.example.ui.screens.NowPlayingSheet
import com.example.ui.screens.SearchScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.StreamScreen
import com.example.ui.shaders.LocalDataSaverEnabled
import com.example.ui.theme.LiquidRose
import com.example.ui.theme.SoundLapaTheme
import kotlinx.serialization.Serializable

@Serializable
object HomeRoute

@Serializable
object SearchRoute

@Serializable
object LibraryRoute

private val SilkyEaseOut = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

private fun tabIndexForRoute(route: String?): Int = when {
    route?.contains("SearchRoute") == true -> 1
    route?.contains("LibraryRoute") == true -> 2
    else -> 0
}

@Composable
fun SoundLapaApp(
    viewModel: SoundLapaViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val homeState by viewModel.homeUiState.collectAsStateWithLifecycle()
    val isPlayerExpanded by viewModel.isPlayerExpanded.collectAsStateWithLifecycle()
    val artistProfile by viewModel.artistProfileState.collectAsStateWithLifecycle()
    val queueTracks by viewModel.playbackController.queueTracks.collectAsStateWithLifecycle()
    val requestedLibrarySection by viewModel.requestedLibrarySection.collectAsStateWithLifecycle()
    val showReconnectedBanner by viewModel.showReconnectedBanner.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = rememberSoundLapaHaptics()
    var askedNotificationPermission by remember { mutableStateOf(false) }

    var showSettingsSheet by remember { mutableStateOf(false) }
    var showAboutScreen by remember { mutableStateOf(false) }
    var showUploadSheet by remember { mutableStateOf(false) }
    var showQueueSheet by remember { mutableStateOf(false) }
    var optionsMenuTrack by remember { mutableStateOf<TrackEntity?>(null) }
    var editingTrack by remember { mutableStateOf<TrackEntity?>(null) }
    var playlistPickerTrack by remember { mutableStateOf<TrackEntity?>(null) }
    var pendingDeleteTrack by remember { mutableStateOf<TrackEntity?>(null) }
    var isPlayerDragging by remember { mutableStateOf(false) }

    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { }

    fun ensureNotificationPermissionOnce() {
        if (askedNotificationPermission) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                askedNotificationPermission = true
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshConnectivity()
                viewModel.syncAutoUploadFolderIfEnabled()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val selectedTab = when {
        currentRoute?.contains("SearchRoute") == true -> SoundLapaTab.SEARCH
        currentRoute?.contains("LibraryRoute") == true -> SoundLapaTab.LIBRARY
        else -> SoundLapaTab.HOME
    }

    fun navigateToTab(tab: SoundLapaTab) {
        haptics.tick()
        if (artistProfile.isVisible) {
            viewModel.closeArtistProfile()
        }
        val route: Any = when (tab) {
            SoundLapaTab.HOME -> HomeRoute
            SoundLapaTab.SEARCH -> SearchRoute
            SoundLapaTab.LIBRARY -> LibraryRoute
        }
        navController.navigate(route) {
            popUpTo(HomeRoute) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    fun openDownloadedTracksOffline() {
        viewModel.setPlayerExpanded(false)
        showSettingsSheet = false
        showAboutScreen = false
        viewModel.requestOpenDownloadedTracksSection()
        navigateToTab(SoundLapaTab.LIBRARY)
    }

    val appStrings = remember(state.settings.appLanguage) {
        stringsForLanguage(state.settings.appLanguage)
    }

    SoundLapaTheme(
        darkTheme = state.settings.darkLiquidGlass,
        accentColor = Color(state.settings.accentColorArgb),
    ) {
        CompositionLocalProvider(
            LocalAppStrings provides appStrings,
            LocalDataSaverEnabled provides state.settings.dataSaverEnabled,
        ) {
            val playerTransition = remember { MutableTransitionState(false) }
            playerTransition.targetState = isPlayerExpanded && state.currentTrack != null
            val settingsTransition = remember { MutableTransitionState(false) }
            settingsTransition.targetState = showSettingsSheet
            val aboutTransition = remember { MutableTransitionState(false) }
            aboutTransition.targetState = showAboutScreen

            val isMainScreenCovered =
                (!isPlayerDragging && playerTransition.currentState && playerTransition.targetState && playerTransition.isIdle) ||
                    (settingsTransition.currentState && settingsTransition.targetState && settingsTransition.isIdle) ||
                    (aboutTransition.currentState && aboutTransition.targetState && aboutTransition.isIdle)

            // Simple clean dark background for main screens; Liquid Glass shader is reserved for Now Playing
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
            ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        if (!isMainScreenCovered) {
                            drawContent()
                        }
                    },
            ) {
                val useRail = maxWidth >= 700.dp
                val bottomOverlaySpace = if (useRail) 104.dp else 164.dp

                Row(modifier = Modifier.fillMaxSize()) {
                    if (useRail) {
                        LiquidNavigationRail(
                            selectedTab = selectedTab,
                            onSelectTab = ::navigateToTab,
                            modifier = Modifier
                                .fillMaxHeight()
                                .windowInsetsPadding(WindowInsets.statusBars),
                        )
                    }

                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .windowInsetsPadding(WindowInsets.statusBars),
                        ) {
                            SystemUtilityTopBar(
                                onAboutClick = {
                                    haptics.tick()
                                    showAboutScreen = true
                                },
                                onSettingsClick = {
                                    haptics.tick()
                                    viewModel.refreshCacheSize()
                                    showSettingsSheet = true
                                },
                            )

                            AnimatedVisibility(visible = !state.isOnline) {
                                OfflineNetworkErrorBanner(
                                    offlineTracksCount = state.offlineTracksCount,
                                    onGoToDownloadedTracks = ::openDownloadedTracksOffline,
                                )
                            }

                            NavHost(
                                navController = navController,
                                startDestination = HomeRoute,
                                modifier = Modifier.weight(1f),
                                enterTransition = {
                                    val from = tabIndexForRoute(initialState.destination.route)
                                    val to = tabIndexForRoute(targetState.destination.route)
                                    val dir = if (to >= from) 1 else -1
                                    slideInHorizontally(
                                        animationSpec = tween(290, easing = SilkyEaseOut),
                                    ) { fullWidth -> dir * fullWidth } +
                                        fadeIn(animationSpec = tween(210, easing = FastOutSlowInEasing))
                                },
                                exitTransition = {
                                    val from = tabIndexForRoute(initialState.destination.route)
                                    val to = tabIndexForRoute(targetState.destination.route)
                                    val dir = if (to >= from) 1 else -1
                                    slideOutHorizontally(
                                        animationSpec = tween(290, easing = SilkyEaseOut),
                                    ) { fullWidth -> -dir * (fullWidth / 3) } +
                                        fadeOut(animationSpec = tween(170, easing = FastOutSlowInEasing))
                                },
                                popEnterTransition = {
                                    val from = tabIndexForRoute(initialState.destination.route)
                                    val to = tabIndexForRoute(targetState.destination.route)
                                    val dir = if (to >= from) 1 else -1
                                    slideInHorizontally(
                                        animationSpec = tween(290, easing = SilkyEaseOut),
                                    ) { fullWidth -> dir * fullWidth } +
                                        fadeIn(animationSpec = tween(210, easing = FastOutSlowInEasing))
                                },
                                popExitTransition = {
                                    val from = tabIndexForRoute(initialState.destination.route)
                                    val to = tabIndexForRoute(targetState.destination.route)
                                    val dir = if (to >= from) 1 else -1
                                    slideOutHorizontally(
                                        animationSpec = tween(290, easing = SilkyEaseOut),
                                    ) { fullWidth -> -dir * (fullWidth / 3) } +
                                        fadeOut(animationSpec = tween(170, easing = FastOutSlowInEasing))
                                },
                            ) {
                                composable<HomeRoute> {
                                    StreamScreen(
                                        state = homeState,
                                        availableFilters = viewModel.availableFilters,
                                        onSelectFilter = viewModel::selectFilter,
                                        onSearchQueryChange = viewModel::updateSearchQuery,
                                        onCloseSearch = viewModel::toggleSearch,
                                        onPlayTrack = { track ->
                                            ensureNotificationPermissionOnce()
                                            viewModel.playOrToggleTrack(track)
                                        },
                                        onPlayRecommendedTrack = { track ->
                                            ensureNotificationPermissionOnce()
                                            viewModel.playOrToggleTrack(track, homeState.recommendedTracks)
                                        },
                                        onToggleLike = viewModel::toggleLike,
                                        onOpenTrackOptions = { optionsMenuTrack = it },
                                        bottomContentPadding = bottomOverlaySpace,
                                    )
                                }

                                composable<SearchRoute> {
                                    SearchScreen(
                                        state = state,
                                        onQueryChange = { viewModel.updateSearchQuery(it) },
                                        onSubmitSearch = viewModel::submitSearchQuery,
                                        onClearQuery = { viewModel.updateSearchQuery("") },
                                        onRemoveHistoryItem = viewModel::removeSearchHistoryItem,
                                        onClearHistory = viewModel::clearSearchHistory,
                                        onPlayTrack = { track, results ->
                                            ensureNotificationPermissionOnce()
                                            viewModel.saveCurrentSearchToHistory()
                                            viewModel.playOrToggleTrack(track, results)
                                        },
                                        onToggleLike = viewModel::toggleLike,
                                        onOpenTrackOptions = { optionsMenuTrack = it },
                                        onOpenArtistProfile = { profile ->
                                            viewModel.saveCurrentSearchToHistory()
                                            viewModel.openArtistProfile(
                                                artistName = profile.name,
                                                seedTrack = profile.seedTrack,
                                                spotifyId = profile.spotifyId,
                                                soundCloudUrl = profile.soundCloudUrl,
                                            )
                                        },
                                        bottomContentPadding = bottomOverlaySpace,
                                    )
                                }

                                composable<LibraryRoute> {
                                    LibraryScreen(
                                        state = state,
                                        requestedSection = requestedLibrarySection,
                                        onConsumeRequestedSection = viewModel::consumeRequestedLibrarySection,
                                        onCreatePlaylist = { name, desc ->
                                            viewModel.createPlaylist(name, desc)
                                        },
                                        onDeletePlaylist = viewModel::deletePlaylist,
                                        onPlayPlaylist = { pl ->
                                            ensureNotificationPermissionOnce()
                                            viewModel.playPlaylist(pl)
                                        },
                                        onPlayPlaylistShuffle = { pl ->
                                            ensureNotificationPermissionOnce()
                                            viewModel.playPlaylist(pl, shuffled = true)
                                        },
                                        onPlayTrack = { track, queue ->
                                            ensureNotificationPermissionOnce()
                                            viewModel.playOrToggleTrack(track, queue)
                                        },
                                        onRemoveTrackFromPlaylist = viewModel::removeTrackFromPlaylist,
                                        onToggleLike = viewModel::toggleLike,
                                        onOpenTrackOptions = { optionsMenuTrack = it },
                                        onOpenUploadSheet = { showUploadSheet = true },
                                        bottomContentPadding = bottomOverlaySpace,
                                    )
                                }
                            }
                        }

                        androidx.compose.animation.AnimatedVisibility(
                            visible = artistProfile.isVisible,
                            enter = slideInHorizontally(
                                initialOffsetX = { it },
                                animationSpec = tween(310, easing = SilkyEaseOut),
                            ) + fadeIn(animationSpec = tween(200, easing = FastOutSlowInEasing)),
                            exit = slideOutHorizontally(
                                targetOffsetX = { it },
                                animationSpec = tween(260, easing = SilkyEaseOut),
                            ) + fadeOut(animationSpec = tween(180, easing = FastOutSlowInEasing)),
                        ) {
                            ArtistProfileScreen(
                                state = artistProfile,
                                currentTrackId = state.playback.currentTrackId,
                                isPlaying = state.playback.isPlaying,
                                bottomContentPadding = bottomOverlaySpace,
                                onClose = viewModel::closeArtistProfile,
                                onPlayAll = {
                                    ensureNotificationPermissionOnce()
                                    viewModel.playArtistTracks(shuffled = false)
                                },
                                onShufflePlay = {
                                    ensureNotificationPermissionOnce()
                                    viewModel.playArtistTracks(shuffled = true)
                                },
                                onPlayTrack = { track, queue ->
                                    ensureNotificationPermissionOnce()
                                    viewModel.playOrToggleTrack(track, queue)
                                },
                                onToggleLike = viewModel::toggleLike,
                                onOpenTrackOptions = { optionsMenuTrack = it },
                                onOpenAlbum = viewModel::openArtistAlbum,
                                onCloseAlbum = viewModel::closeArtistAlbum,
                                onPlayAlbum = { albumTracks, startTrack ->
                                    ensureNotificationPermissionOnce()
                                    viewModel.playAlbumTracks(albumTracks, startTrack)
                                },
                            )
                        }

                        Column(
                            modifier = Modifier.align(Alignment.BottomCenter),
                        ) {
                            if (!isMainScreenCovered) {
                                FloatingMiniPlayer(
                                    track = state.currentTrack,
                                    isPlaying = state.playback.isPlaying,
                                    positionFlow = viewModel.playbackPositionMs,
                                    durationMs = state.playback.durationMs.takeIf { it > 0L }
                                        ?: state.currentTrack?.durationMs ?: 0L,
                                    onPrevious = {
                                        haptics.tap()
                                        viewModel.playbackController.skipPrevious()
                                    },
                                    onTogglePlayPause = {
                                        haptics.tap()
                                        ensureNotificationPermissionOnce()
                                        val current = state.currentTrack
                                        if (current != null &&
                                            (state.playback.currentTrackId == null ||
                                                !viewModel.playbackController.isTrackActiveInController(current.id))
                                        ) {
                                            viewModel.playOrToggleTrack(current)
                                        } else {
                                            viewModel.playbackController.togglePlayPause(current)
                                        }
                                    },
                                    onNext = {
                                        haptics.tap()
                                        viewModel.playbackController.skipNext()
                                    },
                                    onExpand = {
                                        haptics.tick()
                                        viewModel.setPlayerExpanded(true)
                                    },
                                    modifier = Modifier.padding(bottom = if (useRail) 14.dp else 6.dp),
                                )
                            }

                            if (!useRail) {
                                LiquidBottomNavBar(
                                    selectedTab = selectedTab,
                                    onSelectTab = ::navigateToTab,
                                )
                            }
                        }
                    }
                }
            }

            // Full-Screen Now Playing Sheet with silky slide
            AnimatedVisibility(
                visibleState = playerTransition,
                enter = slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = tween(320, easing = SilkyEaseOut),
                ) + fadeIn(animationSpec = tween(180)),
                exit = slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = tween(260, easing = FastOutSlowInEasing),
                ) + fadeOut(animationSpec = tween(160)),
            ) {
                state.currentTrack?.let { track ->
                    val isInPlaylist = remember(state.playlists, track.id) {
                        state.playlists.any { pl ->
                            pl.tracks.any { it.id == track.id }
                        }
                    }
                    val activeQueue = remember(queueTracks, state.tracks) {
                        queueTracks.ifEmpty { state.tracks }
                    }
                    val nextTrack = remember(activeQueue, track.id) {
                        val idx = activeQueue.indexOfFirst { it.id == track.id }
                        if (idx >= 0 && idx + 1 < activeQueue.size) activeQueue[idx + 1] else null
                    }
                    NowPlayingSheet(
                        track = track,
                        playback = state.playback,
                        positionFlow = viewModel.playbackPositionMs,
                        isInPlaylist = isInPlaylist,
                        nextTrack = nextTrack,
                        queueCount = activeQueue.size,
                        onCollapse = {
                            isPlayerDragging = false
                            viewModel.setPlayerExpanded(false)
                        },
                        onDragStateChange = { isPlayerDragging = it },
                        onTogglePlayPause = {
                            ensureNotificationPermissionOnce()
                            if (state.playback.currentTrackId == null ||
                                !viewModel.playbackController.isTrackActiveInController(track.id)
                            ) {
                                viewModel.playOrToggleTrack(track)
                            } else {
                                viewModel.playbackController.togglePlayPause(track)
                            }
                        },
                        onPrevious = viewModel.playbackController::skipPrevious,
                        onNext = viewModel.playbackController::skipNext,
                        onSeekFraction = { fraction ->
                            viewModel.playbackController.seekToFraction(fraction, track)
                        },
                        onToggleShuffle = viewModel.playbackController::toggleShuffle,
                        onCycleRepeat = viewModel.playbackController::cycleRepeatMode,
                        onToggleLike = { viewModel.toggleLike(track) },
                        onOpenTrackOptions = { optionsMenuTrack = track },
                        onAddToPlaylist = { playlistPickerTrack = track },
                        onOpenQueue = { showQueueSheet = true },
                        onShareTrack = {
                            val shareText = buildString {
                                append("${track.artist} — ${track.title}")
                                val link = track.sourceUri?.takeIf { it.startsWith("http") }
                                if (link != null) {
                                    append("\n")
                                    append(link)
                                }
                            }
                            runCatching {
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, "${track.artist} — ${track.title}")
                                    putExtra(Intent.EXTRA_TEXT, shareText)
                                }
                                context.startActivity(
                                    Intent.createChooser(intent, appStrings.shareTrackChooserTitle),
                                )
                            }
                        },
                        onOpenArtistProfile = {
                            viewModel.setPlayerExpanded(false)
                            viewModel.openArtistProfile(track.artist, track)
                        },
                    )
                }
            }

            // Full-Screen Settings Page with horizontal slide transition
            AnimatedVisibility(
                visibleState = settingsTransition,
                enter = slideInHorizontally(
                    initialOffsetX = { it },
                    animationSpec = tween(310, easing = SilkyEaseOut),
                ) + fadeIn(animationSpec = tween(200, easing = FastOutSlowInEasing)),
                exit = slideOutHorizontally(
                    targetOffsetX = { it },
                    animationSpec = tween(260, easing = SilkyEaseOut),
                ) + fadeOut(animationSpec = tween(180, easing = FastOutSlowInEasing)),
            ) {
                SettingsScreen(
                    state = state,
                    onClose = {
                        viewModel.clearAudiusStatus()
                        viewModel.clearCacheMessage()
                        viewModel.clearImportStatus()
                        showSettingsSheet = false
                    },
                    onSelectLanguage = viewModel::setAppLanguage,
                    onSelectAccentColor = viewModel::setAccentColorArgb,
                    onToggleDarkTheme = viewModel::setDarkLiquidGlass,
                    onToggleSmoothTransition = viewModel::setSmoothTransitionEnabled,
                    onChangeSmoothTransitionDuration = viewModel::setSmoothTransitionDurationSec,
                    onToggleAutoUpload = viewModel::setAutoUploadEnabled,
                    onToggleAutoCache = viewModel::setAutoCacheOnPlay,
                    onToggleDataSaver = viewModel::setDataSaverEnabled,
                    onClearCache = viewModel::clearAppCache,
                    onImportFromInput = viewModel::importExternalPlaylistFromInput,
                    onImportFromFile = viewModel::importExternalPlaylistFromFile,
                    onSetAutoUploadFolder = viewModel::setAutoUploadFolder,
                    onClearAutoUploadFolder = viewModel::clearAutoUploadFolder,
                    onSyncAudiusAccount = viewModel::syncAudiusAccount,
                    onDisconnectAudius = viewModel::disconnectAudiusAccount,
                )
            }

            // Full-Screen About App Page with horizontal slide transition
            AnimatedVisibility(
                visibleState = aboutTransition,
                enter = slideInHorizontally(
                    initialOffsetX = { it },
                    animationSpec = tween(310, easing = SilkyEaseOut),
                ) + fadeIn(animationSpec = tween(200, easing = FastOutSlowInEasing)),
                exit = slideOutHorizontally(
                    targetOffsetX = { it },
                    animationSpec = tween(260, easing = SilkyEaseOut),
                ) + fadeOut(animationSpec = tween(180, easing = FastOutSlowInEasing)),
            ) {
                AboutScreen(
                    onClose = { showAboutScreen = false },
                )
            }

            AnimatedVisibility(
                visible = showReconnectedBanner,
                enter = slideInVertically(
                    initialOffsetY = { -it },
                    animationSpec = tween(280, easing = SilkyEaseOut),
                ) + fadeIn(animationSpec = tween(200)),
                exit = slideOutVertically(
                    targetOffsetY = { -it },
                    animationSpec = tween(240, easing = FastOutSlowInEasing),
                ) + fadeOut(animationSpec = tween(180)),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(top = 6.dp),
            ) {
                NetworkReconnectedBanner(
                    onDismiss = viewModel::dismissReconnectedBanner,
                )
            }
        }

        // Bottom Sheets & Dialogs
        optionsMenuTrack?.let { track ->
            TrackOptionsBottomSheet(
                track = track,
                onDismiss = { optionsMenuTrack = null },
                onEditClick = if (track.isUserUpload) {
                    { editingTrack = track }
                } else null,
                onAddToPlaylistClick = { playlistPickerTrack = track },
                onDeleteClick = if (track.isUserUpload) {
                    { pendingDeleteTrack = track }
                } else null,
                onOpenArtistProfile = {
                    viewModel.setPlayerExpanded(false)
                    viewModel.openArtistProfile(track.artist, track)
                },
            )
        }

        pendingDeleteTrack?.let { trackToDelete ->
            AlertDialog(
                onDismissRequest = { pendingDeleteTrack = null },
                title = { Text(appStrings.confirmDeleteTitle) },
                text = { Text(appStrings.confirmDeleteTrackMessage(trackToDelete.title)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            haptics.confirm()
                            viewModel.deleteTrack(trackToDelete)
                            pendingDeleteTrack = null
                        },
                    ) {
                        Text(appStrings.deleteConfirmAction, color = LiquidRose)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDeleteTrack = null }) {
                        Text(appStrings.cancel)
                    }
                },
            )
        }

        editingTrack?.let { track ->
            EditTrackDialog(
                track = track,
                onDismiss = { editingTrack = null },
                onSave = { newTitle, newArtist, newCoverUri, clearCover ->
                    viewModel.updateTrackDetails(track, newTitle, newArtist, newCoverUri, clearCover)
                    editingTrack = null
                },
            )
        }

        playlistPickerTrack?.let { track ->
            AddToPlaylistBottomSheet(
                track = track,
                playlists = state.playlists,
                onDismiss = { playlistPickerTrack = null },
                onSelectPlaylist = { playlistId ->
                    viewModel.addTrackToPlaylist(playlistId, track)
                },
                onRemoveFromPlaylist = { playlistId ->
                    viewModel.removeTrackFromPlaylist(playlistId, track.id)
                },
                onCreateNewPlaylist = { name ->
                    viewModel.createPlaylist(name = name, initialTrack = track)
                },
            )
        }

        if (showQueueSheet) {
            val activeQueue = queueTracks.ifEmpty { state.tracks }
            QueueBottomSheet(
                queueTracks = activeQueue,
                currentTrackId = state.currentTrack?.id,
                onSelectTrack = { chosen ->
                    ensureNotificationPermissionOnce()
                    viewModel.playOrToggleTrack(chosen, activeQueue)
                },
                onDismiss = { showQueueSheet = false },
            )
        }

        if (showUploadSheet) {
            UploadTracksBottomSheet(
                state = state,
                palettes = viewModel.uploadPalettes,
                onDismiss = {
                    viewModel.clearUploadMessage()
                    showUploadSheet = false
                },
                onStageAudioFiles = viewModel::importMultipleAudioFiles,
                onUpdateDraft = viewModel::updateStagedDraft,
                onPublishDraft = {
                    ensureNotificationPermissionOnce()
                    viewModel.publishStagedTrack()
                },
                onCancelDraft = viewModel::cancelStagedDraft,
                onSetAutoUploadFolder = viewModel::setAutoUploadFolder,
                onSyncNow = { viewModel.syncAutoUploadFolderNow() },
            )
        }

        if (state.showOfflineDialog && !state.isOnline) {
            val blockedTitle = state.offlineDialogTrackTitle
            val message = when {
                blockedTitle != null && state.offlineTracksCount > 0 ->
                    appStrings.offlineTrackRequiresNetworkMessage(blockedTitle)
                state.offlineTracksCount > 0 ->
                    appStrings.offlineErrorWithDownloadsMessage(state.offlineTracksCount)
                else ->
                    appStrings.offlineErrorNoDownloadsMessage
            }
            AlertDialog(
                onDismissRequest = viewModel::dismissOfflineDialog,
                icon = {
                    Icon(
                        imageVector = Icons.Rounded.WifiOff,
                        contentDescription = appStrings.offlineErrorTitle,
                        tint = MaterialTheme.colorScheme.error,
                    )
                },
                title = { Text(appStrings.offlineErrorTitle) },
                text = { Text(message) },
                confirmButton = {
                    if (state.offlineTracksCount > 0) {
                        Button(
                            onClick = {
                                haptics.confirm()
                                openDownloadedTracksOffline()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                        ) {
                            Text(appStrings.offlineGoToDownloadedAction)
                        }
                    } else {
                        TextButton(onClick = viewModel::dismissOfflineDialog) {
                            Text(appStrings.close)
                        }
                    }
                },
                dismissButton = {
                    if (state.offlineTracksCount > 0) {
                        TextButton(onClick = viewModel::dismissOfflineDialog) {
                            Text(appStrings.close)
                        }
                    }
                },
            )
        }
        }
    }
}
