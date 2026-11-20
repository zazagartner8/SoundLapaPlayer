package com.example.data.remote

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.data.local.ExplicitContentDetector
import com.example.data.local.PlaylistEntity
import com.example.data.local.PlaylistTrackCrossRef
import com.example.data.local.SoundLapaDao
import com.example.data.local.TrackEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.schabi.newpipe.extractor.ListExtractor
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

data class ImportedTrackQuery(
    val title: String,
    val artist: String,
    val durationMs: Long = 180_000L,
    val albumTitle: String? = null,
    val coverArtUrl: String? = null,
    val isExplicit: Boolean = false,
)

data class ParsedPlaylistPayload(
    val playlistName: String,
    val sourceLabel: String,
    val queries: List<ImportedTrackQuery> = emptyList(),
    val directTracks: List<TrackEntity> = emptyList(),
    val coverArtUrl: String? = null,
)

data class PlaylistImportResult(
    val playlistId: String,
    val playlistName: String,
    val sourceLabel: String,
    val totalFound: Int,
    val importedCount: Int,
)

class PlaylistImporter(
    private val context: Context,
    private val dao: SoundLapaDao,
    private val audiusRepository: AudiusRepository,
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun importFromInput(
        rawInput: String,
        customPlaylistName: String? = null,
    ): Result<PlaylistImportResult> = withContext(Dispatchers.IO) {
        runCatching {
            val trimmed = rawInput.trim()
            require(trimmed.isNotBlank()) { "Введите ссылку на плейлист или список треков" }

            val payload = when {
                isUrlLike(trimmed) -> extractFromUrl(trimmed, customPlaylistName)
                trimmed.contains("#EXTM3U", ignoreCase = true) || trimmed.contains("#EXTINF", ignoreCase = true) -> {
                    parseM3uTracklist(trimmed, customPlaylistName ?: "Импорт M3U")
                }
                looksLikeCsv(trimmed) -> {
                    parseCsvTracklist(trimmed, customPlaylistName ?: "Импорт CSV")
                }
                else -> {
                    parsePlainTextTracklist(trimmed, customPlaylistName ?: "Импортированный плейлист")
                }
            }

            finalizeImport(
                payload = payload,
                overrideName = customPlaylistName?.takeIf { it.isNotBlank() },
            )
        }
    }

    suspend fun importFromFileUri(
        uri: Uri,
        customPlaylistName: String? = null,
    ): Result<PlaylistImportResult> = withContext(Dispatchers.IO) {
        runCatching {
            val displayName = resolveDisplayName(uri)
            val mimeType = context.contentResolver.getType(uri)
            val rawBytes = context.contentResolver.openInputStream(uri)?.use { stream ->
                val limited = ByteArray(MAX_PLAYLIST_FILE_BYTES + 1)
                var totalRead = 0
                while (totalRead < limited.size) {
                    val read = stream.read(limited, totalRead, limited.size - totalRead)
                    if (read <= 0) break
                    totalRead += read
                }
                if (totalRead > MAX_PLAYLIST_FILE_BYTES) {
                    throw IllegalArgumentException("Файл слишком большой (максимум 5 МБ)")
                }
                limited.copyOf(totalRead)
            } ?: throw IllegalStateException("Не удалось прочитать файл")

            val payload = validateAndParseFileContent(
                fileName = displayName,
                mimeType = mimeType,
                rawBytes = rawBytes,
                customPlaylistName = customPlaylistName,
            )

            finalizeImport(
                payload = payload,
                overrideName = customPlaylistName?.takeIf { it.isNotBlank() },
            )
        }
    }

    private suspend fun extractFromUrl(
        rawUrl: String,
        customName: String?,
    ): ParsedPlaylistPayload {
        val url = normalizeUrlInput(rawUrl)
        val lower = url.lowercase()

        // 1. Spotify playlist / album / track (including spotify.link shortlinks & spotify: URIs)
        if (lower.contains("spotify.com") || lower.contains("spotify.link") || lower.startsWith("spotify:")) {
            val resolvedSpotifyUrl = resolveSpotifyCanonicalUrl(url)
            val parsedRef = parseSpotifyResourceRef(resolvedSpotifyUrl)
                ?: throw IllegalArgumentException("Поддерживаются ссылки Spotify на плейлист, альбом или трек")

            // 1a. Try full Web API pagination with anonymous web token for entire large playlists (>100 tracks)
            val apiPayload = runCatching {
                fetchFullSpotifyPlaylistViaWebToken(
                    kind = parsedRef.first,
                    id = parsedRef.second,
                    fallbackName = customName ?: "Spotify Импорт",
                )
            }.getOrNull()
            if (apiPayload != null && apiPayload.queries.isNotEmpty()) {
                return apiPayload
            }

            // 1b. Fallback to Spotify embed page (__NEXT_DATA__) + main page initial-state
            val embedUrl = "https://open.spotify.com/embed/${parsedRef.first}/${parsedRef.second}"
            val embedHtml = fetchHtmlText(embedUrl)
            if (embedHtml != null) {
                val parsed = parseSpotifyEmbedHtml(embedHtml, customName ?: "Spotify Импорт")
                if (parsed.queries.isNotEmpty()) {
                    return parsed
                }
            }

            val pageHtml = fetchHtmlText(resolvedSpotifyUrl)
            if (pageHtml != null) {
                val parsed = parseSpotifyEmbedHtml(pageHtml, customName ?: "Spotify Импорт")
                if (parsed.queries.isNotEmpty()) {
                    return parsed
                }
            }

            throw IllegalStateException(
                "Не удалось извлечь треки из ссылки Spotify. Убедитесь, что плейлист публичный, или экспортируйте его в CSV.",
            )
        }

        // 2. Deezer public playlist or album API
        val deezerMatch = Regex(
            "https?://(?:www\\.)?deezer\\.com/(?:[a-z]{2}/)?(playlist|album)/(\\d+)",
            RegexOption.IGNORE_CASE,
        ).find(url)
        if (deezerMatch != null) {
            val kind = deezerMatch.groupValues[1].lowercase()
            val id = deezerMatch.groupValues[2]
            val dzPayload = fetchDeezerCollection(kind, id, customName ?: "Deezer Импорт")
            if (dzPayload != null && dzPayload.queries.isNotEmpty()) {
                return dzPayload
            }
        }

        // 3. YouTube / YouTube Music playlist (with full pagination up to MAX_IMPORT_TRACKS)
        if ((lower.contains("youtube.com") || lower.contains("youtu.be")) && lower.contains("list=")) {
            FullStreamResolver.ensureInitialized()
            val extractor = ServiceList.YouTube.getPlaylistExtractor(url)
            extractor.fetchPage()
            val playlistTitle = customName?.takeIf { it.isNotBlank() }
                ?: extractor.name?.takeIf { it.isNotBlank() }
                ?: "YouTube Импорт"
            val items = collectAllPlaylistItems(extractor, maxItems = MAX_IMPORT_TRACKS)
            val tracks = items.mapNotNull { FullStreamResolver.mapStreamItemToTrack(it, isYouTube = true) }
            val playlistCover = FullStreamResolver.upgradeArtworkUrl(extractor.thumbnails.lastOrNull()?.url)
                ?: tracks.firstOrNull()?.coverArtPath
            if (tracks.isNotEmpty()) {
                return ParsedPlaylistPayload(
                    playlistName = playlistTitle,
                    sourceLabel = "YouTube Music",
                    directTracks = tracks,
                    coverArtUrl = playlistCover,
                )
            }
        }

        // 4. SoundCloud playlist / set (with pagination)
        if (lower.contains("soundcloud.com") && (lower.contains("/sets/") || lower.contains("/albums/"))) {
            FullStreamResolver.ensureInitialized()
            val extractor = ServiceList.SoundCloud.getPlaylistExtractor(url)
            extractor.fetchPage()
            val playlistTitle = customName?.takeIf { it.isNotBlank() }
                ?: extractor.name?.takeIf { it.isNotBlank() }
                ?: "SoundCloud Импорт"
            val items = collectAllPlaylistItems(extractor, maxItems = MAX_IMPORT_TRACKS)
            val tracks = items.mapNotNull { FullStreamResolver.mapStreamItemToTrack(it, isYouTube = false) }
            val playlistCover = FullStreamResolver.upgradeArtworkUrl(extractor.thumbnails.lastOrNull()?.url)
                ?: tracks.firstOrNull()?.coverArtPath
            if (tracks.isNotEmpty()) {
                return ParsedPlaylistPayload(
                    playlistName = playlistTitle,
                    sourceLabel = "SoundCloud",
                    directTracks = tracks,
                    coverArtUrl = playlistCover,
                )
            }
        }

        // 5. Single YouTube or SoundCloud track link
        if (FullStreamResolver.isResolvablePageOrUpgradeUri(url)) {
            FullStreamResolver.ensureInitialized()
            val isSc = lower.contains("soundcloud.com")
            val service = if (isSc) ServiceList.SoundCloud else ServiceList.YouTube
            val info = runCatching { StreamInfo.getInfo(service, url) }.getOrNull()
            if (info != null && !info.name.isNullOrBlank()) {
                val track = TrackEntity(
                    id = (if (isSc) "sc_" else "yt_") + kotlin.math.abs(url.hashCode()),
                    title = info.name.trim(),
                    artist = info.uploaderName?.trim()?.removeSuffix(" - Topic")?.ifBlank { "Track" } ?: "Track",
                    genre = "Импорт",
                    durationMs = (info.duration.coerceAtLeast(30L) * 1000L),
                    waveformJson = TrackEntity.encodeWaveform(TrackEntity.defaultWaveform()),
                    cachedFilePath = null,
                    sourceUri = info.url ?: url,
                    coverArtPath = FullStreamResolver.upgradeArtworkUrl(info.thumbnails.lastOrNull()?.url),
                    accentColorHex = 0xFF8B5CF6L,
                    secondaryColorHex = 0xFFA78BFAL,
                    isUserUpload = false,
                    isAutoImported = false,
                    isCachedOffline = false,
                    fileSizeBytes = 0L,
                    playCount = 1,
                    likeCount = 0,
                    isLiked = false,
                    description = "Импортировано по ссылке",
                    createdAt = System.currentTimeMillis(),
                )
                return ParsedPlaylistPayload(
                    playlistName = customName ?: track.title,
                    sourceLabel = if (isSc) "SoundCloud" else "YouTube",
                    directTracks = listOf(track),
                    coverArtUrl = track.coverArtPath,
                )
            }
        }

        // 6. Generic web music link (Apple Music, Yandex Music, etc.) via HTML meta / JSON-LD extraction
        val html = fetchHtmlText(url)
            ?: throw IllegalStateException("Не удалось открыть ссылку")
        val generic = parseGenericHtmlPlaylist(html, customName ?: "Веб-импорт")
        if (generic.queries.isEmpty()) {
            throw IllegalStateException("Не удалось распознать список треков по ссылке. Попробуйте вставить текст «Исполнитель - Трек» или файл CSV/M3U.")
        }
        return generic
    }

    private fun collectAllPlaylistItems(
        extractor: ListExtractor<StreamInfoItem>,
        maxItems: Int,
    ): List<StreamInfoItem> {
        val collected = mutableListOf<StreamInfoItem>()
        var page = extractor.initialPage
        while (collected.size < maxItems) {
            val pageStreams = page.items.filterIsInstance<StreamInfoItem>()
            collected += pageStreams
            if (collected.size >= maxItems || !page.hasNextPage()) break
            val nextPage = page.nextPage ?: break
            page = runCatching { extractor.getPage(nextPage) }.getOrNull() ?: break
        }
        return collected.take(maxItems)
    }

    private fun fetchFullSpotifyPlaylistViaWebToken(
        kind: String,
        id: String,
        fallbackName: String,
    ): ParsedPlaylistPayload? {
        if (kind != "playlist" && kind != "album") return null
        val tokenJson = fetchJsonText("https://open.spotify.com/get_access_token?reason=transport&productType=web_player")
            ?: return null
        val accessToken = runCatching {
            jsonParser.parseToJsonElement(tokenJson).jsonObject["accessToken"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null

        val metaUrl = if (kind == "playlist") {
            "https://api.spotify.com/v1/playlists/$id?fields=name,images,tracks(total)"
        } else {
            "https://api.spotify.com/v1/albums/$id"
        }
        val metaBody = fetchAuthorizedJsonText(metaUrl, accessToken) ?: return null
        val metaObj = runCatching { jsonParser.parseToJsonElement(metaBody).jsonObject }.getOrNull() ?: return null
        val collectionTitle = metaObj["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: fallbackName
        val collectionCover = (metaObj["images"] as? JsonArray)
            ?.firstOrNull()
            ?.let { (it as? JsonObject)?.get("url")?.jsonPrimitive?.contentOrNull }
            ?.takeIf { it.isNotBlank() }

        val queries = mutableListOf<ImportedTrackQuery>()
        var offset = 0
        val pageSize = 100
        while (queries.size < MAX_IMPORT_TRACKS) {
            val pageUrl = if (kind == "playlist") {
                "https://api.spotify.com/v1/playlists/$id/tracks?offset=$offset&limit=$pageSize&fields=items(track(name,duration_ms,album(name,images),artists(name))),next"
            } else {
                "https://api.spotify.com/v1/albums/$id/tracks?offset=$offset&limit=$pageSize"
            }
            val body = fetchAuthorizedJsonText(pageUrl, accessToken) ?: break
            val root = runCatching { jsonParser.parseToJsonElement(body).jsonObject }.getOrNull() ?: break
            val items = root["items"] as? JsonArray ?: break
            if (items.isEmpty()) break

            for (el in items) {
                val obj = el as? JsonObject ?: continue
                val trackObj = (obj["track"] as? JsonObject) ?: obj
                val title = trackObj["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (title.isBlank()) continue
                val artistsArr = trackObj["artists"] as? JsonArray
                val artist = artistsArr?.mapNotNull {
                    (it as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull?.trim()
                }?.joinToString(", ").orEmpty()
                val durationMs = trackObj["duration_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 180_000L
                val albumObj = trackObj["album"] as? JsonObject
                val albumTitle = if (kind == "album") {
                    collectionTitle
                } else {
                    albumObj?.get("name")?.jsonPrimitive?.contentOrNull?.trim()
                }
                val trackCover = if (kind == "album") {
                    collectionCover
                } else {
                    (albumObj?.get("images") as? JsonArray)
                        ?.firstOrNull()
                        ?.let { (it as? JsonObject)?.get("url")?.jsonPrimitive?.contentOrNull }
                        ?.takeIf { it.isNotBlank() }
                }
                val isExplicitFlag = trackObj["explicit"]?.jsonPrimitive?.booleanOrNull == true
                queries += ImportedTrackQuery(
                    title = title,
                    artist = artist,
                    durationMs = durationMs,
                    albumTitle = albumTitle,
                    coverArtUrl = trackCover,
                    isExplicit = isExplicitFlag,
                )
                if (queries.size >= MAX_IMPORT_TRACKS) break
            }

            val hasNext = root["next"]?.jsonPrimitive?.contentOrNull != null
            if (!hasNext || items.size < pageSize) break
            offset += pageSize
        }

        return ParsedPlaylistPayload(
            playlistName = collectionTitle,
            sourceLabel = "Spotify",
            queries = queries,
            coverArtUrl = collectionCover,
        )
    }

    private fun fetchDeezerCollection(
        kind: String,
        id: String,
        fallbackName: String,
    ): ParsedPlaylistPayload? {
        val metaJson = fetchJsonText("https://api.deezer.com/$kind/$id") ?: return null
        val root = runCatching { jsonParser.parseToJsonElement(metaJson).jsonObject }.getOrNull() ?: return null
        val title = root["title"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: fallbackName
        val coverUrl = listOf("picture_xl", "picture_big", "cover_xl", "cover_big", "cover_medium", "picture_medium")
            .firstNotNullOfOrNull { key ->
                root[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            }
        val initialTracks = (root["tracks"] as? JsonObject)?.get("data") as? JsonArray
        val queries = mutableListOf<ImportedTrackQuery>()

        fun appendDeezerArray(arr: JsonArray?) {
            if (arr == null) return
            for (el in arr) {
                if (queries.size >= MAX_IMPORT_TRACKS) break
                val obj = el as? JsonObject ?: continue
                val trackTitle = obj["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (trackTitle.isBlank()) continue
                val contributors = (obj["contributors"] as? JsonArray)?.mapNotNull { c ->
                    (c as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
                }.orEmpty()
                val primaryArtist = (obj["artist"] as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val artist = if (contributors.isNotEmpty()) contributors.distinct().joinToString(", ") else primaryArtist
                val sec = obj["duration"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 180L
                val isExplicitFlag = obj["explicit_lyrics"]?.jsonPrimitive?.booleanOrNull == true
                val albumObj = obj["album"] as? JsonObject
                val album = if (kind == "album") {
                    title
                } else {
                    albumObj?.get("title")?.jsonPrimitive?.contentOrNull?.trim()
                }
                val trackCover = listOf("cover_xl", "cover_big", "cover_medium")
                    .firstNotNullOfOrNull { key ->
                        albumObj?.get(key)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    } ?: coverUrl
                queries += ImportedTrackQuery(
                    title = trackTitle,
                    artist = artist,
                    durationMs = (sec.coerceAtLeast(30L) * 1000L),
                    albumTitle = album,
                    coverArtUrl = trackCover,
                    isExplicit = isExplicitFlag,
                )
            }
        }

        // Paginate Deezer tracks endpoint when playlist/album has more tracks than the initial payload
        val totalTracks = root["nb_tracks"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        if (totalTracks > (initialTracks?.size ?: 0)) {
            var offset = 0
            val pageLimit = 100
            while (queries.size < MAX_IMPORT_TRACKS) {
                val pageJson = fetchJsonText("https://api.deezer.com/$kind/$id/tracks?index=$offset&limit=$pageLimit")
                    ?: break
                val pageRoot = runCatching { jsonParser.parseToJsonElement(pageJson).jsonObject }.getOrNull() ?: break
                val dataArr = pageRoot["data"] as? JsonArray ?: break
                if (dataArr.isEmpty()) break
                appendDeezerArray(dataArr)
                if (dataArr.size < pageLimit || pageRoot["next"]?.jsonPrimitive?.contentOrNull == null) break
                offset += pageLimit
            }
        }
        if (queries.isEmpty()) {
            appendDeezerArray(initialTracks)
        }
        if (queries.isEmpty()) return null
        return ParsedPlaylistPayload(
            playlistName = title,
            sourceLabel = "Deezer",
            queries = queries,
            coverArtUrl = coverUrl,
        )
    }

    private suspend fun finalizeImport(
        payload: ParsedPlaylistPayload,
        overrideName: String?,
    ): PlaylistImportResult {
        val finalName = overrideName ?: payload.playlistName.ifBlank { "Импорт" }
        val resolvedTracks = mutableListOf<TrackEntity>()
        resolvedTracks += payload.directTracks.take(MAX_IMPORT_TRACKS)

        if (payload.queries.isNotEmpty()) {
            val limitedQueries = payload.queries.take(MAX_IMPORT_TRACKS)
            val existingTracks = runCatching { dao.getAllTracksOnce() }.getOrDefault(emptyList())
            val byId = existingTracks.associateBy { it.id }
            val byArtistTitle = HashMap<String, TrackEntity>(existingTracks.size * 2)
            val byTitleOnly = HashMap<String, TrackEntity>(existingTracks.size * 2)
            for (existing in existingTracks) {
                val isConcreteStream = !existing.sourceUri.isNullOrBlank() &&
                    !existing.sourceUri.startsWith("soundlapa://resolve-full") &&
                    !existing.sourceUri.startsWith("soundlap://resolve-full")
                val artistTitleKey = "${existing.artist.trim().lowercase()}|${existing.title.trim().lowercase()}"
                val prevByPair = byArtistTitle[artistTitleKey]
                if (prevByPair == null || (isConcreteStream && prevByPair.sourceUri?.startsWith("soundlapa://") == true)) {
                    byArtistTitle[artistTitleKey] = existing
                }
                val titleKey = existing.title.trim().lowercase()
                val prevByTitle = byTitleOnly[titleKey]
                if (prevByTitle == null || (isConcreteStream && prevByTitle.sourceUri?.startsWith("soundlapa://") == true)) {
                    byTitleOnly[titleKey] = existing
                }
            }

            // Decide upfront which unmatched items get eager parallel network resolution (first 10),
            // while remaining items are materialized instantly with soundlapa://resolve-full for zero-wait playback lookup.
            val localMatches = arrayOfNulls<TrackEntity>(limitedQueries.size)
            val eagerResolveIndices = HashSet<Int>(EAGER_IMPORT_RESOLUTION_LIMIT)
            limitedQueries.forEachIndexed { idx, item ->
                val pairKey = "${item.artist.trim().lowercase()}|${item.title.trim().lowercase()}"
                val lazyId = deterministicImportTrackId(item)
                val localMatch = byId[lazyId]
                    ?: byArtistTitle[pairKey]
                    ?: if (item.artist.isBlank()) byTitleOnly[item.title.trim().lowercase()] else null
                if (localMatch != null) {
                    val hasConcreteSource = !localMatch.sourceUri.isNullOrBlank() &&
                        !localMatch.sourceUri.startsWith("soundlapa://resolve-full") &&
                        !localMatch.sourceUri.startsWith("soundlap://resolve-full")
                    if (hasConcreteSource) {
                        val enrichedCover = localMatch.coverArtPath?.takeIf { it.isNotBlank() }
                            ?: item.coverArtUrl?.takeIf { it.isNotBlank() }
                        val enrichedAlbum = localMatch.albumTitle?.takeIf { it.isNotBlank() }
                            ?: item.albumTitle?.takeIf { it.isNotBlank() }
                        localMatches[idx] = localMatch.copy(
                            coverArtPath = enrichedCover,
                            albumTitle = enrichedAlbum,
                        )
                        return@forEachIndexed
                    }
                }
                if (eagerResolveIndices.size < EAGER_IMPORT_RESOLUTION_LIMIT) {
                    eagerResolveIndices.add(idx)
                }
            }

            val semaphore = Semaphore(IMPORT_SEMAPHORE_CONCURRENCY)
            val foundOrdered = coroutineScope {
                limitedQueries.mapIndexed { idx, item ->
                    async {
                        val cachedLocal = localMatches[idx]
                        if (cachedLocal != null) {
                            return@async idx to cachedLocal
                        }
                        if (item.title.isBlank()) {
                            return@async idx to null
                        }
                        if (!eagerResolveIndices.contains(idx)) {
                            return@async idx to buildLazyImportedTrack(item)
                        }

                        semaphore.withPermit {
                            val searchQuery = buildString {
                                if (item.artist.isNotBlank()) {
                                    append(item.artist)
                                    append(' ')
                                }
                                append(item.title)
                            }.trim()
                            if (searchQuery.isBlank()) return@withPermit idx to null
                            val matched = withTimeoutOrNull(IMPORT_PER_TRACK_TIMEOUT_MS) {
                                val scDef = async {
                                    runCatching { FullStreamResolver.searchSoundCloud(searchQuery, limit = 4) }
                                        .getOrDefault(emptyList())
                                }
                                val ytDef = async {
                                    runCatching { FullStreamResolver.searchYouTubeMusic(searchQuery, limit = 4) }
                                        .getOrDefault(emptyList())
                                }
                                val combinedCandidates = scDef.await() + ytDef.await()
                                FullStreamResolver.pickBestTrackMatch(
                                    candidates = combinedCandidates,
                                    targetArtist = item.artist,
                                    targetTitle = item.title,
                                    expectedDurationMs = item.durationMs,
                                ) ?: audiusRepository.searchTracks(searchQuery, limit = 4).getOrNull()?.let { list ->
                                    FullStreamResolver.pickBestTrackMatch(
                                        candidates = list,
                                        targetArtist = item.artist,
                                        targetTitle = item.title,
                                        expectedDurationMs = item.durationMs,
                                    )
                                }
                            }
                            val finalMatched = if (matched != null) {
                                val resolvedTitle = item.title.takeIf { it.isNotBlank() } ?: matched.title
                                val resolvedArtist = item.artist.takeIf { it.isNotBlank() } ?: matched.artist
                                val preferredCover = item.coverArtUrl?.takeIf { it.isNotBlank() }
                                    ?: matched.coverArtPath?.takeIf { it.isNotBlank() }
                                    ?: audiusRepository.resolvePreferredCoverArt(
                                        artist = resolvedArtist,
                                        title = resolvedTitle,
                                    )
                                matched.copy(
                                    title = resolvedTitle,
                                    artist = resolvedArtist,
                                    albumTitle = item.albumTitle?.takeIf { it.isNotBlank() } ?: matched.albumTitle,
                                    coverArtPath = preferredCover,
                                ).also {
                                    if (item.isExplicit) ExplicitContentDetector.markPreDetectedExplicit(it.id)
                                }
                            } else {
                                buildLazyImportedTrack(item)
                            }
                            idx to finalMatched
                        }
                    }
                }.awaitAll()
                    .sortedBy { it.first }
                    .mapNotNull { it.second }
            }
            resolvedTracks += foundOrdered
        }

        val uniqueTracks = resolvedTracks.distinctBy { it.id }
        if (uniqueTracks.isEmpty()) {
            throw IllegalStateException("Не удалось найти воспроизводимые версии треков")
        }

        audiusRepository.upsertPreservingLocalState(uniqueTracks)

        val playlistId = "import_${UUID.randomUUID()}"
        val resolvedPlaylistCover = payload.coverArtUrl?.takeIf { it.isNotBlank() }
            ?: uniqueTracks.firstNotNullOfOrNull { it.coverArtPath?.takeIf { path -> path.isNotBlank() } }
        dao.upsertPlaylist(
            PlaylistEntity(
                id = playlistId,
                name = finalName,
                description = com.example.data.local.PlaylistMetadataHelper.encodeImportedDescription(payload.sourceLabel),
                coverArtPath = resolvedPlaylistCover,
            ),
        )
        val baseAddedAt = System.currentTimeMillis()
        uniqueTracks.forEachIndexed { idx, track ->
            dao.addTrackToPlaylist(
                PlaylistTrackCrossRef(
                    playlistId = playlistId,
                    trackId = track.id,
                    addedAt = baseAddedAt + idx,
                    position = idx,
                ),
            )
        }

        return PlaylistImportResult(
            playlistId = playlistId,
            playlistName = finalName,
            sourceLabel = payload.sourceLabel,
            totalFound = payload.directTracks.size + payload.queries.size,
            importedCount = uniqueTracks.size,
        )
    }

    private fun resolveSpotifyCanonicalUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        if (trimmed.startsWith("spotify:", ignoreCase = true)) {
            val parts = trimmed.removePrefix("spotify:").split(":")
            if (parts.size >= 2) {
                return "https://open.spotify.com/${parts[0]}/${parts[1]}"
            }
        }
        if (trimmed.contains("spotify.link", ignoreCase = true)) {
            return runCatching {
                val req = Request.Builder()
                    .url(trimmed)
                    .header("User-Agent", FullStreamResolver.DEFAULT_BROWSER_USER_AGENT)
                    .get()
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    resp.request.url.toString()
                }
            }.getOrDefault(trimmed)
        }
        return trimmed
    }

    private fun fetchHtmlText(url: String): String? {
        return runCatching {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", FullStreamResolver.DEFAULT_BROWSER_USER_AGENT)
                .header("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8")
                .get()
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                response.body?.string()
            }
        }.getOrNull()
    }

    private fun fetchJsonText(url: String): String? {
        return runCatching {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", FullStreamResolver.DEFAULT_BROWSER_USER_AGENT)
                .header("Accept", "application/json")
                .get()
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                response.body?.string()
            }
        }.getOrNull()
    }

    private fun fetchAuthorizedJsonText(url: String, bearerToken: String): String? {
        return runCatching {
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $bearerToken")
                .header("User-Agent", FullStreamResolver.DEFAULT_BROWSER_USER_AGENT)
                .header("Accept", "application/json")
                .get()
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                response.body?.string()
            }
        }.getOrNull()
    }

    private fun resolveDisplayName(uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) cursor.getString(idx) else null
                    } else null
                }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')
    }

    companion object {
        const val MAX_PLAYLIST_FILE_BYTES = 12 * 1024 * 1024
        const val MAX_IMPORT_TRACKS = 500
        const val EAGER_IMPORT_RESOLUTION_LIMIT = 10
        const val IMPORT_SEMAPHORE_CONCURRENCY = 12
        const val IMPORT_PER_TRACK_TIMEOUT_MS = 4500L

        fun deterministicImportTrackId(item: ImportedTrackQuery): String {
            val normArtist = item.artist.trim().lowercase()
            val normTitle = item.title.trim().lowercase()
            val hash = kotlin.math.abs("$normArtist|$normTitle".hashCode())
            return "imp_$hash"
        }

        fun buildLazyImportedTrack(item: ImportedTrackQuery): TrackEntity? {
            val cleanTitle = item.title.trim()
            if (cleanTitle.isBlank()) return null
            val cleanArtist = item.artist.trim()
            val lazyId = deterministicImportTrackId(item)
            val searchQuery = listOf(cleanArtist, cleanTitle).filter { it.isNotBlank() }.joinToString(" ")
            val duration = item.durationMs.coerceAtLeast(30_000L)
            fun encodeParam(value: String): String =
                java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
            val lazyUri = "soundlapa://resolve-full?id=${encodeParam(lazyId)}" +
                "&q=${encodeParam(searchQuery)}" +
                "&artist=${encodeParam(cleanArtist)}" +
                "&title=${encodeParam(cleanTitle)}" +
                "&dur=$duration"
            if (item.isExplicit) {
                ExplicitContentDetector.markPreDetectedExplicit(lazyId)
            }
            return TrackEntity(
                id = lazyId,
                title = cleanTitle,
                artist = cleanArtist.ifBlank { "Неизвестный артист" },
                genre = "Импорт",
                durationMs = duration,
                waveformJson = TrackEntity.encodeWaveform(TrackEntity.defaultWaveform()),
                cachedFilePath = null,
                sourceUri = lazyUri,
                coverArtPath = item.coverArtUrl?.takeIf { it.isNotBlank() },
                accentColorHex = 0xFF8B5CF6L,
                secondaryColorHex = 0xFF5B21B6L,
                isUserUpload = false,
                isAutoImported = true,
                isCachedOffline = false,
                fileSizeBytes = 0L,
                albumTitle = item.albumTitle?.takeIf { it.isNotBlank() },
            )
        }

        private val ALLOWED_FILE_EXTENSIONS = setOf("csv", "m3u", "m3u8", "txt", "json")
        private val ALLOWED_MIME_TYPES = setOf(
            "text/csv",
            "text/comma-separated-values",
            "text/tab-separated-values",
            "text/plain",
            "application/json",
            "application/vnd.ms-excel",
            "audio/x-mpegurl",
            "application/vnd.apple.mpegurl",
            "audio/mpegurl",
        )

        private val jsonParser = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        fun validateAndParseFileContent(
            fileName: String?,
            mimeType: String?,
            rawBytes: ByteArray,
            customPlaylistName: String? = null,
        ): ParsedPlaylistPayload {
            if (rawBytes.isEmpty()) {
                throw IllegalArgumentException("Файл пустой")
            }
            if (rawBytes.size > MAX_PLAYLIST_FILE_BYTES) {
                throw IllegalArgumentException("Файл слишком большой (максимум 5 МБ)")
            }

            val cleanName = fileName?.trim().orEmpty()
            val ext = if (cleanName.contains('.')) {
                cleanName.substringAfterLast('.', "").lowercase()
            } else {
                ""
            }
            if (ext.isNotEmpty() && ext !in ALLOWED_FILE_EXTENSIONS) {
                throw IllegalArgumentException(
                    "Неподдерживаемый формат файла (.$ext). Допустимы только CSV, M3U, M3U8, JSON и TXT со списком треков.",
                )
            }

            val normMime = mimeType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
            if (normMime.isNotEmpty() && normMime != "application/octet-stream" && normMime !in ALLOWED_MIME_TYPES) {
                throw IllegalArgumentException(
                    "Неподдерживаемый тип файла ($normMime). Выберите файл плейлиста CSV, M3U, JSON или TXT.",
                )
            }
            if (ext.isEmpty() && normMime !in ALLOWED_MIME_TYPES) {
                throw IllegalArgumentException(
                    "Неизвестный формат файла. Выберите файл .csv, .m3u, .m3u8, .json или .txt.",
                )
            }

            val headSampleSize = minOf(rawBytes.size, 8192)
            for (i in 0 until headSampleSize) {
                if (rawBytes[i] == 0.toByte()) {
                    throw IllegalArgumentException("Файл не соответствует требованиям: обнаружены бинарные данные.")
                }
            }
            if (hasBinaryMagicHeader(rawBytes)) {
                throw IllegalArgumentException("Этот файл не является текстовым плейлистом (обнаружен медиа/архивный заголовок).")
            }

            val rawText = try {
                val decoder = Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                decoder.decode(ByteBuffer.wrap(rawBytes)).toString()
            } catch (_: Exception) {
                throw IllegalArgumentException("Файл не соответствует требованиям: некорректная кодировка (ожидается UTF-8).")
            }

            val trimmed = rawText.removePrefix("\uFEFF").trim()
            if (trimmed.isEmpty()) {
                throw IllegalArgumentException("Файл пустой")
            }

            val controlCount = trimmed.take(4096).count { ch ->
                ch.code < 32 && ch != '\n' && ch != '\r' && ch != '\t'
            }
            if (controlCount > 0) {
                throw IllegalArgumentException("Файл содержит недопустимые управляющие символы.")
            }

            val lowerTrimmed = trimmed.lowercase()
            if (lowerTrimmed.startsWith("<!doctype") ||
                lowerTrimmed.startsWith("<html") ||
                lowerTrimmed.startsWith("<?xml")
            ) {
                throw IllegalArgumentException("HTML/XML-страницы не поддерживаются для импорта плейлистов.")
            }

            val fallbackName = customPlaylistName?.takeIf { it.isNotBlank() }
                ?: cleanName.substringBeforeLast('.').ifBlank { "Импортированный плейлист" }

            val payload = when (ext) {
                "m3u", "m3u8" -> {
                    val hasM3uMarker = trimmed.contains("#EXTM3U", ignoreCase = true) ||
                        trimmed.contains("#EXTINF", ignoreCase = true)
                    val parsed = parseM3uTracklist(trimmed, fallbackName)
                    if (!hasM3uMarker && parsed.queries.isEmpty()) {
                        throw IllegalArgumentException("Файл M3U не содержит корректных записей #EXTINF.")
                    }
                    parsed
                }
                "json" -> {
                    if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
                        throw IllegalArgumentException("Файл JSON повреждён или имеет неверную структуру.")
                    }
                    val root = runCatching { jsonParser.parseToJsonElement(trimmed) }.getOrNull()
                        ?: throw IllegalArgumentException("Не удалось разобрать JSON-файл плейлиста.")
                    val parsed = parseJsonTracklist(trimmed, fallbackName)
                    if ((root !is JsonObject && root !is JsonArray) || parsed.queries.isEmpty()) {
                        throw IllegalArgumentException("В JSON-файле не найдены треки с полями названия и исполнителя.")
                    }
                    parsed
                }
                "csv" -> {
                    if (!looksLikeValidCsvTable(trimmed)) {
                        throw IllegalArgumentException("CSV-файл не содержит колонок с треками и исполнителями.")
                    }
                    parseCsvTracklist(trimmed, fallbackName)
                }
                "txt" -> {
                    parseStrictPlainTextTracklist(trimmed, fallbackName)
                }
                else -> {
                    // Extensionless file validated by MIME type
                    when {
                        normMime.contains("mpegurl") -> parseM3uTracklist(trimmed, fallbackName)
                        normMime == "application/json" -> parseJsonTracklist(trimmed, fallbackName)
                        normMime.contains("csv") -> {
                            if (!looksLikeValidCsvTable(trimmed)) {
                                throw IllegalArgumentException("CSV-файл не содержит корректной таблицы треков.")
                            }
                            parseCsvTracklist(trimmed, fallbackName)
                        }
                        else -> parseStrictPlainTextTracklist(trimmed, fallbackName)
                    }
                }
            }

            if (payload.queries.isEmpty() && payload.directTracks.isEmpty()) {
                throw IllegalArgumentException("Файл не содержит распознанных треков в допустимом формате.")
            }
            return payload
        }

        private fun hasBinaryMagicHeader(bytes: ByteArray): Boolean {
            if (bytes.size >= 4) {
                val b0 = bytes[0].toInt() and 0xFF
                val b1 = bytes[1].toInt() and 0xFF
                val b2 = bytes[2].toInt() and 0xFF
                val b3 = bytes[3].toInt() and 0xFF
                // %PDF, ZIP/APK/DOCX, PNG, JPEG, GIF, ID3, MP3 frame sync, FLAC, OggS, RIFF
                if ((b0 == 0x25 && b1 == 0x50 && b2 == 0x44 && b3 == 0x46) ||
                    (b0 == 0x50 && b1 == 0x4B && b2 == 0x03 && b3 == 0x04) ||
                    (b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47) ||
                    (b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF) ||
                    (b0 == 0x47 && b1 == 0x49 && b2 == 0x46 && b3 == 0x38) ||
                    (b0 == 0x49 && b1 == 0x44 && b2 == 0x33) ||
                    (b0 == 0x66 && b1 == 0x4C && b2 == 0x61 && b3 == 0x43) ||
                    (b0 == 0x4F && b1 == 0x67 && b2 == 0x67 && b3 == 0x53) ||
                    (b0 == 0x52 && b1 == 0x49 && b2 == 0x46 && b3 == 0x46) ||
                    (b0 == 0x7F && b1 == 0x45 && b2 == 0x4C && b3 == 0x46)
                ) {
                    return true
                }
            }
            return false
        }

        private fun looksLikeValidCsvTable(content: String): Boolean {
            val nonEmpty = content.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
            if (nonEmpty.isEmpty()) return false
            val first = nonEmpty.first()
            val delimiter = when {
                first.count { it == ';' } > first.count { it == ',' } -> ';'
                first.count { it == ',' } > 0 -> ','
                first.count { it == '\t' } > 0 -> '\t'
                else -> return false
            }
            val headerCells = splitCsvRow(first, delimiter).map { it.trim().lowercase() }
            if (headerCells.size < 2) return false
            val hasKnownHeader = headerCells.any {
                it.contains("track") || it.contains("title") || it.contains("song") ||
                    it.contains("name") || it.contains("artist") || it.contains("название") ||
                    it.contains("исполнитель")
            }
            if (hasKnownHeader) return nonEmpty.size >= 2
            // Headerless CSV: ensure all sampled rows have at least 2 non-code columns
            return nonEmpty.take(5).all { row ->
                val cols = splitCsvRow(row, delimiter)
                cols.size >= 2 && cols[0].isNotBlank() && cols[0].length <= 160 && cols[1].length <= 160
            }
        }

        private fun parseStrictPlainTextTracklist(text: String, fallbackName: String): ParsedPlaylistPayload {
            val contentLines = text.lines()
                .map { it.trim() }
                .filter { it.isNotBlank() && !it.startsWith("#") }
            if (contentLines.isEmpty()) {
                throw IllegalArgumentException("Текстовый файл не содержит строк со списком треков.")
            }

            val parsedQueries = mutableListOf<ImportedTrackQuery>()
            var matchedSeparatorLines = 0
            for (rawLine in contentLines) {
                val cleaned = rawLine.replace(Regex("^\\d+[.)]\\s*"), "").trim()
                if (cleaned.length !in 3..200) continue
                val withSep = parseStrictSeparatedTrackLine(cleaned)
                if (withSep != null) {
                    matchedSeparatorLines++
                    parsedQueries += withSep
                }
            }

            if (parsedQueries.isEmpty() || matchedSeparatorLines * 2 < contentLines.size) {
                throw IllegalArgumentException(
                    "TXT-файл должен содержать список треков в формате «Исполнитель - Название трека» (по одной записи на строку).",
                )
            }

            return ParsedPlaylistPayload(
                playlistName = fallbackName,
                sourceLabel = "TXT Tracklist",
                queries = parsedQueries,
            )
        }

        private fun parseStrictSeparatedTrackLine(cleaned: String): ImportedTrackQuery? {
            val separators = listOf(" — ", " – ", " - ", " | ")
            for (sep in separators) {
                val idx = cleaned.indexOf(sep)
                if (idx > 0) {
                    val left = cleaned.substring(0, idx).trim()
                    val right = cleaned.substring(idx + sep.length).trim()
                    if (left.isNotBlank() && right.isNotBlank() && left.length <= 100 && right.length <= 120) {
                        return ImportedTrackQuery(title = right, artist = left)
                    }
                }
            }
            return null
        }

        fun isUrlLike(input: String): Boolean {
            val t = input.trim().lowercase()
            return t.startsWith("http://") ||
                t.startsWith("https://") ||
                t.startsWith("spotify:") ||
                t.startsWith("open.spotify.com/") ||
                t.startsWith("music.youtube.com/")
        }

        fun normalizeUrlInput(input: String): String {
            val trimmed = input.trim().lines().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            return if (trimmed.startsWith("open.spotify.com/") || trimmed.startsWith("music.youtube.com/")) {
                "https://$trimmed"
            } else {
                trimmed
            }
        }

        fun parseSpotifyResourceRef(rawUrl: String): Pair<String, String>? {
            val match = Regex(
                "https?://open\\.spotify\\.com/(?:intl-[a-z]{2}/)?(playlist|album|track)/([a-zA-Z0-9]+)",
                RegexOption.IGNORE_CASE,
            ).find(rawUrl) ?: return null
            return match.groupValues[1].lowercase() to match.groupValues[2]
        }

        fun toSpotifyEmbedUrl(rawUrl: String): String? {
            val (kind, id) = parseSpotifyResourceRef(rawUrl) ?: return null
            return "https://open.spotify.com/embed/$kind/$id"
        }

        fun looksLikeCsv(raw: String): Boolean {
            val firstLine = raw.lineSequence().firstOrNull { it.isNotBlank() }?.lowercase() ?: return false
            return (firstLine.contains(",") || firstLine.contains(";")) &&
                (firstLine.contains("track") || firstLine.contains("artist") || firstLine.contains("title") || firstLine.contains("spotify"))
        }

        fun parseSpotifyEmbedHtml(html: String, fallbackName: String): ParsedPlaylistPayload {
            val nextDataMatch = Regex(
                """<script[^>]*id=["']__NEXT_DATA__["'][^>]*>(.*?)</script>""",
                setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
            ).find(html)

            if (nextDataMatch != null) {
                val jsonText = nextDataMatch.groupValues[1].trim()
                val parsed = runCatching {
                    val root = jsonParser.parseToJsonElement(jsonText).jsonObject
                    val entity = root["props"]?.jsonObject
                        ?.get("pageProps")?.jsonObject
                        ?.get("state")?.jsonObject
                        ?.get("data")?.jsonObject
                        ?.get("entity")?.jsonObject

                    if (entity != null) {
                        val title = entity["name"]?.jsonPrimitive?.contentOrNull
                            ?: entity["title"]?.jsonPrimitive?.contentOrNull
                            ?: fallbackName
                        val entityType = entity["type"]?.jsonPrimitive?.contentOrNull?.lowercase()
                        val trackList = entity["trackList"] as? JsonArray
                        val queries = if (trackList != null && trackList.isNotEmpty()) {
                            trackList.mapNotNull { el ->
                                val obj = el as? JsonObject ?: return@mapNotNull null
                                val trackTitle = obj["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                                val subtitle = obj["subtitle"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                                val dur = obj["duration"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 180_000L
                                val isExp = obj["isExplicit"]?.jsonPrimitive?.booleanOrNull == true
                                if (trackTitle.isBlank()) return@mapNotNull null
                                ImportedTrackQuery(
                                    title = trackTitle,
                                    artist = subtitle,
                                    durationMs = dur,
                                    albumTitle = if (entityType == "album") decodeHtmlEntities(title) else null,
                                    isExplicit = isExp,
                                )
                            }
                        } else {
                            // Single track embed entity
                            val trackTitle = entity["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                            val artistsArr = entity["artists"] as? JsonArray
                            val artistNames = artistsArr?.mapNotNull {
                                (it as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull
                            }?.joinToString(", ").orEmpty()
                            if (trackTitle.isNotBlank()) {
                                listOf(ImportedTrackQuery(title = trackTitle, artist = artistNames))
                            } else {
                                emptyList()
                            }
                        }
                        val embedCover = ((entity["coverArt"] as? JsonObject)?.get("sources") as? JsonArray)
                            ?.lastOrNull()?.let { (it as? JsonObject)?.get("url")?.jsonPrimitive?.contentOrNull }
                            ?: ((entity["visualIdentity"] as? JsonObject)?.get("image") as? JsonArray)
                                ?.lastOrNull()?.let { (it as? JsonObject)?.get("url")?.jsonPrimitive?.contentOrNull }
                            ?: extractMetaContent(html, "og:image")?.takeIf { it.isNotBlank() }

                        ParsedPlaylistPayload(
                            playlistName = decodeHtmlEntities(title),
                            sourceLabel = "Spotify",
                            queries = queries,
                            coverArtUrl = embedCover,
                        )
                    } else null
                }.getOrNull()

                if (parsed != null && parsed.queries.isNotEmpty()) {
                    return parsed
                }
            }

            // Try Base64 <script id="initial-state"> on modern open.spotify.com web pages
            val initialStateMatch = Regex(
                """<script[^>]*id=["']initial-state["'][^>]*>(.*?)</script>""",
                setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
            ).find(html)
            if (initialStateMatch != null) {
                val decodedJson = runCatching {
                    val b64 = initialStateMatch.groupValues[1].trim()
                    String(Base64.getDecoder().decode(b64), Charsets.UTF_8)
                }.getOrNull()
                if (!decodedJson.isNullOrBlank()) {
                    val queries = mutableListOf<ImportedTrackQuery>()
                    runCatching {
                        extractTracksFromJsonElement(jsonParser.parseToJsonElement(decodedJson), queries)
                    }
                    if (queries.isNotEmpty()) {
                        return ParsedPlaylistPayload(
                            playlistName = fallbackName,
                            sourceLabel = "Spotify",
                            queries = queries.distinctBy { "${it.artist.lowercase()}|${it.title.lowercase()}" },
                            coverArtUrl = extractMetaContent(html, "og:image")?.takeIf { it.isNotBlank() },
                        )
                    }
                }
            }

            // Fallback: OpenGraph / track title extraction
            return parseGenericHtmlPlaylist(html, fallbackName, sourceLabelOverride = "Spotify")
        }

        fun parseGenericHtmlPlaylist(
            html: String,
            fallbackName: String,
            sourceLabelOverride: String? = null,
        ): ParsedPlaylistPayload {
            val ogTitle = extractMetaContent(html, "og:title")
                ?: Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                    .find(html)?.groupValues?.get(1)?.trim()
            val ogImage = extractMetaContent(html, "og:image")?.takeIf { it.isNotBlank() }

            val playlistName = ogTitle
                ?.substringBefore("| Spotify")
                ?.substringBefore(" - playlist by")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: fallbackName

            val queries = mutableListOf<ImportedTrackQuery>()

            // Try JSON-LD scripts
            val ldRegex = Regex(
                """<script[^>]*type=["']application/ld\+json["'][^>]*>(.*?)</script>""",
                setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
            )
            ldRegex.findAll(html).forEach { m ->
                runCatching {
                    val el = jsonParser.parseToJsonElement(m.groupValues[1].trim())
                    extractTracksFromJsonElement(el, queries)
                }
            }

            // Try Apple Music serialized server data
            if (queries.isEmpty()) {
                val appleMatch = Regex(
                    """<script[^>]*id=["']serialized-server-data["'][^>]*>(.*?)</script>""",
                    setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
                ).find(html)
                if (appleMatch != null) {
                    runCatching {
                        val el = jsonParser.parseToJsonElement(appleMatch.groupValues[1].trim())
                        extractTracksFromJsonElement(el, queries)
                    }
                }
            }

            // Try Apple Music / Spotify / YouTube meta tags or single song title-artist
            if (queries.isEmpty()) {
                val ogDesc = extractMetaContent(html, "og:description").orEmpty()
                if (ogTitle != null && (ogTitle.contains(" - ") || ogTitle.contains(" — "))) {
                    parseTrackLine(decodeHtmlEntities(ogTitle))?.let { queries += it }
                } else if (ogDesc.contains(" · ") || ogDesc.contains(" - ")) {
                    ogDesc.split(" · ", " • ", ";").forEach { segment ->
                        parseTrackLine(decodeHtmlEntities(segment))?.let { queries += it }
                    }
                }
            }

            return ParsedPlaylistPayload(
                playlistName = decodeHtmlEntities(playlistName),
                sourceLabel = sourceLabelOverride ?: "Link",
                queries = queries.distinctBy { "${it.artist.lowercase()}|${it.title.lowercase()}" },
                coverArtUrl = ogImage,
            )
        }

        fun parseCsvTracklist(csvContent: String, fallbackName: String): ParsedPlaylistPayload {
            val lines = csvContent.lines().filter { it.isNotBlank() }
            if (lines.isEmpty()) {
                return ParsedPlaylistPayload(fallbackName, "CSV", emptyList())
            }

            val delimiter = when {
                lines.first().count { it == ';' } > lines.first().count { it == ',' } -> ';'
                lines.first().count { it == '\t' } > lines.first().count { it == ',' } -> '\t'
                else -> ','
            }
            val firstCells = splitCsvRow(lines.first(), delimiter)
            val headerNormalized = firstCells.map { it.trim().lowercase().removePrefix("\uFEFF") }

            val titleIdx = headerNormalized.indexOfFirst {
                it == "track name" || it == "title" || it == "track" || it == "song" || it == "name" || it.contains("название")
            }
            val artistIdx = headerNormalized.indexOfFirst {
                it == "artist name(s)" || it == "artist" || it == "artists" || it.contains("artist") || it.contains("исполнитель")
            }
            val albumIdx = headerNormalized.indexOfFirst {
                it == "album name" || it == "album" || it.contains("album") || it.contains("альбом")
            }

            val hasHeader = titleIdx >= 0 || artistIdx >= 0
            val dataLines = if (hasHeader) lines.drop(1) else lines
            val effectiveTitleIdx = if (titleIdx >= 0) titleIdx else 0
            val effectiveArtistIdx = if (artistIdx >= 0) artistIdx else 1

            val queries = dataLines.mapNotNull { line ->
                val cols = splitCsvRow(line, delimiter)
                val title = cols.getOrNull(effectiveTitleIdx)?.trim().orEmpty()
                val rawArtist = cols.getOrNull(effectiveArtistIdx)?.trim().orEmpty()
                val artist = rawArtist.split(";").joinToString(", ") { it.trim() }.trim(',', ' ')
                val albumTitle = if (albumIdx >= 0) cols.getOrNull(albumIdx)?.trim()?.takeIf { it.isNotBlank() } else null
                if (title.isBlank()) return@mapNotNull null
                ImportedTrackQuery(title = title, artist = artist, albumTitle = albumTitle)
            }

            return ParsedPlaylistPayload(
                playlistName = fallbackName,
                sourceLabel = "CSV / Spotify Export",
                queries = queries,
            )
        }

        fun parseM3uTracklist(m3uContent: String, fallbackName: String): ParsedPlaylistPayload {
            var detectedName = fallbackName
            val queries = mutableListOf<ImportedTrackQuery>()
            var lastWasExtInf = false

            for (rawLine in m3uContent.lines()) {
                val line = rawLine.trim()
                if (line.isBlank()) continue
                if (line.startsWith("#PLAYLIST:", ignoreCase = true)) {
                    line.substringAfter(":").trim().takeIf { it.isNotBlank() }?.let { detectedName = it }
                    lastWasExtInf = false
                    continue
                }
                if (line.startsWith("#EXTINF:", ignoreCase = true)) {
                    val infoPart = line.substringAfter(",", "").trim()
                    val sec = line.substringAfter(":").substringBefore(",").trim().toLongOrNull() ?: 180L
                    val parsed = parseTrackLine(infoPart)
                        ?: ImportedTrackQuery(title = infoPart, artist = "")
                    if (parsed.title.isNotBlank()) {
                        queries += parsed.copy(durationMs = (sec.coerceAtLeast(30L) * 1000L))
                    }
                    lastWasExtInf = true
                    continue
                }
                if (line.startsWith("#")) {
                    lastWasExtInf = false
                    continue
                }
                if (lastWasExtInf) {
                    lastWasExtInf = false
                    continue
                }
                if (!line.startsWith("http", ignoreCase = true)) {
                    val filename = line.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.')
                    parseStrictSeparatedTrackLine(filename)?.let { parsed ->
                        if (queries.none { it.title.equals(parsed.title, ignoreCase = true) }) {
                            queries += parsed
                        }
                    }
                }
            }

            return ParsedPlaylistPayload(
                playlistName = detectedName,
                sourceLabel = "M3U Playlist",
                queries = queries,
            )
        }

        fun parseJsonTracklist(jsonContent: String, fallbackName: String): ParsedPlaylistPayload {
            val queries = mutableListOf<ImportedTrackQuery>()
            var detectedName = fallbackName
            runCatching {
                val root = jsonParser.parseToJsonElement(jsonContent)
                if (root is JsonObject) {
                    (root["name"] ?: root["title"])?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let {
                        detectedName = it
                    }
                }
                extractTracksFromJsonElement(root, queries)
            }
            return ParsedPlaylistPayload(
                playlistName = detectedName,
                sourceLabel = "JSON",
                queries = queries,
            )
        }

        fun parsePlainTextTracklist(text: String, fallbackName: String): ParsedPlaylistPayload {
            val queries = text.lines()
                .map { it.trim() }
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .mapNotNull { line ->
                    val cleaned = line.replace(Regex("^\\d+[.)]\\s*"), "").trim()
                    if (cleaned.length < 2) null
                    else parseTrackLine(cleaned) ?: ImportedTrackQuery(title = cleaned, artist = "")
                }
            return ParsedPlaylistPayload(
                playlistName = fallbackName,
                sourceLabel = "Tracklist",
                queries = queries,
            )
        }

        private fun parseTrackLine(line: String): ImportedTrackQuery? {
            val cleaned = line.trim()
            if (cleaned.isBlank()) return null
            parseStrictSeparatedTrackLine(cleaned)?.let { return it }
            return ImportedTrackQuery(title = cleaned, artist = "")
        }

        private fun extractTracksFromJsonElement(
            element: kotlinx.serialization.json.JsonElement,
            out: MutableList<ImportedTrackQuery>,
        ) {
            when (element) {
                is JsonArray -> element.forEach { extractTracksFromJsonElement(it, out) }
                is JsonObject -> {
                    val title = (element["trackName"] ?: element["track"] ?: element["title"] ?: element["name"])
                        ?.jsonPrimitive?.contentOrNull?.trim()
                    val artist = (element["artistName"] ?: element["artist"] ?: element["artists"] ?: element["subtitle"])
                        ?.let { artistEl ->
                            when (artistEl) {
                                is JsonArray -> artistEl.mapNotNull {
                                    (it as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull
                                        ?: runCatching { it.jsonPrimitive.contentOrNull }.getOrNull()
                                }.joinToString(", ")
                                is JsonObject -> artistEl["name"]?.jsonPrimitive?.contentOrNull
                                else -> runCatching { artistEl.jsonPrimitive.contentOrNull }.getOrNull()
                            }
                        }?.trim().orEmpty()
                    val album = (element["albumName"] ?: element["album"])?.let { albEl ->
                        when (albEl) {
                            is JsonObject -> albEl["name"]?.jsonPrimitive?.contentOrNull
                            else -> runCatching { albEl.jsonPrimitive.contentOrNull }.getOrNull()
                        }
                    }?.trim()?.takeIf { it.isNotBlank() }

                    val nestedTracks = element["tracks"] ?: element["items"] ?: element["track"]
                    if (nestedTracks is JsonArray || nestedTracks is JsonObject) {
                        extractTracksFromJsonElement(nestedTracks, out)
                    } else if (!title.isNullOrBlank() && artist.isNotBlank()) {
                        out += ImportedTrackQuery(title = title, artist = artist, albumTitle = album)
                    } else {
                        element.values.forEach { child ->
                            if (child is JsonArray || child is JsonObject) {
                                extractTracksFromJsonElement(child, out)
                            }
                        }
                    }
                }
                else -> Unit
            }
        }

        private fun splitCsvRow(line: String, delimiter: Char): List<String> {
            val result = mutableListOf<String>()
            val current = StringBuilder()
            var inQuotes = false
            var i = 0
            while (i < line.length) {
                val c = line[i]
                when {
                    c == '"' -> {
                        if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                            current.append('"')
                            i++
                        } else {
                            inQuotes = !inQuotes
                        }
                    }
                    c == delimiter && !inQuotes -> {
                        result += current.toString()
                        current.clear()
                    }
                    else -> current.append(c)
                }
                i++
            }
            result += current.toString()
            return result
        }

        private fun extractMetaContent(html: String, propertyOrName: String): String? {
            val escaped = Regex.escape(propertyOrName)
            val regex1 = Regex(
                """<meta[^>]+(?:property|name)=["']$escaped["'][^>]+content=["']([^"']*)["']""",
                RegexOption.IGNORE_CASE,
            )
            val regex2 = Regex(
                """<meta[^>]+content=["']([^"']*)["'][^>]+(?:property|name)=["']$escaped["']""",
                RegexOption.IGNORE_CASE,
            )
            return (regex1.find(html) ?: regex2.find(html))?.groupValues?.get(1)?.trim()
        }

        private fun decodeHtmlEntities(raw: String): String {
            return raw
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&#x27;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .trim()
        }
    }
}
