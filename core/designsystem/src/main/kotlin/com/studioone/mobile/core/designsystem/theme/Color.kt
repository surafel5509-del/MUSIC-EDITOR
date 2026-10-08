package com.studioone.mobile.core.designsystem.theme

import androidx.compose.ui.graphics.Color

/**
 * StudioOne brand palette.
 *
 * Design language: "midnight console" — deep neutral surfaces, one confident
 * accent (electric cyan), and functional track colors that survive color-blind
 * modes (see ColorBlindPalette below: every hue pair differs in lightness as
 * well as hue, per WCAG 1.4.1 "not the only visual means").
 */
object S1Colors {
    // Brand
    val ElectricCyan = Color(0xFF23D9E8)
    val ElectricCyanDim = Color(0xFF1A9FAB)
    val StudioMagenta = Color(0xFFE84FA0)
    val SunsetAmber = Color(0xFFFFB023)

    // Dark surfaces (default theme)
    val DarkBackground = Color(0xFF0E1116)
    val DarkSurface = Color(0xFF151A21)
    val DarkSurfaceVariant = Color(0xFF1D242E)
    val DarkSurfaceElevated = Color(0xFF232B36)
    val DarkOutline = Color(0xFF333D4A)
    val DarkOnSurface = Color(0xFFE6EBF2)
    val DarkOnSurfaceVariant = Color(0xFF9AA7B5)

    // Light surfaces
    val LightBackground = Color(0xFFF7F9FB)
    val LightSurface = Color(0xFFFFFFFF)
    val LightSurfaceVariant = Color(0xFFE9EDF2)
    val LightSurfaceElevated = Color(0xFFFFFFFF)
    val LightOutline = Color(0xFFC6CDD6)
    val LightOnSurface = Color(0xFF12181F)
    val LightOnSurfaceVariant = Color(0xFF4E5A67)

    // Functional (recording, meters)
    val RecordRed = Color(0xFFE5484D)
    val MeterGreen = Color(0xFF46C46A)
    val MeterYellow = Color(0xFFE5C143)
    val MeterRed = Color(0xFFE5484D)
    val WaveformFill = Color(0xFF7BD7E4)
    val WaveformFillDim = Color(0xFF3A6E77)
    val MidiNoteFill = Color(0xFF8FA6FF)
    val AutomationLine = Color(0xFFFFB023)

    // Track colors (match core:model TrackColor argb values)
    val TrackPalette = listOf(
        Color(0xFFE57373), Color(0xFFFFB74D), Color(0xFFAED581),
        Color(0xFF4DB6AC), Color(0xFF4FC3F7), Color(0xFF7986CB),
        Color(0xFFBA68C8), Color(0xFFF06292), Color(0xFF90A4AE),
    )

    /**
     * Color-blind-safe remap. Ordered by lightness deltas so adjacent tracks
     * remain distinguishable under protanopia/deuteranopia/tritanopia.
     */
    val TrackPaletteColorBlind = listOf(
        Color(0xFF648FFF), Color(0xFF785EF0), Color(0xFFDC267F),
        Color(0xFFFE6100), Color(0xFFFFB000), Color(0xFF22B8CF),
        Color(0xFF20C997), Color(0xFF9AA5B1), Color(0xFFF783AC),
    )
}
