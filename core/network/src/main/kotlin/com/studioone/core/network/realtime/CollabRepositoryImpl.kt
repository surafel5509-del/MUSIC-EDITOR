package com.studioone.core.network.realtime

import com.studioone.core.domain.model.ProjectId
import com.studioone.core.domain.model.collab.CollabConnectionState
import com.studioone.core.domain.model.collab.CollabOp
import com.studioone.core.domain.model.collab.Participant
import com.studioone.core.domain.repository.CollabRepository
import com.studioone.core.domain.repository.CollabSessionHandle
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Repository adapter over [SupabaseCollabClient]. Presence tracking is
 * simplified to join/leave here; cursor presence rides on broadcast events.
 */
@Singleton
class CollabRepositoryImpl @Inject constructor(
    private val client: SupabaseCollabClient,
) : CollabRepository {

    override suspend fun join(projectId: ProjectId): CollabSessionHandle {
        val me = client.join(projectId)
        return SessionHandle(projectId, me, client)
    }

    override suspend fun leave(projectId: ProjectId) = client.leave(projectId)

    override suspend fun createInviteLink(projectId: ProjectId): String =
        "https://studioone.app/join/${projectId.value}"

    override fun observeConnectionState(projectId: ProjectId): Flow<CollabConnectionState> =
        client.connectionState(projectId)

    private class SessionHandle(
        private val projectId: ProjectId,
        private val me: Participant,
        private val client: SupabaseCollabClient,
    ) : CollabSessionHandle {

        private val presenceFlow = MutableStateFlow(listOf(me))
        private val activityFlow = MutableSharedFlow<String>(extraBufferCapacity = 64)

        override val participants: Flow<List<Participant>> = presenceFlow
        override val remoteOps: Flow<CollabOp> = client.ops(projectId)
        override val activity: Flow<String> = activityFlow

        override suspend fun send(op: CollabOp) {
            client.send(projectId, op)
            activityFlow.tryEmit("You · ${op.javaClass.simpleName}")
        }

        override suspend fun updatePresence(screen: String) {
            presenceFlow.value = listOf(me.copy(currentScreen = screen))
        }

        override suspend fun close() = client.leave(projectId)
    }
}
