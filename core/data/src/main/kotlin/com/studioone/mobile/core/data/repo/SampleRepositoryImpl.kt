package com.studioone.mobile.core.data.repo

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.studioone.mobile.core.audio.PcmDecoder
import com.studioone.mobile.core.audio.WaveformExtractor
import com.studioone.mobile.core.common.DataError
import com.studioone.mobile.core.common.DataResult
import com.studioone.mobile.core.common.IdGenerator
import com.studioone.mobile.core.database.dao.SampleDao
import com.studioone.mobile.core.database.entity.SampleEntity
import com.studioone.mobile.core.domain.SampleRepository
import com.studioone.mobile.core.model.AudioDeviceInfo
import com.studioone.mobile.core.model.AudioFileRef
import com.studioone.mobile.core.model.AudioFormatKind
import com.studioone.mobile.core.model.BitDepth
import com.studioone.mobile.core.model.ProjectId
import com.studioone.mobile.core.model.SampleId
import com.studioone.mobile.core.model.WaveformPeaks
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * Sample import & analysis: SAF/MediaStore URIs -> decoded PCM cache ->
 * multi-resolution peaks -> (optional) BPM/key analysis.
 *
 * BPM/key detection: local energy-flux autocorrelation for BPM (fast, good
 * enough for loops) + Krumhansl-Schmuckler key profiles over a chroma built
 * from the decoded PCM. Cloud re-analysis (higher quality, libsonic-based)
 * runs asynchronously at library ingest; local values are the fallback.
 */
@Singleton
class SampleRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pcmDecoder: PcmDecoder,
    private val waveformExtractor: WaveformExtractor,
    private val sampleDao: SampleDao,
    private val filesDirProvider: FilesDirProvider,
) : SampleRepository {

    override suspend fun importFromUri(uri: String, projectId: ProjectId?): DataResult<AudioFileRef> {
        return try {
            val parsed = Uri.parse(uri)
            val retriever = MediaMetadataRetriever().apply { setDataSource(context, parsed) }
            val name = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?: parsed.lastPathSegment ?: "Sample"
            val decoded = pcmDecoder.decodeToFloat(parsed)
                ?: return DataResult.Failure(DataError(DataError.Kind.UNSUPPORTED_DEVICE, "Cannot decode this file"))
            val peaks = waveformExtractor.extract(decoded.file, decoded.channels, decoded.frames)
            val id = SampleId(IdGenerator.newId())
            val ref = AudioFileRef(
                id = id, uri = uri,
                format = guessFormat(uri),
                sampleRate = decoded.sampleRate, channels = decoded.channels,
                bitDepth = BitDepth.FLOAT_32,
                durationFrames = decoded.frames,
                sizeBytes = decoded.file.length(),
                peaks = peaks.firstOrNull(),
            )
            sampleDao.upsert(
                SampleEntity(
                    id = id.value, projectId = projectId?.value, packId = null, name = name,
                    uri = uri, format = ref.format.name, sampleRate = decoded.sampleRate,
                    channels = decoded.channels, bitDepth = 32, durationFrames = decoded.frames,
                    sizeBytes = decoded.file.length(),
                    peaksJson = encodePeaks(peaks),
                    loudnessLufs = null, bpm = null, keyName = null,
                    createdAt = System.currentTimeMillis(), origin = "IMPORTED",
                ),
            )
            retriever.release()
            DataResult.Success(ref)
        } catch (t: Throwable) {
            DataResult.Failure(DataError.from(t))
        }
    }

    override suspend fun importFromMediaStore(mediaId: Long): DataResult<AudioFileRef> =
        importFromUri(
            android.content.ContentUris.withAppendedId(
                android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaId,
            ).toString(),
            projectId = null,
        )

    override suspend fun extractPeaks(fileRef: AudioFileRef): DataResult<AudioFileRef> = fileRef // peaks built at import

    override suspend fun detectBpmAndKey(fileRef: AudioFileRef): DataResult<Pair<Double?, String?>> {
        return try {
            val decoded = pcmDecoder.decodeToFloat(Uri.parse(fileRef.uri))
                ?: return DataResult.Success(null to null)
            val analyzer = com.studioone.mobile.core.data.analysis.TempoKeyAnalyzer()
            val bpm = analyzer.estimateBpm(decoded.file, decoded.sampleRate, decoded.channels)
            val key = analyzer.estimateKey(decoded.file, decoded.sampleRate, decoded.channels)
            DataResult.Success(bpm to key)
        } catch (t: Throwable) {
            DataResult.Failure(DataError.from(t))
        }
    }

    override suspend fun deleteSample(sampleId: String): DataResult<Unit> {
        sampleDao.delete(sampleId)
        return DataResult.Success(Unit)
    }

    override fun observeProjectSamples(projectId: ProjectId): Flow<List<AudioFileRef>> = flow {
        emit(sampleDao.forProject(projectId.value).map { it.toRef() })
    }

    override suspend fun listInputDevices(): List<AudioDeviceInfo> {
        val manager = context.getSystemService(android.media.AudioManager::class.java)
        val devices = manager.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS)
        return devices.map { d ->
            AudioDeviceInfo(
                id = d.id.toString(),
                name = d.productName?.toString() ?: "Input ${d.id}",
                type = when (d.type) {
                    android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC -> com.studioone.mobile.core.model.AudioDeviceType.BUILTIN_MIC
                    android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET -> com.studioone.mobile.core.model.AudioDeviceType.WIRED_HEADSET
                    android.media.AudioDeviceInfo.TYPE_USB_DEVICE, android.media.AudioDeviceInfo.TYPE_USB_HEADSET -> com.studioone.mobile.core.model.AudioDeviceType.USB_AUDIO
                    android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO, android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> com.studioone.mobile.core.model.AudioDeviceType.BLUETOOTH_A2DP
                    android.media.AudioDeviceInfo.TYPE_BLE_HEADSET -> com.studioone.mobile.core.model.AudioDeviceType.BLUETOOTH_LE
                    else -> com.studioone.mobile.core.model.AudioDeviceType.BUILTIN_MIC
                },
                inputChannels = d.channelCounts.maxOrNull() ?: 1,
                outputChannels = 0,
                sampleRates = d.sampleRates.toList(),
                isLowLatencyCapable = d.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC ||
                    d.type == android.media.AudioDeviceInfo.TYPE_USB_DEVICE,
            )
        }
    }

    private fun guessFormat(uri: String): AudioFormatKind = when (uri.substringAfterLast('.', "wav").lowercase()) {
        "mp3" -> AudioFormatKind.MP3
        "aac", "m4a" -> AudioFormatKind.AAC
        "flac" -> AudioFormatKind.FLAC
        "ogg" -> AudioFormatKind.OGG
        else -> AudioFormatKind.WAV
    }

    private fun encodePeaks(peaks: List<WaveformPeaks>): String =
        com.studioone.mobile.core.data.mapper.ProjectSerializer.json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(WaveformPeaks.serializer()), peaks)
}

private fun SampleEntity.toRef() = AudioFileRef(
    id = SampleId(id), uri = uri,
    format = runCatching { AudioFormatKind.valueOf(format) }.getOrDefault(AudioFormatKind.WAV),
    sampleRate = sampleRate, channels = channels,
    bitDepth = BitDepth.PCM_16,
    durationFrames = durationFrames, sizeBytes = sizeBytes,
)
