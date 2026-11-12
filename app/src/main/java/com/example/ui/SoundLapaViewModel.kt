package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.SoundLapaApplication
import com.example.audio.AutoSyncResult
import com.example.audio.ImportPreviewMetadata
import com.example.data.local.PlaylistEntity
import com.example.data.local.PlaylistTrackCrossRef
import com.example.data.local.PlaylistWithTracks
import com.example.data.local.SoundLapaSettings
import com.example.data.local.TrackEntity
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
    val statusMessage: String? = null,
)

@Immutable
data class HomeUiState(
    val quickPickRows: List<List<TrackEntity>> = emptyList(),
    val trendingCarousel: List<TrackEntity> = emptyList(),
    val filteredTracks: List<TrackEntity> = emptyList(),
    val selectedFilter: String = "Все",
    val isSearchActive: Boolean = false,
    val searchQuery: String = "",
    val isSearching: Boolean = false,
    val isRefreshingTrending: Boolean = false,
    val currentTrackId: String? = null,
    val isPlaying: Boolean = false,
)

@Immutable
data class MainUiState(
    val tracks: List<TrackEntity> = emptyList(),
    val filteredTracks: List<TrackEntity> = emptyList(),
    val searchScreenResults: List<TrackEntity> = emptyList(),
    val playlists: List<PlaylistWithTracks> = emptyList(),
    val selectedFilter: String = "Все",
    val searchQuery: String = "",
    val isSearchActive: Boolean = false,
    val playback: PlaybackUiState = PlaybackUiState(),
    val currentTrack: TrackEntity? = null,
    val settings: SoundLapaSettings = SoundLapaSettings(),
    val upload: UploadUiState = UploadUiState(),
    val audius: AudiusUiState = AudiusUiState(),
)

class SoundLapaViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as SoundLapaApplication
    private val dao = app.dao
    private val preferences = app.preferences
    private val cacheManager = app.audioCacheManager
    private val audiusRepository = app.audiusRepository
    val playbackController = app.playbackController
    val playbackPositionMs: StateFlow<Long> = playbackController.positionMs

    private val _selectedFilter = MutableStateFlow("Все")
    private val _searchQuery = MutableStateFlow("")
    private val _isSearchActive = MutableStateFlow(false)
    private val _uploadState = MutableStateFlow(UploadUiState())
    private val _audiusState = MutableStateFlow(AudiusUiState())
    private val _isPlayerExpanded = MutableStateFlow(false)
    val isPlayerExpanded: StateFlow<Boolean> = _isPlayerExpanded.asStateFlow()

    private var searchJob: Job? = null

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
        val filteredTracks: List<TrackEntity>,
        val searchScreenResults: List<TrackEntity>,
        val selectedFilter: String,
    )

    private val tracksAndFiltersFlow = combine(
        dao.observeAllTracks(),
        _selectedFilter,
        _searchQuery,
        _isSearchActive,
        _audiusState,
    ) { tracks, filter, query, searchActive, audius ->
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

        val mergedHome = if (searchActive && homeQuery.length >= 2 && audius.searchResults.isNotEmpty()) {
            val localIds = localFiltered.mapTo(HashSet()) { it.id }
            localFiltered + audius.searchResults.filter { it.id !in localIds }
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
            localMatches + audius.searchResults.filter { it.id !in localIds }
        }

        val quickPickRows = tracks.take(4).chunked(2)
        val trendingCarousel = tracks.sortedByDescending { it.playCount }.take(6)

        TracksFilterSnapshot(
            allTracks = tracks,
            quickPickRows = quickPickRows,
            trendingCarousel = trendingCarousel,
            filteredTracks = mergedHome,
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
    ) { snapshot, searchActive, query, audius, playback ->
        HomeUiState(
            quickPickRows = snapshot.quickPickRows,
            trendingCarousel = snapshot.trendingCarousel,
            filteredTracks = snapshot.filteredTracks,
            selectedFilter = snapshot.selectedFilter,
            isSearchActive = searchActive,
            searchQuery = query,
            isSearching = audius.isSearching,
            isRefreshingTrending = audius.isRefreshingTrending,
            currentTrackId = playback.currentTrackId,
            isPlaying = playback.isPlaying,
        )
    }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = HomeUiState(),
        )

    val uiState: StateFlow<MainUiState> = combine(
        tracksAndFiltersFlow,
        dao.observePlaylistsWithTracks(),
        playbackController.playbackState,
        preferences.settings,
        _uploadState,
        _audiusState,
    ) { values ->
        val snapshot = values[0] as TracksFilterSnapshot
        @Suppress("UNCHECKED_CAST")
        val playlists = values[1] as List<PlaylistWithTracks>
        val playback = values[2] as PlaybackUiState
        val settings = values[3] as SoundLapaSettings
        val upload = values[4] as UploadUiState
        val audius = values[5] as AudiusUiState

        val tracks = snapshot.allTracks
        val currentTrack = tracks.find { it.id == playback.currentTrackId }
            ?: audius.searchResults.find { it.id == playback.currentTrackId }
            ?: tracks.find { it.id == settings.lastPlayedTrackId }
            ?: tracks.firstOrNull()

        MainUiState(
            tracks = tracks,
            filteredTracks = snapshot.filteredTracks,
            searchScreenResults = snapshot.searchScreenResults,
            playlists = playlists,
            selectedFilter = snapshot.selectedFilter,
            searchQuery = _searchQuery.value,
            isSearchActive = _isSearchActive.value,
            playback = playback,
            currentTrack = currentTrack,
            settings = settings,
            upload = upload,
            audius = audius,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = MainUiState(settings = preferences.currentSettings()),
    )

    init {
        playbackController.connect()
        viewModelScope.launch {
            dao.observeAllTracks()
                .distinctUntilChanged()
                .collect { tracks ->
                    playbackController.updateAvailableTracks(tracks)
                }
        }
        refreshAudiusTrending()
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
            _audiusState.update { it.copy(isSearching = false, searchResults = emptyList()) }
        }
    }

    fun updateSearchQuery(query: String, immediate: Boolean = false) {
        _searchQuery.value = query
        searchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.length < 2) {
            _audiusState.update { it.copy(isSearching = false, searchResults = emptyList()) }
            return
        }
        searchJob = viewModelScope.launch {
            _audiusState.update { it.copy(isSearching = true) }
            if (!immediate) {
                delay(150L)
            }
            val result = audiusRepository.searchTracksProgressive(trimmed) { partial ->
                if (_searchQuery.value.trim() == trimmed) {
                    _audiusState.update { it.copy(searchResults = partial) }
                }
            }
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
        viewModelScope.launch {
            _audiusState.update { it.copy(isRefreshingTrending = true) }
            audiusRepository.fetchTrendingTracks()
            _audiusState.update { it.copy(isRefreshingTrending = false) }
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

    fun playOrToggleTrack(track: TrackEntity, customQueue: List<TrackEntity>? = null) {
        val currentId = playbackController.playbackState.value.currentTrackId
        if (currentId == track.id && customQueue == null) {
            playbackController.togglePlayPause()
        } else {
            val queue = customQueue ?: uiState.value.filteredTracks.ifEmpty { uiState.value.tracks }
            viewModelScope.launch {
                audiusRepository.ensureTrackPersisted(track)
                playbackController.playTrack(track, queue)

                val settings = uiState.value.settings
                if (!track.isCachedOffline && settings.autoCacheOnPlay) {
                    if (track.sourceUri?.startsWith("http") == true) {
                        audiusRepository.cacheTrackAudioIfNeeded(track)
                    } else {
                        cacheManager.toggleOfflineCache(track)
                    }
                }
            }
        }
    }

    fun playPlaylist(playlist: PlaylistWithTracks) {
        val first = playlist.tracks.firstOrNull() ?: return
        playOrToggleTrack(first, playlist.tracks)
    }

    fun toggleLike(track: TrackEntity) {
        viewModelScope.launch {
            audiusRepository.ensureTrackPersisted(track)
            val nextLiked = !track.isLiked
            val nextCount = (track.likeCount + if (nextLiked) 1 else -1).coerceAtLeast(0)
            dao.updateLike(track.id, nextLiked, nextCount)
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
            dao.addTrackToPlaylist(PlaylistTrackCrossRef(playlistId = playlistId, trackId = track.id))
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

    fun clearUploadMessage() {
        _uploadState.update { it.copy(statusMessage = null) }
    }

    override fun onCleared() {
        playbackController.disconnect()
        super.onCleared()
    }
}
