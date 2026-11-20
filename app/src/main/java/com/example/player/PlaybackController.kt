package com.example.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.compose.runtime.Immutable
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.data.local.SoundLapaDao
import com.example.data.local.SoundLapaPreferences
import com.example.data.local.TrackEntity
import com.example.data.remote.FullStreamResolver
import com.example.data.remote.isNetworkCurrentlyAvailable
import com.example.data.remote.isTrackPlayableOffline
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.min

@Immutable
data class PlaybackUiState(
    val currentTrackId: String? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffleEnabled: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val audioFocusStatus: AudioFocusUiStatus = AudioFocusUiStatus.IDLE,
    val transitionCounter: Int = 0,
    val playbackContextTitle: String? = null,
)

/**
 * Enforces the honest 40-second play count rule:
 * - A track is immediately added to Recently Played when playback starts without incrementing playCount.
 * - Play count (+1) is credited at most once per play session when playback naturally reaches
 *   40,000 ms without forward-seeking past unplayed content or directly onto >= 40s.
 */
internal class PlayCountSessionTracker(
    private val creditThresholdMs: Long = 40_000L,
    private val minNaturalElapsedMs: Long = 35_000L,
) {
    var sessionTrackId: String? = null
        private set
    var isCredited: Boolean = false
        private set
    var isSeekDisqualified: Boolean = false
        private set
    var naturalPlayedMs: Long = 0L
        private set
    private var lastSamplePosMs: Long = -1L

    fun onTrackStarted(trackId: String, startPositionMs: Long = 0L) {
        sessionTrackId = trackId
        isCredited = false
        isSeekDisqualified = startPositionMs >= creditThresholdMs
        naturalPlayedMs = 0L
        lastSamplePosMs = startPositionMs.coerceAtLeast(0L)
    }

    fun onUserSeek(fromPosMs: Long, toPosMs: Long) {
        if (isCredited) return
        val safeTo = toPosMs.coerceAtLeast(0L)
        val safeFrom = fromPosMs.coerceAtLeast(0L)
        if (safeTo < 5_000L) {
            isSeekDisqualified = false
            naturalPlayedMs = 0L
            lastSamplePosMs = safeTo
            return
        }
        if (safeTo >= creditThresholdMs || (safeTo - safeFrom) > 2_500L) {
            isSeekDisqualified = true
        }
        lastSamplePosMs = safeTo
    }

    fun onProgressSample(
        trackId: String,
        posMs: Long,
        isActuallyPlaying: Boolean,
        wallDeltaMs: Long,
        expectPendingSeek: Boolean = false,
    ): Boolean {
        if (trackId != sessionTrackId) {
            onTrackStarted(trackId, posMs)
        } else if (lastSamplePosMs >= creditThresholdMs && posMs < 3_000L && !expectPendingSeek) {
            // Natural repeat / loop back to start of track earns a fresh play session
            onTrackStarted(trackId, posMs)
        }

        if (isActuallyPlaying && posMs >= 0L) {
            if (!expectPendingSeek && lastSamplePosMs >= 0L && (posMs - lastSamplePosMs) > 3_500L) {
                isSeekDisqualified = true
            }
            naturalPlayedMs += wallDeltaMs.coerceIn(0L, 1_500L)
        }
        lastSamplePosMs = posMs

        if (!isCredited && !isSeekDisqualified && posMs >= creditThresholdMs && naturalPlayedMs >= minNaturalElapsedMs) {
            isCredited = true
            return true
        }
        return false
    }
}

