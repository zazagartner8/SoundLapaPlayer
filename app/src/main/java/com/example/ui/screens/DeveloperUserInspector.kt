package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PauseCircleOutline
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.data.local.ListeningStatsAggregator
import com.example.data.local.ListeningStatsPeriod
import com.example.data.remote.DeveloperUserFullDetails
import com.example.data.remote.DeveloperUserOverview
import com.example.data.remote.formatCloudExactSize
import com.example.ui.components.AccountAvatarImage
import com.example.ui.components.rememberSoundLapaHaptics
import com.example.ui.formatDuration
import com.example.ui.i18n.AppStrings
import com.example.ui.i18n.LocalAppStrings
import com.example.ui.theme.LiquidCyan
import com.example.ui.theme.LiquidMint
import com.example.ui.theme.LiquidRose
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class DevUsersFilter {
    ALL,
    ONLINE,
    LISTENING,
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DeveloperUsersTabContent(
    users: List<DeveloperUserOverview>,
    isLoadingUsers: Boolean,
    usersErrorMessage: String?,
    accent: Color,
    onRefreshUsers: () -> Unit,
    onSelectUser: (String) -> Unit,
) {
    val strings = LocalAppStrings.current
    val haptics = rememberSoundLapaHaptics()
    var searchQuery by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(DevUsersFilter.ALL) }

    val onlineCount = remember(users) { users.count { it.isOnline } }
    val listeningCount = remember(users) {
        users.count { it.currentTrackIsPlaying && !it.currentTrackTitle.isNullOrBlank() }
    }

    val filteredUsers = remember(users, searchQuery, filter) {
        val q = searchQuery.trim().lowercase()
        users.filter { user ->
            val matchesFilter = when (filter) {
                DevUsersFilter.ALL -> true
                DevUsersFilter.ONLINE -> user.isOnline
                DevUsersFilter.LISTENING -> user.currentTrackIsPlaying && !user.currentTrackTitle.isNullOrBlank()
            }
            if (!matchesFilter) return@filter false
            if (q.isEmpty()) return@filter true
            user.displayName.lowercase().contains(q) ||
                user.username?.lowercase()?.contains(q) == true ||
                user.email?.lowercase()?.contains(q) == true ||
                user.accountUid?.lowercase()?.contains(q) == true ||
                user.userId.lowercase().contains(q) ||
                user.currentTrackTitle?.lowercase()?.contains(q) == true ||
                user.currentTrackArtist?.lowercase()?.contains(q) == true
        }
    }

    val dateFormatter = remember { SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("developer_users_list"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "users_controls", contentType = "users_controls") {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(24.dp),
                    ),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "${strings.devExtras.tabUsers} (${users.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        OutlinedButton(
                            onClick = {
                                haptics.tick()
                                onRefreshUsers()
                            },
                            enabled = !isLoadingUsers,
                            shape = CircleShape,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.testTag("developer_refresh_users_button"),
                        ) {
                            if (isLoadingUsers) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = accent,
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = strings.settingsDeveloperRefreshButton,
                                    modifier = Modifier.size(15.dp),
                                )
                            }
                            Spacer(Modifier.width(5.dp))
                            Text(strings.settingsDeveloperRefreshButton)
                        }
                    }

                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = {
                            Text(
                                text = strings.devExtras.usersSearchPlaceholder,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.Search,
                                contentDescription = null,
                            )
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = strings.close,
                                    )
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("developer_users_search_input"),
                    )

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        DevFilterChip(
                            selected = filter == DevUsersFilter.ALL,
                            label = "${strings.devExtras.usersFilterAll} (${users.size})",
                            accent = accent,
                            onClick = { filter = DevUsersFilter.ALL },
                        )
                        DevFilterChip(
                            selected = filter == DevUsersFilter.ONLINE,
                            label = "${strings.devExtras.usersFilterOnline} ($onlineCount)",
                            accent = LiquidMint,
                            onClick = { filter = DevUsersFilter.ONLINE },
                        )
                        DevFilterChip(
                            selected = filter == DevUsersFilter.LISTENING,
                            label = "${strings.devExtras.usersFilterListening} ($listeningCount)",
                            accent = LiquidCyan,
                            onClick = { filter = DevUsersFilter.LISTENING },
                        )
                    }

                    usersErrorMessage?.let { err ->
                        Text(
                            text = err,
                            style = MaterialTheme.typography.bodySmall,
                            color = LiquidRose,
                        )
                    }
                }
            }
        }

        if (filteredUsers.isEmpty() && !isLoadingUsers) {
            item(key = "users_empty", contentType = "users_empty") {
                Text(
                    text = strings.devExtras.usersEmptyLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                )
            }
        }

        items(
            items = filteredUsers,
            key = { it.userId },
            contentType = { "developer_user_row" },
        ) { user ->
            DeveloperUserOverviewCard(
                user = user,
                accent = accent,
                strings = strings,
                dateFormatter = dateFormatter,
                onClick = {
                    haptics.tick()
                    onSelectUser(user.userId)
                },
            )
        }

        item(key = "users_bottom_spacer", contentType = "spacer") {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun DevFilterChip(
    selected: Boolean,
    label: String,
    accent: Color,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text = label, style = MaterialTheme.typography.labelMedium) },
        shape = CircleShape,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = accent.copy(alpha = 0.22f),
            selectedLabelColor = accent,
        ),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeveloperUserOverviewCard(
    user: DeveloperUserOverview,
    accent: Color,
    strings: AppStrings,
    dateFormatter: SimpleDateFormat,
    onClick: () -> Unit,
) {
    val totalMinutes = remember(user.listeningEvents) {
        (user.listeningEvents.sumOf { it.listenedMs } / 60_000L).toInt()
    }
    val sizeLabel = remember(user.storageBytes, strings) {
        formatCloudExactSize(
            bytes = user.storageBytes,
            gbUnit = strings.cloudStorageGbUnit,
            mbUnit = strings.cloudStorageMbUnit,
            kbUnit = strings.cloudStorageKbUnit,
            bUnit = strings.cloudStorageBUnit,
        )
    }

    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = if (user.isOnline) LiquidMint.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(22.dp),
            )
            .clip(RoundedCornerShape(22.dp))
            .clickable(onClick = onClick)
            .testTag("developer_user_card_${user.userId}"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box {
                    AccountAvatarImage(
                        avatarUrlOrData = user.photoUrl,
                        contentDescription = user.displayName,
                        isGuest = false,
                        size = 46.dp,
                    )
                    Canvas(
                        modifier = Modifier
                            .size(13.dp)
                            .align(Alignment.BottomEnd),
                    ) {
                        drawCircle(color = if (user.isOnline) LiquidMint else Color.Gray)
                    }
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = user.displayName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (!user.username.isNullOrBlank()) {
                            Text(
                                text = "@${user.username}",
                                style = MaterialTheme.typography.labelMedium,
                                color = accent,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (user.isBanned) {
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(LiquidRose.copy(alpha = 0.16f))
                                    .border(1.dp, LiquidRose.copy(alpha = 0.55f), CircleShape)
                                    .padding(horizontal = 7.dp, vertical = 2.dp),
                            ) {
                                Text(
                                    text = strings.devExtras.bannedBadgeLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = LiquidRose,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                    if (!user.email.isNullOrBlank()) {
                        Text(
                            text = user.email,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    val lastActiveText = if (user.lastActiveAtMs > 0L) {
                        dateFormatter.format(Date(user.lastActiveAtMs))
                    } else {
                        "—"
                    }
                    Text(
                        text = buildString {
                            append(
                                if (user.isOnline) strings.devExtras.statusOnlineLabel else strings.devExtras.statusOfflineLabel,
                            )
                            append(" • ")
                            append(strings.devExtras.lastActiveLabel(lastActiveText))
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (user.isOnline) LiquidMint else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (!user.currentTrackTitle.isNullOrBlank()) {
                UserNowPlayingBanner(
                    title = user.currentTrackTitle,
                    artist = user.currentTrackArtist.orEmpty(),
                    coverUrl = user.currentTrackCoverUrl,
                    isPlaying = user.currentTrackIsPlaying,
                    accent = accent,
                    strings = strings,
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (!user.appVersion.isNullOrBlank()) {
                    UserStatBadge(label = "v${user.appVersion}")
                }
                UserStatBadge(label = strings.settingsDeveloperStorageTracksLabel(user.tracksCount))
                UserStatBadge(label = strings.settingsDeveloperStoragePlaylistsLabel(user.playlistsCount))
                UserStatBadge(label = strings.stats.minutesUnitShort(totalMinutes))
                UserStatBadge(label = sizeLabel)
            }
        }
    }
}

@Composable
private fun UserNowPlayingBanner(
    title: String,
    artist: String,
    coverUrl: String?,
    isPlaying: Boolean,
    accent: Color,
    strings: AppStrings,
) {
    val statusColor = if (isPlaying) LiquidCyan else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.6f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RemoteSquareThumb(
            url = coverUrl,
            contentDescription = title,
            size = 36.dp,
            cornerRadius = 10.dp,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (isPlaying) strings.devExtras.nowListeningLabel else strings.devExtras.lastTrackPausedLabel,
                style = MaterialTheme.typography.labelSmall,
                color = statusColor,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (artist.isNotBlank()) "$artist — $title" else title,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = if (isPlaying) Icons.Rounded.GraphicEq else Icons.Rounded.PauseCircleOutline,
            contentDescription = null,
            tint = if (isPlaying) accent else statusColor,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun UserStatBadge(label: String) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.55f))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DeveloperUserDetailOverlay(
    details: DeveloperUserFullDetails?,
    isLoading: Boolean,
    currentDeveloperUid: String?,
    isActionInProgress: Boolean,
    actionStatusMessage: String?,
    accent: Color,
    onBanUser: (targetUid: String, reason: String?) -> Unit,
    onUnbanUser: (targetUid: String) -> Unit,
    onDeleteUserFromServer: (targetUid: String) -> Unit,
    onClearActionStatus: () -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)

    val strings = LocalAppStrings.current
    val haptics = rememberSoundLapaHaptics()
    var detailTab by remember { mutableIntStateOf(0) }
    var statsPeriod by remember { mutableStateOf(ListeningStatsPeriod.WEEK) }
    var expandedPlaylistIds by remember { mutableStateOf(emptySet<String>()) }
    var showBanDialog by remember { mutableStateOf(false) }
    var banReasonInput by remember { mutableStateOf("") }
    var showUnbanDialog by remember { mutableStateOf(false) }
    var showDeleteUserDialog by remember { mutableStateOf(false) }
    val dateFormatter = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .testTag("developer_user_detail_overlay"),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                IconButton(
                    onClick = {
                        haptics.tick()
                        onClose()
                    },
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f))
                        .testTag("developer_user_detail_back_button"),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = strings.close,
                    )
                }
                Text(
                    text = details?.overview?.displayName ?: strings.devExtras.tabUsers,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }

            if (isLoading && details == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = accent)
                }
                return@Column
            }

            if (details == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(strings.devExtras.usersEmptyLabel)
                }
                return@Column
            }

            val overview = details.overview

            // Sub-tab selector
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val tabs = listOf(
                    0 to strings.devExtras.userDetailOverviewTab,
                    1 to "${strings.devExtras.userDetailTracksTab} (${details.tracks.size})",
                    2 to "${strings.devExtras.userDetailPlaylistsTab} (${details.playlists.size})",
                    3 to "${strings.devExtras.userDetailSocialTab} (${details.friends.size + details.subscriptions.size})",
                )
                tabs.forEach { (idx, label) ->
                    DevFilterChip(
                        selected = detailTab == idx,
                        label = label,
                        accent = accent,
                        onClick = {
                            haptics.tick()
                            detailTab = idx
                        },
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Header card always visible
                item(key = "detail_header", contentType = "detail_header") {
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(24.dp)),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                AccountAvatarImage(
                                    avatarUrlOrData = overview.photoUrl,
                                    contentDescription = overview.displayName,
                                    isGuest = false,
                                    size = 54.dp,
                                )
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        text = overview.displayName,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    overview.username?.let { h ->
                                        Text(
                                            text = "@$h",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = accent,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                    }
                                    val lastText = if (overview.lastActiveAtMs > 0L) {
                                        dateFormatter.format(Date(overview.lastActiveAtMs))
                                    } else {
                                        "—"
                                    }
                                    Text(
                                        text = "${if (overview.isOnline) strings.devExtras.statusOnlineLabel else strings.devExtras.statusOfflineLabel} • ${strings.devExtras.lastActiveLabel(lastText)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (overview.isOnline) LiquidMint else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }

                            if (!overview.currentTrackTitle.isNullOrBlank()) {
                                UserNowPlayingBanner(
                                    title = overview.currentTrackTitle,
                                    artist = overview.currentTrackArtist.orEmpty(),
                                    coverUrl = overview.currentTrackCoverUrl,
                                    isPlaying = overview.currentTrackIsPlaying,
                                    accent = accent,
                                    strings = strings,
                                )
                            }

                            if (overview.isBanned) {
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = LiquidRose.copy(alpha = 0.14f),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .border(1.dp, LiquidRose.copy(alpha = 0.50f), RoundedCornerShape(14.dp)),
                                ) {
                                    Column(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                                        verticalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        Text(
                                            text = strings.devExtras.bannedBadgeLabel,
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = LiquidRose,
                                        )
                                        overview.banReason?.takeIf { it.isNotBlank() }?.let { reason ->
                                            Text(
                                                text = "${strings.v34.bannedReasonPrefix}: $reason",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurface,
                                            )
                                        }
                                        if (overview.bannedAtMs > 0L) {
                                            Text(
                                                text = "${strings.v34.bannedDatePrefix}: ${dateFormatter.format(Date(overview.bannedAtMs))}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }

                            val isSelf = !currentDeveloperUid.isNullOrBlank() && overview.userId == currentDeveloperUid
                            if (isSelf) {
                                Text(
                                    text = strings.devExtras.cannotModifySelfHint,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    if (overview.isBanned) {
                                        Button(
                                            onClick = {
                                                haptics.tick()
                                                onClearActionStatus()
                                                showUnbanDialog = true
                                            },
                                            enabled = !isActionInProgress,
                                            shape = CircleShape,
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = LiquidMint,
                                                contentColor = Color(0xFF07130E),
                                            ),
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("developer_unban_user_button"),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.CheckCircle,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp),
                                            )
                                            Spacer(Modifier.width(5.dp))
                                            Text(
                                                text = strings.devExtras.unbanUserButton,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                style = MaterialTheme.typography.labelMedium,
                                            )
                                        }
                                    } else {
                                        OutlinedButton(
                                            onClick = {
                                                haptics.tick()
                                                onClearActionStatus()
                                                banReasonInput = ""
                                                showBanDialog = true
                                            },
                                            enabled = !isActionInProgress,
                                            shape = CircleShape,
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("developer_ban_user_button"),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Block,
                                                contentDescription = null,
                                                tint = LiquidRose,
                                                modifier = Modifier.size(16.dp),
                                            )
                                            Spacer(Modifier.width(5.dp))
                                            Text(
                                                text = strings.devExtras.banUserButton,
                                                color = LiquidRose,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                style = MaterialTheme.typography.labelMedium,
                                            )
                                        }
                                    }

                                    Button(
                                        onClick = {
                                            haptics.tick()
                                            onClearActionStatus()
                                            showDeleteUserDialog = true
                                        },
                                        enabled = !isActionInProgress,
                                        shape = CircleShape,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = LiquidRose,
                                            contentColor = Color.White,
                                        ),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                        modifier = Modifier
                                            .weight(1.25f)
                                            .testTag("developer_delete_user_button"),
                                    ) {
                                        if (isActionInProgress) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(16.dp),
                                                strokeWidth = 2.dp,
                                                color = Color.White,
                                            )
                                        } else {
                                            Icon(
                                                imageVector = Icons.Rounded.DeleteForever,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp),
                                            )
                                            Spacer(Modifier.width(5.dp))
                                            Text(
                                                text = strings.devExtras.deleteUserFromServerButton,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                style = MaterialTheme.typography.labelMedium,
                                            )
                                        }
                                    }
                                }
                            }

                            actionStatusMessage?.takeIf { it.isNotBlank() }?.let { status ->
                                Text(
                                    text = status,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = accent,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.testTag("developer_user_action_status"),
                                )
                            }
                        }
                    }
                }

                when (detailTab) {
                    0 -> {
                        // Profile fields
                        item(key = "detail_profile_meta", contentType = "detail_profile") {
                            Surface(
                                shape = RoundedCornerShape(22.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(22.dp)),
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    KeyValueRow(
                                        label = strings.devExtras.userDetailEmailLabel,
                                        value = overview.email ?: "—",
                                    )
                                    KeyValueRow(
                                        label = strings.devExtras.userDetailUidLabel,
                                        value = "${overview.accountUid ?: "—"} (${overview.userId})",
                                    )
                                    KeyValueRow(
                                        label = strings.appVersionLabel,
                                        value = overview.appVersion?.let { "v$it" } ?: "—",
                                    )
                                    if (!overview.bio.isNullOrBlank()) {
                                        KeyValueRow(
                                            label = strings.devExtras.userDetailBioLabel,
                                            value = overview.bio,
                                        )
                                    }
                                    KeyValueRow(
                                        label = strings.settingsDeveloperStorageTitle,
                                        value = formatCloudExactSize(
                                            bytes = overview.storageBytes,
                                            gbUnit = strings.cloudStorageGbUnit,
                                            mbUnit = strings.cloudStorageMbUnit,
                                            kbUnit = strings.cloudStorageKbUnit,
                                            bUnit = strings.cloudStorageBUnit,
                                        ),
                                    )
                                }
                            }
                        }

                        // Listening stats 7d/30d/365d
                        item(key = "detail_listening_stats", contentType = "detail_stats") {
                            val snapshot = remember(overview.listeningEvents, statsPeriod) {
                                ListeningStatsAggregator.compute(
                                    events = overview.listeningEvents,
                                    period = statsPeriod,
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(22.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(22.dp)),
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Text(
                                        text = strings.stats.sectionTitle,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        listOf(
                                            ListeningStatsPeriod.WEEK to strings.stats.periodWeek,
                                            ListeningStatsPeriod.MONTH to strings.stats.periodMonth,
                                            ListeningStatsPeriod.YEAR to strings.stats.periodYear,
                                        ).forEach { (period, title) ->
                                            DevFilterChip(
                                                selected = statsPeriod == period,
                                                label = title,
                                                accent = accent,
                                                onClick = { statsPeriod = period },
                                            )
                                        }
                                    }
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        UserStatBadge(label = strings.stats.minutesUnitShort(snapshot.totalMinutes))
                                        UserStatBadge(label = strings.stats.playsCountShort(snapshot.totalPlays))
                                    }
                                    snapshot.favoriteArtist?.let { art ->
                                        KeyValueRow(
                                            label = strings.stats.favoriteArtistTitle,
                                            value = "${art.artistName} (${strings.stats.minutesUnitShort(art.listenedMinutes)})",
                                        )
                                    }
                                    snapshot.favoriteTrack?.let { tr ->
                                        KeyValueRow(
                                            label = strings.stats.favoriteTrackTitle,
                                            value = "${tr.artist} — ${tr.title} (${strings.stats.playsCountShort(tr.playCount)})",
                                        )
                                    }
                                    if (snapshot.topTracks.isNotEmpty()) {
                                        Text(
                                            text = strings.stats.topTracksTitle,
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        snapshot.topTracks.take(5).forEachIndexed { idx, top ->
                                            Text(
                                                text = "${idx + 1}. ${top.artist} — ${top.title} • ${strings.stats.minutesUnitShort(top.listenedMinutes)}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                    if (snapshot.topArtists.isNotEmpty()) {
                                        Text(
                                            text = strings.stats.topArtistsTitle,
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        snapshot.topArtists.take(5).forEachIndexed { idx, art ->
                                            Text(
                                                text = "${idx + 1}. ${art.artistName} • ${strings.stats.minutesUnitShort(art.listenedMinutes)}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                    if (snapshot.totalPlays == 0) {
                                        Text(
                                            text = strings.stats.emptyStatsHint,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }

                        // Search history
                        if (overview.searchHistory.isNotEmpty()) {
                            item(key = "detail_search_history", contentType = "detail_history") {
                                Surface(
                                    shape = RoundedCornerShape(22.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(22.dp)),
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(14.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Text(
                                            text = strings.devExtras.userDetailSearchHistoryTitle,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                        )
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            overview.searchHistory.forEach { query ->
                                                UserStatBadge(label = query)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // User settings
                        overview.settings?.let { s ->
                            item(key = "detail_settings", contentType = "detail_settings") {
                                Surface(
                                    shape = RoundedCornerShape(22.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(22.dp)),
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(14.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Text(
                                            text = strings.devExtras.userDetailSettingsTitle,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                        )
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            UserStatBadge(label = "Lang: ${s.appLanguage.uppercase()}")
                                            UserStatBadge(label = if (s.darkLiquidGlass) "Dark Glass" else "Light Glass")
                                            UserStatBadge(label = "AudioFocus: ${s.audioFocusMode}")
                                            if (s.dataSaverEnabled) UserStatBadge(label = "Data Saver")
                                            if (s.batterySaverEnabled) UserStatBadge(label = "Battery Saver")
                                            if (s.smoothTransitionEnabled) {
                                                UserStatBadge(label = "Crossfade: ${s.smoothTransitionDurationSec}s")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    1 -> {
                        if (details.tracks.isEmpty()) {
                            item(key = "empty_tracks", contentType = "empty") {
                                Text(
                                    text = strings.devExtras.userDetailEmptyTracks,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            items(
                                items = details.tracks,
                                key = { "tr_${it.docId.ifBlank { it.trackId }}" },
                                contentType = { "dev_user_track" },
                            ) { track ->
                                Surface(
                                    shape = RoundedCornerShape(18.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        RemoteSquareThumb(
                                            url = track.coverArtPath,
                                            contentDescription = track.title,
                                            size = 44.dp,
                                            cornerRadius = 12.dp,
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = track.title,
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                text = buildString {
                                                    append(track.artist)
                                                    if (!track.albumTitle.isNullOrBlank()) {
                                                        append(" • ")
                                                        append(track.albumTitle)
                                                    }
                                                },
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                text = "${formatDuration(track.durationMs)} • ▶ ${track.playCount} • ♥ ${track.likeCount}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        if (track.isLiked) {
                                            Icon(
                                                imageVector = Icons.Rounded.Favorite,
                                                contentDescription = null,
                                                tint = LiquidRose,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    2 -> {
                        if (details.playlists.isEmpty()) {
                            item(key = "empty_playlists", contentType = "empty") {
                                Text(
                                    text = strings.devExtras.userDetailEmptyPlaylists,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            items(
                                items = details.playlists,
                                key = { "pl_${it.playlist.playlistId}" },
                                contentType = { "dev_user_playlist" },
                            ) { plDetail ->
                                val pl = plDetail.playlist
                                val isExpanded = pl.playlistId in expandedPlaylistIds
                                Surface(
                                    shape = RoundedCornerShape(20.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(20.dp))
                                        .clickable {
                                            expandedPlaylistIds = if (isExpanded) {
                                                expandedPlaylistIds - pl.playlistId
                                            } else {
                                                expandedPlaylistIds + pl.playlistId
                                            }
                                        },
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        ) {
                                            if (pl.coverArtPath.isNullOrBlank()) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(46.dp)
                                                        .clip(RoundedCornerShape(12.dp))
                                                        .background(accent.copy(alpha = 0.16f)),
                                                    contentAlignment = Alignment.Center,
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Rounded.PlaylistPlay,
                                                        contentDescription = pl.name,
                                                        tint = accent,
                                                    )
                                                }
                                            } else {
                                                RemoteSquareThumb(
                                                    url = pl.coverArtPath,
                                                    contentDescription = pl.name,
                                                    size = 46.dp,
                                                    cornerRadius = 12.dp,
                                                )
                                            }
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = pl.name,
                                                    style = MaterialTheme.typography.titleSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                Text(
                                                    text = strings.devExtras.userDetailPlaylistTracksCount(plDetail.tracks.size),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                            Icon(
                                                imageVector = if (isExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                                contentDescription = null,
                                            )
                                        }
                                        AnimatedVisibility(visible = isExpanded && plDetail.tracks.isNotEmpty()) {
                                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                                plDetail.tracks.forEachIndexed { i, tr ->
                                                    Text(
                                                        text = "${i + 1}. ${if (tr.artist.isNotBlank()) "${tr.artist} — " else ""}${tr.title}",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurface,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    3 -> {
                        item(key = "friends_header", contentType = "section_header") {
                            Text(
                                text = "${strings.social.friendsTitle} (${details.friends.size})",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        if (details.friends.isEmpty()) {
                            item(key = "empty_friends", contentType = "empty") {
                                Text(
                                    text = strings.devExtras.userDetailEmptyFriends,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            items(
                                items = details.friends,
                                key = { "fr_${it.friendUid}" },
                                contentType = { "dev_user_friend" },
                            ) { friend ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                                        .padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    AccountAvatarImage(
                                        avatarUrlOrData = friend.photoUrl,
                                        contentDescription = friend.displayName,
                                        isGuest = false,
                                        size = 38.dp,
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            Text(
                                                text = friend.displayName,
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                            if (friend.isDeveloper) {
                                                Icon(
                                                    imageVector = Icons.Rounded.Verified,
                                                    contentDescription = null,
                                                    tint = accent,
                                                    modifier = Modifier.size(15.dp),
                                                )
                                            }
                                        }
                                        friend.username?.let { h ->
                                            Text(
                                                text = "@$h",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = accent,
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        item(key = "subs_header", contentType = "section_header") {
                            Text(
                                text = "${strings.social.subscribedArtistsHeader} (${details.subscriptions.size})",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                        if (details.subscriptions.isEmpty()) {
                            item(key = "empty_subs", contentType = "empty") {
                                Text(
                                    text = strings.devExtras.userDetailEmptySubscriptions,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            items(
                                items = details.subscriptions,
                                key = { "sub_${it.artistKey}" },
                                contentType = { "dev_user_sub" },
                            ) { sub ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                                        .padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    RemoteSquareThumb(
                                        url = sub.avatarUrl,
                                        contentDescription = sub.artistName,
                                        size = 38.dp,
                                        cornerRadius = 19.dp,
                                    )
                                    Text(
                                        text = sub.artistName,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                        }
                    }
                }

                item(key = "detail_bottom_spacer", contentType = "spacer") {
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }

    details?.overview?.let { overview ->
        if (showBanDialog) {
            AlertDialog(
                onDismissRequest = { showBanDialog = false },
                containerColor = MaterialTheme.colorScheme.surface,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
                textContentColor = MaterialTheme.colorScheme.onSurface,
                title = { Text(strings.devExtras.banDialogTitle) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = overview.displayName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        OutlinedTextField(
                            value = banReasonInput,
                            onValueChange = { banReasonInput = it.take(300) },
                            label = { Text(strings.devExtras.banReasonLabel) },
                            placeholder = { Text(strings.devExtras.banReasonPlaceholder) },
                            maxLines = 3,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("developer_ban_reason_input"),
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            haptics.confirm()
                            showBanDialog = false
                            onBanUser(overview.userId, banReasonInput.trim().ifBlank { null })
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = LiquidRose,
                            contentColor = Color.White,
                        ),
                        modifier = Modifier.testTag("developer_confirm_ban_button"),
                    ) {
                        Text(strings.devExtras.banConfirmButton)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showBanDialog = false }) {
                        Text(strings.cancel)
                    }
                },
            )
        }

        if (showUnbanDialog) {
            AlertDialog(
                onDismissRequest = { showUnbanDialog = false },
                containerColor = MaterialTheme.colorScheme.surface,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
                textContentColor = MaterialTheme.colorScheme.onSurface,
                title = { Text(strings.devExtras.unbanConfirmTitle) },
                text = { Text(strings.devExtras.unbanConfirmMessage(overview.displayName)) },
                confirmButton = {
                    Button(
                        onClick = {
                            haptics.confirm()
                            showUnbanDialog = false
                            onUnbanUser(overview.userId)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = LiquidMint,
                            contentColor = Color(0xFF07130E),
                        ),
                        modifier = Modifier.testTag("developer_confirm_unban_button"),
                    ) {
                        Text(strings.devExtras.unbanUserButton)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showUnbanDialog = false }) {
                        Text(strings.cancel)
                    }
                },
            )
        }

        if (showDeleteUserDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteUserDialog = false },
                containerColor = MaterialTheme.colorScheme.surface,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
                textContentColor = MaterialTheme.colorScheme.onSurface,
                title = { Text(strings.devExtras.deleteUserConfirmTitle) },
                text = { Text(strings.devExtras.deleteUserConfirmMessage(overview.displayName)) },
                confirmButton = {
                    Button(
                        onClick = {
                            haptics.confirm()
                            showDeleteUserDialog = false
                            onDeleteUserFromServer(overview.userId)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = LiquidRose,
                            contentColor = Color.White,
                        ),
                        modifier = Modifier.testTag("developer_confirm_delete_user_button"),
                    ) {
                        Text(strings.deleteConfirmAction)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteUserDialog = false }) {
                        Text(strings.cancel)
                    }
                },
            )
        }
    }
}

@Composable
private fun KeyValueRow(
    label: String,
    value: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun RemoteSquareThumb(
    url: String?,
    contentDescription: String,
    size: Dp,
    cornerRadius: Dp,
) {
    val context = LocalContext.current
    val shape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
    val model = remember(url) {
        url?.takeIf { it.isNotBlank() }?.let {
            ImageRequest.Builder(context)
                .data(it)
                .size(128)
                .crossfade(0)
                .build()
        }
    }
    if (model != null) {
        AsyncImage(
            model = model,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(size)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (cornerRadius >= size / 2) Icons.Rounded.Person else Icons.Rounded.MusicNote,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(size * 0.5f),
            )
        }
    }
}
