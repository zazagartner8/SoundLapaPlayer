package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.example.data.local.TrackEntity
import com.example.data.remote.ArtistAlbumItem
import com.example.ui.ArtistProfileUiState
import com.example.ui.components.TrackRowItem
import com.example.ui.components.rememberSoundLapaHaptics
import com.example.ui.i18n.LocalAppStrings
import com.example.ui.shaders.LocalBatterySaverEnabled
import com.example.ui.shaders.rememberIsAppForeground
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistProfileScreen(
    state: ArtistProfileUiState,
    currentTrackId: String?,
    isPlaying: Boolean,
    bottomContentPadding: Dp,
    isSubscribed: Boolean = false,
    onToggleSubscribe: () -> Unit = {},
    onClose: () -> Unit,
    onPlayAll: () -> Unit,
    onShufflePlay: () -> Unit,
    onPlayTrack: (TrackEntity, List<TrackEntity>) -> Unit,
    onToggleLike: (TrackEntity) -> Unit,
    onOpenTrackOptions: (TrackEntity) -> Unit,
    onOpenAlbum: (ArtistAlbumItem) -> Unit,
    onCloseAlbum: () -> Unit,
    onPlayAlbum: (List<TrackEntity>, TrackEntity?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current
    val haptics = rememberSoundLapaHaptics()
    val context = LocalContext.current

    BackHandler {
        if (state.selectedAlbum != null) {
            onCloseAlbum()
        } else {
            onClose()
        }
    }

    val heroAccent = Color(state.accentColorHex)
    val heroSecondary = Color(state.secondaryColorHex)
    val backgroundColor = MaterialTheme.colorScheme.background
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val collapseRangePx = remember(density) { with(density) { 260.dp.toPx() } }
    val itemZeroPx = remember(density) { with(density) { 80.dp.toPx() } }
    val scrollOffsetPx by remember {
        derivedStateOf {
            when {
                listState.firstVisibleItemIndex == 0 -> listState.firstVisibleItemScrollOffset.toFloat()
                listState.firstVisibleItemIndex == 1 -> itemZeroPx + listState.firstVisibleItemScrollOffset.toFloat()
                else -> collapseRangePx
            }
        }
    }
    val bannerCollapseFraction by remember {
        derivedStateOf { (scrollOffsetPx / collapseRangePx).coerceIn(0f, 1f) }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            )
            .testTag("artist_profile_screen"),
        contentAlignment = Alignment.TopCenter,
    ) {
        if (bannerCollapseFraction < 0.99f) {
            ArtistShimmeringBanner(
                avatarUrl = state.avatarUrl,
                heroAccent = heroAccent,
                heroSecondary = heroSecondary,
                backgroundColor = backgroundColor,
                collapseFraction = bannerCollapseFraction,
                scrollOffsetPx = scrollOffsetPx,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(340.dp)
                    .align(Alignment.TopCenter),
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 720.dp)
                .windowInsetsPadding(WindowInsets.statusBars),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 8.dp,
                bottom = bottomContentPadding,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "artist_top_bar") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    IconButton(
                        onClick = {
                            haptics.tick()
                            onClose()
                        },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f))
                            .testTag("artist_profile_back_button"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = strings.close,
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }

                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                    ) {
                        Text(
                            text = strings.artistProfileMenu,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                        )
                    }

                    Spacer(modifier = Modifier.size(48.dp))
                }
            }

            item(key = "artist_hero") {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(116.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(listOf(heroAccent, heroSecondary)),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        val avatar = state.avatarUrl
                        if (!avatar.isNullOrBlank()) {
                            SubcomposeAsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(avatar)
                                    .allowHardware(true)
                                    .crossfade(true)
                                    .size(420)
                                    .build(),
                                contentDescription = state.artistName,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(CircleShape),
                                error = {
                                    ArtistFallbackEmblem(name = state.artistName)
                                },
                                loading = {
                                    ArtistFallbackEmblem(name = state.artistName)
                                },
                            )
                        } else {
                            ArtistFallbackEmblem(name = state.artistName)
                        }
                    }

                    Text(
                        text = state.artistName,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("artist_profile_name"),
                    )

                    Text(
                        text = buildString {
                            append(strings.tracksCountLabel(state.tracks.size))
                            if (state.albums.isNotEmpty()) {
                                append("  •  ")
                                append(strings.artistAlbumsCountLabel(state.albums.size))
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Button(
                            onClick = {
                                haptics.tap()
                                onPlayAll()
                            },
                            enabled = state.tracks.isNotEmpty(),
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("artist_play_all_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = strings.artistPlayAll,
                                fontWeight = FontWeight.Bold,
                            )
                        }

                        FilledTonalButton(
                            onClick = {
                                haptics.tap()
                                onShufflePlay()
                            },
                            enabled = state.tracks.isNotEmpty(),
                            shape = CircleShape,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("artist_shuffle_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Shuffle,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = strings.artistShufflePlay,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            haptics.confirm()
                            onToggleSubscribe()
                        },
                        shape = CircleShape,
                        colors = if (isSubscribed) {
                            ButtonDefaults.outlinedButtonColors(
                                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                                contentColor = MaterialTheme.colorScheme.primary,
                            )
                        } else {
                            ButtonDefaults.outlinedButtonColors()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("artist_subscribe_button"),
                    ) {
                        Icon(
                            imageVector = if (isSubscribed) Icons.Rounded.Check else Icons.Rounded.NotificationsActive,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isSubscribed) strings.social.subscribedArtist else strings.social.subscribeArtist,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }

                    if (state.isLoading) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth(0.65f)
                                    .height(3.dp)
                                    .clip(CircleShape),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            )
                            Text(
                                text = strings.artistLoadingLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            if (state.albums.isNotEmpty()) {
                item(key = "artist_albums_section") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = strings.artistAlbumsTitle,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(
                                items = state.albums,
                                key = { it.id },
                                contentType = { "artist_album" },
                            ) { album ->
                                ArtistAlbumCard(
                                    album = album,
                                    accent = heroAccent,
                                    secondary = heroSecondary,
                                    onClick = {
                                        haptics.tick()
                                        onOpenAlbum(album)
                                    },
                                )
                            }
                        }
                    }
                }
            }

            item(key = "artist_tracks_header") {
                Text(
                    text = strings.artistTracksTitle,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }

            if (state.tracks.isEmpty() && !state.isLoading) {
                item(key = "artist_empty_tracks") {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(22.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    ) {
                        Text(
                            text = strings.artistEmptyTracks,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(22.dp),
                        )
                    }
                }
            } else {
                items(
                    items = state.tracks,
                    key = { "artist_tr_${it.id}" },
                    contentType = { "artist_track" },
                ) { track ->
                    val isCurrent = currentTrackId == track.id
                    TrackRowItem(
                        track = track,
                        isCurrentTrack = isCurrent,
                        isPlaying = isCurrent && isPlaying,
                        onPlayClick = {
                            haptics.tap()
                            onPlayTrack(track, state.tracks)
                        },
                        onLikeClick = {
                            haptics.confirm()
                            onToggleLike(track)
                        },
                        onMoreClick = {
                            haptics.tick()
                            onOpenTrackOptions(track)
                        },
                    )
                }
            }
        }

        val activeAlbum = state.selectedAlbum
        if (activeAlbum != null) {
            ModalBottomSheet(
                onDismissRequest = onCloseAlbum,
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        AlbumArtworkBox(
                            artworkUrl = activeAlbum.artworkUrl,
                            accent = heroAccent,
                            secondary = heroSecondary,
                            modifier = Modifier.size(72.dp),
                            cornerRadius = 18.dp,
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = activeAlbum.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = activeAlbum.artist,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    Button(
                        onClick = {
                            haptics.tap()
                            onPlayAlbum(state.selectedAlbumTracks, null)
                            onCloseAlbum()
                        },
                        enabled = state.selectedAlbumTracks.isNotEmpty() && !state.isLoadingAlbum,
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("artist_album_play_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = strings.artistAlbumPlay,
                            fontWeight = FontWeight.Bold,
                        )
                    }

                    if (state.isLoadingAlbum) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    } else if (state.selectedAlbumTracks.isEmpty()) {
                        Text(
                            text = strings.artistEmptyTracks,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 20.dp),
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 380.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(
                                items = state.selectedAlbumTracks,
                                key = { "alb_tr_${it.id}" },
                                contentType = { "artist_album_track" },
                            ) { track ->
                                val isCurrent = currentTrackId == track.id
                                TrackRowItem(
                                    track = track,
                                    isCurrentTrack = isCurrent,
                                    isPlaying = isCurrent && isPlaying,
                                    onPlayClick = {
                                        haptics.tap()
                                        onPlayAlbum(state.selectedAlbumTracks, track)
                                    },
                                    onLikeClick = {
                                        haptics.confirm()
                                        onToggleLike(track)
                                    },
                                    onMoreClick = {
                                        haptics.tick()
                                        onOpenTrackOptions(track)
                                    },
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                }
            }
        }
    }
}

@Composable
private fun ArtistShimmeringBanner(
    avatarUrl: String?,
    heroAccent: Color,
    heroSecondary: Color,
    backgroundColor: Color,
    collapseFraction: Float,
    scrollOffsetPx: Float,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val isForeground = rememberIsAppForeground()
    val batterySaver = LocalBatterySaverEnabled.current
    val phase = if (!batterySaver && isForeground && collapseFraction < 0.99f) {
        val infiniteTransition = rememberInfiniteTransition(label = "artistBannerShimmer")
        val animatedPhase by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = (2.0 * PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 14_000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "artistBannerPhase",
        )
        animatedPhase
    } else {
        0f
    }

    val visibleAlpha = (1f - collapseFraction).coerceIn(0f, 1f)
    val bottomFadeScrim = remember(backgroundColor) {
        Brush.verticalGradient(
            colors = listOf(
                backgroundColor.copy(alpha = 0.12f),
                backgroundColor.copy(alpha = 0.42f),
                backgroundColor,
            ),
        )
    }

    Box(
        modifier = modifier
            .graphicsLayer {
                alpha = visibleAlpha
                translationY = -scrollOffsetPx * 0.42f
                scaleY = 1f - collapseFraction * 0.10f
            }
            .clip(RoundedCornerShape(0.dp))
            .testTag("artist_shimmering_banner"),
    ) {
        if (!avatarUrl.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val ampX = size.width * 0.06f
                        val ampY = size.height * 0.07f
                        translationX = sin(phase) * ampX
                        translationY = cos(phase) * ampY
                        val scale = 1.42f + 0.08f * sin(phase)
                        scaleX = scale
                        scaleY = scale
                        rotationZ = sin(phase) * 3.5f
                        alpha = 0.44f
                    }
                    .let { base ->
                        if (batterySaver) base
                        else base.blur(
                            radius = 24.dp,
                            edgeTreatment = BlurredEdgeTreatment.Rectangle,
                        )
                    },
            ) {
                SubcomposeAsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(avatarUrl)
                        .allowHardware(true)
                        .crossfade(true)
                        .size(320)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // Translucent iridescent shimmer sweep over the avatar backdrop
        val shimmerOffset = 0.5f + 0.5f * sin(phase * 1.3f)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            heroAccent.copy(alpha = 0.24f),
                            Color.White.copy(alpha = 0.10f),
                            heroSecondary.copy(alpha = 0.22f),
                            heroAccent.copy(alpha = 0.14f),
                        ),
                        start = Offset(0f, shimmerOffset * 320f),
                        end = Offset(900f, (1f - shimmerOffset) * 520f),
                    ),
                ),
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(bottomFadeScrim),
        )
    }
}

