package com.studioone.mobile.feature.instruments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.studioone.mobile.core.audio.AudioEngineController
import com.studioone.mobile.core.audio.EngineHandleMapping
import com.studioone.mobile.core.domain.ProjectRepository
import com.studioone.mobile.core.model.DrumPad
import com.studioone.mobile.core.model.InstrumentFamily
import com.studioone.mobile.core.model.InstrumentId
import com.studioone.mobile.core.model.InstrumentInstance
import com.studioone.mobile.core.model.PresetId
import com.studioone.mobile.core.model.Project
import com.studioone.mobile.core.model.ProjectId
import com.studioone.mobile.core.model.StepPattern
import com.studioone.mobile.core.model.StepRow
import com.studioone.mobile.core.model.TrackId
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class InstrumentsUiState(
    val project: Project? = null,
    val trackId: TrackId? = null,
    val selectedFamily: InstrumentFamily? = null,
    val current: InstrumentInstance? = null,
    val stepPattern: StepPattern? = null,
    val isStepSequencerPlaying: Boolean = false,
    val currentStep: Int = -1,
    val pads: List<DrumPad> = defaultPads(),
)

@HiltViewModel
class InstrumentsViewModel @Inject constructor(
    private val projectRepository: ProjectRepository,
    private val engine: AudioEngineController,
) : ViewModel() {

    private val _uiState = MutableStateFlow(InstrumentsUiState())
    val uiState: StateFlow<InstrumentsUiState> = _uiState.asStateFlow()

    fun bind(projectId: ProjectId, trackId: TrackId) {
        viewModelScope.launch {
            projectRepository.observeProject(projectId).collect { project ->
                val track = project?.track(trackId)
                _uiState.value = _uiState.value.copy(
                    project = project,
                    trackId = trackId,
                    current = track?.instrument,
                    stepPattern = track?.instrument?.stepPattern ?: defaultStepPattern(),
                )
            }
        }
    }

    fun selectFamily(family: InstrumentFamily?) {
        _uiState.value = _uiState.value.copy(selectedFamily = family)
    }

    /** Load an instrument on the bound track (entitlement-gated in the UI). */
    fun loadInstrument(instrument: InstrumentId, presetId: PresetId) {
        val trackId = _uiState.value.trackId ?: return
        val project = _uiState.value.project ?: return
        val updated = project.copy(
            tracks = project.tracks.map {
                if (it.id == trackId) it.copy(
                    instrument = InstrumentInstance(instrument = instrument, presetId = presetId),
                ) else it
            },
        )
        viewModelScope.launch {
            projectRepository.saveProject(updated)
            pushInstrumentToEngine(trackId, instrument, presetId)
        }
        _uiState.value = _uiState.value.copy(current = updated.track(trackId)?.instrument)
    }

    fun setMacro(index: Int, value: Float) {
        val trackId = _uiState.value.trackId ?: return
        val instance = _uiState.value.current ?: return
        val macros = instance.macros.copyOf().also { it[index] = value }
        _uiState.value = _uiState.value.copy(current = instance.copy(macros = macros))
        engine.setMacro(EngineHandleMapping.handleFor(trackId.value), index, value)
        persistInstrument(instance.copy(macros = macros))
    }

    /** Drum pad trigger — live play from the pad grid. */
    fun triggerPad(padIndex: Int, velocity: Int = 110) {
        val trackId = _uiState.value.trackId ?: return
        val handle = EngineHandleMapping.handleFor(trackId.value)
        val pad = _uiState.value.pads.getOrNull(padIndex) ?: return
        engine.sendMidiNoteOn(handle, pad.key, velocity)
        engine.sendMidiNoteOff(handle, pad.key, velocity = 0) // one-shot release handled natively
    }

    /** Step sequencer toggle. */
    fun toggleStep(row: Int, step: Int) {
        val pattern = _uiState.value.stepPattern ?: return
        val rows = pattern.rows.toMutableList()
        val target = rows[row]
        val velocities = target.velocities.copyOf()
        velocities[step] = if (velocities[step] == 0) 110 else 0
        rows[row] = target.copy(velocities = velocities)
        _uiState.value = _uiState.value.copy(stepPattern = pattern.copy(rows = rows))
    }

    fun setStepVelocity(row: Int, step: Int, velocity: Int) {
        val pattern = _uiState.value.stepPattern ?: return
        val rows = pattern.rows.toMutableList()
        val velocities = rows[row].velocities.copyOf()
        velocities[step] = velocity.coerceIn(0, 127)
        rows[row] = rows[row].copy(velocities = velocities)
        _uiState.value = _uiState.value.copy(stepPattern = pattern.copy(rows = rows))
    }

    fun clearPattern() {
        val pattern = _uiState.value.stepPattern ?: return
        _uiState.value = _uiState.value.copy(
            stepPattern = pattern.copy(rows = pattern.rows.map { it.copy(velocities = IntArray(pattern.steps)) }),
        )
    }

    private fun persistInstrument(instance: InstrumentInstance) {
        val trackId = _uiState.value.trackId ?: return
        val project = _uiState.value.project ?: return
        val updated = project.copy(
            tracks = project.tracks.map { if (it.id == trackId) it.copy(instrument = instance) else it },
        )
        viewModelScope.launch { projectRepository.saveProject(updated) }
    }

    private fun pushInstrumentToEngine(trackId: TrackId, instrument: InstrumentId, presetId: PresetId) {
        val handle = EngineHandleMapping.handleFor(trackId.value)
        val type = InstrumentPresetEncoder.nativeType(instrument)
        val blob = InstrumentPresetEncoder.encode(instrument, presetId)
        engine.setInstrument(handle, type, blob)
    }
}

