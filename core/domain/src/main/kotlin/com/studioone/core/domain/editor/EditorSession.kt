package com.studioone.core.domain.editor

import com.studioone.core.domain.model.AudioClip
import com.studioone.core.domain.model.Clip
import com.studioone.core.domain.model.ClipId
import com.studioone.core.domain.model.EffectInstance
import com.studioone.core.domain.model.GridSettings
import com.studioone.core.domain.model.MidiClip
import com.studioone.core.domain.model.MidiNote
import com.studioone.core.domain.model.Project
import com.studioone.core.domain.model.ProjectId
import com.studioone.core.domain.model.ProjectStatus
import com.studioone.core.domain.model.TempoMap
import com.studioone.core.domain.model.Track
import com.studioone.core.domain.model.TrackId
import com.studioone.core.domain.model.TrackType
import com.studioone.core.domain.repository.ProjectRepository
import com.studioone.core.domain.undo.Command
import com.studioone.core.domain.undo.UndoRedoManager
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The editing session for one open project. All timeline mutations funnel
 * through here so that:
 *
 *  1. every edit is an undoable [Command],
 *  2. local edits can be broadcast as CRDT ops for collaboration,
 *  3. remote ops can be applied without clobbering local state,
 *  4. autosave debounces persistence to the repository.
 */
