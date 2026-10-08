package com.studioone.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.studioone.core.database.dao.AudioClipDao
import com.studioone.core.database.dao.LibraryDao
import com.studioone.core.database.dao.MidiClipDao
import com.studioone.core.database.dao.PresetDao
import com.studioone.core.database.dao.ProjectDao
import com.studioone.core.database.dao.ProjectVersionDao
import com.studioone.core.database.dao.SyncOutboxDao
import com.studioone.core.database.dao.TrackDao
import com.studioone.core.database.dao.WaveformPeaksDao
import com.studioone.core.database.entity.AudioClipEntity
import com.studioone.core.database.entity.LibraryItemEntity
import com.studioone.core.database.entity.MidiClipEntity
import com.studioone.core.database.entity.PresetEntity
import com.studioone.core.database.entity.ProjectEntity
import com.studioone.core.database.entity.ProjectVersionEntity
import com.studioone.core.database.entity.SyncOutboxEntity
import com.studioone.core.database.entity.TrackEntity
import com.studioone.core.database.entity.WaveformPeaksEntity

@Database(
    entities = [
        ProjectEntity::class,
        TrackEntity::class,
        AudioClipEntity::class,
        MidiClipEntity::class,
        ProjectVersionEntity::class,
        SyncOutboxEntity::class,
        LibraryItemEntity::class,
        PresetEntity::class,
        WaveformPeaksEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class StudioOneDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun trackDao(): TrackDao
    abstract fun audioClipDao(): AudioClipDao
    abstract fun midiClipDao(): MidiClipDao
    abstract fun projectVersionDao(): ProjectVersionDao
    abstract fun syncOutboxDao(): SyncOutboxDao
    abstract fun libraryDao(): LibraryDao
    abstract fun presetDao(): PresetDao
    abstract fun waveformPeaksDao(): WaveformPeaksDao

    companion object {
        private const val NAME = "studioone.db"

        fun build(context: Context): StudioOneDatabase =
            Room.databaseBuilder(context, StudioOneDatabase::class.java, NAME)
                .fallbackToDestructionDuringDevelopment() // replaced by migrations before 1.0
                .build()
    }
}
