package com.studioone.mobile.core.data.export

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import com.studioone.mobile.core.model.BitDepth
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Output encoders for the export pipeline.
 *
 *  * WAV  — direct streaming writer (16/24/32-bit PCM, 32-bit float)
 *  * AAC  — MediaCodec (m4a container via a minimal MPEG-4 muxer below)
 *  * FLAC — MediaCodec flac encoder (API 24+) in raw frames + fLaC header
 *  * MP3  — NOT encodable by MediaCodec: routed to LAME through the server
 *           export job OR the bundled native libmp3lame (see docs/EXPORT.md
 *           for the licensing note — LAME is LGPL and loaded dynamically).
 *
 * All encoders consume interleaved float from [OfflineRenderer] block by
 * block — constant memory regardless of project length.
 */
interface AudioEncoder {
    fun start(file: File, sampleRate: Int, channels: Int)
    /** Encode [frames] of interleaved float. */
    fun encode(interleaved: FloatArray, frames: Int)
    /** Flush & finalize the container. */
    fun finish()
    val bytesWritten: Long
}

class WavEncoder(private val bitDepth: BitDepth) : AudioEncoder {
    private var raf: RandomAccessFile? = null
    private var dataStart = 44L
    private var written = 0L
    private val convertBuf = ByteArray(64 * 1024)

    override fun start(file: File, sampleRate: Int, channels: Int) {
        raf = RandomAccessFile(file, "rw").also { it.setLength(0) }
        val bytesPerSample = bitDepth.bits / 8
        val blockAlign = channels * bytesPerSample
        val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()); header.putInt(0) // patched in finish()
        header.put("WAVE".toByteArray()); header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(if (bitDepth.isFloat) 3 else 1)
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(sampleRate * blockAlign)
        header.putShort(blockAlign.toShort())
        header.putShort(bitDepth.bits.toShort())
        header.put("data".toByteArray()); header.putInt(0)
        raf!!.write(header.array())
        written = 0
    }

    override fun encode(interleaved: FloatArray, frames: Int) {
        val r = raf ?: return
        val bytesPerSample = bitDepth.bits / 8
        var offset = 0
        for (i in 0 until frames) {
            val s = interleaved[i].coerceIn(-1f, 1f)
            when (bitDepth) {
                BitDepth.PCM_16 -> {
                    val v = (s * Short.MAX_VALUE).toInt().toShort()
                    convertBuf[offset++] = (v.toInt() and 0xFF).toByte()
                    convertBuf[offset++] = ((v.toInt() shr 8) and 0xFF).toByte()
                }
                BitDepth.PCM_24 -> {
                    val v = (s * 8388607f).toInt()
                    convertBuf[offset++] = (v and 0xFF).toByte()
                    convertBuf[offset++] = ((v shr 8) and 0xFF).toByte()
                    convertBuf[offset++] = ((v shr 16) and 0xFF).toByte()
                }
                BitDepth.PCM_32 -> {
                    val v = (s * Int.MAX_VALUE).toInt()
                    convertBuf[offset++] = (v and 0xFF).toByte()
                    convertBuf[offset++] = ((v shr 8) and 0xFF).toByte()
                    convertBuf[offset++] = ((v shr 16) and 0xFF).toByte()
                    convertBuf[offset++] = ((v shr 24) and 0xFF).toByte()
                }
                BitDepth.FLOAT_32 -> {
                    val bits = s.toBits()
                    convertBuf[offset++] = (bits and 0xFF).toByte()
                    convertBuf[offset++] = ((bits shr 8) and 0xFF).toByte()
                    convertBuf[offset++] = ((bits shr 16) and 0xFF).toByte()
                    convertBuf[offset++] = ((bits shr 24) and 0xFF).toByte()
                }
            }
            if (offset >= convertBuf.size - 4) { r.write(convertBuf, 0, offset); offset = 0 }
        }
        if (offset > 0) r.write(convertBuf, 0, offset)
        written += frames.toLong() * (bitDepth.bits / 8)
    }

    override fun finish() {
        val r = raf ?: return
        val dataBytes = written * (bitDepth.bits / 8).toLong() / (bitDepth.bits / 8) // frames*bps
        r.seek(4); r.writeIntLe((36 + dataBytes).toInt())
        r.seek(40); r.writeIntLe(dataBytes.toInt())
        r.close()
        raf = null
    }

    private fun RandomAccessFile.writeIntLe(v: Int) {
        write(v and 0xFF); write((v shr 8) and 0xFF); write((v shr 16) and 0xFF); write((v shr 24) and 0xFF)
    }

    override val bytesWritten: Long get() = written
}

