package com.studioone.mobile.feature.arranger

import android.Manifest
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.studioone.mobile.core.audio.AudioEngineController
import com.studioone.mobile.core.common.TimeMath
import com.studioone.mobile.core.datastore.SettingsRepository
import com.studioone.mobile.core.domain.EditorActions
import com.studioone.mobile.core.domain.GridMath
import com.studioone.mobile.core.domain.SampleRepository
import com.studioone.mobile.core.model.AudioClip
import com.studioone.mobile.core.model.ClipId
import com.studioone.mobile.core.model.Fade
import com.studioone.mobile.core.model.Project
import com.studioone.mobile.core.model.ProjectId
import com.studioone.mobile.core.model.RecordMode
import com.studioone.mobile.core.model.SnapDivision
import com.studioone.mobile.core.model.Track
import com.studioone.mobile.core.model.TrackId
import com.studioone.mobile.core.model.TrackType
import com.studioone.mobile.core.model.TransportState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class EditorTool { SELECT, SPLIT, ERASE, AUTOMATION, PENCIL }

sealed interface Selection {
    data class Clip(val trackId: TrackId, val clipId: ClipId) : Selection
    data class Track(val trackId: TrackId) : Selection
    data class Region(val startFrame: Long, val endFrame: Long, val trackId: TrackId?) : Selection
    data object None : Selection
}

data class ArrangerUiState(
    val project: Project? = null,
    val pixelsPerFrame: Float = 0.02f,
    val scrollFrames: Long = 0,
    val playheadFrame: Long = 0,
    val transport: TransportState = TransportState.STOPPED,
    val tool: EditorTool = EditorTool.SELECT,
    val selection: Selection = Selection.None,
    val snap: SnapDivision = SnapDivision.SIXTEENTH,
    val snapEnabled: Boolean = true,
    val loopEnabled: Boolean = false,
    val loopStartFrame: Long = 0,
    val loopEndFrame: Long = 0,
    val metronomeOn: Boolean = false,
    val countInBars: Int = 0,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val undoLabel: String? = null,
    val recordingHud: RecordingHud? = null,
    val collaboratorsOnline: Int = 0,
    val positionLabel: String = "1.1.0",
    val zoomFraction: Float = 0.3f,
)

data class RecordingHud(
    val trackName: String,
    val elapsedFrames: Long,
    val inputPeak: Float,
    val takeNumber: Int,
)

