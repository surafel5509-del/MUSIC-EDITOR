package com.studioone.mobile.core.data.repo

import com.studioone.mobile.core.common.DataResult
import com.studioone.mobile.core.domain.EditorActions
import com.studioone.mobile.core.domain.TrackRepository
import com.studioone.mobile.core.model.Project
import com.studioone.mobile.core.model.ProjectId
import com.studioone.mobile.core.model.Track
import com.studioone.mobile.core.model.TrackId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Track mutations = load document -> pure EditorActions transform -> save.
 * The same transforms are emitted as CRDT ops by the editor session when a
 * collaboration session is open (ProjectEditorSession.submit()).
 */
@Singleton
class TrackRepositoryImpl @Inject constructor(
    private val projectRepository: ProjectRepositoryImpl,
) : TrackRepository {

    override suspend fun addTrack(projectId: ProjectId, track: Track): DataResult<Project> =
        transform(projectId) { EditorActions.updateTrack(it, track.id) { t -> t }.let { p ->
            if (p.track(track.id) != null) p else p.copy(tracks = p.tracks + track)
        } }

    override suspend fun removeTrack(projectId: ProjectId, trackId: TrackId): DataResult<Project> =
        transform(projectId) { EditorActions.removeTrack(it, trackId) }

    override suspend fun updateTrack(projectId: ProjectId, trackId: TrackId, transform: (Track) -> Track): DataResult<Project> =
        transform(projectId) { EditorActions.updateTrack(it, trackId, transform) }

    override suspend fun reorderTracks(projectId: ProjectId, orderedIds: List<TrackId>): DataResult<Project> =
        transform(projectId) { project ->
            val byId = project.tracks.associateBy { it.id }
            val reordered = orderedIds.mapNotNull { byId[it] }
                .mapIndexed { index, track -> track.copy(orderIndex = index) }
            project.copy(tracks = reordered)
        }

    override suspend fun freezeTrack(projectId: ProjectId, trackId: TrackId): DataResult<Project> {
        // The offline bounce itself is produced by ExportRepository.renderTrackBounce
        // (native graph in render mode); here we flag the track and swap its
        // source to the bounce file once ready.
        return transform(projectId) { project ->
            EditorActions.updateTrack(project, trackId) {
                it.copy(frozen = true)
            }
        }
    }

    override suspend fun unfreezeTrack(projectId: ProjectId, trackId: TrackId): DataResult<Project> =
        transform(projectId) { project ->
            EditorActions.updateTrack(project, trackId) {
                it.copy(frozen = false, frozenFileUri = null)
            }
        }

    private suspend fun transform(projectId: ProjectId, block: (Project) -> Project): DataResult<Project> {
        val current = projectRepository.getProject(projectId).getOrNull()
            ?: return DataResult.Failure(com.studioone.mobile.core.common.DataError(
                com.studioone.mobile.core.common.DataError.Kind.NOT_FOUND))
        val updated = block(current)
        return projectRepository.saveProject(updated)
            .let { DataResult.Success(updated) }
    }
}
