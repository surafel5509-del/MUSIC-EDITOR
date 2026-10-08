package com.studioone.mobile.core.data.analysis

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Lightweight local analysis (no native deps):
 *
 * BPM: energy-flux onset envelope -> autocorrelation in the 60..200 BPM lag
 * range -> peak pick with preference window (octave-error correction).
 *
 * Key: chroma via Goertzel bins over octaves 2..5, correlated against
 * Krumhansl-Schmuckler major/minor profiles for all 12 tonics.
 *
 * Accuracy target: ±1 BPM for steady loops, >80% key hit-rate on tonal
 * material — good enough for library tagging; cloud ingest refines.
 */
class TempoKeyAnalyzer {

    fun estimateBpm(pcmFile: File, sampleRate: Int, channels: Int): Double? {
        val flux = onsetEnvelope(pcmFile, sampleRate, channels, hop = 512, maxFrames = sampleRate * 30 / 512)
        if (flux.size < 256) return null
        val minLag = (60.0 * sampleRate / 200.0 / 512).toInt().coerceAtLeast(2)
        val maxLag = (60.0 * sampleRate / 60.0 / 512).toInt().coerceAtMost(flux.size / 2)
        var bestLag = minLag
        var bestScore = Double.NEGATIVE_INFINITY
        for (lag in minLag..maxLag) {
            var sum = 0.0
            for (i in 0 until flux.size - lag) sum += flux[i] * flux[i + lag]
            sum /= (flux.size - lag)
            val bpm = 60.0 * sampleRate / 512.0 / lag
            val pref = if (bpm in 85.0..160.0) 1.15 else 1.0
            if (sum * pref > bestScore) { bestScore = sum * pref; bestLag = lag }
        }
        val bpm = 60.0 * sampleRate / 512.0 / bestLag
        return if (bpm in 40.0..240.0) (bpm * 10).roundToInt() / 10.0 else null
    }

    private fun onsetEnvelope(pcmFile: File, sampleRate: Int, channels: Int, hop: Int, maxFrames: Int): DoubleArray {
        val out = mutableListOf<Double>()
        RandomAccessFile(pcmFile, "r").use { raf ->
            val buf = ByteArray(hop * channels * 4)
            var prevEnergy = 0.0
            var frames = 0
            while (frames < maxFrames) {
                val read = raf.read(buf)
                if (read < buf.size) break
                var energy = 0.0
                for (i in 0 until hop) {
                    val s = Float.fromBits(intFromLe(buf, i * channels * 4))
                    energy += s * s
                }
                energy /= hop
                out.add(max(0.0, energy - prevEnergy))
                prevEnergy = energy
                frames++
            }
        }
        return out.toDoubleArray()
    }

    fun estimateKey(pcmFile: File, sampleRate: Int, channels: Int): String? {
        val chroma = DoubleArray(12)
        RandomAccessFile(pcmFile, "r").use { raf ->
            val frameSize = 4096
            val buf = ByteArray(frameSize * channels * 4)
            var framesRead = 0
            while (framesRead < 64) {
                val read = raf.read(buf)
                if (read < buf.size) break
                val samples = DoubleArray(frameSize) {
                    Float.fromBits(intFromLe(buf, it * channels * 4)).toDouble()
                }
                accumulateChroma(samples, sampleRate, chroma)
                framesRead++
            }
        }
        if (chroma.all { it == 0.0 }) return null
        return matchKey(chroma)
    }

    private fun accumulateChroma(frame: DoubleArray, sampleRate: Int, chroma: DoubleArray) {
        for (pc in 0 until 12) {
            for (octave in 2..5) {
                val midi = pc + (octave + 1) * 12
                val freq = 440.0 * Math.pow(2.0, (midi - 69) / 12.0)
                chroma[pc] += goertzel(frame, freq, sampleRate)
            }
        }
    }

    private fun goertzel(x: DoubleArray, freq: Double, sampleRate: Int): Double {
        val k = (0.5 + x.size * freq / sampleRate).toInt()
        val w = 2.0 * PI * k / x.size
        val coeff = 2.0 * cos(w)
        var s1 = 0.0; var s2 = 0.0
        for (sample in x) {
            val s0 = sample + coeff * s1 - s2
            s2 = s1; s1 = s0
        }
        return (s1 * s1 + s2 * s2 - coeff * s1 * s2) / (x.size * x.size)
    }

    private fun matchKey(chroma: DoubleArray): String {
        val names = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        val major = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
        val minor = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)
        var best = ""
        var bestCorr = Double.NEGATIVE_INFINITY
        for (tonic in 0 until 12) {
            for (mode in 0..1) {
                val profile = if (mode == 0) major else minor
                val corr = correlation(chroma, profile, tonic)
                if (corr > bestCorr) { bestCorr = corr; best = names[tonic] + if (mode == 0) " Major" else " Minor" }
            }
        }
        return best
    }

    private fun correlation(chroma: DoubleArray, profile: DoubleArray, rotation: Int): Double {
        val n = 12
        val y = DoubleArray(n) { profile[(it + rotation) % n] }
        val mx = chroma.average(); val my = y.average()
        var num = 0.0; var dx = 0.0; var dy = 0.0
        for (i in 0 until n) {
            num += (chroma[i] - mx) * (y[i] - my)
            dx += (chroma[i] - mx) * (chroma[i] - mx)
            dy += (y[i] - my) * (y[i] - my)
        }
        return if (dx == 0.0 || dy == 0.0) 0.0 else num / sqrt(dx * dy)
    }

    private fun intFromLe(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xFF) or
            ((buf[offset + 1].toInt() and 0xFF) shl 8) or
            ((buf[offset + 2].toInt() and 0xFF) shl 16) or
            ((buf[offset + 3].toInt() and 0xFF) shl 24)
}
