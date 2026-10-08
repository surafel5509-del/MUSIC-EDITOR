package com.studioone.core.designsystem.theme

import androidx.compose.ui.graphics.Color

/**
 * StudioOne color system.
 * Dark is the primary scheme (studio environments); light follows Material 3
 * contrast guidance. High-contrast variants deepen surface separation.
 */

// ---- Brand -----------------------------------------------------------------
val StudioTeal = Color(0xFF00C2B8)
val StudioTealDim = Color(0xFF00837D)
val StudioAmber = Color(0xFFFFB547)
val StudioCoral = Color(0xFFFF5C5C)
val StudioViolet = Color(0xFF8C7BFF)

// ---- Dark scheme -----------------------------------------------------------
val DarkBackground = Color(0xFF0E1116)
val DarkSurface = Color(0xFF161B22)
val DarkSurfaceRaised = Color(0xFF1E2530)
val DarkSurfaceContainer = Color(0xFF232B38)
val DarkOnBackground = Color(0xFFE8ECF2)
val DarkOnSurface = Color(0xFFD6DCE5)
val DarkOutline = Color(0xFF3A4553)

// ---- Light scheme ------------------------------------------------------------
val LightBackground = Color(0xFFF6F8FA)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceRaised = Color(0xFFEEF2F6)
val LightSurfaceContainer = Color(0xFFE4EAF0)
val LightOnBackground = Color(0xFF14181E)
val LightOnSurface = Color(0xFF2A313B)
val LightOutline = Color(0xFFC3CDD8)

// ---- Semantic ----------------------------------------------------------------
val RecordRed = Color(0xFFE5484D)
val ArmedRed = Color(0xFFFF6363)
val SoloAmber = Color(0xFFFFC53D)
val MuteGray = Color(0xFF7C8794)
val PlayGreen = Color(0xFF46A758)

/** Track lane palette (8 rotating colors). */
val TrackColors = listOf(
    Color(0xFF5B8DEF),
    Color(0xFF8C7BFF),
    Color(0xFF00C2B8),
    Color(0xFF46A758),
    Color(0xFFFFB547),
    Color(0xFFFF7A59),
    Color(0xFFFF5C8A),
    Color(0xFF9AA7B4),
)

fun trackColor(index: Int): Color = TrackColors[index % TrackColors.size]
