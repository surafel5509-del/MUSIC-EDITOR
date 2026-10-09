package com.studioone.core.data.repository

import com.studioone.core.common.error.StudioOneException
import com.studioone.core.data.mapper.toDomain
import com.studioone.core.data.mapper.toEntity
import com.studioone.core.data.sync.SyncOutboxWriter
import com.studioone.core.database.dao.AudioClipDao
import com.studioone.core.database.dao.MidiClipDao
import com.studioone.core.database.dao.ProjectDao
import com.studioone.core.database.dao.ProjectVersionDao
import com.studioone.core.database.dao.TrackDao
import com.studioone.core.database.entity.ProjectVersionEntity
import com.studioone.core.domain.model.AudioClip
import com.studioone.core.domain.model.Clip
import com.studioone.core.domain.model.ClipId
import com.studioone.core.domain.model.MidiClip
import com.studioone.core.domain.model.Project
import com.studioone.core.domain.model.ProjectId
import com.studioone.core.domain.model.ProjectTemplate
import com.studioone.core.domain.model.ProjectStatus
import com.studioone.core.domain.model.TimeSignature
import com.studioone.core.domain.model.Track
import com.studioone.core.domain.model.TrackId
import com.studioone.core.domain.model.TrackSpec
import com.studioone.core.domain.model.TrackType
import com.studioone.core.domain.repository.ProjectDocument
import com.studioone.core.domain.repository.ProjectRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Offline-first project repository:
 *  - reads stream straight from Room,
 *  - writes commit to Room synchronously and enqueue a sync op,
 *  - snapshots power version history and remote collaboration catch-up.
 */
