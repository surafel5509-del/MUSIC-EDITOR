package com.studioone.feature.recorder

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.studioone.audio.AudioEngineConfig
import com.studioone.audio.AudioEngineController
import com.studioone.core.domain.repository.AudioSettings
import com.studioone.core.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class RecorderUiState(
    val isRecording: Boolean = false,
    val elapsedFrames: Long = 0,
    val inputId: String = "default-mic",
    val availableInputs: List<InputOption> = emptyList(),
    val gain: Float = 1f,
    val monitoringEnabled: Boolean = true,
    val countInBars: Int = 1,
    val latencyMs: Double = 0.0,
    val sampleRate: Int = 44_100,
    val bufferSize: Int = 128,
    val meter: FloatArray = FloatArray(8),
) {
    data class InputOption(val id: String, val label: String, val type: String)
}

/**
 * Drives the capture pipeline: configures the engine from audio settings,
 * starts/stops the WAV writer and streams meter data to the UI.
 */
@HiltViewModel
class RecordViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engineController: AudioEngineController,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(RecorderUiState())
    val state: StateFlow<RecorderUiState> = _state.asStateFlow()

    private var outputFile: File? = null

    init {
        viewModelScope.launch {
            settingsRepository.observeAudioSettings().collect { settings ->
                _state.value = _state.value.copy(
                    sampleRate = settings.sampleRate,
                    bufferSize = settings.bufferSize,
                    monitoringEnabled = settings.monitoringEnabled,
                    countInBars = settings.countInBars,
                )
                ensureEngine(settings)
            }
        }
        viewModelScope.launch {
            while (true) {
                _state.value = _state.value.copy(meter = engineController.readMasterMeter())
                kotlinx.coroutines.delay(33)
            }
        }
    }

    private suspend fun ensureEngine(settings: AudioSettings) {
        engineController.start(
            AudioEngineConfig(
                sampleRate = settings.sampleRate,
                bufferSize = settings.bufferSize,
                inputEnabled = true,
                useLowLatency = settings.useLowLatencyPath,
                forceOpenSlesFallback = settings.useOpenSlesFallback,
            ),
        )
        _state.value = _state.value.copy(latencyMs = engineController.outputLatencyMs())
    }

    fun setInput(id: String) {
        _state.value = _state.value.copy(inputId = id)
    }

    fun setGain(gain: Float) {
        _state.value = _state.value.copy(gain = gain)
    }

    fun setMonitoring(enabled: Boolean) {
        _state.value = _state.value.copy(monitoringEnabled = enabled)
        viewModelScope.launch {
            settingsRepository.updateAudioSettings { it.copy(monitoringEnabled = enabled) }
        }
    }

    fun toggleRecording() {
        if (_state.value.isRecording) stopRecording() else startRecording()
    }

    private fun startRecording() {
        val dir = File(context.filesDir, "recordings").apply { mkdirs() }
        val file = File(dir, "take-${System.currentTimeMillis()}.wav")
        outputFile = file
        viewModelScope.launch {
            val started = engineController.startRecording(file.absolutePath, bitDepth = 24)
            if (started) {
                engineController.record()
                _state.value = _state.value.copy(isRecording = true, elapsedFrames = 0)
            }
        }
    }

    private fun stopRecording() {
        engineController.stopRecording()
        engineController.play()
        _state.value = _state.value.copy(isRecording = false)
        // The take file is handed back to the editor via navigation result
        // (see RecorderScreen); the clip is created on the domain side.
    }

    /** Returns the last recorded WAV path (consumed by the editor). */
    fun consumeRecordedFile(): String? {
        val path = outputFile?.absolutePath
        outputFile = null
        return path
    }
}
