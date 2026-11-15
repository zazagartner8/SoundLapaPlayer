package com.example.data.local

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.PrimaryKey

@Immutable
@Entity(tableName = "artist_subscriptions")
data class ArtistSubscriptionEntity(
    @PrimaryKey val artistKey: String,
    val artistName: String,
    val avatarUrl: String? = null,
    val soundCloudUrl: String? = null,
    val spotifyArtistId: String? = null,
    val subscribedAtMs: Long = System.currentTimeMillis(),
)

@Immutable
@Entity(tableName = "lyrics_cache")
data class LyricsCacheEntity(
    @PrimaryKey val trackKey: String,
    val title: String,
    val artist: String,
    val durationMs: Long = 0L,
    val syncedLrc: String? = null,
    val plainLyrics: String? = null,
    val fetchedAtMs: Long = System.currentTimeMillis(),
)
