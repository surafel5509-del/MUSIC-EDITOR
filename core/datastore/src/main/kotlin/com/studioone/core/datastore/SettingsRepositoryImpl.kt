package com.studioone.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.studioone.core.domain.repository.AudioSettings
import com.studioone.core.domain.repository.ColorVisionMode
import com.studioone.core.domain.repository.SettingsRepository
import com.studioone.core.domain.repository.ThemeMode
import com.studioone.core.domain.repository.UiSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "studioone_settings")

/** DataStore-backed user settings (audio engine config + UI preferences). */
@Singleton
class SettingsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : SettingsRepository {

    private object Keys {
        val sampleRate = intPreferencesKey("audio_sample_rate")
        val bufferSize = intPreferencesKey("audio_buffer_size")
        val bitDepth = intPreferencesKey("audio_bit_depth")
        val lowLatency = booleanPreferencesKey("audio_low_latency")
        val openSlesFallback = booleanPreferencesKey("audio_opensles_fallback")
        val monitoring = booleanPreferencesKey("audio_monitoring")
        val metronome = booleanPreferencesKey("audio_metronome")
        val countInBars = intPreferencesKey("audio_count_in_bars")
        val outLatencyFrames = intPreferencesKey("audio_out_latency_frames")
        val inLatencyFrames = intPreferencesKey("audio_in_latency_frames")
        val themeMode = stringPreferencesKey("ui_theme_mode")
        val highContrast = booleanPreferencesKey("ui_high_contrast")
        val colorVision = stringPreferencesKey("ui_color_vision")
        val largeTargets = booleanPreferencesKey("ui_large_targets")
        val tutorialHints = booleanPreferencesKey("ui_tutorial_hints")
        val workspaceLayout = stringPreferencesKey("ui_workspace_layout")
    }

    override fun observeAudioSettings(): Flow<AudioSettings> =
        context.settingsDataStore.data.map { p ->
            AudioSettings(
                sampleRate = p[Keys.sampleRate] ?: 44_100,
                bufferSize = p[Keys.bufferSize] ?: 128,
                bitDepth = p[Keys.bitDepth] ?: 24,
                useLowLatencyPath = p[Keys.lowLatency] ?: true,
                useOpenSlesFallback = p[Keys.openSlesFallback] ?: false,
                monitoringEnabled = p[Keys.monitoring] ?: true,
                metronomeEnabled = p[Keys.metronome] ?: false,
                countInBars = p[Keys.countInBars] ?: 1,
                measuredOutputLatencyFrames = p[Keys.outLatencyFrames] ?: 0,
                measuredInputLatencyFrames = p[Keys.inLatencyFrames] ?: 0,
            )
        }

    override suspend fun updateAudioSettings(transform: (AudioSettings) -> AudioSettings) {
        context.settingsDataStore.edit { p ->
            val current = AudioSettings(
                sampleRate = p[Keys.sampleRate] ?: 44_100,
                bufferSize = p[Keys.bufferSize] ?: 128,
                bitDepth = p[Keys.bitDepth] ?: 24,
                useLowLatencyPath = p[Keys.lowLatency] ?: true,
                useOpenSlesFallback = p[Keys.openSlesFallback] ?: false,
                monitoringEnabled = p[Keys.monitoring] ?: true,
                metronomeEnabled = p[Keys.metronome] ?: false,
                countInBars = p[Keys.countInBars] ?: 1,
                measuredOutputLatencyFrames = p[Keys.outLatencyFrames] ?: 0,
                measuredInputLatencyFrames = p[Keys.inLatencyFrames] ?: 0,
            )
            val next = transform(current)
            p[Keys.sampleRate] = next.sampleRate
            p[Keys.bufferSize] = next.bufferSize
            p[Keys.bitDepth] = next.bitDepth
            p[Keys.lowLatency] = next.useLowLatencyPath
            p[Keys.openSlesFallback] = next.useOpenSlesFallback
            p[Keys.monitoring] = next.monitoringEnabled
            p[Keys.metronome] = next.metronomeEnabled
            p[Keys.countInBars] = next.countInBars
            p[Keys.outLatencyFrames] = next.measuredOutputLatencyFrames
            p[Keys.inLatencyFrames] = next.measuredInputLatencyFrames
        }
    }

    override fun observeUiSettings(): Flow<UiSettings> =
        context.settingsDataStore.data.map { p ->
            UiSettings(
                themeMode = runCatching { ThemeMode.valueOf(p[Keys.themeMode] ?: "") }.getOrDefault(ThemeMode.SYSTEM),
                highContrast = p[Keys.highContrast] ?: false,
                colorVisionMode = runCatching { ColorVisionMode.valueOf(p[Keys.colorVision] ?: "") }
                    .getOrDefault(ColorVisionMode.NONE),
                largeTouchTargets = p[Keys.largeTargets] ?: false,
                showTutorialHints = p[Keys.tutorialHints] ?: true,
                workspaceLayout = p[Keys.workspaceLayout] ?: "default",
            )
        }

    override suspend fun updateUiSettings(transform: (UiSettings) -> UiSettings) {
        context.settingsDataStore.edit { p ->
            val current = UiSettings(
                themeMode = runCatching { ThemeMode.valueOf(p[Keys.themeMode] ?: "") }.getOrDefault(ThemeMode.SYSTEM),
                highContrast = p[Keys.highContrast] ?: false,
                colorVisionMode = runCatching { ColorVisionMode.valueOf(p[Keys.colorVision] ?: "") }
                    .getOrDefault(ColorVisionMode.NONE),
                largeTouchTargets = p[Keys.largeTargets] ?: false,
                showTutorialHints = p[Keys.tutorialHints] ?: true,
                workspaceLayout = p[Keys.workspaceLayout] ?: "default",
            )
            val next = transform(current)
            p[Keys.themeMode] = next.themeMode.name
            p[Keys.highContrast] = next.highContrast
            p[Keys.colorVision] = next.colorVisionMode.name
            p[Keys.largeTargets] = next.largeTouchTargets
            p[Keys.tutorialHints] = next.showTutorialHints
            p[Keys.workspaceLayout] = next.workspaceLayout
        }
    }
}
