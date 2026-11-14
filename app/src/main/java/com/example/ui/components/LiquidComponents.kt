package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.local.TrackEntity
import com.example.ui.BugReportUiState
import com.example.ui.SoundLapaTab
import com.example.ui.formatDuration
import com.example.ui.i18n.LocalAppStrings
import com.example.ui.shaders.TrackCoverArtwork
import com.example.ui.theme.LiquidMint
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs

@Composable
fun SystemUtilityTopBar(
    onBugReportClick: () -> Unit,
    onAboutClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    val strings = LocalAppStrings.current
    val accent = MaterialTheme.colorScheme.primary
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val creatorLabel = remember(accent, onSurfaceVariant) {
        buildAnnotatedString {
            withStyle(SpanStyle(color = onSurfaceVariant, fontWeight = FontWeight.Medium)) {
                append("by ")
            }
            withStyle(SpanStyle(color = accent, fontWeight = FontWeight.Bold)) {
                append("@TylerW1nston")
            }
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Surface(
            onClick = { runCatching { uriHandler.openUri("https://t.me/tylerw1nston") } },
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
            modifier = Modifier.testTag("top_creator_badge"),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Image(
                    painter = painterResource(R.drawable.soundlapa_brand_logo),
                    contentDescription = "SoundLapa Logo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(21.dp)
                        .clip(CircleShape),
                )
                Text(
                    text = creatorLabel,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            CompactTopBarIconButton(
                icon = Icons.Rounded.Flag,
                contentDescription = strings.bugReportButtonCd,
                onClick = onBugReportClick,
                testTag = "top_bug_report_button",
            )
            CompactTopBarIconButton(
                icon = Icons.Rounded.Info,
                contentDescription = stringResource(R.string.cd_about_app),
                onClick = onAboutClick,
                testTag = "top_about_button",
            )
            CompactTopBarIconButton(
                icon = Icons.Rounded.Settings,
                contentDescription = stringResource(R.string.cd_settings),
                onClick = onSettingsClick,
                testTag = "top_settings_button",
            )
        }
    }
}

@Composable
private fun CompactTopBarIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    testTag: String,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .size(48.dp)
            .testTag(testTag),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(21.dp),
            )
        }
    }
}

