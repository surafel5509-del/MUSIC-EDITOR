package com.studioone.mobile.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class ExportFormat(
    val displayName: String,
    val extension: String,
    val mimeType: String,
    val lossless: Boolean,
    val premiumOnly: Boolean,
) {
    WAV("WAV", "wav", "audio/wav", lossless = true, premiumOnly = false),
    MP3("MP3", "mp3", "audio/mpeg", lossless = false, premiumOnly = false),
    FLAC("FLAC", "flac", "audio/flac", lossless = true, premiumOnly = true),
    AAC("AAC (m4a)", "m4a", "audio/mp4", lossless = false, premiumOnly = false),
    OGG("OGG Vorbis", "ogg", "audio/ogg", lossless = false, premiumOnly = true);

    val containerNeedsEncoder: Boolean get() = !lossless || this == FLAC
}

/** Everything needed to render one export job (mixdown, stem, or backup). */
@Serializable
data class ExportSettings(
    val kind: ExportKind = ExportKind.MIXDOWN,
    val format: ExportFormat = ExportFormat.WAV,
    val sampleRate: Int = 48_000,
    val bitDepth: BitDepth = BitDepth.PCM_24,
    val channels: ExportChannels = ExportChannels.STEREO,
    /** Audio codecs only: */
    val bitrateKbps: Int = 320,
    val vbr: Boolean = true,
    /** Range */
    val rangeStartFrame: Long = 0,
    val rangeEndFrame: Long = -1,        // -1 = end of project (incl. fx tails)
    val includeFxTails: Boolean = true,
    val normalize: Boolean = false,
    val targetPeakDb: Float = -0.3f,
    /** Loudness target for streaming platforms (master chain preset). */
    val loudnessTargetLUFS: Float? = -14f,
    /** Stem export: which tracks become individual files. */
    val stemTracks: List<TrackId> = emptyList(),
    /** Metadata written into the file (ID3/VORBIS_COMMENT/RIFF INFO). */
    val metadata: AudioMetadata = AudioMetadata(),
    /** Where the rendered file goes; resolved via MediaStore/SAF at runtime. */
    val destinationUri: String? = null,
)

@Serializable
enum class ExportKind(val displayName: String) {
    MIXDOWN("Stereo Mixdown"),
    STEMS("Stems"),
    MASTER("Master (with limiter chain)"),
    PROJECT_BACKUP("Project Backup (.s1z)"),
    RINGTONE("Ringtone (30s)"),
    VIDEO_AUDIO("Audio for Video"),
}

@Serializable
enum class ExportChannels { MONO, STEREO, BINAURAL }

@Serializable
data class AudioMetadata(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val genre: String? = null,
    val year: Int? = null,
    val comment: String? = null,
    val bpm: Double? = null,
    val initialKey: String? = null,      // ID3 TKEY, e.g. "Am"
    val artworkUri: String? = null,
    val copyright: String? = null,
    val isrc: String? = null,            // distribution-ready code
)

/** Progress feed from the render engine. */
data class ExportProgress(
    val phase: ExportPhase,
    val fraction: Float,                 // 0..1
    val framesRendered: Long,
    val totalFrames: Long,
    val currentStem: String? = null,
    val error: String? = null,
)

enum class ExportPhase { PREPARING, RENDERING, ENCODING, WRITING_METADATA, COMPLETE, FAILED }

/** Publish targets after export. */
@Serializable
enum class PublishTarget(val displayName: String) {
    STUDIOONE_FEED("StudioOne Feed"),
    INSTAGRAM("Instagram"), TIKTOK("TikTok"), YOUTUBE("YouTube"),
    WHATSAPP("WhatsApp"), TELEGRAM("Telegram"), EMAIL("Email"),
    DRIVE("Cloud Drive"), OTHER_APP("Other App"),
    DISTRIBUTION("Distribution (Pro)"),
}
