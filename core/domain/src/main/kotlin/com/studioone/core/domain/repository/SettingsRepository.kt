package com.studioone.core.domain.repository

import kotlinx.coroutines.flow.Flow

/** Audio engine configuration persisted in DataStore. */
data class AudioSettings(
    val sampleRate: Int = 44_100,
    val bufferSize: Int = 128,
    val bitDepth: Int = 24,
    val useLowLatencyPath: Boolean = true,
    val useOpenSlesFallback: Boolean = false,
    val monitoringEnabled: Boolean = true,
    val metronomeEnabled: Boolean = false,
    val countInBars: Int = 1,
    /** Measured round-trip latency in frames, filled by the latency probe. */
    val measuredOutputLatencyFrames: Int = 0,
    val measuredInputLatencyFrames: Int = 0,
)

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class ColorVisionMode { NONE, PROTANOPIA, DEUTERANOPIA, TRITANOPIA }

data class UiSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val highContrast: Boolean = false,
    val colorVisionMode: ColorVisionMode = ColorVisionMode.NONE,
    val largeTouchTargets: Boolean = false,
    val showTutorialHints: Boolean = true,
    val workspaceLayout: String = "default",
)

interface SettingsRepository {
    fun observeAudioSettings(): Flow<AudioSettings>
    suspend fun updateAudioSettings(transform: (AudioSettings) -> AudioSettings)

    fun observeUiSettings(): Flow<UiSettings>
    suspend fun updateUiSettings(transform: (UiSettings) -> UiSettings)
}