@Composable
fun NetworkReconnectedBanner(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current
    val accent = MaterialTheme.colorScheme.primary

    Surface(
        onClick = onDismiss,
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .border(
                width = 1.dp,
                color = accent.copy(alpha = 0.55f),
                shape = CircleShape,
            )
            .testTag("network_reconnected_banner"),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 8.dp,
        shadowElevation = 10.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Wifi,
                    contentDescription = strings.networkReconnectedMessage,
                    tint = accent,
                    modifier = Modifier.size(17.dp),
                )
            }
            Text(
                text = strings.networkReconnectedMessage,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun OfflineNetworkErrorBanner(
    offlineTracksCount: Int,
    onGoToDownloadedTracks: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current
    val haptics = rememberSoundLapaHaptics()
    val errorTint = MaterialTheme.colorScheme.error

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .border(
                width = 1.dp,
                color = errorTint.copy(alpha = 0.45f),
                shape = RoundedCornerShape(18.dp),
            )
            .testTag("offline_error_banner"),
        shape = RoundedCornerShape(18.dp),
        color = errorTint.copy(alpha = 0.14f),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.WifiOff,
                    contentDescription = strings.offlineErrorTitle,
                    tint = errorTint,
                    modifier = Modifier.size(22.dp),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = strings.offlineErrorTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = if (offlineTracksCount > 0) {
                            strings.offlineErrorWithDownloadsMessage(offlineTracksCount)
                        } else {
                            strings.offlineErrorNoDownloadsMessage
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (offlineTracksCount > 0) {
                Button(
                    onClick = {
                        haptics.tap()
                        onGoToDownloadedTracks()
                    },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("offline_banner_go_downloaded_button"),
                ) {
                    Text(
                        text = strings.offlineGoToDownloadedAction,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private val QuickPickShape = RoundedCornerShape(15.dp)
private val TrackRowShape = RoundedCornerShape(18.dp)
private val TrackThumbOverlayShape = RoundedCornerShape(14.dp)
private val MiniPlayerShape = RoundedCornerShape(22.dp)

@Composable
fun QuickPickGridCard(
    track: TrackEntity,
    isCurrentPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .testTag("quick_pick_${track.id}"),
        onClick = onClick,
        shape = QuickPickShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TrackCoverArtwork(
                track = track,
                modifier = Modifier.size(64.dp),
                cornerRadius = 15.dp,
                targetSizePx = 256,
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = track.title,
                style = MaterialTheme.typography.labelLarge,
                color = if (isCurrentPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
            )
        }
    }
}

@Composable
fun TrackRowItem(
    track: TrackEntity,
    isCurrentTrack: Boolean,
    isPlaying: Boolean,
    onPlayClick: () -> Unit,
    onLikeClick: () -> Unit,
    onMoreClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onRemoveFromPlaylistClick: (() -> Unit)? = null,
) {
    val strings = LocalAppStrings.current
    val subtitle = remember(track.artist, track.durationMs) {
        "${track.artist} • ${formatDuration(track.durationMs)}"
    }
    val rowBackground = if (isCurrentTrack) {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f)
    } else {
        Color.Transparent
    }
    val thumbSize = if (compact) 48.dp else 62.dp
    val thumbRadius = if (compact) 11.dp else 15.dp
    val verticalPad = if (compact) 5.dp else 8.dp

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(rowBackground, TrackRowShape)
            .clickable(onClick = onPlayClick)
            .padding(horizontal = 8.dp, vertical = verticalPad)
            .testTag("track_row_${track.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 12.dp),
    ) {
        Box(
            modifier = Modifier.size(thumbSize),
            contentAlignment = Alignment.Center,
        ) {
            TrackCoverArtwork(
                track = track,
                modifier = Modifier.matchParentSize(),
                cornerRadius = thumbRadius,
                targetSizePx = if (compact) 192 else 256,
            )
            if (isCurrentTrack) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(Color.Black.copy(alpha = 0.45f), TrackThumbOverlayShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Rounded.GraphicEq else Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(if (compact) 20.dp else 24.dp),
                    )
                }
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 3.dp),
        ) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (isCurrentTrack) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (onRemoveFromPlaylistClick != null) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onRemoveFromPlaylistClick)
                    .testTag("track_remove_${track.id}"),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .border(
                            width = 1.4.dp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                            shape = CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = strings.removeFromPlaylist,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onLikeClick)
                    .testTag("track_like_${track.id}"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (track.isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    contentDescription = strings.cdLikeTrack,
                    tint = if (track.isLiked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .clickable(onClick = onMoreClick)
                .testTag("track_more_${track.id}"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.MoreVert,
                contentDescription = strings.cdMoreOptions,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
fun FloatingMiniPlayer(
    track: TrackEntity?,
    isPlaying: Boolean,
    positionFlow: StateFlow<Long>,
    durationMs: Long,
    onPrevious: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(visible = track != null, modifier = modifier) {
        if (track == null) return@AnimatedVisibility
        val accent = Color(track.accentColorHex)

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .clip(MiniPlayerShape)
                .clickable(onClick = onExpand)
                .testTag("floating_mini_player"),
            shape = MiniPlayerShape,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.86f),
            tonalElevation = 8.dp,
            shadowElevation = 8.dp,
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TrackCoverArtwork(
                        track = track,
                        modifier = Modifier.size(46.dp),
                        cornerRadius = 14.dp,
                        targetSizePx = 220,
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.Start,
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = track.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = track.artist,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(0.dp),
                    ) {
                        IconButton(
                            onClick = onPrevious,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("mini_player_previous"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.SkipPrevious,
                                contentDescription = stringResource(R.string.cd_previous_track),
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(24.dp),
                            )
                        }

                        IconButton(
                            onClick = onTogglePlayPause,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("mini_player_play_pause"),
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = stringResource(R.string.cd_play_pause),
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(28.dp),
                            )
                        }

                        IconButton(
                            onClick = onNext,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("mini_player_next"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.SkipNext,
                                contentDescription = stringResource(R.string.cd_next_track),
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                }

                MiniPlayerProgressLine(
                    trackId = track.id,
                    positionFlow = positionFlow,
                    durationMs = durationMs,
                    isPlaying = isPlaying,
                    accent = accent,
                )
            }
        }
    }
}

@Composable
private fun MiniPlayerProgressLine(
    trackId: String,
    positionFlow: StateFlow<Long>,
    durationMs: Long,
    isPlaying: Boolean,
    accent: Color,
) {
    val basePosMs by positionFlow.collectAsStateWithLifecycle()
    val animFraction = remember(trackId) {
        val init = if (durationMs > 0L) (basePosMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
        Animatable(if (init.isFinite()) init else 0f)
    }

    LaunchedEffect(trackId, basePosMs, durationMs, isPlaying) {
        val target = if (durationMs > 0L) {
            (basePosMs.toFloat() / durationMs.toFloat())
                .let { if (it.isFinite()) it.coerceIn(0f, 1f) else 0f }
        } else {
            0f
        }
        val delta = abs(target - animFraction.value)
        if (!isPlaying || delta > 0.04f || target < animFraction.value) {
            animFraction.snapTo(target)
        } else {
            animFraction.animateTo(
                targetValue = target,
                animationSpec = tween(durationMillis = 190, easing = LinearEasing),
            )
        }
    }

    LinearProgressIndicator(
        progress = { animFraction.value.coerceIn(0f, 1f) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 3.dp)
            .height(3.dp)
            .clip(CircleShape),
        color = accent,
        trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f),
        strokeCap = StrokeCap.Round,
    )
}

@Composable
fun LiquidBottomNavBar(
    selectedTab: SoundLapaTab,
    onSelectTab: (SoundLapaTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = if (isDark) Color(0xEC0E0D14) else MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        tonalElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 24.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavTabItem(
                selected = selectedTab == SoundLapaTab.HOME,
                label = strings.tabHome,
                selectedIconRes = R.drawable.ic_nav_home_filled,
                unselectedIconRes = R.drawable.ic_nav_home_outline,
                testTag = "nav_tab_home",
                onClick = { onSelectTab(SoundLapaTab.HOME) },
                modifier = Modifier.weight(1f),
            )
            NavTabItem(
                selected = selectedTab == SoundLapaTab.SEARCH,
                label = strings.tabSearch,
                selectedIconRes = R.drawable.ic_nav_search_filled,
                unselectedIconRes = R.drawable.ic_nav_search_outline,
                testTag = "nav_tab_search",
                onClick = { onSelectTab(SoundLapaTab.SEARCH) },
                modifier = Modifier.weight(1f),
            )
            NavTabItem(
                selected = selectedTab == SoundLapaTab.LIBRARY,
                label = strings.tabLibrary,
                selectedIconRes = R.drawable.ic_nav_library_filled,
                unselectedIconRes = R.drawable.ic_nav_library_outline,
                testTag = "nav_tab_library",
                onClick = { onSelectTab(SoundLapaTab.LIBRARY) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun NavTabItem(
    selected: Boolean,
    label: String,
    selectedIconRes: Int,
    unselectedIconRes: Int,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    val iconScale by animateFloatAsState(
        targetValue = if (selected) 1.08f else 0.96f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 480f),
        label = "navIconScale",
    )
    val iconTint by animateColorAsState(
        targetValue = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
        animationSpec = tween(180),
        label = "navIconTint",
    )
    val labelColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.70f),
        animationSpec = tween(180),
        label = "navLabelColor",
    )

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp)
            .testTag(testTag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(if (selected) accent.copy(alpha = 0.13f) else Color.Transparent)
                .padding(horizontal = 16.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(if (selected) selectedIconRes else unselectedIconRes),
                contentDescription = label,
                tint = iconTint,
                modifier = Modifier
                    .size(22.dp)
                    .scale(iconScale),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontSize = 11.sp,
            lineHeight = 13.sp,
            fontWeight = FontWeight.Bold,
            color = labelColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun LiquidNavigationRail(
    selectedTab: SoundLapaTab,
    onSelectTab: (SoundLapaTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current
    val accent = MaterialTheme.colorScheme.primary
    NavigationRail(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
    ) {
        Spacer(modifier = Modifier.height(16.dp))
        NavigationRailItem(
            selected = selectedTab == SoundLapaTab.HOME,
            onClick = { onSelectTab(SoundLapaTab.HOME) },
            icon = {
                Icon(
                    painter = painterResource(
                        if (selectedTab == SoundLapaTab.HOME) R.drawable.ic_nav_home_filled
                        else R.drawable.ic_nav_home_outline,
                    ),
                    contentDescription = strings.tabHome,
                    modifier = Modifier.size(22.dp),
                )
            },
            label = { Text(strings.tabHome) },
            colors = NavigationRailItemDefaults.colors(indicatorColor = accent.copy(alpha = 0.18f)),
        )
        NavigationRailItem(
            selected = selectedTab == SoundLapaTab.SEARCH,
            onClick = { onSelectTab(SoundLapaTab.SEARCH) },
            icon = {
                Icon(
                    painter = painterResource(
                        if (selectedTab == SoundLapaTab.SEARCH) R.drawable.ic_nav_search_filled
                        else R.drawable.ic_nav_search_outline,
                    ),
                    contentDescription = strings.tabSearch,
                    modifier = Modifier.size(22.dp),
                )
            },
            label = { Text(strings.tabSearch) },
            colors = NavigationRailItemDefaults.colors(indicatorColor = accent.copy(alpha = 0.18f)),
        )
        NavigationRailItem(
            selected = selectedTab == SoundLapaTab.LIBRARY,
            onClick = { onSelectTab(SoundLapaTab.LIBRARY) },
            icon = {
                Icon(
                    painter = painterResource(
                        if (selectedTab == SoundLapaTab.LIBRARY) R.drawable.ic_nav_library_filled
                        else R.drawable.ic_nav_library_outline,
                    ),
                    contentDescription = strings.tabLibrary,
                    modifier = Modifier.size(22.dp),
                )
            },
            label = { Text(strings.tabLibrary) },
            colors = NavigationRailItemDefaults.colors(indicatorColor = accent.copy(alpha = 0.18f)),
        )
    }
}

@Composable
fun BugReportDialog(
    bugReportState: BugReportUiState,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalAppStrings.current
    val haptics = rememberSoundLapaHaptics()
    val accent = MaterialTheme.colorScheme.primary
    var messageText by remember { mutableStateOf("") }

    LaunchedEffect(bugReportState.statusMessage) {
        if (bugReportState.statusMessage == "sent") {
            messageText = ""
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Rounded.Flag,
                contentDescription = strings.bugReportDialogTitle,
                tint = accent,
            )
        },
        title = {
            Text(
                text = strings.bugReportDialogTitle,
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = strings.bugReportDialogHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = messageText,
                    onValueChange = { messageText = it },
                    label = { Text(strings.bugReportInputLabel) },
                    placeholder = { Text(strings.bugReportInputPlaceholder) },
                    minLines = 3,
                    maxLines = 6,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("bug_report_message_input"),
                )
                if (bugReportState.statusMessage == "sent") {
                    Text(
                        text = if (bugReportState.savedOfflineOnly) {
                            strings.bugReportSavedOfflineNote
                        } else {
                            strings.bugReportSentSuccess
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = LiquidMint,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.testTag("bug_report_status_text"),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    haptics.confirm()
                    onSubmit(messageText)
                },
                enabled = messageText.isNotBlank() && !bugReportState.isSubmitting,
                colors = ButtonDefaults.buttonColors(
                    containerColor = accent,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                modifier = Modifier.testTag("bug_report_submit_button"),
            ) {
                if (bugReportState.isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(strings.bugReportSendingLabel)
                } else {
                    Text(strings.bugReportSendButton)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.close)
            }
        },
    )
}

