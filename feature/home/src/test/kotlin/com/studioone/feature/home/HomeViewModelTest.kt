package com.studioone.feature.home

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.studioone.core.domain.model.Project
import com.studioone.core.domain.model.ProjectId
import com.studioone.core.domain.model.ProjectTemplate
import com.studioone.core.domain.model.TemplateCategory
import com.studioone.core.domain.model.TimeSignature
import com.studioone.core.domain.usecase.ArchiveProjectUseCase
import com.studioone.core.domain.usecase.CreateProjectUseCase
import com.studioone.core.domain.usecase.DeleteProjectUseCase
import com.studioone.core.domain.usecase.DuplicateProjectUseCase
import com.studioone.core.domain.usecase.GetProjectsUseCase
import com.studioone.core.domain.usecase.GetTemplatesUseCase
import com.studioone.core.domain.usecase.RenameProjectUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val projectsFlow = MutableStateFlow<List<Project>>(emptyList())

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `create project emits navigation event`() = runTest {
        val project = Project(id = ProjectId("p-1"), name = "Demo")
        val vm = TestHomeViewModelFactory.create(
            projects = projectsFlow,
            createResult = project,
        )

        vm.navigationEvents.test {
            vm.onAction(HomeAction.CreateProject("Demo", null, 120.0))
            assertThat(awaitItem()).isEqualTo("p-1")
        }
    }

    @Test
    fun `search filters projects`() = runTest {
        val a = Project(id = ProjectId("a"), name = "Beat Idea")
        val b = Project(id = ProjectId("b"), name = "Podcast Ep 1")
        projectsFlow.value = listOf(a, b)

        val vm = TestHomeViewModelFactory.create(projects = projectsFlow)
        vm.onAction(HomeAction.Search("podcast"))

        // Filtering happens in the use case; fake emits the filtered list.
        vm.state.test {
            val state = awaitItem()
            assertThat(state.projects.map { it.id }).containsExactly(ProjectId("b"))
        }
    }
}

/** Builds a HomeViewModel wired to fakes without Hilt. */
object TestHomeViewModelFactory {
    fun create(
        projects: Flow<List<Project>> = flowOf(emptyList()),
        createResult: Project? = null,
    ): HomeViewModel {
        val repository = FakeProjectRepository(projects, createResult)
        return HomeViewModel(
            getProjects = GetProjectsUseCase(repository),
            getTemplates = GetTemplatesUseCase(FakeTemplateRepository()),
            createProject = CreateProjectUseCase(repository, FakeTemplateRepository()),
            duplicateProject = DuplicateProjectUseCase(repository),
            deleteProject = DeleteProjectUseCase(repository),
            archiveProject = ArchiveProjectUseCase(repository),
            renameProject = RenameProjectUseCase(repository),
        )
    }
}

class FakeTemplateRepository : com.studioone.core.domain.repository.TemplateRepository {
    override suspend fun getTemplates(): List<ProjectTemplate> = listOf(
        ProjectTemplate("beat", "Beat", "Make a beat", TemplateCategory.BEAT, 140.0, TimeSignature(4, 4), null, emptyList()),
    )
    override suspend fun getTemplate(id: String): ProjectTemplate? = getTemplates().firstOrNull { it.id == id }
}

class FakeProjectRepository(
    private val projects: Flow<List<Project>>,
    private val createResult: Project?,
) : com.studioone.core.domain.repository.ProjectRepository {
    override fun observeProjects(): Flow<List<Project>> = projects
    override fun observeProject(projectId: ProjectId): Flow<Project?> = flowOf(null)
    override suspend fun createProject(
        template: ProjectTemplate?,
        name: String,
        tempo: Double?,
    ): Project = createResult ?: Project(id = ProjectId.random(), name = name)
    override suspend fun openProject(projectId: ProjectId) = throw NotImplementedError()
    override suspend fun saveProject(
        project: Project,
        tracks: List<com.studioone.core.domain.model.Track>,
        clips: List<com.studioone.core.domain.model.Clip>,
    ) = Unit
    override suspend fun updateProjectMeta(project: Project) = Unit
    override suspend fun duplicateProject(projectId: ProjectId): Project = createResult ?: Project(id = ProjectId.random(), name = "copy")
    override suspend fun deleteProject(projectId: ProjectId) = Unit
    override suspend fun archiveProject(projectId: ProjectId, archived: Boolean) = Unit
    override suspend fun saveVersion(projectId: ProjectId, label: String): Int = 1
    override fun observeVersions(projectId: ProjectId) = flowOf<List<com.studioone.core.domain.repository.ProjectRepository.ProjectVersion>>(emptyList())
    override suspend fun restoreVersion(projectId: ProjectId, version: Int) = Unit
}
