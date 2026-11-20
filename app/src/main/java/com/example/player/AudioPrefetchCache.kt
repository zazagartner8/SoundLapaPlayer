package com.example.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.example.data.local.SoundLapaDao
import com.example.data.local.TrackEntity
import com.example.data.remote.FullStreamResolver
import com.example.data.remote.isNetworkCurrentlyAvailable
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class AudioPrefetchCacheStats(
    val usedBytes: Long = 0L,
    val maxBytes: Long = AUDIO_PREFETCH_CACHE_MAX_BYTES,
    val cachedTracksCount: Int = 0,
)

private const val AUDIO_PREFETCH_CACHE_MAX_BYTES: Long = 180L * 1024L * 1024L // 180 MB LRU disk cache
private const val PREFETCH_BYTES_PER_TRACK: Long = 2_621_440L // 2.5 MB per upcoming track (~1.5-2 min instant start)
private const val PREFETCH_ALREADY_WARM_THRESHOLD: Long = 524_288L // 512 KB already buffered on disk

@OptIn(UnstableApi::class)
object AudioPrefetchCache {

    private const val TAG = "AudioPrefetchCache"
    private const val CACHE_DIR_NAME = "soundlap_audio_cache"

    private val lock = Any()
    @Volatile
    private var simpleCache: SimpleCache? = null
    private var databaseProvider: StandaloneDatabaseProvider? = null

    private val prefetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeBatchJob: Job? = null
    private val inFlightKeys = ConcurrentHashMap.newKeySet<String>()
    private val batchToken = AtomicInteger(0)

    fun cacheKeyForTrack(trackId: String?): String? {
        val clean = trackId?.trim().orEmpty()
        if (clean.isEmpty()) return null
        return "sl_trk_$clean"
    }

    fun getOrCreateCache(context: Context): SimpleCache {
        simpleCache?.let { return it }
        synchronized(lock) {
            simpleCache?.let { return it }
            val appCtx = context.applicationContext
            val dir = File(appCtx.cacheDir, CACHE_DIR_NAME)
            if (!dir.exists()) {
                dir.mkdirs()
            }
            val dbProvider = databaseProvider ?: StandaloneDatabaseProvider(appCtx).also {
                databaseProvider = it
            }
            val evictor = LeastRecentlyUsedCacheEvictor(AUDIO_PREFETCH_CACHE_MAX_BYTES)
            return SimpleCache(dir, evictor, dbProvider).also {
                simpleCache = it
            }
        }
    }

