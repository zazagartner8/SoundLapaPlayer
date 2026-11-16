package com.example.data.remote

import android.content.Context
import com.example.R
import com.example.data.local.PlaylistEntity
import com.example.data.local.PlaylistTrackCrossRef
import com.example.data.local.SoundLapaDao
import com.example.data.local.TrackEntity
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

data class CloudSyncSummary(
    val restoredTracks: Int,
    val restoredPlaylists: Int,
    val uploadedTracks: Int,
    val uploadedPlaylists: Int,
    val totalCloudTracks: Int,
    val totalCloudPlaylists: Int,
)

class CloudLibraryRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth,
) {
    constructor(context: Context) : this(
        db = FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id),
        ),
        auth = Firebase.auth,
    )

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    private fun userDocRef(uid: String) = db.collection("users").document(uid)

    private fun friendsCollection(uid: String) = userDocRef(uid).collection("friends")

    private fun artistSubscriptionsCollection(uid: String) = userDocRef(uid).collection("artist_subscriptions")

    private fun publicProfilesCollection() = db.collection("public_profiles")

    private fun usernamesCollection() = db.collection("usernames")

    private fun tracksCollection(uid: String) = userDocRef(uid).collection("tracks")

    private fun playlistsCollection(uid: String) = userDocRef(uid).collection("playlists")

    private fun playlistItemsCollection(uid: String, playlistId: String) =
        playlistsCollection(uid).document(safeFirestoreDocId(playlistId)).collection("items")

    fun isCurrentUserVerifiedDeveloper(): Boolean {
        val user = auth.currentUser ?: return false
        return user.email.equals(DEVELOPER_EMAIL, ignoreCase = true) && user.isEmailVerified
    }

    suspend fun upsertUserProfile(
        displayName: String?,
        email: String?,
        photoUrl: String?,
        localSettings: com.example.data.local.SoundLapaSettings? = null,
    ): Result<CloudUserProfile?> = withContext(Dispatchers.IO) {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
        val docRef = userDocRef(uid)
        val fallbackName = displayName?.trim()?.ifBlank { null }
            ?: email?.substringBefore('@')?.takeIf { it.isNotBlank() }
            ?: "SoundLap User"
        try {
            val snapshot = docRef.get().await()
            if (snapshot.exists()) {
                val existing = snapshot.toObject(
                    CloudUserProfile::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE,
                )
                val isBlocked = existing?.avatarBlocked == true || localSettings?.avatarModerationBlocked == true
                // Preserve user's custom displayName, username, bio, and custom photoUrl unless avatar is blocked!
                val resolvedName = existing?.displayName?.takeIf { it.isNotBlank() } ?: fallbackName
                val resolvedUsername = existing?.username?.takeIf { isValidUsernameHandle(it) }
                    ?: localSettings?.customUsername?.let(::normalizeUsernameHandle)?.takeIf(::isValidUsernameHandle)
                val resolvedEmail = existing?.email?.takeIf { it.isNotBlank() } ?: email
                val resolvedPhoto = if (isBlocked) null else (existing?.photoUrl?.takeIf { it.isNotBlank() } ?: photoUrl)
                val resolvedBio = existing?.bio

                val cloudSettings = existing?.parsedSettings()
                val mergedSettingsMap = when {
                    localSettings == null -> existing?.settings
                    cloudSettings == null -> CloudUserSettings.fromSoundLapaSettings(localSettings).toMap()
                    localSettings.settingsUpdatedAtMs > cloudSettings.updatedAtMs ->
                        CloudUserSettings.fromSoundLapaSettings(localSettings).toMap()
                    else -> cloudSettings.toMap()
                }
                val resolvedAccountUid = existing?.accountUid?.takeIf { it.length in 3..32 }
                    ?: com.example.data.local.SoundLapUidFormatter.format(uid)
                val mergedSearchHistory = localSettings?.let { ls ->
                    val combined = ls.searchHistory + existing?.searchHistory.orEmpty()
                    val seen = HashSet<String>()
                    combined.map { it.trim() }.filter { it.length >= 2 && seen.add(it.lowercase()) }.take(25)
                } ?: existing?.searchHistory
                val mergedEvents = localSettings?.let { ls ->
                    com.example.data.local.ListeningStatsAggregator.mergeEvents(
                        local = ls.listeningEvents,
                        remote = existing?.parsedListeningEvents().orEmpty(),
                    )
                }
                val encodedStatsJson = mergedEvents?.let { CloudUserProfile.encodeListeningEventsForCloud(it) }
                    ?: existing?.listeningStatsJson

                val merged = CloudUserProfile(
                    docId = snapshot.id,
                    userId = uid,
                    displayName = resolvedName,
                    username = resolvedUsername,
                    email = resolvedEmail,
                    photoUrl = resolvedPhoto,
                    bio = resolvedBio,
                    avatarBlocked = isBlocked,
                    accountUid = resolvedAccountUid,
                    searchHistory = mergedSearchHistory,
                    listeningStatsJson = encodedStatsJson,
                    settings = mergedSettingsMap,
                )
                val needsCloudWrite = existing?.displayName.isNullOrBlank() ||
                    existing?.accountUid.isNullOrBlank() ||
                    (existing?.username.isNullOrBlank() && !resolvedUsername.isNullOrBlank()) ||
                    (existing?.email.isNullOrBlank() && !resolvedEmail.isNullOrBlank()) ||
                    (!isBlocked && existing?.photoUrl.isNullOrBlank() && !resolvedPhoto.isNullOrBlank()) ||
                    (isBlocked && (existing?.avatarBlocked != true || !existing?.photoUrl.isNullOrBlank())) ||
                    (localSettings != null && (cloudSettings == null || localSettings.settingsUpdatedAtMs > cloudSettings.updatedAtMs)) ||
                    (mergedSearchHistory != null && mergedSearchHistory != existing?.searchHistory) ||
                    (encodedStatsJson != null && encodedStatsJson != existing?.listeningStatsJson)
                if (needsCloudWrite) {
                    val updateMap = merged.toUpdateMap(includeBio = false, includeSettings = mergedSettingsMap != null)
                        .toMutableMap()
                    updateMap["accountUid"] = resolvedAccountUid
                    mergedSearchHistory?.let { updateMap["searchHistory"] = it }
                    encodedStatsJson?.let { updateMap["listeningStatsJson"] = it }
                    if (isBlocked) {
                        updateMap["avatarBlocked"] = true
                        updateMap["photoUrl"] = com.google.firebase.firestore.FieldValue.delete()
                    }
                    docRef.update(updateMap).await()
                }
                runCatching {
                    syncPublicProfileInternal(
                        uid = uid,
                        displayName = resolvedName,
                        username = resolvedUsername,
                        photoUrl = resolvedPhoto,
                        bio = resolvedBio,
                    )
                }
                Result.success(merged)
            } else {
                val isBlocked = localSettings?.avatarModerationBlocked == true
                val settingsMap = localSettings?.let { CloudUserSettings.fromSoundLapaSettings(it).toMap() }
                val initialUsername = localSettings?.customUsername
                    ?.let(::normalizeUsernameHandle)
                    ?.takeIf(::isValidUsernameHandle)
                val profile = CloudUserProfile(
                    docId = uid,
                    userId = uid,
                    displayName = fallbackName,
                    username = initialUsername,
                    email = email,
                    photoUrl = if (isBlocked) null else photoUrl,
                    bio = null,
                    avatarBlocked = isBlocked,
                    accountUid = com.example.data.local.SoundLapUidFormatter.format(uid),
                    searchHistory = localSettings?.searchHistory?.take(25),
                    listeningStatsJson = localSettings?.listeningEvents
                        ?.let { CloudUserProfile.encodeListeningEventsForCloud(it) },
                    settings = settingsMap,
                )
                docRef.set(profile.toCreateMap(uid)).await()
                runCatching {
                    syncPublicProfileInternal(
                        uid = uid,
                        displayName = fallbackName,
                        username = initialUsername,
                        photoUrl = if (isBlocked) null else photoUrl,
                        bio = null,
                    )
                }
                Result.success(profile)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val op = OperationType.WRITE
            handleFirestoreError(e, op, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun revokeBlockedAvatar(
        email: String? = null,
        displayName: String? = null,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
        val docRef = userDocRef(uid)
        try {
            val snap = docRef.get().await()
            if (snap.exists()) {
                docRef.update(
                    mapOf(
                        "avatarBlocked" to true,
                        "photoUrl" to com.google.firebase.firestore.FieldValue.delete(),
                        "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                    ),
                ).await()
            } else {
                val fallbackName = displayName?.trim()?.takeIf { it.isNotEmpty() }
                    ?: auth.currentUser?.displayName?.takeIf { it.isNotBlank() }
                    ?: "SoundLap User"
                val profile = CloudUserProfile(
                    docId = uid,
                    userId = uid,
                    displayName = fallbackName,
                    email = email ?: auth.currentUser?.email,
                    photoUrl = null,
                    avatarBlocked = true,
                )
                docRef.set(profile.toCreateMap(uid)).await()
            }
            runCatching {
                val pubSnap = publicProfilesCollection().document(uid).get().await()
                if (pubSnap.exists()) {
                    publicProfilesCollection().document(uid).update(
                        mapOf(
                            "photoUrl" to com.google.firebase.firestore.FieldValue.delete(),
                            "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                        ),
                    ).await()
                }
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun updateUserCustomProfile(
        displayName: String,
        bio: String?,
        photoUrl: String?,
        email: String? = null,
        clearAvatar: Boolean = false,
        username: String? = null,
    ): Result<CloudUserProfile> = withContext(Dispatchers.IO) {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
        val docRef = userDocRef(uid)
        val cleanName = displayName.trim().ifBlank { "SoundLap User" }.take(64)
        val cleanBio = bio?.trim()?.take(180)
        val cleanPhoto = photoUrl?.trim()?.takeIf { it.isNotEmpty() }?.take(24_000)
        val cleanHandle = username?.let(::normalizeUsernameHandle)?.takeIf(::isValidUsernameHandle)
        try {
            val snap = docRef.get().await()
            if (snap.exists()) {
                val isBlocked = snap.getBoolean("avatarBlocked") == true
                val existingUsername = snap.getString("username")?.takeIf(::isValidUsernameHandle)
                val resolvedHandle = cleanHandle ?: existingUsername
                val updateMap = buildMap<String, Any> {
                    put("displayName", cleanName)
                    put("bio", cleanBio ?: "")
                    if (cleanHandle != null) {
                        put("username", cleanHandle)
                    }
                    when {
                        isBlocked -> {
                            put("avatarBlocked", true)
                            put("photoUrl", com.google.firebase.firestore.FieldValue.delete())
                        }
                        clearAvatar -> {
                            put("photoUrl", com.google.firebase.firestore.FieldValue.delete())
                        }
                        cleanPhoto != null -> {
                            put("photoUrl", cleanPhoto)
                        }
                    }
                    put("updatedAt", com.google.firebase.firestore.FieldValue.serverTimestamp())
                }
                docRef.update(updateMap).await()
                val effectivePhoto = if (isBlocked || clearAvatar) null else (cleanPhoto ?: snap.getString("photoUrl"))
                runCatching {
                    syncPublicProfileInternal(
                        uid = uid,
                        displayName = cleanName,
                        username = resolvedHandle,
                        photoUrl = effectivePhoto,
                        bio = cleanBio,
                    )
                }
                Result.success(
                    CloudUserProfile(
                        docId = uid,
                        userId = uid,
                        displayName = cleanName,
                        username = resolvedHandle,
                        email = email,
                        photoUrl = effectivePhoto,
                        bio = cleanBio,
                        avatarBlocked = isBlocked,
                    ),
                )
            } else {
                val effectivePhoto = if (clearAvatar) null else cleanPhoto
                val profile = CloudUserProfile(
                    userId = uid,
                    displayName = cleanName,
                    username = cleanHandle,
                    email = email,
                    photoUrl = effectivePhoto,
                    bio = cleanBio,
                )
                docRef.set(profile.toCreateMap(uid)).await()
                runCatching {
                    syncPublicProfileInternal(
                        uid = uid,
                        displayName = cleanName,
                        username = cleanHandle,
                        photoUrl = effectivePhoto,
                        bio = cleanBio,
                    )
                }
                Result.success(profile.copy(docId = uid))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun syncUserSettings(settings: com.example.data.local.SoundLapaSettings): Result<Unit> =
        withContext(Dispatchers.IO) {
            val uid = try {
                requireUserId()
            } catch (e: Exception) {
                return@withContext Result.failure(e)
            }
            val docRef = userDocRef(uid)
            val settingsMap = CloudUserSettings.fromSoundLapaSettings(settings).toMap()
            try {
                val snap = docRef.get().await()
                if (snap.exists()) {
                    docRef.update(
                        mapOf(
                            "settings" to settingsMap,
                            "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                        ),
                    ).await()
                } else {
                    val isBlocked = settings.avatarModerationBlocked
                    val profile = CloudUserProfile(
                        userId = uid,
                        displayName = settings.customDisplayName ?: auth.currentUser?.displayName ?: "SoundLap User",
                        username = settings.customUsername?.let(::normalizeUsernameHandle)?.takeIf(::isValidUsernameHandle),
                        email = auth.currentUser?.email,
                        photoUrl = if (isBlocked) null else (settings.customAvatarData ?: auth.currentUser?.photoUrl?.toString()),
                        bio = settings.customBio,
                        avatarBlocked = isBlocked,
                        settings = settingsMap,
                    )
                    docRef.set(profile.toCreateMap(uid)).await()
                }
                Result.success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.WRITE, docRef.path)
                Result.failure(e)
            }
        }

    suspend fun syncSearchHistoryAndListeningStats(
        searchHistory: List<String>,
        listeningEvents: List<com.example.data.local.ListeningPlayEvent>,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
        val docRef = userDocRef(uid)
        val cleanHistory = searchHistory.map { it.trim() }.filter { it.length >= 2 }.take(25)
        val encodedStats = CloudUserProfile.encodeListeningEventsForCloud(listeningEvents)
        try {
            val snap = docRef.get().await()
            if (snap.exists()) {
                val updateMap = buildMap<String, Any> {
                    put("accountUid", com.example.data.local.SoundLapUidFormatter.format(uid))
                    put("searchHistory", cleanHistory)
                    encodedStats?.let { put("listeningStatsJson", it) }
                    put("updatedAt", com.google.firebase.firestore.FieldValue.serverTimestamp())
                }
                docRef.update(updateMap).await()
            } else {
                val profile = CloudUserProfile(
                    docId = uid,
                    userId = uid,
                    displayName = auth.currentUser?.displayName ?: "SoundLap User",
                    email = auth.currentUser?.email,
                    accountUid = com.example.data.local.SoundLapUidFormatter.format(uid),
                    searchHistory = cleanHistory,
                    listeningStatsJson = encodedStats,
                )
                docRef.set(profile.toCreateMap(uid)).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun submitBugReport(report: CloudBugReport): Result<String> = withContext(Dispatchers.IO) {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
        val docId = safeFirestoreDocId(report.reportId.ifBlank { "rep_${System.currentTimeMillis()}" })
        val docRef = db.collection("bug_reports").document(docId)
        try {
            docRef.set(report.copy(reportId = docId, userId = uid).toCreateMap(uid)).await()
            Result.success(docId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun fetchDeveloperBugReports(accessPassword: String): Result<List<CloudBugReport>> =
        withContext(Dispatchers.IO) {
            try {
                requireUserId()
            } catch (e: Exception) {
                return@withContext Result.failure(e)
            }
            if (accessPassword != DEVELOPER_REPORTS_PASSWORD) {
                return@withContext Result.failure(IllegalAccessException("Invalid developer password"))
            }
            val path = "bug_reports"
            try {
                val snapshot = db.collection(path)
                    .whereEqualTo("devAccessKey", accessPassword)
                    .get()
                    .await()
                val reports = snapshot.toObjects(
                    CloudBugReport::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE,
                ).sortedByDescending { it.createdAtMs }
                Result.success(reports)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.LIST, path)
                Result.failure(e)
            }
        }

    suspend fun deleteDeveloperBugReport(
        docIdOrReportId: String,
        accessPassword: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            requireUserId()
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
        if (accessPassword != DEVELOPER_REPORTS_PASSWORD) {
            return@withContext Result.failure(IllegalAccessException("Invalid developer password"))
        }
        val cleanId = docIdOrReportId.trim()
        if (cleanId.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Empty report ID"))
        }
        val docRef = db.collection("bug_reports").document(cleanId)
        try {
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun syncCloudMetricsSnapshot(
        storageBytes: Long,
        tracksCount: Int,
        playlistsCount: Int,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
        val docRef = db.collection("cloud_metrics").document(uid)
        val model = CloudUserMetrics(
            docId = uid,
            userId = uid,
            storageBytes = storageBytes.coerceAtLeast(0L),
            tracksCount = tracksCount.coerceAtLeast(0),
            playlistsCount = playlistsCount.coerceAtLeast(0),
        )
        try {
            val snap = docRef.get().await()
            if (snap.exists()) {
                docRef.update(model.toUpdateMap()).await()
            } else {
                docRef.set(model.toCreateMap(uid)).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun syncCloudMetricsFromLocal(
        dao: SoundLapaDao,
        profileSnapshot: CloudUserProfile? = null,
    ): Result<CloudUserMetrics> = withContext(Dispatchers.IO) {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
        try {
            val tracks = dao.getAllTracksOnce()
            val playlists = dao.getAllPlaylistsOnce()
            val refs = dao.getAllPlaylistRefsOnce()
            val refTrackIds = refs.mapTo(HashSet()) { it.trackId }
            val personalTracks = tracks
                .filter { it.isLiked || it.isUserUpload || it.id in refTrackIds }
                .map { CloudSavedTrack.fromTrackEntity(uid, it) }
            val cloudPlaylists = playlists.map { CloudUserPlaylist.fromPlaylistEntity(uid, it) }
            val estimatedBytes = estimateUserCloudStorageBytes(
                profile = profileSnapshot,
                tracks = personalTracks,
                playlists = cloudPlaylists,
                playlistItemsCount = refs.size,
            )
            syncCloudMetricsSnapshot(
                storageBytes = estimatedBytes,
                tracksCount = personalTracks.size,
                playlistsCount = cloudPlaylists.size,
            ).getOrThrow()
            Result.success(
                CloudUserMetrics(
                    docId = uid,
                    userId = uid,
                    storageBytes = estimatedBytes,
                    tracksCount = personalTracks.size,
                    playlistsCount = cloudPlaylists.size,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchDeveloperCloudStorageStats(accessPassword: String): Result<CloudStorageStats> =
        withContext(Dispatchers.IO) {
            val uid = try {
                requireUserId()
            } catch (e: Exception) {
                return@withContext Result.failure(e)
            }
            if (accessPassword != DEVELOPER_REPORTS_PASSWORD) {
                return@withContext Result.failure(IllegalAccessException("Invalid developer password"))
            }
            try {
                // Measure exact live Firestore documents owned by the signed-in user
                val userSnap = userDocRef(uid).get().await()
                val tracksSnap = tracksCollection(uid).get().await()
                val playlistsSnap = playlistsCollection(uid).get().await()

                var currentUserBytes = if (userSnap.exists()) {
                    estimateFirestoreDocBytes(userSnap.reference.path, userSnap.data)
                } else {
                    0L
                }
                tracksSnap.documents.forEach { doc ->
                    currentUserBytes += estimateFirestoreDocBytes(doc.reference.path, doc.data)
                }
                playlistsSnap.documents.forEach { plDoc ->
                    currentUserBytes += estimateFirestoreDocBytes(plDoc.reference.path, plDoc.data)
                    val itemsSnap = plDoc.reference.collection("items").get().await()
                    itemsSnap.documents.forEach { itemDoc ->
                        currentUserBytes += estimateFirestoreDocBytes(itemDoc.reference.path, itemDoc.data)
                    }
                }

                val currentUserTracks = tracksSnap.size()
                val currentUserPlaylists = playlistsSnap.size()

                val metricsSnapshot = db.collection("cloud_metrics")
                    .whereEqualTo("devAccessKey", accessPassword)
                    .get()
                    .await()
                val userMetrics = metricsSnapshot.toObjects(
                    CloudUserMetrics::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE,
                )
                val selfMetric = userMetrics.find { it.userId == uid }
                val otherMetrics = userMetrics.filter { it.userId != uid }
                val effectiveUserBytes = maxOf(
                    currentUserBytes,
                    selfMetric?.storageBytes?.coerceAtLeast(0L) ?: 0L,
                )
                val effectiveUserTracks = maxOf(
                    currentUserTracks,
                    selfMetric?.tracksCount?.coerceAtLeast(0) ?: 0,
                )
                val effectiveUserPlaylists = maxOf(
                    currentUserPlaylists,
                    selfMetric?.playlistsCount?.coerceAtLeast(0) ?: 0,
                )
                val metricsDocsBytes = metricsSnapshot.documents.sumOf { doc ->
                    estimateFirestoreDocBytes(doc.reference.path, doc.data)
                }

                val reportsSnapshot = db.collection("bug_reports")
                    .whereEqualTo("devAccessKey", accessPassword)
                    .get()
                    .await()
                val reportsBytes = reportsSnapshot.documents.sumOf { doc ->
                    estimateFirestoreDocBytes(doc.reference.path, doc.data)
                }

                val otherUsersBytes = otherMetrics.sumOf { it.storageBytes.coerceAtLeast(0L) }
                val totalUsed = (effectiveUserBytes + otherUsersBytes + metricsDocsBytes + reportsBytes).coerceAtLeast(0L)

                val hasCurrentUserData = userSnap.exists() || effectiveUserTracks > 0 || effectiveUserPlaylists > 0
                val distinctUsers = buildSet {
                    otherMetrics.forEach { if (it.userId.isNotBlank()) add(it.userId) }
                    if (hasCurrentUserData) add(uid)
                }

                Result.success(
                    CloudStorageStats(
                        usedBytes = totalUsed,
                        quotaBytes = DEFAULT_CLOUD_QUOTA_BYTES,
                        activeUsersCount = distinctUsers.size,
                        totalTracks = effectiveUserTracks + otherMetrics.sumOf { it.tracksCount.coerceAtLeast(0) },
                        totalPlaylists = effectiveUserPlaylists + otherMetrics.sumOf { it.playlistsCount.coerceAtLeast(0) },
                        totalBugReports = reportsSnapshot.size(),
                        isCloudLive = true,
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.LIST, "cloud_metrics")
                Result.failure(e)
            }
        }

    suspend fun getUserProfile(): Result<CloudUserProfile?> = withContext(Dispatchers.IO) {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
        val docRef = userDocRef(uid)
        try {
            val snap = docRef.get().await()
            val profile = snap.toObject(
                CloudUserProfile::class.java,
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE,
            )
            Result.success(profile)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, docRef.path)
            Result.failure(e)
        }
    }

    fun observeSavedTracks(): Flow<List<CloudSavedTrack>> = flow {
        val uid = requireUserId()
        val path = "users/$uid/tracks"
        emitAll(
            tracksCollection(uid)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        CloudSavedTrack::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE,
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                },
        )
    }

    fun observePlaylists(): Flow<List<CloudUserPlaylist>> = flow {
        val uid = requireUserId()
        val path = "users/$uid/playlists"
        emitAll(
            playlistsCollection(uid)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        CloudUserPlaylist::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE,
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                },
        )
    }

    suspend fun upsertSavedTrack(track: TrackEntity): Result<String> = withContext(Dispatchers.IO) {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
        val docId = safeFirestoreDocId(track.id)
        val docRef = tracksCollection(uid).document(docId)
        val model = CloudSavedTrack.fromTrackEntity(uid, track)
        try {
            val snap = docRef.get().await()
            if (snap.exists()) {
                docRef.update(model.toUpdateMap()).await()
            } else {
                docRef.set(model.toCreateMap(uid)).await()
            }
            Result.success(docId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun getSavedTrack(trackId: String): Result<CloudSavedTrack?> = withContext(Dispatchers.IO) {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
        val docRef = tracksCollection(uid).document(safeFirestoreDocId(trackId))
        try {
            val snap = docRef.get().await()
            val item = snap.toObject(
                CloudSavedTrack::class.java,
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE,
            )
            Result.success(item)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun getSavedTracks(): Result<List<CloudSavedTrack>> = withContext(Dispatchers.IO) {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
        val path = "users/$uid/tracks"
        try {
            val snapshot = tracksCollection(uid).get().await()
            val list = snapshot.toObjects(
                CloudSavedTrack::class.java,
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE,
            )
            Result.success(list)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, path)
            Result.failure(e)
        }
    }

    suspend fun deleteSavedTrack(trackId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
        val docRef = tracksCollection(uid).document(safeFirestoreDocId(trackId))
        try {
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun upsertPlaylist(playlist: PlaylistEntity): Result<String> = withContext(Dispatchers.IO) {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
        val docId = safeFirestoreDocId(playlist.id)
        val docRef = playlistsCollection(uid).document(docId)
        val model = CloudUserPlaylist.fromPlaylistEntity(uid, playlist)
        try {
            val snap = docRef.get().await()
            if (snap.exists()) {
                docRef.update(model.toUpdateMap()).await()
            } else {
                docRef.set(model.toCreateMap(uid)).await()
            }
            Result.success(docId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun getPlaylists(): Result<List<CloudUserPlaylist>> = withContext(Dispatchers.IO) {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
        val path = "users/$uid/playlists"
        try {
            val snapshot = playlistsCollection(uid).get().await()
            val list = snapshot.toObjects(
                CloudUserPlaylist::class.java,
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE,
            )
            Result.success(list)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, path)
            Result.failure(e)
        }
    }

    suspend fun getPlaylistItems(playlistId: String): Result<List<CloudPlaylistItem>> = withContext(Dispatchers.IO) {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
        val safePlId = safeFirestoreDocId(playlistId)
        val path = "users/$uid/playlists/$safePlId/items"
        try {
            val snapshot = playlistItemsCollection(uid, playlistId).get().await()
            val list = snapshot.toObjects(
                CloudPlaylistItem::class.java,
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE,
            ).sortedWith(compareBy<CloudPlaylistItem> { it.position }.thenBy { it.addedAtMs })
            Result.success(list)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, path)
            Result.failure(e)
        }
    }

    suspend fun upsertPlaylistItem(
        playlist: PlaylistEntity,
        track: TrackEntity,
        position: Int,
        addedAtMs: Long,
    ): Result<String> = withContext(Dispatchers.IO) {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
        // Ensure referenced track exists in user's cloud library
        val trackResult = upsertSavedTrack(track)
        if (trackResult.isFailure) {
            return@withContext Result.failure(trackResult.exceptionOrNull() ?: IllegalStateException("Track sync failed"))
        }

        val playlistDocRef = playlistsCollection(uid).document(safeFirestoreDocId(playlist.id))
        val itemDocId = playlistItemDocId(playlist.id, track.id)
        val itemDocRef = playlistItemsCollection(uid, playlist.id).document(itemDocId)
        val itemModel = CloudPlaylistItem(
            userId = uid,
            playlistId = playlist.id.take(160),
            trackId = track.id.take(200),
            position = position.coerceAtLeast(0),
            addedAtMs = addedAtMs.coerceAtLeast(0L),
        )
        try {
            val playlistSnap = playlistDocRef.get().await()
            val itemSnap = itemDocRef.get().await()
            if (!playlistSnap.exists()) {
                val batch = db.batch()
                val plModel = CloudUserPlaylist.fromPlaylistEntity(uid, playlist)
                batch.set(playlistDocRef, plModel.toCreateMap(uid))
                if (itemSnap.exists()) {
                    batch.update(itemDocRef, itemModel.toUpdateMap())
                } else {
                    batch.set(itemDocRef, itemModel.toCreateMap(uid))
                }
                batch.commit().await()
            } else if (itemSnap.exists()) {
                itemDocRef.update(itemModel.toUpdateMap()).await()
            } else {
                itemDocRef.set(itemModel.toCreateMap(uid)).await()
            }
            Result.success(itemDocId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, itemDocRef.path)
            Result.failure(e)
        }
    }

    suspend fun removePlaylistItem(playlistId: String, trackId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
        val itemDocRef = playlistItemsCollection(uid, playlistId)
            .document(playlistItemDocId(playlistId, trackId))
        try {
            itemDocRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, itemDocRef.path)
            Result.failure(e)
        }
    }

    suspend fun deletePlaylist(playlistId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
        val safePlId = safeFirestoreDocId(playlistId)
        val playlistDocRef = playlistsCollection(uid).document(safePlId)
        val itemsCol = playlistDocRef.collection("items")
        try {
            val itemSnapshots = itemsCol.get().await()
            val batches = itemSnapshots.documents.chunked(400)
            for (chunk in batches) {
                val batch = db.batch()
                chunk.forEach { batch.delete(it.reference) }
                batch.commit().await()
            }
            playlistDocRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, playlistDocRef.path)
            Result.failure(e)
        }
    }

    suspend fun pushPlaylistWithTracks(
        playlist: PlaylistEntity,
        tracks: List<TrackEntity>,
        refs: List<PlaylistTrackCrossRef>,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
        try {
            upsertPlaylist(playlist).getOrThrow()
            val trackMap = tracks.associateBy { it.id }
            refs.forEach { ref ->
                val track = trackMap[ref.trackId] ?: return@forEach
                upsertPlaylistItem(
                    playlist = playlist,
                    track = track,
                    position = ref.position,
                    addedAtMs = ref.addedAt,
                ).getOrThrow()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun syncWithLocalDatabase(dao: SoundLapaDao): Result<CloudSyncSummary> = withContext(Dispatchers.IO) {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
        try {
            val cloudTracks = getSavedTracks().getOrThrow()
            val cloudPlaylists = getPlaylists().getOrThrow()
            val cloudItemsByPlaylist = HashMap<String, List<CloudPlaylistItem>>()
            for (pl in cloudPlaylists) {
                val items = getPlaylistItems(pl.playlistId).getOrDefault(emptyList())
                cloudItemsByPlaylist[pl.playlistId] = items
            }

            val localTracks = dao.getAllTracksOnce()
            val localTracksById = localTracks.associateBy { it.id }
            val localPlaylists = dao.getAllPlaylistsOnce()
            val localPlaylistsById = localPlaylists.associateBy { it.id }
            val localRefs = dao.getAllPlaylistRefsOnce()
            val localRefKeys = localRefs.mapTo(HashSet()) { "${it.playlistId}::${it.trackId}" }

            // 1. Restore Cloud -> Room in FK order (tracks -> playlists -> crossRefs)
            var restoredTracksCount = 0
            val mergedRemoteTracks = mutableListOf<TrackEntity>()
            for (ct in cloudTracks) {
                val existing = localTracksById[ct.trackId]
                val merged = ct.toLocalTrackEntity(existing)
                if (existing == null ||
                    (merged.isLiked && !existing.isLiked) ||
                    (merged.isUserUpload && !existing.isUserUpload)
                ) {
                    mergedRemoteTracks += merged
                    if (existing == null) restoredTracksCount++
                }
            }
            if (mergedRemoteTracks.isNotEmpty()) {
                dao.upsertTracks(mergedRemoteTracks)
            }

            var restoredPlaylistsCount = 0
            val cloudPlaylistsById = cloudPlaylists.associateBy { it.playlistId }
            for (cpl in cloudPlaylists) {
                val existingPl = localPlaylistsById[cpl.playlistId]
                if (existingPl == null) {
                    dao.upsertPlaylist(cpl.toLocalPlaylistEntity(null))
                    restoredPlaylistsCount++
                } else if (cpl.isPinned && !existingPl.isPinned) {
                    dao.updatePlaylistPin(existingPl.id, true, cpl.pinnedAtMs)
                }
            }

            val updatedLocalTrackIds = (localTracks.map { it.id } + mergedRemoteTracks.map { it.id }).toHashSet()
            for ((playlistId, items) in cloudItemsByPlaylist) {
                for (item in items) {
                    val key = "$playlistId::${item.trackId}"
                    if (key !in localRefKeys && item.trackId in updatedLocalTrackIds) {
                        dao.addTrackToPlaylist(
                            PlaylistTrackCrossRef(
                                playlistId = playlistId,
                                trackId = item.trackId,
                                addedAt = item.addedAtMs,
                                position = item.position,
                            ),
                        )
                    }
                }
            }

            // 2. Upload local personal data (liked, user uploads, and playlist-referenced tracks) -> Cloud
            val refreshedTracks = dao.getAllTracksOnce()
            val refreshedTracksById = refreshedTracks.associateBy { it.id }
            val refreshedPlaylists = dao.getAllPlaylistsOnce()
            val refreshedRefs = dao.getAllPlaylistRefsOnce()
            val referencedTrackIds = refreshedRefs.mapTo(HashSet()) { it.trackId }
            val cloudTrackById = cloudTracks.associateBy { it.trackId }

            var uploadedTracksCount = 0
            val tracksToPush = refreshedTracks.filter { t ->
                val isPersonal = t.isLiked || t.isUserUpload || t.id in referencedTrackIds
                if (!isPersonal) return@filter false
                val cloudMatch = cloudTrackById[t.id]
                cloudMatch == null ||
                    cloudMatch.isLiked != t.isLiked ||
                    cloudMatch.title != t.title ||
                    cloudMatch.artist != t.artist
            }
            for (track in tracksToPush) {
                if (upsertSavedTrack(track).isSuccess) {
                    uploadedTracksCount++
                }
            }

            val cloudPlaylistIds = cloudPlaylists.mapTo(HashSet()) { it.playlistId }
            var uploadedPlaylistsCount = 0
            for (pl in refreshedPlaylists) {
                val cloudPl = cloudPlaylistsById[pl.id]
                val needsUpload = pl.id !in cloudPlaylistIds ||
                    (cloudPl != null && (
                        cloudPl.isPinned != pl.isPinned ||
                            cloudPl.sortMode != pl.sortMode ||
                            cloudPl.name != pl.name ||
                            cloudPl.description != pl.description
                        ))
                if (needsUpload) {
                    if (upsertPlaylist(pl).isSuccess) {
                        uploadedPlaylistsCount++
                    }
                }
            }

            val refreshedPlaylistsById = refreshedPlaylists.associateBy { it.id }
            val cloudItemKeys = HashSet<String>()
            cloudItemsByPlaylist.forEach { (plId, items) ->
                items.forEach { cloudItemKeys += "$plId::${it.trackId}" }
            }
            for (ref in refreshedRefs) {
                val key = "${ref.playlistId}::${ref.trackId}"
                if (key !in cloudItemKeys) {
                    val pl = refreshedPlaylistsById[ref.playlistId] ?: continue
                    val track = refreshedTracksById[ref.trackId] ?: continue
                    upsertPlaylistItem(
                        playlist = pl,
                        track = track,
                        position = ref.position,
                        addedAtMs = ref.addedAt,
                    )
                }
            }

            val personalCloudTracks = refreshedTracks
                .filter { it.isLiked || it.isUserUpload || it.id in referencedTrackIds }
                .map { CloudSavedTrack.fromTrackEntity(uid, it) }
            val finalTracksCount = maxOf(cloudTracks.size, personalCloudTracks.size)
            val finalPlaylistsCount = maxOf(cloudPlaylists.size, refreshedPlaylists.size)

            val metricsPlaylists = refreshedPlaylists.map { CloudUserPlaylist.fromPlaylistEntity(uid, it) }
            val profileEstimate = getUserProfile().getOrNull()
            val usedBytes = estimateUserCloudStorageBytes(
                profile = profileEstimate,
                tracks = personalCloudTracks,
                playlists = metricsPlaylists,
                playlistItemsCount = refreshedRefs.size,
            )
            runCatching {
                syncCloudMetricsSnapshot(
                    storageBytes = usedBytes,
                    tracksCount = finalTracksCount,
                    playlistsCount = finalPlaylistsCount,
                )
            }

            Result.success(
                CloudSyncSummary(
                    restoredTracks = restoredTracksCount,
                    restoredPlaylists = restoredPlaylistsCount,
                    uploadedTracks = uploadedTracksCount,
                    uploadedPlaylists = uploadedPlaylistsCount,
                    totalCloudTracks = finalTracksCount,
                    totalCloudPlaylists = finalPlaylistsCount,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun syncPublicProfileInternal(
        uid: String,
        displayName: String,
        username: String?,
        photoUrl: String?,
        bio: String?,
    ): CloudPublicProfile {
        val isDev = isCurrentUserVerifiedDeveloper()
        val cleanHandle = username?.let(::normalizeUsernameHandle)?.takeIf(::isValidUsernameHandle)
        val model = CloudPublicProfile(
            docId = uid,
            userId = uid,
            displayName = displayName.trim().ifBlank { "SoundLap User" }.take(64),
            username = cleanHandle,
            photoUrl = photoUrl?.trim()?.takeIf { it.isNotEmpty() }?.take(24_000),
            bio = bio?.trim()?.takeIf { it.isNotEmpty() }?.take(180),
            isDeveloper = isDev,
            isPublic = true,
        )
        publicProfilesCollection().document(uid)
            .set(model.toWriteMap(uid, allowDeveloperFlag = isDev))
            .await()
        if (cleanHandle != null) {
            val handleDocRef = usernamesCollection().document(cleanHandle)
            val existingHandle = runCatching { handleDocRef.get().await() }.getOrNull()
            if (existingHandle == null || !existingHandle.exists() || existingHandle.getString("userId") == uid) {
                val handleModel = CloudUsernameHandle(
                    docId = cleanHandle,
                    username = cleanHandle,
                    userId = uid,
                    displayName = model.displayName,
                    photoUrl = model.photoUrl,
                    isDeveloper = isDev,
                    isPublic = true,
                )
                handleDocRef.set(handleModel.toWriteMap(uid, allowDeveloperFlag = isDev)).await()
            }
        }
        return model
    }

    suspend fun syncPublicProfile(
        displayName: String,
        username: String? = null,
        photoUrl: String? = null,
        bio: String? = null,
    ): Result<CloudPublicProfile> = withContext(Dispatchers.IO) {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
        val docRef = publicProfilesCollection().document(uid)
        try {
            val model = syncPublicProfileInternal(uid, displayName, username, photoUrl, bio)
            Result.success(model)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun claimOrUpdateUsername(
        rawHandle: String,
        displayName: String,
        photoUrl: String? = null,
        bio: String? = null,
    ): Result<String> = withContext(Dispatchers.IO) {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
        val handle = normalizeUsernameHandle(rawHandle)
        if (!isValidUsernameHandle(handle)) {
            return@withContext Result.failure(
                IllegalArgumentException("Handle must be 3-24 characters: a-z, 0-9, _"),
            )
        }
        val handleDocRef = usernamesCollection().document(handle)
        try {
            val existingHandleSnap = handleDocRef.get().await()
            val ownerUid = existingHandleSnap.getString("userId")
            if (existingHandleSnap.exists() && ownerUid != null && ownerUid != uid) {
                return@withContext Result.failure(IllegalStateException("Username @$handle is already taken"))
            }

            val userSnap = userDocRef(uid).get().await()
            val previousHandle = userSnap.getString("username")?.let(::normalizeUsernameHandle)
                ?.takeIf(::isValidUsernameHandle)

            val isDev = isCurrentUserVerifiedDeveloper()
            val cleanName = displayName.trim().ifBlank { "SoundLap User" }.take(64)
            val cleanPhoto = photoUrl?.trim()?.takeIf { it.isNotEmpty() }?.take(24_000)

            val handleModel = CloudUsernameHandle(
                docId = handle,
                username = handle,
                userId = uid,
                displayName = cleanName,
                photoUrl = cleanPhoto,
                isDeveloper = isDev,
                isPublic = true,
            )
            handleDocRef.set(handleModel.toWriteMap(uid, allowDeveloperFlag = isDev)).await()

            if (userSnap.exists()) {
                userDocRef(uid).update(
                    mapOf(
                        "username" to handle,
                        "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                    ),
                ).await()
            } else {
                val createdProfile = CloudUserProfile(
                    userId = uid,
                    displayName = cleanName,
                    username = handle,
                    email = auth.currentUser?.email,
                    photoUrl = cleanPhoto,
                    bio = bio,
                )
                userDocRef(uid).set(createdProfile.toCreateMap(uid)).await()
            }

            syncPublicProfileInternal(
                uid = uid,
                displayName = cleanName,
                username = handle,
                photoUrl = cleanPhoto,
                bio = bio ?: userSnap.getString("bio"),
            )

            if (previousHandle != null && previousHandle != handle) {
                runCatching {
                    val prevSnap = usernamesCollection().document(previousHandle).get().await()
                    if (prevSnap.exists() && prevSnap.getString("userId") == uid) {
                        usernamesCollection().document(previousHandle).delete().await()
                    }
                }
            }
            Result.success(handle)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, handleDocRef.path)
            Result.failure(e)
        }
    }

    suspend fun getPublicProfile(userId: String): Result<CloudPublicProfile?> = withContext(Dispatchers.IO) {
        try {
            requireUserId()
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
        val docId = safeFirestoreDocId(userId)
        val docRef = publicProfilesCollection().document(docId)
        try {
            val snap = docRef.get().await()
            val profile = snap.toObject(
                CloudPublicProfile::class.java,
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE,
            )
            Result.success(profile)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun getDeveloperPublicProfile(): Result<CloudPublicProfile?> = withContext(Dispatchers.IO) {
        try {
            requireUserId()
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
        try {
            val devSnap = publicProfilesCollection()
                .whereEqualTo("isDeveloper", true)
                .limit(1)
                .get()
                .await()
            val found = devSnap.toObjects(
                CloudPublicProfile::class.java,
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE,
            ).firstOrNull()
            if (found != null) {
                return@withContext Result.success(found)
            }
            val handleSnap = usernamesCollection().document(DEVELOPER_DEFAULT_HANDLE).get().await()
            if (handleSnap.exists()) {
                val handleObj = handleSnap.toObject(
                    CloudUsernameHandle::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE,
                )
                if (handleObj != null && handleObj.userId.isNotBlank()) {
                    val pub = getPublicProfile(handleObj.userId).getOrNull()
                    if (pub != null) return@withContext Result.success(pub)
                    return@withContext Result.success(
                        CloudPublicProfile(
                            docId = handleObj.userId,
                            userId = handleObj.userId,
                            displayName = handleObj.displayName.ifBlank { "TylerW1nston" },
                            username = handleObj.username,
                            photoUrl = handleObj.photoUrl,
                            isDeveloper = handleObj.isDeveloper,
                            isPublic = true,
                        ),
                    )
                }
            }
            Result.success(null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, "public_profiles")
            Result.failure(e)
        }
    }

    suspend fun searchUsersByUsername(query: String): Result<List<CloudPublicProfile>> =
        withContext(Dispatchers.IO) {
            val myUid = try {
                requireUserId()
            } catch (e: Exception) {
                return@withContext Result.failure(e)
            }
            val cleanQuery = normalizeUsernameHandle(query)
            try {
                val resultsByUid = LinkedHashMap<String, CloudPublicProfile>()
                if (isValidUsernameHandle(cleanQuery)) {
                    val exactSnap = runCatching { usernamesCollection().document(cleanQuery).get().await() }.getOrNull()
                    if (exactSnap != null && exactSnap.exists()) {
                        val handleObj = exactSnap.toObject(
                            CloudUsernameHandle::class.java,
                            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE,
                        )
                        if (handleObj != null && handleObj.userId.isNotBlank()) {
                            val pub = getPublicProfile(handleObj.userId).getOrNull()
                            val profile = pub ?: CloudPublicProfile(
                                docId = handleObj.userId,
                                userId = handleObj.userId,
                                displayName = handleObj.displayName,
                                username = handleObj.username,
                                photoUrl = handleObj.photoUrl,
                                isDeveloper = handleObj.isDeveloper,
                                isPublic = true,
                            )
                            resultsByUid[profile.userId] = profile
                        }
                    }
                }

                val listSnap = publicProfilesCollection()
                    .whereEqualTo("isPublic", true)
                    .limit(100)
                    .get()
                    .await()
                val publicUsers = listSnap.toObjects(
                    CloudPublicProfile::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE,
                )
                for (profile in publicUsers) {
                    val matches = cleanQuery.isBlank() ||
                        (profile.username?.contains(cleanQuery, ignoreCase = true) == true) ||
                        profile.displayName.contains(cleanQuery, ignoreCase = true)
                    if (matches && profile.userId.isNotBlank()) {
                        resultsByUid.putIfAbsent(profile.userId, profile)
                    }
                }

                val sorted = resultsByUid.values
                    .filter { cleanQuery.isNotBlank() || it.userId != myUid }
                    .sortedWith(
                        compareByDescending<CloudPublicProfile> { it.isDeveloper }
                            .thenByDescending { it.username?.equals(cleanQuery, ignoreCase = true) == true }
                            .thenBy { it.displayName.lowercase() },
                    )
                Result.success(sorted)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.LIST, "public_profiles")
                Result.failure(e)
            }
        }

    suspend fun addFriend(friend: CloudPublicProfile): Result<CloudFriend> = withContext(Dispatchers.IO) {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
        val safeFriendUid = safeFirestoreDocId(friend.userId)
        if (safeFriendUid == uid || friend.userId.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Cannot add yourself as friend"))
        }
        val docRef = friendsCollection(uid).document(safeFriendUid)
        val model = CloudFriend(
            docId = safeFriendUid,
            userId = uid,
            friendUid = safeFriendUid,
            username = friend.username?.let(::normalizeUsernameHandle)?.takeIf(::isValidUsernameHandle),
            displayName = friend.displayName.ifBlank { "SoundLap User" },
            photoUrl = friend.photoUrl,
            isDeveloper = friend.isDeveloper,
            addedAtMs = System.currentTimeMillis(),
        )
        try {
            val snap = docRef.get().await()
            if (snap.exists()) {
                docRef.update(model.toUpdateMap()).await()
            } else {
                docRef.set(model.toCreateMap(uid)).await()
            }
            Result.success(model)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun removeFriend(friendUid: String): Result<Unit> = withContext(Dispatchers.IO) {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
        val docRef = friendsCollection(uid).document(safeFirestoreDocId(friendUid))
        try {
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun getFriends(): Result<List<CloudFriend>> = withContext(Dispatchers.IO) {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
        val path = "users/$uid/friends"
        try {
            val snap = friendsCollection(uid).get().await()
            val list = snap.toObjects(
                CloudFriend::class.java,
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE,
            ).sortedWith(compareByDescending<CloudFriend> { it.isDeveloper }.thenBy { it.displayName.lowercase() })
            Result.success(list)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, path)
            Result.failure(e)
        }
    }

    suspend fun upsertArtistSubscription(
        artistName: String,
        avatarUrl: String? = null,
        soundCloudUrl: String? = null,
        spotifyArtistId: String? = null,
        subscribedAtMs: Long = System.currentTimeMillis(),
    ): Result<String> = withContext(Dispatchers.IO) {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
        val cleanName = artistName.trim().take(120)
        if (cleanName.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Blank artist name"))
        }
        val artistKey = artistSubscriptionDocId(cleanName)
        val docRef = artistSubscriptionsCollection(uid).document(artistKey)
        val model = CloudArtistSubscription(
            docId = artistKey,
            userId = uid,
            artistKey = artistKey,
            artistName = cleanName,
            avatarUrl = avatarUrl,
            soundCloudUrl = soundCloudUrl,
            spotifyArtistId = spotifyArtistId,
            subscribedAtMs = subscribedAtMs,
        )
        try {
            val snap = docRef.get().await()
            if (snap.exists()) {
                docRef.update(model.toUpdateMap()).await()
            } else {
                docRef.set(model.toCreateMap(uid)).await()
            }
            Result.success(artistKey)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun removeArtistSubscription(artistKeyOrName: String): Result<Unit> = withContext(Dispatchers.IO) {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
        val key = if (artistKeyOrName.startsWith("art_")) {
            safeFirestoreDocId(artistKeyOrName)
        } else {
            artistSubscriptionDocId(artistKeyOrName)
        }
        val docRef = artistSubscriptionsCollection(uid).document(key)
        try {
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun getArtistSubscriptions(): Result<List<CloudArtistSubscription>> = withContext(Dispatchers.IO) {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
        val path = "users/$uid/artist_subscriptions"
        try {
            val snap = artistSubscriptionsCollection(uid).get().await()
            val list = snap.toObjects(
                CloudArtistSubscription::class.java,
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE,
            ).sortedByDescending { it.subscribedAtMs }
            Result.success(list)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, path)
            Result.failure(e)
        }
    }
}
