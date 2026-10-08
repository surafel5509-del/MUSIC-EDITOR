package com.studioone.core.domain.model

import kotlin.math.floor

/** A tempo change event at a beat position. */
data class TempoMark(val beat: Double, val bpm: Double)

/** A time-signature change event at a bar position. */
data class TimeSigMark(val bar: Int, val timeSignature: TimeSignature)

/**
 * Converts between frames, beats and bars for the timeline ruler, snapping,
 * metronome and MIDI quantization. Supports tempo and meter automation.
 */
class TempoMap(
    val sampleRate: Int,
    private val tempoMarks: List<TempoMark> = listOf(TempoMark(0.0, 120.0)),
    private val timeSigMarks: List<TimeSigMark> = listOf(TimeSigMark(0, TimeSignature(4, 4))),
) {
    private val sortedTempo = tempoMarks.sortedBy { it.beat }
    private val sortedTimeSig = timeSigMarks.sortedBy { it.bar }

    val baseBpm: Double get() = sortedTempo.firstOrNull()?.bpm ?: 120.0

    fun bpmAt(beat: Double): Double =
        sortedTempo.lastOrNull { it.beat <= beat }?.bpm ?: sortedTempo.first().bpm

    fun timeSignatureAt(bar: Int): TimeSignature =
        sortedTimeSig.lastOrNull { it.bar <= bar }?.timeSignature ?: sortedTimeSig.first().timeSignature

    /** Quarter-note length in frames at a given beat position. */
    fun framesPerBeat(beat: Double): Long =
        (sampleRate * 60.0 / bpmAt(beat)).toLong()

    /** Beat position (quarter notes) for a frame position. */
    fun beatAtFrame(frame: Long): Double {
        if (frame <= 0) return 0.0
        var beat = 0.0
        var frameCursor = 0L
        val marks = sortedTempo
        for (i in marks.indices) {
            val bpm = marks[i].bpm
            val framesPerBeat = (sampleRate * 60.0 / bpm)
            val nextMarkBeat = marks.getOrNull(i + 1)?.beat ?: Double.MAX_VALUE
            val beatsInSegment = nextMarkBeat - beat
            val framesInSegment = (beatsInSegment * framesPerBeat).toLong()
            if (frameCursor + framesInSegment >= frame || nextMarkBeat == Double.MAX_VALUE) {
                return beat + (frame - frameCursor) / framesPerBeat
            }
            frameCursor += framesInSegment
            beat = nextMarkBeat
        }
        return beat
    }

    /** Frame position for a beat position. */
    fun frameAtBeat(beat: Double): Long {
        if (beat <= 0.0) return 0L
        var accumulated = 0.0
        var beatCursor = 0.0
        for (i in sortedTempo.indices) {
            val bpm = sortedTempo[i].bpm
            val framesPerBeat = sampleRate * 60.0 / bpm
            val nextMarkBeat = sortedTempo.getOrNull(i + 1)?.beat ?: Double.MAX_VALUE
            val segmentBeats = minOf(beat, nextMarkBeat) - beatCursor
            if (segmentBeats > 0) accumulated += segmentBeats * framesPerBeat
            if (nextMarkBeat >= beat) break
            beatCursor = nextMarkBeat
        }
        return accumulated.toLong()
    }

    /** Bar index (0-based) for a beat position. */
    fun barAtBeat(beat: Double): Int {
        var bar = 0
        var beatCursor = 0.0
        var markIndex = 0
        while (true) {
            val ts = timeSigAtBarUnchecked(bar)
            val nextChangeBar = sortedTimeSig.getOrNull(markIndex + 1)?.bar ?: Int.MAX_VALUE
            val barsInSegment = (nextChangeBar - bar).coerceAtLeast(1)
            val beatsInSegment = barsInSegment * ts.beatsPerBar
            if (beatCursor + beatsInSegment > beat || nextChangeBar == Int.MAX_VALUE) {
                return bar + floor((beat - beatCursor) / ts.beatsPerBar).toInt()
            }
            beatCursor += beatsInSegment
            bar = nextChangeBar
            markIndex++
        }
    }

    private fun timeSigAtBarUnchecked(bar: Int): TimeSignature =
        sortedTimeSig.lastOrNull { it.bar <= bar }?.timeSignature ?: TimeSignature(4, 4)

    /** Frame at the start of a bar. */
    fun frameAtBar(bar: Int): Long {
        var beat = 0.0
        var currentBar = 0
        var markIndex = 0
        while (currentBar < bar) {
            val ts = timeSigAtBarUnchecked(currentBar)
            val nextChangeBar = sortedTimeSig.getOrNull(markIndex + 1)?.bar ?: Int.MAX_VALUE
            val jump = minOf(bar, nextChangeBar) - currentBar
            beat += jump * ts.beatsPerBar
            currentBar += jump
            if (currentBar >= nextChangeBar) markIndex++
        }
        return frameAtBeat(beat)
    }

    /** Snap a frame to the given grid division. */
    fun snapFrame(frame: Long, division: SnapDivision): Long {
        if (division == SnapDivision.OFF || frame <= 0) return frame.coerceAtLeast(0)
        val beat = beatAtFrame(frame)
        val snappedBeats = kotlin.math.round(beat / division.beats) * division.beats
        return frameAtBeat(snappedBeats)
    }
}
