package com.studioone.mobile.core.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
enum class LibraryCategory(val displayName: String) {
    LOOPS("Loops"), ONE_SHOTS("One-Shots"), MIDI_PACKS("MIDI Packs"),
    PRESETS("Presets"), INSTRUMENT_PACKS("Instrument Packs"), FX_PACKS("FX Presets");
}

@Serializable
enum class LibraryGenre(val displayName: String) {
    HIP_HOP("Hip-Hop"), TRAP("Trap"), RNB("R&B"), POP("Pop"), ROCK("Rock"),
    ELECTRONIC("Electronic"), HOUSE("House"), TECHNO("Techno"), DRUM_AND_BASS("DnB"),
    LO_FI("Lo-Fi"), AMBIENT("Ambient"), CINEMATIC("Cinematic"), JAZZ("Jazz"),
    LATIN("Latin"), AFROBEATS("Afrobeats"), PODCAST("Podcast/VO"),
}

@Serializable
enum class LibraryMood(val displayName: String) {
    DARK, BRIGHT, CHILL, AGGRESSIVE, DREAMY, EMOTIONAL, GROOVY, EPIC, MINIMAL, WARM;
}

/**
 * An item in the loop/sample library. Content is delivered through the CDN;
 * [localUri] is set once the pack is downloaded (offline-first: browsed items
 * stream low-bitrate previews, full quality downloads on drop-into-timeline).
 */
@Serializable
data class LibraryItem(
    val id: SampleId,
    val name: String,
    val category: LibraryCategory,
    val genre: LibraryGenre?,
    val mood: LibraryMood?,
    val bpm: Double?,                    // detected at ingest (see backend edge function)
    val key: MusicalKey?,                // detected at ingest
    val isLoop: Boolean,
    val durationFrames: Long,
    val sampleRate: Int,
    val format: AudioFormatKind,
    val previewUrl: String,
    val fullUrl: String,
    val artworkUrl: String? = null,
    val packId: String? = null,
    val isPremium: Boolean = false,
    val isDownloaded: Boolean = false,
    val localUri: String? = null,
    val waveformPeaks: WaveformPeaks? = null,
    val tags: List<String> = emptyList(),
    val uploadCount: Int = 0,
    val createdAt: Instant? = null,
    val licenseKind: LicenseKind = LicenseKind.ROYALTY_FREE,
)

@Serializable
enum class LicenseKind(val displayName: String) {
    ROYALTY_FREE("Royalty-free"), CC_BY("CC-BY"), CC_BY_NC("CC-BY-NC"), EXCLUSIVE("Exclusive/Pro");
}

@Serializable
data class SamplePack(
    val id: String,
    val name: String,
    val description: String,
    val curator: String,
    val category: LibraryCategory,
    val genre: LibraryGenre?,
    val itemCount: Int,
    val sizeBytes: Long,
    val artworkUrl: String?,
    val priceMicros: Long = 0,           // 0 = free; >0 = IAP product
    val productId: String? = null,
    val isPremiumOnly: Boolean = false,
    val isInstalled: Boolean = false,
    val rating: Float = 0f,
)
