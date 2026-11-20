package com.example.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DataSaverOn
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import com.example.data.local.AudioFocusMode
import com.example.data.remote.AvatarModerationStatus
import com.example.ui.MainUiState
import com.example.ui.StatsBackupUiState
import com.example.ui.components.AccountAvatarImage
import com.example.ui.components.AccountUidCopyRow
import com.example.ui.components.Avatar18PlusWarningBanner
import com.example.ui.components.AvatarCropDialog
import com.example.ui.components.CountryFlagIcon
import com.example.ui.components.StatsBackupRestoreCard
import com.example.ui.components.rememberSoundLapaHaptics
import com.example.ui.formatFileSize
import com.example.ui.i18n.AppLanguage
import com.example.ui.i18n.LocalAppStrings
import com.example.ui.theme.LiquidCyan
import com.example.ui.theme.LiquidMint
import com.example.ui.theme.LiquidRose
import kotlin.math.roundToInt

private fun formatRgbHex(argb: Long): String {
    val rgb = (argb and 0xFFFFFFL).toInt()
    return "#%06X".format(rgb)
}

private fun parseRgbHexOrNull(raw: String): Long? {
    val cleaned = raw.trim().removePrefix("#").trim()
    if (cleaned.length != 6) return null
    val rgb = cleaned.toLongOrNull(16) ?: return null
    return (0xFF000000L or rgb) and 0xFFFFFFFFL
}

