package com.studioone.mobile.core.data.repo

import com.studioone.mobile.core.common.DataError
import com.studioone.mobile.core.common.DataResult
import com.studioone.mobile.core.data.sync.SyncScheduler
import com.studioone.mobile.core.database.dao.CommentDao
import com.studioone.mobile.core.database.dao.SyncOpDao
import com.studioone.mobile.core.database.entity.CommentEntity
import com.studioone.mobile.core.database.entity.SyncOpEntity
import com.studioone.mobile.core.domain.CollabSessionHandle
import com.studioone.mobile.core.domain.CollaborationRepository
import com.studioone.mobile.core.model.CollabOp
import com.studioone.mobile.core.model.Collaborator
import com.studioone.mobile.core.model.CollaboratorPresence
import com.studioone.mobile.core.model.Comment
import com.studioone.mobile.core.model.CommentId
import com.studioone.mobile.core.model.JsonValueLike
import com.studioone.mobile.core.model.OpEnvelope
import com.studioone.mobile.core.model.Project
import com.studioone.mobile.core.model.ProjectId
import com.studioone.mobile.core.model.ProjectRole
import com.studioone.mobile.core.model.ShareLink
import com.studioone.mobile.core.model.UserId
import com.studioone.mobile.core.network.api.AcceptShareRequest
import com.studioone.mobile.core.network.api.ApiConfig
import com.studioone.mobile.core.network.api.PullOpsResponse
import com.studioone.mobile.core.network.api.ShareLinkRequest
import com.studioone.mobile.core.network.api.StudioOneApi
import com.studioone.mobile.core.realtime.CollabTransport
import com.studioone.mobile.core.realtime.CollaborationEngine
import com.studioone.mobile.core.realtime.crdt.ProjectCrdt
import com.studioone.mobile.core.realtime.socket.CollabSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Live collaboration sessions + share links + comments.
 *
 * The outbox is persisted to Room (sync_ops) BEFORE any network attempt, so a
 * process death mid-session loses nothing: OpFlushWorker replays on next
 * connectivity. The socket is a latency optimization over that durable path.
 */
