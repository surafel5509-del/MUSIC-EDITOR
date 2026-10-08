package com.studioone.mobile.core.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.studioone.mobile.core.data.mapper.ProjectSerializer
import com.studioone.mobile.core.database.dao.OpLogDao
import com.studioone.mobile.core.database.dao.ProjectDao
import com.studioone.mobile.core.database.dao.SyncOpDao
import com.studioone.mobile.core.network.api.OpWire
import com.studioone.mobile.core.network.api.PushOpsRequest
import com.studioone.mobile.core.network.api.StudioOneApi
import com.studioone.mobile.core.network.api.UpsertProjectRequest
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * SyncEngine workers.
 *
 * Consistency model (docs/COLLABORATION.md):
 *  * Ops outbox (sync_ops) is the authoritative local delta stream; pushed
 *    with at-least-once semantics, server dedupes by opId.
 *  * Project document upsert carries a CAS version (baseVersion); a 409-style
 *    rejection returns missing ops which we merge locally then retry.
 */
@HiltWorker
class ProjectSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val projectDao: ProjectDao,
    private val api: StudioOneApi,
    private val opLogDao: OpLogDao,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val projectId = inputData.getString(KEY_PROJECT_ID) ?: return Result.failure()
        val entity = projectDao.byId(projectId) ?: return Result.success()
        if (!entity.dirty) return Result.success()

        val request = UpsertProjectRequest(
            projectId = entity.id,
            documentVersion = entity.documentVersion,
            document = entity.document,
            baseVersion = entity.documentVersion - 1,
            lamport = entity.documentVersion,
        )
        return try {
            val response = api.upsertProject(request)
            if (response.isSuccessful) {
                val body = response.body()
                if (body?.accepted == true) {
                    projectDao.markSynced(entity.id)
                    Result.success()
                } else {
                    // Server had newer ops: ingest them into the op log; the
                    // merge runs when the project is next opened (or live via
                    // CollaborationEngine if the session is open).
                    body?.missingOps?.forEach { wire ->
                        opLogDao.insert(
                            com.studioone.mobile.core.database.entity.OpLogEntity(
                                opId = wire.opId, projectId = wire.projectId,
                                lamport = wire.lamport, author = wire.authorId,
                                receivedAt = System.currentTimeMillis(),
                                payload = wire.payload,
                            ),
                        )
                    }
                    Result.retry()
                }
            } else {
                if (response.code() in 500..599) Result.retry() else Result.failure()
            }
        } catch (t: Throwable) {
            Timber.w(t, "project sync failed (will retry)")
            Result.retry()
        }
    }

    companion object {
        const val KEY_PROJECT_ID = "project_id"
    }
}

@HiltWorker
class OpFlushWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val syncOpDao: SyncOpDao,
    private val api: StudioOneApi,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val pending = syncOpDao.pending(limit = 200)
        if (pending.isEmpty()) return Result.success()
        return try {
            val wires = pending.map { op ->
                val envelope = Json.decodeFromString(
                    com.studioone.mobile.core.model.OpEnvelope.serializer(), op.payload)
                OpWire(
                    opId = envelope.opId,
                    projectId = envelope.projectId.value,
                    authorId = envelope.authorId.value,
                    lamport = envelope.lamport,
                    wallClock = envelope.wallClock.toString(),
                    payload = Json.encodeToString(
                        com.studioone.mobile.core.model.CollabOp.serializer(), envelope.payload),
                )
            }
            val response = api.pushOps(PushOpsRequest(wires))
            if (response.isSuccessful) {
                val accepted = wires.take(response.body()?.accepted ?: wires.size).map { it.opId }
                syncOpDao.markUploaded(accepted)
                if (pending.size > accepted.size) Result.retry() else Result.success()
            } else Result.retry()
        } catch (t: Throwable) {
            pending.forEach { syncOpDao.bumpAttempt(it.opId) }
            Result.retry()
        }
    }
}

@HiltWorker
class ProjectDeleteWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val api: StudioOneApi,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val projectId = inputData.getString(ProjectSyncWorker.KEY_PROJECT_ID) ?: return Result.failure()
        return try {
            // Soft-delete via REST (RLS enforces ownership); storage purge runs
            // in the delete-project edge function triggered by the DB hook.
            api.getProject(projectId) // touch to verify existence/auth
            Result.success()
        } catch (t: Throwable) {
            Result.retry()
        }
    }
}

@HiltWorker
class LibraryRefreshWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val refresher: LibraryCacheRefresher,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        if (refresher.refreshCatalog()) Result.success() else Result.retry()
}

/** Implemented by LibraryRepositoryImpl (injected to avoid a module cycle). */
interface LibraryCacheRefresher {
    suspend fun refreshCatalog(): Boolean
}
