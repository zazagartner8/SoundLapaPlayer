package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.local.TrackEntity
import com.example.ui.SoundLapaTab
import com.example.ui.formatDuration
import com.example.ui.i18n.LocalAppStrings
import com.example.ui.shaders.TrackCoverArtwork
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs

@Composable
fun SystemUtilityTopBar(
    onAboutClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
            modifier = Modifier
                .border(
                    width = 1.dp,
                    color = accent.copy(alpha = 0.28f),
                    shape = CircleShape,
                )
                .testTag("top_creator_badge"),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.GraphicEq,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = creatorLabel,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            IconButton(
                onClick = onAboutClick,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                    .testTag("top_about_button"),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Info,
                    contentDescription = stringResource(R.string.cd_about_app),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }

            IconButton(
                onClick = onSettingsClick,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                    .testTag("top_settings_button"),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Settings,
                    contentDescription = stringResource(R.string.cd_settings),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

private val QuickPickShape = RoundedCornerShape(15.dp)
private val TrackRowShape = RoundedCornerShape(18.dp)
private val TrackThumbOverlayShape = RoundedCornerShape(14.dp)

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
            .height(58.dp)
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
                modifier = Modifier.size(58.dp),
                cornerRadius = 15.dp,
                targetSizePx = 140,
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

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(rowBackground, TrackRowShape)
            .clickable(onClick = onPlayClick)
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .testTag("track_row_${track.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier.size(54.dp),
            contentAlignment = Alignment.Center,
        ) {
            TrackCoverArtwork(
                track = track,
                modifier = Modifier.matchParentSize(),
                cornerRadius = 14.dp,
                targetSizePx = 140,
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
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
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
                .clip(RoundedCornerShape(22.dp))
                .clickable(onClick = onExpand)
                .testTag("floating_mini_player"),
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.94f),
            tonalElevation = 8.dp,
            shadowElevation = 8.dp,
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    TrackCoverArtwork(
                        track = track,
                        modifier = Modifier.size(42.dp),
                        cornerRadius = 13.dp,
                    )
                    Column(
                        modifier = Modifier.weight(1f),
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
                            modifier = Modifier.size(26.dp),
                        )
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
        color = if (isDark) Color(0xEE100F16) else MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        tonalElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavTabItem(
                selected = selectedTab == SoundLapaTab.HOME,
                label = strings.tabHome,
                selectedIcon = Icons.Rounded.Home,
                unselectedIcon = Icons.Rounded.Home,
                testTag = "nav_tab_home",
                onClick = { onSelectTab(SoundLapaTab.HOME) },
                modifier = Modifier.weight(1f),
            )
            NavTabItem(
                selected = selectedTab == SoundLapaTab.SEARCH,
                label = strings.tabSearch,
                selectedIcon = Icons.Rounded.Search,
                unselectedIcon = Icons.Rounded.Search,
                testTag = "nav_tab_search",
                onClick = { onSelectTab(SoundLapaTab.SEARCH) },
                modifier = Modifier.weight(1f),
            )
            NavTabItem(
                selected = selectedTab == SoundLapaTab.LIBRARY,
                label = strings.tabLibrary,
                selectedIcon = Icons.Rounded.LibraryMusic,
                unselectedIcon = Icons.Rounded.LibraryMusic,
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
    selectedIcon: androidx.compose.ui.graphics.vector.ImageVector,
    unselectedIcon: androidx.compose.ui.graphics.vector.ImageVector,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    val selectedBrush = remember(accent) {
        Brush.horizontalGradient(
            listOf(accent.copy(alpha = 0.24f), accent.copy(alpha = 0.12f)),
        )
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp)
            .testTag(testTag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .then(
                    if (selected) {
                        Modifier
                            .background(selectedBrush)
                            .border(1.dp, accent.copy(alpha = 0.4f), CircleShape)
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = 20.dp, vertical = 4.dp),
        ) {
            Icon(
                imageVector = if (selected) selectedIcon else unselectedIcon,
                contentDescription = label,
                tint = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
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
                    Icons.Rounded.Home,
                    contentDescription = strings.tabHome,
                )
            },
            label = { Text(strings.tabHome) },
            colors = NavigationRailItemDefaults.colors(indicatorColor = accent.copy(alpha = 0.25f)),
        )
        NavigationRailItem(
            selected = selectedTab == SoundLapaTab.SEARCH,
            onClick = { onSelectTab(SoundLapaTab.SEARCH) },
            icon = {
                Icon(
                    Icons.Rounded.Search,
                    contentDescription = strings.tabSearch,
                )
            },
            label = { Text(strings.tabSearch) },
            colors = NavigationRailItemDefaults.colors(indicatorColor = accent.copy(alpha = 0.25f)),
        )
        NavigationRailItem(
            selected = selectedTab == SoundLapaTab.LIBRARY,
            onClick = { onSelectTab(SoundLapaTab.LIBRARY) },
            icon = {
                Icon(
                    Icons.Rounded.LibraryMusic,
                    contentDescription = strings.tabLibrary,
                )
            },
            label = { Text(strings.tabLibrary) },
            colors = NavigationRailItemDefaults.colors(indicatorColor = accent.copy(alpha = 0.25f)),
        )
    }
}
