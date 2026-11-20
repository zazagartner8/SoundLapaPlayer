package com.example.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.SoundLapaApplication
import com.example.audio.AutoSyncResult
import com.example.audio.ImportPreviewMetadata
import com.example.audio.RecommendationEngine
import com.example.data.local.ArtistSubscriptionEntity
import com.example.data.local.AudioFocusMode
import com.example.data.local.ExplicitContentDetector
import com.example.data.local.ListeningPlayEvent
import com.example.data.local.ListeningStatsBackupManager
import com.example.data.local.LocalBugReport
import com.example.data.local.PlaylistEntity
import com.example.data.local.PlaylistMetadataHelper
import com.example.data.local.PlaylistTrackCrossRef
import com.example.data.local.PlaylistWithTracks
import com.example.data.local.SoundLapUidFormatter
import com.example.data.local.SoundLapaSettings
import com.example.data.local.TrackEntity
import com.example.data.local.WaveFilterConfig
import com.example.data.remote.ArtistAlbumItem
import com.example.data.remote.AvatarModerationStatus
import com.example.data.remote.BugReportStatus
import com.example.data.remote.CloudArtistSubscription
import com.example.data.remote.CloudAvatarModerationRequest
import com.example.data.remote.CloudBanRecord
import com.example.data.remote.CloudBugReport
import com.example.data.remote.CloudFriend
import com.example.data.remote.CloudLibraryRepository
import com.example.data.remote.CloudPublicProfile
import com.example.data.remote.CloudSavedTrack
import com.example.data.remote.CloudStorageStats
import com.example.data.remote.CloudUserPlaylist
import com.example.data.remote.CloudUserProfile
import com.example.data.remote.DEFAULT_CLOUD_QUOTA_BYTES
import com.example.data.remote.DeveloperUserFullDetails
import com.example.data.remote.DeveloperUserOverview
import com.example.data.remote.FullStreamResolver
import com.example.data.remote.LocalDeviceCacheStats
import com.example.data.remote.LyricsLine
import com.example.data.remote.LyricsRepository
import com.example.data.remote.NetworkConnectivityObserver
import com.example.data.remote.ResolvedVideoStream
import com.example.data.remote.SearchArtistProfile
import com.example.data.remote.SpotifyCatalogClient
import com.example.data.remote.artistSubscriptionDocId
import com.example.data.remote.estimateBugReportBytes
import com.example.data.remote.estimateUserCloudStorageBytes
import com.example.data.remote.isTrackPlayableOffline
import com.example.data.remote.isValidUsernameHandle
import com.example.data.remote.normalizeUsernameHandle
import com.example.notifications.AdminNotificationHelper
import com.example.player.AudioPrefetchCache
import com.example.player.PlaybackUiState
import com.example.player.SoundLapaPlaybackService
import com.example.ui.auth.isAdminPanelAccount
import com.example.ui.auth.isVerifiedDeveloperAccount
import com.example.ui.auth.isVerifiedModeratorUid
import com.example.ui.auth.provisionDeveloperBiometricKey
import com.example.ui.auth.revokeDeveloperBiometricKey
import com.example.ui.components.bitmapToWebpDataUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

enum class SoundLapaTab {
    HOME,
    SEARCH,
    LIBRARY,
}

enum class PlaylistSortMode(val raw: String) {
    CUSTOM("CUSTOM"),
    TITLE_ASC("TITLE_ASC"),
    ADDED_DESC("ADDED_DESC"),
    DURATION_DESC("DURATION_DESC"),
    ARTIST_ASC("ARTIST_ASC"),
    ALBUM_ASC("ALBUM_ASC");

    companion object {
        fun fromRaw(raw: String?): PlaylistSortMode =
            entries.find { it.raw.equals(raw?.trim(), ignoreCase = true) } ?: CUSTOM
    }
}

@Immutable
data class StatsBackupUiState(
    val lastSavedAtMs: Long = 0L,
    val locationLabel: String = "Downloads/SoundLap/soundlap_stats_backup.json",
    val isRestoring: Boolean = false,
    val restoreStatusMessage: String? = null,
)

@Immutable
data class WaveUiState(
    val config: WaveFilterConfig = WaveFilterConfig(),
    val queueTracks: List<TrackEntity> = emptyList(),
    val currentWaveTrack: TrackEntity? = null,
    val isWaveActive: Boolean = false,
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
)

@Immutable
data class StagedUploadDraft(
    val preview: ImportPreviewMetadata,
    val title: String,
    val artist: String,
    val genre: String,
    val description: String,
    val paletteIndex: Int = 0,
)

@Immutable
data class UploadUiState(
    val stagedDraft: StagedUploadDraft? = null,
    val isImporting: Boolean = false,
    val isSyncingFolder: Boolean = false,
    val lastSyncResult: AutoSyncResult? = null,
    val statusMessage: String? = null,
)

@Immutable
data class AudiusUiState(
    val isRefreshingTrending: Boolean = false,
    val isSearching: Boolean = false,
    val isSyncingAccount: Boolean = false,
    val searchResults: List<TrackEntity> = emptyList(),
    val searchArtistProfiles: List<SearchArtistProfile> = emptyList(),
    val statusMessage: String? = null,
)

@Immutable
data class HomeUiState(
    val quickPickRows: List<List<TrackEntity>> = emptyList(),
    val trendingCarousel: List<TrackEntity> = emptyList(),
    val trendingAllTracks: List<TrackEntity> = emptyList(),
    val recommendedTracks: List<TrackEntity> = emptyList(),
    val recommendedAllTracks: List<TrackEntity> = emptyList(),
    val recommendationSeedArtists: List<String> = emptyList(),
    val isRefreshingRecommendations: Boolean = false,
    val filteredTracks: List<TrackEntity> = emptyList(),
    val selectedFilter: String = "Все",
    val isSearchActive: Boolean = false,
    val searchQuery: String = "",
    val isSearching: Boolean = false,
    val isRefreshingTrending: Boolean = false,
    val isRefreshingHome: Boolean = false,
    val currentTrackId: String? = null,
    val isPlaying: Boolean = false,
    val isOnline: Boolean = true,
    val offlineTracksCount: Int = 0,
)

@Immutable
data class CloudSyncUiState(
    val userId: String? = null,
    val accountUid: String? = null,
    val displayName: String? = null,
    val username: String? = null,
    val email: String? = null,
    val photoUrl: String? = null,
    val bio: String? = null,
    val avatarBlocked: Boolean = false,
    val banRecord: CloudBanRecord? = null,
    val isGuest: Boolean = false,
    val isVerifiedDeveloper: Boolean = false,
    val isVerifiedModerator: Boolean = false,
    val ownAvatarRequest: CloudAvatarModerationRequest? = null,
    val isSyncing: Boolean = false,
    val isSavingProfile: Boolean = false,
    val syncedTracksCount: Int = 0,
    val syncedPlaylistsCount: Int = 0,
    val statusMessage: String? = null,
    val profileStatusMessage: String? = null,
) {
    val canOpenAdminPanel: Boolean
        get() = isVerifiedDeveloper || isVerifiedModerator
}

@Immutable
data class LyricsUiState(
    val trackId: String? = null,
    val isLoading: Boolean = false,
    val syncedLines: List<LyricsLine> = emptyList(),
    val plainText: String? = null,
    val isSynced: Boolean = false,
    val source: String? = null,
    val notFound: Boolean = false,
)

@Immutable
data class MusicVideoUiState(
    val trackId: String? = null,
    val isVideoModeEnabled: Boolean = false,
    val useClipAudio: Boolean = false,
    val isLoading: Boolean = false,
    val isCheckingAvailability: Boolean = false,
    val stream: ResolvedVideoStream? = null,
    val unavailable: Boolean = false,
    val clipDurationMs: Long = 0L,
    val clipAudioSessionId: Int = 0,
) {
    companion object {
        fun activatedState(
            trackId: String,
            stream: ResolvedVideoStream,
            previousSessionId: Int,
        ): MusicVideoUiState {
            val autoClipAudio = stream.hasAudio
            return MusicVideoUiState(
                trackId = trackId,
                isVideoModeEnabled = true,
                useClipAudio = autoClipAudio,
                isLoading = false,
                isCheckingAvailability = false,
                stream = stream,
                unavailable = false,
                clipDurationMs = if (!stream.isLoopingCanvas) stream.durationMs.coerceAtLeast(0L) else 0L,
                clipAudioSessionId = if (autoClipAudio) previousSessionId + 1 else previousSessionId,
            )
        }
    }
}

@Immutable
data class SocialUiState(
    val currentPublicProfile: CloudPublicProfile? = null,
    val inspectedProfile: CloudPublicProfile? = null,
    val friends: List<CloudFriend> = emptyList(),
    val searchResults: List<CloudPublicProfile> = emptyList(),
    val isSearching: Boolean = false,
    val isClaimingUsername: Boolean = false,
    val isLoadingCreatorProfile: Boolean = false,
    val statusMessage: String? = null,
)

@Immutable
data class BugReportItemUi(
    val reportId: String,
    val docId: String = "",
    val userId: String = "",
    val reporterName: String,
    val reporterAvatarUrl: String? = null,
    val message: String,
    val appVersion: String,
    val status: BugReportStatus = BugReportStatus.OPEN,
    val createdAtMs: Long,
)

@Immutable
data class BugReportUiState(
    val isSubmitting: Boolean = false,
    val statusMessage: String? = null,
    val savedOfflineOnly: Boolean = false,
)

enum class AdminAccessLevel {
    LOCKED,
    MODERATOR,
    DEVELOPER,
}

@Immutable
data class DeveloperModeUiState(
    val accessLevel: AdminAccessLevel = AdminAccessLevel.LOCKED,
    val isLoadingReports: Boolean = false,
    val isLoadingStorageStats: Boolean = false,
    val isLoadingUsers: Boolean = false,
    val isLoadingUserDetails: Boolean = false,
    val isManagingUserAction: Boolean = false,
    val isLoadingAvatarRequests: Boolean = false,
    val isClearingLocalCache: Boolean = false,
    val deletingReportIds: Set<String> = emptySet(),
    val updatingReportIds: Set<String> = emptySet(),
    val processingAvatarRequestIds: Set<String> = emptySet(),
    val playingUserTrackUid: String? = null,
    val reports: List<BugReportItemUi> = emptyList(),
    val users: List<DeveloperUserOverview> = emptyList(),
    val avatarRequests: List<CloudAvatarModerationRequest> = emptyList(),
    val selectedUserDetails: DeveloperUserFullDetails? = null,
    val cloudStorageStats: CloudStorageStats = CloudStorageStats(),
    val localCacheStats: LocalDeviceCacheStats = LocalDeviceCacheStats(),
    val errorMessage: String? = null,
    val usersErrorMessage: String? = null,
    val userActionMessage: String? = null,
    val localCacheMessage: String? = null,
) {
    val isUnlocked: Boolean
        get() = accessLevel != AdminAccessLevel.LOCKED

    val isDeveloperUnlocked: Boolean
        get() = accessLevel == AdminAccessLevel.DEVELOPER

    val isModeratorUnlocked: Boolean
        get() = accessLevel == AdminAccessLevel.MODERATOR || accessLevel == AdminAccessLevel.DEVELOPER
}

@Immutable
data class MainUiState(
    val tracks: List<TrackEntity> = emptyList(),
    val filteredTracks: List<TrackEntity> = emptyList(),
    val searchScreenResults: List<TrackEntity> = emptyList(),
    val searchArtistProfiles: List<SearchArtistProfile> = emptyList(),
    val playlists: List<PlaylistWithTracks> = emptyList(),
    val subscribedArtists: List<ArtistSubscriptionEntity> = emptyList(),
    val selectedFilter: String = "Все",
    val searchQuery: String = "",
    val isSearchActive: Boolean = false,
    val playback: PlaybackUiState = PlaybackUiState(),
    val currentTrack: TrackEntity? = null,
    val settings: SoundLapaSettings = SoundLapaSettings(),
    val upload: UploadUiState = UploadUiState(),
    val audius: AudiusUiState = AudiusUiState(),
    val cloudSync: CloudSyncUiState = CloudSyncUiState(),
    val bugReport: BugReportUiState = BugReportUiState(),
    val developerMode: DeveloperModeUiState = DeveloperModeUiState(),
    val cacheSizeBytes: Long = 0L,
    val isClearingCache: Boolean = false,
    val cacheStatusMessage: String? = null,
    val isImportingPlaylist: Boolean = false,
    val importStatusMessage: String? = null,
    val isOnline: Boolean = true,
    val offlineTracksCount: Int = 0,
    val showOfflineDialog: Boolean = false,
    val offlineDialogTrackTitle: String? = null,
    val explicitTrackIds: Set<String> = emptySet(),
)

@Immutable
data class ArtistProfileUiState(
    val isVisible: Boolean = false,
    val artistName: String = "",
    val avatarUrl: String? = null,
    val accentColorHex: Long = 0xFF8B5CF6L,
    val secondaryColorHex: Long = 0xFF5CE1E6L,
    val isLoading: Boolean = false,
    val tracks: List<TrackEntity> = emptyList(),
    val albums: List<ArtistAlbumItem> = emptyList(),
    val selectedAlbum: ArtistAlbumItem? = null,
    val selectedAlbumTracks: List<TrackEntity> = emptyList(),
    val isLoadingAlbum: Boolean = false,
)

