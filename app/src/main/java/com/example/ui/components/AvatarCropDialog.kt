package com.example.ui.components

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ui.i18n.LocalAppStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * Interactive circular avatar cropper allowing pinch-to-zoom, zoom slider, and finger pan
 * to center the selected image before AI moderation and cloud upload.
 */
@Composable
fun AvatarCropDialog(
    sourceUri: Uri,
    accent: Color,
    onDismiss: () -> Unit,
    onConfirmCropped: (croppedBitmap: Bitmap) -> Unit,
) {
    val context = LocalContext.current
    val strings = LocalAppStrings.current
    val density = LocalDensity.current

    var sourceBitmap by remember(sourceUri) { mutableStateOf<Bitmap?>(null) }
    var isLoading by remember(sourceUri) { mutableStateOf(true) }
    var loadFailed by remember(sourceUri) { mutableStateOf(false) }

    var zoom by remember(sourceUri) { mutableFloatStateOf(1f) }
    var offsetX by remember(sourceUri) { mutableFloatStateOf(0f) }
    var offsetY by remember(sourceUri) { mutableFloatStateOf(0f) }

    LaunchedEffect(sourceUri) {
        isLoading = true
        loadFailed = false
        val bmp = withContext(Dispatchers.IO) {
            decodeAvatarSourceBitmap(context, sourceUri, maxDimPx = 1024)
        }
        sourceBitmap = bmp
        loadFailed = bmp == null
        isLoading = false
    }

    DisposableEffect(sourceBitmap) {
        val current = sourceBitmap
        onDispose {
            // Cropped copy is produced before onDismiss; only recycle if no longer needed
        }
    }

    BackHandler(onBack = onDismiss)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CenterFocusStrong,
                        contentDescription = null,
                        tint = accent,
                    )
                    Text(
                        text = strings.playerV33.cropAvatarTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }

                Text(
                    text = strings.playerV33.cropAvatarHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )

                val bmp = sourceBitmap
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(Color(0xFF0F0D14)),
                    contentAlignment = Alignment.Center,
                ) {
                    val boxW = with(density) { maxWidth.toPx() }
                    val boxH = with(density) { maxHeight.toPx() }
                    val cropSizePx = minOf(boxW, boxH) * 0.84f

                    when {
                        isLoading -> {
                            CircularProgressIndicator(color = accent)
                        }
                        loadFailed || bmp == null -> {
                            Text(
                                text = strings.settingsProfileModerationErrorFallback,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        else -> {
                            val baseScale = max(cropSizePx / bmp.width, cropSizePx / bmp.height)
                            val totalScale = baseScale * zoom
                            val drawnW = bmp.width * totalScale
                            val drawnH = bmp.height * totalScale
                            val maxPanX = max(0f, (drawnW - cropSizePx) / 2f)
                            val maxPanY = max(0f, (drawnH - cropSizePx) / 2f)

                            val imageBitmap = remember(bmp) { bmp.asImageBitmap() }
                            val displayW = bmp.width * baseScale
                            val displayH = bmp.height * baseScale

                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .pointerInput(bmp, cropSizePx) {
                                        detectTransformGestures { _, pan, gestureZoom, _ ->
                                            val nextZoom = (zoom * gestureZoom).coerceIn(1f, 4.5f)
                                            val nextTotalScale = baseScale * nextZoom
                                            val nextMaxPanX = max(0f, (bmp.width * nextTotalScale - cropSizePx) / 2f)
                                            val nextMaxPanY = max(0f, (bmp.height * nextTotalScale - cropSizePx) / 2f)
                                            zoom = nextZoom
                                            offsetX = (offsetX + pan.x).coerceIn(-nextMaxPanX, nextMaxPanX)
                                            offsetY = (offsetY + pan.y).coerceIn(-nextMaxPanY, nextMaxPanY)
                                        }
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Image(
                                    bitmap = imageBitmap,
                                    contentDescription = strings.playerV33.cropAvatarTitle,
                                    contentScale = ContentScale.FillBounds,
                                    modifier = Modifier
                                        .size(
                                            width = with(density) { displayW.toDp() },
                                            height = with(density) { displayH.toDp() },
                                        )
                                        .graphicsLayer {
                                            scaleX = zoom
                                            scaleY = zoom
                                            translationX = offsetX.coerceIn(-maxPanX, maxPanX)
                                            translationY = offsetY.coerceIn(-maxPanY, maxPanY)
                                        },
                                )

                                Canvas(modifier = Modifier.fillMaxSize()) {
                                    val radius = cropSizePx / 2f
                                    val center = Offset(size.width / 2f, size.height / 2f)
                                    val circlePath = Path().apply {
                                        addOval(
                                            androidx.compose.ui.geometry.Rect(
                                                left = center.x - radius,
                                                top = center.y - radius,
                                                right = center.x + radius,
                                                bottom = center.y + radius,
                                            ),
                                        )
                                    }
                                    clipPath(circlePath, clipOp = ClipOp.Difference) {
                                        drawRect(Color.Black.copy(alpha = 0.62f))
                                    }
                                    drawCircle(
                                        color = accent.copy(alpha = 0.92f),
                                        radius = radius,
                                        center = center,
                                        style = androidx.compose.ui.graphics.drawscope.Stroke(
                                            width = 2.5.dp.toPx(),
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }

                // Zoom slider fallback + reset button for fine precision
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ZoomIn,
                        contentDescription = strings.playerV33.cropAvatarZoomLabel,
                        tint = accent,
                        modifier = Modifier.size(20.dp),
                    )
                    Slider(
                        value = zoom,
                        onValueChange = { next ->
                            zoom = next.coerceIn(1f, 4.5f)
                        },
                        valueRange = 1f..4.5f,
                        enabled = bmp != null,
                        colors = SliderDefaults.colors(
                            thumbColor = accent,
                            activeTrackColor = accent,
                            inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant,
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("avatar_crop_zoom_slider"),
                    )
                    TextButton(
                        onClick = {
                            zoom = 1f
                            offsetX = 0f
                            offsetY = 0f
                        },
                        enabled = bmp != null,
                        modifier = Modifier.testTag("avatar_crop_reset_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(strings.playerV33.cropAvatarResetButton)
                    }
                }

                Avatar18PlusWarningBanner()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("avatar_crop_cancel_button"),
                    ) {
                        Text(strings.cancel, maxLines = 1)
                    }
                    Button(
                        onClick = {
                            val src = bmp ?: return@Button
                            val cropSizePx = 280f * 0.84f * density.density
                            val baseScale = max(cropSizePx / src.width, cropSizePx / src.height)
                            val totalScale = baseScale * zoom
                            val maxPanX = max(1f, (src.width * totalScale - cropSizePx) / 2f)
                            val maxPanY = max(1f, (src.height * totalScale - cropSizePx) / 2f)
                            val normX = (offsetX / maxPanX).coerceIn(-1f, 1f)
                            val normY = (offsetY / maxPanY).coerceIn(-1f, 1f)
                            val cropped = renderCroppedAvatarBitmap(
                                source = src,
                                zoom = zoom,
                                normalizedOffsetX = normX,
                                normalizedOffsetY = normY,
                                outputPx = 512,
                            )
                            onConfirmCropped(cropped)
                        },
                        enabled = bmp != null && !isLoading,
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = accent),
                        modifier = Modifier
                            .weight(1.3f)
                            .height(48.dp)
                            .testTag("avatar_crop_confirm_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(strings.playerV33.cropAvatarApplyButton, maxLines = 1)
                    }
                }
            }
        }
    }
}
