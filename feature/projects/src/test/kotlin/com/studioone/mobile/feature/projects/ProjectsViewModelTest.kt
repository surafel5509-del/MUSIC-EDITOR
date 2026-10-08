package com.studioone.mobile.feature.projects

import com.google.common.truth.Truth.assertThat
import com.studioone.mobile.core.common.DataError
import com.studioone.mobile.core.common.DataResult
import com.studioone.mobile.core.domain.CreateProject
import com.studioone.mobile.core.domain.EntitlementRepository
import com.studioone.mobile.core.domain.LimitKind
import com.studioone.mobile.core.domain.ProjectRepository
import com.studioone.mobile.core.model.Entitlement
import com.studioone.mobile.core.model.MergeOutcome
import com.studioone.mobile.core.model.Product
import com.studioone.mobile.core.model.Project
import com.studioone.mobile.core.model.ProjectId
import com.studioone.mobile.core.model.ProjectTemplate
import com.studioone.mobile.core.model.PurchaseState
import com.studioone.mobile.core.model.TierLimits
import com.studioone.mobile.core.model.SubscriptionTier
import com.studioone.mobile.core.model.UserId
import com.studioone.mobile.core.model.VersionSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Clock
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * ProjectsViewModel MVI tests with fake repositories — no Robolectric, no
 * Hilt. The dispatcher is swapped to a StandardTestDispatcher so flows are
 * deterministic (Turbine asserts exact emission sequences).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProjectsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private class FakeProjectRepository : ProjectRepository {
        val projects = MutableStateFlow<List<Project>>(emptyList())
        var saved: MutableList<Project> = mutableListOf()
        var opened: ProjectId? = null

        override fun observeProjects(includeArchived: Boolean): Flow<List<Project>> = projects
        override fun observeProject(projectId: ProjectId): Flow<Project?> =
            MutableStateFlow(projects.value.firstOrNull { it.id == projectId })
        override suspend fun getProject(projectId: ProjectId) =
            projects.value.firstOrNull { it.id == projectId }?.let { DataResult.Success(it) }
                ?: DataResult.Failure(DataError(DataError.Kind.NOT_FOUND))
        override suspend fun createProject(name: String, template: ProjectTemplate, bpm: Double): DataResult<Project> {
            val p = testProject(name = name, template = template)
            projects.value = projects.value + p
            return DataResult.Success(p)
        }
        override suspend fun saveProject(project: Project, snapshotLabel: String?): DataResult<Long> {
            saved += project
            projects.value = projects.value.map { if (it.id == project.id) project else it }
            return DataResult.Success(project.documentVersion + 1)
        }
        override suspend fun duplicateProject(projectId: ProjectId, newName: String) =
            createProject(newName, ProjectTemplate.EMPTY)
        override suspend fun archiveProject(projectId: ProjectId, archived: Boolean): DataResult<Unit> {
            projects.value = projects.value.map { if (it.id == projectId) it.copy(isArchived = archived) else it }
            return DataResult.Success(Unit)
        }
        override suspend fun deleteProject(projectId: ProjectId): DataResult<Unit> {
            projects.value = projects.value.filterNot { it.id == projectId }
            return DataResult.Success(Unit)
        }
        override suspend fun forkProject(projectId: ProjectId) = createProject("Remix", ProjectTemplate.EMPTY)
        override fun observeVersions(projectId: ProjectId): Flow<List<VersionSnapshot>> = MutableStateFlow(emptyList())
        override suspend fun restoreVersion(versionId: String): DataResult<Project> =
            DataResult.Failure(DataError(DataError.Kind.NOT_FOUND))
        override suspend fun mergeRemote(projectId: ProjectId): DataResult<MergeOutcome> =
            DataResult.Success(MergeOutcome(1, 0, emptyList()))
        override suspend fun lastOpenedProjectId(): ProjectId? = opened
        override suspend fun markOpened(projectId: ProjectId) { opened = projectId }
    }

    private class FakeEntitlements(private val failKind: LimitKind? = null) : EntitlementRepository {
        override val entitlement: Flow<Entitlement> =
            MutableStateFlow(Entitlement(UserId("u"), SubscriptionTier.FREE, TierLimits.FREE))
        override suspend fun refresh() = DataResult.Success(
            Entitlement(UserId("u"), SubscriptionTier.FREE, TierLimits.FREE))
        override suspend fun products(): DataResult<List<Product>> = DataResult.Success(emptyList())
        override suspend fun purchase(productId: String) = DataResult.Success(PurchaseState.PURCHASED)
        override suspend fun restorePurchases() = refresh()
        override suspend fun checkLimit(kind: LimitKind): DataResult<Unit> =
            if (kind == failKind) DataResult.Failure(DataError(DataError.Kind.ENTITLEMENT, kind.message))
            else DataResult.Success(Unit)
    }

    private fun testProject(name: String = "Session", template: ProjectTemplate = ProjectTemplate.EMPTY) =
        Project(
            id = ProjectId(com.studioone.mobile.core.common.IdGenerator.newId()),
            ownerId = UserId("u1"), name = name, template = template,
            createdAt = Clock.System.now(), updatedAt = Clock.System.now(),
        )

    private lateinit var repo: FakeProjectRepository

    private fun viewModel(entitlements: EntitlementRepository = FakeEntitlements()): ProjectsViewModel {
        val createProject = CreateProject(repo, entitlements)
        return ProjectsViewModel(repo, createProject, entitlements)
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repo = FakeProjectRepository()
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** Collect the StateFlow in the test's background scope so WhileSubscribed stays active. */
    private fun kotlinx.coroutines.test.TestScope.observe(vm: ProjectsViewModel) {
        backgroundScope.launch(dispatcher) { vm.uiState.collect { } }
    }

    @Test
    fun `initial state reflects repository projects`() = runTest(dispatcher) {
        repo.projects.value = listOf(testProject("Beat 1"), testProject("Song A"))
        val vm = viewModel()
        observe(vm)
        advanceUntilIdle()
        assertThat(vm.uiState.value.projects.map { it.name }).containsExactly("Beat 1", "Song A")
    }

    @Test
    fun `search filters case-insensitively`() = runTest(dispatcher) {
        repo.projects.value = listOf(testProject("Trap Beat"), testProject("Podcast Ep 1"))
        val vm = viewModel()
        observe(vm)
        vm.onIntent(ProjectsIntent.Search("trap"))
        advanceUntilIdle()
        assertThat(vm.uiState.value.projects.map { it.name }).containsExactly("Trap Beat")
    }

    @Test
    fun `create intent emits created project id for navigation`() = runTest(dispatcher) {
        val vm = viewModel()
        observe(vm)
        vm.onIntent(ProjectsIntent.Create("My Beat", ProjectTemplate.BEAT, 140.0))
        advanceUntilIdle()
        assertThat(vm.uiState.value.createdProjectId).isNotNull()
        assertThat(repo.projects.value).hasSize(1)
    }

    @Test
    fun `create surfaces entitlement limit as paywall message`() = runTest(dispatcher) {
        val vm = viewModel(FakeEntitlements(failKind = LimitKind.PROJECT_COUNT))
        observe(vm)
        vm.onIntent(ProjectsIntent.Create("Fourth", ProjectTemplate.EMPTY, 120.0))
        advanceUntilIdle()
        assertThat(vm.uiState.value.createdProjectId).isNull()
        assertThat(vm.uiState.value.limitMessage).isEqualTo(LimitKind.PROJECT_COUNT.message)
    }

    @Test
    fun `open intent marks project opened and signals navigation`() = runTest(dispatcher) {
        val project = testProject()
        repo.projects.value = listOf(project)
        val vm = viewModel()
        observe(vm)
        vm.onIntent(ProjectsIntent.Open(project.id))
        advanceUntilIdle()
        assertThat(vm.uiState.value.openedProjectId).isEqualTo(project.id.value)
        assertThat(repo.opened).isEqualTo(project.id)
    }

    @Test
    fun `archive intent moves project out of recents tab`() = runTest(dispatcher) {
        val project = testProject()
        repo.projects.value = listOf(project)
        val vm = viewModel()
        observe(vm)
        vm.onIntent(ProjectsIntent.Archive(project.id, true))
        advanceUntilIdle()
        assertThat(vm.uiState.value.projects).isEmpty() // recents excludes archived
    }

    @Test
    fun `delete removes the project`() = runTest(dispatcher) {
        val project = testProject()
        repo.projects.value = listOf(project)
        val vm = viewModel()
        observe(vm)
        vm.onIntent(ProjectsIntent.Delete(project.id))
        advanceUntilIdle()
        assertThat(repo.projects.value).isEmpty()
    }
}
