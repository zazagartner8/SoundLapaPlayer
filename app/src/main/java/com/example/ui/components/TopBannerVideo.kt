package com.example.ui.components

import android.net.Uri
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
import androidx.compose.runtime.remember
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
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.R
import com.example.ui.shaders.LocalBatterySaverEnabled

/**
 * Ambient muted looped video banner rendered seamlessly behind the top utility bar.
 * Automatically pauses video decoding when Battery Saver mode is active or the app goes background.
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
    val bg = MaterialTheme.colorScheme.background
    val isDark = bg.luminance() < 0.5f

    val exoPlayer = remember(context) {
        ExoPlayer.Builder(context).build().apply {
            val videoUri = Uri.parse("android.resource://${context.packageName}/${R.raw.banner_soundlap}")
            setMediaItem(MediaItem.fromUri(videoUri))
            repeatMode = Player.REPEAT_MODE_ALL
            volume = 0f
            playWhenReady = !batterySaver
            prepare()
        }
    }

    LaunchedEffect(batterySaver) {
        if (batterySaver) {
            exoPlayer.pause()
        } else if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            exoPlayer.playWhenReady = true
            exoPlayer.play()
        }
    }

    DisposableEffect(lifecycleOwner, exoPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    if (!batterySaver) {
                        exoPlayer.playWhenReady = true
                        exoPlayer.play()
                    }
                }
                Lifecycle.Event.ON_STOP -> {
                    exoPlayer.pause()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            exoPlayer.release()
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
                    PlayerView(ctx).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        player = exoPlayer
                    }
                },
                update = { view ->
                    if (view.player !== exoPlayer) {
                        view.player = exoPlayer
                    }
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
