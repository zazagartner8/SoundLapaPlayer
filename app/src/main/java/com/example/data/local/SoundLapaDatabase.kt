package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        TrackEntity::class,
        PlaylistEntity::class,
        PlaylistTrackCrossRef::class,
        ArtistSubscriptionEntity::class,
        LyricsCacheEntity::class,
    ],
    version = 6,
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

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS artist_subscriptions (
                        artistKey TEXT NOT NULL PRIMARY KEY,
                        artistName TEXT NOT NULL,
                        avatarUrl TEXT DEFAULT NULL,
                        soundCloudUrl TEXT DEFAULT NULL,
                        spotifyArtistId TEXT DEFAULT NULL,
                        subscribedAtMs INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS lyrics_cache (
                        trackKey TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        artist TEXT NOT NULL,
                        durationMs INTEGER NOT NULL DEFAULT 0,
                        syncedLrc TEXT DEFAULT NULL,
                        plainLyrics TEXT DEFAULT NULL,
                        fetchedAtMs INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "DELETE FROM tracks WHERE id IN ('track_terracotta_dusk','track_liquid_horizon','track_night_tram','track_neon_pawprint','track_amber_fog','track_velvet_static')",
                )
                db.execSQL(
                    "UPDATE tracks SET albumTitle = NULL WHERE albumTitle = 'SoundLapa Originals'",
                )
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE playlists ADD COLUMN isPinned INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE playlists ADD COLUMN pinnedAtMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE playlists ADD COLUMN sortMode TEXT NOT NULL DEFAULT 'CUSTOM'")
            }
        }

        fun getInstance(context: Context): SoundLapaDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    SoundLapaDatabase::class.java,
                    "soundlapa_cache.db",
                )
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
