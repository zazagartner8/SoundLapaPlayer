package com.example.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.credentials.CredentialManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import coil.imageLoader
import coil.request.ImageRequest
import com.example.R
import com.example.SoundLapaApplication
import com.example.data.local.LocalExplicitTrackIds
import com.example.data.local.PlaylistEntity
import com.example.data.local.TrackEntity
import com.example.data.remote.CloudLibraryRepository
import com.example.ui.auth.GoogleAuthManager
import com.example.ui.auth.authStateFlow
import com.example.ui.components.AddToPlaylistBottomSheet
import com.example.ui.components.BugReportDialog
import com.example.ui.components.EditPlaylistDialog
import com.example.ui.components.EditTrackDialog
import com.example.ui.components.FloatingMiniPlayer
import com.example.ui.components.HapticConfig
import com.example.ui.components.ImportPlaylistModalDialog
import com.example.ui.components.LiquidBottomNavBar
import com.example.ui.components.LiquidNavigationRail
import com.example.ui.components.LocalHapticsConfig
import com.example.ui.components.NetworkReconnectedBanner
import com.example.ui.components.OfflineNetworkErrorBanner
import com.example.ui.components.QueueBottomSheet
import com.example.ui.components.StartupLoadingOverlay
import com.example.ui.components.SystemUtilityTopBar
import com.example.ui.components.TrackOptionsBottomSheet
import com.example.ui.components.UploadTracksBottomSheet
import com.example.ui.components.rememberSoundLapaHaptics
import com.example.ui.i18n.LocalAppStrings
import com.example.ui.i18n.stringsForLanguage
import com.example.ui.screens.AboutScreen
import com.example.ui.screens.ArtistProfileScreen
import com.example.ui.screens.ChangelogHistoryScreen
import com.example.ui.screens.DeveloperScreen
import com.example.ui.screens.GoogleAuthScreen
import com.example.ui.screens.LibraryScreen
import com.example.ui.screens.NowPlayingSheet
import com.example.ui.screens.SearchScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.StreamScreen
import com.example.ui.screens.UserProfileAndFriendsScreen
import com.example.ui.shaders.LocalBatterySaverEnabled
import com.example.ui.shaders.LocalDataSaverEnabled
import com.example.ui.theme.LiquidRose
import com.example.ui.theme.SoundLapaTheme
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.util.concurrent.atomic.AtomicInteger

@Serializable
object HomeRoute

@Serializable
object SearchRoute

@Serializable
object LibraryRoute

private val SilkyEaseOut = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

private data class TrackOptionsMenuTarget(
    val track: TrackEntity,
    val playlistId: String? = null,
    val canMoveUp: Boolean = false,
    val canMoveDown: Boolean = false,
)

private fun tabIndexForRoute(route: String?): Int = when {
    route?.contains("SearchRoute") == true -> 1
    route?.contains("LibraryRoute") == true -> 2
    else -> 0
}

@Composable
fun SoundLapaApp() {
    val context = LocalContext.current
    val app = context.applicationContext as SoundLapaApplication
    val preferences = remember(app) { app.preferences }
    val preAuthSettings by preferences.settings.collectAsStateWithLifecycle(
        initialValue = preferences.currentSettings(),
    )
    val preAuthStrings = remember(preAuthSettings.appLanguage) {
        stringsForLanguage(preAuthSettings.appLanguage)
    }

    val currentUser by remember { Firebase.auth.authStateFlow() }
        .collectAsStateWithLifecycle(initialValue = Firebase.auth.currentUser)

    val user = currentUser
    if (user == null && !preAuthSettings.isGuestMode) {
        val scope = rememberCoroutineScope()
        SoundLapaTheme(
            darkTheme = preAuthSettings.darkLiquidGlass,
            accentColor = Color(preAuthSettings.accentColorArgb),
        ) {
            CompositionLocalProvider(
                LocalAppStrings provides preAuthStrings,
                LocalDataSaverEnabled provides preAuthSettings.dataSaverEnabled,
                LocalBatterySaverEnabled provides preAuthSettings.batterySaverEnabled,
                LocalHapticsConfig provides HapticConfig(
                    enabled = preAuthSettings.hapticsEnabled,
                    intensityPercent = preAuthSettings.hapticIntensity,
                ),
            ) {
                GoogleAuthScreen(
                    currentLanguageCode = preAuthSettings.appLanguage,
                    onSelectLanguage = { langCode ->
                        scope.launch { preferences.setAppLanguage(langCode) }
                    },
                    onAuthSuccess = {
                        scope.launch { preferences.setGuestMode(false) }
                    },
                    onContinueAsGuest = {
                        scope.launch { preferences.setGuestMode(true) }
                    },
                )
            }
        }
        return
    }

    val isGuest = user == null && preAuthSettings.isGuestMode
    val uid = user?.uid ?: "guest_local"
    key(uid) {
        val viewModel: SoundLapaViewModel = viewModel(
            key = uid,
            factory = viewModelFactory {
                initializer {
                    val application = checkNotNull(this[APPLICATION_KEY]) {
                        "APPLICATION_KEY missing from CreationExtras"
                    }
                    val cloudRepository = if (isGuest) {
                        null
                    } else {
                        val databaseId = application.getString(R.string.firestore_database_id)
                        val db = FirebaseFirestore.getInstance(databaseId)
                        CloudLibraryRepository(db, Firebase.auth)
                    }
                    SoundLapaViewModel(
                        application = application,
                        cloudRepository = cloudRepository,
                        cloudUserId = if (isGuest) null else uid,
                        cloudDisplayName = user?.displayName,
                        cloudEmail = user?.email,
                        cloudPhotoUrl = user?.photoUrl?.toString(),
                        isGuestMode = isGuest,
                    )
                }
            },
        )
        SoundLapaAuthenticatedShell(viewModel = viewModel)
    }
}