@HiltViewModel
class ArrangerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val projectRepository: com.studioone.mobile.core.domain.ProjectRepository,
    private val collaborationRepository: com.studioone.mobile.core.domain.CollaborationRepository,
    private val engine: AudioEngineController,
    private val compiler: com.studioone.mobile.core.audio.EngineGraphCompiler,
    private val settings: SettingsRepository,
    private val sampleRepository: SampleRepository,
) : ViewModel() {

    private val projectId = ProjectId(savedStateHandle.get<String>("projectId")!!)

    private val session = ProjectEditorSession(
        projectRepository = projectRepository,
        collaborationRepository = collaborationRepository,
        engine = engine,
        compiler = compiler,
        scope = viewModelScope,
    )
    private val takePaths = mutableMapOf<Int, String>()

    private val viewState = MutableStateFlow(ArrangerUiState())
    private var activeTakeIndex = -1
    private var recordStartFrame = 0L
    private var recordTrackId: TrackId? = null

    val uiState: StateFlow<ArrangerUiState> = combine(
        viewState,
        session.project,
        engine.transportState,
        engine.positionFrames,
        session.undoRedo.canUndo,
        session.undoRedo.canRedo,
    ) { view, project, transport, position, canUndo, canRedo ->
        val grid = project?.let { GridMath(it.tempoMap, it.timeSignature, it.sampleRate) }
        view.copy(
            project = project,
            transport = transport,
            playheadFrame = position,
            canUndo = canUndo,
            canRedo = canRedo,
            undoLabel = session.undoRedo.undoLabel(),
            positionLabel = project?.let {
                TimeMath.formatBarsBeatsTicks(position, it.tempoMap.bpmAt(0.0), it.sampleRate,
                    it.timeSignature.barLengthBeats)
            } ?: "1.1.0",
            recordingHud = view.recordingHud?.copy(
                elapsedFrames = if (transport == TransportState.RECORDING) position - recordStartFrame else 0,
            ),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ArrangerUiState())

    init {
        val joinCollab = savedStateHandle.get<Boolean>("collab") ?: true
        session.open(projectId, joinCollab)
        viewModelScope.launch {
            val audioSettings = settings.audioSettings.first()
            engine.start(audioSettings)
            // Metronome/count-in from settings.
            engine.setCountIn(audioSettings.countInBars)
        }
    }

    override fun onCleared() {
        session.saveNow()
        session.close()
        super.onCleared()
    }

    // ── Intents ──────────────────────────────────────────────────────────────

    fun onZoom(delta: Float, anchorFrame: Long) {
        viewState.update {
            val newPpf = (it.pixelsPerFrame * delta).coerceIn(0.0005f, 2f)
            val zoomFraction = ((kotlin.math.log10(newPpf / 0.0005f)) / kotlin.math.log10(2f / 0.0005f)).toFloat()
            // Keep the anchor frame under the same screen position.
            val anchorPx = (anchorFrame - it.scrollFrames) * it.pixelsPerFrame
            val newScroll = max(0, anchorFrame - (anchorPx / newPpf).toLong())
            it.copy(pixelsPerFrame = newPpf, scrollFrames = newScroll, zoomFraction = zoomFraction)
        }
    }

    fun onScroll(deltaFrames: Long) {
        viewState.update { it.copy(scrollFrames = max(0, it.scrollFrames + deltaFrames)) }
    }

    fun onScrollTo(frame: Long) {
        viewState.update { it.copy(scrollFrames = max(0, frame)) }
    }

    fun select(selection: Selection) = viewState.update { it.copy(selection = selection) }

    fun setTool(tool: EditorTool) = viewState.update { it.copy(tool = tool) }

    fun setSnap(division: SnapDivision) = viewState.update { it.copy(snap = division) }

    fun toggleSnap() = viewState.update { it.copy(snapEnabled = !it.snapEnabled) }

    fun play() {
        engine.play()
    }

    fun stop() {
        if (viewState.value.transport == TransportState.RECORDING) stopRecording()
        engine.stopTransport()
    }

    fun pause() = engine.pause()

    fun seek(frame: Long) {
        engine.seek(max(0, snapFrame(frame)))
        onScrollTo(max(0, frame - viewState.value.project?.sampleRate?.toLong()?.times(2) ?: 0))
    }

    fun toggleMetronome() {
        val on = !viewState.value.metronomeOn
        viewState.update { it.copy(metronomeOn = on) }
        engine.setMetronome(on, 0.7f)
    }

    fun toggleLoop() {
        viewState.update {
            val enabled = !it.loopEnabled
            val start = it.playheadFrame
            val end = if (enabled) {
                val grid = it.project?.let { p -> GridMath(p.tempoMap, p.timeSignature, p.sampleRate) }
                grid?.barsToFrames(grid.framesToBars(start) + 4) ?: start + 4 * 48000
            } else it.loopEndFrame
            engine.setLoop(enabled, start, end)
            it.copy(loopEnabled = enabled, loopStartFrame = start, loopEndFrame = end)
        }
    }

    fun setLoopRegion(startFrame: Long, endFrame: Long) {
        engine.setLoop(true, startFrame, endFrame)
        viewState.update { it.copy(loopEnabled = true, loopStartFrame = startFrame, loopEndFrame = endFrame) }
    }

    // ── Track & clip editing ─────────────────────────────────────────────────

    fun addTrack(type: TrackType) {
        session.mutate(label = "Add track", op = { p ->
            val track = p.tracks.lastOrNull()
            track?.let { CollabOpFactory.addTrack(it) }
        }) { project ->
            EditorActions.addTrack(project, type)
        }
    }

    fun deleteSelected() {
        val state = viewState.value
        when (val sel = state.selection) {
            is Selection.Clip -> session.mutate(label = "Delete clip") { project ->
                EditorActions.removeClip(project, sel.trackId, sel.clipId)
            }
            is Selection.Track -> session.mutate(label = "Delete track") { project ->
                EditorActions.removeTrack(project, sel.trackId)
            }
            else -> Unit
        }
        viewState.update { it.copy(selection = Selection.None) }
    }

    fun splitAtPlayhead() {
        val state = viewState.value
        val sel = state.selection as? Selection.Clip ?: run {
            // No selection: split every clip under the playhead (razor-all).
            val position = state.playheadFrame
            session.mutate(label = "Split at playhead") { project ->
                var p = project
                for (track in project.tracks) {
                    for (clip in track.clips.filter { position in (it.startFrame + 1) until it.endFrame }) {
                        p = (EditorActions.splitClip(p, track.id, clip.id, position)
                            .getOrNull() ?: p)
                    }
                }
                p
            }
            return
        }
        session.mutate(label = "Split clip") { project ->
            EditorActions.splitClip(project, sel.trackId, sel.clipId, state.playheadFrame).getOrNull() ?: project
        }
    }

    fun moveSelectionTo(frame: Long) {
        val sel = viewState.value.selection as? Selection.Clip ?: return
        val snapped = snapFrame(frame)
        session.mutate(
            label = "Move clip",
            coalesceKey = "move:${sel.clipId.value}",
        ) { project ->
            EditorActions.moveClip(project, sel.trackId, sel.clipId, snapped)
        }
    }

    fun toggleTrackMute(trackId: TrackId) = session.mutate(coalesceKey = "mute:$trackId") { p ->
        EditorActions.updateTrack(p, trackId) { it.copy(mute = !it.mute) }
    }.also { pushMixerParam(trackId) }

    fun toggleTrackSolo(trackId: TrackId) = session.mutate(coalesceKey = "solo:$trackId") { p ->
        EditorActions.updateTrack(p, trackId) { it.copy(solo = !it.solo) }
    }.also { pushMixerParam(trackId) }

    fun toggleTrackArm(trackId: TrackId) {
        session.mutate(coalesceKey = "arm:$trackId") { p ->
            EditorActions.updateTrack(p, trackId) {
                it.copy(input = it.input.copy(armed = !it.input.armed))
            }
        }
        pushMixerParam(trackId)
    }

    fun setTrackVolume(trackId: TrackId, db: Float) {
        session.mutate(coalesceKey = "vol:$trackId") { p ->
            EditorActions.updateTrack(p, trackId) { it.copy(volumeDb = db) }
        }
        val handle = session.project.value?.track(trackId)?.let { engineHandle(it) }
        if (handle != null) engine.setStripGain(handle, db)
    }

    fun setTrackPan(trackId: TrackId, pan: Float) {
        session.mutate(coalesceKey = "pan:$trackId") { p ->
            EditorActions.updateTrack(p, trackId) { it.copy(pan = pan) }
        }
        val handle = session.project.value?.track(trackId)?.let { engineHandle(it) }
        if (handle != null) engine.setStripPan(handle, pan)
    }

    fun renameTrack(trackId: TrackId, name: String) = session.mutate(label = "Rename track") { p ->
        EditorActions.updateTrack(p, trackId) { it.copy(name = name) }
    }

    fun undo() = session.undo()
    fun redo() = session.redo()

    // ── Recording ────────────────────────────────────────────────────────────

    fun startRecording() {
        val project = session.project.value ?: return
        val armed = project.tracks.filter { it.input.armed }
        if (armed.isEmpty()) {
            // Arm the first audio track by default (quick-record UX).
            val first = project.tracks.firstOrNull { it.type == TrackType.AUDIO } ?: return
            toggleTrackArm(first.id)
            startRecordingInternal(project, listOf(first.copy(input = first.input.copy(armed = true))))
            return
        }
        startRecordingInternal(project, armed)
    }

    private fun startRecordingInternal(project: Project, armedTracks: List<Track>) {
        viewModelScope.launch {
            val settings = settings.audioSettings.first()
            recordStartFrame = max(0, engine.positionFrames.value)
            val track = armedTracks.first()
            recordTrackId = track.id
            val takeId = com.studioone.mobile.core.common.IdGenerator.newId()
            val takesDir = java.io.File(context.cacheDir, "takes").apply { mkdirs() }
            val takePath = java.io.File(takesDir, "$takeId.wav").absolutePath
            val handle = engineHandle(track)
            activeTakeIndex = engine.beginTake(handle, takePath, 2, settings.recordingBitDepth.bits)
            takePaths[activeTakeIndex] = takePath
            viewState.update {
                it.copy(recordingHud = RecordingHud(track.name, 0, 0f, 1))
            }
            engine.startRecording()
        }
    }

    fun stopRecording() {
        val takeIndex = activeTakeIndex
        val trackId = recordTrackId
        if (takeIndex < 0 || trackId == null) return
        activeTakeIndex = -1
        viewModelScope.launch {
            engine.stopTransport()
            val frames = engine.endTake(takeIndex)
            viewState.update { it.copy(recordingHud = null) }
            val takePath = takePaths.remove(takeIndex)
            if (frames == null || frames <= 0 || takePath == null) return@launch
            // Import the recorded take as a sample and place it at the record
            // start position (comping layers arrive with version history).
            val imported = sampleRepository.importFromUri(
                android.net.Uri.fromFile(java.io.File(takePath)).toString(), projectId)
            val fileRef = (imported as? com.studioone.mobile.core.common.DataResult.Success)?.data
                ?: return@launch
            val tid = recordTrackId ?: return@launch
            session.mutate(label = "Record take") { project ->
                val clip = AudioClip(
                    id = ClipId(com.studioone.mobile.core.common.IdGenerator.newId()),
                    startFrame = recordStartFrame,
                    lengthFrames = frames,
                    name = "Take ${Clock.System.now().toEpochMilliseconds() % 1000}",
                    fileRef = fileRef,
                    fade = Fade(fadeInFrames = 64, fadeOutFrames = 256),
                )
                EditorActions.addClip(project, tid, clip).getOrNull() ?: project
            }
        }
    }

    fun normalizeSelectedClip() {
        val sel = viewState.value.selection as? Selection.Clip ?: return
        session.mutate(label = "Normalize clip") { project ->
            EditorActions.normalizeClip(project, sel.trackId, sel.clipId)
        }
    }

    fun reverseSelectedClip() {
        val sel = viewState.value.selection as? Selection.Clip ?: return
        session.mutate(label = "Reverse clip") { project ->
            EditorActions.reverseClip(project, sel.trackId, sel.clipId)
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun snapFrame(frame: Long): Long {
        val state = viewState.value
        if (!state.snapEnabled) return frame
        val project = state.project ?: return frame
        val grid = GridMath(project.tempoMap, project.timeSignature, project.sampleRate)
        return grid.snapFrames(frame, state.snap)
    }

    private fun engineHandle(track: Track): Int =
        com.studioone.mobile.core.audio.EngineHandleMapping.handleFor(track.id.value)

    private fun pushMixerParam(trackId: TrackId) {
        val project = session.project.value ?: return
        val track = project.track(trackId) ?: return
        val handle = engineHandle(track)
        engine.setStripMute(handle, track.mute)
        engine.setStripSolo(handle, track.solo)
        engine.setStripArmed(handle, track.input.armed)
    }

    private inline fun MutableStateFlow<ArrangerUiState>.update(block: (ArrangerUiState) -> ArrangerUiState) {
        value = block(value)
    }
}

/** Factory helpers building CollabOps from EditorActions results. */
object CollabOpFactory {
    fun addTrack(track: Track) = com.studioone.mobile.core.model.CollabOp.AddTrack(track)
    fun removeTrack(trackId: TrackId) = com.studioone.mobile.core.model.CollabOp.RemoveTrack(trackId)
}
