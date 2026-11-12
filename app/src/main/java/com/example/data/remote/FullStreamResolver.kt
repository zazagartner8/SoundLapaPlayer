package com.example.data.remote

import android.net.Uri
import com.example.data.local.SoundLapaDao
import com.example.data.local.TrackEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.ConnectionPool
import okhttp3.Headers.Companion.toHeaders
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs

data class ResolvedAudioStream(
    val directUrl: String,
    val canonicalPageUrl: String,
    val durationMs: Long,
    val extension: String,
)

private class OkHttpNewPipeDownloader(
    private val client: OkHttpClient,
) : Downloader() {

    @Throws(IOException::class)
    override fun execute(request: Request): Response {
        val url = request.url()
        val method = request.httpMethod()
        val headersMap = mutableMapOf<String, String>()
        request.headers().forEach { (key, values) ->
            if (values.isNotEmpty()) {
                headersMap[key] = values.joinToString(", ")
            }
        }
        if (headersMap.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            headersMap["User-Agent"] =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        }

        val reqBuilder = okhttp3.Request.Builder()
            .url(url)
            .headers(headersMap.toHeaders())

        when (method.uppercase()) {
            "GET" -> reqBuilder.get()
            "HEAD" -> reqBuilder.head()
            "POST" -> {
                val contentType = headersMap.entries
                    .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
                    ?.value
                    ?.toMediaTypeOrNull()
                    ?: "application/json; charset=utf-8".toMediaTypeOrNull()
                val payload = request.dataToSend() ?: ByteArray(0)
                reqBuilder.post(payload.toRequestBody(contentType))
            }
            else -> reqBuilder.method(method, request.dataToSend()?.toRequestBody(null))
        }

        client.newCall(reqBuilder.build()).execute().use { okResponse ->
            val responseBody = okResponse.body?.string()
            val latestUrl = okResponse.request.url.toString()
            return Response(
                okResponse.code,
                okResponse.message,
                okResponse.headers.toMultimap(),
                responseBody,
                latestUrl,
            )
        }
    }
}

object FullStreamResolver {

    private const val MIN_FULL_TRACK_SECONDS = 45L
    private const val CACHE_TTL_MS = 90L * 60L * 1000L // 90 minutes

    private data class CachedStreamEntry(
        val resolved: ResolvedAudioStream,
        val expiresAtMs: Long,
    )

