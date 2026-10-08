package com.studioone.mobile.core.database.di

import android.content.Context
import androidx.room.Room
import com.studioone.mobile.core.database.StudioOneDatabase
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
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): StudioOneDatabase =
        Room.databaseBuilder(context, StudioOneDatabase::class.java, StudioOneDatabase.NAME)
            // WAL keeps the audio prefetch coroutine reading while autosave writes.
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .setQueryCoroutineContext(kotlinx.coroutines.Dispatchers.IO)
            .build()

    @Provides fun projectDao(db: StudioOneDatabase): ProjectDao = db.projectDao()
    @Provides fun syncOpDao(db: StudioOneDatabase): SyncOpDao = db.syncOpDao()
    @Provides fun opLogDao(db: StudioOneDatabase): OpLogDao = db.opLogDao()
    @Provides fun versionDao(db: StudioOneDatabase): VersionDao = db.versionDao()
    @Provides fun sampleDao(db: StudioOneDatabase): SampleDao = db.sampleDao()
    @Provides fun libraryDao(db: StudioOneDatabase): LibraryDao = db.libraryDao()
    @Provides fun presetDao(db: StudioOneDatabase): PresetDao = db.presetDao()
    @Provides fun userDao(db: StudioOneDatabase): UserDao = db.userDao()
    @Provides fun postDao(db: StudioOneDatabase): PostDao = db.postDao()
    @Provides fun commentDao(db: StudioOneDatabase): CommentDao = db.commentDao()
}
