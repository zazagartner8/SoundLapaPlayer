package com.example.ui.shaders

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import com.example.data.local.TrackEntity
import java.io.File

private val PlaceholderTrackBg = Color(0xFF1A1624)

@Composable
fun TrackCoverArtwork(
    track: TrackEntity,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 20.dp,
    targetSizePx: Int = 180,
    crossfade: Boolean = false,
) {
    val context = LocalContext.current
    val coverPath = track.coverArtPath
    val shape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }

    val imageModel = remember(coverPath, targetSizePx, crossfade) {
        if (coverPath.isNullOrBlank()) {
            null
        } else {
            val isRemoteUrl = coverPath.startsWith("http://") || coverPath.startsWith("https://")
            val data: Any = if (isRemoteUrl) coverPath else File(coverPath)
            ImageRequest.Builder(context)
                .data(data)
                .size(targetSizePx)
                .precision(Precision.INEXACT)
                .allowHardware(true)
                .crossfade(if (crossfade) 160 else 0)
                .build()
        }
    }

    if (imageModel != null) {
        AsyncImage(
            model = imageModel,
            contentDescription = track.title,
            contentScale = ContentScale.Crop,
            modifier = modifier
                .clip(shape)
                .background(PlaceholderTrackBg),
        )
    } else {
        val bgBrush = remember(track.accentColorHex, track.secondaryColorHex) {
            Brush.linearGradient(
                colors = listOf(Color(track.accentColorHex), Color(track.secondaryColorHex), Color(0xFF181420)),
            )
        }
        val initials = remember(track.title) {
            track.title
                .split(" ")
                .filter { it.isNotBlank() }
                .take(2)
                .joinToString("") { it.first().uppercaseChar().toString() }
                .ifBlank { "SL" }
        }
        Box(
            modifier = modifier
                .clip(shape)
                .background(bgBrush),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.matchParentSize()) {
                drawCircle(
                    color = Color.White.copy(alpha = 0.22f),
                    radius = size.minDimension * 0.36f,
                    center = Offset(size.width * 0.72f, size.height * 0.28f),
                )
                drawCircle(
                    color = Color.Black.copy(alpha = 0.24f),
                    radius = size.minDimension * 0.28f,
                    center = Offset(size.width * 0.30f, size.height * 0.72f),
                )
                drawCircle(
                    color = Color.White.copy(alpha = 0.45f),
                    radius = size.minDimension * 0.08f,
                    center = Offset(size.width * 0.5f, size.height * 0.5f),
                )
            }
            Text(
                text = initials,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.92f),
            )
        }
    }
}
