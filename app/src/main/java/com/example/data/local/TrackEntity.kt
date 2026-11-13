package com.example.data.local

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Immutable
@Serializable
@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String,
    val genre: String,
    val durationMs: Long,
    val waveformJson: String,
    val cachedFilePath: String?,
    val sourceUri: String?,
    val coverArtPath: String?,
    val accentColorHex: Long,
    val secondaryColorHex: Long,
    val isUserUpload: Boolean,
    val isAutoImported: Boolean = false,
    val isCachedOffline: Boolean,
    val fileSizeBytes: Long,
    val playCount: Int = 0,
    val likeCount: Int = 0,
    val isLiked: Boolean = false,
    val description: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val albumTitle: String? = null,
) {
    fun waveformPeaks(): List<Float> = runCatching {
        Json.decodeFromString<List<Float>>(waveformJson)
    }.getOrElse { defaultWaveform() }

    companion object {
        fun encodeWaveform(peaks: List<Float>): String = Json.encodeToString(peaks)

        fun defaultWaveform(): List<Float> = List(64) { index ->
            val phase = index / 64f * Math.PI * 4
            (0.28f + 0.55f * kotlin.math.abs(kotlin.math.sin(phase)).toFloat()).coerceIn(0.12f, 1f)
        }
    }
}
