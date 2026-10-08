package com.studioone.mobile.core.model

import kotlinx.serialization.Serializable

/** Fade curve applied at clip edges. Times are in frames relative to clip start/end. */
@Serializable
data class Fade(
    val fadeInFrames: Long = 0,
    val fadeOutFrames: Long = 0,
    val curve: FadeCurve = FadeCurve.EQUAL_POWER,
)

@Serializable
enum class FadeCurve { LINEAR, EQUAL_POWER, EXPONENTIAL, S_CURVE }

/** Loop settings for audio clips (MIDI clips loop by [MidiClip.isLooped]). */
@Serializable
data class ClipLoop(val enabled: Boolean = false, val loopCount: Int = -1 /* -1 = to clip end */)

/** How a clip's source audio maps onto the project timeline. */
@Serializable
enum class StretchAlgorithm {
    NONE,        // resample only (pitch follows tempo)
    ELASTIQUE,   // time-stretch preserving pitch (phase vocoder + transient preservation)
    REALTIME_MPE,// musical pitch editing: stretch + per-region pitch shift
}

/**
 * Base clip = a positioned region on a track. [startFrame] is absolute
 * timeline position in project sample frames; [lengthFrames] the visible
 * region length. All timeline math is frame-exact (no floats) — musical
 * positions are derived via the tempo map.
 */
@Serializable
sealed class Clip {
    abstract val id: ClipId
    abstract val startFrame: Long
    abstract val lengthFrames: Long
    abstract val name: String
    abstract val gainDb: Float
    abstract val fade: Fade
    abstract val muted: Boolean

    val endFrame: Long get() = startFrame + lengthFrames

    abstract fun withPosition(newStartFrame: Long): Clip
    abstract fun withLength(newLengthFrames: Long): Clip
}

@Serializable
data class AudioClip(
    override val id: ClipId,
    override val startFrame: Long,
    override val lengthFrames: Long,
    override val name: String = "Audio",
    override val gainDb: Float = 0f,
    override val fade: Fade = Fade(),
    override val muted: Boolean = false,
    /** Pointer to the source file; never null after import completes. */
    val fileRef: AudioFileRef,
    /** Read offset inside the source file, in source frames (trim-in). */
    val sourceOffsetFrames: Long = 0,
    /** Trim-out: source frames excluded at the end. */
    val sourceTailCutFrames: Long = 0,
    val loop: ClipLoop = ClipLoop(),
    val reverse: Boolean = false,
    val pitchShiftSemitones: Float = 0f,
    val stretch: StretchAlgorithm = StretchAlgorithm.NONE,
    /** Source tempo when the file is a loop — enables tempo match on import. */
    val sourceBpm: Double? = null,
    val sourceKey: MusicalKey? = null,
    val normalized: Boolean = false,
    val crossfadeId: String? = null, // shared id links two clips in a crossfade
) : Clip() {
    override fun withPosition(newStartFrame: Long) = copy(startFrame = newStartFrame)
    override fun withLength(newLengthFrames: Long) = copy(lengthFrames = newLengthFrames)
}

@Serializable
data class MidiClip(
    override val id: ClipId,
    override val startFrame: Long,
    override val lengthFrames: Long,
    override val name: String = "MIDI",
    override val gainDb: Float = 0f,
    override val fade: Fade = Fade(),
    override val muted: Boolean = false,
    val notes: List<MidiNote> = emptyList(),
    val isLooped: Boolean = false,
    /** Clip can override project key/scale for scale-snapping inside the editor. */
    val keyOverride: MusicalKey? = null,
    val quantizeOnRecord: QuantizeOptions? = null,
) : Clip() {
    override fun withPosition(newStartFrame: Long) = copy(startFrame = newStartFrame)
    override fun withLength(newLengthFrames: Long) = copy(lengthFrames = newLengthFrames)
}

/** Reference to an audio file managed by the sample store (MediaStore/SAF/app cache). */
@Serializable
data class AudioFileRef(
    val id: SampleId,
    val uri: String,               // content:// or file:// — resolved through SAF grants
    val format: AudioFormatKind = AudioFormatKind.WAV,
    val sampleRate: Int = 48_000,
    val channels: Int = 2,
    val bitDepth: BitDepth = BitDepth.PCM_24,
    val durationFrames: Long = 0,
    val sizeBytes: Long = 0,
    /** Pre-computed peak envelope for waveform rendering (min/max per bucket). */
    val peaks: WaveformPeaks? = null,
    val loudnessLUFS: Float? = null,
)

@Serializable
enum class AudioFormatKind { WAV, AIFF, FLAC, MP3, AAC, OGG, CAF, RAW }

@Serializable
enum class BitDepth(val bits: Int, val isFloat: Boolean) {
    PCM_16(16, false), PCM_24(24, false), PCM_32(32, false), FLOAT_32(32, true);
}

/**
 * Peak envelope for waveform drawing: [peaks] holds interleaved min,max per
 * bucket at [framesPerBucket] resolution. Generated on a background thread at
 * import time and cached in Room; arranger renders from this at 60fps without
 * touching the audio file.
 */
@Serializable
data class WaveformPeaks(
    val framesPerBucket: Int,
    val channelCount: Int,
    val peaks: FloatArray, // [bucket * channels * 2 + ch * 2 + (0=min,1=max)]
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WaveformPeaks) return false
        return framesPerBucket == other.framesPerBucket &&
            channelCount == other.channelCount && peaks.contentEquals(other.peaks)
    }
    override fun hashCode(): Int =
        31 * (31 * framesPerBucket + channelCount) + peaks.contentHashCode()

    fun minAt(bucket: Int, channel: Int): Float =
        peaks[(bucket * channelCount + channel) * 2]
    fun maxAt(bucket: Int, channel: Int): Float =
        peaks[(bucket * channelCount + channel) * 2 + 1]
    val bucketCount: Int get() = peaks.size / (channelCount * 2)
}
