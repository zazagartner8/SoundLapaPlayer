package com.example.data.local

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class SoundLapStatsBackup(
    val version: Int = 1,
    val updatedAtMs: Long = 0L,
    val listeningEvents: List<ListeningPlayEvent> = emptyList(),
    val listeningHistory: List<ListeningHistoryEntry> = emptyList(),
)

data class StatsBackupSaveResult(
    val success: Boolean,
    val locationLabel: String = "Downloads/SoundLap/soundlap_stats_backup.json",
    val savedAtMs: Long = 0L,
)

/**
 * Persists listening statistics to user-visible Downloads/SoundLap/soundlap_stats_backup.json
 * (zero storage permissions via MediaStore.Downloads on API 29+) so stats survive uninstall and
 * can be restored immediately via SAF OpenDocument after reinstalling.
 */
object ListeningStatsBackupManager {

    private const val PREF_NAME = "soundlapa_stats_backup_meta"
    private const val KEY_MEDIASTORE_URI = "mediastore_backup_uri"
    private const val KEY_LAST_SAVED_MS = "last_backup_saved_ms"
    private const val BACKUP_FILENAME = "soundlap_stats_backup.json"
    private const val BACKUP_SUBDIR = "SoundLap"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    fun encodeBackup(
        events: List<ListeningPlayEvent>,
        history: List<ListeningHistoryEntry>,
        updatedAtMs: Long = System.currentTimeMillis(),
    ): String {
        val prunedEvents = ListeningStatsAggregator.pruneOldEvents(events, nowMs = updatedAtMs)
        val payload = SoundLapStatsBackup(
            version = 1,
            updatedAtMs = updatedAtMs,
            listeningEvents = prunedEvents,
            listeningHistory = history.take(120),
        )
        return json.encodeToString(payload)
    }

    fun decodeBackup(rawJson: String): SoundLapStatsBackup? {
        val trimmed = rawJson.trim().removePrefix("\uFEFF")
        if (trimmed.isEmpty() || (!trimmed.startsWith("{") && !trimmed.startsWith("["))) return null
        return runCatching {
            if (trimmed.startsWith("[")) {
                // Support raw ListeningPlayEvent array too
                val events = json.decodeFromString<List<ListeningPlayEvent>>(trimmed)
                SoundLapStatsBackup(
                    version = 1,
                    updatedAtMs = System.currentTimeMillis(),
                    listeningEvents = ListeningStatsAggregator.pruneOldEvents(events),
                )
            } else {
                val parsed = json.decodeFromString<SoundLapStatsBackup>(trimmed)
                parsed.copy(
                    listeningEvents = ListeningStatsAggregator.pruneOldEvents(parsed.listeningEvents),
                )
            }
        }.getOrNull()
    }

    fun mergeHistory(
        local: List<ListeningHistoryEntry>,
        imported: List<ListeningHistoryEntry>,
    ): List<ListeningHistoryEntry> {
        val merged = LinkedHashMap<String, ListeningHistoryEntry>(local.size + imported.size)
        for (entry in local + imported) {
            if (entry.trackId.isBlank()) continue
            val existing = merged[entry.trackId]
            if (existing == null) {
                merged[entry.trackId] = entry
            } else {
                merged[entry.trackId] = existing.copy(
                    playCount = maxOf(existing.playCount, entry.playCount),
                    lastPlayedAtMs = maxOf(existing.lastPlayedAtMs, entry.lastPlayedAtMs),
                )
            }
        }
        return merged.values
            .sortedByDescending { it.lastPlayedAtMs }
            .take(80)
    }

    fun getLastBackupTimestampMs(context: Context): Long =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_SAVED_MS, 0L)

    suspend fun saveAutoBackup(
        context: Context,
        events: List<ListeningPlayEvent>,
        history: List<ListeningHistoryEntry>,
    ): StatsBackupSaveResult = withContext(Dispatchers.IO) {
        if (events.isEmpty() && history.isEmpty()) {
            return@withContext StatsBackupSaveResult(success = false)
        }
        val now = System.currentTimeMillis()
        val encoded = encodeBackup(events, history, now)
        val bytes = encoded.toByteArray(Charsets.UTF_8)
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

        val saved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            writeToMediaStoreDownloads(context, prefs.getString(KEY_MEDIASTORE_URI, null), bytes)?.also { uriStr ->
                prefs.edit()
                    .putString(KEY_MEDIASTORE_URI, uriStr)
                    .putLong(KEY_LAST_SAVED_MS, now)
                    .apply()
            } != null
        } else {
            runCatching {
                val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
                val targetDir = File(baseDir, BACKUP_SUBDIR).apply { mkdirs() }
                File(targetDir, BACKUP_FILENAME).writeBytes(bytes)
                prefs.edit().putLong(KEY_LAST_SAVED_MS, now).apply()
                true
            }.getOrDefault(false)
        }

        StatsBackupSaveResult(
            success = saved,
            locationLabel = "Downloads/$BACKUP_SUBDIR/$BACKUP_FILENAME",
            savedAtMs = if (saved) now else 0L,
        )
    }

    private fun writeToMediaStoreDownloads(
        context: Context,
        savedUriString: String?,
        bytes: ByteArray,
    ): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val resolver = context.contentResolver

        // Try overwriting our previously owned MediaStore file cleanly so we don't spam numbered copies
        if (!savedUriString.isNullOrBlank()) {
            val savedUri = runCatching { Uri.parse(savedUriString) }.getOrNull()
            if (savedUri != null) {
                val overwritten = runCatching {
                    resolver.openOutputStream(savedUri, "wt")?.use { os ->
                        os.write(bytes)
                        os.flush()
                    } != null
                }.getOrDefault(false)
                if (overwritten) {
                    return savedUriString
                }
            }
        }

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, BACKUP_FILENAME)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$BACKUP_SUBDIR")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val itemUri = runCatching {
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        }.getOrNull() ?: return null

        val wrote = runCatching {
            resolver.openOutputStream(itemUri, "wt")?.use { os ->
                os.write(bytes)
                os.flush()
            } ?: return@runCatching false
            val publishValues = ContentValues().apply {
                put(MediaStore.Downloads.IS_PENDING, 0)
            }
            resolver.update(itemUri, publishValues, null, null)
            true
        }.getOrDefault(false)

        if (!wrote) {
            runCatching { resolver.delete(itemUri, null, null) }
            return null
        }
        return itemUri.toString()
    }

    suspend fun readBackupFromUri(
        context: Context,
        uri: Uri,
    ): SoundLapStatsBackup? = withContext(Dispatchers.IO) {
        runCatching {
            val raw = context.contentResolver.openInputStream(uri)?.use { stream ->
                stream.bufferedReader(Charsets.UTF_8).readText()
            } ?: return@runCatching null
            decodeBackup(raw)
        }.getOrNull()
    }
}
