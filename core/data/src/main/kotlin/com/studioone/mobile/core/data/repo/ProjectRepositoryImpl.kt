package com.studioone.mobile.core.data.repo

import com.studioone.mobile.core.common.DataError
import com.studioone.mobile.core.common.DataResult
import com.studioone.mobile.core.common.IdGenerator
import com.studioone.mobile.core.data.mapper.ProjectSerializer
import com.studioone.mobile.core.data.sync.SyncScheduler
import com.studioone.mobile.core.database.dao.OpLogDao
import com.studioone.mobile.core.database.dao.ProjectDao
import com.studioone.mobile.core.database.dao.VersionDao
import com.studioone.mobile.core.database.entity.VersionEntity
import com.studioone.mobile.core.domain.EditorActions
import com.studioone.mobile.core.domain.ProjectRepository
import com.studioone.mobile.core.model.MergeOutcome
import com.studioone.mobile.core.model.Project
import com.studioone.mobile.core.model.ProjectId
import com.studioone.mobile.core.model.ProjectTemplate
import com.studioone.mobile.core.model.VersionSnapshot
import com.studioone.mobile.core.model.UserId
import com.studioone.mobile.core.datastore.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * Offline-first project storage.
 *
 * Write path: save -> Room (dirty=1) -> schedule sync. The cloud copy is
 * updated by SyncEngine with the full document + version CAS; concurrent
 * remote edits surface as [MergeOutcome] conflicts (docs/COLLABORATION.md §5).
 * Autosave is debounced by the editor session (ProjectEditorSession in
 * feature:arranger) which calls save() with the coalesced document.
 */
