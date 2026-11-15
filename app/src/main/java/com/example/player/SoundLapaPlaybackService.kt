package com.example.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes as PlatformAudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
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
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.example.MainActivity
import com.example.data.local.AudioFocusMode
import com.example.data.local.SoundLapaDao
import com.example.data.local.SoundLapaDatabase
import com.example.data.local.SoundLapaPreferences
import com.example.data.remote.FullStreamResolver
import com.example.data.remote.ResolvedAudioStream
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.IOException
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
    private var crossfadeJob: Job? = null
    private var crossfadeGeneration: Long = 0L
    private var isReplacingCrossfade = false
    private var isTransitioningSwap = false
    private var currentFocusMode: AudioFocusMode = AudioFocusMode.PAUSE
    private var audioManager: AudioManager? = null
    private var appAudioFocusRequest: AudioFocusRequest? = null
    private var appAudioFocusModeBuilt: AudioFocusMode? = null
    private var isDuckedByFocus: Boolean = false
    private var pausedByFocusLoss: Boolean = false

    private val appAudioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (currentFocusMode) {
            AudioFocusMode.MIX -> {
                isDuckedByFocus = false
                pausedByFocusLoss = false
                if (crossfadeJob?.isActive != true) {
                    activePlayer?.volume = 1f
                }
                activePlayer?.let { updateAudioFocusTelemetry(it, it.playbackSuppressionReason) }
            }
            AudioFocusMode.DUCK -> {
                when (focusChange) {
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        isDuckedByFocus = false
                        pausedByFocusLoss = false
                        if (crossfadeJob?.isActive != true) {
                            activePlayer?.volume = 1f
                        }
                        activePlayer?.let { updateAudioFocusTelemetry(it, it.playbackSuppressionReason) }
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
                    -> {
                        isDuckedByFocus = true
                        activePlayer?.volume = 0.25f
                        _audioFocusStatus.value = AudioFocusUiStatus.DUCKING
                    }
                    AudioManager.AUDIOFOCUS_LOSS -> {
                        isDuckedByFocus = false
                        pausedByFocusLoss = false
                        activePlayer?.pause()
                        _audioFocusStatus.value = AudioFocusUiStatus.IDLE
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
                                player.volume = 1f
                            }
                            if (shouldResume) {
                                player.playWhenReady = true
                                player.play()
                            }
                            updateAudioFocusTelemetry(player, player.playbackSuppressionReason)
                        } else {
                            _audioFocusStatus.value = AudioFocusUiStatus.IDLE
                        }
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
                    }
                    AudioManager.AUDIOFOCUS_LOSS -> {
                        isDuckedByFocus = false
                        pausedByFocusLoss = false
                        cancelCrossfade(restorePrimaryVolume = true)
                        activePlayer?.pause()
                        _audioFocusStatus.value = AudioFocusUiStatus.IDLE
                    }
                }
            }
        }
    }

    private val activePlayerListener = object : Player.Listener {
        override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
            activePlayer?.let { updateAudioFocusTelemetry(it, playbackSuppressionReason) }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying && currentFocusMode != AudioFocusMode.MIX) {
                ensureAppAudioFocusRequested()
            }
            activePlayer?.let { updateAudioFocusTelemetry(it, it.playbackSuppressionReason) }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (playWhenReady && currentFocusMode != AudioFocusMode.MIX) {
                ensureAppAudioFocusRequested()
            }
            if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) {
                pausedByFocusLoss = false
            }
            if (!isTransitioningSwap && !playWhenReady && crossfadeJob?.isActive == true) {
                cancelCrossfade(restorePrimaryVolume = true)
            }
            activePlayer?.let { updateAudioFocusTelemetry(it, it.playbackSuppressionReason) }
        }
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        currentFocusMode = runCatching {
            SoundLapaPreferences(this).currentSettings().audioFocusMode
        }.getOrDefault(AudioFocusMode.PAUSE)

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

    @OptIn(UnstableApi::class)
    private fun buildDeckPlayer(handleAudioFocus: Boolean): ExoPlayer {
        val dao = sharedDao ?: SoundLapaDatabase.getInstance(this).soundLapaDao().also { sharedDao = it }
        val attrs = sharedAudioAttributes ?: AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build().also { sharedAudioAttributes = it }

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 10_000,
                /* maxBufferMs = */ 40_000,
                /* bufferForPlaybackMs = */ 200,
                /* bufferForPlaybackAfterRebufferMs = */ 600,
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val extractorsFactory = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
        val mediaSourceFactory = SoundLapaMediaSourceFactory(
            context = this,
            dao = dao,
            resolveScope = serviceScope,
            extractorsFactory = extractorsFactory,
        ).apply {
            setLoadErrorHandlingPolicy(
                object : DefaultLoadErrorHandlingPolicy(4) {
                    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
                        val cause = loadErrorInfo.exception
                        if (cause is HttpDataSource.InvalidResponseCodeException &&
                            (cause.responseCode == 403 || cause.responseCode == 410 || cause.responseCode == 404)
                        ) {
                            // Fail immediately on expired signed CDN URLs so PlaybackController re-resolves cleanly
                            return C.TIME_UNSET
                        }
                        return super.getRetryDelayMsFor(loadErrorInfo)
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
        if (outgoing == null || attrs == null || targetIndex !in mediaItems.indices) {
            fallbackApplyOnActive()
            return
        }

        isReplacingCrossfade = true
        crossfadeJob?.cancel()
        isReplacingCrossfade = false
        val myGeneration = ++crossfadeGeneration

        runCatching {
            incoming.stop()
            incoming.clearMediaItems()
        }

        val safeFadeMs = fadeDurationMs.coerceIn(500L, 10_000L)
        val startOutVol = outgoing.volume.coerceIn(0.05f, 1f)
        val safeStart = startPositionMs.coerceAtLeast(0L)
        val expectedIncomingId = mediaItems[targetIndex].mediaId

        crossfadeJob = serviceScope.launch {
            var swapped = false
            try {
                // 1. Prepare incoming track silently on standby deck while outgoing continues untouched
                incoming.volume = 0f
                incoming.playWhenReady = false
                incoming.repeatMode = repeatMode
                incoming.shuffleModeEnabled = shuffleEnabled
                incoming.setMediaItems(mediaItems, targetIndex, safeStart)
                incoming.prepare()

                var waitedReadyMs = 0L
                val maxPrepareWaitMs = 9_000L
                while (isActive &&
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

                if (!isActive) return@launch

                if (incoming.playbackState != Player.STATE_READY) {
                    // Fallback: incoming could not buffer before deadline; let active deck handle directly
                    runCatching {
                        incoming.stop()
                        incoming.clearMediaItems()
                        incoming.volume = 1f
                    }
                    outgoing.volume = 1f
                    fallbackApplyOnActive()
                    return@launch
                }

                // 2. If triggered slightly early for pre-buffering, wait until remaining enters fade window
                if (maxLeadWaitMs > 0L) {
                    var leadElapsed = 0L
                    while (isActive &&
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

                if (!isActive || !outgoing.isPlaying || outgoing.playbackState == Player.STATE_ENDED) {
                    runCatching {
                        incoming.stop()
                        incoming.clearMediaItems()
                        incoming.volume = 1f
                    }
                    outgoing.volume = 1f
                    fallbackApplyOnActive()
                    return@launch
                }

                // 3. Start incoming track at zero volume and prime audio decoder before session swap
                incoming.volume = 0f
                incoming.playWhenReady = true
                incoming.play()

                var primeWaitMs = 0L
                while (isActive && !incoming.isPlaying && primeWaitMs < 140L) {
                    delay(16L)
                    primeWaitMs += 16L
                }

                // 4. Swap active deck cleanly on MediaSession without interrupting outgoing playback
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

                onDeckActivated(expectedIncomingId, true)

                val outgoingTotalDur = outgoing.duration
                val effectiveFadeMs = if (outgoingTotalDur > 0L) {
                    val remainingOut = (outgoingTotalDur - outgoing.currentPosition).coerceAtLeast(350L)
                    minOf(safeFadeMs, remainingOut).coerceAtLeast(350L)
                } else {
                    safeFadeMs
                }

                // 5. Equal-power cubic smoothstep crossfade (no volume dip, no abrupt jump)
                var fadeElapsedMs = 0L
                var lastTickMs = SystemClock.elapsedRealtime()
                while (isActive) {
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

                    outgoing.volume = outGain
                    incoming.volume = inGain

                    if (t >= 1f) break
                    delay(16L)
                }
                incoming.volume = 1f
            } finally {
                if (myGeneration == crossfadeGeneration) {
                    isTransitioningSwap = false
                    if (!isReplacingCrossfade) {
                        activePlayer?.volume = 1f
                    }
                    val deckToReset = if (swapped) outgoing else incoming
                    if (deckToReset !== activePlayer) {
                        runCatching {
                            deckToReset.stop()
                            deckToReset.clearMediaItems()
                            deckToReset.volume = 1f
                        }
                    }
                }
            }
        }
    }

    private fun cancelCrossfade(restorePrimaryVolume: Boolean) {
        isReplacingCrossfade = false
        isTransitioningSwap = false
        crossfadeJob?.cancel()
        crossfadeJob = null
        standbyPlayer?.let { standby ->
            runCatching {
                standby.stop()
                standby.clearMediaItems()
                standby.volume = 1f
            }
        }
        if (restorePrimaryVolume) {
            activePlayer?.volume = if (isDuckedByFocus && currentFocusMode == AudioFocusMode.DUCK) 0.25f else 1f
        }
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
                    activePlayer?.volume = 1f
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
                    activePlayer?.volume = 1f
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
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        if (activeInstance === this) {
            activeInstance = null
        }
        abandonAppAudioFocus()
        cancelCrossfade(restorePrimaryVolume = true)
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

        private val _audioFocusStatus = MutableStateFlow(AudioFocusUiStatus.IDLE)
        val audioFocusStatus: StateFlow<AudioFocusUiStatus> = _audioFocusStatus.asStateFlow()

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
    }
}

@UnstableApi
private class SoundLapaMediaSourceFactory(
    private val context: Context,
    private val dao: SoundLapaDao,
    private val resolveScope: CoroutineScope,
    private val extractorsFactory: DefaultExtractorsFactory,
) : MediaSource.Factory {

    private var drmProvider: DrmSessionManagerProvider? = null
    private var loadErrorPolicy: LoadErrorHandlingPolicy? = null

    private val localHttpFactory = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
        .setUserAgent(FullStreamResolver.DEFAULT_BROWSER_USER_AGENT)
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(30_000)

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
            val dataSourceFactory = DefaultDataSource.Factory(context, localHttpFactory)
            return ProgressiveMediaSource.Factory(dataSourceFactory, extractorsFactory).apply {
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
            drmProvider = drmProvider,
            loadErrorPolicy = loadErrorPolicy,
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
    private val drmProvider: DrmSessionManagerProvider?,
    private val loadErrorPolicy: LoadErrorHandlingPolicy?,
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
        resolveJob = resolveScope.launch(Dispatchers.IO) {
            val resolved = runCatching {
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
        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
        if (resolved.headers.isNotEmpty()) {
            httpFactory.setDefaultRequestProperties(resolved.headers)
        } else {
            httpFactory.setUserAgent(FullStreamResolver.DEFAULT_BROWSER_USER_AGENT)
        }
        val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)

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
