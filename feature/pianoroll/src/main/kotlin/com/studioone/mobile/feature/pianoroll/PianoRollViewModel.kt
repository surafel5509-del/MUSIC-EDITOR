package com.studioone.mobile.feature.pianoroll

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.studioone.mobile.core.common.IdGenerator
import com.studioone.mobile.core.domain.EditorActions
import com.studioone.mobile.core.domain.GridMath
import com.studioone.mobile.core.domain.ProjectRepository
import com.studioone.mobile.core.domain.UndoRedoManager
import com.studioone.mobile.core.midi.MidiQuantizer
import com.studioone.mobile.core.model.ClipId
import com.studioone.mobile.core.model.MidiClip
import com.studioone.mobile.core.model.MidiConstants
import com.studioone.mobile.core.model.MidiNote
import com.studioone.mobile.core.model.MusicalKey
import com.studioone.mobile.core.model.Project
import com.studioone.mobile.core.model.ProjectId
import com.studioone.mobile.core.model.QuantizeOptions
import com.studioone.mobile.core.model.SnapDivision
import com.studioone.mobile.core.model.TrackId
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class PianoRollTool { SELECT, DRAW, ERASE }

data class PianoRollUiState(
    val project: Project? = null,
    val trackId: TrackId? = null,
    val clipId: ClipId? = null,
    val clip: MidiClip? = null,
    val tool: PianoRollTool = PianoRollTool.SELECT,
    val snap: SnapDivision = SnapDivision.SIXTEENTH,
    val drawLengthTicks: Long = MidiConstants.PPQ / 4,
    val drawVelocity: Int = 100,
    val scaleLock: MusicalKey? = null,   // non-null => draw/quantize snaps to scale
    val quantizeOptions: QuantizeOptions = QuantizeOptions(),
    val selectedNoteIds: Set<Long> = emptySet(),
    val pixelsPerTick: Float = 0.25f,
    val scrollTicks: Long = 0,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val undoLabel: String? = null,
    val showVelocityLane: Boolean = true,
)

