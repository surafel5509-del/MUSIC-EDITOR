package com.studioone.feature.pianoroll

import androidx.lifecycle.ViewModel
import com.studioone.core.domain.editor.EditorSessionHolder
import com.studioone.core.domain.model.ClipId
import com.studioone.core.domain.model.MidiClip
import com.studioone.core.domain.model.MidiNote
import com.studioone.core.domain.model.SnapDivision
import com.studioone.core.domain.theory.Quantizer
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PianoRollTool { SELECT, DRAW, ERASE }

data class PianoRollUiState(
    val clipId: ClipId? = null,
    val clip: MidiClip? = null,
    val tool: PianoRollTool = PianoRollTool.SELECT,
    val snap: SnapDivision = SnapDivision.BEAT_1_16,
    val selectedNotes: Set<String> = emptySet(),
    val velocityEdit: Int = 100,
)

/**
 * Note editing model for one MIDI clip. Edits are pushed through the shared
 * [EditorSessionHolder] so undo/redo and autosave stay global.
 */
@HiltViewModel
class PianoRollViewModel @Inject constructor(
    private val sessionHolder: EditorSessionHolder,
) : ViewModel() {

    private val _state = MutableStateFlow(PianoRollUiState())
    val state: StateFlow<PianoRollUiState> = _state.asStateFlow()

    private val sampleRate: Int
        get() = sessionHolder.session?.state?.value?.project?.sampleRate ?: 44_100
    private val bpm: Double
        get() = sessionHolder.session?.state?.value?.project?.tempo ?: 120.0

    /** Opens the clip currently selected in the arrange view (first MIDI clip if none). */
    fun openClip(clipId: ClipId?) {
        val session = sessionHolder.session ?: return
        val editorState = session.state.value ?: return
        val clip = clipId?.let { id -> editorState.midiClips().firstOrNull { it.id == id } }
            ?: editorState.midiClips().firstOrNull()
        _state.value = _state.value.copy(clipId = clip?.id, clip = clip)
    }

    fun setTool(tool: PianoRollTool) {
        _state.value = _state.value.copy(tool = tool)
    }

    fun setSnap(snap: SnapDivision) {
        _state.value = _state.value.copy(snap = snap)
    }

    fun quantizeSelection() {
        val clip = _state.value.clip ?: return
        val selected = _state.value.selectedNotes
        if (selected.isEmpty()) return
        val quantized = clip.notes.map { note ->
            if (note.id in selected) {
                Quantizer.quantize(note, sampleRate, bpm, _state.value.snap)
            } else note
        }
        updateClip(clip.copy(notes = quantized))
    }

    /** Draws a note at the snapped position. */
    fun addNote(startFrame: Long, pitch: Int, lengthFrames: Long) {
        val clip = _state.value.clip ?: return
        val snapped = Quantizer.quantizeStart(startFrame, sampleRate, bpm, _state.value.snap)
        val note = MidiNote(
            startFrame = snapped.coerceIn(0, clip.lengthFrames),
            lengthFrames = lengthFrames.coerceAtLeast(Quantizer.framesPerBeat(sampleRate, bpm) / 8),
            pitch = pitch,
            velocity = _state.value.velocityEdit,
        )
        sessionHolder.session?.addNote(clip.id, note)
        refresh()
    }

    fun eraseNote(noteId: String) {
        val clip = _state.value.clip ?: return
        sessionHolder.session?.removeNotes(clip.id, setOf(noteId))
        refresh()
    }

    fun moveNote(note: MidiNote, newStart: Long, newPitch: Int) {
        val clip = _state.value.clip ?: return
        val snapped = Quantizer.quantizeStart(newStart, sampleRate, bpm, _state.value.snap)
        sessionHolder.session?.replaceNote(
            clip.id,
            note,
            note.copy(startFrame = snapped.coerceAtLeast(0), pitch = newPitch.coerceIn(0, 127)),
        )
        refresh()
    }

    fun setVelocity(velocity: Int) {
        _state.value = _state.value.copy(velocityEdit = velocity.coerceIn(1, 127))
    }

    private fun updateClip(updated: MidiClip) {
        val clip = _state.value.clip ?: return
        sessionHolder.session?.removeNotes(clip.id, clip.notes.map { it.id }.toSet())
        updated.notes.forEach { sessionHolder.session?.addNote(clip.id, it) }
        refresh()
    }

    fun refresh() {
        val session = sessionHolder.session ?: return
        val clipId = _state.value.clipId ?: return
        val clip = session.state.value?.midiClips()?.firstOrNull { it.id == clipId }
        _state.value = _state.value.copy(clip = clip)
    }
}
