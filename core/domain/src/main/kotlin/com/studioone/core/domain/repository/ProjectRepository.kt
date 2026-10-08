package com.studioone.core.domain.repository

import com.studioone.core.domain.model.AudioClip
import com.studioone.core.domain.model.Clip
import com.studioone.core.domain.model.MidiClip
import com.studioone.core.domain.model.Project
import com.studioone.core.domain.model.ProjectId
import com.studioone.core.domain.model.ProjectTemplate
import com.studioone.core.domain.model.Track
import com.studioone.core.domain.model.TrackId
import kotlinx.coroutines.flow.Flow

/** What the repository returns when opening a project: full document graph. */
data class ProjectDocument(
    val project: Project,
    val tracks: List<Track>,
    val audioClips: List<AudioClip>,
    val midiClips: List<MidiClip>,
) {
    val clips: List<Clip> get() = audioClips + midiClips
}

/**
 * Offline-first project store. All reads come from local storage; writes are
 * applied locally first and queued for sync in the background.
 */
interface ProjectRepository {
    fun observeProjects(): Flow<List<Project>>
    fun observeProject(projectId: ProjectId): Flow<Project?>

    suspend fun createProject(template: ProjectTemplate?, name: String, tempo: Double? = null): Project
    suspend fun openProject(projectId: ProjectId): ProjectDocument
    suspend fun saveProject(project: Project, tracks: List<Track>, clips: List<Clip>)
    suspend fun updateProjectMeta(project: Project)
    suspend fun duplicateProject(projectId: ProjectId): Project
    suspend fun deleteProject(projectId: ProjectId)
    suspend fun archiveProject(projectId: ProjectId, archived: Boolean)
    suspend fun saveVersion(projectId: ProjectId, label: String): Int

    /** Version history for a project (autosave snapshots + manual saves). */
    fun observeVersions(projectId: ProjectId): Flow<List<ProjectVersion>>
    suspend fun restoreVersion(projectId: ProjectId, version: Int)

    data class ProjectVersion(
        val version: Int,
        val label: String,
        val createdAt: kotlinx.datetime.Instant,
        val sizeBytes: Long,
    )
}

interface TemplateRepository {
    suspend fun getTemplates(): List<ProjectTemplate>
    suspend fun getTemplate(id: String): ProjectTemplate?
}