private val PresetAccentColors = listOf(
    0xFF8B5CF6L, // Purple (Default)
    0xFF10B981L, // Emerald Green
    0xFF00D2E6L, // Electric Cyan
    0xFFFF5500L, // Flame Orange
    0xFFF43F5EL, // Crimson Rose
    0xFFF59E0BL, // Solar Gold
    0xFF3B82F6L, // Cobalt Blue
    0xFFEC4899L, // Sakura Pink
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    state: MainUiState,
    onClose: () -> Unit,
    onSelectLanguage: (String) -> Unit,
    onSelectAccentColor: (Long) -> Unit,
    onToggleDarkTheme: (Boolean) -> Unit,
    onToggleSmoothTransition: (Boolean) -> Unit,
    onChangeSmoothTransitionDuration: (Int) -> Unit,
    onToggleHaptics: (Boolean) -> Unit = {},
    onChangeHapticIntensity: (Int) -> Unit = {},
    onToggleAutoUpload: (Boolean) -> Unit,
    @Suppress("UNUSED_PARAMETER") onToggleAutoCache: (Boolean) -> Unit = {},
    onToggleDataSaver: (Boolean) -> Unit,
    onToggleBatterySaver: (Boolean) -> Unit = {},
    onChangeAudioFocusMode: (AudioFocusMode) -> Unit = {},
    onClearCache: () -> Unit,
    onImportFromInput: (String, String?) -> Unit,
    onImportFromFile: (Uri, String?) -> Unit,
    onSetAutoUploadFolder: (Uri, String?) -> Unit,
    onClearAutoUploadFolder: () -> Unit,
    onSyncCloudNow: () -> Unit = {},
    onSignOutGoogle: () -> Unit = {},
    onSignInGoogleFromGuest: () -> Unit = {},
    isSigningInGoogleFromGuest: Boolean = false,
    guestGoogleSignInError: String? = null,
    onUpdateProfile: (displayName: String, bio: String, croppedAvatarBitmap: Bitmap?, clearAvatar: Boolean) -> Unit = { _, _, _, _ -> },
    onCancelOwnAvatarRequest: () -> Unit = {},
    onClearProfileStatus: () -> Unit = {},
    onOpenFriendsScreen: () -> Unit = {},
    onOpenDeveloperScreen: () -> Unit = {},
    statsBackupState: StatsBackupUiState = StatsBackupUiState(),
    onRestoreStatsFromUri: (Uri) -> Unit = {},
    onClearStatsRestoreStatus: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onClose)

    val strings = LocalAppStrings.current
    val context = LocalContext.current
    val haptics = rememberSoundLapaHaptics()
    val accent = MaterialTheme.colorScheme.primary

    var importTextInput by remember { mutableStateOf("") }
    var importPlaylistName by remember { mutableStateOf("") }

    var profileNameInput by remember(state.cloudSync.userId, state.cloudSync.displayName) {
        mutableStateOf(state.cloudSync.displayName.orEmpty())
    }
    var profileBioInput by remember(state.cloudSync.userId, state.cloudSync.bio) {
        mutableStateOf(state.cloudSync.bio.orEmpty())
    }
    var pendingCropAvatarUri by remember(state.cloudSync.userId) {
        mutableStateOf<Uri?>(null)
    }
    var croppedAvatarBitmap by remember(state.cloudSync.userId, state.cloudSync.photoUrl) {
        mutableStateOf<Bitmap?>(null)
    }
    var clearProfileAvatar by remember(state.cloudSync.userId, state.cloudSync.photoUrl) {
        mutableStateOf(false)
    }
    var isProfileEditorExpanded by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.cloudSync.avatarBlocked) {
        if (state.cloudSync.avatarBlocked) {
            pendingCropAvatarUri = null
            croppedAvatarBitmap = null
            clearProfileAvatar = true
        }
    }

    LaunchedEffect(state.cloudSync.profileStatusMessage) {
        if (state.cloudSync.profileStatusMessage == "avatar_pending_review") {
            croppedAvatarBitmap = null
            clearProfileAvatar = false
        }
    }

    val avatarPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null && !state.cloudSync.avatarBlocked) {
            pendingCropAvatarUri = uri
            onClearProfileStatus()
        }
    }

    pendingCropAvatarUri?.let { sourceUri ->
        AvatarCropDialog(
            sourceUri = sourceUri,
            accent = accent,
            onDismiss = { pendingCropAvatarUri = null },
            onConfirmCropped = { cropped ->
                croppedAvatarBitmap = cropped
                clearProfileAvatar = false
                pendingCropAvatarUri = null
                onClearProfileStatus()
            },
        )
    }

    val initialHsv = remember {
        val hsv = FloatArray(3)
        AndroidColor.colorToHSV(state.settings.accentColorArgb.toInt(), hsv)
        hsv
    }
    var hue by remember { mutableFloatStateOf(initialHsv[0]) }
    var saturation by remember { mutableFloatStateOf(initialHsv[1].coerceIn(0f, 1f)) }
    var brightness by remember { mutableFloatStateOf(initialHsv[2].coerceIn(0f, 1f)) }
    var hexInput by remember { mutableStateOf(formatRgbHex(state.settings.accentColorArgb)) }

    LaunchedEffect(state.settings.accentColorArgb) {
        val formatted = formatRgbHex(state.settings.accentColorArgb)
        if (!hexInput.equals(formatted, ignoreCase = true) && parseRgbHexOrNull(hexInput) != state.settings.accentColorArgb) {
            hexInput = formatted
        }
    }

    fun applyHsvColor() {
        val argb = Color(
            AndroidColor.HSVToColor(floatArrayOf(hue, saturation, brightness)),
        ).toArgb().toLong() and 0xFFFFFFFFL
        hexInput = formatRgbHex(argb)
        onSelectAccentColor(argb)
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { treeUri ->
        if (treeUri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            val folderName = DocumentFile.fromTreeUri(context, treeUri)?.name
            onSetAutoUploadFolder(treeUri, folderName)
        }
    }

    val playlistFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            onImportFromFile(uri, importPlaylistName.takeIf { it.isNotBlank() })
        }
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
                            .testTag("settings_back_button"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = strings.close,
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Text(
                        text = strings.settingsTitle,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // 0. Google Account / Guest Mode & Profile Customization
                    item(key = "cloud_account_section") {
                        SettingsCard(
                            icon = Icons.Rounded.AccountCircle,
                            title = if (state.cloudSync.isGuest) {
                                strings.settingsGuestAccountTitle
                            } else {
                                strings.settingsCloudAccountSection
                            },
                            accent = accent,
                        ) {
                            if (state.cloudSync.isGuest) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    AccountAvatarImage(
                                        avatarUrlOrData = null,
                                        contentDescription = strings.settingsGuestAccountTitle,
                                        isGuest = true,
                                        size = 56.dp,
                                    )
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Text(
                                            text = strings.settingsGuestAccountTitle,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                        Text(
                                            text = strings.settingsGuestAccountSubtitle,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }

                                val effectiveError = guestGoogleSignInError ?: state.cloudSync.statusMessage
                                effectiveError?.let { err ->
                                    Text(
                                        text = err,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = LiquidRose,
                                    )
                                }

                                Button(
                                    onClick = {
                                        haptics.confirm()
                                        onSignInGoogleFromGuest()
                                    },
                                    enabled = !isSigningInGoogleFromGuest,
                                    shape = CircleShape,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = accent,
                                        contentColor = MaterialTheme.colorScheme.onPrimary,
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("settings_guest_google_sign_in_button"),
                                ) {
                                    if (isSigningInGoogleFromGuest) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.onPrimary,
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = strings.stats.guestSignInLoadingLabel,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    } else {
                                        Text(
                                            text = strings.settingsSignInGoogleAndSyncButton,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }

                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(MaterialTheme.colorScheme.background.copy(alpha = 0.45f))
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Lock,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            text = strings.settingsProfileEditTitle,
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                        Text(
                                            text = strings.settingsProfileGuestLockedHint,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            } else {
                                val accountLabel = state.cloudSync.displayName?.takeIf { it.isNotBlank() }
                                    ?: state.cloudSync.email?.takeIf { it.isNotBlank() }
                                    ?: state.cloudSync.userId.orEmpty()
                                val localCroppedPreview = croppedAvatarBitmap?.takeIf {
                                    !it.isRecycled && !clearProfileAvatar && !state.cloudSync.avatarBlocked
                                }
                                val previewAvatarModel = when {
                                    state.cloudSync.avatarBlocked -> null
                                    clearProfileAvatar -> null
                                    else -> state.cloudSync.photoUrl
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    if (localCroppedPreview != null) {
                                        Image(
                                            bitmap = localCroppedPreview.asImageBitmap(),
                                            contentDescription = accountLabel,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .size(56.dp)
                                                .clip(CircleShape)
                                                .border(1.5.dp, accent.copy(alpha = 0.7f), CircleShape)
                                                .testTag("settings_account_avatar"),
                                        )
                                    } else {
                                        AccountAvatarImage(
                                            avatarUrlOrData = previewAvatarModel,
                                            contentDescription = accountLabel,
                                            isGuest = false,
                                            size = 56.dp,
                                            modifier = Modifier.testTag("settings_account_avatar"),
                                        )
                                    }
                                    val compactPlatformStyle = remember { PlatformTextStyle(includeFontPadding = false) }
                                    val compactLineHeightStyle = remember {
                                        LineHeightStyle(
                                            alignment = LineHeightStyle.Alignment.Center,
                                            trim = LineHeightStyle.Trim.Both,
                                        )
                                    }
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("settings_account_metadata_column"),
                                        verticalArrangement = Arrangement.spacedBy(0.dp),
                                    ) {
                                        if (accountLabel.isNotBlank()) {
                                            Text(
                                                text = strings.social.settingsSignedInAsCaption,
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    lineHeight = 12.sp,
                                                    platformStyle = compactPlatformStyle,
                                                    lineHeightStyle = compactLineHeightStyle,
                                                ),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                            )
                                            Text(
                                                text = accountLabel,
                                                style = MaterialTheme.typography.titleSmall.copy(
                                                    lineHeight = 16.sp,
                                                    platformStyle = compactPlatformStyle,
                                                    lineHeightStyle = compactLineHeightStyle,
                                                ),
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        val handle = state.cloudSync.username?.takeIf { it.isNotBlank() }
                                        if (handle != null || state.cloudSync.isVerifiedDeveloper || state.cloudSync.isVerifiedModerator) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                modifier = Modifier.fillMaxWidth(),
                                            ) {
                                                handle?.let { h ->
                                                    Text(
                                                        text = "${strings.social.settingsUsernamePrefixLabel}: @$h",
                                                        style = MaterialTheme.typography.labelMedium.copy(
                                                            lineHeight = 13.sp,
                                                            platformStyle = compactPlatformStyle,
                                                            lineHeightStyle = compactLineHeightStyle,
                                                        ),
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = accent,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        modifier = Modifier.weight(1f, fill = false),
                                                    )
                                                }
                                                if (state.cloudSync.isVerifiedDeveloper) {
                                                    DeveloperVerifiedPill(
                                                        label = strings.social.developerBadge,
                                                        accent = accent,
                                                    )
                                                }
                                                if (state.cloudSync.isVerifiedModerator) {
                                                    ModeratorVerifiedPill(
                                                        label = strings.v35.moderatorBadge,
                                                    )
                                                }
                                            }
                                        }
                                        state.cloudSync.email?.takeIf { it.isNotBlank() && it != accountLabel }?.let { email ->
                                            Text(
                                                text = email,
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    lineHeight = 14.sp,
                                                    platformStyle = compactPlatformStyle,
                                                    lineHeightStyle = compactLineHeightStyle,
                                                ),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        state.cloudSync.bio?.takeIf { it.isNotBlank() }?.let { bio ->
                                            Text(
                                                text = bio,
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    lineHeight = 14.sp,
                                                    platformStyle = compactPlatformStyle,
                                                    lineHeightStyle = compactLineHeightStyle,
                                                ),
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        Text(
                                            text = strings.settingsCloudSyncStats(
                                                state.cloudSync.syncedTracksCount,
                                                state.cloudSync.syncedPlaylistsCount,
                                            ),
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                lineHeight = 14.sp,
                                                platformStyle = compactPlatformStyle,
                                                lineHeightStyle = compactLineHeightStyle,
                                            ),
                                            color = LiquidMint,
                                        )
                                    }
                                }

                                state.cloudSync.accountUid?.takeIf { it.isNotBlank() }?.let { uid ->
                                    AccountUidCopyRow(
                                        accountUid = uid,
                                        accent = accent,
                                    )
                                }

                                OutlinedButton(
                                    onClick = {
                                        haptics.tick()
                                        onOpenFriendsScreen()
                                    },
                                    shape = CircleShape,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("settings_open_friends_button"),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Group,
                                        contentDescription = null,
                                        tint = accent,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = strings.social.friendsEntryButton,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }

                                state.cloudSync.statusMessage?.let { err ->
                                    Text(
                                        text = err,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = LiquidRose,
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Button(
                                        onClick = {
                                            haptics.confirm()
                                            onSyncCloudNow()
                                        },
                                        enabled = !state.cloudSync.isSyncing,
                                        shape = CircleShape,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = accent,
                                            contentColor = MaterialTheme.colorScheme.onPrimary,
                                        ),
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("settings_cloud_sync_button"),
                                    ) {
                                        if (state.cloudSync.isSyncing) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(16.dp),
                                                strokeWidth = 2.dp,
                                                color = MaterialTheme.colorScheme.onPrimary,
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                text = strings.settingsCloudSyncingLabel,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        } else {
                                            Icon(
                                                imageVector = Icons.Rounded.Sync,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp),
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                text = strings.settingsCloudSyncNowButton,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            haptics.tick()
                                            onSignOutGoogle()
                                        },
                                        shape = CircleShape,
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                                        modifier = Modifier.testTag("settings_google_sign_out_button"),
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Rounded.Logout,
                                            contentDescription = null,
                                            tint = LiquidRose,
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = strings.settingsCloudSignOutButton,
                                            color = LiquidRose,
                                            maxLines = 1,
                                            softWrap = false,
                                        )
                                    }
                                }

                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(MaterialTheme.colorScheme.background.copy(alpha = 0.42f))
                                        .clickable {
                                            haptics.tick()
                                            isProfileEditorExpanded = !isProfileEditorExpanded
                                        }
                                        .padding(horizontal = 12.dp, vertical = 11.dp)
                                        .testTag("settings_profile_expand_toggle"),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Edit,
                                            contentDescription = null,
                                            tint = accent,
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Text(
                                            text = strings.settingsProfileEditTitle,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Icon(
                                        imageVector = if (isProfileEditorExpanded) {
                                            Icons.Rounded.ExpandLess
                                        } else {
                                            Icons.Rounded.ExpandMore
                                        },
                                        contentDescription = strings.settingsProfileEditTitle,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }

                                AnimatedVisibility(
                                    visible = isProfileEditorExpanded,
                                    enter = expandVertically(
                                        animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow),
                                    ) + fadeIn(tween(220)),
                                    exit = shrinkVertically(
                                        animationSpec = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMedium),
                                    ) + fadeOut(tween(160)),
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .animateContentSize(
                                                animationSpec = spring(
                                                    dampingRatio = 0.85f,
                                                    stiffness = Spring.StiffnessMediumLow,
                                                ),
                                            ),
                                        verticalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        if (state.cloudSync.avatarBlocked) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(16.dp))
                                                    .background(LiquidRose.copy(alpha = 0.14f))
                                                    .border(1.dp, LiquidRose.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
                                                    .padding(12.dp)
                                                    .testTag("settings_profile_avatar_blocked_banner"),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.Block,
                                                    contentDescription = null,
                                                    tint = LiquidRose,
                                                    modifier = Modifier.size(20.dp),
                                                )
                                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                    Text(
                                                        text = strings.settingsProfileAvatarBlockedTitle,
                                                        style = MaterialTheme.typography.labelLarge,
                                                        fontWeight = FontWeight.Bold,
                                                        color = LiquidRose,
                                                    )
                                                    Text(
                                                        text = strings.settingsProfileAvatarBlockedNotice,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurface,
                                                    )
                                                }
                                            }
                                        } else {
                                            Avatar18PlusWarningBanner()

                                            val ownAvatarReq = state.cloudSync.ownAvatarRequest
                                            val ownReqStatus = ownAvatarReq?.parsedStatus()
                                            if (ownAvatarReq != null && ownReqStatus == AvatarModerationStatus.PENDING) {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clip(RoundedCornerShape(16.dp))
                                                        .background(LiquidCyan.copy(alpha = 0.12f))
                                                        .border(1.dp, LiquidCyan.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
                                                        .padding(12.dp)
                                                        .testTag("settings_avatar_pending_banner"),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                                ) {
                                                    AccountAvatarImage(
                                                        avatarUrlOrData = ownAvatarReq.proposedPhotoUrl,
                                                        contentDescription = strings.v35.avatarPendingUserTitle,
                                                        isGuest = false,
                                                        size = 44.dp,
                                                        accent = LiquidCyan,
                                                    )
                                                    Column(
                                                        modifier = Modifier.weight(1f),
                                                        verticalArrangement = Arrangement.spacedBy(2.dp),
                                                    ) {
                                                        Text(
                                                            text = strings.v35.avatarPendingUserTitle,
                                                            style = MaterialTheme.typography.labelLarge,
                                                            fontWeight = FontWeight.Bold,
                                                            color = LiquidCyan,
                                                        )
                                                        Text(
                                                            text = strings.v35.avatarPendingUserBody,
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.onSurface,
                                                        )
                                                        TextButton(
                                                            onClick = {
                                                                haptics.tick()
                                                                onCancelOwnAvatarRequest()
                                                            },
                                                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                                                            modifier = Modifier.testTag("settings_cancel_avatar_request_button"),
                                                        ) {
                                                            Text(
                                                                text = strings.v35.avatarPendingCancelButton,
                                                                color = LiquidRose,
                                                                style = MaterialTheme.typography.labelMedium,
                                                                fontWeight = FontWeight.SemiBold,
                                                            )
                                                        }
                                                    }
                                                }
                                            } else if (ownAvatarReq != null && ownReqStatus == AvatarModerationStatus.REJECTED) {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clip(RoundedCornerShape(16.dp))
                                                        .background(LiquidRose.copy(alpha = 0.12f))
                                                        .border(1.dp, LiquidRose.copy(alpha = 0.42f), RoundedCornerShape(16.dp))
                                                        .padding(12.dp)
                                                        .testTag("settings_avatar_rejected_banner"),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Rounded.Block,
                                                        contentDescription = null,
                                                        tint = LiquidRose,
                                                        modifier = Modifier.size(20.dp),
                                                    )
                                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                        Text(
                                                            text = strings.v35.avatarRejectedUserTitle,
                                                            style = MaterialTheme.typography.labelLarge,
                                                            fontWeight = FontWeight.Bold,
                                                            color = LiquidRose,
                                                        )
                                                        Text(
                                                            text = ownAvatarReq.rejectReason?.takeIf { it.isNotBlank() }
                                                                ?: strings.v35.avatarRejectedUserFallback,
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.onSurface,
                                                        )
                                                    }
                                                }
                                            }

                                            FlowRow(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                            ) {
                                                OutlinedButton(
                                                    onClick = {
                                                        haptics.tick()
                                                        avatarPickerLauncher.launch(
                                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                                        )
                                                    },
                                                    enabled = !state.cloudSync.isSavingProfile,
                                                    shape = CircleShape,
                                                    modifier = Modifier.testTag("settings_profile_avatar_button"),
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Rounded.PhotoCamera,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(16.dp),
                                                    )
                                                    Spacer(Modifier.width(6.dp))
                                                    Text(
                                                        text = strings.settingsProfileChangeAvatarButton,
                                                        maxLines = 1,
                                                        softWrap = false,
                                                    )
                                                }
                                                if (croppedAvatarBitmap != null || (!clearProfileAvatar && !state.cloudSync.photoUrl.isNullOrBlank())) {
                                                    OutlinedButton(
                                                        onClick = {
                                                            haptics.tick()
                                                            croppedAvatarBitmap = null
                                                            clearProfileAvatar = true
                                                            onClearProfileStatus()
                                                        },
                                                        enabled = !state.cloudSync.isSavingProfile,
                                                        shape = CircleShape,
                                                        modifier = Modifier.testTag("settings_profile_clear_avatar_button"),
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Rounded.DeleteOutline,
                                                            contentDescription = null,
                                                            tint = LiquidRose,
                                                            modifier = Modifier.size(16.dp),
                                                        )
                                                        Spacer(Modifier.width(6.dp))
                                                        Text(
                                                            text = strings.settingsV33.removeAvatarButton,
                                                            color = LiquidRose,
                                                            maxLines = 1,
                                                            softWrap = false,
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        OutlinedTextField(
                                            value = profileNameInput,
                                            onValueChange = {
                                                profileNameInput = it
                                                onClearProfileStatus()
                                            },
                                            label = { Text(strings.settingsProfileNameLabel) },
                                            singleLine = true,
                                            shape = RoundedCornerShape(18.dp),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .testTag("settings_profile_name_input"),
                                        )

                                        OutlinedTextField(
                                            value = profileBioInput,
                                            onValueChange = {
                                                profileBioInput = it.take(300)
                                                onClearProfileStatus()
                                            },
                                            label = { Text(strings.settingsProfileBioLabel) },
                                            placeholder = { Text(strings.settingsProfileBioPlaceholder) },
                                            minLines = 2,
                                            maxLines = 4,
                                            shape = RoundedCornerShape(18.dp),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .testTag("settings_profile_bio_input"),
                                        )

                                        state.cloudSync.profileStatusMessage?.let { status ->
                                            val resolvedStatusText = when (status) {
                                                "✓" -> strings.settingsProfileSavedSuccess
                                                "avatar_pending_review" -> strings.v35.avatarSubmittedForReviewNotice
                                                "moderating" -> strings.settingsProfileModeratingLabel
                                                "blocked_18plus" -> strings.settingsProfileAvatarBlockedNotice
                                                com.example.data.remote.AvatarModerationService.ERR_APPCHECK ->
                                                    strings.settingsV33.moderationAppCheckError
                                                com.example.data.remote.AvatarModerationService.ERR_NETWORK ->
                                                    strings.settingsV33.moderationNetworkError
                                                com.example.data.remote.AvatarModerationService.ERR_TIMEOUT ->
                                                    strings.settingsV33.moderationTimeoutError
                                                com.example.data.remote.AvatarModerationService.ERR_GENERIC ->
                                                    strings.settingsProfileModerationErrorFallback
                                                else -> status
                                            }
                                            val statusColor = when (status) {
                                                "✓" -> LiquidMint
                                                "avatar_pending_review" -> LiquidCyan
                                                "moderating" -> accent
                                                else -> LiquidRose
                                            }
                                            Text(
                                                text = resolvedStatusText,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = statusColor,
                                                fontWeight = FontWeight.SemiBold,
                                                modifier = Modifier.testTag("settings_profile_status"),
                                            )
                                        }

                                        Button(
                                            onClick = {
                                                haptics.confirm()
                                                val bitmapToUpload = if (state.cloudSync.avatarBlocked || clearProfileAvatar) {
                                                    null
                                                } else {
                                                    croppedAvatarBitmap?.takeIf { !it.isRecycled }?.let { src ->
                                                        src.copy(src.config ?: Bitmap.Config.ARGB_8888, false)
                                                    }
                                                }
                                                onUpdateProfile(
                                                    profileNameInput,
                                                    profileBioInput,
                                                    bitmapToUpload,
                                                    clearProfileAvatar || state.cloudSync.avatarBlocked,
                                                )
                                            },
                                            enabled = !state.cloudSync.isSavingProfile,
                                            shape = CircleShape,
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = accent,
                                                contentColor = MaterialTheme.colorScheme.onPrimary,
                                            ),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .testTag("settings_profile_save_button"),
                                        ) {
                                            if (state.cloudSync.isSavingProfile) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(16.dp),
                                                    strokeWidth = 2.dp,
                                                    color = MaterialTheme.colorScheme.onPrimary,
                                                )
                                                Spacer(Modifier.width(6.dp))
                                                Text(
                                                    text = if (state.cloudSync.profileStatusMessage == "moderating") {
                                                        strings.settingsProfileModeratingLabel
                                                    } else {
                                                        strings.settingsProfileSavingLabel
                                                    },
                                                )
                                            } else {
                                                Text(strings.settingsProfileSaveButton)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 1. Language Section with iOS-style flag emojis
                    item(key = "lang_section") {
                        SettingsCard(
                            icon = Icons.Rounded.Translate,
                            title = strings.settingsLanguageSection,
                            accent = accent,
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                AppLanguage.entries.forEach { lang ->
                                    val selected = state.settings.appLanguage == lang.code
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(20.dp))
                                            .background(
                                                if (selected) accent.copy(alpha = 0.20f)
                                                else MaterialTheme.colorScheme.background.copy(alpha = 0.55f),
                                            )
                                            .border(
                                                width = if (selected) 1.5.dp else 1.dp,
                                                color = if (selected) accent else MaterialTheme.colorScheme.outlineVariant,
                                                shape = RoundedCornerShape(20.dp),
                                            )
                                            .clickable {
                                                haptics.tick()
                                                onSelectLanguage(lang.code)
                                            }
                                            .padding(vertical = 12.dp, horizontal = 8.dp)
                                            .testTag("lang_option_${lang.code}"),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        CountryFlagIcon(
                                            languageCode = lang.code,
                                            width = 36.dp,
                                            height = 25.dp,
                                        )
                                        Text(
                                            text = lang.nativeName,
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (selected) accent else MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 2. Theme Mode Buttons + Custom Accent Color Studio
                    item(key = "appearance_section") {
                        SettingsCard(
                            icon = Icons.Rounded.Palette,
                            title = strings.settingsAppearanceSection,
                            accent = accent,
                        ) {
                            // Two explicit theme buttons: Light vs Dark
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    val isLight = !state.settings.darkLiquidGlass
                                    val isDark = state.settings.darkLiquidGlass

                                    ThemeChoiceButton(
                                        selected = isLight,
                                        icon = Icons.Rounded.LightMode,
                                        label = strings.settingsLightThemeButton,
                                        accent = accent,
                                        testTag = "settings_light_theme_button",
                                        onClick = {
                                            haptics.tick()
                                            onToggleDarkTheme(false)
                                        },
                                        modifier = Modifier.weight(1f),
                                    )
                                    ThemeChoiceButton(
                                        selected = isDark,
                                        icon = Icons.Rounded.DarkMode,
                                        label = strings.settingsDarkThemeButton,
                                        accent = accent,
                                        testTag = "settings_dark_theme_button",
                                        onClick = {
                                            haptics.tick()
                                            onToggleDarkTheme(true)
                                        },
                                        modifier = Modifier.weight(1f),
                                    )
                                }

                                Text(
                                    text = strings.settingsDarkRecommendedHint,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.50f),
                                    modifier = Modifier.padding(start = 4.dp),
                                )
                            }

                            // Live Preview Strip
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(
                                        Brush.horizontalGradient(
                                            listOf(
                                                accent.copy(alpha = 0.28f),
                                                accent.copy(alpha = 0.08f),
                                            ),
                                        ),
                                    )
                                    .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
                                    .padding(14.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = strings.settingsPreviewLabel,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = accent,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = strings.settingsAccentColorTitle,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .clip(CircleShape)
                                            .background(accent)
                                            .padding(horizontal = 14.dp, vertical = 9.dp)
                                            .testTag("settings_preview_listen_pill"),
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.PlayArrow,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onPrimary,
                                                modifier = Modifier.size(16.dp),
                                            )
                                            Text(
                                                text = strings.settingsPreviewButton,
                                                style = MaterialTheme.typography.labelLarge,
                                                color = MaterialTheme.colorScheme.onPrimary,
                                                maxLines = 1,
                                                softWrap = false,
                                            )
                                        }
                                    }
                                }
                            }

                            // Preset Swatches
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                PresetAccentColors.forEach { argb ->
                                    val swatchColor = Color(argb)
                                    val selected = state.settings.accentColorArgb == argb
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(CircleShape)
                                            .background(swatchColor)
                                            .border(
                                                width = if (selected) 2.5.dp else 1.dp,
                                                color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                                                shape = CircleShape,
                                            )
                                            .clickable {
                                                haptics.tick()
                                                val hsv = FloatArray(3)
                                                AndroidColor.colorToHSV(argb.toInt(), hsv)
                                                hue = hsv[0]
                                                saturation = hsv[1].coerceIn(0f, 1f)
                                                brightness = hsv[2].coerceIn(0f, 1f)
                                                hexInput = formatRgbHex(argb)
                                                onSelectAccentColor(argb)
                                            }
                                            .testTag("color_swatch_$argb"),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (selected) {
                                            Icon(
                                                imageVector = Icons.Rounded.Check,
                                                contentDescription = null,
                                                tint = if (swatchColor == Color(0xFF00D2E6L) || swatchColor == Color(0xFFF59E0BL)) {
                                                    Color.Black
                                                } else {
                                                    Color.White
                                                },
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                    }
                                }
                            }

                            // Custom HSV Mixer
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = strings.settingsCustomHueLabel,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Box(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(10.dp)
                                            .clip(CircleShape)
                                            .background(
                                                Brush.horizontalGradient(
                                                    listOf(
                                                        Color.Red,
                                                        Color.Yellow,
                                                        Color.Green,
                                                        Color.Cyan,
                                                        Color.Blue,
                                                        Color.Magenta,
                                                        Color.Red,
                                                    ),
                                                ),
                                            ),
                                    )
                                    Slider(
                                        value = hue,
                                        onValueChange = { nextHue ->
                                            hue = nextHue
                                            applyHsvColor()
                                        },
                                        valueRange = 0f..360f,
                                        colors = SliderDefaults.colors(
                                            thumbColor = accent,
                                            activeTrackColor = Color.Transparent,
                                            inactiveTrackColor = Color.Transparent,
                                        ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("custom_hue_slider"),
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = strings.settingsCustomSaturationLabel,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Slider(
                                            value = saturation,
                                            onValueChange = { nextSat ->
                                                saturation = nextSat
                                                applyHsvColor()
                                            },
                                            valueRange = 0f..1f,
                                            colors = SliderDefaults.colors(
                                                thumbColor = accent,
                                                activeTrackColor = accent,
                                            ),
                                        )
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = strings.settingsCustomBrightnessLabel,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Slider(
                                            value = brightness,
                                            onValueChange = { nextVal ->
                                                brightness = nextVal
                                                applyHsvColor()
                                            },
                                            valueRange = 0f..1f,
                                            colors = SliderDefaults.colors(
                                                thumbColor = accent,
                                                activeTrackColor = accent,
                                            ),
                                        )
                                    }
                                }

                                val parsedHex = parseRgbHexOrNull(hexInput)
                                val hasInvalidHex = hexInput.removePrefix("#").trim().isNotEmpty() && parsedHex == null
                                OutlinedTextField(
                                    value = hexInput,
                                    onValueChange = { raw ->
                                        val cleaned = raw.filter { ch ->
                                            ch == '#' || ch in '0'..'9' || ch in 'a'..'f' || ch in 'A'..'F'
                                        }.take(7).uppercase()
                                        hexInput = if (cleaned.startsWith("#")) cleaned else "#$cleaned".take(7)
                                        parseRgbHexOrNull(hexInput)?.let { nextArgb ->
                                            val hsv = FloatArray(3)
                                            AndroidColor.colorToHSV(nextArgb.toInt(), hsv)
                                            hue = hsv[0]
                                            saturation = hsv[1].coerceIn(0f, 1f)
                                            brightness = hsv[2].coerceIn(0f, 1f)
                                            onSelectAccentColor(nextArgb)
                                        }
                                    },
                                    label = { Text(strings.v34.customHexLabel) },
                                    isError = hasInvalidHex,
                                    supportingText = if (hasInvalidHex) {
                                        { Text(strings.v34.customHexInvalidHint) }
                                    } else null,
                                    leadingIcon = {
                                        Box(
                                            modifier = Modifier
                                                .size(22.dp)
                                                .clip(CircleShape)
                                                .background(Color(parsedHex ?: state.settings.accentColorArgb))
                                                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                                        )
                                    },
                                    singleLine = true,
                                    shape = RoundedCornerShape(18.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 4.dp)
                                        .testTag("custom_hex_input"),
                                )
                            }
                        }
                    }

                    // 3. Smooth Transitions (Crossfade) & Local Files Card
                    item(key = "playback_section") {
                        SettingsCard(
                            icon = Icons.Rounded.GraphicEq,
                            title = strings.settingsPlaybackSection,
                            accent = accent,
                        ) {
                            // Smooth transition toggle + duration slider
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                    Text(
                                        text = strings.settingsSmoothTransitionTitle,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = strings.settingsSmoothTransitionSubtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Switch(
                                    checked = state.settings.smoothTransitionEnabled,
                                    onCheckedChange = {
                                        haptics.tick()
                                        onToggleSmoothTransition(it)
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                        checkedTrackColor = accent,
                                    ),
                                    modifier = Modifier.testTag("settings_smooth_transition_switch"),
                                )
                            }

                            AnimatedVisibility(visible = state.settings.smoothTransitionEnabled) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        text = strings.settingsSmoothTransitionDurationLabel(
                                            state.settings.smoothTransitionDurationSec,
                                        ),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = accent,
                                    )
                                    Slider(
                                        value = state.settings.smoothTransitionDurationSec.toFloat(),
                                        onValueChange = { value ->
                                            val rounded = value.toInt().coerceIn(1, 10)
                                            if (rounded != state.settings.smoothTransitionDurationSec) {
                                                haptics.tick()
                                                onChangeSmoothTransitionDuration(rounded)
                                            }
                                        },
                                        valueRange = 1f..10f,
                                        steps = 8,
                                        colors = SliderDefaults.colors(
                                            thumbColor = accent,
                                            activeTrackColor = accent,
                                        ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("settings_smooth_transition_slider"),
                                    )
                                }
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                    Text(
                                        text = strings.settingsAutoUploadTitle,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = state.settings.autoUploadFolderName
                                            ?.let { strings.folderLinkedPrefix(it) }
                                            ?: strings.autoUploadFolderHint,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Switch(
                                    checked = state.settings.autoUploadEnabled,
                                    onCheckedChange = {
                                        haptics.tick()
                                        onToggleAutoUpload(it)
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                        checkedTrackColor = accent,
                                    ),
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        haptics.tick()
                                        folderPickerLauncher.launch(null)
                                    },
                                    shape = CircleShape,
                                ) {
                                    Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(strings.pickFolderButton)
                                }
                                if (state.settings.autoUploadTreeUri != null) {
                                    TextButton(onClick = onClearAutoUploadFolder) {
                                        Text(strings.unlinkButton, color = LiquidRose)
                                    }
                                }
                            }
                        }
                    }

                    // 3b. Standalone Haptic Feedback Card
                    item(key = "haptics_section") {
                        SettingsCard(
                            icon = Icons.Rounded.Vibration,
                            title = strings.settingsHapticsSection,
                            accent = accent,
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                    Text(
                                        text = strings.settingsHapticsTitle,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = strings.settingsHapticsSubtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Switch(
                                    checked = state.settings.hapticsEnabled,
                                    onCheckedChange = { enabled ->
                                        onToggleHaptics(enabled)
                                        if (enabled) {
                                            haptics.previewIntensity(state.settings.hapticIntensity)
                                        }
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                        checkedTrackColor = accent,
                                    ),
                                    modifier = Modifier.testTag("settings_haptics_switch"),
                                )
                            }

                            AnimatedVisibility(visible = state.settings.hapticsEnabled) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        text = strings.settingsHapticsIntensityLabel(
                                            state.settings.hapticIntensity,
                                        ),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = accent,
                                    )
                                    Slider(
                                        value = state.settings.hapticIntensity.toFloat(),
                                        onValueChange = { value ->
                                            val snapped = (((value / 10f).roundToInt()) * 10).coerceIn(10, 100)
                                            if (snapped != state.settings.hapticIntensity) {
                                                haptics.previewIntensity(snapped)
                                                onChangeHapticIntensity(snapped)
                                            }
                                        },
                                        valueRange = 10f..100f,
                                        steps = 8,
                                        colors = SliderDefaults.colors(
                                            thumbColor = accent,
                                            activeTrackColor = accent,
                                        ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("settings_haptics_intensity_slider"),
                                    )
                                }
                            }
                        }
                    }

                    // 3c. Standalone Data Saver Card
                    item(key = "data_saver_section") {
                        SettingsCard(
                            icon = Icons.Rounded.DataSaverOn,
                            title = strings.settingsDataSaverSection,
                            accent = accent,
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                    Text(
                                        text = strings.settingsDataSaverTitle,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = strings.settingsDataSaverSubtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Switch(
                                    checked = state.settings.dataSaverEnabled,
                                    onCheckedChange = {
                                        haptics.tick()
                                        onToggleDataSaver(it)
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                        checkedTrackColor = accent,
                                    ),
                                    modifier = Modifier.testTag("settings_data_saver_switch"),
                                )
                            }
                        }
                    }

                    // 3d. Standalone Battery Saver Card
                    item(key = "battery_saver_section") {
                        SettingsCard(
                            icon = Icons.Rounded.BatterySaver,
                            title = strings.settingsV33.batterySaverSection,
                            accent = accent,
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                    Text(
                                        text = strings.settingsV33.batterySaverTitle,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = strings.settingsV33.batterySaverSubtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Switch(
                                    checked = state.settings.batterySaverEnabled,
                                    onCheckedChange = {
                                        haptics.tick()
                                        onToggleBatterySaver(it)
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                        checkedTrackColor = accent,
                                    ),
                                    modifier = Modifier.testTag("settings_battery_saver_switch"),
                                )
                            }
                        }
                    }

                    // 3e. Audio Focus Priority Over Other Apps Card
                    item(key = "audio_focus_section") {
                        SettingsCard(
                            icon = Icons.Rounded.VolumeUp,
                            title = strings.settingsV33.audioFocusSection,
                            accent = accent,
                        ) {
                            Text(
                                text = strings.settingsV33.audioFocusSubtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            val modes = listOf(
                                Triple(
                                    AudioFocusMode.PAUSE,
                                    strings.settingsV33.audioFocusPauseTitle,
                                    strings.settingsV33.audioFocusPauseDesc,
                                ),
                                Triple(
                                    AudioFocusMode.DUCK,
                                    strings.settingsV33.audioFocusDuckTitle,
                                    strings.settingsV33.audioFocusDuckDesc,
                                ),
                                Triple(
                                    AudioFocusMode.MIX,
                                    strings.settingsV33.audioFocusMixTitle,
                                    strings.settingsV33.audioFocusMixDesc,
                                ),
                            )

                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                modes.forEach { (mode, title, desc) ->
                                    val selected = state.settings.audioFocusMode == mode
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(18.dp))
                                            .background(
                                                if (selected) accent.copy(alpha = 0.18f)
                                                else MaterialTheme.colorScheme.background.copy(alpha = 0.50f),
                                            )
                                            .border(
                                                width = if (selected) 1.5.dp else 1.dp,
                                                color = if (selected) accent else MaterialTheme.colorScheme.outlineVariant,
                                                shape = RoundedCornerShape(18.dp),
                                            )
                                            .clickable {
                                                haptics.tick()
                                                onChangeAudioFocusMode(mode)
                                            }
                                            .padding(horizontal = 14.dp, vertical = 12.dp)
                                            .testTag("audio_focus_${mode.name.lowercase()}"),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        Column(
                                            modifier = Modifier.weight(1f),
                                            verticalArrangement = Arrangement.spacedBy(2.dp),
                                        ) {
                                            Text(
                                                text = title,
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                                                color = if (selected) accent else MaterialTheme.colorScheme.onSurface,
                                            )
                                            Text(
                                                text = desc,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        if (selected) {
                                            Icon(
                                                imageVector = Icons.Rounded.Check,
                                                contentDescription = null,
                                                tint = accent,
                                                modifier = Modifier.size(20.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 4. Storage & Cache Management Card
                    item(key = "storage_section") {
                        SettingsCard(
                            icon = Icons.Rounded.DeleteSweep,
                            title = strings.settingsStorageSection,
                            accent = accent,
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f).padding(end = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        text = strings.settingsCacheSizeLabel(formatFileSize(state.cacheSizeBytes)),
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    state.cacheStatusMessage?.let { freed ->
                                        Text(
                                            text = strings.settingsCacheClearedResult(freed),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = LiquidMint,
                                        )
                                    }
                                }

                                OutlinedButton(
                                    onClick = {
                                        haptics.confirm()
                                        onClearCache()
                                    },
                                    enabled = !state.isClearingCache,
                                    shape = CircleShape,
                                    modifier = Modifier.testTag("settings_clear_cache_button"),
                                ) {
                                    if (state.isClearingCache) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                            color = accent,
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(strings.settingsClearingCacheLabel)
                                    } else {
                                        Icon(
                                            imageVector = Icons.Rounded.DeleteSweep,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(strings.settingsClearCacheButton)
                                    }
                                }
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                            StatsBackupRestoreCard(
                                backupState = statsBackupState,
                                accent = accent,
                                onRestoreBackupUri = onRestoreStatsFromUri,
                                onClearRestoreStatus = onClearStatsRestoreStatus,
                            )
                        }
                    }

                    // 5. External Playlist & Track Import Card (Spotify, YouTube, SoundCloud, CSV/M3U)
                    item(key = "import_section") {
                        SettingsCard(
                            icon = Icons.Rounded.PlaylistAdd,
                            title = strings.settingsImportSection,
                            accent = accent,
                        ) {
                            Text(
                                text = strings.settingsImportHint,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            OutlinedTextField(
                                value = importTextInput,
                                onValueChange = { importTextInput = it },
                                label = { Text(strings.settingsImportInputLabel) },
                                placeholder = { Text(strings.settingsImportInputPlaceholder) },
                                minLines = 2,
                                maxLines = 4,
                                shape = RoundedCornerShape(18.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("settings_import_input"),
                            )

                            Text(
                                text = strings.settingsV33.importTitleFineHint,
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 11.sp,
                                lineHeight = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                                modifier = Modifier.testTag("settings_import_title_hint"),
                            )

                            OutlinedTextField(
                                value = importPlaylistName,
                                onValueChange = { importPlaylistName = it },
                                label = { Text(strings.settingsImportPlaylistNameLabel) },
                                placeholder = { Text(strings.settingsImportPlaylistNamePlaceholder) },
                                singleLine = true,
                                shape = RoundedCornerShape(18.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("settings_import_playlist_name"),
                            )

                            state.importStatusMessage?.let { msg ->
                                Text(
                                    text = msg,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (msg.startsWith("✓")) LiquidMint else LiquidRose,
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Button(
                                    onClick = {
                                        haptics.confirm()
                                        onImportFromInput(
                                            importTextInput,
                                            importPlaylistName.takeIf { it.isNotBlank() },
                                        )
                                    },
                                    enabled = importTextInput.isNotBlank() && !state.isImportingPlaylist,
                                    shape = CircleShape,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = accent,
                                        contentColor = MaterialTheme.colorScheme.onPrimary,
                                    ),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                                    modifier = Modifier
                                        .weight(1.35f)
                                        .testTag("settings_import_link_button"),
                                ) {
                                    if (state.isImportingPlaylist) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.onPrimary,
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = strings.settingsImportingLabel,
                                            maxLines = 1,
                                            softWrap = false,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    } else {
                                        Text(
                                            text = strings.settingsImportFromLinkButton,
                                            maxLines = 1,
                                            softWrap = false,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }

                                OutlinedButton(
                                    onClick = {
                                        haptics.tick()
                                        playlistFileLauncher.launch(
                                            arrayOf(
                                                "text/csv",
                                                "text/comma-separated-values",
                                                "text/tab-separated-values",
                                                "text/plain",
                                                "application/json",
                                                "application/vnd.ms-excel",
                                                "audio/x-mpegurl",
                                                "application/vnd.apple.mpegurl",
                                                "audio/mpegurl",
                                            ),
                                        )
                                    },
                                    enabled = !state.isImportingPlaylist,
                                    shape = CircleShape,
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("settings_import_file_button"),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.UploadFile,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = strings.settingsImportFromFileButton,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }

                            Text(
                                text = strings.settingsImportPublicNotice,
                                style = MaterialTheme.typography.bodySmall,
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.80f),
                                modifier = Modifier.testTag("settings_import_public_notice"),
                            )
                        }
                    }

                    // 6. Dedicated Developer Section Navigation Card
                    item(key = "developer_section") {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                    shape = RoundedCornerShape(26.dp),
                                )
                                .clip(RoundedCornerShape(26.dp))
                                .clickable {
                                    haptics.tick()
                                    onOpenDeveloperScreen()
                                }
                                .testTag("settings_open_developer_page_button"),
                            shape = RoundedCornerShape(26.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(18.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(accent.copy(alpha = 0.18f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.AdminPanelSettings,
                                        contentDescription = null,
                                        tint = accent,
                                        modifier = Modifier.size(22.dp),
                                    )
                                }
                                Text(
                                    text = strings.settingsDeveloperSection,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2,
                                    modifier = Modifier.weight(1f),
                                )
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                    contentDescription = strings.settingsDeveloperSection,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemeChoiceButton(
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    accent: Color,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(
                if (selected) accent.copy(alpha = 0.22f)
                else MaterialTheme.colorScheme.background.copy(alpha = 0.55f),
            )
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) accent else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(18.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) accent else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun SettingsCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    accent: Color,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
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
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
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
                        imageVector = icon,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            content()
        }
    }
}