/** Maps Kotlin presets to the native preset-blob wire format (see VoiceManager.cpp). */
object InstrumentPresetEncoder {
    fun nativeType(instrument: InstrumentId): Int = when (instrument) {
        InstrumentId.DRUM_MACHINE, InstrumentId.DRUM_KIT_808, InstrumentId.DRUM_KIT_ACOUSTIC,
        InstrumentId.DRUM_KIT_LIVE -> 5 // kInstDrumMachine
        InstrumentId.SAMPLER, InstrumentId.GRAND_PIANO, InstrumentId.ELECTRIC_PIANO,
        InstrumentId.ORGAN, InstrumentId.STRINGS, InstrumentId.BRASS, InstrumentId.PLUCKS -> 4 // kInstSampler
        InstrumentId.FM_SYNTH -> 2
        InstrumentId.WAVETABLE_SYNTH, InstrumentId.PAD_SYNTH -> 3
        else -> 1 // kInstSubtractive
    }

    /** Factory presets encoded inline (pack presets load zones from SamplePool slots). */
    fun encode(instrument: InstrumentId, presetId: PresetId): FloatArray = when (instrument) {
        InstrumentId.SUBTRACTIVE_SYNTH -> floatArrayOf(
            1f, /*version*/ 1f /*saw*/, 1f /*saw*/, 8f /*detune*/, 0.4f /*mix*/, 0f /*oct*/, 0f,
            2400f /*cutoff*/, 0.35f /*reso*/, 0.5f /*envAmt*/, 0f /*12dB*/,
            2f, 220f, 0.75f, 300f, /*amp A D S R*/
            2f, 500f, 0.3f, 350f, /*flt A D S R*/
            5f /*lfo*/, 0f /*glide*/, 0.8f /*level*/,
        )
        InstrumentId.BASS_SYNTH -> floatArrayOf(
            1f, 1f, 2f /*square*/, 4f, 0.35f, -1f /*oct down*/, 0.25f /*sub*/,
            700f, 0.5f, 0.6f, 1f /*24dB*/,
            1f, 180f, 0.9f, 120f,
            1f, 300f, 0.2f, 200f,
            0f, 0f, 0.9f,
        )
        InstrumentId.PAD_SYNTH -> floatArrayOf(
            1f, 1f, 1f, 14f, 0.55f, 1f, 0f,
            1800f, 0.2f, 0.7f, 0f,
            800f, 1200f, 0.9f, 2500f,
            900f, 1500f, 0.8f, 3000f,
            0.35f, 0f, 0.55f,
        )
        else -> floatArrayOf(1f) // sampler/drum blobs are built when pack zones load
    }
}

fun defaultPads(): List<DrumPad> {
    // GM-flavored 16-pad layout (kick..perc), keys C2..D#3.
    val labels = listOf("Kick", "Snare", "Clap", "Rim", "Hat C", "Hat O", "Tom L", "Tom M",
        "Tom H", "Crash", "Ride", "Shaker", "Cowbell", "Perc 1", "Perc 2", "FX")
    return labels.mapIndexed { i, l ->
        DrumPad(index = i, label = l, sampleId = null, key = 36 + i,
            chokeGroup = if (i == 4 || i == 5) 1 else -1)
    }
}

fun defaultStepPattern(): StepPattern = StepPattern(
    steps = 16,
    rows = (0 until 8).map { pad ->
        StepRow(padIndex = pad, velocities = IntArray(16))
    },
)
