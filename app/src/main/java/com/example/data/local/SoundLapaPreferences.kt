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

const val DEFAULT_PURPLE_ACCENT_ARGB: Long = 0xFF8B5CF6L

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
@Serializable
data class LocalBugReport(
    val reportId: String,
    val reporterName: String,
    val reporterAvatarUrl: String? = null,
    val message: String,
    val appVersion: String,
    val createdAtMs: Long,
    val isSyncedToCloud: Boolean = false,
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
    val accentColorArgb: Long = DEFAULT_PURPLE_ACCENT_ARGB,
    val smoothTransitionEnabled: Boolean = true,
    val smoothTransitionDurationSec: Int = 3,
    val dataSaverEnabled: Boolean = false,
    val searchHistory: List<String> = emptyList(),
    val listeningHistory: List<ListeningHistoryEntry> = emptyList(),
    val lastCloudUserId: String? = null,
    val hapticsEnabled: Boolean = true,
    val hapticIntensity: Int = 70,
    val syncedCloudUids: Set<String> = emptySet(),
    val lastTrendingRefreshMs: Long = 0L,
    val isGuestMode: Boolean = false,
    val customDisplayName: String? = null,
    val customBio: String? = null,
    val customAvatarData: String? = null,
    val avatarModerationBlocked: Boolean = false,
    val settingsUpdatedAtMs: Long = 0L,
    val pendingBugReports: List<LocalBugReport> = emptyList(),
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
        val LAST_CLOUD_USER_ID = stringPreferencesKey("last_cloud_user_id")
        val HAPTICS_ENABLED = booleanPreferencesKey("haptics_enabled")
        val HAPTIC_INTENSITY = intPreferencesKey("haptic_intensity")
        val SYNCED_CLOUD_UIDS = stringPreferencesKey("synced_cloud_uids")
        val LAST_TRENDING_REFRESH_MS = longPreferencesKey("last_trending_refresh_ms")
        val IS_GUEST_MODE = booleanPreferencesKey("is_guest_mode")
        val CUSTOM_DISPLAY_NAME = stringPreferencesKey("custom_display_name")
        val CUSTOM_BIO = stringPreferencesKey("custom_bio")
        val CUSTOM_AVATAR_DATA = stringPreferencesKey("custom_avatar_data")
        val AVATAR_MODERATION_BLOCKED = booleanPreferencesKey("avatar_moderation_blocked")
        val SETTINGS_UPDATED_AT_MS = longPreferencesKey("settings_updated_at_ms")
        val PENDING_BUG_REPORTS_JSON = stringPreferencesKey("pending_bug_reports_json")
    }

    // Synchronous SharedPreferences mirror ensures instant restoration on frame 0
    // and guarantees no setting is ever lost if the user closes the app immediately.
    private val syncPrefs: SharedPreferences =
        context.getSharedPreferences("soundlapa_prefs_sync", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(readFromSyncPrefs())
    val settings: Flow<SoundLapaSettings> = _settings.asStateFlow()

    fun currentSettings(): SoundLapaSettings = _settings.value

    private fun readFromSyncPrefs(): SoundLapaSettings {
        val migratedPurple = syncPrefs.getBoolean("default_purple_theme_v1", false)
        var accentArgb = syncPrefs.getLong("accent_color_argb", DEFAULT_PURPLE_ACCENT_ARGB)
        if (!migratedPurple) {
            if (!syncPrefs.contains("accent_color_argb") || accentArgb == 0xFFFF5500L) {
                accentArgb = DEFAULT_PURPLE_ACCENT_ARGB
                syncPrefs.edit()
                    .putLong("accent_color_argb", DEFAULT_PURPLE_ACCENT_ARGB)
                    .putBoolean("default_purple_theme_v1", true)
                    .apply()
            } else {
                syncPrefs.edit().putBoolean("default_purple_theme_v1", true).apply()
            }
        }
        return SoundLapaSettings(
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
            accentColorArgb = accentArgb,
            smoothTransitionEnabled = syncPrefs.getBoolean("smooth_transition_enabled", true),
            smoothTransitionDurationSec = syncPrefs.getInt("smooth_transition_duration_sec", 3).coerceIn(1, 10),
            dataSaverEnabled = syncPrefs.getBoolean("data_saver_enabled", false),
            searchHistory = decodeHistory(syncPrefs.getString("search_history_json", null)),
            listeningHistory = decodeListeningHistory(syncPrefs.getString("listening_history_json", null)),
            lastCloudUserId = syncPrefs.getString("last_cloud_user_id", null),
            hapticsEnabled = syncPrefs.getBoolean("haptics_enabled", true),
            hapticIntensity = syncPrefs.getInt("haptic_intensity", 70).coerceIn(10, 100),
            syncedCloudUids = decodeStringSet(syncPrefs.getString("synced_cloud_uids", null)),
            lastTrendingRefreshMs = syncPrefs.getLong("last_trending_refresh_ms", 0L),
            isGuestMode = syncPrefs.getBoolean("is_guest_mode", false),
            customDisplayName = syncPrefs.getString("custom_display_name", null),
            customBio = syncPrefs.getString("custom_bio", null),
            customAvatarData = syncPrefs.getString("custom_avatar_data", null),
            avatarModerationBlocked = syncPrefs.getBoolean("avatar_moderation_blocked", false),
            settingsUpdatedAtMs = syncPrefs.getLong("settings_updated_at_ms", 0L),
            pendingBugReports = decodeBugReports(syncPrefs.getString("pending_bug_reports_json", null)),
        )
    }

    private fun decodeStringSet(raw: String?): Set<String> {
        if (raw.isNullOrBlank()) return emptySet()
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    private fun decodeHistory(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { Json.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())
    }

    private fun decodeListeningHistory(raw: String?): List<ListeningHistoryEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { Json.decodeFromString<List<ListeningHistoryEntry>>(raw) }.getOrDefault(emptyList())
    }

    private fun decodeBugReports(raw: String?): List<LocalBugReport> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { Json.decodeFromString<List<LocalBugReport>>(raw) }.getOrDefault(emptyList())
    }

    private inline fun updateSync(
        transformState: (SoundLapaSettings) -> SoundLapaSettings,
        editSync: SharedPreferences.Editor.() -> Unit,
    ) {
        val next = transformState(_settings.value)
        _settings.value = next
        syncPrefs.edit().apply(editSync).apply()
    }

    suspend fun setGuestMode(enabled: Boolean) {
        updateSync({ it.copy(isGuestMode = enabled) }) {
            putBoolean("is_guest_mode", enabled)
        }
        context.soundLapaDataStore.edit { it[Keys.IS_GUEST_MODE] = enabled }
    }

    suspend fun setCustomProfile(
        displayName: String?,
        bio: String?,
        avatarData: String?,
    ) {
        val cleanName = displayName?.trim()?.takeIf { it.isNotEmpty() }?.take(100)
        val cleanBio = bio?.trim()?.takeIf { it.isNotEmpty() }?.take(300)
        val cleanAvatar = if (_settings.value.avatarModerationBlocked) {
            null
        } else {
            avatarData?.trim()?.takeIf { it.isNotEmpty() }?.take(98_000)
        }
        updateSync({
            it.copy(
                customDisplayName = cleanName,
                customBio = cleanBio,
                customAvatarData = cleanAvatar,
            )
        }) {
            if (cleanName == null) remove("custom_display_name") else putString("custom_display_name", cleanName)
            if (cleanBio == null) remove("custom_bio") else putString("custom_bio", cleanBio)
            if (cleanAvatar == null) remove("custom_avatar_data") else putString("custom_avatar_data", cleanAvatar)
        }
        context.soundLapaDataStore.edit { prefs ->
            if (cleanName == null) prefs.remove(Keys.CUSTOM_DISPLAY_NAME) else prefs[Keys.CUSTOM_DISPLAY_NAME] = cleanName
            if (cleanBio == null) prefs.remove(Keys.CUSTOM_BIO) else prefs[Keys.CUSTOM_BIO] = cleanBio
            if (cleanAvatar == null) prefs.remove(Keys.CUSTOM_AVATAR_DATA) else prefs[Keys.CUSTOM_AVATAR_DATA] = cleanAvatar
        }
    }

    suspend fun setAvatarModerationBlocked(blocked: Boolean) {
        updateSync({
            it.copy(
                avatarModerationBlocked = blocked,
                customAvatarData = if (blocked) null else it.customAvatarData,
            )
        }) {
            putBoolean("avatar_moderation_blocked", blocked)
            if (blocked) remove("custom_avatar_data")
        }
        context.soundLapaDataStore.edit { prefs ->
            prefs[Keys.AVATAR_MODERATION_BLOCKED] = blocked
            if (blocked) prefs.remove(Keys.CUSTOM_AVATAR_DATA)
        }
    }

    suspend fun addLocalBugReport(report: LocalBugReport) {
        val updated = (listOf(report) + _settings.value.pendingBugReports.filterNot { it.reportId == report.reportId })
            .take(50)
        val encoded = Json.encodeToString(updated)
        updateSync({ it.copy(pendingBugReports = updated) }) {
            putString("pending_bug_reports_json", encoded)
        }
        context.soundLapaDataStore.edit { it[Keys.PENDING_BUG_REPORTS_JSON] = encoded }
    }

    suspend fun markBugReportSynced(reportId: String) {
        val updated = _settings.value.pendingBugReports.map {
            if (it.reportId == reportId) it.copy(isSyncedToCloud = true) else it
        }
        val encoded = Json.encodeToString(updated)
        updateSync({ it.copy(pendingBugReports = updated) }) {
            putString("pending_bug_reports_json", encoded)
        }
        context.soundLapaDataStore.edit { it[Keys.PENDING_BUG_REPORTS_JSON] = encoded }
    }

    suspend fun removeLocalBugReport(reportId: String) {
        val updated = _settings.value.pendingBugReports.filterNot { it.reportId == reportId }
        val encoded = Json.encodeToString(updated)
        updateSync({ it.copy(pendingBugReports = updated) }) {
            putString("pending_bug_reports_json", encoded)
        }
        context.soundLapaDataStore.edit { it[Keys.PENDING_BUG_REPORTS_JSON] = encoded }
    }

    suspend fun applySyncedAccountSettings(
        appLanguage: String,
        darkLiquidGlass: Boolean,
        accentColorArgb: Long,
        smoothTransitionEnabled: Boolean,
        smoothTransitionDurationSec: Int,
        hapticsEnabled: Boolean,
        hapticIntensity: Int,
        autoCacheOnPlay: Boolean,
        dataSaverEnabled: Boolean,
        autoUploadEnabled: Boolean,
        updatedAtMs: Long,
    ) {
        val clampedDur = smoothTransitionDurationSec.coerceIn(1, 10)
        val clampedHaptic = hapticIntensity.coerceIn(10, 100)
        updateSync({
            it.copy(
                appLanguage = appLanguage,
                darkLiquidGlass = darkLiquidGlass,
                accentColorArgb = accentColorArgb,
                smoothTransitionEnabled = smoothTransitionEnabled,
                smoothTransitionDurationSec = clampedDur,
                hapticsEnabled = hapticsEnabled,
                hapticIntensity = clampedHaptic,
                autoCacheOnPlay = autoCacheOnPlay,
                dataSaverEnabled = dataSaverEnabled,
                autoUploadEnabled = autoUploadEnabled,
                settingsUpdatedAtMs = updatedAtMs,
            )
        }) {
            putString("app_language", appLanguage)
            putBoolean("dark_liquid_glass", darkLiquidGlass)
            putLong("accent_color_argb", accentColorArgb)
            putBoolean("smooth_transition_enabled", smoothTransitionEnabled)
            putInt("smooth_transition_duration_sec", clampedDur)
            putBoolean("haptics_enabled", hapticsEnabled)
            putInt("haptic_intensity", clampedHaptic)
            putBoolean("auto_cache_on_play", autoCacheOnPlay)
            putBoolean("data_saver_enabled", dataSaverEnabled)
            putBoolean("auto_upload_enabled", autoUploadEnabled)
            putLong("settings_updated_at_ms", updatedAtMs)
        }
        context.soundLapaDataStore.edit { prefs ->
            prefs[Keys.APP_LANGUAGE] = appLanguage
            prefs[Keys.DARK_LIQUID_GLASS] = darkLiquidGlass
            prefs[Keys.ACCENT_COLOR_ARGB] = accentColorArgb
            prefs[Keys.SMOOTH_TRANSITION_ENABLED] = smoothTransitionEnabled
            prefs[Keys.SMOOTH_TRANSITION_DURATION_SEC] = clampedDur
            prefs[Keys.HAPTICS_ENABLED] = hapticsEnabled
            prefs[Keys.HAPTIC_INTENSITY] = clampedHaptic
            prefs[Keys.AUTO_CACHE_ON_PLAY] = autoCacheOnPlay
            prefs[Keys.DATA_SAVER_ENABLED] = dataSaverEnabled
            prefs[Keys.AUTO_UPLOAD_ENABLED] = autoUploadEnabled
            prefs[Keys.SETTINGS_UPDATED_AT_MS] = updatedAtMs
        }
    }

    suspend fun setAutoUploadEnabled(enabled: Boolean) {
        val now = System.currentTimeMillis()
        updateSync({ it.copy(autoUploadEnabled = enabled, settingsUpdatedAtMs = now) }) {
            putBoolean("auto_upload_enabled", enabled)
            putLong("settings_updated_at_ms", now)
        }
        context.soundLapaDataStore.edit {
            it[Keys.AUTO_UPLOAD_ENABLED] = enabled
            it[Keys.SETTINGS_UPDATED_AT_MS] = now
        }
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
        val now = System.currentTimeMillis()
        updateSync({ it.copy(autoCacheOnPlay = enabled, settingsUpdatedAtMs = now) }) {
            putBoolean("auto_cache_on_play", enabled)
            putLong("settings_updated_at_ms", now)
        }
        context.soundLapaDataStore.edit {
            it[Keys.AUTO_CACHE_ON_PLAY] = enabled
            it[Keys.SETTINGS_UPDATED_AT_MS] = now
        }
    }

    suspend fun setDarkLiquidGlass(dark: Boolean) {
        val now = System.currentTimeMillis()
        updateSync({ it.copy(darkLiquidGlass = dark, settingsUpdatedAtMs = now) }) {
            putBoolean("dark_liquid_glass", dark)
            putLong("settings_updated_at_ms", now)
        }
        context.soundLapaDataStore.edit {
            it[Keys.DARK_LIQUID_GLASS] = dark
            it[Keys.SETTINGS_UPDATED_AT_MS] = now
        }
    }

    suspend fun setAppLanguage(languageCode: String) {
        val now = System.currentTimeMillis()
        updateSync({ it.copy(appLanguage = languageCode, settingsUpdatedAtMs = now) }) {
            putString("app_language", languageCode)
            putLong("settings_updated_at_ms", now)
        }
        context.soundLapaDataStore.edit {
            it[Keys.APP_LANGUAGE] = languageCode
            it[Keys.SETTINGS_UPDATED_AT_MS] = now
        }
    }

    suspend fun setAccentColorArgb(argb: Long) {
        val now = System.currentTimeMillis()
        updateSync({ it.copy(accentColorArgb = argb, settingsUpdatedAtMs = now) }) {
            putLong("accent_color_argb", argb)
            putLong("settings_updated_at_ms", now)
        }
        context.soundLapaDataStore.edit {
            it[Keys.ACCENT_COLOR_ARGB] = argb
            it[Keys.SETTINGS_UPDATED_AT_MS] = now
        }
    }

    suspend fun setSmoothTransitionEnabled(enabled: Boolean) {
        val now = System.currentTimeMillis()
        updateSync({ it.copy(smoothTransitionEnabled = enabled, settingsUpdatedAtMs = now) }) {
            putBoolean("smooth_transition_enabled", enabled)
            putLong("settings_updated_at_ms", now)
        }
        context.soundLapaDataStore.edit {
            it[Keys.SMOOTH_TRANSITION_ENABLED] = enabled
            it[Keys.SETTINGS_UPDATED_AT_MS] = now
        }
    }

    suspend fun setSmoothTransitionDurationSec(seconds: Int) {
        val clamped = seconds.coerceIn(1, 10)
        val now = System.currentTimeMillis()
        updateSync({ it.copy(smoothTransitionDurationSec = clamped, settingsUpdatedAtMs = now) }) {
            putInt("smooth_transition_duration_sec", clamped)
            putLong("settings_updated_at_ms", now)
        }
        context.soundLapaDataStore.edit {
            it[Keys.SMOOTH_TRANSITION_DURATION_SEC] = clamped
            it[Keys.SETTINGS_UPDATED_AT_MS] = now
        }
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
        val now = System.currentTimeMillis()
        updateSync({ it.copy(dataSaverEnabled = enabled, settingsUpdatedAtMs = now) }) {
            putBoolean("data_saver_enabled", enabled)
            putLong("settings_updated_at_ms", now)
        }
        context.soundLapaDataStore.edit {
            it[Keys.DATA_SAVER_ENABLED] = enabled
            it[Keys.SETTINGS_UPDATED_AT_MS] = now
        }
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

    suspend fun setLastCloudUserId(uid: String?) {
        updateSync({ it.copy(lastCloudUserId = uid) }) {
            if (uid == null) remove("last_cloud_user_id") else putString("last_cloud_user_id", uid)
        }
        context.soundLapaDataStore.edit { prefs ->
            if (uid == null) prefs.remove(Keys.LAST_CLOUD_USER_ID)
            else prefs[Keys.LAST_CLOUD_USER_ID] = uid
        }
    }

    suspend fun setHapticsEnabled(enabled: Boolean) {
        val now = System.currentTimeMillis()
        updateSync({ it.copy(hapticsEnabled = enabled, settingsUpdatedAtMs = now) }) {
            putBoolean("haptics_enabled", enabled)
            putLong("settings_updated_at_ms", now)
        }
        context.soundLapaDataStore.edit {
            it[Keys.HAPTICS_ENABLED] = enabled
            it[Keys.SETTINGS_UPDATED_AT_MS] = now
        }
    }

    suspend fun setHapticIntensity(intensityPercent: Int) {
        val clamped = intensityPercent.coerceIn(10, 100)
        val now = System.currentTimeMillis()
        updateSync({ it.copy(hapticIntensity = clamped, settingsUpdatedAtMs = now) }) {
            putInt("haptic_intensity", clamped)
            putLong("settings_updated_at_ms", now)
        }
        context.soundLapaDataStore.edit {
            it[Keys.HAPTIC_INTENSITY] = clamped
            it[Keys.SETTINGS_UPDATED_AT_MS] = now
        }
    }

    fun hasCompletedInitialCloudSync(uid: String): Boolean =
        uid in _settings.value.syncedCloudUids

    suspend fun markInitialCloudSyncCompleted(uid: String) {
        if (uid.isBlank()) return
        val next = _settings.value.syncedCloudUids + uid
        val csv = next.joinToString(",")
        updateSync({ it.copy(syncedCloudUids = next) }) {
            putString("synced_cloud_uids", csv)
        }
        context.soundLapaDataStore.edit { it[Keys.SYNCED_CLOUD_UIDS] = csv }
    }

    suspend fun setLastTrendingRefreshMs(timestampMs: Long) {
        updateSync({ it.copy(lastTrendingRefreshMs = timestampMs) }) {
            putLong("last_trending_refresh_ms", timestampMs)
        }
        context.soundLapaDataStore.edit { it[Keys.LAST_TRENDING_REFRESH_MS] = timestampMs }
    }
}
