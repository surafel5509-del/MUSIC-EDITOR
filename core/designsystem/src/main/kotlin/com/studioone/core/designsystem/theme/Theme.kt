package com.studioone.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Accessibility + vision modifiers applied on top of the base scheme. */
enum class ColorVisionAssist { NONE, PROTANOPIA, DEUTERANOPIA, TRITANOPIA }

@Immutable
data class StudioThemeOptions(
    val highContrast: Boolean = false,
    val colorVisionAssist: ColorVisionAssist = ColorVisionAssist.NONE,
    val largeTouchTargets: Boolean = false,
)

/** Density-independent spacing scale used across all screens. */
@Immutable
data class StudioSpacing(
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 16.dp,
    val lg: Dp = 24.dp,
    val xl: Dp = 32.dp,
    val trackHeaderWidth: Dp = 128.dp,
    val touchTarget: Dp,
)

val LocalStudioThemeOptions = staticCompositionLocalOf { StudioThemeOptions() }
val LocalStudioSpacing = staticCompositionLocalOf { StudioSpacing(touchTarget = 48.dp) }

private val DarkScheme = darkColorScheme(
    primary = StudioTeal,
    onPrimary = Color(0xFF003734),
    secondary = StudioViolet,
    tertiary = StudioAmber,
    background = DarkBackground,
    surface = DarkSurface,
    surfaceVariant = DarkSurfaceRaised,
    onBackground = DarkOnBackground,
    onSurface = DarkOnSurface,
    onSurfaceVariant = DarkOnSurface,
    outline = DarkOutline,
    error = StudioCoral,
)

private val LightScheme = lightColorScheme(
    primary = StudioTealDim,
    onPrimary = Color.White,
    secondary = StudioViolet,
    tertiary = Color(0xFFB87A00),
    background = LightBackground,
    surface = LightSurface,
    surfaceVariant = LightSurfaceRaised,
    onBackground = LightOnBackground,
    onSurface = LightOnSurface,
    onSurfaceVariant = LightOnSurface,
    outline = LightOutline,
    error = Color(0xFFC53030),
)

/**
 * Applies accessibility adjustments:
 *  - high contrast boosts outline/surface separation,
 *  - color-vision assist swaps the most-confusable hue pair in the palette
 *    (red/green -> blue/orange family) rather than filtering the whole UI.
 */
private fun applyAccessibility(
    base: androidx.compose.material3.ColorScheme,
    options: StudioThemeOptions,
): androidx.compose.material3.ColorScheme {
    var scheme = base
    if (options.highContrast) {
        scheme = scheme.copy(
            outline = if (scheme == base) DarkOnSurface else LightOnSurface,
            surfaceVariant = scheme.surfaceContainer,
        )
    }
    if (options.colorVisionAssist != ColorVisionAssist.NONE) {
        // Replace the red/green semantic pair with a color-blind-safe pair.
        // (Record stays red-by-shape + iconography everywhere.)
        scheme = scheme.copy(tertiary = Color(0xFF3B82F6))
    }
    return scheme
}

@Composable
fun StudioOneTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    options: StudioThemeOptions = StudioThemeOptions(),
    content: @Composable () -> Unit,
) {
    val baseScheme = if (darkTheme) DarkScheme else LightScheme
    val scheme = applyAccessibility(baseScheme, options)
    val spacing = StudioSpacing(
        touchTarget = if (options.largeTouchTargets) 60.dp else 48.dp,
    )

    CompositionLocalProvider(
        LocalStudioThemeOptions provides options,
        LocalStudioSpacing provides spacing,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = StudioTypography,
            shapes = Shapes(),
            content = content,
        )
    }
}
