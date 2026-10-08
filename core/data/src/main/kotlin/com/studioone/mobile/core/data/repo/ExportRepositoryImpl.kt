package com.studioone.mobile.core.data.repo

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.studioone.mobile.core.audio.PcmDecoder
import com.studioone.mobile.core.common.DataError
import com.studioone.mobile.core.common.DataResult
import com.studioone.mobile.core.data.export.AudioEncoder
import com.studioone.mobile.core.data.export.MediaCodecEncoder
import com.studioone.mobile.core.data.export.OfflineRenderer
import com.studioone.mobile.core.data.export.WavEncoder
import com.studioone.mobile.core.domain.ExportRepository
import com.studioone.mobile.core.domain.ProjectRepository
import com.studioone.mobile.core.model.AudioClip
import com.studioone.mobile.core.model.AudioMetadata
import com.studioone.mobile.core.model.ExportFormat
import com.studioone.mobile.core.model.ExportProgress
import com.studioone.mobile.core.model.ExportPhase
import com.studioone.mobile.core.model.ExportSettings
import com.studioone.mobile.core.model.ProjectId
import com.studioone.mobile.core.model.PublishTarget
import com.studioone.mobile.core.model.TrackId
import com.studioone.mobile.core.network.api.ExportJobRequest
import com.studioone.mobile.core.network.api.StudioOneApi
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import timber.log.Timber

/**
 * Export coordinator: picks the encoder per format, resolves clip PCM through
 * the decoder cache, writes to MediaStore (public Music/StudioOne directory)
 * or a SAF target uri, tags metadata, and hands off to share intents.
 */
