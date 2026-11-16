package com.example.ui.components

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.ExplicitContentDetector
import com.example.data.local.ListeningHistoryEntry
import com.example.data.local.ListeningPlayEvent
import com.example.data.local.ListeningStatsAggregator
import com.example.data.local.ListeningStatsPeriod
import com.example.data.local.LocalExplicitTrackIds
import com.example.data.local.TopArtistStat
import com.example.data.local.TopTrackStat
import com.example.data.local.TrackEntity
import com.example.ui.StatsBackupUiState
import com.example.ui.i18n.LocalAppStrings
import com.example.ui.shaders.LiquidGlassCard
import com.example.ui.shaders.TrackCoverArtwork
import com.example.ui.theme.LiquidMint
import com.example.ui.theme.LiquidRose
import com.example.ui.theme.SoundLapaAmber

/**
 * Compact Spotify-style explicit [E] badge rendered to the left of explicit track titles.
 */
@Composable
fun ExplicitTrackBadge(
    modifier: Modifier = Modifier,
    onDarkSurface: Boolean = false,
) {
    val bgColor = if (onDarkSurface) {
        Color.White.copy(alpha = 0.22f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.20f)
    }
    val fgColor = if (onDarkSurface) {
        Color.White.copy(alpha = 0.92f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = modifier
            .size(16.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(bgColor)
            .testTag("explicit_badge"),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "E",
            fontSize = 10.sp,
            lineHeight = 10.sp,
            fontWeight = FontWeight.ExtraBold,
            color = fgColor,
        )
    }
}

/**
 * Displays a deterministic readable UID (SL-XXXX-XXXX) with a 48dp copy-to-clipboard button.
 */
@Composable
fun AccountUidCopyRow(
    accountUid: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val haptics = rememberSoundLapaHaptics()

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, accent.copy(alpha = 0.28f), RoundedCornerShape(16.dp))
            .testTag("account_uid_row"),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.52f),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = strings.stats.accountUidLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = accountUid,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("account_uid_value"),
                )
            }
            IconButton(
                onClick = {
                    haptics.confirm()
                    clipboardManager.setText(AnnotatedString(accountUid))
                    Toast.makeText(
                        context,
                        strings.stats.accountUidCopiedNotice,
                        Toast.LENGTH_SHORT,
                    ).show()
                },
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("copy_account_uid_button"),
            ) {
                Icon(
                    imageVector = Icons.Rounded.ContentCopy,
                    contentDescription = strings.stats.accountUidLabel,
                    tint = accent,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/**
 * Prominent 18+ / NSFW avatar policy warning shown before users upload or confirm an avatar.
 */
@Composable
fun Avatar18PlusWarningBanner(
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SoundLapaAmber.copy(alpha = 0.14f))
            .border(1.dp, SoundLapaAmber.copy(alpha = 0.42f), RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("avatar_18plus_ban_warning"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.WarningAmber,
            contentDescription = strings.stats.avatarModerationBanTitle,
            tint = SoundLapaAmber,
            modifier = Modifier.size(20.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = strings.stats.avatarModerationBanTitle,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = SoundLapaAmber,
            )
            Text(
                text = strings.stats.avatarModerationBanWarning,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                lineHeight = 16.sp,
            )
        }
    }
}

/**
 * Spotify-style Listening Statistics card stack showing Week / Month / Year breakdowns:
 * total minutes listened, favorite artist, favorite track, top artists, and top tracks.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListeningStatsSection(
    events: List<ListeningPlayEvent>,
    fallbackHistory: List<ListeningHistoryEntry>,
    tracksById: Map<String, TrackEntity>,
    accent: Color,
    modifier: Modifier = Modifier,
    onPlayTrack: ((TrackEntity) -> Unit)? = null,
    backupState: StatsBackupUiState? = null,
    onRestoreBackupUri: ((Uri) -> Unit)? = null,
    onClearRestoreStatus: (() -> Unit)? = null,
) {
    val strings = LocalAppStrings.current
    val statsStrings = strings.stats
    val haptics = rememberSoundLapaHaptics()
    val explicitIds = LocalExplicitTrackIds.current

    var selectedPeriod by remember { mutableStateOf(ListeningStatsPeriod.WEEK) }
    val snapshot = remember(events, fallbackHistory, selectedPeriod) {
        ListeningStatsAggregator.compute(
            events = events,
            period = selectedPeriod,
            fallbackHistory = fallbackHistory,
        )
    }

    val periodLabels = remember(statsStrings) {
        listOf(
            ListeningStatsPeriod.WEEK to statsStrings.periodWeek,
            ListeningStatsPeriod.MONTH to statsStrings.periodMonth,
            ListeningStatsPeriod.YEAR to statsStrings.periodYear,
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("listening_stats_section"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.BarChart,
                contentDescription = statsStrings.sectionTitle,
                tint = accent,
                modifier = Modifier.size(22.dp),
            )
            Text(
                text = statsStrings.sectionTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("stats_period_selector"),
        ) {
            periodLabels.forEachIndexed { index, (period, label) ->
                val selected = selectedPeriod == period
                SegmentedButton(
                    selected = selected,
                    onClick = {
                        haptics.tick()
                        selectedPeriod = period
                    },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = periodLabels.size,
                    ),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = accent,
                        activeContentColor = MaterialTheme.colorScheme.onPrimary,
                        inactiveContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        inactiveContentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                    modifier = Modifier.testTag("stats_period_${period.name.lowercase()}"),
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // Hero Minutes Listened Card
        LiquidGlassCard(
            accentTint = accent,
            shape = RoundedCornerShape(24.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.linearGradient(
                            listOf(
                                accent.copy(alpha = 0.22f),
                                accent.copy(alpha = 0.05f),
                            ),
                        ),
                    )
                    .padding(18.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = statsStrings.totalMinutesTitle,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = statsStrings.minutesUnitShort(snapshot.totalMinutes),
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.testTag("stats_total_minutes_value"),
                        )
                        Text(
                            text = statsStrings.playsCountShort(snapshot.totalPlays),
                            style = MaterialTheme.typography.bodySmall,
                            color = accent,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(accent.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AutoAwesome,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
            }
        }

        if (snapshot.topTracks.isEmpty() && snapshot.topArtists.isEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f),
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Text(
                    text = statsStrings.emptyStatsHint,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(18.dp),
                )
            }
            if (backupState != null && onRestoreBackupUri != null) {
                StatsBackupRestoreCard(
                    backupState = backupState,
                    accent = accent,
                    onRestoreBackupUri = onRestoreBackupUri,
                    onClearRestoreStatus = onClearRestoreStatus,
                )
            }
            return@Column
        }

        // Favorite Artist + Favorite Track highlight cards
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            snapshot.favoriteArtist?.let { favArtist ->
                StatHighlightCard(
                    title = statsStrings.favoriteArtistTitle,
                    primary = favArtist.artistName,
                    secondary = statsStrings.minutesUnitShort(favArtist.listenedMinutes),
                    icon = Icons.Rounded.Person,
                    accent = accent,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("stats_favorite_artist_card"),
                )
            }
            snapshot.favoriteTrack?.let { favTrack ->
                val playable = tracksById[favTrack.trackId]
                StatHighlightCard(
                    title = statsStrings.favoriteTrackTitle,
                    primary = favTrack.title,
                    secondary = favTrack.artist,
                    icon = Icons.Rounded.Favorite,
                    accent = accent,
                    onClick = if (playable != null && onPlayTrack != null) {
                        {
                            haptics.tap()
                            onPlayTrack(playable)
                        }
                    } else null,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("stats_favorite_track_card"),
                )
            }
        }

        // Top Artists Card
        if (snapshot.topArtists.isNotEmpty()) {
            LiquidGlassCard(
                accentTint = accent,
                shape = RoundedCornerShape(22.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = statsStrings.topArtistsTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    snapshot.topArtists.take(5).forEachIndexed { index, artist ->
                        TopArtistStatRow(
                            rank = index + 1,
                            stat = artist,
                            minutesLabel = statsStrings.minutesUnitShort(artist.listenedMinutes),
                            playsLabel = statsStrings.playsCountShort(artist.playCount),
                            accent = accent,
                        )
                    }
                }
            }
        }

        // Top Tracks Card
        if (snapshot.topTracks.isNotEmpty()) {
            LiquidGlassCard(
                accentTint = accent,
                shape = RoundedCornerShape(22.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = statsStrings.topTracksTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    snapshot.topTracks.take(5).forEachIndexed { index, trackStat ->
                        val entity = tracksById[trackStat.trackId]
                        val isExplicit = if (entity != null) {
                            ExplicitContentDetector.isExplicit(entity, explicitIds)
                        } else {
                            ExplicitContentDetector.containsExplicitLanguage(trackStat.title)
                        }
                        TopTrackStatRow(
                            rank = index + 1,
                            stat = trackStat,
                            entity = entity,
                            isExplicit = isExplicit,
                            minutesLabel = statsStrings.minutesUnitShort(trackStat.listenedMinutes),
                            accent = accent,
                            onPlay = if (entity != null && onPlayTrack != null) {
                                {
                                    haptics.tap()
                                    onPlayTrack(entity)
                                }
                            } else null,
                        )
                    }
                }
            }
        }

        if (backupState != null && onRestoreBackupUri != null) {
            StatsBackupRestoreCard(
                backupState = backupState,
                accent = accent,
                onRestoreBackupUri = onRestoreBackupUri,
                onClearRestoreStatus = onClearRestoreStatus,
            )
        }
    }
}

@Composable
fun StatsBackupRestoreCard(
    backupState: StatsBackupUiState,
    accent: Color,
    onRestoreBackupUri: (Uri) -> Unit,
    onClearRestoreStatus: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current
    val haptics = rememberSoundLapaHaptics()
    val restoreLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            onRestoreBackupUri(uri)
        }
    }

    LiquidGlassCard(
        modifier = modifier.testTag("stats_backup_restore_card"),
        accentTint = accent,
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.CloudDone,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = strings.v34.statsBackupTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            Text(
                text = strings.v34.statsBackupLocationHint(backupState.locationLabel),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            backupState.restoreStatusMessage?.let { raw ->
                val isOk = raw.startsWith("ok:")
                val displayMsg = if (isOk) {
                    val count = raw.removePrefix("ok:").toIntOrNull() ?: 0
                    strings.v34.statsRestoreSuccess(count)
                } else {
                    strings.v34.statsRestoreError
                }
                Text(
                    text = displayMsg,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isOk) LiquidMint else LiquidRose,
                    modifier = Modifier.testTag("stats_restore_status_text"),
                )
            }

            OutlinedButton(
                onClick = {
                    haptics.tick()
                    onClearRestoreStatus?.invoke()
                    restoreLauncher.launch(arrayOf("application/json", "*/*"))
                },
                enabled = !backupState.isRestoring,
                shape = CircleShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("stats_restore_backup_button"),
            ) {
                if (backupState.isRestoring) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = accent,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(strings.v34.statsRestoringLabel)
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Restore,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = strings.v34.statsRestoreButton,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatHighlightCard(
    title: String,
    primary: String,
    secondary: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.50f),
                RoundedCornerShape(20.dp),
            ),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = primary,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = secondary,
                style = MaterialTheme.typography.bodySmall,
                color = accent,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TopArtistStatRow(
    rank: Int,
    stat: TopArtistStat,
    minutesLabel: String,
    playsLabel: String,
    accent: Color,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "$rank",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Black,
            color = if (rank == 1) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(22.dp),
        )
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Person,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                text = stat.artistName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = playsLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Text(
            text = minutesLabel,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = accent,
        )
    }
}

@Composable
private fun TopTrackStatRow(
    rank: Int,
    stat: TopTrackStat,
    entity: TrackEntity?,
    isExplicit: Boolean,
    minutesLabel: String,
    accent: Color,
    onPlay: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .then(if (onPlay != null) Modifier.clickable(onClick = onPlay) else Modifier)
            .padding(vertical = 4.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "$rank",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Black,
            color = if (rank == 1) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(22.dp),
        )
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            if (entity != null) {
                TrackCoverArtwork(
                    track = entity,
                    cornerRadius = 10.dp,
                    targetSizePx = 140,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                if (isExplicit) {
                    ExplicitTrackBadge()
                }
                Text(
                    text = stat.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            Text(
                text = stat.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = minutesLabel,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = accent,
        )
        if (onPlay != null) {
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