@OptIn(FlowPreview::class)
class EditorSession(
    private val projectRepository: ProjectRepository,
    private val scope: CoroutineScope,
    private val autosaveDelayMs: Long = 1_500,
) {
    private val _state = MutableStateFlow<EditorState?>(null)
    val state: StateFlow<EditorState?> = _state.asStateFlow()

    val undoRedo = UndoRedoManager()

    private var autosaveJob: Job? = null
    private var loaded = false

    val tempoMap: TempoMap?
        get() = _state.value?.let { TempoMap(it.project.sampleRate) }

    /** Loads the project document from the repository into the session. */
    suspend fun open(projectId: ProjectId) {
        val doc = projectRepository.openProject(projectId)
        _state.value = EditorState(
            project = doc.project,
            tracks = doc.tracks.sortedBy { it.orderIndex },
            clips = doc.clips,
        )
        undoRedo.clear()
        loaded = true
    }

    // ------------------------------------------------------------------
    // Track operations
    // ------------------------------------------------------------------

    fun addTrack(type: TrackType, name: String? = null, instrumentId: String? = null) {
        val snapshot = _state.value ?: return
        val track = Track(
            id = TrackId.random(),
            projectId = snapshot.project.id,
            name = name ?: defaultTrackName(type),
            type = type,
            colorIndex = snapshot.tracks.size % TRACK_COLORS,
            orderIndex = snapshot.tracks.size,
            armed = type == TrackType.AUDIO,
            instrumentId = instrumentId,
        )
        scope.launch {
            undoRedo.execute(object : Command {
                override val label = "Add track"
                override suspend fun apply() = mutate { it.copy(tracks = it.tracks + track) }
                override suspend fun revert() = mutate { it.copy(tracks = it.tracks - track) }
            })
        }
    }

    fun removeTrack(trackId: TrackId) {
        val snapshot = _state.value ?: return
        val track = snapshot.track(trackId) ?: return
        val clips = snapshot.clipsOn(trackId)
        scope.launch {
            undoRedo.execute(object : Command {
                override val label = "Delete track"
                override suspend fun apply() = mutate {
                    it.copy(tracks = it.tracks - track, clips = it.clips - clips.toSet())
                }
                override suspend fun revert() = mutate {
                    it.copy(tracks = it.tracks + track, clips = it.clips + clips)
                }
            })
        }
    }

    fun updateTrack(trackId: TrackId, transform: (Track) -> Track) {
        scope.launch {
            val before = _state.value?.track(trackId) ?: return@launch
            val after = transform(before)
            undoRedo.execute(object : Command {
                override val label = "Edit track"
                override val groupKey: String get() = "track:$trackId"
                override suspend fun apply() = replaceTrack(after)
                override suspend fun revert() = replaceTrack(before)
            })
        }
    }

    fun setTrackVolume(trackId: TrackId, volume: Float, finishGesture: Boolean = false) =
        updateTrack(trackId) { if (finishGesture) it.withVolume(volume) else it.copy(volume = volume.coerceIn(0f, 4f)) }

    // ------------------------------------------------------------------
    // Clip operations
    // ------------------------------------------------------------------

    fun addClip(clip: Clip) {
        scope.launch {
            undoRedo.execute(object : Command {
                override val label = "Add clip"
                override suspend fun apply() = mutate { it.copy(clips = it.clips + clip) }
                override suspend fun revert() = mutate { it.copy(clips = it.clips - clip) }
            })
        }
    }

    fun moveClips(clipIds: Set<ClipId>, deltaFrames: Long, deltaTrackOrder: Int = 0) {
        val snapshot = _state.value ?: return
        val affected = snapshot.clips.filter { it.id in clipIds }
        if (affected.isEmpty()) return
        scope.launch {
            undoRedo.execute(object : Command {
                override val label = "Move clip"
                override val groupKey = "move:${clipIds.joinToString { it.value }}"
                override suspend fun apply() = mutate { state ->
                    val trackOrder = state.tracks.sortedBy { t -> t.orderIndex }.map { it.id }
                    state.copy(clips = state.clips.map { clip ->
                        if (clip.id !in clipIds) return@map clip
                        val moved = when (clip) {
                            is AudioClip -> clip.copy(startFrame = (clip.startFrame + deltaFrames).coerceAtLeast(0))
                            is MidiClip -> clip.copy(startFrame = (clip.startFrame + deltaFrames).coerceAtLeast(0))
                        }
                        if (deltaTrackOrder == 0) return@map moved
                        val currentIndex = trackOrder.indexOf(clip.trackId)
                        val newIndex = (currentIndex + deltaTrackOrder).coerceIn(trackOrder.indices)
                        remapTrack(moved, trackOrder[newIndex])
                    })
                }
                override suspend fun revert() = mutate { state ->
                    val trackOrder = state.tracks.sortedBy { t -> t.orderIndex }.map { it.id }
                    state.copy(clips = state.clips.map { clip ->
                        if (clip.id !in clipIds) return@map clip
                        val moved = when (clip) {
                            is AudioClip -> clip.copy(startFrame = (clip.startFrame - deltaFrames).coerceAtLeast(0))
                            is MidiClip -> clip.copy(startFrame = (clip.startFrame - deltaFrames).coerceAtLeast(0))
                        }
                        if (deltaTrackOrder == 0) return@map moved
                        val currentIndex = trackOrder.indexOf(clip.trackId)
                        val newIndex = (currentIndex - deltaTrackOrder).coerceIn(trackOrder.indices)
                        remapTrack(moved, trackOrder[newIndex])
                    })
                }
            })
        }
    }

    /** Splits [clipId] at [frame], producing two clips (trim-safe). */
    fun splitClip(clipId: ClipId, frame: Long) {
        val snapshot = _state.value ?: return
        val original = snapshot.clips.firstOrNull { it.id == clipId } ?: return
        if (frame <= original.startFrame || frame >= original.endFrame) return
        val splitOffset = frame - original.startFrame
        scope.launch {
            undoRedo.execute(object : Command {
                override val label = "Split clip"
                private val leftId = ClipId.random()
                private val rightId = ClipId.random()

                override suspend fun apply() = mutate { state ->
                    val replacement = when (original) {
                        is AudioClip -> listOf(
                            original.copy(id = leftId, lengthFrames = splitOffset, fadeOutFrames = 0),
                            original.copy(
                                id = rightId,
                                startFrame = frame,
                                lengthFrames = original.lengthFrames - splitOffset,
                                sourceOffsetFrames = original.sourceOffsetFrames + splitOffset,
                                fadeInFrames = 0,
                            ),
                        )
                        is MidiClip -> listOf(
                            original.copy(
                                id = leftId,
                                lengthFrames = splitOffset,
                                notes = original.notes.filter { it.startFrame < splitOffset },
                            ),
                            original.copy(
                                id = rightId,
                                startFrame = frame,
                                lengthFrames = original.lengthFrames - splitOffset,
                                notes = original.notes
                                    .filter { it.startFrame >= splitOffset }
                                    .map { it.copy(startFrame = it.startFrame - splitOffset) },
                            ),
                        )
                    }
                    state.copy(clips = state.clips - original + replacement)
                }

                override suspend fun revert() = mutate { state ->
                    state.copy(
                        clips = state.clips
                            .filterNot { it.id == leftId || it.id == rightId } + original,
                    )
                }
            })
        }
    }

    fun deleteClips(clipIds: Set<ClipId>) {
        val snapshot = _state.value ?: return
        val removed = snapshot.clips.filter { it.id in clipIds }
        if (removed.isEmpty()) return
        scope.launch {
            undoRedo.execute(object : Command {
                override val label = "Delete clip"
                override suspend fun apply() = mutate { it.copy(clips = it.clips - removed.toSet(), selection = emptySet()) }
                override suspend fun revert() = mutate { it.copy(clips = it.clips + removed) }
            })
        }
    }

    /** Adds fades to selected/typed clips; [fadeIn] in frames. */
    fun applyFade(clipId: ClipId, fadeInFrames: Long? = null, fadeOutFrames: Long? = null) {
        val original = _state.value?.clips?.firstOrNull { it.id == clipId } ?: return
        scope.launch {
            undoRedo.execute(object : Command {
                override val label = "Fade"
                override suspend fun apply() = replaceClip(
                    when (original) {
                        is AudioClip -> original.copy(
                            fadeInFrames = fadeInFrames ?: original.fadeInFrames,
                            fadeOutFrames = fadeOutFrames ?: original.fadeOutFrames,
                        )
                        is MidiClip -> original
                    },
                )
                override suspend fun revert() = replaceClip(original)
            })
        }
    }

    fun reverseClip(clipId: ClipId) {
        val original = _state.value?.clips?.filterIsInstance<AudioClip>()?.firstOrNull { it.id == clipId } ?: return
        scope.launch {
            undoRedo.execute(object : Command {
                override val label = "Reverse"
                override suspend fun apply() = replaceClip(original.copy(reversed = !original.reversed))
                override suspend fun revert() = replaceClip(original)
            })
        }
    }

    // ------------------------------------------------------------------
    // MIDI note operations
    // ------------------------------------------------------------------

    fun addNote(clipId: ClipId, note: MidiNote) = mutateMidiClip(clipId, "Add note") { it.copy(notes = it.notes + note) }

    fun removeNotes(clipId: ClipId, noteIds: Set<String>) =
        mutateMidiClip(clipId, "Delete note") { clip ->
            clip.copy(notes = clip.notes.filterNot { it.id in noteIds })
        }

    fun replaceNote(clipId: ClipId, before: MidiNote, after: MidiNote) =
        mutateMidiClip(clipId, "Edit note") { clip ->
            clip.copy(notes = clip.notes.map { if (it.id == before.id) after else it })
        }

    private fun mutateMidiClip(clipId: ClipId, label: String, transform: (MidiClip) -> MidiClip) {
        val original = _state.value?.midiClips()?.firstOrNull { it.id == clipId } ?: return
        scope.launch {
            undoRedo.execute(object : Command {
                override val label = label
                override val groupKey = "midi:$clipId"
                override suspend fun apply() = replaceClip(transform(original))
                override suspend fun revert() = replaceClip(original)
            })
        }
    }

    // ------------------------------------------------------------------
    // Effects & automation
    // ------------------------------------------------------------------

    fun addEffect(trackId: TrackId, effect: EffectInstance) =
        updateTrack(trackId) { it.copy(inserts = it.inserts + effect.copy(orderIndex = it.inserts.size)) }

    fun removeEffect(trackId: TrackId, effectInstanceId: String) =
        updateTrack(trackId) { track -> track.copy(inserts = track.inserts.filterNot { e -> e.id == effectInstanceId }) }

    // ------------------------------------------------------------------
    // Transport / selection / view state
    // ------------------------------------------------------------------

    fun setPlayhead(frame: Long) = _state.update { it?.copy(playheadFrame = frame.coerceAtLeast(0)) }
    fun setPlaying(playing: Boolean) = _state.update { it?.copy(isPlaying = playing) }
    fun setRecording(recording: Boolean) = _state.update { it?.copy(isRecording = recording) }
    fun setLoop(enabled: Boolean, start: Long = 0, end: Long = 0) = _state.update {
        it?.copy(loopEnabled = enabled, loopStartFrame = start, loopEndFrame = end)
    }
    fun setGrid(grid: GridSettings) = _state.update { it?.copy(grid = grid) }
    fun setSelection(ids: Set<String>) = _state.update { it?.copy(selection = ids) }

    // ------------------------------------------------------------------
    // Remote collaboration
    // ------------------------------------------------------------------

    /**
     * Applies an op received from a collaborator. Remote ops bypass the local
     * undo stack (undo is per-site; see docs/COLLABORATION.md).
     */
    fun applyRemoteState(project: Project, tracks: List<Track>, clips: List<Clip>) {
        _state.update { current ->
            (current ?: EditorState(project = project)).copy(project = project, tracks = tracks, clips = clips)
        }
        scheduleAutosave()
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    fun save(label: String = "Manual save") {
        val snapshot = _state.value ?: return
        scope.launch {
            projectRepository.saveProject(snapshot.project, snapshot.tracks, snapshot.clips)
            projectRepository.saveVersion(snapshot.project.id, label)
        }
    }

    fun updateProjectMeta(transform: (Project) -> Project) {
        _state.update { it?.copy(project = transform(it.project)) }
        scheduleAutosave()
    }

    private fun scheduleAutosave() {
        if (!loaded) return
        autosaveJob?.cancel()
        autosaveJob = scope.launch {
            delay(autosaveDelayMs)
            val snapshot = _state.value ?: return@launch
            projectRepository.saveProject(snapshot.project, snapshot.tracks, snapshot.clips)
        }
    }

    fun archiveProject() {
        updateProjectMeta { it.copy(status = ProjectStatus.ARCHIVED) }
        save("Archived")
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private suspend fun mutate(transform: suspend (EditorState) -> EditorState) {
        val next = _state.value?.let { transform(it) } ?: return
        _state.value = next
        scheduleAutosave()
    }

    private suspend fun replaceTrack(track: Track) =
        mutate { it.copy(tracks = it.tracks.map { t -> if (t.id == track.id) track else t }) }

    private suspend fun replaceClip(clip: Clip) =
        mutate { it.copy(clips = it.clips.map { c -> if (c.id == clip.id) clip else c }) }

    private fun remapTrack(clip: Clip, trackId: TrackId): Clip = when (clip) {
        is AudioClip -> clip.copy(trackId = trackId)
        is MidiClip -> clip.copy(trackId = trackId)
    }

    private fun defaultTrackName(type: TrackType): String {
        val snapshot = _state.value ?: return if (type == TrackType.AUDIO) "Audio 1" else "Instrument 1"
        val count = snapshot.tracks.count { it.type == type } + 1
        return if (type == TrackType.AUDIO) "Audio $count" else "Instrument $count"
    }

    /** Factory used by DI to scope a session per open project. */
    class Factory @Inject constructor(private val projectRepository: ProjectRepository) {
        fun create(scope: CoroutineScope): EditorSession = EditorSession(projectRepository, scope)
    }

    private companion object {
        const val TRACK_COLORS = 8
    }
}
