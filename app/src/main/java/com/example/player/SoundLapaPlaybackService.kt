package com.example.player

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes as PlatformAudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.CompositeMediaSource
import androidx.media3.exoplayer.source.ForwardingTimeline
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.example.MainActivity
import com.example.R
import com.example.data.local.AudioFocusMode
import com.example.data.local.SoundLapaDao
import com.example.data.local.SoundLapaDatabase
import com.example.data.local.SoundLapaPreferences
import com.example.data.remote.FullStreamResolver
import com.example.data.remote.ResolvedAudioStream
import com.example.data.remote.isNetworkCurrentlyAvailable
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

enum class AudioFocusUiStatus {
    IDLE,
    ACTIVE,
    TRANSIENT_LOSS,
    DUCKING,
}

class SoundLapaPlaybackService : MediaSessionService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mediaSession: MediaSession? = null
    private var deckA: ExoPlayer? = null
    private var deckB: ExoPlayer? = null
    private var activePlayer: ExoPlayer? = null
    private var standbyPlayer: ExoPlayer? = null
    private var sharedAudioAttributes: AudioAttributes? = null
    private var sharedDao: SoundLapaDao? = null
    private var sharedPrefs: SoundLapaPreferences? = null

    private var crossfadeJob: Job? = null
    private var supervisorJob: Job? = null
    private var serviceRecoveryJob: Job? = null
    private var crossfadeGeneration: Long = 0L
    private var isTransitioningSwap = false
    private var autoCrossfadeTriggeredTrackId: String? = null
    private var lastPrefetchedTrackId: String? = null
    private val serviceRetryCounts = ConcurrentHashMap<String, Int>()

    private var currentFocusMode: AudioFocusMode = AudioFocusMode.PAUSE
    private var smoothTransitionEnabled: Boolean = true
    private var smoothTransitionDurationSec: Int = 3
    private var audioManager: AudioManager? = null
    private var appAudioFocusRequest: AudioFocusRequest? = null
    private var appAudioFocusModeBuilt: AudioFocusMode? = null
    private var isDuckedByFocus: Boolean = false
    private var pausedByFocusLoss: Boolean = false
    @Volatile
    private var clipAudioMuteOverride: Boolean = false

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val activeNetworkResolutions = AtomicInteger(0)

    private fun desiredPrimaryVolume(): Float = when {
        clipAudioMuteOverride -> 0f
        isDuckedByFocus && currentFocusMode == AudioFocusMode.DUCK -> 0.25f
        else -> 1f
    }

    private fun applyClipAudioMuteOverrideSync(muted: Boolean) {
        clipAudioMuteOverride = muted
        if (muted && crossfadeJob?.isActive == true) {
            cancelCrossfade(restorePrimaryVolume = false)
        }
        activePlayer?.volume = desiredPrimaryVolume()
    }

    private val appAudioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (currentFocusMode) {
            AudioFocusMode.MIX -> {
                isDuckedByFocus = false
                pausedByFocusLoss = false
                if (crossfadeJob?.isActive != true) {
                    activePlayer?.volume = desiredPrimaryVolume()
                }
                activePlayer?.let { updateAudioFocusTelemetry(it, it.playbackSuppressionReason) }
            }
            AudioFocusMode.DUCK -> {
                when (focusChange) {
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        isDuckedByFocus = false
                        pausedByFocusLoss = false
                        if (crossfadeJob?.isActive != true) {
                            activePlayer?.volume = desiredPrimaryVolume()
                        }
                        activePlayer?.let { updateAudioFocusTelemetry(it, it.playbackSuppressionReason) }
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
                    -> {
                        isDuckedByFocus = true
                        activePlayer?.volume = if (clipAudioMuteOverride) 0f else 0.25f
                        _audioFocusStatus.value = AudioFocusUiStatus.DUCKING
                    }
                    AudioManager.AUDIOFOCUS_LOSS -> {
                        isDuckedByFocus = false
                        pausedByFocusLoss = false
                        activePlayer?.pause()
                        _audioFocusStatus.value = AudioFocusUiStatus.IDLE
                        refreshWakeLocks()
                    }
                }
            }
            AudioFocusMode.PAUSE -> {
                when (focusChange) {
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        isDuckedByFocus = false
                        val shouldResume = pausedByFocusLoss
                        pausedByFocusLoss = false
                        val player = activePlayer
                        if (player != null) {
                            if (crossfadeJob?.isActive != true) {
                                player.volume = desiredPrimaryVolume()
                            }
                            if (shouldResume) {
                                player.playWhenReady = true
                                player.play()
                            }
                            updateAudioFocusTelemetry(player, player.playbackSuppressionReason)
                        } else {
                            _audioFocusStatus.value = AudioFocusUiStatus.IDLE
                        }
                        refreshWakeLocks()
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
                    -> {
                        isDuckedByFocus = false
                        val player = activePlayer
                        if (player != null && (player.isPlaying || player.playWhenReady)) {
                            pausedByFocusLoss = true
                            cancelCrossfade(restorePrimaryVolume = true)
                            player.pause()
                        }
                        _audioFocusStatus.value = AudioFocusUiStatus.TRANSIENT_LOSS
                        refreshWakeLocks()
                    }
                    AudioManager.AUDIOFOCUS_LOSS -> {
                        isDuckedByFocus = false
                        pausedByFocusLoss = false
                        cancelCrossfade(restorePrimaryVolume = true)
                        activePlayer?.pause()
                        _audioFocusStatus.value = AudioFocusUiStatus.IDLE
                        refreshWakeLocks()
                    }
                }
            }
        }
    }

    private val activePlayerListener = object : Player.Listener {
        override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
            activePlayer?.let { updateAudioFocusTelemetry(it, playbackSuppressionReason) }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val player = activePlayer ?: return
            if (playbackState == Player.STATE_READY) {
                player.currentMediaItem?.mediaId?.let { serviceRetryCounts.remove(it) }
                prefetchUpcomingForActiveDeck(player)
            }
            if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) {
                cancelCrossfade(restorePrimaryVolume = true)
            }
            updateAudioFocusTelemetry(player, player.playbackSuppressionReason)
            refreshWakeLocks()
            ensureSupervisorState(player)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val player = activePlayer ?: return
            if (isPlaying && currentFocusMode != AudioFocusMode.MIX) {
                ensureAppAudioFocusRequested()
            }
            updateAudioFocusTelemetry(player, player.playbackSuppressionReason)
            refreshWakeLocks()
            ensureSupervisorState(player)
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            val player = activePlayer ?: return
            if (playWhenReady && currentFocusMode != AudioFocusMode.MIX) {
                ensureAppAudioFocusRequested()
            }
            if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) {
                pausedByFocusLoss = false
            }
            if (!isTransitioningSwap && !playWhenReady && crossfadeJob?.isActive == true) {
                cancelCrossfade(restorePrimaryVolume = true)
            }
            if (!playWhenReady) {
                serviceRecoveryJob?.cancel()
            }
            updateAudioFocusTelemetry(player, player.playbackSuppressionReason)
            refreshWakeLocks()
            ensureSupervisorState(player)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val player = activePlayer ?: return
            val id = mediaItem?.mediaId
            if (!isTransitioningSwap && crossfadeJob?.isActive != true) {
                silenceStandbyDecksSync(exceptDeck = player)
                player.volume = desiredPrimaryVolume()
            }
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED ||
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
            ) {
                autoCrossfadeTriggeredTrackId = null
            }
            id?.let { serviceRetryCounts.remove(it) }
            prefetchUpcomingForActiveDeck(player)
            refreshWakeLocks()
        }

        override fun onPlayerError(error: PlaybackException) {
            val player = activePlayer ?: return
            val currentMedia = player.currentMediaItem ?: return
            val mediaId = currentMedia.mediaId
            val rawUri = currentMedia.localConfiguration?.uri?.toString()
            val sourceUriExtra = currentMedia.mediaMetadata.extras?.getString("source_uri")

            FullStreamResolver.invalidateCachedStream(rawUri)
            FullStreamResolver.invalidateCachedStream(sourceUriExtra)

            if (!isNetworkCurrentlyAvailable(this@SoundLapaPlaybackService)) {
                refreshWakeLocks()
                return
            }

            val attempts = serviceRetryCounts[mediaId] ?: 0
            if (attempts < 4 && player.mediaItemCount > 0) {
                val nextAttempt = attempts + 1
                serviceRetryCounts[mediaId] = nextAttempt
                val resumePos = player.currentPosition.coerceAtLeast(0L)
                val idx = player.currentMediaItemIndex.coerceIn(0, player.mediaItemCount - 1)
                cancelCrossfade(restorePrimaryVolume = true)
                serviceRecoveryJob?.cancel()
                serviceRecoveryJob = serviceScope.launch {
                    refreshWakeLocks()
                    delay(350L * nextAttempt)
                    val target = activePlayer ?: return@launch
                    if (target.mediaItemCount > 0 && target.currentMediaItem?.mediaId == mediaId) {
                        target.seekTo(idx, resumePos)
                        target.playWhenReady = true
                        target.prepare()
                        target.play()
                    }
                    refreshWakeLocks()
                }
            } else {
                refreshWakeLocks()
            }
        }
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        initWakeLocks()

        val prefs = SoundLapaPreferences(this).also { sharedPrefs = it }
        val initialSettings = runCatching { prefs.currentSettings() }.getOrNull()
        currentFocusMode = initialSettings?.audioFocusMode ?: AudioFocusMode.PAUSE
        smoothTransitionEnabled = initialSettings?.smoothTransitionEnabled ?: true
        smoothTransitionDurationSec = (initialSettings?.smoothTransitionDurationSec ?: 3).coerceIn(1, 10)

        serviceScope.launch {
            prefs.settings.collect { settings ->
                currentFocusMode = settings.audioFocusMode
                smoothTransitionEnabled = settings.smoothTransitionEnabled
                smoothTransitionDurationSec = settings.smoothTransitionDurationSec.coerceIn(1, 10)
                if (!smoothTransitionEnabled && crossfadeJob?.isActive == true) {
                    cancelCrossfade(restorePrimaryVolume = true)
                }
            }
        }

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        sharedAudioAttributes = audioAttributes

        FullStreamResolver.configureHttpCache(cacheDir)
        FullStreamResolver.warmUpAsync()
        val dao = SoundLapaDatabase.getInstance(this).soundLapaDao()
        sharedDao = dao

        val playerA = buildDeckPlayer(handleAudioFocus = false)
        playerA.addListener(activePlayerListener)

        deckA = playerA
        deckB = null
        activePlayer = playerA
        standbyPlayer = null

        val notificationProvider = DefaultMediaNotificationProvider.Builder(this)
            .build()
            .apply {
                setSmallIcon(R.drawable.ic_notification_music)
            }
        setMediaNotificationProvider(notificationProvider)

        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        mediaSession = MediaSession.Builder(this, playerA)
            .setSessionActivity(sessionActivityPendingIntent)
            .setCallback(object : MediaSession.Callback {
                override fun onPlayerCommandRequest(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    playerCommand: Int,
                ): Int {
                    if (playerCommand == Player.COMMAND_SET_MEDIA_ITEM ||
                        playerCommand == Player.COMMAND_CHANGE_MEDIA_ITEMS ||
                        playerCommand == Player.COMMAND_SEEK_TO_MEDIA_ITEM ||
                        playerCommand == Player.COMMAND_SEEK_TO_NEXT ||
                        playerCommand == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM ||
                        playerCommand == Player.COMMAND_SEEK_TO_PREVIOUS ||
                        playerCommand == Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
                    ) {
                        prepareCleanSingleDeckStartSync()
                    }
                    return super.onPlayerCommandRequest(session, controller, playerCommand)
                }

                override fun onAddMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: List<MediaItem>,
                ): ListenableFuture<List<MediaItem>> {
                    val updated = mediaItems.map { item ->
                        if (item.localConfiguration != null) {
                            item
                        } else {
                            val uri = item.requestMetadata.mediaUri
                            if (uri != null) item.buildUpon().setUri(uri).build() else item
                        }
                    }
                    return Futures.immediateFuture(updated)
                }
            })
            .build()
    }

    private fun initWakeLocks() {
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SoundLap::PlaybackWakeLock")?.apply {
                setReferenceCounted(false)
            }
        }
        runCatching {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val wifiMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wm?.createWifiLock(wifiMode, "SoundLap::PlaybackWifiLock")?.apply {
                setReferenceCounted(false)
            }
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun refreshWakeLocks() {
        val player = activePlayer
        val needActiveLocks = (player != null &&
            (player.isPlaying ||
                (player.playWhenReady && player.playbackState == Player.STATE_BUFFERING))) ||
            crossfadeJob?.isActive == true ||
            serviceRecoveryJob?.isActive == true ||
            activeNetworkResolutions.get() > 0

        if (needActiveLocks) {
            runCatching {
                val wl = wakeLock
                if (wl != null && !wl.isHeld) {
                    wl.acquire(10 * 60 * 1000L)
                }
            }
            runCatching {
                val wfl = wifiLock
                if (wfl != null && !wfl.isHeld) {
                    wfl.acquire()
                }
            }
        } else {
            runCatching {
                val wl = wakeLock
                if (wl != null && wl.isHeld) {
                    wl.release()
                }
            }
            runCatching {
                val wfl = wifiLock
                if (wfl != null && wfl.isHeld) {
                    wfl.release()
                }
            }
        }
    }

    private fun ensureSupervisorState(player: Player) {
        val shouldRun = player.playWhenReady || player.isPlaying || crossfadeJob?.isActive == true
        if (!shouldRun) {
            supervisorJob?.cancel()
            supervisorJob = null
            return
        }
        if (supervisorJob?.isActive == true) return
        supervisorJob = serviceScope.launch {
            var stalledSinceMs = 0L
            var lastObservedBufferedPos = -1L
            var lastObservedTrackId: String? = null
            while (isActive) {
                val current = activePlayer
                if (current == null || (!current.playWhenReady && !current.isPlaying && crossfadeJob?.isActive != true)) {
                    break
                }
                refreshWakeLocks()

                val trackId = current.currentMediaItem?.mediaId
                val state = current.playbackState
                val now = SystemClock.elapsedRealtime()

                // 1. Background buffering stall detector & auto-recovery
                if (current.playWhenReady && state == Player.STATE_BUFFERING && !current.isPlaying) {
                    val buf = current.bufferedPosition
                    if (trackId != lastObservedTrackId || buf > lastObservedBufferedPos + 1500L) {
                        lastObservedTrackId = trackId
                        lastObservedBufferedPos = buf
                        stalledSinceMs = now
                    } else if (stalledSinceMs > 0L && now - stalledSinceMs > 16_000L) {
                        stalledSinceMs = now
                        recoverStalledStreamInBackground(current)
                    }
                } else {
                    stalledSinceMs = 0L
                    lastObservedBufferedPos = current.bufferedPosition
                    lastObservedTrackId = trackId
                }

                // 2. Background next-track pre-resolve & service-hosted end-of-track auto-crossfade
                val duration = current.duration
                val pos = current.currentPosition.coerceAtLeast(0L)
                if (current.isPlaying &&
                    state == Player.STATE_READY &&
                    duration > 0L &&
                    trackId != null
                ) {
                    val remaining = (duration - pos).coerceAtLeast(0L)
                    if (remaining <= 16_000L) {
                        prefetchUpcomingForActiveDeck(current)
                    }
                    if (smoothTransitionEnabled &&
                        !clipAudioMuteOverride &&
                        crossfadeJob?.isActive != true &&
                        autoCrossfadeTriggeredTrackId != trackId &&
                        current.hasNextMediaItem() &&
                        current.repeatMode != Player.REPEAT_MODE_ONE &&
                        duration > 20_000L &&
                        pos >= 4_000L
                    ) {
                        val configuredFadeMs = (smoothTransitionDurationSec * 1000L).coerceIn(1000L, 10_000L)
                        val effectiveFadeMs = min(configuredFadeMs, (duration / 2L).coerceAtLeast(800L))
                        if (remaining in 450L..(effectiveFadeMs + 2_200L)) {
                            val nextIdx = current.nextMediaItemIndex
                            val count = current.mediaItemCount
                            if (nextIdx in 0 until count) {
                                val items = List(count) { idx -> current.getMediaItemAt(idx) }
                                if (items[nextIdx].mediaId != trackId) {
                                    autoCrossfadeTriggeredTrackId = trackId
                                    startDualDeckCrossfade(
                                        mediaItems = items,
                                        targetIndex = nextIdx,
                                        startPositionMs = 0L,
                                        fadeDurationMs = effectiveFadeMs,
                                        repeatMode = current.repeatMode,
                                        shuffleEnabled = current.shuffleModeEnabled,
                                        maxLeadWaitMs = 2_400L,
                                        fallbackApplyOnActive = {
                                            activePlayer?.volume = desiredPrimaryVolume()
                                        },
                                        onDeckActivated = { _, _ -> },
                                    )
                                }
                            }
                        }
                    }
                }
                delay(350L)
            }
            supervisorJob = null
            refreshWakeLocks()
        }
    }

    private fun recoverStalledStreamInBackground(player: ExoPlayer) {
        if (!isNetworkCurrentlyAvailable(this)) return
        val media = player.currentMediaItem ?: return
        val mediaId = media.mediaId
        val attempts = serviceRetryCounts[mediaId] ?: 0
        if (attempts >= 4) return
        serviceRetryCounts[mediaId] = attempts + 1

        val rawUri = media.localConfiguration?.uri?.toString()
        val sourceUri = media.mediaMetadata.extras?.getString("source_uri")
        FullStreamResolver.invalidateCachedStream(rawUri)
        FullStreamResolver.invalidateCachedStream(sourceUri)

        val resumePos = player.currentPosition.coerceAtLeast(0L)
        val idx = player.currentMediaItemIndex.coerceIn(0, (player.mediaItemCount - 1).coerceAtLeast(0))
        serviceScope.launch {
            refreshWakeLocks()
            if (player === activePlayer && player.mediaItemCount > 0) {
                player.seekTo(idx, resumePos)
                player.playWhenReady = true
                player.prepare()
                player.play()
            }
        }
    }

    private fun prefetchUpcomingForActiveDeck(player: Player) {
        val dao = sharedDao ?: return
        val count = player.mediaItemCount
        if (count <= 0) return

        fun triggerPrefetchAt(index: Int, markPrimary: Boolean) {
            if (index !in 0 until count) return
            val item = player.getMediaItemAt(index)
            val id = item.mediaId
            if (markPrimary && id.isNotBlank() && id == lastPrefetchedTrackId) return
            if (markPrimary) lastPrefetchedTrackId = id

            val rawUri = (item.localConfiguration?.uri ?: item.requestMetadata.mediaUri)?.toString().orEmpty()
            if (rawUri.isBlank() || rawUri.startsWith("content://") || rawUri.startsWith("file://")) return
            if (FullStreamResolver.peekCachedOrDirectStreamSync(rawUri) != null) return

            val title = item.mediaMetadata.title?.toString()
            val artist = item.mediaMetadata.artist?.toString()
            val sourceUri = item.mediaMetadata.extras?.getString("source_uri")
            val durationMs = item.mediaMetadata.extras?.getLong("duration_ms", 0L) ?: 0L

            serviceScope.launch(Dispatchers.IO) {
                activeNetworkResolutions.incrementAndGet()
                serviceScope.launch(Dispatchers.Main.immediate) { refreshWakeLocks() }
                try {
                    FullStreamResolver.resolveAudioStreamForUri(
                        rawUri = rawUri,
                        fallbackTitle = title,
                        fallbackArtist = artist,
                        trackId = id.takeIf { it.isNotBlank() },
                        originalSourceUri = sourceUri,
                        dao = dao,
                        expectedDurationMs = durationMs,
                    )
                } finally {
                    activeNetworkResolutions.decrementAndGet()
                    serviceScope.launch(Dispatchers.Main.immediate) { refreshWakeLocks() }
                }
            }
        }

        val nextIdx = player.nextMediaItemIndex
        if (nextIdx != C.INDEX_UNSET) {
            triggerPrefetchAt(nextIdx, markPrimary = true)
            if (nextIdx + 1 in 0 until count) {
                triggerPrefetchAt(nextIdx + 1, markPrimary = false)
            }
        }
        val prevIdx = player.previousMediaItemIndex
        if (prevIdx != C.INDEX_UNSET) {
            triggerPrefetchAt(prevIdx, markPrimary = false)
        }
    }

    @OptIn(UnstableApi::class)
    private fun buildDeckPlayer(handleAudioFocus: Boolean): ExoPlayer {
        val dao = sharedDao ?: SoundLapaDatabase.getInstance(this).soundLapaDao().also { sharedDao = it }
        val attrs = sharedAudioAttributes ?: AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build().also { sharedAudioAttributes = it }

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 28_000,
                /* maxBufferMs = */ 75_000,
                /* bufferForPlaybackMs = */ 450,
                /* bufferForPlaybackAfterRebufferMs = */ 1_200,
            )
            .setBackBuffer(/* backBufferDurationMs = */ 20_000, /* retainBackBufferFromKeyframe = */ false)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val extractorsFactory = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
        val mediaSourceFactory = SoundLapaMediaSourceFactory(
            context = this,
            dao = dao,
            resolveScope = serviceScope,
            extractorsFactory = extractorsFactory,
            onResolutionStateChanged = { delta ->
                if (delta > 0) {
                    activeNetworkResolutions.incrementAndGet()
                } else {
                    activeNetworkResolutions.updateAndGet { cur -> (cur - 1).coerceAtLeast(0) }
                }
                serviceScope.launch(Dispatchers.Main.immediate) { refreshWakeLocks() }
            },
        ).apply {
            setLoadErrorHandlingPolicy(
                object : DefaultLoadErrorHandlingPolicy(6) {
                    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
                        val cause = loadErrorInfo.exception
                        if (cause is HttpDataSource.InvalidResponseCodeException &&
                            (cause.responseCode == 403 || cause.responseCode == 410 || cause.responseCode == 404)
                        ) {
                            // Fail fast on expired signed CDN URLs so service error recovery re-resolves cleanly
                            return C.TIME_UNSET
                        }
                        return super.getRetryDelayMsFor(loadErrorInfo).coerceAtMost(4_000L)
                    }
                },
            )
        }

        return ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setAudioAttributes(attrs, handleAudioFocus)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
    }

    private fun ensureStandbyPlayer(): ExoPlayer {
        standbyPlayer?.let { return it }
        val existingOther = when {
            activePlayer === deckA && deckB != null -> deckB
            activePlayer === deckB && deckA != null -> deckA
            else -> null
        }
        if (existingOther != null) {
            standbyPlayer = existingOther
            return existingOther
        }
        val created = buildDeckPlayer(handleAudioFocus = false)
        if (deckA == null) deckA = created else deckB = created
        standbyPlayer = created
        return created
    }

    /**
     * Synchronously stops, mutes, and clears every deck other than [exceptDeck] so two tracks
     * can never overlap or echo at playback start.
     */
    private fun silenceStandbyDecksSync(exceptDeck: ExoPlayer? = activePlayer) {
        listOfNotNull(deckA, deckB).forEach { deck ->
            if (deck !== exceptDeck) {
                runCatching {
                    deck.removeListener(activePlayerListener)
                    deck.volume = 0f
                    deck.playWhenReady = false
                    deck.pause()
                    deck.stop()
                    deck.clearMediaItems()
                }
            }
        }
    }

    private fun prepareCleanSingleDeckStartSync() {
        crossfadeGeneration++
        isTransitioningSwap = false
        autoCrossfadeTriggeredTrackId = null
        crossfadeJob?.cancel()
        crossfadeJob = null
        silenceStandbyDecksSync(exceptDeck = activePlayer)
        activePlayer?.let { active ->
            active.volume = desiredPrimaryVolume()
        }
        refreshWakeLocks()
    }

    @OptIn(UnstableApi::class)
    private fun startDualDeckCrossfade(
        mediaItems: List<MediaItem>,
        targetIndex: Int,
        startPositionMs: Long,
        fadeDurationMs: Long,
        repeatMode: Int,
        shuffleEnabled: Boolean,
        maxLeadWaitMs: Long,
        fallbackApplyOnActive: () -> Unit,
        onDeckActivated: (activatedMediaId: String?, isReady: Boolean) -> Unit,
    ) {
        val outgoing = activePlayer
        val incoming = standbyPlayer ?: ensureStandbyPlayer()
        val attrs = sharedAudioAttributes
        val expectedIncomingId = mediaItems.getOrNull(targetIndex)?.mediaId
        val outgoingId = outgoing?.currentMediaItem?.mediaId
        if (outgoing == null ||
            attrs == null ||
            targetIndex !in mediaItems.indices ||
            outgoing === incoming ||
            (outgoingId != null && outgoingId == expectedIncomingId)
        ) {
            fallbackApplyOnActive()
            return
        }

        val myGeneration = ++crossfadeGeneration
        crossfadeJob?.cancel()
        isTransitioningSwap = false
        silenceStandbyDecksSync(exceptDeck = outgoing)

        val safeFadeMs = fadeDurationMs.coerceIn(500L, 10_000L)
        val startOutVol = outgoing.volume.coerceIn(0.05f, 1f)
        val safeStart = startPositionMs.coerceAtLeast(0L)

        crossfadeJob = serviceScope.launch {
            var swapped = false
            refreshWakeLocks()
            try {
                // 1. Prepare incoming track silently on standby deck while outgoing finishes cleanly
                incoming.volume = 0f
                incoming.playWhenReady = false
                incoming.repeatMode = repeatMode
                incoming.shuffleModeEnabled = shuffleEnabled
                incoming.setMediaItems(mediaItems, targetIndex, safeStart)
                incoming.prepare()

                var waitedReadyMs = 0L
                val maxPrepareWaitMs = 9_000L
                while (isActive &&
                    myGeneration == crossfadeGeneration &&
                    incoming.playerError == null &&
                    incoming.playbackState != Player.STATE_READY &&
                    incoming.playbackState != Player.STATE_ENDED &&
                    waitedReadyMs < maxPrepareWaitMs
                ) {
                    val outDuration = outgoing.duration
                    if (outDuration > 0L && outgoing.currentPosition >= outDuration - 240L) {
                        break
                    }
                    if (!outgoing.isPlaying && outgoing.playbackState == Player.STATE_ENDED) {
                        break
                    }
                    delay(16L)
                    waitedReadyMs += 16L
                }

                if (!isActive || myGeneration != crossfadeGeneration) return@launch

                if (incoming.playbackState != Player.STATE_READY) {
                    silenceStandbyDecksSync(exceptDeck = outgoing)
                    outgoing.volume = 1f
                    fallbackApplyOnActive()
                    return@launch
                }

                // 2. If triggered slightly early for pre-buffering, wait until remaining enters fade window
                if (maxLeadWaitMs > 0L) {
                    var leadElapsed = 0L
                    while (isActive &&
                        myGeneration == crossfadeGeneration &&
                        outgoing.isPlaying &&
                        outgoing.playbackState == Player.STATE_READY &&
                        leadElapsed < maxLeadWaitMs
                    ) {
                        val dur = outgoing.duration
                        if (dur <= 0L) break
                        val rem = dur - outgoing.currentPosition
                        if (rem <= safeFadeMs + 100L) break
                        delay(16L)
                        leadElapsed += 16L
                    }
                }

                if (!isActive ||
                    myGeneration != crossfadeGeneration ||
                    clipAudioMuteOverride ||
                    !outgoing.isPlaying ||
                    outgoing.playbackState == Player.STATE_ENDED
                ) {
                    silenceStandbyDecksSync(exceptDeck = outgoing)
                    outgoing.volume = desiredPrimaryVolume()
                    fallbackApplyOnActive()
                    return@launch
                }

                // 3. Start incoming track at zero volume before deck bridge swap
                incoming.volume = 0f
                incoming.playWhenReady = true
                incoming.play()

                var primeWaitMs = 0L
                while (isActive && myGeneration == crossfadeGeneration && !incoming.isPlaying && primeWaitMs < 120L) {
                    delay(16L)
                    primeWaitMs += 16L
                }
                if (!isActive || myGeneration != crossfadeGeneration || clipAudioMuteOverride) return@launch

                // 4. Switch active deck on MediaSession once incoming deck is already READY and playing
                isTransitioningSwap = true
                outgoing.removeListener(activePlayerListener)
                outgoing.setAudioAttributes(attrs, /* handleAudioFocus = */ false)
                incoming.setAudioAttributes(attrs, /* handleAudioFocus = */ false)
                incoming.addListener(activePlayerListener)
                activePlayer = incoming
                standbyPlayer = outgoing
                mediaSession?.player = incoming
                swapped = true
                isTransitioningSwap = false

                onActiveDeckSwapped?.invoke(incoming.currentMediaItem?.mediaId ?: expectedIncomingId)
                onDeckActivated(expectedIncomingId, true)

                val outgoingTotalDur = outgoing.duration
                val effectiveFadeMs = if (outgoingTotalDur > 0L) {
                    val remainingOut = (outgoingTotalDur - outgoing.currentPosition).coerceAtLeast(350L)
                    minOf(safeFadeMs, remainingOut).coerceAtLeast(350L)
                } else {
                    safeFadeMs
                }

                // 5. Equal-power cubic smoothstep crossfade
                var fadeElapsedMs = 0L
                var lastTickMs = SystemClock.elapsedRealtime()
                while (isActive && myGeneration == crossfadeGeneration) {
                    val now = SystemClock.elapsedRealtime()
                    val dt = (now - lastTickMs).coerceIn(0L, 48L)
                    lastTickMs = now

                    if (incoming.isPlaying || outgoing.playbackState == Player.STATE_ENDED) {
                        fadeElapsedMs += dt
                    }

                    val t = (fadeElapsedMs.toFloat() / effectiveFadeMs.toFloat()).coerceIn(0f, 1f)
                    val smoothT = t * t * (3f - 2f * t)
                    val angle = smoothT * (PI / 2.0)
                    val outGain = if (outgoing.playbackState == Player.STATE_ENDED) {
                        0f
                    } else {
                        (startOutVol * cos(angle).toFloat()).coerceIn(0f, 1f)
                    }
                    val inGain = sin(angle).toFloat().coerceIn(0f, 1f)

                    outgoing.volume = if (clipAudioMuteOverride) 0f else outGain
                    incoming.volume = if (clipAudioMuteOverride) 0f else inGain

                    if (t >= 1f) break
                    delay(16L)
                }

                if (myGeneration == crossfadeGeneration) {
                    incoming.volume = desiredPrimaryVolume()
                    silenceStandbyDecksSync(exceptDeck = activePlayer)
                }
            } finally {
                isTransitioningSwap = false
                if (myGeneration == crossfadeGeneration) {
                    activePlayer?.volume = desiredPrimaryVolume()
                    val nonActive = if (swapped) outgoing else incoming
                    if (nonActive !== activePlayer) {
                        silenceStandbyDecksSync(exceptDeck = activePlayer)
                    }
                } else if (!swapped && incoming !== activePlayer) {
                    runCatching {
                        incoming.volume = 0f
                        incoming.playWhenReady = false
                        incoming.pause()
                        incoming.stop()
                        incoming.clearMediaItems()
                    }
                }
                refreshWakeLocks()
            }
        }
    }

    private fun cancelCrossfade(restorePrimaryVolume: Boolean) {
        crossfadeGeneration++
        isTransitioningSwap = false
        crossfadeJob?.cancel()
        crossfadeJob = null
        silenceStandbyDecksSync(exceptDeck = activePlayer)
        if (restorePrimaryVolume) {
            activePlayer?.volume = desiredPrimaryVolume()
        }
        refreshWakeLocks()
    }

    private fun ensureAppAudioFocusRequested() {
        val am = audioManager ?: return
        val mode = currentFocusMode
        if (mode == AudioFocusMode.MIX) {
            abandonAppAudioFocus()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (appAudioFocusRequest != null && appAudioFocusModeBuilt != mode) {
                appAudioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
                appAudioFocusRequest = null
            }
            if (appAudioFocusRequest == null) {
                val attrs = PlatformAudioAttributes.Builder()
                    .setUsage(PlatformAudioAttributes.USAGE_MEDIA)
                    .setContentType(PlatformAudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
                appAudioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attrs)
                    .setOnAudioFocusChangeListener(appAudioFocusListener, Handler(Looper.getMainLooper()))
                    .setWillPauseWhenDucked(mode == AudioFocusMode.PAUSE)
                    .setAcceptsDelayedFocusGain(true)
                    .build()
                appAudioFocusModeBuilt = mode
            }
            appAudioFocusRequest?.let { am.requestAudioFocus(it) }
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
                appAudioFocusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN,
            )
        }
    }

    private fun abandonAppAudioFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appAudioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(appAudioFocusListener)
        }
        appAudioFocusRequest = null
        appAudioFocusModeBuilt = null
        isDuckedByFocus = false
        pausedByFocusLoss = false
    }

    private fun applyAudioFocusMode(mode: AudioFocusMode) {
        currentFocusMode = mode
        val attrs = sharedAudioAttributes ?: return
        activePlayer?.setAudioAttributes(attrs, /* handleAudioFocus = */ false)
        when (mode) {
            AudioFocusMode.PAUSE -> {
                isDuckedByFocus = false
                if (crossfadeJob?.isActive != true) {
                    activePlayer?.volume = desiredPrimaryVolume()
                }
                if (activePlayer?.isPlaying == true || activePlayer?.playWhenReady == true) {
                    ensureAppAudioFocusRequested()
                }
            }
            AudioFocusMode.DUCK -> {
                pausedByFocusLoss = false
                if (activePlayer?.isPlaying == true || activePlayer?.playWhenReady == true) {
                    ensureAppAudioFocusRequested()
                }
            }
            AudioFocusMode.MIX -> {
                abandonAppAudioFocus()
                if (crossfadeJob?.isActive != true) {
                    activePlayer?.volume = desiredPrimaryVolume()
                }
            }
        }
        activePlayer?.let { updateAudioFocusTelemetry(it, it.playbackSuppressionReason) }
    }

    private fun updateAudioFocusTelemetry(player: Player, suppressionReason: Int) {
        _audioFocusStatus.value = when {
            isDuckedByFocus && currentFocusMode == AudioFocusMode.DUCK -> AudioFocusUiStatus.DUCKING
            pausedByFocusLoss && currentFocusMode == AudioFocusMode.PAUSE -> AudioFocusUiStatus.TRANSIENT_LOSS
            suppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS ->
                AudioFocusUiStatus.TRANSIENT_LOSS
            player.isPlaying ->
                AudioFocusUiStatus.ACTIVE
            else ->
                AudioFocusUiStatus.IDLE
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        // Keep the player notification alive in the shade even while paused; only tear down on
        // explicit stop (STATE_IDLE / empty queue) or when the user swipes the paused notification.
        val hasActiveMedia = player != null &&
            player.mediaItemCount > 0 &&
            player.playbackState != Player.STATE_IDLE
        if (!hasActiveMedia) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        if (activeInstance === this) {
            activeInstance = null
        }
        abandonAppAudioFocus()
        cancelCrossfade(restorePrimaryVolume = true)
        supervisorJob?.cancel()
        supervisorJob = null
        serviceRecoveryJob?.cancel()
        serviceRecoveryJob = null
        activeNetworkResolutions.set(0)
        refreshWakeLocks()
        serviceScope.cancel()
        deckA?.removeListener(activePlayerListener)
        deckB?.removeListener(activePlayerListener)
        mediaSession?.release()
        mediaSession = null
        deckA?.release()
        deckB?.release()
        deckA = null
        deckB = null
        activePlayer = null
        standbyPlayer = null
        _audioFocusStatus.value = AudioFocusUiStatus.IDLE
        super.onDestroy()
    }

    companion object {
        @Volatile
        private var activeInstance: SoundLapaPlaybackService? = null

        @Volatile
        var onActiveDeckSwapped: ((String?) -> Unit)? = null

        private val _audioFocusStatus = MutableStateFlow(AudioFocusUiStatus.IDLE)
        val audioFocusStatus: StateFlow<AudioFocusUiStatus> = _audioFocusStatus.asStateFlow()

        fun prepareCleanSingleDeckStart() {
            activeInstance?.prepareCleanSingleDeckStartSync()
        }

        fun performDualDeckCrossfade(
            mediaItems: List<MediaItem>,
            targetIndex: Int,
            startPositionMs: Long,
            fadeDurationMs: Long,
            repeatMode: Int,
            shuffleEnabled: Boolean,
            maxLeadWaitMs: Long = 0L,
            fallbackApplyOnActive: () -> Unit,
            onDeckActivated: (activatedMediaId: String?, isReady: Boolean) -> Unit,
        ) {
            val service = activeInstance
            if (service != null) {
                service.startDualDeckCrossfade(
                    mediaItems = mediaItems,
                    targetIndex = targetIndex,
                    startPositionMs = startPositionMs,
                    fadeDurationMs = fadeDurationMs,
                    repeatMode = repeatMode,
                    shuffleEnabled = shuffleEnabled,
                    maxLeadWaitMs = maxLeadWaitMs,
                    fallbackApplyOnActive = fallbackApplyOnActive,
                    onDeckActivated = onDeckActivated,
                )
            } else {
                fallbackApplyOnActive()
            }
        }

        fun stopActiveCrossfade(restorePrimaryVolume: Boolean = true) {
            activeInstance?.cancelCrossfade(restorePrimaryVolume)
        }

        fun setAudioFocusMode(mode: AudioFocusMode) {
            activeInstance?.applyAudioFocusMode(mode)
        }

        fun setClipAudioMuteOverride(muted: Boolean) {
            activeInstance?.applyClipAudioMuteOverrideSync(muted)
        }
    }
}

