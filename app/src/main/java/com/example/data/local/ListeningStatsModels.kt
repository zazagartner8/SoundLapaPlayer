package com.example.data.local

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

enum class ListeningStatsPeriod(val days: Int) {
    WEEK(7),
    MONTH(30),
    YEAR(365);

    val windowMs: Long
        get() = TimeUnit.DAYS.toMillis(days.toLong())
}

/**
 * Daily-bucketed per-track listening record retained up to 365 days for Spotify-style statistics
 * across Week / Month / Year windows and cross-device Firestore sync.
 */
@Immutable
@Serializable
data class ListeningPlayEvent(
    val dayEpoch: Long,
    val trackId: String,
    val title: String,
    val artist: String,
    val genre: String = "",
    val coverArtPath: String? = null,
    val listenedMs: Long = 0L,
    val playCount: Int = 0,
    val lastPlayedAtMs: Long = 0L,
)

@Immutable
data class TopTrackStat(
    val trackId: String,
    val title: String,
    val artist: String,
    val coverArtPath: String?,
    val listenedMs: Long,
    val listenedMinutes: Int,
    val playCount: Int,
)

@Immutable
data class TopArtistStat(
    val artistName: String,
    val coverArtPath: String?,
    val listenedMs: Long,
    val listenedMinutes: Int,
    val playCount: Int,
    val trackCount: Int,
)

@Immutable
data class ListeningStatsSnapshot(
    val period: ListeningStatsPeriod,
    val totalListenedMs: Long,
    val totalMinutes: Int,
    val totalPlays: Int,
    val uniqueTracksCount: Int,
    val uniqueArtistsCount: Int,
    val favoriteArtist: TopArtistStat?,
    val favoriteTrack: TopTrackStat?,
    val topTracks: List<TopTrackStat>,
    val topArtists: List<TopArtistStat>,
)

object SoundLapUidFormatter {
    fun format(firebaseUid: String?): String {
        val clean = firebaseUid?.trim().orEmpty()
        if (clean.isEmpty()) return "SL-GUEST"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("soundlap_uid_v1:$clean".toByteArray(Charsets.UTF_8))
        val hex = digest.take(5).joinToString("") { "%02X".format(it) }
        return "SL-${hex.take(4)}-${hex.drop(4).take(4)}"
    }
}

object ListeningStatsAggregator {

    private const val ONE_DAY_MS = 86_400_000L

    fun dayEpochFor(timestampMs: Long): Long =
        (timestampMs.coerceAtLeast(0L)) / ONE_DAY_MS

    fun pruneOldEvents(
        events: List<ListeningPlayEvent>,
        nowMs: Long = System.currentTimeMillis(),
        retentionDays: Long = 370L,
    ): List<ListeningPlayEvent> {
        val minDayEpoch = dayEpochFor(nowMs) - retentionDays
        return events
            .filter { it.dayEpoch >= minDayEpoch && (it.listenedMs > 0L || it.playCount > 0) }
            .sortedByDescending { it.lastPlayedAtMs }
            .take(2_500)
    }

    fun mergeEvents(
        local: List<ListeningPlayEvent>,
        remote: List<ListeningPlayEvent>,
        nowMs: Long = System.currentTimeMillis(),
    ): List<ListeningPlayEvent> {
        val merged = LinkedHashMap<Pair<Long, String>, ListeningPlayEvent>(local.size + remote.size)
        for (ev in local + remote) {
            if (ev.trackId.isBlank()) continue
            val key = ev.dayEpoch to ev.trackId
            val existing = merged[key]
            if (existing == null) {
                merged[key] = ev
            } else {
                merged[key] = existing.copy(
                    title = existing.title.ifBlank { ev.title },
                    artist = existing.artist.ifBlank { ev.artist },
                    genre = existing.genre.ifBlank { ev.genre },
                    coverArtPath = existing.coverArtPath ?: ev.coverArtPath,
                    listenedMs = maxOf(existing.listenedMs, ev.listenedMs),
                    playCount = maxOf(existing.playCount, ev.playCount),
                    lastPlayedAtMs = maxOf(existing.lastPlayedAtMs, ev.lastPlayedAtMs),
                )
            }
        }
        return pruneOldEvents(merged.values.toList(), nowMs = nowMs)
    }

