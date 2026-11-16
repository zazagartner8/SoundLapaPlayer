package com.example.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.example.data.local.TrackEntity
import com.example.data.remote.SearchArtistProfile
import com.example.ui.MainUiState
import com.example.ui.components.TrackRowItem
import com.example.ui.components.rememberSoundLapaHaptics
import com.example.ui.i18n.LocalAppStrings

@Composable
fun SearchScreen(
    state: MainUiState,
    onQueryChange: (String) -> Unit,
    onSubmitSearch: (String) -> Unit,
    onClearQuery: () -> Unit,
    onRemoveHistoryItem: (String) -> Unit,
    onClearHistory: () -> Unit,
    onPlayTrack: (TrackEntity, List<TrackEntity>) -> Unit,
    onToggleLike: (TrackEntity) -> Unit,
    onOpenTrackOptions: (TrackEntity) -> Unit,
    onOpenArtistProfile: (SearchArtistProfile) -> Unit = {},
    bottomContentPadding: Dp,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current
    val haptics = rememberSoundLapaHaptics()
    val focusManager = LocalFocusManager.current
    var isFocused by remember { mutableStateOf(false) }
    var textFieldValue by remember {
        mutableStateOf(
            TextFieldValue(
                text = state.searchQuery,
                selection = TextRange(state.searchQuery.length),
            ),
        )
    }

    LaunchedEffect(state.searchQuery, isFocused) {
        if (!isFocused && state.searchQuery != textFieldValue.text) {
            textFieldValue = TextFieldValue(
                text = state.searchQuery,
                selection = TextRange(state.searchQuery.length),
            )
        }
    }

    val trimmedQuery = textFieldValue.text.trim()

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 720.dp)
                .padding(horizontal = 16.dp),
        ) {
            Spacer(modifier = Modifier.height(6.dp))

            // Top Search Bar
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("search_tab_input_card"),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (state.audius.isSearching) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp),
                        )
                    }

                    Box(modifier = Modifier.weight(1f)) {
                        if (textFieldValue.text.isEmpty()) {
                            Text(
                                text = strings.searchPlaceholder,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
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
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(
                                onSearch = {
                                    onSubmitSearch(textFieldValue.text)
                                    focusManager.clearFocus()
                                },
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { isFocused = it.isFocused }
                                .testTag("search_tab_text_field"),
                        )
                    }

                    if (textFieldValue.text.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                haptics.tick()
                                textFieldValue = TextFieldValue("")
                                onClearQuery()
                            },
                            modifier = Modifier
                                .size(28.dp)
                                .testTag("search_tab_clear_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = strings.close,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            if (trimmedQuery.isEmpty()) {
                // Search History below the Search Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = strings.searchHistoryTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    if (state.settings.searchHistory.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                haptics.tick()
                                onClearHistory()
                            },
                            modifier = Modifier.testTag("clear_search_history_button"),
                        ) {
                            Text(
                                text = strings.clearSearchHistory,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                if (state.settings.searchHistory.isEmpty()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.History,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(32.dp),
                            )
                            Text(
                                text = strings.emptySearchHistoryHint,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = bottomContentPadding),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(
                            items = state.settings.searchHistory,
                            key = { "hist_$it" },
                            contentType = { "history_item" },
                        ) { historyQuery ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                                    .clickable {
                                        haptics.tick()
                                        textFieldValue = TextFieldValue(
                                            text = historyQuery,
                                            selection = TextRange(historyQuery.length),
                                        )
                                        focusManager.clearFocus()
                                        onSubmitSearch(historyQuery)
                                    }
                                    .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
                                    .testTag("search_history_item_$historyQuery"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.History,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                                Text(
                                    text = historyQuery,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(
                                    onClick = {
                                        haptics.tick()
                                        onRemoveHistoryItem(historyQuery)
                                    },
                                    modifier = Modifier.minimumInteractiveComponentSize(),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = strings.close,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                // Fast Search Results
                Text(
                    text = strings.searchResultsHeader,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )

                Spacer(modifier = Modifier.height(8.dp))

                if (state.searchScreenResults.isEmpty() && state.searchArtistProfiles.isEmpty() && !state.audius.isSearching) {
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
                } else {
                    val currentOnPlayTrack by rememberUpdatedState(onPlayTrack)
                    val currentOnToggleLike by rememberUpdatedState(onToggleLike)
                    val currentOnOpenTrackOptions by rememberUpdatedState(onOpenTrackOptions)
                    val currentOnOpenArtistProfile by rememberUpdatedState(onOpenArtistProfile)
                    val resultsListState = rememberLazyListState()
                    val firstArtistId = state.searchArtistProfiles.firstOrNull()?.id
                    LaunchedEffect(trimmedQuery, firstArtistId) {
                        if (trimmedQuery.isNotBlank() && firstArtistId != null) {
                            resultsListState.scrollToItem(0)
                        }
                    }
                    LazyColumn(
                        state = resultsListState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = bottomContentPadding),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(
                            items = state.searchArtistProfiles,
                            key = { "artist_${it.id}" },
                            contentType = { "artist_profile" },
                        ) { profile ->
                            val onArtistClick = remember(profile) {
                                {
                                    haptics.tick()
                                    focusManager.clearFocus()
                                    currentOnOpenArtistProfile(profile)
                                }
                            }
                            ArtistSearchResultCard(
                                profile = profile,
                                subtitle = "${strings.artistSearchProfileSubtitle} • ${profile.sourceLabel}",
                                onClick = onArtistClick,
                            )
                        }

                        items(
                            items = state.searchScreenResults,
                            key = { "search_${it.id}" },
                            contentType = { "track_row" },
                        ) { track ->
                            val isCurrent = state.playback.currentTrackId == track.id
                            val onPlay = remember(track, state.searchScreenResults) {
                                {
                                    haptics.tap()
                                    focusManager.clearFocus()
                                    currentOnPlayTrack(track, state.searchScreenResults)
                                }
                            }
                            val onLike = remember(track) {
                                {
                                    haptics.confirm()
                                    currentOnToggleLike(track)
                                }
                            }
                            val onMore = remember(track) {
                                {
                                    haptics.tick()
                                    currentOnOpenTrackOptions(track)
                                }
                            }
                            TrackRowItem(
                                track = track,
                                isCurrentTrack = isCurrent,
                                isPlaying = isCurrent && state.playback.isPlaying,
                                onPlayClick = onPlay,
                                onLikeClick = onLike,
                                onMoreClick = onMore,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ArtistSearchResultCard(
    profile: SearchArtistProfile,
    subtitle: String,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val primary = MaterialTheme.colorScheme.primary
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .clickable(onClick = onClick)
            .testTag("search_artist_card_${profile.id}"),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.68f),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(primary.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                if (!profile.avatarUrl.isNullOrBlank()) {
                    SubcomposeAsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(profile.avatarUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = profile.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape),
                        error = {
                            Icon(
                                imageVector = Icons.Rounded.Person,
                                contentDescription = null,
                                tint = primary,
                                modifier = Modifier.size(26.dp),
                            )
                        },
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Person,
                        contentDescription = null,
                        tint = primary,
                        modifier = Modifier.size(26.dp),
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = profile.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
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

            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = profile.name,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