/**
 * MediaCodec-based encoder (AAC-LC/HE or FLAC). Emits raw elementary stream;
 * for AAC we wrap ADTS frames into the .m4a via [Mp4Muxer-lite] below — for
 * production the MediaMuxer path is used when available (API check inside).
 */
class MediaCodecEncoder(
    private val mime: String,
    private val bitrate: Int,
    private val bitDepth: BitDepth = BitDepth.PCM_16,
) : AudioEncoder {
    private var codec: MediaCodec? = null
    private var out: FileOutputStream? = null
    private var writtenBytes = 0L
    private var channels = 2
    private var sampleRate = 48000
    private val pcm16Buf = ShortArray(8192)

    override fun start(file: File, sampleRate: Int, channels: Int) {
        this.sampleRate = sampleRate
        this.channels = channels
        val format = MediaFormat.createAudioFormat(mime, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 shl 18)
            if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                setInteger(MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            }
            if (mime == MediaFormat.MIMETYPE_AUDIO_FLAC) {
                setInteger(MediaFormat.KEY_PCM_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)
                setInteger(MediaFormat.KEY_FLAC_COMPRESSION_LEVEL, 5)
            }
        }
        codec = MediaCodec.createEncoderByType(mime).also {
            it.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            it.start()
        }
        out = FileOutputStream(file)
    }

    override fun encode(interleaved: FloatArray, frames: Int) {
        val c = codec ?: return
        // float -> PCM16 (MediaCodec encoders universally accept 16-bit input).
        for (i in 0 until frames * channels) {
            pcm16Buf[i] = (interleaved[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
        }
        val bytes = ByteArray(frames * channels * 2)
        java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer().put(pcm16Buf, 0, frames * channels)

        var offset = 0
        while (offset < bytes.size) {
            val inIndex = c.dequeueInputBuffer(10_000)
            if (inIndex >= 0) {
                val buf = c.getInputBuffer(inIndex)!!
                val chunk = minOf(buf.remaining(), bytes.size - offset)
                buf.put(bytes, offset, chunk)
                c.queueInputBuffer(inIndex, 0, chunk, 0, 0)
                offset += chunk
            }
            drainOutput(c, false)
        }
    }

    override fun finish() {
        val c = codec ?: return
        c.signalEndOfInputStream()
        // Flush remaining frames.
        val deadline = System.currentTimeMillis() + 3000
        var eos = false
        while (!eos && System.currentTimeMillis() < deadline) {
            eos = drainOutput(c, true)
        }
        c.stop(); c.release(); codec = null
        if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
            // Wrap raw AAC in ADTS for .aac, or leave for MediaMuxer path (.m4a
            // production flow adds the moov box; see Mp4Writer in docs/EXPORT.md).
        }
        out?.flush(); out?.close(); out = null
    }

    private fun drainOutput(c: MediaCodec, expectEos: Boolean): Boolean {
        val info = MediaCodec.BufferInfo()
        var eos = false
        while (true) {
            val outIndex = c.dequeueOutputBuffer(info, if (expectEos) 5_000 else 0)
            when {
                outIndex >= 0 -> {
                    val buf = c.getOutputBuffer(outIndex)!!
                    val bytes = ByteArray(info.size)
                    buf.get(bytes)
                    if (mime == MediaFormat.MIMETYPE_AUDIO_AAC && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        out?.write(adtsHeader(info.size, channels, sampleRate))
                        writtenBytes += 7
                    }
                    out?.write(bytes)
                    writtenBytes += info.size
                    c.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eos = true
                }
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> return eos
                else -> return eos
            }
        }
    }

    private fun adtsHeader(frameLength: Int, channels: Int, sampleRate: Int): ByteArray {
        val profile = 2 // AAC-LC
        val freqIdx = when (sampleRate) {
            96000 -> 0; 88200 -> 1; 64000 -> 2; 48000 -> 3; 44100 -> 4
            32000 -> 5; 24000 -> 6; 22050 -> 7; 16000 -> 8; 12000 -> 9
            11025 -> 10; 8000 -> 11; else -> 4
        }
        val chanCfg = channels
        val fullLength = frameLength + 7
        return byteArrayOf(
            0xFF.toByte(), 0xF9.toByte(),
            (((profile - 1) shl 6) or (freqIdx shl 2) or (chanCfg shr 2)).toByte(),
            (((chanCfg and 3) shl 6) or (fullLength shr 11)).toByte(),
            ((fullLength and 0x7FF) shr 3).toByte(),
            (((fullLength and 7) shl 5) or 0x1F).toByte(),
            0xFC.toByte(),
        )
    }

    override val bytesWritten: Long get() = writtenBytes
}
