package com.studioone.mobile.core.data.export

import com.studioone.mobile.core.model.AudioClip
import com.studioone.mobile.core.model.ExportChannels
import com.studioone.mobile.core.model.ExportSettings
import com.studioone.mobile.core.model.MidiClip
import com.studioone.mobile.core.model.Project
import com.studioone.mobile.core.model.Track
import com.studioone.mobile.core.model.TrackType
import com.studioone.mobile.core.model.WaveformPeaks
import java.io.File
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import timber.log.Timber

/**
 * Offline mixdown renderer (device-side).
 *
 * Architecture decision (docs/EXPORT.md §2): the *authoritative* export with
 * full FX/instrument fidelity runs through the NATIVE graph in render mode
 * (the same C++ code path as live playback, pumped faster than realtime by
 * [NativeRenderBridge]). This Kotlin renderer is the portable fallback used
 * when:
 *   * the render is audio-clip-only (podcast stems, simple songs), or
 *   * the native render mode is unavailable (very old devices), or
 *   * background export while the live engine owns the audio device.
 *
 * It handles: clip gain, fades (equal-power), pan (constant power), mute/solo,
 * track order summing, stereo/mono downmix, optional peak normalization.
 */
class OfflineRenderer(
    private val pcmFileResolver: (AudioClip) -> File?,
) {
    data class RenderResult(val framesRendered: Long, val peak: Float, val error: String? = null)

    fun render(
        project: Project,
        settings: ExportSettings,
        encoder: AudioEncoder,
        outputFile: File,
        onProgress: (Float) -> Unit,
        tracksFilter: Set<String>? = null, // stem export: single track per pass
    ): RenderResult {
        val sr = settings.sampleRate
        val channels = if (settings.channels == ExportChannels.MONO) 1 else 2
        encoder.start(outputFile, sr, channels)

        val startFrame = settings.rangeStartFrame
        val endFrame = if (settings.rangeEndFrame > 0) settings.rangeEndFrame else
            (com.studioone.mobile.core.domain.GridMath(project.tempoMap, project.timeSignature, project.sampleRate)
                .barsToFrames(project.durationBars))

        val audible = project.tracks.filter { t ->
            (tracksFilter == null || t.id.value in tracksFilter) &&
                !t.mute && (project.tracks.none { it.solo } || it.solo) &&
                t.type != TrackType.MASTER
        }

        // Open clip readers.
        val readers = audible.mapNotNull { track ->
            val clips = track.clips.filterIsInstance<AudioClip>()
            if (clips.isEmpty()) return@mapNotNull null
            TrackReader(track, clips, sr)
        }

        val block = 1024
        val mixL = FloatArray(block)
        val mixR = FloatArray(block)
        val interleaved = FloatArray(block * channels)
        var frame = startFrame
        var peak = 0f
        var error: String? = null
        val totalFrames = max(1, endFrame - startFrame)

        try {
            readers.forEach { it.open(pcmFileResolver) }
            while (frame < endFrame) {
                val n = min(block.toLong(), endFrame - frame).toInt()
                mixL.fill(0f, 0, n); mixR.fill(0f, 0, n)
                for (reader in readers) {
                    reader.readInto(frame, n, mixL, mixR)
                }
                // Track pan/gain per reader is applied inside readInto.
                for (i in 0 until n) {
                    peak = max(peak, max(kotlin.math.abs(mixL[i]), kotlin.math.abs(mixR[i])))
                }
                if (channels == 2) {
                    for (i in 0 until n) { interleaved[i * 2] = mixL[i]; interleaved[i * 2 + 1] = mixR[i] }
                } else {
                    for (i in 0 until n) { interleaved[i] = (mixL[i] + mixR[i]) * 0.5f }
                }
                encoder.encode(interleaved, n)
                frame += n
                if ((frame - startFrame) % (sr * 2) < block) onProgress((frame - startFrame).toFloat() / totalFrames)
            }
        } catch (t: Throwable) {
            Timber.e(t, "offline render failed")
            error = t.message ?: "render error"
        } finally {
            readers.forEach { it.close() }
            encoder.finish()
        }
        onProgress(1f)
        return RenderResult(frame - startFrame, peak, error)
    }

    /** Per-track clip reader: resolves the active clip at a frame and applies
     *  clip gain + fades + track gain/pan into the mix buffers. */
    private class TrackReader(
        val track: Track,
        val clips: List<AudioClip>,
        val projectRate: Int,
    ) {
        private val sources = mutableMapOf<AudioClip, ClipSource?>()

        fun open(resolver: (AudioClip) -> File?) {
            for (clip in clips) {
                val file = resolver(clip)
                sources[clip] = file?.let { ClipSource(it, clip) }
            }
        }

        fun readInto(startFrame: Long, frames: Int, mixL: FloatArray, mixR: FloatArray) {
            if (track.mute) return
            val trackGain = 10f.pow(track.volumeDb / 20f)
            val angle = (track.pan + 1f) * 0.25f * Math.PI.toFloat()
            val gL = cos(angle) * 1.4142135f
            val gR = sin(angle) * 1.4142135f

            for (clip in clips) {
                if (clip.muted) continue
                val overlapStart = max(startFrame, clip.startFrame)
                val overlapEnd = min(startFrame + frames, clip.endFrame)
                if (overlapStart >= overlapEnd) continue
                val source = sources[clip] ?: continue

                val clipGain = 10f.pow(clip.gainDb / 20f)
                for (frame in overlapStart until overlapEnd) {
                    val idx = (frame - startFrame).toInt()
                    val clipRel = frame - clip.startFrame
                    val sample = source.sampleAt(clipRel) ?: continue
                    // Equal-power fades.
                    var fade = 1f
                    if (clip.fade.fadeInFrames > 0 && clipRel < clip.fade.fadeInFrames) {
                        fade = kotlin.math.sqrt(clipRel.toFloat() / clip.fade.fadeInFrames)
                    }
                    if (clip.fade.fadeOutFrames > 0 && clipRel > clip.lengthFrames - clip.fade.fadeOutFrames) {
                        fade = kotlin.math.sqrt(max(0f, (clip.lengthFrames - clipRel).toFloat() / clip.fade.fadeOutFrames))
                    }
                    val g = clipGain * trackGain * fade
                    mixL[idx] += sample.first * g * gL
                    mixR[idx] += sample.second * g * gR
                }
            }
        }

        fun close() { sources.values.forEach { it?.close() } }
    }

    /** Random-access float PCM reader over a decoded cache file. */
    private class ClipSource(file: File, private val clip: AudioClip) {
        private val raf = java.io.RandomAccessFile(file, "r")
        private val channels = clip.fileRef.channels.coerceAtLeast(1)
        private val lastPos = -1L
        private val buf = FloatArray(2)

        fun sampleAt(clipRelativeFrame: Long): Pair<Float, Float>? {
            val srcFrame = clip.sourceOffsetFrames + clipRelativeFrame
            val bytePos = srcFrame * channels * 4
            if (bytePos + channels * 4 > raf.length()) return null
            raf.seek(bytePos)
            val bitsL = raf.readIntLe()
            buf[0] = Float.fromBits(bitsL)
            buf[1] = if (channels >= 2) Float.fromBits(raf.readIntLe()) else buf[0]
            return buf[0] to buf[1]
        }

        fun close() = raf.close()
    }
}

private fun java.io.RandomAccessFile.readIntLe(): Int {
    val b0 = read(); val b1 = read(); val b2 = read(); val b3 = read()
    if ((b0 or b1 or b2 or b3) < 0) throw java.io.EOFException()
    return (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
}
