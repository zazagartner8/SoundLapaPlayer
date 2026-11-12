package com.example.ui.screens

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Translate
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import com.example.ui.MainUiState
import com.example.ui.components.rememberSoundLapaHaptics
import com.example.ui.i18n.AppLanguage
import com.example.ui.i18n.LocalAppStrings
import com.example.ui.theme.LiquidCyan
import com.example.ui.theme.LiquidMint
import com.example.ui.theme.LiquidRose

private val PresetAccentColors = listOf(
    0xFFFF5500L, // Flame Orange
    0xFF00D2E6L, // Electric Cyan
    0xFF8B5CF6L, // Neon Violet
    0xFF10B981L, // Emerald Mint
    0xFFF43F5EL, // Crimson Rose
    0xFFF59E0BL, // Solar Gold
    0xFF3B82F6L, // Cobalt Blue
    0xFFEC4899L, // Sakura Pink
)

@Composable
fun SettingsScreen(
    state: MainUiState,
    onClose: () -> Unit,
    onSelectLanguage: (String) -> Unit,
    onSelectAccentColor: (Long) -> Unit,
    onToggleDarkTheme: (Boolean) -> Unit,
    onToggleSmoothTransition: (Boolean) -> Unit,
    onChangeSmoothTransitionDuration: (Int) -> Unit,
    onToggleAutoUpload: (Boolean) -> Unit,
    onToggleAutoCache: (Boolean) -> Unit,
    onSetAutoUploadFolder: (Uri, String?) -> Unit,
    onClearAutoUploadFolder: () -> Unit,
    onSyncAudiusAccount: (String) -> Unit,
    onDisconnectAudius: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onClose)

    val strings = LocalAppStrings.current
    val context = LocalContext.current
    val haptics = rememberSoundLapaHaptics()
    val accent = MaterialTheme.colorScheme.primary

    var handleInput by remember(state.settings.audiusHandle) {
        mutableStateOf(state.settings.audiusHandle.orEmpty())
    }

    val initialHsv = remember {
        val hsv = FloatArray(3)
        AndroidColor.colorToHSV(state.settings.accentColorArgb.toInt(), hsv)
        hsv
    }
    var hue by remember { mutableFloatStateOf(initialHsv[0]) }
    var saturation by remember { mutableFloatStateOf(initialHsv[1].coerceIn(0.25f, 1f)) }
    var brightness by remember { mutableFloatStateOf(initialHsv[2].coerceIn(0.55f, 1f)) }

    fun applyHsvColor() {
        val argb = Color(
            AndroidColor.HSVToColor(floatArrayOf(hue, saturation, brightness)),
        ).toArgb().toLong() and 0xFFFFFFFFL
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

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
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
                    Column {
                        Text(
                            text = strings.settingsTitle,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Text(
                            text = strings.settingsSubtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
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
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Text(
                                            text = lang.badge,
                                            fontSize = 28.sp,
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
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Column {
                                        Text(
                                            text = strings.settingsPreviewLabel,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = accent,
                                        )
                                        Text(
                                            text = strings.settingsAccentColorTitle,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .clip(CircleShape)
                                            .background(accent)
                                            .padding(horizontal = 14.dp, vertical = 8.dp),
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
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
                                                saturation = hsv[1].coerceIn(0.25f, 1f)
                                                brightness = hsv[2].coerceIn(0.55f, 1f)
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
                                            valueRange = 0.25f..1f,
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
                                            valueRange = 0.55f..1f,
                                            colors = SliderDefaults.colors(
                                                thumbColor = accent,
                                                activeTrackColor = accent,
                                            ),
                                        )
                                    }
                                }
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

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                    Text(
                                        text = strings.settingsAutoCacheTitle,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = strings.settingsAutoCacheSubtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Switch(
                                    checked = state.settings.autoCacheOnPlay,
                                    onCheckedChange = {
                                        haptics.tick()
                                        onToggleAutoCache(it)
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                        checkedTrackColor = accent,
                                    ),
                                )
                            }
                        }
                    }

                    // 4. Audius Sync Card
                    item(key = "audius_section") {
                        SettingsCard(
                            icon = Icons.Rounded.Sync,
                            title = strings.settingsAudiusSection,
                            accent = accent,
                        ) {
                            Text(
                                text = state.settings.audiusHandle?.let { handle ->
                                    strings.settingsAudiusConnected(
                                        state.settings.audiusDisplayName ?: handle,
                                        handle,
                                    )
                                } ?: strings.settingsAudiusHint,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (state.settings.audiusHandle != null) LiquidMint else MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            OutlinedTextField(
                                value = handleInput,
                                onValueChange = { handleInput = it },
                                label = { Text(strings.settingsAudiusHandleLabel) },
                                placeholder = { Text(strings.settingsAudiusHandlePlaceholder) },
                                singleLine = true,
                                shape = RoundedCornerShape(18.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("settings_audius_handle_input"),
                            )

                            state.audius.statusMessage?.let { msg ->
                                Text(
                                    text = msg,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = LiquidCyan,
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        haptics.confirm()
                                        onSyncAudiusAccount(handleInput)
                                    },
                                    enabled = handleInput.isNotBlank() && !state.audius.isSyncingAccount,
                                    shape = CircleShape,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = accent,
                                        contentColor = MaterialTheme.colorScheme.onPrimary,
                                    ),
                                    modifier = Modifier.testTag("settings_audius_sync_button"),
                                ) {
                                    if (state.audius.isSyncingAccount) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.onPrimary,
                                        )
                                        Spacer(Modifier.width(6.dp))
                                    }
                                    Text(
                                        if (state.settings.audiusHandle != null) {
                                            strings.settingsAudiusRefresh
                                        } else {
                                            strings.settingsAudiusSync
                                        },
                                    )
                                }

                                if (state.settings.audiusHandle != null) {
                                    TextButton(onClick = onDisconnectAudius) {
                                        Text(strings.settingsAudiusDisconnect, color = LiquidRose)
                                    }
                                }
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
