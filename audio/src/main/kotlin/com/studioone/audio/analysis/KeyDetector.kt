package com.studioone.audio.analysis

import kotlin.math.PI
import kotlin.math.cos

/**
 * Key estimation via chromagram + Krumhansl-Schmuckler profiles.
 *
 * A Goertzel-based chroma keeps this dependency-free and JVM-testable; the
 * loop browser shows the result and users can override it.
 */
object KeyDetector {

    data class Result(val pitchClass: Int, val minor: Boolean, val confidence: Double) {
        val display: String
            get() {
                val names = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
                return names[pitchClass] + if (minor) "m" else ""
            }
    }

    // Krumhansl-Kessler major/minor tone profiles.
    private val MAJOR_PROFILE = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
    private val MINOR_PROFILE = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)

    fun detect(samples: FloatArray, sampleRate: Int): Result {
        if (samples.size < sampleRate) return Result(0, false, 0.0)

        // Chroma: Goertzel energy per pitch class, octaves 2..6 summed.
        val chroma = DoubleArray(12)
        val blockSize = minOf(samples.size, sampleRate * 15)
        for (pc in 0 until 12) {
            var energy = 0.0
            for (octave in 2..6) {
                val midi = octave * 12 + pc
                val freq = 440.0 * Math.pow(2.0, (midi - 69) / 12.0)
                energy += goertzelMagnitude(samples, 0, blockSize, freq, sampleRate)
            }
            chroma[pc] = energy
        }
        val maxChroma = chroma.max()
        if (maxChroma <= 0.0) return Result(0, false, 0.0)
        for (i in chroma.indices) chroma[i] /= maxChroma

        var bestPc = 0
        var bestMinor = false
        var bestCorr = Double.NEGATIVE_INFINITY
        for (pc in 0 until 12) {
            val majorCorr = correlation(rotated(chroma, pc), MAJOR_PROFILE)
            val minorCorr = correlation(rotated(chroma, pc), MINOR_PROFILE)
            if (majorCorr > bestCorr) { bestCorr = majorCorr; bestPc = pc; bestMinor = false }
            if (minorCorr > bestCorr) { bestCorr = minorCorr; bestPc = pc; bestMinor = true }
        }
        return Result(bestPc, bestMinor, ((bestCorr + 1.0) / 2.0).coerceIn(0.0, 1.0))
    }

    private fun rotated(chroma: DoubleArray, shift: Int): DoubleArray =
        DoubleArray(12) { chroma[(it + shift) % 12] }

    private fun correlation(a: DoubleArray, b: DoubleArray): Double {
        val meanA = a.average()
        val meanB = b.average()
        var num = 0.0
        var denA = 0.0
        var denB = 0.0
        for (i in a.indices) {
            val da = a[i] - meanA
            val db = b[i] - meanB
            num += da * db
            denA += da * da
            denB += db * db
        }
        val den = kotlin.math.sqrt(denA * denB)
        return if (den > 0) num / den else 0.0
    }

    /** Goertzel algorithm: energy of one frequency bin without a full FFT. */
    private fun goertzelMagnitude(
        samples: FloatArray,
        offset: Int,
        length: Int,
        freq: Double,
        sampleRate: Int,
    ): Double {
        val k = (length * freq / sampleRate).toLong()
        val omega = 2.0 * PI * k / length
        val coeff = 2.0 * cos(omega)
        var s0 = 0.0
        var s1 = 0.0
        var s2 = 0.0
        val end = minOf(offset + length, samples.size)
        for (i in offset until end) {
            s0 = samples[i] + coeff * s1 - s2
            s2 = s1
            s1 = s0
        }
        return s1 * s1 + s2 * s2 - coeff * s1 * s2
    }
}
