package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.data.local.TrackEntity
import com.example.ui.HomeUiState
import com.example.ui.components.QuickPickGridCard
import com.example.ui.components.TrackRowItem
import com.example.ui.components.rememberSoundLapaHaptics
import com.example.ui.i18n.LocalAppStrings
import com.example.ui.i18n.filterDisplayLabel
import com.example.ui.shaders.TrackCoverArtwork

@Composable
fun StreamScreen(
    state: HomeUiState,
    availableFilters: List<String>,
    onSelectFilter: (String) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onCloseSearch: () -> Unit,
    onPlayTrack: (TrackEntity) -> Unit,
    onToggleLike: (TrackEntity) -> Unit,
    onOpenTrackOptions: (TrackEntity) -> Unit,
    onOpenUploadSheet: () -> Unit,
    onRefreshTrending: () -> Unit,
    bottomContentPadding: Dp,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current
    val haptics = rememberSoundLapaHaptics()
    val listState = rememberLazyListState()
    val trendingRowState = rememberLazyListState()

    val currentOnPlayTrack by rememberUpdatedState(onPlayTrack)
    val currentOnToggleLike by rememberUpdatedState(onToggleLike)
    val currentOnOpenTrackOptions by rememberUpdatedState(onOpenTrackOptions)

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 720.dp),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 4.dp,
                bottom = bottomContentPadding,
            ),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item(key = "expandable_search") {
                AnimatedVisibility(visible = state.isSearchActive) {
                    CompactSearchField(
                        query = state.searchQuery,
                        placeholder = strings.searchPlaceholder,
                        closeContentDescription = strings.close,
                        isSearching = state.isSearching,
                        onQueryChange = onSearchQueryChange,
                        onClose = onCloseSearch,
                    )
                }
            }

            item(key = "filter_chips", contentType = "filter_chips") {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(
                        items = availableFilters,
                        key = { it },
                        contentType = { "filter_pill" },
                    ) { filter ->
                        val selected = state.selectedFilter == filter
                        FilterPill(
                            label = filterDisplayLabel(filter, strings),
                            testTag = "filter_chip_$filter",
                            selected = selected,
                            onClick = {
                                haptics.tick()
                                onSelectFilter(filter)
                            },
                        )
                    }
                }
            }

            if (state.quickPickRows.isNotEmpty() && !state.isSearchActive && state.selectedFilter == "Все") {
                item(key = "quick_picks_grid", contentType = "quick_picks") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = strings.quickPicks,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.quickPickRows.forEach { pair ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    pair.forEach { track ->
                                        val isCurrentPlaying =
                                            state.currentTrackId == track.id && state.isPlaying
                                        val onQuickPickClick = remember(track) {
                                            {
                                                haptics.tap()
                                                currentOnPlayTrack(track)
                                            }
                                        }
                                        QuickPickGridCard(
                                            track = track,
                                            isCurrentPlaying = isCurrentPlaying,
                                            onClick = onQuickPickClick,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                    if (pair.size == 1) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }

                item(key = "trending_carousel", contentType = "trending_carousel") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = strings.trending,
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                            IconButton(
                                onClick = onRefreshTrending,
                                enabled = !state.isRefreshingTrending,
                                modifier = Modifier.size(32.dp),
                            ) {
                                if (state.isRefreshingTrending) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Rounded.Refresh,
                                        contentDescription = strings.trending,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        }
                        LazyRow(
                            state = trendingRowState,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(
                                items = state.trendingCarousel,
                                key = { "trend_${it.id}" },
                                contentType = { "trending_card" },
                            ) { track ->
                                val isCurrent = state.currentTrackId == track.id
                                val onTrendingClick = remember(track) {
                                    { currentOnPlayTrack(track) }
                                }
                                TrendingSquareCard(
                                    track = track,
                                    isCurrent = isCurrent,
                                    onClick = onTrendingClick,
                                )
                            }
                        }
                    }
                }
            }

            item(key = "tracks_section_header") {
                Text(
                    text = if (state.selectedFilter == "Все") {
                        strings.forYou
                    } else {
                        filterDisplayLabel(state.selectedFilter, strings)
                    },
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }

            if (state.filteredTracks.isEmpty()) {
                item(key = "empty_tracks") {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(22.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = strings.nothingFound,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = strings.nothingFoundHint,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else {
                items(
                    items = state.filteredTracks,
                    key = { it.id },
                    contentType = { "track_row" },
                ) { track ->
                    val isCurrent = state.currentTrackId == track.id
                    val onPlayClick = remember(track) {
                        {
                            haptics.tap()
                            currentOnPlayTrack(track)
                        }
                    }
                    val onLikeClick = remember(track) {
                        {
                            haptics.confirm()
                            currentOnToggleLike(track)
                        }
                    }
                    val onMoreClick = remember(track) {
                        {
                            haptics.tick()
                            currentOnOpenTrackOptions(track)
                        }
                    }
                    TrackRowItem(
                        track = track,
                        isCurrentTrack = isCurrent,
                        isPlaying = isCurrent && state.isPlaying,
                        onPlayClick = onPlayClick,
                        onLikeClick = onLikeClick,
                        onMoreClick = onMoreClick,
                    )
                }
            }

            // Button to upload user's own tracks at the very bottom of Home
            item(key = "bottom_upload_cta") {
                Spacer(modifier = Modifier.height(6.dp))
                Button(
                    onClick = onOpenUploadSheet,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("home_bottom_upload_button"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.UploadFile,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = strings.uploadOwnTracks,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactSearchField(
    query: String,
    placeholder: String,
    closeContentDescription: String,
    isSearching: Boolean,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("home_search_field"),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (isSearching) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            Box(modifier = Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            IconButton(
                onClick = onClose,
                modifier = Modifier.size(24.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = closeContentDescription,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun FilterPill(
    label: String,
    testTag: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(
                if (selected) accent
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
            )
            .border(
                width = 1.dp,
                color = if (selected) accent else Color.Transparent,
                shape = CircleShape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun TrendingSquareCard(
    track: TrackEntity,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(136.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TrackCoverArtwork(
            track = track,
            modifier = Modifier.size(128.dp),
            cornerRadius = 18.dp,
            targetSizePx = 260,
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.titleSmall,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
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
    }
}