@UnstableApi
private class SoundLapaMediaSourceFactory(
    private val context: Context,
    private val dao: SoundLapaDao,
    private val resolveScope: CoroutineScope,
    private val extractorsFactory: DefaultExtractorsFactory,
    private val onResolutionStateChanged: (Int) -> Unit,
) : MediaSource.Factory {

    private var drmProvider: DrmSessionManagerProvider? = null
    private var loadErrorPolicy: LoadErrorHandlingPolicy? = null

    private val sharedHttpFactory = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
        .setUserAgent(FullStreamResolver.DEFAULT_BROWSER_USER_AGENT)
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(30_000)

    private val sharedDataSourceFactory = DefaultDataSource.Factory(context, sharedHttpFactory)

    override fun setDrmSessionManagerProvider(drmSessionManagerProvider: DrmSessionManagerProvider): MediaSource.Factory {
        drmProvider = drmSessionManagerProvider
        return this
    }

    override fun setLoadErrorHandlingPolicy(loadErrorHandlingPolicy: LoadErrorHandlingPolicy): MediaSource.Factory {
        this.loadErrorPolicy = loadErrorHandlingPolicy
        return this
    }

    override fun getSupportedTypes(): IntArray = intArrayOf(
        C.CONTENT_TYPE_HLS,
        C.CONTENT_TYPE_OTHER,
    )

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val uri = mediaItem.localConfiguration?.uri ?: mediaItem.requestMetadata.mediaUri
        val scheme = uri?.scheme?.lowercase().orEmpty()
        if (scheme == "content" || scheme == "android.resource") {
            return ProgressiveMediaSource.Factory(sharedDataSourceFactory, extractorsFactory).apply {
                drmProvider?.let { setDrmSessionManagerProvider(it) }
                loadErrorPolicy?.let { setLoadErrorHandlingPolicy(it) }
            }.createMediaSource(mediaItem)
        }

        val resolvedItem = if (mediaItem.localConfiguration == null && uri != null) {
            mediaItem.buildUpon().setUri(uri).build()
        } else {
            mediaItem
        }

        return ResolvingCompositeMediaSource(
            initialMediaItem = resolvedItem,
            context = context,
            dao = dao,
            resolveScope = resolveScope,
            extractorsFactory = extractorsFactory,
            sharedDataSourceFactory = sharedDataSourceFactory,
            drmProvider = drmProvider,
            loadErrorPolicy = loadErrorPolicy,
            onResolutionStateChanged = onResolutionStateChanged,
        )
    }
}

