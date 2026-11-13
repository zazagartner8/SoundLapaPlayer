package com.example

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.example.audio.AudioCacheManager
import com.example.data.local.SoundLapaDatabase
import com.example.data.local.SoundLapaPreferences
import com.example.data.remote.AudiusRepository
import com.example.data.remote.FullStreamResolver
import com.example.data.remote.PlaylistImporter
import com.example.player.PlaybackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SoundLapaApplication : Application(), ImageLoaderFactory {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val database by lazy { SoundLapaDatabase.getInstance(this) }
    val dao by lazy { database.soundLapaDao() }
    val preferences by lazy { SoundLapaPreferences(this) }
    val audioCacheManager by lazy { AudioCacheManager(this, dao) }
    val audiusRepository by lazy { AudiusRepository(this, dao) }
    val playlistImporter by lazy { PlaylistImporter(this, dao, audiusRepository) }
    val playbackController by lazy { PlaybackController(this, dao, preferences, appScope) }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.28)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("coil_image_cache"))
                    .maxSizeBytes(128L * 1024L * 1024L)
                    .build()
            }
            .allowHardware(true)
            .crossfade(false)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        FullStreamResolver.configureHttpCache(cacheDir)
        FullStreamResolver.warmUpAsync()
        appScope.launch(Dispatchers.IO) {
            audioCacheManager.seedStarterLibraryIfEmpty()
        }
    }
}
