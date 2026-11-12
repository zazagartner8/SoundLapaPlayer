package com.example.player

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.example.MainActivity
import com.example.data.local.SoundLapaDatabase
import com.example.data.remote.FullStreamResolver
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
    private var primaryPlayer: ExoPlayer? = null
    private var outgoingPlayer: ExoPlayer? = null
    private var crossfadeJob: Job? = null
    private var isReplacingCrossfade = false

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        activeInstance = this

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        FullStreamResolver.ensureInitialized()
        val dao = SoundLapaDatabase.getInstance(this).soundLapaDao()

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
            )
            .setConnectTimeoutMs(12_000)
            .setReadTimeoutMs(20_000)

        val baseDataSourceFactory = DefaultDataSource.Factory(this, httpDataSourceFactory)
        val dataSourceFactory = ResolvingDataSource.Factory(baseDataSourceFactory) { dataSpec ->
            val rawUri = dataSpec.uri.toString()
            val resolvedUri = FullStreamResolver.resolveDataSpecUriBlocking(rawUri, dao)
            if (resolvedUri != null && resolvedUri != rawUri) {
                dataSpec.withUri(Uri.parse(resolvedUri))
            } else {
                dataSpec
            }
        }

        val extractorsFactory = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)

        val mainPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory))
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        // Secondary deck for Spotify-style overlapping crossfades (never steals audio focus)
        val fadeDeck = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory))
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ false)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        mainPlayer.addListener(object : Player.Listener {
            override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
                updateAudioFocusTelemetry(mainPlayer, playbackSuppressionReason)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updateAudioFocusTelemetry(mainPlayer, mainPlayer.playbackSuppressionReason)
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady && crossfadeJob?.isActive == true) {
                    cancelCrossfade(restorePrimaryVolume = true)
                }
            }
        })

        primaryPlayer = mainPlayer
        outgoingPlayer = fadeDeck

        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        mediaSession = MediaSession.Builder(this, mainPlayer)
            .setSessionActivity(sessionActivityPendingIntent)
            .build()
    }

    private fun startDualDeckCrossfade(
        outgoingItem: MediaItem,
        outgoingPositionMs: Long,
        fadeDurationMs: Long,
        onSwitchIncomingDeck: () -> Unit,
    ) {
        val main = primaryPlayer ?: run {
            onSwitchIncomingDeck()
            return
        }
        val outgoing = outgoingPlayer ?: run {
            onSwitchIncomingDeck()
            return
        }

        isReplacingCrossfade = true
        crossfadeJob?.cancel()
        isReplacingCrossfade = false

        val safeFadeMs = fadeDurationMs.coerceIn(400L, 10_000L)
        val startOutVol = main.volume.coerceIn(0.05f, 1f)

        crossfadeJob = serviceScope.launch {
            try {
                // Prime outgoing deck at current position while primary keeps playing smoothly
                outgoing.stop()
                outgoing.clearMediaItems()
                outgoing.volume = startOutVol
                outgoing.setMediaItem(outgoingItem, outgoingPositionMs.coerceAtLeast(0L))
                outgoing.prepare()
                outgoing.playWhenReady = true

                var waitedMs = 0L
                while (isActive && outgoing.playbackState == Player.STATE_BUFFERING && waitedMs < 320L) {
                    delay(16L)
                    waitedMs += 16L
                }

                // Switch primary deck immediately to incoming track starting silently at 0f
                main.volume = 0f
                onSwitchIncomingDeck()

                // Equal-power overlapping crossfade: cos(t*pi/2) out, sin(t*pi/2) in
                val startTime = SystemClock.elapsedRealtime()
                while (isActive) {
                    val elapsed = SystemClock.elapsedRealtime() - startTime
                    val t = (elapsed.toFloat() / safeFadeMs.toFloat()).coerceIn(0f, 1f)
                    val angle = t * (PI / 2.0)
                    val outGain = cos(angle).toFloat().coerceIn(0f, 1f)
                    val inGain = sin(angle).toFloat().coerceIn(0f, 1f)

                    outgoing.volume = (startOutVol * outGain).coerceIn(0f, 1f)
                    main.volume = inGain

                    if (t >= 1f) break
                    delay(16L)
                }
                main.volume = 1f
            } finally {
                if (!isReplacingCrossfade) {
                    main.volume = 1f
                }
                runCatching {
                    outgoing.stop()
                    outgoing.clearMediaItems()
                    outgoing.volume = 1f
                }
            }
        }
    }

    private fun cancelCrossfade(restorePrimaryVolume: Boolean) {
        isReplacingCrossfade = false
        crossfadeJob?.cancel()
        crossfadeJob = null
        outgoingPlayer?.let { outgoing ->
            runCatching {
                outgoing.stop()
                outgoing.clearMediaItems()
                outgoing.volume = 1f
            }
        }
        if (restorePrimaryVolume) {
            primaryPlayer?.volume = 1f
        }
    }

    private fun updateAudioFocusTelemetry(player: Player, suppressionReason: Int) {
        _audioFocusStatus.value = when {
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
        cancelCrossfade(restorePrimaryVolume = true)
        serviceScope.cancel()
        outgoingPlayer?.release()
        outgoingPlayer = null
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        primaryPlayer = null
        _audioFocusStatus.value = AudioFocusUiStatus.IDLE
        super.onDestroy()
    }

    companion object {
        @Volatile
        private var activeInstance: SoundLapaPlaybackService? = null

        private val _audioFocusStatus = MutableStateFlow(AudioFocusUiStatus.IDLE)
        val audioFocusStatus: StateFlow<AudioFocusUiStatus> = _audioFocusStatus.asStateFlow()

        fun performDualDeckCrossfade(
            outgoingItem: MediaItem,
            outgoingPositionMs: Long,
            fadeDurationMs: Long,
            onSwitchIncomingDeck: () -> Unit,
        ) {
            val service = activeInstance
            if (service != null) {
                service.startDualDeckCrossfade(
                    outgoingItem = outgoingItem,
                    outgoingPositionMs = outgoingPositionMs,
                    fadeDurationMs = fadeDurationMs,
                    onSwitchIncomingDeck = onSwitchIncomingDeck,
                )
            } else {
                onSwitchIncomingDeck()
            }
        }

        fun stopActiveCrossfade(restorePrimaryVolume: Boolean = true) {
            activeInstance?.cancelCrossfade(restorePrimaryVolume)
        }
    }
}