@Singleton
class ProjectRepositoryImpl @Inject constructor(
    private val projectDao: ProjectDao,
    private val trackDao: TrackDao,
    private val audioClipDao: AudioClipDao,
    private val midiClipDao: MidiClipDao,
    private val versionDao: ProjectVersionDao,
    private val outboxWriter: SyncOutboxWriter,
) : ProjectRepository {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override fun observeProjects(): Flow<List<Project>> =
        projectDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override fun observeProject(projectId: ProjectId): Flow<Project?> =
        projectDao.observeById(projectId.value).map { it?.toDomain() }

    override suspend fun createProject(
        template: ProjectTemplate?,
        name: String,
        tempo: Double?,
    ): Project {
        val now = Clock.System.now()
        val project = Project(
            id = ProjectId.random(),
            name = name,
            templateId = template?.id,
            tempo = tempo ?: template?.tempo ?: 120.0,
            timeSignature = template?.timeSignature ?: TimeSignature(4, 4),
            key = template?.key,
            createdAt = now,
            updatedAt = now,
        )
        val tracks = (template?.tracks ?: defaultTracks()).mapIndexed { index, spec ->
            Track(
                id = TrackId.random(),
                projectId = project.id,
                name = spec.name,
                type = spec.type,
                colorIndex = spec.colorIndex,
                orderIndex = index,
                armed = spec.armed,
                instrumentId = spec.instrumentId,
            )
        }

        projectDao.upsert(project.toEntity())
        trackDao.upsertAll(tracks.map { it.toEntity() })
        outboxWriter.enqueueProjectUpsert(project)
        return project
    }

    override suspend fun openProject(projectId: ProjectId): ProjectDocument {
        val projectEntity = projectDao.getById(projectId.value)
            ?: throw StudioOneException.ProjectNotFound(projectId.value)
        return ProjectDocument(
            project = projectEntity.toDomain(),
            tracks = trackDao.tracksFor(projectId.value).map { it.toDomain() },
            audioClips = audioClipDao.clipsFor(projectId.value).map { it.toDomain() },
            midiClips = midiClipDao.clipsFor(projectId.value).map { it.toDomain() },
        )
    }

    override suspend fun saveProject(project: Project, tracks: List<Track>, clips: List<Clip>) {
        val stamped = project.copy(updatedAt = Clock.System.now())
        projectDao.upsert(stamped.toEntity())
        trackDao.deleteAll(project.id.value)
        trackDao.upsertAll(tracks.map { it.toEntity() })
        audioClipDao.deleteAll(project.id.value)
        audioClipDao.upsertAll(clips.filterIsInstance<AudioClip>().map { it.toEntity(project.id) })
        midiClipDao.deleteAll(project.id.value)
        midiClipDao.upsertAll(clips.filterIsInstance<MidiClip>().map { it.toEntity(project.id) })
        outboxWriter.enqueueProjectUpsert(stamped)
    }

    override suspend fun updateProjectMeta(project: Project) {
        projectDao.upsert(project.copy(updatedAt = Clock.System.now()).toEntity())
        outboxWriter.enqueueProjectUpsert(project)
    }

    override suspend fun duplicateProject(projectId: ProjectId): Project {
        val doc = openProject(projectId)
        val now = Clock.System.now()
        val copy = doc.project.copy(
            id = ProjectId.random(),
            name = "${doc.project.name} (copy)",
            createdAt = now,
            updatedAt = now,
            version = 1,
            collabSessionId = null,
        )
        val trackIdMap = doc.tracks.associate { it.id to TrackId.random() }
        val tracks = doc.tracks.map { track ->
            track.copy(
                id = trackIdMap.getValue(track.id),
                projectId = copy.id,
                name = track.name,
            )
        }
        val clips = doc.clips.map { clip ->
            val newTrackId = trackIdMap.getValue(clip.trackId)
            when (clip) {
                is AudioClip -> clip.copy(id = ClipId.random(), trackId = newTrackId)
                is MidiClip -> clip.copy(id = ClipId.random(), trackId = newTrackId)
            }
        }
        saveProject(copy, tracks, clips)
        return copy
    }

    override suspend fun deleteProject(projectId: ProjectId) {
        projectDao.deleteProjectCascade(projectId.value)
        outboxWriter.enqueueProjectDelete(projectId)
    }

    override suspend fun archiveProject(projectId: ProjectId, archived: Boolean) {
        val entity = projectDao.getById(projectId.value) ?: return
        val updated = entity.toDomain().copy(
            status = if (archived) ProjectStatus.ARCHIVED else ProjectStatus.ACTIVE,
            updatedAt = Clock.System.now(),
        )
        projectDao.upsert(updated.toEntity())
        outboxWriter.enqueueProjectUpsert(updated)
    }

    override suspend fun saveVersion(projectId: ProjectId, label: String): Int {
        val doc = openProject(projectId)
        val version = versionDao.maxVersion(projectId.value) + 1
        val payload = json.encodeToString(ProjectSnapshot.serializer(), ProjectSnapshot.from(doc))
        versionDao.insert(
            ProjectVersionEntity(
                projectId = projectId.value,
                version = version,
                label = label,
                createdAt = Clock.System.now().toEpochMilliseconds(),
                payloadJson = payload,
                sizeBytes = payload.length.toLong(),
            ),
        )
        return version
    }

    override fun observeVersions(projectId: ProjectId): Flow<List<ProjectRepository.ProjectVersion>> =
        versionDao.observeVersions(projectId.value).map { rows ->
            rows.map {
                ProjectRepository.ProjectVersion(
                    version = it.version,
                    label = it.label,
                    createdAt = kotlinx.datetime.Instant.fromEpochMilliseconds(it.createdAt),
                    sizeBytes = it.sizeBytes,
                )
            }
        }

    override suspend fun restoreVersion(projectId: ProjectId, version: Int) {
        val row = versionDao.get(projectId.value, version)
            ?: throw StudioOneException.Validation("Version $version not found")
        val snapshot = json.decodeFromString(ProjectSnapshot.serializer(), row.payloadJson)
        val doc = snapshot.toDocument()
        saveProject(
            doc.project.copy(id = projectId, updatedAt = Clock.System.now()),
            doc.tracks.map { it.copy(projectId = projectId) },
            doc.clips,
        )
    }

    private fun defaultTracks(): List<TrackSpec> = listOf(
        TrackSpec("Audio 1", TrackType.AUDIO, colorIndex = 0, armed = true),
        TrackSpec("Instrument 1", TrackType.MIDI, colorIndex = 1, instrumentId = "synth-subtractive"),
    )

    /** Serializable version-history payload built directly from entities. */
    @Serializable
    data class ProjectSnapshot(
        val project: com.studioone.core.database.entity.ProjectEntity,
        val tracks: List<com.studioone.core.database.entity.TrackEntity>,
        val audioClips: List<com.studioone.core.database.entity.AudioClipEntity>,
        val midiClips: List<com.studioone.core.database.entity.MidiClipEntity>,
    ) {
        fun toDocument(): ProjectDocument = ProjectDocument(
            project = project.toDomain(),
            tracks = tracks.map { it.toDomain() },
            audioClips = audioClips.map { it.toDomain() },
            midiClips = midiClips.map { it.toDomain() },
        )

        companion object {
            fun from(doc: ProjectDocument): ProjectSnapshot = ProjectSnapshot(
                project = doc.project.toEntity(),
                tracks = doc.tracks.map { it.toEntity() },
                audioClips = doc.audioClips.map { it.toEntity(doc.project.id) },
                midiClips = doc.midiClips.map { it.toEntity(doc.project.id) },
            )
        }
    }
}
