package com.example.data.remote

import androidx.compose.runtime.Immutable
import com.example.data.local.ExplicitContentDetector
import com.example.data.local.LyricsCacheEntity
import com.example.data.local.SoundLapaDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo
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
    val isExplicit: Boolean = false,
)

private data class PlatformLyricsCandidate(
    val syncedLrc: String?,
    val syncedLines: List<LyricsLine>,
    val plainLyrics: String?,
    val source: String,
)

/**
 * Multi-platform verified lyrics resolver without Genius:
 * Spicy Lyrics, Spotify/LRCLIB, Apple Music, YouTube Music (captions), SoundCloud, and Amazon Music.
 */
class LyricsRepository(
    private val dao: SoundLapaDao,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(7, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    suspend fun getTrackLyrics(
        artist: String,
        title: String,
        durationMs: Long,
        trackId: String? = null,
        sourceUri: String? = null,
    ): TrackLyricsResult? = withContext(Dispatchers.IO) {
        val (extractedArtist, extractedTitle) = splitEmbeddedArtistTitle(artist, title)
        val cleanTitle = cleanTrackTitle(extractedTitle)
        val cleanArtist = cleanArtistName(extractedArtist)
        if (cleanTitle.isBlank()) return@withContext null

        // v3 cache key invalidates legacy Genius / unverified lyrics cache entries
        val cacheKey = "v3|${normalizeKey(cleanArtist)}|${normalizeKey(cleanTitle)}"
        val cached = runCatching { dao.getLyrics(cacheKey) }.getOrNull()
        if (cached != null &&
            (!cached.syncedLrc.isNullOrBlank() || !cached.plainLyrics.isNullOrBlank()) &&
            isVerifiedCandidateMatch(
                targetArtist = cleanArtist,
                targetTitle = cleanTitle,
                expectedDurationMs = durationMs,
                candArtist = cached.artist,
                candTitle = cached.title,
                candDurationMs = cached.durationMs,
            )
        ) {
            val lines = cached.syncedLrc?.let(::parseLrc).orEmpty()
            val explicit = ExplicitContentDetector.isLyricsExplicit(
                cached.plainLyrics ?: lines.joinToString("\n") { it.text },
            )
            return@withContext TrackLyricsResult(
                syncedLines = lines,
                plainText = cached.plainLyrics,
                isSynced = lines.isNotEmpty(),
                source = "Cached",
                isExplicit = explicit,
            )
        }

        // Phase 1: Parallel fast lookups across Spicy Lyrics, Spotify/LRCLIB, and Apple Music
        val primaryCandidate = coroutineScope {
            val directSpId = extractDirectSpotifyTrackId(trackId, sourceUri)
            val spicyDeferred = async {
                withTimeoutOrNull(5200L) {
                    val spId = directSpId ?: runCatching {
                        SpotifyCatalogClient.findSpotifyTrackId(
                            artist = cleanArtist,
                            title = cleanTitle,
                            durationMs = durationMs,
                            sourceUri = sourceUri,
                        )
                    }.getOrNull()
                    if (!spId.isNullOrBlank()) fetchSpicyLyrics(spId, durationMs) else null
                }
            }
            val spotifyLrcDeferred = async {
                withTimeoutOrNull(4200L) {
                    fetchLrcLibVerified(
                        cleanArtist = cleanArtist,
                        fullArtist = extractedArtist,
                        cleanTitle = cleanTitle,
                        rawTitle = title,
                        durationMs = durationMs,
                    )
                }
            }
            val appleDeferred = async {
                withTimeoutOrNull(4500L) {
                    fetchAppleMusicMatchedLyrics(cleanArtist, cleanTitle, durationMs)
                }
            }

            val spicy = spicyDeferred.await()
            if (spicy != null && spicy.syncedLines.isNotEmpty()) {
                return@coroutineScope spicy
            }
            val spotifyLrc = spotifyLrcDeferred.await()
            if (spotifyLrc != null && spotifyLrc.syncedLines.isNotEmpty()) {
                return@coroutineScope spotifyLrc
            }
            val apple = appleDeferred.await()
            if (apple != null && apple.syncedLines.isNotEmpty()) {
                return@coroutineScope apple
            }
            spicy ?: spotifyLrc ?: apple
        }

        if (primaryCandidate != null && primaryCandidate.syncedLines.isNotEmpty()) {
            return@withContext cacheAndBuildResult(cacheKey, cleanArtist, cleanTitle, durationMs, primaryCandidate)
        }

        // Phase 2: YouTube Music captions, SoundCloud metadata/canonical match, and Amazon Music canonical match
        val secondaryCandidate = coroutineScope {
            val ytCaptionsDeferred = async {
                withTimeoutOrNull(5200L) {
                    fetchYouTubeMusicCaptionsLyrics(cleanArtist, cleanTitle, durationMs, sourceUri)
                }
            }
            val scDeferred = async {
                withTimeoutOrNull(4500L) {
                    fetchSoundCloudMatchedLyrics(cleanArtist, cleanTitle, durationMs)
                }
            }
            val amazonDeferred = async {
                withTimeoutOrNull(4500L) {
                    fetchAmazonMusicMatchedLyrics(cleanArtist, cleanTitle, durationMs)
                }
            }

            val yt = ytCaptionsDeferred.await()
            if (yt != null && yt.syncedLines.isNotEmpty()) return@coroutineScope yt
            val sc = scDeferred.await()
            if (sc != null && sc.syncedLines.isNotEmpty()) return@coroutineScope sc
            val amz = amazonDeferred.await()
            if (amz != null && amz.syncedLines.isNotEmpty()) return@coroutineScope amz

            primaryCandidate ?: yt ?: sc ?: amz
        } ?: return@withContext null

        cacheAndBuildResult(
            cacheKey = cacheKey,
            artist = cleanArtist,
            title = cleanTitle,
            durationMs = durationMs,
            candidate = secondaryCandidate,
        )
    }

    private suspend fun cacheAndBuildResult(
        cacheKey: String,
        artist: String,
        title: String,
        durationMs: Long,
        candidate: PlatformLyricsCandidate,
    ): TrackLyricsResult {
        val syncedLrcToStore = candidate.syncedLrc ?: serializeLinesToLrc(candidate.syncedLines)
        val plainToStore = candidate.plainLyrics ?: candidate.syncedLines
            .joinToString("\n") { it.text }
            .takeIf { it.isNotBlank() }

        runCatching {
            dao.upsertLyrics(
                LyricsCacheEntity(
                    trackKey = cacheKey,
                    title = title,
                    artist = artist,
                    durationMs = durationMs,
                    syncedLrc = syncedLrcToStore,
                    plainLyrics = plainToStore,
                    fetchedAtMs = System.currentTimeMillis(),
                ),
            )
        }

        val explicit = ExplicitContentDetector.isLyricsExplicit(plainToStore)
        return TrackLyricsResult(
            syncedLines = candidate.syncedLines,
            plainText = plainToStore,
            isSynced = candidate.syncedLines.isNotEmpty(),
            source = candidate.source,
            isExplicit = explicit,
        )
    }

    private fun extractDirectSpotifyTrackId(trackId: String?, sourceUri: String?): String? {
        if (!trackId.isNullOrBlank() && trackId.startsWith("sp_") && !trackId.startsWith("sp_emb_")) {
            val candidate = trackId.removePrefix("sp_").substringBefore("_")
            if (SPOTIFY_22_ID_REGEX.matches(candidate)) return candidate
        }
        if (!sourceUri.isNullOrBlank()) {
            val m = Regex("open\\.spotify\\.com/(?:intl-[a-z]{2}/)?track/([A-Za-z0-9]{22})").find(sourceUri)
            if (m != null) return m.groupValues[1]
            val u = Regex("spotify:track:([A-Za-z0-9]{22})").find(sourceUri)
            if (u != null) return u.groupValues[1]
        }
        return null
    }

    private fun fetchSpicyLyrics(spotifyId: String, durationMs: Long): PlatformLyricsCandidate? {
        val url = "https://api.spicylyrics.org/v1/lyrics/$spotifyId"
        val body = executeGet(url) ?: return null
        val parsed = parseSpicyLyricsJson(body, durationMs) ?: return null
        val (lines, plain) = parsed
        if (lines.isEmpty() && plain.isNullOrBlank()) return null
        return PlatformLyricsCandidate(
            syncedLrc = serializeLinesToLrc(lines),
            syncedLines = lines,
            plainLyrics = plain ?: lines.joinToString("\n") { it.text }.takeIf { it.isNotBlank() },
            source = "Spicy Lyrics",
        )
    }

    private fun fetchLrcLibVerified(
        cleanArtist: String,
        fullArtist: String,
        cleanTitle: String,
        rawTitle: String,
        durationMs: Long,
    ): PlatformLyricsCandidate? {
        var match = if (cleanArtist.isNotBlank()) {
            fetchLrcGet(cleanArtist, cleanTitle, durationMs, sourceLabel = "Spotify")
        } else {
            null
        }
        if (match == null && fullArtist.isNotBlank() && !fullArtist.equals(cleanArtist, true)) {
            match = fetchLrcGet(fullArtist, cleanTitle, durationMs, sourceLabel = "Spotify")
        }
        if (match == null) {
            match = fetchLrcSearch(cleanArtist, cleanTitle, durationMs, sourceLabel = "Spotify")
        }
        if (match == null && rawTitle != cleanTitle) {
            val baseTitle = cleanTitle.substringBefore("(").trim()
            if (baseTitle.length >= 2) {
                match = fetchLrcSearch(cleanArtist, baseTitle, durationMs, sourceLabel = "Spotify")
            }
        }
        return match
    }

    private fun fetchAppleMusicMatchedLyrics(
        cleanArtist: String,
        cleanTitle: String,
        durationMs: Long,
    ): PlatformLyricsCandidate? {
        val q = listOf(cleanArtist, cleanTitle).filter { it.isNotBlank() }.joinToString(" ").trim()
        if (q.isBlank()) return null
        val enc = runCatching { URLEncoder.encode(q, "UTF-8") }.getOrNull() ?: return null
        val itunesBody = executeGet("https://itunes.apple.com/search?term=$enc&entity=song&limit=6") ?: return null
        val canonical = runCatching {
            val results = json.parseToJsonElement(itunesBody).jsonObject["results"] as? JsonArray
                ?: return@runCatching null
            results.mapNotNull { it as? JsonObject }.firstNotNullOfOrNull { item ->
                val tName = item["trackName"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val aName = item["artistName"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val itemDur = item["trackTimeMillis"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
                if (isVerifiedCandidateMatch(cleanArtist, cleanTitle, durationMs, aName, tName, itemDur)) {
                    Triple(aName, tName, itemDur)
                } else {
                    null
                }
            }
        }.getOrNull() ?: return null

        val (canonArtist, canonTitle, canonDur) = canonical
        return fetchLrcGet(canonArtist, canonTitle, canonDur, sourceLabel = "Apple Music")
            ?: fetchLrcSearch(canonArtist, canonTitle, canonDur, sourceLabel = "Apple Music")
    }

    private fun fetchYouTubeMusicCaptionsLyrics(
        cleanArtist: String,
        cleanTitle: String,
        durationMs: Long,
        sourceUri: String?,
    ): PlatformLyricsCandidate? {
        FullStreamResolver.ensureInitialized()
        val pageUrl = if (!sourceUri.isNullOrBlank() &&
            (sourceUri.contains("youtube.com/watch", true) || sourceUri.contains("music.youtube.com/watch", true))
        ) {
            sourceUri
        } else {
            val q = listOf(cleanArtist, cleanTitle).filter { it.isNotBlank() }.joinToString(" ").trim()
            if (q.isBlank()) return null
            val candidates = runCatching { FullStreamResolver.searchYouTubeMusic(q, limit = 4) }
                .getOrDefault(emptyList())
            val best = FullStreamResolver.pickBestTrackMatch(candidates, cleanArtist, cleanTitle, durationMs)
            best?.takeIf {
                isVerifiedCandidateMatch(cleanArtist, cleanTitle, durationMs, it.artist, it.title, it.durationMs)
            }?.sourceUri
        } ?: return null

        val info = runCatching { StreamInfo.getInfo(ServiceList.YouTube, pageUrl) }.getOrNull() ?: return null
        val subtitles = info.subtitles.filter { !it.content.isNullOrBlank() && !it.isAutoGenerated }
            .ifEmpty { info.subtitles.filter { !it.content.isNullOrBlank() } }
        val chosen = subtitles.firstOrNull { sub ->
            val tag = sub.locale.language.lowercase()
            tag == "ru" || tag == "en"
        } ?: subtitles.firstOrNull() ?: return null

        val subUrl = chosen.content ?: return null
        val xmlOrSrt = executeGet(subUrl, accept = "text/xml,text/plain,*/*") ?: return null
        val parsedLines = parseTimedTextOrSrt(xmlOrSrt)
        if (parsedLines.size < 4) return null
        val plain = parsedLines.joinToString("\n") { it.text }.takeIf { it.isNotBlank() } ?: return null
        return PlatformLyricsCandidate(
            syncedLrc = serializeLinesToLrc(parsedLines),
            syncedLines = parsedLines,
            plainLyrics = plain,
            source = "YouTube Music",
        )
    }

    private fun fetchSoundCloudMatchedLyrics(
        cleanArtist: String,
        cleanTitle: String,
        durationMs: Long,
    ): PlatformLyricsCandidate? {
        val q = listOf(cleanArtist, cleanTitle).filter { it.isNotBlank() }.joinToString(" ").trim()
        if (q.isBlank()) return null
        val scMatches = runCatching { FullStreamResolver.searchSoundCloud(q, limit = 5) }.getOrDefault(emptyList())
        val best = FullStreamResolver.pickBestTrackMatch(scMatches, cleanArtist, cleanTitle, durationMs)
            ?: return null
        val candCleanTitle = cleanTrackTitle(best.title)
        val candCleanArtist = cleanArtistName(best.artist)
        if (!isVerifiedCandidateMatch(cleanArtist, cleanTitle, durationMs, candCleanArtist, candCleanTitle, best.durationMs)) {
            return null
        }
        return fetchLrcGet(candCleanArtist, candCleanTitle, best.durationMs, sourceLabel = "SoundCloud")
            ?: fetchLrcSearch(candCleanArtist, candCleanTitle, best.durationMs, sourceLabel = "SoundCloud")
    }

    private fun fetchAmazonMusicMatchedLyrics(
        cleanArtist: String,
        cleanTitle: String,
        durationMs: Long,
    ): PlatformLyricsCandidate? {
        val q = listOf(cleanArtist, cleanTitle).filter { it.isNotBlank() }.joinToString(" ").trim()
        if (q.isBlank()) return null
        val enc = runCatching { URLEncoder.encode(q, "UTF-8") }.getOrNull() ?: return null
        val dzBody = executeGet("https://api.deezer.com/search?q=$enc&limit=6") ?: return null
        val canonical = runCatching {
            val data = json.parseToJsonElement(dzBody).jsonObject["data"] as? JsonArray ?: return@runCatching null
            data.mapNotNull { it as? JsonObject }.firstNotNullOfOrNull { obj ->
                val tName = obj["title_short"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?: obj["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val aName = (obj["artist"] as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val itemDur = (obj["duration"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L) * 1000L
                if (isVerifiedCandidateMatch(cleanArtist, cleanTitle, durationMs, aName, tName, itemDur)) {
                    Triple(aName, tName, itemDur)
                } else {
                    null
                }
            }
        }.getOrNull() ?: return null

        val (canonArtist, canonTitle, canonDur) = canonical
        return fetchLrcGet(canonArtist, canonTitle, canonDur, sourceLabel = "Amazon Music")
            ?: fetchLrcSearch(canonArtist, canonTitle, canonDur, sourceLabel = "Amazon Music")
    }

    private fun parseTimedTextOrSrt(raw: String): List<LyricsLine> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return emptyList()
        val out = mutableListOf<LyricsLine>()
        // YouTube timedtext XML: <text start="12.34" dur="2.1">Line</text>
        val xmlRegex = Regex(
            """<text[^>]+start=["']([0-9.]+)["'][^>]*>(.*?)</text>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        xmlRegex.findAll(trimmed).forEach { m ->
            val sec = m.groupValues[1].toDoubleOrNull() ?: return@forEach
            val cleanText = decodeSubtitleText(m.groupValues[2])
            if (cleanText.isNotBlank() && !isSubtitleStageDirection(cleanText)) {
                out += LyricsLine(timeMs = (sec * 1000.0).toLong().coerceAtLeast(0L), text = cleanText)
            }
        }
        if (out.isNotEmpty()) return out.sortedBy { it.timeMs }

        // VTT / SRT cues: 00:01:02.340 --> 00:01:05.000
        val cueRegex = Regex(
            """(?:(\d{1,2}):)?(\d{2}):(\d{2})[.,](\d{3})\s*-->\s*(?:(\d{1,2}):)?(\d{2}):(\d{2})[.,](\d{3})[^\r\n]*\r?\n(.*?)(?=\r?\n\r?\n|\z)""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )
        cueRegex.findAll(trimmed).forEach { m ->
            val hrs = m.groupValues[1].toLongOrNull() ?: 0L
            val mins = m.groupValues[2].toLongOrNull() ?: 0L
            val secs = m.groupValues[3].toLongOrNull() ?: 0L
            val ms = m.groupValues[4].toLongOrNull() ?: 0L
            val startMs = ((hrs * 3600L + mins * 60L + secs) * 1000L) + ms
            val text = decodeSubtitleText(m.groupValues[9])
            if (text.isNotBlank() && !isSubtitleStageDirection(text)) {
                out += LyricsLine(timeMs = startMs, text = text)
            }
        }
        return out.sortedBy { it.timeMs }
    }

    private fun decodeSubtitleText(raw: String): String =
        raw.replace(Regex("<[^>]+>"), " ")
            .replace("&amp;", "&")
            .replace("&#39;", "'")
            .replace("&#x27;", "'")
            .replace("&quot;", "\"")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun isSubtitleStageDirection(text: String): Boolean {
        val lower = text.trim().lowercase()
        return lower == "[music]" || lower == "[applause]" || lower == "[музыка]" ||
            lower == "♪" || lower == "♪♪" || (lower.startsWith("[") && lower.endsWith("]"))
    }

    private fun fetchLrcGet(
        artist: String,
        title: String,
        durationMs: Long,
        sourceLabel: String = "Spotify",
    ): PlatformLyricsCandidate? {
        val artistEnc = URLEncoder.encode(artist, "UTF-8")
        val titleEnc = URLEncoder.encode(title, "UTF-8")
        val durQuery = if (durationMs >= 15_000L) "&duration=${durationMs / 1000L}" else ""
        val url = "https://lrclib.net/api/get?artist_name=$artistEnc&track_name=$titleEnc$durQuery"
        val body = executeGet(url) ?: return null
        return runCatching {
            val obj = json.parseToJsonElement(body).jsonObject
            val itemTitle = obj["trackName"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val itemArtist = obj["artistName"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val itemDurSec = obj["duration"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.toLong() ?: 0L
            if (itemTitle.isNotBlank() &&
                !isVerifiedCandidateMatch(artist, title, durationMs, itemArtist, itemTitle, itemDurSec * 1000L)
            ) {
                return@runCatching null
            }
            val synced = obj["syncedLyrics"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            val plain = obj["plainLyrics"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            if (synced == null && plain == null) {
                null
            } else {
                val lines = synced?.let(::parseLrc).orEmpty()
                PlatformLyricsCandidate(
                    syncedLrc = synced,
                    syncedLines = lines,
                    plainLyrics = plain,
                    source = sourceLabel,
                )
            }
        }.getOrNull()
    }

    private fun fetchLrcSearch(
        artist: String,
        title: String,
        durationMs: Long,
        sourceLabel: String = "Spotify",
    ): PlatformLyricsCandidate? {
        val q = listOf(artist, title).filter { it.isNotBlank() }.joinToString(" ").trim()
        if (q.isBlank()) return null
        val qEnc = URLEncoder.encode(q, "UTF-8")
        val url = "https://lrclib.net/api/search?q=$qEnc"
        val body = executeGet(url) ?: return null
        return runCatching {
            val arr = json.parseToJsonElement(body) as? JsonArray ?: return@runCatching null
            val best = arr.mapNotNull { it as? JsonObject }
                .mapNotNull { item ->
                    val itemTitle = item["trackName"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val itemArtist = item["artistName"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val itemDurMs = (item["duration"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.toLong() ?: 0L) * 1000L
                    val hasSynced = !item["syncedLyrics"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()
                    val hasPlain = !item["plainLyrics"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()
                    if (!hasSynced && !hasPlain) return@mapNotNull null

                    val score = scoreLyricsCandidate(
                        targetArtist = artist,
                        targetTitle = title,
                        expectedDurationMs = durationMs,
                        candArtist = itemArtist,
                        candTitle = itemTitle,
                        candDurationMs = itemDurMs,
                        hasSynced = hasSynced,
                    )
                    if (score == Int.MIN_VALUE) null else item to score
                }
                .maxByOrNull { it.second }
                ?.first ?: return@runCatching null

            val synced = best["syncedLyrics"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            val plain = best["plainLyrics"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            if (synced == null && plain == null) {
                null
            } else {
                val lines = synced?.let(::parseLrc).orEmpty()
                PlatformLyricsCandidate(
                    syncedLrc = synced,
                    syncedLines = lines,
                    plainLyrics = plain,
                    source = sourceLabel,
                )
            }
        }.getOrNull()
    }

    private fun executeGet(url: String, accept: String = "application/json"): String? {
        return runCatching {
            val req = Request.Builder()
                .url(url)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) SoundLap/3.4 Chrome/131.0.0.0 Mobile Safari/537.36",
                )
                .header("Accept", accept)
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                resp.body?.string()
            }
        }.getOrNull()
    }

    private fun splitEmbeddedArtistTitle(artist: String, title: String): Pair<String, String> {
        val cleanArtist = artist.trim()
        val cleanTitle = title.trim()
        val sepMatch = Regex("""^(.+?)\s+[-—–]\s+(.+)$""").find(cleanTitle)
        if (sepMatch != null &&
            (cleanArtist.isBlank() ||
                cleanArtist.equals("SoundLapa", true) ||
                cleanArtist.equals("SoundLap", true) ||
                cleanArtist.equals("YouTube Music", true) ||
                cleanArtist.equals("SoundCloud", true))
        ) {
            return sepMatch.groupValues[1].trim() to sepMatch.groupValues[2].trim()
        }
        return cleanArtist to cleanTitle
    }

    private fun cleanArtistName(raw: String): String {
        return raw.trim()
            .replace(Regex("\\s*-\\s*Topic$", RegexOption.IGNORE_CASE), "")
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

    companion object {
        private val LRC_TAG_REGEX = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")
        private val SPOTIFY_22_ID_REGEX = Regex("^[A-Za-z0-9]{22}$")
        private val lenientJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        private fun normalizeKey(raw: String): String =
            raw.lowercase().replace('ё', 'е').replace(Regex("[^\\p{L}\\p{Nd}]"), "")

        private fun normalizeLatin(raw: String): String =
            FullStreamResolver.transliterateToLatin(raw).lowercase().replace(Regex("[^a-z0-9]"), "")

        internal fun isVerifiedCandidateMatch(
            targetArtist: String,
            targetTitle: String,
            expectedDurationMs: Long,
            candArtist: String,
            candTitle: String,
            candDurationMs: Long,
        ): Boolean = scoreLyricsCandidate(
            targetArtist = targetArtist,
            targetTitle = targetTitle,
            expectedDurationMs = expectedDurationMs,
            candArtist = candArtist,
            candTitle = candTitle,
            candDurationMs = candDurationMs,
            hasSynced = false,
        ) != Int.MIN_VALUE

        internal fun scoreLyricsCandidate(
            targetArtist: String,
            targetTitle: String,
            expectedDurationMs: Long,
            candArtist: String,
            candTitle: String,
            candDurationMs: Long,
            hasSynced: Boolean,
        ): Int {
            val nTargetTitle = normalizeKey(targetTitle)
            val lTargetTitle = normalizeLatin(targetTitle)
            val nCandTitle = normalizeKey(candTitle)
            val lCandTitle = normalizeLatin(candTitle)
            if (nTargetTitle.isBlank() || nCandTitle.isBlank()) return Int.MIN_VALUE

            val titleScore = when {
                nCandTitle == nTargetTitle || (lTargetTitle.isNotBlank() && lCandTitle == lTargetTitle) -> 220
                nTargetTitle.length >= 4 && (nCandTitle.contains(nTargetTitle) || nTargetTitle.contains(nCandTitle)) -> 130
                lTargetTitle.length >= 4 && (lCandTitle.contains(lTargetTitle) || lTargetTitle.contains(lCandTitle)) -> 115
                else -> {
                    val targetWords = targetTitle.lowercase().replace('ё', 'е').split(Regex("[^\\p{L}\\p{Nd}]+")).filter { it.length >= 2 }
                    val candWords = candTitle.lowercase().replace('ё', 'е').split(Regex("[^\\p{L}\\p{Nd}]+")).filter { it.length >= 2 }.toSet()
                    if (targetWords.isEmpty()) return Int.MIN_VALUE
                    val hits = targetWords.count { it in candWords }
                    if (hits * 2 < targetWords.size) return Int.MIN_VALUE
                    (hits * 120) / targetWords.size
                }
            }

            val targetArtists = targetArtist
                .split(Regex("\\s*(?:,|&|feat\\.|ft\\.|featuring|x|×)\\s*", RegexOption.IGNORE_CASE))
                .map { it.trim() }
                .filter { it.length >= 2 }
                .ifEmpty { listOf(targetArtist.trim()).filter { it.length >= 2 } }

            var artistScore = 0
            if (targetArtists.isNotEmpty()) {
                val nCandArtist = normalizeKey(candArtist)
                val lCandArtist = normalizeLatin(candArtist)
                var bestArtistScore = 0
                for (tArt in targetArtists) {
                    val nTargetArtist = normalizeKey(tArt)
                    val lTargetArtist = normalizeLatin(tArt)
                    val direct = nCandArtist == nTargetArtist || (lTargetArtist.isNotBlank() && lCandArtist == lTargetArtist)
                    val contains = nCandArtist.contains(nTargetArtist) || nTargetArtist.contains(nCandArtist) ||
                        (lTargetArtist.length >= 3 && (lCandArtist.contains(lTargetArtist) || lTargetArtist.contains(lCandArtist)))
                    val altTitleMatch = nCandTitle.contains(nTargetArtist) || (lTargetArtist.length >= 3 && lCandTitle.contains(lTargetArtist))
                    val s = when {
                        direct -> 180
                        contains -> 110
                        altTitleMatch -> 70
                        else -> 0
                    }
                    if (s > bestArtistScore) bestArtistScore = s
                }
                if (bestArtistScore == 0) {
                    return Int.MIN_VALUE
                }
                artistScore = bestArtistScore
            }

            var durBonus = 0
            if (expectedDurationMs >= 20_000L && candDurationMs >= 20_000L) {
                val deltaSec = abs(candDurationMs - expectedDurationMs) / 1000L
                if (deltaSec > 45L) return Int.MIN_VALUE
                durBonus = when {
                    deltaSec <= 5L -> 90
                    deltaSec <= 15L -> 40
                    else -> 0
                }
            }

            return titleScore + artistScore + durBonus + (if (hasSynced) 60 else 0)
        }

        internal fun parseSpicyLyricsJson(
            rawJson: String,
            trackDurationMs: Long = 0L,
        ): Pair<List<LyricsLine>, String?>? {
            return runCatching {
                val root = lenientJson.parseToJsonElement(rawJson) as? JsonObject ?: return@runCatching null
                if (root["error"]?.jsonPrimitive?.booleanOrNull == true) return@runCatching null
                val statusVal = root["status"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                if (statusVal != null && statusVal >= 400) return@runCatching null
                val contentArray = (root["Content"] as? JsonArray)
                    ?: (root["lines"] as? JsonArray)
                    ?: (root["lyrics"] as? JsonArray)
                    ?: return@runCatching null
                if (contentArray.isEmpty()) return@runCatching null

                val rawEntries = mutableListOf<Pair<Double, String>>()
                for (el in contentArray) {
                    val obj = el as? JsonObject ?: continue
                    val directText = obj["Text"]?.jsonPrimitive?.contentOrNull
                        ?: obj["text"]?.jsonPrimitive?.contentOrNull
                    val leadSyllables = (obj["Lead"] as? JsonObject)?.get("Syllables") as? JsonArray
                        ?: (obj["Syllables"] as? JsonArray)
                    val text = directText?.trim()?.takeIf { it.isNotBlank() }
                        ?: leadSyllables?.joinToString("") { s ->
                            val sObj = s as? JsonObject
                            val txt = sObj?.get("Text")?.jsonPrimitive?.contentOrNull.orEmpty()
                            val parted = sObj?.get("IsPartOfWord")?.jsonPrimitive?.booleanOrNull == true
                            if (parted) txt else "$txt "
                        }?.trim()?.takeIf { it.isNotBlank() }
                    if (text.isNullOrBlank()) continue

                    val startNum = obj["StartTime"]?.jsonPrimitive?.doubleOrNull
                        ?: (obj["Lead"] as? JsonObject)?.get("StartTime")?.jsonPrimitive?.doubleOrNull
                        ?: obj["startTimeMs"]?.jsonPrimitive?.doubleOrNull
                        ?: obj["time"]?.jsonPrimitive?.doubleOrNull
                        ?: -1.0
                    rawEntries += startNum to text
                }
                if (rawEntries.isEmpty()) return@runCatching null

                val validTimes = rawEntries.map { it.first }.filter { it >= 0.0 }
                val hasTimed = validTimes.isNotEmpty()
                val looksLikeSeconds = hasTimed && run {
                    val maxTime = validTimes.maxOrNull() ?: 0.0
                    val hasFraction = validTimes.any { it % 1.0 != 0.0 }
                    val trackSec = if (trackDurationMs > 0L) trackDurationMs / 1000.0 else 600.0
                    hasFraction || maxTime <= (trackSec * 1.4).coerceAtLeast(900.0)
                }

                val synced = if (hasTimed) {
                    rawEntries.filter { it.first >= 0.0 }.map { (t, txt) ->
                        val ms = if (looksLikeSeconds) (t * 1000.0).toLong() else t.toLong()
                        LyricsLine(timeMs = ms.coerceAtLeast(0L), text = txt)
                    }.sortedBy { it.timeMs }
                } else {
                    emptyList()
                }
                val plain = rawEntries.joinToString("\n") { it.second }.takeIf { it.isNotBlank() }
                synced to plain
            }.getOrNull()
        }

        private fun serializeLinesToLrc(lines: List<LyricsLine>): String? {
            if (lines.isEmpty()) return null
            return lines.joinToString("\n") { line ->
                val min = (line.timeMs / 60_000L).toString().padStart(2, '0')
                val sec = ((line.timeMs % 60_000L) / 1000L).toString().padStart(2, '0')
                val cs = ((line.timeMs % 1000L) / 10L).toString().padStart(2, '0')
                "[$min:$sec.$cs]${line.text}"
            }
        }

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
