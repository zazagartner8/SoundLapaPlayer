package com.example.data.remote

import android.content.Context
import androidx.compose.runtime.Immutable
import com.example.data.local.PlaylistEntity
import com.example.data.local.PlaylistTrackCrossRef
import com.example.data.local.SoundLapaDao
import com.example.data.local.TrackEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs

@Serializable
data class AudiusArtworkDto(
    @SerialName("150x150") val small: String? = null,
    @SerialName("480x480") val medium: String? = null,
    @SerialName("1000x1000") val large: String? = null,
)

@Serializable
data class AudiusUserDto(
    val id: String = "",
    val handle: String = "",
    val name: String = "",
    @SerialName("profile_picture") val profilePicture: AudiusArtworkDto? = null,
)

@Serializable
data class AudiusTrackDto(
    val id: String = "",
    val title: String = "",
    val duration: Int = 0,
    val genre: String? = null,
    val description: String? = null,
    @SerialName("play_count") val playCount: Int = 0,
    @SerialName("favorite_count") val favoriteCount: Int = 0,
    val artwork: AudiusArtworkDto? = null,
    val user: AudiusUserDto? = null,
)

@Serializable
data class AudiusPlaylistDto(
    val id: String = "",
    @SerialName("playlist_name") val playlistName: String = "",
    val description: String? = null,
    val artwork: AudiusArtworkDto? = null,
    @SerialName("is_album") val isAlbum: Boolean = false,
)

@Serializable
data class AudiusFavoriteDto(
    @SerialName("favorite_item_id") val itemId: String = "",
    @SerialName("favorite_type") val favoriteType: String = "",
)

@Serializable
private data class AudiusTrackListResponse(val data: List<AudiusTrackDto> = emptyList())

@Serializable
private data class AudiusSingleTrackResponse(val data: AudiusTrackDto? = null)

@Serializable
private data class AudiusUserResponse(val data: AudiusUserDto? = null)

@Serializable
private data class AudiusUserListResponse(val data: List<AudiusUserDto> = emptyList())

@Serializable
private data class AudiusPlaylistListResponse(val data: List<AudiusPlaylistDto> = emptyList())

@Serializable
private data class AudiusFavoriteListResponse(val data: List<AudiusFavoriteDto> = emptyList())

@Immutable
data class SearchArtistProfile(
    val id: String,
    val name: String,
    val avatarUrl: String?,
    val sourceLabel: String,
    val followersCount: Int = 0,
    val seedTrack: TrackEntity? = null,
    val spotifyId: String? = null,
    val soundCloudUrl: String? = null,
)

data class ArtistCatalogResult(
    val artistName: String,
    val avatarUrl: String?,
    val tracks: List<TrackEntity>,
    val albums: List<ArtistAlbumItem>,
)

data class AudiusSyncSummary(
    val userId: String,
    val handle: String,
    val displayName: String,
    val importedTracks: Int,
    val importedPlaylists: Int,
)

