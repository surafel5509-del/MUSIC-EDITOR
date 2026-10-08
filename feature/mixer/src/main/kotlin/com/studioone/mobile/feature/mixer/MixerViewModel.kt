package com.studioone.mobile.feature.mixer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.studioone.mobile.core.audio.AudioEngineController
import com.studioone.mobile.core.audio.EngineHandleMapping
import com.studioone.mobile.core.audio.MasterMeterFrame
import com.studioone.mobile.core.domain.ProjectRepository
import com.studioone.mobile.core.model.FxSlot
import com.studioone.mobile.core.model.Project
import com.studioone.mobile.core.model.ProjectId
import com.studioone.mobile.core.model.Track
import com.studioone.mobile.core.model.TrackId
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Per-strip meter snapshot polled from the engine at ~30Hz. */
data class StripMeter(
    val peakL: Float = 0f, val peakR: Float = 0f,
    val clipL: Boolean = false, val clipR: Boolean = false,
)

data class MixerUiState(
    val project: Project? = null,
    val stripMeters: Map<String, StripMeter> = emptyMap(),
    val masterMeter: MasterMeterFrame = MasterMeterFrame.EMPTY,
    val spectrum: FloatArray? = null,
    val selectedTrackId: TrackId? = null,
    val showMasterPanel: Boolean = false,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MixerUiState) return false
        return project == other.project && stripMeters == other.stripMeters &&
            masterMeter == other.masterMeter && selectedTrackId == other.selectedTrackId &&
            showMasterPanel == other.showMasterPanel && spectrum.contentEquals(other.spectrum)
    }
    override fun hashCode(): Int {
        var r = project?.hashCode() ?: 0
        r = 31 * r + stripMeters.hashCode()
        r = 31 * r + masterMeter.hashCode()
        r = 31 * r + (spectrum?.contentHashCode() ?: 0)
        return r
    }
}

@HiltViewModel
class MixerViewModel @Inject constructor(
    private val engine: AudioEngineController,
    private val projectRepository: ProjectRepository,
) : ViewModel() {

    private var projectId: ProjectId? = null
    private val _uiState = MutableStateFlow(MixerUiState())
    val uiState: StateFlow<MixerUiState> = _uiState.asStateFlow()

    private val meterBufs = mutableMapOf<Int, FloatArray>()

    fun bind(projectId: ProjectId) {
        this.projectId = projectId
        viewModelScope.launch {
            projectRepository.observeProject(projectId).collect { project ->
                _uiState.value = _uiState.value.copy(project = project)
            }
        }
        startMeterPolling()
    }

    private fun startMeterPolling() {
        viewModelScope.launch {
            val masterBuf = FloatArray(12)
            while (true) {
                val project = _uiState.value.project
                if (project != null) {
                    val meters = mutableMapOf<String, StripMeter>()
                    for (track in project.tracks) {
                        val handle = EngineHandleMapping.handleFor(track.id.value)
                        val buf = meterBufs.getOrPut(handle) { FloatArray(6) }
                        if (enginePullStrip(handle, buf)) {
                            meters[track.id.value] = StripMeter(buf[0], buf[1], buf[4] > 0.5f, buf[5] > 0.5f)
                        }
                    }
                    val spectrum = engine.spectrum.value
                    _uiState.value = _uiState.value.copy(
                        stripMeters = meters,
                        masterMeter = engine.masterMeters.value,
                        spectrum = spectrum,
                    )
                }
                delay(33)
            }
        }
    }

    private fun enginePullStrip(handle: Int, buf: FloatArray): Boolean {
        // Reflection-free direct call through the controller's native wrapper.
        return com.studioone.mobile.core.audio.NativeMeters.pullStrip(engine, handle, buf)
    }

    fun selectTrack(trackId: TrackId?) {
        _uiState.value = _uiState.value.copy(selectedTrackId = trackId)
    }

    fun toggleMasterPanel() {
        _uiState.value = _uiState.value.copy(showMasterPanel = !_uiState.value.showMasterPanel)
    }

    fun setVolume(trackId: TrackId, db: Float) {
        mutateTrack(trackId) { it.copy(volumeDb = db) }
        engine.setStripGain(EngineHandleMapping.handleFor(trackId.value), db)
    }

    fun setPan(trackId: TrackId, pan: Float) {
        mutateTrack(trackId) { it.copy(pan = pan) }
        engine.setStripPan(EngineHandleMapping.handleFor(trackId.value), pan)
    }

    fun toggleMute(trackId: TrackId) {
        mutateTrack(trackId) { it.copy(mute = !it.mute) }
        val t = track(trackId) ?: return
        engine.setStripMute(EngineHandleMapping.handleFor(trackId.value), t.mute)
    }

    fun toggleSolo(trackId: TrackId) {
        mutateTrack(trackId) { it.copy(solo = !it.solo) }
        val t = track(trackId) ?: return
        engine.setStripSolo(EngineHandleMapping.handleFor(trackId.value), t.solo)
    }

    fun toggleArm(trackId: TrackId) {
        mutateTrack(trackId) { it.copy(input = it.input.copy(armed = !it.input.armed)) }
        val t = track(trackId) ?: return
        engine.setStripArmed(EngineHandleMapping.handleFor(trackId.value), t.input.armed)
    }

    fun setSend(trackId: TrackId, index: Int, db: Float) {
        mutateTrack(trackId) { t ->
            val sends = t.sends.toMutableList()
            while (sends.size <= index) {
                sends.add(com.studioone.mobile.core.model.Send(com.studioone.mobile.core.model.BusId("return_1")))
            }
            sends[index] = sends[index].copy(levelDb = db, enabled = db > -100f)
            t.copy(sends = sends)
        }
        engine.setSend(EngineHandleMapping.handleFor(trackId.value), index, db)
    }

    fun setFxParam(trackId: TrackId, slot: Int, paramIndex: Int, value: Float) {
        engine.setFxParam(EngineHandleMapping.handleFor(trackId.value), slot, paramIndex, value)
        mutateTrack(trackId) { t ->
            val inserts = t.inserts.toMutableList()
            if (slot < inserts.size) {
                inserts[slot] = inserts[slot].copy(params = inserts[slot].params + (paramIndex to value))
            }
            t.copy(inserts = inserts)
        }
    }

    fun toggleFxBypass(trackId: TrackId, slot: Int) {
        val track = track(trackId) ?: return
        val fx = track.inserts.getOrNull(slot) ?: return
        engine.setFxBypass(EngineHandleMapping.handleFor(trackId.value), slot, !fx.enabled)
        mutateTrack(trackId) { t ->
            val inserts = t.inserts.toMutableList()
            inserts[slot] = inserts[slot].copy(enabled = !inserts[slot].enabled)
            t.copy(inserts = inserts)
        }
    }

    private fun track(trackId: TrackId): Track? = _uiState.value.project?.track(trackId)

    private fun mutateTrack(trackId: TrackId, transform: (Track) -> Track) {
        val pid = projectId ?: return
        viewModelScope.launch {
            val project = _uiState.value.project ?: return@launch
            val updated = project.copy(
                tracks = project.tracks.map { if (it.id == trackId) transform(it) else it },
            )
            _uiState.value = _uiState.value.copy(project = updated)
            projectRepository.saveProject(updated)
        }
    }
}
