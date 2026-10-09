package com.studioone.core.domain.usecase

import com.studioone.core.common.error.StudioOneException
import com.studioone.core.domain.model.Project
import com.studioone.core.domain.model.ProjectId
import com.studioone.core.domain.model.ProjectStatus
import com.studioone.core.domain.model.ProjectTemplate
import com.studioone.core.domain.repository.ProjectDocument
import com.studioone.core.domain.repository.ProjectRepository
import com.studioone.core.domain.repository.TemplateRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Creates a new project, optionally from a template. */
class CreateProjectUseCase @Inject constructor(
    private val projectRepository: ProjectRepository,
    private val templateRepository: TemplateRepository,
) {
    suspend operator fun invoke(
        name: String,
        templateId: String? = null,
        tempo: Double? = null,
    ): Result<Project> {
        if (name.isBlank()) return Result.failure(StudioOneException.Validation("Project name required"))
        val template = templateId?.let { templateRepository.getTemplate(it) }
        return runCatching { projectRepository.createProject(template, name.trim(), tempo) }
    }
}

/** Streams the project list with optional filtering. */
class GetProjectsUseCase @Inject constructor(
    private val projectRepository: ProjectRepository,
) {
    operator fun invoke(
        query: String = "",
        includeArchived: Boolean = false,
    ): Flow<List<Project>> = projectRepository.observeProjects().map { projects ->
        projects
            .filter { includeArchived || it.status == ProjectStatus.ACTIVE }
            .filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
            .sortedByDescending { it.updatedAt }
    }
}

/** Opens a project document for the editor. */
class OpenProjectUseCase @Inject constructor(
    private val projectRepository: ProjectRepository,
) {
    suspend operator fun invoke(projectId: ProjectId): ProjectDocument =
        projectRepository.openProject(projectId)
}

class DuplicateProjectUseCase @Inject constructor(private val projectRepository: ProjectRepository) {
    suspend operator fun invoke(projectId: ProjectId): Project = projectRepository.duplicateProject(projectId)
}

class DeleteProjectUseCase @Inject constructor(private val projectRepository: ProjectRepository) {
    suspend operator fun invoke(projectId: ProjectId) = projectRepository.deleteProject(projectId)
}

class ArchiveProjectUseCase @Inject constructor(private val projectRepository: ProjectRepository) {
    suspend operator fun invoke(projectId: ProjectId, archived: Boolean = true) =
        projectRepository.archiveProject(projectId, archived)
}

class RenameProjectUseCase @Inject constructor(private val projectRepository: ProjectRepository) {
    suspend operator fun invoke(project: Project, newName: String) {
        require(newName.isNotBlank()) { "Name must not be blank" }
        projectRepository.updateProjectMeta(project.copy(name = newName.trim()))
    }
}

class GetTemplatesUseCase @Inject constructor(private val templateRepository: TemplateRepository) {
    suspend operator fun invoke(): List<ProjectTemplate> = templateRepository.getTemplates()
}

/** Streams a single project (used by home cards for live status updates). */
class ObserveProjectUseCase @Inject constructor(private val projectRepository: ProjectRepository) {
    operator fun invoke(projectId: ProjectId): Flow<Project?> = projectRepository.observeProject(projectId)
}

private typealias Result<T> = kotlin.Result<T>
