package com.studioone.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.studioone.core.domain.model.Project
import com.studioone.core.domain.usecase.ArchiveProjectUseCase
import com.studioone.core.domain.usecase.CreateProjectUseCase
import com.studioone.core.domain.usecase.DeleteProjectUseCase
import com.studioone.core.domain.usecase.DuplicateProjectUseCase
import com.studioone.core.domain.usecase.GetProjectsUseCase
import com.studioone.core.domain.usecase.GetTemplatesUseCase
import com.studioone.core.domain.usecase.RenameProjectUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * MVI view-model for the project list. Effects (navigation to the editor)
 * are emitted through [navigationEvents]; state is a single immutable
 * [HomeUiState].
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val getProjects: GetProjectsUseCase,
    private val getTemplates: GetTemplatesUseCase,
    private val createProject: CreateProjectUseCase,
    private val duplicateProject: DuplicateProjectUseCase,
    private val deleteProject: DeleteProjectUseCase,
    private val archiveProject: ArchiveProjectUseCase,
    private val renameProject: RenameProjectUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    /** Navigation side-effects: open project by id. */
    val navigationEvents = MutableSharedFlow<String>(extraBufferCapacity = 4)

    private var observeJob: Job? = null

    init {
        onAction(HomeAction.Load)
    }

    fun onAction(action: HomeAction) {
        when (action) {
            HomeAction.Load -> load()
            is HomeAction.Search -> {
                _state.update { it.copy(query = action.query) }
                observeProjects()
            }
            is HomeAction.ToggleArchived -> {
                _state.update { it.copy(showArchived = action.show) }
                observeProjects()
            }
            HomeAction.OpenProjectCreate -> _state.update { it.copy(showTemplatePicker = true) }
            HomeAction.DismissProjectCreate -> _state.update { it.copy(showTemplatePicker = false) }
            is HomeAction.CreateProject -> create(action)
            is HomeAction.Open -> viewModelScope.launch { navigationEvents.emit(action.project.id.value) }
            is HomeAction.Duplicate -> viewModelScope.launch {
                runCatching { duplicateProject(action.project.id) }
                    .onFailure { e -> _state.update { it.copy(error = e.message) } }
            }
            is HomeAction.Delete -> viewModelScope.launch {
                runCatching { deleteProject(action.project.id) }
                    .onFailure { e -> _state.update { it.copy(error = e.message) } }
            }
            is HomeAction.Archive -> viewModelScope.launch {
                runCatching { archiveProject(action.project.id, action.archived) }
            }
            is HomeAction.Rename -> viewModelScope.launch {
                runCatching { renameProject(action.project, action.newName) }
                    .onFailure { e -> _state.update { it.copy(error = e.message) } }
            }
        }
    }

    private fun load() {
        viewModelScope.launch {
            val templates = runCatching { getTemplates() }.getOrDefault(emptyList())
            _state.update { it.copy(templates = templates, isLoading = false) }
        }
        observeProjects()
    }

    private fun observeProjects() {
        observeJob?.cancel()
        val snapshot = _state.value
        observeJob = viewModelScope.launch {
            getProjects(query = snapshot.query, includeArchived = snapshot.showArchived)
                .collect { projects ->
                    _state.update { it.copy(projects = projects, isLoading = false) }
                }
        }
    }

    private fun create(action: HomeAction.CreateProject) {
        viewModelScope.launch {
            createProject(action.name, action.templateId, action.tempo)
                .onSuccess { project ->
                    _state.update { it.copy(showTemplatePicker = false) }
                    navigationEvents.emit(project.id.value)
                }
                .onFailure { e ->
                    _state.update { it.copy(error = e.message) }
                }
        }
    }
}
