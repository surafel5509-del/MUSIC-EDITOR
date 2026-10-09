package com.studioone.core.domain.model

import kotlin.jvm.JvmInline

/** Strongly-typed identifiers. Prevents passing a track id where a clip id is expected. */
@kotlinx.serialization.Serializable
@JvmInline value class ProjectId(val value: String) {
    companion object {
        fun random(): ProjectId = ProjectId(java.util.UUID.randomUUID().toString())
    }
}

@kotlinx.serialization.Serializable
@JvmInline value class TrackId(val value: String) {
    companion object {
        fun random(): TrackId = TrackId(java.util.UUID.randomUUID().toString())
    }
}

@kotlinx.serialization.Serializable
@JvmInline value class ClipId(val value: String) {
    companion object {
        fun random(): ClipId = ClipId(java.util.UUID.randomUUID().toString())
    }
}

@kotlinx.serialization.Serializable
@JvmInline value class PresetId(val value: String)

@kotlinx.serialization.Serializable
@JvmInline value class UserId(val value: String)