@Composable
private fun SoundLapaAuthenticatedShell(
    viewModel: SoundLapaViewModel,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val homeState by viewModel.homeUiState.collectAsStateWithLifecycle()
    val socialState by viewModel.socialState.collectAsStateWithLifecycle()
    val lyricsState by viewModel.lyricsState.collectAsStateWithLifecycle()
    val musicVideoState by viewModel.musicVideoState.collectAsStateWithLifecycle()
    val isPlayerExpanded by viewModel.isPlayerExpanded.collectAsStateWithLifecycle()
    val artistProfile by viewModel.artistProfileState.collectAsStateWithLifecycle()
    val queueTracks by viewModel.playbackController.queueTracks.collectAsStateWithLifecycle()
    val requestedLibrarySection by viewModel.requestedLibrarySection.collectAsStateWithLifecycle()
    val showReconnectedBanner by viewModel.showReconnectedBanner.collectAsStateWithLifecycle()
    val statsBackupState by viewModel.statsBackupState.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var askedNotificationPermission by remember { mutableStateOf(false) }

    var showSettingsSheet by remember { mutableStateOf(false) }
    var showDeveloperScreen by remember { mutableStateOf(false) }
    var showAboutScreen by remember { mutableStateOf(false) }
    var showSocialScreen by remember { mutableStateOf(false) }
    var showChangelogHistory by remember { mutableStateOf(false) }
    var showImportPlaylistDialog by remember { mutableStateOf(false) }
    var isPlaylistDetailOpen by remember { mutableStateOf(false) }
    var pendingScrollToLyrics by remember { mutableStateOf(false) }
    var showBugReportDialog by remember { mutableStateOf(false) }
    var showUploadSheet by remember { mutableStateOf(false) }
    var showQueueSheet by remember { mutableStateOf(false) }
    var optionsMenuTarget by remember { mutableStateOf<TrackOptionsMenuTarget?>(null) }
    var pendingRemovePlaylistTrack by remember { mutableStateOf<Pair<String, TrackEntity>?>(null) }
    var editingTrack by remember { mutableStateOf<TrackEntity?>(null) }
    var editingPlaylist by remember { mutableStateOf<PlaylistEntity?>(null) }
    var playlistPickerTrack by remember { mutableStateOf<TrackEntity?>(null) }
    var pendingDeleteTrack by remember { mutableStateOf<TrackEntity?>(null) }
    var isPlayerDragging by remember { mutableStateOf(false) }

    var isStartupReady by rememberSaveable { mutableStateOf(false) }
    var loadedCoversCount by remember { mutableIntStateOf(0) }
    var totalCoversCount by remember { mutableIntStateOf(0) }
    val latestUiState by rememberUpdatedState(state)
    val latestHomeState by rememberUpdatedState(homeState)

    LaunchedEffect(state.currentTrack?.id) {
        viewModel.clearMusicVideoForTrackChange(state.currentTrack?.id)
        state.currentTrack?.let { currentTrack ->
            viewModel.probeMusicVideoAvailability(currentTrack)
        }
    }

    LaunchedEffect(pendingScrollToLyrics) {
        if (pendingScrollToLyrics) {
            delay(500L)
            pendingScrollToLyrics = false
        }
    }

    LaunchedEffect(isStartupReady) {
        if (isStartupReady) return@LaunchedEffect
        val startMs = System.currentTimeMillis()
        withTimeoutOrNull(6000L) {
            viewModel.homeUiState.first {
                it.trendingCarousel.isNotEmpty() || it.quickPickRows.isNotEmpty() || it.filteredTracks.isNotEmpty()
            }
        }
        val snapshot = latestUiState
        val homeSnapshot = latestHomeState
        val coverUrls = buildList {
            homeSnapshot.trendingCarousel.forEach { t ->
                t.coverArtPath?.takeIf { it.isNotBlank() }?.let { add(it) }
            }
            homeSnapshot.quickPickRows.flatten().forEach { t ->
                t.coverArtPath?.takeIf { it.isNotBlank() }?.let { add(it) }
            }
            homeSnapshot.recommendedTracks.forEach { t ->
                t.coverArtPath?.takeIf { it.isNotBlank() }?.let { add(it) }
            }
            snapshot.tracks.forEach { t ->
                t.coverArtPath?.takeIf { it.isNotBlank() }?.let { add(it) }
            }
            snapshot.cloudSync.photoUrl?.takeIf { it.isNotBlank() }?.let { add(it) }
        }.distinct().take(16)

        totalCoversCount = coverUrls.size
        if (snapshot.isOnline && coverUrls.isNotEmpty()) {
            val imageLoader = context.imageLoader
            val counter = AtomicInteger(0)
            withTimeoutOrNull(6500L) {
                coroutineScope {
                    coverUrls.map { url ->
                        async {
                            runCatching {
                                imageLoader.execute(
                                    ImageRequest.Builder(context)
                                        .data(url)
                                        .size(360)
                                        .build(),
                                )
                            }
                            loadedCoversCount = counter.incrementAndGet()
                        }
                    }.awaitAll()
                }
            }
        } else {
            loadedCoversCount = coverUrls.size
        }
        val elapsed = System.currentTimeMillis() - startMs
        if (elapsed < 650L) {
            delay(650L - elapsed)
        }
        isStartupReady = true
    }

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

    val appStrings = remember(state.settings.appLanguage) {
        stringsForLanguage(state.settings.appLanguage)
    }

    SoundLapaTheme(
        darkTheme = state.settings.darkLiquidGlass,
        accentColor = Color(state.settings.accentColorArgb),
    ) {
        CompositionLocalProvider(
            LocalAppStrings provides appStrings,
            LocalExplicitTrackIds provides state.explicitTrackIds,
            LocalDataSaverEnabled provides state.settings.dataSaverEnabled,
            LocalBatterySaverEnabled provides state.settings.batterySaverEnabled,
            LocalHapticsConfig provides HapticConfig(
                enabled = state.settings.hapticsEnabled,
                intensityPercent = state.settings.hapticIntensity,
            ),
        ) {
            val haptics = rememberSoundLapaHaptics()

            fun shareTrackExternal(track: TrackEntity) {
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
            }

            fun navigateToTab(tab: SoundLapaTab) {
                haptics.tick()
                if (artistProfile.isVisible) {
                    viewModel.closeArtistProfile()
                }
                if (showSocialScreen) {
                    viewModel.clearInspectedPublicProfile()
                    showSocialScreen = false
                }
                if (showChangelogHistory) {
                    showChangelogHistory = false
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
                showDeveloperScreen = false
                showSettingsSheet = false
                showAboutScreen = false
                showSocialScreen = false
                showChangelogHistory = false
                viewModel.requestOpenDownloadedTracksSection()
                navigateToTab(SoundLapaTab.LIBRARY)
            }

            val playerTransition = remember { MutableTransitionState(false) }
            playerTransition.targetState = isPlayerExpanded && state.currentTrack != null
            val settingsTransition = remember { MutableTransitionState(false) }
            settingsTransition.targetState = showSettingsSheet
            val developerTransition = remember { MutableTransitionState(false) }
            developerTransition.targetState = showDeveloperScreen
            val aboutTransition = remember { MutableTransitionState(false) }
            aboutTransition.targetState = showAboutScreen
            val socialTransition = remember { MutableTransitionState(false) }
            socialTransition.targetState = showSocialScreen
            val changelogTransition = remember { MutableTransitionState(false) }
            changelogTransition.targetState = showChangelogHistory

            val isMainScreenCovered =
                (!isPlayerDragging && playerTransition.currentState && playerTransition.targetState && playerTransition.isIdle) ||
                    (settingsTransition.currentState && settingsTransition.targetState && settingsTransition.isIdle) ||
                    (developerTransition.currentState && developerTransition.targetState && developerTransition.isIdle) ||
                    (aboutTransition.currentState && aboutTransition.targetState && aboutTransition.isIdle) ||
                    (socialTransition.currentState && socialTransition.targetState && socialTransition.isIdle) ||
                    (changelogTransition.currentState && changelogTransition.targetState && changelogTransition.isIdle)

            val coverAccentTarget = state.currentTrack?.let { Color(it.accentColorHex) } ?: MaterialTheme.colorScheme.primary
            val animatedCoverAccent by androidx.compose.animation.animateColorAsState(
                targetValue = coverAccentTarget,
                animationSpec = tween(durationMillis = 650),
                label = "mainShellCoverTint",
            )
            val isDarkShell = MaterialTheme.colorScheme.background.luminance() < 0.5f

            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onBackground,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (!isPlaylistDetailOpen) {
                                Modifier.background(
                                    androidx.compose.ui.graphics.Brush.radialGradient(
                                        colors = listOf(
                                            animatedCoverAccent.copy(alpha = if (isDarkShell) 0.14f else 0.08f),
                                            Color.Transparent,
                                        ),
                                        center = androidx.compose.ui.geometry.Offset(540f, 1750f),
                                        radius = 1150f,
                                    ),
                                )
                            } else {
                                Modifier
                            },
                        ),
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
                val bottomOverlaySpace = if (useRail) 108.dp else 178.dp

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
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            val showTopBar = !artistProfile.isVisible &&
                                !isPlaylistDetailOpen &&
                                !showSocialScreen &&
                                !showChangelogHistory
                            AnimatedVisibility(
                                visible = showTopBar,
                                enter = fadeIn(animationSpec = tween(200)) + expandVertically(animationSpec = tween(220)),
                                exit = fadeOut(animationSpec = tween(180)) + shrinkVertically(animationSpec = tween(220)),
                            ) {
                                SystemUtilityTopBar(
                                    onBugReportClick = {
                                        haptics.tick()
                                        viewModel.clearBugReportStatus()
                                        showBugReportDialog = true
                                    },
                                    onAboutClick = {
                                        haptics.tick()
                                        showAboutScreen = true
                                    },
                                    onSettingsClick = {
                                        haptics.tick()
                                        viewModel.refreshCacheSize()
                                        showSettingsSheet = true
                                    },
                                    onFriendsClick = {
                                        haptics.tick()
                                        viewModel.clearInspectedPublicProfile()
                                        viewModel.loadFriends()
                                        showSocialScreen = true
                                    },
                                )
                            }
                            if (!showTopBar && !isPlaylistDetailOpen) {
                                Spacer(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .windowInsetsPadding(WindowInsets.statusBars),
                                )
                            }

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
                                        onRefreshHome = viewModel::refreshHomeFeed,
                                        onPlayTrack = { track ->
                                            ensureNotificationPermissionOnce()
                                            viewModel.playOrToggleTrack(
                                                track = track,
                                                contextTitle = appStrings.v34.playbackContextHome,
                                            )
                                        },
                                        onPlayRecommendedTrack = { track ->
                                            ensureNotificationPermissionOnce()
                                            viewModel.playOrToggleTrack(
                                                track = track,
                                                customQueue = homeState.recommendedTracks,
                                                contextTitle = appStrings.recommendedForYou,
                                            )
                                        },
                                        onToggleLike = viewModel::toggleLike,
                                        onOpenTrackOptions = { optionsMenuTarget = TrackOptionsMenuTarget(it) },
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
                                            viewModel.playOrToggleTrack(
                                                track = track,
                                                customQueue = results,
                                                contextTitle = appStrings.v34.playbackContextSearch,
                                            )
                                        },
                                        onToggleLike = viewModel::toggleLike,
                                        onOpenTrackOptions = { optionsMenuTarget = TrackOptionsMenuTarget(it) },
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
                                        onCreatePlaylist = { name, desc, coverUri ->
                                            viewModel.createPlaylist(name = name, description = desc, coverUri = coverUri)
                                        },
                                        onEditPlaylist = { playlist ->
                                            editingPlaylist = playlist
                                        },
                                        onDeletePlaylist = viewModel::deletePlaylist,
                                        onTogglePinPlaylist = viewModel::togglePlaylistPinned,
                                        onChangePlaylistSortMode = viewModel::updatePlaylistSortMode,
                                        onPlayPlaylist = { pl ->
                                            ensureNotificationPermissionOnce()
                                            viewModel.playPlaylist(pl)
                                        },
                                        onPlayPlaylistShuffle = { pl ->
                                            ensureNotificationPermissionOnce()
                                            viewModel.playPlaylist(pl, shuffled = true)
                                        },
                                        onPlayTrack = { track, queue, contextTitle ->
                                            ensureNotificationPermissionOnce()
                                            viewModel.playOrToggleTrack(
                                                track = track,
                                                customQueue = queue,
                                                contextTitle = contextTitle,
                                            )
                                        },
                                        onToggleLike = viewModel::toggleLike,
                                        onOpenTrackOptions = { optionsMenuTarget = TrackOptionsMenuTarget(it) },
                                        onOpenPlaylistTrackOptions = { track, playlistId, canMoveUp, canMoveDown ->
                                            optionsMenuTarget = TrackOptionsMenuTarget(
                                                track = track,
                                                playlistId = playlistId,
                                                canMoveUp = canMoveUp,
                                                canMoveDown = canMoveDown,
                                            )
                                        },
                                        onOpenUploadSheet = { showUploadSheet = true },
                                        onOpenImportPlaylistDialog = {
                                            viewModel.clearImportStatus()
                                            showImportPlaylistDialog = true
                                        },
                                        onOpenSubscribedArtist = { sub ->
                                            viewModel.openArtistProfile(
                                                artistName = sub.artistName,
                                                spotifyId = sub.spotifyArtistId,
                                                soundCloudUrl = sub.soundCloudUrl,
                                            )
                                        },
                                        onToggleUnsubscribeArtist = { sub ->
                                            viewModel.toggleArtistSubscription(
                                                artistName = sub.artistName,
                                                avatarUrl = sub.avatarUrl,
                                                soundCloudUrl = sub.soundCloudUrl,
                                                spotifyArtistId = sub.spotifyArtistId,
                                            )
                                        },
                                        onPlaylistDetailOpenChanged = { isPlaylistDetailOpen = it },
                                        statsBackupState = statsBackupState,
                                        onRestoreStatsFromUri = viewModel::restoreListeningStatsFromUri,
                                        onClearStatsRestoreStatus = viewModel::clearStatsRestoreStatus,
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
                            val isSubscribed = remember(state.subscribedArtists, artistProfile.artistName) {
                                state.subscribedArtists.any {
                                    it.artistName.equals(artistProfile.artistName, ignoreCase = true)
                                }
                            }
                            ArtistProfileScreen(
                                state = artistProfile,
                                currentTrackId = state.playback.currentTrackId,
                                isPlaying = state.playback.isPlaying,
                                bottomContentPadding = bottomOverlaySpace,
                                isSubscribed = isSubscribed,
                                onToggleSubscribe = {
                                    viewModel.toggleArtistSubscription(
                                        artistName = artistProfile.artistName,
                                        avatarUrl = artistProfile.avatarUrl,
                                    )
                                },
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
                                    viewModel.playOrToggleTrack(
                                        track = track,
                                        customQueue = queue,
                                        contextTitle = artistProfile.selectedAlbum?.title ?: artistProfile.artistName,
                                    )
                                },
                                onToggleLike = viewModel::toggleLike,
                                onOpenTrackOptions = { optionsMenuTarget = TrackOptionsMenuTarget(it) },
                                onOpenAlbum = viewModel::openArtistAlbum,
                                onCloseAlbum = viewModel::closeArtistAlbum,
                                onPlayAlbum = { albumTracks, startTrack ->
                                    ensureNotificationPermissionOnce()
                                    viewModel.playAlbumTracks(
                                        tracks = albumTracks,
                                        startTrack = startTrack,
                                        albumTitle = artistProfile.selectedAlbum?.title,
                                    )
                                },
                            )
                        }

                        Column(
                            modifier = Modifier.align(Alignment.BottomCenter),
                        ) {
                            AnimatedVisibility(
                                visible = !playerTransition.targetState && !isMainScreenCovered && state.currentTrack != null,
                                enter = fadeIn(tween(200)) + scaleIn(
                                    initialScale = 0.94f,
                                    transformOrigin = TransformOrigin(0.5f, 1f),
                                    animationSpec = tween(240, easing = SilkyEaseOut),
                                ),
                                exit = fadeOut(tween(150)) + scaleOut(
                                    targetScale = 1.04f,
                                    transformOrigin = TransformOrigin(0.5f, 1f),
                                    animationSpec = tween(180),
                                ),
                            ) {
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
                                    modifier = Modifier.padding(bottom = if (useRail) 14.dp else 4.dp),
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

            // Full-Screen Now Playing Sheet with smooth mini-player morph transition
            AnimatedVisibility(
                visibleState = playerTransition,
                enter = slideInVertically(
                    initialOffsetY = { (it * 0.28f).toInt() },
                    animationSpec = tween(320, easing = SilkyEaseOut),
                ) + scaleIn(
                    initialScale = 0.88f,
                    transformOrigin = TransformOrigin(0.5f, 0.92f),
                    animationSpec = tween(320, easing = SilkyEaseOut),
                ) + fadeIn(animationSpec = tween(200)),
                exit = slideOutVertically(
                    targetOffsetY = { (it * 0.34f).toInt() },
                    animationSpec = tween(260, easing = FastOutSlowInEasing),
                ) + scaleOut(
                    targetScale = 0.90f,
                    transformOrigin = TransformOrigin(0.5f, 0.92f),
                    animationSpec = tween(260, easing = FastOutSlowInEasing),
                ) + fadeOut(animationSpec = tween(170)),
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
                        lyricsState = lyricsState,
                        musicVideoState = musicVideoState,
                        initialScrollToLyrics = pendingScrollToLyrics,
                        onLoadLyrics = { viewModel.loadLyricsForTrack(track) },
                        onProbeMusicVideo = { viewModel.probeMusicVideoAvailability(track) },
                        onSeekToMs = { ms -> viewModel.playbackController.seekTo(ms) },
                        onToggleMusicVideoMode = { enable -> viewModel.toggleMusicVideoMode(track, enable) },
                        onCollapse = {
                            isPlayerDragging = false
                            pendingScrollToLyrics = false
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
                        onOpenTrackOptions = { optionsMenuTarget = TrackOptionsMenuTarget(track) },
                        onAddToPlaylist = { playlistPickerTrack = track },
                        onOpenQueue = { showQueueSheet = true },
                        onShareTrack = { shareTrackExternal(track) },
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
                val settingsScope = rememberCoroutineScope()
                val credentialManager = remember(context) { CredentialManager.create(context) }
                val appPrefs = (context.applicationContext as SoundLapaApplication).preferences
                var isSigningInGoogleFromGuest by remember { mutableStateOf(false) }
                var guestGoogleSignInError by remember { mutableStateOf<String?>(null) }
                SettingsScreen(
                    state = state,
                    onClose = {
                        viewModel.clearProfileStatusMessage()
                        viewModel.clearCacheMessage()
                        viewModel.clearImportStatus()
                        guestGoogleSignInError = null
                        isSigningInGoogleFromGuest = false
                        showSettingsSheet = false
                    },
                    onSelectLanguage = viewModel::setAppLanguage,
                    onSelectAccentColor = viewModel::setAccentColorArgb,
                    onToggleDarkTheme = viewModel::setDarkLiquidGlass,
                    onToggleSmoothTransition = viewModel::setSmoothTransitionEnabled,
                    onChangeSmoothTransitionDuration = viewModel::setSmoothTransitionDurationSec,
                    onToggleHaptics = viewModel::setHapticsEnabled,
                    onChangeHapticIntensity = viewModel::setHapticIntensity,
                    onToggleAutoUpload = viewModel::setAutoUploadEnabled,
                    onToggleDataSaver = viewModel::setDataSaverEnabled,
                    onToggleBatterySaver = viewModel::setBatterySaverEnabled,
                    onChangeAudioFocusMode = viewModel::setAudioFocusMode,
                    onClearCache = viewModel::clearAppCache,
                    onImportFromInput = viewModel::importExternalPlaylistFromInput,
                    onImportFromFile = viewModel::importExternalPlaylistFromFile,
                    onSetAutoUploadFolder = viewModel::setAutoUploadFolder,
                    onClearAutoUploadFolder = viewModel::clearAutoUploadFolder,
                    onSyncCloudNow = viewModel::syncCloudLibraryNow,
                    onSignOutGoogle = {
                        showDeveloperScreen = false
                        showSettingsSheet = false
                        showSocialScreen = false
                        settingsScope.launch {
                            appPrefs.setGuestMode(false)
                            appPrefs.setAvatarModerationBlocked(false)
                            appPrefs.setCustomProfile(null, null, null)
                        }
                        if (!state.cloudSync.isGuest) {
                            GoogleAuthManager.signOut(
                                credentialManager = credentialManager,
                                scope = settingsScope,
                            )
                        }
                    },
                    onSignInGoogleFromGuest = {
                        if (!isSigningInGoogleFromGuest) {
                            isSigningInGoogleFromGuest = true
                            guestGoogleSignInError = null
                            GoogleAuthManager.onGoogleSignInClicked(
                                context = context,
                                credentialManager = credentialManager,
                                onAuthSuccess = {
                                    settingsScope.launch {
                                        appPrefs.setGuestMode(false)
                                    }
                                    isSigningInGoogleFromGuest = false
                                    guestGoogleSignInError = null
                                    showDeveloperScreen = false
                                    showSettingsSheet = false
                                    showSocialScreen = false
                                },
                                onAuthError = { msg ->
                                    isSigningInGoogleFromGuest = false
                                    guestGoogleSignInError = msg
                                },
                                scope = settingsScope,
                                onAuthCancelled = {
                                    isSigningInGoogleFromGuest = false
                                },
                            )
                        }
                    },
                    isSigningInGoogleFromGuest = isSigningInGoogleFromGuest,
                    guestGoogleSignInError = guestGoogleSignInError,
                    onUpdateProfile = viewModel::updateAccountProfile,
                    onClearProfileStatus = viewModel::clearProfileStatusMessage,
                    onOpenFriendsScreen = {
                        viewModel.clearInspectedPublicProfile()
                        viewModel.loadFriends()
                        showSocialScreen = true
                    },
                    onOpenDeveloperScreen = {
                        if (state.developerMode.isUnlocked) {
                            viewModel.refreshDeveloperDashboard()
                        }
                        showDeveloperScreen = true
                    },
                    statsBackupState = statsBackupState,
                    onRestoreStatsFromUri = viewModel::restoreListeningStatsFromUri,
                    onClearStatsRestoreStatus = viewModel::clearStatsRestoreStatus,
                )
            }

            // Dedicated Full-Screen Developer Page (Fingerprint / Password + Cloud Storage Usage + Reports)
            AnimatedVisibility(
                visibleState = developerTransition,
                enter = slideInHorizontally(
                    initialOffsetX = { it },
                    animationSpec = tween(310, easing = SilkyEaseOut),
                ) + fadeIn(animationSpec = tween(200, easing = FastOutSlowInEasing)),
                exit = slideOutHorizontally(
                    targetOffsetX = { it },
                    animationSpec = tween(260, easing = SilkyEaseOut),
                ) + fadeOut(animationSpec = tween(180, easing = FastOutSlowInEasing)),
            ) {
                DeveloperScreen(
                    state = state,
                    onClose = { showDeveloperScreen = false },
                    onUnlockWithPassword = viewModel::unlockDeveloperSection,
                    onUnlockWithBiometrics = viewModel::unlockDeveloperWithBiometrics,
                    onLock = viewModel::lockDeveloperSection,
                    onRefreshDashboard = viewModel::refreshDeveloperDashboard,
                    onDeleteReport = viewModel::deleteDeveloperBugReport,
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
                    onOpenChangelogHistory = { showChangelogHistory = true },
                    onOpenCreatorProfile = {
                        viewModel.openCreatorProfile()
                        showSocialScreen = true
                    },
                )
            }

            // Social / Friends & Public Profiles Screen
            AnimatedVisibility(
                visibleState = socialTransition,
                enter = slideInHorizontally(
                    initialOffsetX = { it },
                    animationSpec = tween(310, easing = SilkyEaseOut),
                ) + fadeIn(animationSpec = tween(200, easing = FastOutSlowInEasing)),
                exit = slideOutHorizontally(
                    targetOffsetX = { it },
                    animationSpec = tween(260, easing = SilkyEaseOut),
                ) + fadeOut(animationSpec = tween(180, easing = FastOutSlowInEasing)),
            ) {
                UserProfileAndFriendsScreen(
                    isSignedIn = !state.cloudSync.isGuest,
                    currentUid = state.cloudSync.userId,
                    accountUid = state.cloudSync.accountUid,
                    currentProfile = socialState.currentPublicProfile,
                    inspectedProfile = socialState.inspectedProfile,
                    friends = socialState.friends,
                    searchResults = socialState.searchResults,
                    isSearching = socialState.isSearching,
                    isClaimingUsername = socialState.isClaimingUsername,
                    statusMessage = socialState.statusMessage,
                    listeningEvents = state.settings.listeningEvents,
                    fallbackHistory = state.settings.listeningHistory,
                    allTracks = state.tracks,
                    accent = MaterialTheme.colorScheme.primary,
                    onBack = {
                        viewModel.clearInspectedPublicProfile()
                        showSocialScreen = false
                    },
                    onClearInspectedProfile = viewModel::clearInspectedPublicProfile,
                    onClaimUsername = viewModel::claimUsername,
                    onSearchUsers = viewModel::searchSocialUsers,
                    onInspectProfile = viewModel::inspectPublicProfile,
                    onAddFriend = viewModel::addFriend,
                    onRemoveFriend = viewModel::removeFriend,
                    onPlayTrack = { track ->
                        ensureNotificationPermissionOnce()
                        viewModel.playOrToggleTrack(
                            track = track,
                            contextTitle = appStrings.stats.sectionTitle,
                        )
                    },
                    onSignInRequest = {
                        showSocialScreen = false
                        showSettingsSheet = true
                    },
                    statsBackupState = statsBackupState,
                    onRestoreStatsFromUri = viewModel::restoreListeningStatsFromUri,
                    onClearStatsRestoreStatus = viewModel::clearStatsRestoreStatus,
                )
            }

            // Full Version History / Changelog Screen
            AnimatedVisibility(
                visibleState = changelogTransition,
                enter = slideInHorizontally(
                    initialOffsetX = { it },
                    animationSpec = tween(310, easing = SilkyEaseOut),
                ) + fadeIn(animationSpec = tween(200, easing = FastOutSlowInEasing)),
                exit = slideOutHorizontally(
                    targetOffsetX = { it },
                    animationSpec = tween(260, easing = SilkyEaseOut),
                ) + fadeOut(animationSpec = tween(180, easing = FastOutSlowInEasing)),
            ) {
                ChangelogHistoryScreen(
                    accent = MaterialTheme.colorScheme.primary,
                    onBack = { showChangelogHistory = false },
                )
            }

            AnimatedVisibility(
                visible = !isStartupReady,
                enter = fadeIn(animationSpec = tween(120)),
                exit = fadeOut(animationSpec = tween(380, easing = FastOutSlowInEasing)),
            ) {
                StartupLoadingOverlay(
                    loadedCoversCount = loadedCoversCount,
                    totalCoversCount = totalCoversCount,
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
            }

        // Bottom Sheets & Dialogs
        optionsMenuTarget?.let { target ->
            val track = target.track
            TrackOptionsBottomSheet(
                track = track,
                onDismiss = { optionsMenuTarget = null },
                onEditClick = { editingTrack = track },
                onAddToPlaylistClick = { playlistPickerTrack = track },
                onDeleteClick = if (track.isUserUpload) {
                    { pendingDeleteTrack = track }
                } else null,
                onOpenArtistProfile = {
                    viewModel.setPlayerExpanded(false)
                    viewModel.openArtistProfile(track.artist, track)
                },
                onPlayNextClick = {
                    viewModel.playNextInQueue(track)
                    Toast.makeText(
                        context,
                        appStrings.playerV33.queuedNextNotice(track.title),
                        Toast.LENGTH_SHORT,
                    ).show()
                },
                onAddToQueueClick = {
                    viewModel.addToQueue(track)
                    Toast.makeText(
                        context,
                        appStrings.playerV33.addedToQueueNotice(track.title),
                        Toast.LENGTH_SHORT,
                    ).show()
                },
                onOpenAlbumClick = if (!track.albumTitle.isNullOrBlank()) {
                    {
                        viewModel.setPlayerExpanded(false)
                        viewModel.openArtistProfile(track.artist, track)
                    }
                } else null,
                onLyricsClick = {
                    ensureNotificationPermissionOnce()
                    if (state.currentTrack?.id != track.id) {
                        viewModel.playOrToggleTrack(track)
                    }
                    viewModel.loadLyricsForTrack(track)
                    pendingScrollToLyrics = true
                    viewModel.setPlayerExpanded(true)
                },
                onWatchClipClick = {
                    ensureNotificationPermissionOnce()
                    if (state.currentTrack?.id != track.id) {
                        viewModel.playOrToggleTrack(track)
                    }
                    viewModel.toggleMusicVideoMode(track, true)
                    viewModel.setPlayerExpanded(true)
                },
                onShareClick = { shareTrackExternal(track) },
                onMoveUpInPlaylistClick = if (target.playlistId != null && target.canMoveUp) {
                    { viewModel.movePlaylistTrack(target.playlistId, track.id, moveUp = true) }
                } else null,
                onMoveDownInPlaylistClick = if (target.playlistId != null && target.canMoveDown) {
                    { viewModel.movePlaylistTrack(target.playlistId, track.id, moveUp = false) }
                } else null,
                onRemoveFromPlaylistClick = if (target.playlistId != null) {
                    { pendingRemovePlaylistTrack = target.playlistId to track }
                } else null,
            )
        }

        pendingRemovePlaylistTrack?.let { (playlistId, trackToRemove) ->
            AlertDialog(
                onDismissRequest = { pendingRemovePlaylistTrack = null },
                containerColor = MaterialTheme.colorScheme.surface,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
                textContentColor = MaterialTheme.colorScheme.onSurface,
                title = { Text(appStrings.confirmDeleteTitle) },
                text = { Text(appStrings.confirmRemoveFromPlaylistMessage(trackToRemove.title)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            haptics.confirm()
                            viewModel.removeTrackFromPlaylist(playlistId, trackToRemove.id)
                            pendingRemovePlaylistTrack = null
                        },
                    ) {
                        Text(appStrings.deleteConfirmAction, color = LiquidRose)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingRemovePlaylistTrack = null }) {
                        Text(appStrings.cancel)
                    }
                },
            )
        }

        pendingDeleteTrack?.let { trackToDelete ->
            AlertDialog(
                onDismissRequest = { pendingDeleteTrack = null },
                containerColor = MaterialTheme.colorScheme.surface,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
                textContentColor = MaterialTheme.colorScheme.onSurface,
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
                onSave = { newTitle, newArtist, newAlbumTitle, newGenre, newCoverUri, clearCover ->
                    viewModel.updateTrackDetails(
                        track = track,
                        newTitle = newTitle,
                        newArtist = newArtist,
                        newAlbumTitle = newAlbumTitle,
                        newGenre = newGenre,
                        newCoverUri = newCoverUri,
                        clearCover = clearCover,
                    )
                    Toast.makeText(
                        context,
                        appStrings.playerV33.trackUpdatedNotice,
                        Toast.LENGTH_SHORT,
                    ).show()
                    editingTrack = null
                },
            )
        }

        editingPlaylist?.let { playlist ->
            val fallbackTracks = remember(state.playlists, playlist.id) {
                state.playlists.find { it.playlist.id == playlist.id }?.tracks.orEmpty()
            }
            EditPlaylistDialog(
                playlist = playlist,
                fallbackTracks = fallbackTracks,
                onDismiss = { editingPlaylist = null },
                onSave = { newName, newDescription, newCoverUri, clearCover ->
                    viewModel.updatePlaylistDetails(
                        playlist = playlist,
                        newName = newName,
                        newDescription = newDescription,
                        newCoverUri = newCoverUri,
                        clearCover = clearCover,
                    )
                    Toast.makeText(
                        context,
                        appStrings.playerV33.playlistUpdatedNotice,
                        Toast.LENGTH_SHORT,
                    ).show()
                    editingPlaylist = null
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
                onCreateNewPlaylist = { name, desc, coverUri ->
                    viewModel.createPlaylist(
                        name = name,
                        description = desc,
                        coverUri = coverUri,
                        initialTrack = track,
                    )
                },
            )
        }

        if (showImportPlaylistDialog) {
            ImportPlaylistModalDialog(
                isImporting = state.isImportingPlaylist,
                statusMessage = state.importStatusMessage,
                onImportFromInput = viewModel::importExternalPlaylistFromInput,
                onImportFromFile = viewModel::importExternalPlaylistFromFile,
                onDismiss = {
                    viewModel.clearImportStatus()
                    showImportPlaylistDialog = false
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
                    viewModel.playOrToggleTrack(
                        track = chosen,
                        customQueue = activeQueue,
                        contextTitle = state.playback.playbackContextTitle ?: appStrings.queueSheetTitle,
                    )
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
                containerColor = MaterialTheme.colorScheme.surface,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
                textContentColor = MaterialTheme.colorScheme.onSurface,
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

        if (showBugReportDialog) {
            BugReportDialog(
                bugReportState = state.bugReport,
                onSubmit = viewModel::submitBugReport,
                onDismiss = {
                    viewModel.clearBugReportStatus()
                    showBugReportDialog = false
                },
            )
        }
        }
    }
}
