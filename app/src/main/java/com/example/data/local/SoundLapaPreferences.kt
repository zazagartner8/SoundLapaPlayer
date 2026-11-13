package com.example.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Immutable
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.soundLapaDataStore: DataStore<Preferences> by preferencesDataStore(name = "soundlapa_prefs")

@Immutable
@Serializable
data class ListeningHistoryEntry(
    val trackId: String,
    val title: String,
    val artist: String,
    val genre: String,
    val playCount: Int = 1,
    val lastPlayedAtMs: Long = 0L,
)

@Immutable
data class SoundLapaSettings(
    val autoUploadEnabled: Boolean = true,
    val autoUploadTreeUri: String? = null,
    val autoUploadFolderName: String? = null,
    val autoCacheOnPlay: Boolean = true,
    val darkLiquidGlass: Boolean = true,
    val lastSyncTimestamp: Long = 0L,
    val lastPlayedTrackId: String? = null,
    val lastPlayedPositionMs: Long = 0L,
    val audiusHandle: String? = null,
    val audiusUserId: String? = null,
    val audiusDisplayName: String? = null,
    val audiusLastSyncTimestamp: Long = 0L,
    val appLanguage: String = "ru",
    val accentColorArgb: Long = 0xFF10B981L,
    val smoothTransitionEnabled: Boolean = true,
    val smoothTransitionDurationSec: Int = 3,
    val dataSaverEnabled: Boolean = false,
    val searchHistory: List<String> = emptyList(),
    val listeningHistory: List<ListeningHistoryEntry> = emptyList(),
)

class SoundLapaPreferences(private val context: Context) {

    private object Keys {
        val AUTO_UPLOAD_ENABLED = booleanPreferencesKey("auto_upload_enabled")
        val AUTO_UPLOAD_TREE_URI = stringPreferencesKey("auto_upload_tree_uri")
        val AUTO_UPLOAD_FOLDER_NAME = stringPreferencesKey("auto_upload_folder_name")
        val AUTO_CACHE_ON_PLAY = booleanPreferencesKey("auto_cache_on_play")
        val DARK_LIQUID_GLASS = booleanPreferencesKey("dark_liquid_glass")
        val LAST_SYNC_TIMESTAMP = longPreferencesKey("last_sync_timestamp")
        val LAST_PLAYED_TRACK_ID = stringPreferencesKey("last_played_track_id")
        val LAST_PLAYED_POSITION_MS = longPreferencesKey("last_played_position_ms")
        val AUDIUS_HANDLE = stringPreferencesKey("audius_handle")
        val AUDIUS_USER_ID = stringPreferencesKey("audius_user_id")
        val AUDIUS_DISPLAY_NAME = stringPreferencesKey("audius_display_name")
        val AUDIUS_LAST_SYNC = longPreferencesKey("audius_last_sync")
        val APP_LANGUAGE = stringPreferencesKey("app_language")
        val ACCENT_COLOR_ARGB = longPreferencesKey("accent_color_argb")
        val SMOOTH_TRANSITION_ENABLED = booleanPreferencesKey("smooth_transition_enabled")
        val SMOOTH_TRANSITION_DURATION_SEC = intPreferencesKey("smooth_transition_duration_sec")
        val DATA_SAVER_ENABLED = booleanPreferencesKey("data_saver_enabled")
        val SEARCH_HISTORY = stringPreferencesKey("search_history_json")
        val LISTENING_HISTORY = stringPreferencesKey("listening_history_json")
    }

    // Synchronous SharedPreferences mirror ensures instant restoration on frame 0
    // and guarantees no setting is ever lost if the user closes the app immediately.
    private val syncPrefs: SharedPreferences =
        context.getSharedPreferences("soundlapa_prefs_sync", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(readFromSyncPrefs())
    val settings: Flow<SoundLapaSettings> = _settings.asStateFlow()

    fun currentSettings(): SoundLapaSettings = _settings.value

    private fun readFromSyncPrefs(): SoundLapaSettings = SoundLapaSettings(
        autoUploadEnabled = syncPrefs.getBoolean("auto_upload_enabled", true),
        autoUploadTreeUri = syncPrefs.getString("auto_upload_tree_uri", null),
        autoUploadFolderName = syncPrefs.getString("auto_upload_folder_name", null),
        autoCacheOnPlay = syncPrefs.getBoolean("auto_cache_on_play", true),
        darkLiquidGlass = syncPrefs.getBoolean("dark_liquid_glass", true),
        lastSyncTimestamp = syncPrefs.getLong("last_sync_timestamp", 0L),
        lastPlayedTrackId = syncPrefs.getString("last_played_track_id", null),
        lastPlayedPositionMs = syncPrefs.getLong("last_played_position_ms", 0L),
        audiusHandle = syncPrefs.getString("audius_handle", null),
        audiusUserId = syncPrefs.getString("audius_user_id", null),
        audiusDisplayName = syncPrefs.getString("audius_display_name", null),
        audiusLastSyncTimestamp = syncPrefs.getLong("audius_last_sync", 0L),
        appLanguage = syncPrefs.getString("app_language", "ru") ?: "ru",
        accentColorArgb = syncPrefs.getLong("accent_color_argb", 0xFF10B981L),
        smoothTransitionEnabled = syncPrefs.getBoolean("smooth_transition_enabled", true),
        smoothTransitionDurationSec = syncPrefs.getInt("smooth_transition_duration_sec", 3).coerceIn(1, 10),
        dataSaverEnabled = syncPrefs.getBoolean("data_saver_enabled", false),
        searchHistory = decodeHistory(syncPrefs.getString("search_history_json", null)),
        listeningHistory = decodeListeningHistory(syncPrefs.getString("listening_history_json", null)),
    )

    private fun decodeHistory(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { Json.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())
    }

    private fun decodeListeningHistory(raw: String?): List<ListeningHistoryEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { Json.decodeFromString<List<ListeningHistoryEntry>>(raw) }.getOrDefault(emptyList())
    }

