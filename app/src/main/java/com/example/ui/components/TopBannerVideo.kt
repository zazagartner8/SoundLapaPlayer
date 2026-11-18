package com.example.ui.components

import android.graphics.Matrix
import android.net.Uri
import android.view.TextureView
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.example.R
import com.example.ui.shaders.LocalBatterySaverEnabled
import com.example.ui.shaders.LocalIsMainUiVisible

/**
 * Ambient muted looped video banner rendered seamlessly behind the top utility bar.
 * Uses TextureView + explicit CENTER_CROP transform + automatic state recovery so returning
 * after long navigation or off-screen pauses never leaves a blank black/transparent surface.
 */
@OptIn(UnstableApi::class)
@Composable
fun TopBannerVideo(
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val batterySaver = LocalBatterySaverEnabled.current
    val isUiVisible = LocalIsMainUiVisible.current
    val latestBatterySaver by rememberUpdatedState(batterySaver)
    val latestIsUiVisible by rememberUpdatedState(isUiVisible)
    val bg = MaterialTheme.colorScheme.background
    val isDark = bg.luminance() < 0.5f

    val videoWidthState = remember { mutableIntStateOf(0) }
    val videoHeightState = remember { mutableIntStateOf(0) }

    val exoPlayer = remember(context) {
        ExoPlayer.Builder(context).build().apply {
            val videoUri = Uri.parse("android.resource://${context.packageName}/${R.raw.banner_soundlap}")
            setMediaItem(MediaItem.fromUri(videoUri))
            repeatMode = Player.REPEAT_MODE_ALL
            volume = 0f
            playWhenReady = !batterySaver && isUiVisible
            prepare()
        }
    }

    fun applyCenterCropMatrix(view: TextureView, videoW: Int, videoH: Int) {
        val viewW = view.width.toFloat()
        val viewH = view.height.toFloat()
        if (viewW <= 0f || viewH <= 0f || videoW <= 0 || videoH <= 0) return
        val scaleX = viewW / videoW.toFloat()
        val scaleY = viewH / videoH.toFloat()
        val maxScale = maxOf(scaleX, scaleY)
        val dx = (viewW - videoW * maxScale) / 2f
        val dy = (viewH - videoH * maxScale) / 2f
        val matrix = Matrix().apply {
            setScale(
                (videoW * maxScale) / viewW,
                (videoH * maxScale) / viewH,
                0f,
                0f,
            )
            postTranslate(dx, dy)
        }
        view.setTransform(matrix)
    }

    fun ensurePlayingBanner() {
        if (latestBatterySaver || !latestIsUiVisible) return
        if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        if (exoPlayer.playbackState == Player.STATE_IDLE || exoPlayer.playbackState == Player.STATE_ENDED) {
            val videoUri = Uri.parse("android.resource://${context.packageName}/${R.raw.banner_soundlap}")
            exoPlayer.setMediaItem(MediaItem.fromUri(videoUri))
            exoPlayer.prepare()
        }
        exoPlayer.playWhenReady = true
        if (!exoPlayer.isPlaying) {
            exoPlayer.play()
        }
    }

    LaunchedEffect(batterySaver, isUiVisible) {
        if (batterySaver || !isUiVisible) {
            exoPlayer.playWhenReady = false
            exoPlayer.pause()
        } else {
            ensurePlayingBanner()
        }
    }

    DisposableEffect(exoPlayer) {
        onDispose {
            exoPlayer.release()
        }
    }

    DisposableEffect(lifecycleOwner, exoPlayer) {
        val playerListener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    videoWidthState.intValue = videoSize.width
                    videoHeightState.intValue = videoSize.height
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && !latestBatterySaver && latestIsUiVisible) {
                    exoPlayer.seekTo(0L)
                    exoPlayer.playWhenReady = true
                    exoPlayer.play()
                }
            }
        }
        exoPlayer.addListener(playerListener)

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START,
                Lifecycle.Event.ON_RESUME -> ensurePlayingBanner()
                Lifecycle.Event.ON_STOP -> exoPlayer.pause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            exoPlayer.removeListener(playerListener)
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val scrimBrush = remember(isDark, accent, bg) {
        val topTint = if (isDark) Color(0x2609080D) else Color(0x1C18151D)
        val midTint = if (isDark) Color(0x0A09080D) else Color(0x0818151D)
        Brush.verticalGradient(
            0.0f to topTint,
            0.42f to midTint,
            0.70f to bg.copy(alpha = 0.42f),
            0.88f to bg.copy(alpha = 0.84f),
            1.0f to bg,
        )
    }

    Box(modifier = modifier) {
        if (!batterySaver) {
            AndroidView(
                factory = { ctx ->
                    TextureView(ctx).apply {
                        isOpaque = false
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                            val tv = v as TextureView
                            val vw = if (videoWidthState.intValue > 0) videoWidthState.intValue else exoPlayer.videoSize.width
                            val vh = if (videoHeightState.intValue > 0) videoHeightState.intValue else exoPlayer.videoSize.height
                            if (vw > 0 && vh > 0) {
                                applyCenterCropMatrix(tv, vw, vh)
                            }
                        }
                        exoPlayer.setVideoTextureView(this)
                    }
                },
                update = { view ->
                    exoPlayer.setVideoTextureView(view)
                    val vw = if (videoWidthState.intValue > 0) videoWidthState.intValue else exoPlayer.videoSize.width
                    val vh = if (videoHeightState.intValue > 0) videoHeightState.intValue else exoPlayer.videoSize.height
                    if (vw > 0 && vh > 0) {
                        applyCenterCropMatrix(view, vw, vh)
                    }
                    ensurePlayingBanner()
                },
                onRelease = { view ->
                    exoPlayer.clearVideoTextureView(view)
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                bg,
                                accent.copy(alpha = if (isDark) 0.28f else 0.18f),
                                bg,
                            ),
                        ),
                    ),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(scrimBrush),
        )
    }
}
