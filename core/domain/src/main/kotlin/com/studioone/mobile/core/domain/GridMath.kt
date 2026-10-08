package com.studioone.mobile.core.domain

import com.studioone.mobile.core.model.MidiConstants
import com.studioone.mobile.core.model.SnapDivision
import com.studioone.mobile.core.model.TempoMap
import com.studioone.mobile.core.model.TimeSignature
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * Musical grid math over a tempo map: ticks <-> frames <-> bars.
 *
 * Everything timeline-related (snap, quantize display, automation baking,
 * MIDI clip length) funnels through here so the whole app agrees on time.
 * Pure + deterministic => heavily unit tested (GridMathTest).
 */
class GridMath(
    private val tempoMap: TempoMap,
    private val timeSignature: TimeSignature,
    private val sampleRate: Int,
) {
    /** Absolute frame position of a tick position (integrates the tempo map). */
    fun ticksToFrames(tick: Long): Long {
        var remainingTicks = tick.toDouble()
        var frames = 0.0
        var bar = 0.0
        val ticksPerBar = MidiConstants.PPQ * timeSignature.barLengthBeats

        // Walk tempo markers; between markers tempo is constant or ramped.
        val markers = tempoMap.markers.sortedBy { it.bar }
        var markerIndex = 0
        while (remainingTicks > 0) {
            val marker = markers[markerIndex]
            val nextMarker = markers.getOrNull(markerIndex + 1)
            val segmentEndBar = nextMarker?.bar ?: Double.MAX_VALUE
            val ticksLeftInSegment = (segmentEndBar - bar) * ticksPerBar

            if (remainingTicks <= ticksLeftInSegment || nextMarker == null) {
                val bpm = tempoMap.bpmAt(bar + remainingTicks / ticksPerBar)
                frames += remainingTicks / MidiConstants.PPQ * (60.0 * sampleRate / bpm)
                remainingTicks = 0.0
            } else {
                // Integrate across the segment (ramped tempo: average of ends).
                val bpmStart = tempoMap.bpmAt(bar)
                val bpmEnd = tempoMap.bpmAt(segmentEndBar - 1e-9)
                val avgBpm = (bpmStart + bpmEnd) / 2.0
                frames += ticksLeftInSegment / MidiConstants.PPQ * (60.0 * sampleRate / avgBpm)
                remainingTicks -= ticksLeftInSegment
                bar = segmentEndBar
            }
            markerIndex++
        }
        return frames.roundToLong()
    }

    /** Inverse of [ticksToFrames] (bisection — exact enough for UI & editing). */
    fun framesToTicks(frames: Long): Long {
        if (frames <= 0) return 0
        var lo = 0L
        var hi = frames * 2 // generous upper bound at slowest tempo
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (ticksToFrames(mid) < frames) lo = mid + 1 else hi = mid
        }
        return lo
    }

    fun barsToFrames(bars: Double): Long = ticksToFrames((bars * MidiConstants.PPQ * timeSignature.barLengthBeats).roundToLong())
    fun framesToBars(frames: Long): Double = framesToTicks(frames).toDouble() / (MidiConstants.PPQ * timeSignature.barLengthBeats)

    /** Snap a frame position to the current grid division. */
    fun snapFrames(frames: Long, division: SnapDivision, strength: Float = 1f): Long {
        val gridTicks = when (division) {
            SnapDivision.NONE -> return frames
            SnapDivision.BAR -> (MidiConstants.PPQ * timeSignature.barLengthBeats).toLong()
            else -> division.ticksPer
        }
        if (gridTicks <= 0) return frames
        val ticks = framesToTicks(frames)
        val nearest = ((ticks + gridTicks / 2) / gridTicks) * gridTicks
        val snapped = (ticks + (nearest - ticks) * strength).roundToLong()
        return ticksToFrames(snapped)
    }

    /** Frame length of one bar at the grid's start position. */
    fun barFrames(atFrames: Long = 0): Long {
        val startTicks = framesToTicks(atFrames)
        val ticksPerBar = (MidiConstants.PPQ * timeSignature.barLengthBeats).toLong()
        return ticksToFrames(startTicks + ticksPerBar) - ticksToFrames(startTicks)
    }

    /** Beat grid positions (frames) spanning [fromFrame, toFrame] — timeline ruler. */
    fun beatFrames(fromFrame: Long, toFrame: Long): List<Long> {
        val result = mutableListOf<Long>()
        var tick = floor(framesToTicks(fromFrame).toDouble() / MidiConstants.PPQ).toLong() * MidiConstants.PPQ
        while (true) {
            val frame = ticksToFrames(tick)
            if (frame > toFrame) break
            if (frame >= fromFrame) result += frame
            tick += MidiConstants.PPQ
            if (result.size > 4096) break // ruler guard
        }
        return result
    }

    fun barIndexAt(frames: Long): Int = floor(framesToBars(frames)).toInt()
}
