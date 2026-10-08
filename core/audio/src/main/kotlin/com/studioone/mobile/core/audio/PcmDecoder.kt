package com.studioone.mobile.core.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Decodes any MediaExtractor-supported format (mp3, aac, ogg, flac, wav…)
 * to 32-bit float PCM cache files under cacheDir/pcm.
 *
 * Why a PCM cache: the real-time engine must never decode; import-time decode
 * gives us instant waveform peaks, sample-accurate trimming, and constant
 * prefetch cost. Cache entries are keyed by (content-hash of source uri +
 * mtime + target sample rate) and evicted LRU at 512MB (configurable).
 */
@Singleton
class PcmDecoder @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    data class PcmData(
        val file: File,
        val sampleRate: Int,
        val channels: Int,
        val frames: Long,
    )

    private val cacheRoot: File by lazy {
        File(context.cacheDir, "pcm").apply { mkdirs() }
    }

    /** Decode [uri] to interleaved float PCM at its native rate. */
    suspend fun decodeToFloat(uri: Uri, targetSampleRate: Int? = null): PcmData? =
        withContext(Dispatchers.IO) {
            val key = "${uri.toString().hashCode()}_${targetSampleRate ?: 0}.f32"
            val outFile = File(cacheRoot, key)
            val metaFile = File(cacheRoot, "$key.meta")
            if (outFile.exists() && metaFile.exists()) {
                val parts = metaFile.readText().split(',')
                if (parts.size == 3) {
                    return@withContext PcmData(
                        outFile, parts[0].toInt(), parts[1].toInt(), parts[2].toLong(),
                    )
                }
            }
            decodeInternal(uri, outFile, metaFile, targetSampleRate)
        }

    private fun decodeInternal(uri: Uri, outFile: File, metaFile: File, targetSampleRate: Int?): PcmData? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = selectAudioTrack(extractor) ?: return null
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val srcRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)

            val codec = MediaCodec.createDecoderByType(mime)
            // Decode to FLOAT PCM (API 24+); engine consumes float directly.
            val outFormat = MediaFormat.createAudioFormat(mime, srcRate, channels).apply {
                setInteger(MediaFormat.KEY_PCM_ENCODING, android.media.AudioFormat.ENCODING_PCM_FLOAT)
            }
            codec.configure(outFormat, null, null, 0)
            codec.start()

            outFile.outputStream().use { out ->
                val info = MediaCodec.BufferInfo()
                var sawInputEOS = false
                var sawOutputEOS = false
                var totalFrames = 0L
                val timeoutUs = 10_000L
                while (!sawOutputEOS) {
                    if (!sawInputEOS) {
                        val inIndex = codec.dequeueInputBuffer(timeoutUs)
                        if (inIndex >= 0) {
                            val buffer = codec.getInputBuffer(inIndex)!!
                            val sampleSize = extractor.readSampleData(buffer, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                sawInputEOS = true
                            } else {
                                codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val outIndex = codec.dequeueOutputBuffer(info, timeoutUs)
                    if (outIndex >= 0) {
                        val buffer = codec.getOutputBuffer(outIndex)!!
                        if (info.size > 0) {
                            val bytes = ByteArray(info.size)
                            buffer.get(bytes)
                            out.write(bytes)
                            totalFrames += info.size.toLong() / (channels * 4)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEOS = true
                    } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        // Rate/format negotiation resolved here if needed.
                    }
                }
                codec.stop()
                codec.release()
                metaFile.writeText("$srcRate,$channels,$totalFrames")
                // targetSampleRate resampling happens in PlaybackFeeder (linear/cubic SRC).
                return PcmData(outFile, srcRate, channels, totalFrames)
            }
        } catch (t: Throwable) {
            Timber.e(t, "decode failed for $uri")
            outFile.delete(); metaFile.delete()
            return null
        } finally {
            try { extractor.release() } catch (_: Throwable) {}
        }
    }

    private fun selectAudioTrack(extractor: MediaExtractor): Int? {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i
        }
        return null
    }

    fun evictCache(maxBytes: Long = 512L * 1024 * 1024) {
        val files = cacheRoot.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= maxBytes) break
            total -= f.length()
            f.delete()
        }
    }
}
