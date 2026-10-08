package com.studioone.mobile.core.data.repo

import android.content.Context
import com.studioone.mobile.core.audio.AudioEngineController
import com.studioone.mobile.core.common.DataError
import com.studioone.mobile.core.common.DataResult
import com.studioone.mobile.core.database.dao.ProjectDao
import com.studioone.mobile.core.data.mapper.ProjectSerializer
import com.studioone.mobile.core.domain.AudioSettingsRepository
import com.studioone.mobile.core.domain.EngineHealth
import com.studioone.mobile.core.domain.ProjectRepository
import com.studioone.mobile.core.domain.ProjectDocumentStore as DomainDocumentStore
import com.studioone.mobile.core.model.LatencyReport
import com.studioone.mobile.core.model.Project
import com.studioone.mobile.core.model.ProjectId
import com.studioone.mobile.core.datastore.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Latency probe + engine health surface for Settings. */
@Singleton
class AudioSettingsRepositoryImpl @Inject constructor(
    private val engine: AudioEngineController,
) : AudioSettingsRepository {

    override val latencyReport: Flow<LatencyReport?> = engine.latency

    override suspend fun runLatencyTest(): DataResult<LatencyReport> {
        // The definitive round-trip measurement (loopback click test) is a
        // native routine; here we surface the stream-reported latency which
        // is what users can act on (buffer size guidance).
        val report = engine.latency.value
            ?: return DataResult.Failure(DataError(DataError.Kind.UNSUPPORTED_DEVICE, "Engine not running"))
        return DataResult.Success(report)
    }

    override fun observeEngineHealth(): Flow<EngineHealth> =
        engine.masterMeters.map { frame ->
            EngineHealth(
                underrunsPerMinute = engine.latency.value?.xrunCount?.toFloat() ?: 0f,
                cpuLoadPercent = 0f, // native cpu probe exposed via latencyReport extension (roadmap)
                fxPoolPressure = frame.fxPoolPressure,
                thermalThrottling = false, // PowerManager.addThermalStatusListener wired in app module
            )
        }
}

/** App-private directories (bounces, record takes, downloaded packs). */
@Singleton
class FilesDirProviderImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : FilesDirProvider {
    override fun packsDir(): File = File(context.filesDir, "packs").apply { mkdirs() }
    override fun projectsDir(): File = File(context.filesDir, "projects").apply { mkdirs() }
    override fun bounceDir(): File = File(context.cacheDir, "bounce").apply { mkdirs() }
    override fun takesDir(): String = File(context.cacheDir, "takes").apply { mkdirs() }.absolutePath
}

/** Bridges ProjectDocumentStore (collab) to the project repository. */
@Singleton
class ProjectDocumentStoreImpl @Inject constructor(
    private val projectRepository: ProjectRepository,
) : ProjectDocumentStore {
    override suspend fun getProject(projectId: ProjectId): DataResult<Project> =
        projectRepository.getProject(projectId)

    override suspend fun saveLocal(project: Project) {
        projectRepository.saveProject(project)
    }

    override suspend fun saveProject(project: Project): Long =
        (projectRepository.saveProject(project) as? DataResult.Success)?.data ?: -1L
}
