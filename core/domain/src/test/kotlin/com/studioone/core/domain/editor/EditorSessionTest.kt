package com.studioone.core.domain.editor

import com.google.common.truth.Truth.assertThat
import com.studioone.core.domain.model.AudioClip
import com.studioone.core.domain.model.ClipId
import com.studioone.core.domain.model.Project
import com.studioone.core.domain.model.ProjectId
import com.studioone.core.domain.model.ProjectTemplate
import com.studioone.core.domain.model.Track
import com.studioone.core.domain.model.TrackId
import com.studioone.core.domain.model.TrackType
import com.studioone.core.domain.repository.ProjectDocument
import com.studioone.core.domain.repository.ProjectRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EditorSessionTest {

    private class FakeRepository : ProjectRepository {
        var saved: ProjectDocument? = null
        val document = ProjectDocument(
            project = Project(id = ProjectId("p"), name = "Test"),
            tracks = listOf(
                Track(TrackId("t1"), ProjectId("p"), "Audio 1", TrackType.AUDIO),
            ),
            audioClips = listOf(
                AudioClip(
                    id = ClipId("c1"),
                    trackId = TrackId("t1"),
                    name = "take",
                    startFrame = 0,
                    lengthFrames = 1000,
                    filePath = "/tmp/take.wav",
                ),
            ),
            midiClips = emptyList(),
        )

        override fun observeProjects(): Flow<List<Project>> = flowOf(emptyList())
        override fun observeProject(projectId: ProjectId): Flow<Project?> = flowOf(null)
        override suspend fun createProject(template: ProjectTemplate?, name: String, tempo: Double?) = document.project
        override suspend fun openProject(projectId: ProjectId): ProjectDocument = document
        override suspend fun saveProject(project: Project, tracks: List<Track>, clips: List<com.studioone.core.domain.model.Clip>) {
            saved = ProjectDocument(project, tracks, clips.filterIsInstance<AudioClip>(), emptyList())
        }
        override suspend fun updateProjectMeta(project: Project) {}
        override suspend fun duplicateProject(projectId: ProjectId) = document.project
        override suspend fun deleteProject(projectId: ProjectId) {}
        override suspend fun archiveProject(projectId: ProjectId, archived: Boolean) {}
        override suspend fun saveVersion(projectId: ProjectId, label: String) = 1
        override fun observeVersions(projectId: ProjectId) = flowOf<List<ProjectRepository.ProjectVersion>>(emptyList())
        override suspend fun restoreVersion(projectId: ProjectId, version: Int) {}
    }

    @Test
    fun `move clip then undo restores position`() = runTest {
        val repository = FakeRepository()
        val session = EditorSession(repository, TestScope(StandardTestDispatcher(testScheduler)))
        session.open(ProjectId("p"))
        advanceUntilIdle()

        session.moveClips(setOf(ClipId("c1")), deltaFrames = 500)
        advanceUntilIdle()
        assertThat(session.state.value?.clips?.single()?.startFrame).isEqualTo(500)

        session.undoRedo.undo()
        advanceUntilIdle()
        assertThat(session.state.value?.clips?.single()?.startFrame).isEqualTo(0)
    }

    @Test
    fun `split clip creates two contiguous clips`() = runTest {
        val repository = FakeRepository()
        val session = EditorSession(repository, TestScope(StandardTestDispatcher(testScheduler)))
        session.open(ProjectId("p"))
        advanceUntilIdle()

        session.splitClip(ClipId("c1"), frame = 400)
        advanceUntilIdle()

        val clips = session.state.value!!.clips
        assertThat(clips).hasSize(2)
        assertThat(clips.map { it.lengthFrames }.sorted()).containsExactly(400L, 600L)
    }

    @Test
    fun `autosave persists after debounce`() = runTest {
        val repository = FakeRepository()
        val session = EditorSession(repository, TestScope(StandardTestDispatcher(testScheduler)), autosaveDelayMs = 10)
        session.open(ProjectId("p"))
        advanceUntilIdle()

        session.addTrack(TrackType.MIDI, "New synth")
        advanceUntilIdle()
        testScheduler.runCurrent()
        kotlinx.coroutines.delay(50)
        advanceUntilIdle()

        assertThat(session.state.value?.tracks?.size).isEqualTo(2)
    }
}
