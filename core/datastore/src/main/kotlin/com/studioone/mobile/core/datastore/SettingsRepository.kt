package com.studioone.mobile.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.studioone.mobile.core.model.AudioSettings
import com.studioone.mobile.core.model.EngineBufferSize
import com.studioone.mobile.core.model.EngineSampleRate
import com.studioone.mobile.core.model.BitDepth
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "studioone_settings")

/** UI/theme/locale preferences. */
data class UiPreferences(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val highContrast: Boolean = false,
    val colorBlindMode: ColorBlindMode = ColorBlindMode.NONE,
    val largeTouchTargets: Boolean = false,
    val localeOverride: String? = null,   // null = follow system
    val workspaceLayout: WorkspaceLayout = WorkspaceLayout.DEFAULT,
    val showTooltips: Boolean = true,
    val reduceMotion: Boolean = false,
)

enum class ThemeMode { LIGHT, DARK, SYSTEM }
enum class ColorBlindMode { NONE, PROTANOPIA, DEUTERANOPIA, TRITANOPIA }
enum class WorkspaceLayout { DEFAULT, COMPACT, TABLET_DUAL_PANE, CUSTOM }

/** Onboarding funnel state (persisted so force-quit resumes where you left off). */
data class OnboardingState(
    val completed: Boolean = false,
    val step: Int = 0,
    val choseTemplate: String? = null,
    val permissionsGrantedAudio: Boolean = false,
    val tutorialOverlaysDismissed: Set<String> = emptySet(),
)

