package com.studioone.mobile.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.studioone.mobile.core.database.dao.CommentDao
import com.studioone.mobile.core.database.dao.LibraryDao
import com.studioone.mobile.core.database.dao.OpLogDao
import com.studioone.mobile.core.database.dao.PostDao
import com.studioone.mobile.core.database.dao.PresetDao
import com.studioone.mobile.core.database.dao.ProjectDao
import com.studioone.mobile.core.database.dao.SampleDao
import com.studioone.mobile.core.database.dao.SyncOpDao
import com.studioone.mobile.core.database.dao.UserDao
import com.studioone.mobile.core.database.dao.VersionDao
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

/**
 * Local persistence. Version history:
 *  1 — initial schema
 * Migration policy: additive-only with fallbackToDestructiveMigration OFF in
 * production (data loss is unacceptable for a DAW); every schema change ships
 * a Migration + a migration test (see androidTest).
 */
@Database(
    entities = [
        ProjectEntity::class, SyncOpEntity::class, OpLogEntity::class,
        VersionEntity::class, SampleEntity::class, LibraryItemEntity::class,
        PresetEntity::class, UserEntity::class, PostEntity::class, CommentEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class StudioOneDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun syncOpDao(): SyncOpDao
    abstract fun opLogDao(): OpLogDao
    abstract fun versionDao(): VersionDao
    abstract fun sampleDao(): SampleDao
    abstract fun libraryDao(): LibraryDao
    abstract fun presetDao(): PresetDao
    abstract fun userDao(): UserDao
    abstract fun postDao(): PostDao
    abstract fun commentDao(): CommentDao

    companion object {
        const val NAME = "studioone.db"
    }
}
