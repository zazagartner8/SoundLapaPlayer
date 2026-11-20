package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
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
import com.example.ui.theme.expressivePressScale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamScreen(
    state: HomeUiState,
    availableFilters: List<String>,
    onSelectFilter: (String) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onCloseSearch: () -> Unit,
    onRefreshHome: () -> Unit,
    onPlayTrack: (TrackEntity) -> Unit,
    onPlayRecommendedTrack: (TrackEntity) -> Unit,
    onToggleLike: (TrackEntity) -> Unit,
    onOpenTrackOptions: (TrackEntity) -> Unit,
    bottomContentPadding: Dp,
    modifier: Modifier = Modifier,
    onPlayQuickPickTrack: (TrackEntity) -> Unit = onPlayTrack,
    onPlayTrendingTrack: (TrackEntity) -> Unit = onPlayTrack,
    onOpenTrendingAll: () -> Unit = {},
    onOpenRecommendedAll: () -> Unit = {},
    isInitialFeedSettled: Boolean = true,
) {
    val strings = LocalAppStrings.current
    val haptics = rememberSoundLapaHaptics()
    val listState = rememberLazyListState()
    val trendingRowState = rememberLazyListState()
    val recommendedRowState = rememberLazyListState()
    val pullToRefreshState = rememberPullToRefreshState()

    val currentOnSelectFilter by rememberUpdatedState(onSelectFilter)
    val currentOnPlayTrack by rememberUpdatedState(onPlayTrack)
    val currentOnPlayQuickPickTrack by rememberUpdatedState(onPlayQuickPickTrack)
    val currentOnPlayTrendingTrack by rememberUpdatedState(onPlayTrendingTrack)
    val currentOnPlayRecommendedTrack by rememberUpdatedState(onPlayRecommendedTrack)
    val currentOnToggleLike by rememberUpdatedState(onToggleLike)
    val currentOnOpenTrackOptions by rememberUpdatedState(onOpenTrackOptions)

    PullToRefreshBox(
        isRefreshing = state.isRefreshingHome,
        onRefresh = {
            haptics.tap()
            onRefreshHome()
        },
        state = pullToRefreshState,
        modifier = modifier
            .fillMaxSize()
            .testTag("home_pull_to_refresh"),
        contentAlignment = Alignment.TopCenter,
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = pullToRefreshState,
                isRefreshing = state.isRefreshingHome,
                color = MaterialTheme.colorScheme.primary,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        },
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
            item(key = "expandable_search", contentType = "search_bar") {
                val slideEase = remember { CubicBezierEasing(0.22f, 1f, 0.36f, 1f) }
                AnimatedVisibility(
                    visible = state.isSearchActive,
                    enter = expandVertically(tween(240, easing = slideEase)) + fadeIn(tween(180)),
                    exit = shrinkVertically(tween(200, easing = slideEase)) + fadeOut(tween(150)),
                ) {
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
                        val onFilterClick = remember(filter) {
                            {
                                haptics.tick()
                                currentOnSelectFilter(filter)
                            }
                        }
                        FilterPill(
                            label = filterDisplayLabel(filter, strings),
                            testTag = "filter_chip_$filter",
                            selected = selected,
                            onClick = onFilterClick,
                        )
                    }
                }
            }

            if (!state.isSearchActive && state.selectedFilter == "Все") {
                if (state.quickPickRows.isNotEmpty()) {
                    item(key = "quick_picks_grid", contentType = "quick_picks") {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                text = strings.quickPicks,
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                state.quickPickRows.forEachIndexed { rowIdx, pair ->
                                    androidx.compose.runtime.key("qp_row_$rowIdx") {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            pair.forEach { track ->
                                                androidx.compose.runtime.key("qp_${track.id}") {
                                                    val isCurrentPlaying =
                                                        state.currentTrackId == track.id && state.isPlaying
                                                    val latestTrack by rememberUpdatedState(track)
                                                    val onQuickPickClick = remember(track.id) {
                                                        {
                                                            haptics.tap()
                                                            currentOnPlayQuickPickTrack(latestTrack)
                                                        }
                                                    }
                                                    QuickPickGridCard(
                                                        track = track,
                                                        isCurrentPlaying = isCurrentPlaying,
                                                        onClick = onQuickPickClick,
                                                        modifier = Modifier.weight(1f),
                                                    )
                                                }
                                            }
                                            if (pair.size == 1) {
                                                Spacer(modifier = Modifier.weight(1f))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (state.trendingCarousel.isNotEmpty()) {
                    item(key = "trending_carousel", contentType = "trending_carousel") {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            SectionNavigationHeader(
                                title = strings.trending,
                                contentDescription = strings.startup.openAllCd,
                                testTag = "home_trending_header",
                                onClick = {
                                    haptics.tick()
                                    onOpenTrendingAll()
                                },
                            )
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
                                    val latestTrack by rememberUpdatedState(track)
                                    val onTrendingClick = remember(track.id) {
                                        {
                                            haptics.tap()
                                            currentOnPlayTrendingTrack(latestTrack)
                                        }
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

                if (state.recommendedTracks.isNotEmpty()) {
                    item(key = "recommendations_carousel", contentType = "recommendations_carousel") {
                        Column(
                            modifier = Modifier.testTag("home_recommendations_section"),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            SectionNavigationHeader(
                                title = strings.recommendedForYou,
                                contentDescription = strings.startup.openAllCd,
                                testTag = "home_recommendations_header",
                                onClick = {
                                    haptics.tick()
                                    onOpenRecommendedAll()
                                },
                            )

                            LazyRow(
                                state = recommendedRowState,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                items(
                                    items = state.recommendedTracks,
                                    key = { "rec_${it.id}" },
                                    contentType = { "recommended_card" },
                                ) { track ->
                                    val isCurrent = state.currentTrackId == track.id
                                    val latestTrack by rememberUpdatedState(track)
                                    val onRecommendedClick = remember(track.id) {
                                        {
                                            haptics.tap()
                                            currentOnPlayRecommendedTrack(latestTrack)
                                        }
                                    }
                                    TrendingSquareCard(
                                        track = track,
                                        isCurrent = isCurrent,
                                        onClick = onRecommendedClick,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item(key = "tracks_section_header", contentType = "section_header") {
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
                val stillLoadingFeed =
                    !isInitialFeedSettled || state.isRefreshingTrending || state.isRefreshingHome || state.isSearching
                item(key = "empty_tracks", contentType = "empty_notice") {
                    if (stillLoadingFeed) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 28.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.primary,
                                strokeWidth = 3.dp,
                                modifier = Modifier.size(28.dp),
                            )
                        }
                    } else {
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
                }
            } else {
                items(
                    items = state.filteredTracks,
                    key = { it.id },
                    contentType = { "track_row" },
                ) { track ->
                    val isCurrent = state.currentTrackId == track.id
                    val latestTrack by rememberUpdatedState(track)
                    val onPlayClick = remember(track.id) {
                        {
                            haptics.tap()
                            currentOnPlayTrack(latestTrack)
                        }
                    }
                    val onLikeClick = remember(track.id) {
                        {
                            haptics.confirm()
                            currentOnToggleLike(latestTrack)
                        }
                    }
                    val onMoreClick = remember(track.id) {
                        {
                            haptics.tick()
                            currentOnOpenTrackOptions(latestTrack)
                        }
                    }
                    TrackRowItem(
                        track = track,
                        isCurrentTrack = isCurrent,
                        isPlaying = isCurrent && state.isPlaying,
                        onPlayClick = onPlayClick,
                        onLikeClick = onLikeClick,
                        onMoreClick = onMoreClick,
                        modifier = Modifier.animateItem(
                            fadeInSpec = tween(180),
                            placementSpec = spring(
                                dampingRatio = 0.86f,
                                stiffness = Spring.StiffnessMediumLow,
                            ),
                        ),
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
    var isFocused by remember { mutableStateOf(false) }
    var textFieldValue by remember {
        mutableStateOf(TextFieldValue(text = query, selection = TextRange(query.length)))
    }
    LaunchedEffect(query, isFocused) {
        if (!isFocused && query != textFieldValue.text) {
            textFieldValue = TextFieldValue(text = query, selection = TextRange(query.length))
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("home_search_field"),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
        contentColor = MaterialTheme.colorScheme.onSurface,
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
                if (textFieldValue.text.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BasicTextField(
                    value = textFieldValue,
                    onValueChange = { next ->
                        val textChanged = next.text != textFieldValue.text
                        textFieldValue = next
                        if (textChanged) {
                            onQueryChange(next.text)
                        }
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { isFocused = it.isFocused },
                )
            }
            IconButton(
                onClick = {
                    textFieldValue = TextFieldValue("")
                    onClose()
                },
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
            .expressivePressScale(pressedScale = 0.93f)
            .clip(CircleShape)
            .background(
                if (selected) {
                    androidx.compose.ui.graphics.Brush.horizontalGradient(
                        listOf(accent, accent.copy(alpha = 0.84f)),
                    )
                } else {
                    androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.70f))
                },
            )
            .border(
                width = 1.dp,
                color = if (selected) Color.White.copy(alpha = 0.34f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f),
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
private fun SectionNavigationHeader(
    title: String,
    contentDescription: String,
    testTag: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize()
            .expressivePressScale(pressedScale = 0.985f)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(26.dp),
        )
    }
}

@Composable
private fun TrendingSquareCard(
    track: TrackEntity,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    val trackAccent = Color(track.accentColorHex)
    Column(
        modifier = Modifier
            .width(156.dp)
            .expressivePressScale(pressedScale = 0.955f)
            .clip(RoundedCornerShape(22.dp))
            .background(
                if (isCurrent) trackAccent.copy(alpha = 0.16f)
                else MaterialTheme.colorScheme.surface.copy(alpha = 0.38f),
            )
            .clickable(onClick = onClick)
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier.size(144.dp),
        ) {
            TrackCoverArtwork(
                track = track,
                modifier = Modifier.fillMaxSize(),
                cornerRadius = 18.dp,
                targetSizePx = 360,
            )
        }
        Column(
            modifier = Modifier.padding(horizontal = 2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
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
