package com.studioone.core.data.di

import android.content.Context
import androidx.room.RoomDatabase
import com.studioone.core.data.repository.LibraryRepositoryImpl
import com.studioone.core.data.repository.ProjectRepositoryImpl
import com.studioone.core.data.repository.TemplateRepositoryImpl
import com.studioone.core.data.repository.WaveformRepositoryImpl
import com.studioone.core.database.StudioOneDatabase
import com.studioone.core.database.dao.AudioClipDao
import com.studioone.core.database.dao.LibraryDao
import com.studioone.core.database.dao.MidiClipDao
import com.studioone.core.database.dao.PresetDao
import com.studioone.core.database.dao.ProjectDao
import com.studioone.core.database.dao.ProjectVersionDao
import com.studioone.core.database.dao.SyncOutboxDao
import com.studioone.core.database.dao.TrackDao
import com.studioone.core.database.dao.WaveformPeaksDao
import com.studioone.core.domain.repository.LibraryRepository
import com.studioone.core.domain.repository.ProjectRepository
import com.studioone.core.domain.repository.TemplateRepository
import com.studioone.core.domain.repository.WaveformRepository
import dagger.Binds
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
        StudioOneDatabase.build(context)

    @Provides fun provideProjectDao(db: StudioOneDatabase): ProjectDao = db.projectDao()
    @Provides fun provideTrackDao(db: StudioOneDatabase): TrackDao = db.trackDao()
    @Provides fun provideAudioClipDao(db: StudioOneDatabase): AudioClipDao = db.audioClipDao()
    @Provides fun provideMidiClipDao(db: StudioOneDatabase): MidiClipDao = db.midiClipDao()
    @Provides fun provideProjectVersionDao(db: StudioOneDatabase): ProjectVersionDao = db.projectVersionDao()
    @Provides fun provideSyncOutboxDao(db: StudioOneDatabase): SyncOutboxDao = db.syncOutboxDao()
    @Provides fun provideLibraryDao(db: StudioOneDatabase): LibraryDao = db.libraryDao()
    @Provides fun providePresetDao(db: StudioOneDatabase): PresetDao = db.presetDao()
    @Provides fun provideWaveformPeaksDao(db: StudioOneDatabase): WaveformPeaksDao = db.waveformPeaksDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds @Singleton
    abstract fun bindProjectRepository(impl: ProjectRepositoryImpl): ProjectRepository

    @Binds @Singleton
    abstract fun bindTemplateRepository(impl: TemplateRepositoryImpl): TemplateRepository

    @Binds @Singleton
    abstract fun bindLibraryRepository(impl: LibraryRepositoryImpl): LibraryRepository

    @Binds @Singleton
    abstract fun bindWaveformRepository(impl: WaveformRepositoryImpl): WaveformRepository
}
