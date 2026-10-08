package com.studioone.mobile.core.common

/**
 * Frame <-> musical-time conversion helpers. The engine is frame-exact;
 * musical positions derive from the tempo map. PPQ = 480 everywhere.
 */
object TimeMath {
    const val PPQ = 480

    /** Frames for one beat at [sampleRate] and [bpm]. */
    fun framesPerBeat(bpm: Double, sampleRate: Int): Double = sampleRate * 60.0 / bpm

    fun framesPerTick(bpm: Double, sampleRate: Int): Double = framesPerBeat(bpm, sampleRate) / PPQ

    fun beatsToFrames(beats: Double, bpm: Double, sampleRate: Int): Long =
        Math.round(beats * framesPerBeat(bpm, sampleRate))

    fun framesToBeats(frames: Long, bpm: Double, sampleRate: Int): Double =
        frames / framesPerBeat(bpm, sampleRate)

    /** mm:ss.mmm formatting for transport displays. */
    fun formatClock(frames: Long, sampleRate: Int): String {
        val totalMs = frames * 1000 / sampleRate
        val m = totalMs / 60_000
        val s = (totalMs % 60_000) / 1000
        val ms = totalMs % 1000
        return "%d:%02d.%03d".format(m, s, ms)
    }

    /** Bars.beats.ticks display ("3.2.240"). */
    fun formatBarsBeatsTicks(
        frames: Long, bpm: Double, sampleRate: Int, beatsPerBar: Double = 4.0,
    ): String {
        val beats = framesToBeats(frames, bpm, sampleRate)
        val bar = (beats / beatsPerBar).toInt() + 1
        val beat = (beats % beatsPerBar).toInt() + 1
        val tick = Math.round((beats - Math.floor(beats)) * PPQ).toInt()
        return "$bar.$beat.$tick"
    }

    fun dbToLinear(db: Float): Float = if (db <= -144f) 0f else Math.pow(10.0, db / 20.0).toFloat()
    fun linearToDb(linear: Float): Float =
        if (linear <= 1e-9f) -144f else (20.0 * Math.log10(linear.toDouble())).toFloat()
}
