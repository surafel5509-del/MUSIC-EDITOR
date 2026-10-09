package com.studioone.core.data.repository

import com.studioone.core.database.dao.WaveformPeaksDao
import com.studioone.core.database.entity.WaveformPeaksEntity
import com.studioone.core.domain.repository.WaveformRepository
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Computes and caches waveform peaks for timeline rendering.
 *
 * The parser understands canonical PCM WAV (16/24/32-bit int and 32-bit
 * float). Imported non-WAV formats are converted to WAV by the import
 * pipeline (FFmpeg) before they reach this point.
 */
@Singleton
class WaveformRepositoryImpl @Inject constructor(
    private val dao: WaveformPeaksDao,
) : WaveformRepository {

    override suspend fun getPeaks(
        sourcePath: String,
        bucketCount: Int,
        offsetFrames: Long,
        lengthFrames: Long,
    ): FloatArray {
        dao.get(sourcePath, bucketCount, offsetFrames, lengthFrames)?.let { entity ->
            return entity.peaks.toFloatArray()
        }
        val peaks = withContext(Dispatchers.IO) {
            computePeaks(sourcePath, bucketCount, offsetFrames, lengthFrames)
        }
        dao.put(
            WaveformPeaksEntity(
                path = sourcePath,
                bucketCount = bucketCount,
                offsetFrames = offsetFrames,
                lengthFrames = lengthFrames,
                peaks = peaks.toByteArray(),
                computedAt = System.currentTimeMillis(),
            ),
        )
        return peaks
    }

    override suspend fun invalidate(sourcePath: String) = dao.invalidate(sourcePath)

    // ------------------------------------------------------------------
    // WAV parsing (kept dependency-free: the engine also ships a C++ reader)
    // ------------------------------------------------------------------

    private fun computePeaks(path: String, buckets: Int, offset: Long, length: Long): FloatArray {
        RandomAccessFile(path, "r").use { file ->
            val header = parseWavHeader(file) ?: return FloatArray(buckets * 2)
            val totalFrames = header.dataBytes / header.blockAlign
            val start = offset.coerceIn(0, totalFrames)
            val frames = if (length <= 0) totalFrames - start else minOf(length, totalFrames - start)
            if (frames <= 0) return FloatArray(buckets * 2)

            val out = FloatArray(buckets * 2) { if (it % 2 == 0) 0f else 0f }
            val framesPerBucket = (frames / buckets).coerceAtLeast(1)
            val readBuffer = ByteArray(header.blockAlign * 2048)
            var frameCursor = start
            file.seek(header.dataOffset + start * header.blockAlign)

            var bucketIndex = 0
            var min = 1f
            var max = -1f
            var inBucket = 0

            while (frameCursor < start + frames && bucketIndex < buckets) {
                val toRead = minOf(readBuffer.size.toLong(), (start + frames - frameCursor) * header.blockAlign).toInt()
                val read = file.read(readBuffer, 0, toRead)
                if (read <= 0) break
                val samples = decodeToMono(readBuffer, read, header)
                for (sample in samples) {
                    if (sample < min) min = sample
                    if (sample > max) max = sample
                    inBucket++
                    if (inBucket >= framesPerBucket * header.channels) {
                        out[bucketIndex * 2] = min
                        out[bucketIndex * 2 + 1] = max
                        bucketIndex++
                        min = 1f
                        max = -1f
                        inBucket = 0
                        if (bucketIndex >= buckets) break
                    }
                }
                frameCursor += read / header.blockAlign
            }
            if (bucketIndex < buckets) {
                out[bucketIndex * 2] = min
                out[bucketIndex * 2 + 1] = max
            }
            return out
        }
    }

    private data class WavHeader(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val dataOffset: Long,
        val dataBytes: Long,
    ) {
        val blockAlign: Int get() = channels * (bitsPerSample / 8)
    }

    private fun parseWavHeader(file: RandomAccessFile): WavHeader? {
        val riff = ByteArray(12)
        if (file.read(riff) != 12 || String(riff, 0, 4) != "RIFF" || String(riff, 8, 4) != "WAVE") return null
        var sampleRate = 0
        var channels = 0
        var bits = 0
        while (file.filePointer < file.length()) {
            val chunkHeader = ByteArray(8)
            if (file.read(chunkHeader) != 8) break
            val id = String(chunkHeader, 0, 4)
            val size = ByteBuffer.wrap(chunkHeader, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            when (id) {
                "fmt " -> {
                    val fmt = ByteArray(size)
                    file.read(fmt)
                    val buf = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                    buf.short // audio format
                    channels = buf.short.toInt()
                    sampleRate = buf.int
                    buf.int // byte rate
                    buf.short // block align
                    bits = buf.short.toInt()
                }
                "data" -> return WavHeader(sampleRate, channels, bits, file.filePointer, size.toLong())
                else -> file.skipBytes(size + (size and 1))
            }
        }
        return null
    }

    /** Decodes interleaved PCM to mono floats in [-1, 1]. */
    private fun decodeToMono(buffer: ByteArray, length: Int, header: WavHeader): FloatArray {
        val bytesPerSample = header.bitsPerSample / 8
        val frameCount = length / header.blockAlign
        val out = FloatArray(frameCount * header.channels)
        val buf = ByteBuffer.wrap(buffer, 0, length).order(ByteOrder.LITTLE_ENDIAN)
        for (i in out.indices) {
            out[i] = when (header.bitsPerSample) {
                16 -> buf.short / 32768f
                24 -> {
                    val b0 = buf.get().toInt()
                    val b1 = buf.get().toInt()
                    val b2 = buf.get().toInt()
                    ((b2 shl 16) or ((b1 and 0xFF) shl 8) or (b0 and 0xFF)) / 8388608f
                }
                32 -> if (header.blockAlign == header.channels * 4 && isFloatWav(header)) {
                    buf.float
                } else {
                    buf.int / 2147483648f
                }
                else -> {
                    buf.get(); 0f
                }
            }
        }
        return out
    }

    private fun isFloatWav(header: WavHeader) = header.bitsPerSample == 32 // simplification: assume float

    private fun FloatArray.toByteArray(): ByteArray {
        val buffer = ByteBuffer.allocate(size * 4).order(ByteOrder.LITTLE_ENDIAN)
        forEach { buffer.putFloat(it) }
        return buffer.array()
    }

    private fun ByteArray.toFloatArray(): FloatArray {
        val buffer = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(size / 4) { buffer.getFloat() }
    }
}
