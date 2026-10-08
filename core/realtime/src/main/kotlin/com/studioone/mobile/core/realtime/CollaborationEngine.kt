package com.studioone.mobile.core.realtime

import com.studioone.mobile.core.model.CollabOp
import com.studioone.mobile.core.model.CollaboratorPresence
import com.studioone.mobile.core.model.OpEnvelope
import com.studioone.mobile.core.model.Project
import com.studioone.mobile.core.model.ProjectId
import com.studioone.mobile.core.model.UserId
import com.studioone.mobile.core.realtime.crdt.HybridClock
import com.studioone.mobile.core.realtime.crdt.ProjectCrdt
import com.studioone.mobile.core.realtime.socket.CollabSocket
import com.studioone.mobile.core.realtime.socket.SocketFrame
import com.studioone.mobile.core.realtime.socket.SocketState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/** Transport contract to the backend (implemented in core:data with Retrofit). */
interface CollabTransport {
    suspend fun pushOps(ops: List<OpEnvelope>): Int
    suspend fun pullOps(projectId: ProjectId, afterLamport: Long, limit: Int = 500): List<OpEnvelope>
    suspend fun serverLamport(projectId: ProjectId): Long
}

/** Events features observe to update UI on remote changes. */
sealed interface CollabEvent {
    data class RemoteOpApplied(val envelope: OpEnvelope) : CollabEvent
    data class PeerPresence(val userId: String, val presence: CollaboratorPresence) : CollabEvent
    data class Connected(val peers: List<String>) : CollabEvent
    data object Disconnected : CollabEvent
    data class SyncGapResolved(val opsPulled: Int) : CollabEvent
    data class Error(val message: String) : CollabEvent
}

/**
 * Per-session collaboration orchestrator for ONE open project.
 *
 * Pipeline:
 *   local edit -> ProjectCrdt.applyLocal -> envelope ->
 *     (a) outbox (persisted by caller for offline durability)
 *     (b) CollabSocket broadcast (best-effort, low latency)
 *
 *   remote: socket frame -> apply -> CollabEvent
 *           periodic/reconnect REST pull (gap fill, source of truth)
 *
 * Lamport wire encoding: millis*1000 + HLC counter — keeps envelope.lamport a
 * single Long while preserving total order with the clock's tie-break rules.
 */
