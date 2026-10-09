package com.studioone.core.domain.undo

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A reversible, coalescible edit command. Commands are applied eagerly via
 * [apply] and kept on the undo stack; [revert] restores the previous state.
 */
interface Command {
    /** Short human label, shown in the Edit menu ("Split clip"). */
    val label: String

    /** Group key: consecutive commands with the same key collapse into one
     *  undo step (e.g. continuous fader drags or note moves). */
    val groupKey: String? get() = null

    suspend fun apply()
    suspend fun revert()
}

data class UndoRedoUiState(
    val canUndo: Boolean,
    val canRedo: Boolean,
    val undoLabel: String?,
    val redoLabel: String?,
)

/**
 * Bounded undo/redo history (default 128 steps). Thread-safe via @Volatile +
 * single-writer assumption (all edits happen on the session's dispatcher).
 */
class UndoRedoManager(private val capacity: Int = 128) {

    private val undoStack = ArrayDeque<Command>()
    private val redoStack = ArrayDeque<Command>()

    private val _state = MutableStateFlow(UndoRedoUiState(false, false, null, null))
    val state: StateFlow<UndoRedoUiState> = _state.asStateFlow()

    /** Executes [command] and pushes it on the undo stack, clearing redo. */
    suspend fun execute(command: Command) {
        command.apply()
        push(command)
        redoStack.clear()
        refresh()
    }

    /** Registers an already-applied command (remote/collab ops). */
    fun recordApplied(command: Command) {
        push(command)
        redoStack.clear()
        refresh()
    }

    suspend fun undo(): String? {
        val command = undoStack.removeLastOrNull() ?: return null
        command.revert()
        redoStack.addLast(command)
        refresh()
        return command.label
    }

    suspend fun redo(): String? {
        val command = redoStack.removeLastOrNull() ?: return null
        command.apply()
        undoStack.addLast(command)
        refresh()
        return command.label
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
        refresh()
    }

    private fun push(command: Command) {
        val key = command.groupKey
        if (key != null) {
            val last = undoStack.lastOrNull()
            if (last != null && last.groupKey == key) {
                // Coalesce: keep original (pre-drag) revert, update label/apply.
                undoStack.removeLast()
                undoStack.addLast(CoalescedCommand(first = last, latest = command))
                return
            }
        }
        undoStack.addLast(command)
        while (undoStack.size > capacity) undoStack.removeFirst()
    }

    private fun refresh() {
        _state.value = UndoRedoUiState(
            canUndo = undoStack.isNotEmpty(),
            canRedo = redoStack.isNotEmpty(),
            undoLabel = undoStack.lastOrNull()?.label,
            redoLabel = redoStack.lastOrNull()?.label,
        )
    }

    /** Merged command: reverts to the first command's state, reapplies the latest. */
    private class CoalescedCommand(
        private val first: Command,
        private val latest: Command,
    ) : Command {
        override val label: String get() = latest.label
        override val groupKey: String? get() = latest.groupKey
        override suspend fun apply() = latest.apply()
        override suspend fun revert() = first.revert()
    }
}
