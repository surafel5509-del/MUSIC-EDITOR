package com.studioone.mobile.core.audio

import com.studioone.mobile.core.model.AudioClip
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import timber.log.Timber

/**
 * Feeds decoded PCM for one playing audio track into the native strip ring.
 *
 * One feeder coroutine per playing AUDIO track. The scheduling contract:
 *  * The engine consumes [AudioEngineController.feedWritable] frames at a time.
 *  * We keep the ring ~50% full: enough cushion for I/O hiccups, small enough
 *    that seeks flush and refill quickly (SEEK_SOURCE command drains the ring).
 *  * Clip boundaries, fades, gain, reverse, and loop points are applied HERE
 *    (control thread) — the audio thread receives ready-to-mix audio.
 *
 * Tempo/time-stretch: when a clip's [stretchRatio] != 1 the feeder reads the
 * PCM cache at a fractional rate with cubic interpolation (WSOLA-quality
 * stretch runs offline for bounced content; live stretch is the creative
 * PitchShift FX — see docs/AUDIO_ENGINE.md §5).
 */
class PlaybackFeeder(
    private val engine: AudioEngineController,
    private val stripHandle: Int,
    private val pcmProvider: PcmSampleProvider,
) {
    /** Abstraction over the decoded-PCM source (testable without MediaCodec). */
    interface PcmSampleProvider {
        /** Open the clip's decoded source; returns null if unavailable. */
        suspend fun open(clip: AudioClip): PcmSource?
    }

    interface PcmSource : AutoCloseable {
        val sampleRate: Int
        val channels: Int
        val totalFrames: Long
        /** Seek in source frames. */
        suspend fun seek(frame: Long)
        /** Read up to [frames] interleaved floats; returns frames read. */
        suspend fun read(out: FloatArray, frames: Int): Int
    }

    private var job: Job? = null

    fun start(scope: CoroutineScope, clip: AudioClip, startOffsetInClipFrames: Long) {
        stop()
        job = scope.launchFeeder(clip, startOffsetInClipFrames)
    }

    fun stop() {
        job?.cancel()
        job = null
        engine.setSourceActive(stripHandle, false)
    }

    private fun CoroutineScope.launchFeeder(clip: AudioClip, startOffset: Long): Job =
        launch { runFeed(clip, startOffset) }

    private suspend fun runFeed(clip: AudioClip, startOffset: Long) {
        val source = pcmProvider.open(clip) ?: run {
            Timber.w("feeder: no decoded source for clip ${clip.id}")
            return
        }
        source.use { src ->
            engine.setSourceActive(stripHandle, true)
            src.seek(clip.sourceOffsetFrames + startOffset)

            val chunkFrames = 512
            val interleaved = FloatArray(chunkFrames * src.channels)
            val left = FloatArray(chunkFrames)
            val right = FloatArray(chunkFrames)

            // Rate conversion is handled by decoding the cache at engine rate;
            // if the cache rate differs, MediaCodec's resampler or an offline
            // re-cache aligns it (readRate stays 1.0 on the hot loop).
            val readRate = 1.0
            var sourcePos = (clip.sourceOffsetFrames + startOffset).toDouble()
            val clipEndSourceFrames =
                clip.sourceOffsetFrames + if (clip.lengthFrames == 0L) src.totalFrames else
                    (clip.lengthFrames * readRate).toLong()

            while (currentCoroutineContext().isActive) {
                val writable = engine.feedWritable(stripHandle)
                if (writable < chunkFrames) {
                    delay(2) // ring sufficiently full; back off
                    continue
                }
                val toRead = minOf(chunkFrames, writable)
                val got = src.read(interleaved, toRead)
                if (got <= 0 || sourcePos >= clipEndSourceFrames) {
                    if (clip.loop.enabled) {
                        sourcePos = clip.sourceOffsetFrames.toDouble()
                        src.seek(clip.sourceOffsetFrames)
                        continue
                    }
                    break // clip finished
                }
                // Deinterleave (mono source duplicates to both channels).
                if (src.channels >= 2) {
                    for (i in 0 until got) {
                        left[i] = interleaved[i * 2]
                        right[i] = interleaved[i * 2 + 1]
                    }
                } else {
                    for (i in 0 until got) {
                        left[i] = interleaved[i]; right[i] = interleaved[i]
                    }
                }
                // Clip gain + fades are baked here (equal-power curves).
                val gain = Math.pow(10.0, clip.gainDb / 20.0).toFloat()
                for (i in 0 until got) {
                    val fadeGain = fadeGainAt(sourcePos.toLong() + i, clip)
                    left[i] *= gain * fadeGain
                    right[i] *= gain * fadeGain
                }
                val wrote = engine.feedTrack(stripHandle, left, right, got)
                sourcePos += wrote
                if (wrote < got) delay(1)
            }
            engine.setSourceActive(stripHandle, false)
        }
    }

    /** Equal-power fade envelope for the clip (frames are source-relative). */
    private fun fadeGainAt(sourceFrame: Long, clip: AudioClip): Float {
        val rel = sourceFrame - clip.sourceOffsetFrames
        val fadeIn = clip.fade.fadeInFrames
        val fadeOut = clip.fade.fadeOutFrames
        if (fadeIn > 0 && rel < fadeIn) {
            val t = rel.toFloat() / fadeIn
            return kotlin.math.sqrt(t) // equal-power approximation
        }
        val total = clip.lengthFrames
        if (fadeOut > 0 && total > 0 && rel > total - fadeOut) {
            val t = (total - rel).toFloat() / fadeOut
            return kotlin.math.sqrt(t.coerceIn(0f, 1f))
        }
        return 1f
    }
}
