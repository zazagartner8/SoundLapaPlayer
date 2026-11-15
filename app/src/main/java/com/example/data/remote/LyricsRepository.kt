package com.example.data.remote

import androidx.compose.runtime.Immutable
import com.example.data.local.LyricsCacheEntity
import com.example.data.local.SoundLapaDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.math.abs

@Immutable
data class LyricsLine(
    val timeMs: Long,
    val text: String,
)

@Immutable
data class TrackLyricsResult(
    val syncedLines: List<LyricsLine>,
    val plainText: String?,
    val isSynced: Boolean,
    val source: String,
)

class LyricsRepository(
    private val dao: SoundLapaDao,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    suspend fun getTrackLyrics(
        artist: String,
        title: String,
        durationMs: Long,
    ): TrackLyricsResult? = withContext(Dispatchers.IO) {
        val cleanTitle = cleanTrackTitle(title)
        val cleanArtist = cleanArtistName(artist)
        if (cleanTitle.isBlank()) return@withContext null

        val cacheKey = "${normalizeKey(cleanArtist)}|${normalizeKey(cleanTitle)}"
        val cached = runCatching { dao.getLyrics(cacheKey) }.getOrNull()
        if (cached != null && (!cached.syncedLrc.isNullOrBlank() || !cached.plainLyrics.isNullOrBlank())) {
            val lines = cached.syncedLrc?.let(::parseLrc).orEmpty()
            return@withContext TrackLyricsResult(
                syncedLines = lines,
                plainText = cached.plainLyrics,
                isSynced = lines.isNotEmpty(),
                source = "LrcLib (cached)",
            )
        }

        // 1. Exact endpoint /api/get
        var remote = if (cleanArtist.isNotBlank()) {
            fetchLrcGet(cleanArtist, cleanTitle, durationMs)
        } else {
            null
        }

        // 2. Fallback /api/search if exact lookup had no synced/plain match
        if (remote == null || (remote.first.isNullOrBlank() && remote.second.isNullOrBlank())) {
            remote = fetchLrcSearch(cleanArtist, cleanTitle, durationMs)
        }

        // 3. Retry with primary title only if featured/parenthetical tags obstructed match
        if ((remote == null || (remote.first.isNullOrBlank() && remote.second.isNullOrBlank())) &&
            title != cleanTitle
        ) {
            remote = fetchLrcSearch(cleanArtist, cleanTitle.substringBefore("(").trim(), durationMs)
        }

        val (syncedLrc, plainLyrics) = remote ?: return@withContext null
        if (syncedLrc.isNullOrBlank() && plainLyrics.isNullOrBlank()) return@withContext null

        runCatching {
            dao.upsertLyrics(
                LyricsCacheEntity(
                    trackKey = cacheKey,
                    title = cleanTitle,
                    artist = cleanArtist,
                    durationMs = durationMs,
                    syncedLrc = syncedLrc,
                    plainLyrics = plainLyrics,
                    fetchedAtMs = System.currentTimeMillis(),
                ),
            )
        }

        val parsedLines = syncedLrc?.let(::parseLrc).orEmpty()
        TrackLyricsResult(
            syncedLines = parsedLines,
            plainText = plainLyrics,
            isSynced = parsedLines.isNotEmpty(),
            source = "LRCLIB",
        )
    }

    private fun fetchLrcGet(
        artist: String,
        title: String,
        durationMs: Long,
    ): Pair<String?, String?>? {
        val artistEnc = URLEncoder.encode(artist, "UTF-8")
        val titleEnc = URLEncoder.encode(title, "UTF-8")
        val durQuery = if (durationMs >= 15_000L) "&duration=${durationMs / 1000L}" else ""
        val url = "https://lrclib.net/api/get?artist_name=$artistEnc&track_name=$titleEnc$durQuery"
        val body = executeGet(url) ?: return null
        return runCatching {
            val obj = json.parseToJsonElement(body).jsonObject
            val synced = obj["syncedLyrics"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            val plain = obj["plainLyrics"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            if (synced == null && plain == null) null else synced to plain
        }.getOrNull()
    }

    private fun fetchLrcSearch(
        artist: String,
        title: String,
        durationMs: Long,
    ): Pair<String?, String?>? {
        val q = listOf(artist, title).filter { it.isNotBlank() }.joinToString(" ").trim()
        if (q.isBlank()) return null
        val qEnc = URLEncoder.encode(q, "UTF-8")
        val url = "https://lrclib.net/api/search?q=$qEnc"
        val body = executeGet(url) ?: return null
        return runCatching {
            val arr = json.parseToJsonElement(body) as? JsonArray ?: return@runCatching null
            val normTitle = normalizeKey(title)
            val normArtist = normalizeKey(artist)
            val best = arr.mapNotNull { it as? JsonObject }.maxByOrNull { item ->
                val itemTitle = normalizeKey(item["trackName"]?.jsonPrimitive?.contentOrNull.orEmpty())
                val itemArtist = normalizeKey(item["artistName"]?.jsonPrimitive?.contentOrNull.orEmpty())
                val hasSynced = !item["syncedLyrics"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()
                val hasPlain = !item["plainLyrics"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()
                if (!hasSynced && !hasPlain) return@maxByOrNull Int.MIN_VALUE

                var score = 0
                if (hasSynced) score += 200
                if (itemTitle == normTitle) score += 150
                else if (normTitle.length >= 3 && (itemTitle.contains(normTitle) || normTitle.contains(itemTitle))) score += 80
                if (normArtist.isNotBlank()) {
                    if (itemArtist == normArtist) score += 120
                    else if (itemArtist.contains(normArtist) || normArtist.contains(itemArtist)) score += 60
                }
                val itemDurSec = item["duration"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.toLong() ?: 0L
                if (durationMs >= 15_000L && itemDurSec > 0L) {
                    val deltaSec = abs(itemDurSec - durationMs / 1000L)
                    if (deltaSec <= 5L) score += 80
                    else if (deltaSec <= 15L) score += 30
                    else if (deltaSec > 60L) score -= 120
                }
                score
            } ?: return@runCatching null

            val synced = best["syncedLyrics"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            val plain = best["plainLyrics"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            if (synced == null && plain == null) null else synced to plain
        }.getOrNull()
    }

    private fun executeGet(url: String): String? {
        return runCatching {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "SoundLap/3.3 (Android)")
                .header("Accept", "application/json")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                resp.body?.string()
            }
        }.getOrNull()
    }

    private fun cleanArtistName(raw: String): String {
        return raw.trim()
            .split(Regex("\\s*(?:,|&|feat\\.|ft\\.|featuring|x|×|при\\s+уч\\.)\\s*", RegexOption.IGNORE_CASE))
            .firstOrNull()
            ?.trim()
            ?: raw.trim()
    }

    private fun cleanTrackTitle(raw: String): String {
        return raw.trim()
            .replace(Regex("\\s*[(\\[](?:feat\\.?|ft\\.?|featuring|official|audio|video|prod\\.?).*?[)\\]]", RegexOption.IGNORE_CASE), "")
            .trim()
            .ifBlank { raw.trim() }
    }

    private fun normalizeKey(raw: String): String =
        raw.lowercase().replace(Regex("[^\\p{L}\\p{Nd}]"), "")

    companion object {
        private val LRC_TAG_REGEX = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")

        fun parseLrc(rawLrc: String): List<LyricsLine> {
            val out = mutableListOf<LyricsLine>()
            for (rawLine in rawLrc.lineSequence()) {
                val matches = LRC_TAG_REGEX.findAll(rawLine).toList()
                if (matches.isEmpty()) continue
                val text = rawLine.replace(LRC_TAG_REGEX, "").trim()
                if (text.isBlank()) continue
                for (m in matches) {
                    val min = m.groupValues[1].toLongOrNull() ?: 0L
                    val sec = m.groupValues[2].toLongOrNull() ?: 0L
                    val fracStr = m.groupValues[3]
                    val millis = when (fracStr.length) {
                        0 -> 0L
                        1 -> (fracStr.toLongOrNull() ?: 0L) * 100L
                        2 -> (fracStr.toLongOrNull() ?: 0L) * 10L
                        else -> fracStr.take(3).toLongOrNull() ?: 0L
                    }
                    val totalMs = (min * 60_000L) + (sec * 1_000L) + millis
                    out += LyricsLine(timeMs = totalMs, text = text)
                }
            }
            return out.sortedBy { it.timeMs }
        }
    }
}