    private val resolverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(18, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    @Volatile
    private var initialized = false

    private val streamCache = ConcurrentHashMap<String, CachedStreamEntry>()

    fun ensureInitialized() {
        if (initialized) return
        synchronized(this) {
            if (!initialized) {
                NewPipe.init(OkHttpNewPipeDownloader(okHttpClient), Localization("RU", "ru"))
                initialized = true
            }
        }
    }

    fun isShortPreviewTrack(track: TrackEntity): Boolean {
        val uri = track.sourceUri.orEmpty()
        return track.id.startsWith("itunes_") ||
            track.id.startsWith("deezer_") ||
            uri.contains("audio-ssl.itunes.apple.com", ignoreCase = true) ||
            uri.contains("dzcdn.net", ignoreCase = true)
    }

    fun buildLegacyUpgradeUri(track: TrackEntity): String {
        val query = "${track.artist} ${track.title}".trim()
        return "soundlapa://resolve-full?id=${Uri.encode(track.id)}&q=${Uri.encode(query)}"
    }

    fun isResolvablePageOrUpgradeUri(uri: String): Boolean {
        if (uri.startsWith("soundlapa://resolve-full")) return true
        if (!uri.startsWith("http://") && !uri.startsWith("https://")) return false
        val lower = uri.lowercase()
        // Never re-resolve already-extracted CDN media URLs or direct Audius stream endpoints
        if (lower.contains("googlevideo.com") ||
            lower.contains("sndcdn.com") ||
            lower.contains("audius.co/") ||
            lower.endsWith(".mp3") ||
            lower.endsWith(".m4a") ||
            lower.endsWith(".webm")
        ) {
            return false
        }
        return lower.contains("youtube.com/watch") ||
            lower.contains("music.youtube.com/watch") ||
            lower.contains("youtu.be/") ||
            lower.contains("soundcloud.com/")
    }

    fun invalidateCachedStream(sourceUri: String?) {
        if (!sourceUri.isNullOrBlank()) {
            streamCache.remove(sourceUri)
        }
    }

    fun searchYouTubeMusic(query: String, limit: Int = 18): List<TrackEntity> {
        ensureInitialized()
        val trimmed = query.trim()
        if (trimmed.isBlank()) return emptyList()

        val songItems = runCatching {
            val extractor = ServiceList.YouTube.getSearchExtractor(trimmed, listOf("music_songs"), "")
            extractor.fetchPage()
            extractor.initialPage.items
                .filterIsInstance<StreamInfoItem>()
                .filter { isValidFullTrackItem(it) }
                .take(limit)
        }.getOrDefault(emptyList())

        val items = if (songItems.size >= 4) {
            songItems
        } else {
            val fallbackVideos = runCatching {
                val extractor = ServiceList.YouTube.getSearchExtractor(trimmed, listOf("videos"), "")
                extractor.fetchPage()
                extractor.initialPage.items
                    .filterIsInstance<StreamInfoItem>()
                    .filter { isValidFullTrackItem(it) && it.duration in MIN_FULL_TRACK_SECONDS..900L }
                    .take(limit)
            }.getOrDefault(emptyList())
            (songItems + fallbackVideos).distinctBy { it.url }.take(limit)
        }

        return items.mapNotNull { mapStreamItemToTrack(it, isYouTube = true) }
    }

    fun searchSoundCloud(query: String, limit: Int = 16): List<TrackEntity> {
        ensureInitialized()
        val trimmed = query.trim()
        if (trimmed.isBlank()) return emptyList()

        val items = runCatching {
            val extractor = ServiceList.SoundCloud.getSearchExtractor(trimmed)
            extractor.fetchPage()
            extractor.initialPage.items
                .filterIsInstance<StreamInfoItem>()
                .filter { isValidFullTrackItem(it) && it.duration in MIN_FULL_TRACK_SECONDS..900L }
                .take(limit)
        }.getOrDefault(emptyList())

        return items.mapNotNull { mapStreamItemToTrack(it, isYouTube = false) }
    }

    fun resolveDataSpecUriBlocking(rawUri: String, dao: SoundLapaDao? = null): String? {
        if (!isResolvablePageOrUpgradeUri(rawUri)) return null

        streamCache[rawUri]?.let { entry ->
            if (System.currentTimeMillis() < entry.expiresAtMs) {
                return entry.resolved.directUrl
            }
            streamCache.remove(rawUri)
        }

        if (rawUri.startsWith("soundlapa://resolve-full")) {
            val parsed = Uri.parse(rawUri)
            val trackId = parsed.getQueryParameter("id").orEmpty()
            val query = parsed.getQueryParameter("q").orEmpty()
            if (query.isBlank()) return null

            val resolved = resolveByQuery(query) ?: return null
            streamCache[rawUri] = CachedStreamEntry(resolved, System.currentTimeMillis() + CACHE_TTL_MS)
            streamCache[resolved.canonicalPageUrl] = CachedStreamEntry(resolved, System.currentTimeMillis() + CACHE_TTL_MS)

            if (trackId.isNotBlank() && dao != null) {
                resolverScope.launch {
                    runCatching {
                        dao.updateTrackStream(
                            id = trackId,
                            sourceUri = resolved.canonicalPageUrl,
                            durationMs = resolved.durationMs,
                        )
                    }
                }
            }
            return resolved.directUrl
        }

        val resolved = resolveFromPageUrl(rawUri) ?: return null
        streamCache[rawUri] = CachedStreamEntry(resolved, System.currentTimeMillis() + CACHE_TTL_MS)
        return resolved.directUrl
    }

    fun resolveDirectStreamForTrack(track: TrackEntity, dao: SoundLapaDao? = null): ResolvedAudioStream? {
        val rawUri = if (isShortPreviewTrack(track)) {
            buildLegacyUpgradeUri(track)
        } else {
            track.sourceUri ?: return null
        }

        if (!isResolvablePageOrUpgradeUri(rawUri)) {
            val ext = if (rawUri.contains(".m4a", true)) "m4a" else "mp3"
            return ResolvedAudioStream(
                directUrl = rawUri,
                canonicalPageUrl = rawUri,
                durationMs = track.durationMs,
                extension = ext,
            )
        }

        streamCache[rawUri]?.let { entry ->
            if (System.currentTimeMillis() < entry.expiresAtMs) {
                return entry.resolved
            }
            streamCache.remove(rawUri)
        }

        val resolved = if (rawUri.startsWith("soundlapa://resolve-full")) {
            resolveByQuery("${track.artist} ${track.title}")
        } else {
            resolveFromPageUrl(rawUri, fallbackQuery = "${track.artist} ${track.title}")
        } ?: return null

        streamCache[rawUri] = CachedStreamEntry(resolved, System.currentTimeMillis() + CACHE_TTL_MS)
        streamCache[resolved.canonicalPageUrl] = CachedStreamEntry(resolved, System.currentTimeMillis() + CACHE_TTL_MS)

        if (dao != null && (isShortPreviewTrack(track) || track.durationMs <= 35_000L)) {
            resolverScope.launch {
                runCatching {
                    dao.updateTrackStream(
                        id = track.id,
                        sourceUri = resolved.canonicalPageUrl,
                        durationMs = resolved.durationMs,
                    )
                }
            }
        }
        return resolved
    }

    fun prefetchTracks(tracks: List<TrackEntity>, dao: SoundLapaDao? = null) {
        if (tracks.isEmpty()) return
        tracks.take(2).forEach { track ->
            val uri = if (isShortPreviewTrack(track)) {
                buildLegacyUpgradeUri(track)
            } else {
                track.sourceUri
            }
            if (!uri.isNullOrBlank() && isResolvablePageOrUpgradeUri(uri)) {
                val cached = streamCache[uri]
                if (cached == null || System.currentTimeMillis() >= cached.expiresAtMs) {
                    resolverScope.launch {
                        runCatching { resolveDataSpecUriBlocking(uri, dao) }
                    }
                }
            }
        }
    }

    private fun resolveByQuery(query: String): ResolvedAudioStream? {
        ensureInitialized()
        val ytMatches = searchYouTubeMusic(query, limit = 5)
        for (candidate in ytMatches) {
            val pageUrl = candidate.sourceUri ?: continue
            resolveFromPageUrl(pageUrl)?.let { return it }
        }
        val scMatches = searchSoundCloud(query, limit = 5)
        for (candidate in scMatches) {
            val pageUrl = candidate.sourceUri ?: continue
            resolveFromPageUrl(pageUrl)?.let { return it }
        }
        return null
    }

    private fun resolveFromPageUrl(
        pageUrl: String,
        fallbackQuery: String? = null,
    ): ResolvedAudioStream? {
        ensureInitialized()
        val service: StreamingService = when {
            pageUrl.contains("soundcloud.com", ignoreCase = true) -> ServiceList.SoundCloud
            else -> ServiceList.YouTube
        }

        val info = runCatching { StreamInfo.getInfo(service, pageUrl) }.getOrNull()
        if (info != null) {
            val progressive = pickBestProgressiveAudioStream(info.audioStreams)
            if (progressive != null && !progressive.content.isNullOrBlank()) {
                val durationMs = (info.duration.takeIf { it > 0 } ?: 180L) * 1000L
                val ext = when (progressive.format) {
                    MediaFormat.M4A, MediaFormat.MPEG_4 -> "m4a"
                    MediaFormat.WEBMA, MediaFormat.WEBMA_OPUS -> "webm"
                    MediaFormat.MP3 -> "mp3"
                    else -> "m4a"
                }
                return ResolvedAudioStream(
                    directUrl = progressive.content,
                    canonicalPageUrl = pageUrl,
                    durationMs = durationMs,
                    extension = ext,
                )
            }

            // If SoundCloud only returned HLS for this item, try resolving its full progressive stream on YouTube Music
            if (service === ServiceList.SoundCloud) {
                val query = fallbackQuery?.takeIf { it.isNotBlank() }
                    ?: "${info.uploaderName.orEmpty()} ${info.name.orEmpty()}".trim()
                if (query.isNotBlank()) {
                    val ytCandidate = searchYouTubeMusic(query, limit = 3).firstOrNull()
                    if (ytCandidate?.sourceUri != null) {
                        resolveFromPageUrl(ytCandidate.sourceUri)?.let { return it }
                    }
                }
                val anyAudio = info.audioStreams.firstOrNull { !it.content.isNullOrBlank() }
                if (anyAudio != null) {
                    val durationMs = (info.duration.takeIf { it > 0 } ?: 180L) * 1000L
                    return ResolvedAudioStream(
                        directUrl = anyAudio.content,
                        canonicalPageUrl = pageUrl,
                        durationMs = durationMs,
                        extension = "mp3",
                    )
                }
            }
        }
        return null
    }

    private fun pickBestProgressiveAudioStream(streams: List<AudioStream>): AudioStream? {
        val progressive = streams.filter {
            it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && !it.content.isNullOrBlank()
        }
        if (progressive.isEmpty()) return null

        // Prefer M4A (AAC, universally supported and fast-seeking in ExoPlayer), then WEBM_OPUS, then MP3
        return progressive.maxWithOrNull(
            compareBy<AudioStream>(
                { stream ->
                    when (stream.format) {
                        MediaFormat.M4A, MediaFormat.MPEG_4 -> 3
                        MediaFormat.WEBMA_OPUS, MediaFormat.WEBMA -> 2
                        MediaFormat.MP3 -> 1
                        else -> 0
                    }
                },
                { it.averageBitrate.takeIf { br -> br > 0 } ?: it.bitrate },
            ),
        )
    }

    private fun isValidFullTrackItem(item: StreamInfoItem): Boolean {
        if (item.url.isNullOrBlank() || item.name.isNullOrBlank()) return false
        if (item.streamType == StreamType.LIVE_STREAM || item.streamType == StreamType.AUDIO_LIVE_STREAM) {
            return false
        }
        return item.duration >= MIN_FULL_TRACK_SECONDS
    }

    private fun mapStreamItemToTrack(item: StreamInfoItem, isYouTube: Boolean): TrackEntity? {
        val pageUrl = item.url ?: return null
        val rawTitle = item.name?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val rawUploader = item.uploaderName?.trim().orEmpty()
            .removeSuffix(" - Topic")
            .trim()
            .ifBlank { if (isYouTube) "YouTube Music" else "SoundCloud" }

        val id = if (isYouTube) {
            val videoId = extractYouTubeVideoId(pageUrl) ?: abs(pageUrl.hashCode()).toString()
            "yt_$videoId"
        } else {
            "sc_${abs(pageUrl.hashCode())}"
        }

        val artworkUrl = item.thumbnails
            .maxByOrNull { it.width * it.height }
            ?.url
            ?: item.thumbnails.lastOrNull()?.url

        val (accent, secondary) = paletteForSeed(id)
        val genre = inferGenre(rawTitle, rawUploader)

        return TrackEntity(
            id = id,
            title = rawTitle,
            artist = rawUploader,
            genre = genre,
            durationMs = (item.duration.coerceAtLeast(MIN_FULL_TRACK_SECONDS) * 1000L),
            waveformJson = TrackEntity.encodeWaveform(TrackEntity.defaultWaveform()),
            cachedFilePath = null,
            sourceUri = pageUrl,
            coverArtPath = artworkUrl,
            accentColorHex = accent,
            secondaryColorHex = secondary,
            isUserUpload = false,
            isAutoImported = false,
            isCachedOffline = false,
            fileSizeBytes = 0L,
            playCount = (item.viewCount.coerceIn(1_000L, 900_000L)).toInt(),
            likeCount = 0,
            isLiked = false,
            description = "",
            createdAt = System.currentTimeMillis(),
        )
    }

    private fun extractYouTubeVideoId(url: String): String? {
        val parsed = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        parsed.getQueryParameter("v")?.takeIf { it.isNotBlank() }?.let { return it }
        if (parsed.host?.contains("youtu.be", ignoreCase = true) == true) {
            return parsed.lastPathSegment?.takeIf { it.isNotBlank() }
        }
        return null
    }

    private fun inferGenre(title: String, artist: String): String {
        val combined = "$title $artist".lowercase()
        return when {
            combined.contains("phonk") || combined.contains("madk1d") || combined.contains("miyagi") ||
                combined.contains("eminem") || combined.contains("rap") || combined.contains("hip") -> "Хип-хоп"
            combined.contains("linkin park") || combined.contains("rock") || combined.contains("metal") -> "Рок"
            combined.contains("daft punk") || combined.contains("electronic") || combined.contains("remix") -> "Электроника"
            combined.contains("lofi") || combined.contains("lo-fi") -> "Lo-Fi"
            combined.contains("synth") -> "Синтвейв"
            else -> "Поп"
        }
    }

    private fun paletteForSeed(seed: String): Pair<Long, Long> {
        val palettes = listOf(
            0xFFFF5500L to 0xFF9C2B00L,
            0xFF5CE1E6L to 0xFFA78BFAL,
            0xFFFF9248L to 0xFFFB7185L,
            0xFF34D399L to 0xFF5CE1E6L,
            0xFFA78BFAL to 0xFFFB7185L,
        )
        val idx = abs(seed.hashCode()) % palettes.size
        return palettes[idx]
    }
}
