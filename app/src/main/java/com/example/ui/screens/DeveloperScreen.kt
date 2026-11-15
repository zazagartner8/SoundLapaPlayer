package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.remote.formatCloudExactSize
import com.example.data.remote.formatCloudStorageHeadline
import com.example.ui.BugReportItemUi
import com.example.ui.MainUiState
import com.example.ui.auth.BiometricAvailability
import com.example.ui.auth.checkBiometricAvailability
import com.example.ui.auth.hasProvisionedDeveloperBiometricKey
import com.example.ui.auth.isAllowedDeveloperEmail
import com.example.ui.auth.launchDeveloperBiometricPrompt
import com.example.ui.components.AccountAvatarImage
import com.example.ui.components.rememberSoundLapaHaptics
import com.example.ui.i18n.LocalAppStrings
import com.example.ui.theme.LiquidCyan
import com.example.ui.theme.LiquidMint
import com.example.ui.theme.LiquidRose
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DeveloperScreen(
    state: MainUiState,
    onClose: () -> Unit,
    onUnlockWithPassword: (String) -> Unit,
    onUnlockWithBiometrics: () -> Unit,
    onLock: () -> Unit,
    onRefreshDashboard: () -> Unit,
    onDeleteReport: (BugReportItemUi) -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onClose)

    val strings = LocalAppStrings.current
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val haptics = rememberSoundLapaHaptics()
    val accent = MaterialTheme.colorScheme.primary

    var passwordInput by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var biometricStatusMessage by remember { mutableStateOf<String?>(null) }
    var pendingDeleteReport by remember { mutableStateOf<BugReportItemUi?>(null) }

    val biometricAvailability = remember(context) {
        checkBiometricAvailability(context)
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
                // Header
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
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (state.developerMode.isUnlocked) {
                        OutlinedButton(
                            onClick = {
                                haptics.tick()
                                onLock()
                            },
                            shape = CircleShape,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.testTag("developer_lock_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Lock,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(strings.settingsDeveloperLockButton)
                        }
                    }
                }

                if (!state.developerMode.isUnlocked) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
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
                                    .padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
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
                                            text = strings.settingsDeveloperLockTitle,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                        )
                                        Text(
                                            text = strings.settingsDeveloperLockHint,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }

                                Button(
                                    onClick = {
                                        haptics.tick()
                                        biometricStatusMessage = null
                                        val signedInEmail = state.cloudSync.email
                                        val signedInUid = state.cloudSync.userId
                                        val activatedDevUid = state.settings.devUnlockedUid
                                        when {
                                            !isAllowedDeveloperEmail(signedInEmail) -> {
                                                biometricStatusMessage = strings.settingsV33.biometricOwnerOnlyHint
                                            }
                                            signedInUid.isNullOrBlank() ||
                                                activatedDevUid != signedInUid ||
                                                !hasProvisionedDeveloperBiometricKey(signedInUid) -> {
                                                biometricStatusMessage = strings.settingsV33.biometricNeedPasswordFirstHint
                                            }
                                            biometricAvailability != BiometricAvailability.AVAILABLE -> {
                                                biometricStatusMessage = strings.settingsDeveloperBiometricUnavailable
                                            }
                                            else -> {
                                                launchDeveloperBiometricPrompt(
                                                    context = context,
                                                    signedInUid = signedInUid,
                                                    signedInEmail = signedInEmail,
                                                    activatedDevUid = activatedDevUid,
                                                    title = strings.settingsDeveloperBiometricPromptTitle,
                                                    subtitle = strings.settingsDeveloperBiometricPromptSubtitle,
                                                    negativeButtonText = strings.settingsDeveloperBiometricFallbackPassword,
                                                    unavailableMessage = strings.settingsDeveloperBiometricUnavailable,
                                                    failedMessage = strings.settingsDeveloperBiometricFailed,
                                                    onAuthenticated = {
                                                        haptics.confirm()
                                                        biometricStatusMessage = null
                                                        onUnlockWithBiometrics()
                                                    },
                                                    onFallbackOrDismiss = {
                                                        // Keep password card visible for immediate fallback
                                                    },
                                                    onError = { msg ->
                                                        biometricStatusMessage = msg
                                                    },
                                                )
                                            }
                                        }
                                    },
                                    shape = RoundedCornerShape(18.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = accent.copy(alpha = 0.18f),
                                        contentColor = accent,
                                    ),
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .border(
                                            width = 1.dp,
                                            color = accent.copy(alpha = 0.45f),
                                            shape = RoundedCornerShape(18.dp),
                                        )
                                        .testTag("developer_biometric_unlock_button"),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Fingerprint,
                                        contentDescription = strings.settingsDeveloperBiometricButton,
                                        modifier = Modifier.size(22.dp),
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        text = strings.settingsDeveloperBiometricButton,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }

                                biometricStatusMessage?.let { msg ->
                                    Text(
                                        text = msg,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = LiquidCyan,
                                        modifier = Modifier.testTag("developer_biometric_status"),
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    HorizontalDivider(
                                        modifier = Modifier.weight(1f),
                                        color = MaterialTheme.colorScheme.outlineVariant,
                                    )
                                    Text(
                                        text = strings.settingsDeveloperOrPasswordDivider,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    HorizontalDivider(
                                        modifier = Modifier.weight(1f),
                                        color = MaterialTheme.colorScheme.outlineVariant,
                                    )
                                }

                                OutlinedTextField(
                                    value = passwordInput,
                                    onValueChange = { passwordInput = it },
                                    label = { Text(strings.settingsDeveloperPasswordLabel) },
                                    singleLine = true,
                                    visualTransformation = if (showPassword) {
                                        VisualTransformation.None
                                    } else {
                                        PasswordVisualTransformation()
                                    },
                                    trailingIcon = {
                                        IconButton(onClick = { showPassword = !showPassword }) {
                                            Icon(
                                                imageVector = if (showPassword) {
                                                    Icons.Rounded.VisibilityOff
                                                } else {
                                                    Icons.Rounded.Visibility
                                                },
                                                contentDescription = null,
                                            )
                                        }
                                    },
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Password,
                                        imeAction = ImeAction.Done,
                                    ),
                                    keyboardActions = KeyboardActions(
                                        onDone = {
                                            focusManager.clearFocus()
                                            if (passwordInput.isNotBlank()) {
                                                onUnlockWithPassword(passwordInput)
                                            }
                                        },
                                    ),
                                    shape = RoundedCornerShape(18.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("settings_developer_password_input"),
                                )

                                if (state.developerMode.wrongPasswordError) {
                                    Text(
                                        text = strings.settingsDeveloperWrongPassword,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = LiquidRose,
                                        modifier = Modifier.testTag("settings_developer_wrong_password"),
                                    )
                                }

                                Button(
                                    onClick = {
                                        focusManager.clearFocus()
                                        haptics.confirm()
                                        onUnlockWithPassword(passwordInput)
                                    },
                                    enabled = passwordInput.isNotBlank(),
                                    shape = CircleShape,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = accent,
                                        contentColor = MaterialTheme.colorScheme.onPrimary,
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("settings_developer_unlock_button"),
                                ) {
                                    Text(strings.settingsDeveloperUnlockButton)
                                }
                            }
                        }
                    }
                } else {
                    val dateFormatter = remember {
                        SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
                    }
                    val storageStats = state.developerMode.cloudStorageStats
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
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        // 1. Cloud Storage Usage Card
                        item(key = "cloud_storage_usage") {
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
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                Text(
                                                    text = strings.cloudStorageQuotaBadge,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                        if (state.developerMode.isLoadingStorageStats) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(18.dp),
                                                strokeWidth = 2.dp,
                                                color = accent,
                                            )
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
                                            label = strings.settingsDeveloperStorageUsedExact(
                                                formatCloudExactSize(
                                                    bytes = storageStats.usedBytes,
                                                    gbUnit = strings.cloudStorageGbUnit,
                                                    mbUnit = strings.cloudStorageMbUnit,
                                                    kbUnit = strings.cloudStorageKbUnit,
                                                    bUnit = strings.cloudStorageBUnit,
                                                ),
                                            ),
                                            accent = LiquidMint,
                                        )
                                        StorageStatChip(
                                            label = strings.settingsDeveloperStorageFreeLabel(
                                                formatCloudExactSize(
                                                    bytes = storageStats.freeBytes,
                                                    gbUnit = strings.cloudStorageGbUnit,
                                                    mbUnit = strings.cloudStorageMbUnit,
                                                    kbUnit = strings.cloudStorageKbUnit,
                                                    bUnit = strings.cloudStorageBUnit,
                                                ),
                                            ),
                                            accent = LiquidCyan,
                                        )
                                        StorageStatChip(
                                            label = strings.settingsDeveloperStorageAccountsLabel(
                                                storageStats.activeUsersCount,
                                            ),
                                            accent = accent,
                                        )
                                        StorageStatChip(
                                            label = strings.settingsDeveloperStorageTracksLabel(
                                                storageStats.totalTracks,
                                            ),
                                            accent = accent,
                                        )
                                        StorageStatChip(
                                            label = strings.settingsDeveloperStoragePlaylistsLabel(
                                                storageStats.totalPlaylists,
                                            ),
                                            accent = accent,
                                        )
                                        StorageStatChip(
                                            label = strings.settingsDeveloperStorageReportsLabel(
                                                storageStats.totalBugReports,
                                            ),
                                            accent = LiquidRose,
                                        )
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

                        // 2. Reports Header & Actions
                        item(key = "reports_header") {
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
                                        .padding(18.dp),
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
                                                text = strings.settingsDeveloperReportsTitle,
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
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

                                    if (state.developerMode.reports.isEmpty() && !state.developerMode.isLoadingReports) {
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
                            items = state.developerMode.reports,
                            key = { it.reportId },
                        ) { report ->
                            val isDeleting = report.reportId in state.developerMode.deletingReportIds
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(
                                        width = 1.dp,
                                        color = MaterialTheme.colorScheme.outlineVariant,
                                        shape = RoundedCornerShape(20.dp),
                                    )
                                    .testTag("developer_report_item_${report.reportId}"),
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
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
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                text = "${dateFormatter.format(Date(report.createdAtMs))} • v${report.appVersion}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        IconButton(
                                            onClick = {
                                                haptics.tick()
                                                pendingDeleteReport = report
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
                                }
                            }
                        }

                        item(key = "developer_bottom_spacer") {
                            Spacer(modifier = Modifier.height(24.dp))
                        }
                    }
                }
            }
        }
    }

    pendingDeleteReport?.let { targetReport ->
        AlertDialog(
            onDismissRequest = { pendingDeleteReport = null },
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

@Composable
private fun StorageStatChip(
    label: String,
    accent: androidx.compose.ui.graphics.Color,
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
