package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
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
import androidx.compose.material3.MaterialTheme
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
import com.example.ui.components.SystemUtilityTopBar
import com.example.ui.components.TrackOptionsBottomSheet
import com.example.ui.components.UploadTracksBottomSheet
import com.example.ui.components.rememberSoundLapaHaptics
import com.example.ui.i18n.LocalAppStrings
import com.example.ui.i18n.stringsForLanguage
import com.example.ui.screens.AboutScreen
import com.example.ui.screens.LibraryScreen
import com.example.ui.screens.NowPlayingSheet
import com.example.ui.screens.SearchScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.StreamScreen
import com.example.ui.theme.SoundLapaTheme
import kotlinx.serialization.Serializable

@Serializable
object HomeRoute

@Serializable
object SearchRoute

@Serializable
object LibraryRoute

@Composable
fun SoundLapaApp(
    viewModel: SoundLapaViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val homeState by viewModel.homeUiState.collectAsStateWithLifecycle()
    val isPlayerExpanded by viewModel.isPlayerExpanded.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = rememberSoundLapaHaptics()
    var askedNotificationPermission by remember { mutableStateOf(false) }

    var showSettingsSheet by remember { mutableStateOf(false) }
    var showAboutScreen by remember { mutableStateOf(false) }
    var showUploadSheet by remember { mutableStateOf(false) }
    var optionsMenuTrack by remember { mutableStateOf<TrackEntity?>(null) }
    var editingTrack by remember { mutableStateOf<TrackEntity?>(null) }
    var playlistPickerTrack by remember { mutableStateOf<TrackEntity?>(null) }

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

    val appStrings = remember(state.settings.appLanguage) {
        stringsForLanguage(state.settings.appLanguage)
    }

    SoundLapaTheme(
        darkTheme = state.settings.darkLiquidGlass,
        accentColor = Color(state.settings.accentColorArgb),
    ) {
        CompositionLocalProvider(LocalAppStrings provides appStrings) {
            val playerTransition = remember { MutableTransitionState(false) }
            playerTransition.targetState = isPlayerExpanded && state.currentTrack != null
            val settingsTransition = remember { MutableTransitionState(false) }
            settingsTransition.targetState = showSettingsSheet
            val aboutTransition = remember { MutableTransitionState(false) }
            aboutTransition.targetState = showAboutScreen

            val isMainScreenCovered =
                (playerTransition.currentState && playerTransition.targetState && playerTransition.isIdle) ||
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
                                    showSettingsSheet = true
                                },
                            )

                            NavHost(
                                navController = navController,
                                startDestination = HomeRoute,
                                modifier = Modifier.weight(1f),
                                enterTransition = {
                                    fadeIn(animationSpec = tween(170, easing = FastOutSlowInEasing))
                                },
                                exitTransition = {
                                    fadeOut(animationSpec = tween(140, easing = FastOutSlowInEasing))
                                },
                                popEnterTransition = {
                                    fadeIn(animationSpec = tween(170, easing = FastOutSlowInEasing))
                                },
                                popExitTransition = {
                                    fadeOut(animationSpec = tween(140, easing = FastOutSlowInEasing))
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
                                        onToggleLike = viewModel::toggleLike,
                                        onOpenTrackOptions = { optionsMenuTrack = it },
                                        onOpenUploadSheet = { showUploadSheet = true },
                                        onRefreshTrending = viewModel::refreshAudiusTrending,
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
                                        bottomContentPadding = bottomOverlaySpace,
                                    )
                                }

                                composable<LibraryRoute> {
                                    LibraryScreen(
                                        state = state,
                                        onCreatePlaylist = { name, desc ->
                                            viewModel.createPlaylist(name, desc)
                                        },
                                        onDeletePlaylist = viewModel::deletePlaylist,
                                        onPlayPlaylist = { pl ->
                                            ensureNotificationPermissionOnce()
                                            viewModel.playPlaylist(pl)
                                        },
                                        onPlayTrack = { track, queue ->
                                            ensureNotificationPermissionOnce()
                                            viewModel.playOrToggleTrack(track, queue)
                                        },
                                        onRemoveTrackFromPlaylist = viewModel::removeTrackFromPlaylist,
                                        onToggleLike = viewModel::toggleLike,
                                        onOpenTrackOptions = { optionsMenuTrack = it },
                                        onSyncAudiusAccount = viewModel::syncAudiusAccount,
                                        onDisconnectAudius = viewModel::disconnectAudiusAccount,
                                        bottomContentPadding = bottomOverlaySpace,
                                    )
                                }
                            }
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
                                    onTogglePlayPause = {
                                        haptics.tap()
                                        ensureNotificationPermissionOnce()
                                        val current = state.currentTrack
                                        if (state.playback.currentTrackId == null && current != null) {
                                            viewModel.playOrToggleTrack(current)
                                        } else {
                                            viewModel.playbackController.togglePlayPause()
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

            // Full-Screen Liquid Glass Now Playing Sheet with snappy slide
            AnimatedVisibility(
                visibleState = playerTransition,
                enter = slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = tween(280, easing = FastOutSlowInEasing),
                ),
                exit = slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = tween(240, easing = FastOutSlowInEasing),
                ),
            ) {
                state.currentTrack?.let { track ->
                    val isInPlaylist = remember(state.playlists, track.id) {
                        state.playlists.any { pl ->
                            pl.tracks.any { it.id == track.id }
                        }
                    }
                    NowPlayingSheet(
                        track = track,
                        playback = state.playback,
                        positionFlow = viewModel.playbackPositionMs,
                        isInPlaylist = isInPlaylist,
                        onCollapse = { viewModel.setPlayerExpanded(false) },
                        onTogglePlayPause = {
                            ensureNotificationPermissionOnce()
                            if (state.playback.currentTrackId == null) {
                                viewModel.playOrToggleTrack(track)
                            } else {
                                viewModel.playbackController.togglePlayPause()
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
                    )
                }
            }

            // Full-Screen Settings Page with snappy slide transition
            AnimatedVisibility(
                visibleState = settingsTransition,
                enter = slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = tween(260, easing = FastOutSlowInEasing),
                ),
                exit = slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = tween(220, easing = FastOutSlowInEasing),
                ),
            ) {
                SettingsScreen(
                    state = state,
                    onClose = {
                        viewModel.clearAudiusStatus()
                        showSettingsSheet = false
                    },
                    onSelectLanguage = viewModel::setAppLanguage,
                    onSelectAccentColor = viewModel::setAccentColorArgb,
                    onToggleDarkTheme = viewModel::setDarkLiquidGlass,
                    onToggleSmoothTransition = viewModel::setSmoothTransitionEnabled,
                    onChangeSmoothTransitionDuration = viewModel::setSmoothTransitionDurationSec,
                    onToggleAutoUpload = viewModel::setAutoUploadEnabled,
                    onToggleAutoCache = viewModel::setAutoCacheOnPlay,
                    onSetAutoUploadFolder = viewModel::setAutoUploadFolder,
                    onClearAutoUploadFolder = viewModel::clearAutoUploadFolder,
                    onSyncAudiusAccount = viewModel::syncAudiusAccount,
                    onDisconnectAudius = viewModel::disconnectAudiusAccount,
                )
            }

            // Full-Screen About App Page with snappy slide transition
            AnimatedVisibility(
                visibleState = aboutTransition,
                enter = slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = tween(240, easing = FastOutSlowInEasing),
                ),
                exit = slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = tween(200, easing = FastOutSlowInEasing),
                ),
            ) {
                AboutScreen(
                    onClose = { showAboutScreen = false },
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
                    { viewModel.deleteTrack(track) }
                } else null,
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
        }
    }
}
