package com.studioone.mobile.core.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/** Starting point templates offered during onboarding / new-project sheet. */
@Serializable
enum class ProjectTemplate {
    EMPTY, BEAT, SONG, PODCAST, LIVE_RECORDING, REMIX, VOCAL_SESSION;

    val isMultiTrackDefault: Boolean
        get() = this == SONG || this == PODCAST || this == VOCAL_SESSION
}

/** Sync lifecycle of a local project relative to the cloud copy. */
@Serializable
enum class SyncStatus { LOCAL_ONLY, SYNCED, PENDING_UPLOAD, PENDING_DOWNLOAD, CONFLICT, ERROR }

/**
 * Time signature. [beatUnit] is the note value that receives one beat
 * (4 = quarter note). Compound meters (6/8, 9/8, 12/8) are expressed with
 * beatUnit = 8 and numerator 6/9/12.
 */
@Serializable
data class TimeSignature(val numerator: Int = 4, val beatUnit: Int = 4) {
    init {
        require(numerator in 1..16) { "numerator out of range: $numerator" }
        require(beatUnit in setOf(2, 4, 8, 16)) { "beatUnit must be 2,4,8,16: $beatUnit" }
    }

    /** Duration of one bar in beats (quarter notes). */
    val barLengthBeats: Double get() = numerator * 4.0 / beatUnit

    override fun toString(): String = "$numerator/$beatUnit"
}

/** Musical key = tonic pitch class + mode. Used by scale snapping & loop key detection. */
@Serializable
data class MusicalKey(val tonicPc: Int, val scale: ScaleType) {
    init { require(tonicPc in 0..11) { "pitch class out of range" } }
    val noteName: String get() = PITCH_NAMES[tonicPc]
    override fun toString(): String = "$noteName ${scale.displayName}"

    companion object {
        val PITCH_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        val C_MAJOR = MusicalKey(0, ScaleType.MAJOR)
    }
}

@Serializable
enum class ScaleType(val displayName: String, val intervals: IntArray) {
    MAJOR("Major", intArrayOf(0, 2, 4, 5, 7, 9, 11)),
    NATURAL_MINOR("Minor", intArrayOf(0, 2, 3, 5, 7, 8, 10)),
    HARMONIC_MINOR("Harmonic Minor", intArrayOf(0, 2, 3, 5, 7, 8, 11)),
    MELODIC_MINOR("Melodic Minor", intArrayOf(0, 2, 3, 5, 7, 9, 11)),
    DORIAN("Dorian", intArrayOf(0, 2, 3, 5, 7, 9, 10)),
    PHRYGIAN("Phrygian", intArrayOf(0, 1, 3, 5, 7, 8, 10)),
    LYDIAN("Lydian", intArrayOf(0, 2, 4, 6, 7, 9, 11)),
    MIXOLYDIAN("Mixolydian", intArrayOf(0, 2, 4, 5, 7, 9, 10)),
    LOCRIAN("Locrian", intArrayOf(0, 1, 3, 5, 6, 8, 10)),
    MAJOR_PENTATONIC("Major Pentatonic", intArrayOf(0, 2, 4, 7, 9)),
    MINOR_PENTATONIC("Minor Pentatonic", intArrayOf(0, 3, 5, 7, 10)),
    BLUES("Blues", intArrayOf(0, 3, 5, 6, 7, 10)),
    HIJAZ("Hijaz", intArrayOf(0, 1, 4, 5, 7, 8, 11)),
    WHOLE_TONE("Whole Tone", intArrayOf(0, 2, 4, 6, 8, 10)),
    CHROMATIC("Chromatic", intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11));

    /** True when [pitch] (0-11) belongs to this scale relative to tonic pc. */
    fun contains(pitchClass: Int, tonicPc: Int): Boolean =
        intervals.contains(Math.floorMod(pitchClass - tonicPc, 12))
}

/**
 * Tempo automation is a *map*, not a scalar — supports tempo changes and
 * rubato. Segments are stored sorted by bar position; between markers tempo
 * is constant (step) or linearly interpolated (ramp).
 */
@Serializable
data class TempoMap(
    val markers: List<TempoMarker> = listOf(TempoMarker(0.0, 120.0, TempoCurve.STEP)),
) {
    init { require(markers.isNotEmpty()) { "tempo map must have at least one marker" } }

    /** BPM active at bar position [bar] (fractional bars allowed). */
    fun bpmAt(bar: Double): Double {
        var current = markers.first()
        for (m in markers) {
            if (m.bar <= bar) current = m else break
        }
        if (current.curve == TempoCurve.STEP) return current.bpm
        val next = markers.firstOrNull { it.bar > current.bar } ?: return current.bpm
        val t = ((bar - current.bar) / (next.bar - current.bar)).coerceIn(0.0, 1.0)
        return current.bpm + (next.bpm - current.bpm) * t
    }

    val baseBpm: Double get() = markers.first().bpm
}

@Serializable
data class TempoMarker(val bar: Double, val bpm: Double, val curve: TempoCurve = TempoCurve.STEP) {
    init { require(bpm in 10.0..400.0) { "BPM out of range: $bpm" } }
}

@Serializable
enum class TempoCurve { STEP, RAMP }

/**
 * The aggregate root for a DAW session. Everything else (tracks, clips, mixer,
 * automation) hangs off a project. Projects are versioned documents synced
 * with the CRDT protocol in core:realtime (see docs/COLLABORATION.md).
 */
@Serializable
data class Project(
    val id: ProjectId,
    val ownerId: UserId?,            // null => guest/offline project
    val name: String,
    val template: ProjectTemplate = ProjectTemplate.EMPTY,
    val tempoMap: TempoMap = TempoMap(),
    val timeSignature: TimeSignature = TimeSignature(),
    val key: MusicalKey = MusicalKey.C_MAJOR,
    val sampleRate: Int = 48_000,
    val bitDepth: BitDepth = BitDepth.FLOAT_32,
    val tracks: List<Track> = emptyList(),
    val buses: List<Bus> = listOf(Bus.master()),
    val durationBars: Double = 64.0,
    val isArchived: Boolean = false,
    val isFavorite: Boolean = false,
    val syncStatus: SyncStatus = SyncStatus.LOCAL_ONLY,
    val documentVersion: Long = 0L,  // monotonic Lamport-ish clock for LWW merges
    val createdAt: Instant,
    val updatedAt: Instant,
    val lastOpenedAt: Instant? = null,
    val artworkUri: String? = null,
    val genre: String? = null,
    val collaborators: List<Collaborator> = emptyList(),
) {
    val isGuest: Boolean get() = ownerId == null
    fun track(id: TrackId): Track? = tracks.firstOrNull { it.id == id }
}
