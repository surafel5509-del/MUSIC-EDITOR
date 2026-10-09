package com.studioone.feature.instruments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.studioone.audio.AudioEngineController
import com.studioone.core.domain.editor.EditorSessionHolder
import com.studioone.core.domain.model.Track
import com.studioone.core.domain.model.TrackType
import com.studioone.core.domain.theory.ArpPattern
import com.studioone.core.domain.theory.Arpeggiator
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class InstrumentsUiState(
    val targetTrackId: String? = null,
    val runningSequencer: Boolean = false,
    val activeStep: Int = -1,
    /** 8 drum rows x 16 steps. */
    val steps: List<BooleanArray> = List(8) { BooleanArray(16) },
    val arpPattern: ArpPattern = ArpPattern.UP,
)

/** GM-ish drum map used by pads + step sequencer. */
val DrumPadNotes = intArrayOf(36, 38, 42, 46, 39, 43, 49, 51, 45, 47, 56, 57, 58, 62, 63, 64)

/**
 * Live-play controller: routes pads/keys/sequencer to the armed MIDI track's
 * native instrument, and hosts the arpeggiator clock.
 */
@HiltViewModel
class InstrumentsViewModel @Inject constructor(
    private val engineController: AudioEngineController,
    private val sessionHolder: EditorSessionHolder,
) : ViewModel() {

    private val _state = MutableStateFlow(InstrumentsUiState())
    val state: StateFlow<InstrumentsUiState> = _state.asStateFlow()

    private var sequencerJob: Job? = null
    private val arpeggiator = Arpeggiator()

    init {
        // Target the first MIDI track by default.
        val session = sessionHolder.session
        val track = session?.state?.value?.tracks?.firstOrNull { it.type == TrackType.MIDI }
        _state.value = _state.value.copy(targetTrackId = track?.id?.value)
    }

    fun noteOn(pitch: Int, velocity: Int = 100) {
        val trackId = _state.value.targetTrackId ?: return
        engineController.noteOn(trackId, pitch, velocity)
    }

    fun noteOff(pitch: Int) {
        val trackId = _state.value.targetTrackId ?: return
        engineController.noteOff(trackId, pitch)
    }

    fun togglePad(index: Int) {
        if (index !in DrumPadNotes.indices) return
        noteOn(DrumPadNotes[index], 110)
        viewModelScope.launch {
            delay(120)
            noteOff(DrumPadNotes[index])
        }
    }

    fun toggleStep(row: Int, step: Int) {
        if (row !in _state.value.steps.indices) return
        val steps = _state.value.steps.map { it.clone() }
        steps[row][step] = !steps[row][step]
        _state.value = _state.value.copy(steps = steps)
    }

    fun toggleSequencer() {
        if (_state.value.runningSequencer) {
            sequencerJob?.cancel()
            _state.value = _state.value.copy(runningSequencer = false, activeStep = -1)
        } else {
            _state.value = _state.value.copy(runningSequencer = true)
            sequencerJob = viewModelScope.launch {
                var step = 0
                val bpm = sessionHolder.session?.state?.value?.project?.tempo ?: 120.0
                val stepMs = (60_000.0 / bpm / 4).toLong() // 16th notes
                while (true) {
                    _state.value = _state.value.copy(activeStep = step)
                    val steps = _state.value.steps
                    steps.forEachIndexed { row, rowSteps ->
                        if (rowSteps[step]) noteOn(DrumPadNotes.getOrElse(row) { 36 }, 100)
                    }
                    delay(stepMs)
                    steps.forEachIndexed { row, rowSteps ->
                        if (rowSteps[step]) noteOff(DrumPadNotes.getOrElse(row) { 36 })
                    }
                    step = (step + 1) % 16
                }
            }
        }
    }

    fun setArpPattern(pattern: ArpPattern) {
        arpeggiator.pattern = pattern
        _state.value = _state.value.copy(arpPattern = pattern)
    }
}
