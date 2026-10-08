package com.studioone.mobile.core.domain

import com.google.common.truth.Truth.assertThat
import com.studioone.mobile.core.model.Project
import com.studioone.mobile.core.model.ProjectId
import kotlinx.datetime.Clock
import org.junit.Test

class UndoRedoManagerTest {

    private fun project(version: Long) = Project(
        id = ProjectId("p1"), ownerId = null, name = "v$version",
        documentVersion = version,
        createdAt = Clock.System.now(), updatedAt = Clock.System.now(),
    )

    private var fakeTime = 0L
    private fun manager() = UndoRedoManager(maxDepth = 8, timeSource = { fakeTime })

    @Test
    fun `undo returns previous snapshot`() {
        val m = manager()
        m.baseline(project(0))
        fakeTime = 1000
        m.push(project(1), "edit A")
        fakeTime = 2000
        m.push(project(2), "edit B")

        assertThat(m.undo()?.documentVersion).isEqualTo(1)
        assertThat(m.undo()?.documentVersion).isEqualTo(0)
        assertThat(m.undo()).isNull()
    }

    @Test
    fun `redo replays undone edits`() {
        val m = manager()
        m.baseline(project(0))
        m.push(project(1))
        m.push(project(2))
        m.undo()
        assertThat(m.redo()?.documentVersion).isEqualTo(2)
        assertThat(m.redo()).isNull()
    }

    @Test
    fun `new push clears redo stack`() {
        val m = manager()
        m.baseline(project(0))
        m.push(project(1))
        m.undo()
        m.push(project(9))
        assertThat(m.canRedo.value).isFalse()
    }

    @Test
    fun `coalesced edits collapse into one undo step`() {
        val m = manager()
        m.baseline(project(0))
        // Fader drag: 50 rapid mutations with the same coalesce key inside the window.
        repeat(50) { i ->
            fakeTime += 10 // 10ms apart, well inside COALESCE_WINDOW_MS
            m.push(project(i.toLong() + 1), coalesceKey = "fader")
        }
        assertThat(m.canUndo.value).isTrue()
        val undone = m.undo()
        // One step returns all the way to the baseline.
        assertThat(undone?.documentVersion).isEqualTo(0)
        assertThat(m.canUndo.value).isFalse()
    }

    @Test
    fun `coalescing expires after the window`() {
        val m = manager()
        m.baseline(project(0))
        fakeTime = 0
        m.push(project(1), coalesceKey = "fader")
        fakeTime = UndoRedoManager.COALESCE_WINDOW_MS + 1
        m.push(project(2), coalesceKey = "fader")
        m.undo() // -> v1
        assertThat(m.undo()?.documentVersion).isEqualTo(0)
    }

    @Test
    fun `depth is bounded`() {
        val m = manager()
        m.baseline(project(0))
        repeat(100) { i -> fakeTime += 1000; m.push(project(i.toLong() + 1)) }
        var undone = 0
        while (m.undo() != null) undone++
        assertThat(undone).isEqualTo(8) // maxDepth
    }
}
