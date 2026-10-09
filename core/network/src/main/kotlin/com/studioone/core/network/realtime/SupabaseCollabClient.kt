package com.studioone.core.network.realtime

import com.studioone.core.domain.model.ProjectId
import com.studioone.core.domain.model.UserId
import com.studioone.core.domain.model.collab.CollabConnectionState
import com.studioone.core.domain.model.collab.CollabOp
import com.studioone.core.domain.model.collab.Participant
import com.studioone.core.network.collab.CollabOpSerializer
import com.studioone.core.network.collab.CrdtEngine
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Realtime collaboration transport built on Supabase Realtime:
 *  - one broadcast channel per project for CRDT ops,
 *  - presence for participant lists and cursor/screen info,
 *  - a postgres_changes feed + `collab_ops` table as a durable fallback so
 *    late joiners and reconnecting clients can rebuild missed state.
 *
 * Ops are serialized with [CollabOpSerializer]; merging happens client-side
 * in [CrdtEngine], so the server stays stateless with respect to conflicts.
 */
@Singleton
class SupabaseCollabClient @Inject constructor(
    private val supabase: SupabaseClient,
) {
    private val scope = CoroutineScope(SupervisorJob())
    private val channels = HashMap<String, RealtimeChannel>()
    private val connectionStates = HashMap<String, MutableStateFlow<CollabConnectionState>>()
    private val opFlows = HashMap<String, MutableSharedFlow<CollabOp>>()
    private val siteId: String = java.util.UUID.randomUUID().toString()

    val engine = CrdtEngine(siteId)

    fun connectionState(projectId: ProjectId): Flow<CollabConnectionState> =
        connectionStates.getOrPut(projectId.value) { MutableStateFlow(CollabConnectionState.DISCONNECTED) }

    fun ops(projectId: ProjectId): Flow<CollabOp> =
        opFlows.getOrPut(projectId.value) { MutableSharedFlow(extraBufferCapacity = 256) }

    /** Joins the project channel and returns the local participant. */
    suspend fun join(projectId: ProjectId): Participant {
        val state = connectionStates.getOrPut(projectId.value) { MutableStateFlow(CollabConnectionState.DISCONNECTED) }
        state.value = CollabConnectionState.CONNECTING

        val channel = supabase.realtime.channel("project:${projectId.value}")
        channels[projectId.value] = channel

        // Ops arrive as broadcast messages on event "op".
        scope.launch {
            channel.broadcastFlow<OpEnvelope>("op")
                .collect { envelope ->
                    val op = CollabOpSerializer.decode(envelope.payload) ?: return@collect
                    engine.observe(op.lamport)
                    opFlows.getOrPut(projectId.value) { MutableSharedFlow(extraBufferCapacity = 256) }
                        .tryEmit(op)
                }
        }

        // Durable fallback: watch inserts into collab_ops (supabase-kt's
        // PostgresChangeFilter has no server-side equality filter helper here,
        // so rows are filtered client-side by project_id).
        scope.launch {
            channel.postgresChangeFlow<PostgresAction.Insert>(schema = "public") {
                table = "collab_ops"
            }.collect { change ->
                val rowProjectId = change.record["project_id"]?.jsonPrimitive?.content
                if (rowProjectId != projectId.value) return@collect
                val payload = change.record["op_json"]?.jsonPrimitive?.content ?: return@collect
                val op = CollabOpSerializer.decode(payload) ?: return@collect
                engine.observe(op.lamport)
                opFlows[projectId.value]?.tryEmit(op)
            }
        }

        val me = Participant(
            userId = UserId(supabase.auth.currentUserOrNull()?.id ?: siteId),
            displayName = supabase.auth.currentUserOrNull()?.email ?: "Guest",
            colorIndex = (siteId.hashCode() and 0x7fffffff) % 8,
            isHost = false,
            joinedAt = Clock.System.now(),
            lastSeenAt = Clock.System.now(),
        )

        channel.subscribe(blockUntilSubscribed = true)
        state.value = CollabConnectionState.CONNECTED
        return me
    }

    /** Broadcasts an op to peers and persists it to the durable op log. */
    suspend fun send(projectId: ProjectId, op: CollabOp) {
        engine.observe(op.lamport)
        val encoded = CollabOpSerializer.encode(op)
        channels[projectId.value]?.broadcast("op", buildJsonObject { put("payload", encoded) })

        // Late joiners rebuild state from this table.
        runCatching {
            supabase.from("collab_ops").insert(
                buildJsonObject {
                    put("project_id", projectId.value)
                    put("op_json", encoded)
                    put("lamport", op.lamport)
                },
            )
        }
    }

    suspend fun leave(projectId: ProjectId) {
        channels.remove(projectId.value)?.unsubscribe()
        connectionStates[projectId.value]?.value = CollabConnectionState.DISCONNECTED
    }

    /** Broadcast payload wrapper. */
    @Serializable
    data class OpEnvelope(val payload: String)
}
