package com.studioone.mobile.core.domain

import com.studioone.mobile.core.model.Project
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Project-level undo/redo with coalescing.
 *
 * Model: immutable [Project] snapshots on a bounded stack. Mixer/slider edits
 * coalesce while [coalesceKey] repeats within [COALESCE_WINDOW_MS] (dragging a
 * fader produces ONE undo step, not 200). Structural edits (add/remove track,
 * split clip) never coalesce.
 *
 * Memory bound: 48 snapshots; project documents are typically 20-200KB of
 * serialized state, so the worst case is < 10MB. Snapshots hold shared
 * immutable lists (copy-on-write semantics of Kotlin data classes) so the
 * real footprint is far smaller than 48x full documents.
 */
class UndoRedoManager(
    private val maxDepth: Int = 48,
    private val timeSource: () -> Long = System::currentTimeMillis,
) {
    private val undoStack = ArrayDeque<LabeledSnapshot>()
    private val redoStack = ArrayDeque<LabeledSnapshot>()

    private data class LabeledSnapshot(val project: Project, val label: String?, val coalesceKey: String?, val atMs: Long)

    private val _canUndo = MutableStateFlow(false)
    private val _canRedo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    private var current: Project? = null

    /** Baseline: call when a project is opened/replaced externally. */
    fun baseline(project: Project) {
        current = project
        undoStack.clear()
        redoStack.clear()
        publish()
    }

    /**
     * Record a mutation. [newProject] is the post-edit state; [label] shows in
     * the undo tooltip ("Undo: Split clip"); [coalesceKey] groups rapid edits.
     */
    fun push(newProject: Project, label: String? = null, coalesceKey: String? = null) {
        val prev = current ?: run { current = newProject; return }
        val now = timeSource()
        val top = undoStack.lastOrNull()
        val shouldCoalesce = coalesceKey != null &&
            top?.coalesceKey == coalesceKey &&
            now - top.atMs < COALESCE_WINDOW_MS
        if (shouldCoalesce) {
            // Replace the top snapshot's timestamp only — prev state stays.
            undoStack[undoStack.size - 1] = top.copy(atMs = now)
        } else {
            undoStack.addLast(LabeledSnapshot(prev, label, coalesceKey, now))
            if (undoStack.size > maxDepth) undoStack.removeFirst()
        }
        redoStack.clear()
        current = newProject
        publish()
    }

    fun undo(): Project? {
        val snap = undoStack.removeLastOrNull() ?: return null
        val now = current ?: return null
        redoStack.addLast(LabeledSnapshot(now, snap.label, null, timeSource()))
        current = snap.project
        publish()
        return snap.project
    }

    fun redo(): Project? {
        val snap = redoStack.removeLastOrNull() ?: return null
        val now = current ?: return null
        undoStack.addLast(LabeledSnapshot(now, snap.label, snap.coalesceKey, timeSource()))
        current = snap.project
        publish()
        return snap.project
    }

    fun undoLabel(): String? = undoStack.lastOrNull()?.label
    fun redoLabel(): String? = redoStack.lastOrNull()?.label

    private fun publish() {
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = redoStack.isNotEmpty()
    }

    companion object {
        const val COALESCE_WINDOW_MS = 700L
    }
}
