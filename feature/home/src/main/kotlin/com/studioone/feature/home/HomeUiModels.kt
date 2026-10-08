package com.studioone.feature.home

import com.studioone.core.domain.model.Project
import com.studioone.core.domain.model.ProjectTemplate

/** MVI contract for the project list. */
data class HomeUiState(
    val projects: List<Project> = emptyList(),
    val templates: List<ProjectTemplate> = emptyList(),
    val query: String = "",
    val showArchived: Boolean = false,
    val isLoading: Boolean = true,
    val error: String? = null,
    val showTemplatePicker: Boolean = false,
)

sealed interface HomeAction {
    data object Load : HomeAction
    data class Search(val query: String) : HomeAction
    data class ToggleArchived(val show: Boolean) : HomeAction
    data object OpenProjectCreate : HomeAction
    data object DismissProjectCreate : HomeAction
    data class CreateProject(val name: String, val templateId: String?, val tempo: Double?) : HomeAction
    data class Open(val project: Project) : HomeAction
    data class Duplicate(val project: Project) : HomeAction
    data class Delete(val project: Project) : HomeAction
    data class Archive(val project: Project, val archived: Boolean) : HomeAction
    data class Rename(val project: Project, val newName: String) : HomeAction
}
