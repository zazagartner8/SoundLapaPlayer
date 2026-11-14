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
)

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

    init {
        scope.launch {
            SoundLapaPlaybackService.audioFocusStatus.collect { focusStatus ->
                _playbackState.update { it.copy(audioFocusStatus = focusStatus) }
            }
        }
        scope.launch {
            preferences.settings.collect { settings ->
                FullStreamResolver.isDataSaverEnabled = settings.dataSaverEnabled
                smoothTransitionEnabled = settings.smoothTransitionEnabled
                smoothTransitionDurationSec = settings.smoothTransitionDurationSec.coerceIn(1, 10)
                if (!smoothTransitionEnabled) {
                    SoundLapaPlaybackService.stopActiveCrossfade(restorePrimaryVolume = true)
                    mediaController?.volume = 1f
                }
            }
        }
    }

    fun connect() {
        if (controllerFuture != null) return
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
                    controller.volume = 1f
                    attachControllerListener(controller)
                    startPositionTicker()
                    val pending = pendingPlayRequest
                    val togglePending = pendingTogglePlay
                    pendingPlayRequest = null
                    pendingTogglePlay = false
                    if (pending != null) {
                        playTrack(pending.track, pending.queue, pending.startPositionMs)
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

    fun disconnect() {
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

    fun isTrackActiveInController(trackId: String): Boolean {
        val controller = mediaController ?: return false
        return controller.mediaItemCount > 0 && controller.currentMediaItem?.mediaId == trackId
    }

    fun playFromActiveQueue(track: TrackEntity) {
        val active = _queueTracks.value.ifEmpty { cachedQueue }
        playTrack(track, active, startPositionMs = 0L)
    }

    fun playTrack(track: TrackEntity, queue: List<TrackEntity> = cachedQueue, startPositionMs: Long = 0L) {
        knownTracksById[track.id] = track
        queue.forEach { knownTracksById[it.id] = it }

        val controller = mediaController ?: run {
            pendingPlayRequest = PendingPlayRequest(track, queue, startPositionMs)
            // Start resolving direct audio URL immediately in parallel with service bind
            FullStreamResolver.prefetchTracks(listOf(track), dao)
            // Optimistic UI feedback so 1 tap never feels ignored while binding MediaController
            _playbackState.update {
                it.copy(
                    currentTrackId = track.id,
                    isPlaying = true,
                    isBuffering = true,
                    durationMs = track.durationMs,
                )
            }
            connect()
            return
        }
        pendingPlayRequest = null
        pendingTogglePlay = false
        errorRecoveryJob?.cancel()
        errorRetryCounts.remove(track.id)

        val playableQueue = queue.filter { resolveTrackUri(it) != null }.ifEmpty {
            listOfNotNull(track.takeIf { resolveTrackUri(it) != null })
        }
        _queueTracks.value = playableQueue
        val targetIndex = playableQueue.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        val mediaItems = playableQueue.mapNotNull { toMediaItem(it) }
        if (mediaItems.isEmpty()) return

        val outgoingId = controller.currentMediaItem?.mediaId
        val shouldCrossfade = smoothTransitionEnabled &&
            controller.isPlaying &&
            controller.mediaItemCount > 0 &&
            outgoingId != null &&
            outgoingId != track.id

        pendingSeekTargetMs = startPositionMs
        pendingSeekExpiresAt = System.currentTimeMillis() + 1200L
        autoCrossfadeTriggeredTrackId = null

        if (shouldCrossfade) {
            val configuredFadeMs = (smoothTransitionDurationSec * 1000L).coerceIn(1000L, 10_000L)
            val currentPos = controller.currentPosition.coerceAtLeast(0L)
            val totalDur = controller.duration.takeIf { it > 0L } ?: 0L
            val effectiveFadeMs = if (totalDur > currentPos + 450L) {
                min(configuredFadeMs, totalDur - currentPos).coerceAtLeast(500L)
            } else {
                configuredFadeMs
            }

            rapidSkipTrackId = track.id
            rapidSkipExpiresAtMs = System.currentTimeMillis() + 900L

            SoundLapaPlaybackService.performDualDeckCrossfade(
                mediaItems = mediaItems,
                targetIndex = targetIndex,
                startPositionMs = startPositionMs,
                fadeDurationMs = effectiveFadeMs,
                repeatMode = controller.repeatMode,
                shuffleEnabled = controller.shuffleModeEnabled,
                maxLeadWaitMs = 0L,
                fallbackApplyOnActive = {
                    startMediaItems(
                        controller = controller,
                        mediaItems = mediaItems,
                        targetIndex = targetIndex,
                        startPositionMs = startPositionMs,
                        track = track,
                        prefetchQueue = playableQueue,
                        initialVolume = 1f,
                    )
                },
                onDeckActivated = { _, isReady ->
                    onCrossfadeDeckActivated(
                        track = track,
                        queue = playableQueue,
                        startPositionMs = startPositionMs,
                        isReady = isReady,
                    )
                },
            )
        } else {
            rapidSkipTrackId = track.id
            rapidSkipExpiresAtMs = System.currentTimeMillis() + 900L
            SoundLapaPlaybackService.stopActiveCrossfade(restorePrimaryVolume = true)
            startMediaItems(
                controller = controller,
                mediaItems = mediaItems,
                targetIndex = targetIndex,
                startPositionMs = startPositionMs,
                track = track,
                prefetchQueue = playableQueue,
                initialVolume = 1f,
            )
        }
    }

    private fun onCrossfadeDeckActivated(
        track: TrackEntity,
        queue: List<TrackEntity>,
        startPositionMs: Long,
        isReady: Boolean = true,
    ) {
        val safeStart = startPositionMs.coerceAtLeast(0L)
        lastCrossfadeActivatedId = track.id
        lastCrossfadeActivatedAtMs = System.currentTimeMillis()
        pendingSeekTargetMs = safeStart
        pendingSeekExpiresAt = System.currentTimeMillis() + 1200L

        val currentIdxInQueue = queue.indexOfFirst { it.id == track.id }
        val upcoming = if (currentIdxInQueue >= 0) {
            queue.drop(currentIdxInQueue + 1).take(2)
        } else {
            emptyList()
        }
        FullStreamResolver.prefetchTracks(upcoming, dao)

        _positionMs.value = safeStart
        _playbackState.update {
            it.copy(
                currentTrackId = track.id,
                isPlaying = true,
                isBuffering = !isReady,
                positionMs = safeStart,
                durationMs = track.durationMs,
                transitionCounter = it.transitionCounter + 1,
            )
        }

        scope.launch(Dispatchers.IO) {
            if (dao.getTrackById(track.id) == null) {
                dao.upsertTrack(track)
            }
            dao.incrementPlayCount(track.id)
            preferences.savePlaybackState(track.id, safeStart)
            preferences.recordTrackPlayed(track)
        }
    }

    private fun startMediaItems(
        controller: MediaController,
        mediaItems: List<MediaItem>,
        targetIndex: Int,
        startPositionMs: Long,
        track: TrackEntity,
        prefetchQueue: List<TrackEntity>,
        initialVolume: Float?,
    ) {
        val safeStart = startPositionMs.coerceAtLeast(0L)
        if (initialVolume != null) {
            controller.volume = initialVolume
        }
        controller.setMediaItems(mediaItems, targetIndex, safeStart)
        controller.playWhenReady = true
        controller.prepare()
        controller.play()

        // Prefetch next 2 tracks in queue so track switching & crossfading stay instant
        val currentIdxInQueue = prefetchQueue.indexOfFirst { it.id == track.id }
        val upcoming = if (currentIdxInQueue >= 0) {
            prefetchQueue.drop(currentIdxInQueue + 1).take(2)
        } else {
            emptyList()
        }
        FullStreamResolver.prefetchTracks(upcoming, dao)

        _positionMs.value = safeStart
        _playbackState.update {
            it.copy(
                currentTrackId = track.id,
                isPlaying = true,
                isBuffering = true,
                positionMs = safeStart,
                durationMs = track.durationMs,
                transitionCounter = it.transitionCounter + 1,
            )
        }

        scope.launch(Dispatchers.IO) {
            if (dao.getTrackById(track.id) == null) {
                dao.upsertTrack(track)
            }
            dao.incrementPlayCount(track.id)
            preferences.savePlaybackState(track.id, safeStart)
            preferences.recordTrackPlayed(track)
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
            controller.volume = 1f
            controller.pause()
            _playbackState.update { it.copy(isPlaying = false, isBuffering = false) }
        } else {
            if (controller.playbackState == Player.STATE_IDLE) {
                controller.prepare()
            } else if (controller.playbackState == Player.STATE_ENDED) {
                pendingSeekTargetMs = 0L
                pendingSeekExpiresAt = System.currentTimeMillis() + 900L
                controller.seekTo(0L)
            }
            controller.volume = 1f
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

        SoundLapaPlaybackService.stopActiveCrossfade(restorePrimaryVolume = true)
        runCatching { controller.volume = 1f }
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

        autoCrossfadeTriggeredTrackId = null
        val now = System.currentTimeMillis()
        val rapidIdx = if (!controller.shuffleModeEnabled &&
            rapidSkipTrackId != null &&
            now < rapidSkipExpiresAtMs
        ) {
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
        rapidSkipExpiresAtMs = now + 2_500L
        pendingSeekTargetMs = 0L
        pendingSeekExpiresAt = now + 2_500L

        val upcomingNext = mediaItems.getOrNull((nextIdx + 1) % mediaItems.size)
            ?.let { knownTracksById[it.mediaId] }
        if (upcomingNext != null) {
            FullStreamResolver.prefetchTracks(listOf(upcomingNext), dao)
        }

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

        if (smoothTransitionEnabled && controller.isPlaying) {
            val configuredFadeMs = (smoothTransitionDurationSec * 1000L).coerceIn(1000L, 10_000L)
            val currentPos = controller.currentPosition.coerceAtLeast(0L)
            val totalDur = controller.duration.takeIf { it > 0L } ?: 0L
            val effectiveFadeMs = if (totalDur > currentPos + 450L) {
                min(configuredFadeMs, totalDur - currentPos).coerceAtLeast(500L)
            } else {
                configuredFadeMs
            }

            SoundLapaPlaybackService.performDualDeckCrossfade(
                mediaItems = mediaItems,
                targetIndex = nextIdx,
                startPositionMs = 0L,
                fadeDurationMs = effectiveFadeMs,
                repeatMode = controller.repeatMode,
                shuffleEnabled = controller.shuffleModeEnabled,
                maxLeadWaitMs = 0L,
                fallbackApplyOnActive = {
                    controller.volume = 1f
                    controller.seekTo(nextIdx, 0L)
                    controller.playWhenReady = true
                    if (controller.playbackState == Player.STATE_IDLE) {
                        controller.prepare()
                    }
                    controller.play()
                    _playbackState.update { it.copy(transitionCounter = it.transitionCounter + 1) }
                },
                onDeckActivated = { _, isReady ->
                    if (targetTrack != null) {
                        val activeQueue = _queueTracks.value.ifEmpty { cachedQueue }
                        onCrossfadeDeckActivated(targetTrack, activeQueue, 0L, isReady = isReady)
                    } else {
                        _positionMs.value = 0L
                        _playbackState.update {
                            it.copy(
                                currentTrackId = targetMediaId,
                                isPlaying = true,
                                isBuffering = !isReady,
                                positionMs = 0L,
                                transitionCounter = it.transitionCounter + 1,
                            )
                        }
                    }
                },
            )
        } else {
            SoundLapaPlaybackService.stopActiveCrossfade(restorePrimaryVolume = true)
            controller.volume = 1f
            controller.seekTo(nextIdx, 0L)
            controller.playWhenReady = true
            if (controller.playbackState == Player.STATE_IDLE) {
                controller.prepare()
            }
            controller.play()
        }
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

        autoCrossfadeTriggeredTrackId = null
        val rapidIdx = if (!controller.shuffleModeEnabled && inRapidSkipWindow) {
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
        rapidSkipExpiresAtMs = now + 2_500L
        pendingSeekTargetMs = 0L
        pendingSeekExpiresAt = now + 2_500L

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

        if (smoothTransitionEnabled && controller.isPlaying) {
            val configuredFadeMs = (smoothTransitionDurationSec * 1000L).coerceIn(1000L, 10_000L)
            SoundLapaPlaybackService.performDualDeckCrossfade(
                mediaItems = mediaItems,
                targetIndex = prevIdx,
                startPositionMs = 0L,
                fadeDurationMs = configuredFadeMs,
                repeatMode = controller.repeatMode,
                shuffleEnabled = controller.shuffleModeEnabled,
                maxLeadWaitMs = 0L,
                fallbackApplyOnActive = {
                    controller.volume = 1f
                    controller.seekTo(prevIdx, 0L)
                    controller.playWhenReady = true
                    if (controller.playbackState == Player.STATE_IDLE) {
                        controller.prepare()
                    }
                    controller.play()
                    _playbackState.update { it.copy(transitionCounter = it.transitionCounter + 1) }
                },
                onDeckActivated = { _, isReady ->
                    if (targetTrack != null) {
                        val activeQueue = _queueTracks.value.ifEmpty { cachedQueue }
                        onCrossfadeDeckActivated(targetTrack, activeQueue, 0L, isReady = isReady)
                    } else {
                        _positionMs.value = 0L
                        _playbackState.update {
                            it.copy(
                                currentTrackId = targetMediaId,
                                isPlaying = true,
                                isBuffering = !isReady,
                                positionMs = 0L,
                                transitionCounter = it.transitionCounter + 1,
                            )
                        }
                    }
                },
            )
        } else {
            SoundLapaPlaybackService.stopActiveCrossfade(restorePrimaryVolume = true)
            controller.volume = 1f
            controller.seekTo(prevIdx, 0L)
            controller.playWhenReady = true
            if (controller.playbackState == Player.STATE_IDLE) {
                controller.prepare()
            }
            controller.play()
        }
    }

    fun toggleShuffle() {
        val controller = mediaController ?: return
        val next = !controller.shuffleModeEnabled
        controller.shuffleModeEnabled = next
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

                val nextIdx = controller.currentMediaItemIndex + 1
                if (nextIdx in 0 until controller.mediaItemCount) {
                    val nextTrack = knownTracksById[controller.getMediaItemAt(nextIdx).mediaId]
                    if (nextTrack != null) {
                        FullStreamResolver.prefetchTracks(listOf(nextTrack), dao)
                    }
                }

                transitioningId?.let { id ->
                    val knownTrack = knownTracksById[id]
                    scope.launch(Dispatchers.IO) {
                        if (knownTrack != null && dao.getTrackById(id) == null) {
                            dao.upsertTrack(knownTrack)
                        }
                        dao.incrementPlayCount(id)
                        preferences.savePlaybackState(id, 0L)
                        if (knownTrack != null) {
                            preferences.recordTrackPlayed(knownTrack)
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
        val effectivePlaying = controller.isPlaying || buffering
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
                shuffleEnabled = controller.shuffleModeEnabled,
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

                    if (_positionMs.value != pos) {
                        _positionMs.value = pos
                    }
                    val prev = _playbackState.value
                    if (prev.durationMs != dur || prev.isPlaying != playing || prev.isBuffering != buffering) {
                        _playbackState.update {
                            it.copy(
                                durationMs = dur,
                                isPlaying = playing,
                                isBuffering = buffering,
                                positionMs = pos,
                            )
                        }
                    }

                    // Spotify-style automatic overlapping crossfade near the end of the track
                    val currentId = controller.currentMediaItem?.mediaId
                    if (smoothTransitionEnabled &&
                        controller.isPlaying &&
                        controller.playbackState == Player.STATE_READY &&
                        currentId != null &&
                        autoCrossfadeTriggeredTrackId != currentId &&
                        controller.hasNextMediaItem() &&
                        controller.repeatMode != Player.REPEAT_MODE_ONE &&
                        actualPlayerDuration > 3000L
                    ) {
                        val configuredFadeMs = (smoothTransitionDurationSec * 1000L).coerceIn(1000L, 10_000L)
                        val effectiveFadeMs = min(configuredFadeMs, (actualPlayerDuration / 2L).coerceAtLeast(800L))
                        val remaining = actualPlayerDuration - pos
                        if (remaining in 400L..(effectiveFadeMs + 2200L)) {
                            val nextIdx = controller.nextMediaItemIndex
                            val mediaItems = buildControllerMediaItems(controller)
                            if (nextIdx in mediaItems.indices) {
                                autoCrossfadeTriggeredTrackId = currentId
                                val nextMediaId = mediaItems[nextIdx].mediaId
                                val nextTrack = knownTracksById[nextMediaId] ?: cachedQueue.find { it.id == nextMediaId }
                                SoundLapaPlaybackService.performDualDeckCrossfade(
                                    mediaItems = mediaItems,
                                    targetIndex = nextIdx,
                                    startPositionMs = 0L,
                                    fadeDurationMs = effectiveFadeMs,
                                    repeatMode = controller.repeatMode,
                                    shuffleEnabled = controller.shuffleModeEnabled,
                                    maxLeadWaitMs = 2400L,
                                    fallbackApplyOnActive = {
                                        controller.volume = 1f
                                    },
                                    onDeckActivated = { _, isReady ->
                                        if (nextTrack != null) {
                                            val activeQueue = _queueTracks.value.ifEmpty { cachedQueue }
                                            onCrossfadeDeckActivated(nextTrack, activeQueue, 0L, isReady = isReady)
                                        } else {
                                            _playbackState.update { it.copy(transitionCounter = it.transitionCounter + 1) }
                                        }
                                    },
                                )
                            }
                        }
                    }

                    if (playing && currentId != null &&
                        (currentId != lastPersistedTrackId || abs(pos - lastPersistedPosMs) >= 2500L)
                    ) {
                        lastPersistedTrackId = currentId
                        lastPersistedPosMs = pos
                        launch(Dispatchers.IO) {
                            preferences.savePlaybackState(currentId, pos)
                        }
                    }
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
        }
        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setGenre(track.genre)
            .setExtras(extras)

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
