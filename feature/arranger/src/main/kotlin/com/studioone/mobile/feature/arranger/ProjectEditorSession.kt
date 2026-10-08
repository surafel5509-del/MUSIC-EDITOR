package com.studioone.mobile.feature.arranger

import com.studioone.mobile.core.audio.AudioEngineController
import com.studioone.mobile.core.audio.EngineGraphCompiler
import com.studioone.mobile.core.common.DataResult
import com.studioone.mobile.core.domain.CollabSessionHandle
import com.studioone.mobile.core.domain.CollaborationRepository
import com.studioone.mobile.core.domain.ProjectRepository
import com.studioone.mobile.core.domain.UndoRedoManager
import com.studioone.mobile.core.model.CollabOp
import com.studioone.mobile.core.model.Project
import com.studioone.mobile.core.model.ProjectId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The editing session for one open project. All mutations flow through here:
 *
 *   intent -> EditorActions (pure) -> UndoRedoManager -> StateFlow<Project>
 *          -> debounced autosave (Room) -> engine recompile (native)
 *          -> collab op broadcast (when a session is open)
 *
 * This keeps ViewModels thin and guarantees undo/save/sync never diverge.
 */
class ProjectEditorSession(
    private val projectRepository: ProjectRepository,
    private val collaborationRepository: CollaborationRepository,
    private val engine: AudioEngineController,
    private val compiler: EngineGraphCompiler,
    private val scope: CoroutineScope,
) {
    val undoRedo = UndoRedoManager()

    private val _project = MutableStateFlow<Project?>(null)
    val project: StateFlow<Project?> = _project.asStateFlow()

    private var collab: CollabSessionHandle? = null
    private var collabJob: Job? = null
    private var autosaveJob: Job? = null
    private var pendingDirty = false
    private var compiledVersion = -1L

    /** Open a project: load, baseline undo, compile the engine graph, join collab. */
    fun open(projectId: ProjectId, joinCollab: Boolean) {
        scope.launch {
            val result = projectRepository.getProject(projectId)
            val project = (result as? DataResult.Success)?.data ?: return@launch
            undoRedo.baseline(project)
            _project.value = project
            projectRepository.markOpened(projectId)
            compileEngine(project)

            if (joinCollab && project.ownerId != null) {
                val session = collaborationRepository.openSession(projectId, project)
                if (session is DataResult.Success) {
                    collab = session.data
                    collabJob?.cancel()
                    collabJob = scope.launch {
                        session.data.document.collect { merged ->
                            // Remote merges replace local state WITHOUT pushing an
                            // undo entry (remote edits are not locally undoable —
                            // standard collab UX; local undo stack stays coherent
                            // because it only contains our own snapshots).
                            _project.value = merged
                        }
                    }
                }
            }
            startAutosaver()
        }
    }

    fun close() {
        collabJob?.cancel()
        autosaveJob?.cancel()
        collab?.close()
        collab = null
        flushSave()
        _project.value = null
    }

    /**
     * Apply a pure transform. [label] feeds the undo UI; [coalesceKey] groups
     * rapid edits (fader drags); [op] is the CRDT broadcast (null = local-only
     * projects).
     */
    fun mutate(
        label: String? = null,
        coalesceKey: String? = null,
        op: ((Project) -> CollabOp?)? = null,
        transform: (Project) -> Project,
    ) {
        val current = _project.value ?: return
        val updated = transform(current)
        if (updated == current) return
        undoRedo.push(updated, label, coalesceKey)
        _project.value = updated
        pendingDirty = true
        compileEngineIfStructural(current, updated)
        op?.invoke(current)?.let { collabOp ->
            scope.launch { collab?.submit(collabOp) }
        }
    }

    fun undo() {
        undoRedo.undo()?.let { updated ->
            _project.value = updated
            pendingDirty = true
            compileEngine(updated)
        }
    }

    fun redo() {
        undoRedo.redo()?.let { updated ->
            _project.value = updated
            pendingDirty = true
            compileEngine(updated)
        }
    }

    /** Explicit save (on pause/background) with an optional version label. */
    fun saveNow(label: String? = null) {
        val current = _project.value ?: return
        scope.launch {
            projectRepository.saveProject(current, label)
            pendingDirty = false
        }
    }

    private fun flushSave() {
        val current = _project.value
        if (current != null && pendingDirty) {
            kotlinx.coroutines.runBlocking { projectRepository.saveProject(current) }
            pendingDirty = false
        }
    }

    private fun startAutosaver() {
        autosaveJob?.cancel()
        autosaveJob = scope.launch {
            while (true) {
                delay(AUTOSAVE_INTERVAL_MS)
                val current = _project.value
                if (current != null && pendingDirty) {
                    projectRepository.saveProject(current)
                    pendingDirty = false
                }
            }
        }
    }

    /** Mixer-only changes skip a full engine recompile (params were already pushed). */
    private fun compileEngineIfStructural(before: Project, after: Project) {
        val structural = before.tracks.size != after.tracks.size ||
            before.tracks.zip(after.tracks).any { (a, b) ->
                a.id != b.id || a.clips.size != b.clips.size || a.inserts != b.inserts
            } || before.tempoMap != after.tempoMap
        if (structural) compileEngine(after)
    }

    private fun compileEngine(project: Project) {
        if (project.documentVersion == compiledVersion) return
        compiledVersion = project.documentVersion
        compiler.compileProject(project)
    }

    companion object {
        const val AUTOSAVE_INTERVAL_MS = 20_000L
    }
}
