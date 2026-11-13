package com.example.audio

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import coil.imageLoader
import com.example.data.local.SoundLapaDao
import com.example.data.local.TrackEntity
import com.example.data.remote.FullStreamResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

data class ImportPreviewMetadata(
    val uri: Uri,
    val title: String,
    val artist: String,
    val albumTitle: String?,
    val durationMs: Long,
    val fileSizeBytes: Long,
    val extension: String,
)

data class AutoSyncResult(
    val scannedFiles: Int,
    val newlyImported: Int,
    val folderName: String?,
)

class AudioCacheManager(
    private val context: Context,
    private val dao: SoundLapaDao,
) {
    private val audioCacheDir: File by lazy {
        File(context.filesDir, "audio_cache").apply { mkdirs() }
    }

    private val coverCacheDir: File by lazy {
        File(context.filesDir, "cover_cache").apply { mkdirs() }
    }

    suspend fun seedStarterLibraryIfEmpty() = withContext(Dispatchers.IO) {
        if (dao.getTrackCount() > 0) return@withContext

        val now = System.currentTimeMillis()
        val seededTracks = mutableListOf<TrackEntity>()

        AudioSynthesizer.starterPresets.forEachIndexed { index, preset ->
            val targetFile = File(audioCacheDir, "${preset.id}.wav")
            val peaks = AudioSynthesizer.generateWavTrack(preset, targetFile)
            val createdAt = now - (index * 3_600_000L)

            seededTracks += TrackEntity(
                id = preset.id,
                title = preset.title,
                artist = preset.artist,
                genre = preset.genre,
                durationMs = preset.durationSeconds * 1000L,
                waveformJson = TrackEntity.encodeWaveform(peaks),
                cachedFilePath = targetFile.absolutePath,
                sourceUri = null,
                coverArtPath = null,
                accentColorHex = preset.accentColorHex,
                secondaryColorHex = preset.secondaryColorHex,
                isUserUpload = false,
                isAutoImported = false,
                isCachedOffline = true,
                fileSizeBytes = targetFile.length(),
                playCount = preset.playCount,
                likeCount = preset.likeCount,
                isLiked = false,
                description = preset.description,
                createdAt = createdAt,
                albumTitle = "SoundLapa Originals",
            )
        }

        dao.upsertTracks(seededTracks)
    }

    suspend fun saveCustomCover(trackId: String, imageUri: Uri): String? = withContext(Dispatchers.IO) {
        val coverFile = File(coverCacheDir, "${trackId}_${System.currentTimeMillis()}.jpg")
        val copied = runCatching {
            context.contentResolver.openInputStream(imageUri)?.use { input ->
                FileOutputStream(coverFile).use { output ->
                    input.copyTo(output, bufferSize = 16 * 1024)
                }
            }
            coverFile.exists() && coverFile.length() > 0L
        }.getOrDefault(false)
        if (copied) coverFile.absolutePath else {
            coverFile.delete()
            null
        }
    }

    suspend fun inspectUriMetadata(uri: Uri): ImportPreviewMetadata = withContext(Dispatchers.IO) {
        val displayName = resolveDisplayName(uri) ?: "Новый трек"
        val baseTitle = displayName.substringBeforeLast('.')
        val ext = displayName.substringAfterLast('.', "mp3").lowercase()
        var title = baseTitle
        var artist = "Мой канал"
        var albumTitle: String? = null
        var durationMs = 0L

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.takeIf { it.isNotBlank() }
                ?.let { title = it }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.takeIf { it.isNotBlank() }
                ?.let { artist = it }
            albumTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                ?.takeIf { it.isNotBlank() }
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            // Fall back to filename metadata if retriever cannot parse container tags
        } finally {
            runCatching { retriever.release() }
        }

        val sizeBytes = resolveFileSize(uri)
        ImportPreviewMetadata(
            uri = uri,
            title = title,
            artist = artist,
            albumTitle = albumTitle,
            durationMs = durationMs.coerceAtLeast(1000L),
            fileSizeBytes = sizeBytes,
            extension = ext,
        )
    }

    suspend fun importTrackFromUri(
        uri: Uri,
        titleOverride: String? = null,
        artistOverride: String? = null,
        genre: String = "Мои треки",
        description: String = "",
        accentColorHex: Long = 0xFFFF5500,
        secondaryColorHex: Long = 0xFF9C2B00,
        isAutoImported: Boolean = false,
    ): TrackEntity? = withContext(Dispatchers.IO) {
        val uriString = uri.toString()
        if (isAutoImported && dao.countBySourceUri(uriString) > 0) {
            return@withContext null
        }

        val preview = inspectUriMetadata(uri)
        val trackId = "user_${UUID.randomUUID()}"
        val safeExt = preview.extension.takeIf { it.length in 2..5 && it.all { ch -> ch.isLetterOrDigit() } } ?: "audio"
        val cachedFile = File(audioCacheDir, "$trackId.$safeExt")

        val copied = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(cachedFile).use { output ->
                    input.copyTo(output, bufferSize = 16 * 1024)
                }
            }
            cachedFile.exists() && cachedFile.length() > 0L
        }.getOrDefault(false)

        if (!copied) {
            cachedFile.delete()
            return@withContext null
        }

        // Extract embedded artwork and accurate duration from the local cached copy
        var coverPath: String? = null
        var durationMs = preview.durationMs
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(cachedFile.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
                ?.let { durationMs = it }

            val embeddedPic = retriever.embeddedPicture
            if (embeddedPic != null && embeddedPic.isNotEmpty()) {
                val coverFile = File(coverCacheDir, "$trackId.jpg")
                FileOutputStream(coverFile).use { it.write(embeddedPic) }
                coverPath = coverFile.absolutePath
            }
        } catch (_: Exception) {
            // Keep metadata fallback
        } finally {
            runCatching { retriever.release() }
        }

        val peaks = extractWaveformPeaks(cachedFile, seed = trackId.hashCode())
        val track = TrackEntity(
            id = trackId,
            title = titleOverride?.takeIf { it.isNotBlank() } ?: preview.title,
            artist = artistOverride?.takeIf { it.isNotBlank() } ?: preview.artist,
            genre = genre,
            durationMs = durationMs,
            waveformJson = TrackEntity.encodeWaveform(peaks),
            cachedFilePath = cachedFile.absolutePath,
            sourceUri = uriString,
            coverArtPath = coverPath,
            accentColorHex = accentColorHex,
            secondaryColorHex = secondaryColorHex,
            isUserUpload = true,
            isAutoImported = isAutoImported,
            isCachedOffline = true,
            fileSizeBytes = cachedFile.length(),
            playCount = 0,
            likeCount = 1,
            isLiked = false,
            description = description.ifBlank {
                if (isAutoImported) "Авто-загружено в офлайн-кэш SoundLapa" else "Загружено пользователем"
            },
            createdAt = System.currentTimeMillis(),
            albumTitle = preview.albumTitle,
        )

        dao.upsertTrack(track)
        track
    }

    suspend fun scanAutoUploadFolder(treeUri: Uri): AutoSyncResult = withContext(Dispatchers.IO) {
        val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
            ?: return@withContext AutoSyncResult(0, 0, null)

        val supportedExtensions = setOf("mp3", "wav", "m4a", "aac", "ogg", "flac", "opus")
        val palettePairs = listOf(
            0xFFFF5500L to 0xFF9C2B00L,
            0xFF5CE1E6L to 0xFFA78BFAL,
            0xFFFF9248L to 0xFFFB7185L,
            0xFF34D399L to 0xFF5CE1E6L,
        )

        var scanned = 0
        var imported = 0
        val children = rootDoc.listFiles()

        for (doc in children) {
            if (!doc.isFile) continue
            val name = doc.name ?: continue
            val mime = doc.type.orEmpty()
            val ext = name.substringAfterLast('.', "").lowercase()
            if (!mime.startsWith("audio/") && ext !in supportedExtensions) continue

            scanned++
            val palette = palettePairs[imported % palettePairs.size]
            val result = importTrackFromUri(
                uri = doc.uri,
                genre = "Авто-загрузка",
                accentColorHex = palette.first,
                secondaryColorHex = palette.second,
                isAutoImported = true,
            )
            if (result != null) {
                imported++
            }
        }

        AutoSyncResult(
            scannedFiles = scanned,
            newlyImported = imported,
            folderName = rootDoc.name,
        )
    }

    suspend fun toggleOfflineCache(track: TrackEntity): TrackEntity = withContext(Dispatchers.IO) {
        if (track.isCachedOffline) {
            // Only remove cached copy if it can be re-created or re-read from sourceUri
            val canRebuild = !track.isUserUpload || track.sourceUri != null
            if (!canRebuild) return@withContext track

            track.cachedFilePath?.let { path ->
                runCatching { File(path).delete() }
            }
            dao.updateCacheStatus(
                id = track.id,
                isCached = false,
                cachedPath = null,
                sizeBytes = 0L,
            )
            return@withContext track.copy(
                isCachedOffline = false,
                cachedFilePath = null,
                fileSizeBytes = 0L,
            )
        } else {
            val preset = AudioSynthesizer.starterPresets.find { it.id == track.id }
            val restoredFile: File? = when {
                preset != null -> {
                    val file = File(audioCacheDir, "${track.id}.wav")
                    AudioSynthesizer.generateWavTrack(preset, file)
                    file
                }
                track.sourceUri != null -> {
                    val uri = Uri.parse(track.sourceUri)
                    val file = File(audioCacheDir, "${track.id}.audio")
                    val ok = runCatching {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            FileOutputStream(file).use { output -> input.copyTo(output) }
                        }
                        file.exists() && file.length() > 0L
                    }.getOrDefault(false)
                    if (ok) file else null
                }
                else -> null
            }

            if (restoredFile != null) {
                dao.updateCacheStatus(
                    id = track.id,
                    isCached = true,
                    cachedPath = restoredFile.absolutePath,
                    sizeBytes = restoredFile.length(),
                )
                return@withContext track.copy(
                    isCachedOffline = true,
                    cachedFilePath = restoredFile.absolutePath,
                    fileSizeBytes = restoredFile.length(),
                )
            }
            return@withContext track
        }
    }

    suspend fun deleteTrack(track: TrackEntity) = withContext(Dispatchers.IO) {
        track.cachedFilePath?.let { runCatching { File(it).delete() } }
        track.coverArtPath?.let { runCatching { File(it).delete() } }
        dao.deleteTrackById(track.id)
    }

    suspend fun calculateCacheSizeBytes(): Long = withContext(Dispatchers.IO) {
        val allTracks = dao.getAllTracksOnce()
        val protectedAudioPaths = allTracks
            .filter { it.isUserUpload || it.sourceUri == null }
            .mapNotNull { it.cachedFilePath }
            .toHashSet()
        val protectedCoverPaths = allTracks
            .filter { it.isUserUpload }
            .mapNotNull { it.coverArtPath }
            .toHashSet()

        val tempCacheBytes = directorySizeBytes(context.cacheDir)
        val audioCacheBytes = audioCacheDir.listFiles()
            ?.filter { it.isFile && it.absolutePath !in protectedAudioPaths }
            ?.sumOf { it.length() }
            ?: 0L
        val coverCacheBytes = coverCacheDir.listFiles()
            ?.filter { it.isFile && it.absolutePath !in protectedCoverPaths }
            ?.sumOf { it.length() }
            ?: 0L

        tempCacheBytes + audioCacheBytes + coverCacheBytes
    }

    suspend fun clearAppCache(): Long = withContext(Dispatchers.IO) {
        val sizeBefore = calculateCacheSizeBytes()
        val allTracks = dao.getAllTracksOnce()
        val protectedAudioPaths = allTracks
            .filter { it.isUserUpload || it.sourceUri == null }
            .mapNotNull { it.cachedFilePath }
            .toHashSet()
        val protectedCoverPaths = allTracks
            .filter { it.isUserUpload }
            .mapNotNull { it.coverArtPath }
            .toHashSet()

        // 1. Clear streamed audio cache & orphaned audio files (keep user uploads and built-in wavs)
        audioCacheDir.listFiles()?.forEach { file ->
            if (file.isFile && file.absolutePath !in protectedAudioPaths) {
                runCatching { file.delete() }
            }
        }
        // 2. Clear orphaned cover files
        coverCacheDir.listFiles()?.forEach { file ->
            if (file.isFile && file.absolutePath !in protectedCoverPaths) {
                runCatching { file.delete() }
            }
        }
        // 3. Reset Room cache flags for streamed tracks
        runCatching { dao.clearStreamedTracksCacheStatus() }

        // 4. Clear Coil image caches & app cacheDir
        runCatching {
            context.imageLoader.diskCache?.clear()
            context.imageLoader.memoryCache?.clear()
        }
        context.cacheDir.listFiles()?.forEach { child ->
            runCatching { child.deleteRecursively() }
        }
        FullStreamResolver.clearStreamCache()

        sizeBefore
    }

    private fun directorySizeBytes(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        if (dir.isFile) return dir.length()
        return dir.listFiles()?.sumOf { child ->
            if (child.isDirectory) directorySizeBytes(child) else child.length()
        } ?: 0L
    }

    private fun extractWaveformPeaks(file: File, seed: Int): List<Float> {
        val barCount = 64
        val fileLen = file.length()
        if (fileLen <= 64L) return TrackEntity.defaultWaveform()

        return runCatching {
            val isWav = file.extension.equals("wav", ignoreCase = true)
            val offset = if (isWav && fileLen > 44L) 44L else (fileLen / 16L).coerceAtMost(2048L)
            val usableBytes = (fileLen - offset).coerceAtLeast(barCount.toLong())
            val step = (usableBytes / barCount).coerceAtLeast(1L)
            val buffer = ByteArray(1024)
            val raw = FloatArray(barCount)

            FileInputStream(file).use { fis ->
                for (b in 0 until barCount) {
                    val pos = offset + b * step
                    fis.channel.position(pos.coerceAtMost(fileLen - 1))
                    val read = fis.read(buffer, 0, buffer.size.coerceAtMost(step.toInt().coerceAtLeast(64)))
                    if (read > 1) {
                        var sumSq = 0.0
                        if (isWav) {
                            var j = 0
                            while (j + 1 < read) {
                                val lo = buffer[j].toInt() and 0xFF
                                val hi = buffer[j + 1].toInt()
                                val sample = ((hi shl 8) or lo).toShort().toDouble() / Short.MAX_VALUE
                                sumSq += sample * sample
                                j += 2
                            }
                            raw[b] = sqrt(sumSq / (read / 2).coerceAtLeast(1)).toFloat()
                        } else {
                            // Compressed audio byte variance envelope blended with harmonic contour
                            var prev = buffer[0].toInt()
                            for (j in 1 until read) {
                                val cur = buffer[j].toInt()
                                val diff = abs(cur - prev).toDouble() / 255.0
                                sumSq += diff * diff
                                prev = cur
                            }
                            val variance = sqrt(sumSq / (read - 1)).toFloat()
                            val contour = 0.25f + 0.35f * abs(sin((b + (seed and 7)) * 0.28)).toFloat()
                            raw[b] = variance * 0.7f + contour * 0.3f
                        }
                    } else {
                        raw[b] = 0.3f
                    }
                }
            }
            val maxVal = (raw.maxOrNull() ?: 1f).coerceAtLeast(0.01f)
            raw.map { (it / maxVal).coerceIn(0.14f, 1f) }
        }.getOrElse { TrackEntity.defaultWaveform() }
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

    private fun resolveFileSize(uri: Uri): Long {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (idx >= 0) cursor.getLong(idx) else 0L
                    } else 0L
                } ?: 0L
        }.getOrDefault(0L)
    }
}
