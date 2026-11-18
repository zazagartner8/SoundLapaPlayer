package com.example.data.remote

import android.net.Uri
import androidx.compose.runtime.Immutable
import com.example.data.local.ExplicitContentDetector
import com.example.data.local.SoundLapaDao
import com.example.data.local.TrackEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.Headers.Companion.toHeaders
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs

@Immutable
data class ArtistAlbumItem(
    val id: String,
    val title: String,
    val artist: String,
    val sourceUrl: String,
    val artworkUrl: String?,
    val trackCount: Int,
    val tracks: List<TrackEntity> = emptyList(),
)

data class SoundCloudArtistProfile(
    val id: Long = 0L,
    val name: String,
    val url: String,
    val avatarUrl: String?,
    val subscriberCount: Long = 0L,
    val trackCount: Int = 0,
    val verified: Boolean = false,
    val linkedSpotifyArtistId: String? = null,
)

data class ResolvedAudioStream(
    val directUrl: String,
    val canonicalPageUrl: String,
    val durationMs: Long,
    val extension: String,
    val isHls: Boolean = false,
    val headers: Map<String, String> = emptyMap(),
)

enum class VideoStreamSource {
    SPOTIFY_CANVAS,
    SPOTIFY_VIDEO,
    YOUTUBE_MUSIC,
}

data class ResolvedVideoStream(
    val videoUrl: String,
    val title: String,
    val durationMs: Long,
    val sourcePageUrl: String,
    val headers: Map<String, String> = emptyMap(),
    val width: Int = 0,
    val height: Int = 0,
    val audioUrl: String? = null,
    val isMuxed: Boolean = false,
    val isLoopingCanvas: Boolean = false,
    val sourceKind: VideoStreamSource = VideoStreamSource.YOUTUBE_MUSIC,
) {
    val hasAudio: Boolean
        get() = !isLoopingCanvas && (isMuxed || !audioUrl.isNullOrBlank())
}

