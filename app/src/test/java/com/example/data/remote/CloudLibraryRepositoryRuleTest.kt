package com.example.data.remote

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.base.FirestoreEmulatorTestBase
import com.example.data.local.AudioFocusMode
import com.example.data.local.PlaylistEntity
import com.example.data.local.SoundLapaDatabase
import com.example.data.local.SoundLapaSettings
import com.example.data.local.TrackEntity
import com.google.firebase.firestore.FirebaseFirestoreException
import org.junit.Assert.assertFalse
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID

class CloudLibraryRepositoryRuleTest : FirestoreEmulatorTestBase() {

    private fun sampleTrack(id: String, title: String = "Yamakasi", isLiked: Boolean = true): TrackEntity =
        TrackEntity(
            id = id,
            title = title,
            artist = "Miyagi",
            genre = "Hip-Hop",
            durationMs = 210_000L,
            waveformJson = "[]",
            cachedFilePath = null,
            sourceUri = "soundlapa://resolve-full?artist=Miyagi&title=Yamakasi",
            coverArtPath = "https://i.scdn.co/image/cover",
            accentColorHex = 0xFF10B981L,
            secondaryColorHex = 0xFF5CE1E6L,
            isUserUpload = false,
            isCachedOffline = false,
            fileSizeBytes = 0L,
            playCount = 1_500_000,
            likeCount = 42_000,
            isLiked = isLiked,
            description = "Spotify",
            createdAt = 1_700_000_000_000L,
            albumTitle = "YAMAKASI",
        )

    @Test
    fun ownerCanUpsertReadAndObserveSavedTracksAndPlaylists() = runBlocking {
        val uid = signInTestUser(ALICE_EMAIL)
        val repo = CloudLibraryRepository(firestore, auth)

        val profileRes = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.upsertUserProfile(
                displayName = "Alice Listener",
                email = ALICE_EMAIL,
                photoUrl = "https://example.com/alice.jpg",
            )
        }
        assertTrue(profileRes.isSuccess)
        val loadedProfile = withTimeout(DEFAULT_TIMEOUT_MS) { repo.getUserProfile().getOrThrow() }
        assertNotNull(loadedProfile)
        assertEquals(uid, loadedProfile?.userId)

        val trackId = "sp:track:${UUID.randomUUID()}"
        val track = sampleTrack(trackId)
        val upsertTrackRes = withTimeout(DEFAULT_TIMEOUT_MS) { repo.upsertSavedTrack(track) }
        assertTrue(upsertTrackRes.isSuccess)

        val loadedTrack = withTimeout(DEFAULT_TIMEOUT_MS) { repo.getSavedTrack(trackId).getOrThrow() }
        assertNotNull(loadedTrack)
        assertEquals(trackId, loadedTrack?.trackId)
        assertTrue(loadedTrack?.isLiked == true)