class CollaborationEngine(
    private val scope: CoroutineScope,
    private val projectId: ProjectId,
    private val localUserId: UserId,
    private val transport: CollabTransport,
    private val socket: CollabSocket,
    private val baseDocument: Project,
    private val tokenProvider: suspend () -> String? = { null },
) {
    private val clock = HybridClock(nodeId = localUserId.value)
    private val crdt = ProjectCrdt(projectId, clock, localUserId.value)

    private var lastLamport: Long = 0L
    private var pullJob: Job? = null
    private var socketJob: Job? = null

    private val _events = MutableSharedFlow<CollabEvent>(extraBufferCapacity = 128)
    val events: SharedFlow<CollabEvent> = _events.asSharedFlow()

    private val _document = MutableStateFlow(baseDocument)
    /** The live, merged project document. Arranger/mixer observe this. */
    val document: StateFlow<Project> = _document.asStateFlow()

    private val _socketState = MutableStateFlow(SocketState.DISCONNECTED)
    val socketState: StateFlow<SocketState> = _socketState.asStateFlow()

    /** Outbox sink — core:data persists these to Room before network push. */
    var outboxSink: (suspend (OpEnvelope) -> Unit)? = null

    fun start(channelUrl: String) {
        crdt.apply(OpEnvelope(
            opId = "base", projectId = projectId, authorId = localUserId,
            lamport = baseDocument.documentVersion, wallClock = baseDocument.updatedAt,
            payload = CollabOp.UpdateProjectField(
                com.studioone.mobile.core.model.ProjectField.NAME,
                com.studioone.mobile.core.model.JsonValueLike.Str(baseDocument.name),
            ),
        ))
        lastLamport = baseDocument.documentVersion

        socketJob = scope.launch {
            socket.frames.collect { frame ->
                when (frame) {
                    is SocketFrame.Op -> handleWireOp(frame.json)
                    is SocketFrame.Presence -> handlePresence(frame.json)
                    is SocketFrame.ServerAck -> { /* outbox pruning handled by REST confirm */ }
                    is SocketFrame.ServerHello -> {
                        lastLamport = maxOf(lastLamport, frame.serverLamport)
                        _events.emit(CollabEvent.Connected(frame.peers))
                        pullGap() // catch up on anything missed while offline
                    }
                    is SocketFrame.Error -> _events.emit(CollabEvent.Error(frame.message))
                }
            }
        }
        scope.launch {
            socket.state.collect { _socketState.value = it }
        }
        socket.connect(channelUrl, projectId.value, tokenProvider)

        // Periodic REST reconciliation: the socket is a hint channel only.
        pullJob = scope.launch {
            while (isActive) {
                delay(PULL_INTERVAL_MS)
                pullGap()
            }
        }
    }

    fun stop() {
        pullJob?.cancel()
        socketJob?.cancel()
        socket.disconnect()
    }

    /** Submit a local edit; returns the envelope that must hit the outbox. */
    suspend fun submitLocal(op: CollabOp): OpEnvelope {
        val envelope = crdt.applyLocal(op, localUserId.value)
        outboxSink?.invoke(envelope)
        socket.sendOp(ProjectCrdt.json.encodeToString(OpEnvelope.serializer(), envelope))
        refreshDocument()
        return envelope
    }

    /** Broadcast presence (throttled by caller — arranger cursor moves etc.). */
    fun sendPresence(presence: CollaboratorPresence) {
        val json = ProjectCrdt.json.encodeToString(CollaboratorPresence.serializer(), presence)
        socket.sendPresence("""{"user":"${localUserId.value}","presence":$json}""")
    }

    private suspend fun pullGap() {
        try {
            val ops = transport.pullOps(projectId, lastLamport)
            var applied = 0
            for (op in ops.sortedBy { it.lamport }) {
                if (crdt.apply(op)) applied++
                lastLamport = maxOf(lastLamport, op.lamport)
            }
            if (applied > 0) {
                refreshDocument()
                _events.emit(CollabEvent.SyncGapResolved(applied))
            }
        } catch (t: Throwable) {
            Timber.w(t, "collab gap pull failed (offline?)")
        }
    }

    private suspend fun handleWireOp(json: String) {
        try {
            val envelope = ProjectCrdt.json.decodeFromString(OpEnvelope.serializer(), json)
            if (envelope.projectId != projectId) return
            if (crdt.apply(envelope)) {
                lastLamport = maxOf(lastLamport, envelope.lamport)
                refreshDocument()
                _events.emit(CollabEvent.RemoteOpApplied(envelope))
            }
        } catch (t: Throwable) {
            Timber.e(t, "bad wire op")
        }
    }

    private suspend fun handlePresence(json: String) {
        try {
            val obj = ProjectCrdt.json.parseToJsonElement(json).let {
                it as kotlinx.serialization.json.JsonObject
            }
            val userId = obj["user"]?.toString()?.trim('"') ?: return
            val presenceJson = obj["presence"]?.toString() ?: return
            val presence = ProjectCrdt.json.decodeFromString(
                CollaboratorPresence.serializer(), presenceJson,
            )
            _events.emit(CollabEvent.PeerPresence(userId, presence))
        } catch (t: Throwable) {
            Timber.w(t, "bad presence frame")
        }
    }

    private fun refreshDocument() {
        _document.value = crdt.materialize(_document.value)
    }

    companion object {
        const val PULL_INTERVAL_MS = 5_000L
    }
}