@Composable
private fun ArtistFallbackEmblem(name: String) {
    val initial = name.trim().firstOrNull()?.uppercaseChar()?.toString()
    if (initial != null) {
        Text(
            text = initial,
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
    } else {
        Icon(
            imageVector = Icons.Rounded.Person,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(44.dp),
        )
    }
}

@Composable
private fun ArtistAlbumCard(
    album: ArtistAlbumItem,
    accent: Color,
    secondary: Color,
    onClick: () -> Unit,
) {
    val strings = LocalAppStrings.current
    Column(
        modifier = Modifier
            .width(148.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(4.dp)
            .testTag("artist_album_card_${album.id}"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AlbumArtworkBox(
            artworkUrl = album.artworkUrl,
            accent = accent,
            secondary = secondary,
            modifier = Modifier.size(140.dp),
            cornerRadius = 18.dp,
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = album.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (album.trackCount > 0) {
                    strings.tracksCountLabel(album.trackCount)
                } else {
                    album.artist
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AlbumArtworkBox(
    artworkUrl: String?,
    accent: Color,
    secondary: Color,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 18.dp,
) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(accent.copy(alpha = 0.72f), secondary.copy(alpha = 0.56f)),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (!artworkUrl.isNullOrBlank()) {
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(context)
                    .data(artworkUrl)
                    .allowHardware(true)
                    .crossfade(true)
                    .size(480)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                error = {
                    Icon(
                        imageVector = Icons.Rounded.Album,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(36.dp),
                    )
                },
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.Album,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.size(36.dp),
            )
        }
    }
}
