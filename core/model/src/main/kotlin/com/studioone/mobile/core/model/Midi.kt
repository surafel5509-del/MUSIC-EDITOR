package com.studioone.mobile.core.model

import kotlinx.serialization.Serializable

/**
 * A MIDI note in a piano-roll clip. Timing is in PPQ ticks (pulses per
 * quarter note) — the industry standard resolution; 480 PPQ is our default
 * ([com.studioone.mobile.core.model.MidiConstants.PPQ]).
 *
 * MPE (MIDI Polyphonic Expression) dimensions ride on the note: per-note
 * pressure/slide/timbre curves sampled at expression rate.
 */
@Serializable
data class MidiNote(
    val id: Long = 0,               // stable within clip; used by undo & CRDT ops
    val startTick: Long,
    val durationTicks: Long,
    val key: Int,                   // 0..127 (middle C = 60)
    val velocity: Int = 100,        // 1..127
    val channel: Int = 0,           // 0..15; MPE uses 1..15 for voices, 0 for global
    val legato: Boolean = false,    // overlaps previous note on same channel
    val muted: Boolean = false,
    val mpe: MpeExpression? = null,
) {
    val endTick: Long get() = startTick + durationTicks

    /** Note name with octave, e.g. "C4" for middle C (scientific pitch notation). */
    val noteName: String get() = "${PITCH_NAMES[key % 12]}${key / 12 - 1}"

    fun frequencyHz(a4Hz: Double = 440.0): Double =
        a4Hz * Math.pow(2.0, (key - 69) / 12.0)

    companion object {
        val PITCH_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    }
}

/** MPE per-note expression: normalized 0..1 curves sampled over the note's life. */
@Serializable
data class MpeExpression(
    val pressure: FloatArray = FloatArray(0),  // aftertouch
    val slideX: FloatArray = FloatArray(0),    // pitch bend, -1..1
    val timbreY: FloatArray = FloatArray(0),   // CC74 brightness
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MpeExpression) return false
        return pressure.contentEquals(other.pressure) &&
            slideX.contentEquals(other.slideX) &&
            timbreY.contentEquals(other.timbreY)
    }
    override fun hashCode(): Int = 31 * (31 * pressure.contentHashCode() + slideX.contentHashCode()) + timbreY.contentHashCode()
}

object MidiConstants {
    const val PPQ = 480                     // ticks per quarter note
    const val MAX_KEY = 127
    const val A4_KEY = 69
    const val DEFAULT_VELOCITY = 100
}

/** Quantization strength & grid. [division] expressed as fraction of a beat: 1 = 1/1 ... */
@Serializable
data class QuantizeOptions(
    val division: SnapDivision = SnapDivision.SIXTEENTH,
    val strength: Float = 1f,   // 0..1 — partial quantize keeps human feel
    val swing: Float = 0f,      // 0..0.75 shuffle amount applied to off-beats
    val quantizeNoteEnd: Boolean = false,
    val grooveTemplateId: String? = null, // extract timing from a reference clip
)

@Serializable
enum class SnapDivision(val ticksPer: Long, val label: String) {
    WHOLE(MidiConstants.PPQ * 4, "1/1"),
    HALF(MidiConstants.PPQ * 2, "1/2"),
    QUARTER(MidiConstants.PPQ, "1/4"),
    EIGHTH(MidiConstants.PPQ / 2, "1/8"),
    EIGHTH_TRIPLET(MidiConstants.PPQ / 3, "1/8T"),
    SIXTEENTH(MidiConstants.PPQ / 4, "1/16"),
    SIXTEENTH_TRIPLET(MidiConstants.PPQ / 6, "1/16T"),
    THIRTY_SECOND(MidiConstants.PPQ / 8, "1/32"),
    SIXTY_FOURTH(MidiConstants.PPQ / 16, "1/64"),
    BAR(MidiConstants.PPQ * 16, "Bar"),   // assumes 4/4 default; recomputed per project
    NONE(0, "Off");
}

/** Chord voicing helpers used by the chord editor & arpeggiator. */
@Serializable
enum class ChordType(val displayName: String, val semitones: IntArray) {
    MAJOR("maj", intArrayOf(0, 4, 7)),
    MINOR("min", intArrayOf(0, 3, 7)),
    DIM("dim", intArrayOf(0, 3, 6)),
    AUG("aug", intArrayOf(0, 4, 8)),
    MAJOR7("maj7", intArrayOf(0, 4, 7, 11)),
    MINOR7("min7", intArrayOf(0, 3, 7, 10)),
    DOM7("7", intArrayOf(0, 4, 7, 10)),
    MINOR_MAJOR7("minMaj7", intArrayOf(0, 3, 7, 11)),
    HALF_DIM7("m7b5", intArrayOf(0, 3, 6, 10)),
    DIM7("dim7", intArrayOf(0, 3, 6, 9)),
    SUS2("sus2", intArrayOf(0, 2, 7)),
    SUS4("sus4", intArrayOf(0, 5, 7)),
    ADD9("add9", intArrayOf(0, 4, 7, 14)),
    MAJOR9("maj9", intArrayOf(0, 4, 7, 11, 14)),
    MINOR9("min9", intArrayOf(0, 3, 7, 10, 14)),
    DOM9("9", intArrayOf(0, 4, 7, 10, 14)),
    POWER("5", intArrayOf(0, 7));

    /** Absolute MIDI keys for this chord rooted at [rootKey], in root position. */
    fun keys(rootKey: Int): List<Int> = semitones.map { rootKey + it }
}

@Serializable
data class Chord(val rootPc: Int, val type: ChordType, val inversion: Int = 0) {
    /** Voiced keys with [inversion] applied (each inversion lifts the bottom note an octave). */
    fun voicedKeys(rootOctaveKey: Int = 60): List<Int> {
        val base = type.keys(rootOctaveKey + rootPc).toMutableList()
        repeat(inversion.coerceIn(0, base.size - 1)) {
            base.add(base.removeAt(0) + 12)
        }
        return base.sorted()
    }
}

/** Step sequencer pattern for the drum machine. One pattern = 16th-note grid rows. */
@Serializable
data class StepPattern(
    val steps: Int = 16,
    val rows: List<StepRow>,
)

@Serializable
data class StepRow(
    val padIndex: Int,
    /** velocity 0..127 per step; 0 = off. Length == [StepPattern.steps]. */
    val velocities: IntArray,
    val muted: Boolean = false,
    val probability: Float = 1f,   // 0..1 humanization: chance a triggered step actually fires
    val swingFollowsGlobal: Boolean = true,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StepRow) return false
        return padIndex == other.padIndex && velocities.contentEquals(other.velocities) &&
            muted == other.muted && probability == other.probability
    }
    override fun hashCode(): Int = 31 * padIndex + velocities.contentHashCode()
}

/** Arpeggiator configuration bound to an instrument track. */
@Serializable
data class ArpeggiatorConfig(
    val enabled: Boolean = false,
    val mode: ArpMode = ArpMode.UP,
    val division: SnapDivision = SnapDivision.EIGHTH,
    val gate: Float = 0.8f,        // note length as fraction of step
    val octaveRange: Int = 1,      // 1..4
    val velocityMode: ArpVelocityMode = ArpVelocityMode.AS_PLAYED,
)

@Serializable
enum class ArpMode { UP, DOWN, UP_DOWN, RANDOM, ORDERED, CHORD }
@Serializable
enum class ArpVelocityMode { AS_PLAYED, FIXED, ACCENT_PATTERN }
