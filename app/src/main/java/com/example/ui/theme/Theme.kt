package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

private fun buildDarkColorScheme(accent: Color) = darkColorScheme(
    primary = accent,
    onPrimary = if (accent.luminance() > 0.50f) Color(0xFF09080D) else Color.White,
    primaryContainer = if (accent.luminance() < 0.04f) Color(0xFF282534) else accent.copy(alpha = 0.24f),
    onPrimaryContainer = SoundLapaCream,
    secondary = LiquidCyan,
    onSecondary = ObsidianVoid,
    secondaryContainer = Color(0xFF19353B),
    onSecondaryContainer = LiquidCyan,
    tertiary = LiquidViolet,
    onTertiary = ObsidianVoid,
    tertiaryContainer = Color(0xFF28203D),
    onTertiaryContainer = Color(0xFFE9DDFF),
    background = ObsidianVoid,
    onBackground = Color(0xFFF6F4FA),
    surface = ObsidianSurface,
    onSurface = Color(0xFFF6F4FA),
    surfaceVariant = ObsidianCard,
    onSurfaceVariant = Color(0xFFB4AEC2),
    outline = Color(0x40FFFFFF),
    outlineVariant = Color(0x1FFFFFFF),
    error = LiquidRose,
    onError = ObsidianVoid,
)

private fun buildLightColorScheme(accent: Color) = lightColorScheme(
    primary = accent,
    onPrimary = if (accent.luminance() > 0.50f) Color(0xFF16131A) else Color.White,
    primaryContainer = if (accent.luminance() > 0.90f) Color(0xFFDCD3C8) else accent.copy(alpha = 0.16f),
    onPrimaryContainer = FrostInk,
    secondary = Color(0xFF006874),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFB3F0F8),
    onSecondaryContainer = Color(0xFF002024),
    tertiary = Color(0xFF6247AA),
    onTertiary = Color.White,
    background = FrostCanvas,
    onBackground = FrostInk,
    surface = FrostSurface,
    onSurface = FrostInk,
    surfaceVariant = FrostSurfaceVariant,
    onSurfaceVariant = FrostMuted,
    outline = Color(0x3618151D),
    outlineVariant = Color(0x1A18151D),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFF8F4F0),
)

val SoundLapaShapes = Shapes(
    extraSmall = RoundedCornerShape(14.dp),
    small = RoundedCornerShape(18.dp),
    medium = RoundedCornerShape(24.dp),
    large = RoundedCornerShape(30.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

@Composable
fun SoundLapaTheme(
    darkTheme: Boolean = true,
    accentColor: Color = SoundLapaPurple,
    content: @Composable () -> Unit,
) {
    val colorScheme = remember(darkTheme, accentColor) {
        if (darkTheme) {
            buildDarkColorScheme(accentColor)
        } else {
            buildLightColorScheme(accentColor)
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = SoundLapaTypography,
        shapes = SoundLapaShapes,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides colorScheme.onBackground,
            content = content,
        )
    }
}
