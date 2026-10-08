package com.studioone.mobile.feature.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.studioone.mobile.core.common.DataResult
import com.studioone.mobile.core.domain.CreateProject
import com.studioone.mobile.core.domain.EntitlementRepository
import com.studioone.mobile.core.domain.LimitKind
import com.studioone.mobile.core.domain.ProjectRepository
import com.studioone.mobile.core.model.Project
import com.studioone.mobile.core.model.ProjectId
import com.studioone.mobile.core.model.ProjectTemplate
import com.studioone.mobile.core.model.SyncStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** MVI intent surface for the project browser. */
sealed interface ProjectsIntent {
    data class Search(val query: String) : ProjectsIntent
    data class SetTab(val tab: ProjectsTab) : ProjectsIntent
    data class Create(val name: String, val template: ProjectTemplate, val bpm: Double) : ProjectsIntent
    data class Open(val projectId: ProjectId) : ProjectsIntent
    data class Duplicate(val projectId: ProjectId) : ProjectsIntent
    data class Archive(val projectId: ProjectId, val archived: Boolean) : ProjectsIntent
    data class Delete(val projectId: ProjectId) : ProjectsIntent
    data class ToggleFavorite(val projectId: ProjectId) : ProjectsIntent
    data class Rename(val projectId: ProjectId, val name: String) : ProjectsIntent
}

enum class ProjectsTab { RECENTS, FAVORITES, ARCHIVE, CLOUD }

data class ProjectsUiState(
    val projects: List<Project> = emptyList(),
    val query: String = "",
    val tab: ProjectsTab = ProjectsTab.RECENTS,
    val isLoading: Boolean = true,
    val isCreating: Boolean = false,
    val error: String? = null,
    val limitMessage: String? = null,     // paywall trigger from entitlement check
    val createdProjectId: String? = null, // one-shot navigation signal
    val openedProjectId: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ProjectsViewModel @Inject constructor(
    private val projectRepository: ProjectRepository,
    private val createProject: CreateProject,
    private val entitlementRepository: EntitlementRepository,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val tab = MutableStateFlow(ProjectsTab.RECENTS)
    private val oneShots = MutableStateFlow(OneShots())

    private data class OneShots(
        val createdProjectId: String? = null,
        val openedProjectId: String? = null,
        val error: String? = null,
        val limitMessage: String? = null,
    )

    private val projectsFlow = tab.flatMapLatest { t ->
        when (t) {
            ProjectsTab.ARCHIVE -> projectRepository.observeProjects(includeArchived = true)
            else -> projectRepository.observeProjects()
        }
    }

    val uiState: StateFlow<ProjectsUiState> =
        combine(projectsFlow, query, tab, oneShots) { projects, q, t, shots ->
            val filtered = projects
                .filter { p ->
                    when (t) {
                        ProjectsTab.ARCHIVE -> p.isArchived
                        ProjectsTab.FAVORITES -> p.isFavorite && !p.isArchived
                        ProjectsTab.CLOUD -> !p.isArchived && p.syncStatus != SyncStatus.LOCAL_ONLY
                        ProjectsTab.RECENTS -> !p.isArchived
                    }
                }
                .filter { q.isBlank() || it.name.contains(q, ignoreCase = true) }
                .sortedByDescending { it.lastOpenedAt ?: it.updatedAt }
            ProjectsUiState(
                projects = filtered,
                query = q,
                tab = t,
                isLoading = false,
                createdProjectId = shots.createdProjectId,
                openedProjectId = shots.openedProjectId,
                error = shots.error,
                limitMessage = shots.limitMessage,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProjectsUiState())

    fun onIntent(intent: ProjectsIntent) {
        when (intent) {
            is ProjectsIntent.Search -> query.value = intent.query
            is ProjectsIntent.SetTab -> tab.value = intent.tab
            is ProjectsIntent.Create -> viewModelScope.launch {
                val result = createProject(intent.name, intent.template, intent.bpm)
                when (result) {
                    is DataResult.Success -> oneShots.value = OneShots(createdProjectId = result.data.id.value)
                    is DataResult.Failure -> oneShots.value = OneShots(
                        limitMessage = if (result.error.kind == com.studioone.mobile.core.common.DataError.Kind.ENTITLEMENT)
                            result.error.message else null,
                        error = if (result.error.kind != com.studioone.mobile.core.common.DataError.Kind.ENTITLEMENT)
                            result.error.message else null,
                    )
                    DataResult.Loading -> Unit
                }
            }
            is ProjectsIntent.Open -> viewModelScope.launch {
                projectRepository.markOpened(intent.projectId)
                oneShots.value = OneShots(openedProjectId = intent.projectId.value)
            }
            is ProjectsIntent.Duplicate -> viewModelScope.launch {
                val name = projectRepository.getProject(intent.projectId).getOrNull()?.name ?: "Project"
                projectRepository.duplicateProject(intent.projectId, "$name (copy)")
            }
            is ProjectsIntent.Archive -> viewModelScope.launch {
                projectRepository.archiveProject(intent.projectId, intent.archived)
            }
            is ProjectsIntent.Delete -> viewModelScope.launch {
                projectRepository.deleteProject(intent.projectId)
            }
            is ProjectsIntent.ToggleFavorite -> viewModelScope.launch {
                val p = projectRepository.getProject(intent.projectId).getOrNull() ?: return@launch
                projectRepository.saveProject(p.copy(isFavorite = !p.isFavorite))
            }
            is ProjectsIntent.Rename -> viewModelScope.launch {
                val p = projectRepository.getProject(intent.projectId).getOrNull() ?: return@launch
                projectRepository.saveProject(p.copy(name = intent.name))
            }
        }
    }

    /** Called after navigation consumed the one-shot. */
    fun consumeOneShots() {
        oneShots.value = OneShots()
    }
}