@HiltViewModel
class PianoRollViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val projectRepository: ProjectRepository,
) : ViewModel() {

    private val projectId = ProjectId(savedStateHandle.get<String>("projectId")!!)
    private val trackId = TrackId(savedStateHandle.get<String>("trackId")!!)
    private val clipId = ClipId(savedStateHandle.get<String>("clipId")!!)

    private val undoRedo = UndoRedoManager()
    private val _uiState = MutableStateFlow(PianoRollUiState())
    val uiState: StateFlow<PianoRollUiState> = _uiState.asStateFlow()

    private var autosaveJob: kotlinx.coroutines.Job? = null
    private var dirty = false

    init {
        viewModelScope.launch {
            projectRepository.observeProject(projectId).collect { project ->
                if (project != null && _uiState.value.project?.documentVersion != project.documentVersion) {
                    val clip = project.track(trackId)?.clips?.filterIsInstance<MidiClip>()
                        ?.firstOrNull { it.id == clipId }
                    _uiState.value = _uiState.value.copy(
                        project = project, trackId = trackId, clipId = clipId, clip = clip,
                        canUndo = undoRedo.canUndo.value, canRedo = undoRedo.canRedo.value,
                    )
                }
            }
        }
        viewModelScope.launch {
            undoRedo.canUndo.collect { _uiState.value = _uiState.value.copy(canUndo = it, undoLabel = undoRedo.undoLabel()) }
        }
        viewModelScope.launch {
            undoRedo.canRedo.collect { _uiState.value = _uiState.value.copy(canRedo = it) }
        }
    }

    private fun mutateClip(label: String, coalesceKey: String? = null, transform: (MidiClip) -> MidiClip) {
        val project = _uiState.value.project ?: return
        val clip = _uiState.value.clip ?: return
        val updated = transform(clip)
        if (updated == clip) return
        val newProject = EditorActions.updateClip(project, trackId, clipId) { updated }.let { it }
        undoRedo.push(newProject, label, coalesceKey)
        _uiState.value = _uiState.value.copy(project = newProject, clip = updated)
        dirty = true
        scheduleAutosave()
    }

    private fun scheduleAutosave() {
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch {
            delay(1500)
            val project = _uiState.value.project
            if (project != null && dirty) {
                projectRepository.saveProject(project)
                dirty = false
            }
        }
    }

    // ── Tool / grid ──────────────────────────────────────────────────────────

    fun setTool(tool: PianoRollTool) { _uiState.value = _uiState.value.copy(tool = tool) }
    fun setSnap(division: SnapDivision) { _uiState.value = _uiState.value.copy(snap = division) }
    fun setDrawLength(ticks: Long) { _uiState.value = _uiState.value.copy(drawLengthTicks = ticks) }
    fun setDrawVelocity(v: Int) { _uiState.value = _uiState.value.copy(drawVelocity = v.coerceIn(1, 127)) }
    fun setZoom(ppTick: Float) { _uiState.value = _uiState.value.copy(pixelsPerTick = ppTick.coerceIn(0.01f, 8f)) }
    fun scrollBy(ticks: Long) { _uiState.value = _uiState.value.copy(scrollTicks = (_uiState.value.scrollTicks + ticks).coerceAtLeast(0)) }
    fun toggleScaleLock() {
        val current = _uiState.value.scaleLock
        val key = _uiState.value.project?.key ?: MusicalKey.C_MAJOR
        _uiState.value = _uiState.value.copy(scaleLock = if (current == null) key else null)
    }
    fun toggleVelocityLane() {
        _uiState.value = _uiState.value.copy(showVelocityLane = !_uiState.value.showVelocityLane)
    }

    // ── Note editing ─────────────────────────────────────────────────────────

    fun addNote(key: Int, startTick: Long) {
        val state = _uiState.value
        val snapped = snapTick(startTick)
        val scaleKey = state.scaleLock?.let { s -> snapToScaleKey(key, s) } ?: key
        val note = MidiNote(
            id = nextNoteId(),
            startTick = snapped.coerceAtLeast(0),
            durationTicks = state.drawLengthTicks,
            key = scaleKey.coerceIn(0, MidiConstants.MAX_KEY),
            velocity = state.drawVelocity,
        )
        mutateClip("Add note") { clip ->
            clip.copy(notes = (clip.notes.filterNot {
                it.key == note.key && it.startTick == note.startTick
            } + note).sortedBy { it.startTick })
        }
    }

    fun deleteNote(noteId: Long) {
        mutateClip("Delete note") { clip -> clip.copy(notes = clip.notes.filterNot { it.id == noteId }) }
    }

    fun deleteSelected() {
        val sel = _uiState.value.selectedNoteIds
        if (sel.isEmpty()) return
        mutateClip("Delete ${sel.size} notes") { clip -> clip.copy(notes = clip.notes.filterNot { it.id in sel }) }
        _uiState.value = _uiState.value.copy(selectedNoteIds = emptySet())
    }

    fun moveNote(noteId: Long, deltaTicks: Long, deltaKeys: Int) {
        mutateClip("Move note", coalesceKey = "move:$noteId") { clip ->
            clip.copy(notes = clip.notes.map {
                if (it.id == noteId) it.copy(
                    startTick = (it.startTick + snapTick(deltaTicks)).coerceAtLeast(0),
                    key = (it.key + deltaKeys).coerceIn(0, MidiConstants.MAX_KEY),
                ) else it
            })
        }
    }

    fun resizeNote(noteId: Long, newDurationTicks: Long) {
        mutateClip("Resize note", coalesceKey = "resize:$noteId") { clip ->
            clip.copy(notes = clip.notes.map {
                if (it.id == noteId) it.copy(durationTicks = snapTick(newDurationTicks).coerceAtLeast(snapTick(1)))
                else it
            })
        }
    }

    fun setVelocity(noteId: Long, velocity: Int) {
        mutateClip("Velocity", coalesceKey = "vel:$noteId") { clip ->
            clip.copy(notes = clip.notes.map { if (it.id == noteId) it.copy(velocity = velocity.coerceIn(1, 127)) else it })
        }
    }

    fun selectNote(noteId: Long, additive: Boolean) {
        val current = _uiState.value.selectedNoteIds
        _uiState.value = _uiState.value.copy(
            selectedNoteIds = if (additive) current + noteId else setOf(noteId),
        )
    }

    fun clearSelection() { _uiState.value = _uiState.value.copy(selectedNoteIds = emptySet()) }

    // ── Bulk tools ───────────────────────────────────────────────────────────

    fun quantizeSelectionOrAll() {
        val state = _uiState.value
        mutateClip("Quantize") { clip ->
            val targets = if (state.selectedNoteIds.isEmpty()) clip.notes
            else clip.notes.filter { it.id in state.selectedNoteIds }
            val quantized = MidiQuantizer.quantizeAll(targets, state.quantizeOptions)
            clip.copy(notes = clip.notes.map { n -> quantized.firstOrNull { it.id == n.id } ?: n })
        }
    }

    fun transposeSelection(semitones: Int) {
        val sel = _uiState.value.selectedNoteIds
        mutateClip("Transpose") { clip ->
            clip.copy(notes = clip.notes.map {
                if (sel.isEmpty() || it.id in sel) it.copy(key = (it.key + semitones).coerceIn(0, 127)) else it
            })
        }
    }

    fun humanizeSelection() {
        val sel = _uiState.value.selectedNoteIds
        mutateClip("Humanize") { clip ->
            val grid = snapTick(1).coerceAtLeast(1)
            val targets = if (sel.isEmpty()) clip.notes else clip.notes.filter { it.id in sel }
            val humanized = MidiQuantizer.humanize(targets, timingTicks = grid / 4, velocityAmount = 8)
            clip.copy(notes = clip.notes.map { n -> humanized.firstOrNull { it.id == n.id } ?: n })
        }
    }

    fun legatoSelection() {
        val sel = _uiState.value.selectedNoteIds
        mutateClip("Legato") { clip ->
            val targets = if (sel.isEmpty()) clip.notes else clip.notes.filter { it.id in sel }
            val legato = MidiQuantizer.legato(targets)
            clip.copy(notes = clip.notes.map { n -> legato.firstOrNull { it.id == n.id } ?: n })
        }
    }

    fun undo() { undoRedo.undo()?.let { applyExternal(it, "Undo") } }
    fun redo() { undoRedo.redo()?.let { applyExternal(it, "Redo") } }

    private fun applyExternal(project: Project, label: String) {
        val clip = project.track(trackId)?.clips?.filterIsInstance<MidiClip>()?.firstOrNull { it.id == clipId }
        _uiState.value = _uiState.value.copy(project = project, clip = clip)
        dirty = true
        scheduleAutosave()
    }

    fun saveNow() {
        val project = _uiState.value.project ?: return
        viewModelScope.launch { projectRepository.saveProject(project) ; dirty = false }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun snapTick(tick: Long): Long {
        val grid = MidiQuantizer.divisionTicks(_uiState.value.snap)
        if (grid <= 0) return tick
        return ((tick + grid / 2) / grid) * grid
    }

    private fun snapToScaleKey(key: Int, scale: MusicalKey): Int {
        if (scale.scale.contains(key % 12, scale.tonicPc)) return key
        for (delta in intArrayOf(-1, 1, -2, 2)) {
            val c = key + delta
            if (c in 0..127 && scale.scale.contains(c % 12, scale.tonicPc)) return c
        }
        return key
    }

    private fun nextNoteId(): Long =
        (_uiState.value.clip?.notes?.maxOfOrNull { it.id } ?: 0) + 1
}
