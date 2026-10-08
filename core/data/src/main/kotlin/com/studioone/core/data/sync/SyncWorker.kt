package com.studioone.core.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.studioone.core.database.dao.SyncOutboxDao
import com.studioone.core.network.projects.RemoteProjectApi
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.contentOrNull

/**
 * Drains the sync outbox whenever the device is online. Retries with
 * exponential backoff; individual failures do not block later ops (attempts
 * counter + dead-lettering after MAX_ATTEMPTS keeps poison ops from looping).
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val outboxDao: SyncOutboxDao,
    private val remoteProjectApi: RemoteProjectApi,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        var failures = 0
        while (true) {
            val batch = outboxDao.nextBatch(BATCH_SIZE)
            if (batch.isEmpty()) break
            for (op in batch) {
                try {
                    dispatch(op.opJson)
                    outboxDao.acknowledge(op.id)
                } catch (e: Exception) {
                    outboxDao.markAttempt(op.id)
                    failures++
                    if (op.attempts >= MAX_ATTEMPTS) outboxDao.acknowledge(op.id) // dead-letter
                }
            }
            if (failures > 0) return Result.retry()
        }
        return Result.success()
    }

    private suspend fun dispatch(opJson: String) {
        val obj = Json.parseToJsonElement(opJson).jsonObject
        when (obj["type"]?.jsonPrimitive?.contentOrNull) {
            "project_upsert" -> {
                val row = com.studioone.core.network.projects.ProjectRow(
                    id = obj["id"]!!.jsonPrimitive.content,
                    name = obj["name"]?.jsonPrimitive?.content.orEmpty(),
                    tempo = obj["tempo"]?.jsonPrimitive?.let { kotlin.runCatching { it.content.toDouble() }.getOrDefault(120.0) } ?: 120.0,
                    sampleRate = obj["sample_rate"]?.jsonPrimitive?.let { kotlin.runCatching { it.content.toInt() }.getOrDefault(44100) } ?: 44100,
                    version = obj["version"]?.jsonPrimitive?.long?.toInt() ?: 1,
                )
                remoteProjectApi.upsertProject(row)
            }
            "project_delete" -> remoteProjectApi.deleteProject(obj["id"]!!.jsonPrimitive.content)
            "stem_upload" -> { /* handled by ExportPipeline; placeholder for milestone 2 */ }
            else -> Unit
        }
    }

    companion object {
        private const val WORK_NAME = "studioone_sync"
        private const val BATCH_SIZE = 25
        private const val MAX_ATTEMPTS = 8

        fun enqueue(workManager: WorkManager) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