@UnstableApi
private class ResolvingCompositeMediaSource(
    private val initialMediaItem: MediaItem,
    private val context: Context,
    private val dao: SoundLapaDao,
    private val resolveScope: CoroutineScope,
    private val extractorsFactory: DefaultExtractorsFactory,
    private val sharedDataSourceFactory: DefaultDataSource.Factory,
    private val drmProvider: DrmSessionManagerProvider?,
    private val loadErrorPolicy: LoadErrorHandlingPolicy?,
    private val onResolutionStateChanged: (Int) -> Unit,
) : CompositeMediaSource<Int>() {

    @Volatile
    private var released = false
    @Volatile
    private var resolutionError: IOException? = null
    private var childMediaSource: MediaSource? = null
    private var resolveJob: Job? = null

    override fun getMediaItem(): MediaItem = initialMediaItem

    override fun prepareSourceInternal(mediaTransferListener: TransferListener?) {
        super.prepareSourceInternal(mediaTransferListener)
        released = false
        resolutionError = null
        childMediaSource = null

        val playbackLooper = Looper.myLooper() ?: Looper.getMainLooper()
        val playbackHandler = Handler(playbackLooper)

        val rawUri = (initialMediaItem.localConfiguration?.uri ?: initialMediaItem.requestMetadata.mediaUri)
            ?.toString()
            .orEmpty()
        val title = initialMediaItem.mediaMetadata.title?.toString()
        val artist = initialMediaItem.mediaMetadata.artist?.toString()
        val trackId = initialMediaItem.mediaId.takeIf { it.isNotBlank() }
        val originalSourceUri = initialMediaItem.mediaMetadata.extras?.getString("source_uri")
        val expectedDurationMs = initialMediaItem.mediaMetadata.extras?.getLong("duration_ms", 0L) ?: 0L

        resolveJob?.cancel()
        val fastCached = FullStreamResolver.peekCachedOrDirectStreamSync(rawUri)
        if (fastCached != null) {
            val childSource = buildChildMediaSource(fastCached)
            childMediaSource = childSource
            prepareChildSource(0, childSource)
            return
        }

        resolveJob = resolveScope.launch(Dispatchers.IO) {
            onResolutionStateChanged(+1)
            val resolved = try {
                runCatching {
                    FullStreamResolver.resolveAudioStreamForUri(
                        rawUri = rawUri,
                        fallbackTitle = title,
                        fallbackArtist = artist,
                        trackId = trackId,
                        originalSourceUri = originalSourceUri,
                        dao = dao,
                        expectedDurationMs = expectedDurationMs,
                    )
                }.getOrNull()
            } finally {
                onResolutionStateChanged(-1)
            }

            if (!isActive || released || !playbackLooper.thread.isAlive) {
                return@launch
            }

            playbackHandler.post {
                if (released) return@post
                if (resolved == null) {
                    resolutionError = IOException("Unable to resolve playable audio stream for $rawUri")
                    return@post
                }
                val childSource = buildChildMediaSource(resolved)
                childMediaSource = childSource
                prepareChildSource(0, childSource)
            }
        }
    }

    @Throws(IOException::class)
    override fun maybeThrowSourceInfoRefreshError() {
        resolutionError?.let { throw it }
        super.maybeThrowSourceInfoRefreshError()
    }

    override fun onChildSourceInfoRefreshed(
        childSourceId: Int,
        mediaSource: MediaSource,
        newTimeline: Timeline,
    ) {
        refreshSourceInfo(PreservingMediaItemTimeline(newTimeline, initialMediaItem))
    }

    override fun createPeriod(
        id: MediaSource.MediaPeriodId,
        allocator: Allocator,
        startPositionUs: Long,
    ): MediaPeriod {
        val child = childMediaSource ?: throw IOException("Child MediaSource is not initialized")
        return child.createPeriod(id, allocator, startPositionUs)
    }

    override fun releasePeriod(mediaPeriod: MediaPeriod) {
        childMediaSource?.releasePeriod(mediaPeriod)
    }

    override fun releaseSourceInternal() {
        released = true
        resolveJob?.cancel()
        resolveJob = null
        childMediaSource = null
        resolutionError = null
        super.releaseSourceInternal()
    }

    private fun buildChildMediaSource(resolved: ResolvedAudioStream): MediaSource {
        val dataSourceFactory = if (resolved.headers.isNotEmpty()) {
            val httpFactory = DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(30_000)
                .setDefaultRequestProperties(resolved.headers)
            DefaultDataSource.Factory(context, httpFactory)
        } else {
            sharedDataSourceFactory
        }

        val childItem = initialMediaItem.buildUpon()
            .setUri(Uri.parse(resolved.directUrl))
            .build()

        val isHls = resolved.isHls || resolved.directUrl.contains(".m3u8", ignoreCase = true)
        return if (isHls) {
            HlsMediaSource.Factory(dataSourceFactory).apply {
                drmProvider?.let { setDrmSessionManagerProvider(it) }
                loadErrorPolicy?.let { setLoadErrorHandlingPolicy(it) }
            }.createMediaSource(childItem)
        } else {
            ProgressiveMediaSource.Factory(dataSourceFactory, extractorsFactory).apply {
                drmProvider?.let { setDrmSessionManagerProvider(it) }
                loadErrorPolicy?.let { setLoadErrorHandlingPolicy(it) }
            }.createMediaSource(childItem)
        }
    }
}

@UnstableApi
private class PreservingMediaItemTimeline(
    timeline: Timeline,
    private val mediaItem: MediaItem,
) : ForwardingTimeline(timeline) {
    override fun getWindow(
        windowIndex: Int,
        window: Window,
        defaultPositionProjectionUs: Long,
    ): Window {
        super.getWindow(windowIndex, window, defaultPositionProjectionUs)
        window.mediaItem = mediaItem
        return window
    }
}
