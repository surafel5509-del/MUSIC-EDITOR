package com.studioone.mobile.core.audio

import com.studioone.mobile.core.common.TimeMath
import com.studioone.mobile.core.model.WaveformPeaks
import java.io.File
import java.io.RandomAccessFile
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Extracts min/max peak envelopes from decoded PCM (float) cache files for
 * waveform rendering. Produces a multi-resolution pyramid:
 *   level 0: 256 frames/bucket  (zoomed in)
 *   level 1: 1024
 *   level 2: 4096
 *   level 3: 16384 (fully zoomed out)
 * The arranger picks the level matching pixels-per-frame so drawing never
 * reads more than ~4 buckets per pixel column.
 */
@Singleton
class WaveformExtractor @Inject constructor() {

    companion object {
        val BUCKET_SIZES = intArrayOf(256, 1024, 4096, 16384)
    }

    /** Extract all levels for a decoded float-PCM file. */
    suspend fun extract(
        pcmFile: File, channels: Int, totalFrames: Long,
    ): List<WaveformPeaks> = withContext(Dispatchers.IO) {
        BUCKET_SIZES.map { bucket -> extractLevel(pcmFile, channels, totalFrames, bucket) }
    }

    private fun extractLevel(
        pcmFile: File, channels: Int, totalFrames: Long, bucketFrames: Int,
    ): WaveformPeaks {
        val buckets = ((totalFrames + bucketFrames - 1) / bucketFrames).toInt().coerceAtLeast(1)
        val peaks = FloatArray(buckets * channels * 2)
        // Init mins to +1, maxes to -1.
        for (b in 0 until buckets) for (c in 0 until channels) {
            peaks[(b * channels + c) * 2] = 1f
            peaks[(b * channels + c) * 2 + 1] = -1f
        }
        RandomAccessFile(pcmFile, "r").use { raf ->
            val readBuf = ByteArray(8192 * 4 * channels)
            val floatBuf = FloatArray(8192 * channels)
            var framePos = 0L
            while (framePos < totalFrames) {
                val wantFrames = minOf(8192L, totalFrames - framePos).toInt()
                val wantBytes = wantFrames * channels * 4
                val read = raf.read(readBuf, 0, wantBytes)
                if (read <= 0) break
                val gotFrames = read / (channels * 4)
                // Little-endian float decode.
                for (i in 0 until gotFrames * channels) {
                    val bits = (readBuf[i * 4].toInt() and 0xFF) or
                        ((readBuf[i * 4 + 1].toInt() and 0xFF) shl 8) or
                        ((readBuf[i * 4 + 2].toInt() and 0xFF) shl 16) or
                        ((readBuf[i * 4 + 3].toInt() and 0xFF) shl 24)
                    floatBuf[i] = Float.fromBits(bits)
                }
                for (f in 0 until gotFrames) {
                    val bucket = ((framePos + f) / bucketFrames).toInt()
                    for (c in 0 until channels) {
                        val s = floatBuf[f * channels + c]
                        val idx = (bucket * channels + c) * 2
                        if (s < peaks[idx]) peaks[idx] = s
                        if (s > peaks[idx + 1]) peaks[idx + 1] = s
                    }
                }
                framePos += gotFrames
            }
        }
        // Normalize inverted sentinels (empty buckets) to zero.
        for (i in peaks.indices step 2) {
            if (peaks[i] > peaks[i + 1]) { peaks[i] = 0f; peaks[i + 1] = 0f }
        }
        return WaveformPeaks(bucketFrames, channels, peaks)
    }
}

/** dB helpers re-exported for feature modules (avoid depending on core:common internals). */
object AudioUnits {
    fun dbToLinear(db: Float): Float = TimeMath.dbToLinear(db)
    fun linearToDb(lin: Float): Float = TimeMath.linearToDb(lin)
}