class AudiusRepository(
    private val context: Context,
    private val dao: SoundLapaDao,
) {
    private val appName = "SoundLapa"
    private val baseUrls = listOf(
        "https://api.audius.co/v1",
        "https://discoveryprovider.audius.co/v1",
    )
    @Volatile
    private var preferredBaseUrl: String = baseUrls.first()

    private val connectionPool = ConnectionPool(8, 5, TimeUnit.MINUTES)

    private val client = OkHttpClient.Builder()
        .connectionPool(connectionPool)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val searchClient = client.newBuilder()
        .connectTimeout(3500, TimeUnit.MILLISECONDS)
        .readTimeout(4000, TimeUnit.MILLISECONDS)
        .callTimeout(5000, TimeUnit.MILLISECONDS)
        .build()

    private val searchCache = ConcurrentHashMap<String, Pair<Long, List<TrackEntity>>>()
    private val artistCatalogCache = ConcurrentHashMap<String, Pair<Long, ArtistCatalogResult>>()
    private val searchCacheTtlMs = 5 * 60 * 1000L

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    private val audioCacheDir: File by lazy {
        File(context.filesDir, "audio_cache").apply { mkdirs() }
    }

    fun clearSearchCache() {
        searchCache.clear()
        artistCatalogCache.clear()
    }

    suspend fun fetchTrendingTracks(limit: Int = 36): Result<List<TrackEntity>> = withContext(Dispatchers.IO) {
        runCatching {
            runCatching { dao.deleteUnreferencedPreviewTracks() }
            coroutineScope {
                val audiusWeekDeferred = async {
                    withTimeoutOrNull(6500L) {
                        runCatching {
                            val body = executeGet("/tracks/trending?app_name=$appName&time=week&limit=$limit")
                            json.decodeFromString<AudiusTrackListResponse>(body).data
                                .filter { it.id.isNotBlank() && it.title.isNotBlank() && it.duration >= 45 }
                                .map { dtoToEntity(it, forceLiked = false) }
                        }.getOrDefault(emptyList())
                    }.orEmpty()
                }

                val audiusHipHopDeferred = async {
                    withTimeoutOrNull(6500L) {
                        runCatching {
                            val genreEncoded = URLEncoder.encode("Hip-Hop/Rap", "UTF-8")
                            val body = executeGet("/tracks/trending?app_name=$appName&genre=$genreEncoded&time=month&limit=16")
                            json.decodeFromString<AudiusTrackListResponse>(body).data
                                .filter { it.id.isNotBlank() && it.title.isNotBlank() && it.duration >= 45 }
                                .map { dtoToEntity(it, forceLiked = false) }
                        }.getOrDefault(emptyList())
                    }.orEmpty()
                }

                val audiusElectronicDeferred = async {
                    withTimeoutOrNull(6500L) {
                        runCatching {
                            val genreEncoded = URLEncoder.encode("Electronic", "UTF-8")
                            val body = executeGet("/tracks/trending?app_name=$appName&genre=$genreEncoded&time=month&limit=16")
                            json.decodeFromString<AudiusTrackListResponse>(body).data
                                .filter { it.id.isNotBlank() && it.title.isNotBlank() && it.duration >= 45 }
                                .map { dtoToEntity(it, forceLiked = false) }
                        }.getOrDefault(emptyList())
                    }.orEmpty()
                }

                val popularQueries = listOf(
                    "madk1d",
                    "miyagi",
                    "eminem",
                    "the weeknd",
                )
                val ytPopularDeferred = popularQueries.map { q ->
                    async {
                        withTimeoutOrNull(7000L) {
                            FullStreamResolver.searchYouTubeMusic(query = q, limit = 5)
                        }.orEmpty()
                    }
                }
                val scPopularDeferred = listOf("madk1d", "miyagi").map { q ->
                    async {
                        withTimeoutOrNull(6500L) {
                            FullStreamResolver.searchSoundCloud(query = q, limit = 5)
                        }.orEmpty()
                    }
                }

                val combined = LinkedHashMap<String, TrackEntity>()
                (ytPopularDeferred.flatMap { it.await() } +
                    scPopularDeferred.flatMap { it.await() } +
                    audiusWeekDeferred.await() +
                    audiusHipHopDeferred.await() +
                    audiusElectronicDeferred.await()
                ).forEach { track ->
                    combined.putIfAbsent(track.id, track)
                }

                val entities = combined.values.toList()
                upsertPreservingLocalState(entities)
                entities
            }
        }
    }

    suspend fun searchTracks(query: String, limit: Int = 28): Result<List<TrackEntity>> =
        searchTracksProgressive(query = query, limit = limit, onPartialResults = {})

    suspend fun searchTracksProgressive(
        query: String,
        limit: Int = 28,
        onPartialResults: suspend (List<TrackEntity>) -> Unit,
    ): Result<List<TrackEntity>> = withContext(Dispatchers.IO) {
        runCatching {
            val trimmed = query.trim()
            val cacheKey = trimmed.lowercase()
            val now = System.currentTimeMillis()
            searchCache[cacheKey]?.let { (ts, cached) ->
                if (now - ts < searchCacheTtlMs) {
                    onPartialResults(cached)
                    return@runCatching cached
                }
            }

            val expandedQuery = expandRussianArtistAliases(trimmed)
            coroutineScope {
                val ytDeferred = async {
                    withTimeoutOrNull(7500L) {
                        FullStreamResolver.searchYouTubeMusic(
                            query = expandedQuery,
                            limit = limit.coerceAtMost(20),
                        )
                    }.orEmpty()
                }
                val scDeferred = async {
                    withTimeoutOrNull(6500L) {
                        FullStreamResolver.searchSoundCloud(
                            query = expandedQuery,
                            limit = 14,
                        )
                    }.orEmpty()
                }
                val audiusDeferred = async {
                    withTimeoutOrNull(5000L) {
                        runCatching {
                            val encoded = URLEncoder.encode(expandedQuery, "UTF-8")
                            val body = executeGet(
                                pathAndQuery = "/tracks/search?query=$encoded&app_name=$appName&limit=$limit",
                                httpClient = searchClient,
                            )
                            json.decodeFromString<AudiusTrackListResponse>(body).data
                                .filter { it.id.isNotBlank() && it.title.isNotBlank() && it.duration >= 45 }
                                .map { dtoToEntity(it, forceLiked = false) }
                        }.getOrDefault(emptyList())
                    }.orEmpty()
                }

                val ytTracks = ytDeferred.await()
                if (ytTracks.isNotEmpty()) {
                    onPartialResults(ytTracks)
                }

                val scTracks = scDeferred.await()
                val audiusTracks = audiusDeferred.await()
                val combined = LinkedHashMap<String, TrackEntity>(
                    ytTracks.size + scTracks.size + audiusTracks.size,
                )
                (ytTracks + scTracks + audiusTracks).forEach { track ->
                    combined.putIfAbsent(track.id, track)
                }
                val finalResults = combined.values.toList()
                if (finalResults.isNotEmpty()) {
                    searchCache[cacheKey] = System.currentTimeMillis() to finalResults
                }
                finalResults
            }
        }
    }

    private fun expandRussianArtistAliases(raw: String): String {
        var normalized = raw
        val aliases = listOf(
            "медкид" to "madk1d",
            "мэдкид" to "madk1d",
            "виллиан" to "villian",
            "мияги" to "miyagi",
            "эндшпиль" to "andy panda",
            "скриптонит" to "scriptonite",
            "эминем" to "eminem",
            "викенд" to "the weeknd",
        )
        aliases.forEach { (ru, en) ->
            if (normalized.contains(ru, ignoreCase = true)) {
                normalized = normalized.replace(ru, en, ignoreCase = true)
            }
        }
        return normalized
    }

    suspend fun ensureTrackPersisted(track: TrackEntity) = withContext(Dispatchers.IO) {
        if (dao.getTrackById(track.id) == null) {
            dao.upsertTrack(track)
        }
    }

    suspend fun syncUserByHandle(rawHandle: String): Result<AudiusSyncSummary> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanHandle = rawHandle.trim().removePrefix("@")
            require(cleanHandle.isNotBlank()) { "Введите @handle аккаунта Audius" }

            val encodedHandle = URLEncoder.encode(cleanHandle, "UTF-8")
            val userBody = executeGet("/users/handle/$encodedHandle?app_name=$appName")
            val user = json.decodeFromString<AudiusUserResponse>(userBody).data
                ?: throw IllegalStateException("Аккаунт @$cleanHandle не найден на Audius")

            var importedTracksCount = 0
            var importedPlaylistsCount = 0

            runCatching {
                val tracksBody = executeGet("/users/${user.id}/tracks?app_name=$appName&limit=30")
                val userTracks = json.decodeFromString<AudiusTrackListResponse>(tracksBody).data
                    .filter { it.id.isNotBlank() && it.title.isNotBlank() }
                    .map { dtoToEntity(it, forceLiked = false) }
                upsertPreservingLocalState(userTracks)
                importedTracksCount += userTracks.size
            }

            runCatching {
                val playlistsBody = executeGet("/users/${user.id}/playlists?app_name=$appName&limit=20")
                val playlists = json.decodeFromString<AudiusPlaylistListResponse>(playlistsBody).data
                for (pl in playlists) {
                    if (pl.id.isBlank() || pl.playlistName.isBlank()) continue
                    val localPlaylistId = "audius_pl_${pl.id}"
                    dao.upsertPlaylist(
                        PlaylistEntity(
                            id = localPlaylistId,
                            name = pl.playlistName,
                            description = pl.description ?: "Синхронизировано с Audius (@${user.handle})",
                            coverArtPath = pl.artwork?.large ?: pl.artwork?.medium ?: pl.artwork?.small,
                        ),
                    )
                    importedPlaylistsCount++

                    val plTracksBody = executeGet("/playlists/${pl.id}/tracks?app_name=$appName")
                    val plTracks = json.decodeFromString<AudiusTrackListResponse>(plTracksBody).data
                        .filter { it.id.isNotBlank() && it.title.isNotBlank() }
                        .map { dtoToEntity(it, forceLiked = false) }
                    upsertPreservingLocalState(plTracks)
                    val baseTime = System.currentTimeMillis()
                    plTracks.forEachIndexed { idx, t ->
                        dao.addTrackToPlaylist(
                            PlaylistTrackCrossRef(
                                playlistId = localPlaylistId,
                                trackId = t.id,
                                addedAt = baseTime + idx,
                                position = idx,
                            ),
                        )
                    }
                    importedTracksCount += plTracks.size
                }
            }

            runCatching {
                val favBody = executeGet("/users/${user.id}/favorites?app_name=$appName&limit=20")
                val favs = json.decodeFromString<AudiusFavoriteListResponse>(favBody).data
                    .filter { it.favoriteType.equals("track", ignoreCase = true) && it.itemId.isNotBlank() }
                    .take(15)

                val likedEntities = mutableListOf<TrackEntity>()
                for (fav in favs) {
                    runCatching {
                        val tBody = executeGet("/tracks/${fav.itemId}?app_name=$appName")
                        val tDto = json.decodeFromString<AudiusSingleTrackResponse>(tBody).data
                        if (tDto != null && tDto.id.isNotBlank()) {
                            likedEntities += dtoToEntity(tDto, forceLiked = true)
                        }
                    }
                }
                if (likedEntities.isNotEmpty()) {
                    upsertPreservingLocalState(likedEntities)
                    importedTracksCount += likedEntities.size
                }
            }

            AudiusSyncSummary(
                userId = user.id,
                handle = user.handle.ifBlank { cleanHandle },
                displayName = user.name.ifBlank { cleanHandle },
                importedTracks = importedTracksCount,
                importedPlaylists = importedPlaylistsCount,
            )
        }
    }

    suspend fun cacheTrackAudioIfNeeded(track: TrackEntity): TrackEntity = withContext(Dispatchers.IO) {
        val rawStreamUrl = track.sourceUri ?: return@withContext track
        if (!rawStreamUrl.startsWith("http")) return@withContext track
        val existingFile = track.cachedFilePath?.let { File(it) }
        if (!FullStreamResolver.isShortPreviewTrack(track) &&
            track.isCachedOffline &&
            existingFile != null &&
            existingFile.exists() &&
            existingFile.length() > 1024L
        ) {
            return@withContext track
        }

        val resolved = FullStreamResolver.resolveDirectStreamForTrack(track, dao)
        val streamUrl = resolved?.directUrl ?: rawStreamUrl
        if (resolved?.isHls == true || streamUrl.contains(".m3u8", ignoreCase = true)) {
            return@withContext track
        }

        val ext = resolved?.extension ?: when {
            streamUrl.contains(".m4a", ignoreCase = true) || streamUrl.contains("mime=audio%2Fmp4", ignoreCase = true) -> "m4a"
            streamUrl.contains(".webm", ignoreCase = true) || streamUrl.contains("mime=audio%2Fwebm", ignoreCase = true) -> "webm"
            else -> "mp3"
        }
        val targetFile = File(audioCacheDir, "${track.id}.$ext")
        val ua = resolved?.headers?.get("User-Agent") ?: FullStreamResolver.userAgentForStreamUrl(streamUrl)
        val ok = runCatching {
            val request = Request.Builder()
                .url(streamUrl)
                .header("User-Agent", ua)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching false
                val ct = response.header("Content-Type")?.lowercase().orEmpty()
                if (ct.contains("text/html") ||
                    ct.contains("application/json") ||
                    ct.contains("mpegurl") ||
                    ct.contains("application/xml")
                ) {
                    return@runCatching false
                }
                val body = response.body ?: return@runCatching false
                body.byteStream().use { input ->
                    FileOutputStream(targetFile).use { output ->
                        input.copyTo(output, bufferSize = 32 * 1024)
                    }
                }
            }
            FullStreamResolver.isLikelyPlayableLocalFile(targetFile)
        }.getOrDefault(false)

        if (ok) {
            dao.updateCacheStatus(
                id = track.id,
                isCached = true,
                cachedPath = targetFile.absolutePath,
                sizeBytes = targetFile.length(),
            )
            track.copy(
                isCachedOffline = true,
                cachedFilePath = targetFile.absolutePath,
                fileSizeBytes = targetFile.length(),
            )
        } else {
            targetFile.delete()
            track
        }
    }

    suspend fun fetchSimilarTracksForQueries(
        queries: List<String>,
        perQueryLimit: Int = 6,
    ): List<TrackEntity> = withContext(Dispatchers.IO) {
        val cleanQueries = queries.map { it.trim() }.filter { it.length >= 2 }.distinct().take(3)
        if (cleanQueries.isEmpty()) return@withContext emptyList()
        coroutineScope {
            val ytDeferred = cleanQueries.map { q ->
                async {
                    withTimeoutOrNull(6500L) {
                        FullStreamResolver.searchYouTubeMusic(query = expandRussianArtistAliases(q), limit = perQueryLimit)
                    }.orEmpty()
                }
            }
            val scDeferred = cleanQueries.take(2).map { q ->
                async {
                    withTimeoutOrNull(6000L) {
                        FullStreamResolver.searchSoundCloud(query = expandRussianArtistAliases(q), limit = (perQueryLimit / 2).coerceAtLeast(2))
                    }.orEmpty()
                }
            }
            val merged = LinkedHashMap<String, TrackEntity>()
            (ytDeferred.flatMap { it.await() } + scDeferred.flatMap { it.await() }).forEach { track ->
                merged.putIfAbsent(track.id, track)
            }
            val list = merged.values.toList()
            if (list.isNotEmpty()) {
                upsertPreservingLocalState(list)
            }
            list
        }
    }

    suspend fun searchArtistProfiles(
        query: String,
        limit: Int = 6,
    ): List<SearchArtistProfile> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.length < 2) return@withContext emptyList()
        val expanded = expandRussianArtistAliases(trimmed)
        val normQuery = normalizeStrictToken(trimmed)
        val normExpanded = normalizeStrictToken(expanded)

        coroutineScope {
            val spDeferred = async {
                withTimeoutOrNull(5500L) {
                    SpotifyCatalogClient.searchArtists(expanded, limit = 5).map { sp ->
                        SearchArtistProfile(
                            id = "sp_art_${sp.id}",
                            name = sp.name,
                            avatarUrl = sp.avatarUrl,
                            sourceLabel = "Spotify",
                            followersCount = sp.followers,
                            spotifyId = sp.id,
                        )
                    }
                }.orEmpty()
            }
            val scDeferred = async {
                withTimeoutOrNull(4500L) {
                    FullStreamResolver.searchSoundCloudUsers(expanded, limit = 5).map { sc ->
                        SearchArtistProfile(
                            id = "sc_art_${abs(sc.url.hashCode())}",
                            name = sc.name,
                            avatarUrl = sc.avatarUrl,
                            sourceLabel = "SoundCloud",
                            followersCount = sc.subscriberCount.toInt().coerceAtLeast(0),
                            soundCloudUrl = sc.url,
                        )
                    }
                }.orEmpty()
            }
            val audiusDeferred = async {
                withTimeoutOrNull(3500L) {
                    runCatching {
                        val encoded = URLEncoder.encode(expanded, "UTF-8")
                        val usersBody = executeGet(
                            "/users/search?query=$encoded&app_name=$appName&limit=3",
                            httpClient = searchClient,
                        )
                        json.decodeFromString<AudiusUserListResponse>(usersBody).data
                            .filter { it.name.isNotBlank() }
                            .map { u ->
                                SearchArtistProfile(
                                    id = "audius_art_${u.id}",
                                    name = u.name.trim(),
                                    avatarUrl = u.profilePicture?.large ?: u.profilePicture?.medium ?: u.profilePicture?.small,
                                    sourceLabel = "Audius",
                                    followersCount = 0,
                                )
                            }
                    }.getOrDefault(emptyList())
                }.orEmpty()
            }

            val allCandidates = spDeferred.await() + scDeferred.await() + audiusDeferred.await()
            val merged = LinkedHashMap<String, SearchArtistProfile>()
            for (cand in allCandidates) {
                val key = normalizeStrictToken(cand.name)
                if (key.isBlank()) continue
                val relevant = key == normQuery ||
                    key == normExpanded ||
                    key.startsWith(normQuery) ||
                    key.startsWith(normExpanded) ||
                    (normQuery.length >= 3 && key.contains(normQuery)) ||
                    (normExpanded.length >= 3 && key.contains(normExpanded))
                if (!relevant) continue

                val existing = merged[key]
                if (existing == null) {
                    merged[key] = cand
                } else {
                    merged[key] = existing.copy(
                        avatarUrl = existing.avatarUrl ?: cand.avatarUrl,
                        followersCount = maxOf(existing.followersCount, cand.followersCount),
                        spotifyId = existing.spotifyId ?: cand.spotifyId,
                        soundCloudUrl = existing.soundCloudUrl ?: cand.soundCloudUrl,
                        sourceLabel = if (existing.sourceLabel.contains(cand.sourceLabel)) {
                            existing.sourceLabel
                        } else {
                            "${existing.sourceLabel} • ${cand.sourceLabel}"
                        },
                    )
                }
            }

            merged.values.sortedByDescending { profile ->
                val normName = normalizeStrictToken(profile.name)
                val exactBonus = when {
                    normName == normQuery || normName == normExpanded -> 10_000_000L
                    normName.startsWith(normQuery) || normName.startsWith(normExpanded) -> 1_000_000L
                    else -> 100_000L
                }
                exactBonus + profile.followersCount.coerceAtMost(999_999)
            }.take(limit)
        }
    }

    suspend fun fetchArtistCatalog(
        artistName: String,
        seedTrack: TrackEntity? = null,
        knownSpotifyArtistId: String? = null,
        knownSoundCloudUrl: String? = null,
        onPartialResult: suspend (ArtistCatalogResult) -> Unit = {},
    ): ArtistCatalogResult = withContext(Dispatchers.IO) {
        val cleanName = normalizeArtistDisplayName(artistName).ifBlank { artistName.trim() }
        val expanded = expandRussianArtistAliases(cleanName)
        val cacheKey = listOfNotNull(
            knownSpotifyArtistId?.takeIf { it.isNotBlank() },
            knownSoundCloudUrl?.takeIf { it.isNotBlank() },
            normalizeStrictToken(expanded.ifBlank { cleanName }),
        ).joinToString("|")
        val now = System.currentTimeMillis()
        if (cacheKey.isNotBlank()) {
            artistCatalogCache[cacheKey]?.let { (ts, cached) ->
                if (now - ts < searchCacheTtlMs && (cached.tracks.isNotEmpty() || cached.albums.isNotEmpty())) {
                    onPartialResult(cached)
                    return@withContext cached
                }
            }
        }

        val scSeedUrl = knownSoundCloudUrl?.takeIf { it.isNotBlank() }
            ?: seedTrack?.sourceUri?.takeIf { it.contains("soundcloud.com/", true) }?.let { trackUrl ->
                Regex("""(https?://(?:m\.)?soundcloud\.com/[^/?#]+)""", RegexOption.IGNORE_CASE)
                    .find(trackUrl)?.groupValues?.get(1)
            }

        coroutineScope {
            val localTracksDeferred = async { dao.getAllTracksOnce() }

            @Volatile
            var spInfo: SpotifyArtistInfo? = null
            @Volatile
            var spTracks: List<TrackEntity> = emptyList()
            @Volatile
            var spAlbums: List<ArtistAlbumItem> = emptyList()

            @Volatile
            var scProfile: SoundCloudArtistProfile? = null
            @Volatile
            var scChannelTracks: List<TrackEntity> = emptyList()
            @Volatile
            var scChannelAlbums: List<ArtistAlbumItem> = emptyList()

            @Volatile
            var scSearchTracks: List<TrackEntity> = emptyList()

            @Volatile
            var audiusAvatar: String? = null
            @Volatile
            var audiusTracks: List<TrackEntity> = emptyList()
            @Volatile
            var audiusAlbums: List<ArtistAlbumItem> = emptyList()

            val buildMutex = Mutex()
            suspend fun buildMergedSnapshot(
                localTracks: List<TrackEntity>,
                ytFallbackTracks: List<TrackEntity> = emptyList(),
            ): ArtistCatalogResult {
                val targetNames = listOfNotNull(
                    cleanName,
                    expanded,
                    spInfo?.name,
                    scProfile?.name,
                ).map { it.trim() }.filter { it.isNotBlank() }.distinct()

                val strictScSearchTracks = scSearchTracks.filter { isTrackByArtist(it, targetNames) }
                val persistedById = localTracks.associateBy { it.id }

                val mergedTracks = LinkedHashMap<String, TrackEntity>()
                val seenTitleKeys = HashSet<String>()

                fun appendTrackUnique(candidate: TrackEntity) {
                    val track = persistedById[candidate.id] ?: candidate
                    val titleKey = normalizeStrictToken(track.title)
                    if (titleKey.isNotBlank() && seenTitleKeys.add(titleKey)) {
                        mergedTracks.putIfAbsent(track.id, track)
                    }
                }

                if (seedTrack != null && isTrackByArtist(seedTrack, targetNames)) {
                    appendTrackUnique(seedTrack)
                }
                spTracks.forEach(::appendTrackUnique)
                scChannelTracks.forEach(::appendTrackUnique)

                val secondaryRemote = (audiusTracks + strictScSearchTracks + ytFallbackTracks)
                    .map { persistedById[it.id] ?: it }
                    .sortedByDescending { track -> artistMatchScore(track, targetNames) }
                secondaryRemote.forEach(::appendTrackUnique)

                localTracks.filter { isTrackByArtist(it, targetNames) }.forEach(::appendTrackUnique)

                val allTracksList = mergedTracks.values.toList()
                val mergedAlbums = (spAlbums + scChannelAlbums + audiusAlbums)
                    .filter { album ->
                        targetNames.any { target ->
                            isArtistNameMatch(album.artist, target) ||
                                album.id.startsWith("sp_alb_") ||
                                album.id.startsWith("sc_alb_")
                        }
                    }
                    .distinctBy { normalizeStrictToken(it.title) }
                    .take(20)

                val displayArtistName = spInfo?.name?.takeIf { it.isNotBlank() }
                    ?: scProfile?.name?.takeIf { it.isNotBlank() }
                    ?: cleanName

                val resolvedAvatar = spInfo?.avatarUrl
                    ?: scProfile?.avatarUrl
                    ?: audiusAvatar
                    ?: allTracksList.firstNotNullOfOrNull { it.coverArtPath?.takeIf { path -> path.startsWith("http") } }
                    ?: seedTrack?.coverArtPath

                return ArtistCatalogResult(
                    artistName = displayArtistName,
                    avatarUrl = resolvedAvatar,
                    tracks = allTracksList,
                    albums = mergedAlbums,
                )
            }

            suspend fun emitProgressSnapshot() {
                val localTracks = localTracksDeferred.await()
                val snapshot = buildMutex.withLock {
                    buildMergedSnapshot(localTracks)
                }
                if (snapshot.tracks.isNotEmpty() || snapshot.albums.isNotEmpty() || !snapshot.avatarUrl.isNullOrBlank()) {
                    onPartialResult(snapshot)
                }
            }

            val spotifyJob = launch {
                val res = withTimeoutOrNull(7000L) {
                    SpotifyCatalogClient.fetchArtistCatalog(
                        artistQuery = expanded,
                        preferredDisplayName = cleanName,
                        knownSpotifyArtistId = knownSpotifyArtistId,
                    )
                }
                if (res != null) {
                    spInfo = res.first
                    spTracks = res.second
                    spAlbums = res.third
                    if (res.second.isNotEmpty() || res.third.isNotEmpty()) {
                        emitProgressSnapshot()
                    }
                }
            }

            val scCatalogJob = launch {
                val res = withTimeoutOrNull(7000L) {
                    FullStreamResolver.fetchSoundCloudArtistCatalog(
                        artistName = expanded,
                        limit = 25,
                        knownUserUrl = scSeedUrl,
                    )
                }
                if (res != null) {
                    scProfile = res.first
                    scChannelTracks = res.second
                    scChannelAlbums = res.third
                    if (res.second.isNotEmpty() || res.third.isNotEmpty()) {
                        emitProgressSnapshot()
                    }
                }
            }

            val scSearchJob = launch {
                val res = withTimeoutOrNull(4500L) {
                    FullStreamResolver.searchSoundCloud(query = expanded, limit = 14)
                }.orEmpty()
                if (res.isNotEmpty()) {
                    scSearchTracks = res
                    emitProgressSnapshot()
                }
            }

            val audiusJob = launch {
                val res = withTimeoutOrNull(4000L) {
                    runCatching {
                        val encoded = URLEncoder.encode(expanded, "UTF-8")
                        val usersBody = executeGet(
                            "/users/search?query=$encoded&app_name=$appName&limit=4",
                            httpClient = searchClient,
                        )
                        val users = json.decodeFromString<AudiusUserListResponse>(usersBody).data
                        val normNeedle = normalizeStrictToken(expanded)
                        val matchedUser = users.firstOrNull {
                            val nn = normalizeStrictToken(it.name)
                            val nh = normalizeStrictToken(it.handle)
                            nn == normNeedle || nh == normNeedle
                        }

                        if (matchedUser != null && matchedUser.id.isNotBlank()) {
                            coroutineScope {
                                val tracksDef = async {
                                    runCatching {
                                        val tBody = executeGet(
                                            "/users/${matchedUser.id}/tracks?app_name=$appName&limit=20",
                                            httpClient = searchClient,
                                        )
                                        json.decodeFromString<AudiusTrackListResponse>(tBody).data
                                            .filter { it.id.isNotBlank() && it.title.isNotBlank() && it.duration >= 30 }
                                            .map { dtoToEntity(it, forceLiked = false) }
                                    }.getOrDefault(emptyList())
                                }
                                val albumsDef = async {
                                    runCatching {
                                        val plBody = executeGet(
                                            "/users/${matchedUser.id}/playlists?app_name=$appName&limit=10",
                                            httpClient = searchClient,
                                        )
                                        json.decodeFromString<AudiusPlaylistListResponse>(plBody).data
                                            .filter { it.id.isNotBlank() && it.playlistName.isNotBlank() }
                                            .map { pl ->
                                                ArtistAlbumItem(
                                                    id = "audius_alb_${pl.id}",
                                                    title = pl.playlistName,
                                                    artist = matchedUser.name.ifBlank { cleanName },
                                                    sourceUrl = "audius_pl:${pl.id}",
                                                    artworkUrl = pl.artwork?.large ?: pl.artwork?.medium ?: pl.artwork?.small,
                                                    trackCount = 0,
                                                )
                                            }
                                    }.getOrDefault(emptyList())
                                }
                                val avatar = matchedUser.profilePicture?.large
                                    ?: matchedUser.profilePicture?.medium
                                    ?: matchedUser.profilePicture?.small
                                Triple(avatar, tracksDef.await(), albumsDef.await())
                            }
                        } else {
                            Triple(null, emptyList(), emptyList())
                        }
                    }.getOrDefault(Triple(null, emptyList(), emptyList()))
                }
                if (res != null) {
                    audiusAvatar = res.first
                    audiusTracks = res.second
                    audiusAlbums = res.third
                    if (res.second.isNotEmpty() || res.third.isNotEmpty()) {
                        emitProgressSnapshot()
                    }
                }
            }

            spotifyJob.join()
            scCatalogJob.join()

            // Cross-link: if SoundCloud web-profile includes a Spotify artist URL and Spotify search missed it, load Spotify now!
            if (spTracks.isEmpty() && !scProfile?.linkedSpotifyArtistId.isNullOrBlank() && knownSpotifyArtistId == null) {
                val linked = withTimeoutOrNull(5500L) {
                    SpotifyCatalogClient.fetchArtistCatalog(
                        artistQuery = expanded,
                        preferredDisplayName = scProfile?.name ?: cleanName,
                        knownSpotifyArtistId = scProfile?.linkedSpotifyArtistId,
                    )
                }
                if (linked != null) {
                    spInfo = linked.first
                    spTracks = linked.second
                    spAlbums = linked.third
                    emitProgressSnapshot()
                }
            }

            scSearchJob.join()
            audiusJob.join()

            val targetNames = listOfNotNull(
                cleanName,
                expanded,
                spInfo?.name,
                scProfile?.name,
            ).map { it.trim() }.filter { it.isNotBlank() }.distinct()

            val strictScSearchTracks = scSearchTracks.filter { isTrackByArtist(it, targetNames) }
            val preCount = spTracks.size + scChannelTracks.size + strictScSearchTracks.size + audiusTracks.size
            val strictYtFallbackTracks = if (preCount < 4) {
                withTimeoutOrNull(4500L) {
                    FullStreamResolver.searchYouTubeMusic(query = expanded, limit = 14)
                        .filter { isTrackByArtist(it, targetNames) }
                }.orEmpty()
            } else {
                emptyList()
            }

            val localTracks = localTracksDeferred.await()
            val finalCatalog = buildMutex.withLock {
                buildMergedSnapshot(localTracks, strictYtFallbackTracks)
            }
            if (cacheKey.isNotBlank() && (finalCatalog.tracks.isNotEmpty() || finalCatalog.albums.isNotEmpty())) {
                artistCatalogCache[cacheKey] = System.currentTimeMillis() to finalCatalog
            }
            finalCatalog
        }
    }

    suspend fun fetchAlbumTracks(album: ArtistAlbumItem): List<TrackEntity> = withContext(Dispatchers.IO) {
        if (album.tracks.isNotEmpty()) return@withContext album.tracks
        val tracks = when {
            album.sourceUrl.contains("open.spotify.com/album/", ignoreCase = true) -> {
                SpotifyCatalogClient.fetchAlbumTracks(
                    albumUrl = album.sourceUrl,
                    artistHint = album.artist,
                    albumTitle = album.title,
                    coverUrl = album.artworkUrl,
                )
            }
            album.sourceUrl.startsWith("audius_pl:") -> {
                val playlistId = album.sourceUrl.removePrefix("audius_pl:")
                runCatching {
                    val body = executeGet("/playlists/$playlistId/tracks?app_name=$appName")
                    json.decodeFromString<AudiusTrackListResponse>(body).data
                        .filter { it.id.isNotBlank() && it.title.isNotBlank() }
                        .map { dtoToEntity(it, forceLiked = false).copy(albumTitle = album.title) }
                }.getOrDefault(emptyList())
            }
            else -> {
                FullStreamResolver.fetchPlaylistTracks(
                    playlistUrl = album.sourceUrl,
                    artistHint = album.artist,
                    fallbackCoverUrl = album.artworkUrl,
                    albumTitleHint = album.title,
                )
            }
        }
        val existingById = dao.getAllTracksOnce().associateBy { it.id }
        tracks.map { incoming ->
            val existing = existingById[incoming.id]
            (existing ?: incoming).let { base ->
                if (base.albumTitle.isNullOrBlank()) base.copy(albumTitle = album.title) else base
            }
        }
    }

    private fun normalizeArtistDisplayName(raw: String): String {
        return raw.trim()
            .split(Regex("\\s*(?:,|&|feat\\.|ft\\.|x|\\s+feat\\s+)\\s*", RegexOption.IGNORE_CASE))
            .firstOrNull()
            ?.trim()
            ?.ifBlank { raw.trim() }
            ?: raw.trim()
    }

    private fun normalizeStrictToken(raw: String): String =
        raw.lowercase().replace(Regex("[^\\p{L}\\p{Nd}]"), "")

    private fun isArtistNameMatch(candidateArtistField: String, targetArtist: String): Boolean {
        val normTarget = normalizeStrictToken(targetArtist)
        if (normTarget.isBlank()) return false
        val cleanedField = candidateArtistField
            .replace(Regex("\\s*-\\s*Topic$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+(?:Official|VEVO)$", RegexOption.IGNORE_CASE), "")
        val rawParts = cleanedField
            .split(Regex("\\s*(?:,|&|/|;|feat\\.|ft\\.|featuring|\\s+x\\s+|\\s+feat\\s+|\\s+with\\s+)\\s*", RegexOption.IGNORE_CASE))
            .map { normalizeStrictToken(it) }
            .filter { it.isNotBlank() }
        val fullNorm = normalizeStrictToken(cleanedField)
        return fullNorm == normTarget || rawParts.any { it == normTarget }
    }

    internal fun isTrackByArtist(track: TrackEntity, targetNames: List<String>): Boolean {
        val combinedLower = "${track.title} ${track.artist}".lowercase()
        val suspiciousNoise = combinedLower.contains("реакция") ||
            combinedLower.contains("reaction") ||
            combinedLower.contains("karaoke") ||
            combinedLower.contains("караоке") ||
            combinedLower.contains("type beat") ||
            combinedLower.contains("кавер") ||
            combinedLower.contains("tribute")
        if (suspiciousNoise && targetNames.none { it.lowercase().contains("cover") }) {
            return false
        }
        return targetNames.any { target -> isArtistNameMatch(track.artist, target) }
    }

    private fun artistMatchScore(track: TrackEntity, targetNames: List<String>): Int {
        val artistNorm = normalizeStrictToken(track.artist)
        var best = 0
        for (target in targetNames) {
            val needle = normalizeStrictToken(target)
            if (needle.isBlank()) continue
            val score = when {
                artistNorm == needle -> 120
                isArtistNameMatch(track.artist, target) -> 90
                else -> 0
            }
            if (score > best) best = score
        }
        if (track.id.startsWith("sp_")) best += 20
        return best
    }

    internal suspend fun upsertPreservingLocalState(incoming: List<TrackEntity>) {
        if (incoming.isEmpty()) return
        val existingById = dao.getAllTracksOnce().associateBy { it.id }
        val merged = incoming.map { track ->
            val existing = existingById[track.id]
            if (existing != null) {
                val mergedCover = if (existing.isUserUpload) {
                    existing.coverArtPath ?: track.coverArtPath
                } else {
                    FullStreamResolver.upgradeArtworkUrl(track.coverArtPath ?: existing.coverArtPath)
                }
                track.copy(
                    title = existing.title,
                    artist = existing.artist,
                    coverArtPath = mergedCover,
                    albumTitle = existing.albumTitle ?: track.albumTitle,
                    isLiked = existing.isLiked || track.isLiked,
                    isCachedOffline = existing.isCachedOffline,
                    cachedFilePath = existing.cachedFilePath,
                    fileSizeBytes = existing.fileSizeBytes,
                    playCount = maxOf(existing.playCount, track.playCount),
                )
            } else {
                track
            }
        }
        dao.upsertTracks(merged)
    }

    private fun dtoToEntity(dto: AudiusTrackDto, forceLiked: Boolean): TrackEntity {
        val (accent, secondary) = paletteForGenre(dto.genre, dto.id)
        val artworkUrl = dto.artwork?.large ?: dto.artwork?.medium ?: dto.artwork?.small
        val streamUrl = "https://api.audius.co/v1/tracks/${dto.id}/stream?app_name=$appName"
        val mappedGenre = mapGenreLabel(dto.genre)

        return TrackEntity(
            id = "audius_${dto.id}",
            title = dto.title,
            artist = dto.user?.name?.takeIf { it.isNotBlank() } ?: dto.user?.handle ?: "Audius Artist",
            genre = mappedGenre,
            durationMs = (dto.duration.coerceAtLeast(1) * 1000L),
            waveformJson = TrackEntity.encodeWaveform(TrackEntity.defaultWaveform()),
            cachedFilePath = null,
            sourceUri = streamUrl,
            coverArtPath = artworkUrl,
            accentColorHex = accent,
            secondaryColorHex = secondary,
            isUserUpload = false,
            isAutoImported = false,
            isCachedOffline = false,
            fileSizeBytes = 0L,
            playCount = dto.playCount,
            likeCount = dto.favoriteCount,
            isLiked = forceLiked,
            description = dto.description.orEmpty(),
            createdAt = System.currentTimeMillis(),
        )
    }

    private fun mapGenreLabel(raw: String?): String {
        val g = raw?.trim().orEmpty()
        return when {
            g.contains("Hip-Hop", true) || g.contains("Rap", true) -> "Хип-хоп"
            g.equals("Electronic", ignoreCase = true) || g.contains("House", true) ||
                g.contains("Techno", true) || g.contains("Dance", true) || g.contains("EDM", true) -> "Электроника"
            g.equals("Pop", ignoreCase = true) -> "Поп"
            g.contains("Rock", true) || g.contains("Metal", true) -> "Рок"
            g.contains("Lo-Fi", true) -> "Lo-Fi"
            g.contains("Ambient", true) -> "Амбиент"
            g.contains("Synth", true) -> "Синтвейв"
            g.contains("Indie", true) || g.contains("Alternative", true) -> "Инди"
            g.isNotBlank() -> g
            else -> "Поп"
        }
    }

    private fun paletteForGenre(genre: String?, seed: String): Pair<Long, Long> {
        val palettes = listOf(
            0xFFFF5500L to 0xFF9C2B00L,
            0xFF5CE1E6L to 0xFFA78BFAL,
            0xFFFF9248L to 0xFFFB7185L,
            0xFF34D399L to 0xFF5CE1E6L,
            0xFFA78BFAL to 0xFFFB7185L,
        )
        val idx = ((genre?.hashCode() ?: 0) + seed.hashCode()).let { abs(it) } % palettes.size
        return palettes[idx]
    }

    private fun executeGet(
        pathAndQuery: String,
        httpClient: OkHttpClient = client,
    ): String {
        var lastError: Throwable? = null
        val orderedBases = listOf(preferredBaseUrl) + baseUrls.filterNot { it == preferredBaseUrl }
        for (base in orderedBases) {
            try {
                val request = Request.Builder()
                    .url("$base$pathAndQuery")
                    .header("Accept", "application/json")
                    .header("User-Agent", "SoundLapa/1.0")
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val text = response.body?.string()
                        if (!text.isNullOrBlank()) {
                            preferredBaseUrl = base
                            return text
                        }
                    } else {
                        lastError = IllegalStateException("HTTP ${response.code}")
                    }
                }
            } catch (t: Throwable) {
                lastError = t
            }
        }
        throw lastError ?: IllegalStateException("Audius API недоступен")
    }
}
