package com.studioone.mobile.core.data.mapper

import com.studioone.mobile.core.database.entity.ProjectEntity
import com.studioone.mobile.core.model.Project
import com.studioone.mobile.core.model.ProjectId
import com.studioone.mobile.core.model.SyncStatus
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json

/**
 * Project document <-> Room entity mapping. The document column holds the
 * canonical kotlinx.serialization JSON; metadata columns are denormalized for
 * indexed queries. Json config is app-wide (classDiscriminator "kind" matches
 * the CRDT wire format so documents and ops interoperate).
 */
object ProjectSerializer {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        classDiscriminator = "kind"
        prettyPrint = false
    }

    fun encode(project: Project): String = json.encodeToString(Project.serializer(), project)

    fun decode(document: String): Project? =
        runCatching { json.decodeFromString(Project.serializer(), document) }.getOrNull()

    fun toEntity(project: Project, dirty: Boolean = false): ProjectEntity = ProjectEntity(
        id = project.id.value,
        ownerId = project.ownerId?.value,
        name = project.name,
        template = project.template.name,
        bpm = project.tempoMap.baseBpm,
        keyName = project.key.toString(),
        trackCount = project.tracks.size,
        durationBars = project.durationBars,
        isArchived = project.isArchived,
        isFavorite = project.isFavorite,
        syncStatus = project.syncStatus.name,
        documentVersion = project.documentVersion,
        document = encode(project),
        artworkUri = project.artworkUri,
        genre = project.genre,
        createdAt = project.createdAt.toEpochMilliseconds(),
        updatedAt = project.updatedAt.toEpochMilliseconds(),
        lastOpenedAt = project.lastOpenedAt?.toEpochMilliseconds(),
        dirty = dirty,
    )

    fun toModel(entity: ProjectEntity): Project? {
        val doc = decode(entity.document) ?: return null
        return doc.copy(
            syncStatus = runCatching { SyncStatus.valueOf(entity.syncStatus) }.getOrDefault(SyncStatus.LOCAL_ONLY),
            lastOpenedAt = entity.lastOpenedAt?.let { Instant.fromEpochMilliseconds(it) },
        )
    }
}
