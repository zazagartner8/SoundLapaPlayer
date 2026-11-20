package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.remote.AvatarModerationStatus
import com.example.data.remote.CloudAvatarModerationRequest
import com.example.ui.components.AccountAvatarImage
import com.example.ui.components.rememberSoundLapaHaptics
import com.example.ui.i18n.LocalAppStrings
import com.example.ui.theme.LiquidCyan
import com.example.ui.theme.LiquidMint
import com.example.ui.theme.LiquidRose
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class AvatarQueueFilter {
    PENDING,
    ALL,
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ModeratorAvatarsTabContent(
    requests: List<CloudAvatarModerationRequest>,
    isLoading: Boolean,
    processingIds: Set<String>,
    errorMessage: String?,
    accent: Color,
    onRefresh: () -> Unit,
    onApprove: (CloudAvatarModerationRequest) -> Unit,
    onReject: (CloudAvatarModerationRequest, String?) -> Unit,
) {
    val strings = LocalAppStrings.current
    val v35 = strings.v35
    val haptics = rememberSoundLapaHaptics()
    var filter by remember { mutableStateOf(AvatarQueueFilter.PENDING) }
    var rejectingTarget by remember { mutableStateOf<CloudAvatarModerationRequest?>(null) }
    var rejectReasonInput by remember { mutableStateOf("") }
    val dateFormatter = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }

    val pendingCount = remember(requests) {
        requests.count { it.parsedStatus() == AvatarModerationStatus.PENDING }
    }
    val visibleRequests = remember(requests, filter) {
        when (filter) {
            AvatarQueueFilter.PENDING -> requests.filter { it.parsedStatus() == AvatarModerationStatus.PENDING }
            AvatarQueueFilter.ALL -> requests
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("moderator_avatars_list"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "mod_avatars_header", contentType = "mod_header") {
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
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(LiquidCyan.copy(alpha = 0.18f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.VerifiedUser,
                                    contentDescription = null,
                                    tint = LiquidCyan,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = v35.moderatorSectionTitle,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = v35.moderatorSectionSubtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = {
                                haptics.tick()
                                onRefresh()
                            },
                            enabled = !isLoading,
                            shape = CircleShape,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.testTag("moderator_refresh_avatars_button"),
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = LiquidCyan,
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

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        FilterChip(
                            selected = filter == AvatarQueueFilter.PENDING,
                            onClick = {
                                haptics.tick()
                                filter = AvatarQueueFilter.PENDING
                            },
                            label = {
                                Text(
                                    text = "${v35.avatarRequestsFilterPending} ($pendingCount)",
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            },
                            shape = CircleShape,
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = LiquidCyan.copy(alpha = 0.22f),
                                selectedLabelColor = LiquidCyan,
                            ),
                            modifier = Modifier.testTag("moderator_filter_pending"),
                        )
                        FilterChip(
                            selected = filter == AvatarQueueFilter.ALL,
                            onClick = {
                                haptics.tick()
                                filter = AvatarQueueFilter.ALL
                            },
                            label = {
                                Text(
                                    text = "${v35.avatarRequestsFilterAll} (${requests.size})",
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            },
                            shape = CircleShape,
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = accent.copy(alpha = 0.22f),
                                selectedLabelColor = accent,
                            ),
                            modifier = Modifier.testTag("moderator_filter_all"),
                        )
                    }

                    errorMessage?.takeIf { it.isNotBlank() }?.let { err ->
                        Text(
                            text = err,
                            style = MaterialTheme.typography.bodySmall,
                            color = LiquidRose,
                        )
                    }
                }
            }
        }

        if (visibleRequests.isEmpty() && !isLoading) {
            item(key = "mod_avatars_empty", contentType = "mod_empty") {
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("moderator_avatars_empty"),
                ) {
                    Text(
                        text = v35.avatarRequestsEmpty,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(18.dp),
                    )
                }
            }
        }

        items(
            items = visibleRequests,
            key = { "${it.userId}_${it.createdAtMs}" },
            contentType = { "avatar_request_card" },
        ) { req ->
            val isProcessing = req.userId in processingIds
            val status = req.parsedStatus()
            val statusColor = when (status) {
                AvatarModerationStatus.PENDING -> LiquidCyan
                AvatarModerationStatus.APPROVED -> LiquidMint
                AvatarModerationStatus.REJECTED -> LiquidRose
            }
            val statusLabel = when (status) {
                AvatarModerationStatus.PENDING -> v35.avatarRequestStatusPending
                AvatarModerationStatus.APPROVED -> v35.avatarRequestStatusApproved
                AvatarModerationStatus.REJECTED -> v35.avatarRequestStatusRejected
            }

            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        color = if (status == AvatarModerationStatus.PENDING) {
                            LiquidCyan.copy(alpha = 0.48f)
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                        shape = RoundedCornerShape(22.dp),
                    )
                    .testTag("avatar_request_card_${req.userId}"),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = req.displayName.ifBlank { "SoundLap User" },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val metaLine = buildString {
                                req.username?.takeIf { it.isNotBlank() }?.let { append("@$it • ") }
                                req.accountUid?.takeIf { it.isNotBlank() }?.let { append("$it • ") }
                                if (req.createdAtMs > 0L) {
                                    append(dateFormatter.format(Date(req.createdAtMs)))
                                }
                            }.trimEnd(' ', '•')
                            if (metaLine.isNotBlank()) {
                                Text(
                                    text = metaLine,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(statusColor.copy(alpha = 0.16f))
                                .border(1.dp, statusColor.copy(alpha = 0.5f), CircleShape)
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = statusLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = statusColor,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }

                    // Side-by-side Current vs Proposed Avatar comparison
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.55f))
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            AccountAvatarImage(
                                avatarUrlOrData = req.currentPhotoUrl,
                                contentDescription = v35.avatarRequestCurrentLabel,
                                isGuest = false,
                                size = 70.dp,
                                accent = MaterialTheme.colorScheme.outline,
                            )
                            Text(
                                text = v35.avatarRequestCurrentLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        Icon(
                            imageVector = Icons.Rounded.ArrowForward,
                            contentDescription = null,
                            tint = LiquidCyan,
                            modifier = Modifier.size(22.dp),
                        )

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            AccountAvatarImage(
                                avatarUrlOrData = req.proposedPhotoUrl,
                                contentDescription = v35.avatarRequestProposedLabel,
                                isGuest = false,
                                size = 76.dp,
                                accent = LiquidCyan,
                            )
                            Text(
                                text = v35.avatarRequestProposedLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = LiquidCyan,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }

                    req.rejectReason?.takeIf { it.isNotBlank() && status == AvatarModerationStatus.REJECTED }?.let { reason ->
                        Text(
                            text = "${v35.avatarRequestRejectReasonLabel}: $reason",
                            style = MaterialTheme.typography.bodySmall,
                            color = LiquidRose,
                        )
                    }

                    if (status == AvatarModerationStatus.PENDING) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Button(
                                onClick = {
                                    haptics.confirm()
                                    onApprove(req)
                                },
                                enabled = !isProcessing,
                                shape = CircleShape,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = LiquidMint,
                                    contentColor = Color(0xFF061611),
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("avatar_request_approve_${req.userId}"),
                            ) {
                                if (isProcessing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = Color(0xFF061611),
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = v35.avatarRequestApproveButton,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        softWrap = false,
                                    )
                                }
                            }

                            OutlinedButton(
                                onClick = {
                                    haptics.tick()
                                    rejectReasonInput = ""
                                    rejectingTarget = req
                                },
                                enabled = !isProcessing,
                                shape = CircleShape,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("avatar_request_reject_${req.userId}"),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = null,
                                    tint = LiquidRose,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = v35.avatarRequestRejectButton,
                                    color = LiquidRose,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                        }
                    }
                }
            }
        }

        item(key = "mod_avatars_bottom_spacer", contentType = "spacer") {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    rejectingTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { rejectingTarget = null },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurface,
            title = {
                Text(
                    text = v35.avatarRequestRejectDialogTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                OutlinedTextField(
                    value = rejectReasonInput,
                    onValueChange = { rejectReasonInput = it.take(240) },
                    label = { Text(v35.avatarRequestRejectReasonLabel) },
                    placeholder = { Text(v35.avatarRequestRejectReasonPlaceholder) },
                    minLines = 2,
                    maxLines = 4,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("avatar_reject_reason_input"),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        haptics.confirm()
                        onReject(target, rejectReasonInput.trim().takeIf { it.isNotBlank() })
                        rejectingTarget = null
                    },
                    modifier = Modifier.testTag("avatar_reject_confirm_button"),
                ) {
                    Text(
                        text = v35.avatarRequestRejectButton,
                        color = LiquidRose,
                        fontWeight = FontWeight.Bold,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { rejectingTarget = null }) {
                    Text(strings.cancel)
                }
            },
        )
    }
}
