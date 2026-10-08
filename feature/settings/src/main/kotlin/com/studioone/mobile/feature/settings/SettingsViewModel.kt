package com.studioone.mobile.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.studioone.mobile.core.audio.AudioEngineController
import com.studioone.mobile.core.datastore.SettingsRepository
import com.studioone.mobile.core.model.AudioSettings
import com.studioone.mobile.core.model.BitDepth
import com.studioone.mobile.core.model.EngineBufferSize
import com.studioone.mobile.core.model.EngineSampleRate
import com.studioone.mobile.core.model.LatencyReport
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

data class SettingsUiState(
    val settings: AudioSettings = AudioSettings(),
    val latency: LatencyReport? = null,
    val engineBuild: String = "1.0.0",
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val engine: AudioEngineController,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        settingsRepository.audioSettings
            .onEach { _uiState.value = _uiState.value.copy(settings = it) }
            .launchIn(viewModelScope)
        engine.latency
            .onEach { _uiState.value = _uiState.value.copy(latency = it) }
            .launchIn(viewModelScope)
    }

    private fun update(transform: (AudioSettings) -> AudioSettings, restartEngine: Boolean = false) {
        viewModelScope.launch {
            val updated = transform(_uiState.value.settings)
            _uiState.value = _uiState.value.copy(settings = updated)
            settingsRepository.saveAudioSettings(updated)
            if (restartEngine) engine.restart(updated)
        }
    }

    fun setSampleRate(rate: EngineSampleRate) = update({ it.copy(sampleRate = rate) }, restartEngine = true)
    fun setBufferSize(size: EngineBufferSize) = update({ it.copy(bufferSize = size) }, restartEngine = true)
    fun setBitDepth(depth: BitDepth) = update({ it.copy(recordingBitDepth = depth) })
    fun setLowLatency(on: Boolean) = update({ it.copy(lowLatencyMode = on) }, restartEngine = true)
    fun setExclusive(on: Boolean) = update({ it.copy(exclusiveMode = on) }, restartEngine = true)
    fun setLatencyCompensation(ms: Float) = update({ it.copy(recordLatencyCompensationMs = ms) })
    fun setMetronomeRecord(on: Boolean) = update({ it.copy(metronomeDuringRecord = on) })
    fun setMetronomeVolume(v: Float) = update({ it.copy(metronomeVolume = v) })
    fun setCountIn(bars: Int) = update({ it.copy(countInBars = bars) })
}