enum class StreamPayloadKind {
    PROGRESSIVE_AUDIO,
    HLS_PLAYLIST,
    REJECTED,
}

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
            headersMap["User-Agent"] = FullStreamResolver.DEFAULT_BROWSER_USER_AGENT
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

    const val DEFAULT_BROWSER_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private const val IOS_YOUTUBE_USER_AGENT =
        "com.google.ios.youtube/19.29.1 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X;)"
    private const val ANDROID_VR_YOUTUBE_USER_AGENT =
        "com.google.android.apps.youtube.vr.oculus/1.56.21 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"
    private const val ANDROID_YOUTUBE_USER_AGENT =
        "com.google.android.youtube/19.28.35 (Linux; U; Android 15; en_US; Pixel 9 Pro Build/AP3A.241005.015) gzip"

    private const val MIN_FULL_TRACK_SECONDS = 45L
    private const val CACHE_TTL_MS = 90L * 60L * 1000L // 90 minutes

    private data class CachedStreamEntry(
        val resolved: ResolvedAudioStream,
        val expiresAtMs: Long,
    )

    private val resolverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var httpCache: Cache? = null

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .apply { httpCache?.let { cache(it) } }
            .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(18, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private val probeClient: OkHttpClient by lazy {
        okHttpClient.newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    @Volatile
    private var initialized = false

    @Volatile
    var isDataSaverEnabled: Boolean = false

    @Volatile
    var isBatterySaverEnabled: Boolean = false

    private data class CachedVideoEntry(
        val resolved: ResolvedVideoStream,
        val expiresAtMs: Long,
    )

    private val streamCache = ConcurrentHashMap<String, CachedStreamEntry>()
    private val musicVideoCache = ConcurrentHashMap<String, CachedVideoEntry>()
    private val musicVideoMissCache = ConcurrentHashMap<String, Long>()
    private val forceProbeKeys = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val inFlightResolutions = ConcurrentHashMap<String, CompletableFuture<ResolvedAudioStream?>>()
    private val scArtworkCache = ConcurrentHashMap<String, String>()

    private val scJsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Volatile
    private var cachedScClientId: String? = null
    private val scClientIdLock = Any()

    fun configureHttpCache(cacheDir: File) {
        if (httpCache != null) return
        synchronized(this) {
            if (httpCache == null) {
                val httpCacheDir = File(cacheDir, "soundlapa_http_cache").apply { mkdirs() }
                httpCache = Cache(httpCacheDir, 16L * 1024L * 1024L)
            }
        }
    }

    fun warmUpAsync() {
        if (initialized) return
        resolverScope.launch {
            runCatching { ensureInitialized() }
        }
    }

    fun clearStreamCache() {
        streamCache.clear()
        musicVideoCache.clear()
        musicVideoMissCache.clear()
        forceProbeKeys.clear()
    }

    fun ensureInitialized() {
        if (initialized) return
        synchronized(this) {
            if (!initialized) {
                NewPipe.init(OkHttpNewPipeDownloader(okHttpClient), Localization("ru", "RU"))
                initialized = true
            }
        }
    }

    fun userAgentForStreamUrl(url: String): String = when {
        url.contains("c=IOS", ignoreCase = true) -> IOS_YOUTUBE_USER_AGENT
        url.contains("c=ANDROID_VR", ignoreCase = true) -> ANDROID_VR_YOUTUBE_USER_AGENT
        url.contains("c=ANDROID", ignoreCase = true) -> ANDROID_YOUTUBE_USER_AGENT
        else -> DEFAULT_BROWSER_USER_AGENT
    }

    fun classifyStreamProbe(
        finalUrl: String,
        contentType: String?,
        headBytes: ByteArray,
        expectedHls: Boolean = false,
    ): StreamPayloadKind {
        val ct = contentType?.lowercase().orEmpty()
        val prefix = headBytes.decodeToString().trimStart('\uFEFF', ' ', '\t', '\r', '\n').lowercase()
        val urlLower = finalUrl.lowercase()

        val isHtmlOrJson = ct.contains("text/html") ||
            ct.contains("application/xhtml") ||
            ct.contains("application/json") ||
            ct.contains("text/xml") ||
            ct.contains("application/xml") ||
            prefix.startsWith("<!doctype") ||
            prefix.startsWith("<html") ||
            prefix.startsWith("<head") ||
            prefix.startsWith("<body") ||
            prefix.startsWith("<?xml") ||
            prefix.startsWith("{")
        if (isHtmlOrJson) {
            return StreamPayloadKind.REJECTED
        }

        val isHls = expectedHls ||
            ct.contains("mpegurl") ||
            prefix.startsWith("#extm3u") ||
            urlLower.contains(".m3u8")
        return if (isHls) {
            StreamPayloadKind.HLS_PLAYLIST
        } else {
            StreamPayloadKind.PROGRESSIVE_AUDIO
        }
    }

    fun isLikelyPlayableLocalFile(file: File): Boolean {
        if (!file.exists() || !file.isFile || file.length() <= 1024L) return false
        return runCatching {
            val buffer = ByteArray(128)
            val readCount = file.inputStream().use { it.read(buffer) }
            if (readCount <= 0) return@runCatching false
            val head = buffer.copyOf(readCount)
            val kind = classifyStreamProbe(
                finalUrl = file.name,
                contentType = null,
                headBytes = head,
                expectedHls = false,
            )
            kind == StreamPayloadKind.PROGRESSIVE_AUDIO
        }.getOrDefault(false)
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
        return "soundlap://resolve-full?id=${Uri.encode(track.id)}&q=${Uri.encode(query)}&artist=${Uri.encode(track.artist)}&title=${Uri.encode(track.title)}&dur=${track.durationMs}"
    }

    fun isResolvablePageOrUpgradeUri(uri: String): Boolean {
        if (uri.startsWith("soundlapa://resolve-full") || uri.startsWith("soundlap://resolve-full")) return true
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
            forceProbeKeys.add(sourceUri)
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
        val trimmed = query.trim()
        if (trimmed.isBlank()) return emptyList()

        // Fast SoundCloud api-v2 track search first (rich metadata, playback counts, square t500x500 artwork)
        val cid = getSoundCloudClientId()
        if (!cid.isNullOrBlank()) {
            val encoded = URLEncoder.encode(trimmed, "UTF-8")
            val body = soundCloudApiGet(
                "https://api-v2.soundcloud.com/search/tracks?q=$encoded&client_id=$cid&limit=${limit.coerceAtLeast(10)}",
            )
            if (!body.isNullOrBlank()) {
                val parsed = runCatching {
                    val col = scJsonParser.parseToJsonElement(body).jsonObject["collection"] as? JsonArray
                    if (col != null) {
                        parseSoundCloudApiTracks(col, fallbackArtist = "SoundCloud", fallbackAvatar = null)
                    } else {
                        emptyList()
                    }
                }.getOrDefault(emptyList())
                if (parsed.isNotEmpty()) {
                    return parsed.take(limit)
                }
            }
        }

        ensureInitialized()
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

    fun isSpotifyOrSoundCloudArtwork(url: String?): Boolean {
        val lower = url?.trim()?.lowercase() ?: return false
        return lower.contains("scdn.co/") ||
            lower.contains("spotifycdn.com/") ||
            lower.contains("sndcdn.com/artworks")
    }

    fun findSoundCloudArtwork(
        artist: String,
        title: String,
        dataSaver: Boolean = isDataSaverEnabled,
    ): String? {
        val cleanTitle = title.trim()
        if (cleanTitle.isBlank()) return null
        val cleanArtist = artist.trim()
        val cacheKey = "${cleanArtist.lowercase()}|${cleanTitle.lowercase()}"
        scArtworkCache[cacheKey]?.let { cached ->
            return cached.takeIf { it.isNotBlank() }?.let { upgradeArtworkUrl(it, dataSaver = dataSaver) }
        }

        val cid = getSoundCloudClientId() ?: return null
        val query = buildString {
            if (cleanArtist.isNotBlank() &&
                !cleanArtist.equals("YouTube Music", ignoreCase = true) &&
                !cleanArtist.equals("SoundLapa", ignoreCase = true) &&
                !cleanArtist.equals("SoundLap", ignoreCase = true)
            ) {
                append(cleanArtist)
                append(' ')
            }
            append(cleanTitle)
        }.trim()
        if (query.length < 2) return null

        val encoded = URLEncoder.encode(query, "UTF-8")
        val body = soundCloudApiGet(
            "https://api-v2.soundcloud.com/search/tracks?q=$encoded&client_id=$cid&limit=4",
        ) ?: return null

        val normTitle = cleanTitle.lowercase().replace(Regex("[^\\p{L}\\p{Nd}]"), "")
        val resolvedArt = runCatching {
            val col = scJsonParser.parseToJsonElement(body).jsonObject["collection"] as? JsonArray
                ?: return@runCatching null
            val candidates = col.mapNotNull { it as? JsonObject }
            val best = candidates.firstOrNull { obj ->
                val objTitle = obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    .lowercase().replace(Regex("[^\\p{L}\\p{Nd}]"), "")
                val hasArt = !obj["artwork_url"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()
                hasArt && normTitle.length >= 2 && (objTitle.contains(normTitle) || normTitle.contains(objTitle))
            } ?: candidates.firstOrNull { !it["artwork_url"]?.jsonPrimitive?.contentOrNull.isNullOrBlank() }
            best?.get("artwork_url")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        }.getOrNull()

        scArtworkCache[cacheKey] = resolvedArt.orEmpty()
        return resolvedArt?.let { upgradeArtworkUrl(it, dataSaver = dataSaver) }
    }

    fun searchArtistAlbumsAndAvatar(
        artistName: String,
        limit: Int = 12,
    ): Pair<String?, List<ArtistAlbumItem>> {
        ensureInitialized()
        val trimmed = artistName.trim()
        if (trimmed.isBlank()) return null to emptyList()

        val avatarUrl = runCatching {
            val channelItems = runCatching {
                val extractor = ServiceList.YouTube.getSearchExtractor(trimmed, listOf("music_artists"), "")
                extractor.fetchPage()
                extractor.initialPage.items.filterIsInstance<ChannelInfoItem>()
            }.getOrDefault(emptyList()).ifEmpty {
                val extractor = ServiceList.YouTube.getSearchExtractor(trimmed, listOf("channels"), "")
                extractor.fetchPage()
                extractor.initialPage.items.filterIsInstance<ChannelInfoItem>()
            }
            val bestChannel = channelItems.firstOrNull {
                it.name.equals(trimmed, ignoreCase = true) ||
                    it.name.contains(trimmed, ignoreCase = true) ||
                    trimmed.contains(it.name, ignoreCase = true)
            } ?: channelItems.firstOrNull()
            bestThumbnailUrl(bestChannel?.thumbnails.orEmpty())
        }.getOrNull()

        val albumItems = runCatching {
            val extractor = ServiceList.YouTube.getSearchExtractor(trimmed, listOf("music_albums"), "")
            extractor.fetchPage()
            extractor.initialPage.items
                .filterIsInstance<PlaylistInfoItem>()
                .take(limit)
        }.getOrDefault(emptyList())

        val playlistFallback = if (albumItems.size >= 4) {
            albumItems
        } else {
            val extra = runCatching {
                val extractor = ServiceList.YouTube.getSearchExtractor("$trimmed album", listOf("playlists"), "")
                extractor.fetchPage()
                extractor.initialPage.items
                    .filterIsInstance<PlaylistInfoItem>()
                    .filter { item ->
                        val title = item.name.orEmpty()
                        val uploader = item.uploaderName.orEmpty()
                        !title.startsWith("Mix -", ignoreCase = true) &&
                            (title.contains(trimmed, ignoreCase = true) || uploader.contains(trimmed, ignoreCase = true))
                    }
                    .take(limit)
            }.getOrDefault(emptyList())
            (albumItems + extra).distinctBy { it.url }.take(limit)
        }

        val mappedAlbums = playlistFallback.mapNotNull { item ->
            val url = item.url?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = item.name?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val uploader = item.uploaderName?.trim()?.removeSuffix(" - Topic").orEmpty()
                .ifBlank { trimmed }
            val art = bestThumbnailUrl(item.thumbnails)
            ArtistAlbumItem(
                id = "yt_alb_${abs(url.hashCode())}",
                title = title,
                artist = uploader,
                sourceUrl = url,
                artworkUrl = art,
                trackCount = item.streamCount.toInt().coerceAtLeast(0),
            )
        }

        return avatarUrl to mappedAlbums
    }

    private fun getSoundCloudClientId(forceRefresh: Boolean = false): String? {
        if (!forceRefresh) {
            cachedScClientId?.let { return it }
        }
        return synchronized(scClientIdLock) {
            if (!forceRefresh) {
                cachedScClientId?.let { return@synchronized it }
            }
            val found = runCatching {
                val homeReq = okhttp3.Request.Builder()
                    .url("https://soundcloud.com")
                    .header("User-Agent", DEFAULT_BROWSER_USER_AGENT)
                    .get()
                    .build()
                val homeHtml = okHttpClient.newCall(homeReq).execute().use { resp ->
                    if (resp.isSuccessful) resp.body?.string() else null
                } ?: return@runCatching null

                val bundles = Regex("""https://a-v2\.sndcdn\.com/assets/[0-9a-zA-Z-]+\.js""")
                    .findAll(homeHtml)
                    .map { it.value }
                    .toList()
                for (bundleUrl in bundles.asReversed().take(8)) {
                    val js = runCatching {
                        val bReq = okhttp3.Request.Builder()
                            .url(bundleUrl)
                            .header("User-Agent", DEFAULT_BROWSER_USER_AGENT)
                            .get()
                            .build()
                        okHttpClient.newCall(bReq).execute().use { resp ->
                            if (resp.isSuccessful) resp.body?.string() else null
                        }
                    }.getOrNull() ?: continue
                    val match = Regex("""client_id\s*:\s*["']([a-zA-Z0-9]{20,40})["']""").find(js)
                    if (match != null) {
                        return@runCatching match.groupValues[1]
                    }
                }
                null
            }.getOrNull()
            if (!found.isNullOrBlank()) {
                cachedScClientId = found
            }
            found
        }
    }

    private fun soundCloudApiGet(url: String): String? {
        val req = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", DEFAULT_BROWSER_USER_AGENT)
            .header("Accept", "application/json")
            .get()
            .build()
        return runCatching {
            okHttpClient.newCall(req).execute().use { resp ->
                if (resp.code == 401 || resp.code == 403) {
                    cachedScClientId = null
                    return@use null
                }
                if (!resp.isSuccessful) return@use null
                resp.body?.string()
            }
        }.getOrNull()
    }

    fun searchSoundCloudUsers(
        query: String,
        limit: Int = 5,
    ): List<SoundCloudArtistProfile> {
        val trimmed = query.trim()
        if (trimmed.length < 2) return emptyList()

        // Fast SoundCloud api-v2 user search first
        val cid = getSoundCloudClientId()
        if (!cid.isNullOrBlank()) {
            val encoded = URLEncoder.encode(trimmed, "UTF-8")
            val body = soundCloudApiGet(
                "https://api-v2.soundcloud.com/search/users?q=$encoded&client_id=$cid&limit=${limit.coerceAtLeast(6)}",
            )
            if (!body.isNullOrBlank()) {
                val parsed = runCatching {
                    val col = scJsonParser.parseToJsonElement(body).jsonObject["collection"] as? JsonArray
                    col?.mapNotNull { el ->
                        val u = el as? JsonObject ?: return@mapNotNull null
                        val uid = u["id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
                        val name = u["username"]?.jsonPrimitive?.contentOrNull?.trim()
                            ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        val url = u["permalink_url"]?.jsonPrimitive?.contentOrNull?.trim()
                            ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        val avatar = upgradeArtworkUrl(u["avatar_url"]?.jsonPrimitive?.contentOrNull)
                        val followers = u["followers_count"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
                        val trackCount = u["track_count"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                        val verified = u["verified"]?.jsonPrimitive?.booleanOrNull == true
                        SoundCloudArtistProfile(
                            id = uid,
                            name = name,
                            url = url,
                            avatarUrl = avatar,
                            subscriberCount = followers,
                            trackCount = trackCount,
                            verified = verified,
                        )
                    }.orEmpty()
                }.getOrDefault(emptyList())
                if (parsed.isNotEmpty()) return parsed.take(limit)
            }
        }

        ensureInitialized()
        return runCatching {
            val extractor = ServiceList.SoundCloud.getSearchExtractor(trimmed, listOf("users"), "")
            extractor.fetchPage()
            extractor.initialPage.items
                .filterIsInstance<ChannelInfoItem>()
                .take(limit)
                .mapNotNull { ch ->
                    val name = ch.name?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val url = ch.url?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val avatar = bestThumbnailUrl(ch.thumbnails)
                    SoundCloudArtistProfile(
                        name = name,
                        url = url,
                        avatarUrl = avatar,
                        subscriberCount = ch.subscriberCount.coerceAtLeast(0L),
                    )
                }
        }.getOrDefault(emptyList())
    }

    fun fetchSoundCloudArtistCatalog(
        artistName: String,
        limit: Int = 25,
        knownUserUrl: String? = null,
    ): Triple<SoundCloudArtistProfile?, List<TrackEntity>, List<ArtistAlbumItem>> {
        val trimmed = artistName.trim()
        if (trimmed.isBlank() && knownUserUrl.isNullOrBlank()) {
            return Triple(null, emptyList(), emptyList())
        }

        val cid = getSoundCloudClientId()
        var bestMatch: SoundCloudArtistProfile? = null

        if (!cid.isNullOrBlank() && !knownUserUrl.isNullOrBlank() && knownUserUrl.contains("soundcloud.com", true)) {
            val resolvedBody = soundCloudApiGet(
                "https://api-v2.soundcloud.com/resolve?url=${URLEncoder.encode(knownUserUrl, "UTF-8")}&client_id=$cid",
            )
            if (!resolvedBody.isNullOrBlank()) {
                bestMatch = runCatching {
                    val u = scJsonParser.parseToJsonElement(resolvedBody).jsonObject
                    val uid = u["id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
                    val name = u["username"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    val url = u["permalink_url"]?.jsonPrimitive?.contentOrNull?.trim() ?: knownUserUrl
                    if (uid > 0L && name.isNotBlank()) {
                        SoundCloudArtistProfile(
                            id = uid,
                            name = name,
                            url = url,
                            avatarUrl = upgradeArtworkUrl(u["avatar_url"]?.jsonPrimitive?.contentOrNull),
                            subscriberCount = u["followers_count"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
                            trackCount = u["track_count"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
                            verified = u["verified"]?.jsonPrimitive?.booleanOrNull == true,
                        )
                    } else null
                }.getOrNull()
            }
        }

        val normNeedle = trimmed.lowercase().replace(Regex("[^\\p{L}\\p{Nd}]"), "")
        if (bestMatch == null && trimmed.isNotBlank()) {
            val candidates = searchSoundCloudUsers(trimmed, limit = 8)
            bestMatch = candidates.maxByOrNull { ch ->
                val normName = ch.name.lowercase().replace(Regex("[^\\p{L}\\p{Nd}]"), "")
                val urlSlug = ch.url.substringAfterLast('/').lowercase().replace(Regex("[^\\p{L}\\p{Nd}]"), "")
                val matchBonus = when {
                    normName == normNeedle || urlSlug == normNeedle -> 400_000L
                    normName.startsWith(normNeedle) || urlSlug.startsWith(normNeedle) -> 220_000L
                    normNeedle.length >= 3 && (normName.contains(normNeedle) || normNeedle.contains(normName)) -> 100_000L
                    else -> -1L
                }
                if (matchBonus < 0L) {
                    Long.MIN_VALUE
                } else {
                    val verifiedBonus = if (ch.verified) 150_000L else 0L
                    val tracksBonus = if (ch.trackCount > 0) 90_000L else 0L
                    matchBonus + verifiedBonus + tracksBonus + ch.subscriberCount.coerceAtMost(5_000_000L)
                }
            }?.takeIf { ch ->
                val normName = ch.name.lowercase().replace(Regex("[^\\p{L}\\p{Nd}]"), "")
                val urlSlug = ch.url.substringAfterLast('/').lowercase().replace(Regex("[^\\p{L}\\p{Nd}]"), "")
                normName == normNeedle ||
                    urlSlug == normNeedle ||
                    normName.startsWith(normNeedle) ||
                    urlSlug.startsWith(normNeedle) ||
                    (normNeedle.length >= 3 && (normName.contains(normNeedle) || normNeedle.contains(normName)))
            }
        }

        if (bestMatch == null) {
            return Triple(null, emptyList(), emptyList())
        }

        // Fast direct api-v2 track + album + web-profile fetching in parallel
        if (!cid.isNullOrBlank() && bestMatch.id > 0L) {
            val uid = bestMatch.id
            val topTracksFut = CompletableFuture.supplyAsync {
                soundCloudApiGet("https://api-v2.soundcloud.com/users/$uid/toptracks?client_id=$cid&limit=30")
            }
            val tracksFut = CompletableFuture.supplyAsync {
                soundCloudApiGet("https://api-v2.soundcloud.com/users/$uid/tracks?client_id=$cid&limit=30")
            }
            val albumsFut = CompletableFuture.supplyAsync {
                soundCloudApiGet("https://api-v2.soundcloud.com/users/$uid/albums?client_id=$cid&limit=16")
            }
            val playlistsFut = CompletableFuture.supplyAsync {
                soundCloudApiGet("https://api-v2.soundcloud.com/users/$uid/playlists_without_albums?client_id=$cid&limit=10")
            }
            val webProfilesFut = CompletableFuture.supplyAsync {
                soundCloudApiGet("https://api-v2.soundcloud.com/users/soundcloud%3Ausers%3A$uid/web-profiles?client_id=$cid")
            }

            val topTracksBody = runCatching { topTracksFut.get(7, TimeUnit.SECONDS) }.getOrNull()
            val tracksBody = runCatching { tracksFut.get(7, TimeUnit.SECONDS) }.getOrNull()
            val albumsBody = runCatching { albumsFut.get(7, TimeUnit.SECONDS) }.getOrNull()
            val playlistsBody = runCatching { playlistsFut.get(6, TimeUnit.SECONDS) }.getOrNull()
            val webProfilesBody = runCatching { webProfilesFut.get(5, TimeUnit.SECONDS) }.getOrNull()

            val apiTracks = LinkedHashMap<String, TrackEntity>()
            listOfNotNull(topTracksBody, tracksBody).forEach { body ->
                runCatching {
                    val arr = scJsonParser.parseToJsonElement(body).jsonObject["collection"] as? JsonArray
                    if (arr != null) {
                        parseSoundCloudApiTracks(arr, bestMatch.name, bestMatch.avatarUrl).forEach { t ->
                            apiTracks.putIfAbsent(t.id, t)
                        }
                    }
                }
            }

            val apiAlbums = LinkedHashMap<String, ArtistAlbumItem>()
            listOfNotNull(albumsBody, playlistsBody).forEach { body ->
                runCatching {
                    val arr = scJsonParser.parseToJsonElement(body).jsonObject["collection"] as? JsonArray
                    arr?.forEach { el ->
                        val pl = el as? JsonObject ?: return@forEach
                        val plUrl = pl["permalink_url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                            ?: return@forEach
                        val plTitle = pl["title"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
                            ?: return@forEach
                        val plArt = upgradeArtworkUrl(pl["artwork_url"]?.jsonPrimitive?.contentOrNull)
                            ?: bestMatch.avatarUrl
                        val trackCount = pl["track_count"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                        val key = plTitle.lowercase().trim()
                        if (!apiAlbums.containsKey(key)) {
                            apiAlbums[key] = ArtistAlbumItem(
                                id = "sc_alb_${abs(plUrl.hashCode())}",
                                title = plTitle,
                                artist = bestMatch.name,
                                sourceUrl = plUrl,
                                artworkUrl = plArt,
                                trackCount = trackCount,
                            )
                        }
                    }
                }
            }

            val linkedSpotifyId = webProfilesBody?.let { wpBody ->
                Regex("""open\.spotify\.com/(?:intl-[a-z]{2}/)?artist/([A-Za-z0-9]{22})""")
                    .find(wpBody)?.groupValues?.get(1)
            }

            if (apiTracks.isNotEmpty() || apiAlbums.isNotEmpty()) {
                val enrichedProfile = bestMatch.copy(linkedSpotifyArtistId = linkedSpotifyId)
                return Triple(
                    enrichedProfile,
                    apiTracks.values.take(limit),
                    apiAlbums.values.take(18),
                )
            }
        }

        // NewPipe ChannelInfo / ChannelTabInfo fallback if api-v2 returned nothing
        ensureInitialized()
        val channelInfo = runCatching { ChannelInfo.getInfo(ServiceList.SoundCloud, bestMatch.url) }.getOrNull()
        val resolvedAvatar = bestThumbnailUrl(channelInfo?.avatars.orEmpty()) ?: bestMatch.avatarUrl
        val resolvedProfile = bestMatch.copy(
            name = channelInfo?.name?.trim()?.takeIf { it.isNotBlank() } ?: bestMatch.name,
            avatarUrl = resolvedAvatar,
        )

        val tracks = mutableListOf<TrackEntity>()
        val albums = mutableListOf<ArtistAlbumItem>()

        val tabs = channelInfo?.tabs.orEmpty().sortedBy { tab ->
            val u = tab.url.lowercase()
            when {
                u.endsWith("/tracks") || u.contains("top") -> 0
                u.contains("albums") || u.contains("playlists") -> 1
                u.contains("repost") -> 9
                else -> 2
            }
        }
        for (tab in tabs) {
            val tabTitle = tab.url.lowercase()
            if (tabTitle.contains("repost")) continue
            val tabInfo = runCatching { ChannelTabInfo.getInfo(ServiceList.SoundCloud, tab) }.getOrNull() ?: continue
            val tabItems = tabInfo.relatedItems
            if (tracks.size < limit && !tabTitle.contains("playlists") && !tabTitle.contains("albums")) {
                tabItems.filterIsInstance<StreamInfoItem>()
                    .filter { isValidFullTrackItem(it) }
                    .take(limit - tracks.size)
                    .mapNotNull { mapStreamItemToTrack(it, isYouTube = false) }
                    .forEach { t ->
                        tracks += if (t.artist.equals("SoundCloud", true)) t.copy(artist = resolvedProfile.name) else t
                    }
            }
            tabItems.filterIsInstance<PlaylistInfoItem>()
                .take(14)
                .forEach { pl ->
                    val plUrl = pl.url?.takeIf { it.isNotBlank() } ?: return@forEach
                    val plTitle = pl.name?.trim()?.takeIf { it.isNotBlank() } ?: return@forEach
                    albums += ArtistAlbumItem(
                        id = "sc_alb_${abs(plUrl.hashCode())}",
                        title = plTitle,
                        artist = resolvedProfile.name,
                        sourceUrl = plUrl,
                        artworkUrl = bestThumbnailUrl(pl.thumbnails) ?: resolvedAvatar,
                        trackCount = pl.streamCount.toInt().coerceAtLeast(0),
                    )
                }
        }

        return Triple(resolvedProfile, tracks.distinctBy { it.id }, albums.distinctBy { it.title.lowercase().trim() })
    }

    internal fun parseSoundCloudApiTracks(
        collection: JsonArray,
        fallbackArtist: String,
        fallbackAvatar: String?,
    ): List<TrackEntity> {
        val rawMapped = collection.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            if (obj["streamable"]?.jsonPrimitive?.booleanOrNull == false) return@mapNotNull null
            val tid = obj["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = obj["title"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val permalink = obj["permalink_url"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val durationMs = obj["duration"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
            if (durationMs < MIN_FULL_TRACK_SECONDS * 1000L) return@mapNotNull null

            val userObj = obj["user"] as? JsonObject
            val uploader = userObj?.get("username")?.jsonPrimitive?.contentOrNull?.trim()
                ?.takeIf { it.isNotBlank() } ?: fallbackArtist
            val artRaw = obj["artwork_url"]?.jsonPrimitive?.contentOrNull
                ?: userObj?.get("avatar_url")?.jsonPrimitive?.contentOrNull
                ?: fallbackAvatar
            val artUrl = upgradeArtworkUrl(artRaw)
            val playCount = (obj["playback_count"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 5_000L)
                .coerceIn(1_000L, 900_000_000L).toInt()
            val likeCount = (obj["likes_count"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L)
                .coerceIn(0L, 50_000_000L).toInt()
            val genre = obj["genre"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
                ?: inferGenre(title, uploader)
            val stableId = "sc_${abs(permalink.hashCode())}"
            val isExplicitMeta = ((obj["publisher_metadata"] as? JsonObject)
                ?.get("explicit")?.jsonPrimitive?.booleanOrNull == true) ||
                (obj["tag_list"]?.jsonPrimitive?.contentOrNull?.contains("explicit", ignoreCase = true) == true)
            if (isExplicitMeta) {
                ExplicitContentDetector.markPreDetectedExplicit(stableId)
            }
            val (accent, secondary) = paletteForSeed(stableId)

            TrackEntity(
                id = stableId,
                title = title,
                artist = uploader,
                genre = genre,
                durationMs = durationMs,
                waveformJson = TrackEntity.encodeWaveform(TrackEntity.defaultWaveform()),
                cachedFilePath = null,
                sourceUri = permalink,
                coverArtPath = artUrl,
                accentColorHex = accent,
                secondaryColorHex = secondary,
                isUserUpload = false,
                isAutoImported = false,
                isCachedOffline = false,
                fileSizeBytes = 0L,
                playCount = playCount,
                likeCount = likeCount,
                isLiked = false,
                description = "SoundCloud",
                createdAt = System.currentTimeMillis(),
            )
        }
        // Prefer standard song lengths (<= 12 min) if normal tracks exist so long DJ sets don't crowd the artist page
        val standardLength = rawMapped.filter { it.durationMs <= 12L * 60L * 1000L }
        return standardLength.ifEmpty { rawMapped }
    }

    fun fetchPlaylistTracks(
        playlistUrl: String,
        artistHint: String? = null,
        fallbackCoverUrl: String? = null,
        limit: Int = 40,
        albumTitleHint: String? = null,
    ): List<TrackEntity> {
        ensureInitialized()
        if (playlistUrl.isBlank()) return emptyList()
        val isSoundCloud = playlistUrl.contains("soundcloud.com", ignoreCase = true)
        val service: StreamingService = if (isSoundCloud) ServiceList.SoundCloud else ServiceList.YouTube

        val info = runCatching { PlaylistInfo.getInfo(service, playlistUrl) }.getOrNull() ?: return emptyList()
        val albumCover = fallbackCoverUrl ?: bestThumbnailUrl(info.thumbnails)
        val resolvedAlbumTitle = albumTitleHint?.takeIf { it.isNotBlank() }
            ?: info.name?.trim()?.takeIf { it.isNotBlank() }

        return info.relatedItems
            .filterIsInstance<StreamInfoItem>()
            .filter { item ->
                !item.url.isNullOrBlank() &&
                    !item.name.isNullOrBlank() &&
                    item.streamType != StreamType.LIVE_STREAM &&
                    item.streamType != StreamType.AUDIO_LIVE_STREAM &&
                    item.duration >= 15L
            }
            .take(limit)
            .mapNotNull { item ->
                mapStreamItemToTrack(item, isYouTube = !isSoundCloud)?.let { track ->
                    val resolvedCover = track.coverArtPath ?: albumCover
                    val resolvedArtist = if (artistHint != null &&
                        (track.artist.equals("YouTube Music", ignoreCase = true) ||
                            track.artist.equals("Various Artists", ignoreCase = true))
                    ) {
                        artistHint
                    } else {
                        track.artist
                    }
                    track.copy(
                        artist = resolvedArtist,
                        coverArtPath = resolvedCover,
                        albumTitle = resolvedAlbumTitle,
                    )
                }
            }
    }

    private fun bestThumbnailUrl(thumbnails: List<Image>): String? {
        val valid = thumbnails.filter { !it.url.isNullOrBlank() }
        val best = valid.filter { it.width > 0 && it.height > 0 }.maxByOrNull { it.width * it.height }
            ?: valid.lastOrNull()
        return upgradeArtworkUrl(best?.url)
    }

    fun resolveAudioStreamForUri(
        rawUri: String,
        fallbackTitle: String? = null,
        fallbackArtist: String? = null,
        trackId: String? = null,
        originalSourceUri: String? = null,
        dao: SoundLapaDao? = null,
        expectedDurationMs: Long = 0L,
    ): ResolvedAudioStream? {
        if (rawUri.isBlank()) return null

        val queryFromMetadata = listOfNotNull(
            fallbackArtist?.takeIf {
                it.isNotBlank() &&
                    !it.equals("SoundLapa", ignoreCase = true) &&
                    !it.equals("SoundLap", ignoreCase = true)
            },
            fallbackTitle?.takeIf { it.isNotBlank() },
        ).joinToString(" ").trim()

        // Local file check: validate header bytes so a corrupted cached HTML/m3u8 file never hits ExoPlayer
        if (rawUri.startsWith("file://")) {
            val path = Uri.parse(rawUri).path
            val localFile = path?.let { File(it) }
            if (localFile != null && isLikelyPlayableLocalFile(localFile)) {
                val ext = localFile.extension.lowercase().ifBlank { "mp3" }
                return ResolvedAudioStream(
                    directUrl = rawUri,
                    canonicalPageUrl = rawUri,
                    durationMs = 0L,
                    extension = ext,
                    isHls = false,
                    headers = emptyMap(),
                )
            }
            // Corrupt cached file -> delete, clear DB flag, and fall back to original or searched online stream
            localFile?.delete()
            if (!trackId.isNullOrBlank() && dao != null) {
                resolverScope.launch {
                    runCatching { dao.updateCacheStatus(trackId, false, null, 0L) }
                }
            }
            if (!originalSourceUri.isNullOrBlank() && !originalSourceUri.startsWith("file://")) {
                return resolveAudioStreamForUri(
                    rawUri = originalSourceUri,
                    fallbackTitle = fallbackTitle,
                    fallbackArtist = fallbackArtist,
                    trackId = trackId,
                    originalSourceUri = null,
                    dao = dao,
                )
            }
            if (queryFromMetadata.isNotBlank()) {
                return resolveByQuery(queryFromMetadata)
            }
            return null
        }

        if (rawUri.startsWith("content://") || rawUri.startsWith("android.resource://")) {
            return ResolvedAudioStream(
                directUrl = rawUri,
                canonicalPageUrl = rawUri,
                durationMs = 0L,
                extension = "mp3",
                isHls = false,
                headers = emptyMap(),
            )
        }

        getFreshCachedStream(rawUri)?.let { return it }

        if (rawUri.startsWith("soundlapa://resolve-full") ||
            rawUri.startsWith("soundlap://resolve-full") ||
            isResolvablePageOrUpgradeUri(rawUri)
        ) {
            val forceProbe = forceProbeKeys.remove(rawUri)
            val existingFuture = inFlightResolutions[rawUri]
            if (existingFuture != null && !forceProbe) {
                return runCatching { existingFuture.get(15, TimeUnit.SECONDS) }.getOrNull()
            }

            val createdFuture = CompletableFuture<ResolvedAudioStream?>()
            val racedFuture = if (!forceProbe) inFlightResolutions.putIfAbsent(rawUri, createdFuture) else null
            if (racedFuture != null) {
                return runCatching { racedFuture.get(15, TimeUnit.SECONDS) }.getOrNull()
            }

            try {
                val resolved: ResolvedAudioStream? = if (
                    rawUri.startsWith("soundlapa://resolve-full") ||
                    rawUri.startsWith("soundlap://resolve-full")
                ) {
                    val parsed = Uri.parse(rawUri)
                    val parsedTrackId = parsed.getQueryParameter("id")?.takeIf { it.isNotBlank() } ?: trackId.orEmpty()
                    val parsedArtist = parsed.getQueryParameter("artist")?.takeIf { it.isNotBlank() } ?: fallbackArtist
                    val parsedTitle = parsed.getQueryParameter("title")?.takeIf { it.isNotBlank() } ?: fallbackTitle
                    val parsedDuration = parsed.getQueryParameter("dur")?.toLongOrNull() ?: expectedDurationMs
                    val query = parsed.getQueryParameter("q")?.takeIf { it.isNotBlank() } ?: queryFromMetadata
                    if (query.isBlank()) {
                        null
                    } else {
                        val found = resolveByQuery(
                            query = query,
                            verifyWithProbe = forceProbe,
                            targetArtist = parsedArtist.orEmpty(),
                            targetTitle = parsedTitle.orEmpty(),
                            expectedDurationMs = parsedDuration,
                        )
                        if (found != null) {
                            putCachedStream(rawUri, found)
                            putCachedStream(found.canonicalPageUrl, found)
                            if (parsedTrackId.isNotBlank() && dao != null) {
                                resolverScope.launch {
                                    runCatching {
                                        dao.updateTrackStream(
                                            id = parsedTrackId,
                                            sourceUri = found.canonicalPageUrl,
                                            durationMs = found.durationMs,
                                        )
                                    }
                                }
                            }
                        }
                        found
                    }
                } else {
                    val found = resolveFromPageUrl(
                        pageUrl = rawUri,
                        fallbackQuery = queryFromMetadata.takeIf { it.isNotBlank() },
                        verifyWithProbe = forceProbe,
                    )
                    if (found != null) {
                        putCachedStream(rawUri, found)
                    }
                    found
                }
                createdFuture.complete(resolved)
                return resolved
            } catch (t: Throwable) {
                createdFuture.complete(null)
                return null
            } finally {
                inFlightResolutions.remove(rawUri, createdFuture)
            }
        }

        // Direct HTTP/HTTPS URLs (Audius stream endpoints, CDN URLs, preview URLs):
        // Probe once to resolve redirects, detect HLS (.m3u8), and reject broken HTML/JSON error pages.
        if (rawUri.startsWith("http://") || rawUri.startsWith("https://")) {
            val probed = probeStreamCandidate(
                candidateUrl = rawUri,
                expectedHls = rawUri.contains(".m3u8", ignoreCase = true),
            )
            if (probed != null) {
                val ext = when {
                    probed.isHls -> "m3u8"
                    probed.finalUrl.contains(".m4a", true) -> "m4a"
                    probed.finalUrl.contains(".webm", true) -> "webm"
                    else -> "mp3"
                }
                val resolved = ResolvedAudioStream(
                    directUrl = probed.finalUrl,
                    canonicalPageUrl = rawUri,
                    durationMs = 180_000L,
                    extension = ext,
                    isHls = probed.isHls,
                    headers = probed.headers,
                )
                putCachedStream(rawUri, resolved)
                return resolved
            }

            if (queryFromMetadata.isNotBlank()) {
                val fallback = resolveByQuery(queryFromMetadata)
                if (fallback != null) {
                    putCachedStream(rawUri, fallback)
                    return fallback
                }
            }
            return null
        }

        return null
    }

    fun resolveDataSpecUriBlocking(rawUri: String, dao: SoundLapaDao? = null): String? {
        if (!isResolvablePageOrUpgradeUri(rawUri)) return null
        return resolveAudioStreamForUri(rawUri = rawUri, dao = dao)?.directUrl
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
                isHls = rawUri.contains(".m3u8", true),
                headers = mapOf("User-Agent" to userAgentForStreamUrl(rawUri)),
            )
        }

        val resolved = resolveAudioStreamForUri(
            rawUri = rawUri,
            fallbackTitle = track.title,
            fallbackArtist = track.artist,
            trackId = track.id,
            dao = dao,
            expectedDurationMs = track.durationMs,
        ) ?: return null

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

    fun peekCachedOrDirectStreamSync(rawUri: String): ResolvedAudioStream? {
        if (rawUri.isBlank()) return null
        if (rawUri.startsWith("file://") || rawUri.startsWith("/")) {
            val localFile = runCatching {
                if (rawUri.startsWith("file://")) File(Uri.parse(rawUri).path.orEmpty()) else File(rawUri)
            }.getOrNull()
            if (localFile != null && isLikelyPlayableLocalFile(localFile)) {
                val ext = localFile.extension.lowercase().ifBlank { "mp3" }
                return ResolvedAudioStream(
                    directUrl = rawUri,
                    canonicalPageUrl = rawUri,
                    durationMs = 0L,
                    extension = ext,
                    isHls = false,
                    headers = emptyMap(),
                )
            }
            return null
        }
        if (rawUri.startsWith("content://") || rawUri.startsWith("android.resource://")) {
            return ResolvedAudioStream(
                directUrl = rawUri,
                canonicalPageUrl = rawUri,
                durationMs = 0L,
                extension = "mp3",
                isHls = false,
                headers = emptyMap(),
            )
        }
        return getFreshCachedStream(rawUri)
    }

    fun prefetchTracks(tracks: List<TrackEntity>, dao: SoundLapaDao? = null) {
        if (isDataSaverEnabled || isBatterySaverEnabled || tracks.isEmpty()) return
        tracks.take(3).forEach { track ->
            val uri = if (isShortPreviewTrack(track)) {
                buildLegacyUpgradeUri(track)
            } else {
                track.sourceUri
            }
            if (!uri.isNullOrBlank() && (isResolvablePageOrUpgradeUri(uri) || uri.contains("audius.co/", true))) {
                if (getFreshCachedStream(uri) == null) {
                    resolverScope.launch {
                        runCatching {
                            resolveAudioStreamForUri(
                                rawUri = uri,
                                fallbackTitle = track.title,
                                fallbackArtist = track.artist,
                                trackId = track.id,
                                dao = dao,
                                expectedDurationMs = track.durationMs,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun getFreshCachedStream(key: String): ResolvedAudioStream? {
        val entry = streamCache[key] ?: return null
        if (System.currentTimeMillis() < entry.expiresAtMs) {
            return entry.resolved
        }
        streamCache.remove(key)
        return null
    }

    private fun putCachedStream(key: String, resolved: ResolvedAudioStream) {
        if (key.isBlank()) return
        val defaultExpiry = System.currentTimeMillis() + CACHE_TTL_MS
        val urlExpireSec = runCatching {
            Uri.parse(resolved.directUrl).getQueryParameter("expire")?.toLongOrNull()
        }.getOrNull()
        val effectiveExpiry = if (urlExpireSec != null && urlExpireSec > 0L) {
            minOf(defaultExpiry, (urlExpireSec - 120L) * 1000L)
        } else {
            defaultExpiry
        }
        if (effectiveExpiry > System.currentTimeMillis() + 5_000L) {
            streamCache[key] = CachedStreamEntry(resolved, effectiveExpiry)
        }
    }

    fun transliterateToLatin(input: String): String {
        val sb = StringBuilder(input.length * 2)
        for (ch in input.lowercase()) {
            when (ch) {
                'а' -> sb.append("a")
                'б' -> sb.append("b")
                'в' -> sb.append("v")
                'г' -> sb.append("g")
                'д' -> sb.append("d")
                'е', 'ё', 'э' -> sb.append("e")
                'ж' -> sb.append("zh")
                'з' -> sb.append("z")
                'и', 'й', 'ы' -> sb.append("i")
                'к' -> sb.append("k")
                'л' -> sb.append("l")
                'м' -> sb.append("m")
                'н' -> sb.append("n")
                'о' -> sb.append("o")
                'п' -> sb.append("p")
                'р' -> sb.append("r")
                'с' -> sb.append("s")
                'т' -> sb.append("t")
                'у' -> sb.append("u")
                'ф' -> sb.append("f")
                'х' -> sb.append("h")
                'ц' -> sb.append("ts")
                'ч' -> sb.append("ch")
                'ш' -> sb.append("sh")
                'щ' -> sb.append("sch")
                'ъ', 'ь' -> Unit
                'ю' -> sb.append("yu")
                'я' -> sb.append("ya")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    internal fun extractArtistTitleFromQuery(rawQuery: String): Pair<String, String>? {
        val parts = rawQuery.trim().split(Regex("\\s+[-—–]\\s+"), limit = 2)
        if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
            return parts[0].trim() to parts[1].trim()
        }
        return null
    }

    private fun resolveByQuery(
        query: String,
        excludePageUrl: String? = null,
        verifyWithProbe: Boolean = false,
        targetArtist: String = "",
        targetTitle: String = "",
        expectedDurationMs: Long = 0L,
    ): ResolvedAudioStream? {
        ensureInitialized()
        val parsedPair = if (targetArtist.isBlank()) extractArtistTitleFromQuery(targetTitle.ifBlank { query }) else null
        val effArtist = targetArtist.trim().ifBlank { parsedPair?.first.orEmpty() }
        val effTitle = targetTitle.trim().ifBlank { parsedPair?.second ?: query.trim() }

        fun searchYouTubeVideosRaw(q: String): List<TrackEntity> {
            return runCatching {
                val extractor = ServiceList.YouTube.getSearchExtractor(q, listOf("videos"), "")
                extractor.fetchPage()
                extractor.initialPage.items
                    .filterIsInstance<StreamInfoItem>()
                    .filter { isValidFullTrackItem(it) && it.duration in MIN_FULL_TRACK_SECONDS..900L }
                    .take(6)
                    .mapNotNull { mapStreamItemToTrack(it, isYouTube = true) }
            }.getOrDefault(emptyList())
        }

        // Fetch concurrently across YTM music_songs, YouTube videos, and SoundCloud so indie/RU/rap tracks resolve accurately
        val ytmFut = CompletableFuture.supplyAsync { searchYouTubeMusic(query, limit = 8) }
        val ytVidFut = CompletableFuture.supplyAsync { searchYouTubeVideosRaw(query) }
        val scFut = CompletableFuture.supplyAsync { searchSoundCloud(query, limit = 8) }

        val pool = LinkedHashMap<String, TrackEntity>()
        listOf(ytmFut, ytVidFut, scFut).forEach { fut ->
            runCatching { fut.get(8, TimeUnit.SECONDS) }.getOrDefault(emptyList()).forEach { item ->
                val uri = item.sourceUri
                if (!uri.isNullOrBlank() && uri != excludePageUrl) {
                    pool.putIfAbsent(uri, item)
                }
            }
        }

        fun tryResolveCandidates(candidates: List<TrackEntity>): ResolvedAudioStream? {
            if (candidates.isEmpty()) return null
            val scoredList = candidates.map { cand ->
                cand to bestTrackMatchScoreConsideringSwap(cand, effArtist, effTitle, expectedDurationMs, parsedPair != null)
            }.sortedByDescending { it.second }

            val minRequiredScore = if (effArtist.isNotBlank() && effTitle.isNotBlank()) 240 else 60
            val qualified = scoredList.filter { it.second >= minRequiredScore }
            val orderedToTry = if (qualified.isNotEmpty()) {
                qualified
            } else {
                // Only fall back to clearly positive candidates; never play an unrelated track
                scoredList.filter { it.second >= 60 }.take(2)
            }
            for ((candidate, _) in orderedToTry.take(4)) {
                val pageUrl = candidate.sourceUri ?: continue
                resolveFromPageUrl(pageUrl, fallbackQuery = null, verifyWithProbe = verifyWithProbe)?.let { return it }
            }
            return null
        }

        tryResolveCandidates(pool.values.toList())?.let { return it }

        // Targeted exact-artist + title retry if initial broad search missed or scored poorly
        if (effArtist.isNotBlank() && effTitle.isNotBlank()) {
            val strictQuery = "\"$effArtist\" \"$effTitle\""
            val retryYtm = CompletableFuture.supplyAsync { searchYouTubeMusic(strictQuery, limit = 6) }
            val retrySc = CompletableFuture.supplyAsync { searchSoundCloud("$effArtist $effTitle", limit = 6) }
            val retryPool = LinkedHashMap<String, TrackEntity>()
            listOf(retryYtm, retrySc).forEach { fut ->
                runCatching { fut.get(6, TimeUnit.SECONDS) }.getOrDefault(emptyList()).forEach { item ->
                    val uri = item.sourceUri
                    if (!uri.isNullOrBlank() && uri != excludePageUrl) {
                        retryPool.putIfAbsent(uri, item)
                    }
                }
            }
            tryResolveCandidates(retryPool.values.toList())?.let { return it }
        }

        // Retry with swapped artist/title ordering if "Left - Right" might be "Title - Artist"
        if (parsedPair != null) {
            val flippedQuery = "${parsedPair.second} ${parsedPair.first}".trim()
            val retryYtm = CompletableFuture.supplyAsync { searchYouTubeMusic(flippedQuery, limit = 6) }
            val retrySc = CompletableFuture.supplyAsync { searchSoundCloud(flippedQuery, limit = 6) }
            val retryPool = LinkedHashMap<String, TrackEntity>()
            listOf(retryYtm, retrySc).forEach { fut ->
                runCatching { fut.get(6, TimeUnit.SECONDS) }.getOrDefault(emptyList()).forEach { item ->
                    val uri = item.sourceUri
                    if (!uri.isNullOrBlank() && uri != excludePageUrl) {
                        retryPool.putIfAbsent(uri, item)
                    }
                }
            }
            tryResolveCandidates(retryPool.values.toList())?.let { return it }
        }
        return null
    }

    private fun bestTrackMatchScoreConsideringSwap(
        candidate: TrackEntity,
        artist: String,
        title: String,
        expectedDurationMs: Long,
        allowSwapped: Boolean,
    ): Int {
        val direct = scoreTrackMatch(candidate, artist, title, expectedDurationMs)
        if (!allowSwapped || artist.isBlank() || title.isBlank()) return direct
        val swapped = scoreTrackMatch(candidate, title, artist, expectedDurationMs)
        return maxOf(direct, swapped)
    }

    fun isUnwantedVariantTitle(candidateTitle: String, allowedReference: String = ""): Boolean {
        val candLower = candidateTitle.lowercase()
        val refLower = allowedReference.lowercase()
        val markers = listOf(
            "remix", "ремикс", "slowed", "замедл", "reverb", "nightcore",
            "sped up", "speed up", "ускорен", "8d audio", "8d", "bass boosted",
            "karaoke", "караоке", "type beat", "тип бит", "reaction", "реакция",
            "кавер", "tribute", "mashup", "bootleg", "chopped", "screwed",
        )
        return markers.any { m -> candLower.contains(m) && !refLower.contains(m) }
    }

    fun scoreTrackMatch(
        candidate: TrackEntity,
        targetArtist: String,
        targetTitle: String,
        expectedDurationMs: Long = 0L,
    ): Int {
        val cleanTitle = targetTitle.trim()
        val cleanArtist = targetArtist.trim()
        val refQuery = "$cleanArtist $cleanTitle".lowercase()
        val candTitleLower = candidate.title.lowercase()
        val candArtistLower = candidate.artist.lowercase()

        var score = 0
        // Strong negative penalty against remixes, slowed/reverb, nightcore, sped up, covers, karaoke, etc.
        val heavyNegative = listOf(
            "remix", "ремикс", "slowed", "замедл", "reverb", "nightcore",
            "sped up", "speed up", "ускорен", "8d", "bass boosted",
            "karaoke", "караоке", "type beat", "reaction", "реакция",
            "cover", "кавер", "tribute", "mashup", "bootleg", "acapella",
        )
        for (marker in heavyNegative) {
            if ((candTitleLower.contains(marker) || candArtistLower.contains(marker)) && !refQuery.contains(marker)) {
                score -= 900
            }
        }
        val softNegative = listOf("live", "instrumental", "edit", "pitched", "concert", "session")
        for (marker in softNegative) {
            if (candTitleLower.contains(marker) && !refQuery.contains(marker)) {
                score -= 350
            }
        }

        val normTargetTitle = cleanTitle.lowercase().replace('ё', 'е').replace(Regex("[^\\p{L}\\p{Nd}]"), "")
        val latinTargetTitle = transliterateToLatin(cleanTitle).replace(Regex("[^a-z0-9]"), "")
        val strippedCandTitle = candidate.title
            .replace(Regex("\\s*[(\\[](?:official|audio|music video|lyric|prod\\.?).*?[)\\]]", RegexOption.IGNORE_CASE), "")
            .trim()
        val normCandTitle = strippedCandTitle.lowercase().replace('ё', 'е').replace(Regex("[^\\p{L}\\p{Nd}]"), "")
        val latinCandTitle = transliterateToLatin(strippedCandTitle).replace(Regex("[^a-z0-9]"), "")
        val latinFullCand = transliterateToLatin("$candArtistLower $candTitleLower").replace(Regex("[^a-z0-9]"), "")

        var titleMatched = false
        if (normTargetTitle.isNotBlank()) {
            when {
                normCandTitle == normTargetTitle ||
                    (latinTargetTitle.isNotBlank() && latinCandTitle == latinTargetTitle) -> {
                    score += 480
                    titleMatched = true
                }
                normCandTitle.contains(normTargetTitle) || normTargetTitle.contains(normCandTitle) ||
                    (latinTargetTitle.length >= 3 && (latinCandTitle.contains(latinTargetTitle) || latinFullCand.contains(latinTargetTitle))) -> {
                    score += 300
                    titleMatched = true
                }
                else -> {
                    val targetTokens = cleanTitle.lowercase().replace('ё', 'е')
                        .split(Regex("[^\\p{L}\\p{Nd}]+")).filter { it.length >= 2 }
                    val candTokens = (candTitleLower + " " + candArtistLower).replace('ё', 'е')
                        .split(Regex("[^\\p{L}\\p{Nd}]+")).filter { it.length >= 2 }.toSet()
                    val latinTargetTokens = targetTokens.map { transliterateToLatin(it) }.filter { it.length >= 2 }
                    val latinCandTokens = candTokens.map { transliterateToLatin(it) }.toSet()
                    if (targetTokens.isNotEmpty()) {
                        val overlap = targetTokens.indices.count { i ->
                            targetTokens[i] in candTokens ||
                                (i < latinTargetTokens.size && latinTargetTokens[i] in latinCandTokens)
                        }
                        if (overlap > 0 && overlap * 2 >= targetTokens.size) {
                            score += (overlap * 210) / targetTokens.size
                            titleMatched = true
                        } else {
                            // Never let a random song by the right artist win when the title missed
                            score -= 650
                        }
                    }
                }
            }
        }

        if (cleanArtist.isNotBlank()) {
            val requestedArtists = cleanArtist
                .split(Regex("\\s*(?:,|&|feat\\.|ft\\.|featuring|x|×)\\s*", RegexOption.IGNORE_CASE))
                .map { it.trim() }
                .filter { it.length >= 2 }
                .ifEmpty { listOf(cleanArtist) }

            val normCandArtist = candArtistLower.replace('ё', 'е').replace(Regex("[^\\p{L}\\p{Nd}]"), "")
            val latinCandArtist = transliterateToLatin(candArtistLower).replace(Regex("[^a-z0-9]"), "")
            val normFullTitleAndArtist = "${candArtistLower} ${candTitleLower}"
                .replace('ё', 'е').replace(Regex("[^\\p{L}\\p{Nd}]"), "")

            var bestArtistBonus = Int.MIN_VALUE
            for (reqArt in requestedArtists) {
                val normReq = reqArt.lowercase().replace('ё', 'е').replace(Regex("[^\\p{L}\\p{Nd}]"), "")
                val latinReq = transliterateToLatin(reqArt).replace(Regex("[^a-z0-9]"), "")
                val bonus = when {
                    normReq.isNotBlank() &&
                        (normCandArtist == normReq || (latinReq.isNotBlank() && latinCandArtist == latinReq)) -> 360
                    normReq.isNotBlank() &&
                        (normCandArtist.contains(normReq) || (latinReq.length >= 3 && latinCandArtist.contains(latinReq))) -> 260
                    normReq.isNotBlank() &&
                        (normFullTitleAndArtist.contains(normReq) || (latinReq.length >= 3 && latinFullCand.contains(latinReq))) -> 190
                    else -> Int.MIN_VALUE
                }
                if (bonus > bestArtistBonus) bestArtistBonus = bonus
            }
            if (bestArtistBonus != Int.MIN_VALUE) {
                score += bestArtistBonus
            } else {
                // Strong mismatch penalty when candidate does not match any requested artist
                score -= if (!titleMatched) 700 else 480
            }
        }

        if (expectedDurationMs >= 30_000L && candidate.durationMs >= 30_000L) {
            val deltaSec = abs(candidate.durationMs - expectedDurationMs) / 1000L
            score += when {
                deltaSec <= 5L -> 240
                deltaSec <= 12L -> 150
                deltaSec <= 25L -> 40
                deltaSec > 65L -> -320
                else -> 0
            }
        }

        if (candidate.id.startsWith("yt_") || candidate.id.startsWith("sc_")) {
            score += 40
        }
        return score
    }

    fun rankTrackMatchCandidates(
        candidates: List<TrackEntity>,
        targetArtist: String,
        targetTitle: String,
        expectedDurationMs: Long = 0L,
    ): List<TrackEntity> {
        if (candidates.size <= 1) return candidates
        return candidates.sortedByDescending {
            scoreTrackMatch(it, targetArtist, targetTitle, expectedDurationMs)
        }
    }

    fun pickBestTrackMatch(
        candidates: List<TrackEntity>,
        targetArtist: String,
        targetTitle: String,
        expectedDurationMs: Long = 0L,
    ): TrackEntity? = rankTrackMatchCandidates(candidates, targetArtist, targetTitle, expectedDurationMs).firstOrNull()

    fun computeSynchronizedVideoPositionMs(
        audioPositionMs: Long,
        audioDurationMs: Long,
        clipDurationMs: Long,
    ): Long {
        if (clipDurationMs <= 0L) return audioPositionMs.coerceAtLeast(0L)
        if (audioDurationMs <= 0L) return audioPositionMs.coerceIn(0L, clipDurationMs)

        val safeAudioPos = audioPositionMs.coerceIn(0L, audioDurationMs)
        val diffMs = kotlin.math.abs(clipDurationMs - audioDurationMs)
        if (diffMs <= 2_000L) {
            return safeAudioPos.coerceIn(0L, clipDurationMs)
        }

        val ratio = clipDurationMs.toDouble() / audioDurationMs.toDouble()
        // Smooth proportional time mapping across the entire song duration (prevents abrupt jump desync)
        if (ratio in 0.65..1.45) {
            val fraction = (safeAudioPos.toDouble() / audioDurationMs.toDouble()).coerceIn(0.0, 1.0)
            return (fraction * clipDurationMs.toDouble()).toLong().coerceIn(0L, clipDurationMs)
        }

        // Short visual loop / teaser compared to track length
        return safeAudioPos % clipDurationMs
    }

    fun computeSynchronizedVideoPlaybackSpeed(
        audioDurationMs: Long,
        clipDurationMs: Long,
    ): Float {
        if (audioDurationMs <= 2_000L || clipDurationMs <= 2_000L) return 1.0f
        if (kotlin.math.abs(clipDurationMs - audioDurationMs) <= 2_000L) return 1.0f
        val ratio = clipDurationMs.toFloat() / audioDurationMs.toFloat()
        return if (ratio in 0.72f..1.38f) ratio.coerceIn(0.85f, 1.18f) else 1.0f
    }

    fun findOfficialMusicVideoStream(
        artist: String,
        title: String,
        expectedDurationMs: Long = 0L,
        trackId: String? = null,
        sourceUri: String? = null,
    ): ResolvedVideoStream? {
        ensureInitialized()
        val cleanTitle = title
            .replace(Regex("\\s*[(\\[](?:feat\\.?|ft\\.?|official|audio).*?[)\\]]", RegexOption.IGNORE_CASE), "")
            .trim()
            .ifBlank { title.trim() }
        val cleanArtist = artist
            .split(Regex("\\s*(?:,|&|feat\\.|ft\\.)\\s*", RegexOption.IGNORE_CASE))
            .firstOrNull().orEmpty().trim()
        if (cleanTitle.isBlank()) return null

        val cacheKey = "${trackId.orEmpty()}|${cleanArtist.lowercase()}|${cleanTitle.lowercase()}"
        val nowMs = System.currentTimeMillis()
        musicVideoCache[cacheKey]?.let { cached ->
            if (cached.expiresAtMs > nowMs) return cached.resolved
            musicVideoCache.remove(cacheKey)
        }
        musicVideoMissCache[cacheKey]?.let { missExpiry ->
            if (missExpiry > nowMs) return null
            musicVideoMissCache.remove(cacheKey)
        }

        val resolved = resolveMusicVideoWithFallback(
            spotifyLookup = {
                SpotifyCatalogClient.resolveTrackClip(
                    artist = artist,
                    title = cleanTitle,
                    durationMs = expectedDurationMs,
                    sourceUri = sourceUri,
                    trackIdHint = trackId,
                )?.let { spClip ->
                    ResolvedVideoStream(
                        videoUrl = spClip.videoUrl,
                        title = spClip.title.ifBlank { cleanTitle },
                        durationMs = spClip.durationMs,
                        sourcePageUrl = spClip.sourcePageUrl,
                        headers = spClip.headers,
                        width = spClip.width,
                        height = spClip.height,
                        audioUrl = null,
                        isMuxed = spClip.isMuxed,
                        isLoopingCanvas = spClip.isLoopingCanvas,
                        sourceKind = if (spClip.isLoopingCanvas) {
                            VideoStreamSource.SPOTIFY_CANVAS
                        } else {
                            VideoStreamSource.SPOTIFY_VIDEO
                        },
                    )
                }
            },
            youtubeMusicFallback = {
                findYouTubeMusicOfficialVideoStream(
                    cleanArtist = cleanArtist,
                    cleanTitle = cleanTitle,
                    expectedDurationMs = expectedDurationMs,
                )
            },
        )

        if (resolved != null) {
            musicVideoCache[cacheKey] = CachedVideoEntry(
                resolved = resolved,
                expiresAtMs = System.currentTimeMillis() + 60L * 60L * 1000L,
            )
            return resolved
        }

        musicVideoMissCache[cacheKey] = System.currentTimeMillis() + 10L * 60L * 1000L
        return null
    }

    internal inline fun resolveMusicVideoWithFallback(
        spotifyLookup: () -> ResolvedVideoStream?,
        youtubeMusicFallback: () -> ResolvedVideoStream?,
    ): ResolvedVideoStream? {
        return runCatching { spotifyLookup() }.getOrNull()
            ?: runCatching { youtubeMusicFallback() }.getOrNull()
    }

    private fun findYouTubeMusicOfficialVideoStream(
        cleanArtist: String,
        cleanTitle: String,
        expectedDurationMs: Long = 0L,
    ): ResolvedVideoStream? {

        val baseQuery = "$cleanArtist $cleanTitle".trim()
        val mvQuery = "$baseQuery official music video".trim()

        // Search YouTube Music music_videos first and YouTube videos as fallback concurrently
        val ytmVideosFut = CompletableFuture.supplyAsync {
            runCatching {
                val extractor = ServiceList.YouTube.getSearchExtractor(baseQuery, listOf("music_videos"), "")
                extractor.fetchPage()
                extractor.initialPage.items
                    .filterIsInstance<StreamInfoItem>()
                    .filter {
                        !it.url.isNullOrBlank() &&
                            !it.name.isNullOrBlank() &&
                            it.streamType != StreamType.LIVE_STREAM &&
                            it.duration in 30L..900L
                    }
                    .take(8)
            }.getOrDefault(emptyList())
        }
        val ytVideosFut = CompletableFuture.supplyAsync {
            runCatching {
                val extractor = ServiceList.YouTube.getSearchExtractor(mvQuery, listOf("videos"), "")
                extractor.fetchPage()
                extractor.initialPage.items
                    .filterIsInstance<StreamInfoItem>()
                    .filter {
                        !it.url.isNullOrBlank() &&
                            !it.name.isNullOrBlank() &&
                            it.streamType != StreamType.LIVE_STREAM &&
                            it.duration in 30L..900L
                    }
                    .take(6)
            }.getOrDefault(emptyList())
        }

        val ytmItems = runCatching { ytmVideosFut.get(5, TimeUnit.SECONDS) }.getOrDefault(emptyList())
        val ytItems = runCatching { ytVideosFut.get(5, TimeUnit.SECONDS) }.getOrDefault(emptyList())
        val candidates = (ytmItems + ytItems).distinctBy { extractYouTubeVideoId(it.url) ?: it.url }

        if (candidates.isEmpty()) {
            return null
        }

        val normTitle = cleanTitle.lowercase().replace('ё', 'е').replace(Regex("[^\\p{L}\\p{Nd}]"), "")
        val latinTitle = transliterateToLatin(cleanTitle).replace(Regex("[^a-z0-9]"), "")
        val titleWords = cleanTitle.lowercase().replace('ё', 'е').split(Regex("[^\\p{L}\\p{Nd}]+")).filter { it.length >= 2 }
        val normArtist = cleanArtist.lowercase().replace('ё', 'е').replace(Regex("[^\\p{L}\\p{Nd}]"), "")
        val latinArtist = transliterateToLatin(cleanArtist).replace(Regex("[^a-z0-9]"), "")
        val refLower = "$cleanArtist $cleanTitle".lowercase()
        val ytmKeySet = ytmItems.mapNotNullTo(HashSet()) { extractYouTubeVideoId(it.url) ?: it.url }

        val scoredCandidates = candidates.mapNotNull { item ->
            val nameRaw = item.name.orEmpty()
            val nameLower = nameRaw.lowercase().replace('ё', 'е')
            val normName = nameLower.replace(Regex("[^\\p{L}\\p{Nd}]"), "")
            val latinName = transliterateToLatin(nameLower).replace(Regex("[^a-z0-9]"), "")
            val uploaderRaw = item.uploaderName.orEmpty().lowercase().replace('ё', 'е')
            val uploaderLower = uploaderRaw.replace(Regex("[^\\p{L}\\p{Nd}]"), "")
            val latinUploader = transliterateToLatin(uploaderRaw).replace(Regex("[^a-z0-9]"), "")

            val hasTitleExact = (normTitle.isNotBlank() && normName.contains(normTitle)) ||
                (latinTitle.length >= 3 && latinName.contains(latinTitle))
            val nameWordSet = nameLower.split(Regex("[^\\p{L}\\p{Nd}]+")).filter { it.length >= 2 }.toSet()
            val hasTitleTokens = titleWords.isNotEmpty() && titleWords.count { it in nameWordSet } * 2 >= titleWords.size
            if (!hasTitleExact && !hasTitleTokens) {
                return@mapNotNull null
            }

            val hasArtist = normArtist.isBlank() ||
                uploaderLower.contains(normArtist) ||
                normName.contains(normArtist) ||
                (latinArtist.length >= 3 && (latinUploader.contains(latinArtist) || latinName.contains(latinArtist)))
            // Require strict artist match so unrelated artists' music videos are never attached
            if (!hasArtist) {
                return@mapNotNull null
            }
            if (isUnwantedVariantTitle(nameRaw, refLower) || nameLower.contains("lyric") || nameLower.contains("audio only")) {
                return@mapNotNull null
            }
            if (expectedDurationMs >= 30_000L && item.duration > 0L) {
                val clipMs = item.duration * 1000L
                val ratio = clipMs.toDouble() / expectedDurationMs.toDouble()
                if (ratio < 0.45 || ratio > 2.0) {
                    return@mapNotNull null
                }
            }

            val candidateKey = extractYouTubeVideoId(item.url) ?: item.url
            val isFromYtmVideos = candidateKey != null && candidateKey in ytmKeySet
            var s = if (isFromYtmVideos) 280 else 0
            if (nameLower.contains("official") && (nameLower.contains("video") || nameLower.contains("mv") || nameLower.contains("клип"))) s += 320
            else if (nameLower.contains("music video") || nameLower.contains("клип") || nameLower.contains("official mv")) s += 240
            if (hasTitleExact) s += 200 else if (hasTitleTokens) s += 110
            if (hasArtist) s += 180
            if (s < 320) null else item to s
        }.sortedByDescending { it.second }

        val ranked = scoredCandidates.map { it.first }
        if (ranked.isEmpty()) {
            return null
        }

        fun scoreVideoStreamCandidate(height: Int, codec: String?): Int {
            val isAvc = codec.isNullOrBlank() ||
                codec.contains("avc", ignoreCase = true) ||
                codec.contains("h264", ignoreCase = true)
            val codecBonus = if (isAvc) 2000 else 0
            val resScore = when (height) {
                in 460..540 -> 950
                in 340..459 -> 880
                in 541..740 -> 780
                in 220..339 -> 500
                else -> 250
            }
            return codecBonus + resScore
        }

        fun extractCandidate(cand: StreamInfoItem): ResolvedVideoStream? {
            val pageUrl = cand.url ?: return null
            val info = runCatching { StreamInfo.getInfo(ServiceList.YouTube, pageUrl) }.getOrNull() ?: return null
            val muxedMp4 = info.videoStreams
                .filter {
                    it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP &&
                        !it.content.isNullOrBlank() &&
                        it.format == MediaFormat.MPEG_4
                }
                .maxByOrNull { stream ->
                    val h = Regex("(\\d+)").find(stream.resolution.orEmpty())?.groupValues?.get(1)?.toIntOrNull() ?: 360
                    scoreVideoStreamCandidate(h, stream.codec)
                }
            val videoOnlyMp4 = info.videoOnlyStreams
                .filter {
                    it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP &&
                        !it.content.isNullOrBlank() &&
                        it.format == MediaFormat.MPEG_4
                }
                .maxByOrNull { stream ->
                    val h = Regex("(\\d+)").find(stream.resolution.orEmpty())?.groupValues?.get(1)?.toIntOrNull() ?: 360
                    scoreVideoStreamCandidate(h, stream.codec) + 120
                }
            val pairedAudio = sortProgressiveAudioStreams(info.audioStreams)
                .firstOrNull { stream ->
                    !stream.content.isNullOrBlank() &&
                        (stream.format == MediaFormat.M4A || stream.format == MediaFormat.MPEG_4)
                }
                ?: sortProgressiveAudioStreams(info.audioStreams).firstOrNull { !it.content.isNullOrBlank() }

            val usingVideoOnly = videoOnlyMp4 != null && (pairedAudio != null || muxedMp4 == null)
            val chosen = if (usingVideoOnly) videoOnlyMp4 else (muxedMp4 ?: videoOnlyMp4)
            chosen ?: return null
            val url = chosen.content ?: return null
            val isMuxed = !usingVideoOnly && chosen === muxedMp4
            val resolvedAudioUrl = if (isMuxed) null else pairedAudio?.content
            return ResolvedVideoStream(
                videoUrl = url,
                title = info.name ?: cand.name ?: cleanTitle,
                durationMs = (info.duration.takeIf { it > 0L } ?: 180L) * 1000L,
                sourcePageUrl = pageUrl,
                headers = mapOf("User-Agent" to userAgentForStreamUrl(url)),
                width = chosen.width.coerceAtLeast(0),
                height = chosen.height.coerceAtLeast(0),
                audioUrl = resolvedAudioUrl,
                isMuxed = isMuxed,
                isLoopingCanvas = false,
                sourceKind = VideoStreamSource.YOUTUBE_MUSIC,
            )
        }

        val topCandidates = ranked.take(2)
        val futures = topCandidates.map { cand ->
            CompletableFuture.supplyAsync { extractCandidate(cand) }
        }
        for (future in futures) {
            val resolved = runCatching { future.get(6, TimeUnit.SECONDS) }.getOrNull()
            if (resolved != null) {
                return resolved
            }
        }
        return null
    }

    private data class ProbedStream(
        val finalUrl: String,
        val isHls: Boolean,
        val headers: Map<String, String>,
    )

    private fun probeStreamCandidate(
        candidateUrl: String,
        expectedHls: Boolean,
    ): ProbedStream? {
        val ua = userAgentForStreamUrl(candidateUrl)
        val headers = mapOf("User-Agent" to ua)
        return runCatching {
            val reqBuilder = okhttp3.Request.Builder()
                .url(candidateUrl)
                .header("User-Agent", ua)
                .header("Accept", "*/*")
            if (!expectedHls && !candidateUrl.contains(".m3u8", ignoreCase = true)) {
                reqBuilder.header("Range", "bytes=0-255")
            }

            probeClient.newCall(reqBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                val finalUrl = response.request.url.toString()
                val contentType = response.header("Content-Type")
                val body = response.body ?: return@runCatching null
                val source = body.source()
                source.request(256L)
                val available = minOf(256L, source.buffer.size).toInt()
                if (available <= 0 && body.contentLength() == 0L) {
                    return@runCatching null
                }
                val headBytes = if (available > 0) {
                    source.buffer.clone().readByteArray(available.toLong())
                } else {
                    ByteArray(0)
                }
                val kind = classifyStreamProbe(
                    finalUrl = finalUrl,
                    contentType = contentType,
                    headBytes = headBytes,
                    expectedHls = expectedHls,
                )
                when (kind) {
                    StreamPayloadKind.REJECTED -> null
                    StreamPayloadKind.HLS_PLAYLIST -> ProbedStream(finalUrl, isHls = true, headers = headers)
                    StreamPayloadKind.PROGRESSIVE_AUDIO -> ProbedStream(finalUrl, isHls = false, headers = headers)
                }
            }
        }.getOrNull()
    }

    private fun isTrustedExtractedCdnUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("googlevideo.com/videoplayback") ||
            lower.contains("cf-media.sndcdn.com") ||
            lower.contains("sndcdn.com/media")
    }

    private fun resolveFromPageUrl(
        pageUrl: String,
        fallbackQuery: String? = null,
        verifyWithProbe: Boolean = false,
    ): ResolvedAudioStream? {
        ensureInitialized()
        val service: StreamingService = when {
            pageUrl.contains("soundcloud.com", ignoreCase = true) -> ServiceList.SoundCloud
            else -> ServiceList.YouTube
        }

        val info = runCatching { StreamInfo.getInfo(service, pageUrl) }.getOrNull()
        if (info != null) {
            val durationMs = (info.duration.takeIf { it > 0 } ?: 180L) * 1000L

            // 1) Try ranked progressive HTTP audio streams first (fast seeking & instant startup)
            val progressiveCandidates = sortProgressiveAudioStreams(info.audioStreams)
            for (stream in progressiveCandidates.take(4)) {
                val streamUrl = stream.content?.takeIf { it.isNotBlank() } ?: continue
                val ext = when (stream.format) {
                    MediaFormat.M4A, MediaFormat.MPEG_4 -> "m4a"
                    MediaFormat.WEBMA, MediaFormat.WEBMA_OPUS -> "webm"
                    MediaFormat.MP3 -> "mp3"
                    else -> "m4a"
                }

                // Skip blocking HTTP probe on fresh signed CDN URLs to shave 0.5-1.5s off startup time;
                // fall back to probing if a previous attempt on this track errored out.
                if (!verifyWithProbe && isTrustedExtractedCdnUrl(streamUrl) && !streamUrl.contains(".m3u8", true)) {
                    return ResolvedAudioStream(
                        directUrl = streamUrl,
                        canonicalPageUrl = pageUrl,
                        durationMs = durationMs,
                        extension = ext,
                        isHls = false,
                        headers = mapOf("User-Agent" to userAgentForStreamUrl(streamUrl)),
                    )
                }

                val probed = probeStreamCandidate(streamUrl, expectedHls = false)
                if (probed != null) {
                    return ResolvedAudioStream(
                        directUrl = probed.finalUrl,
                        canonicalPageUrl = pageUrl,
                        durationMs = durationMs,
                        extension = if (probed.isHls) "m3u8" else ext,
                        isHls = probed.isHls,
                        headers = probed.headers,
                    )
                }
            }

            // 2) Try HLS audio streams / manifest (critical for SoundCloud & fallback YouTube items)
            val hlsCandidates = buildList {
                val hlsStreams = info.audioStreams
                    .filter { it.deliveryMethod == DeliveryMethod.HLS && !it.content.isNullOrBlank() }
                val sortedHls = if (isDataSaverEnabled) {
                    hlsStreams.sortedBy { stream ->
                        val br = stream.averageBitrate.takeIf { it > 0 } ?: stream.bitrate
                        if (br in 1..47) Int.MAX_VALUE else br
                    }
                } else {
                    hlsStreams.sortedByDescending { it.averageBitrate.takeIf { br -> br > 0 } ?: it.bitrate }
                }
                sortedHls.mapNotNullTo(this) { it.content }
                info.hlsUrl?.takeIf { it.isNotBlank() }?.let { add(it) }
            }.distinct()

            for (hlsUrl in hlsCandidates.take(3)) {
                val probed = probeStreamCandidate(hlsUrl, expectedHls = true)
                if (probed != null) {
                    return ResolvedAudioStream(
                        directUrl = probed.finalUrl,
                        canonicalPageUrl = pageUrl,
                        durationMs = durationMs,
                        extension = "m3u8",
                        isHls = true,
                        headers = probed.headers,
                    )
                }
            }
        }

        // 3) Cross-service or query fallback if page extraction failed or all candidate URLs failed probe
        val derivedQuery = fallbackQuery?.takeIf { it.isNotBlank() }
            ?: info?.let { "${it.uploaderName.orEmpty()} ${it.name.orEmpty()}".trim() }
                ?.takeIf { it.isNotBlank() }
        if (!derivedQuery.isNullOrBlank()) {
            return resolveByQuery(derivedQuery, excludePageUrl = pageUrl, verifyWithProbe = verifyWithProbe)
        }

        return null
    }

    private fun sortProgressiveAudioStreams(streams: List<AudioStream>): List<AudioStream> {
        val progressive = streams.filter {
            it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && !it.content.isNullOrBlank()
        }
        return if (isDataSaverEnabled) {
            progressive.sortedWith(
                compareBy<AudioStream> { stream ->
                    val br = stream.averageBitrate.takeIf { it > 0 } ?: stream.bitrate
                    if (br in 1..47) Int.MAX_VALUE else br
                }.thenByDescending { stream ->
                    when (stream.format) {
                        MediaFormat.WEBMA_OPUS, MediaFormat.WEBMA -> 3
                        MediaFormat.M4A, MediaFormat.MPEG_4 -> 2
                        MediaFormat.MP3 -> 1
                        else -> 0
                    }
                },
            )
        } else {
            progressive.sortedWith(
                compareByDescending<AudioStream> { stream ->
                    when (stream.format) {
                        MediaFormat.M4A, MediaFormat.MPEG_4 -> 3
                        MediaFormat.WEBMA_OPUS, MediaFormat.WEBMA -> 2
                        MediaFormat.MP3 -> 1
                        else -> 0
                    }
                }.thenByDescending { it.averageBitrate.takeIf { br -> br > 0 } ?: it.bitrate },
            )
        }
    }

    private fun isValidFullTrackItem(item: StreamInfoItem): Boolean {
        if (item.url.isNullOrBlank() || item.name.isNullOrBlank()) return false
        if (item.streamType == StreamType.LIVE_STREAM || item.streamType == StreamType.AUDIO_LIVE_STREAM) {
            return false
        }
        return item.duration >= MIN_FULL_TRACK_SECONDS
    }

    fun upgradeArtworkUrl(
        rawUrl: String?,
        videoId: String? = null,
        dataSaver: Boolean = isDataSaverEnabled,
    ): String? {
        val url = rawUrl?.trim()?.takeIf { it.isNotBlank() }
        if (url == null) {
            val ytPreset = if (dataSaver) "hqdefault.jpg" else "maxresdefault.jpg"
            return videoId?.takeIf { it.isNotBlank() }?.let { "https://i.ytimg.com/vi/$it/$ytPreset" }
        }
        val lower = url.lowercase()

        // YouTube Music / GoogleUserContent square album artwork
        if (lower.contains("googleusercontent.com") || lower.contains("ggpht.com")) {
            val sizeParam = if (dataSaver) "=w360-h360-l80-rj" else "=w1000-h1000-l90-rj"
            val sParam = if (dataSaver) "=s360-c-k-c0x00ffffff-no-rj" else "=s1000-c-k-c0x00ffffff-no-rj"
            val upgraded = url
                .replace(Regex("=w\\d+-h\\d+[^/?&]*"), sizeParam)
                .replace(Regex("=s\\d+[^/?&]*"), sParam)
            return if (upgraded != url) upgraded else if (!url.contains("=")) "$url$sizeParam" else url
        }

        // SoundCloud artwork (-large is 100x100; -t300x300 for data saver, -t500x500 for HD)
        if (lower.contains("sndcdn.com/artworks")) {
            val scPreset = if (dataSaver) "-t300x300" else "-t500x500"
            return url.replace(
                Regex("-(?:tiny|small|badge|mini|large|t50x50|t67x67|t120x120|t200x200|t300x300|t500x500)(\\.[a-zA-Z0-9]+)((?:\\?.*)?)$"),
                "$scPreset$1$2",
            )
        }

        // Standard YouTube video thumbnails: strip signed sqp/rs params
        val ytThumbMatch = Regex("https?://[^/]*ytimg\\.com/vi(?:_webp)?/([^/]+)/[^/?]+\\.(?:jpg|webp)(?:\\?.*)?$", RegexOption.IGNORE_CASE)
            .matchEntire(url)
        if (ytThumbMatch != null) {
            val ytId = ytThumbMatch.groupValues[1]
            val ytPreset = if (dataSaver) "hqdefault.jpg" else "maxresdefault.jpg"
            return "https://i.ytimg.com/vi/$ytId/$ytPreset"
        }

        return url
    }

    fun fallbackArtworkUrl(rawUrl: String?): String? {
        val url = rawUrl?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val lower = url.lowercase()
        if (lower.contains("ytimg.com/vi") && lower.contains("/maxresdefault.")) {
            return url.replace("/maxresdefault.", "/hqdefault.")
        }
        if (lower.contains("sndcdn.com/artworks") && lower.contains("-t500x500.")) {
            return url.replace("-t500x500.", "-large.")
        }
        return null
    }

    internal fun mapStreamItemToTrack(item: StreamInfoItem, isYouTube: Boolean): TrackEntity? {
        val pageUrl = item.url ?: return null
        val rawTitle = item.name?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val rawUploader = item.uploaderName?.trim().orEmpty()
            .removeSuffix(" - Topic")
            .trim()
            .ifBlank { if (isYouTube) "YouTube Music" else "SoundCloud" }

        val videoId = if (isYouTube) extractYouTubeVideoId(pageUrl) else null
        val id = if (isYouTube) {
            "yt_${videoId ?: abs(pageUrl.hashCode()).toString()}"
        } else {
            "sc_${abs(pageUrl.hashCode())}"
        }

        val validThumbs = item.thumbnails.filter { !it.url.isNullOrBlank() }
        val knownSizedBest = validThumbs
            .filter { it.width > 0 && it.height > 0 }
            .maxByOrNull { it.width * it.height }
        val rawThumbUrl = knownSizedBest?.url ?: validThumbs.lastOrNull()?.url
        val artworkUrl = upgradeArtworkUrl(rawThumbUrl, videoId = videoId)

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
            0xFF8B5CF6L to 0xFFA78BFAL,
            0xFF5CE1E6L to 0xFFA78BFAL,
            0xFF34D399L to 0xFF5CE1E6L,
            0xFFA78BFAL to 0xFFFB7185L,
            0xFF7C3AEDL to 0xFF5CE1E6L,
        )
        val idx = abs(seed.hashCode()) % palettes.size
        return palettes[idx]
    }
}
