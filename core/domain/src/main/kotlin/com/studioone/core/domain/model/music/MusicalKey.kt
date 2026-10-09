package com.studioone.core.domain.model.music

import kotlinx.serialization.Serializable

/** Pitch classes used across key/scale logic. */
enum class PitchClass(val semitone: Int, val display: String) {
    C(0, "C"), C_SHARP(1, "C#"), D(2, "D"), D_SHARP(3, "D#"),
    E(4, "E"), F(5, "F"), F_SHARP(6, "F#"), G(7, "G"),
    G_SHARP(8, "G#"), A(9, "A"), A_SHARP(10, "A#"), B(11, "B");

    companion object {
        fun of(semitone: Int): PitchClass = entries[(semitone % 12 + 12) % 12]
    }
}

enum class ScaleType(val display: String, val intervals: IntArray) {
    MAJOR("Major", intArrayOf(0, 2, 4, 5, 7, 9, 11)),
    MINOR_NATURAL("Minor", intArrayOf(0, 2, 3, 5, 7, 8, 10)),
    MINOR_PENTATONIC("Minor Pentatonic", intArrayOf(0, 3, 5, 7, 10)),
    MAJOR_PENTATONIC("Major Pentatonic", intArrayOf(0, 2, 4, 7, 9)),
    BLUES("Blues", intArrayOf(0, 3, 5, 6, 7, 10)),
    DORIAN("Dorian", intArrayOf(0, 2, 3, 5, 7, 9, 10)),
    MIXOLYDIAN("Mixolydian", intArrayOf(0, 2, 4, 5, 7, 9, 10)),
    HARMONIC_MINOR("Harmonic Minor", intArrayOf(0, 2, 3, 5, 7, 8, 11)),
    CHROMATIC("Chromatic", intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11));

    /** Set of pitch classes belonging to this scale rooted at 0. */
    fun pitchClasses(): Set<Int> = intervals.map { it % 12 }.toSet()
}

@Serializable
data class MusicalKey(
    val root: Int = 0, // pitch class 0..11
    val scale: String = ScaleType.MAJOR.name,
) {
    val scaleType: ScaleType get() = ScaleType.valueOf(scale)
    val display: String get() = "${PitchClass.of(root).display} ${scaleType.display}"
}

/** Diatonic chord built on a scale degree. */
data class Chord(
    val rootPitchClass: Int,
    val intervals: IntArray,
    val symbol: String,
) {
    /** Returns MIDI pitches for the chord around [octaveRoot] (e.g. 48 = C3). */
    fun pitches(octaveRoot: Int): List<Int> = intervals.map { octaveRoot + it }

    override fun equals(other: Any?) =
        other is Chord && rootPitchClass == other.rootPitchClass && intervals.contentEquals(other.intervals)

    override fun hashCode(): Int = 31 * rootPitchClass + intervals.contentHashCode()
}

/** Chord library: triads, sevenths, sus, sixths used by the chord stamp tool. */
object Chords {
    private val MAJOR = intArrayOf(0, 4, 7)
    private val MINOR = intArrayOf(0, 3, 7)
    private val DIM = intArrayOf(0, 3, 6)
    private val AUG = intArrayOf(0, 4, 8)
    private val MAJ7 = intArrayOf(0, 4, 7, 11)
    private val MIN7 = intArrayOf(0, 3, 7, 10)
    private val DOM7 = intArrayOf(0, 4, 7, 10)
    private val SUS2 = intArrayOf(0, 2, 7)
    private val SUS4 = intArrayOf(0, 5, 7)
    private val SIX = intArrayOf(0, 4, 7, 9)

    fun triad(root: Int, minor: Boolean): Chord =
        Chord(root, if (minor) MINOR else MAJOR, "${PitchClass.of(root).display}${if (minor) "m" else ""}")

    fun major(root: Int) = Chord(root, MAJOR, PitchClass.of(root).display)
    fun minor(root: Int) = Chord(root, MINOR, "${PitchClass.of(root).display}m")
    fun diminished(root: Int) = Chord(root, DIM, "${PitchClass.of(root).display}dim")
    fun augmented(root: Int) = Chord(root, AUG, "${PitchClass.of(root).display}aug")
    fun major7(root: Int) = Chord(root, MAJ7, "${PitchClass.of(root).display}maj7")
    fun minor7(root: Int) = Chord(root, MIN7, "${PitchClass.of(root).display}m7")
    fun dominant7(root: Int) = Chord(root, DOM7, "${PitchClass.of(root).display}7")
    fun sus2(root: Int) = Chord(root, SUS2, "${PitchClass.of(root).display}sus2")
    fun sus4(root: Int) = Chord(root, SUS4, "${PitchClass.of(root).display}sus4")
    fun sixth(root: Int) = Chord(root, SIX, "${PitchClass.of(root).display}6")

    /** Diatonic triads for a scale (major-key qualities when scale is MAJOR). */
    fun diatonicTriads(key: MusicalKey): List<Chord> {
        val scale = key.scaleType
        val degrees = scale.intervals
        if (degrees.size < 7) return emptyList()
        return (0 until 7).map { i ->
            val root = (key.root + degrees[i]) % 12
            val third = (degrees[(i + 2) % 7] + if (i + 2 >= 7) 12 else 0)
            val fifth = (degrees[(i + 4) % 7] + if (i + 4 >= 7) 12 else 0)
            val thirdSize = third - degrees[i]
            val fifthSize = fifth - degrees[i]
            when {
                thirdSize == 3 && fifthSize == 6 -> diminished(root)
                thirdSize == 3 -> minor(root)
                fifthSize == 8 -> augmented(root)
                else -> major(root)
            }
        }
    }
}