    private inline fun updateSync(
        transformState: (SoundLapaSettings) -> SoundLapaSettings,
        editSync: SharedPreferences.Editor.() -> Unit,
    ) {
        val next = transformState(_settings.value)
        _settings.value = next
        syncPrefs.edit().apply(editSync).apply()
    }

    suspend fun setAutoUploadEnabled(enabled: Boolean) {
        updateSync({ it.copy(autoUploadEnabled = enabled) }) {
            putBoolean("auto_upload_enabled", enabled)
        }
        context.soundLapaDataStore.edit { it[Keys.AUTO_UPLOAD_ENABLED] = enabled }
    }

    suspend fun setAutoUploadFolder(treeUri: String?, folderName: String?) {
        updateSync({ it.copy(autoUploadTreeUri = treeUri, autoUploadFolderName = folderName) }) {
            if (treeUri == null) {
                remove("auto_upload_tree_uri")
                remove("auto_upload_folder_name")
            } else {
                putString("auto_upload_tree_uri", treeUri)
                if (folderName != null) putString("auto_upload_folder_name", folderName)
            }
        }
        context.soundLapaDataStore.edit { prefs ->
            if (treeUri == null) {
                prefs.remove(Keys.AUTO_UPLOAD_TREE_URI)
                prefs.remove(Keys.AUTO_UPLOAD_FOLDER_NAME)
            } else {
                prefs[Keys.AUTO_UPLOAD_TREE_URI] = treeUri
                if (folderName != null) {
                    prefs[Keys.AUTO_UPLOAD_FOLDER_NAME] = folderName
                }
            }
        }
    }

    suspend fun setAutoCacheOnPlay(enabled: Boolean) {
        updateSync({ it.copy(autoCacheOnPlay = enabled) }) {
            putBoolean("auto_cache_on_play", enabled)
        }
        context.soundLapaDataStore.edit { it[Keys.AUTO_CACHE_ON_PLAY] = enabled }
    }

    suspend fun setDarkLiquidGlass(dark: Boolean) {
        updateSync({ it.copy(darkLiquidGlass = dark) }) {
            putBoolean("dark_liquid_glass", dark)
        }
        context.soundLapaDataStore.edit { it[Keys.DARK_LIQUID_GLASS] = dark }
    }

    suspend fun setAppLanguage(languageCode: String) {
        updateSync({ it.copy(appLanguage = languageCode) }) {
            putString("app_language", languageCode)
        }
        context.soundLapaDataStore.edit { it[Keys.APP_LANGUAGE] = languageCode }
    }

    suspend fun setAccentColorArgb(argb: Long) {
        updateSync({ it.copy(accentColorArgb = argb) }) {
            putLong("accent_color_argb", argb)
        }
        context.soundLapaDataStore.edit { it[Keys.ACCENT_COLOR_ARGB] = argb }
    }

    suspend fun setSmoothTransitionEnabled(enabled: Boolean) {
        updateSync({ it.copy(smoothTransitionEnabled = enabled) }) {
            putBoolean("smooth_transition_enabled", enabled)
        }
        context.soundLapaDataStore.edit { it[Keys.SMOOTH_TRANSITION_ENABLED] = enabled }
    }

    suspend fun setSmoothTransitionDurationSec(seconds: Int) {
        val clamped = seconds.coerceIn(1, 10)
        updateSync({ it.copy(smoothTransitionDurationSec = clamped) }) {
            putInt("smooth_transition_duration_sec", clamped)
        }
        context.soundLapaDataStore.edit { it[Keys.SMOOTH_TRANSITION_DURATION_SEC] = clamped }
    }

    suspend fun setLastSyncTimestamp(timestamp: Long) {
        updateSync({ it.copy(lastSyncTimestamp = timestamp) }) {
            putLong("last_sync_timestamp", timestamp)
        }
        context.soundLapaDataStore.edit { it[Keys.LAST_SYNC_TIMESTAMP] = timestamp }
    }

