package com.studioone.audio.analysis

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Tempo estimation from PCM via onset autocorrelation.
 * Pure Kotlin (JVM-testable): feeds the loop browser's BPM column.
 *
 * Pipeline: frame-energy-difference onset strength -> autocorrelation over
 * lag range 60..200 BPM -> peak pick with a tempo-range prior. Accurate for
 * beat-driven material; the UI lets users correct 2x/0.5x locks.
 */
object BpmDetector {

    data class Result(val bpm: Double, val confidence: Double)

    /**
     * @param samples mono PCM in [-1, 1]
     * @param sampleRate sample rate of [samples]
     */
    fun detect(samples: FloatArray, sampleRate: Int): Result {
        if (samples.size < sampleRate * 2) return Result(0.0, 0.0) // need >= 2s

        val hopSize = 512
        val frameCount = samples.size / hopSize
        if (frameCount < 16) return Result(0.0, 0.0)

        // Onset strength: half-wave rectified frame energy difference.
        val energy = FloatArray(frameCount)
        for (f in 0 until frameCount) {
            var sum = 0f
            val start = f * hopSize
            val end = min(start + hopSize, samples.size)
            for (i in start until end) sum += samples[i] * samples[i]
            energy[f] = sum / (end - start)
        }
        val onset = FloatArray(frameCount)
        for (f in 1 until frameCount) onset[f] = max(0f, energy[f] - energy[f - 1])

        // Autocorrelation across BPM lag range.
        val hopSeconds = hopSize.toDouble() / sampleRate
        val minLag = (60.0 / 200.0 / hopSeconds).roundToInt() // 200 BPM
        val maxLag = (60.0 / 60.0 / hopSeconds).roundToInt()  // 60 BPM
        if (maxLag <= minLag + 1) return Result(0.0, 0.0)

        var bestLag = minLag
        var bestScore = -1.0
        for (lag in minLag..maxLag) {
            var score = 0.0
            for (f in 0 until frameCount - lag) score += onset[f].toDouble() * onset[f + lag]
            score /= (frameCount - lag)
            // Gentle prior toward 90-150 BPM (most library content).
            val bpm = 60.0 / (lag * hopSeconds)
            val prior = if (bpm in 90.0..150.0) 1.1 else 1.0
            score *= prior
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }

        var bpm = 60.0 / (bestLag * hopSeconds)
        // Fold out-of-range estimates into 60..180.
        while (bpm < 60.0) bpm *= 2.0
        while (bpm > 180.0) bpm /= 2.0

        val baseline = onset.fold(0.0) { acc, v -> acc + v } / frameCount
        val confidence = if (baseline > 0) min(1.0, bestScore / (baseline * 4.0)) else 0.0
        return Result(Math.round(bpm * 10.0) / 10.0, confidence)
    }
}