class PlaybackController(
    private val context: Context,
    private val dao: SoundLapaDao,
    private val preferences: SoundLapaPreferences,
    private val scope: CoroutineScope,
) {
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null
    private var positionTickerJob: Job? = null
    private var cachedQueue: List<TrackEntity> = emptyList()
    private val knownTracksById = ConcurrentHashMap<String, TrackEntity>()

    private data class PendingPlayRequest(
        val track: TrackEntity,
        val queue: List<TrackEntity>,
        val startPositionMs: Long,
        val contextTitle: String? = null,
    )
    private var pendingPlayRequest: PendingPlayRequest? = null
    private var pendingTogglePlay: Boolean = false
    private val errorRetryCounts = HashMap<String, Int>()
    private var errorRecoveryJob: Job? = null

    private var pendingSeekTargetMs: Long = -1L
    private var pendingSeekExpiresAt: Long = 0L

    private var smoothTransitionEnabled: Boolean = true
    private var smoothTransitionDurationSec: Int = 3
    private var autoCrossfadeTriggeredTrackId: String? = null
    private var lastCrossfadeActivatedId: String? = null
    private var lastCrossfadeActivatedAtMs: Long = 0L
    private var rapidSkipTrackId: String? = null
    private var rapidSkipExpiresAtMs: Long = 0L
    @Volatile
    private var isClipAudioMuted: Boolean = false
    private val playCountTracker = PlayCountSessionTracker()

    private val _playbackState = MutableStateFlow(PlaybackUiState())
    val playbackState: StateFlow<PlaybackUiState> = _playbackState.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _queueTracks = MutableStateFlow<List<TrackEntity>>(emptyList())
    val queueTracks: StateFlow<List<TrackEntity>> = _queueTracks.asStateFlow()

    private val tickerWakeChannel = Channel<Unit>(Channel.CONFLATED)

    var onOfflinePlaybackBlocked: ((String?) -> Unit)? = null

    private fun wakeTicker() {
        tickerWakeChannel.trySend(Unit)
    }

    fun setClipAudioMuteOverride(muted: Boolean) {
        if (isClipAudioMuted == muted) return
        isClipAudioMuted = muted
        SoundLapaPlaybackService.setClipAudioMuteOverride(muted)
        mediaController?.volume = if (muted) 0f else 1f
    }

    init {
        scope.launch {
            SoundLapaPlaybackService.audioFocusStatus.collect { focusStatus ->
                _playbackState.update { it.copy(audioFocusStatus = focusStatus) }
            }
        }
        scope.launch {
            preferences.settings.collect { settings ->
                FullStreamResolver.isDataSaverEnabled = settings.dataSaverEnabled
                FullStreamResolver.isBatterySaverEnabled = settings.batterySaverEnabled
                SoundLapaPlaybackService.setAudioFocusMode(settings.audioFocusMode)
                smoothTransitionEnabled = settings.smoothTransitionEnabled
                smoothTransitionDurationSec = settings.smoothTransitionDurationSec.coerceIn(1, 10)
                if (!smoothTransitionEnabled) {
                    SoundLapaPlaybackService.stopActiveCrossfade(restorePrimaryVolume = true)
                    mediaController?.volume = if (isClipAudioMuted) 0f else 1f
                }
            }
        }
    }

    fun connect() {
        if (controllerFuture != null) return
        SoundLapaPlaybackService.onActiveDeckSwapped = {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                mediaController?.let { controller ->
                    rapidSkipTrackId = null
                    syncFromController(controller)
                }
            }
        }
        val sessionToken = SessionToken(
            context,
            ComponentName(context, SoundLapaPlaybackService::class.java),
        )
        val future = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                runCatching {
                    val controller = future.get()
                    mediaController = controller
                    controller.volume = if (isClipAudioMuted) 0f else 1f
                    attachControllerListener(controller)
                    startPositionTicker()
                    val pending = pendingPlayRequest
                    val togglePending = pendingTogglePlay
                    pendingPlayRequest = null
                    pendingTogglePlay = false
                    if (pending != null) {
                        playTrack(pending.track, pending.queue, pending.startPositionMs, pending.contextTitle)
                    } else if (togglePending) {
                        togglePlayPause()
                    } else {
                        syncFromController(controller)
                    }
                }.onFailure {
                    controllerFuture = null
                    mediaController = null
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    fun syncNowFromService() {
        val controller = mediaController ?: run {
            connect()
            return
        }
        rapidSkipTrackId = null
        syncFromController(controller)
    }

    fun disconnect() {
        val currentId = mediaController?.currentMediaItem?.mediaId ?: _playbackState.value.currentTrackId
        val currentPos = _positionMs.value
        if (currentId != null) {
            scope.launch(Dispatchers.IO) {
                preferences.savePlaybackState(currentId, currentPos)
            }
        }
        SoundLapaPlaybackService.onActiveDeckSwapped = null
        pendingPlayRequest = null
        pendingTogglePlay = false
        errorRecoveryJob?.cancel()
        errorRecoveryJob = null
        SoundLapaPlaybackService.stopActiveCrossfade(restorePrimaryVolume = true)
        positionTickerJob?.cancel()
        positionTickerJob = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        mediaController = null
    }

    fun updateAvailableTracks(tracks: List<TrackEntity>) {
        cachedQueue = tracks
        tracks.forEach { knownTracksById[it.id] = it }
        if (_queueTracks.value.isEmpty() && tracks.isNotEmpty()) {
            _queueTracks.value = tracks.filter { resolveTrackUri(it) != null }
        }
    }

    fun findTrackById(trackId: String?): TrackEntity? = trackId?.let { knownTracksById[it] }

    fun currentMediaMetadataFallback(): Triple<String, String, String?>? {
        val item = mediaController?.currentMediaItem ?: return null
        val meta = item.mediaMetadata
        val title = meta.title?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val artist = meta.artist?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: "SoundLap"
        val cover = meta.artworkUri?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        return Triple(title, artist, cover)
    }

    fun isTrackActiveInController(trackId: String): Boolean {
        val controller = mediaController ?: return false
        return controller.mediaItemCount > 0 && controller.currentMediaItem?.mediaId == trackId
    }

    private var unshuffledQueueBackup: List<TrackEntity> = emptyList()

    private fun syncQueueOrderFromController(controller: MediaController) {
        val count = controller.mediaItemCount
        if (count <= 0) return
        val synced = ArrayList<TrackEntity>(count)
        for (i in 0 until count) {
            val id = controller.getMediaItemAt(i).mediaId
            val track = knownTracksById[id] ?: _queueTracks.value.find { it.id == id } ?: cachedQueue.find { it.id == id }
            if (track != null) {
                synced += track
            }
        }
        if (synced.isNotEmpty()) {
            _queueTracks.value = synced
        }
    }

    private fun prefetchUpcomingWindow(controller: MediaController, fromIndex: Int = controller.currentMediaItemIndex) {
        val count = controller.mediaItemCount
        if (count <= 1 || fromIndex !in 0 until count) return
        val repeatAll = controller.repeatMode == Player.REPEAT_MODE_ALL
        val upcoming = buildList {
            for (offset in 1..3) {
                val idx = fromIndex + offset
                val resolvedIdx = when {
                    idx < count -> idx
                    repeatAll -> idx % count
                    else -> -1
                }
                if (resolvedIdx >= 0 && resolvedIdx != fromIndex) {
                    val id = controller.getMediaItemAt(resolvedIdx).mediaId
                    val track = knownTracksById[id] ?: _queueTracks.value.find { it.id == id }
                    if (track != null) add(track)
                }
            }
        }
        if (upcoming.isNotEmpty()) {
            AudioPrefetchCache.prefetchUpcomingTracks(context, upcoming, dao)
        }
    }

    fun playFromActiveQueue(track: TrackEntity) {
        val controller = mediaController
        if (controller != null && controller.mediaItemCount > 0) {
            var targetIdx = -1
            for (i in 0 until controller.mediaItemCount) {
                if (controller.getMediaItemAt(i).mediaId == track.id) {
                    targetIdx = i
                    break
                }
            }
            if (targetIdx >= 0) {
                knownTracksById[track.id] = track
                SoundLapaPlaybackService.prepareCleanSingleDeckStart()
                pendingSeekTargetMs = 0L
                pendingSeekExpiresAt = System.currentTimeMillis() + 1200L
                autoCrossfadeTriggeredTrackId = null
                rapidSkipTrackId = track.id
                rapidSkipExpiresAtMs = System.currentTimeMillis() + 3_000L
                controller.volume = if (isClipAudioMuted) 0f else 1f
                controller.seekTo(targetIdx, 0L)
                if (controller.playbackState == Player.STATE_IDLE) {
                    controller.prepare()
                }
                controller.playWhenReady = true
                controller.play()
                playCountTracker.onTrackStarted(track.id, 0L)
                _positionMs.value = 0L
                _playbackState.update {
                    it.copy(
                        currentTrackId = track.id,
                        isPlaying = true,
                        isBuffering = true,
                        positionMs = 0L,
                        durationMs = track.durationMs,
                        transitionCounter = it.transitionCounter + 1,
                    )
                }
                prefetchUpcomingWindow(controller, targetIdx)
                scope.launch(Dispatchers.IO) {
                    if (dao.getTrackById(track.id) == null) dao.upsertTrack(track)
                    preferences.savePlaybackState(track.id, 0L)
                    preferences.recordTrackRecentlyPlayed(track)
                }
                return
            }
        }
        val active = _queueTracks.value.ifEmpty { cachedQueue }
        playTrack(track, active, startPositionMs = 0L, contextTitle = _playbackState.value.playbackContextTitle)
    }

    fun playNextInQueue(track: TrackEntity) {
        knownTracksById[track.id] = track
        val mediaItem = toMediaItem(track) ?: return
        val controller = mediaController
        val currentQueue = _queueTracks.value.ifEmpty { cachedQueue }.toMutableList()
        if (controller == null || controller.mediaItemCount == 0) {
            playTrack(track, (listOf(track) + currentQueue).distinctBy { it.id })
            return
        }
        val currentIdx = controller.currentMediaItemIndex.coerceIn(0, controller.mediaItemCount - 1)
        var existingIdx = -1
        for (i in 0 until controller.mediaItemCount) {
            if (controller.getMediaItemAt(i).mediaId == track.id) {
                existingIdx = i
                break
            }
        }
        when {
            existingIdx == currentIdx -> {
                // Already currently playing
            }
            existingIdx >= 0 -> {
                val targetIdx = if (existingIdx < currentIdx) currentIdx else (currentIdx + 1).coerceAtMost(controller.mediaItemCount - 1)
                if (existingIdx != targetIdx) {
                    controller.moveMediaItem(existingIdx, targetIdx)
                }
            }
            else -> {
                val insertIdx = (currentIdx + 1).coerceIn(0, controller.mediaItemCount)
                controller.addMediaItem(insertIdx, mediaItem)
            }
        }
        syncQueueOrderFromController(controller)
        prefetchUpcomingWindow(controller)
    }

    fun addToQueue(track: TrackEntity) {
        knownTracksById[track.id] = track
        val mediaItem = toMediaItem(track) ?: return
        val controller = mediaController
        val currentQueue = _queueTracks.value.ifEmpty { cachedQueue }.toMutableList()
        if (controller == null || controller.mediaItemCount == 0) {
            playTrack(track, (currentQueue + track).distinctBy { it.id })
            return
        }
        val currentIdx = controller.currentMediaItemIndex.coerceIn(0, controller.mediaItemCount - 1)
        var existingIdx = -1
        for (i in 0 until controller.mediaItemCount) {
            if (controller.getMediaItemAt(i).mediaId == track.id) {
                existingIdx = i
                break
            }
        }
        when {
            existingIdx == currentIdx -> {
                // Already currently playing; keep queue unique
            }
            existingIdx >= 0 -> {
                val endIdx = (controller.mediaItemCount - 1).coerceAtLeast(0)
                if (existingIdx != endIdx) {
                    controller.moveMediaItem(existingIdx, endIdx)
                }
            }
            else -> {
                controller.addMediaItem(mediaItem)
            }
        }
        syncQueueOrderFromController(controller)
        prefetchUpcomingWindow(controller)
    }

    fun removeFromQueue(trackId: String) {
        val controller = mediaController ?: return
        val count = controller.mediaItemCount
        if (count <= 1) return
        val currentIdx = controller.currentMediaItemIndex
        var targetIdx = -1
        for (i in 0 until count) {
            if (controller.getMediaItemAt(i).mediaId == trackId) {
                targetIdx = i
                break
            }
        }
        if (targetIdx < 0 || targetIdx == currentIdx) return
        controller.removeMediaItem(targetIdx)
        syncQueueOrderFromController(controller)
        prefetchUpcomingWindow(controller)
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        val controller = mediaController ?: return
        val count = controller.mediaItemCount
        if (fromIndex == toIndex || fromIndex !in 0 until count || toIndex !in 0 until count) return
        controller.moveMediaItem(fromIndex, toIndex)
        syncQueueOrderFromController(controller)
        prefetchUpcomingWindow(controller)
    }

    fun clearUpcomingQueue() {
        val controller = mediaController ?: return
        val count = controller.mediaItemCount
        val currentIdx = controller.currentMediaItemIndex
        if (currentIdx !in 0 until count || currentIdx >= count - 1) return
        controller.removeMediaItems(currentIdx + 1, count)
        syncQueueOrderFromController(controller)
    }

    fun appendTracksToQueue(tracks: List<TrackEntity>) {
        if (tracks.isEmpty()) return
        tracks.forEach { knownTracksById[it.id] = it }
        val controller = mediaController ?: return
        if (controller.mediaItemCount == 0) return
        val existingIds = HashSet<String>(controller.mediaItemCount)
        for (i in 0 until controller.mediaItemCount) {
            existingIds += controller.getMediaItemAt(i).mediaId
        }
        val newTracks = tracks.filter { it.id !in existingIds && resolveTrackUri(it) != null }.distinctBy { it.id }
        if (newTracks.isEmpty()) return
        val newItems = newTracks.mapNotNull { toMediaItem(it) }
        if (newItems.isEmpty()) return
        controller.addMediaItems(newItems)
        syncQueueOrderFromController(controller)
        prefetchUpcomingWindow(controller)
    }

    fun playTrack(
        track: TrackEntity,
        queue: List<TrackEntity> = cachedQueue,
        startPositionMs: Long = 0L,
        contextTitle: String? = null,
    ) {
        knownTracksById[track.id] = track
        queue.forEach { knownTracksById[it.id] = it }
        val resolvedContext = contextTitle?.trim()?.takeIf { it.isNotEmpty() }

        val controller = mediaController ?: run {
            pendingPlayRequest = PendingPlayRequest(track, queue, startPositionMs, resolvedContext)
            // Start resolving direct audio URL immediately in parallel with service bind
            FullStreamResolver.prefetchTracks(listOf(track), dao)
            // Optimistic UI feedback so 1 tap never feels ignored while binding MediaController
            _playbackState.update {
                it.copy(
                    currentTrackId = track.id,
                    isPlaying = true,
                    isBuffering = true,
                    durationMs = track.durationMs,
                    playbackContextTitle = resolvedContext,
                )
            }
            connect()
            return
        }
        pendingPlayRequest = null
        pendingTogglePlay = false
        errorRecoveryJob?.cancel()
        errorRetryCounts.remove(track.id)

        val rawFiltered = queue.filter { resolveTrackUri(it) != null }
        val normalizedQueue = if (resolveTrackUri(track) != null && rawFiltered.none { it.id == track.id }) {
            listOf(track) + rawFiltered
        } else {
            rawFiltered.ifEmpty { listOfNotNull(track.takeIf { resolveTrackUri(it) != null }) }
        }
        val playableQueue = normalizedQueue.distinctBy { it.id }
        val isSameQueueIds = _queueTracks.value.size == playableQueue.size &&
            _queueTracks.value.indices.all { _queueTracks.value[it].id == playableQueue[it].id }
        if (!isSameQueueIds) {
            unshuffledQueueBackup = emptyList()
            if (_playbackState.value.shuffleEnabled) {
                _playbackState.update { it.copy(shuffleEnabled = false) }
            }
        }
        _queueTracks.value = playableQueue
        val targetIndex = playableQueue.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        val mediaItems = playableQueue.mapNotNull { toMediaItem(it) }
        if (mediaItems.isEmpty()) return

        pendingSeekTargetMs = startPositionMs
        pendingSeekExpiresAt = System.currentTimeMillis() + 1200L
        autoCrossfadeTriggeredTrackId = null
        rapidSkipTrackId = track.id
        rapidSkipExpiresAtMs = System.currentTimeMillis() + 3_000L

        // Always start manual track selections cleanly on a single active deck so two audio
        // streams can never overlap or double at playback start.
        SoundLapaPlaybackService.prepareCleanSingleDeckStart()
        startMediaItems(
            controller = controller,
            mediaItems = mediaItems,
            targetIndex = targetIndex,
            startPositionMs = startPositionMs,
            track = track,
            prefetchQueue = playableQueue,
            initialVolume = if (isClipAudioMuted) 0f else 1f,
            contextTitle = resolvedContext,
        )
    }

    private fun controllerQueueMatches(
        controller: MediaController,
        mediaItems: List<MediaItem>,
    ): Boolean {
        val count = controller.mediaItemCount
        if (count != mediaItems.size || count == 0) return false
        for (i in 0 until count) {
            if (controller.getMediaItemAt(i).mediaId != mediaItems[i].mediaId) return false
        }
        return true
    }

    private fun startMediaItems(
        controller: MediaController,
        mediaItems: List<MediaItem>,
        targetIndex: Int,
        startPositionMs: Long,
        track: TrackEntity,
        prefetchQueue: List<TrackEntity>,
        initialVolume: Float?,
        contextTitle: String? = null,
    ) {
        val safeStart = startPositionMs.coerceAtLeast(0L)
        if (initialVolume != null) {
            controller.volume = initialVolume
        }
        if (controllerQueueMatches(controller, mediaItems) && targetIndex in 0 until controller.mediaItemCount) {
            controller.seekTo(targetIndex, safeStart)
            if (controller.playbackState == Player.STATE_IDLE) {
                controller.prepare()
            }
        } else {
            controller.setMediaItems(mediaItems, targetIndex, safeStart)
            controller.prepare()
        }
        controller.playWhenReady = true
        controller.play()

        // Prefetch & disk-cache next 3 upcoming tracks in the background so switching has zero delay
        val currentIdxInQueue = prefetchQueue.indexOfFirst { it.id == track.id }
        val upcoming = if (currentIdxInQueue >= 0) {
            buildList {
                for (offset in 1..3) {
                    prefetchQueue.getOrNull(currentIdxInQueue + offset)?.let { add(it) }
                }
            }
        } else {
            emptyList()
        }
        if (upcoming.isNotEmpty()) {
            AudioPrefetchCache.prefetchUpcomingTracks(context, upcoming, dao)
        }
        prefetchQueue.getOrNull(currentIdxInQueue - 1)?.let { prev ->
            FullStreamResolver.prefetchTracks(listOf(prev), dao)
        }

        playCountTracker.onTrackStarted(track.id, safeStart)
        _positionMs.value = safeStart
        _playbackState.update {
            it.copy(
                currentTrackId = track.id,
                isPlaying = true,
                isBuffering = true,
                positionMs = safeStart,
                durationMs = track.durationMs,
                transitionCounter = it.transitionCounter + 1,
                playbackContextTitle = contextTitle,
            )
        }

        scope.launch(Dispatchers.IO) {
            if (dao.getTrackById(track.id) == null) {
                dao.upsertTrack(track)
            }
            preferences.savePlaybackState(track.id, safeStart)
            preferences.recordTrackRecentlyPlayed(track)
        }
    }

    fun togglePlayPause(fallbackTrack: TrackEntity? = null) {
        val controller = mediaController ?: run {
            if (fallbackTrack != null) {
                playTrack(fallbackTrack, cachedQueue.ifEmpty { listOf(fallbackTrack) })
            } else {
                pendingTogglePlay = true
                connect()
            }
            return
        }
        if (controller.mediaItemCount == 0) {
            val chosen = fallbackTrack?.takeIf { resolveTrackUri(it) != null }
                ?: cachedQueue.firstOrNull { resolveTrackUri(it) != null }
            if (chosen != null) {
                playTrack(chosen, cachedQueue.ifEmpty { listOf(chosen) })
            }
            return
        }
        val activelyEngaged = controller.playWhenReady &&
            controller.playbackState != Player.STATE_ENDED &&
            controller.playbackState != Player.STATE_IDLE
        if (activelyEngaged) {
            SoundLapaPlaybackService.stopActiveCrossfade(restorePrimaryVolume = true)
            controller.volume = if (isClipAudioMuted) 0f else 1f
            controller.pause()
            _playbackState.update { it.copy(isPlaying = false, isBuffering = false) }
            val currentId = controller.currentMediaItem?.mediaId ?: _playbackState.value.currentTrackId
            val currentPos = _positionMs.value
            scope.launch(Dispatchers.IO) {
                preferences.savePlaybackState(currentId, currentPos)
            }
        } else {
            if (controller.playbackState == Player.STATE_IDLE) {
                controller.prepare()
            } else if (controller.playbackState == Player.STATE_ENDED) {
                pendingSeekTargetMs = 0L
                pendingSeekExpiresAt = System.currentTimeMillis() + 900L
                val currentId = controller.currentMediaItem?.mediaId ?: _playbackState.value.currentTrackId
                if (currentId != null) {
                    playCountTracker.onTrackStarted(currentId, 0L)
                }
                controller.seekTo(0L)
            }
            controller.volume = if (isClipAudioMuted) 0f else 1f
            controller.playWhenReady = true
            controller.play()
            _playbackState.update {
                it.copy(
                    isPlaying = true,
                    isBuffering = controller.playbackState != Player.STATE_READY,
                )
            }
        }
    }

    fun seekTo(positionMs: Long) {
        val controller = mediaController ?: return
        val maxDuration = controller.duration.takeIf { it > 0L }
            ?: _playbackState.value.durationMs.takeIf { it > 0L }
            ?: 0L
        val clamped = if (maxDuration > 0L) {
            positionMs.coerceIn(0L, maxDuration)
        } else {
            positionMs.coerceAtLeast(0L)
        }

        val currentPosBeforeSeek = _positionMs.value.coerceAtLeast(controller.currentPosition.coerceAtLeast(0L))
        playCountTracker.onUserSeek(fromPosMs = currentPosBeforeSeek, toPosMs = clamped)

        SoundLapaPlaybackService.stopActiveCrossfade(restorePrimaryVolume = true)
        runCatching { controller.volume = if (isClipAudioMuted) 0f else 1f }
        autoCrossfadeTriggeredTrackId = null
        pendingSeekTargetMs = clamped
        pendingSeekExpiresAt = System.currentTimeMillis() + 1200L
        _positionMs.value = clamped
        _playbackState.update { it.copy(positionMs = clamped) }

        val currentMediaId = runCatching { controller.currentMediaItem?.mediaId }.getOrNull()
            ?: _playbackState.value.currentTrackId

        if (controller.mediaItemCount > 0) {
            runCatching {
                if (controller.playbackState == Player.STATE_IDLE) {
                    controller.prepare()
                }
                controller.seekTo(clamped)
            }
        }
        scope.launch(Dispatchers.IO) {
            preferences.savePlaybackState(currentMediaId, clamped)
        }
    }

    fun seekToFraction(fraction: Float, fallbackTrack: TrackEntity? = null) {
        val safeFraction = if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else 0f
        val controller = mediaController ?: run {
            connect()
            return
        }
        val knownDuration = controller.duration.takeIf { it > 0L }
            ?: _playbackState.value.durationMs.takeIf { it > 0L }
            ?: fallbackTrack?.durationMs?.takeIf { it > 0L }
            ?: 0L

        if (knownDuration <= 0L) return
        val targetPos = (knownDuration * safeFraction).toLong().coerceIn(0L, knownDuration)

        if (controller.mediaItemCount == 0 && fallbackTrack != null) {
            playTrack(fallbackTrack, cachedQueue.ifEmpty { listOf(fallbackTrack) }, startPositionMs = targetPos)
            return
        }

        seekTo(targetPos)
    }

    private fun buildControllerMediaItems(controller: MediaController): List<MediaItem> {
        val count = controller.mediaItemCount
        if (count <= 0) return emptyList()
        return List(count) { idx -> controller.getMediaItemAt(idx) }
    }

    fun skipNext() {
        val fallbackQueue = _queueTracks.value.ifEmpty { cachedQueue }
            .filter { resolveTrackUri(it) != null }
        val controller = mediaController
        if (controller == null) {
            if (fallbackQueue.isNotEmpty()) {
                val curId = _playbackState.value.currentTrackId
                val curIdx = fallbackQueue.indexOfFirst { it.id == curId }
                val targetIdx = if (curIdx >= 0) (curIdx + 1) % fallbackQueue.size else 0
                playTrack(fallbackQueue[targetIdx], fallbackQueue, startPositionMs = 0L)
            } else {
                connect()
            }
            return
        }

        val mediaItems = buildControllerMediaItems(controller)
        if (mediaItems.size <= 1) {
            if (fallbackQueue.size > 1) {
                val curId = controller.currentMediaItem?.mediaId ?: _playbackState.value.currentTrackId
                val curIdx = fallbackQueue.indexOfFirst { it.id == curId }
                val targetIdx = if (curIdx >= 0) (curIdx + 1) % fallbackQueue.size else 0
                playTrack(fallbackQueue[targetIdx], fallbackQueue, startPositionMs = 0L)
            }
            return
        }

        errorRecoveryJob?.cancel()
        autoCrossfadeTriggeredTrackId = null
        val now = System.currentTimeMillis()
        val rapidIdx = if (rapidSkipTrackId != null && now < rapidSkipExpiresAtMs) {
            mediaItems.indexOfFirst { it.mediaId == rapidSkipTrackId }
        } else {
            -1
        }

        val nextIdx = if (rapidIdx >= 0) {
            (rapidIdx + 1) % mediaItems.size
        } else if (controller.hasNextMediaItem()) {
            controller.nextMediaItemIndex
        } else {
            val curIdx = controller.currentMediaItemIndex
            if (curIdx in mediaItems.indices) (curIdx + 1) % mediaItems.size else 0
        }
        if (nextIdx !in mediaItems.indices) return

        val targetMediaId = mediaItems[nextIdx].mediaId
        val targetTrack = knownTracksById[targetMediaId] ?: cachedQueue.find { t -> t.id == targetMediaId }
        rapidSkipTrackId = targetMediaId
        rapidSkipExpiresAtMs = now + 3_000L
        pendingSeekTargetMs = 0L
        pendingSeekExpiresAt = now + 2_500L

        val upcomingWindow = listOfNotNull(
            mediaItems.getOrNull((nextIdx + 1) % mediaItems.size)?.let { knownTracksById[it.mediaId] },
            mediaItems.getOrNull((nextIdx + 2) % mediaItems.size)?.let { knownTracksById[it.mediaId] },
            mediaItems.getOrNull((nextIdx + 3) % mediaItems.size)?.let { knownTracksById[it.mediaId] },
        )
        if (upcomingWindow.isNotEmpty()) {
            AudioPrefetchCache.prefetchUpcomingTracks(context, upcomingWindow, dao)
        }

        playCountTracker.onTrackStarted(targetMediaId, 0L)
        _positionMs.value = 0L
        _playbackState.update {
            it.copy(
                currentTrackId = targetMediaId,
                isPlaying = true,
                isBuffering = true,
                positionMs = 0L,
                durationMs = targetTrack?.durationMs ?: it.durationMs,
                transitionCounter = it.transitionCounter + 1,
            )
        }

        SoundLapaPlaybackService.prepareCleanSingleDeckStart()
        controller.volume = if (isClipAudioMuted) 0f else 1f
        controller.seekTo(nextIdx, 0L)
        controller.playWhenReady = true
        if (controller.playbackState == Player.STATE_IDLE) {
            controller.prepare()
        }
        controller.play()
    }

    fun skipPrevious() {
        val fallbackQueue = _queueTracks.value.ifEmpty { cachedQueue }
            .filter { resolveTrackUri(it) != null }
        val now = System.currentTimeMillis()
        val controller = mediaController
        if (controller == null) {
            if (fallbackQueue.isNotEmpty()) {
                val curId = _playbackState.value.currentTrackId
                val curIdx = fallbackQueue.indexOfFirst { it.id == curId }
                val targetIdx = if (curIdx > 0) curIdx - 1 else fallbackQueue.lastIndex
                playTrack(fallbackQueue[targetIdx], fallbackQueue, startPositionMs = 0L)
            } else {
                connect()
            }
            return
        }

        val effectivePos = if (pendingSeekTargetMs >= 0L && now < pendingSeekExpiresAt) {
            pendingSeekTargetMs
        } else {
            controller.currentPosition
        }
        val inRapidSkipWindow = rapidSkipTrackId != null && now < rapidSkipExpiresAtMs
        if (controller.mediaItemCount > 0 && effectivePos > 3000L && !inRapidSkipWindow) {
            rapidSkipTrackId = controller.currentMediaItem?.mediaId ?: _playbackState.value.currentTrackId
            rapidSkipExpiresAtMs = now + 3_500L
            seekTo(0L)
            return
        }

        val mediaItems = buildControllerMediaItems(controller)
        if (mediaItems.size <= 1) {
            if (fallbackQueue.size > 1) {
                val curId = controller.currentMediaItem?.mediaId ?: _playbackState.value.currentTrackId
                val curIdx = fallbackQueue.indexOfFirst { it.id == curId }
                val targetIdx = if (curIdx > 0) curIdx - 1 else fallbackQueue.lastIndex
                playTrack(fallbackQueue[targetIdx], fallbackQueue, startPositionMs = 0L)
            } else if (mediaItems.isNotEmpty()) {
                seekTo(0L)
            }
            return
        }

        errorRecoveryJob?.cancel()
        autoCrossfadeTriggeredTrackId = null
        val rapidIdx = if (inRapidSkipWindow) {
            mediaItems.indexOfFirst { it.mediaId == rapidSkipTrackId }
        } else {
            -1
        }

        val prevIdx = if (rapidIdx >= 0) {
            if (rapidIdx - 1 >= 0) rapidIdx - 1 else mediaItems.lastIndex
        } else if (controller.hasPreviousMediaItem()) {
            controller.previousMediaItemIndex
        } else {
            val curIdx = controller.currentMediaItemIndex
            if (curIdx > 0) curIdx - 1 else mediaItems.lastIndex
        }
        if (prevIdx !in mediaItems.indices) {
            seekTo(0L)
            return
        }

        val targetMediaId = mediaItems[prevIdx].mediaId
        val targetTrack = knownTracksById[targetMediaId] ?: cachedQueue.find { t -> t.id == targetMediaId }
        rapidSkipTrackId = targetMediaId
        rapidSkipExpiresAtMs = now + 3_000L
        pendingSeekTargetMs = 0L
        pendingSeekExpiresAt = now + 2_500L
        val prevOneMoreIdx = if (prevIdx - 1 >= 0) prevIdx - 1 else mediaItems.lastIndex
        mediaItems.getOrNull(prevOneMoreIdx)?.let { knownTracksById[it.mediaId] }?.let { prevTrack ->
            FullStreamResolver.prefetchTracks(listOf(prevTrack), dao)
        }

        playCountTracker.onTrackStarted(targetMediaId, 0L)
        _positionMs.value = 0L
        _playbackState.update {
            it.copy(
                currentTrackId = targetMediaId,
                isPlaying = true,
                isBuffering = true,
                positionMs = 0L,
                durationMs = targetTrack?.durationMs ?: it.durationMs,
                transitionCounter = it.transitionCounter + 1,
            )
        }

        SoundLapaPlaybackService.prepareCleanSingleDeckStart()
        controller.volume = if (isClipAudioMuted) 0f else 1f
        controller.seekTo(prevIdx, 0L)
        controller.playWhenReady = true
        if (controller.playbackState == Player.STATE_IDLE) {
            controller.prepare()
        }
        controller.play()
    }

    fun toggleShuffle() {
        val controller = mediaController ?: return
        val next = !_playbackState.value.shuffleEnabled
        // Materialize shuffle order directly into the controller queue so Up Next, Play Next,
        // drag/reorder, and 3-track background caching ALWAYS match the exact playback order!
        controller.shuffleModeEnabled = false
        val count = controller.mediaItemCount
        val currentIdx = controller.currentMediaItemIndex
        val currentId = controller.currentMediaItem?.mediaId ?: _playbackState.value.currentTrackId
        if (count > 2 && currentIdx in 0 until count) {
            val currentTracks = _queueTracks.value.ifEmpty {
                List(count) { i -> knownTracksById[controller.getMediaItemAt(i).mediaId] }.filterNotNull()
            }
            if (next) {
                unshuffledQueueBackup = currentTracks
                val currentTrack = currentTracks.find { it.id == currentId } ?: currentTracks.getOrNull(currentIdx)
                val others = currentTracks.filter { it.id != currentTrack?.id }.shuffled()
                val reordered = if (currentTrack != null) listOf(currentTrack) + others else others
                val reorderedItems = reordered.mapNotNull { toMediaItem(it) }
                if (reorderedItems.size >= 2) {
                    // Replace only items before and after current item so audio never hiccups!
                    if (currentIdx + 1 < controller.mediaItemCount) {
                        controller.removeMediaItems(currentIdx + 1, controller.mediaItemCount)
                    }
                    if (currentIdx > 0) {
                        controller.removeMediaItems(0, currentIdx)
                    }
                    controller.addMediaItems(reorderedItems.drop(1))
                    syncQueueOrderFromController(controller)
                    prefetchUpcomingWindow(controller, 0)
                }
            } else if (unshuffledQueueBackup.isNotEmpty()) {
                val restored = unshuffledQueueBackup
                unshuffledQueueBackup = emptyList()
                val restoredCurIdx = restored.indexOfFirst { it.id == currentId }
                if (restoredCurIdx >= 0) {
                    val beforeItems = restored.subList(0, restoredCurIdx).mapNotNull { toMediaItem(it) }
                    val afterItems = restored.subList(restoredCurIdx + 1, restored.size).mapNotNull { toMediaItem(it) }
                    if (currentIdx + 1 < controller.mediaItemCount) {
                        controller.removeMediaItems(currentIdx + 1, controller.mediaItemCount)
                    }
                    if (currentIdx > 0) {
                        controller.removeMediaItems(0, currentIdx)
                    }
                    if (beforeItems.isNotEmpty()) {
                        controller.addMediaItems(0, beforeItems)
                    }
                    if (afterItems.isNotEmpty()) {
                        controller.addMediaItems(afterItems)
                    }
                    syncQueueOrderFromController(controller)
                    prefetchUpcomingWindow(controller, beforeItems.size)
                }
            }
        }
        _playbackState.update { it.copy(shuffleEnabled = next) }
    }

    fun cycleRepeatMode() {
        val controller = mediaController ?: return
        val nextMode = when (controller.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        controller.repeatMode = nextMode
        _playbackState.update { it.copy(repeatMode = nextMode) }
    }

    private fun attachControllerListener(controller: MediaController) {
        controller.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                syncFromController(controller)
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                syncFromController(controller)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                errorRecoveryJob?.cancel()
                val transitioningId = mediaItem?.mediaId
                val now = System.currentTimeMillis()
                val activeRapidId = rapidSkipTrackId?.takeIf { now < rapidSkipExpiresAtMs }
                if (activeRapidId != null && transitioningId != activeRapidId) {
                    return
                }
                if (transitioningId == rapidSkipTrackId) {
                    rapidSkipTrackId = null
                }
                pendingSeekTargetMs = -1L
                autoCrossfadeTriggeredTrackId = null
                transitioningId?.let { errorRetryCounts.remove(it) }
                syncFromController(controller)

                val recentlyCrossfadeHandled = transitioningId != null &&
                    transitioningId == lastCrossfadeActivatedId &&
                    (System.currentTimeMillis() - lastCrossfadeActivatedAtMs) < 1800L
                if (recentlyCrossfadeHandled) {
                    lastCrossfadeActivatedId = null
                    return
                }

                _playbackState.update { it.copy(transitionCounter = it.transitionCounter + 1) }

                prefetchUpcomingWindow(controller)

                transitioningId?.let { id ->
                    playCountTracker.onTrackStarted(id, 0L)
                    val knownTrack = knownTracksById[id]
                    scope.launch(Dispatchers.IO) {
                        if (knownTrack != null && dao.getTrackById(id) == null) {
                            dao.upsertTrack(knownTrack)
                        }
                        preferences.savePlaybackState(id, 0L)
                        if (knownTrack != null) {
                            preferences.recordTrackRecentlyPlayed(knownTrack)
                        }
                    }
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    controller.currentMediaItem?.mediaId?.let { errorRetryCounts.remove(it) }
                }
                syncFromController(controller)
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                _playbackState.update { it.copy(shuffleEnabled = shuffleModeEnabled) }
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                _playbackState.update { it.copy(repeatMode = repeatMode) }
            }

            override fun onPlayerError(error: PlaybackException) {
                val currentMedia = controller.currentMediaItem
                val uri = currentMedia?.localConfiguration?.uri?.toString()
                val sourceUriExtra = currentMedia?.mediaMetadata?.extras?.getString("source_uri")
                val mediaId = currentMedia?.mediaId
                val knownTrack = mediaId?.let { knownTracksById[it] }

                FullStreamResolver.invalidateCachedStream(uri)
                FullStreamResolver.invalidateCachedStream(sourceUriExtra)
                FullStreamResolver.invalidateCachedStream(knownTrack?.sourceUri)
                AudioPrefetchCache.evictTrack(context, mediaId)
                if (knownTrack != null && FullStreamResolver.isShortPreviewTrack(knownTrack)) {
                    FullStreamResolver.invalidateCachedStream(FullStreamResolver.buildLegacyUpgradeUri(knownTrack))
                }

                if (!isNetworkCurrentlyAvailable(context) && (knownTrack == null || !isTrackPlayableOffline(knownTrack))) {
                    SoundLapaPlaybackService.stopActiveCrossfade(restorePrimaryVolume = true)
                    runCatching {
                        controller.volume = 1f
                        controller.playWhenReady = false
                        controller.pause()
                    }
                    _playbackState.update { it.copy(isPlaying = false, isBuffering = false) }
                    onOfflinePlaybackBlocked?.invoke(knownTrack?.title)
                    return
                }

                val attempts = errorRetryCounts[mediaId ?: ""] ?: 0
                if (mediaId != null && attempts < 3 && controller.mediaItemCount > 0) {
                    val nextAttempt = attempts + 1
                    errorRetryCounts[mediaId] = nextAttempt
                    val resumePos = maxOf(controller.currentPosition, _positionMs.value).coerceAtLeast(0L)
                    val idx = controller.currentMediaItemIndex
                    SoundLapaPlaybackService.stopActiveCrossfade(restorePrimaryVolume = true)
                    runCatching { controller.volume = 1f }

                    errorRecoveryJob?.cancel()
                    errorRecoveryJob = scope.launch {
                        if (nextAttempt > 1) {
                            delay(280L)
                        }
                        pendingSeekTargetMs = resumePos
                        pendingSeekExpiresAt = System.currentTimeMillis() + 3000L
                        if (idx in 0 until controller.mediaItemCount) {
                            controller.seekTo(idx, resumePos)
                        } else {
                            controller.seekTo(resumePos)
                        }
                        controller.playWhenReady = true
                        controller.prepare()
                        controller.play()
                        syncFromController(controller)
                    }
                } else {
                    syncFromController(controller)
                }
            }
        })
    }

    private fun resolvePosition(controllerPos: Long): Long {
        val pending = pendingSeekTargetMs
        if (pending >= 0L) {
            val now = System.currentTimeMillis()
            if (abs(controllerPos - pending) <= 1500L || now >= pendingSeekExpiresAt) {
                pendingSeekTargetMs = -1L
            } else {
                return pending
            }
        }
        return controllerPos.coerceAtLeast(0L)
    }

    private fun computeEffectivePlayback(controller: MediaController): Pair<Boolean, Boolean> {
        val state = controller.playbackState
        val buffering = controller.playWhenReady && state == Player.STATE_BUFFERING
        val activelyIntended = controller.playWhenReady &&
            state != Player.STATE_ENDED &&
            state != Player.STATE_IDLE
        val effectivePlaying = controller.isPlaying || activelyIntended
        return effectivePlaying to buffering
    }

    private fun syncFromController(controller: MediaController) {
        val mediaId = controller.currentMediaItem?.mediaId
        val now = System.currentTimeMillis()
        val activeRapidId = rapidSkipTrackId?.takeIf { now < rapidSkipExpiresAtMs }
        if (activeRapidId != null && mediaId != activeRapidId) {
            _playbackState.update {
                it.copy(
                    shuffleEnabled = controller.shuffleModeEnabled,
                    repeatMode = controller.repeatMode,
                )
            }
            return
        }
        if (mediaId != null && mediaId == rapidSkipTrackId) {
            rapidSkipTrackId = null
        }

        val duration = controller.duration.takeIf { it > 0L }
            ?: mediaId?.let { knownTracksById[it]?.durationMs }
            ?: cachedQueue.find { it.id == mediaId }?.durationMs
            ?: 0L
        val resolvedPos = resolvePosition(controller.currentPosition)
        val (effectivePlaying, buffering) = computeEffectivePlayback(controller)
        _positionMs.value = resolvedPos
        _playbackState.update {
            it.copy(
                currentTrackId = mediaId ?: it.currentTrackId,
                isPlaying = effectivePlaying,
                isBuffering = buffering,
                positionMs = resolvedPos,
                durationMs = duration,
                shuffleEnabled = it.shuffleEnabled,
                repeatMode = controller.repeatMode,
            )
        }
        wakeTicker()
    }

    private fun startPositionTicker() {
        positionTickerJob?.cancel()
        positionTickerJob = scope.launch {
            var lastPersistedTrackId: String? = null
            var lastPersistedPosMs = -1L
            var activeListeningTrackId: String? = null
            var lastListeningWallMs = 0L
            var pendingListeningMs = 0L

            fun flushPendingListening(trackId: String?, force: Boolean) {
                if (trackId == null) return
                val minThreshold = if (force) 1000L else 30_000L
                if (pendingListeningMs >= minThreshold) {
                    val deltaToFlush = pendingListeningMs
                    pendingListeningMs = 0L
                    val trackObj = knownTracksById[trackId]
                        ?: cachedQueue.find { it.id == trackId }
                    if (trackObj != null) {
                        launch(Dispatchers.IO) {
                            preferences.recordListeningProgress(trackObj, deltaToFlush)
                        }
                    }
                } else if (force) {
                    pendingListeningMs = 0L
                }
            }

            while (isActive) {
                val controller = mediaController
                var isActivelyRunning = false
                if (controller != null && controller.mediaItemCount > 0) {
                    val controllerMediaId = controller.currentMediaItem?.mediaId
                    val now = System.currentTimeMillis()
                    val activeRapidId = rapidSkipTrackId?.takeIf { now < rapidSkipExpiresAtMs }
                    val binderMatchesTarget = activeRapidId == null || controllerMediaId == activeRapidId
                    if (controllerMediaId != null && controllerMediaId == rapidSkipTrackId) {
                        rapidSkipTrackId = null
                    }

                    val pos = if (binderMatchesTarget) {
                        resolvePosition(controller.currentPosition)
                    } else {
                        _positionMs.value
                    }
                    val actualPlayerDuration = if (binderMatchesTarget) {
                        controller.duration.takeIf { it > 0L } ?: 0L
                    } else {
                        0L
                    }
                    val dur = actualPlayerDuration.takeIf { it > 0L } ?: _playbackState.value.durationMs
                    val (playing, buffering) = if (binderMatchesTarget) {
                        computeEffectivePlayback(controller)
                    } else {
                        true to _playbackState.value.isBuffering
                    }
                    isActivelyRunning = playing || buffering

                    // Track actual elapsed wall-clock listening time for Week/Month/Year stats + 40s play credit
                    if (controllerMediaId != activeListeningTrackId) {
                        flushPendingListening(activeListeningTrackId, force = true)
                        activeListeningTrackId = controllerMediaId
                        lastListeningWallMs = if (playing && controller.isPlaying) now else 0L
                    } else if (playing && controller.isPlaying && controllerMediaId != null) {
                        val wallDelta = if (lastListeningWallMs > 0L) {
                            (now - lastListeningWallMs).coerceIn(0L, 1500L)
                        } else {
                            0L
                        }
                        pendingListeningMs += wallDelta
                        lastListeningWallMs = now
                        flushPendingListening(controllerMediaId, force = false)

                        val shouldCreditPlay = playCountTracker.onProgressSample(
                            trackId = controllerMediaId,
                            posMs = pos,
                            isActuallyPlaying = true,
                            wallDeltaMs = wallDelta,
                            expectPendingSeek = pendingSeekTargetMs >= 0L && now < pendingSeekExpiresAt,
                        )
                        if (shouldCreditPlay) {
                            val creditedTrack = knownTracksById[controllerMediaId]
                                ?: cachedQueue.find { it.id == controllerMediaId }
                            launch(Dispatchers.IO) {
                                if (creditedTrack != null && dao.getTrackById(controllerMediaId) == null) {
                                    dao.upsertTrack(creditedTrack)
                                }
                                dao.incrementPlayCount(controllerMediaId)
                                if (creditedTrack != null) {
                                    preferences.recordTrackPlayCredit(creditedTrack)
                                }
                            }
                        }
                    } else {
                        flushPendingListening(activeListeningTrackId, force = true)
                        lastListeningWallMs = 0L
                    }

                    if (_positionMs.value != pos) {
                        _positionMs.value = pos
                    }
                    val prev = _playbackState.value
                    val resolvedTrackId = if (binderMatchesTarget && controllerMediaId != null) {
                        controllerMediaId
                    } else {
                        prev.currentTrackId
                    }
                    if (prev.currentTrackId != resolvedTrackId ||
                        prev.durationMs != dur ||
                        prev.isPlaying != playing ||
                        prev.isBuffering != buffering
                    ) {
                        _playbackState.update {
                            it.copy(
                                currentTrackId = resolvedTrackId ?: it.currentTrackId,
                                durationMs = dur,
                                isPlaying = playing,
                                isBuffering = buffering,
                                positionMs = pos,
                            )
                        }
                    }

                    val currentId = controller.currentMediaItem?.mediaId
                    if (playing && currentId != null &&
                        (currentId != lastPersistedTrackId || abs(pos - lastPersistedPosMs) >= 4_000L)
                    ) {
                        lastPersistedTrackId = currentId
                        lastPersistedPosMs = pos
                        preferences.savePlaybackPositionLightweight(currentId, pos)
                    }
                } else {
                    flushPendingListening(activeListeningTrackId, force = true)
                    activeListeningTrackId = null
                    lastListeningWallMs = 0L
                }
                val nextDelayMs = when {
                    controller == null || controller.mediaItemCount == 0 -> 900L
                    isActivelyRunning -> 200L
                    else -> 750L
                }
                withTimeoutOrNull(nextDelayMs) {
                    tickerWakeChannel.receive()
                }
            }
        }
    }

    private fun toMediaItem(track: TrackEntity): MediaItem? {
        val uri = resolveTrackUri(track) ?: return null
        val extras = Bundle().apply {
            track.sourceUri?.let { putString("source_uri", it) }
            putLong("duration_ms", track.durationMs)
        }
        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setGenre(track.genre)
            .setExtras(extras)
        track.albumTitle?.takeIf { it.isNotBlank() }?.let { album ->
            metadataBuilder.setAlbumTitle(album)
        }

        track.coverArtPath?.let { cover ->
            if (cover.startsWith("http://") || cover.startsWith("https://")) {
                metadataBuilder.setArtworkUri(Uri.parse(cover))
            } else if (cover.isNotBlank()) {
                metadataBuilder.setArtworkUri(Uri.fromFile(File(cover)))
            }
        }

        return MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(uri)
            .setRequestMetadata(
                MediaItem.RequestMetadata.Builder()
                    .setMediaUri(uri)
                    .build(),
            )
            .setMediaMetadata(metadataBuilder.build())
            .build()
    }

    private fun resolveTrackUri(track: TrackEntity): Uri? {
        if (FullStreamResolver.isShortPreviewTrack(track)) {
            return Uri.parse(FullStreamResolver.buildLegacyUpgradeUri(track))
        }
        val cachedPath = track.cachedFilePath
        if (!cachedPath.isNullOrBlank()) {
            return Uri.fromFile(File(cachedPath))
        }
        return track.sourceUri?.let { Uri.parse(it) }
    }
}
