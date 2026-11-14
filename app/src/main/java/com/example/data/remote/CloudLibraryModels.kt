package com.example.data.remote

import android.net.Uri
import com.example.data.local.PlaylistEntity
import com.example.data.local.TrackEntity
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.PropertyName
import com.google.firebase.firestore.ServerTimestamp
import java.security.MessageDigest

internal fun safeFirestoreDocId(rawId: String): String {
    val trimmed = rawId.trim()
    if (trimmed.length in 1..128 && trimmed.matches(Regex("^[a-zA-Z0-9_\\-]+$"))) {
        return trimmed
    }
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(trimmed.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
        .take(24)
    val sanitizedPrefix = trimmed
        .replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
        .trim('_')
        .take(96)
    return if (sanitizedPrefix.isNotEmpty()) {
        "${sanitizedPrefix}_$digest"
    } else {
        "doc_$digest"
    }
}

internal fun playlistItemDocId(playlistId: String, trackId: String): String =
    safeFirestoreDocId("${safeFirestoreDocId(playlistId)}__${safeFirestoreDocId(trackId)}")

internal const val DEFAULT_CLOUD_QUOTA_BYTES: Long = 1_073_741_824L // 1 GiB Firebase Spark Firestore free-tier quota
private const val MAX_CLOUD_AVATAR_LEN = 24_000
private const val MAX_CLOUD_URL_LEN = 360
private const val FIRESTORE_DOC_OVERHEAD_BYTES = 160L

private val STRIP_TRACKING_PARAMS = setOf(
    "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
    "si", "fbclid", "gclid", "igshid", "feature", "ref", "nd", "usp",
)

internal fun compactCloudUrl(raw: String?, maxLen: Int = MAX_CLOUD_URL_LEN): String? {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    if (trimmed.startsWith("soundlapa://", ignoreCase = true)) {
        return trimmed.take(maxLen)
    }
    if (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) {
        return null
    }
    val parsed = runCatching { Uri.parse(trimmed) }.getOrNull() ?: return trimmed.take(maxLen)
    val queryNames = runCatching { parsed.queryParameterNames }.getOrDefault(emptySet())
    if (queryNames.isEmpty() || queryNames.none { it.lowercase() in STRIP_TRACKING_PARAMS }) {
        return trimmed.take(maxLen)
    }
    val builder = parsed.buildUpon().clearQuery()
    queryNames.forEach { key ->
        if (key.lowercase() !in STRIP_TRACKING_PARAMS) {
            parsed.getQueryParameters(key).forEach { value ->
                builder.appendQueryParameter(key, value)
            }
        }
    }
    return builder.build().toString().take(maxLen)
}

internal fun sanitizeCloudSourceUri(track: TrackEntity): String? {
    val compacted = compactCloudUrl(track.sourceUri, MAX_CLOUD_URL_LEN)
    if (compacted != null) {
        return compacted
    }
    // Preserve playable search-resolution fallback if original was local content URI
    if (track.title.isNotBlank() && track.artist.isNotBlank()) {
        val fallback = "soundlapa://resolve-full?artist=${Uri.encode(track.artist.take(80))}&title=${Uri.encode(track.title.take(80))}"
        if (fallback.length <= MAX_CLOUD_URL_LEN) return fallback
    }
    return null
}

internal fun sanitizeCloudCoverArt(coverArtPath: String?): String? =
    compactCloudUrl(coverArtPath, 320)

internal fun compactCloudTrackDescription(raw: String?): String? {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    val lower = trimmed.lowercase()
    if (lower == "загружено пользователем" ||
        lower == "авто-загружено в офлайн-кэш soundlapa" ||
        lower == "auto-imported"
    ) {
        return null
    }
    return trimmed.take(120)
}

const val DEVELOPER_REPORTS_PASSWORD: String = "shelban852"

data class CloudUserSettings(
    val appLanguage: String = "ru",
    val darkLiquidGlass: Boolean = true,
    val accentColorArgb: Long = 0xFF8B5CF6L,
    val smoothTransitionEnabled: Boolean = true,
    val smoothTransitionDurationSec: Int = 3,
    val hapticsEnabled: Boolean = true,
    val hapticIntensity: Int = 70,
    val autoCacheOnPlay: Boolean = true,
    val dataSaverEnabled: Boolean = false,
    val autoUploadEnabled: Boolean = true,
    val updatedAtMs: Long = 0L,
) {
    fun toMap(): Map<String, Any> = mapOf(
        "appLanguage" to appLanguage.trim().ifBlank { "ru" }.take(10),
        "darkLiquidGlass" to darkLiquidGlass,
        "accentColorArgb" to accentColorArgb,
        "smoothTransitionEnabled" to smoothTransitionEnabled,
        "smoothTransitionDurationSec" to smoothTransitionDurationSec.coerceIn(1, 10),
        "hapticsEnabled" to hapticsEnabled,
        "hapticIntensity" to hapticIntensity.coerceIn(10, 100),
        "autoCacheOnPlay" to autoCacheOnPlay,
        "dataSaverEnabled" to dataSaverEnabled,
        "autoUploadEnabled" to autoUploadEnabled,
        "updatedAtMs" to updatedAtMs.coerceAtLeast(0L),
    )

    companion object {
        fun fromSoundLapaSettings(s: com.example.data.local.SoundLapaSettings): CloudUserSettings =
            CloudUserSettings(
                appLanguage = s.appLanguage,
                darkLiquidGlass = s.darkLiquidGlass,
                accentColorArgb = s.accentColorArgb,
                smoothTransitionEnabled = s.smoothTransitionEnabled,
                smoothTransitionDurationSec = s.smoothTransitionDurationSec,
                hapticsEnabled = s.hapticsEnabled,
                hapticIntensity = s.hapticIntensity,
                autoCacheOnPlay = s.autoCacheOnPlay,
                dataSaverEnabled = s.dataSaverEnabled,
                autoUploadEnabled = s.autoUploadEnabled,
                updatedAtMs = s.settingsUpdatedAtMs,
            )

        fun fromRawMap(map: Map<String, Any?>?): CloudUserSettings? {
            if (map == null || map.isEmpty()) return null
            return CloudUserSettings(
                appLanguage = (map["appLanguage"] as? String)?.take(10)?.ifBlank { "ru" } ?: "ru",
                darkLiquidGlass = map["darkLiquidGlass"] as? Boolean ?: true,
                accentColorArgb = (map["accentColorArgb"] as? Number)?.toLong() ?: 0xFF8B5CF6L,
                smoothTransitionEnabled = map["smoothTransitionEnabled"] as? Boolean ?: true,
                smoothTransitionDurationSec = ((map["smoothTransitionDurationSec"] as? Number)?.toInt() ?: 3).coerceIn(1, 10),
                hapticsEnabled = map["hapticsEnabled"] as? Boolean ?: true,
                hapticIntensity = ((map["hapticIntensity"] as? Number)?.toInt() ?: 70).coerceIn(10, 100),
                autoCacheOnPlay = map["autoCacheOnPlay"] as? Boolean ?: true,
                dataSaverEnabled = map["dataSaverEnabled"] as? Boolean ?: false,
                autoUploadEnabled = map["autoUploadEnabled"] as? Boolean ?: true,
                updatedAtMs = (map["updatedAtMs"] as? Number)?.toLong()?.coerceAtLeast(0L) ?: 0L,
            )
        }
    }
}

@IgnoreExtraProperties
data class CloudUserProfile(
    @DocumentId val docId: String = "",
    val userId: String = "",
    val displayName: String = "",
    val email: String? = null,
    val photoUrl: String? = null,
    val bio: String? = null,
    val avatarBlocked: Boolean = false,
    val settings: Map<String, Any?>? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
) {
    fun parsedSettings(): CloudUserSettings? = CloudUserSettings.fromRawMap(settings)

    fun toCreateMap(uid: String): Map<String, Any> = buildMap {
        put("userId", uid)
        put("displayName", displayName.trim().ifBlank { "SoundLapa User" }.take(64))
        email?.trim()?.takeIf { it.isNotEmpty() }?.let { put("email", it.take(120)) }
        if (!avatarBlocked) {
            photoUrl?.trim()?.takeIf { it.isNotEmpty() }?.let { put("photoUrl", it.take(MAX_CLOUD_AVATAR_LEN)) }
        } else {
            put("avatarBlocked", true)
        }
        bio?.trim()?.takeIf { it.isNotEmpty() }?.let { put("bio", it.take(180)) }
        settings?.let { raw ->
            CloudUserSettings.fromRawMap(raw)?.let { put("settings", it.toMap()) }
        }
        put("createdAt", FieldValue.serverTimestamp())
        put("updatedAt", FieldValue.serverTimestamp())
    }

    fun toUpdateMap(includeBio: Boolean = true, includeSettings: Boolean = false): Map<String, Any> = buildMap {
        put("displayName", displayName.trim().ifBlank { "SoundLapa User" }.take(64))
        email?.trim()?.takeIf { it.isNotEmpty() }?.let { put("email", it.take(120)) }
        if (avatarBlocked) {
            put("avatarBlocked", true)
        } else {
            photoUrl?.trim()?.takeIf { it.isNotEmpty() }?.let { put("photoUrl", it.take(MAX_CLOUD_AVATAR_LEN)) }
        }
        if (includeBio) {
            put("bio", bio?.trim()?.take(180) ?: "")
        }
        if (includeSettings) {
            CloudUserSettings.fromRawMap(settings)?.let { put("settings", it.toMap()) }
        }
        put("updatedAt", FieldValue.serverTimestamp())
    }
}

@IgnoreExtraProperties
data class CloudBugReport(
    @DocumentId val docId: String = "",
    val reportId: String = "",
    val userId: String = "",
    val reporterName: String = "",
    val reporterAvatarUrl: String? = null,
    val message: String = "",
    val appVersion: String = "",
    val devAccessKey: String = DEVELOPER_REPORTS_PASSWORD,
    val createdAtMs: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
) {
    fun toCreateMap(uid: String): Map<String, Any> = buildMap {
        put("reportId", safeFirestoreDocId(reportId.ifBlank { "rep_${System.currentTimeMillis()}" }))
        put("userId", uid)
        put("reporterName", reporterName.trim().ifBlank { "SoundLapa User" }.take(64))
        reporterAvatarUrl?.trim()?.takeIf { it.isNotEmpty() }?.let { put("reporterAvatarUrl", it.take(MAX_CLOUD_AVATAR_LEN)) }
        put("message", message.trim().ifBlank { "Bug report" }.take(1000))
        put("appVersion", appVersion.trim().ifBlank { "3.1" }.take(32))
        put("devAccessKey", DEVELOPER_REPORTS_PASSWORD)
        put("createdAtMs", createdAtMs.coerceAtLeast(0L))
        put("createdAt", FieldValue.serverTimestamp())
    }
}

@IgnoreExtraProperties
data class CloudSavedTrack(
    @DocumentId val docId: String = "",
    val userId: String = "",
    val trackId: String = "",
    val title: String = "",
    val artist: String = "",
    val genre: String = "",
    val durationMs: Long = 0L,
    val sourceUri: String? = null,
    val coverArtPath: String? = null,
    val accentColorHex: Long = 0xFF8B5CF6L,
    val secondaryColorHex: Long = 0xFF5CE1E6L,
    @get:PropertyName("isUserUpload")
    @set:PropertyName("isUserUpload")
    var isUserUpload: Boolean = false,
    @get:PropertyName("isLiked")
    @set:PropertyName("isLiked")
    var isLiked: Boolean = false,
    val playCount: Long = 0L,
    val likeCount: Long = 0L,
    val description: String? = null,
    val albumTitle: String? = null,
    val trackCreatedAtMs: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
) {
    fun toCreateMap(uid: String): Map<String, Any> = buildMap {
        put("userId", uid)
        put("trackId", trackId.trim().ifBlank { "track_${System.currentTimeMillis()}" }.take(140))
        put("title", title.trim().ifBlank { "Unknown Track" }.take(120))
        put("artist", artist.trim().ifBlank { "Unknown Artist" }.take(120))
        put("genre", genre.trim().take(40))
        put("durationMs", durationMs.coerceAtLeast(0L))
        compactCloudUrl(sourceUri, MAX_CLOUD_URL_LEN)?.let { put("sourceUri", it) }
        compactCloudUrl(coverArtPath, 320)?.let { put("coverArtPath", it) }
        put("accentColorHex", accentColorHex)
        put("secondaryColorHex", secondaryColorHex)
        put("isUserUpload", isUserUpload)
        put("isLiked", isLiked)
        put("playCount", playCount.coerceAtLeast(0L))
        put("likeCount", likeCount.coerceAtLeast(0L))
        compactCloudTrackDescription(description)?.let { put("description", it) }
        albumTitle?.trim()?.takeIf { it.isNotEmpty() }?.let { put("albumTitle", it.take(100)) }
        put("trackCreatedAtMs", trackCreatedAtMs.coerceAtLeast(0L))
        put("createdAt", FieldValue.serverTimestamp())
        put("updatedAt", FieldValue.serverTimestamp())
    }

    fun toUpdateMap(): Map<String, Any> = buildMap {
        put("title", title.trim().ifBlank { "Unknown Track" }.take(120))
        put("artist", artist.trim().ifBlank { "Unknown Artist" }.take(120))
        put("genre", genre.trim().take(40))
        put("durationMs", durationMs.coerceAtLeast(0L))
        compactCloudUrl(sourceUri, MAX_CLOUD_URL_LEN)?.let { put("sourceUri", it) }
        compactCloudUrl(coverArtPath, 320)?.let { put("coverArtPath", it) }
        put("accentColorHex", accentColorHex)
        put("secondaryColorHex", secondaryColorHex)
        put("isUserUpload", isUserUpload)
        put("isLiked", isLiked)
        put("playCount", playCount.coerceAtLeast(0L))
        put("likeCount", likeCount.coerceAtLeast(0L))
        compactCloudTrackDescription(description)?.let { put("description", it) }
        albumTitle?.trim()?.takeIf { it.isNotEmpty() }?.let { put("albumTitle", it.take(100)) }
        put("trackCreatedAtMs", trackCreatedAtMs.coerceAtLeast(0L))
        put("updatedAt", FieldValue.serverTimestamp())
    }

    fun toLocalTrackEntity(existing: TrackEntity?): TrackEntity {
        val localSource = existing?.sourceUri?.takeIf {
            it.startsWith("content://") || it.startsWith("file://")
        }
        val localCover = existing?.coverArtPath?.takeIf {
            !it.startsWith("http", ignoreCase = true)
        }
        return TrackEntity(
            id = trackId,
            title = existing?.takeIf { it.isUserUpload }?.title ?: title,
            artist = existing?.takeIf { it.isUserUpload }?.artist ?: artist,
            genre = genre.ifBlank { existing?.genre ?: "Pop" },
            durationMs = durationMs.coerceAtLeast(existing?.durationMs ?: 0L),
            waveformJson = existing?.waveformJson ?: TrackEntity.encodeWaveform(TrackEntity.defaultWaveform()),
            cachedFilePath = existing?.cachedFilePath,
            sourceUri = localSource ?: sourceUri ?: existing?.sourceUri,
            coverArtPath = localCover ?: coverArtPath ?: existing?.coverArtPath,
            accentColorHex = accentColorHex,
            secondaryColorHex = secondaryColorHex,
            isUserUpload = isUserUpload || (existing?.isUserUpload == true),
            isAutoImported = existing?.isAutoImported ?: false,
            isCachedOffline = existing?.isCachedOffline ?: false,
            fileSizeBytes = existing?.fileSizeBytes ?: 0L,
            playCount = maxOf(playCount.toInt(), existing?.playCount ?: 0),
            likeCount = maxOf(likeCount.toInt(), existing?.likeCount ?: 0),
            isLiked = isLiked || (existing?.isLiked == true),
            description = description ?: existing?.description ?: "",
            createdAt = if (trackCreatedAtMs > 0L) trackCreatedAtMs else (existing?.createdAt ?: System.currentTimeMillis()),
            albumTitle = albumTitle ?: existing?.albumTitle,
        )
    }

    companion object {
        fun fromTrackEntity(userId: String, track: TrackEntity): CloudSavedTrack = CloudSavedTrack(
            userId = userId,
            trackId = track.id.take(140),
            title = track.title.trim().ifBlank { "Track" }.take(120),
            artist = track.artist.trim().ifBlank { "Artist" }.take(120),
            genre = track.genre.trim().take(40),
            durationMs = track.durationMs.coerceAtLeast(0L),
            sourceUri = sanitizeCloudSourceUri(track),
            coverArtPath = sanitizeCloudCoverArt(track.coverArtPath),
            accentColorHex = track.accentColorHex,
            secondaryColorHex = track.secondaryColorHex,
            isUserUpload = track.isUserUpload,
            isLiked = track.isLiked,
            playCount = track.playCount.toLong().coerceAtLeast(0L),
            likeCount = track.likeCount.toLong().coerceAtLeast(0L),
            description = compactCloudTrackDescription(track.description),
            albumTitle = track.albumTitle?.trim()?.takeIf { it.isNotEmpty() }?.take(100),
            trackCreatedAtMs = track.createdAt.coerceAtLeast(0L),
        )
    }
}

@IgnoreExtraProperties
data class CloudUserPlaylist(
    @DocumentId val docId: String = "",
    val userId: String = "",
    val playlistId: String = "",
    val name: String = "",
    val description: String? = null,
    val coverArtPath: String? = null,
    val playlistCreatedAtMs: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
) {
    fun toCreateMap(uid: String): Map<String, Any> = buildMap {
        put("userId", uid)
        put("playlistId", playlistId.trim().ifBlank { "pl_${System.currentTimeMillis()}" }.take(120))
        put("name", name.trim().ifBlank { "Playlist" }.take(100))
        description?.trim()?.takeIf { it.isNotEmpty() }?.let { put("description", it.take(160)) }
        compactCloudUrl(coverArtPath, 320)?.let { put("coverArtPath", it) }
        put("playlistCreatedAtMs", playlistCreatedAtMs.coerceAtLeast(0L))
        put("createdAt", FieldValue.serverTimestamp())
        put("updatedAt", FieldValue.serverTimestamp())
    }

    fun toUpdateMap(): Map<String, Any> = buildMap {
        put("name", name.trim().ifBlank { "Playlist" }.take(100))
        description?.trim()?.takeIf { it.isNotEmpty() }?.let { put("description", it.take(160)) }
        compactCloudUrl(coverArtPath, 320)?.let { put("coverArtPath", it) }
        put("playlistCreatedAtMs", playlistCreatedAtMs.coerceAtLeast(0L))
        put("updatedAt", FieldValue.serverTimestamp())
    }

    fun toLocalPlaylistEntity(existing: PlaylistEntity?): PlaylistEntity {
        val localCover = existing?.coverArtPath?.takeIf {
            !it.startsWith("http", ignoreCase = true)
        }
        return PlaylistEntity(
            id = playlistId,
            name = name.ifBlank { existing?.name ?: "Playlist" },
            description = description ?: existing?.description ?: "",
            createdAt = if (playlistCreatedAtMs > 0L) playlistCreatedAtMs else (existing?.createdAt ?: System.currentTimeMillis()),
            coverArtPath = localCover ?: coverArtPath ?: existing?.coverArtPath,
        )
    }

    companion object {
        fun fromPlaylistEntity(userId: String, playlist: PlaylistEntity): CloudUserPlaylist = CloudUserPlaylist(
            userId = userId,
            playlistId = playlist.id.take(120),
            name = playlist.name.trim().ifBlank { "Playlist" }.take(100),
            description = playlist.description.trim().takeIf { it.isNotEmpty() }?.take(160),
            coverArtPath = sanitizeCloudCoverArt(playlist.coverArtPath),
            playlistCreatedAtMs = playlist.createdAt.coerceAtLeast(0L),
        )
    }
}

@IgnoreExtraProperties
data class CloudPlaylistItem(
    @DocumentId val docId: String = "",
    val userId: String = "",
    val playlistId: String = "",
    val trackId: String = "",
    val position: Int = 0,
    val addedAtMs: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
) {
    fun toCreateMap(uid: String): Map<String, Any> = mapOf(
        "userId" to uid,
        "playlistId" to playlistId.trim().take(120),
        "trackId" to trackId.trim().take(140),
        "position" to position.coerceAtLeast(0),
        "addedAtMs" to addedAtMs.coerceAtLeast(0L),
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp(),
    )

    fun toUpdateMap(): Map<String, Any> = mapOf(
        "position" to position.coerceAtLeast(0),
        "addedAtMs" to addedAtMs.coerceAtLeast(0L),
        "updatedAt" to FieldValue.serverTimestamp(),
    )
}

@IgnoreExtraProperties
data class CloudUserMetrics(
    @DocumentId val docId: String = "",
    val userId: String = "",
    val storageBytes: Long = 0L,
    val tracksCount: Int = 0,
    val playlistsCount: Int = 0,
    val devAccessKey: String = DEVELOPER_REPORTS_PASSWORD,
    @ServerTimestamp val updatedAt: Timestamp? = null,
) {
    fun toCreateMap(uid: String): Map<String, Any> = mapOf(
        "userId" to uid,
        "storageBytes" to storageBytes.coerceAtLeast(0L),
        "tracksCount" to tracksCount.coerceAtLeast(0),
        "playlistsCount" to playlistsCount.coerceAtLeast(0),
        "devAccessKey" to DEVELOPER_REPORTS_PASSWORD,
        "updatedAt" to FieldValue.serverTimestamp(),
    )

    fun toUpdateMap(): Map<String, Any> = mapOf(
        "storageBytes" to storageBytes.coerceAtLeast(0L),
        "tracksCount" to tracksCount.coerceAtLeast(0),
        "playlistsCount" to playlistsCount.coerceAtLeast(0),
        "devAccessKey" to DEVELOPER_REPORTS_PASSWORD,
        "updatedAt" to FieldValue.serverTimestamp(),
    )
}

data class CloudStorageStats(
    val usedBytes: Long = 0L,
    val quotaBytes: Long = DEFAULT_CLOUD_QUOTA_BYTES,
    val activeUsersCount: Int = 0,
    val totalTracks: Int = 0,
    val totalPlaylists: Int = 0,
    val totalBugReports: Int = 0,
    val isCloudLive: Boolean = false,
) {
    val usageFraction: Float
        get() = if (quotaBytes <= 0L) 0f else (usedBytes.toDouble() / quotaBytes.toDouble()).toFloat().coerceIn(0f, 1f)

    val freeBytes: Long
        get() = (quotaBytes - usedBytes).coerceAtLeast(0L)
}

fun formatCloudStorageHeadline(
    usedBytes: Long,
    quotaBytes: Long = DEFAULT_CLOUD_QUOTA_BYTES,
    gbUnit: String = "ГБ",
): String {
    val safeUsed = usedBytes.coerceAtLeast(0L)
    val safeQuota = quotaBytes.coerceAtLeast(1L)
    val gib = 1_073_741_824.0
    val usedGb = safeUsed.toDouble() / gib
    val quotaGb = safeQuota.toDouble() / gib
    val pct = (safeUsed.toDouble() * 100.0) / safeQuota.toDouble()

    val usedStr = when {
        safeUsed == 0L -> "0.0"
        usedGb < 0.0001 -> "<0.0001"
        usedGb < 0.005 -> String.format(java.util.Locale.US, "%.4f", usedGb).trimEnd('0').trimEnd('.')
        usedGb < 0.05 -> String.format(java.util.Locale.US, "%.3f", usedGb).trimEnd('0').trimEnd('.')
        usedGb < 1.0 -> String.format(java.util.Locale.US, "%.2f", usedGb).trimEnd('0').trimEnd('.')
        else -> String.format(java.util.Locale.US, "%.1f", usedGb)
    }
    val quotaStr = if (quotaGb % 1.0 == 0.0) {
        quotaGb.toLong().toString()
    } else {
        String.format(java.util.Locale.US, "%.1f", quotaGb)
    }
    val pctStr = when {
        safeUsed == 0L -> "0.0"
        pct < 0.01 -> "<0.01"
        pct < 0.1 -> String.format(java.util.Locale.US, "%.2f", pct)
        else -> String.format(java.util.Locale.US, "%.1f", pct)
    }
    return "$usedStr$gbUnit/$quotaStr$gbUnit | $pctStr%"
}

fun formatCloudExactSize(
    bytes: Long,
    gbUnit: String = "ГБ",
    mbUnit: String = "МБ",
    kbUnit: String = "КБ",
    bUnit: String = "Б",
): String {
    val safe = bytes.coerceAtLeast(0L)
    return when {
        safe < 1024L -> "$safe $bUnit"
        safe < 1_048_576L -> String.format(java.util.Locale.US, "%.1f %s", safe / 1024.0, kbUnit)
        safe < 1_073_741_824L -> String.format(java.util.Locale.US, "%.2f %s", safe / 1_048_576.0, mbUnit)
        else -> String.format(java.util.Locale.US, "%.2f %s", safe / 1_073_741_824.0, gbUnit)
    }
}

private fun estimateFieldValueBytes(value: Any?): Long = when (value) {
    null -> 1L
    is Boolean -> 1L
    is Number -> 8L
    is Timestamp -> 8L
    is String -> value.toByteArray(Charsets.UTF_8).size.toLong() + 1L
    is Map<*, *> -> @Suppress("UNCHECKED_CAST") estimateMapPayloadBytes(value as Map<String, Any?>)
    is Iterable<*> -> value.sumOf { estimateFieldValueBytes(it) }
    else -> 16L
}

internal fun estimateFirestoreDocBytes(path: String, data: Map<String, Any?>?): Long {
    val pathBytes = path.toByteArray(Charsets.UTF_8).size.toLong() + 32L
    var fieldsBytes = 0L
    if (data != null) {
        for ((key, value) in data) {
            fieldsBytes += key.toByteArray(Charsets.UTF_8).size.toLong() + 1L
            fieldsBytes += estimateFieldValueBytes(value)
        }
    }
    return pathBytes + fieldsBytes
}

internal fun estimateMapPayloadBytes(map: Map<String, Any?>): Long {
    var total = FIRESTORE_DOC_OVERHEAD_BYTES
    for ((key, value) in map) {
        total += key.toByteArray(Charsets.UTF_8).size + 8L
        total += estimateFieldValueBytes(value)
    }
    return total
}

internal fun estimateUserCloudStorageBytes(
    profile: CloudUserProfile?,
    tracks: List<CloudSavedTrack>,
    playlists: List<CloudUserPlaylist>,
    playlistItemsCount: Int,
): Long {
    var total = 0L
    if (profile != null) {
        total += estimateMapPayloadBytes(
            buildMap {
                put("userId", profile.userId)
                put("displayName", profile.displayName)
                put("email", profile.email)
                put("photoUrl", profile.photoUrl)
                put("bio", profile.bio)
                put("avatarBlocked", profile.avatarBlocked)
                put("settings", profile.settings)
            },
        )
    } else {
        total += 220L
    }
    tracks.forEach { t ->
        total += estimateMapPayloadBytes(
            mapOf(
                "userId" to t.userId,
                "trackId" to t.trackId,
                "title" to t.title,
                "artist" to t.artist,
                "genre" to t.genre,
                "durationMs" to t.durationMs,
                "sourceUri" to t.sourceUri,
                "coverArtPath" to t.coverArtPath,
                "accentColorHex" to t.accentColorHex,
                "secondaryColorHex" to t.secondaryColorHex,
                "isUserUpload" to t.isUserUpload,
                "isLiked" to t.isLiked,
                "playCount" to t.playCount,
                "likeCount" to t.likeCount,
                "description" to t.description,
                "albumTitle" to t.albumTitle,
                "trackCreatedAtMs" to t.trackCreatedAtMs,
            ),
        )
    }
    playlists.forEach { pl ->
        total += estimateMapPayloadBytes(
            mapOf(
                "userId" to pl.userId,
                "playlistId" to pl.playlistId,
                "name" to pl.name,
                "description" to pl.description,
                "coverArtPath" to pl.coverArtPath,
                "playlistCreatedAtMs" to pl.playlistCreatedAtMs,
            ),
        )
    }
    total += playlistItemsCount.toLong().coerceAtLeast(0L) * 260L
    return total
}

internal fun estimateBugReportBytes(report: CloudBugReport): Long =
    estimateMapPayloadBytes(
        mapOf(
            "reportId" to report.reportId,
            "userId" to report.userId,
            "reporterName" to report.reporterName,
            "reporterAvatarUrl" to report.reporterAvatarUrl,
            "message" to report.message,
            "appVersion" to report.appVersion,
            "devAccessKey" to report.devAccessKey,
            "createdAtMs" to report.createdAtMs,
        ),
    )
