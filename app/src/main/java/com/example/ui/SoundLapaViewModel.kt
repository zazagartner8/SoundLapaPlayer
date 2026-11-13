package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.SoundLapaApplication
import com.example.audio.AutoSyncResult
import com.example.audio.ImportPreviewMetadata
import com.example.audio.RecommendationEngine
import com.example.data.local.PlaylistEntity
import com.example.data.local.PlaylistTrackCrossRef
import com.example.data.local.PlaylistWithTracks
import com.example.data.local.SoundLapaSettings
import com.example.data.local.TrackEntity
import com.example.data.remote.ArtistAlbumItem
import com.example.data.remote.FullStreamResolver
import com.example.data.remote.NetworkConnectivityObserver
import com.example.data.remote.SearchArtistProfile
import com.example.data.remote.isTrackPlayableOffline
import com.example.player.PlaybackUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
    val recommendedTracks: List<TrackEntity> = emptyList(),
    val recommendationSeedArtists: List<String> = emptyList(),
    val isRefreshingRecommendations: Boolean = false,
    val filteredTracks: List<TrackEntity> = emptyList(),
    val selectedFilter: String = "Все",
    val isSearchActive: Boolean = false,
    val searchQuery: String = "",
    val isSearching: Boolean = false,
    val isRefreshingTrending: Boolean = false,
    val currentTrackId: String? = null,
    val isPlaying: Boolean = false,
    val isOnline: Boolean = true,
    val offlineTracksCount: Int = 0,
)

@Immutable
data class MainUiState(
    val tracks: List<TrackEntity> = emptyList(),
    val filteredTracks: List<TrackEntity> = emptyList(),
    val searchScreenResults: List<TrackEntity> = emptyList(),
    val searchArtistProfiles: List<SearchArtistProfile> = emptyList(),
    val playlists: List<PlaylistWithTracks> = emptyList(),
    val selectedFilter: String = "Все",
    val searchQuery: String = "",
    val isSearchActive: Boolean = false,
    val playback: PlaybackUiState = PlaybackUiState(),
    val currentTrack: TrackEntity? = null,
    val settings: SoundLapaSettings = SoundLapaSettings(),
    val upload: UploadUiState = UploadUiState(),
    val audius: AudiusUiState = AudiusUiState(),
    val cacheSizeBytes: Long = 0L,
    val isClearingCache: Boolean = false,
    val cacheStatusMessage: String? = null,
    val isImportingPlaylist: Boolean = false,
    val importStatusMessage: String? = null,
    val isOnline: Boolean = true,
    val offlineTracksCount: Int = 0,
    val showOfflineDialog: Boolean = false,
    val offlineDialogTrackTitle: String? = null,
)

@Immutable
data class ArtistProfileUiState(
    val isVisible: Boolean = false,
    val artistName: String = "",
    val avatarUrl: String? = null,
    val accentColorHex: Long = 0xFF10B981L,
    val secondaryColorHex: Long = 0xFF5CE1E6L,
    val isLoading: Boolean = false,
    val tracks: List<TrackEntity> = emptyList(),
    val albums: List<ArtistAlbumItem> = emptyList(),
    val selectedAlbum: ArtistAlbumItem? = null,
    val selectedAlbumTracks: List<TrackEntity> = emptyList(),
    val isLoadingAlbum: Boolean = false,
)

class SoundLapaViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as SoundLapaApplication
    private val dao = app.dao
    private val preferences = app.preferences
    private val cacheManager = app.audioCacheManager
    private val audiusRepository = app.audiusRepository
    private val playlistImporter = app.playlistImporter
    val playbackController = app.playbackController
    val playbackPositionMs: StateFlow<Long> = playbackController.positionMs

    private val _selectedFilter = MutableStateFlow("Все")
    private val _searchQuery = MutableStateFlow("")
    private val _isSearchActive = MutableStateFlow(false)
    private val _uploadState = MutableStateFlow(UploadUiState())
    private val _audiusState = MutableStateFlow(AudiusUiState())
    private val _isRefreshingRecommendations = MutableStateFlow(false)
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

    private var searchJob: Job? = null
    private var artistProfileJob: Job? = null
    private var albumLoadJob: Job? = null
    private var reconnectedBannerJob: Job? = null

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
        0xFFFF5500L to 0xFF9C2B00L,
        0xFF5CE1E6L to 0xFFA78BFAL,
        0xFFFF9248L to 0xFFFB7185L,
        0xFF34D399L to 0xFF5CE1E6L,
    )

    private data class TracksFilterSnapshot(
        val allTracks: List<TrackEntity>,
        val quickPickRows: List<List<TrackEntity>>,
        val trendingCarousel: List<TrackEntity>,
        val recommendedTracks: List<TrackEntity>,
        val recommendationSeedArtists: List<String>,
        val filteredTracks: List<TrackEntity>,
        val searchScreenResults: List<TrackEntity>,
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
    ) { values ->
        val cacheBytes = values[0] as Long
        val clearing = values[1] as Boolean
        val cacheMsg = values[2] as String?
        val importing = values[3] as Boolean
        val importMsg = values[4] as String?
        @Suppress("UNCHECKED_CAST")
        val offline = values[5] as Triple<Boolean, Boolean, String?>
        UtilityStatusSnapshot(
            cacheSizeBytes = cacheBytes,
            isClearingCache = clearing,
            cacheStatusMessage = cacheMsg,
            isImportingPlaylist = importing,
            importStatusMessage = importMsg,
            isOnline = offline.first,
            showOfflineDialog = offline.second,
            offlineDialogTrackTitle = offline.third,
        )
    }

    private val tracksAndFiltersFlow = combine(
        dao.observeAllTracks(),
        _selectedFilter,
        _searchQuery,
        _isSearchActive,
        _audiusState,
        preferences.settings,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val tracks = values[0] as List<TrackEntity>
        val filter = values[1] as String
        val query = values[2] as String
        val searchActive = values[3] as Boolean
        val audius = values[4] as AudiusUiState
        val settings = values[5] as SoundLapaSettings

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

        val recResult = RecommendationEngine.computeRecommendations(
            allTracks = tracks,
            listeningHistory = settings.listeningHistory,
            searchHistory = settings.searchHistory,
            limit = tracks.size.coerceAtLeast(12),
        )
        val recentTracks = settings.listeningHistory
            .sortedByDescending { it.lastPlayedAtMs }
            .mapNotNull { entry -> persistedById[entry.trackId] }
            .distinctBy { it.id }
            .take(4)
        val quickPickRows = recentTracks.chunked(2)
        val trendingCarousel = RecommendationEngine.computeTrendingSimilarTracks(
            allTracks = tracks,
            listeningHistory = settings.listeningHistory,
            searchHistory = settings.searchHistory,
            limit = 6,
        )
        val recommendedTracks = recResult.tracks.take(12)
        val finalHomeTracks = if (!searchActive && filter == "Все") {
            recResult.tracks
        } else {
            mergedHome
        }

        TracksFilterSnapshot(
            allTracks = tracks,
            quickPickRows = quickPickRows,
            trendingCarousel = trendingCarousel,
            recommendedTracks = recommendedTracks,
            recommendationSeedArtists = recResult.topSeedArtists,
            filteredTracks = finalHomeTracks,
            searchScreenResults = searchScreenMatches,
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
        networkObserver.isOnline,
    ) { values ->
        val snapshot = values[0] as TracksFilterSnapshot
        val searchActive = values[1] as Boolean
        val query = values[2] as String
        val audius = values[3] as AudiusUiState
        val playback = values[4] as PlaybackUiState
        val refreshingRecs = values[5] as Boolean
        val isOnline = values[6] as Boolean
        val offlineCount = snapshot.allTracks.count { isTrackPlayableOffline(it) }
        HomeUiState(
            quickPickRows = snapshot.quickPickRows,
            trendingCarousel = snapshot.trendingCarousel,
            recommendedTracks = snapshot.recommendedTracks,
            recommendationSeedArtists = snapshot.recommendationSeedArtists,
            isRefreshingRecommendations = refreshingRecs,
            filteredTracks = snapshot.filteredTracks,
            selectedFilter = snapshot.selectedFilter,
            isSearchActive = searchActive,
            searchQuery = query,
            isSearching = audius.isSearching,
            isRefreshingTrending = audius.isRefreshingTrending,
            currentTrackId = playback.currentTrackId,
            isPlaying = playback.isPlaying,
            isOnline = isOnline,
            offlineTracksCount = offlineCount,
        )
    }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
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
            val playlistTracks = refsByPlaylist[playlist.id].orEmpty()
                .sortedWith(compareBy<PlaylistTrackCrossRef> { it.position }.thenBy { it.addedAt })
                .mapNotNull { ref -> tracksById[ref.trackId] }
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
    ) { values ->
        val snapshot = values[0] as TracksFilterSnapshot
        @Suppress("UNCHECKED_CAST")
        val playlists = values[1] as List<PlaylistWithTracks>
        val playback = values[2] as PlaybackUiState
        val settings = values[3] as SoundLapaSettings
        val upload = values[4] as UploadUiState
        val audius = values[5] as AudiusUiState
        val utility = values[6] as UtilityStatusSnapshot

        val tracks = snapshot.allTracks
        val currentTrack = tracks.find { it.id == playback.currentTrackId }
            ?: audius.searchResults.find { it.id == playback.currentTrackId }
            ?: playbackController.findTrackById(playback.currentTrackId)
            ?: tracks.find { it.id == settings.lastPlayedTrackId }
            ?: tracks.firstOrNull()

        val artistMatches = buildSearchArtistMatches(
            rawQuery = _searchQuery.value,
            remoteProfiles = audius.searchArtistProfiles,
            allTracks = tracks + audius.searchResults,
        )

        val offlineCount = tracks.count { isTrackPlayableOffline(it) }

        MainUiState(
            tracks = tracks,
            filteredTracks = snapshot.filteredTracks,
            searchScreenResults = snapshot.searchScreenResults,
            searchArtistProfiles = artistMatches,
            playlists = playlists,
            selectedFilter = snapshot.selectedFilter,
            searchQuery = _searchQuery.value,
            isSearchActive = _isSearchActive.value,
            playback = playback,
            currentTrack = currentTrack,
            settings = settings,
            upload = upload,
            audius = audius,
            cacheSizeBytes = utility.cacheSizeBytes,
            isClearingCache = utility.isClearingCache,
            cacheStatusMessage = utility.cacheStatusMessage,
            isImportingPlaylist = utility.isImportingPlaylist,
            importStatusMessage = utility.importStatusMessage,
            isOnline = utility.isOnline,
            offlineTracksCount = offlineCount,
            showOfflineDialog = utility.showOfflineDialog,
            offlineDialogTrackTitle = utility.offlineDialogTrackTitle,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = MainUiState(settings = preferences.currentSettings()),
    )

    init {
        FullStreamResolver.isDataSaverEnabled = preferences.currentSettings().dataSaverEnabled
        networkObserver.start()
        playbackController.onOfflinePlaybackBlocked = { blockedTitle ->
            _offlineDialogTrackTitle.value = blockedTitle
            _showOfflineDialog.value = true
        }
        playbackController.connect()
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
                    if (!initialPrefetchDone && tracks.isNotEmpty() && !preferences.currentSettings().dataSaverEnabled) {
                        initialPrefetchDone = true
                        if (networkObserver.refresh()) {
                            val lastId = preferences.currentSettings().lastPlayedTrackId
                            val priorityTracks = buildList {
                                tracks.find { it.id == lastId }?.let { add(it) }
                                tracks.filterNot { it.id == lastId }.take(2).forEach { add(it) }
                            }
                            FullStreamResolver.prefetchTracks(priorityTracks, dao)
                        }
                    }
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
        refreshCacheSize()
        viewModelScope.launch {
            val existingCount = kotlinx.coroutines.withContext(Dispatchers.IO) {
                dao.getAllTracksOnce().size
            }
            if (existingCount > 6) {
                delay(1800L)
            }
            refreshAudiusTrending()
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
        val normQuery = trimmed.lowercase().replace(Regex("[^\\p{L}\\p{Nd}]"), "")
        if (normQuery.isBlank()) return emptyList()

        val merged = LinkedHashMap<String, SearchArtistProfile>()
        remoteProfiles.forEach { profile ->
            val key = profile.name.lowercase().replace(Regex("[^\\p{L}\\p{Nd}]"), "")
            if (key.isNotBlank()) {
                merged.putIfAbsent(key, profile)
            }
        }

        // Supplement with local track artists that match the query
        allTracks.forEach { track ->
            val primaryArtist = track.artist
                .split(Regex("\\s*(?:,|&|feat\\.|ft\\.|\\s+feat\\s+)\\s*", RegexOption.IGNORE_CASE))
                .firstOrNull()
                ?.trim()
                .orEmpty()
            if (primaryArtist.isBlank()) return@forEach
            val key = primaryArtist.lowercase().replace(Regex("[^\\p{L}\\p{Nd}]"), "")
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
                        sourceLabel = "SoundLapa",
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
            val normName = profile.name.lowercase().replace(Regex("[^\\p{L}\\p{Nd}]"), "")
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

    fun refreshAudiusTrending() {
        if (_audiusState.value.isRefreshingTrending) return
        if (!networkObserver.refresh()) return
        viewModelScope.launch {
            _audiusState.update { it.copy(isRefreshingTrending = true) }
            audiusRepository.fetchTrendingTracks()
            _audiusState.update { it.copy(isRefreshingTrending = false) }
            refreshCacheSize()
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
        _requestedLibrarySection.value = 2
    }

    fun consumeRequestedLibrarySection() {
        _requestedLibrarySection.value = null
    }

    fun refreshConnectivity(): Boolean = networkObserver.refresh()

    fun playOrToggleTrack(track: TrackEntity, customQueue: List<TrackEntity>? = null) {
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
            playbackController.playTrack(track, queue)

            viewModelScope.launch {
                audiusRepository.ensureTrackPersisted(track)
                val settings = uiState.value.settings
                if (online && !track.isCachedOffline && settings.autoCacheOnPlay && !settings.dataSaverEnabled) {
                    if (track.sourceUri?.startsWith("http") == true) {
                        audiusRepository.cacheTrackAudioIfNeeded(track)
                    } else {
                        cacheManager.toggleOfflineCache(track)
                    }
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
            .split(Regex("\\s*(?:,|&|feat\\.|ft\\.|\\s+feat\\s+)\\s*", RegexOption.IGNORE_CASE))
            .firstOrNull()
            ?.trim()
            ?.ifBlank { artistName.trim() }
            ?: return
        if (cleanName.isBlank()) return

        artistProfileJob?.cancel()
        albumLoadJob?.cancel()

        val currentTracks = uiState.value.tracks
        val localMatches = buildList {
            if (seedTrack != null) add(seedTrack)
            currentTracks.forEach { t ->
                if (t.id != seedTrack?.id && audiusRepository.isTrackByArtist(t, listOf(cleanName, artistName))) {
                    add(t)
                }
            }
        }

        val online = networkObserver.refresh()
        _artistProfileState.value = ArtistProfileUiState(
            isVisible = true,
            artistName = cleanName,
            avatarUrl = seedTrack?.coverArtPath ?: localMatches.firstOrNull()?.coverArtPath,
            accentColorHex = seedTrack?.accentColorHex ?: localMatches.firstOrNull()?.accentColorHex ?: 0xFF10B981L,
            secondaryColorHex = seedTrack?.secondaryColorHex ?: localMatches.firstOrNull()?.secondaryColorHex ?: 0xFF5CE1E6L,
            isLoading = online,
            tracks = localMatches,
            albums = emptyList(),
        )

        if (!online) return

        artistProfileJob = viewModelScope.launch {
            val catalog = runCatching {
                audiusRepository.fetchArtistCatalog(
                    artistName = cleanName,
                    seedTrack = seedTrack,
                    knownSpotifyArtistId = spotifyId,
                    knownSoundCloudUrl = soundCloudUrl,
                    onPartialResult = { partial ->
                        _artistProfileState.update { current ->
                            if (!current.isVisible || !current.artistName.equals(cleanName, ignoreCase = true)) {
                                current
                            } else {
                                current.copy(
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
        val tracks = _artistProfileState.value.tracks
        if (tracks.isEmpty()) return
        val queue = if (shuffled) tracks.shuffled() else tracks
        playOrToggleTrack(queue.first(), queue)
    }

    fun playAlbumTracks(tracks: List<TrackEntity>, startTrack: TrackEntity? = null) {
        if (tracks.isEmpty()) return
        val target = startTrack ?: tracks.first()
        playOrToggleTrack(target, tracks)
    }

    fun playPlaylist(playlist: PlaylistWithTracks, shuffled: Boolean = false) {
        if (playlist.tracks.isEmpty()) return
        val queue = if (shuffled) playlist.tracks.shuffled() else playlist.tracks
        playOrToggleTrack(queue.first(), queue)
    }

    fun toggleLike(track: TrackEntity) {
        viewModelScope.launch {
            audiusRepository.ensureTrackPersisted(track)
            val nextLiked = !track.isLiked
            val nextCount = (track.likeCount + if (nextLiked) 1 else -1).coerceAtLeast(0)
            dao.updateLike(track.id, nextLiked, nextCount)
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
        }
    }

    fun updateTrackDetails(
        track: TrackEntity,
        newTitle: String,
        newArtist: String,
        newCoverUri: Uri?,
        clearCover: Boolean,
    ) {
        if (!track.isUserUpload) return
        viewModelScope.launch {
            audiusRepository.ensureTrackPersisted(track)
            val finalCoverPath = when {
                clearCover -> null
                newCoverUri != null -> cacheManager.saveCustomCover(track.id, newCoverUri) ?: track.coverArtPath
                else -> track.coverArtPath
            }
            dao.updateTrackMetadata(
                id = track.id,
                title = newTitle.trim().ifBlank { track.title },
                artist = newArtist.trim().ifBlank { track.artist },
                coverArtPath = finalCoverPath,
            )
        }
    }

    fun createPlaylist(name: String, description: String = "", initialTrack: TrackEntity? = null) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val id = "pl_${UUID.randomUUID()}"
            dao.upsertPlaylist(
                PlaylistEntity(
                    id = id,
                    name = trimmed,
                    description = description.trim(),
                ),
            )
            if (initialTrack != null) {
                audiusRepository.ensureTrackPersisted(initialTrack)
                dao.addTrackToPlaylist(PlaylistTrackCrossRef(playlistId = id, trackId = initialTrack.id))
            }
        }
    }

    fun deletePlaylist(playlistId: String) {
        viewModelScope.launch {
            dao.deletePlaylist(playlistId)
        }
    }

    fun addTrackToPlaylist(playlistId: String, track: TrackEntity) {
        viewModelScope.launch {
            audiusRepository.ensureTrackPersisted(track)
            val nextPos = dao.getMaxPlaylistTrackPosition(playlistId) + 1
            dao.addTrackToPlaylist(
                PlaylistTrackCrossRef(
                    playlistId = playlistId,
                    trackId = track.id,
                    addedAt = System.currentTimeMillis(),
                    position = nextPos,
                ),
            )
        }
    }

    fun removeTrackFromPlaylist(playlistId: String, trackId: String) {
        viewModelScope.launch {
            dao.removeTrackFromPlaylist(playlistId, trackId)
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
            uris.forEachIndexed { idx, uri ->
                val palette = uploadPalettes[idx % uploadPalettes.size]
                val imported = cacheManager.importTrackFromUri(
                    uri = uri,
                    genre = "Мои треки",
                    accentColorHex = palette.first,
                    secondaryColorHex = palette.second,
                    isAutoImported = false,
                )
                if (imported != null) successCount++
            }
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
        syncAutoUploadFolderNow(Uri.parse(uriString))
    }

    fun syncAutoUploadFolderNow(explicitUri: Uri? = null) {
        val uri = explicitUri
            ?: uiState.value.settings.autoUploadTreeUri?.let { Uri.parse(it) }
            ?: return

        viewModelScope.launch {
            _uploadState.update { it.copy(isSyncingFolder = true) }
            val result = cacheManager.scanAutoUploadFolder(uri)
            preferences.setLastSyncTimestamp(System.currentTimeMillis())
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
        }
    }

    fun setAutoCacheOnPlay(enabled: Boolean) {
        app.appScope.launch(NonCancellable) {
            preferences.setAutoCacheOnPlay(enabled)
        }
    }

    fun setDarkLiquidGlass(dark: Boolean) {
        app.appScope.launch(NonCancellable) {
            preferences.setDarkLiquidGlass(dark)
        }
    }

    fun setAppLanguage(languageCode: String) {
        app.appScope.launch(NonCancellable) {
            preferences.setAppLanguage(languageCode)
        }
    }

    fun setAccentColorArgb(argb: Long) {
        app.appScope.launch(NonCancellable) {
            preferences.setAccentColorArgb(argb)
        }
    }

    fun setSmoothTransitionEnabled(enabled: Boolean) {
        app.appScope.launch(NonCancellable) {
            preferences.setSmoothTransitionEnabled(enabled)
        }
    }

    fun setSmoothTransitionDurationSec(seconds: Int) {
        app.appScope.launch(NonCancellable) {
            preferences.setSmoothTransitionDurationSec(seconds)
        }
    }

    fun setDataSaverEnabled(enabled: Boolean) {
        FullStreamResolver.isDataSaverEnabled = enabled
        app.appScope.launch(NonCancellable) {
            preferences.setDataSaverEnabled(enabled)
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
            val freed = cacheManager.clearAppCache()
            audiusRepository.clearSearchCache()
            val remaining = cacheManager.calculateCacheSizeBytes()
            _cacheSizeBytes.value = remaining
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

    fun clearImportStatus() {
        _importStatusMessage.value = null
    }

    fun clearUploadMessage() {
        _uploadState.update { it.copy(statusMessage = null) }
    }

    override fun onCleared() {
        networkObserver.stop()
        playbackController.onOfflinePlaybackBlocked = null
        playbackController.disconnect()
        super.onCleared()
    }
}