class SoundLapaViewModel(
    application: Application,
    private val cloudRepository: CloudLibraryRepository? = null,
    private val cloudUserId: String? = null,
    cloudDisplayName: String? = null,
    cloudEmail: String? = null,
    private val cloudPhotoUrl: String? = null,
    private val isGuestMode: Boolean = false,
) : AndroidViewModel(application) {

    private val app = application as SoundLapaApplication
    private val dao = app.dao
    private val preferences = app.preferences
    private val cacheManager = app.audioCacheManager
    private val audiusRepository = app.audiusRepository
    private val playlistImporter = app.playlistImporter
    private val lyricsRepository = LyricsRepository(dao)
    val playbackController = app.playbackController
    val playbackPositionMs: StateFlow<Long> = playbackController.positionMs

    private val _selectedFilter = MutableStateFlow("Все")
    private val _searchQuery = MutableStateFlow("")
    private val _isSearchActive = MutableStateFlow(false)
    private val _uploadState = MutableStateFlow(UploadUiState())
    private val _audiusState = MutableStateFlow(AudiusUiState())
    private val initialCloudState = CloudSyncUiState(
        userId = cloudUserId,
        accountUid = if (isGuestMode || cloudUserId.isNullOrBlank()) null else SoundLapUidFormatter.format(cloudUserId),
        displayName = if (isGuestMode) {
            null
        } else {
            preferences.currentSettings().customDisplayName ?: cloudDisplayName
        },
        username = if (isGuestMode) null else preferences.currentSettings().customUsername,
        email = if (isGuestMode) null else cloudEmail,
        photoUrl = if (isGuestMode || preferences.currentSettings().avatarModerationBlocked) {
            null
        } else {
            preferences.currentSettings().customAvatarData ?: cloudPhotoUrl
        },
        bio = if (isGuestMode) null else preferences.currentSettings().customBio,
        avatarBlocked = !isGuestMode && preferences.currentSettings().avatarModerationBlocked,
        isGuest = isGuestMode,
        isVerifiedDeveloper = !isGuestMode && (
            cloudRepository?.isCurrentUserVerifiedDeveloper() == true ||
                isVerifiedDeveloperAccount(cloudUserId, cloudEmail)
            ),
        isVerifiedModerator = !isGuestMode && (
            cloudRepository?.isCurrentUserVerifiedModerator() == true ||
                isVerifiedModeratorUid(cloudUserId)
            ),
    )
    private val _cloudSyncState = MutableStateFlow(initialCloudState)
    private val _socialState = MutableStateFlow(
        SocialUiState(
            currentPublicProfile = if (!isGuestMode && !cloudUserId.isNullOrBlank()) {
                CloudPublicProfile(
                    docId = cloudUserId,
                    userId = cloudUserId,
                    displayName = initialCloudState.displayName ?: "SoundLap User",
                    username = initialCloudState.username,
                    photoUrl = initialCloudState.photoUrl,
                    bio = initialCloudState.bio,
                    isDeveloper = initialCloudState.isVerifiedDeveloper,
                    isModerator = initialCloudState.isVerifiedModerator,
                    isPublic = true,
                )
            } else {
                null
            },
        ),
    )
    val socialState: StateFlow<SocialUiState> = _socialState.asStateFlow()

    private val _lyricsState = MutableStateFlow(LyricsUiState())
    val lyricsState: StateFlow<LyricsUiState> = _lyricsState.asStateFlow()

    private val _musicVideoState = MutableStateFlow(MusicVideoUiState())
    val musicVideoState: StateFlow<MusicVideoUiState> = _musicVideoState.asStateFlow()

    private val _bugReportState = MutableStateFlow(BugReportUiState())
    private val _developerModeState = MutableStateFlow(DeveloperModeUiState())
    private val _isRefreshingRecommendations = MutableStateFlow(false)
    private val _isRefreshingHome = MutableStateFlow(false)
    private val _cacheSizeBytes = MutableStateFlow(0L)
    private val _isClearingCache = MutableStateFlow(false)
    private val _cacheStatusMessage = MutableStateFlow<String?>(null)
    private val _isImportingPlaylist = MutableStateFlow(false)
    private val _importStatusMessage = MutableStateFlow<String?>(null)
    private val _isPlayerExpanded = MutableStateFlow(false)
    val isPlayerExpanded: StateFlow<Boolean> = _isPlayerExpanded.asStateFlow()

    private val _artistProfileState = MutableStateFlow(ArtistProfileUiState())
    val artistProfileState: StateFlow<ArtistProfileUiState> = _artistProfileState.asStateFlow()

    private val networkObserver = NetworkConnectivityObserver(application)
    private val _showOfflineDialog = MutableStateFlow(false)
    private val _offlineDialogTrackTitle = MutableStateFlow<String?>(null)
    private val _showReconnectedBanner = MutableStateFlow(false)
    val showReconnectedBanner: StateFlow<Boolean> = _showReconnectedBanner.asStateFlow()
    private val _requestedLibrarySection = MutableStateFlow<Int?>(null)
    val requestedLibrarySection: StateFlow<Int?> = _requestedLibrarySection.asStateFlow()
    private var shownAutoOfflinePrompt: Boolean = false
    private var wasPreviouslyOffline: Boolean = false

    private var lastAutoSeedKey: String? = null
    private var initialPrefetchDone: Boolean = false
    private var lastAutoSyncedTreeUri: String? = null

    private var searchJob: Job? = null
    private var artistProfileJob: Job? = null
    private var albumLoadJob: Job? = null
    private var reconnectedBannerJob: Job? = null
    private var settingsSyncJob: Job? = null
    private var metricsSyncJob: Job? = null
    private var activitySyncJob: Job? = null
    private var lyricsJob: Job? = null
    private var musicVideoJob: Job? = null
    private var musicVideoProbeJob: Job? = null
    private var waveLoadJob: Job? = null
    private var waveRefillJob: Job? = null
    private var devUsersJob: Job? = null
    private var devUserDetailsJob: Job? = null
    private var presenceSyncJob: Job? = null
    private var presenceHeartbeatJob: Job? = null
    private var isAppForeground: Boolean = true
    private var lastPublishedPresenceKey: String? = null

    private val _waveIsLoading = MutableStateFlow(false)
    private val _waveSessionActive = MutableStateFlow(false)
    private val _isLocalLibraryHydrated = MutableStateFlow(false)
    val isLocalLibraryHydrated: StateFlow<Boolean> = _isLocalLibraryHydrated.asStateFlow()
    private val _isInitialFeedSettled = MutableStateFlow(false)
    val isInitialFeedSettled: StateFlow<Boolean> = _isInitialFeedSettled.asStateFlow()
    private var statsBackupJob: Job? = null
    private var explicitPrewarmJob: Job? = null
    private val checkedLocalExplicitIds = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>(),
    )
    private val checkedUpfrontExplicitIds = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>(),
    )
    private val _statsBackupState = MutableStateFlow(
        StatsBackupUiState(
            lastSavedAtMs = ListeningStatsBackupManager.getLastBackupTimestampMs(app),
        ),
    )
    val statsBackupState: StateFlow<StatsBackupUiState> = _statsBackupState.asStateFlow()

    val availableFilters = listOf(
        "Все",
        "Хип-хоп",
        "Электроника",
        "Поп",
        "Рок",
        "Lo-Fi",
        "Синтвейв",
        "Амбиент",
        "Инди",
        "Мои треки",
    )

    val uploadPalettes = listOf(
        0xFF8B5CF6L to 0xFFA78BFAL,
        0xFF5CE1E6L to 0xFFA78BFAL,
        0xFF34D399L to 0xFF5CE1E6L,
        0xFFFF9248L to 0xFFFB7185L,
    )

    private data class TracksFilterSnapshot(
        val allTracks: List<TrackEntity>,
        val quickPickRows: List<List<TrackEntity>>,
        val trendingCarousel: List<TrackEntity>,
        val trendingAllTracks: List<TrackEntity>,
        val recommendedTracks: List<TrackEntity>,
        val recommendedAllTracks: List<TrackEntity>,
        val recommendationSeedArtists: List<String>,
        val filteredTracks: List<TrackEntity>,
        val searchScreenResults: List<TrackEntity>,
        val searchArtistProfiles: List<SearchArtistProfile>,
        val offlineTracksCount: Int,
        val selectedFilter: String,
    )

    private data class UtilityStatusSnapshot(
        val cacheSizeBytes: Long,
        val isClearingCache: Boolean,
        val cacheStatusMessage: String?,
        val isImportingPlaylist: Boolean,
        val importStatusMessage: String?,
        val isOnline: Boolean,
        val showOfflineDialog: Boolean,
        val offlineDialogTrackTitle: String?,
        val cloudSync: CloudSyncUiState,
        val bugReport: BugReportUiState,
        val developerMode: DeveloperModeUiState,
    )

    private val offlineUiFlow = combine(
        networkObserver.isOnline,
        _showOfflineDialog,
        _offlineDialogTrackTitle,
    ) { online, showDialog, blockedTitle ->
        Triple(online, showDialog, blockedTitle)
    }

    private val utilityStatusFlow = combine(
        _cacheSizeBytes,
        _isClearingCache,
        _cacheStatusMessage,
        _isImportingPlaylist,
        _importStatusMessage,
        offlineUiFlow,
        _cloudSyncState,
        _bugReportState,
        _developerModeState,
    ) { values ->
        val cacheBytes = values[0] as Long
        val clearing = values[1] as Boolean
        val cacheMsg = values[2] as String?
        val importing = values[3] as Boolean
        val importMsg = values[4] as String?
        @Suppress("UNCHECKED_CAST")
        val offline = values[5] as Triple<Boolean, Boolean, String?>
        val cloudSync = values[6] as CloudSyncUiState
        val bugReport = values[7] as BugReportUiState
        val developerMode = values[8] as DeveloperModeUiState
        UtilityStatusSnapshot(
            cacheSizeBytes = cacheBytes,
            isClearingCache = clearing,
            cacheStatusMessage = cacheMsg,
            isImportingPlaylist = importing,
            importStatusMessage = importMsg,
            isOnline = offline.first,
            showOfflineDialog = offline.second,
            offlineDialogTrackTitle = offline.third,
            cloudSync = cloudSync,
            bugReport = bugReport,
            developerMode = developerMode,
        )
    }

    private data class RecommendationSnapshot(
        val quickPickRows: List<List<TrackEntity>>,
        val trendingCarousel: List<TrackEntity>,
        val trendingAllTracks: List<TrackEntity>,
        val recommendedTracks: List<TrackEntity>,
        val recommendedAllTracks: List<TrackEntity>,
        val recommendationSeedArtists: List<String>,
        val homeRecTracks: List<TrackEntity>,
    )

    private data class RecSettingsSignal(
        val listeningHistory: List<com.example.data.local.ListeningHistoryEntry>,
        val listeningEvents: List<ListeningPlayEvent>,
        val searchHistory: List<String>,
        val appLanguage: String,
    )

    private val recommendationsFlow = combine(
        dao.observeAllTracks().distinctUntilChanged(),
        preferences.settings
            .distinctUntilChangedBy { s ->
                listOf(
                    s.listeningHistory,
                    s.listeningEvents.map { Triple(it.dayEpoch, it.trackId, it.playCount) },
                    s.searchHistory,
                    s.appLanguage,
                )
            }
            .map { s ->
                RecSettingsSignal(
                    listeningHistory = s.listeningHistory,
                    listeningEvents = s.listeningEvents,
                    searchHistory = s.searchHistory,
                    appLanguage = s.appLanguage,
                )
            },
    ) { tracks, signal ->
        val (listeningHistory, listeningEvents, searchHistory, appLanguage) = signal
        val persistedById = tracks.associateBy { it.id }
        val recentTracks = listeningHistory
            .sortedByDescending { it.lastPlayedAtMs }
            .mapNotNull { entry -> persistedById[entry.trackId] }
            .distinctBy { it.id }
            .distinctBy {
                "${it.title.trim().lowercase().replace('ё', 'е')}|${it.artist.trim().lowercase().replace('ё', 'е')}"
            }
            .take(4)
        val trendingAll = RecommendationEngine.computeTrendingSimilarTracks(
            allTracks = tracks,
            listeningHistory = listeningHistory,
            searchHistory = searchHistory,
            limit = 30,
        ).distinctBy {
            "${it.title.trim().lowercase().replace('ё', 'е')}|${it.artist.trim().lowercase().replace('ё', 'е')}"
        }
        val trendingCarousel = trendingAll.take(6)
        val quickAndTrendIds = (recentTracks.map { it.id } + trendingCarousel.map { it.id }).toHashSet()
        val recResult = RecommendationEngine.computeRecommendations(
            allTracks = tracks,
            listeningHistory = listeningHistory,
            searchHistory = searchHistory,
            limit = 36,
            listeningEvents = listeningEvents,
            excludeIds = quickAndTrendIds,
            appLanguage = appLanguage,
        )
        val recommendedAll = recResult.tracks
            .distinctBy {
                "${it.title.trim().lowercase().replace('ё', 'е')}|${it.artist.trim().lowercase().replace('ё', 'е')}"
            }
        val recommendedTop = recommendedAll.take(12)
        val recAndShownIds = quickAndTrendIds + recommendedTop.map { it.id }
        val forYouTracks = RecommendationEngine.computeForYouTracks(
            allTracks = tracks,
            listeningHistory = listeningHistory,
            listeningEvents = listeningEvents,
            searchHistory = searchHistory,
            excludeIds = recAndShownIds,
            limit = tracks.size.coerceAtLeast(24),
            appLanguage = appLanguage,
        )
        RecommendationSnapshot(
            quickPickRows = recentTracks.chunked(2),
            trendingCarousel = trendingCarousel,
            trendingAllTracks = trendingAll,
            recommendedTracks = recommendedTop,
            recommendedAllTracks = recommendedAll,
            recommendationSeedArtists = recResult.topSeedArtists,
            homeRecTracks = forYouTracks,
        )
    }.flowOn(Dispatchers.Default)

    private val tracksAndFiltersFlow = combine(
        dao.observeAllTracks(),
        _selectedFilter,
        _searchQuery,
        _isSearchActive,
        _audiusState,
        recommendationsFlow,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val tracks = values[0] as List<TrackEntity>
        val filter = values[1] as String
        val query = values[2] as String
        val searchActive = values[3] as Boolean
        val audius = values[4] as AudiusUiState
        val recs = values[5] as RecommendationSnapshot

        val trimmedQuery = query.trim().lowercase()
        val homeQuery = if (searchActive) trimmedQuery else ""

        val localFiltered = tracks.filter { track ->
            val matchesFilter = when (filter) {
                "Все" -> true
                "Мои треки" -> track.isUserUpload
                else -> track.genre.equals(filter, ignoreCase = true)
            }
            val matchesQuery = homeQuery.isEmpty() ||
                track.title.lowercase().contains(homeQuery) ||
                track.artist.lowercase().contains(homeQuery) ||
                track.genre.lowercase().contains(homeQuery)
            matchesFilter && matchesQuery
        }

        val persistedById = tracks.associateBy { it.id }
        val resolvedSearchResults = audius.searchResults.map { persistedById[it.id] ?: it }

        val mergedHome = if (searchActive && homeQuery.length >= 2 && resolvedSearchResults.isNotEmpty()) {
            val localIds = localFiltered.mapTo(HashSet()) { it.id }
            localFiltered + resolvedSearchResults.filter { it.id !in localIds }
        } else {
            localFiltered
        }

        // Instant local + progressive remote matches for the dedicated Search tab
        val searchScreenMatches = if (trimmedQuery.isEmpty()) {
            emptyList()
        } else {
            val localMatches = tracks.filter { track ->
                track.title.lowercase().contains(trimmedQuery) ||
                    track.artist.lowercase().contains(trimmedQuery) ||
                    track.genre.lowercase().contains(trimmedQuery)
            }
            val localIds = localMatches.mapTo(HashSet()) { it.id }
            localMatches + resolvedSearchResults.filter { it.id !in localIds }
        }

        val artistMatches = if (trimmedQuery.length >= 2) {
            buildSearchArtistMatches(
                rawQuery = trimmedQuery,
                remoteProfiles = audius.searchArtistProfiles,
                allTracks = tracks + resolvedSearchResults,
            )
        } else {
            emptyList()
        }

        val offlineCount = tracks.count { it.isUserUpload && isTrackPlayableOffline(it) }

        val finalHomeTracks = if (!searchActive && filter == "Все") {
            recs.homeRecTracks
        } else if (!searchActive && filter != "Мои треки") {
            mergedHome.filterNot { !it.isUserUpload && !it.isLiked && RecommendationEngine.isBlockedRecommendationTrack(it) }
        } else {
            mergedHome
        }

        TracksFilterSnapshot(
            allTracks = tracks,
            quickPickRows = recs.quickPickRows,
            trendingCarousel = recs.trendingCarousel,
            trendingAllTracks = recs.trendingAllTracks,
            recommendedTracks = recs.recommendedTracks,
            recommendedAllTracks = recs.recommendedAllTracks,
            recommendationSeedArtists = recs.recommendationSeedArtists,
            filteredTracks = finalHomeTracks,
            searchScreenResults = searchScreenMatches,
            searchArtistProfiles = artistMatches,
            offlineTracksCount = offlineCount,
            selectedFilter = filter,
        )
    }.flowOn(Dispatchers.Default)

    val homeUiState: StateFlow<HomeUiState> = combine(
        tracksAndFiltersFlow,
        _isSearchActive,
        _searchQuery,
        _audiusState,
        playbackController.playbackState,
        _isRefreshingRecommendations,
        _isRefreshingHome,
        networkObserver.isOnline,
    ) { values ->
        val snapshot = values[0] as TracksFilterSnapshot
        val searchActive = values[1] as Boolean
        val query = values[2] as String
        val audius = values[3] as AudiusUiState
        val playback = values[4] as PlaybackUiState
        val refreshingRecs = values[5] as Boolean
        val refreshingHome = values[6] as Boolean
        val isOnline = values[7] as Boolean
        HomeUiState(
            quickPickRows = snapshot.quickPickRows,
            trendingCarousel = snapshot.trendingCarousel,
            trendingAllTracks = snapshot.trendingAllTracks,
            recommendedTracks = snapshot.recommendedTracks,
            recommendedAllTracks = snapshot.recommendedAllTracks,
            recommendationSeedArtists = snapshot.recommendationSeedArtists,
            isRefreshingRecommendations = refreshingRecs,
            filteredTracks = snapshot.filteredTracks,
            selectedFilter = snapshot.selectedFilter,
            isSearchActive = searchActive,
            searchQuery = query,
            isSearching = audius.isSearching,
            isRefreshingTrending = audius.isRefreshingTrending,
            isRefreshingHome = refreshingHome,
            currentTrackId = playback.currentTrackId,
            isPlaying = playback.isPlaying,
            isOnline = isOnline,
            offlineTracksCount = snapshot.offlineTracksCount,
        )
    }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = HomeUiState(),
        )

    private val orderedPlaylistsFlow = combine(
        dao.observeAllPlaylists(),
        dao.observeOrderedPlaylistRefs(),
        dao.observeAllTracks(),
    ) { playlists, refs, allTracks ->
        val tracksById = allTracks.associateBy { it.id }
        val refsByPlaylist = refs.groupBy { it.playlistId }
        playlists.map { playlist ->
            val rawRefs = refsByPlaylist[playlist.id].orEmpty()
            val sortedRefs = when (PlaylistSortMode.fromRaw(playlist.sortMode)) {
                PlaylistSortMode.CUSTOM ->
                    rawRefs.sortedWith(compareBy<PlaylistTrackCrossRef> { it.position }.thenBy { it.addedAt })
                PlaylistSortMode.TITLE_ASC ->
                    rawRefs.sortedWith(
                        compareBy<PlaylistTrackCrossRef> {
                            tracksById[it.trackId]?.title?.lowercase().orEmpty()
                        }.thenBy { it.position },
                    )
                PlaylistSortMode.ADDED_DESC ->
                    rawRefs.sortedWith(compareByDescending<PlaylistTrackCrossRef> { it.addedAt }.thenBy { it.position })
                PlaylistSortMode.DURATION_DESC ->
                    rawRefs.sortedWith(
                        compareByDescending<PlaylistTrackCrossRef> {
                            tracksById[it.trackId]?.durationMs ?: 0L
                        }.thenBy { it.position },
                    )
                PlaylistSortMode.ARTIST_ASC ->
                    rawRefs.sortedWith(
                        compareBy<PlaylistTrackCrossRef> {
                            tracksById[it.trackId]?.artist?.lowercase().orEmpty()
                        }.thenBy { it.position },
                    )
                PlaylistSortMode.ALBUM_ASC ->
                    rawRefs.sortedWith(
                        compareBy<PlaylistTrackCrossRef> {
                            (tracksById[it.trackId]?.albumTitle ?: "\uFFFF").lowercase()
                        }.thenBy { it.position },
                    )
            }
            val playlistTracks = sortedRefs.mapNotNull { ref -> tracksById[ref.trackId] }
            PlaylistWithTracks(playlist = playlist, tracks = playlistTracks)
        }
    }.flowOn(Dispatchers.Default)

    val uiState: StateFlow<MainUiState> = combine(
        tracksAndFiltersFlow,
        orderedPlaylistsFlow,
        playbackController.playbackState,
        preferences.settings,
        _uploadState,
        _audiusState,
        utilityStatusFlow,
        dao.observeArtistSubscriptions(),
    ) { values ->
        val snapshot = values[0] as TracksFilterSnapshot
        @Suppress("UNCHECKED_CAST")
        val playlists = values[1] as List<PlaylistWithTracks>
        val playback = values[2] as PlaybackUiState
        val settings = values[3] as SoundLapaSettings
        val upload = values[4] as UploadUiState
        val audius = values[5] as AudiusUiState
        val utility = values[6] as UtilityStatusSnapshot
        @Suppress("UNCHECKED_CAST")
        val subscribedArtists = values[7] as List<ArtistSubscriptionEntity>

        val tracks = snapshot.allTracks
        val currentTrack = tracks.find { it.id == playback.currentTrackId }
            ?: audius.searchResults.find { it.id == playback.currentTrackId }
            ?: playbackController.findTrackById(playback.currentTrackId)
            ?: tracks.find { it.id == settings.lastPlayedTrackId }
            ?: tracks.firstOrNull()

        MainUiState(
            tracks = tracks,
            filteredTracks = snapshot.filteredTracks,
            searchScreenResults = snapshot.searchScreenResults,
            searchArtistProfiles = snapshot.searchArtistProfiles,
            playlists = playlists,
            subscribedArtists = subscribedArtists,
            selectedFilter = snapshot.selectedFilter,
            searchQuery = _searchQuery.value,
            isSearchActive = _isSearchActive.value,
            playback = playback,
            currentTrack = currentTrack,
            settings = settings,
            upload = upload,
            audius = audius,
            cloudSync = utility.cloudSync,
            bugReport = utility.bugReport,
            developerMode = utility.developerMode,
            cacheSizeBytes = utility.cacheSizeBytes,
            isClearingCache = utility.isClearingCache,
            cacheStatusMessage = utility.cacheStatusMessage,
            isImportingPlaylist = utility.isImportingPlaylist,
            importStatusMessage = utility.importStatusMessage,
            isOnline = utility.isOnline,
            offlineTracksCount = snapshot.offlineTracksCount,
            showOfflineDialog = utility.showOfflineDialog,
            offlineDialogTrackTitle = utility.offlineDialogTrackTitle,
            explicitTrackIds = settings.explicitTrackIds,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = MainUiState(settings = preferences.currentSettings()),
    )

    val waveUiState: StateFlow<WaveUiState> = combine(
        dao.observeAllTracks(),
        preferences.settings
            .map { Triple(it.waveFilterConfig, it.listeningHistory, it.appLanguage) }
            .distinctUntilChanged(),
        playbackController.playbackState,
        playbackController.queueTracks,
        _waveIsLoading,
        _waveSessionActive,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val allTracks = values[0] as List<TrackEntity>
        @Suppress("UNCHECKED_CAST")
        val prefsTriple = values[1] as Triple<WaveFilterConfig, List<com.example.data.local.ListeningHistoryEntry>, String>
        val playback = values[2] as PlaybackUiState
        @Suppress("UNCHECKED_CAST")
        val activeQueue = values[3] as List<TrackEntity>
        val loading = values[4] as Boolean
        val waveSession = values[5] as Boolean

        val (savedConfig, history, lang) = prefsTriple
        val effectiveConfig = if (savedConfig.hasCustomized) {
            savedConfig
        } else {
            RecommendationEngine.deriveDefaultWaveConfig(
                allTracks = allTracks,
                listeningHistory = history,
                appLanguage = lang,
            )
        }

        val candidateQueue = RecommendationEngine.computeWaveTracks(
            allTracks = allTracks,
            listeningHistory = history,
            config = effectiveConfig,
            limit = 24,
        )
        val displayQueue = if (waveSession && activeQueue.isNotEmpty()) activeQueue else candidateQueue
        val currentTrack = if (waveSession && playback.currentTrackId != null) {
            displayQueue.find { it.id == playback.currentTrackId }
                ?: allTracks.find { it.id == playback.currentTrackId }
                ?: playbackController.findTrackById(playback.currentTrackId)
                ?: displayQueue.firstOrNull()
        } else {
            displayQueue.firstOrNull()
        }

        WaveUiState(
            config = effectiveConfig,
            queueTracks = displayQueue,
            currentWaveTrack = currentTrack,
            isWaveActive = waveSession,
            isPlaying = waveSession && playback.isPlaying,
            isLoading = loading,
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = WaveUiState(config = preferences.currentSettings().waveFilterConfig),
        )

    init {
        val bootSettings = preferences.currentSettings()
        FullStreamResolver.isDataSaverEnabled = bootSettings.dataSaverEnabled
        FullStreamResolver.isBatterySaverEnabled = bootSettings.batterySaverEnabled
        SoundLapaPlaybackService.setAudioFocusMode(bootSettings.audioFocusMode)
        networkObserver.start()
        playbackController.onOfflinePlaybackBlocked = { blockedTitle ->
            _offlineDialogTrackTitle.value = blockedTitle
            _showOfflineDialog.value = true
        }
        playbackController.connect()
        // Event-driven presence & now-playing updates strictly on track open or play/pause changes
        if (!isGuestMode && cloudRepository != null && !cloudUserId.isNullOrBlank()) {
            viewModelScope.launch {
                cloudRepository.observeOwnBanStatus(cloudUserId).collect { record ->
                    _cloudSyncState.update { it.copy(banRecord = record) }
                }
            }
            viewModelScope.launch {
                cloudRepository.observeOwnAvatarModerationRequest(cloudUserId).collect { req ->
                    _cloudSyncState.update { it.copy(ownAvatarRequest = req) }
                    if (req != null &&
                        req.parsedStatus() == AvatarModerationStatus.APPROVED &&
                        req.proposedPhotoUrl.isNotBlank() &&
                        _cloudSyncState.value.photoUrl != req.proposedPhotoUrl
                    ) {
                        val approvedUrl = req.proposedPhotoUrl
                        preferences.setCustomProfile(
                            displayName = _cloudSyncState.value.displayName,
                            bio = _cloudSyncState.value.bio,
                            avatarData = approvedUrl,
                        )
                        _cloudSyncState.update { it.copy(photoUrl = approvedUrl, avatarBlocked = false) }
                        _socialState.update { s ->
                            s.copy(
                                currentPublicProfile = s.currentPublicProfile?.copy(photoUrl = approvedUrl),
                            )
                        }
                    }
                }
            }
            if (initialCloudState.canOpenAdminPanel) {
                AdminNotificationHelper.scheduleBackgroundPolling(app)
                viewModelScope.launch {
                    var initializedAvatarWatcher = false
                    cloudRepository.observePendingAvatarModerationRequests().collect { list ->
                        _developerModeState.update { it.copy(avatarRequests = list) }
                        val pending = list.filter { it.parsedStatus() == AvatarModerationStatus.PENDING }
                        val lastNotified = preferences.currentSettings().lastNotifiedAvatarRequestMs
                        if (pending.isNotEmpty()) {
                            val newestTs = pending.maxOf { it.createdAtMs }
                            if (lastNotified == 0L && !initializedAvatarWatcher) {
                                preferences.setLastNotifiedAvatarRequestMs(newestTs)
                            } else {
                                val brandNew = pending.filter { it.createdAtMs > lastNotified }
                                if (brandNew.isNotEmpty()) {
                                    val head = brandNew.first()
                                    AdminNotificationHelper.notifyNewAvatarRequest(
                                        context = app,
                                        requesterName = head.displayName,
                                        accountUid = head.accountUid,
                                        pendingCount = brandNew.size,
                                    )
                                    preferences.setLastNotifiedAvatarRequestMs(newestTs)
                                }
                            }
                        }
                        initializedAvatarWatcher = true
                    }
                }
                if (initialCloudState.isVerifiedDeveloper) {
                    viewModelScope.launch {
                        var initializedReportWatcher = false
                        cloudRepository.observeDeveloperBugReports().collect { cloudReports ->
                            if (_developerModeState.value.isUnlocked) {
                                val merged = mergeBugReportsForUi(cloudReports)
                                _developerModeState.update { it.copy(reports = merged) }
                            }
                            val lastNotified = preferences.currentSettings().lastNotifiedBugReportMs
                            if (cloudReports.isNotEmpty()) {
                                val newestTs = cloudReports.maxOf { it.createdAtMs }
                                if (lastNotified == 0L && !initializedReportWatcher) {
                                    preferences.setLastNotifiedBugReportMs(newestTs)
                                } else {
                                    val brandNew = cloudReports.filter { it.createdAtMs > lastNotified }
                                    if (brandNew.isNotEmpty()) {
                                        val head = brandNew.first()
                                        AdminNotificationHelper.notifyNewBugReport(
                                            context = app,
                                            reporterName = head.reporterName,
                                            previewMessage = head.message,
                                            newCount = brandNew.size,
                                        )
                                        preferences.setLastNotifiedBugReportMs(newestTs)
                                    }
                                }
                            }
                            initializedReportWatcher = true
                        }
                    }
                }
            } else {
                AdminNotificationHelper.cancelBackgroundPolling(app)
            }
            viewModelScope.launch {
                var initialPresenceSkipped = false
                playbackController.playbackState
                    .map { it.currentTrackId to it.isPlaying }
                    .distinctUntilChanged()
                    .collect { (trackId, isPlaying) ->
                        val activeOnline = isAppForeground || isPlaying
                        if (!initialPresenceSkipped) {
                            initialPresenceSkipped = true
                            if (trackId != null || isPlaying) {
                                publishPresenceAndNowPlaying(isOnline = activeOnline)
                            }
                        } else {
                            publishPresenceAndNowPlaying(isOnline = activeOnline)
                        }
                    }
            }
            presenceHeartbeatJob = viewModelScope.launch {
                while (true) {
                    delay(60_000L)
                    val isPlayingNow = playbackController.playbackState.value.isPlaying
                    if (isAppForeground || isPlayingNow) {
                        publishPresenceAndNowPlaying(isOnline = true, force = true)
                    }
                }
            }
        } else {
            AdminNotificationHelper.cancelBackgroundPolling(app)
        }
        viewModelScope.launch {
            networkObserver.isOnline.collect { online ->
                if (online) {
                    shownAutoOfflinePrompt = false
                    _showOfflineDialog.value = false
                    _offlineDialogTrackTitle.value = null
                    if (wasPreviouslyOffline) {
                        wasPreviouslyOffline = false
                        _showReconnectedBanner.value = true
                        reconnectedBannerJob?.cancel()
                        reconnectedBannerJob = viewModelScope.launch {
                            delay(3600L)
                            _showReconnectedBanner.value = false
                        }
                        if (uiState.value.tracks.size < 8) {
                            refreshAudiusTrending()
                        }
                    }
                } else {
                    wasPreviouslyOffline = true
                    reconnectedBannerJob?.cancel()
                    _showReconnectedBanner.value = false
                    if (!shownAutoOfflinePrompt) {
                        shownAutoOfflinePrompt = true
                        _offlineDialogTrackTitle.value = null
                        _showOfflineDialog.value = true
                    }
                }
            }
        }
        viewModelScope.launch {
            dao.observeAllTracks()
                .distinctUntilChanged()
                .collect { tracks ->
                    playbackController.updateAvailableTracks(tracks)
                    if (!_isLocalLibraryHydrated.value) {
                        _isLocalLibraryHydrated.value = true
                    }
                    if (!_isInitialFeedSettled.value && tracks.size >= 4) {
                        _isInitialFeedSettled.value = true
                    }
                    val uncheckedTracks = tracks.filter { checkedLocalExplicitIds.add(it.id) }
                    if (uncheckedTracks.isNotEmpty()) {
                        val detectedExplicit = kotlinx.coroutines.withContext(Dispatchers.Default) {
                            uncheckedTracks.filter { ExplicitContentDetector.isExplicit(it, emptySet()) }.map { it.id }
                        }
                        if (detectedExplicit.isNotEmpty()) {
                            preferences.markTracksExplicit(detectedExplicit)
                        }
                    }
                    if (!preferences.currentSettings().dataSaverEnabled && networkObserver.refresh()) {
                        prewarmExplicitBadges(tracks.take(14))
                    }
                    if (!initialPrefetchDone && tracks.isNotEmpty() && !preferences.currentSettings().dataSaverEnabled) {
                        initialPrefetchDone = true
                        if (networkObserver.refresh()) {
                            val lastId = preferences.currentSettings().lastPlayedTrackId
                            val priorityTracks = buildList {
                                tracks.find { it.id == lastId }?.let { add(it) }
                                tracks.filterNot { it.id == lastId }.take(3).forEach { add(it) }
                            }
                            FullStreamResolver.prefetchTracks(priorityTracks, dao)
                            AudioPrefetchCache.prefetchUpcomingTracks(app, priorityTracks.take(3), dao)
                        }
                    }
                }
        }
        // Auto-save listening stats to Downloads/SoundLap/soundlap_stats_backup.json so stats survive uninstall
        viewModelScope.launch {
            var initializedStatsBackup = false
            preferences.settings
                .map { s ->
                    Triple(
                        s.listeningEvents.size,
                        s.listeningEvents.sumOf { it.listenedMs },
                        s.listeningHistory.size,
                    )
                }
                .distinctUntilChanged()
                .collect {
                    if (initializedStatsBackup || preferences.currentSettings().listeningEvents.isNotEmpty()) {
                        scheduleStatsAutoBackup()
                    }
                    initializedStatsBackup = true
                }
        }
        // Automatically sync search history & listening activity deltas to cloud
        if (!isGuestMode && cloudRepository != null && !cloudUserId.isNullOrBlank()) {
            viewModelScope.launch {
                var initializedActivity = false
                preferences.settings
                    .map { it.searchHistory to it.listeningEvents.size }
                    .distinctUntilChanged()
                    .collect {
                        if (initializedActivity) {
                            scheduleCloudActivitySync()
                        }
                        initializedActivity = true
                    }
            }
        }
        // Keep Settings "Synced: X tracks • Y playlists" in sync live with local library changes
        viewModelScope.launch {
            var initializedCounts = false
            combine(
                dao.observeAllTracks(),
                dao.observeAllPlaylists(),
                dao.observeOrderedPlaylistRefs(),
            ) { allTracks, playlists, refs ->
                val referencedIds = refs.mapTo(HashSet()) { it.trackId }
                val personalCount = allTracks.count { it.isLiked || it.isUserUpload || it.id in referencedIds }
                personalCount to playlists.size
            }
                .flowOn(Dispatchers.Default)
                .distinctUntilChanged()
                .collect { (personalCount, playlistsCount) ->
                    _cloudSyncState.update { state ->
                        if (state.syncedTracksCount == personalCount &&
                            state.syncedPlaylistsCount == playlistsCount
                        ) {
                            state
                        } else {
                            state.copy(
                                syncedTracksCount = personalCount,
                                syncedPlaylistsCount = playlistsCount,
                            )
                        }
                    }
                    if (initializedCounts && !isGuestMode && cloudRepository != null && !cloudUserId.isNullOrBlank()) {
                        scheduleCloudMetricsSync()
                    }
                    initializedCounts = true
                }
        }
        // Automatically enrich online recommendations when user plays new artists (unless Data Saver is on)
        viewModelScope.launch {
            preferences.settings
                .map { it.listeningHistory.take(3).joinToString("|") { e -> e.artist } to it.dataSaverEnabled }
                .distinctUntilChanged()
                .collect { (seedKey, dataSaver) ->
                    if (seedKey.isNotBlank() && !dataSaver && seedKey != lastAutoSeedKey) {
                        lastAutoSeedKey = seedKey
                        refreshRecommendations(forceOnline = false)
                    }
                }
        }
        // Auto-refill Wave queue when listening near the tail of active Wave stream
        viewModelScope.launch {
            combine(
                playbackController.playbackState,
                playbackController.queueTracks,
                _waveSessionActive,
            ) { playback, queue, isWave ->
                Triple(playback.currentTrackId, queue, isWave)
            }.distinctUntilChanged().collect { (currentId, queue, isWave) ->
                if (isWave && currentId != null && queue.isNotEmpty()) {
                    val idx = queue.indexOfFirst { it.id == currentId }
                    if (idx >= 0 && (queue.size - 1 - idx) <= 2) {
                        refillWaveQueueIfNeeded()
                    }
                }
            }
        }
        refreshCacheSize()
        if (!isGuestMode && cloudRepository != null && !cloudUserId.isNullOrBlank()) {
            viewModelScope.launch {
                val previousUid = preferences.currentSettings().lastCloudUserId
                val wasGuest = preferences.currentSettings().isGuestMode || previousUid.isNullOrBlank()
                val isAccountSwitch = !previousUid.isNullOrBlank() && previousUid != cloudUserId
                if (isAccountSwitch && !wasGuest) {
                    kotlinx.coroutines.withContext(Dispatchers.IO) {
                        dao.clearAllPlaylists()
                        dao.clearAllTrackLikes()
                    }
                    preferences.setAvatarModerationBlocked(false)
                    preferences.setCustomProfile(null, null, null)
                    preferences.setCustomUsername(null)
                }
                if (preferences.currentSettings().isGuestMode) {
                    preferences.setGuestMode(false)
                }
                preferences.setLastCloudUserId(cloudUserId)
                // Always sync profile + settings + guest data on first login or transition from guest
                if (!preferences.hasCompletedInitialCloudSync(cloudUserId) || isAccountSwitch || wasGuest) {
                    syncCloudLibraryNow()
                } else {
                    val profileResult = cloudRepository.getUserProfile()
                    profileResult.getOrNull()?.let { profile ->
                        applyLoadedCloudProfileAndSettings(profile)
                    }
                    syncArtistSubscriptionsWithCloud(cloudRepository)
                    loadFriends()
                    flushPendingBugReportsToCloud()
                    val (savedCount, playlistsCount) = kotlinx.coroutines.withContext(Dispatchers.IO) {
                        val allTracks = dao.getAllTracksOnce()
                        val playlists = dao.getAllPlaylistsOnce()
                        val refs = dao.getAllPlaylistRefsOnce()
                        val referencedIds = refs.mapTo(HashSet()) { it.trackId }
                        val count = allTracks.count { it.isLiked || it.isUserUpload || it.id in referencedIds }
                        count to playlists.size
                    }
                    _cloudSyncState.update {
                        it.copy(
                            userId = cloudUserId,
                            isVerifiedDeveloper = cloudRepository.isCurrentUserVerifiedDeveloper() ||
                                isVerifiedDeveloperAccount(cloudUserId, it.email),
                            isVerifiedModerator = cloudRepository.isCurrentUserVerifiedModerator() ||
                                isVerifiedModeratorUid(cloudUserId),
                            syncedTracksCount = savedCount,
                            syncedPlaylistsCount = playlistsCount,
                        )
                    }
                }
            }
        }
        viewModelScope.launch {
            val (existingCount, lastRefreshMs) = kotlinx.coroutines.withContext(Dispatchers.IO) {
                dao.getTrackCount() to preferences.currentSettings().lastTrendingRefreshMs
            }
            val twelveHoursMs = 12L * 3600_000L
            if (existingCount >= 6) {
                _isInitialFeedSettled.value = true
            }
            if (existingCount >= 18 && System.currentTimeMillis() - lastRefreshMs < twelveHoursMs) {
                _isInitialFeedSettled.value = true
                return@launch
            }
            if (!networkObserver.refresh()) {
                _isInitialFeedSettled.value = true
                return@launch
            }
            if (existingCount >= 6) {
                delay(1500L)
                refreshAudiusTrending()
            } else {
                try {
                    fetchTrendingTracksBlocking()
                } finally {
                    _isInitialFeedSettled.value = true
                }
            }
        }
    }

    fun selectFilter(filter: String) {
        _selectedFilter.value = filter
    }

    fun toggleSearch() {
        val next = !_isSearchActive.value
        _isSearchActive.value = next
        if (!next) {
            _searchQuery.value = ""
            searchJob?.cancel()
            _audiusState.update {
                it.copy(
                    isSearching = false,
                    searchResults = emptyList(),
                    searchArtistProfiles = emptyList(),
                )
            }
        }
    }

    fun updateSearchQuery(query: String, immediate: Boolean = false) {
        _searchQuery.value = query
        searchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.length < 2) {
            _audiusState.update {
                it.copy(
                    isSearching = false,
                    searchResults = emptyList(),
                    searchArtistProfiles = emptyList(),
                )
            }
            return
        }
        searchJob = viewModelScope.launch {
            if (!networkObserver.refresh()) {
                _audiusState.update {
                    it.copy(
                        isSearching = false,
                        searchResults = emptyList(),
                        searchArtistProfiles = emptyList(),
                    )
                }
                return@launch
            }
            _audiusState.update { it.copy(isSearching = true) }
            if (!immediate) {
                delay(150L)
            }
            val artistSearchJob = launch {
                val profiles = runCatching { audiusRepository.searchArtistProfiles(trimmed, limit = 5) }
                    .getOrDefault(emptyList())
                if (_searchQuery.value.trim() == trimmed) {
                    _audiusState.update { it.copy(searchArtistProfiles = profiles) }
                }
            }
            val result = audiusRepository.searchTracksProgressive(trimmed) { partial ->
                if (_searchQuery.value.trim() == trimmed) {
                    _audiusState.update { it.copy(searchResults = partial) }
                }
            }
            artistSearchJob.join()
            if (_searchQuery.value.trim() == trimmed) {
                _audiusState.update {
                    it.copy(
                        isSearching = false,
                        searchResults = result.getOrDefault(it.searchResults),
                    )
                }
            }
        }
    }

    private fun buildSearchArtistMatches(
        rawQuery: String,
        remoteProfiles: List<SearchArtistProfile>,
        allTracks: List<TrackEntity>,
    ): List<SearchArtistProfile> {
        val trimmed = rawQuery.trim()
        if (trimmed.length < 2) return emptyList()
        val normQuery = trimmed.lowercase().replace(SEARCH_NORM_REGEX, "")
        if (normQuery.isBlank()) return emptyList()

        val merged = LinkedHashMap<String, SearchArtistProfile>()
        remoteProfiles.forEach { profile ->
            val key = profile.name.lowercase().replace(SEARCH_NORM_REGEX, "")
            if (key.isNotBlank()) {
                merged.putIfAbsent(key, profile)
            }
        }

        // Supplement with local track artists that match the query
        allTracks.forEach { track ->
            val primaryArtist = track.artist
                .split(SEARCH_ARTIST_SPLIT_REGEX)
                .firstOrNull()
                ?.trim()
                .orEmpty()
            if (primaryArtist.isBlank()) return@forEach
            val key = primaryArtist.lowercase().replace(SEARCH_NORM_REGEX, "")
            if (key.isBlank()) return@forEach
            val matches = key == normQuery ||
                key.startsWith(normQuery) ||
                (normQuery.length >= 3 && key.contains(normQuery))
            if (matches) {
                val existing = merged[key]
                if (existing == null) {
                    merged[key] = SearchArtistProfile(
                        id = "local_art_$key",
                        name = primaryArtist,
                        avatarUrl = track.coverArtPath,
                        sourceLabel = "SoundLap",
                        followersCount = track.playCount,
                        seedTrack = track,
                    )
                } else if (existing.seedTrack == null) {
                    merged[key] = existing.copy(
                        avatarUrl = existing.avatarUrl ?: track.coverArtPath,
                        seedTrack = track,
                    )
                }
            }
        }

        return merged.values.sortedByDescending { profile ->
            val normName = profile.name.lowercase().replace(SEARCH_NORM_REGEX, "")
            val exactBonus = when {
                normName == normQuery -> 10_000_000L
                normName.startsWith(normQuery) -> 1_000_000L
                else -> 100_000L
            }
            exactBonus + profile.followersCount.coerceAtMost(999_999)
        }.take(4)
    }

    fun submitSearchQuery(query: String) {
        val trimmed = query.trim()
        if (trimmed.length < 2) return
        updateSearchQuery(trimmed, immediate = true)
        app.appScope.launch(NonCancellable) {
            preferences.addSearchHistoryQuery(trimmed)
        }
    }

    fun saveCurrentSearchToHistory() {
        val trimmed = _searchQuery.value.trim()
        if (trimmed.length < 2) return
        app.appScope.launch(NonCancellable) {
            preferences.addSearchHistoryQuery(trimmed)
        }
    }

    fun removeSearchHistoryItem(query: String) {
        app.appScope.launch(NonCancellable) {
            preferences.removeSearchHistoryQuery(query)
        }
    }

    fun clearSearchHistory() {
        app.appScope.launch(NonCancellable) {
            preferences.clearSearchHistory()
        }
    }

    private suspend fun fetchTrendingTracksBlocking() {
        if (_audiusState.value.isRefreshingTrending) return
        if (!networkObserver.refresh()) return
        _audiusState.update { it.copy(isRefreshingTrending = true) }
        try {
            val currentSettings = preferences.currentSettings()
            val seedArtists = currentSettings.listeningHistory
                .take(3)
                .map { it.artist }
                .filter { it.isNotBlank() }
                .distinct()
            audiusRepository.fetchTrendingTracks(
                seedArtists = seedArtists,
                appLanguage = currentSettings.appLanguage,
            )
            preferences.setLastTrendingRefreshMs(System.currentTimeMillis())
        } finally {
            _audiusState.update { it.copy(isRefreshingTrending = false) }
            refreshCacheSize()
        }
    }

    fun refreshAudiusTrending() {
        if (_audiusState.value.isRefreshingTrending) return
        if (!networkObserver.refresh()) return
        viewModelScope.launch {
            fetchTrendingTracksBlocking()
            _isInitialFeedSettled.value = true
        }
    }

    fun refreshHomeFeed() {
        if (_isRefreshingHome.value) return
        if (!networkObserver.refresh()) {
            _offlineDialogTrackTitle.value = null
            _showOfflineDialog.value = true
            return
        }
        viewModelScope.launch {
            _isRefreshingHome.value = true
            val startedAt = System.currentTimeMillis()
            try {
                val settings = preferences.currentSettings()
                val seedArtists = settings.listeningHistory
                    .take(3)
                    .map { it.artist }
                    .filter { it.isNotBlank() }
                    .distinct()
                runCatching {
                    audiusRepository.fetchTrendingTracks(
                        seedArtists = seedArtists,
                        appLanguage = settings.appLanguage,
                    )
                }
                runCatching {
                    val tracks = dao.getAllTracksOnce()
                    val rec = RecommendationEngine.computeRecommendations(
                        allTracks = tracks,
                        listeningHistory = settings.listeningHistory,
                        searchHistory = settings.searchHistory,
                        limit = 12,
                        appLanguage = settings.appLanguage,
                    )
                    if (rec.onlineSeedQueries.isNotEmpty() && !settings.dataSaverEnabled) {
                        audiusRepository.fetchSimilarTracksForQueries(
                            queries = rec.onlineSeedQueries,
                            perQueryLimit = 5,
                        )
                    }
                }
                preferences.setLastTrendingRefreshMs(System.currentTimeMillis())
                refreshCacheSize()
            } finally {
                val elapsed = System.currentTimeMillis() - startedAt
                if (elapsed < 380L) {
                    delay(380L - elapsed)
                }
                _isRefreshingHome.value = false
            }
        }
    }

    private suspend fun applyLoadedCloudProfileAndSettings(profile: CloudUserProfile) {
        val local = preferences.currentSettings()
        val resolvedName = profile.displayName.takeIf { it.isNotBlank() } ?: _cloudSyncState.value.displayName
        val resolvedUsername = profile.username
            ?.let(::normalizeUsernameHandle)
            ?.takeIf(::isValidUsernameHandle)
            ?: local.customUsername
        val isBlocked = profile.avatarBlocked || local.avatarModerationBlocked
        val resolvedPhoto = if (isBlocked) {
            null
        } else {
            profile.photoUrl?.takeIf { it.isNotBlank() } ?: _cloudSyncState.value.photoUrl
        }
        val resolvedBio = profile.bio
        if (isBlocked) {
            preferences.setAvatarModerationBlocked(true)
            if (!profile.avatarBlocked || !profile.photoUrl.isNullOrBlank()) {
                cloudRepository?.revokeBlockedAvatar(
                    email = profile.email ?: _cloudSyncState.value.email,
                    displayName = resolvedName,
                )
            }
        }
        preferences.setCustomProfile(
            displayName = resolvedName,
            bio = resolvedBio,
            avatarData = resolvedPhoto,
        )
        if (resolvedUsername != local.customUsername) {
            preferences.setCustomUsername(resolvedUsername)
        }
        val resolvedEmail = profile.email ?: _cloudSyncState.value.email
        val resolvedAccountUid = profile.accountUid?.takeIf { it.isNotBlank() }
            ?: (cloudUserId ?: profile.userId).takeIf { it.isNotBlank() }?.let(SoundLapUidFormatter::format)
        val isVerifiedDev = cloudRepository?.isCurrentUserVerifiedDeveloper() == true ||
            isVerifiedDeveloperAccount(cloudUserId ?: profile.userId, resolvedEmail)
        val isVerifiedMod = cloudRepository?.isCurrentUserVerifiedModerator() == true ||
            isVerifiedModeratorUid(cloudUserId ?: profile.userId)
        _cloudSyncState.update {
            it.copy(
                accountUid = resolvedAccountUid ?: it.accountUid,
                displayName = resolvedName,
                username = resolvedUsername,
                photoUrl = resolvedPhoto,
                bio = resolvedBio,
                avatarBlocked = isBlocked,
                email = resolvedEmail,
                isVerifiedDeveloper = isVerifiedDev,
                isVerifiedModerator = isVerifiedMod,
            )
        }
        preferences.mergeCloudSearchAndListening(
            cloudSearchHistory = profile.searchHistory.orEmpty(),
            cloudEvents = profile.parsedListeningEvents(),
        )
        val uid = cloudUserId ?: profile.userId
        if (!isGuestMode && uid.isNotBlank()) {
            _socialState.update { current ->
                current.copy(
                    currentPublicProfile = CloudPublicProfile(
                        docId = uid,
                        userId = uid,
                        displayName = resolvedName ?: "SoundLap User",
                        username = resolvedUsername,
                        photoUrl = resolvedPhoto,
                        bio = resolvedBio,
                        isDeveloper = isVerifiedDev,
                        isModerator = isVerifiedMod,
                        isPublic = true,
                    ),
                )
            }
        }
        val cloudSettings = profile.parsedSettings()
        if (cloudSettings != null && (local.settingsUpdatedAtMs == 0L || cloudSettings.updatedAtMs >= local.settingsUpdatedAtMs)) {
            FullStreamResolver.isDataSaverEnabled = cloudSettings.dataSaverEnabled
            FullStreamResolver.isBatterySaverEnabled = cloudSettings.batterySaverEnabled
            SoundLapaPlaybackService.setAudioFocusMode(AudioFocusMode.fromRaw(cloudSettings.audioFocusMode))
            preferences.applySyncedAccountSettings(
                appLanguage = cloudSettings.appLanguage,
                darkLiquidGlass = cloudSettings.darkLiquidGlass,
                accentColorArgb = cloudSettings.accentColorArgb,
                smoothTransitionEnabled = cloudSettings.smoothTransitionEnabled,
                smoothTransitionDurationSec = cloudSettings.smoothTransitionDurationSec,
                hapticsEnabled = cloudSettings.hapticsEnabled,
                hapticIntensity = cloudSettings.hapticIntensity,
                autoCacheOnPlay = cloudSettings.autoCacheOnPlay,
                dataSaverEnabled = cloudSettings.dataSaverEnabled,
                autoUploadEnabled = cloudSettings.autoUploadEnabled,
                updatedAtMs = cloudSettings.updatedAtMs,
                batterySaverEnabled = cloudSettings.batterySaverEnabled,
                audioFocusMode = cloudSettings.audioFocusMode,
            )
        }
    }

    private suspend fun flushPendingBugReportsToCloud() {
        val repo = cloudRepository ?: return
        val uid = cloudUserId?.takeIf { it.isNotBlank() } ?: return
        val unsynced = preferences.currentSettings().pendingBugReports.filter { !it.isSyncedToCloud }
        for (item in unsynced) {
            val res = repo.submitBugReport(
                CloudBugReport(
                    reportId = item.reportId,
                    userId = uid,
                    reporterName = item.reporterName,
                    reporterAvatarUrl = item.reporterAvatarUrl,
                    message = item.message,
                    appVersion = item.appVersion,
                    createdAtMs = item.createdAtMs,
                ),
            )
            if (res.isSuccess) {
                preferences.markBugReportSynced(item.reportId)
            }
        }
    }

    private suspend fun syncArtistSubscriptionsWithCloud(repo: CloudLibraryRepository) {
        val cloudSubs = repo.getArtistSubscriptions().getOrNull() ?: return
        val localSubs = kotlinx.coroutines.withContext(Dispatchers.IO) {
            dao.getArtistSubscriptionsOnce()
        }
        val localByKey = localSubs.associateBy { it.artistKey }
        val cloudByKey = cloudSubs.associateBy { it.artistKey }

        for (cloudSub in cloudSubs) {
            if (cloudSub.artistKey !in localByKey) {
                dao.upsertArtistSubscription(
                    ArtistSubscriptionEntity(
                        artistKey = cloudSub.artistKey,
                        artistName = cloudSub.artistName,
                        avatarUrl = cloudSub.avatarUrl,
                        soundCloudUrl = cloudSub.soundCloudUrl,
                        spotifyArtistId = cloudSub.spotifyArtistId,
                        subscribedAtMs = cloudSub.subscribedAtMs,
                    ),
                )
            }
        }
        for (localSub in localSubs) {
            if (localSub.artistKey !in cloudByKey) {
                repo.upsertArtistSubscription(
                    artistName = localSub.artistName,
                    avatarUrl = localSub.avatarUrl,
                    soundCloudUrl = localSub.soundCloudUrl,
                    spotifyArtistId = localSub.spotifyArtistId,
                    subscribedAtMs = localSub.subscribedAtMs,
                )
            }
        }
    }

    fun syncCloudLibraryNow() {
        val repo = cloudRepository ?: return
        val uid = cloudUserId?.takeIf { it.isNotBlank() } ?: return
        if (_cloudSyncState.value.isSyncing) return
        viewModelScope.launch {
            _cloudSyncState.update { it.copy(isSyncing = true, statusMessage = null) }
            val currentState = _cloudSyncState.value
            val localSettings = preferences.currentSettings()
            val profileResult = repo.upsertUserProfile(
                displayName = currentState.displayName,
                email = currentState.email,
                photoUrl = currentState.photoUrl ?: cloudPhotoUrl,
                localSettings = localSettings,
            )
            profileResult.getOrNull()?.let { loadedProfile ->
                applyLoadedCloudProfileAndSettings(loadedProfile)
            }
            val result = repo.syncWithLocalDatabase(dao)
            syncArtistSubscriptionsWithCloud(repo)
            loadFriends()
            flushPendingBugReportsToCloud()
            if (result.isSuccess) {
                preferences.markInitialCloudSyncCompleted(uid)
            }
            _cloudSyncState.update { state ->
                result.fold(
                    onSuccess = { summary ->
                        state.copy(
                            userId = uid,
                            isSyncing = false,
                            syncedTracksCount = summary.totalCloudTracks,
                            syncedPlaylistsCount = summary.totalCloudPlaylists,
                            statusMessage = null,
                        )
                    },
                    onFailure = { err ->
                        state.copy(
                            userId = uid,
                            isSyncing = false,
                            statusMessage = err.localizedMessage,
                        )
                    },
                )
            }
        }
    }

    fun updateAccountProfile(
        displayName: String,
        bio: String,
        croppedAvatarBitmap: Bitmap?,
        clearAvatar: Boolean = false,
    ) {
        val repo = cloudRepository ?: return
        if (isGuestMode || cloudUserId.isNullOrBlank()) return
        val cleanName = displayName.trim().ifBlank { "SoundLap User" }.take(100)
        val cleanBio = bio.trim().take(300)
        viewModelScope.launch {
            val alreadyBlocked = _cloudSyncState.value.avatarBlocked ||
                preferences.currentSettings().avatarModerationBlocked
            if (croppedAvatarBitmap != null && alreadyBlocked) {
                runCatching { croppedAvatarBitmap.recycle() }
                preferences.setAvatarModerationBlocked(true)
                preferences.setCustomProfile(
                    displayName = _cloudSyncState.value.displayName,
                    bio = _cloudSyncState.value.bio,
                    avatarData = null,
                )
                _cloudSyncState.update {
                    it.copy(
                        photoUrl = null,
                        avatarBlocked = true,
                        isSavingProfile = false,
                        profileStatusMessage = "blocked_18plus",
                    )
                }
                return@launch
            }

            _cloudSyncState.update { it.copy(isSavingProfile = true, profileStatusMessage = "saving") }

            var submittedAvatarForManualReview = false
            if (croppedAvatarBitmap != null) {
                val encodedProposed = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    bitmapToWebpDataUrl(croppedAvatarBitmap, targetPx = 128, maxBytes = 16_000).also {
                        runCatching { croppedAvatarBitmap.recycle() }
                    }
                }
                val submitRes = repo.submitAvatarModerationRequest(
                    displayName = cleanName,
                    username = _cloudSyncState.value.username,
                    currentPhotoUrl = _cloudSyncState.value.photoUrl,
                    proposedPhotoUrl = encodedProposed,
                )
                if (submitRes.isFailure) {
                    _cloudSyncState.update {
                        it.copy(
                            isSavingProfile = false,
                            profileStatusMessage = submitRes.exceptionOrNull()?.localizedMessage ?: "moderation_error",
                        )
                    }
                    return@launch
                }
                submitRes.getOrNull()?.let { pendingReq ->
                    _cloudSyncState.update { it.copy(ownAvatarRequest = pendingReq) }
                }
                submittedAvatarForManualReview = true
            }

            // Keep existing avatar untouched while new avatar awaits manual moderator approval
            val effectiveClear = clearAvatar || alreadyBlocked
            val preservedPhoto = if (effectiveClear) null else _cloudSyncState.value.photoUrl
            val currentUsername = _cloudSyncState.value.username
            val res = repo.updateUserCustomProfile(
                displayName = cleanName,
                bio = cleanBio,
                photoUrl = preservedPhoto,
                email = _cloudSyncState.value.email,
                clearAvatar = effectiveClear,
                username = currentUsername,
            )
            res.onSuccess { updated ->
                val effectiveBlocked = updated.avatarBlocked || alreadyBlocked
                if (effectiveBlocked) {
                    preferences.setAvatarModerationBlocked(true)
                }
                val finalPhoto = if (effectiveBlocked) null else updated.photoUrl
                preferences.setCustomProfile(
                    displayName = updated.displayName,
                    bio = updated.bio,
                    avatarData = finalPhoto,
                )
                val resolvedHandle = updated.username ?: currentUsername
                _cloudSyncState.update {
                    it.copy(
                        displayName = updated.displayName,
                        username = resolvedHandle,
                        bio = updated.bio,
                        photoUrl = finalPhoto,
                        avatarBlocked = effectiveBlocked,
                        isSavingProfile = false,
                        profileStatusMessage = when {
                            effectiveBlocked -> "blocked_18plus"
                            submittedAvatarForManualReview -> "avatar_pending_review"
                            else -> "✓"
                        },
                    )
                }
                val uid = cloudUserId
                if (!uid.isNullOrBlank()) {
                    _socialState.update { current ->
                        current.copy(
                            currentPublicProfile = CloudPublicProfile(
                                docId = uid,
                                userId = uid,
                                displayName = updated.displayName,
                                username = resolvedHandle,
                                photoUrl = finalPhoto,
                                bio = updated.bio,
                                isDeveloper = _cloudSyncState.value.isVerifiedDeveloper,
                                isModerator = _cloudSyncState.value.isVerifiedModerator,
                                isPublic = true,
                            ),
                        )
                    }
                }
            }.onFailure { err ->
                _cloudSyncState.update {
                    it.copy(
                        isSavingProfile = false,
                        profileStatusMessage = err.localizedMessage ?: "Error",
                    )
                }
            }
        }
    }

    fun cancelOwnAvatarModerationRequest() {
        val repo = cloudRepository ?: return
        if (isGuestMode || cloudUserId.isNullOrBlank()) return
        viewModelScope.launch {
            repo.cancelOwnAvatarModerationRequest().onSuccess {
                _cloudSyncState.update { it.copy(ownAvatarRequest = null, profileStatusMessage = null) }
            }
        }
    }

    fun clearProfileStatusMessage() {
        _cloudSyncState.update { it.copy(profileStatusMessage = null) }
    }

    fun submitBugReport(message: String) {
        val clean = message.trim()
        if (clean.isBlank()) return
        viewModelScope.launch {
            _bugReportState.update { it.copy(isSubmitting = true, statusMessage = null, savedOfflineOnly = false) }
            val reportId = "rep_${UUID.randomUUID().toString().replace("-", "").take(20)}"
            val now = System.currentTimeMillis()
            val reporterName = if (isGuestMode) {
                "Гость"
            } else {
                _cloudSyncState.value.displayName?.takeIf { it.isNotBlank() }
                    ?: _cloudSyncState.value.email?.substringBefore('@')
                    ?: "SoundLap User"
            }
            val avatar = if (isGuestMode) null else _cloudSyncState.value.photoUrl
            val localReport = LocalBugReport(
                reportId = reportId,
                reporterName = reporterName,
                reporterAvatarUrl = avatar,
                message = clean.take(2000),
                appVersion = BuildConfig.VERSION_NAME,
                createdAtMs = now,
                isSyncedToCloud = false,
            )
            preferences.addLocalBugReport(localReport)

            var syncedOnline = false
            val repo = cloudRepository
            val uid = cloudUserId
            if (!isGuestMode && repo != null && !uid.isNullOrBlank()) {
                val res = repo.submitBugReport(
                    CloudBugReport(
                        reportId = reportId,
                        userId = uid,
                        reporterName = reporterName,
                        reporterAvatarUrl = avatar,
                        message = clean.take(2000),
                        appVersion = BuildConfig.VERSION_NAME,
                        createdAtMs = now,
                    ),
                )
                if (res.isSuccess) {
                    syncedOnline = true
                    preferences.markBugReportSynced(reportId)
                }
            }

            _bugReportState.update {
                it.copy(
                    isSubmitting = false,
                    statusMessage = "sent",
                    savedOfflineOnly = !syncedOnline,
                )
            }
            if (_developerModeState.value.isUnlocked) {
                refreshDeveloperReports()
            }
        }
    }

    fun clearBugReportStatus() {
        _bugReportState.update { it.copy(statusMessage = null, savedOfflineOnly = false) }
    }

    fun unlockDeveloperWithBiometrics() {
        val uid = cloudUserId
        val email = _cloudSyncState.value.email
        val isDev = !isGuestMode &&
            !uid.isNullOrBlank() &&
            (cloudRepository?.isCurrentUserVerifiedDeveloper() == true || isVerifiedDeveloperAccount(uid, email))
        if (isDev) {
            unlockDeveloperAreaWithBiometrics()
        } else {
            unlockModeratorAreaWithBiometrics()
        }
    }

    fun unlockDeveloperAreaWithBiometrics() {
        val uid = cloudUserId
        val email = _cloudSyncState.value.email
        val isDev = !isGuestMode &&
            !uid.isNullOrBlank() &&
            (cloudRepository?.isCurrentUserVerifiedDeveloper() == true || isVerifiedDeveloperAccount(uid, email))
        if (!isDev) {
            return
        }
        provisionDeveloperBiometricKey(uid!!, email)
        _developerModeState.update {
            it.copy(
                accessLevel = AdminAccessLevel.DEVELOPER,
                errorMessage = null,
                usersErrorMessage = null,
            )
        }
        refreshDeveloperDashboard()
    }

    fun unlockModeratorAreaWithBiometrics() {
        val uid = cloudUserId
        val email = _cloudSyncState.value.email
        val canModerate = !isGuestMode &&
            !uid.isNullOrBlank() &&
            (cloudRepository?.isCurrentUserAvatarReviewer() == true || isAdminPanelAccount(uid, email))
        if (!canModerate) {
            return
        }
        provisionDeveloperBiometricKey(uid!!, email)
        _developerModeState.update {
            it.copy(
                accessLevel = AdminAccessLevel.MODERATOR,
                selectedUserDetails = null,
                isLoadingUserDetails = false,
                errorMessage = null,
                usersErrorMessage = null,
            )
        }
        refreshAvatarModerationRequests()
    }

    fun lockDeveloperSection() {
        revokeDeveloperBiometricKey(cloudUserId)
        devUsersJob?.cancel()
        devUserDetailsJob?.cancel()
        viewModelScope.launch {
            preferences.setDevUnlockedUid(null)
        }
        _developerModeState.update {
            it.copy(
                accessLevel = AdminAccessLevel.LOCKED,
                selectedUserDetails = null,
                isLoadingUserDetails = false,
                errorMessage = null,
                usersErrorMessage = null,
            )
        }
    }

    fun refreshDeveloperDashboard() {
        refreshAvatarModerationRequests()
        if (_cloudSyncState.value.isVerifiedDeveloper && _developerModeState.value.isDeveloperUnlocked) {
            refreshDeveloperUsers()
            refreshDeveloperReports()
            refreshCloudStorageUsage()
            refreshLocalCacheStats()
        }
    }

    fun refreshAvatarModerationRequests() {
        val repo = cloudRepository ?: return
        if (!_developerModeState.value.isUnlocked) return
        if (isGuestMode || cloudUserId.isNullOrBlank()) return
        viewModelScope.launch {
            _developerModeState.update { it.copy(isLoadingAvatarRequests = true) }
            val result = repo.fetchAllAvatarModerationRequests()
            _developerModeState.update { state ->
                result.fold(
                    onSuccess = { list ->
                        state.copy(isLoadingAvatarRequests = false, avatarRequests = list)
                    },
                    onFailure = { err ->
                        state.copy(
                            isLoadingAvatarRequests = false,
                            userActionMessage = err.localizedMessage ?: state.userActionMessage,
                        )
                    },
                )
            }
        }
    }

    fun approveAvatarModerationRequest(request: CloudAvatarModerationRequest) {
        val repo = cloudRepository ?: return
        if (!_developerModeState.value.isUnlocked) return
        val targetUid = request.userId.ifBlank { request.docId }
        if (targetUid in _developerModeState.value.processingAvatarRequestIds) return
        viewModelScope.launch {
            _developerModeState.update {
                it.copy(
                    processingAvatarRequestIds = it.processingAvatarRequestIds + targetUid,
                    userActionMessage = null,
                )
            }
            val reviewerName = _cloudSyncState.value.displayName
                ?: _cloudSyncState.value.username?.let { "@$it" }
                ?: "Moderator"
            val result = repo.approveAvatarModerationRequest(request, reviewerName)
            val v35 = com.example.ui.i18n.stringsForLanguage(preferences.currentSettings().appLanguage).v35
            _developerModeState.update { state ->
                result.fold(
                    onSuccess = {
                        state.copy(
                            processingAvatarRequestIds = state.processingAvatarRequestIds - targetUid,
                            avatarRequests = state.avatarRequests.map { item ->
                                if ((item.userId.ifBlank { item.docId }) == targetUid) {
                                    item.copy(
                                        status = AvatarModerationStatus.APPROVED.wireValue,
                                        rejectReason = null,
                                        reviewedByUid = cloudUserId,
                                        reviewedByName = reviewerName,
                                        reviewedAtMs = System.currentTimeMillis(),
                                    )
                                } else {
                                    item
                                }
                            },
                            userActionMessage = v35.avatarApprovedSuccessNotice,
                        )
                    },
                    onFailure = { err ->
                        state.copy(
                            processingAvatarRequestIds = state.processingAvatarRequestIds - targetUid,
                            userActionMessage = err.localizedMessage ?: "Error",
                        )
                    },
                )
            }
        }
    }

    fun rejectAvatarModerationRequest(request: CloudAvatarModerationRequest, reason: String?) {
        val repo = cloudRepository ?: return
        if (!_developerModeState.value.isUnlocked) return
        val targetUid = request.userId.ifBlank { request.docId }
        if (targetUid in _developerModeState.value.processingAvatarRequestIds) return
        viewModelScope.launch {
            _developerModeState.update {
                it.copy(
                    processingAvatarRequestIds = it.processingAvatarRequestIds + targetUid,
                    userActionMessage = null,
                )
            }
            val reviewerName = _cloudSyncState.value.displayName
                ?: _cloudSyncState.value.username?.let { "@$it" }
                ?: "Moderator"
            val result = repo.rejectAvatarModerationRequest(request, reason, reviewerName)
            val v35 = com.example.ui.i18n.stringsForLanguage(preferences.currentSettings().appLanguage).v35
            _developerModeState.update { state ->
                result.fold(
                    onSuccess = {
                        state.copy(
                            processingAvatarRequestIds = state.processingAvatarRequestIds - targetUid,
                            avatarRequests = state.avatarRequests.map { item ->
                                if ((item.userId.ifBlank { item.docId }) == targetUid) {
                                    item.copy(
                                        status = AvatarModerationStatus.REJECTED.wireValue,
                                        rejectReason = reason?.trim()?.takeIf { it.isNotEmpty() },
                                        reviewedByUid = cloudUserId,
                                        reviewedByName = reviewerName,
                                        reviewedAtMs = System.currentTimeMillis(),
                                    )
                                } else {
                                    item
                                }
                            },
                            userActionMessage = v35.avatarRejectedSuccessNotice,
                        )
                    },
                    onFailure = { err ->
                        state.copy(
                            processingAvatarRequestIds = state.processingAvatarRequestIds - targetUid,
                            userActionMessage = err.localizedMessage ?: "Error",
                        )
                    },
                )
            }
        }
    }

    fun playDeveloperUserCurrentTrack(overview: DeveloperUserOverview) {
        val trackTitle = overview.currentTrackTitle?.trim().orEmpty()
        val trackArtist = overview.currentTrackArtist?.trim().orEmpty()
        val trackId = overview.currentTrackId?.trim()
        if (trackId.isNullOrBlank() && trackTitle.isBlank()) return
        if (_developerModeState.value.playingUserTrackUid == overview.userId) return

        viewModelScope.launch {
            _developerModeState.update { it.copy(playingUserTrackUid = overview.userId) }
            try {
                // 1. Check active local library & selected user's saved tracks
                val localTracks = kotlinx.coroutines.withContext(Dispatchers.IO) { dao.getAllTracksOnce() }
                val directMatch = trackId?.let { id ->
                    localTracks.find { it.id == id }
                        ?: playbackController.findTrackById(id)
                }
                if (directMatch != null) {
                    playOrToggleTrack(directMatch, listOf(directMatch) + localTracks.filterNot { it.id == directMatch.id }.take(20), contextTitle = overview.displayName)
                    return@launch
                }

                // 2. Check inspected user's cloud saved tracks
                val inspectedSaved = _developerModeState.value.selectedUserDetails
                    ?.takeIf { it.overview.userId == overview.userId }
                    ?.tracks
                    ?.find { it.trackId == trackId || (trackTitle.isNotBlank() && it.title.equals(trackTitle, ignoreCase = true)) }
                if (inspectedSaved != null) {
                    val entity = inspectedSaved.toLocalTrackEntity(existing = null)
                    playOrToggleTrack(entity, listOf(entity) + localTracks.take(20), contextTitle = overview.displayName)
                    return@launch
                }

                // 3. Fuzzy local match by title + artist
                if (trackTitle.isNotBlank()) {
                    val fuzzyLocal = localTracks.find { t ->
                        t.title.equals(trackTitle, ignoreCase = true) &&
                            (trackArtist.isBlank() || t.artist.contains(trackArtist, ignoreCase = true) || trackArtist.contains(t.artist, ignoreCase = true))
                    }
                    if (fuzzyLocal != null) {
                        playOrToggleTrack(fuzzyLocal, listOf(fuzzyLocal) + localTracks.filterNot { it.id == fuzzyLocal.id }.take(20), contextTitle = overview.displayName)
                        return@launch
                    }
                }

                // 4. Online search resolution
                val query = listOf(trackArtist, trackTitle).filter { it.isNotBlank() }.joinToString(" ").trim()
                if (query.isNotBlank() && networkObserver.refresh()) {
                    val remote = audiusRepository.searchTracksProgressive(query) {}.getOrDefault(emptyList())
                    val best = remote.firstOrNull { it.id == trackId }
                        ?: remote.firstOrNull {
                            it.title.equals(trackTitle, ignoreCase = true) &&
                                (trackArtist.isBlank() || it.artist.contains(trackArtist, ignoreCase = true))
                        }
                        ?: remote.firstOrNull()
                    if (best != null) {
                        playOrToggleTrack(best, remote, contextTitle = overview.displayName)
                    }
                }
            } finally {
                _developerModeState.update {
                    if (it.playingUserTrackUid == overview.userId) it.copy(playingUserTrackUid = null) else it
                }
            }
        }
    }

    fun refreshDeveloperUsers() {
        val repo = cloudRepository ?: return
        if (!_developerModeState.value.isUnlocked) return
        if (isGuestMode || cloudUserId.isNullOrBlank()) return
        devUsersJob?.cancel()
        devUsersJob = viewModelScope.launch {
            _developerModeState.update { it.copy(isLoadingUsers = true, usersErrorMessage = null) }
            val result = repo.fetchDeveloperAllUsersOverview()
            _developerModeState.update { state ->
                result.fold(
                    onSuccess = { list ->
                        state.copy(
                            isLoadingUsers = false,
                            users = list,
                            usersErrorMessage = null,
                        )
                    },
                    onFailure = { err ->
                        state.copy(
                            isLoadingUsers = false,
                            usersErrorMessage = err.localizedMessage,
                        )
                    },
                )
            }
        }
    }

    fun inspectDeveloperUser(targetUid: String) {
        val repo = cloudRepository ?: return
        if (!_developerModeState.value.isUnlocked) return
        if (isGuestMode || cloudUserId.isNullOrBlank()) return
        devUserDetailsJob?.cancel()
        val cachedOverview = _developerModeState.value.users.find { it.userId == targetUid }
        devUserDetailsJob = viewModelScope.launch {
            _developerModeState.update { state ->
                state.copy(
                    isLoadingUserDetails = true,
                    selectedUserDetails = cachedOverview?.let {
                        DeveloperUserFullDetails(overview = it)
                    },
                )
            }
            val result = repo.fetchDeveloperUserDetails(targetUid)
            _developerModeState.update { state ->
                result.fold(
                    onSuccess = { full ->
                        state.copy(
                            isLoadingUserDetails = false,
                            selectedUserDetails = full,
                            users = state.users.map { u ->
                                if (u.userId == full.overview.userId) full.overview else u
                            },
                        )
                    },
                    onFailure = { err ->
                        state.copy(
                            isLoadingUserDetails = false,
                            usersErrorMessage = err.localizedMessage,
                        )
                    },
                )
            }
        }
    }

    fun closeDeveloperUserDetails() {
        devUserDetailsJob?.cancel()
        _developerModeState.update {
            it.copy(
                isLoadingUserDetails = false,
                selectedUserDetails = null,
                userActionMessage = null,
            )
        }
    }

    fun clearDeveloperUserActionMessage() {
        _developerModeState.update { it.copy(userActionMessage = null) }
    }

    fun banDeveloperUser(targetUid: String, reason: String?) {
        val repo = cloudRepository ?: return
        if (!_developerModeState.value.isUnlocked) return
        if (isGuestMode || cloudUserId.isNullOrBlank()) return
        if (_developerModeState.value.isManagingUserAction) return
        viewModelScope.launch {
            _developerModeState.update { it.copy(isManagingUserAction = true, userActionMessage = null) }
            val result = repo.banUser(targetUid = targetUid, reason = reason)
            val devExtras = com.example.ui.i18n.stringsForLanguage(preferences.currentSettings().appLanguage).devExtras
            _developerModeState.update { state ->
                result.fold(
                    onSuccess = { banRecord ->
                        val targetName = state.selectedUserDetails?.overview?.takeIf { it.userId == targetUid }?.displayName
                            ?: state.users.find { it.userId == targetUid }?.displayName
                            ?: targetUid
                        val updatedSelected = state.selectedUserDetails?.let { details ->
                            if (details.overview.userId == targetUid) {
                                details.copy(
                                    overview = details.overview.copy(
                                        isBanned = true,
                                        banReason = banRecord.reason,
                                        bannedAtMs = banRecord.bannedAtMs,
                                    ),
                                )
                            } else {
                                details
                            }
                        }
                        val updatedUsers = state.users.map { u ->
                            if (u.userId == targetUid) {
                                u.copy(
                                    isBanned = true,
                                    banReason = banRecord.reason,
                                    bannedAtMs = banRecord.bannedAtMs,
                                )
                            } else {
                                u
                            }
                        }
                        state.copy(
                            isManagingUserAction = false,
                            selectedUserDetails = updatedSelected,
                            users = updatedUsers,
                            userActionMessage = devExtras.bannedSuccessMessage(targetName),
                        )
                    },
                    onFailure = { err ->
                        state.copy(
                            isManagingUserAction = false,
                            userActionMessage = err.localizedMessage ?: "Error",
                        )
                    },
                )
            }
        }
    }

    fun unbanDeveloperUser(targetUid: String) {
        val repo = cloudRepository ?: return
        if (!_developerModeState.value.isUnlocked) return
        if (isGuestMode || cloudUserId.isNullOrBlank()) return
        if (_developerModeState.value.isManagingUserAction) return
        viewModelScope.launch {
            _developerModeState.update { it.copy(isManagingUserAction = true, userActionMessage = null) }
            val result = repo.unbanUser(targetUid = targetUid)
            val devExtras = com.example.ui.i18n.stringsForLanguage(preferences.currentSettings().appLanguage).devExtras
            _developerModeState.update { state ->
                result.fold(
                    onSuccess = {
                        val targetName = state.selectedUserDetails?.overview?.takeIf { it.userId == targetUid }?.displayName
                            ?: state.users.find { it.userId == targetUid }?.displayName
                            ?: targetUid
                        val updatedSelected = state.selectedUserDetails?.let { details ->
                            if (details.overview.userId == targetUid) {
                                details.copy(
                                    overview = details.overview.copy(
                                        isBanned = false,
                                        banReason = null,
                                        bannedAtMs = 0L,
                                    ),
                                )
                            } else {
                                details
                            }
                        }
                        val updatedUsers = state.users.map { u ->
                            if (u.userId == targetUid) {
                                u.copy(
                                    isBanned = false,
                                    banReason = null,
                                    bannedAtMs = 0L,
                                )
                            } else {
                                u
                            }
                        }
                        state.copy(
                            isManagingUserAction = false,
                            selectedUserDetails = updatedSelected,
                            users = updatedUsers,
                            userActionMessage = devExtras.unbannedSuccessMessage(targetName),
                        )
                    },
                    onFailure = { err ->
                        state.copy(
                            isManagingUserAction = false,
                            userActionMessage = err.localizedMessage ?: "Error",
                        )
                    },
                )
            }
        }
    }

    fun deleteDeveloperUserFromServer(targetUid: String) {
        val repo = cloudRepository ?: return
        if (!_developerModeState.value.isUnlocked) return
        if (isGuestMode || cloudUserId.isNullOrBlank()) return
        if (_developerModeState.value.isManagingUserAction) return
        viewModelScope.launch {
            _developerModeState.update { it.copy(isManagingUserAction = true, userActionMessage = null) }
            val targetName = _developerModeState.value.selectedUserDetails?.overview?.takeIf { it.userId == targetUid }?.displayName
                ?: _developerModeState.value.users.find { it.userId == targetUid }?.displayName
                ?: targetUid
            val result = repo.deleteDeveloperUserFromServer(targetUid)
            val devExtras = com.example.ui.i18n.stringsForLanguage(preferences.currentSettings().appLanguage).devExtras
            result.onSuccess {
                _developerModeState.update { state ->
                    state.copy(
                        isManagingUserAction = false,
                        selectedUserDetails = null,
                        users = state.users.filterNot { it.userId == targetUid },
                        usersErrorMessage = null,
                        userActionMessage = devExtras.deletedUserSuccessMessage(targetName),
                    )
                }
                refreshDeveloperReports()
                refreshCloudStorageUsage()
            }.onFailure { err ->
                _developerModeState.update { state ->
                    state.copy(
                        isManagingUserAction = false,
                        userActionMessage = err.localizedMessage ?: "Error",
                    )
                }
            }
        }
    }

    fun syncPlaybackOnForeground() {
        playbackController.syncNowFromService()
    }

    fun onAppLifecycleChanged(isForeground: Boolean) {
        isAppForeground = isForeground
        if (isForeground) {
            playbackController.syncNowFromService()
        } else if (_musicVideoState.value.useClipAudio) {
            playbackController.setClipAudioMuteOverride(false)
            _musicVideoState.update { it.copy(useClipAudio = false) }
        }
        val isPlayingNow = playbackController.playbackState.value.isPlaying
        publishPresenceAndNowPlaying(
            isOnline = isForeground || isPlayingNow,
            force = true,
        )
    }

    private fun publishPresenceAndNowPlaying(
        isOnline: Boolean,
        force: Boolean = false,
    ) {
        val repo = cloudRepository ?: return
        val uid = cloudUserId?.takeIf { it.isNotBlank() } ?: return
        if (isGuestMode || _cloudSyncState.value.banRecord != null) return

        val playback = playbackController.playbackState.value
        val activeTrackId = playback.currentTrackId?.takeIf { it.isNotBlank() }
        val track = activeTrackId?.let { tid ->
            playbackController.findTrackById(tid)
                ?: uiState.value.tracks.find { it.id == tid }
                ?: _audiusState.value.searchResults.find { it.id == tid }
        }
        val mediaMetaFallback = if (track == null && activeTrackId != null) {
            playbackController.currentMediaMetadataFallback()
        } else {
            null
        }
        val resolvedTrackId = track?.id ?: activeTrackId
        val resolvedTitle = track?.title ?: mediaMetaFallback?.first
        val resolvedArtist = track?.artist ?: mediaMetaFallback?.second
        val resolvedCover = track?.coverArtPath ?: mediaMetaFallback?.third
        val effectiveIsPlaying = playback.isPlaying && resolvedTrackId != null
        val effectiveOnline = isOnline || effectiveIsPlaying

        val key = "$effectiveOnline|$effectiveIsPlaying|$resolvedTrackId|$resolvedTitle"
        if (!force && key == lastPublishedPresenceKey) return
        lastPublishedPresenceKey = key

        presenceSyncJob?.cancel()
        presenceSyncJob = app.appScope.launch(NonCancellable) {
            repo.updateUserPresenceAndNowPlaying(
                isOnline = effectiveOnline,
                trackId = resolvedTrackId,
                trackTitle = resolvedTitle,
                trackArtist = resolvedArtist,
                trackCoverUrl = resolvedCover,
                isPlaying = effectiveIsPlaying,
            )
        }
        if (uid.isBlank()) return
    }

    fun refreshCloudStorageUsage() {
        viewModelScope.launch {
            _developerModeState.update { it.copy(isLoadingStorageStats = true) }
            val repo = cloudRepository
            val uid = cloudUserId
            var stats: CloudStorageStats? = null

            if (!isGuestMode && repo != null && !uid.isNullOrBlank()) {
                // Ensure current user's snapshot is recorded in /cloud_metrics first
                val profile = buildCurrentCloudProfileSnapshot(uid)
                runCatching { repo.syncCloudMetricsFromLocal(dao, profile) }
                stats = repo.fetchDeveloperCloudStorageStats().getOrNull()
            }

            if (stats == null) {
                stats = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    computeLocalStorageStatsEstimate()
                }
            }

            _developerModeState.update {
                it.copy(
                    isLoadingStorageStats = false,
                    cloudStorageStats = stats,
                )
            }
        }
    }

    private fun buildCurrentCloudProfileSnapshot(uid: String): CloudUserProfile {
        val cs = _cloudSyncState.value
        val s = preferences.currentSettings()
        return CloudUserProfile(
            docId = uid,
            userId = uid,
            displayName = cs.displayName ?: "SoundLap User",
            username = cs.username,
            email = cs.email,
            photoUrl = if (cs.avatarBlocked) null else cs.photoUrl,
            bio = cs.bio,
            avatarBlocked = cs.avatarBlocked,
            accountUid = cs.accountUid ?: SoundLapUidFormatter.format(uid),
            appVersion = com.example.BuildConfig.VERSION_NAME,
            searchHistory = s.searchHistory.take(25),
            listeningStatsJson = CloudUserProfile.encodeListeningEventsForCloud(s.listeningEvents),
            settings = com.example.data.remote.CloudUserSettings.fromSoundLapaSettings(s).toMap(),
        )
    }

    private suspend fun computeLocalStorageStatsEstimate(): CloudStorageStats {
        val uid = cloudUserId ?: "guest_local"
        val tracks = dao.getAllTracksOnce()
        val playlists = dao.getAllPlaylistsOnce()
        val refs = dao.getAllPlaylistRefsOnce()
        val refIds = refs.mapTo(HashSet()) { it.trackId }
        val personalTracks = tracks
            .filter { it.isLiked || it.isUserUpload || it.id in refIds }
            .map { CloudSavedTrack.fromTrackEntity(uid, it) }
        val cloudPlaylists = playlists.map { CloudUserPlaylist.fromPlaylistEntity(uid, it) }
        val pendingReports = preferences.currentSettings().pendingBugReports
        val reportsBytes = pendingReports.sumOf { local ->
            estimateBugReportBytes(
                CloudBugReport(
                    reportId = local.reportId,
                    userId = uid,
                    reporterName = local.reporterName,
                    reporterAvatarUrl = local.reporterAvatarUrl,
                    message = local.message,
                    appVersion = local.appVersion,
                    createdAtMs = local.createdAtMs,
                ),
            )
        }
        val userBytes = if (isGuestMode) {
            0L
        } else {
            estimateUserCloudStorageBytes(
                profile = buildCurrentCloudProfileSnapshot(uid),
                tracks = personalTracks,
                playlists = cloudPlaylists,
                playlistItemsCount = refs.size,
            )
        }
        return CloudStorageStats(
            usedBytes = userBytes + reportsBytes,
            quotaBytes = DEFAULT_CLOUD_QUOTA_BYTES,
            activeUsersCount = if (isGuestMode) 0 else 1,
            totalTracks = if (isGuestMode) 0 else personalTracks.size,
            totalPlaylists = if (isGuestMode) 0 else cloudPlaylists.size,
            totalBugReports = pendingReports.size,
            isCloudLive = false,
        )
    }

    private fun mergeBugReportsForUi(cloudList: List<CloudBugReport>): List<BugReportItemUi> {
        val merged = LinkedHashMap<String, BugReportItemUi>()
        cloudList.forEach { c ->
            merged[c.reportId] = BugReportItemUi(
                reportId = c.reportId,
                docId = c.docId.ifBlank { c.reportId },
                userId = c.userId,
                reporterName = c.reporterName.ifBlank { "User" },
                reporterAvatarUrl = c.reporterAvatarUrl,
                message = c.message,
                appVersion = c.appVersion,
                status = c.parsedStatus(),
                createdAtMs = c.createdAtMs,
            )
        }
        preferences.currentSettings().pendingBugReports.forEach { local ->
            if (!merged.containsKey(local.reportId)) {
                merged[local.reportId] = BugReportItemUi(
                    reportId = local.reportId,
                    docId = local.reportId,
                    userId = cloudUserId.orEmpty(),
                    reporterName = local.reporterName,
                    reporterAvatarUrl = local.reporterAvatarUrl,
                    message = local.message,
                    appVersion = local.appVersion,
                    status = BugReportStatus.OPEN,
                    createdAtMs = local.createdAtMs,
                )
            }
        }
        return merged.values.sortedByDescending { it.createdAtMs }
    }

    fun refreshDeveloperReports() {
        viewModelScope.launch {
            _developerModeState.update { it.copy(isLoadingReports = true, errorMessage = null) }
            val repo = cloudRepository
            var cloudError: String? = null
            var cloudList: List<CloudBugReport> = emptyList()
            if (!isGuestMode && repo != null && !cloudUserId.isNullOrBlank()) {
                val res = repo.fetchDeveloperBugReports()
                res.onSuccess { list ->
                    cloudList = list
                }.onFailure { err ->
                    cloudError = err.localizedMessage
                }
            }
            val sorted = mergeBugReportsForUi(cloudList)
            _developerModeState.update {
                it.copy(
                    isLoadingReports = false,
                    reports = sorted,
                    errorMessage = cloudError,
                )
            }
        }
    }

    fun updateDeveloperBugReportStatus(report: BugReportItemUi, status: BugReportStatus) {
        if (report.reportId in _developerModeState.value.updatingReportIds) return
        if (report.status == status) return
        viewModelScope.launch {
            _developerModeState.update {
                it.copy(
                    updatingReportIds = it.updatingReportIds + report.reportId,
                    errorMessage = null,
                )
            }
            val repo = cloudRepository
            if (!isGuestMode && repo != null && !cloudUserId.isNullOrBlank()) {
                val targetDocId = report.docId.ifBlank { report.reportId }
                val res = repo.updateDeveloperBugReportStatus(targetDocId, status)
                if (res.isFailure) {
                    _developerModeState.update {
                        it.copy(
                            updatingReportIds = it.updatingReportIds - report.reportId,
                            errorMessage = res.exceptionOrNull()?.localizedMessage ?: "Failed to update report",
                        )
                    }
                    return@launch
                }
            }
            _developerModeState.update { state ->
                state.copy(
                    updatingReportIds = state.updatingReportIds - report.reportId,
                    reports = state.reports.map { item ->
                        if (item.reportId == report.reportId) item.copy(status = status) else item
                    },
                )
            }
        }
    }

    fun deleteDeveloperBugReport(report: BugReportItemUi) {
        if (report.reportId in _developerModeState.value.deletingReportIds) return
        viewModelScope.launch {
            _developerModeState.update {
                it.copy(
                    deletingReportIds = it.deletingReportIds + report.reportId,
                    errorMessage = null,
                )
            }
            val repo = cloudRepository
            if (!isGuestMode && repo != null && !cloudUserId.isNullOrBlank()) {
                val targetDocId = report.docId.ifBlank { report.reportId }
                val res = repo.deleteDeveloperBugReport(targetDocId)
                if (res.isFailure) {
                    _developerModeState.update {
                        it.copy(
                            deletingReportIds = it.deletingReportIds - report.reportId,
                            errorMessage = res.exceptionOrNull()?.localizedMessage ?: "Failed to delete report",
                        )
                    }
                    return@launch
                }
            }
            preferences.removeLocalBugReport(report.reportId)
            _developerModeState.update {
                it.copy(
                    deletingReportIds = it.deletingReportIds - report.reportId,
                    reports = it.reports.filterNot { item -> item.reportId == report.reportId },
                )
            }
            refreshCloudStorageUsage()
        }
    }

    fun refreshLocalCacheStats() {
        viewModelScope.launch {
            val stats = kotlinx.coroutines.withContext(Dispatchers.IO) {
                val prefetch = AudioPrefetchCache.inspectStats(app)
                val imageDir = java.io.File(app.cacheDir, "image_cache")
                val imageBytes = computeDirBytes(imageDir)
                val dbFile = app.getDatabasePath("soundlapa.db")
                val roomBytes = listOf(
                    dbFile,
                    java.io.File("${dbFile.path}-wal"),
                    java.io.File("${dbFile.path}-shm"),
                ).sumOf { f -> if (f.exists()) f.length().coerceAtLeast(0L) else 0L }
                val allTracks = dao.getAllTracksOnce()
                val offlineTracks = allTracks.filter { !it.cachedFilePath.isNullOrBlank() || it.isUserUpload }
                val offlineBytes = offlineTracks.sumOf { t ->
                    val path = t.cachedFilePath
                    if (!path.isNullOrBlank()) {
                        val f = java.io.File(path)
                        if (f.exists()) f.length().coerceAtLeast(0L) else t.fileSizeBytes.coerceAtLeast(0L)
                    } else {
                        t.fileSizeBytes.coerceAtLeast(0L)
                    }
                }
                LocalDeviceCacheStats(
                    audioPrefetchBytes = prefetch.usedBytes,
                    audioPrefetchTracksCount = prefetch.cachedTracksCount,
                    audioPrefetchMaxBytes = prefetch.maxBytes,
                    imageCacheBytes = imageBytes,
                    roomDatabaseBytes = roomBytes,
                    offlineAudioBytes = offlineBytes,
                    offlineTracksCount = offlineTracks.size,
                )
            }
            _developerModeState.update { it.copy(localCacheStats = stats) }
        }
    }

    fun clearAudioPrefetchCacheOnly() {
        if (_developerModeState.value.isClearingLocalCache) return
        viewModelScope.launch {
            _developerModeState.update { it.copy(isClearingLocalCache = true, localCacheMessage = null) }
            val freed = kotlinx.coroutines.withContext(Dispatchers.IO) {
                AudioPrefetchCache.clearCache(app)
            }
            refreshCacheSize()
            refreshLocalCacheStats()
            _developerModeState.update {
                it.copy(
                    isClearingLocalCache = false,
                    localCacheMessage = formatFileSize(freed),
                )
            }
        }
    }

    fun clearImageCoverCacheOnly() {
        if (_developerModeState.value.isClearingLocalCache) return
        viewModelScope.launch {
            _developerModeState.update { it.copy(isClearingLocalCache = true, localCacheMessage = null) }
            val freed = kotlinx.coroutines.withContext(Dispatchers.IO) {
                val imageDir = java.io.File(app.cacheDir, "image_cache")
                val before = computeDirBytes(imageDir)
                runCatching { imageDir.deleteRecursively() }
                before
            }
            refreshCacheSize()
            refreshLocalCacheStats()
            _developerModeState.update {
                it.copy(
                    isClearingLocalCache = false,
                    localCacheMessage = formatFileSize(freed),
                )
            }
        }
    }

    fun clearAllLocalCachesFromDev() {
        if (_developerModeState.value.isClearingLocalCache) return
        viewModelScope.launch {
            _developerModeState.update { it.copy(isClearingLocalCache = true, localCacheMessage = null) }
            val freedPrefetch = kotlinx.coroutines.withContext(Dispatchers.IO) {
                AudioPrefetchCache.clearCache(app)
            }
            val freedApp = cacheManager.clearAppCache()
            audiusRepository.clearSearchCache()
            val remaining = cacheManager.calculateCacheSizeBytes()
            _cacheSizeBytes.value = remaining
            refreshLocalCacheStats()
            _developerModeState.update {
                it.copy(
                    isClearingLocalCache = false,
                    localCacheMessage = formatFileSize(freedPrefetch + freedApp),
                )
            }
        }
    }

    private fun computeDirBytes(dir: java.io.File): Long {
        if (!dir.exists()) return 0L
        if (dir.isFile) return dir.length().coerceAtLeast(0L)
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length().coerceAtLeast(0L) }
    }

    private fun scheduleCloudSettingsSync() {
        val repo = cloudRepository ?: return
        if (isGuestMode || cloudUserId.isNullOrBlank()) return
        settingsSyncJob?.cancel()
        settingsSyncJob = app.appScope.launch(NonCancellable) {
            delay(600L)
            repo.syncUserSettings(preferences.currentSettings())
            scheduleCloudMetricsSync()
        }
    }

    private fun scheduleCloudMetricsSync() {
        val repo = cloudRepository ?: return
        val uid = cloudUserId?.takeIf { it.isNotBlank() } ?: return
        if (isGuestMode) return
        metricsSyncJob?.cancel()
        metricsSyncJob = app.appScope.launch(NonCancellable) {
            delay(1200L)
            repo.syncCloudMetricsFromLocal(dao, buildCurrentCloudProfileSnapshot(uid))
        }
    }

    private fun scheduleCloudActivitySync() {
        val repo = cloudRepository ?: return
        val uid = cloudUserId?.takeIf { it.isNotBlank() } ?: return
        if (isGuestMode) return
        activitySyncJob?.cancel()
        activitySyncJob = app.appScope.launch(NonCancellable) {
            delay(2000L)
            val s = preferences.currentSettings()
            repo.syncSearchHistoryAndListeningStats(
                searchHistory = s.searchHistory,
                listeningEvents = s.listeningEvents,
            )
        }
    }

    fun syncListeningStatsNow() {
        val repo = cloudRepository ?: return
        val uid = cloudUserId?.takeIf { it.isNotBlank() } ?: return
        if (isGuestMode) return
        viewModelScope.launch {
            val s = preferences.currentSettings()
            repo.syncSearchHistoryAndListeningStats(
                searchHistory = s.searchHistory,
                listeningEvents = s.listeningEvents,
            )
        }
    }

    fun refreshRecommendations(forceOnline: Boolean = true) {
        if (_isRefreshingRecommendations.value) return
        if (!networkObserver.refresh()) return
        val settings = preferences.currentSettings()
        if (settings.dataSaverEnabled && !forceOnline) return

        viewModelScope.launch {
            _isRefreshingRecommendations.value = true
            runCatching {
                val tracks = dao.getAllTracksOnce()
                val rec = RecommendationEngine.computeRecommendations(
                    allTracks = tracks,
                    listeningHistory = settings.listeningHistory,
                    searchHistory = settings.searchHistory,
                    limit = 12,
                )
                val queries = rec.onlineSeedQueries
                if (queries.isNotEmpty() && (!settings.dataSaverEnabled || forceOnline)) {
                    audiusRepository.fetchSimilarTracksForQueries(
                        queries = queries,
                        perQueryLimit = if (settings.dataSaverEnabled) 3 else 5,
                    )
                }
            }
            _isRefreshingRecommendations.value = false
        }
    }

    fun syncAudiusAccount(handleInput: String) {
        val clean = handleInput.trim().removePrefix("@")
        if (clean.isBlank()) return
        viewModelScope.launch {
            _audiusState.update { it.copy(isSyncingAccount = true, statusMessage = null) }
            val result = audiusRepository.syncUserByHandle(clean)
            result.onSuccess { summary ->
                preferences.setAudiusAccount(
                    handle = summary.handle,
                    userId = summary.userId,
                    displayName = summary.displayName,
                )
                _audiusState.update {
                    it.copy(
                        isSyncingAccount = false,
                        statusMessage = "Синхронизировано: @${summary.handle} (${summary.importedPlaylists} плейлистов, ${summary.importedTracks} треков)",
                    )
                }
            }.onFailure { err ->
                _audiusState.update {
                    it.copy(
                        isSyncingAccount = false,
                        statusMessage = err.message ?: "Не удалось подключиться к Audius",
                    )
                }
            }
        }
    }

    fun disconnectAudiusAccount() {
        viewModelScope.launch {
            preferences.setAudiusAccount(null, null, null)
            _audiusState.update { it.copy(statusMessage = "Аккаунт Audius отключён") }
        }
    }

    fun clearAudiusStatus() {
        _audiusState.update { it.copy(statusMessage = null) }
    }

    fun setPlayerExpanded(expanded: Boolean) {
        _isPlayerExpanded.value = expanded
    }

    fun dismissOfflineDialog() {
        _showOfflineDialog.value = false
        _offlineDialogTrackTitle.value = null
    }

    fun dismissReconnectedBanner() {
        reconnectedBannerJob?.cancel()
        _showReconnectedBanner.value = false
    }

    fun requestOpenDownloadedTracksSection() {
        _showOfflineDialog.value = false
        _offlineDialogTrackTitle.value = null
        // Tabs: 0=Playlists, 1=Liked, 2=Subscriptions, 3=Downloads, 4=Stats
        _requestedLibrarySection.value = 3
    }

    fun consumeRequestedLibrarySection() {
        _requestedLibrarySection.value = null
    }

    fun refreshConnectivity(): Boolean = networkObserver.refresh()

    fun playOrToggleTrack(
        track: TrackEntity,
        customQueue: List<TrackEntity>? = null,
        contextTitle: String? = null,
    ) {
        _waveSessionActive.value = false
        val online = networkObserver.refresh()
        if (!online && !isTrackPlayableOffline(track)) {
            _offlineDialogTrackTitle.value = track.title
            _showOfflineDialog.value = true
            return
        }
        val currentId = playbackController.playbackState.value.currentTrackId
        if (currentId == track.id &&
            customQueue == null &&
            playbackController.isTrackActiveInController(track.id)
        ) {
            playbackController.togglePlayPause(track)
        } else {
            val rawQueue = customQueue ?: uiState.value.filteredTracks.ifEmpty { uiState.value.tracks }
            val queue = if (online) {
                rawQueue
            } else {
                rawQueue.filter { isTrackPlayableOffline(it) }.ifEmpty { listOf(track) }
            }
            // Dispatch playback immediately without awaiting Room IO so 1 tap starts resolving audio instantly
            playbackController.playTrack(track, queue, contextTitle = contextTitle)

            viewModelScope.launch {
                audiusRepository.ensureTrackPersisted(track)
                val settings = uiState.value.settings
                // Transient audio buffer only for HTTP stream stability (no manual offline cache)
                if (online && !track.isUserUpload && !track.isCachedOffline && !settings.dataSaverEnabled &&
                    track.sourceUri?.startsWith("http") == true
                ) {
                    audiusRepository.cacheTrackAudioIfNeeded(track)
                    refreshCacheSize()
                }
            }
        }
    }

    fun openArtistProfile(
        artistName: String,
        seedTrack: TrackEntity? = null,
        spotifyId: String? = null,
        soundCloudUrl: String? = null,
    ) {
        val cleanName = artistName.trim()
        if (cleanName.isBlank()) return

        artistProfileJob?.cancel()
        albumLoadJob?.cancel()

        val online = networkObserver.refresh()
        val allTracksSnapshot = uiState.value.tracks
        val instantLocalMatches = buildList {
            if (seedTrack != null) add(seedTrack)
            allTracksSnapshot.forEach { t ->
                if (t.id != seedTrack?.id && audiusRepository.isTrackByArtist(t, listOf(cleanName, artistName))) {
                    add(t)
                }
            }
        }
        _artistProfileState.value = ArtistProfileUiState(
            isVisible = true,
            artistName = cleanName,
            avatarUrl = seedTrack?.coverArtPath ?: instantLocalMatches.firstOrNull()?.coverArtPath,
            accentColorHex = seedTrack?.accentColorHex ?: 0xFF8B5CF6L,
            secondaryColorHex = seedTrack?.secondaryColorHex ?: 0xFF5CE1E6L,
            isLoading = online,
            tracks = instantLocalMatches,
            albums = emptyList(),
        )

        artistProfileJob = viewModelScope.launch {
            if (!online) return@launch

            val catalog = runCatching {
                audiusRepository.fetchArtistCatalog(
                    artistName = cleanName,
                    seedTrack = seedTrack,
                    knownSpotifyArtistId = spotifyId,
                    knownSoundCloudUrl = soundCloudUrl,
                    onPartialResult = { partial ->
                        val hasRichContent = partial.tracks.size >= 4 || partial.albums.isNotEmpty()
                        _artistProfileState.update { current ->
                            if (!current.isVisible || !current.artistName.equals(cleanName, ignoreCase = true)) {
                                current
                            } else {
                                current.copy(
                                    isLoading = if (hasRichContent) false else current.isLoading,
                                    artistName = partial.artistName.ifBlank { current.artistName },
                                    avatarUrl = partial.avatarUrl ?: current.avatarUrl,
                                    tracks = partial.tracks.ifEmpty { current.tracks },
                                    albums = partial.albums.ifEmpty { current.albums },
                                )
                            }
                        }
                    },
                )
            }.getOrNull()

            _artistProfileState.update { current ->
                if (!current.isVisible || !current.artistName.equals(cleanName, ignoreCase = true)) {
                    current
                } else if (catalog != null) {
                    current.copy(
                        isLoading = false,
                        artistName = catalog.artistName.ifBlank { current.artistName },
                        avatarUrl = catalog.avatarUrl ?: current.avatarUrl,
                        tracks = catalog.tracks.ifEmpty { current.tracks },
                        albums = catalog.albums.ifEmpty { current.albums },
                    )
                } else {
                    current.copy(isLoading = false)
                }
            }
        }
    }

    fun closeArtistProfile() {
        artistProfileJob?.cancel()
        albumLoadJob?.cancel()
        _artistProfileState.value = ArtistProfileUiState()
    }

    fun openArtistAlbum(album: ArtistAlbumItem) {
        albumLoadJob?.cancel()
        _artistProfileState.update {
            it.copy(
                selectedAlbum = album,
                selectedAlbumTracks = album.tracks,
                isLoadingAlbum = album.tracks.isEmpty(),
            )
        }
        if (album.tracks.isNotEmpty()) return

        albumLoadJob = viewModelScope.launch {
            val loaded = runCatching { audiusRepository.fetchAlbumTracks(album) }.getOrDefault(emptyList())
            _artistProfileState.update { current ->
                if (current.selectedAlbum?.id != album.id) {
                    current
                } else {
                    val updatedAlbums = current.albums.map {
                        if (it.id == album.id) it.copy(tracks = loaded) else it
                    }
                    current.copy(
                        albums = updatedAlbums,
                        selectedAlbum = album.copy(tracks = loaded),
                        selectedAlbumTracks = loaded,
                        isLoadingAlbum = false,
                    )
                }
            }
        }
    }

    fun closeArtistAlbum() {
        albumLoadJob?.cancel()
        _artistProfileState.update {
            it.copy(
                selectedAlbum = null,
                selectedAlbumTracks = emptyList(),
                isLoadingAlbum = false,
            )
        }
    }

    fun playArtistTracks(shuffled: Boolean = false) {
        val state = _artistProfileState.value
        val tracks = state.tracks
        if (tracks.isEmpty()) return
        val queue = if (shuffled) tracks.shuffled() else tracks
        playOrToggleTrack(queue.first(), queue, contextTitle = state.artistName)
    }

    fun playAlbumTracks(
        tracks: List<TrackEntity>,
        startTrack: TrackEntity? = null,
        albumTitle: String? = null,
    ) {
        if (tracks.isEmpty()) return
        val target = startTrack ?: tracks.first()
        playOrToggleTrack(target, tracks, contextTitle = albumTitle ?: target.albumTitle)
    }

    fun playPlaylist(playlist: PlaylistWithTracks, shuffled: Boolean = false) {
        if (playlist.tracks.isEmpty()) return
        val queue = if (shuffled) playlist.tracks.shuffled() else playlist.tracks
        playOrToggleTrack(queue.first(), queue, contextTitle = playlist.playlist.name)
    }

    fun toggleLike(track: TrackEntity) {
        viewModelScope.launch {
            audiusRepository.ensureTrackPersisted(track)
            val nextLiked = !track.isLiked
            val nextCount = (track.likeCount + if (nextLiked) 1 else -1).coerceAtLeast(0)
            dao.updateLike(track.id, nextLiked, nextCount)
            val updatedTrack = dao.getTrackById(track.id) ?: track.copy(isLiked = nextLiked, likeCount = nextCount)
            if (cloudRepository != null) {
                val isInPlaylist = uiState.value.playlists.any { pl -> pl.tracks.any { it.id == track.id } }
                if (nextLiked || updatedTrack.isUserUpload || isInPlaylist) {
                    cloudRepository.upsertSavedTrack(updatedTrack)
                } else {
                    cloudRepository.deleteSavedTrack(track.id)
                }
            }
            _artistProfileState.update { current ->
                if (!current.isVisible) {
                    current
                } else {
                    current.copy(
                        tracks = current.tracks.map {
                            if (it.id == track.id) it.copy(isLiked = nextLiked, likeCount = nextCount) else it
                        },
                        selectedAlbumTracks = current.selectedAlbumTracks.map {
                            if (it.id == track.id) it.copy(isLiked = nextLiked, likeCount = nextCount) else it
                        },
                    )
                }
            }
        }
    }

    fun deleteTrack(track: TrackEntity) {
        viewModelScope.launch {
            cacheManager.deleteTrack(track)
            cloudRepository?.deleteSavedTrack(track.id)
        }
    }

    fun updateTrackDetails(
        track: TrackEntity,
        newTitle: String,
        newArtist: String,
        newAlbumTitle: String? = track.albumTitle,
        newGenre: String = track.genre,
        newCoverUri: Uri? = null,
        clearCover: Boolean = false,
    ) {
        viewModelScope.launch {
            audiusRepository.ensureTrackPersisted(track)
            val finalCoverPath = when {
                clearCover -> null
                newCoverUri != null -> cacheManager.saveCustomCover(track.id, newCoverUri) ?: track.coverArtPath
                else -> track.coverArtPath
            }
            val cleanTitle = newTitle.trim().ifBlank { track.title }
            val cleanArtist = newArtist.trim().ifBlank { track.artist }
            val cleanAlbum = newAlbumTitle?.trim()?.takeIf { it.isNotEmpty() }
            val cleanGenre = newGenre.trim().ifBlank { track.genre }
            dao.updateTrackFullMetadata(
                id = track.id,
                title = cleanTitle,
                artist = cleanArtist,
                albumTitle = cleanAlbum,
                genre = cleanGenre,
                coverArtPath = finalCoverPath,
            )
            dao.getTrackById(track.id)?.let { updated ->
                playbackController.updateAvailableTracks(dao.getAllTracksOnce())
                cloudRepository?.upsertSavedTrack(updated)
                _artistProfileState.update { current ->
                    if (!current.isVisible) {
                        current
                    } else {
                        current.copy(
                            tracks = current.tracks.map { if (it.id == updated.id) updated else it },
                            selectedAlbumTracks = current.selectedAlbumTracks.map {
                                if (it.id == updated.id) updated else it
                            },
                        )
                    }
                }
            }
        }
    }

    fun updatePlaylistDetails(
        playlist: PlaylistEntity,
        newName: String,
        newDescription: String,
        newCoverUri: Uri?,
        clearCover: Boolean,
    ) {
        viewModelScope.launch {
            val finalCoverPath = when {
                clearCover -> null
                newCoverUri != null -> cacheManager.saveCustomCover(playlist.id, newCoverUri) ?: playlist.coverArtPath
                else -> playlist.coverArtPath
            }
            val cleanName = newName.trim().ifBlank { playlist.name }
            val cleanDesc = PlaylistMetadataHelper.mergeUserEdit(playlist.description, newDescription)
            dao.updatePlaylistMetadata(
                id = playlist.id,
                name = cleanName,
                description = cleanDesc,
                coverArtPath = finalCoverPath,
            )
            val updatedPlaylist = playlist.copy(
                name = cleanName,
                description = cleanDesc,
                coverArtPath = finalCoverPath,
            )
            cloudRepository?.upsertPlaylist(updatedPlaylist)
        }
    }

    fun createPlaylist(
        name: String,
        description: String = "",
        coverUri: Uri? = null,
        initialTrack: TrackEntity? = null,
    ) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val id = "pl_${UUID.randomUUID()}"
            val savedCoverPath = if (coverUri != null) {
                cacheManager.saveCustomCover(id, coverUri)
            } else {
                null
            }
            val playlist = PlaylistEntity(
                id = id,
                name = trimmed,
                description = description.trim(),
                coverArtPath = savedCoverPath,
            )
            dao.upsertPlaylist(playlist)
            if (initialTrack != null) {
                audiusRepository.ensureTrackPersisted(initialTrack)
                val now = System.currentTimeMillis()
                dao.addTrackToPlaylist(PlaylistTrackCrossRef(playlistId = id, trackId = initialTrack.id, addedAt = now, position = 0))
                cloudRepository?.upsertPlaylistItem(playlist, initialTrack, position = 0, addedAtMs = now)
            } else {
                cloudRepository?.upsertPlaylist(playlist)
            }
        }
    }

    fun togglePlaylistPinned(playlist: PlaylistEntity) {
        viewModelScope.launch {
            val nextPinned = !playlist.isPinned
            val nextPinnedAt = if (nextPinned) System.currentTimeMillis() else 0L
            dao.updatePlaylistPin(playlist.id, nextPinned, nextPinnedAt)
            cloudRepository?.upsertPlaylist(
                playlist.copy(isPinned = nextPinned, pinnedAtMs = nextPinnedAt),
            )
        }
    }

    fun updatePlaylistSortMode(playlistId: String, sortMode: PlaylistSortMode) {
        viewModelScope.launch {
            dao.updatePlaylistSortMode(playlistId, sortMode.raw)
            dao.getAllPlaylistsOnce().find { it.id == playlistId }?.let { updated ->
                cloudRepository?.upsertPlaylist(updated)
            }
        }
    }

    fun movePlaylistTrack(playlistId: String, trackId: String, moveUp: Boolean) {
        viewModelScope.launch {
            val refs = dao.getPlaylistRefsForPlaylistOnce(playlistId)
                .sortedWith(compareBy<PlaylistTrackCrossRef> { it.position }.thenBy { it.addedAt })
                .toMutableList()
            val idx = refs.indexOfFirst { it.trackId == trackId }
            if (idx < 0) return@launch
            val targetIdx = if (moveUp) idx - 1 else idx + 1
            if (targetIdx !in refs.indices) return@launch
            val item = refs.removeAt(idx)
            refs.add(targetIdx, item)
            dao.replacePlaylistTrackOrder(playlistId, refs)
            val playlist = dao.getAllPlaylistsOnce().find { it.id == playlistId } ?: return@launch
            val tracksById = dao.getAllTracksOnce().associateBy { it.id }
            refs.forEachIndexed { pos, ref ->
                val tr = tracksById[ref.trackId] ?: return@forEachIndexed
                cloudRepository?.upsertPlaylistItem(playlist, tr, position = pos, addedAtMs = ref.addedAt)
            }
        }
    }

    fun deletePlaylist(playlistId: String) {
        viewModelScope.launch {
            dao.deletePlaylist(playlistId)
            cloudRepository?.deletePlaylist(playlistId)
        }
    }

    fun addTrackToPlaylist(playlistId: String, track: TrackEntity) {
        viewModelScope.launch {
            audiusRepository.ensureTrackPersisted(track)
            val nextPos = dao.getMaxPlaylistTrackPosition(playlistId) + 1
            val now = System.currentTimeMillis()
            dao.addTrackToPlaylist(
                PlaylistTrackCrossRef(
                    playlistId = playlistId,
                    trackId = track.id,
                    addedAt = now,
                    position = nextPos,
                ),
            )
            val playlist = uiState.value.playlists.find { it.playlist.id == playlistId }?.playlist
                ?: dao.getAllPlaylistsOnce().find { it.id == playlistId }
            if (playlist != null) {
                cloudRepository?.upsertPlaylistItem(playlist, track, nextPos, now)
            }
        }
    }

    fun removeTrackFromPlaylist(playlistId: String, trackId: String) {
        viewModelScope.launch {
            dao.removeTrackFromPlaylist(playlistId, trackId)
            cloudRepository?.removePlaylistItem(playlistId, trackId)
        }
    }

    fun stageSingleAudioForUpload(uri: Uri) {
        viewModelScope.launch {
            _uploadState.update { it.copy(isImporting = true, statusMessage = null) }
            val preview = cacheManager.inspectUriMetadata(uri)
            _uploadState.update {
                it.copy(
                    isImporting = false,
                    stagedDraft = StagedUploadDraft(
                        preview = preview,
                        title = preview.title,
                        artist = preview.artist,
                        genre = "Мои треки",
                        description = "",
                    ),
                )
            }
        }
    }

    fun updateStagedDraft(
        title: String? = null,
        artist: String? = null,
        genre: String? = null,
        description: String? = null,
        paletteIndex: Int? = null,
    ) {
        _uploadState.update { state ->
            val current = state.stagedDraft ?: return@update state
            state.copy(
                stagedDraft = current.copy(
                    title = title ?: current.title,
                    artist = artist ?: current.artist,
                    genre = genre ?: current.genre,
                    description = description ?: current.description,
                    paletteIndex = paletteIndex ?: current.paletteIndex,
                ),
            )
        }
    }

    fun cancelStagedDraft() {
        _uploadState.update { it.copy(stagedDraft = null) }
    }

    fun publishStagedTrack() {
        val draft = _uploadState.value.stagedDraft ?: return
        viewModelScope.launch {
            _uploadState.update { it.copy(isImporting = true) }
            val palette = uploadPalettes[draft.paletteIndex % uploadPalettes.size]
            val imported = cacheManager.importTrackFromUri(
                uri = draft.preview.uri,
                titleOverride = draft.title,
                artistOverride = draft.artist,
                genre = draft.genre,
                description = draft.description,
                accentColorHex = palette.first,
                secondaryColorHex = palette.second,
                isAutoImported = false,
            )
            _uploadState.update {
                it.copy(
                    isImporting = false,
                    stagedDraft = null,
                    statusMessage = if (imported != null) {
                        "Трек «${imported.title}» добавлен"
                    } else {
                        "Не удалось открыть аудиофайл"
                    },
                )
            }
            if (imported != null) {
                cloudRepository?.upsertSavedTrack(imported)
                playbackController.playTrack(imported, listOf(imported) + uiState.value.tracks)
            }
        }
    }

    fun importMultipleAudioFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (uris.size == 1) {
            stageSingleAudioForUpload(uris.first())
            return
        }
        viewModelScope.launch {
            _uploadState.update { it.copy(isImporting = true, statusMessage = null) }
            var successCount = 0
            val importedTracks = mutableListOf<TrackEntity>()
            uris.forEachIndexed { idx, uri ->
                val palette = uploadPalettes[idx % uploadPalettes.size]
                val imported = cacheManager.importTrackFromUri(
                    uri = uri,
                    genre = "Мои треки",
                    accentColorHex = palette.first,
                    secondaryColorHex = palette.second,
                    isAutoImported = false,
                )
                if (imported != null) {
                    successCount++
                    importedTracks += imported
                }
            }
            importedTracks.forEach { cloudRepository?.upsertSavedTrack(it) }
            _uploadState.update {
                it.copy(
                    isImporting = false,
                    statusMessage = "Добавлено треков: $successCount из ${uris.size}",
                )
            }
        }
    }

    fun setAutoUploadFolder(treeUri: Uri, folderName: String?) {
        viewModelScope.launch {
            preferences.setAutoUploadFolder(treeUri.toString(), folderName)
            syncAutoUploadFolderNow(treeUri)
        }
    }

    fun clearAutoUploadFolder() {
        lastAutoSyncedTreeUri = null
        viewModelScope.launch {
            preferences.setAutoUploadFolder(null, null)
            _uploadState.update {
                it.copy(lastSyncResult = null, statusMessage = "Папка авто-загрузки отвязана")
            }
        }
    }

    fun syncAutoUploadFolderIfEnabled() {
        val settings = uiState.value.settings
        val uriString = settings.autoUploadTreeUri ?: return
        if (!settings.autoUploadEnabled || _uploadState.value.isSyncingFolder) return
        if (lastAutoSyncedTreeUri == uriString) return
        lastAutoSyncedTreeUri = uriString
        if (System.currentTimeMillis() - settings.lastSyncTimestamp < 6L * 3600_000L) return
        syncAutoUploadFolderNow(Uri.parse(uriString))
    }

    fun syncAutoUploadFolderNow(explicitUri: Uri? = null) {
        val uri = explicitUri
            ?: uiState.value.settings.autoUploadTreeUri?.let { Uri.parse(it) }
            ?: return
        lastAutoSyncedTreeUri = uri.toString()

        viewModelScope.launch {
            _uploadState.update { it.copy(isSyncingFolder = true) }
            val result = cacheManager.scanAutoUploadFolder(uri)
            preferences.setLastSyncTimestamp(System.currentTimeMillis())
            if (result.newlyImported > 0 && cloudRepository != null) {
                dao.getAllTracksOnce()
                    .filter { it.isUserUpload }
                    .forEach { cloudRepository.upsertSavedTrack(it) }
            }
            _uploadState.update {
                it.copy(
                    isSyncingFolder = false,
                    lastSyncResult = result,
                    statusMessage = when {
                        result.newlyImported > 0 ->
                            "Импортировано треков: ${result.newlyImported}"
                        result.scannedFiles > 0 ->
                            "Все ${result.scannedFiles} треков уже добавлены"
                        else ->
                            "Новых аудиофайлов в папке нет"
                    },
                )
            }
        }
    }

    fun setAutoUploadEnabled(enabled: Boolean) {
        app.appScope.launch(NonCancellable) {
            preferences.setAutoUploadEnabled(enabled)
            if (enabled) {
                syncAutoUploadFolderIfEnabled()
            }
            scheduleCloudSettingsSync()
        }
    }

    fun setAutoCacheOnPlay(enabled: Boolean) {
        app.appScope.launch(NonCancellable) {
            preferences.setAutoCacheOnPlay(enabled)
            scheduleCloudSettingsSync()
        }
    }

    fun setDarkLiquidGlass(dark: Boolean) {
        app.appScope.launch(NonCancellable) {
            preferences.setDarkLiquidGlass(dark)
            scheduleCloudSettingsSync()
        }
    }

    fun setAppLanguage(languageCode: String) {
        app.appScope.launch(NonCancellable) {
            preferences.setAppLanguage(languageCode)
            scheduleCloudSettingsSync()
        }
    }

    fun setAccentColorArgb(argb: Long) {
        app.appScope.launch(NonCancellable) {
            preferences.setAccentColorArgb(argb)
            scheduleCloudSettingsSync()
        }
    }

    fun setSmoothTransitionEnabled(enabled: Boolean) {
        app.appScope.launch(NonCancellable) {
            preferences.setSmoothTransitionEnabled(enabled)
            scheduleCloudSettingsSync()
        }
    }

    fun setSmoothTransitionDurationSec(seconds: Int) {
        app.appScope.launch(NonCancellable) {
            preferences.setSmoothTransitionDurationSec(seconds)
            scheduleCloudSettingsSync()
        }
    }

    fun setDataSaverEnabled(enabled: Boolean) {
        FullStreamResolver.isDataSaverEnabled = enabled
        app.appScope.launch(NonCancellable) {
            preferences.setDataSaverEnabled(enabled)
            scheduleCloudSettingsSync()
        }
    }

    fun setHapticsEnabled(enabled: Boolean) {
        app.appScope.launch(NonCancellable) {
            preferences.setHapticsEnabled(enabled)
            scheduleCloudSettingsSync()
        }
    }

    fun setHapticIntensity(percent: Int) {
        app.appScope.launch(NonCancellable) {
            preferences.setHapticIntensity(percent)
            scheduleCloudSettingsSync()
        }
    }

    fun setBatterySaverEnabled(enabled: Boolean) {
        FullStreamResolver.isBatterySaverEnabled = enabled
        app.appScope.launch(NonCancellable) {
            preferences.setBatterySaverEnabled(enabled)
            scheduleCloudSettingsSync()
        }
    }

    fun setAudioFocusMode(mode: AudioFocusMode) {
        SoundLapaPlaybackService.setAudioFocusMode(mode)
        app.appScope.launch(NonCancellable) {
            preferences.setAudioFocusMode(mode)
            scheduleCloudSettingsSync()
        }
    }

    fun playNextInQueue(track: TrackEntity) {
        val online = networkObserver.refresh()
        if (!online && !isTrackPlayableOffline(track)) {
            _offlineDialogTrackTitle.value = track.title
            _showOfflineDialog.value = true
            return
        }
        viewModelScope.launch {
            audiusRepository.ensureTrackPersisted(track)
            playbackController.playNextInQueue(track)
        }
    }

    fun addToQueue(track: TrackEntity) {
        val online = networkObserver.refresh()
        if (!online && !isTrackPlayableOffline(track)) {
            _offlineDialogTrackTitle.value = track.title
            _showOfflineDialog.value = true
            return
        }
        viewModelScope.launch {
            audiusRepository.ensureTrackPersisted(track)
            playbackController.addToQueue(track)
        }
    }

    fun removeFromQueue(trackId: String) {
        playbackController.removeFromQueue(trackId)
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        playbackController.moveQueueItem(fromIndex, toIndex)
    }

    fun clearUpcomingQueue() {
        playbackController.clearUpcomingQueue()
    }

    fun toggleArtistSubscription(
        artistName: String,
        avatarUrl: String? = null,
        soundCloudUrl: String? = null,
        spotifyArtistId: String? = null,
    ) {
        val cleanName = artistName.trim()
        if (cleanName.isBlank()) return
        val key = artistSubscriptionDocId(cleanName)
        viewModelScope.launch {
            val existing = uiState.value.subscribedArtists.find { it.artistKey == key }
            if (existing != null) {
                dao.deleteArtistSubscription(key)
                cloudRepository?.removeArtistSubscription(key)
            } else {
                val now = System.currentTimeMillis()
                val entity = ArtistSubscriptionEntity(
                    artistKey = key,
                    artistName = cleanName,
                    avatarUrl = avatarUrl,
                    soundCloudUrl = soundCloudUrl,
                    spotifyArtistId = spotifyArtistId,
                    subscribedAtMs = now,
                )
                dao.upsertArtistSubscription(entity)
                cloudRepository?.upsertArtistSubscription(
                    artistName = cleanName,
                    avatarUrl = avatarUrl,
                    soundCloudUrl = soundCloudUrl,
                    spotifyArtistId = spotifyArtistId,
                    subscribedAtMs = now,
                )
            }
        }
    }

    fun loadLyricsForTrack(track: TrackEntity) {
        val current = _lyricsState.value
        if (current.trackId == track.id && (current.isLoading || current.syncedLines.isNotEmpty() || !current.plainText.isNullOrBlank())) {
            return
        }
        lyricsJob?.cancel()
        _lyricsState.value = LyricsUiState(
            trackId = track.id,
            isLoading = true,
        )
        lyricsJob = viewModelScope.launch {
            val result = runCatching {
                lyricsRepository.getTrackLyrics(
                    artist = track.artist,
                    title = track.title,
                    durationMs = track.durationMs,
                    trackId = track.id,
                    sourceUri = track.sourceUri,
                )
            }.getOrNull()
            if (result != null && result.isExplicit) {
                preferences.markTracksExplicit(listOf(track.id))
            }
            if (_lyricsState.value.trackId == track.id) {
                _lyricsState.value = if (result != null) {
                    LyricsUiState(
                        trackId = track.id,
                        isLoading = false,
                        syncedLines = result.syncedLines,
                        plainText = result.plainText,
                        isSynced = result.isSynced,
                        source = result.source,
                        notFound = false,
                    )
                } else {
                    LyricsUiState(
                        trackId = track.id,
                        isLoading = false,
                        notFound = true,
                    )
                }
            }
        }
    }

    fun clearMusicVideoForTrackChange(trackId: String?) {
        if (_musicVideoState.value.trackId != trackId) {
            musicVideoJob?.cancel()
            musicVideoProbeJob?.cancel()
            playbackController.setClipAudioMuteOverride(false)
            _musicVideoState.value = MusicVideoUiState(
                trackId = trackId,
                isVideoModeEnabled = false,
                useClipAudio = false,
            )
        }
    }

    fun probeMusicVideoAvailability(track: TrackEntity) {
        val current = _musicVideoState.value
        if (current.trackId == track.id &&
            (current.stream != null || current.unavailable || current.isCheckingAvailability || current.isLoading)
        ) {
            return
        }
        if (preferences.currentSettings().batterySaverEnabled || !networkObserver.refresh()) {
            return
        }
        musicVideoProbeJob?.cancel()
        val keepVideoMode = current.trackId == track.id && current.isVideoModeEnabled
        val keepClipAudio = current.trackId == track.id && current.useClipAudio
        _musicVideoState.value = MusicVideoUiState(
            trackId = track.id,
            isVideoModeEnabled = keepVideoMode,
            useClipAudio = keepClipAudio,
            isCheckingAvailability = true,
        )
        musicVideoProbeJob = viewModelScope.launch {
            val foundStream = kotlinx.coroutines.withContext(Dispatchers.IO) {
                runCatching {
                    FullStreamResolver.findOfficialMusicVideoStream(
                        artist = track.artist,
                        title = track.title,
                        expectedDurationMs = track.durationMs,
                        trackId = track.id,
                        sourceUri = track.sourceUri,
                    )
                }.getOrNull()
            }
            val latest = _musicVideoState.value
            if (latest.trackId == track.id && !latest.isLoading) {
                val resolvedStream = foundStream ?: latest.stream
                if (latest.isVideoModeEnabled && resolvedStream != null) {
                    val activated = MusicVideoUiState.activatedState(
                        trackId = track.id,
                        stream = resolvedStream,
                        previousSessionId = latest.clipAudioSessionId,
                    )
                    playbackController.setClipAudioMuteOverride(activated.useClipAudio)
                    _musicVideoState.value = activated
                } else {
                    _musicVideoState.value = latest.copy(
                        isCheckingAvailability = false,
                        stream = resolvedStream,
                        unavailable = foundStream == null && latest.stream == null,
                    )
                }
            }
        }
    }

    fun toggleMusicVideoMode(track: TrackEntity, enableVideo: Boolean) {
        if (!enableVideo) {
            musicVideoJob?.cancel()
            playbackController.setClipAudioMuteOverride(false)
            _musicVideoState.update {
                it.copy(
                    trackId = track.id,
                    isVideoModeEnabled = false,
                    useClipAudio = false,
                    isLoading = false,
                    clipDurationMs = 0L,
                )
            }
            return
        }
        val current = _musicVideoState.value
        val existingStream = if (current.trackId == track.id) current.stream else null
        if (existingStream != null) {
            val activated = MusicVideoUiState.activatedState(
                trackId = track.id,
                stream = existingStream,
                previousSessionId = current.clipAudioSessionId,
            )
            playbackController.setClipAudioMuteOverride(activated.useClipAudio)
            _musicVideoState.value = activated
            return
        }
        musicVideoProbeJob?.cancel()
        musicVideoJob?.cancel()
        playbackController.setClipAudioMuteOverride(false)
        val preservedSessionId = if (current.trackId == track.id) current.clipAudioSessionId else 0
        _musicVideoState.value = MusicVideoUiState(
            trackId = track.id,
            isVideoModeEnabled = true,
            useClipAudio = false,
            isLoading = true,
            isCheckingAvailability = false,
            clipAudioSessionId = preservedSessionId,
        )
        musicVideoJob = viewModelScope.launch {
            val stream = kotlinx.coroutines.withContext(Dispatchers.IO) {
                runCatching {
                    FullStreamResolver.findOfficialMusicVideoStream(
                        artist = track.artist,
                        title = track.title,
                        expectedDurationMs = track.durationMs,
                        trackId = track.id,
                        sourceUri = track.sourceUri,
                    )
                }.getOrNull()
            }
            val latest = _musicVideoState.value
            if (latest.trackId == track.id && latest.isVideoModeEnabled) {
                if (stream != null) {
                    val activated = MusicVideoUiState.activatedState(
                        trackId = track.id,
                        stream = stream,
                        previousSessionId = latest.clipAudioSessionId,
                    )
                    playbackController.setClipAudioMuteOverride(activated.useClipAudio)
                    _musicVideoState.value = activated
                } else {
                    playbackController.setClipAudioMuteOverride(false)
                    _musicVideoState.value = MusicVideoUiState(
                        trackId = track.id,
                        isVideoModeEnabled = true,
                        useClipAudio = false,
                        isLoading = false,
                        isCheckingAvailability = false,
                        stream = null,
                        unavailable = true,
                        clipDurationMs = 0L,
                        clipAudioSessionId = latest.clipAudioSessionId,
                    )
                }
            }
        }
    }

    fun toggleClipAudio(enabled: Boolean) {
        val current = _musicVideoState.value
        val stream = current.stream
        val canPlayClipAudio = current.isVideoModeEnabled &&
            stream != null &&
            stream.hasAudio
        val nextEnabled = enabled && canPlayClipAudio
        playbackController.setClipAudioMuteOverride(nextEnabled)
        _musicVideoState.update { state ->
            val restartClipFromStart = nextEnabled && !state.useClipAudio
            state.copy(
                useClipAudio = nextEnabled,
                clipAudioSessionId = if (restartClipFromStart) state.clipAudioSessionId + 1 else state.clipAudioSessionId,
            )
        }
    }

    fun selectWaveFilter(
        genre: String? = null,
        language: String? = null,
        vocals: String? = null,
        mood: String? = null,
    ) {
        val current = waveUiState.value.config
        val updated = current.copy(
            genre = genre ?: current.genre,
            language = language ?: current.language,
            vocals = vocals ?: current.vocals,
            mood = mood ?: current.mood,
            hasCustomized = true,
        )
        waveLoadJob?.cancel()
        waveLoadJob = viewModelScope.launch {
            preferences.setWaveFilterConfig(updated)
            buildAndStartWaveQueue(config = updated, startPlayback = _waveSessionActive.value)
        }
    }

    fun resetWaveToPersonalTaste() {
        waveLoadJob?.cancel()
        waveLoadJob = viewModelScope.launch {
            val allTracks = kotlinx.coroutines.withContext(Dispatchers.IO) { dao.getAllTracksOnce() }
            val settings = preferences.currentSettings()
            val derived = RecommendationEngine.deriveDefaultWaveConfig(
                allTracks = allTracks,
                listeningHistory = settings.listeningHistory,
                appLanguage = settings.appLanguage,
            )
            preferences.setWaveFilterConfig(derived.copy(hasCustomized = false))
            if (_waveSessionActive.value) {
                buildAndStartWaveQueue(config = derived, startPlayback = true)
            }
        }
    }

    fun toggleWavePlayPause() {
        val state = waveUiState.value
        val activeTrack = state.currentWaveTrack
        if (state.isWaveActive && activeTrack != null && playbackController.isTrackActiveInController(activeTrack.id)) {
            playbackController.togglePlayPause(activeTrack)
            return
        }
        waveLoadJob?.cancel()
        waveLoadJob = viewModelScope.launch {
            buildAndStartWaveQueue(config = state.config, startPlayback = true)
        }
    }

    fun skipWaveNext() {
        val state = waveUiState.value
        if (state.isWaveActive && playbackController.queueTracks.value.size > 1) {
            val currentIdx = playbackController.queueTracks.value.indexOfFirst {
                it.id == playbackController.playbackState.value.currentTrackId
            }
            if (currentIdx >= 0 && currentIdx < playbackController.queueTracks.value.lastIndex) {
                playbackController.skipNext()
                return
            }
        }
        waveLoadJob?.cancel()
        waveLoadJob = viewModelScope.launch {
            buildAndStartWaveQueue(config = state.config, startPlayback = true)
        }
    }

    private suspend fun buildAndStartWaveQueue(
        config: WaveFilterConfig,
        startPlayback: Boolean,
    ) {
        val online = networkObserver.refresh()
        val settings = preferences.currentSettings()
        var allTracks = kotlinx.coroutines.withContext(Dispatchers.IO) { dao.getAllTracksOnce() }
        val currentPlayedId = playbackController.playbackState.value.currentTrackId
        val localPicked = RecommendationEngine.computeWaveTracks(
            allTracks = allTracks,
            listeningHistory = settings.listeningHistory,
            config = config,
            excludeIds = if (startPlayback && currentPlayedId != null) setOf(currentPlayedId) else emptySet(),
            limit = 24,
        )

        val waveContextTitle = com.example.ui.i18n.stringsForLanguage(settings.appLanguage).wave.title
        if (startPlayback && localPicked.isNotEmpty()) {
            _waveSessionActive.value = true
            playbackController.playTrack(localPicked.first(), localPicked, contextTitle = waveContextTitle)
        }

        if (online && !settings.dataSaverEnabled) {
            _waveIsLoading.value = true
            try {
                val queries = RecommendationEngine.buildWaveOnlineQueries(
                    config = config,
                    listeningHistory = settings.listeningHistory,
                    allTracks = allTracks,
                )
                val fetched = runCatching {
                    audiusRepository.fetchSimilarTracksForQueries(queries, perQueryLimit = 5)
                }.getOrDefault(emptyList())
                if (fetched.isNotEmpty()) {
                    allTracks = kotlinx.coroutines.withContext(Dispatchers.IO) { dao.getAllTracksOnce() }
                    val refreshed = RecommendationEngine.computeWaveTracks(
                        allTracks = allTracks,
                        listeningHistory = settings.listeningHistory,
                        config = config,
                        limit = 26,
                    )
                    if (startPlayback) {
                        if (!_waveSessionActive.value && refreshed.isNotEmpty()) {
                            _waveSessionActive.value = true
                            playbackController.playTrack(refreshed.first(), refreshed, contextTitle = waveContextTitle)
                        } else if (refreshed.isNotEmpty()) {
                            playbackController.appendTracksToQueue(refreshed)
                        }
                    }
                }
            } finally {
                _waveIsLoading.value = false
            }
        } else if (startPlayback && localPicked.isEmpty() && !online) {
            _offlineDialogTrackTitle.value = null
            _showOfflineDialog.value = true
        }
    }

    private fun refillWaveQueueIfNeeded() {
        if (waveRefillJob?.isActive == true) return
        waveRefillJob = viewModelScope.launch {
            val online = networkObserver.refresh()
            val settings = preferences.currentSettings()
            val config = waveUiState.value.config
            val currentQueueIds = playbackController.queueTracks.value.mapTo(HashSet()) { it.id }

            if (online && !settings.dataSaverEnabled) {
                val allBefore = kotlinx.coroutines.withContext(Dispatchers.IO) { dao.getAllTracksOnce() }
                val queries = RecommendationEngine.buildWaveOnlineQueries(
                    config = config,
                    listeningHistory = settings.listeningHistory,
                    allTracks = allBefore,
                )
                runCatching {
                    audiusRepository.fetchSimilarTracksForQueries(queries, perQueryLimit = 4)
                }
            }
            val allAfter = kotlinx.coroutines.withContext(Dispatchers.IO) { dao.getAllTracksOnce() }
            val more = RecommendationEngine.computeWaveTracks(
                allTracks = allAfter,
                listeningHistory = settings.listeningHistory,
                config = config,
                excludeIds = currentQueueIds,
                limit = 12,
            )
            if (more.isNotEmpty()) {
                playbackController.appendTracksToQueue(more)
            }
        }
    }

    fun claimUsername(rawHandle: String) {
        val repo = cloudRepository ?: return
        if (isGuestMode || cloudUserId.isNullOrBlank()) return
        val normalized = normalizeUsernameHandle(rawHandle)
        if (!isValidUsernameHandle(normalized)) {
            _socialState.update { it.copy(statusMessage = "invalid_username") }
            return
        }
        viewModelScope.launch {
            _socialState.update { it.copy(isClaimingUsername = true, statusMessage = null) }
            val cs = _cloudSyncState.value
            val result = repo.claimOrUpdateUsername(
                rawHandle = normalized,
                displayName = cs.displayName ?: "SoundLap User",
                photoUrl = cs.photoUrl,
                bio = cs.bio,
            )
            result.onSuccess { claimedHandle ->
                preferences.setCustomUsername(claimedHandle)
                _cloudSyncState.update { it.copy(username = claimedHandle) }
                val uid = cloudUserId
                _socialState.update { state ->
                    val updatedSelf = state.currentPublicProfile?.copy(username = claimedHandle)
                        ?: uid?.let {
                            CloudPublicProfile(
                                docId = it,
                                userId = it,
                                displayName = cs.displayName ?: "SoundLap User",
                                username = claimedHandle,
                                photoUrl = cs.photoUrl,
                                bio = cs.bio,
                                isDeveloper = cs.isVerifiedDeveloper,
                                isPublic = true,
                            )
                        }
                    state.copy(
                        currentPublicProfile = updatedSelf,
                        isClaimingUsername = false,
                        statusMessage = "@$claimedHandle",
                    )
                }
            }.onFailure { err ->
                _socialState.update {
                    it.copy(
                        isClaimingUsername = false,
                        statusMessage = err.localizedMessage ?: "Error",
                    )
                }
            }
        }
    }

    fun searchSocialUsers(query: String) {
        val repo = cloudRepository ?: return
        if (isGuestMode || cloudUserId.isNullOrBlank()) return
        viewModelScope.launch {
            _socialState.update { it.copy(isSearching = true, statusMessage = null) }
            val result = repo.searchUsersByUsername(query)
            _socialState.update { state ->
                result.fold(
                    onSuccess = { list ->
                        state.copy(isSearching = false, searchResults = list)
                    },
                    onFailure = { err ->
                        state.copy(
                            isSearching = false,
                            statusMessage = err.localizedMessage ?: "Error",
                        )
                    },
                )
            }
        }
    }

    fun inspectPublicProfile(profile: CloudPublicProfile) {
        _socialState.update { it.copy(inspectedProfile = profile) }
        val repo = cloudRepository ?: return
        if (isGuestMode || cloudUserId.isNullOrBlank()) return
        viewModelScope.launch {
            repo.getPublicProfile(profile.userId).getOrNull()?.let { fresh ->
                _socialState.update { current ->
                    if (current.inspectedProfile?.userId == fresh.userId) {
                        current.copy(inspectedProfile = fresh)
                    } else {
                        current
                    }
                }
            }
        }
    }

    fun openCreatorProfile() {
        val fallback = CloudPublicProfile(
            docId = "creator_tylerw1nston",
            userId = "creator_tylerw1nston",
            displayName = "TylerW1nston",
            username = "tylerw1nston",
            bio = "SoundLap creator & Android developer",
            isDeveloper = true,
            isPublic = true,
        )
        _socialState.update {
            it.copy(
                inspectedProfile = fallback,
                isLoadingCreatorProfile = true,
            )
        }
        val repo = cloudRepository
        if (isGuestMode || repo == null || cloudUserId.isNullOrBlank()) {
            _socialState.update { it.copy(isLoadingCreatorProfile = false) }
            return
        }
        viewModelScope.launch {
            val loaded = repo.getDeveloperPublicProfile().getOrNull()
            _socialState.update { current ->
                current.copy(
                    isLoadingCreatorProfile = false,
                    inspectedProfile = loaded ?: current.inspectedProfile,
                )
            }
        }
    }

    fun clearInspectedPublicProfile() {
        _socialState.update { it.copy(inspectedProfile = null) }
    }

    fun addFriend(profile: CloudPublicProfile) {
        val repo = cloudRepository ?: return
        if (isGuestMode || cloudUserId.isNullOrBlank()) return
        viewModelScope.launch {
            repo.addFriend(profile).onSuccess { friend ->
                _socialState.update { state ->
                    val updated = (listOf(friend) + state.friends.filterNot { it.friendUid == friend.friendUid })
                        .sortedWith(compareByDescending<CloudFriend> { it.isDeveloper }.thenBy { it.displayName.lowercase() })
                    state.copy(friends = updated, statusMessage = null)
                }
            }.onFailure { err ->
                _socialState.update { it.copy(statusMessage = err.localizedMessage) }
            }
        }
    }

    fun removeFriend(friendUid: String) {
        val repo = cloudRepository ?: return
        if (isGuestMode || cloudUserId.isNullOrBlank()) return
        viewModelScope.launch {
            repo.removeFriend(friendUid).onSuccess {
                _socialState.update { state ->
                    state.copy(friends = state.friends.filterNot { it.friendUid == friendUid })
                }
            }
        }
    }

    fun loadFriends() {
        val repo = cloudRepository ?: return
        if (isGuestMode || cloudUserId.isNullOrBlank()) return
        viewModelScope.launch {
            repo.getFriends().onSuccess { list ->
                _socialState.update { it.copy(friends = list) }
            }
        }
    }

    fun refreshCacheSize() {
        viewModelScope.launch {
            _cacheSizeBytes.value = cacheManager.calculateCacheSizeBytes()
        }
    }

    fun clearAppCache() {
        if (_isClearingCache.value) return
        viewModelScope.launch {
            _isClearingCache.value = true
            _cacheStatusMessage.value = null
            val freedPrefetch = kotlinx.coroutines.withContext(Dispatchers.IO) {
                AudioPrefetchCache.clearCache(app)
            }
            val freed = cacheManager.clearAppCache() + freedPrefetch
            audiusRepository.clearSearchCache()
            val remaining = cacheManager.calculateCacheSizeBytes()
            _cacheSizeBytes.value = remaining
            refreshLocalCacheStats()
            _isClearingCache.value = false
            _cacheStatusMessage.value = formatFileSize(freed)
        }
    }

    fun clearCacheMessage() {
        _cacheStatusMessage.value = null
    }

    fun importExternalPlaylistFromInput(input: String, playlistName: String?) {
        if (_isImportingPlaylist.value) return
        viewModelScope.launch {
            _isImportingPlaylist.value = true
            _importStatusMessage.value = null
            val result = playlistImporter.importFromInput(input, playlistName)
            result.onSuccess { summary ->
                pushImportedPlaylistToCloud(summary.playlistId)
            }
            _isImportingPlaylist.value = false
            _importStatusMessage.value = result.fold(
                onSuccess = { summary ->
                    "✓ «${summary.playlistName}» (${summary.sourceLabel}): ${summary.importedCount} из ${summary.totalFound}"
                },
                onFailure = { err ->
                    err.message ?: "Не удалось импортировать плейлист"
                },
            )
            refreshCacheSize()
        }
    }

    fun importExternalPlaylistFromFile(uri: Uri, playlistName: String?) {
        if (_isImportingPlaylist.value) return
        viewModelScope.launch {
            _isImportingPlaylist.value = true
            _importStatusMessage.value = null
            val result = playlistImporter.importFromFileUri(uri, playlistName)
            result.onSuccess { summary ->
                pushImportedPlaylistToCloud(summary.playlistId)
            }
            _isImportingPlaylist.value = false
            _importStatusMessage.value = result.fold(
                onSuccess = { summary ->
                    "✓ «${summary.playlistName}» (${summary.sourceLabel}): ${summary.importedCount} из ${summary.totalFound}"
                },
                onFailure = { err ->
                    err.message ?: "Не удалось прочитать файл плейлиста"
                },
            )
            refreshCacheSize()
        }
    }

    private suspend fun pushImportedPlaylistToCloud(playlistId: String) {
        val repo = cloudRepository ?: return
        val playlist = dao.getAllPlaylistsOnce().find { it.id == playlistId } ?: return
        val refs = dao.getAllPlaylistRefsOnce().filter { it.playlistId == playlistId }
        val allTracks = dao.getAllTracksOnce()
        repo.pushPlaylistWithTracks(playlist, allTracks, refs)
    }

    fun clearImportStatus() {
        _importStatusMessage.value = null
    }

    fun clearUploadMessage() {
        _uploadState.update { it.copy(statusMessage = null) }
    }

    private fun scheduleStatsAutoBackup() {
        statsBackupJob?.cancel()
        statsBackupJob = app.appScope.launch(NonCancellable) {
            delay(3500L)
            val s = preferences.currentSettings()
            val res = ListeningStatsBackupManager.saveAutoBackup(
                context = app,
                events = s.listeningEvents,
                history = s.listeningHistory,
            )
            if (res.success) {
                _statsBackupState.update {
                    it.copy(
                        lastSavedAtMs = res.savedAtMs,
                        locationLabel = res.locationLabel,
                    )
                }
            }
        }
    }

    fun restoreListeningStatsFromUri(uri: Uri) {
        if (_statsBackupState.value.isRestoring) return
        viewModelScope.launch {
            _statsBackupState.update { it.copy(isRestoring = true, restoreStatusMessage = null) }
            val parsed = ListeningStatsBackupManager.readBackupFromUri(app, uri)
            if (parsed == null) {
                _statsBackupState.update {
                    it.copy(isRestoring = false, restoreStatusMessage = "invalid")
                }
                return@launch
            }
            val importedCount = preferences.mergeImportedStatsBackup(parsed)
            val s = preferences.currentSettings()
            ListeningStatsBackupManager.saveAutoBackup(app, s.listeningEvents, s.listeningHistory)
            if (!isGuestMode && cloudRepository != null && !cloudUserId.isNullOrBlank()) {
                cloudRepository.syncSearchHistoryAndListeningStats(
                    searchHistory = s.searchHistory,
                    listeningEvents = s.listeningEvents,
                )
            }
            _statsBackupState.update {
                it.copy(
                    isRestoring = false,
                    restoreStatusMessage = "ok:$importedCount",
                )
            }
        }
    }

    fun clearStatsRestoreStatus() {
        _statsBackupState.update { it.copy(restoreStatusMessage = null) }
    }

    fun prewarmExplicitBadges(tracks: List<TrackEntity>) {
        if (preferences.currentSettings().dataSaverEnabled) return
        val existingExplicit = preferences.currentSettings().explicitTrackIds
        val candidates = tracks
            .filter { track ->
                track.id !in existingExplicit &&
                    !ExplicitContentDetector.isExplicit(track, existingExplicit) &&
                    checkedUpfrontExplicitIds.add(track.id)
            }
            .take(8)
        if (candidates.isEmpty()) return
        explicitPrewarmJob?.cancel()
        explicitPrewarmJob = app.appScope.launch(Dispatchers.IO) {
            val foundIds = candidates.map { track ->
                async {
                    val isExp = runCatching {
                        SpotifyCatalogClient.checkUpfrontExplicitFlag(track.artist, track.title)
                    }.getOrDefault(false)
                    if (isExp) {
                        ExplicitContentDetector.markPreDetectedExplicit(track.id)
                        track.id
                    } else {
                        null
                    }
                }
            }.awaitAll().filterNotNull()
            if (foundIds.isNotEmpty()) {
                preferences.markTracksExplicit(foundIds)
            }
        }
    }

    override fun onCleared() {
        presenceHeartbeatJob?.cancel()
        publishPresenceAndNowPlaying(isOnline = false, force = true)
        playbackController.setClipAudioMuteOverride(false)
        networkObserver.stop()
        playbackController.onOfflinePlaybackBlocked = null
        playbackController.disconnect()
        super.onCleared()
    }

    private companion object {
        val SEARCH_NORM_REGEX = Regex("[^\\p{L}\\p{Nd}]")
        val SEARCH_ARTIST_SPLIT_REGEX = Regex("\\s*(?:,|&|feat\\.|ft\\.|\\s+feat\\s+)\\s*", RegexOption.IGNORE_CASE)
    }
}
