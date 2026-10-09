package com.studioone.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.studioone.audio.AudioEngineController
import com.studioone.core.domain.repository.AudioSettings
import com.studioone.core.domain.repository.ColorVisionMode
import com.studioone.core.domain.repository.SettingsRepository
import com.studioone.core.domain.repository.ThemeMode
import com.studioone.core.domain.repository.UiSettings
import com.studioone.core.domain.model.user.AuthState
import com.studioone.core.domain.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val authRepository: AuthRepository,
    private val engineController: AudioEngineController,
) : ViewModel() {

    val audioSettings: StateFlow<AudioSettings> = settingsRepository.observeAudioSettings()
        .stateIn(viewModelScope, SharingStarted.Lazily, AudioSettings())

    val uiSettings: StateFlow<UiSettings> = settingsRepository.observeUiSettings()
        .stateIn(viewModelScope, SharingStarted.Lazily, UiSettings())

    val authState: StateFlow<AuthState> = authRepository.observeAuthState()
        .stateIn(viewModelScope, SharingStarted.Lazily, AuthState.SignedOut)

    fun setSampleRate(rate: Int) = updateAudio { it.copy(sampleRate = rate) }
    fun setBufferSize(size: Int) = updateAudio { it.copy(bufferSize = size) }
    fun setLowLatency(enabled: Boolean) = updateAudio { it.copy(useLowLatencyPath = enabled) }
    fun setOpenSlesFallback(enabled: Boolean) = updateAudio { it.copy(useOpenSlesFallback = enabled) }

    fun setTheme(mode: ThemeMode) = updateUi { it.copy(themeMode = mode) }
    fun setHighContrast(enabled: Boolean) = updateUi { it.copy(highContrast = enabled) }
    fun setColorVision(mode: ColorVisionMode) = updateUi { it.copy(colorVisionMode = mode) }
    fun setLargeTargets(enabled: Boolean) = updateUi { it.copy(largeTouchTargets = enabled) }

    /** Runs the latency probe and stores the measurement. */
    fun probeLatency() {
        viewModelScope.launch {
            val ms = engineController.outputLatencyMs()
            settingsRepository.updateAudioSettings {
                it.copy(measuredOutputLatencyFrames = (ms * it.sampleRate / 1000.0).toInt())
            }
        }
    }

    fun signOut() {
        viewModelScope.launch { runCatching { authRepository.signOut() } }
    }

    private fun updateAudio(transform: (AudioSettings) -> AudioSettings) {
        viewModelScope.launch { settingsRepository.updateAudioSettings(transform) }
    }

    private fun updateUi(transform: (UiSettings) -> UiSettings) {
        viewModelScope.launch { settingsRepository.updateUiSettings(transform) }
    }
}
