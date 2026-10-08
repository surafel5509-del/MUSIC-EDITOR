package com.studioone.core.domain.model.library

import com.studioone.core.domain.model.music.MusicalKey

/** Content kinds shown in the loop browser. */
enum class LoopKind { LOOP, ONE_SHOT, MIDI_PACK, PRESET, SAMPLE_PACK }

/** Where the asset lives. */
enum class LoopSource { LOCAL, CLOUD, DOWNLOADED }

/** A royalty-free loop / one-shot / pack entry in the content library. */
data class LoopItem(
    val id: String,
    val name: String,
    val kind: LoopKind,
    val source: LoopSource,
    val bpm: Double? = null,
    val key: MusicalKey? = null,
    val durationMs: Long? = null,
    val tags: List<String> = emptyList(),
    val packId: String? = null,
    /** Local playable path; null until downloaded. */
    val localPath: String? = null,
    val remoteUrl: String? = null,
    val sizeBytes: Long = 0,
    val favorite: Boolean = false,
) {
    val isDownloaded: Boolean get() = localPath != null
}

/** Downloadable content pack (official or user-shared). */
data class SamplePack(
    val id: String,
    val name: String,
    val description: String,
    val author: String,
    val itemCount: Int,
    val sizeBytes: Long,
    val coverUrl: String?,
    val installed: Boolean,
)

/** Instrument/effect preset stored as serialized parameter JSON. */
data class Preset(
    val id: String,
    val name: String,
    val targetId: String, // instrument id or effect type name
    val category: String,
    val author: String,
    val favorite: Boolean = false,
    /** JSON blob of parameters, interpreted by the target engine. */
    val paramsJson: String,
)
