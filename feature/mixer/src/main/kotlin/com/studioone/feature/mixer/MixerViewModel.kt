package com.studioone.feature.mixer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.studioone.audio.AudioEngineController
import com.studioone.core.domain.editor.EditorSessionHolder
import com.studioone.core.domain.model.EffectInstance
import com.studioone.core.domain.model.EffectType
import com.studioone.core.domain.model.TrackId
import com.studioone.core.domain.model.fx.EffectCatalog
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class MeterData(
    val peakL: Float = 0f,
    val peakR: Float = 0f,
    val rmsL: Float = 0f,
    val rmsR: Float = 0f,
    val lufsMomentary: Float = -70f,
    val lufsIntegrated: Float = -70f,
    val clipL: Boolean = false,
    val clipR: Boolean = false,
)

data class MixerUiState(
    val masterMeter: MeterData = MeterData(),
    val trackMeters: Map<String, MeterData> = emptyMap(),
    val selectedTrackId: String? = null,
    val showFxSheet: Boolean = false,
)

/**
 * Mixer view-model: polls engine meters at ~30fps and applies strip edits to
 * the shared [EditorSession] + native engine.
 */
@HiltViewModel
class MixerViewModel @Inject constructor(
    val sessionProvider: EditorSessionHolder,
    private val engineController: AudioEngineController,
) : ViewModel() {

    private val _state = MutableStateFlow(MixerUiState())
    val state: StateFlow<MixerUiState> = _state.asStateFlow()

    /** Live track list from the shared editor session (polled: session may bind late). */
    private val _tracks = MutableStateFlow<List<com.studioone.core.domain.model.Track>>(emptyList())
    val tracks: StateFlow<List<com.studioone.core.domain.model.Track>> = _tracks.asStateFlow()

    init {
        viewModelScope.launch {
            while (true) {
                _tracks.value = sessionProvider.session?.state?.value?.tracks.orEmpty()
                delay(250)
            }
        }
        viewModelScope.launch {
            while (true) {
                val m = engineController.readMasterMeter()
                val master = MeterData(m[0], m[1], m[2], m[3], m[4], m[5], m[6] > 0.5f, m[7] > 0.5f)
                val tracks = sessionProvider.session?.state?.value?.tracks?.associate { track ->
                    val t = engineController.readTrackMeter(track.id.value)
                    track.id.value to MeterData(t[0], t[1], t[2], t[3], t[4], t[5], t[6] > 0.5f, t[7] > 0.5f)
                } ?: emptyMap()
                _state.value = _state.value.copy(masterMeter = master, trackMeters = tracks)
                delay(33)
            }
        }
    }

    fun selectTrack(trackId: String?) {
        _state.value = _state.value.copy(selectedTrackId = trackId, showFxSheet = trackId != null)
    }

    fun setVolume(trackId: TrackId, gain: Float, finish: Boolean = false) {
        sessionProvider.session?.updateTrack(trackId) { it.copy(volume = gain.coerceIn(0f, 4f)) }
    }

    fun setPan(trackId: TrackId, pan: Float) {
        sessionProvider.session?.updateTrack(trackId) { it.copy(pan = pan.coerceIn(-1f, 1f)) }
    }

    fun toggleMute(trackId: TrackId) {
        sessionProvider.session?.updateTrack(trackId) { it.copy(muted = !it.muted) }
    }

    fun toggleSolo(trackId: TrackId) {
        sessionProvider.session?.updateTrack(trackId) { it.copy(soloed = !it.soloed) }
    }

    fun addEffect(trackId: TrackId, type: EffectType) {
        sessionProvider.session?.addEffect(
            trackId,
            EffectInstance(type = type, params = EffectCatalog.defaults(type)),
        )
        engineController.applyEffects(trackId.value,
            sessionProvider.session?.state?.value?.track(trackId)?.inserts.orEmpty())
    }

    fun setEffectParameter(trackId: TrackId, insertIndex: Int, paramIndex: Int, value: Float) {
        engineController.setEffectParameter(
            nodeId = 0, // resolved inside the controller by domain id mapping
            insertIndex = insertIndex,
            paramIndex = paramIndex,
            value = value,
        )
    }
}