        val emittedTracks = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeSavedTracks().first { list -> list.any { it.trackId == trackId } }
        }
        assertTrue(emittedTracks.any { it.trackId == trackId })

        val plId = "pl_${UUID.randomUUID()}"
        val playlist = PlaylistEntity(
            id = plId,
            name = "Evening Chill",
            description = "Saved tracks",
            createdAt = 1_700_000_000_500L,
            coverArtPath = "https://i.scdn.co/image/plcover",
        )
        val itemRes = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.upsertPlaylistItem(playlist, track, position = 0, addedAtMs = 1_700_000_001_000L)
        }
        assertTrue(itemRes.isSuccess)

        val items = withTimeout(DEFAULT_TIMEOUT_MS) { repo.getPlaylistItems(plId).getOrThrow() }
        assertEquals(1, items.size)
        assertEquals(trackId, items.first().trackId)

        // Update existing track preserving createdAt invariant
        val updatedTrackRes = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.upsertSavedTrack(track.copy(title = "Yamakasi (Deluxe)", isLiked = false))
        }
        assertTrue(updatedTrackRes.isSuccess)
    }

    @Test
    fun syncWithLocalDatabaseRestoresCloudTracksAndPlaylistsAfterFreshInstall() = runBlocking {
        signInTestUser(ALICE_EMAIL)
        val repo = CloudLibraryRepository(firestore, auth)
        val trackId = "track_restore_${UUID.randomUUID()}"
        val track = sampleTrack(trackId, title = "Restored Song", isLiked = true)
        val plId = "pl_restore_${UUID.randomUUID()}"
        val playlist = PlaylistEntity(id = plId, name = "Restored Mix", createdAt = 123_456L)

        withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.upsertPlaylistItem(playlist, track, position = 0, addedAtMs = 123_500L).getOrThrow()
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        val freshDb = Room.inMemoryDatabaseBuilder(context, SoundLapaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = freshDb.soundLapaDao()
            val summary = withTimeout(DEFAULT_TIMEOUT_MS) { repo.syncWithLocalDatabase(dao).getOrThrow() }
            assertTrue(summary.restoredTracks >= 1)
            assertTrue(summary.restoredPlaylists >= 1)

            val restoredTracks = dao.getAllTracksOnce()
            assertTrue(restoredTracks.any { it.id == trackId && it.title == "Restored Song" && it.isLiked })
            val restoredPlaylists = dao.getAllPlaylistsOnce()
            assertTrue(restoredPlaylists.any { it.id == plId && it.name == "Restored Mix" })
            val restoredRefs = dao.getAllPlaylistRefsOnce()
            assertTrue(restoredRefs.any { it.playlistId == plId && it.trackId == trackId })
        } finally {
            freshDb.close()
        }
    }

    @Test
    fun crossUserReadAndWriteRejectedWithPermissionDenied() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val aliceRepo = CloudLibraryRepository(firestore, auth)
        val trackId = "track_cross_${UUID.randomUUID()}"
        withTimeout(DEFAULT_TIMEOUT_MS) {
            aliceRepo.upsertSavedTrack(sampleTrack(trackId)).getOrThrow()
        }

        signInTestUser(BOB_EMAIL)
        try {
            withTimeout(DEFAULT_TIMEOUT_MS) {
                firestore.collection("users").document(aliceUid)
                    .collection("tracks").document(safeFirestoreDocId(trackId))
                    .get().await()
            }
            fail("Expected PERMISSION_DENIED when Bob reads Alice's track")
        } catch (e: Exception) {
            val fsError = e.unwrapFirestoreException()
            assertNotNull("Expected FirebaseFirestoreException, got $e", fsError)
            assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, fsError?.code)
        }
    }

    @Test
    fun unauthenticatedObserveTracksRejectedWithPermissionDenied() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        auth.signOut()

        try {
            withTimeout(FLOW_TIMEOUT_MS) {
                firestore.collection("users").document(aliceUid)
                    .collection("tracks")
                    .get().await()
            }
            fail("Expected PERMISSION_DENIED for unauthenticated read")
        } catch (e: Exception) {
            val fsError = e.unwrapFirestoreException()
            assertNotNull("Expected FirebaseFirestoreException, got $e", fsError)
            assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, fsError?.code)
        }
    }

    @Test
    fun userSettingsAndCustomProfileSyncAndPersistAcrossReloads() = runBlocking {
        signInTestUser(ALICE_EMAIL)
        val repo = CloudLibraryRepository(firestore, auth)

        val customSettings = SoundLapaSettings(
            appLanguage = "en",
            darkLiquidGlass = false,
            accentColorArgb = 0xFF3B82F6L,
            smoothTransitionEnabled = false,
            smoothTransitionDurationSec = 7,
            hapticsEnabled = false,
            hapticIntensity = 40,
            autoCacheOnPlay = false,
            dataSaverEnabled = true,
            autoUploadEnabled = true,
            batterySaverEnabled = true,
            audioFocusMode = AudioFocusMode.MIX,
            settingsUpdatedAtMs = 999_999L,
        )

        withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.upsertUserProfile(
                displayName = "Alice Listener",
                email = ALICE_EMAIL,
                photoUrl = "https://example.com/alice.jpg",
                localSettings = customSettings,
            ).getOrThrow()

            repo.updateUserCustomProfile(
                displayName = "Alice DJ",
                bio = "Deep House & Synthwave",
                photoUrl = "data:image/jpeg;base64,ZmFrZUF2YXRhcg==",
                email = ALICE_EMAIL,
            ).getOrThrow()
        }

        val reloaded = withTimeout(DEFAULT_TIMEOUT_MS) { repo.getUserProfile().getOrThrow() }
        assertNotNull(reloaded)
        assertEquals("Alice DJ", reloaded?.displayName)
        assertEquals("Deep House & Synthwave", reloaded?.bio)
        assertEquals("data:image/jpeg;base64,ZmFrZUF2YXRhcg==", reloaded?.photoUrl)

        val parsed = reloaded?.parsedSettings()
        assertNotNull(parsed)
        assertEquals("en", parsed?.appLanguage)
        assertEquals(false, parsed?.darkLiquidGlass)
        assertEquals(0xFF3B82F6L, parsed?.accentColorArgb)
        assertEquals(false, parsed?.hapticsEnabled)
        assertEquals(40, parsed?.hapticIntensity)
        assertEquals(true, parsed?.dataSaverEnabled)
        assertEquals(true, parsed?.batterySaverEnabled)
        assertEquals("MIX", parsed?.audioFocusMode)
    }

    @Test
    fun bugReportsSubmitAndDeveloperFetchBoundary() = runBlocking {
        val uid = signInTestUser(ALICE_EMAIL)
        val repo = CloudLibraryRepository(firestore, auth)
        val reportId = "rep_${UUID.randomUUID().toString().replace("-", "").take(16)}"

        withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.submitBugReport(
                CloudBugReport(
                    reportId = reportId,
                    userId = uid,
                    reporterName = "Alice DJ",
                    reporterAvatarUrl = "data:image/jpeg;base64,ZmFrZQ==",
                    message = "Crossfade glitch at 0:03",
                    appVersion = "3.1",
                    createdAtMs = 1_750_000_000_000L,
                ),
            ).getOrThrow()
        }

        val wrongPassRes = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.fetchDeveloperBugReports("wrongpass")
        }
        assertTrue(wrongPassRes.isFailure)

        val reports = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.fetchDeveloperBugReports(DEVELOPER_REPORTS_PASSWORD).getOrThrow()
        }
        val matched = reports.find { it.reportId == reportId }
        assertNotNull(matched)
        assertEquals("Alice DJ", matched?.reporterName)
        assertEquals("Crossfade glitch at 0:03", matched?.message)

        val wrongDeleteRes = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.deleteDeveloperBugReport(matched!!.docId.ifBlank { reportId }, "wrongpass")
        }
        assertTrue(wrongDeleteRes.isFailure)

        val deleteRes = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.deleteDeveloperBugReport(matched!!.docId.ifBlank { reportId }, DEVELOPER_REPORTS_PASSWORD)
        }
        assertTrue(deleteRes.isSuccess)

        val afterDelete = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.fetchDeveloperBugReports(DEVELOPER_REPORTS_PASSWORD).getOrThrow()
        }
        assertTrue(afterDelete.none { it.reportId == reportId })
    }

    @Test
    fun revokeBlockedAvatarRemovesCloudPhotoAndBlocksFutureUploads() = runBlocking {
        val blockedEmail = "blocked_${UUID.randomUUID().toString().replace("-", "").take(12)}@test.com"
        val uid = signInTestUser(blockedEmail)
        val repo = CloudLibraryRepository(firestore, auth)

        withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.upsertUserProfile(
                displayName = "Blocked Test User",
                email = blockedEmail,
                photoUrl = "data:image/jpeg;base64,ZmFrZUF2YXRhcg==",
            ).getOrThrow()

            repo.revokeBlockedAvatar(email = blockedEmail, displayName = "Blocked Test User").getOrThrow()
        }

        val revoked = withTimeout(DEFAULT_TIMEOUT_MS) { repo.getUserProfile().getOrThrow() }
        assertNotNull(revoked)
        assertTrue(revoked?.avatarBlocked == true)
        assertEquals(null, revoked?.photoUrl)

        // Even if updateUserCustomProfile is called afterwards with a photoUrl, it stays null & blocked
        val afterUpdate = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.updateUserCustomProfile(
                displayName = "Blocked Renamed",
                bio = "New bio",
                photoUrl = "data:image/jpeg;base64,aGFjaw==",
                email = blockedEmail,
            ).getOrThrow()
        }
        assertTrue(afterUpdate.avatarBlocked)
        assertEquals(null, afterUpdate.photoUrl)

        // Raw Firestore update trying to un-block or upload photoUrl is rejected by security rules
        try {
            withTimeout(DEFAULT_TIMEOUT_MS) {
                firestore.collection("users").document(uid)
                    .update(mapOf("avatarBlocked" to false, "photoUrl" to "https://example.com/bad.jpg"))
                    .await()
            }
            fail("Expected PERMISSION_DENIED when attempting to bypass avatarBlocked")
        } catch (e: Exception) {
            val fsError = e.unwrapFirestoreException()
            assertNotNull("Expected FirebaseFirestoreException, got $e", fsError)
            assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, fsError?.code)
        }
    }

    @Test
    fun cloudMetricsSyncAndDeveloperStorageAggregationRespectsAccessPassword() = runBlocking {
        val uid = signInTestUser(ALICE_EMAIL)
        val repo = CloudLibraryRepository(firestore, auth)

        withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.upsertUserProfile(
                displayName = "Alice Cloud",
                email = ALICE_EMAIL,
                photoUrl = "data:image/webp;base64,UklGRg==",
            ).getOrThrow()

            repo.syncCloudMetricsSnapshot(
                storageBytes = 4_250_000L,
                tracksCount = 12,
                playlistsCount = 3,
            ).getOrThrow()
        }

        val denied = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.fetchDeveloperCloudStorageStats("wrong_password")
        }
        assertTrue(denied.isFailure)

        val stats = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.fetchDeveloperCloudStorageStats(DEVELOPER_REPORTS_PASSWORD).getOrThrow()
        }
        assertTrue(stats.isCloudLive)
        assertTrue(stats.activeUsersCount >= 1)
        assertTrue(stats.usedBytes >= 4_250_000L)
        assertTrue(stats.totalTracks >= 12)
        assertTrue(stats.totalPlaylists >= 3)
        assertEquals(DEFAULT_CLOUD_QUOTA_BYTES, stats.quotaBytes)
        assertTrue(uid.isNotBlank())
    }

    @Test
    fun usernameClaimPublicSearchFriendsAndArtistSubscriptionsWorkWithZeroPiiIsolation() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val aliceRepo = CloudLibraryRepository(firestore, auth)
        val suffix = UUID.randomUUID().toString().replace("-", "").take(6).lowercase()
        val aliceHandle = "alice_$suffix"

        withTimeout(DEFAULT_TIMEOUT_MS) {
            aliceRepo.upsertUserProfile(
                displayName = "Alice Cloud",
                email = ALICE_EMAIL,
                photoUrl = "https://example.com/alice.jpg",
            ).getOrThrow()
            val claimed = aliceRepo.claimOrUpdateUsername(
                rawHandle = "@$aliceHandle",
                displayName = "Alice Cloud",
                photoUrl = "https://example.com/alice.jpg",
                bio = "Vinyl hunter",
            ).getOrThrow()
            assertEquals(aliceHandle, claimed)
        }

        val bobUid = signInTestUser(BOB_EMAIL)
        val bobRepo = CloudLibraryRepository(firestore, auth)
        val bobHandle = "bob_$suffix"

        // Bob cannot steal Alice's handle
        val stealRes = withTimeout(DEFAULT_TIMEOUT_MS) {
            bobRepo.claimOrUpdateUsername(
                rawHandle = aliceHandle,
                displayName = "Bob Thief",
            )
        }
        assertTrue(stealRes.isFailure)

        withTimeout(DEFAULT_TIMEOUT_MS) {
            bobRepo.claimOrUpdateUsername(
                rawHandle = bobHandle,
                displayName = "Bob Producer",
            ).getOrThrow()

            val searchResults = bobRepo.searchUsersByUsername(aliceHandle).getOrThrow()
            val foundAlice = searchResults.find { it.userId == aliceUid }
            assertNotNull(foundAlice)
            assertEquals(aliceHandle, foundAlice?.username)
            assertFalse(foundAlice?.isDeveloper ?: true)

            bobRepo.addFriend(foundAlice!!).getOrThrow()
            val friends = bobRepo.getFriends().getOrThrow()
            assertTrue(friends.any { it.friendUid == aliceUid && it.username == aliceHandle })

            bobRepo.upsertArtistSubscription(
                artistName = "Miyagi & Эндшпиль",
                avatarUrl = "https://example.com/miyagi.jpg",
            ).getOrThrow()
            val subs = bobRepo.getArtistSubscriptions().getOrThrow()
            assertTrue(subs.any { it.artistName == "Miyagi & Эндшпиль" })
        }

        // Cross-user read of Bob's friends & subscriptions from Alice fails
        signInTestUser(ALICE_EMAIL)
        try {
            withTimeout(DEFAULT_TIMEOUT_MS) {
                firestore.collection("users").document(bobUid).collection("friends").get().await()
            }
            fail("Expected PERMISSION_DENIED when Alice reads Bob's friends")
        } catch (e: Exception) {
            val fsError = e.unwrapFirestoreException()
            assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, fsError?.code)
        }
    }

    private companion object {
        const val ALICE_EMAIL = "alice_soundlapa@test.com"
        const val BOB_EMAIL = "bob_soundlapa@test.com"
        const val DEFAULT_TIMEOUT_MS = 5000L
        const val FLOW_TIMEOUT_MS = 3000L
    }
}