@Singleton
class CollaborationRepositoryImpl @Inject constructor(
    private val api: StudioOneApi,
    private val config: ApiConfig,
    private val okHttpClient: OkHttpClient,
    private val syncOpDao: SyncOpDao,
    private val commentDao: CommentDao,
    private val syncScheduler: SyncScheduler,
    private val projectSerializerStore: ProjectDocumentStore,
    private val auth: CurrentUserProvider,
) : CollaborationRepository {

    private val sessionScope = CoroutineScope(kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob())
    private val openSessions = mutableMapOf<String, SessionHandle>()

    override suspend fun openSession(projectId: ProjectId, baseDocument: Project): DataResult<CollabSessionHandle> {
        openSessions[projectId.value]?.let { return DataResult.Success(it) }
        val transport = object : CollabTransport {
            override suspend fun pushOps(ops: List<OpEnvelope>): Int {
                val wires = ops.map {
                    com.studioone.mobile.core.network.api.OpWire(
                        opId = it.opId, projectId = it.projectId.value, authorId = it.authorId.value,
                        lamport = it.lamport, wallClock = it.wallClock.toString(),
                        payload = ProjectCrdt.json.encodeToString(CollabOp.serializer(), it.payload),
                    )
                }
                val resp = api.pushOps(com.studioone.mobile.core.network.api.PushOpsRequest(wires))
                return resp.body()?.accepted ?: 0
            }

            override suspend fun pullOps(projectId: ProjectId, afterLamport: Long, limit: Int): List<OpEnvelope> {
                val resp: retrofit2.Response<PullOpsResponse> = api.pullOps(projectId.value, afterLamport, limit)
                return resp.body()?.ops?.map { wire ->
                    OpEnvelope(
                        opId = wire.opId,
                        projectId = ProjectId(wire.projectId),
                        authorId = UserId(wire.authorId),
                        lamport = wire.lamport,
                        wallClock = kotlinx.datetime.Instant.parse(wire.wallClock),
                        payload = ProjectCrdt.json.decodeFromString(CollabOp.serializer(), wire.payload),
                    )
                } ?: emptyList()
            }

            override suspend fun serverLamport(projectId: ProjectId): Long =
                pullOps(projectId, Long.MAX_VALUE - 1, 1).maxOfOrNull { it.lamport } ?: 0L
        }

        val socket = CollabSocket(okHttpClient, sessionScope)
        val engine = CollaborationEngine(
            scope = sessionScope,
            projectId = projectId,
            localUserId = UserId(auth.currentUserId() ?: "guest"),
            transport = transport,
            socket = socket,
            baseDocument = baseDocument,
            tokenProvider = { auth.currentToken() },
        )
        val handle = SessionHandle(projectId, engine)
        openSessions[projectId.value] = handle

        // Durable outbox hook: persist every local envelope before socket send.
        engine.outboxSink = { envelope ->
            syncOpDao.enqueue(
                SyncOpEntity(
                    opId = envelope.opId,
                    projectId = envelope.projectId.value,
                    author = envelope.authorId.value,
                    lamport = envelope.lamport,
                    createdAt = System.currentTimeMillis(),
                    payload = ProjectCrdt.json.encodeToString(OpEnvelope.serializer(), envelope),
                ),
            )
            syncScheduler.scheduleOpFlush(envelope.projectId.value)
        }

        val channelUrl = "${config.realtimeBaseUrl}/websocket?apikey=${config.supabaseAnonKey}&vsn=1.0.0"
        engine.start(channelUrl)
        return DataResult.Success(handle)
    }

    fun closeSession(projectId: ProjectId) {
        openSessions.remove(projectId.value)?.engine?.stop()
    }

    override fun observeComments(projectId: ProjectId): Flow<List<Comment>> =
        commentDao.observeForProject(projectId.value).map { rows ->
            rows.map {
                Comment(
                    id = CommentId(it.id), projectId = projectId, authorId = UserId(it.authorId),
                    authorName = it.authorName, text = it.text, anchorFrame = it.anchorFrame,
                    anchorTrackId = it.anchorTrackId?.let(::com.studioone.mobile.core.model.TrackId),
                    resolved = it.resolved,
                    createdAt = kotlinx.datetime.Instant.fromEpochMilliseconds(it.createdAt),
                )
            }
        }

    override suspend fun addComment(comment: Comment): DataResult<Unit> {
        commentDao.upsert(
            CommentEntity(
                id = comment.id.value, projectId = comment.projectId.value,
                authorId = comment.authorId.value, authorName = comment.authorName,
                text = comment.text, anchorFrame = comment.anchorFrame,
                anchorTrackId = comment.anchorTrackId?.value, resolved = false,
                createdAt = comment.createdAt.toEpochMilliseconds(), pendingUpload = true,
            ),
        )
        // Broadcast as an op when a session is open.
        openSessions[comment.projectId.value]?.engine?.submitLocal(CollabOp.AddComment(comment))
        syncScheduler.scheduleOpFlush(comment.projectId.value)
        return DataResult.Success(Unit)
    }

    override suspend fun resolveComment(commentId: String, resolved: Boolean): DataResult<Unit> {
        commentDao.setResolved(commentId, resolved)
        return DataResult.Success(Unit)
    }

    override suspend fun createShareLink(projectId: ProjectId, role: ProjectRole, forkOnAccept: Boolean, expiresHours: Int?): DataResult<ShareLink> {
        return try {
            val resp = api.createShareLink(
                ShareLinkRequest(projectId.value, role.name, expiresHours, forkOnAccept),
            )
            val body = resp.body() ?: return DataResult.Failure(DataError(DataError.Kind.NETWORK))
            DataResult.Success(
                ShareLink(
                    token = body.token, projectId = projectId, role = role,
                    expiresAt = body.expiresAt?.let { kotlinx.datetime.Instant.parse(it) },
                    forkOnAccept = forkOnAccept,
                    createdBy = UserId(auth.currentUserId() ?: ""),
                ),
            )
        } catch (t: Throwable) {
            DataResult.Failure(DataError.from(t))
        }
    }

    override suspend fun acceptShareLink(token: String): DataResult<Project> {
        return try {
            val resp = api.acceptShareLink(AcceptShareRequest(token))
            val body = resp.body() ?: return DataResult.Failure(DataError(DataError.Kind.PERMISSION, "Link invalid or expired"))
            val document = body.document?.let { ProjectSerializerDecode.decode(it) }
                ?: projectSerializerStore.getProject(ProjectId(body.projectId)).getOrNull()
                ?: return DataResult.Failure(DataError(DataError.Kind.NOT_FOUND))
            projectSerializerStore.saveLocal(document)
            DataResult.Success(document)
        } catch (t: Throwable) {
            DataResult.Failure(DataError.from(t))
        }
    }

    override suspend fun collaborators(projectId: ProjectId): DataResult<List<Collaborator>> =
        projectSerializerStore.getProject(projectId).let { result ->
            result.getOrNull()?.collaborators?.let { DataResult.Success(it) }
                ?: DataResult.Failure(DataError(DataError.Kind.NOT_FOUND))
        }

    override suspend fun setCollaboratorRole(projectId: ProjectId, userId: String, role: ProjectRole): DataResult<Unit> {
        val project = projectSerializerStore.getProject(projectId).getOrNull()
            ?: return DataResult.Failure(DataError(DataError.Kind.NOT_FOUND))
        val updated = project.copy(
            collaborators = project.collaborators.map {
                if (it.userId.value == userId) it.copy(role = role) else it
            },
        )
        return projectSerializerStore.saveProject(updated).let { DataResult.Success(Unit) }
    }

    private class SessionHandle(
        override val projectId: ProjectId,
        val engine: CollaborationEngine,
    ) : CollabSessionHandle {
        override val document: Flow<Project> = engine.document
        override val events: Flow<Any> = engine.events
        override suspend fun submit(op: CollabOp) { engine.submitLocal(op) }
        override fun sendPresence(presence: CollaboratorPresence) = engine.sendPresence(presence)
        override fun close() = engine.stop()
    }
}

private object ProjectSerializerDecode {
    fun decode(json: String): Project? = com.studioone.mobile.core.data.mapper.ProjectSerializer.decode(json)
}

/** Thin facade so collab code can read/write project documents without
 *  depending on the full ProjectRepository (avoids a Hilt cycle). */
interface ProjectDocumentStore {
    suspend fun getProject(projectId: ProjectId): DataResult<Project>
    suspend fun saveLocal(project: Project)
    suspend fun saveProject(project: Project): Long
}