@Singleton
class ProjectRepositoryImpl @Inject constructor(
    private val projectDao: ProjectDao,
    private val versionDao: VersionDao,
    private val opLogDao: OpLogDao,
    private val settings: SettingsRepository,
    private val syncScheduler: SyncScheduler,
    private val authState: CurrentUserProvider,
) : ProjectRepository {

    override fun observeProjects(includeArchived: Boolean): Flow<List<Project>> =
        projectDao.observeProjects(ownerId = null, archived = includeArchived)
            .map { list -> list.mapNotNull(ProjectSerializer::toModel) }

    override fun observeProject(projectId: ProjectId): Flow<Project?> =
        projectDao.observeById(projectId.value).map(ProjectSerializer::toModel)

    override suspend fun getProject(projectId: ProjectId): DataResult<Project> {
        val entity = projectDao.byId(projectId.value)
            ?: return DataResult.Failure(DataError(DataError.Kind.NOT_FOUND, "Project not found"))
        val model = ProjectSerializer.toModel(entity)
            ?: return DataResult.Failure(DataError(DataError.Kind.UNKNOWN, "Project document corrupt"))
        return DataResult.Success(model)
    }

    override suspend fun createProject(name: String, template: ProjectTemplate, bpm: Double): DataResult<Project> {
        val user = authState.currentUserId()
        val project = EditorActions.newProject(name, template, bpm, user?.let { UserId(it) })
        projectDao.upsert(ProjectSerializer.toEntity(project))
        settings.setLastProject(project.id.value)
        return DataResult.Success(project)
    }

    override suspend fun saveProject(project: Project, snapshotLabel: String?): DataResult<Long> {
        val now = Clock.System.now()
        val bumped = project.copy(updatedAt = now, documentVersion = project.documentVersion + 1)
        projectDao.upsert(ProjectSerializer.toEntity(bumped, dirty = true))
        if (snapshotLabel != null) {
            versionDao.upsert(
                VersionEntity(
                    id = IdGenerator.newId(),
                    projectId = project.id.value,
                    label = snapshotLabel,
                    documentVersion = bumped.documentVersion,
                    createdBy = authState.currentUserId(),
                    createdAt = now.toEpochMilliseconds(),
                    isAutosave = false,
                    storagePath = null,
                    document = ProjectSerializer.encode(bumped),
                ),
            )
        }
        syncScheduler.scheduleProjectSync(bumped.id.value)
        return DataResult.Success(bumped.documentVersion)
    }

    /** Debounced autosave snapshot (pruned to the tier's history window). */
    suspend fun autosave(project: Project) {
        val now = Clock.System.now()
        versionDao.upsert(
            VersionEntity(
                id = IdGenerator.newId(),
                projectId = project.id.value,
                label = "Autosave",
                documentVersion = project.documentVersion,
                createdBy = authState.currentUserId(),
                createdAt = now.toEpochMilliseconds(),
                isAutosave = true,
                storagePath = null,
                document = ProjectSerializer.encode(project),
            ),
        )
        val cutoff = now.toEpochMilliseconds() - AUTOSAVE_RETENTION_MS
        versionDao.pruneAutosaves(project.id.value, cutoff)
    }

    override suspend fun duplicateProject(projectId: ProjectId, newName: String): DataResult<Project> {
        val source = getProject(projectId).getOrNull()
            ?: return DataResult.Failure(DataError(DataError.Kind.NOT_FOUND))
        val now = Clock.System.now()
        val copy = source.copy(
            id = ProjectId(IdGenerator.newId()),
            name = newName,
            documentVersion = 0,
            syncStatus = com.studioone.mobile.core.model.SyncStatus.LOCAL_ONLY,
            createdAt = now,
            updatedAt = now,
            lastOpenedAt = null,
            collaborators = emptyList(),
        )
        projectDao.upsert(ProjectSerializer.toEntity(copy))
        return DataResult.Success(copy)
    }

    override suspend fun archiveProject(projectId: ProjectId, archived: Boolean): DataResult<Unit> {
        projectDao.setArchived(projectId.value, archived)
        return DataResult.Success(Unit)
    }

    override suspend fun deleteProject(projectId: ProjectId): DataResult<Unit> {
        projectDao.delete(projectId.value)
        syncScheduler.scheduleProjectDelete(projectId.value)
        return DataResult.Success(Unit)
    }

    override suspend fun forkProject(projectId: ProjectId): DataResult<Project> =
        duplicateProject(projectId, "Remix of ${getProject(projectId).getOrNull()?.name ?: "project"}")

    override fun observeVersions(projectId: ProjectId): Flow<List<VersionSnapshot>> =
        versionDao.observeVersions(projectId.value).map { list ->
            list.map {
                VersionSnapshot(
                    id = com.studioone.mobile.core.model.VersionId(it.id),
                    projectId = projectId,
                    label = it.label,
                    documentVersion = it.documentVersion,
                    createdBy = it.createdBy?.let(::UserId),
                    createdAt = Instant.fromEpochMilliseconds(it.createdAt),
                    sizeBytes = it.sizeBytes,
                    isAutosave = it.isAutosave,
                    storagePath = it.storagePath ?: "local:${it.id}",
                )
            }
        }

    override suspend fun restoreVersion(versionId: String): DataResult<Project> {
        val version = versionDao.byId(versionId)
            ?: return DataResult.Failure(DataError(DataError.Kind.NOT_FOUND))
        val doc = version.document?.let(ProjectSerializer::decode)
            ?: return DataResult.Failure(DataError(DataError.Kind.NOT_FOUND, "Snapshot body missing"))
        val restored = doc.copy(
            documentVersion = doc.documentVersion + 1,
            updatedAt = Clock.System.now(),
        )
        projectDao.upsert(ProjectSerializer.toEntity(restored, dirty = true))
        syncScheduler.scheduleProjectSync(restored.id.value)
        return DataResult.Success(restored)
    }

    override suspend fun mergeRemote(projectId: ProjectId): DataResult<MergeOutcome> {
        // Full merge happens in SyncEngine (it has the network deltas);
        // this entry point is the manual "resolve now" from the conflict UI.
        val local = projectDao.byId(projectId.value)
            ?: return DataResult.Failure(DataError(DataError.Kind.NOT_FOUND))
        val model = ProjectSerializer.toModel(local)
            ?: return DataResult.Failure(DataError(DataError.Kind.UNKNOWN))
        return DataResult.Success(MergeOutcome(model.documentVersion, 0, emptyList()))
    }

    override suspend fun lastOpenedProjectId(): ProjectId? =
        settings.lastProjectId.firstOrNull()?.let(::ProjectId)

    override suspend fun markOpened(projectId: ProjectId) {
        projectDao.touchOpened(projectId.value)
        settings.setLastProject(projectId.value)
    }

    companion object {
        const val AUTOSAVE_RETENTION_MS = 7L * 24 * 3600 * 1000 // free tier; sync prunes per entitlement
    }
}

/** Broken out to avoid a dependency on core:data's auth impl from repositories. */
interface CurrentUserProvider {
    suspend fun currentUserId(): String?
    suspend fun currentToken(): String?
}
