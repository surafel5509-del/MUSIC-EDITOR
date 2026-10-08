package com.studioone.core.domain.repository

import com.studioone.core.domain.model.ProjectId
import com.studioone.core.domain.model.collab.CollabConnectionState
import com.studioone.core.domain.model.collab.CollabOp
import com.studioone.core.domain.model.collab.Participant
import kotlinx.coroutines.flow.Flow

/** Realtime collaboration transport + session management. */
interface CollabRepository {

    /** Connects to a session; ops flow in/out until [leave] is called. */
    suspend fun join(projectId: ProjectId): CollabSessionHandle

    suspend fun leave(projectId: ProjectId)

    suspend fun createInviteLink(projectId: ProjectId): String

    fun observeConnectionState(projectId: ProjectId): Flow<CollabConnectionState>
}

/** Live handle to a joined session. */
interface CollabSessionHandle {
    val participants: Flow<List<Participant>>
    val remoteOps: Flow<CollabOp>
    val activity: Flow<String>

    suspend fun send(op: CollabOp)
    suspend fun updatePresence(screen: String)
    suspend fun close()
}
