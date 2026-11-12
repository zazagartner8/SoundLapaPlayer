package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [TrackEntity::class, PlaylistEntity::class, PlaylistTrackCrossRef::class],
    version = 2,
    exportSchema = false,
)
abstract class SoundLapaDatabase : RoomDatabase() {
    abstract fun soundLapaDao(): SoundLapaDao

    companion object {
        @Volatile
        private var INSTANCE: SoundLapaDatabase? = null

        fun getInstance(context: Context): SoundLapaDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    SoundLapaDatabase::class.java,
                    "soundlapa_cache.db",
                )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
