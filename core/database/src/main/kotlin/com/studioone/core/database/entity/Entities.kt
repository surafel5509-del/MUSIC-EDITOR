package com.studioone.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entities mirror the domain model. Complex nested values (effect
 * chains, sends, automation, MIDI notes) are stored as JSON columns to keep
 * the schema migration-friendly while the domain layer stays normalized.
 */

@Entity(tableName = "projects")
@kotlinx.serialization.Serializable
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    @ColumnInfo(name = "template_id") val templateId: String?,
    val tempo: Double,
    @ColumnInfo(name = "time_sig_num") val timeSigNum: Int,
    @ColumnInfo(name = "time_sig_den") val timeSigDen: Int,
    @ColumnInfo(name = "musical_key") val musicalKey: String?,
    @ColumnInfo(name = "sample_rate") val sampleRate: Int,
    val status: String,
    @ColumnInfo(name = "color_index") val colorIndex: Int,
    val version: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "duration_frames") val durationFrames: Long,
    @ColumnInfo(name = "owner_id") val ownerId: String?,
    @ColumnInfo(name = "collab_session_id") val collabSessionId: String?,
)

@Entity(
    tableName = "tracks",
    indices = [Index("project_id")],
)
@kotlinx.serialization.Serializable
data class TrackEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "project_id") val projectId: String,
    val name: String,
    val type: String,
    @ColumnInfo(name = "color_index") val colorIndex: Int,
    @ColumnInfo(name = "order_index") val orderIndex: Int,
    val volume: Float,
    val pan: Float,
    val muted: Boolean,
    val soloed: Boolean,
    val armed: Boolean,
    val frozen: Boolean,
    @ColumnInfo(name = "input_id") val inputId: String,
    @ColumnInfo(name = "output_bus") val outputBus: String,
    @ColumnInfo(name = "instrument_id") val instrumentId: String?,
    @ColumnInfo(name = "inserts_json") val insertsJson: String,
    @ColumnInfo(name = "sends_json") val sendsJson: String,
    @ColumnInfo(name = "automation_json") val automationJson: String,
)

@Entity(
    tableName = "audio_clips",
    indices = [Index("track_id"), Index("project_id")],
)
@kotlinx.serialization.Serializable
data class AudioClipEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "project_id") val projectId: String,
    @ColumnInfo(name = "track_id") val trackId: String,
    val name: String,
    @ColumnInfo(name = "start_frame") val startFrame: Long,
    @ColumnInfo(name = "length_frames") val lengthFrames: Long,
    val gain: Float,
    @ColumnInfo(name = "fade_in_frames") val fadeInFrames: Long,
    @ColumnInfo(name = "fade_out_frames") val fadeOutFrames: Long,
    @ColumnInfo(name = "color_index") val colorIndex: Int,
    val locked: Boolean,
    @ColumnInfo(name = "file_path") val filePath: String,
    @ColumnInfo(name = "source_offset_frames") val sourceOffsetFrames: Long,
    @ColumnInfo(name = "source_length_frames") val sourceLengthFrames: Long,
    val reversed: Boolean,
    @ColumnInfo(name = "playback_rate") val playbackRate: Double,
    val normalized: Boolean,
)

@Entity(
    tableName = "midi_clips",
    indices = [Index("track_id"), Index("project_id")],
)
@kotlinx.serialization.Serializable
data class MidiClipEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "project_id") val projectId: String,
    @ColumnInfo(name = "track_id") val trackId: String,
    val name: String,
    @ColumnInfo(name = "start_frame") val startFrame: Long,
    @ColumnInfo(name = "length_frames") val lengthFrames: Long,
    val gain: Float,
    @ColumnInfo(name = "fade_in_frames") val fadeInFrames: Long,
    @ColumnInfo(name = "fade_out_frames") val fadeOutFrames: Long,
    @ColumnInfo(name = "color_index") val colorIndex: Int,
    val locked: Boolean,
    @ColumnInfo(name = "notes_json") val notesJson: String,
    @ColumnInfo(name = "program_id") val programId: String?,
)

@Entity(
    tableName = "project_versions",
    indices = [Index("project_id")],
)
data class ProjectVersionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "project_id") val projectId: String,
    val version: Int,
    val label: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "payload_json") val payloadJson: String,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
)

/** Pending outbound operations for the offline-first sync engine. */
@Entity(tableName = "sync_outbox")
data class SyncOutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "entity_type") val entityType: String,
    @ColumnInfo(name = "entity_id") val entityId: String,
    @ColumnInfo(name = "op_json") val opJson: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    val attempts: Int = 0,
)

@Entity(tableName = "library_items")
data class LibraryItemEntity(
    @PrimaryKey val id: String,
    val name: String,
    val kind: String,
    val source: String,
    val bpm: Double?,
    @ColumnInfo(name = "musical_key") val musicalKey: String?,
    @ColumnInfo(name = "duration_ms") val durationMs: Long?,
    val tags: String,
    @ColumnInfo(name = "pack_id") val packId: String?,
    @ColumnInfo(name = "local_path") val localPath: String?,
    @ColumnInfo(name = "remote_url") val remoteUrl: String?,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    val favorite: Boolean,
)

@Entity(tableName = "presets")
data class PresetEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "target_id") val targetId: String,
    val category: String,
    val author: String,
    val favorite: Boolean,
    @ColumnInfo(name = "params_json") val paramsJson: String,
)

/** Cached waveform peaks so the timeline never re-decodes audio to draw. */
@Entity(
    tableName = "waveform_peaks",
    primaryKeys = ["path", "bucket_count", "offset_frames", "length_frames"],
)
data class WaveformPeaksEntity(
    @ColumnInfo(name = "path") val path: String,
    @ColumnInfo(name = "bucket_count") val bucketCount: Int,
    @ColumnInfo(name = "offset_frames") val offsetFrames: Long,
    @ColumnInfo(name = "length_frames") val lengthFrames: Long,
    val peaks: ByteArray,
    @ColumnInfo(name = "computed_at") val computedAt: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WaveformPeaksEntity) return false
        return path == other.path && bucketCount == other.bucketCount &&
            offsetFrames == other.offsetFrames && lengthFrames == other.lengthFrames
    }

    override fun hashCode(): Int {
        var result = path.hashCode()
        result = 31 * result + bucketCount
        result = 31 * result + offsetFrames.hashCode()
        result = 31 * result + lengthFrames.hashCode()
        return result
    }
}
