package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTrack(track: TrackEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTracks(tracks: List<TrackEntity>)

    @Query("UPDATE tracks SET isLiked = :liked, likeCount = :likeCount WHERE id = :id")
    suspend fun updateLike(id: String, liked: Boolean, likeCount: Int)

    @Query("UPDATE tracks SET playCount = playCount + 1 WHERE id = :id")
    suspend fun incrementPlayCount(id: String)

    @Query(
        "UPDATE tracks SET isCachedOffline = :isCached, cachedFilePath = :cachedPath, fileSizeBytes = :sizeBytes WHERE id = :id"
    )
    suspend fun updateCacheStatus(id: String, isCached: Boolean, cachedPath: String?, sizeBytes: Long)

    @Query("UPDATE tracks SET title = :title, artist = :artist, coverArtPath = :coverArtPath WHERE id = :id")
    suspend fun updateTrackMetadata(id: String, title: String, artist: String, coverArtPath: String?)

    @Query("UPDATE tracks SET sourceUri = :sourceUri, durationMs = :durationMs WHERE id = :id")
    suspend fun updateTrackStream(id: String, sourceUri: String, durationMs: Long)

    @Query(
        "DELETE FROM tracks WHERE (id LIKE 'itunes_%' OR id LIKE 'deezer_%') " +
            "AND isLiked = 0 AND isCachedOffline = 0 " +
            "AND id NOT IN (SELECT trackId FROM playlist_tracks)"
    )
    suspend fun deleteUnreferencedPreviewTracks()

    @Query("DELETE FROM tracks WHERE id = :id")
    suspend fun deleteTrackById(id: String)

    // Playlists
    @Transaction
    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    fun observePlaylistsWithTracks(): Flow<List<PlaylistWithTracks>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlaylist(playlist: PlaylistEntity)

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun deletePlaylist(playlistId: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addTrackToPlaylist(ref: PlaylistTrackCrossRef)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId AND trackId = :trackId")
    suspend fun removeTrackFromPlaylist(playlistId: String, trackId: String)
}
