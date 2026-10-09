package com.studioone.core.data.sync

import com.studioone.core.database.dao.SyncOutboxDao
import com.studioone.core.database.entity.SyncOutboxEntity
import com.studioone.core.domain.model.Project
import com.studioone.core.domain.model.ProjectId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Appends outbound sync operations to the local outbox. The [SyncWorker]
 * drains the outbox when connectivity is available, making every write in the
 * app safe to perform fully offline.
 */
@Singleton
class SyncOutboxWriter @Inject constructor(private val dao: SyncOutboxDao) {

    suspend fun enqueueProjectUpsert(project: Project) {
        val payload = buildJsonObject {
            put("type", "project_upsert")
            put("id", project.id.value)
            put("name", project.name)
            put("tempo", project.tempo)
            put("sample_rate", project.sampleRate)
            put("version", project.version)
            put("updated_at", project.updatedAt.toEpochMilliseconds())
        }
        dao.enqueue(SyncOutboxEntity(entityType = "project", entityId = project.id.value, opJson = payload.toString(), createdAt = System.currentTimeMillis()))
    }

    suspend fun enqueueProjectDelete(projectId: ProjectId) {
        val payload = buildJsonObject {
            put("type", "project_delete")
            put("id", projectId.value)
        }
        dao.enqueue(SyncOutboxEntity(entityType = "project", entityId = projectId.value, opJson = payload.toString(), createdAt = System.currentTimeMillis()))
    }

    suspend fun enqueueStemUpload(projectId: ProjectId, localPath: String, remotePath: String) {
        val payload = buildJsonObject {
            put("type", "stem_upload")
            put("project_id", projectId.value)
            put("local_path", localPath)
            put("remote_path", remotePath)
        }
        dao.enqueue(SyncOutboxEntity(entityType = "stem", entityId = projectId.value, opJson = payload.toString(), createdAt = System.currentTimeMillis()))
    }
}
