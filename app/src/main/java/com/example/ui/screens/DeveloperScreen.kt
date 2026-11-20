package com.example.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.remote.AvatarModerationStatus
import com.example.data.remote.BugReportStatus
import com.example.data.remote.CloudAvatarModerationRequest
import com.example.data.remote.DeveloperUserOverview
import com.example.data.remote.formatCloudExactSize
import com.example.data.remote.formatCloudStorageHeadline
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import com.example.ui.AdminAccessLevel
import com.example.ui.BugReportItemUi
import com.example.ui.MainUiState
import com.example.ui.auth.AdminBiometricScope
import com.example.ui.auth.BiometricAvailability
import com.example.ui.auth.checkBiometricAvailability
import com.example.ui.auth.isAdminPanelAccount
import com.example.ui.auth.isVerifiedDeveloperAccount
import com.example.ui.auth.launchDeveloperBiometricPrompt
import com.example.ui.components.AccountAvatarImage
import com.example.ui.components.rememberSoundLapaHaptics
import com.example.ui.i18n.LocalAppStrings
import com.example.ui.theme.LiquidCyan
import com.example.ui.theme.LiquidMint
import com.example.ui.theme.LiquidRose
import com.example.ui.theme.SoundLapMotion
import com.example.ui.theme.expressivePressScale
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class DevReportFilter {
    ALL,
    OPEN,
    IN_PROGRESS,
    RESOLVED,
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DeveloperScreen(
    state: MainUiState,
    initialTab: String? = null,
    onConsumeInitialTab: () -> Unit = {},
    onClose: () -> Unit,
    onUnlockDeveloper: () -> Unit,
    onUnlockModerators: () -> Unit,
    onLock: () -> Unit,
    onRefreshDashboard: () -> Unit,
    onRefreshUsers: () -> Unit,
    onRefreshAvatarRequests: () -> Unit = {},
    onApproveAvatarRequest: (CloudAvatarModerationRequest) -> Unit = {},
    onRejectAvatarRequest: (CloudAvatarModerationRequest, String?) -> Unit = { _, _ -> },
    onSelectUser: (String) -> Unit,
    onCloseUserDetails: () -> Unit,
    onPlayUserTrack: (DeveloperUserOverview) -> Unit = {},
    onBanUser: (targetUid: String, reason: String?) -> Unit = { _, _ -> },
    onUnbanUser: (targetUid: String) -> Unit = {},
    onDeleteUserFromServer: (targetUid: String) -> Unit = {},
    onClearUserActionMessage: () -> Unit = {},
    onUpdateReportStatus: (BugReportItemUi, BugReportStatus) -> Unit = { _, _ -> },
    onDeleteReport: (BugReportItemUi) -> Unit,
    onRefreshLocalCacheStats: () -> Unit = {},
    onClearAudioPrefetchCache: () -> Unit = {},
    onClearImageCoverCache: () -> Unit = {},
    onClearAllLocalCaches: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onClose)

    val strings = LocalAppStrings.current
    val v35 = strings.v35
    val context = LocalContext.current
    val haptics = rememberSoundLapaHaptics()
    val accent = MaterialTheme.colorScheme.primary

    val isDev = state.cloudSync.isVerifiedDeveloper
    val canModerate = state.cloudSync.canOpenAdminPanel
    val accessLevel = state.developerMode.accessLevel

    var devBiometricStatus by remember { mutableStateOf<String?>(null) }
    var modBiometricStatus by remember { mutableStateOf<String?>(null) }
    var pendingDeleteReport by remember { mutableStateOf<BugReportItemUi?>(null) }
    var activeTab by remember { mutableIntStateOf(0) }

    LaunchedEffect(initialTab, isDev) {
        when (initialTab) {
            "moderators" -> {
                activeTab = 1
                onConsumeInitialTab()
            }
            "reports" -> {
                if (isDev) {
                    activeTab = 2
                }
                onConsumeInitialTab()
            }
        }
    }

    val biometricAvailability = remember(context) {
        checkBiometricAvailability(context)
    }

    val pendingAvatarCount = remember(state.developerMode.avatarRequests) {
        state.developerMode.avatarRequests.count { it.parsedStatus() == AvatarModerationStatus.PENDING }
    }
    val openReportsCount = remember(state.developerMode.reports) {
        state.developerMode.reports.count { it.status == BugReportStatus.OPEN }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 720.dp),
            ) {
                // Header — full title always readable without ellipsis truncation
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
                            .testTag("developer_back_button"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = strings.close,
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Text(
                        text = strings.settingsDeveloperSection,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 2,
                        lineHeight = 20.sp,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("developer_screen_title"),
                    )
                    if (state.developerMode.isUnlocked) {
                        IconButton(
                            onClick = {
                                haptics.tick()
                                onLock()
                            },
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f))
                                .testTag("developer_lock_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Lock,
                                contentDescription = strings.settingsDeveloperLockButton,
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }

                AnimatedContent(
                    targetState = accessLevel,
                    transitionSpec = {
                        (fadeIn(animationSpec = SoundLapMotion.effectsDefault()) +
                            scaleIn(initialScale = 0.96f, animationSpec = SoundLapMotion.spatialDefault())) togetherWith
                            fadeOut(animationSpec = SoundLapMotion.effectsFast())
                    },
                    label = "admin_access_level_transition",
                ) { level ->
                    when (level) {
                        AdminAccessLevel.LOCKED -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                // Block 1: Strictly for Developer (SL-D1E6-EE60)
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .border(
                                            width = 1.dp,
                                            color = if (isDev) accent.copy(alpha = 0.40f) else MaterialTheme.colorScheme.outlineVariant,
                                            shape = RoundedCornerShape(26.dp),
                                        ),
                                    shape = RoundedCornerShape(26.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                    contentColor = MaterialTheme.colorScheme.onSurface,
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(20.dp),
                                        verticalArrangement = Arrangement.spacedBy(14.dp),
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(44.dp)
                                                    .clip(RoundedCornerShape(16.dp))
                                                    .background(accent.copy(alpha = 0.18f)),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.AdminPanelSettings,
                                                    contentDescription = null,
                                                    tint = accent,
                                                    modifier = Modifier.size(24.dp),
                                                )
                                            }
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = v35.devBlockTitle,
                                                    style = MaterialTheme.typography.titleMedium,
                                                    fontWeight = FontWeight.Bold,
                                                )
                                                Text(
                                                    text = v35.devBlockHint,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }

                                        Button(
                                            onClick = {
                                                haptics.tick()
                                                devBiometricStatus = null
                                                val signedInEmail = state.cloudSync.email
                                                val signedInUid = state.cloudSync.userId
                                                val allowedDev = !state.cloudSync.isGuest &&
                                                    !signedInUid.isNullOrBlank() &&
                                                    (isDev || isVerifiedDeveloperAccount(signedInUid, signedInEmail))
                                                when {
                                                    !allowedDev -> {
                                                        devBiometricStatus = v35.devBlockOnlyHint
                                                    }
                                                    biometricAvailability != BiometricAvailability.AVAILABLE -> {
                                                        devBiometricStatus = strings.settingsDeveloperBiometricUnavailable
                                                    }
                                                    else -> {
                                                        launchDeveloperBiometricPrompt(
                                                            context = context,
                                                            signedInUid = signedInUid,
                                                            signedInEmail = signedInEmail,
                                                            title = v35.devBlockTitle,
                                                            subtitle = strings.settingsDeveloperBiometricPromptSubtitle,
                                                            negativeButtonText = strings.cancel,
                                                            unavailableMessage = strings.settingsDeveloperBiometricUnavailable,
                                                            failedMessage = strings.settingsDeveloperBiometricFailed,
                                                            scope = AdminBiometricScope.DEVELOPER_ONLY,
                                                            onAuthenticated = {
                                                                haptics.confirm()
                                                                devBiometricStatus = null
                                                                onUnlockDeveloper()
                                                            },
                                                            onDismissed = {},
                                                            onError = { msg ->
                                                                devBiometricStatus = msg
                                                            },
                                                        )
                                                    }
                                                }
                                            },
                                            enabled = isDev,
                                            shape = RoundedCornerShape(18.dp),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = accent.copy(alpha = 0.2f),
                                                contentColor = accent,
                                                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                            ),
                                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .expressivePressScale(enabled = isDev)
                                                .border(
                                                    width = 1.dp,
                                                    color = if (isDev) accent.copy(alpha = 0.45f) else MaterialTheme.colorScheme.outlineVariant,
                                                    shape = RoundedCornerShape(18.dp),
                                                )
                                                .testTag("developer_biometric_unlock_button"),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Fingerprint,
                                                contentDescription = v35.devBlockUnlockButton,
                                                modifier = Modifier.size(22.dp),
                                            )
                                            Spacer(Modifier.width(10.dp))
                                            Text(
                                                text = if (isDev) v35.devBlockUnlockButton else v35.devBlockOnlyHint,
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 2,
                                            )
                                        }

                                        devBiometricStatus?.let { msg ->
                                            Text(
                                                text = msg,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = LiquidCyan,
                                                modifier = Modifier.testTag("developer_biometric_status"),
                                            )
                                        }
                                    }
                                }

                                // Block 2: For Moderators (SL-C526-964C, SL-6721-BEED & Developer SL-D1E6-EE60)
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .border(
                                            width = 1.dp,
                                            color = if (canModerate) LiquidMint.copy(alpha = 0.42f) else MaterialTheme.colorScheme.outlineVariant,
                                            shape = RoundedCornerShape(26.dp),
                                        ),
                                    shape = RoundedCornerShape(26.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                    contentColor = MaterialTheme.colorScheme.onSurface,
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(20.dp),
                                        verticalArrangement = Arrangement.spacedBy(14.dp),
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(44.dp)
                                                    .clip(RoundedCornerShape(16.dp))
                                                    .background(LiquidMint.copy(alpha = 0.18f)),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.VerifiedUser,
                                                    contentDescription = null,
                                                    tint = LiquidMint,
                                                    modifier = Modifier.size(24.dp),
                                                )
                                            }
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = v35.modBlockTitle,
                                                    style = MaterialTheme.typography.titleMedium,
                                                    fontWeight = FontWeight.Bold,
                                                )
                                                Text(
                                                    text = v35.modBlockHint,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }

                                        Button(
                                            onClick = {
                                                haptics.tick()
                                                modBiometricStatus = null
                                                val signedInEmail = state.cloudSync.email
                                                val signedInUid = state.cloudSync.userId
                                                val allowedMod = !state.cloudSync.isGuest &&
                                                    !signedInUid.isNullOrBlank() &&
                                                    (canModerate || isAdminPanelAccount(signedInUid, signedInEmail))
                                                when {
                                                    !allowedMod -> {
                                                        modBiometricStatus = v35.modBlockOnlyHint
                                                    }
                                                    biometricAvailability != BiometricAvailability.AVAILABLE -> {
                                                        modBiometricStatus = strings.settingsDeveloperBiometricUnavailable
                                                    }
                                                    else -> {
                                                        launchDeveloperBiometricPrompt(
                                                            context = context,
                                                            signedInUid = signedInUid,
                                                            signedInEmail = signedInEmail,
                                                            title = v35.modBlockTitle,
                                                            subtitle = strings.settingsDeveloperBiometricPromptSubtitle,
                                                            negativeButtonText = strings.cancel,
                                                            unavailableMessage = strings.settingsDeveloperBiometricUnavailable,
                                                            failedMessage = strings.settingsDeveloperBiometricFailed,
                                                            scope = AdminBiometricScope.MODERATOR_OR_DEVELOPER,
                                                            onAuthenticated = {
                                                                haptics.confirm()
                                                                modBiometricStatus = null
                                                                onUnlockModerators()
                                                            },
                                                            onDismissed = {},
                                                            onError = { msg ->
                                                                modBiometricStatus = msg
                                                            },
                                                        )
                                                    }
                                                }
                                            },
                                            enabled = canModerate,
                                            shape = RoundedCornerShape(18.dp),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = LiquidMint.copy(alpha = 0.2f),
                                                contentColor = LiquidMint,
                                                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                            ),
                                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .expressivePressScale(enabled = canModerate)
                                                .border(
                                                    width = 1.dp,
                                                    color = if (canModerate) LiquidMint.copy(alpha = 0.45f) else MaterialTheme.colorScheme.outlineVariant,
                                                    shape = RoundedCornerShape(18.dp),
                                                )
                                                .testTag("moderator_biometric_unlock_button"),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Fingerprint,
                                                contentDescription = v35.modBlockUnlockButton,
                                                modifier = Modifier.size(22.dp),
                                            )
                                            Spacer(Modifier.width(10.dp))
                                            Text(
                                                text = if (canModerate) v35.modBlockUnlockButton else v35.modBlockOnlyHint,
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 2,
                                            )
                                        }

                                        modBiometricStatus?.let { msg ->
                                            Text(
                                                text = msg,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = LiquidCyan,
                                                modifier = Modifier.testTag("moderator_biometric_status"),
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        AdminAccessLevel.MODERATOR -> {
                            ModeratorAvatarsTabContent(
                                requests = state.developerMode.avatarRequests,
                                isLoading = state.developerMode.isLoadingAvatarRequests,
                                processingIds = state.developerMode.processingAvatarRequestIds,
                                errorMessage = state.developerMode.errorMessage,
                                accent = LiquidMint,
                                onRefresh = onRefreshAvatarRequests,
                                onApprove = onApproveAvatarRequest,
                                onReject = onRejectAvatarRequest,
                            )
                        }

                        AdminAccessLevel.DEVELOPER -> {
                            Column(modifier = Modifier.fillMaxSize()) {
                                val tabItems = listOf(
                                    Triple(0, strings.devExtras.tabUsers, Icons.Rounded.Group) to "developer_tab_users",
                                    Triple(
                                        1,
                                        if (pendingAvatarCount > 0) "${v35.tabModerators} ($pendingAvatarCount)" else v35.tabModerators,
                                        Icons.Rounded.VerifiedUser,
                                    ) to "developer_tab_moderators",
                                    Triple(
                                        2,
                                        if (openReportsCount > 0) "${strings.devExtras.tabReports} ($openReportsCount)" else strings.devExtras.tabReports,
                                        Icons.Rounded.BugReport,
                                    ) to "developer_tab_reports",
                                    Triple(3, strings.devExtras.tabStorage, Icons.Rounded.CloudQueue) to "developer_tab_storage",
                                )

                                PrimaryScrollableTabRow(
                                    selectedTabIndex = activeTab.coerceIn(0, tabItems.lastIndex),
                                    containerColor = MaterialTheme.colorScheme.background,
                                    contentColor = accent,
                                    edgePadding = 12.dp,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    tabItems.forEach { (tabSpec, tag) ->
                                        val (idx, label, icon) = tabSpec
                                        val selected = activeTab == idx
                                        Tab(
                                            selected = selected,
                                            onClick = {
                                                haptics.tick()
                                                activeTab = idx
                                                if (idx == 1) onRefreshAvatarRequests()
                                                if (idx == 3) onRefreshLocalCacheStats()
                                            },
                                            modifier = Modifier.testTag(tag),
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            ) {
                                                Icon(
                                                    imageVector = icon,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(18.dp),
                                                )
                                                Text(
                                                    text = label,
                                                    fontSize = 13.sp,
                                                    lineHeight = 16.sp,
                                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                                    maxLines = 1,
                                                    softWrap = false,
                                                )
                                            }
                                        }
                                    }
                                }

                                AnimatedContent(
                                    targetState = activeTab,
                                    transitionSpec = {
                                        val dir = if (targetState >= initialState) 1 else -1
                                        (slideInHorizontally(
                                            initialOffsetX = { (it * 0.14f).toInt() * dir },
                                            animationSpec = SoundLapMotion.spatialFast(),
                                        ) + fadeIn(animationSpec = SoundLapMotion.effectsFast())) togetherWith
                                            (slideOutHorizontally(
                                                targetOffsetX = { -(it * 0.10f).toInt() * dir },
                                                animationSpec = SoundLapMotion.spatialFast(),
                                            ) + fadeOut(animationSpec = SoundLapMotion.effectsFast()))
                                    },
                                    label = "developer_tab_content",
                                    modifier = Modifier.weight(1f),
                                ) { tabIndex ->
                                    when (tabIndex) {
                                        0 -> DeveloperUsersTabContent(
                                            users = state.developerMode.users,
                                            isLoadingUsers = state.developerMode.isLoadingUsers,
                                            usersErrorMessage = state.developerMode.usersErrorMessage,
                                            playingUserTrackUid = state.developerMode.playingUserTrackUid,
                                            accent = accent,
                                            onRefreshUsers = onRefreshUsers,
                                            onSelectUser = onSelectUser,
                                            onPlayUserTrack = onPlayUserTrack,
                                        )

                                        1 -> ModeratorAvatarsTabContent(
                                            requests = state.developerMode.avatarRequests,
                                            isLoading = state.developerMode.isLoadingAvatarRequests,
                                            processingIds = state.developerMode.processingAvatarRequestIds,
                                            errorMessage = state.developerMode.errorMessage,
                                            accent = accent,
                                            onRefresh = onRefreshAvatarRequests,
                                            onApprove = onApproveAvatarRequest,
                                            onReject = onRejectAvatarRequest,
                                        )

                                        2 -> DeveloperReportsTabContent(
                                            state = state,
                                            accent = accent,
                                            onRefreshDashboard = onRefreshDashboard,
                                            onOpenUserProfile = onSelectUser,
                                            onUpdateReportStatus = onUpdateReportStatus,
                                            onRequestDeleteReport = { pendingDeleteReport = it },
                                        )

                                        else -> DeveloperStorageTabContent(
                                            state = state,
                                            accent = accent,
                                            onRefreshAll = {
                                                onRefreshDashboard()
                                                onRefreshLocalCacheStats()
                                            },
                                            onSelectUser = onSelectUser,
                                            onClearAudioPrefetchCache = onClearAudioPrefetchCache,
                                            onClearImageCoverCache = onClearImageCoverCache,
                                            onClearAllLocalCaches = onClearAllLocalCaches,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (isDev &&
                state.developerMode.isDeveloperUnlocked &&
                (state.developerMode.selectedUserDetails != null || state.developerMode.isLoadingUserDetails)
            ) {
                DeveloperUserDetailOverlay(
                    details = state.developerMode.selectedUserDetails,
                    isLoading = state.developerMode.isLoadingUserDetails,
                    currentDeveloperUid = state.cloudSync.userId,
                    isActionInProgress = state.developerMode.isManagingUserAction,
                    actionStatusMessage = state.developerMode.userActionMessage,
                    playingUserTrackUid = state.developerMode.playingUserTrackUid,
                    accent = accent,
                    onPlayUserTrack = onPlayUserTrack,
                    onBanUser = onBanUser,
                    onUnbanUser = onUnbanUser,
                    onDeleteUserFromServer = onDeleteUserFromServer,
                    onClearActionStatus = onClearUserActionMessage,
                    onClose = {
                        onClearUserActionMessage()
                        onCloseUserDetails()
                    },
                )
            }
        }
    }

    pendingDeleteReport?.let { targetReport ->
        AlertDialog(
            onDismissRequest = { pendingDeleteReport = null },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurface,
            title = { Text(strings.developerDeleteReportConfirmTitle) },
            text = { Text(strings.developerDeleteReportConfirmMessage) },
            confirmButton = {
                TextButton(
                    onClick = {
                        haptics.confirm()
                        onDeleteReport(targetReport)
                        pendingDeleteReport = null
                    },
                    modifier = Modifier.testTag("developer_confirm_delete_report_button"),
                ) {
                    Text(strings.developerDeleteReportConfirmButton, color = LiquidRose)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteReport = null }) {
                    Text(strings.cancel)
                }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeveloperReportsTabContent(
    state: MainUiState,
    accent: Color,
    onRefreshDashboard: () -> Unit,
    onOpenUserProfile: (String) -> Unit,
    onUpdateReportStatus: (BugReportItemUi, BugReportStatus) -> Unit,
    onRequestDeleteReport: (BugReportItemUi) -> Unit,
) {
    val strings = LocalAppStrings.current
    val v35 = strings.v35
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val haptics = rememberSoundLapaHaptics()
    val dateFormatter = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }

    var searchQuery by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf(DevReportFilter.ALL) }

    val reports = state.developerMode.reports
    val openCount = remember(reports) { reports.count { it.status == BugReportStatus.OPEN } }
    val inProgressCount = remember(reports) { reports.count { it.status == BugReportStatus.IN_PROGRESS } }
    val resolvedCount = remember(reports) { reports.count { it.status == BugReportStatus.RESOLVED } }

    val filteredReports = remember(reports, searchQuery, statusFilter) {
        val q = searchQuery.trim().lowercase()
        reports.filter { report ->
            val matchesStatus = when (statusFilter) {
                DevReportFilter.ALL -> true
                DevReportFilter.OPEN -> report.status == BugReportStatus.OPEN
                DevReportFilter.IN_PROGRESS -> report.status == BugReportStatus.IN_PROGRESS
                DevReportFilter.RESOLVED -> report.status == BugReportStatus.RESOLVED
            }
            if (!matchesStatus) return@filter false
            if (q.isEmpty()) return@filter true
            report.reporterName.lowercase().contains(q) ||
                report.message.lowercase().contains(q) ||
                report.appVersion.lowercase().contains(q) ||
                report.userId.lowercase().contains(q)
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("developer_reports_list"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "reports_header", contentType = "reports_header") {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(26.dp),
                    ),
                shape = RoundedCornerShape(26.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
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
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(accent.copy(alpha = 0.18f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.BugReport,
                                    contentDescription = null,
                                    tint = accent,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            Text(
                                text = "${strings.settingsDeveloperReportsTitle} (${reports.size})",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                haptics.tick()
                                onRefreshDashboard()
                            },
                            enabled = !state.developerMode.isLoadingReports &&
                                !state.developerMode.isLoadingStorageStats,
                            shape = CircleShape,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.testTag("settings_developer_refresh_reports_button"),
                        ) {
                            if (state.developerMode.isLoadingReports) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = accent,
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = null,
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
                                text = v35.reportsSearchPlaceholder,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        leadingIcon = {
                            Icon(imageVector = Icons.Rounded.Search, contentDescription = null)
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(imageVector = Icons.Rounded.Close, contentDescription = strings.close)
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("developer_reports_search_input"),
                    )

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        ReportFilterPill(
                            selected = statusFilter == DevReportFilter.ALL,
                            label = "${v35.reportsFilterAll} (${reports.size})",
                            accent = accent,
                            testTag = "reports_filter_all",
                            onClick = { statusFilter = DevReportFilter.ALL },
                        )
                        ReportFilterPill(
                            selected = statusFilter == DevReportFilter.OPEN,
                            label = "${v35.reportsFilterOpen} ($openCount)",
                            accent = LiquidRose,
                            testTag = "reports_filter_open",
                            onClick = { statusFilter = DevReportFilter.OPEN },
                        )
                        ReportFilterPill(
                            selected = statusFilter == DevReportFilter.IN_PROGRESS,
                            label = "${v35.reportsFilterInProgress} ($inProgressCount)",
                            accent = LiquidCyan,
                            testTag = "reports_filter_in_progress",
                            onClick = { statusFilter = DevReportFilter.IN_PROGRESS },
                        )
                        ReportFilterPill(
                            selected = statusFilter == DevReportFilter.RESOLVED,
                            label = "${v35.reportsFilterResolved} ($resolvedCount)",
                            accent = LiquidMint,
                            testTag = "reports_filter_resolved",
                            onClick = { statusFilter = DevReportFilter.RESOLVED },
                        )
                    }

                    if (state.cloudSync.isGuest) {
                        Text(
                            text = strings.settingsDeveloperReportsCloudHint,
                            style = MaterialTheme.typography.bodySmall,
                            color = LiquidCyan,
                        )
                    }

                    state.developerMode.errorMessage?.let { err ->
                        Text(
                            text = err,
                            style = MaterialTheme.typography.bodySmall,
                            color = LiquidRose,
                        )
                    }

                    if (filteredReports.isEmpty() && !state.developerMode.isLoadingReports) {
                        Text(
                            text = strings.settingsDeveloperNoReports,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("settings_developer_empty_reports"),
                        )
                    }
                }
            }
        }

        items(
            items = filteredReports,
            key = { it.reportId },
            contentType = { "bug_report" },
        ) { report ->
            val isDeleting = report.reportId in state.developerMode.deletingReportIds
            val isUpdating = report.reportId in state.developerMode.updatingReportIds
            val statusColor = when (report.status) {
                BugReportStatus.OPEN -> LiquidRose
                BugReportStatus.IN_PROGRESS -> LiquidCyan
                BugReportStatus.RESOLVED -> LiquidMint
            }

            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                contentColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        color = statusColor.copy(alpha = 0.42f),
                        shape = RoundedCornerShape(20.dp),
                    )
                    .testTag("developer_report_item_${report.reportId}"),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .then(
                                    if (report.userId.isNotBlank()) {
                                        Modifier.clickable {
                                            haptics.tick()
                                            onOpenUserProfile(report.userId)
                                        }
                                    } else {
                                        Modifier
                                    },
                                )
                                .padding(vertical = 2.dp),
                        ) {
                            AccountAvatarImage(
                                avatarUrlOrData = report.reporterAvatarUrl,
                                contentDescription = report.reporterName,
                                isGuest = false,
                                size = 38.dp,
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = report.reporterName,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (report.userId.isNotBlank()) accent else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "${dateFormatter.format(Date(report.createdAtMs))} • v${report.appVersion}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        IconButton(
                            onClick = {
                                haptics.tick()
                                val payload = "${report.reporterName} (v${report.appVersion}):\n${report.message}"
                                clipboard.setText(AnnotatedString(payload))
                                Toast.makeText(context, v35.reportCopiedNotice, Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("developer_copy_report_${report.reportId}"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ContentCopy,
                                contentDescription = v35.reportCopyCd,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(19.dp),
                            )
                        }

                        IconButton(
                            onClick = {
                                haptics.tick()
                                onRequestDeleteReport(report)
                            },
                            enabled = !isDeleting,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .clip(CircleShape)
                                .background(LiquidRose.copy(alpha = 0.14f))
                                .testTag("developer_delete_report_${report.reportId}"),
                        ) {
                            if (isDeleting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = LiquidRose,
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.DeleteOutline,
                                    contentDescription = strings.developerDeleteReportCd,
                                    tint = LiquidRose,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }

                    Text(
                        text = report.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        val statuses = listOf(
                            Triple(BugReportStatus.OPEN, v35.reportStatusOpen, LiquidRose),
                            Triple(BugReportStatus.IN_PROGRESS, v35.reportStatusInProgress, LiquidCyan),
                            Triple(BugReportStatus.RESOLVED, v35.reportStatusResolved, LiquidMint),
                        )
                        statuses.forEach { (statusOption, label, color) ->
                            val selected = report.status == statusOption
                            FilterChip(
                                selected = selected,
                                enabled = !isUpdating,
                                onClick = {
                                    if (!selected) {
                                        haptics.tick()
                                        onUpdateReportStatus(report, statusOption)
                                    }
                                },
                                label = {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    )
                                },
                                shape = CircleShape,
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = color.copy(alpha = 0.22f),
                                    selectedLabelColor = color,
                                ),
                                modifier = Modifier.testTag("report_status_${report.reportId}_${statusOption.name}"),
                            )
                        }
                        if (isUpdating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = accent,
                            )
                        }
                    }
                }
            }
        }

        item(key = "developer_reports_bottom_spacer", contentType = "spacer") {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ReportFilterPill(
    selected: Boolean,
    label: String,
    accent: Color,
    testTag: String,
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
        modifier = Modifier.testTag(testTag),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeveloperStorageTabContent(
    state: MainUiState,
    accent: Color,
    onRefreshAll: () -> Unit,
    onSelectUser: (String) -> Unit,
    onClearAudioPrefetchCache: () -> Unit,
    onClearImageCoverCache: () -> Unit,
    onClearAllLocalCaches: () -> Unit,
) {
    val strings = LocalAppStrings.current
    val v35 = strings.v35
    val haptics = rememberSoundLapaHaptics()
    val storageStats = state.developerMode.cloudStorageStats
    val localCache = state.developerMode.localCacheStats

    fun fmt(bytes: Long): String = formatCloudExactSize(
        bytes = bytes,
        gbUnit = strings.cloudStorageGbUnit,
        mbUnit = strings.cloudStorageMbUnit,
        kbUnit = strings.cloudStorageKbUnit,
        bUnit = strings.cloudStorageBUnit,
    )

    val storageHeadline = remember(
        storageStats.usedBytes,
        storageStats.quotaBytes,
        strings.cloudStorageGbUnit,
    ) {
        formatCloudStorageHeadline(
            usedBytes = storageStats.usedBytes,
            quotaBytes = storageStats.quotaBytes,
            gbUnit = strings.cloudStorageGbUnit,
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("developer_storage_list"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // 1. Cloud Firestore Card with Entity Breakdown & Top Users
        item(key = "cloud_storage_usage", contentType = "storage_card") {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(26.dp),
                    )
                    .testTag("developer_cloud_storage_card"),
                shape = RoundedCornerShape(26.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
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
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(accent.copy(alpha = 0.18f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.CloudQueue,
                                    contentDescription = null,
                                    tint = accent,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = strings.settingsDeveloperStorageTitle,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 2,
                                )
                                Text(
                                    text = strings.cloudStorageQuotaBadge,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        OutlinedButton(
                            onClick = {
                                haptics.tick()
                                onRefreshAll()
                            },
                            enabled = !state.developerMode.isLoadingStorageStats,
                            shape = CircleShape,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier.testTag("developer_storage_refresh_button"),
                        ) {
                            if (state.developerMode.isLoadingStorageStats) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = accent,
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = strings.settingsDeveloperRefreshButton,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }

                    Text(
                        text = storageHeadline,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = accent,
                        modifier = Modifier.testTag("developer_cloud_storage_headline"),
                    )

                    LinearProgressIndicator(
                        progress = { storageStats.usageFraction.coerceIn(0f, 1f) },
                        color = if (storageStats.usageFraction > 0.85f) LiquidRose else accent,
                        trackColor = MaterialTheme.colorScheme.background.copy(alpha = 0.7f),
                        strokeCap = StrokeCap.Round,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .clip(CircleShape)
                            .testTag("developer_cloud_storage_progress"),
                    )

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        StorageStatChip(
                            label = strings.settingsDeveloperStorageUsedExact(fmt(storageStats.usedBytes)),
                            accent = LiquidMint,
                        )
                        StorageStatChip(
                            label = strings.settingsDeveloperStorageFreeLabel(fmt(storageStats.freeBytes)),
                            accent = LiquidCyan,
                        )
                        StorageStatChip(
                            label = strings.settingsDeveloperStorageAccountsLabel(storageStats.activeUsersCount),
                            accent = accent,
                        )
                        StorageStatChip(
                            label = strings.settingsDeveloperStorageTracksLabel(storageStats.totalTracks),
                            accent = accent,
                        )
                        StorageStatChip(
                            label = strings.settingsDeveloperStoragePlaylistsLabel(storageStats.totalPlaylists),
                            accent = accent,
                        )
                        StorageStatChip(
                            label = strings.settingsDeveloperStorageReportsLabel(storageStats.totalBugReports),
                            accent = LiquidRose,
                        )
                        StorageStatChip(
                            label = v35.storageAvatarRequestsCountLabel(storageStats.totalAvatarRequests),
                            accent = LiquidCyan,
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    Text(
                        text = v35.storageCloudBreakdownTitle,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    StorageBreakdownRow(
                        label = v35.storageAccountsBytesLabel,
                        value = fmt(storageStats.accountsBytes),
                        accent = accent,
                    )
                    StorageBreakdownRow(
                        label = v35.storageReportsBytesLabel,
                        value = fmt(storageStats.reportsBytes),
                        accent = LiquidRose,
                    )
                    StorageBreakdownRow(
                        label = v35.storageAvatarRequestsBytesLabel,
                        value = fmt(storageStats.avatarRequestsBytes),
                        accent = LiquidCyan,
                    )

                    if (storageStats.topUsers.isNotEmpty()) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Text(
                            text = v35.storageTopUsersTitle,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            storageStats.topUsers.forEach { slice ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(MaterialTheme.colorScheme.background.copy(alpha = 0.5f))
                                        .clickable {
                                            haptics.tick()
                                            onSelectUser(slice.userId)
                                        }
                                        .padding(horizontal = 12.dp, vertical = 9.dp)
                                        .testTag("storage_top_user_${slice.userId}"),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = slice.displayName.ifBlank { "SoundLap User" },
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = buildString {
                                                slice.accountUid?.takeIf { it.isNotBlank() }?.let { append("$it • ") }
                                                append(strings.settingsDeveloperStorageTracksLabel(slice.tracksCount))
                                                append(" • ")
                                                append(strings.settingsDeveloperStoragePlaylistsLabel(slice.playlistsCount))
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = fmt(slice.storageBytes),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = accent,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                        }
                    }

                    if (!storageStats.isCloudLive) {
                        Text(
                            text = strings.settingsDeveloperStorageOfflineEstimateHint,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // 2. Local Device Cache Breakdown & Cleanup Controls
        item(key = "local_device_storage", contentType = "local_storage_card") {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(26.dp),
                    )
                    .testTag("developer_local_cache_card"),
                shape = RoundedCornerShape(26.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(LiquidCyan.copy(alpha = 0.18f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PhoneAndroid,
                                contentDescription = null,
                                tint = LiquidCyan,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = v35.storageLocalCacheTitle,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = fmt(localCache.totalLocalBytes),
                                style = MaterialTheme.typography.labelMedium,
                                color = LiquidCyan,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }

                    StorageBreakdownRow(
                        label = "${v35.storageAudioPrefetchLabel} (${v35.storageTracksCachedSuffix(localCache.audioPrefetchTracksCount)})",
                        value = fmt(localCache.audioPrefetchBytes),
                        accent = LiquidMint,
                    )
                    StorageBreakdownRow(
                        label = v35.storageImageCacheLabel,
                        value = fmt(localCache.imageCacheBytes),
                        accent = LiquidCyan,
                    )
                    StorageBreakdownRow(
                        label = v35.storageRoomDbLabel,
                        value = fmt(localCache.roomDatabaseBytes),
                        accent = accent,
                    )
                    StorageBreakdownRow(
                        label = "${v35.storageOfflineAudioLabel} (${localCache.offlineTracksCount})",
                        value = fmt(localCache.offlineAudioBytes),
                        accent = LiquidRose,
                    )

                    state.developerMode.localCacheMessage?.takeIf { it.isNotBlank() }?.let { msg ->
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = LiquidMint,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.testTag("developer_local_cache_message"),
                        )
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = {
                                haptics.tick()
                                onClearAudioPrefetchCache()
                            },
                            enabled = !state.developerMode.isClearingLocalCache,
                            shape = CircleShape,
                            modifier = Modifier.testTag("dev_clear_audio_cache_button"),
                        ) {
                            Text(v35.storageClearAudioCacheButton, maxLines = 1, softWrap = false)
                        }
                        OutlinedButton(
                            onClick = {
                                haptics.tick()
                                onClearImageCoverCache()
                            },
                            enabled = !state.developerMode.isClearingLocalCache,
                            shape = CircleShape,
                            modifier = Modifier.testTag("dev_clear_image_cache_button"),
                        ) {
                            Text(v35.storageClearImageCacheButton, maxLines = 1, softWrap = false)
                        }
                    }

                    Button(
                        onClick = {
                            haptics.confirm()
                            onClearAllLocalCaches()
                        },
                        enabled = !state.developerMode.isClearingLocalCache,
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = accent,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("dev_clear_all_cache_button"),
                    ) {
                        if (state.developerMode.isClearingLocalCache) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.DeleteSweep,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(v35.storageClearAllCacheButton, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        item(key = "storage_bottom_spacer", contentType = "spacer") {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StorageBreakdownRow(
    label: String,
    value: String,
    accent: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f),
        ) {
            Canvas(modifier = Modifier.size(7.dp)) {
                drawCircle(color = accent)
            }
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun StorageStatChip(
    label: String,
    accent: Color,
) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.55f))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Canvas(modifier = Modifier.size(6.dp)) {
            drawCircle(color = accent)
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
