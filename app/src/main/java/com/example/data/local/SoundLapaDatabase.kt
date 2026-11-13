package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [TrackEntity::class, PlaylistEntity::class, PlaylistTrackCrossRef::class],
    version = 4,
    exportSchema = false,
)
abstract class SoundLapaDatabase : RoomDatabase() {
    abstract fun soundLapaDao(): SoundLapaDao

    companion object {
        @Volatile
        private var INSTANCE: SoundLapaDatabase? = null

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN albumTitle TEXT DEFAULT NULL")
                db.execSQL("UPDATE tracks SET isLiked = 0 WHERE id LIKE 'track_%'")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE playlists ADD COLUMN coverArtPath TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE playlist_tracks ADD COLUMN position INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun getInstance(context: Context): SoundLapaDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    SoundLapaDatabase::class.java,
                    "soundlapa_cache.db",
                )
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
