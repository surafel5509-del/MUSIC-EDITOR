package com.studioone.mobile.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Storage strategy (docs/ARCHITECTURE.md §data):
 *
 * Projects are stored as *documents* — the full serialized Project JSON in
 * [ProjectEntity.document] — plus denormalized metadata columns for fast,
 * indexed list/sort/filter queries. Normalizing every clip/note into tables
 * buys nothing on mobile (we always load the whole session) and complicates
 * CRDT sync; the document approach matches the collaboration protocol 1:1.
 * Writes go through a single-transaction upsert; autosave debounce keeps the
 * churn low (see core:data ProjectAutosaver).
 */
@Entity(
    tableName = "projects",
    indices = [Index("owner_id"), Index("updated_at"), Index("is_archived"), Index("sync_status")],
)
data class ProjectEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "owner_id") val ownerId: String?,
    val name: String,
    @ColumnInfo(name = "template") val template: String,
    @ColumnInfo(name = "bpm") val bpm: Double,
    @ColumnInfo(name = "key_name") val keyName: String?,
    @ColumnInfo(name = "track_count") val trackCount: Int,
    @ColumnInfo(name = "duration_bars") val durationBars: Double,
    @ColumnInfo(name = "is_archived") val isArchived: Boolean,
    @ColumnInfo(name = "is_favorite") val isFavorite: Boolean,
    @ColumnInfo(name = "sync_status") val syncStatus: String,
    @ColumnInfo(name = "document_version") val documentVersion: Long,
    /** Serialized com.studioone.mobile.core.model.Project (kotlinx.serialization JSON). */
    val document: String,
    @ColumnInfo(name = "artwork_uri") val artworkUri: String?,
    @ColumnInfo(name = "genre") val genre: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "last_opened_at") val lastOpenedAt: Long?,
    @ColumnInfo(name = "dirty") val dirty: Boolean = false, // local edits pending sync
)

/** Outbox of collaboration ops awaiting upload (at-least-once, deduped by opId server-side). */
@Entity(tableName = "sync_ops", indices = [Index("project_id"), Index("created_at")])
data class SyncOpEntity(
    @PrimaryKey @ColumnInfo(name = "op_id") val opId: String,
    @ColumnInfo(name = "project_id") val projectId: String,
    val author: String,
    val lamport: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    /** Serialized OpEnvelope. */
    val payload: String,
    @ColumnInfo(name = "attempts") val attempts: Int = 0,
    @ColumnInfo(name = "uploaded") val uploaded: Boolean = false,
)

/** Incoming ops log per project (used for version history & conflict review). */
@Entity(tableName = "op_log", indices = [Index("project_id", "lamport")])
data class OpLogEntity(
    @PrimaryKey @ColumnInfo(name = "op_id") val opId: String,
    @ColumnInfo(name = "project_id") val projectId: String,
    val lamport: Long,
    val author: String,
    @ColumnInfo(name = "received_at") val receivedAt: Long,
    val payload: String,
)

/** Version history entries (document snapshots stored on-device + cloud). */
@Entity(tableName = "versions", indices = [Index("project_id")])
data class VersionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "project_id") val projectId: String,
    val label: String,
    @ColumnInfo(name = "document_version") val documentVersion: Long,
    @ColumnInfo(name = "created_by") val createdBy: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "is_autosave") val isAutosave: Boolean,
    @ColumnInfo(name = "storage_path") val storagePath: String?,
    /** Inline document for local snapshots (cloud snapshots store null + path). */
    val document: String? = null,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long = 0,
)

