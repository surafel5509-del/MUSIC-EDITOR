package com.studioone.core.domain.model

/**
 * A clip is a block of content on a track timeline. Positions are stored in
 * frames at the project sample rate so editing stays independent of tempo.
 */
sealed interface Clip {
    val id: ClipId
    val trackId: TrackId
    val name: String
    val startFrame: Long
    val lengthFrames: Long
    val gain: Float
    val fadeInFrames: Long
    val fadeOutFrames: Long
    val colorIndex: Int
    val locked: Boolean

    val endFrame: Long get() = startFrame + lengthFrames
}

/** Clip referencing an audio file on disk (recorded or imported). */
data class AudioClip(
    override val id: ClipId = ClipId.random(),
    override val trackId: TrackId,
    override val name: String,
    override val startFrame: Long,
    override val lengthFrames: Long,
    override val gain: Float = 1f,
    override val fadeInFrames: Long = 0,
    override val fadeOutFrames: Long = 0,
    override val colorIndex: Int = 0,
    override val locked: Boolean = false,
    /** Absolute path of the source WAV/FLAC file. */
    val filePath: String,
    /** Offset into the source file where playback of this clip begins. */
    val sourceOffsetFrames: Long = 0,
    /** Total length of the source file in frames (for trim boundaries). */
    val sourceLengthFrames: Long = lengthFrames,
    val reversed: Boolean = false,
    /** Playback rate used for timestretch; 1.0 = original speed. */
    val playbackRate: Double = 1.0,
    val normalized: Boolean = false,
) : Clip

/** Clip containing MIDI notes rendered by the track's instrument. */
data class MidiClip(
    override val id: ClipId = ClipId.random(),
    override val trackId: TrackId,
    override val name: String,
    override val startFrame: Long,
    override val lengthFrames: Long,
    override val gain: Float = 1f,
    override val fadeInFrames: Long = 0,
    override val fadeOutFrames: Long = 0,
    override val colorIndex: Int = 1,
    override val locked: Boolean = false,
    val notes: List<MidiNote> = emptyList(),
    /** Preset/instrument override for this clip; falls back to track instrument. */
    val programId: String? = null,
) : Clip

/** A single MIDI note. Start/length are clip-relative frames. */
@kotlinx.serialization.Serializable
data class MidiNote(
    val id: String = java.util.UUID.randomUUID().toString(),
    val startFrame: Long,
    val lengthFrames: Long,
    val pitch: Int, // 0..127, middle C = 60
    val velocity: Int = 100, // 1..127
    val channel: Int = 0,
    /** MPE dimensions, 0..127 defaults = neutral. */
    val pressure: Int = 0,
    val slide: Int = 64,
) {
    init {
        require(pitch in 0..127) { "pitch out of MIDI range" }
        require(velocity in 0..127) { "velocity out of MIDI range" }
    }
}
