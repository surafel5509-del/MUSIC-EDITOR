package com.studioone.core.network.collab

import com.studioone.core.domain.model.ProjectId
import com.studioone.core.domain.model.collab.CollabOp
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Wire format for ops sent over the realtime channel. */
@Serializable
data class CollabOpDto(
    val type: String,
    val opId: String,
    val siteId: String,
    val lamport: Long,
    val projectId: String,
    val trackId: String? = null,
    val clipId: String? = null,
    val field: String? = null,
    val valueJson: String? = null,
    val docJson: String? = null,
    val position: Int? = null,
    val text: String? = null,
    val sentAt: Long? = null,
)

object CollabOpSerializer {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(op: CollabOp): String = json.encodeToString(CollabOpDto.serializer(), op.toDto())

    fun decode(raw: String): CollabOp? {
        val dto = runCatching { json.decodeFromString(CollabOpDto.serializer(), raw) }.getOrNull()
            ?: return null
        val projectId = ProjectId(dto.projectId)
        return when (dto.type) {
            "add_track" -> CollabOp.AddTrack(dto.opId, dto.siteId, dto.lamport, projectId, dto.docJson.orEmpty(), dto.position ?: 0)
            "remove_track" -> CollabOp.RemoveTrack(dto.opId, dto.siteId, dto.lamport, projectId, dto.trackId.orEmpty())
            "update_track" -> CollabOp.UpdateTrackField(dto.opId, dto.siteId, dto.lamport, projectId, dto.trackId.orEmpty(), dto.field.orEmpty(), dto.valueJson.orEmpty())
            "add_clip" -> CollabOp.AddClip(dto.opId, dto.siteId, dto.lamport, projectId, dto.docJson.orEmpty())
            "update_clip" -> CollabOp.UpdateClipField(dto.opId, dto.siteId, dto.lamport, projectId, dto.clipId.orEmpty(), dto.field.orEmpty(), dto.valueJson.orEmpty())
            "remove_clip" -> CollabOp.RemoveClip(dto.opId, dto.siteId, dto.lamport, projectId, dto.clipId.orEmpty())
            "chat" -> CollabOp.ChatMessage(dto.opId, dto.siteId, dto.lamport, projectId, dto.text.orEmpty(), dto.sentAt ?: 0L)
            else -> null
        }
    }

    private fun CollabOp.toDto(): CollabOpDto = when (this) {
        is CollabOp.AddTrack -> CollabOpDto("add_track", opId, siteId, lamport, projectId.value, docJson = trackJson, position = position)
        is CollabOp.RemoveTrack -> CollabOpDto("remove_track", opId, siteId, lamport, projectId.value, trackId = trackId)
        is CollabOp.UpdateTrackField -> CollabOpDto("update_track", opId, siteId, lamport, projectId.value, trackId = trackId, field = field, valueJson = valueJson)
        is CollabOp.AddClip -> CollabOpDto("add_clip", opId, siteId, lamport, projectId.value, docJson = clipJson)
        is CollabOp.UpdateClipField -> CollabOpDto("update_clip", opId, siteId, lamport, projectId.value, clipId = clipId, field = field, valueJson = valueJson)
        is CollabOp.RemoveClip -> CollabOpDto("remove_clip", opId, siteId, lamport, projectId.value, clipId = clipId)
        is CollabOp.ChatMessage -> CollabOpDto("chat", opId, siteId, lamport, projectId.value, text = text, sentAt = sentAt)
    }
}
