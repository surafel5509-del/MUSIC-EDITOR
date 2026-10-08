package com.studioone.core.domain.undo

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class UndoRedoManagerTest {

    private class Counter : Command {
        var value = 0
        override val label = "increment"
        override suspend fun apply() { value++ }
        override suspend fun revert() { value-- }
    }

    @Test
    fun `execute applies command`() = runTest {
        val manager = UndoRedoManager()
        val command = Counter()
        manager.execute(command)
        assertThat(command.value).isEqualTo(1)
        assertThat(manager.state.value.canUndo).isTrue()
    }

    @Test
    fun `undo reverts and redo reapplies`() = runTest {
        val manager = UndoRedoManager()
        val command = Counter()
        manager.execute(command)
        manager.undo()
        assertThat(command.value).isEqualTo(0)
        manager.redo()
        assertThat(command.value).isEqualTo(1)
    }

    @Test
    fun `new command clears redo stack`() = runTest {
        val manager = UndoRedoManager()
        manager.execute(Counter())
        manager.undo()
        manager.execute(Counter())
        assertThat(manager.state.value.canRedo).isFalse()
    }

    @Test
    fun `commands with same group key coalesce`() = runTest {
        val manager = UndoRedoManager()
        val a = object : Command {
            override val label = "drag"
            override val groupKey = "fader"
            override suspend fun apply() {}
            override suspend fun revert() {}
        }
        val b = object : Command {
            override val label = "drag"
            override val groupKey = "fader"
            override suspend fun apply() {}
            override suspend fun revert() {}
        }
        manager.execute(a)
        manager.execute(b)
        manager.undo()
        assertThat(manager.state.value.canUndo).isFalse()
    }
}
