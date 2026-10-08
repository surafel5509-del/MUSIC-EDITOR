package com.studioone.core.domain.theory

import com.studioone.core.domain.model.MidiNote
import com.studioone.core.domain.model.SnapDivision
import kotlin.math.round

/** Quantizes note starts (and optionally ends) to a grid division. */
object Quantizer {

    /** Frames per quarter-note beat at [sampleRate] and [bpm]. */
    fun framesPerBeat(sampleRate: Int, bpm: Double): Long = (sampleRate * 60.0 / bpm).toLong()

    fun quantizeStart(frame: Long, sampleRate: Int, bpm: Double, division: SnapDivision): Long {
        if (division == SnapDivision.OFF) return frame
        val gridFrames = framesPerBeat(sampleRate, bpm) * division.beats
        if (gridFrames <= 0) return frame
        return round(frame.toDouble() / gridFrames).toLong() * gridFrames.toLong()
    }

    /** Quantizes a full note; length is preserved unless [quantizeEnds] is set. */
    fun quantize(note: MidiNote, sampleRate: Int, bpm: Double, division: SnapDivision, quantizeEnds: Boolean = false): MidiNote {
        val newStart = quantizeStart(note.startFrame, sampleRate, bpm, division)
        val newLength = if (quantizeEnds) {
            val newEnd = quantizeStart(note.startFrame + note.lengthFrames, sampleRate, bpm, division)
            (newEnd - newStart).coerceAtLeast(minNoteLength(sampleRate, bpm, division))
        } else {
            note.lengthFrames
        }
        return note.copy(startFrame = newStart, lengthFrames = newLength)
    }

    private fun minNoteLength(sampleRate: Int, bpm: Double, division: SnapDivision): Long =
        (framesPerBeat(sampleRate, bpm) * division.beats).toLong().coerceAtLeast(1)
}
