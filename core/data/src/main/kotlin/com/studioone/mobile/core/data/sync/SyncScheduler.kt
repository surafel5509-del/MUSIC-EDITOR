package com.studioone.mobile.core.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules background sync via WorkManager. Constraints: network required,
 * expedited where possible (op push latency matters for collaboration),
 * exponential backoff on failures (server 5xx / offline).
 */
@Singleton
class SyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val workManager = WorkManager.getInstance(context)

    fun scheduleProjectSync(projectId: String) {
        val request = OneTimeWorkRequestBuilder<ProjectSyncWorker>()
            .setInputData(workDataOf(ProjectSyncWorker.KEY_PROJECT_ID to projectId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.ONLINE)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(
            "sync-project-$projectId",
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun scheduleOpFlush(projectId: String) {
        val request = OneTimeWorkRequestBuilder<OpFlushWorker>()
            .setInputData(workDataOf(ProjectSyncWorker.KEY_PROJECT_ID to projectId))
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.ONLINE).build(),
            )
            .setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 2, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(
            "flush-ops-$projectId",
            ExistingWorkPolicy.REPLACE, // newest outbox state wins; worker drains all
            request,
        )
    }

    fun scheduleProjectDelete(projectId: String) {
        val request = OneTimeWorkRequestBuilder<ProjectDeleteWorker>()
            .setInputData(workDataOf(ProjectSyncWorker.KEY_PROJECT_ID to projectId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.ONLINE).build())
            .build()
        workManager.enqueueUniqueWork("delete-project-$projectId", ExistingWorkPolicy.KEEP, request)
    }

    fun schedulePeriodicLibraryRefresh() {
        val request = androidx.work.PeriodicWorkRequestBuilder<LibraryRefreshWorker>(6, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .setRequiresDeviceIdle(true)
                    .build(),
            )
            .build()
        workManager.enqueueUniquePeriodicWork(
            "library-refresh", ExistingWorkPolicy.KEEP, request,
        )
    }
}
