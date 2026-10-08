package com.studioone.mobile.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.studioone.mobile.core.database.entity.CommentEntity
import com.studioone.mobile.core.database.entity.LibraryItemEntity
import com.studioone.mobile.core.database.entity.OpLogEntity
import com.studioone.mobile.core.database.entity.PostEntity
import com.studioone.mobile.core.database.entity.PresetEntity
import com.studioone.mobile.core.database.entity.ProjectEntity
import com.studioone.mobile.core.database.entity.SampleEntity
import com.studioone.mobile.core.database.entity.SyncOpEntity
import com.studioone.mobile.core.database.entity.UserEntity
import com.studioone.mobile.core.database.entity.VersionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {
    @Upsert
    suspend fun upsert(project: ProjectEntity)

    @Upsert
    suspend fun upsertAll(projects: List<ProjectEntity>)

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun byId(id: String): ProjectEntity?

    @Query("SELECT * FROM projects WHERE id = :id")
    fun observeById(id: String): Flow<ProjectEntity?>

    /** Project browser list: recents first, archives excluded by default. */
    @Query(
        """SELECT * FROM projects
           WHERE (:ownerId IS NULL OR owner_id = :ownerId OR owner_id IS NULL)
             AND is_archived = :archived
           ORDER BY last_opened_at DESC, updated_at DESC""",
    )
    fun observeProjects(ownerId: String?, archived: Boolean = false): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE is_favorite = 1 AND is_archived = 0 ORDER BY updated_at DESC")
    fun observeFavorites(): Flow<List<ProjectEntity>>

    @Query(
        """SELECT * FROM projects WHERE is_archived = 0
             AND (:query = '' OR name LIKE '%' || :query || '%' OR genre LIKE '%' || :query || '%')
           ORDER BY updated_at DESC LIMIT :limit OFFSET :offset""",
    )
    suspend fun search(query: String, limit: Int, offset: Int): List<ProjectEntity>

    @Query("UPDATE projects SET dirty = 1, sync_status = 'PENDING_UPLOAD', updated_at = :now WHERE id = :id")
    suspend fun markDirty(id: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE projects SET dirty = 0, sync_status = :status WHERE id = :id")
    suspend fun markSynced(id: String, status: String = "SYNCED")

    @Query("UPDATE projects SET last_opened_at = :now WHERE id = :id")
    suspend fun touchOpened(id: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE projects SET is_archived = :archived WHERE id = :id")
    suspend fun setArchived(id: String, archived: Boolean)

    @Query("UPDATE projects SET is_favorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: String, favorite: Boolean)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM projects WHERE owner_id IS NULL OR owner_id = :ownerId")
    suspend fun countForUser(ownerId: String?): Int

    @Query("SELECT SUM(size_bytes) FROM samples")
    suspend fun totalSampleBytes(): Long?
}

@Dao
interface SyncOpDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueue(op: SyncOpEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueueAll(ops: List<SyncOpEntity>)

    @Query("SELECT * FROM sync_ops WHERE uploaded = 0 ORDER BY created_at ASC LIMIT :limit")
    suspend fun pending(limit: Int = 100): List<SyncOpEntity>

    @Query("UPDATE sync_ops SET uploaded = 1 WHERE op_id IN (:ids)")
    suspend fun markUploaded(ids: List<String>)

    @Query("UPDATE sync_ops SET attempts = attempts + 1 WHERE op_id = :id")
    suspend fun bumpAttempt(id: String)

    @Query("DELETE FROM sync_ops WHERE uploaded = 1 AND created_at < :olderThan")
    suspend fun pruneUploaded(olderThan: Long)

    @Query("SELECT * FROM sync_ops WHERE project_id = :projectId AND uploaded = 0")
    fun observePendingFor(projectId: String): Flow<List<SyncOpEntity>>
}

@Dao
interface OpLogDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(op: OpLogEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(ops: List<OpLogEntity>)

    @Query("SELECT * FROM op_log WHERE project_id = :projectId ORDER BY lamport ASC")
    suspend fun forProject(projectId: String): List<OpLogEntity>

    @Query("SELECT MAX(lamport) FROM op_log WHERE project_id = :projectId")
    suspend fun maxLamport(projectId: String): Long?

    @Query("DELETE FROM op_log WHERE project_id = :projectId AND received_at < :olderThan")
    suspend fun prune(projectId: String, olderThan: Long)
}

@Dao
interface VersionDao {
    @Upsert
    suspend fun upsert(version: VersionEntity)

    @Query("SELECT * FROM versions WHERE project_id = :projectId ORDER BY created_at DESC")
    fun observeVersions(projectId: String): Flow<List<VersionEntity>>

    @Query("SELECT * FROM versions WHERE id = :id")
    suspend fun byId(id: String): VersionEntity?

    @Query("DELETE FROM versions WHERE project_id = :projectId AND is_autosave = 1 AND created_at < :olderThan")
    suspend fun pruneAutosaves(projectId: String, olderThan: Long)
}

@Dao
interface SampleDao {
    @Upsert
    suspend fun upsert(sample: SampleEntity)

    @Query("SELECT * FROM samples WHERE project_id = :projectId")
    suspend fun forProject(projectId: String): List<SampleEntity>

    @Query("SELECT * FROM samples WHERE id = :id")
    suspend fun byId(id: String): SampleEntity?

    @Query("DELETE FROM samples WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM samples WHERE pack_id = :packId")
    suspend fun forPack(packId: String): List<SampleEntity>
}

@Dao
interface LibraryDao {
    @Upsert
    suspend fun upsertAll(items: List<LibraryItemEntity>)

    @Query(
        """SELECT * FROM library_items
           WHERE (:category IS NULL OR category = :category)
             AND (:genre IS NULL OR genre = :genre)
             AND (:query = '' OR name LIKE '%' || :query || '%' OR tags LIKE '%' || :query || '%')
           ORDER BY name ASC LIMIT :limit OFFSET :offset""",
    )
    suspend fun search(category: String?, genre: String?, query: String, limit: Int, offset: Int): List<LibraryItemEntity>

    @Query("SELECT * FROM library_items WHERE is_downloaded = 1")
    fun observeDownloaded(): Flow<List<LibraryItemEntity>>

    @Query("UPDATE library_items SET is_downloaded = :downloaded, local_uri = :localUri WHERE id = :id")
    suspend fun setDownloaded(id: String, downloaded: Boolean, localUri: String?)

    @Query("DELETE FROM library_items WHERE cached_at < :olderThan AND is_downloaded = 0")
    suspend fun pruneCache(olderThan: Long)
}

@Dao
interface PresetDao {
    @Upsert
    suspend fun upsert(preset: PresetEntity)

    @Query("SELECT * FROM presets WHERE plugin_id = :pluginId ORDER BY is_factory DESC, name ASC")
    fun observeForPlugin(pluginId: String): Flow<List<PresetEntity>>

    @Query("SELECT * FROM presets WHERE instrument_id = :instrumentId ORDER BY is_factory DESC, name ASC")
    fun observeForInstrument(instrumentId: String): Flow<List<PresetEntity>>

    @Query("SELECT * FROM presets WHERE is_favorite = 1 ORDER BY name ASC")
    fun observeFavorites(): Flow<List<PresetEntity>>

    @Query("UPDATE presets SET is_favorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: String, favorite: Boolean)

    @Query("DELETE FROM presets WHERE id = :id AND is_factory = 0")
    suspend fun delete(id: String)
}

@Dao
interface UserDao {
    @Upsert
    suspend fun upsert(user: UserEntity)

    @Query("SELECT * FROM users WHERE id = :id")
    suspend fun byId(id: String): UserEntity?

    @Query("SELECT * FROM users")
    fun observeAll(): Flow<List<UserEntity>>
}

@Dao
interface PostDao {
    @Upsert
    suspend fun upsertAll(posts: List<PostEntity>)

    @Query("SELECT * FROM posts ORDER BY created_at DESC LIMIT :limit")
    fun observeFeed(limit: Int = 100): Flow<List<PostEntity>>

    @Query("UPDATE posts SET liked_by_me = :liked, like_count = like_count + :delta WHERE id = :id")
    suspend fun setLiked(id: String, liked: Boolean, delta: Int)

    @Query("SELECT * FROM posts WHERE id = :id")
    suspend fun byId(id: String): PostEntity?
}

@Dao
interface CommentDao {
    @Upsert
    suspend fun upsert(comment: CommentEntity)

    @Query("SELECT * FROM comments WHERE project_id = :projectId ORDER BY created_at ASC")
    fun observeForProject(projectId: String): Flow<List<CommentEntity>>

    @Query("UPDATE comments SET resolved = :resolved WHERE id = :id")
    suspend fun setResolved(id: String, resolved: Boolean)

    @Query("SELECT * FROM comments WHERE pending_upload = 1")
    suspend fun pendingUpload(): List<CommentEntity>

    @Query("UPDATE comments SET pending_upload = 0 WHERE id = :id")
    suspend fun markUploaded(id: String)
}
