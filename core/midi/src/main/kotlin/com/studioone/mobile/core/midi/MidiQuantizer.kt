package com.studioone.mobile.core.midi

import com.studioone.mobile.core.model.Chord
import com.studioone.mobile.core.model.ChordType
import com.studioone.mobile.core.model.MidiConstants
import com.studioone.mobile.core.model.MidiNote
import com.studioone.mobile.core.model.MusicalKey
import com.studioone.mobile.core.model.QuantizeOptions
import com.studioone.mobile.core.model.SnapDivision
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * MIDI note editing & quantization — pure functions over immutable note
 * lists. Everything here is deterministic and side-effect free so the same
 * code serves piano-roll edits, record quantize, undo snapshots, and tests.
 */
object MidiQuantizer {

    /** Quantize one note's start (and optionally its end) to the grid. */
    fun quantizeNote(note: MidiNote, options: QuantizeOptions): MidiNote {
        val grid = divisionTicks(options.division)
        if (grid <= 0) return note
        val swung = applySwing(note.startTick, grid, options.swing)
        val target = nearestGrid(swung, grid)
        val delta = ((target - note.startTick) * options.strength).roundToLong()
        val newStart = note.startTick + delta
        val newDuration = if (options.quantizeNoteEnd) {
            val swungEnd = applySwing(note.endTick, grid, options.swing)
            val targetEnd = nearestGrid(swungEnd, grid)
            val endDelta = ((targetEnd - note.endTick) * options.strength).roundToLong()
            (note.durationTicks + endDelta).coerceAtLeast(grid / 4)
        } else {
            note.durationTicks
        }
        return note.copy(startTick = newStart.coerceAtLeast(0), durationTicks = newDuration)
    }

    fun quantizeAll(notes: List<MidiNote>, options: QuantizeOptions): List<MidiNote> =
        notes.map { quantizeNote(it, options) }

    /** Swing: delay every second grid step by [swing] * grid (0..0.75 shuffle). */
    fun applySwing(tick: Long, grid: Long, swing: Float): Long {
        if (swing <= 0f || grid <= 0) return tick
        val step = tick / grid
        val offset = tick % grid
        // Only swing the off-beats (odd steps), and only near the grid start.
        return if (step % 2 == 1L && offset < grid / 2) {
            tick + (grid * swing.coerceIn(0f, 0.75f)).roundToLong()
        } else tick
    }

    private fun nearestGrid(tick: Long, grid: Long): Long =
        ((tick + grid / 2) / grid) * grid

    fun divisionTicks(division: SnapDivision): Long = when (division) {
        SnapDivision.NONE -> 0
        SnapDivision.BAR -> MidiConstants.PPQ * 4 // 4/4 assumption; callers pass project-aware grid
        else -> division.ticksPer
    }

    // ── Note operations (piano roll tools) ──────────────────────────────────

    fun transpose(notes: List<MidiNote>, semitones: Int): List<MidiNote> =
        notes.map { it.copy(key = (it.key + semitones).coerceIn(0, MidiConstants.MAX_KEY)) }

    fun move(notes: List<MidiNote>, deltaTicks: Long, deltaKeys: Int): List<MidiNote> =
        notes.map {
            it.copy(
                startTick = (it.startTick + deltaTicks).coerceAtLeast(0),
                key = (it.key + deltaKeys).coerceIn(0, MidiConstants.MAX_KEY),
            )
        }

    fun resize(notes: List<MidiNote>, newDurationTicks: Long): List<MidiNote> =
        notes.map { it.copy(durationTicks = newDurationTicks.coerceAtLeast(1)) }

    /** Legato: each note extends to the start of the next (last keeps its length). */
    fun legato(notes: List<MidiNote>): List<MidiNote> {
        if (notes.size < 2) return notes
        val sorted = notes.sortedBy { it.startTick }
        return sorted.mapIndexed { i, n ->
            if (i < sorted.size - 1) n.copy(durationTicks = sorted[i + 1].startTick - n.startTick)
            else n.copy(legato = i > 0 && sorted[i - 1].endTick >= n.startTick)
        }
    }

