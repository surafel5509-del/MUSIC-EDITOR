package com.studioone.core.common.util

import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToLong

/** Shared audio math helpers used by UI meters, automation and the native bridge. */
object AudioMath {
    const val MINUS_INFINITY_DB = -144f

    /** Linear amplitude -> dBFS. Returns [MINUS_INFINITY_DB] for silence. */
    fun linearToDb(linear: Float): Float =
        if (linear <= 0f) MINUS_INFINITY_DB else (20f * log10(linear)).coerceAtLeast(MINUS_INFINITY_DB)

    /** dBFS -> linear amplitude. */
    fun dbToLinear(db: Float): Float =
        if (db <= MINUS_INFINITY_DB) 0f else 10f.pow(db / 20f)

    /** Frames -> milliseconds at the given sample rate. */
    fun framesToMillis(frames: Long, sampleRate: Int): Double =
        frames * 1000.0 / sampleRate

    /** Milliseconds -> frames at the given sample rate. */
    fun millisToFrames(millis: Double, sampleRate: Int): Long =
        (millis * sampleRate / 1000.0).roundToLong()

    /** Seconds -> frames. */
    fun secondsToFrames(seconds: Double, sampleRate: Int): Long =
        (seconds * sampleRate).roundToLong()

    /** Frames -> seconds. */
    fun framesToSeconds(frames: Long, sampleRate: Int): Double =
        frames.toDouble() / sampleRate

    /** Pan law: equal-power stereo balance from pan in [-1, 1]. */
    fun panGains(pan: Float): Pair<Float, Float> {
        val p = pan.coerceIn(-1f, 1f)
        val angle = ((p + 1f) / 2f) * (Math.PI / 2)
        return Pair(kotlin.math.cos(angle).toFloat(), kotlin.math.sin(angle).toFloat())
    }
}

/** Formats a position in frames as `m:ss.SSS` (used by the transport readout). */
fun formatTimecode(frames: Long, sampleRate: Int, includeMillis: Boolean = true): String {
    val totalSeconds = frames.toDouble() / sampleRate
    val minutes = (totalSeconds / 60).toInt()
    val seconds = (totalSeconds % 60).toInt()
    if (!includeMillis) return "%d:%02d".format(minutes, seconds)
    val millis = ((totalSeconds - totalSeconds.toLong()) * 1000).toInt()
    return "%d:%02d.%03d".format(minutes, seconds, millis)
}

/** Formats a tempo for display, e.g. `120.00`. */
fun formatTempo(bpm: Double): String = "%.2f".format(bpm)
