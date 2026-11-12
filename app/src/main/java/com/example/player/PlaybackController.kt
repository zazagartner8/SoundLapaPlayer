package com.example.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
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
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs
import kotlin.math.min

@Immutable
data class PlaybackUiState(
    val currentTrackId: String? = null,
    val isPlaying: Boolean = false,
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

    private var pendingSeekTargetMs: Long = -1L
    private var pendingSeekExpiresAt: Long = 0L

    private var smoothTransitionEnabled: Boolean = true
    private var smoothTransitionDurationSec: Int = 3
    private var autoCrossfadeTriggeredTrackId: String? = null

    private val _playbackState = MutableStateFlow(PlaybackUiState())
    val playbackState: StateFlow<PlaybackUiState> = _playbackState.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    init {
        scope.launch {
            SoundLapaPlaybackService.audioFocusStatus.collect { focusStatus ->
                _playbackState.update { it.copy(audioFocusStatus = focusStatus) }
            }
        }
        scope.launch {
            preferences.settings.collect { settings ->
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
                    syncFromController(controller)
                    startPositionTicker()
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    fun disconnect() {
        SoundLapaPlaybackService.stopActiveCrossfade(restorePrimaryVolume = true)
        positionTickerJob?.cancel()
        positionTickerJob = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        mediaController = null
    }

    fun updateAvailableTracks(tracks: List<TrackEntity>) {
        cachedQueue = tracks
    }

    private fun resolveCurrentOutgoingMediaItem(controller: MediaController): MediaItem? {
        val currentId = controller.currentMediaItem?.mediaId
        if (currentId != null) {
            val freshTrack = cachedQueue.find { it.id == currentId }
            if (freshTrack != null) {
                toMediaItem(freshTrack)?.let { return it }
            }
        }
        return controller.currentMediaItem
    }

    fun playTrack(track: TrackEntity, queue: List<TrackEntity> = cachedQueue, startPositionMs: Long = 0L) {
        val controller = mediaController ?: run {
            connect()
            return
        }

        val playableQueue = queue.filter { resolveTrackUri(it) != null }.ifEmpty {
            listOfNotNull(track.takeIf { resolveTrackUri(it) != null })
        }
        val targetIndex = playableQueue.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        val mediaItems = playableQueue.mapNotNull { toMediaItem(it) }
        if (mediaItems.isEmpty()) return

        val outgoingItem = resolveCurrentOutgoingMediaItem(controller)
        val shouldCrossfade = smoothTransitionEnabled &&
            controller.isPlaying &&
            controller.mediaItemCount > 0 &&
            outgoingItem != null &&
            outgoingItem.mediaId != track.id

        pendingSeekTargetMs = startPositionMs
        pendingSeekExpiresAt = System.currentTimeMillis() + 1200L
        autoCrossfadeTriggeredTrackId = null

        if (shouldCrossfade && outgoingItem != null) {
            val configuredFadeMs = (smoothTransitionDurationSec * 1000L).coerceIn(1000L, 10_000L)
            val currentPos = controller.currentPosition.coerceAtLeast(0L)
            val totalDur = controller.duration.takeIf { it > 0L } ?: 0L
            val effectiveFadeMs = if (totalDur > currentPos + 400L) {
                min(configuredFadeMs, totalDur - currentPos).coerceAtLeast(500L)
            } else {
                configuredFadeMs
            }

            SoundLapaPlaybackService.performDualDeckCrossfade(
                outgoingItem = outgoingItem,
                outgoingPositionMs = currentPos,
                fadeDurationMs = effectiveFadeMs,
            ) {
                startMediaItems(
                    controller = controller,
                    mediaItems = mediaItems,
                    targetIndex = targetIndex,
                    startPositionMs = startPositionMs,
                    track = track,
                    initialVolume = 0f,
                )
            }
        } else {
            SoundLapaPlaybackService.stopActiveCrossfade(restorePrimaryVolume = true)
            startMediaItems(
                controller = controller,
                mediaItems = mediaItems,
                targetIndex = targetIndex,
                startPositionMs = startPositionMs,
                track = track,
                initialVolume = 1f,
            )
        }
    }

    private fun startMediaItems(
        controller: MediaController,
        mediaItems: List<MediaItem>,
        targetIndex: Int,
        startPositionMs: Long,
        track: TrackEntity,
        initialVolume: Float,
    ) {
        val safeStart = startPositionMs.coerceAtLeast(0L)
        controller.volume = initialVolume
        controller.setMediaItems(mediaItems, targetIndex, safeStart)
        controller.prepare()
        controller.play()

        // Prefetch next 2 tracks in queue so track switching & crossfading stay instant
        val currentIdxInQueue = cachedQueue.indexOfFirst { it.id == track.id }
        val upcoming = if (currentIdxInQueue >= 0) {
            cachedQueue.drop(currentIdxInQueue + 1).take(2)
        } else {
            emptyList()
        }
        FullStreamResolver.prefetchTracks(upcoming, dao)

        _positionMs.value = safeStart
        _playbackState.update {
            it.copy(
                currentTrackId = track.id,
                isPlaying = true,
                positionMs = safeStart,
                durationMs = track.durationMs,
                transitionCounter = it.transitionCounter + 1,
            )
        }

        scope.launch(Dispatchers.IO) {
            dao.incrementPlayCount(track.id)
            preferences.savePlaybackState(track.id, safeStart)
        }
    }

    fun togglePlayPause() {
        val controller = mediaController ?: return
        if (controller.mediaItemCount == 0 && cachedQueue.isNotEmpty()) {
            val firstPlayable = cachedQueue.firstOrNull { resolveTrackUri(it) != null }
            if (firstPlayable != null) {
                playTrack(firstPlayable, cachedQueue)
            }
            return
        }
        if (controller.isPlaying) {
            SoundLapaPlaybackService.stopActiveCrossfade(restorePrimaryVolume = true)
            controller.volume = 1f
            controller.pause()
        } else {
            if (controller.playbackState == Player.STATE_ENDED) {
                pendingSeekTargetMs = 0L
                pendingSeekExpiresAt = System.currentTimeMillis() + 900L
                controller.seekTo(0L)
            }
            controller.volume = 1f
            controller.play()
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

    fun skipNext() {
        val controller = mediaController ?: return
        if (!controller.hasNextMediaItem()) return
        autoCrossfadeTriggeredTrackId = null

        val outgoingItem = resolveCurrentOutgoingMediaItem(controller)
        if (smoothTransitionEnabled && controller.isPlaying && outgoingItem != null) {
            val configuredFadeMs = (smoothTransitionDurationSec * 1000L).coerceIn(1000L, 10_000L)
            val currentPos = controller.currentPosition.coerceAtLeast(0L)
            val totalDur = controller.duration.takeIf { it > 0L } ?: 0L
            val effectiveFadeMs = if (totalDur > currentPos + 400L) {
                min(configuredFadeMs, totalDur - currentPos).coerceAtLeast(500L)
            } else {
                configuredFadeMs
            }

            SoundLapaPlaybackService.performDualDeckCrossfade(
                outgoingItem = outgoingItem,
                outgoingPositionMs = currentPos,
                fadeDurationMs = effectiveFadeMs,
            ) {
                controller.volume = 0f
                controller.seekToNextMediaItem()
                controller.play()
                _playbackState.update { it.copy(transitionCounter = it.transitionCounter + 1) }
            }
        } else {
            SoundLapaPlaybackService.stopActiveCrossfade(restorePrimaryVolume = true)
            controller.volume = 1f
            controller.seekToNextMediaItem()
            controller.play()
            _playbackState.update { it.copy(transitionCounter = it.transitionCounter + 1) }
        }
    }

    fun skipPrevious() {
        val controller = mediaController ?: return
        if (controller.currentPosition > 3000L) {
            seekTo(0L)
            return
        }
        if (controller.hasPreviousMediaItem()) {
            autoCrossfadeTriggeredTrackId = null
            val outgoingItem = resolveCurrentOutgoingMediaItem(controller)
            if (smoothTransitionEnabled && controller.isPlaying && outgoingItem != null) {
                val configuredFadeMs = (smoothTransitionDurationSec * 1000L).coerceIn(1000L, 10_000L)
                val currentPos = controller.currentPosition.coerceAtLeast(0L)

                SoundLapaPlaybackService.performDualDeckCrossfade(
                    outgoingItem = outgoingItem,
                    outgoingPositionMs = currentPos,
                    fadeDurationMs = configuredFadeMs,
                ) {
                    controller.volume = 0f
                    controller.seekToPreviousMediaItem()
                    controller.play()
                    _playbackState.update { it.copy(transitionCounter = it.transitionCounter + 1) }
                }
            } else {
                SoundLapaPlaybackService.stopActiveCrossfade(restorePrimaryVolume = true)
                controller.volume = 1f
                controller.seekToPreviousMediaItem()
                controller.play()
                _playbackState.update { it.copy(transitionCounter = it.transitionCounter + 1) }
            }
        } else {
            seekTo(0L)
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

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                pendingSeekTargetMs = -1L
                autoCrossfadeTriggeredTrackId = null
                syncFromController(controller)
                _playbackState.update { it.copy(transitionCounter = it.transitionCounter + 1) }
                mediaItem?.mediaId?.let { id ->
                    scope.launch(Dispatchers.IO) {
                        dao.incrementPlayCount(id)
                        preferences.savePlaybackState(id, 0L)
                    }
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                syncFromController(controller)
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                _playbackState.update { it.copy(shuffleEnabled = shuffleModeEnabled) }
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                _playbackState.update { it.copy(repeatMode = repeatMode) }
            }

            override fun onPlayerError(error: PlaybackException) {
                val uri = controller.currentMediaItem?.localConfiguration?.uri?.toString()
                FullStreamResolver.invalidateCachedStream(uri)
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

    private fun syncFromController(controller: MediaController) {
        val mediaId = controller.currentMediaItem?.mediaId
        val duration = controller.duration.takeIf { it > 0L }
            ?: cachedQueue.find { it.id == mediaId }?.durationMs
            ?: 0L
        val resolvedPos = resolvePosition(controller.currentPosition)
        _positionMs.value = resolvedPos
        _playbackState.update {
            it.copy(
                currentTrackId = mediaId ?: it.currentTrackId,
                isPlaying = controller.isPlaying,
                positionMs = resolvedPos,
                durationMs = duration,
                shuffleEnabled = controller.shuffleModeEnabled,
                repeatMode = controller.repeatMode,
            )
        }
    }

    private fun startPositionTicker() {
        positionTickerJob?.cancel()
        positionTickerJob = scope.launch {
            var ticks = 0
            while (isActive) {
                val controller = mediaController
                if (controller != null && controller.mediaItemCount > 0) {
                    val pos = resolvePosition(controller.currentPosition)
                    val dur = controller.duration.takeIf { it > 0L } ?: _playbackState.value.durationMs
                    val playing = controller.isPlaying

                    if (_positionMs.value != pos) {
                        _positionMs.value = pos
                    }
                    val prev = _playbackState.value
                    if (prev.durationMs != dur || prev.isPlaying != playing) {
                        _playbackState.update {
                            it.copy(
                                durationMs = dur,
                                isPlaying = playing,
                                positionMs = pos,
                            )
                        }
                    }

                    // Spotify-style automatic overlapping crossfade near the end of the track
                    val currentId = controller.currentMediaItem?.mediaId
                    if (smoothTransitionEnabled &&
                        playing &&
                        currentId != null &&
                        autoCrossfadeTriggeredTrackId != currentId &&
                        controller.hasNextMediaItem() &&
                        controller.repeatMode != Player.REPEAT_MODE_ONE &&
                        dur > 3000L
                    ) {
                        val configuredFadeMs = (smoothTransitionDurationSec * 1000L).coerceIn(1000L, 10_000L)
                        val effectiveFadeMs = min(configuredFadeMs, (dur / 2L).coerceAtLeast(800L))
                        val remaining = dur - pos
                        if (remaining in 350L..effectiveFadeMs) {
                            val outgoingItem = resolveCurrentOutgoingMediaItem(controller)
                            if (outgoingItem != null) {
                                autoCrossfadeTriggeredTrackId = currentId
                                SoundLapaPlaybackService.performDualDeckCrossfade(
                                    outgoingItem = outgoingItem,
                                    outgoingPositionMs = pos,
                                    fadeDurationMs = remaining.coerceIn(400L, configuredFadeMs),
                                ) {
                                    controller.volume = 0f
                                    controller.seekToNextMediaItem()
                                    controller.play()
                                    _playbackState.update { it.copy(transitionCounter = it.transitionCounter + 1) }
                                }
                            }
                        }
                    }

                    if (ticks % 10 == 0 && playing) {
                        launch(Dispatchers.IO) {
                            preferences.savePlaybackState(currentId, pos)
                        }
                    }
                }
                ticks++
                delay(180L)
            }
        }
    }

    private fun toMediaItem(track: TrackEntity): MediaItem? {
        val uri = resolveTrackUri(track) ?: return null
        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setGenre(track.genre)

        track.coverArtPath?.let { cover ->
            if (cover.startsWith("http://") || cover.startsWith("https://")) {
                metadataBuilder.setArtworkUri(Uri.parse(cover))
            } else {
                val file = File(cover)
                if (file.exists()) {
                    metadataBuilder.setArtworkUri(Uri.fromFile(file))
                }
            }
        }

        return MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(uri)
            .setMediaMetadata(metadataBuilder.build())
            .build()
    }

    private fun resolveTrackUri(track: TrackEntity): Uri? {
        if (FullStreamResolver.isShortPreviewTrack(track)) {
            return Uri.parse(FullStreamResolver.buildLegacyUpgradeUri(track))
        }
        val cached = track.cachedFilePath?.let { File(it) }
        if (cached != null && cached.exists() && cached.length() > 0L) {
            return Uri.fromFile(cached)
        }
        return track.sourceUri?.let { Uri.parse(it) }
    }
}