    fun compute(
        events: List<ListeningPlayEvent>,
        period: ListeningStatsPeriod,
        nowMs: Long = System.currentTimeMillis(),
        fallbackHistory: List<ListeningHistoryEntry> = emptyList(),
    ): ListeningStatsSnapshot {
        val cutoffDay = dayEpochFor(nowMs - period.windowMs)
        val filtered = events.filter {
            it.dayEpoch >= cutoffDay && (it.listenedMs > 0L || it.playCount > 0)
        }

        // If user has legacy listening history entries from earlier app versions inside the window
        // without detailed duration events yet, fold them in gently as fallback baselines.
        val cutoffMs = nowMs - period.windowMs
        val effectiveEvents = if (filtered.isNotEmpty()) {
            filtered
        } else {
            fallbackHistory
                .filter { it.lastPlayedAtMs >= cutoffMs }
                .map { entry ->
                    ListeningPlayEvent(
                        dayEpoch = dayEpochFor(entry.lastPlayedAtMs),
                        trackId = entry.trackId,
                        title = entry.title,
                        artist = entry.artist,
                        genre = entry.genre,
                        coverArtPath = null,
                        listenedMs = entry.playCount.coerceAtLeast(1) * 120_000L,
                        playCount = entry.playCount.coerceAtLeast(1),
                        lastPlayedAtMs = entry.lastPlayedAtMs,
                    )
                }
        }

        var totalMs = 0L
        var totalPlays = 0
        val trackBuckets = LinkedHashMap<String, MutableTrackBucket>()
        val artistBuckets = LinkedHashMap<String, MutableArtistBucket>()

        for (ev in effectiveEvents) {
            val ms = ev.listenedMs.coerceAtLeast(0L)
            val plays = ev.playCount.coerceAtLeast(0)
            totalMs += ms
            totalPlays += plays

            val tKey = "${ev.title.trim().lowercase()}::${ev.artist.trim().lowercase()}"
                .takeIf { it != "::" } ?: ev.trackId
            val tBucket = trackBuckets.getOrPut(tKey) {
                MutableTrackBucket(
                    trackId = ev.trackId,
                    title = ev.title.ifBlank { "Track" },
                    artist = ev.artist.ifBlank { "Artist" },
                    coverArtPath = ev.coverArtPath,
                )
            }
            if (tBucket.coverArtPath == null && ev.coverArtPath != null) {
                tBucket.coverArtPath = ev.coverArtPath
            }
            tBucket.listenedMs += ms
            tBucket.playCount += plays

            val primaryArtist = extractPrimaryArtist(ev.artist)
            val aKey = primaryArtist.lowercase()
            if (aKey.isNotEmpty()) {
                val aBucket = artistBuckets.getOrPut(aKey) {
                    MutableArtistBucket(
                        artistName = primaryArtist,
                        coverArtPath = ev.coverArtPath,
                    )
                }
                if (aBucket.coverArtPath == null && ev.coverArtPath != null) {
                    aBucket.coverArtPath = ev.coverArtPath
                }
                aBucket.listenedMs += ms
                aBucket.playCount += plays
                aBucket.trackKeys += tKey
            }
        }

        val topTracks = trackBuckets.values
            .map { b ->
                TopTrackStat(
                    trackId = b.trackId,
                    title = b.title,
                    artist = b.artist,
                    coverArtPath = b.coverArtPath,
                    listenedMs = b.listenedMs,
                    listenedMinutes = msToDisplayMinutes(b.listenedMs),
                    playCount = b.playCount,
                )
            }
            .sortedWith(
                compareByDescending<TopTrackStat> { it.listenedMs }
                    .thenByDescending { it.playCount },
            )
            .take(10)

        val topArtists = artistBuckets.values
            .map { b ->
                TopArtistStat(
                    artistName = b.artistName,
                    coverArtPath = b.coverArtPath,
                    listenedMs = b.listenedMs,
                    listenedMinutes = msToDisplayMinutes(b.listenedMs),
                    playCount = b.playCount,
                    trackCount = b.trackKeys.size,
                )
            }
            .sortedWith(
                compareByDescending<TopArtistStat> { it.listenedMs }
                    .thenByDescending { it.playCount },
            )
            .take(10)

        return ListeningStatsSnapshot(
            period = period,
            totalListenedMs = totalMs,
            totalMinutes = msToDisplayMinutes(totalMs),
            totalPlays = totalPlays,
            uniqueTracksCount = trackBuckets.size,
            uniqueArtistsCount = artistBuckets.size,
            favoriteArtist = topArtists.firstOrNull(),
            favoriteTrack = topTracks.firstOrNull(),
            topTracks = topTracks,
            topArtists = topArtists,
        )
    }

    private fun msToDisplayMinutes(ms: Long): Int {
        if (ms <= 0L) return 0
        val exactMin = ms / 60_000.0
        return if (exactMin < 1.0 && ms >= 10_000L) 1 else exactMin.roundToInt()
    }

    private fun extractPrimaryArtist(raw: String): String {
        val cleaned = raw.trim()
        if (cleaned.isEmpty()) return ""
        return cleaned
            .split(Regex("""\s*(?:feat\.?|ft\.?|&|,|×|x)\s*""", RegexOption.IGNORE_CASE), limit = 2)
            .firstOrNull()
            ?.trim()
            ?.ifBlank { cleaned }
            ?: cleaned
    }

    private class MutableTrackBucket(
        val trackId: String,
        val title: String,
        val artist: String,
        var coverArtPath: String?,
        var listenedMs: Long = 0L,
        var playCount: Int = 0,
    )

    private class MutableArtistBucket(
        val artistName: String,
        var coverArtPath: String?,
        var listenedMs: Long = 0L,
        var playCount: Int = 0,
        val trackKeys: HashSet<String> = HashSet(),
    )
}
