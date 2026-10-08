package com.studioone.mobile.core.model

import kotlinx.serialization.Serializable

/**
 * Strongly-typed identifiers. Wrapping raw strings prevents whole classes of
 * bugs (passing a trackId where a clipId is expected) and gives us a single
 * place to change the ID scheme (UUIDv7 preferred — time-sortable, index
 * friendly in Postgres/Firestore).
 */
@JvmInline @Serializable value class ProjectId(val value: String)
@JvmInline @Serializable value class TrackId(val value: String)
@JvmInline @Serializable value class ClipId(val value: String)
@JvmInline @Serializable value class UserId(val value: String)
@JvmInline @Serializable value class BusId(val value: String)
@JvmInline @Serializable value class FxSlotId(val value: String)
@JvmInline @Serializable value class SampleId(val value: String)
@JvmInline @Serializable value class PresetId(val value: String)
@JvmInline @Serializable value class CommentId(val value: String)
@JvmInline @Serializable value class PostId(val value: String)
@JvmInline @Serializable value class VersionId(val value: String)
