package com.studioone.mobile.core.model

/**
 * Ruler label provider (bars/beats) computed by the arranger from GridMath.
 * Lives in core:model so both core:ui and features can construct it without
 * depending on each other.
 */
data class GridLabels(
    /** Candidate major-division sizes in frames, coarsest first. */
    val divisionsFrames: List<Long>,
    val barLengthFrames: Long,
    val labelEveryNBars: Int = 1,
    val sampleRate: Int = 48_000,
) {
    fun isBarStart(frame: Long): Boolean = barLengthFrames > 0 && frame % barLengthFrames == 0L

    fun labelFor(frame: Long): String {
        if (barLengthFrames <= 0) return ""
        val bar = frame / barLengthFrames + 1
        return if ((bar - 1) % labelEveryNBars == 0L) bar.toString() else ""
    }
}
