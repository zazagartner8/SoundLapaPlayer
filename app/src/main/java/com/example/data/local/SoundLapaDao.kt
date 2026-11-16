package com.example.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SoundLapaDao {
    @Query("SELECT * FROM tracks ORDER BY createdAt DESC")
    fun observeAllTracks(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks")
    suspend fun getAllTracksOnce(): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE id = :id LIMIT 1")
    suspend fun getTrackById(id: String): TrackEntity?

    @Query("SELECT COUNT(*) FROM tracks WHERE sourceUri = :sourceUri")
    suspend fun countBySourceUri(sourceUri: String): Int

    @Query("SELECT COUNT(*) FROM tracks")
    suspend fun getTrackCount(): Int

    @Upsert
    suspend fun upsertTrack(track: TrackEntity)

    @Upsert
    suspend fun upsertTracks(tracks: List<TrackEntity>)

    @Query("UPDATE tracks SET isLiked = :liked, likeCount = :likeCount WHERE id = :id")
    suspend fun updateLike(id: String, liked: Boolean, likeCount: Int)

    @Query("UPDATE tracks SET playCount = playCount + 1 WHERE id = :id")
    suspend fun incrementPlayCount(id: String)

    @Query(
        "UPDATE tracks SET isCachedOffline = :isCached, cachedFilePath = :cachedPath, fileSizeBytes = :sizeBytes WHERE id = :id"
    )
    suspend fun updateCacheStatus(id: String, isCached: Boolean, cachedPath: String?, sizeBytes: Long)

    @Query(
        "UPDATE tracks SET isCachedOffline = 0, cachedFilePath = NULL, fileSizeBytes = 0 WHERE isUserUpload = 0 AND sourceUri IS NOT NULL"
    )
    suspend fun clearStreamedTracksCacheStatus()

    @Query("UPDATE tracks SET title = :title, artist = :artist, coverArtPath = :coverArtPath WHERE id = :id")
    suspend fun updateTrackMetadata(id: String, title: String, artist: String, coverArtPath: String?)

    @Query(
        "UPDATE tracks SET title = :title, artist = :artist, albumTitle = :albumTitle, " +
            "genre = :genre, coverArtPath = :coverArtPath WHERE id = :id"
    )
    suspend fun updateTrackFullMetadata(
        id: String,
        title: String,
        artist: String,
        albumTitle: String?,
        genre: String,
        coverArtPath: String?,
    )

    @Query("UPDATE tracks SET sourceUri = :sourceUri, durationMs = :durationMs WHERE id = :id")
    suspend fun updateTrackStream(id: String, sourceUri: String, durationMs: Long)

    @Query(
        "DELETE FROM tracks WHERE (id LIKE 'itunes_%' OR id LIKE 'deezer_%') " +
            "AND isLiked = 0 AND isCachedOffline = 0 " +
            "AND id NOT IN (SELECT trackId FROM playlist_tracks)"
    )
    suspend fun deleteUnreferencedPreviewTracks()

    @Query(
        "DELETE FROM tracks WHERE id IN ('track_terracotta_dusk','track_liquid_horizon','track_night_tram','track_neon_pawprint','track_amber_fog','track_velvet_static')"
    )
    suspend fun deleteStarterGeneratedTracks()

    @Query("UPDATE tracks SET albumTitle = NULL WHERE albumTitle = 'SoundLapa Originals'")
    suspend fun clearLegacyOriginalsAlbumTitle()

    @Query("DELETE FROM tracks WHERE id = :id")
    suspend fun deleteTrackById(id: String)

    // Playlists
    @Transaction
    @Query("SELECT * FROM playlists ORDER BY isPinned DESC, pinnedAtMs DESC, createdAt DESC")
    fun observePlaylistsWithTracks(): Flow<List<PlaylistWithTracks>>

    @Query("SELECT * FROM playlists ORDER BY isPinned DESC, pinnedAtMs DESC, createdAt DESC")
    fun observeAllPlaylists(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlist_tracks ORDER BY playlistId ASC, position ASC, addedAt ASC")
    fun observeOrderedPlaylistRefs(): Flow<List<PlaylistTrackCrossRef>>

    @Query("SELECT * FROM playlists ORDER BY isPinned DESC, pinnedAtMs DESC, createdAt DESC")
    suspend fun getAllPlaylistsOnce(): List<PlaylistEntity>

    @Query("SELECT * FROM playlist_tracks ORDER BY playlistId ASC, position ASC, addedAt ASC")
    suspend fun getAllPlaylistRefsOnce(): List<PlaylistTrackCrossRef>

    @Query("SELECT * FROM playlist_tracks WHERE playlistId = :playlistId ORDER BY position ASC, addedAt ASC")
    suspend fun getPlaylistRefsForPlaylistOnce(playlistId: String): List<PlaylistTrackCrossRef>

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun getMaxPlaylistTrackPosition(playlistId: String): Int

    @Upsert
    suspend fun upsertPlaylist(playlist: PlaylistEntity)

    @Query("UPDATE playlists SET name = :name, description = :description, coverArtPath = :coverArtPath WHERE id = :id")
    suspend fun updatePlaylistMetadata(id: String, name: String, description: String, coverArtPath: String?)

    @Query("UPDATE playlists SET isPinned = :isPinned, pinnedAtMs = :pinnedAtMs WHERE id = :id")
    suspend fun updatePlaylistPin(id: String, isPinned: Boolean, pinnedAtMs: Long)

    @Query("UPDATE playlists SET sortMode = :sortMode WHERE id = :id")
    suspend fun updatePlaylistSortMode(id: String, sortMode: String)

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun deletePlaylist(playlistId: String)

    @Query("DELETE FROM playlists")
    suspend fun clearAllPlaylists()

    @Query("UPDATE tracks SET isLiked = 0")
    suspend fun clearAllTrackLikes()

    @Upsert
    suspend fun addTrackToPlaylist(ref: PlaylistTrackCrossRef)

    @Query("UPDATE playlist_tracks SET position = :position WHERE playlistId = :playlistId AND trackId = :trackId")
    suspend fun updatePlaylistTrackPosition(playlistId: String, trackId: String, position: Int)

    @Transaction
    suspend fun replacePlaylistTrackOrder(playlistId: String, orderedRefs: List<PlaylistTrackCrossRef>) {
        orderedRefs.forEachIndexed { idx, ref ->
            updatePlaylistTrackPosition(playlistId, ref.trackId, idx)
        }
    }

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId AND trackId = :trackId")
    suspend fun removeTrackFromPlaylist(playlistId: String, trackId: String)

    // Artist Subscriptions
    @Query("SELECT * FROM artist_subscriptions ORDER BY subscribedAtMs DESC")
    fun observeArtistSubscriptions(): Flow<List<ArtistSubscriptionEntity>>

    @Query("SELECT * FROM artist_subscriptions ORDER BY subscribedAtMs DESC")
    suspend fun getArtistSubscriptionsOnce(): List<ArtistSubscriptionEntity>

    @Upsert
    suspend fun upsertArtistSubscription(subscription: ArtistSubscriptionEntity)

    @Query("DELETE FROM artist_subscriptions WHERE artistKey = :artistKey")
    suspend fun deleteArtistSubscription(artistKey: String)

    // Lyrics Cache
    @Query("SELECT * FROM lyrics_cache WHERE trackKey = :trackKey LIMIT 1")
    suspend fun getLyrics(trackKey: String): LyricsCacheEntity?

    @Upsert
    suspend fun upsertLyrics(lyrics: LyricsCacheEntity)
}