    suspend fun savePlaybackState(trackId: String?, positionMs: Long) {
        val current = _settings.value
        val nextTrackId = trackId ?: current.lastPlayedTrackId
        if (nextTrackId != current.lastPlayedTrackId) {
            _settings.value = current.copy(
                lastPlayedTrackId = nextTrackId,
                lastPlayedPositionMs = positionMs,
            )
        }
        syncPrefs.edit().apply {
            if (trackId != null) putString("last_played_track_id", trackId)
            putLong("last_played_position_ms", positionMs)
        }.apply()
        context.soundLapaDataStore.edit { prefs ->
            if (trackId != null) {
                prefs[Keys.LAST_PLAYED_TRACK_ID] = trackId
            }
            prefs[Keys.LAST_PLAYED_POSITION_MS] = positionMs
        }
    }

    suspend fun setAudiusAccount(handle: String?, userId: String?, displayName: String?) {
        val now = System.currentTimeMillis()
        updateSync({
            if (handle == null || userId == null) {
                it.copy(
                    audiusHandle = null,
                    audiusUserId = null,
                    audiusDisplayName = null,
                    audiusLastSyncTimestamp = 0L,
                )
            } else {
                it.copy(
                    audiusHandle = handle,
                    audiusUserId = userId,
                    audiusDisplayName = displayName ?: it.audiusDisplayName,
                    audiusLastSyncTimestamp = now,
                )
            }
        }) {
            if (handle == null || userId == null) {
                remove("audius_handle")
                remove("audius_user_id")
                remove("audius_display_name")
                remove("audius_last_sync")
            } else {
                putString("audius_handle", handle)
                putString("audius_user_id", userId)
                if (displayName != null) putString("audius_display_name", displayName)
                putLong("audius_last_sync", now)
            }
        }
        context.soundLapaDataStore.edit { prefs ->
            if (handle == null || userId == null) {
                prefs.remove(Keys.AUDIUS_HANDLE)
                prefs.remove(Keys.AUDIUS_USER_ID)
                prefs.remove(Keys.AUDIUS_DISPLAY_NAME)
                prefs.remove(Keys.AUDIUS_LAST_SYNC)
            } else {
                prefs[Keys.AUDIUS_HANDLE] = handle
                prefs[Keys.AUDIUS_USER_ID] = userId
                if (displayName != null) {
                    prefs[Keys.AUDIUS_DISPLAY_NAME] = displayName
                }
                prefs[Keys.AUDIUS_LAST_SYNC] = now
            }
        }
    }

    suspend fun addSearchHistoryQuery(query: String) {
        val trimmed = query.trim()
        if (trimmed.length < 2) return
        val updated = (listOf(trimmed) + _settings.value.searchHistory.filterNot {
            it.equals(trimmed, ignoreCase = true)
        }).take(15)
        val encoded = Json.encodeToString(updated)
        updateSync({ it.copy(searchHistory = updated) }) {
            putString("search_history_json", encoded)
        }
        context.soundLapaDataStore.edit { it[Keys.SEARCH_HISTORY] = encoded }
    }

    suspend fun removeSearchHistoryQuery(query: String) {
        val updated = _settings.value.searchHistory.filterNot { it == query }
        val encoded = Json.encodeToString(updated)
        updateSync({ it.copy(searchHistory = updated) }) {
            putString("search_history_json", encoded)
        }
        context.soundLapaDataStore.edit { it[Keys.SEARCH_HISTORY] = encoded }
    }

    suspend fun clearSearchHistory() {
        val encoded = Json.encodeToString(emptyList<String>())
        updateSync({ it.copy(searchHistory = emptyList()) }) {
            putString("search_history_json", encoded)
        }
        context.soundLapaDataStore.edit { it[Keys.SEARCH_HISTORY] = encoded }
    }

    suspend fun setDataSaverEnabled(enabled: Boolean) {
        updateSync({ it.copy(dataSaverEnabled = enabled) }) {
            putBoolean("data_saver_enabled", enabled)
        }
        context.soundLapaDataStore.edit { it[Keys.DATA_SAVER_ENABLED] = enabled }
    }

    suspend fun recordTrackPlayed(track: TrackEntity) {
        val now = System.currentTimeMillis()
        val current = _settings.value.listeningHistory
        val existing = current.find { it.trackId == track.id }
        val nextEntry = ListeningHistoryEntry(
            trackId = track.id,
            title = track.title,
            artist = track.artist,
            genre = track.genre,
            playCount = (existing?.playCount ?: 0) + 1,
            lastPlayedAtMs = now,
        )
        val updated = (listOf(nextEntry) + current.filterNot { it.trackId == track.id }).take(50)
        val encoded = Json.encodeToString(updated)
        updateSync({ it.copy(listeningHistory = updated) }) {
            putString("listening_history_json", encoded)
        }
        context.soundLapaDataStore.edit { it[Keys.LISTENING_HISTORY] = encoded }
    }
}
