package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.FolderCopy
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.BuildConfig
import com.example.ui.components.rememberSoundLapaHaptics
import com.example.ui.i18n.LocalAppStrings
import kotlinx.coroutines.delay

@Composable
fun AboutScreen(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onClose)

    val strings = LocalAppStrings.current
    val context = LocalContext.current
    val haptics = rememberSoundLapaHaptics()
    val accent = MaterialTheme.colorScheme.primary
    var copiedFeedback by remember { mutableStateOf(false) }

    LaunchedEffect(copiedFeedback) {
        if (copiedFeedback) {
            delay(2200)
            copiedFeedback = false
        }
    }

    val versionSummary = remember {
        buildString {
            appendLine("SoundLapa v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})")
            appendLine("Package: ${BuildConfig.APPLICATION_ID}")
            appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            append("Engine: AndroidX Media3 / ExoPlayer • Compose Material 3")
        }
    }

    val heroGradient = remember(accent) {
        Brush.linearGradient(
            listOf(
                accent.copy(alpha = 0.28f),
                accent.copy(alpha = 0.08f),
            ),
        )
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
                            .minimumInteractiveComponentSize()
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f))
                            .testTag("about_back_button"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = strings.close,
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Column {
                        Text(
                            text = strings.aboutAppTitle,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Text(
                            text = strings.aboutSubtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // 1. Hero Card
                    item(key = "about_hero") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(30.dp))
                                .background(heroGradient)
                                .border(
                                    width = 1.dp,
                                    color = accent.copy(alpha = 0.40f),
                                    shape = RoundedCornerShape(30.dp),
                                )
                                .padding(20.dp)
                                .testTag("about_hero_card"),
                        ) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(60.dp)
                                            .clip(RoundedCornerShape(22.dp))
                                            .background(accent),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.GraphicEq,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.size(34.dp),
                                        )
                                    }

                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Text(
                                            text = "SoundLapa",
                                            style = MaterialTheme.typography.headlineMedium,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                        Text(
                                            text = strings.aboutHeroTagline,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = accent.copy(alpha = 0.22f),
                                        modifier = Modifier.border(
                                            width = 1.dp,
                                            color = accent.copy(alpha = 0.45f),
                                            shape = CircleShape,
                                        ),
                                    ) {
                                        Text(
                                            text = "v${BuildConfig.VERSION_NAME} • ${strings.aboutBuildLabel} ${BuildConfig.VERSION_CODE}",
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = accent,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                        )
                                    }

                                    Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
                                    ) {
                                        Text(
                                            text = "by @TylerW1nston",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                        )
                                    }
                                }

                                Text(
                                    text = strings.aboutAppDescription,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.90f),
                                )
                            }
                        }
                    }

                    // 2. What's New Card
                    item(key = "about_whats_new") {
                        AboutSectionCard(
                            icon = Icons.Rounded.AutoAwesome,
                            title = strings.aboutWhatsNewTitle,
                            accent = accent,
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                strings.aboutWhatsNewItems.forEach { changeItem ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.Top,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.CheckCircle,
                                            contentDescription = null,
                                            tint = accent,
                                            modifier = Modifier
                                                .padding(top = 2.dp)
                                                .size(18.dp),
                                        )
                                        Text(
                                            text = changeItem,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 3. Key Features Card
                    item(key = "about_features") {
                        AboutSectionCard(
                            icon = Icons.Rounded.Verified,
                            title = strings.aboutFeaturesTitle,
                            accent = accent,
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                AboutFeatureItem(
                                    icon = Icons.Rounded.TravelExplore,
                                    title = strings.aboutFeatureSearchTitle,
                                    description = strings.aboutFeatureSearchDesc,
                                    accent = accent,
                                )
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                AboutFeatureItem(
                                    icon = Icons.Rounded.FolderCopy,
                                    title = strings.aboutFeatureLibraryTitle,
                                    description = strings.aboutFeatureLibraryDesc,
                                    accent = accent,
                                )
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                AboutFeatureItem(
                                    icon = Icons.Rounded.QueueMusic,
                                    title = strings.aboutFeatureAudioTitle,
                                    description = strings.aboutFeatureAudioDesc,
                                    accent = accent,
                                )
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                AboutFeatureItem(
                                    icon = Icons.Rounded.Palette,
                                    title = strings.aboutFeatureThemeTitle,
                                    description = strings.aboutFeatureThemeDesc,
                                    accent = accent,
                                )
                            }
                        }
                    }

                    // 4. Technical & Build Info Card
                    item(key = "about_tech_specs") {
                        AboutSectionCard(
                            icon = Icons.Rounded.Code,
                            title = strings.aboutTechSpecsTitle,
                            accent = accent,
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                AboutSpecRow(strings.appVersionLabel, BuildConfig.VERSION_NAME, accent)
                                AboutSpecRow(strings.aboutBuildLabel, BuildConfig.VERSION_CODE.toString(), accent)
                                AboutSpecRow(strings.aboutSpecAudioEngineLabel, "AndroidX Media3 / ExoPlayer", accent)
                                AboutSpecRow(strings.aboutSpecUiStackLabel, "Jetpack Compose + Material 3", accent)
                                AboutSpecRow(strings.aboutSpecStorageLabel, "Room SQLite + DataStore", accent)
                                AboutSpecRow(strings.aboutSpecPackageLabel, BuildConfig.APPLICATION_ID, accent)
                                AboutSpecRow(
                                    strings.aboutSpecOsLabel,
                                    "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                                    accent,
                                )
                            }
                        }
                    }

                    // 5. Sources & Credits Card
                    item(key = "about_credits") {
                        AboutSectionCard(
                            icon = Icons.Rounded.Info,
                            title = strings.aboutCreditsTitle,
                            accent = accent,
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                AboutSpecRow(strings.aboutCreditCreatorLabel, strings.aboutCreditCreatorValue, accent)
                                AboutSpecRow(strings.aboutCreditStreamingLabel, strings.aboutCreditStreamingValue, accent)
                                AboutSpecRow(strings.aboutCreditUiLabel, strings.aboutCreditUiValue, accent)
                            }
                        }
                    }

                    // 6. Copy Version Info Action Button
                    item(key = "about_copy_action") {
                        Button(
                            onClick = {
                                val clipboard = context.getSystemService(ClipboardManager::class.java)
                                clipboard?.setPrimaryClip(ClipData.newPlainText("SoundLapa Version", versionSummary))
                                haptics.confirm()
                                copiedFeedback = true
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .testTag("about_copy_version_button"),
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = accent,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                        ) {
                            Icon(
                                imageVector = if (copiedFeedback) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (copiedFeedback) strings.aboutVersionCopied else strings.aboutCopyVersionInfo,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun AboutSectionCard(
    icon: ImageVector,
    title: String,
    accent: Color,
    content: @Composable () -> Unit,
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

@Composable
private fun AboutFeatureItem(
    icon: ImageVector,
    title: String,
    description: String,
    accent: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(18.dp),
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AboutSpecRow(
    label: String,
    value: String,
    accent: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 12.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
