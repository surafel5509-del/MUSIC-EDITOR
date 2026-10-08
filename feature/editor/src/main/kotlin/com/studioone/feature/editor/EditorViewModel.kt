package com.studioone.feature.editor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.studioone.audio.AudioEngineController
import com.studioone.core.domain.editor.EditorSession
import com.studioone.core.domain.editor.EditorSessionHolder
import com.studioone.core.domain.editor.EditorState
import com.studioone.core.domain.model.ClipId
import com.studioone.core.domain.model.GridSettings
import com.studioone.core.domain.model.ProjectId
import com.studioone.core.domain.model.SnapDivision
import com.studioone.core.domain.model.TrackId
import com.studioone.core.domain.model.TrackType
import com.studioone.core.domain.repository.ProjectRepository
import com.studioone.core.domain.undo.UndoRedoUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/** UI state for the arrange view beyond the domain [EditorState]. */
data class EditorUiState(
    val zoom: Float = 64f,          // pixels per beat at mdpi baseline
    val tool: EditorTool = EditorTool.SELECT,
    val playheadFrame: Long = 0,
    val undoRedo: UndoRedoUiState = UndoRedoUiState(false, false, null, null),
    val engineReady: Boolean = false,
)

enum class EditorTool { SELECT, DRAW, SPLIT, ERASE, AUTOMATION }

/**
 * ViewModel scoped to one open project (created per navigation back-stack
 * entry). Owns the [EditorSession], mirrors edits into the native engine and
 * drives the playhead clock.
 */
@HiltViewModel
class EditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val projectRepository: ProjectRepository,
    private val engineController: AudioEngineController,
    private val sessionHolder: EditorSessionHolder,
) : ViewModel() {

    private val projectId: ProjectId =
        ProjectId(savedStateHandle.get<String>("projectId").orEmpty())

    /** The editing session for this project. */
    val session = EditorSession(projectRepository, viewModelScope)

    val editorState: StateFlow<EditorState?> = session.state

    private val _ui = MutableStateFlow(EditorUiState())
    val ui: StateFlow<EditorUiState> = _ui.asStateFlow()

    private var playheadJob: Job? = null

    init {
        sessionHolder.session = session
        viewModelScope.launch {
            session.open(projectId)
            // Mirror tracks into the native graph whenever they change.
            session.state.filterNotNull().collect { state ->
                engineController.syncTracks(state.tracks)
                engineController.setTempo(state.project.tempo)
            }
        }
        viewModelScope.launch {
            session.undoRedo.state.collect { _ui.value = _ui.value.copy(undoRedo = it) }
        }
    }

    // ---- Transport ---------------------------------------------------------

    fun play() {
        session.setPlaying(true)
        engineController.play()
        startPlayheadClock()
    }

    fun stop() {
        session.setPlaying(false)
        session.setRecording(false)
        engineController.stopTransport()
        playheadJob?.cancel()
    }

    fun toggleRecord() {
        val recording = editorState.value?.isRecording != true
        session.setRecording(recording)
        if (recording) engineController.record() else engineController.play()
        if (recording) startPlayheadClock() else playheadJob?.cancel()
    }

    fun seek(frame: Long) {
        session.setPlayhead(frame)
        engineController.seek(frame)
    }

    fun toggleLoop() {
        val state = editorState.value ?: return
        session.setLoop(!state.loopEnabled, state.loopStartFrame, state.loopEndFrame)
        engineController.setLoop(!state.loopEnabled, state.loopStartFrame, state.loopEndFrame)
    }

    private fun startPlayheadClock() {
        playheadJob?.cancel()
        playheadJob = viewModelScope.launch {
            while (true) {
                session.setPlayhead(engineController.playhead())
                delay(33) // ~30fps playhead updates
            }
        }
    }

    // ---- Editing -----------------------------------------------------------

    fun addTrack(type: TrackType) = session.addTrack(type)
    fun removeTrack(trackId: TrackId) = session.removeTrack(trackId)
    fun selectClips(ids: Set<String>) = session.setSelection(ids)
    fun moveClips(ids: Set<ClipId>, deltaFrames: Long) = session.moveClips(ids, deltaFrames)
    fun splitClip(clipId: ClipId, frame: Long) = session.splitClip(clipId, frame)
    fun deleteSelected() {
        val selection = editorState.value?.selection ?: return
        session.deleteClips(selection.map(::ClipId).toSet())
    }

    fun setTool(tool: EditorTool) {
        _ui.value = _ui.value.copy(tool = tool)
    }

    fun setSnap(division: SnapDivision) {
        val state = editorState.value ?: return
        session.setGrid(GridSettings(snapEnabled = division != SnapDivision.OFF, snapDivision = division))
    }

    fun setZoom(zoom: Float) {
        _ui.value = _ui.value.copy(zoom = zoom.coerceIn(8f, 512f))
    }

    // ---- Undo/redo -----------------------------------------------------------

    fun undo() = viewModelScope.launch { session.undoRedo.undo() }
    fun redo() = viewModelScope.launch { session.undoRedo.redo() }

    fun save() = session.save()
}
