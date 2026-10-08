package com.studioone.mobile.core.designsystem.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

private val DarkScheme = darkColorScheme(
    primary = S1Colors.ElectricCyan,
    onPrimary = S1Colors.DarkBackground,
    primaryContainer = S1Colors.ElectricCyanDim,
    secondary = S1Colors.StudioMagenta,
    tertiary = S1Colors.SunsetAmber,
    background = S1Colors.DarkBackground,
    onBackground = S1Colors.DarkOnSurface,
    surface = S1Colors.DarkSurface,
    onSurface = S1Colors.DarkOnSurface,
    surfaceVariant = S1Colors.DarkSurfaceVariant,
    onSurfaceVariant = S1Colors.DarkOnSurfaceVariant,
    outline = S1Colors.DarkOutline,
    error = S1Colors.RecordRed,
)

private val LightScheme = lightColorScheme(
    primary = S1Colors.ElectricCyanDim,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    secondary = S1Colors.StudioMagenta,
    tertiary = S1Colors.SunsetAmber,
    background = S1Colors.LightBackground,
    onBackground = S1Colors.LightOnSurface,
    surface = S1Colors.LightSurface,
    onSurface = S1Colors.LightOnSurface,
    surfaceVariant = S1Colors.LightSurfaceVariant,
    onSurfaceVariant = S1Colors.LightOnSurfaceVariant,
    outline = S1Colors.LightOutline,
    error = S1Colors.RecordRed,
)

/** High-contrast variants (accessibility setting). */
private val DarkHighContrast = DarkScheme.copy(
    background = androidx.compose.ui.graphics.Color.Black,
    surface = androidx.compose.ui.graphics.Color(0xFF0A0A0A),
    onSurface = androidx.compose.ui.graphics.Color.White,
    primary = androidx.compose.ui.graphics.Color(0xFF6FF6FF),
)
private val LightHighContrast = LightScheme.copy(
    background = androidx.compose.ui.graphics.Color.White,
    onBackground = androidx.compose.ui.graphics.Color.Black,
    onSurface = androidx.compose.ui.graphics.Color.Black,
    primary = androidx.compose.ui.graphics.Color(0xFF005F6B),
)

data class S1ThemeSettings(
    val darkTheme: Boolean = true,
    val highContrast: Boolean = false,
    val colorBlindMode: Boolean = false,
    val largeTouchTargets: Boolean = false,
    val dynamicColor: Boolean = false,
)

val LocalS1Theme = staticCompositionLocalOf { S1ThemeSettings() }

/** Dimension tokens honoring the large-touch-targets setting. */
data class S1Dimensions(
    val minTouchTarget: Int = 48,
    val trackHeaderWidth: Int = 96,
    val faderWidth: Int = 56,
    val knobSize: Int = 44,
    val timelineRulerHeight: Int = 36,
    val transportBarHeight: Int = 72,
)

val LocalS1Dimensions = staticCompositionLocalOf { S1Dimensions() }

@Composable
fun StudioOneTheme(
    settings: S1ThemeSettings = S1ThemeSettings(darkTheme = isSystemInDarkTheme()),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val useDynamic = settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val scheme = when {
        useDynamic && settings.darkTheme -> androidx.compose.material3.dynamicDarkColorScheme(context)
        useDynamic -> androidx.compose.material3.dynamicLightColorScheme(context)
        settings.darkTheme && settings.highContrast -> DarkHighContrast
        settings.darkTheme -> DarkScheme
        settings.highContrast -> LightHighContrast
        else -> LightScheme
    }
    val dimensions = S1Dimensions(
        minTouchTarget = if (settings.largeTouchTargets) 64 else 48,
        trackHeaderWidth = if (settings.largeTouchTargets) 120 else 96,
        faderWidth = if (settings.largeTouchTargets) 72 else 56,
        knobSize = if (settings.largeTouchTargets) 56 else 44,
        timelineRulerHeight = if (settings.largeTouchTargets) 44 else 36,
        transportBarHeight = if (settings.largeTouchTargets) 88 else 72,
    )
    val trackPalette = if (settings.colorBlindMode) S1Colors.TrackPaletteColorBlind else S1Colors.TrackPalette

    CompositionLocalProvider(
        LocalS1Theme provides settings,
        LocalS1Dimensions provides dimensions,
        LocalTrackPalette provides trackPalette,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = S1Typography,
            content = content,
        )
    }
}

val LocalTrackPalette: ProvidableCompositionLocal<List<androidx.compose.ui.graphics.Color>> =
    staticCompositionLocalOf { S1Colors.TrackPalette }

/** Convenience accessors. */
val s1Dimensions: S1Dimensions
    @Composable get() = LocalS1Dimensions.current
