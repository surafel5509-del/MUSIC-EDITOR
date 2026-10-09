package com.studioone.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.studioone.core.database.entity.AudioClipEntity
import com.studioone.core.database.entity.LibraryItemEntity
import com.studioone.core.database.entity.MidiClipEntity
import com.studioone.core.database.entity.PresetEntity
import com.studioone.core.database.entity.ProjectEntity
import com.studioone.core.database.entity.ProjectVersionEntity
import com.studioone.core.database.entity.SyncOutboxEntity
import com.studioone.core.database.entity.TrackEntity
import com.studioone.core.database.entity.WaveformPeaksEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id")
    fun observeById(id: String): Flow<ProjectEntity?>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun getById(id: String): ProjectEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(project: ProjectEntity)

    @Update
    suspend fun update(project: ProjectEntity)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM tracks WHERE project_id = :projectId")
    suspend fun deleteTracks(projectId: String)

    @Query("DELETE FROM audio_clips WHERE project_id = :projectId")
    suspend fun deleteAudioClips(projectId: String)

    @Query("DELETE FROM midi_clips WHERE project_id = :projectId")
    suspend fun deleteMidiClips(projectId: String)

    @Transaction
    suspend fun deleteProjectCascade(projectId: String) {
        deleteTracks(projectId)
        deleteAudioClips(projectId)
        deleteMidiClips(projectId)
        delete(projectId)
    }
}

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks WHERE project_id = :projectId ORDER BY order_index")
    suspend fun tracksFor(projectId: String): List<TrackEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(tracks: List<TrackEntity>)

    @Query("DELETE FROM tracks WHERE project_id = :projectId")
    suspend fun deleteAll(projectId: String)
}

@Dao
interface AudioClipDao {
    @Query("SELECT * FROM audio_clips WHERE project_id = :projectId")
    suspend fun clipsFor(projectId: String): List<AudioClipEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(clips: List<AudioClipEntity>)

    @Query("DELETE FROM audio_clips WHERE project_id = :projectId")
    suspend fun deleteAll(projectId: String)
}

@Dao
interface MidiClipDao {
    @Query("SELECT * FROM midi_clips WHERE project_id = :projectId")
    suspend fun clipsFor(projectId: String): List<MidiClipEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(clips: List<MidiClipEntity>)

    @Query("DELETE FROM midi_clips WHERE project_id = :projectId")
    suspend fun deleteAll(projectId: String)
}

@Dao
interface ProjectVersionDao {
    @Query("SELECT * FROM project_versions WHERE project_id = :projectId ORDER BY version DESC")
    fun observeVersions(projectId: String): Flow<List<ProjectVersionEntity>>

    @Insert
    suspend fun insert(version: ProjectVersionEntity)

    @Query("SELECT * FROM project_versions WHERE project_id = :projectId AND version = :version LIMIT 1")
    suspend fun get(projectId: String, version: Int): ProjectVersionEntity?

    @Query("SELECT COALESCE(MAX(version), 0) FROM project_versions WHERE project_id = :projectId")
    suspend fun maxVersion(projectId: String): Int
}

@Dao
interface SyncOutboxDao {
    @Insert
    suspend fun enqueue(op: SyncOutboxEntity)

    @Query("SELECT * FROM sync_outbox ORDER BY created_at ASC LIMIT :limit")
    suspend fun nextBatch(limit: Int): List<SyncOutboxEntity>

    @Query("DELETE FROM sync_outbox WHERE id = :id")
    suspend fun acknowledge(id: Long)

    @Query("UPDATE sync_outbox SET attempts = attempts + 1 WHERE id = :id")
    suspend fun markAttempt(id: Long)

    @Query("SELECT COUNT(*) FROM sync_outbox")
    fun observePendingCount(): Flow<Int>
}

@Dao
interface LibraryDao {
    @Query("SELECT * FROM library_items")
    fun observeAll(): Flow<List<LibraryItemEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: LibraryItemEntity)

    @Query("DELETE FROM library_items WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface PresetDao {
    @Query("SELECT * FROM presets WHERE target_id = :targetId")
    fun observeFor(targetId: String): Flow<List<PresetEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(preset: PresetEntity)
}

@Dao
interface WaveformPeaksDao {
    @Query(
        "SELECT * FROM waveform_peaks WHERE path = :path AND bucket_count = :bucketCount " +
            "AND offset_frames = :offsetFrames AND length_frames = :lengthFrames LIMIT 1",
    )
    suspend fun get(path: String, bucketCount: Int, offsetFrames: Long, lengthFrames: Long): WaveformPeaksEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: WaveformPeaksEntity)

    @Query("DELETE FROM waveform_peaks WHERE path = :path")
    suspend fun invalidate(path: String)
}