/** Sample store: imported/recorded/downloaded audio files. */
@Entity(tableName = "samples", indices = [Index("project_id"), Index("pack_id")])
data class SampleEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "project_id") val projectId: String?,   // null = global (library)
    @ColumnInfo(name = "pack_id") val packId: String?,
    val name: String,
    val uri: String,
    val format: String,
    @ColumnInfo(name = "sample_rate") val sampleRate: Int,
    val channels: Int,
    @ColumnInfo(name = "bit_depth") val bitDepth: Int,
    @ColumnInfo(name = "duration_frames") val durationFrames: Long,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "peaks_json") val peaksJson: String?,   // serialized WaveformPeaks level set
    @ColumnInfo(name = "loudness_lufs") val loudnessLufs: Float?,
    val bpm: Double?,
    @ColumnInfo(name = "key_name") val keyName: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "origin") val origin: String,           // RECORDED | IMPORTED | LIBRARY | BOUNCE | RENDER
)

/** Cached library catalog items (browse offline after first fetch). */
@Entity(tableName = "library_items", indices = [Index("category"), Index("pack_id")])
data class LibraryItemEntity(
    @PrimaryKey val id: String,
    val name: String,
    val category: String,
    val genre: String?,
    val mood: String?,
    val bpm: Double?,
    @ColumnInfo(name = "key_name") val keyName: String?,
    @ColumnInfo(name = "is_loop") val isLoop: Boolean,
    @ColumnInfo(name = "preview_url") val previewUrl: String,
    @ColumnInfo(name = "full_url") val fullUrl: String,
    @ColumnInfo(name = "artwork_url") val artworkUrl: String?,
    @ColumnInfo(name = "pack_id") val packId: String?,
    @ColumnInfo(name = "is_premium") val isPremium: Boolean,
    @ColumnInfo(name = "is_downloaded") val isDownloaded: Boolean,
    @ColumnInfo(name = "local_uri") val localUri: String?,
    val tags: String?, // comma-joined
    @ColumnInfo(name = "duration_frames") val durationFrames: Long,
    @ColumnInfo(name = "cached_at") val cachedAt: Long,
)

/** FX/instrument presets (factory + user + downloaded). */
@Entity(tableName = "presets", indices = [Index("plugin_id"), Index("instrument_id")])
data class PresetEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "plugin_id") val pluginId: String?,      // FxPluginId name
    @ColumnInfo(name = "instrument_id") val instrumentId: String?,
    val params: String,       // JSON map paramIndex -> value, or chain JSON
    @ColumnInfo(name = "chain_json") val chainJson: String?,
    @ColumnInfo(name = "author_id") val authorId: String?,
    @ColumnInfo(name = "is_factory") val isFactory: Boolean,
    @ColumnInfo(name = "is_favorite") val isFavorite: Boolean,
    val tags: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** Cached profiles (social feed + collaborators render offline). */
@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey val id: String,
    val email: String?,
    @ColumnInfo(name = "display_name") val displayName: String,
    val handle: String?,
    val bio: String?,
    @ColumnInfo(name = "avatar_url") val avatarUrl: String?,
    val tier: String,
    @ColumnInfo(name = "cached_at") val cachedAt: Long,
)

/** Post cache for the social feed (offline scroll). */
@Entity(tableName = "posts", indices = [Index("created_at"), Index("author_id")])
data class PostEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "author_id") val authorId: String,
    val kind: String,
    val text: String?,
    @ColumnInfo(name = "media_url") val mediaUrl: String?,
    @ColumnInfo(name = "waveform_url") val waveformUrl: String?,
    @ColumnInfo(name = "like_count") val likeCount: Int,
    @ColumnInfo(name = "comment_count") val commentCount: Int,
    @ColumnInfo(name = "play_count") val playCount: Int,
    @ColumnInfo(name = "liked_by_me") val likedByMe: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    val document: String, // full serialized Post for detail views
)

/** Project comments (mirrors cloud; offline-capable). */
@Entity(tableName = "comments", indices = [Index("project_id")])
data class CommentEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "project_id") val projectId: String,
    @ColumnInfo(name = "author_id") val authorId: String,
    @ColumnInfo(name = "author_name") val authorName: String,
    val text: String,
    @ColumnInfo(name = "anchor_frame") val anchorFrame: Long?,
    @ColumnInfo(name = "anchor_track_id") val anchorTrackId: String?,
    val resolved: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "pending_upload") val pendingUpload: Boolean = false,
)
