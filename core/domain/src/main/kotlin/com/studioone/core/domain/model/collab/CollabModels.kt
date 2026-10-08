package com.studioone.core.domain.model.collab

import com.studioone.core.domain.model.ProjectId
import com.studioone.core.domain.model.UserId
import kotlinx.datetime.Instant

/** A participant in a live collaboration session. */
data class Participant(
    val userId: UserId,
    val displayName: String,
    val colorIndex: Int,
    val isHost: Boolean,
    val joinedAt: Instant,
    val lastSeenAt: Instant,
    /** Screen the participant is currently viewing (presence info). */
    val currentScreen: String = "arrange",
)

/** Connection state of the realtime channel. */
enum class CollabConnectionState { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING, ERROR }

/**
 * CRDT-safe operation applied to a shared project. Every mutation shared
 * between collaborators is expressed as one of these ops; see
 * docs/COLLABORATION.md for the merge semantics.
 */
sealed interface CollabOp {
    val opId: String
    val siteId: String
    val lamport: Long
    val projectId: ProjectId

    data class AddTrack(
        override val opId: String, override val siteId: String, override val lamport: Long,
        override val projectId: ProjectId,
        val trackJson: String, val position: Int,
    ) : CollabOp

    data class RemoveTrack(
        override val opId: String, override val siteId: String, override val lamport: Long,
        override val projectId: ProjectId,
        val trackId: String,
    ) : CollabOp

    data class UpdateTrackField(
        override val opId: String, override val siteId: String, override val lamport: Long,
        override val projectId: ProjectId,
        val trackId: String, val field: String, val valueJson: String,
    ) : CollabOp

    data class AddClip(
        override val opId: String, override val siteId: String, override val lamport: Long,
        override val projectId: ProjectId,
        val clipJson: String,
    ) : CollabOp

    data class UpdateClipField(
        override val opId: String, override val siteId: String, override val lamport: Long,
        override val projectId: ProjectId,
        val clipId: String, val field: String, val valueJson: String,
    ) : CollabOp

    data class RemoveClip(
        override val opId: String, override val siteId: String, override val lamport: Long,
        override val projectId: ProjectId,
        val clipId: String,
    ) : CollabOp

    data class ChatMessage(
        override val opId: String, override val siteId: String, override val lamport: Long,
        override val projectId: ProjectId,
        val text: String, val sentAt: Long,
    ) : CollabOp
}