    /** Strum: stagger a chord's notes by [msPerNote] converted to ticks at [bpm]. */
    fun strum(notes: List<MidiNote>, bpm: Double, offsetMs: Double): List<MidiNote> {
        val ticksPerMs = MidiConstants.PPQ * bpm / 60_000.0
        val sorted = notes.sortedBy { it.key }
        return sorted.mapIndexed { i, n ->
            n.copy(startTick = n.startTick + (i * offsetMs * ticksPerMs).roundToLong())
        }
    }

    /** Humanize: random timing/velocity offsets bounded by ±amount. */
    fun humanize(notes: List<MidiNote>, timingTicks: Long, velocityAmount: Int, seed: Long = 42): List<MidiNote> {
        val rnd = java.util.Random(seed)
        return notes.map {
            val dt = if (timingTicks > 0) rnd.nextLong() % (timingTicks * 2) - timingTicks else 0
            val dv = if (velocityAmount > 0) rnd.nextInt(velocityAmount * 2) - velocityAmount else 0
            it.copy(
                startTick = (it.startTick + dt).coerceAtLeast(0),
                velocity = (it.velocity + dv).coerceIn(1, 127),
            )
        }
    }

    /** Split a note at [tick] (scissor tool). Returns the two halves. */
    fun split(note: MidiNote, atTick: Long): Pair<MidiNote, MidiNote>? {
        if (atTick <= note.startTick || atTick >= note.endTick) return null
        return note.copy(durationTicks = atTick - note.startTick) to
            note.copy(id = note.id + 1, startTick = atTick, durationTicks = note.endTick - atTick)
    }

    /** Detect chords: notes starting within [windowTicks] of each other (>= 3 notes). */
    fun detectChords(notes: List<MidiNote>, windowTicks: Long = 40): List<Chord> {
        val sorted = notes.sortedBy { it.startTick }
        val chords = mutableListOf<Chord>()
        var i = 0
        while (i < sorted.size) {
            var j = i
            val group = mutableListOf<MidiNote>()
            while (j < sorted.size && sorted[j].startTick - sorted[i].startTick <= windowTicks) {
                group += sorted[j]; j++
            }
            if (group.size >= 3) {
                classifyChord(group.map { it.key % 12 }.toSet())?.let { chords += it }
            }
            i = if (j > i) j else i + 1
        }
        return chords
    }

    /** Classify a pitch-class set as the best-matching chord (root position). */
    fun classifyChord(pitchClasses: Set<Int>): Chord? {
        if (pitchClasses.size < 3) return null
        for (root in pitchClasses) {
            for (type in ChordType.entries) {
                val chordPcs = type.semitones.map { (root + it) % 12 }.toSet()
                if (chordPcs == pitchClasses) return Chord(root, type)
                // Allow one extra color tone (9ths/11ths beyond the base triad).
                if (pitchClasses.size == chordPcs.size + 1 && pitchClasses.containsAll(chordPcs)) {
                    return Chord(root, type)
                }
            }
        }
        return null
    }

    /** Snap note keys into [key]'s scale (used by scale-locked piano roll). */
    fun snapToScale(notes: List<MidiNote>, key: MusicalKey): List<MidiNote> =
        notes.map { n ->
            if (key.scale.contains(n.key % 12, key.tonicPc)) n
            else {
                // Move to the nearest in-scale pitch (prefer downward, ties -> down).
                var best = n.key
                var bestDist = Int.MAX_VALUE
                for (delta in -2..2) {
                    val candidate = n.key + delta
                    if (candidate in 0..127 && key.scale.contains(candidate % 12, key.tonicPc)) {
                        if (abs(delta) < bestDist) { bestDist = abs(delta); best = candidate }
                    }
                }
                n.copy(key = best)
            }
        }

    /** Velocity ramp across a selection (crescendo tool). */
    fun velocityRamp(notes: List<MidiNote>, startVelocity: Int, endVelocity: Int): List<MidiNote> {
        if (notes.size < 2) return notes
        val sorted = notes.sortedBy { it.startTick }
        return sorted.mapIndexed { i, n ->
            val t = i.toFloat() / (sorted.size - 1)
            val v = (startVelocity + (endVelocity - startVelocity) * t).toInt()
            n.copy(velocity = v.coerceIn(1, 127))
        }
    }
}
