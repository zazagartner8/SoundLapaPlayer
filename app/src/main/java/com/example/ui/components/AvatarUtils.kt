package com.example.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

fun compressAvatarToDataUrl(context: Context, uri: Uri): String? {
    return runCatching {
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, boundsOpts)
        }
        val rawW = boundsOpts.outWidth
        val rawH = boundsOpts.outHeight
        if (rawW <= 0 || rawH <= 0) return@runCatching null

        var sampleSize = 1
        while (max(rawW / sampleSize, rawH / sampleSize) > 384) {
            sampleSize *= 2
        }
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val decoded = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOpts)
        } ?: return@runCatching null

        // Center crop square
        val side = min(decoded.width, decoded.height)
        val xOff = (decoded.width - side) / 2
        val yOff = (decoded.height - side) / 2
        val squared = if (xOff > 0 || yOff > 0) {
            Bitmap.createBitmap(decoded, xOff, yOff, side, side).also {
                if (it != decoded) decoded.recycle()
            }
        } else {
            decoded
        }

        val targetPx = 96
        val scaled = if (squared.width != targetPx || squared.height != targetPx) {
            Bitmap.createScaledBitmap(squared, targetPx, targetPx, true).also {
                if (it != squared) squared.recycle()
            }
        } else {
            squared
        }

        val format = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }
        var quality = 66
        var bytes: ByteArray
        do {
            val out = ByteArrayOutputStream()
            scaled.compress(format, quality, out)
            bytes = out.toByteArray()
            quality -= 12
        } while (bytes.size > 12_000 && quality >= 28)

        scaled.recycle()
        "data:image/webp;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }.getOrNull()
}

fun decodeAvatarBitmapForModeration(context: Context, uri: Uri, maxDimPx: Int = 512): Bitmap? {
    return runCatching {
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, boundsOpts)
        }
        val rawW = boundsOpts.outWidth
        val rawH = boundsOpts.outHeight
        if (rawW <= 0 || rawH <= 0) return@runCatching null

        var sampleSize = 1
        while (max(rawW / sampleSize, rawH / sampleSize) > maxDimPx) {
            sampleSize *= 2
        }
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOpts)
        }
    }.getOrNull()
}

@Composable
fun AccountAvatarImage(
    avatarUrlOrData: String?,
    contentDescription: String?,
    size: Dp = 56.dp,
    accent: Color = MaterialTheme.colorScheme.primary,
    isGuest: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val clean = avatarUrlOrData?.trim().orEmpty()
    val decodedBitmap = remember(clean) {
        if (clean.startsWith("data:image", ignoreCase = true)) {
            val commaIdx = clean.indexOf(',')
            if (commaIdx > 0 && commaIdx < clean.length - 1) {
                runCatching {
                    val raw = Base64.decode(clean.substring(commaIdx + 1), Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(raw, 0, raw.size)?.asImageBitmap()
                }.getOrNull()
            } else {
                null
            }
        } else {
            null
        }
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(accent.copy(alpha = 0.16f))
            .border(1.5.dp, accent.copy(alpha = 0.45f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        when {
            decodedBitmap != null -> {
                Image(
                    bitmap = decodedBitmap,
                    contentDescription = contentDescription,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            clean.isNotBlank() && !clean.startsWith("data:", ignoreCase = true) -> {
                AsyncImage(
                    model = clean,
                    contentDescription = contentDescription,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            else -> {
                Icon(
                    imageVector = if (isGuest) Icons.Rounded.Person else Icons.Rounded.AccountCircle,
                    contentDescription = contentDescription,
                    tint = accent,
                    modifier = Modifier.size(size * 0.58f),
                )
            }
        }
    }
}
