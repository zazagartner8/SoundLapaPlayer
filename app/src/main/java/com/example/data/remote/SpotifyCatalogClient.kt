package com.example.data.remote

import com.example.data.local.ExplicitContentDetector
import com.example.data.local.TrackEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs

data class SpotifyArtistInfo(
    val id: String,
    val name: String,
    val avatarUrl: String?,
    val followers: Int = 0,
    val genres: List<String> = emptyList(),
)

internal data class SpotifyParsedArtistPage(
    val info: SpotifyArtistInfo,
    val topTracks: List<TrackEntity>,
    val albums: List<ArtistAlbumItem>,
)

object SpotifyCatalogClient {

    private const val SSR_CURL_USER_AGENT = "curl/8.5.0"

    private val client = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(7, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val artistIdCache = ConcurrentHashMap<String, List<String>>()
    private val artistInfoCache = ConcurrentHashMap<String, SpotifyArtistInfo>()
    private val artistCatalogCache = ConcurrentHashMap<
        String,
        Pair<Long, Triple<SpotifyArtistInfo?, List<TrackEntity>, List<ArtistAlbumItem>>>,
    >()
    private const val ARTIST_CATALOG_CACHE_TTL_MS = 5 * 60 * 1000L
    private const val TRENDING_CACHE_TTL_MS = 15 * 60 * 1000L
    private val trendingTracksCache = ConcurrentHashMap<String, Pair<Long, List<TrackEntity>>>()
    private val trackArtworkCache = ConcurrentHashMap<String, String>()
    private val spotifyTrackIdCache = ConcurrentHashMap<String, String>()
    private val spotifyTrackIdMissCache = ConcurrentHashMap<String, Long>()
    private val spotify22Regex = Regex("^[A-Za-z0-9]{22}$")
    private val spotifyTrackUrlRegex = Regex("open\\.spotify\\.com/(?:intl-[a-z]{2}/)?track/([A-Za-z0-9]{22})")

    fun findCachedTrackArtwork(artist: String, title: String): String? {
        val titleKey = normalizeToken(title)
        if (titleKey.isBlank()) return null
        val artistKey = normalizeToken(artist)
        if (artistKey.isNotBlank()) {
            trackArtworkCache["$artistKey|$titleKey"]?.let { return it }
        }
        return trackArtworkCache["*|$titleKey"]
    }

    fun findSpotifyTrackId(
        artist: String,
        title: String,
        durationMs: Long = 0L,
        sourceUri: String? = null,
    ): String? {
        extractSpotifyIdFromUri(sourceUri)?.takeIf { spotify22Regex.matches(it) }?.let { direct ->
            if (sourceUri?.contains("track", ignoreCase = true) == true) return direct
        }
        val cleanTitle = title.trim()
        val cleanArtist = artist.split(Regex("\\s*(?:,|&|feat\\.|ft\\.|featuring)\\s*", RegexOption.IGNORE_CASE))
            .firstOrNull().orEmpty().trim()
        if (cleanTitle.isBlank()) return null

        val key = "${normalizeToken(cleanArtist)}|${normalizeToken(cleanTitle)}"
        spotifyTrackIdCache[key]?.let { return it }
        spotifyTrackIdMissCache[key]?.let { missUntil ->
            if (System.currentTimeMillis() < missUntil) return null
            spotifyTrackIdMissCache.remove(key)
        }

        // 1. If we already have a YouTube/SoundCloud/Apple URL, try Odesli Songlink direct lookup
        if (!sourceUri.isNullOrBlank() && sourceUri.startsWith("http", ignoreCase = true)) {
            resolveSpotifyIdViaOdesli(sourceUri)?.let { spId ->
                spotifyTrackIdCache[key] = spId
                return spId
            }
        }

        // 2. Search iTunes Search API for verified trackViewUrl -> resolve Spotify ID via Odesli
        val appleUrl = findVerifiedItunesTrackUrl(cleanArtist, cleanTitle, durationMs)
        if (!appleUrl.isNullOrBlank()) {
            resolveSpotifyIdViaOdesli(appleUrl)?.let { spId ->
                spotifyTrackIdCache[key] = spId
                return spId
            }
        }

        spotifyTrackIdMissCache[key] = System.currentTimeMillis() + 10 * 60_000L
        return null
    }

    private fun resolveSpotifyIdViaOdesli(platformUrl: String): String? {
        val enc = runCatching { URLEncoder.encode(platformUrl, "UTF-8") }.getOrNull() ?: return null
        val body = httpGet(
            url = "https://api.song.link/v1-alpha.1/links?url=$enc",
            userAgent = "SoundLap/3.3",
        ) ?: return null
        return runCatching {
            val root = jsonParser.parseToJsonElement(body).jsonObject
            val links = root["linksByPlatform"]?.jsonObject ?: return@runCatching null
            val spotifyObj = links["spotify"]?.jsonObject ?: return@runCatching null
            val spUrl = spotifyObj["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
            spotifyTrackUrlRegex.find(spUrl)?.groupValues?.get(1)
                ?: spotifyObj["entityUniqueId"]?.jsonPrimitive?.contentOrNull
                    ?.substringAfterLast("::")
                    ?.takeIf { spotify22Regex.matches(it) }
        }.getOrNull()
    }

    private fun findVerifiedItunesTrackUrl(
        artist: String,
        title: String,
        durationMs: Long,
    ): String? {
        val q = listOf(artist, title).filter { it.isNotBlank() }.joinToString(" ").trim()
        if (q.isBlank()) return null
        val enc = runCatching { URLEncoder.encode(q, "UTF-8") }.getOrNull() ?: return null
        val body = httpGet(
            url = "https://itunes.apple.com/search?term=$enc&entity=song&limit=6",
            userAgent = "SoundLap/3.3",
        ) ?: return null
        val targetTitle = normalizeToken(title)
        val targetArtist = normalizeToken(artist)
        return runCatching {
            val results = jsonParser.parseToJsonElement(body).jsonObject["results"] as? JsonArray ?: return@runCatching null
            results.mapNotNull { it as? JsonObject }
                .mapNotNull { item ->
                    val trackName = item["trackName"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val artistName = item["artistName"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val viewUrl = item["trackViewUrl"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val itemDur = item["trackTimeMillis"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
                    val nTitle = normalizeToken(trackName)
                    val nArtist = normalizeToken(artistName)
                    val titleOk = nTitle == targetTitle ||
                        (targetTitle.length >= 3 && (nTitle.contains(targetTitle) || targetTitle.contains(nTitle)))
                    if (!titleOk) return@mapNotNull null
                    val artistOk = targetArtist.isBlank() ||
                        nArtist == targetArtist ||
                        nArtist.contains(targetArtist) ||
                        targetArtist.contains(nArtist)
                    if (!artistOk) return@mapNotNull null
                    if (durationMs >= 20_000L && itemDur >= 20_000L && abs(durationMs - itemDur) > 40_000L) {
                        return@mapNotNull null
                    }
                    var s = 0
                    if (nTitle == targetTitle) s += 100
                    if (targetArtist.isNotBlank() && nArtist == targetArtist) s += 80
                    if (durationMs >= 20_000L && itemDur >= 20_000L) {
                        s -= (abs(durationMs - itemDur) / 1000L).toInt()
                    }
                    viewUrl to s
                }
                .maxByOrNull { it.second }
                ?.first
        }.getOrNull()
    }

    fun checkUpfrontExplicitFlag(artist: String, title: String): Boolean {
        val q = listOf(artist.split(",").firstOrNull().orEmpty().trim(), title.trim())
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .trim()
        if (q.length < 3) return false
        val enc = runCatching { URLEncoder.encode(q, "UTF-8") }.getOrNull() ?: return false
        val body = httpGet(
            url = "https://itunes.apple.com/search?term=$enc&entity=song&limit=4",
            userAgent = "SoundLap/3.4",
        ) ?: return false
        val targetTitle = normalizeToken(title)
        val targetArtist = normalizeToken(artist.split(",").firstOrNull().orEmpty())
        return runCatching {
            val results = jsonParser.parseToJsonElement(body).jsonObject["results"] as? JsonArray
                ?: return@runCatching false
            results.mapNotNull { it as? JsonObject }.any { item ->
                val tName = normalizeToken(item["trackName"]?.jsonPrimitive?.contentOrNull.orEmpty())
                val aName = normalizeToken(item["artistName"]?.jsonPrimitive?.contentOrNull.orEmpty())
                val titleMatch = tName == targetTitle || (targetTitle.length >= 4 && (tName.contains(targetTitle) || targetTitle.contains(tName)))
                val artistMatch = targetArtist.isBlank() || aName == targetArtist || aName.contains(targetArtist) || targetArtist.contains(aName)
                val isExp = item["trackExplicitness"]?.jsonPrimitive?.contentOrNull.equals("explicit", ignoreCase = true)
                titleMatch && artistMatch && isExp
            }
        }.getOrDefault(false)
    }

    private fun cacheTrackArtwork(artist: String, title: String, coverUrl: String?) {
        val url = coverUrl?.trim()?.takeIf { it.startsWith("http") } ?: return
        val titleKey = normalizeToken(title)
        if (titleKey.isBlank()) return
        val primaryArtist = artist.split(",", "&", "feat.", "ft.").firstOrNull().orEmpty()
        val artistKey = normalizeToken(primaryArtist)
        if (artistKey.isNotBlank()) {
            trackArtworkCache["$artistKey|$titleKey"] = url
        }
        trackArtworkCache.putIfAbsent("*|$titleKey", url)
    }

    private val defaultTrendingArtists = listOf(
        "The Weeknd",
        "Billie Eilish",
        "Eminem",
        "Miyagi",
        "Travis Scott",
        "Drake",
    )

    @Volatile
    private var lastMusicBrainzCallMs: Long = 0L
    private val musicBrainzLock = Any()

    fun fetchSpotifyTrendingTracks(
        seedArtists: List<String> = emptyList(),
        limit: Int = 18,
    ): List<TrackEntity> {
        val cleanSeeds = seedArtists
            .map { it.trim() }
            .filter { it.length >= 2 }
            .distinctBy { normalizeToken(it) }
            .take(3)
        val queryArtists = (cleanSeeds + defaultTrendingArtists)
            .distinctBy { normalizeToken(it) }
            .take(6)

        val cacheKey = queryArtists.joinToString("|") { normalizeToken(it) }
        val now = System.currentTimeMillis()
        trendingTracksCache[cacheKey]?.let { (ts, cached) ->
            if (now - ts < TRENDING_CACHE_TTL_MS && cached.isNotEmpty()) {
                return cached.take(limit)
            }
        }

        val futures = queryArtists.map { artist ->
            CompletableFuture.supplyAsync {
                runCatching {
                    fetchArtistCatalog(
                        artistQuery = artist,
                        preferredDisplayName = artist,
                    ).second
                }.getOrDefault(emptyList())
            }
        }

        val collected = futures
            .flatMap { fut -> runCatching { fut.get(6, TimeUnit.SECONDS) }.getOrDefault(emptyList()) }
            .filter { it.title.isNotBlank() && it.playCount >= 10_000 }
            .sortedByDescending { it.playCount }

        val picked = mutableListOf<TrackEntity>()
        val seenTitles = HashSet<String>()
        val artistCounts = HashMap<String, Int>()
        for (track in collected) {
            if (picked.size >= limit) break
            val titleKey = normalizeToken(track.title)
            val artistKey = normalizeToken(track.artist.substringBefore(",").trim())
            if (titleKey.isBlank() || !seenTitles.add(titleKey)) continue
            val count = artistCounts[artistKey] ?: 0
            if (count < 2) {
                picked += track
                artistCounts[artistKey] = count + 1
            }
        }
        val ordered = picked.sortedByDescending { it.playCount }
        if (ordered.isNotEmpty()) {
            trendingTracksCache[cacheKey] = System.currentTimeMillis() to ordered
        }
        return ordered
    }

    fun searchArtists(query: String, limit: Int = 5): List<SpotifyArtistInfo> {
        val trimmed = query.trim()
        if (trimmed.length < 2) return emptyList()

        val candidateIds = discoverSpotifyArtistIds(trimmed, limit = limit.coerceAtLeast(3))
        if (candidateIds.isEmpty()) return emptyList()

        val futures = candidateIds.take(limit).map { artistId ->
            CompletableFuture.supplyAsync { fetchArtistInfoQuick(artistId) }
        }
        return futures.mapNotNull { runCatching { it.get(8, TimeUnit.SECONDS) }.getOrNull() }
            .distinctBy { it.id }
    }

    fun fetchArtistCatalog(
        artistQuery: String,
        preferredDisplayName: String,
        knownSpotifyArtistId: String? = null,
    ): Triple<SpotifyArtistInfo?, List<TrackEntity>, List<ArtistAlbumItem>> {
        val explicitId = knownSpotifyArtistId?.takeIf { it.isNotBlank() }
        val catalogCacheKey = explicitId ?: normalizeToken(preferredDisplayName.ifBlank { artistQuery })
        val now = System.currentTimeMillis()
        if (catalogCacheKey.isNotBlank()) {
            artistCatalogCache[catalogCacheKey]?.let { (ts, cached) ->
                if (now - ts < ARTIST_CATALOG_CACHE_TTL_MS && (cached.second.isNotEmpty() || cached.third.isNotEmpty())) {
                    return cached
                }
            }
        }

        val resolvedIds = if (explicitId != null) {
            listOf(explicitId)
        } else {
            val queries = listOf(preferredDisplayName.trim(), artistQuery.trim())
                .filter { it.length >= 2 }
                .distinct()
            if (queries.size == 1) {
                discoverSpotifyArtistIds(queries.first(), limit = 4)
            } else {
                queries.map { q ->
                    CompletableFuture.supplyAsync { discoverSpotifyArtistIds(q, limit = 4) }
                }.flatMap { fut ->
                    runCatching { fut.get(5, TimeUnit.SECONDS) }.getOrDefault(emptyList())
                }.distinct()
            }
        }

        if (resolvedIds.isEmpty()) return Triple(null, emptyList(), emptyList())

        val matchedId = if (resolvedIds.size == 1) {
            resolvedIds.first()
        } else {
            val infos = resolvedIds.take(3).map { id ->
                CompletableFuture.supplyAsync { fetchArtistInfoQuick(id) }
            }.mapNotNull { runCatching { it.get(4, TimeUnit.SECONDS) }.getOrNull() }
            pickBestArtistMatch(infos, artistQuery, preferredDisplayName)?.id ?: resolvedIds.first()
        }

        // Fetch Spotify Artist SSR HTML (initialState) and Embed HTML (__NEXT_DATA__) concurrently
        val ssrFuture = CompletableFuture.supplyAsync {
            httpGet(
                url = "https://open.spotify.com/artist/$matchedId",
                userAgent = SSR_CURL_USER_AGENT,
            )?.let { parseArtistInitialState(it, matchedId) }
        }
        val embedFuture = CompletableFuture.supplyAsync {
            httpGet(
                url = "https://open.spotify.com/embed/artist/$matchedId",
                userAgent = FullStreamResolver.DEFAULT_BROWSER_USER_AGENT,
            )
        }

        val parsedSsr = runCatching { ssrFuture.get(6, TimeUnit.SECONDS) }.getOrNull()
        val embedHtml = runCatching { embedFuture.get(5, TimeUnit.SECONDS) }.getOrNull()

        val fallbackName = parsedSsr?.info?.name?.ifBlank { preferredDisplayName } ?: preferredDisplayName
        val fallbackCover = parsedSsr?.info?.avatarUrl
        val embedTracks = if (!embedHtml.isNullOrBlank()) {
            parseArtistEmbedTracks(embedHtml, fallbackName, fallbackCover)
        } else {
            emptyList()
        }

        val ssrTracks = parsedSsr?.topTracks.orEmpty()
        val ssrAlbums = parsedSsr?.albums.orEmpty()

        // Merge embed tracks (accurate durations + 10 hits) with SSR initialState tracks (album names + high-res covers + playcounts)
        val mergedTracks = mergeSpotifyArtistTracks(
            ssrTracks = ssrTracks,
            embedTracks = embedTracks,
        ).toMutableList()

        // Only fetch extra album tracks if fewer than 8 tracks were resolved directly
        if (mergedTracks.size < 8 && ssrAlbums.isNotEmpty()) {
            val seenTitles = mergedTracks.map { normalizeToken(it.title) }.toMutableSet()
            val albumTracks = runCatching {
                CompletableFuture.supplyAsync {
                    fetchAlbumTracks(
                        albumUrl = ssrAlbums.first().sourceUrl,
                        artistHint = fallbackName,
                        albumTitle = ssrAlbums.first().title,
                        coverUrl = ssrAlbums.first().artworkUrl,
                    )
                }.get(4, TimeUnit.SECONDS)
            }.getOrDefault(emptyList())
            albumTracks.forEach { t ->
                val key = normalizeToken(t.title)
                if (key.isNotBlank() && seenTitles.add(key)) {
                    mergedTracks += t
                }
            }
        }

        val resolvedInfo = parsedSsr?.info ?: SpotifyArtistInfo(
            id = matchedId,
            name = fallbackName,
            avatarUrl = fallbackCover ?: mergedTracks.firstOrNull()?.coverArtPath,
        )
        artistInfoCache[matchedId] = resolvedInfo
        val result = Triple(resolvedInfo, mergedTracks, ssrAlbums)
        if (catalogCacheKey.isNotBlank() && (mergedTracks.isNotEmpty() || ssrAlbums.isNotEmpty())) {
            artistCatalogCache[catalogCacheKey] = System.currentTimeMillis() to result
        }

        return result
    }

    internal fun parseArtistInitialState(html: String, artistId: String): SpotifyParsedArtistPage? {
        val rawBase64 = Regex(
            """<script[^>]*id=["']initialState["'][^>]*>(.*?)</script>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        ).find(html)?.groupValues?.get(1)?.trim()

        if (!rawBase64.isNullOrBlank()) {
            val parsed = runCatching {
                val jsonText = decodeBase64ToUtf8(rawBase64)
                val root = jsonParser.parseToJsonElement(jsonText).jsonObject
                val items = root["entities"]?.jsonObject?.get("items") as? JsonObject ?: return@runCatching null
                val artistObj = (items["spotify:artist:$artistId"] as? JsonObject)
                    ?: items.values.firstOrNull { el ->
                        (el as? JsonObject)?.get("discography") != null
                    } as? JsonObject
                    ?: return@runCatching null

                val profile = artistObj["profile"] as? JsonObject
                val artistName = profile?.get("name")?.jsonPrimitive?.contentOrNull?.trim()
                    ?: extractMetaContent(html, "og:title")
                    ?: ""

                val avatarUrl = ((artistObj["visuals"] as? JsonObject)
                    ?.get("avatarImage") as? JsonObject)
                    ?.get("sources")?.let { it as? JsonArray }
                    ?.let(::pickBestImageUrl)
                    ?: extractMetaContent(html, "og:image")

                val followers = (artistObj["stats"] as? JsonObject)
                    ?.get("followers")?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                    ?: (artistObj["stats"] as? JsonObject)
                        ?.get("monthlyListeners")?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                    ?: 0

                val disco = artistObj["discography"] as? JsonObject
                val topTracksArr = (disco?.get("topTracks") as? JsonObject)?.get("items") as? JsonArray
                val topTracks = topTracksArr?.mapNotNull { el ->
                    val wrapper = el as? JsonObject ?: return@mapNotNull null
                    val trackObj = (wrapper["track"] as? JsonObject) ?: wrapper
                    val tid = extractSpotifyIdFromUri(trackObj["uri"]?.jsonPrimitive?.contentOrNull)
                        ?: return@mapNotNull null
                    val title = trackObj["name"]?.jsonPrimitive?.contentOrNull?.trim()
                        ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val artistsArr = (trackObj["artists"] as? JsonObject)?.get("items") as? JsonArray
                    val artistNames = artistsArr?.mapNotNull { aEl ->
                        ((aEl as? JsonObject)?.get("profile") as? JsonObject)
                            ?.get("name")?.jsonPrimitive?.contentOrNull?.trim()
                    }.orEmpty().ifEmpty { listOfNotNull(artistName.takeIf { it.isNotBlank() }) }

                    val albumObj = trackObj["albumOfTrack"] as? JsonObject
                    val albumName = albumObj?.get("name")?.jsonPrimitive?.contentOrNull?.trim()
                    val coverUrl = ((albumObj?.get("coverArt") as? JsonObject)?.get("sources") as? JsonArray)
                        ?.let(::pickBestImageUrl)
                        ?: avatarUrl
                    val playcount = trackObj["playcount"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 50_000L
                    val durationMs = ((trackObj["duration"] as? JsonObject)
                        ?.get("totalMilliseconds")?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                        ?: 185_000L).coerceAtLeast(30_000L)

                    val isExplicitFlag = ((trackObj["contentRating"] as? JsonObject)
                        ?.get("label")?.jsonPrimitive?.contentOrNull
                        ?.equals("EXPLICIT", ignoreCase = true) == true) ||
                        (trackObj["explicit"]?.jsonPrimitive?.booleanOrNull == true)
                    val trackIdStr = "sp_$tid"
                    if (isExplicitFlag) {
                        ExplicitContentDetector.markPreDetectedExplicit(trackIdStr)
                    }
                    val draft = TrackEntity(
                        id = trackIdStr,
                        title = title,
                        artist = artistNames.joinToString(", ").ifBlank { artistName },
                        genre = "Поп",
                        durationMs = durationMs,
                        waveformJson = TrackEntity.encodeWaveform(TrackEntity.defaultWaveform()),
                        cachedFilePath = null,
                        sourceUri = null,
                        coverArtPath = coverUrl,
                        accentColorHex = 0xFF10B981L,
                        secondaryColorHex = 0xFF5CE1E6L,
                        isUserUpload = false,
                        isAutoImported = false,
                        isCachedOffline = false,
                        fileSizeBytes = 0L,
                        playCount = playcount.coerceIn(1_000L, Int.MAX_VALUE.toLong()).toInt(),
                        likeCount = 0,
                        isLiked = false,
                        description = "Spotify",
                        createdAt = System.currentTimeMillis(),
                        albumTitle = albumName,
                    )
                    cacheTrackArtwork(draft.artist, draft.title, coverUrl)
                    draft.copy(sourceUri = FullStreamResolver.buildLegacyUpgradeUri(draft))
                }.orEmpty()

                val albumItems = LinkedHashMap<String, ArtistAlbumItem>()
                listOf("popularReleasesAlbums", "albums", "singles", "compilations").forEach { groupKey ->
                    val groupArr = (disco?.get(groupKey) as? JsonObject)?.get("items") as? JsonArray ?: return@forEach
                    groupArr.forEach { el ->
                        val itemObj = el as? JsonObject ?: return@forEach
                        val releasesItems = (itemObj["releases"] as? JsonObject)?.get("items") as? JsonArray
                        val releaseList = if (releasesItems != null && releasesItems.isNotEmpty()) {
                            releasesItems.mapNotNull { it as? JsonObject }
                        } else {
                            listOf(itemObj)
                        }
                        releaseList.forEach { rel ->
                            val albId = extractSpotifyIdFromUri(rel["uri"]?.jsonPrimitive?.contentOrNull)
                                ?: return@forEach
                            val albTitle = rel["name"]?.jsonPrimitive?.contentOrNull?.trim()
                                ?.takeIf { it.isNotBlank() } ?: return@forEach
                            val albCover = ((rel["coverArt"] as? JsonObject)?.get("sources") as? JsonArray)
                                ?.let(::pickBestImageUrl)
                                ?: avatarUrl
                            val key = normalizeToken(albTitle)
                            if (key.isNotBlank() && !albumItems.containsKey(key)) {
                                albumItems[key] = ArtistAlbumItem(
                                    id = "sp_alb_$albId",
                                    title = albTitle,
                                    artist = artistName,
                                    sourceUrl = "https://open.spotify.com/album/$albId",
                                    artworkUrl = albCover,
                                    trackCount = 0,
                                )
                            }
                        }
                    }
                }

                // Also parse appearsOn collaborative releases when present in SSR initialState
                val relatedContent = artistObj["relatedContent"] as? JsonObject
                val appearsOnItems = (relatedContent?.get("appearsOn") as? JsonObject)?.get("items") as? JsonArray
                appearsOnItems?.forEach { el ->
                    val wrapper = el as? JsonObject ?: return@forEach
                    val releaseList = ((wrapper["releases"] as? JsonObject)?.get("items") as? JsonArray)
                        ?.mapNotNull { it as? JsonObject }
                        ?: listOf(wrapper)
                    releaseList.forEach { rel ->
                        val albId = extractSpotifyIdFromUri(rel["uri"]?.jsonPrimitive?.contentOrNull)
                            ?: return@forEach
                        val albTitle = rel["name"]?.jsonPrimitive?.contentOrNull?.trim()
                            ?.takeIf { it.isNotBlank() } ?: return@forEach
                        val albCover = ((rel["coverArt"] as? JsonObject)?.get("sources") as? JsonArray)
                            ?.let(::pickBestImageUrl)
                            ?: avatarUrl
                        val key = normalizeToken(albTitle)
                        if (key.isNotBlank() && !albumItems.containsKey(key)) {
                            albumItems[key] = ArtistAlbumItem(
                                id = "sp_alb_$albId",
                                title = albTitle,
                                artist = artistName,
                                sourceUrl = "https://open.spotify.com/album/$albId",
                                artworkUrl = albCover,
                                trackCount = 0,
                            )
                        }
                    }
                }

                SpotifyParsedArtistPage(
                    info = SpotifyArtistInfo(
                        id = artistId,
                        name = artistName,
                        avatarUrl = avatarUrl,
                        followers = followers,
                    ),
                    topTracks = topTracks,
                    albums = albumItems.values.take(18),
                )
            }.getOrNull()
            if (parsed != null) return parsed
        }

        // Meta tag fallback
        val ogTitle = extractMetaContent(html, "og:title")?.trim().orEmpty()
        val ogImage = extractMetaContent(html, "og:image")?.trim()
        return if (ogTitle.isNotBlank()) {
            SpotifyParsedArtistPage(
                info = SpotifyArtistInfo(id = artistId, name = ogTitle, avatarUrl = ogImage),
                topTracks = emptyList(),
                albums = emptyList(),
            )
        } else {
            null
        }
    }

    internal fun parseArtistEmbedTracks(
        html: String,
        fallbackArtistName: String,
        fallbackCoverUrl: String?,
    ): List<TrackEntity> {
        val nextData = Regex(
            """<script[^>]*id=["']__NEXT_DATA__["'][^>]*>(.*?)</script>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        ).find(html)?.groupValues?.get(1)?.trim() ?: return emptyList()

        return runCatching {
            val root = jsonParser.parseToJsonElement(nextData).jsonObject
            val entity = root["props"]?.jsonObject
                ?.get("pageProps")?.jsonObject
                ?.get("state")?.jsonObject
                ?.get("data")?.jsonObject
                ?.get("entity")?.jsonObject ?: return emptyList()

            val entityName = entity["name"]?.jsonPrimitive?.contentOrNull?.trim()
                ?.takeIf { it.isNotBlank() } ?: fallbackArtistName
            val trackList = entity["trackList"] as? JsonArray ?: return emptyList()

            trackList.mapNotNull { el ->
                val obj = el as? JsonObject ?: return@mapNotNull null
                val title = obj["title"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val rawSubtitle = obj["subtitle"]?.jsonPrimitive?.contentOrNull
                    ?.replace('\u00A0', ' ')
                    ?.trim()
                    ?.ifBlank { entityName }
                    ?: entityName
                val tid = extractSpotifyIdFromUri(obj["uri"]?.jsonPrimitive?.contentOrNull)
                    ?: abs("${entityName}_$title".hashCode()).toString()
                val durMs = (obj["duration"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 180_000L)
                    .coerceAtLeast(30_000L)

                val isExplicitFlag = (obj["isExplicit"]?.jsonPrimitive?.booleanOrNull == true) ||
                    (obj["explicit"]?.jsonPrimitive?.booleanOrNull == true)
                val trackIdStr = "sp_$tid"
                if (isExplicitFlag) {
                    ExplicitContentDetector.markPreDetectedExplicit(trackIdStr)
                }
                val draft = TrackEntity(
                    id = trackIdStr,
                    title = title,
                    artist = rawSubtitle,
                    genre = "Поп",
                    durationMs = durMs,
                    waveformJson = TrackEntity.encodeWaveform(TrackEntity.defaultWaveform()),
                    cachedFilePath = null,
                    sourceUri = null,
                    coverArtPath = fallbackCoverUrl,
                    accentColorHex = 0xFF10B981L,
                    secondaryColorHex = 0xFF5CE1E6L,
                    isUserUpload = false,
                    isAutoImported = false,
                    isCachedOffline = false,
                    fileSizeBytes = 0L,
                    playCount = 250_000,
                    likeCount = 0,
                    isLiked = false,
                    description = "Spotify",
                    createdAt = System.currentTimeMillis(),
                )
                draft.copy(sourceUri = FullStreamResolver.buildLegacyUpgradeUri(draft))
            }
        }.getOrDefault(emptyList())
    }

    private fun mergeSpotifyArtistTracks(
        ssrTracks: List<TrackEntity>,
        embedTracks: List<TrackEntity>,
    ): List<TrackEntity> {
        if (ssrTracks.isEmpty()) return embedTracks
        if (embedTracks.isEmpty()) return ssrTracks

        val ssrById = ssrTracks.associateBy { it.id }
        val ssrByTitle = ssrTracks.associateBy { normalizeToken(it.title) }
        val out = LinkedHashMap<String, TrackEntity>()

        embedTracks.forEach { emb ->
            val rich = ssrById[emb.id] ?: ssrByTitle[normalizeToken(emb.title)]
            val combined = if (rich != null) {
                rich.copy(
                    durationMs = emb.durationMs.takeIf { it > 30_000L } ?: rich.durationMs,
                    artist = emb.artist.ifBlank { rich.artist },
                )
            } else {
                emb
            }
            out[combined.id] = combined
        }

        ssrTracks.forEach { ssr ->
            val keyNorm = normalizeToken(ssr.title)
            if (out.values.none { normalizeToken(it.title) == keyNorm }) {
                out[ssr.id] = ssr
            }
        }
        return out.values.toList()
    }

    fun fetchAlbumTracks(
        albumUrl: String,
        artistHint: String,
        albumTitle: String,
        coverUrl: String?,
    ): List<TrackEntity> {
        val albumId = Regex("open\\.spotify\\.com/(?:intl-[a-z]{2}/)?album/([a-zA-Z0-9]+)", RegexOption.IGNORE_CASE)
            .find(albumUrl)?.groupValues?.get(1) ?: return emptyList()

        val embedHtml = httpGet(
            url = "https://open.spotify.com/embed/album/$albumId",
            userAgent = FullStreamResolver.DEFAULT_BROWSER_USER_AGENT,
        )
        if (!embedHtml.isNullOrBlank()) {
            val parsed = PlaylistImporter.parseSpotifyEmbedHtml(embedHtml, albumTitle)
            if (parsed.queries.isNotEmpty()) {
                return parsed.queries.mapIndexed { idx, q ->
                    val cleanArtist = q.artist.ifBlank { artistHint }
                    val draft = TrackEntity(
                        id = "sp_emb_${abs("${albumId}_${idx}_${q.title}".hashCode())}",
                        title = q.title,
                        artist = cleanArtist,
                        genre = "Поп",
                        durationMs = q.durationMs.coerceAtLeast(30_000L),
                        waveformJson = TrackEntity.encodeWaveform(TrackEntity.defaultWaveform()),
                        cachedFilePath = null,
                        sourceUri = null,
                        coverArtPath = coverUrl ?: parsed.coverArtUrl,
                        accentColorHex = 0xFF10B981L,
                        secondaryColorHex = 0xFF5CE1E6L,
                        isUserUpload = false,
                        isAutoImported = false,
                        isCachedOffline = false,
                        fileSizeBytes = 0L,
                        playCount = 1000,
                        likeCount = 0,
                        isLiked = false,
                        description = "Spotify Album",
                        createdAt = System.currentTimeMillis(),
                        albumTitle = albumTitle,
                    )
                    draft.copy(sourceUri = FullStreamResolver.buildLegacyUpgradeUri(draft))
                }
            }
        }
        return emptyList()
    }

    private fun discoverSpotifyArtistIds(query: String, limit: Int): List<String> {
        val cacheKey = normalizeToken(query)
        artistIdCache[cacheKey]?.let { if (it.isNotEmpty()) return it.take(limit) }

        val ids = LinkedHashSet<String>()
        // 1. Wikidata (fast, zero rate-limit, multi-language)
        ids.addAll(lookupSpotifyArtistIdsOnWikidata(query, limit))

        // 2. MusicBrainz fallback (single guarded call if Wikidata found nothing)
        if (ids.isEmpty()) {
            ids.addAll(lookupSpotifyArtistIdsOnMusicBrainz(query))
        }

        val list = ids.toList()
        if (list.isNotEmpty()) {
            artistIdCache[cacheKey] = list
        }
        return list.take(limit)
    }

    private fun lookupSpotifyArtistIdsOnWikidata(query: String, limit: Int): List<String> {
        val hasCyrillic = query.any { it in '\u0400'..'\u04FF' }
        val lang = if (hasCyrillic) "ru" else "en"
        val encoded = URLEncoder.encode(query, "UTF-8")
        val searchBody = httpGet(
            url = "https://www.wikidata.org/w/api.php?action=wbsearchentities&format=json&language=$lang&uselang=$lang&type=item&limit=6&search=$encoded",
            userAgent = "SoundLap/3.3 (Android)",
        ) ?: return emptyList()

        val qids = runCatching {
            val root = jsonParser.parseToJsonElement(searchBody).jsonObject
            val arr = root["search"] as? JsonArray ?: return emptyList()
            arr.mapNotNull { (it as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull }
        }.getOrDefault(emptyList())
        if (qids.isEmpty()) return emptyList()

        val entitiesBody = httpGet(
            url = "https://www.wikidata.org/w/api.php?action=wbgetentities&format=json&ids=${qids.joinToString("|")}&props=claims|labels",
            userAgent = "SoundLap/3.3 (Android)",
        ) ?: return emptyList()

        return runCatching {
            val root = jsonParser.parseToJsonElement(entitiesBody).jsonObject
            val entities = root["entities"] as? JsonObject ?: return emptyList()
            val out = mutableListOf<String>()
            for (qid in qids) {
                val ent = entities[qid] as? JsonObject ?: continue
                val p1902 = (ent["claims"] as? JsonObject)?.get("P1902") as? JsonArray ?: continue
                for (claim in p1902) {
                    val sid = (claim as? JsonObject)
                        ?.get("mainsnak")?.jsonObject
                        ?.get("datavalue")?.jsonObject
                        ?.get("value")?.jsonPrimitive?.contentOrNull
                        ?.trim()
                    if (!sid.isNullOrBlank()) {
                        out += sid
                        if (out.size >= limit) return out
                    }
                }
            }
            out
        }.getOrDefault(emptyList())
    }

    private fun lookupSpotifyArtistIdsOnMusicBrainz(query: String): List<String> {
        return synchronized(musicBrainzLock) {
            val now = System.currentTimeMillis()
            val wait = 1100L - (now - lastMusicBrainzCallMs)
            if (wait in 1..1100L) {
                runCatching { Thread.sleep(wait) }
            }
            lastMusicBrainzCallMs = System.currentTimeMillis()

            val encoded = URLEncoder.encode(query, "UTF-8")
            val searchBody = httpGet(
                url = "https://musicbrainz.org/ws/2/artist?query=$encoded&fmt=json&limit=3",
                userAgent = "SoundLap/3.3 (app@soundlap.example)",
            ) ?: return@synchronized emptyList()

            val mbid = runCatching {
                val artists = jsonParser.parseToJsonElement(searchBody).jsonObject["artists"] as? JsonArray
                    ?: return@synchronized emptyList()
                val needle = normalizeToken(query)
                val ranked = artists.mapNotNull { it as? JsonObject }.sortedByDescending { a ->
                    val name = normalizeToken(a["name"]?.jsonPrimitive?.contentOrNull.orEmpty())
                    val score = a["score"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                    (if (name == needle) 1000 else if (name.startsWith(needle)) 500 else 0) + score
                }
                ranked.firstOrNull { a ->
                    val name = normalizeToken(a["name"]?.jsonPrimitive?.contentOrNull.orEmpty())
                    name == needle || name.startsWith(needle) ||
                        (needle.length >= 3 && (name.contains(needle) || needle.contains(name)))
                }?.get("id")?.jsonPrimitive?.contentOrNull
            }.getOrNull() ?: return@synchronized emptyList()

            runCatching { Thread.sleep(1050L) }
            lastMusicBrainzCallMs = System.currentTimeMillis()
            val relBody = httpGet(
                url = "https://musicbrainz.org/ws/2/artist/$mbid?inc=url-rels&fmt=json",
                userAgent = "SoundLap/3.3 (app@soundlap.example)",
            ) ?: return@synchronized emptyList()

            runCatching {
                val rels = jsonParser.parseToJsonElement(relBody).jsonObject["relations"] as? JsonArray
                    ?: return@synchronized emptyList()
                rels.mapNotNull { el ->
                    val resource = (el as? JsonObject)?.get("url")?.jsonObject?.get("resource")?.jsonPrimitive?.contentOrNull
                        ?: return@mapNotNull null
                    Regex("open\\.spotify\\.com/artist/([A-Za-z0-9]+)").find(resource)?.groupValues?.get(1)
                }.distinct()
            }.getOrDefault(emptyList())
        }
    }

    private fun fetchArtistInfoQuick(artistId: String): SpotifyArtistInfo? {
        artistInfoCache[artistId]?.let { return it }
        val html = httpGet(
            url = "https://open.spotify.com/artist/$artistId",
            userAgent = SSR_CURL_USER_AGENT,
        ) ?: return null
        val parsed = parseArtistInitialState(html, artistId) ?: return null
        artistInfoCache[artistId] = parsed.info
        return parsed.info
    }

    private fun pickBestArtistMatch(
        candidates: List<SpotifyArtistInfo>,
        query: String,
        preferredName: String,
    ): SpotifyArtistInfo? {
        val normQuery = normalizeToken(query)
        val normPreferred = normalizeToken(preferredName)
        val scored = candidates.mapNotNull { artist ->
            val normName = normalizeToken(artist.name)
            val score = when {
                normName == normQuery || normName == normPreferred -> 1000
                normName.startsWith(normQuery) || normName.startsWith(normPreferred) -> 500
                normQuery.length >= 3 && (normName.contains(normQuery) || normQuery.contains(normName)) -> 250
                else -> 0
            }
            if (score > 0) artist to (score * 1_000_000L + artist.followers.coerceAtMost(999_999)) else null
        }
        return scored.maxByOrNull { it.second }?.first
    }

    private fun httpGet(url: String, userAgent: String): String? {
        return runCatching {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                resp.body?.string()
            }
        }.getOrNull()
    }

    private fun pickBestImageUrl(sources: JsonArray): String? {
        var bestUrl: String? = null
        var bestWidth = -1
        for (el in sources) {
            val obj = el as? JsonObject ?: continue
            val url = obj["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: continue
            val w = obj["width"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
            if (w >= bestWidth) {
                bestWidth = w
                bestUrl = url
            }
        }
        return bestUrl
    }

    private fun extractSpotifyIdFromUri(uri: String?): String? {
        val raw = uri?.trim() ?: return null
        return Regex("spotify:(?:track|album|artist):([A-Za-z0-9]+)").find(raw)?.groupValues?.get(1)
            ?: Regex("open\\.spotify\\.com/(?:track|album|artist)/([A-Za-z0-9]+)").find(raw)?.groupValues?.get(1)
    }

    private fun extractMetaContent(html: String, property: String): String? {
        val pattern = Regex(
            """<meta[^>]+(?:property|name)=["']$property["'][^>]+content=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE,
        )
        return pattern.find(html)?.groupValues?.get(1)?.let(::decodeHtmlEntities)
    }

    private fun decodeHtmlEntities(input: String): String {
        return input
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&#x27;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
    }

    private fun decodeBase64ToUtf8(encoded: String): String {
        val clean = encoded.filter { !it.isWhitespace() }
        val table = IntArray(256) { -1 }
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        for (i in alphabet.indices) {
            table[alphabet[i].code] = i
        }
        val out = ByteArray((clean.length * 3) / 4 + 4)
        var outLen = 0
        var buffer = 0
        var bitsLeft = 0
        for (ch in clean) {
            if (ch == '=') break
            val code = ch.code
            val value = if (code < 256) table[code] else -1
            if (value < 0) continue
            buffer = (buffer shl 6) or value
            bitsLeft += 6
            if (bitsLeft >= 8) {
                bitsLeft -= 8
                out[outLen++] = ((buffer shr bitsLeft) and 0xFF).toByte()
            }
        }
        return String(out, 0, outLen, Charsets.UTF_8)
    }

    private fun normalizeToken(raw: String): String =
        raw.lowercase().replace(Regex("[^\\p{L}\\p{Nd}]"), "")
}