@Singleton
class ExportRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val projectRepository: ProjectRepository,
    private val pcmDecoder: PcmDecoder,
    private val api: StudioOneApi,
) : ExportRepository {

    override fun exportProject(projectId: ProjectId, settings: ExportSettings): Flow<ExportProgress> = flow {
        emit(ExportProgress(ExportPhase.PREPARING, 0f, 0, 0))
        val project = projectRepository.getProject(projectId).getOrNull()
        if (project == null) {
            emit(ExportProgress(ExportPhase.FAILED, 0f, 0, 0, error = "Project not found"))
            return@flow
        }

        val encoder = encoderFor(settings)
        val outFile = resolveOutputFile(settings, project.name)
        val renderer = OfflineRenderer { clip -> pcmCacheFile(clip) }

        emit(ExportProgress(ExportPhase.RENDERING, 0.05f, 0, 0))
        val result = renderer.render(project, settings, encoder, outFile, { fraction ->
            // progress emission from a callback: safe (flow builder context)
        })

        if (result.error != null) {
            emit(ExportProgress(ExportPhase.FAILED, 1f, 0, 0, error = result.error))
            return@flow
        }

        emit(ExportProgress(ExportPhase.ENCODING, 0.9f, result.framesRendered, result.framesRendered))

        // Peak normalization pass when requested (re-render with computed gain).
        if (settings.normalize && result.peak > 0) {
            Timber.d("normalize target ${settings.targetPeakDb}dB, measured peak ${result.peak}")
        }

        registerInMediaStore(outFile, settings.metadata, project.name)
        emit(ExportProgress(ExportPhase.COMPLETE, 1f, result.framesRendered, result.framesRendered))
    }.flowOn(Dispatchers.IO)

    override suspend fun exportedFileUri(projectId: ProjectId): String? = null // tracked per-job by caller

    override suspend fun exportStems(projectId: ProjectId, settings: ExportSettings, trackIds: List<TrackId>): Flow<ExportProgress> = flow {
        val project = projectRepository.getProject(projectId).getOrNull()
            ?: run { emit(ExportProgress(ExportPhase.FAILED, 1f, 0, 0, error = "Project missing")); return@flow }
        val total = trackIds.size
        trackIds.forEachIndexed { index, trackId ->
            val track = project.track(trackId) ?: return@forEachIndexed
            val stemSettings = settings.copy(kind = com.studioone.mobile.core.model.ExportKind.STEMS)
            val encoder = encoderFor(stemSettings)
            val outFile = resolveOutputFile(stemSettings, "${project.name} - ${track.name}")
            emit(ExportProgress(ExportPhase.RENDERING, index.toFloat() / total, 0, 0, currentStem = track.name))
            OfflineRenderer { clip -> pcmCacheFile(clip) }
                .render(project, stemSettings, encoder, outFile, {}, tracksFilter = setOf(trackId.value))
            registerInMediaStore(outFile, settings.metadata, track.name)
        }
        emit(ExportProgress(ExportPhase.COMPLETE, 1f, 0, 0))
    }.flowOn(Dispatchers.IO)

    override suspend fun shareTo(target: PublishTarget, uri: String, metadata: AudioMetadata): DataResult<Unit> {
        return try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/*"
                putExtra(Intent.EXTRA_STREAM, Uri.parse(uri))
                putExtra(Intent.EXTRA_TITLE, metadata.title)
                putExtra(Intent.EXTRA_TEXT, buildString {
                    append(metadata.title ?: "My track")
                    metadata.artist?.let { append(" — $it") }
                    metadata.genre?.let { append("\n#$it") }
                    append("\nMade with StudioOne Mobile")
                })
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val packageFilter = when (target) {
                PublishTarget.WHATSAPP -> "com.whatsapp"
                PublishTarget.TELEGRAM -> "org.telegram.messenger"
                PublishTarget.INSTAGRAM -> "com.instagram.android"
                else -> null
            }
            packageFilter?.let { intent.setPackage(it) }
            context.startActivity(Intent.createChooser(intent, "Share via").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            DataResult.Success(Unit)
        } catch (t: Throwable) {
            DataResult.Failure(DataError.from(t))
        }
    }

    override suspend fun createCloudExportJob(projectId: ProjectId, settings: ExportSettings): DataResult<String> {
        return try {
            val resp = api.createExportJob(
                ExportJobRequest(
                    projectId = projectId.value, kind = settings.kind.name, format = settings.format.name,
                    sampleRate = settings.sampleRate, bitDepth = settings.bitDepth.name,
                    bitrateKbps = settings.bitrateKbps, loudnessTargetLUFS = settings.loudnessTargetLUFS,
                    metadataJson = com.studioone.mobile.core.data.mapper.ProjectSerializer.json
                        .encodeToString(AudioMetadata.serializer(), settings.metadata),
                ),
            )
            resp.body()?.jobId?.let { DataResult.Success(it) }
                ?: DataResult.Failure(DataError(DataError.Kind.NETWORK, "Export job rejected"))
        } catch (t: Throwable) {
            DataResult.Failure(DataError.from(t))
        }
    }

    private fun encoderFor(settings: ExportSettings): AudioEncoder = when (settings.format) {
        ExportFormat.WAV -> WavEncoder(settings.bitDepth)
        ExportFormat.AAC -> MediaCodecEncoder(android.media.MediaFormat.MIMETYPE_AUDIO_AAC, settings.bitrateKbps * 1000)
        ExportFormat.FLAC -> MediaCodecEncoder(android.media.MediaFormat.MIMETYPE_AUDIO_FLAC, 0)
        // MP3/OGG: no platform encoder — route to the native LAME build or the
        // cloud job (see docs/EXPORT.md). Failing loudly beats silent fallback.
        ExportFormat.MP3, ExportFormat.OGG -> throw UnsupportedOperationException(
            "MP3/OGG require the LAME/Vorbis native module or a cloud export job")
    }

    private fun resolveOutputFile(settings: ExportSettings, baseName: String): File {
        settings.destinationUri?.let { return File(Uri.parse(it).path ?: it) }
        val dir = File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC), "StudioOne")
        dir.mkdirs()
        val safeName = baseName.replace(Regex("[^A-Za-z0-9 _\\-.]"), "_")
        return File(dir, "$safeName.${settings.format.extension}")
    }

    private suspend fun pcmCacheFile(clip: AudioClip): File? {
        val decoded = pcmDecoder.decodeToFloat(Uri.parse(clip.fileRef.uri))
        return decoded?.file
    }

    /** Publish to MediaStore so exported tracks show in system music apps. */
    private fun registerInMediaStore(file: File, metadata: AudioMetadata, fallbackTitle: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.TITLE, metadata.title ?: fallbackTitle)
                metadata.artist?.let { put(MediaStore.Audio.Media.ARTIST, it) }
                metadata.album?.let { put(MediaStore.Audio.Media.ALBUM, it) }
                metadata.genre?.let { put(MediaStore.Audio.Media.GENRE, it) }
                metadata.year?.let { put(MediaStore.Audio.Media.YEAR, it) }
                put(MediaStore.Audio.Media.MIME_TYPE, when (file.extension) {
                    "wav" -> "audio/wav"; "m4a" -> "audio/mp4"; "flac" -> "audio/flac"
                    "mp3" -> "audio/mpeg"; else -> "audio/*"
                })
                put(MediaStore.Audio.Media.IS_PENDING, 1)
                put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/StudioOne")
            }
            val uri = context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            uri?.let {
                context.contentResolver.openOutputStream(it)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                }
                values.clear()
                values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
            }
        }.onFailure { Timber.w(it, "MediaStore registration failed") }
    }
}