    fun wrapDataSourceFactory(
        context: Context,
        upstreamFactory: DataSource.Factory,
    ): DataSource.Factory {
        return runCatching {
            val cache = getOrCreateCache(context)
            CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(upstreamFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        }.getOrElse {
            upstreamFactory
        }
    }

    /**
     * Background-caches audio bytes for the next 2–3 upcoming tracks in the queue so switching
     * tracks starts instantaneously from disk with zero network stall.
     */
    fun prefetchUpcomingTracks(
        context: Context,
        tracks: List<TrackEntity>,
        dao: SoundLapaDao? = null,
    ) {
        if (tracks.isEmpty()) return
        if (FullStreamResolver.isDataSaverEnabled || FullStreamResolver.isBatterySaverEnabled) return
        val appCtx = context.applicationContext
        if (!isNetworkCurrentlyAvailable(appCtx)) return

        val targets = tracks
            .filter { track ->
                val uri = track.sourceUri.orEmpty()
                track.cachedFilePath.isNullOrBlank() &&
                    !uri.startsWith("content://") &&
                    !uri.startsWith("file://") &&
                    !uri.startsWith("android.resource://")
            }
            .distinctBy { it.id }
            .take(3)
        if (targets.isEmpty()) return

        // Always keep stream URL resolution warm immediately
        FullStreamResolver.prefetchTracks(targets, dao)

        val token = batchToken.incrementAndGet()
        activeBatchJob?.cancel()
        activeBatchJob = prefetchScope.launch {
            val cache = runCatching { getOrCreateCache(appCtx) }.getOrNull() ?: return@launch
            for (track in targets) {
                if (!isActive || batchToken.get() != token) break
                if (FullStreamResolver.isDataSaverEnabled || FullStreamResolver.isBatterySaverEnabled) break
                if (!isNetworkCurrentlyAvailable(appCtx)) break

                val cacheKey = cacheKeyForTrack(track.id) ?: continue
                val cachedAlready = runCatching {
                    cache.getCachedBytes(cacheKey, 0L, PREFETCH_BYTES_PER_TRACK)
                }.getOrDefault(0L)
                if (cachedAlready >= PREFETCH_ALREADY_WARM_THRESHOLD) {
                    continue
                }
                if (!inFlightKeys.add(cacheKey)) continue

                try {
                    val rawUri = if (FullStreamResolver.isShortPreviewTrack(track)) {
                        FullStreamResolver.buildLegacyUpgradeUri(track)
                    } else {
                        track.sourceUri.orEmpty()
                    }
                    if (rawUri.isBlank()) continue

                    val resolved = FullStreamResolver.peekCachedOrDirectStreamSync(rawUri)
                        ?: runCatching {
                            FullStreamResolver.resolveAudioStreamForUri(
                                rawUri = rawUri,
                                fallbackTitle = track.title,
                                fallbackArtist = track.artist,
                                trackId = track.id,
                                originalSourceUri = track.sourceUri,
                                dao = dao,
                                expectedDurationMs = track.durationMs,
                            )
                        }.getOrNull()
                        ?: continue

                    if (!isActive || batchToken.get() != token) break
                    if (resolved.isHls || resolved.directUrl.contains(".m3u8", ignoreCase = true)) {
                        continue
                    }
                    if (!resolved.directUrl.startsWith("http://", ignoreCase = true) &&
                        !resolved.directUrl.startsWith("https://", ignoreCase = true)
                    ) {
                        continue
                    }

                    val httpFactory = DefaultHttpDataSource.Factory()
                        .setAllowCrossProtocolRedirects(true)
                        .setConnectTimeoutMs(10_000)
                        .setReadTimeoutMs(15_000)
                        .setUserAgent(FullStreamResolver.DEFAULT_BROWSER_USER_AGENT)
                    if (resolved.headers.isNotEmpty()) {
                        httpFactory.setDefaultRequestProperties(resolved.headers)
                    }
                    val upstreamFactory = DefaultDataSource.Factory(appCtx, httpFactory)
                    val cacheDataSource = CacheDataSource.Factory()
                        .setCache(cache)
                        .setUpstreamDataSourceFactory(upstreamFactory)
                        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                        .createDataSource()

                    val dataSpec = DataSpec.Builder()
                        .setUri(Uri.parse(resolved.directUrl))
                        .setKey(cacheKey)
                        .setPosition(0L)
                        .setLength(PREFETCH_BYTES_PER_TRACK)
                        .build()

                    val writer = CacheWriter(cacheDataSource, dataSpec, ByteArray(16 * 1024), null)
                    writer.cache()
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    Log.d(TAG, "Background prefetch skipped for ${track.id}: ${t.message}")
                } finally {
                    inFlightKeys.remove(cacheKey)
                }
            }
        }
    }

    fun evictTrack(context: Context, trackId: String?) {
        val key = cacheKeyForTrack(trackId) ?: return
        runCatching {
            val cache = simpleCache ?: getOrCreateCache(context)
            cache.removeResource(key)
        }
    }

    fun inspectStats(context: Context): AudioPrefetchCacheStats {
        return runCatching {
            val appCtx = context.applicationContext
            val cache = simpleCache
            if (cache != null) {
                val keys = cache.keys
                AudioPrefetchCacheStats(
                    usedBytes = cache.cacheSpace.coerceAtLeast(0L),
                    maxBytes = AUDIO_PREFETCH_CACHE_MAX_BYTES,
                    cachedTracksCount = keys.size,
                )
            } else {
                val dir = File(appCtx.cacheDir, CACHE_DIR_NAME)
                val size = computeDirSize(dir)
                val count = dir.walkTopDown().count { it.isFile && it.name.endsWith(".exo") }
                AudioPrefetchCacheStats(
                    usedBytes = size,
                    maxBytes = AUDIO_PREFETCH_CACHE_MAX_BYTES,
                    cachedTracksCount = count,
                )
            }
        }.getOrDefault(AudioPrefetchCacheStats())
    }

    fun clearCache(context: Context): Long {
        batchToken.incrementAndGet()
        activeBatchJob?.cancel()
        return runCatching {
            val cache = getOrCreateCache(context)
            val before = cache.cacheSpace.coerceAtLeast(0L)
            cache.keys.toList().forEach { key ->
                runCatching { cache.removeResource(key) }
            }
            before
        }.getOrDefault(0L)
    }

    private fun computeDirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        var total = 0L
        dir.walkTopDown().forEach { f ->
            if (f.isFile) total += f.length()
        }
        return total
    }
}