/**
 * Single typed facade over Preferences DataStore. Features read/write through
 * this repository only — raw DataStore access from features is banned by
 * convention (keeps key registry in one place, see Keys below).
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        // Audio
        val SAMPLE_RATE = intPreferencesKey("audio.sample_rate")
        val BUFFER_SIZE = intPreferencesKey("audio.buffer_size")
        val RECORD_BIT_DEPTH = stringPreferencesKey("audio.record_bit_depth")
        val INPUT_DEVICE = stringPreferencesKey("audio.input_device")
        val OUTPUT_DEVICE = stringPreferencesKey("audio.output_device")
        val LOW_LATENCY = booleanPreferencesKey("audio.low_latency")
        val EXCLUSIVE = booleanPreferencesKey("audio.exclusive")
        val LATENCY_COMP_MS = floatPreferencesKey("audio.latency_comp_ms")
        val METRO_RECORD = booleanPreferencesKey("audio.metro_record")
        val METRO_VOLUME = floatPreferencesKey("audio.metro_volume")
        val COUNT_IN_BARS = intPreferencesKey("audio.count_in_bars")
        // UI
        val THEME_MODE = stringPreferencesKey("ui.theme_mode")
        val HIGH_CONTRAST = booleanPreferencesKey("ui.high_contrast")
        val COLOR_BLIND = stringPreferencesKey("ui.color_blind")
        val LARGE_TARGETS = booleanPreferencesKey("ui.large_targets")
        val LOCALE = stringPreferencesKey("ui.locale")
        val LAYOUT = stringPreferencesKey("ui.layout")
        val TOOLTIPS = booleanPreferencesKey("ui.tooltips")
        val REDUCE_MOTION = booleanPreferencesKey("ui.reduce_motion")
        // Onboarding
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding.done")
        val ONBOARDING_STEP = intPreferencesKey("onboarding.step")
        val ONBOARDING_TEMPLATE = stringPreferencesKey("onboarding.template")
        val PERM_AUDIO = booleanPreferencesKey("onboarding.perm_audio")
        val TUTORIALS_DISMISSED = stringPreferencesKey("onboarding.tutorials_dismissed")
        // Session
        val SESSION_USER_ID = stringPreferencesKey("session.user_id")
        val SESSION_PROVIDER = stringPreferencesKey("session.provider")
        val LAST_PROJECT_ID = stringPreferencesKey("session.last_project_id")
        // Privacy (GDPR)
        val CONSENT_ANALYTICS = booleanPreferencesKey("privacy.consent_analytics")
        val CONSENT_ADS = booleanPreferencesKey("privacy.consent_ads")
        val CONSENT_CRASH = booleanPreferencesKey("privacy.consent_crash")
    }

    val audioSettings: Flow<AudioSettings> = context.settingsDataStore.data.map { p ->
        AudioSettings(
            sampleRate = EngineSampleRate.entries.firstOrNull { it.hz == p[Keys.SAMPLE_RATE] } ?: EngineSampleRate.RATE_48,
            bufferSize = EngineBufferSize.entries.firstOrNull { it.frames == p[Keys.BUFFER_SIZE] } ?: EngineBufferSize.B128,
            recordingBitDepth = p[Keys.RECORD_BIT_DEPTH]?.let { runCatching { BitDepth.valueOf(it) }.getOrNull() } ?: BitDepth.PCM_24,
            inputDeviceId = p[Keys.INPUT_DEVICE],
            outputDeviceId = p[Keys.OUTPUT_DEVICE],
            lowLatencyMode = p[Keys.LOW_LATENCY] ?: true,
            exclusiveMode = p[Keys.EXCLUSIVE] ?: false,
            recordLatencyCompensationMs = p[Keys.LATENCY_COMP_MS] ?: 0f,
            metronomeDuringRecord = p[Keys.METRO_RECORD] ?: true,
            metronomeVolume = p[Keys.METRO_VOLUME] ?: 0.7f,
            countInBars = p[Keys.COUNT_IN_BARS] ?: 0,
        )
    }

    suspend fun saveAudioSettings(settings: AudioSettings) {
        context.settingsDataStore.edit { p ->
            p[Keys.SAMPLE_RATE] = settings.sampleRate.hz
            p[Keys.BUFFER_SIZE] = settings.bufferSize.frames
            p[Keys.RECORD_BIT_DEPTH] = settings.recordingBitDepth.name
            settings.inputDeviceId?.let { p[Keys.INPUT_DEVICE] = it } ?: p.remove(Keys.INPUT_DEVICE)
            settings.outputDeviceId?.let { p[Keys.OUTPUT_DEVICE] = it } ?: p.remove(Keys.OUTPUT_DEVICE)
            p[Keys.LOW_LATENCY] = settings.lowLatencyMode
            p[Keys.EXCLUSIVE] = settings.exclusiveMode
            p[Keys.LATENCY_COMP_MS] = settings.recordLatencyCompensationMs
            p[Keys.METRO_RECORD] = settings.metronomeDuringRecord
            p[Keys.METRO_VOLUME] = settings.metronomeVolume
            p[Keys.COUNT_IN_BARS] = settings.countInBars
        }
    }

    val uiPreferences: Flow<UiPreferences> = context.settingsDataStore.data.map { p ->
        UiPreferences(
            themeMode = p[Keys.THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            highContrast = p[Keys.HIGH_CONTRAST] ?: false,
            colorBlindMode = p[Keys.COLOR_BLIND]?.let { runCatching { ColorBlindMode.valueOf(it) }.getOrNull() } ?: ColorBlindMode.NONE,
            largeTouchTargets = p[Keys.LARGE_TARGETS] ?: false,
            localeOverride = p[Keys.LOCALE],
            workspaceLayout = p[Keys.LAYOUT]?.let { runCatching { WorkspaceLayout.valueOf(it) }.getOrNull() } ?: WorkspaceLayout.DEFAULT,
            showTooltips = p[Keys.TOOLTIPS] ?: true,
            reduceMotion = p[Keys.REDUCE_MOTION] ?: false,
        )
    }

    suspend fun saveUiPreferences(prefs: UiPreferences) {
        context.settingsDataStore.edit { p ->
            p[Keys.THEME_MODE] = prefs.themeMode.name
            p[Keys.HIGH_CONTRAST] = prefs.highContrast
            p[Keys.COLOR_BLIND] = prefs.colorBlindMode.name
            p[Keys.LARGE_TARGETS] = prefs.largeTouchTargets
            prefs.localeOverride?.let { p[Keys.LOCALE] = it } ?: p.remove(Keys.LOCALE)
            p[Keys.LAYOUT] = prefs.workspaceLayout.name
            p[Keys.TOOLTIPS] = prefs.showTooltips
            p[Keys.REDUCE_MOTION] = prefs.reduceMotion
        }
    }

    val onboarding: Flow<OnboardingState> = context.settingsDataStore.data.map { p ->
        OnboardingState(
            completed = p[Keys.ONBOARDING_DONE] ?: false,
            step = p[Keys.ONBOARDING_STEP] ?: 0,
            choseTemplate = p[Keys.ONBOARDING_TEMPLATE],
            permissionsGrantedAudio = p[Keys.PERM_AUDIO] ?: false,
            tutorialOverlaysDismissed = p[Keys.TUTORIALS_DISMISSED]?.split("|")?.filter { it.isNotEmpty() }?.toSet() ?: emptySet(),
        )
    }

    suspend fun saveOnboarding(state: OnboardingState) {
        context.settingsDataStore.edit { p ->
            p[Keys.ONBOARDING_DONE] = state.completed
            p[Keys.ONBOARDING_STEP] = state.step
            state.choseTemplate?.let { p[Keys.ONBOARDING_TEMPLATE] = it }
            p[Keys.PERM_AUDIO] = state.permissionsGrantedAudio
            p[Keys.TUTORIALS_DISMISSED] = state.tutorialOverlaysDismissed.joinToString("|")
        }
    }

    suspend fun dismissTutorial(id: String) {
        context.settingsDataStore.edit { p ->
            val current = p[Keys.TUTORIALS_DISMISSED]?.split("|")?.toSet() ?: emptySet()
            p[Keys.TUTORIALS_DISMISSED] = (current + id).joinToString("|")
        }
    }

    val sessionUserId: Flow<String?> = context.settingsDataStore.data.map { it[Keys.SESSION_USER_ID] }
    val lastProjectId: Flow<String?> = context.settingsDataStore.data.map { it[Keys.LAST_PROJECT_ID] }

    suspend fun saveSession(userId: String?, provider: String?) {
        context.settingsDataStore.edit { p ->
            if (userId == null) { p.remove(Keys.SESSION_USER_ID); p.remove(Keys.SESSION_PROVIDER) }
            else { p[Keys.SESSION_USER_ID] = userId; p[Keys.SESSION_PROVIDER] = provider ?: "" }
        }
    }

    suspend fun setLastProject(projectId: String?) {
        context.settingsDataStore.edit { p ->
            if (projectId == null) p.remove(Keys.LAST_PROJECT_ID) else p[Keys.LAST_PROJECT_ID] = projectId
        }
    }

    /** GDPR consent flags — analytics/ads/crash SDKs must be gated on these. */
    val consentAnalytics: Flow<Boolean> = context.settingsDataStore.data.map { it[Keys.CONSENT_ANALYTICS] ?: false }
    val consentAds: Flow<Boolean> = context.settingsDataStore.data.map { it[Keys.CONSENT_ADS] ?: false }

    suspend fun saveConsents(analytics: Boolean, ads: Boolean, crash: Boolean) {
        context.settingsDataStore.edit { p ->
            p[Keys.CONSENT_ANALYTICS] = analytics
            p[Keys.CONSENT_ADS] = ads
            p[Keys.CONSENT_CRASH] = crash
        }
    }
}
