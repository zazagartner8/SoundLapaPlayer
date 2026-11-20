package com.example

import com.example.audio.RecommendationEngine
import com.example.data.local.ListeningHistoryEntry
import com.example.data.local.TrackEntity
import com.example.data.local.ExplicitContentDetector
import com.example.data.local.ListeningPlayEvent
import com.example.data.local.ListeningStatsAggregator
import com.example.data.local.ListeningStatsPeriod
import com.example.data.local.PlaylistMetadataHelper
import com.example.data.local.SoundLapUidFormatter
import com.example.data.remote.PlaylistImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit


class RecommendationAndImporterTest {

    private fun track(
        id: String,
        title: String,
        artist: String,
        genre: String,
        playCount: Int = 100,
        isLiked: Boolean = false,
    ) = TrackEntity(
        id = id,
        title = title,
        artist = artist,
        genre = genre,
        durationMs = 180_000L,
        waveformJson = "[]",
        cachedFilePath = null,
        sourceUri = "https://youtube.com/watch?v=$id",
        coverArtPath = null,
        accentColorHex = 0xFFFF5500L,
        secondaryColorHex = 0xFF9C2B00L,
        isUserUpload = false,
        isCachedOffline = false,
        fileSizeBytes = 0L,
        playCount = playCount,
        likeCount = 0,
        isLiked = isLiked,
    )

    @Test
    fun recommendationEnginePrioritizesSimilarArtistAndGenreOverUnrelatedPopularTracks() {
        val playedTrack = track("t1", "Kosandra", "Miyagi", "Хип-хоп", playCount = 500)
        val similarArtistTrack = track("t2", "Minor", "Miyagi", "Хип-хоп", playCount = 300)
        val similarGenreTrack = track("t3", "Династия", "madk1d", "Хип-хоп", playCount = 250)
        val unrelatedMegaHit = track("t4", "Classic Symphony", "Beethoven Orchestra", "Классика", playCount = 900_000)

        val result = RecommendationEngine.computeRecommendations(
            allTracks = listOf(playedTrack, similarArtistTrack, similarGenreTrack, unrelatedMegaHit),
            listeningHistory = listOf(
                ListeningHistoryEntry(
                    trackId = "t1",
                    title = "Kosandra",
                    artist = "Miyagi",
                    genre = "Хип-хоп",
                    playCount = 4,
                    lastPlayedAtMs = 1000L,
                ),
            ),
            searchHistory = listOf("Miyagi"),
            limit = 4,
        )

        assertTrue(result.tracks.isNotEmpty())
        assertEquals("t2", result.tracks.first().id)
        assertTrue(result.tracks.indexOfFirst { it.id == "t3" } < result.tracks.indexOfFirst { it.id == "t4" })
        assertTrue(result.topSeedArtists.contains("Miyagi"))
    }

    @Test
    fun parseSpotifyEmbedHtmlExtractsPlaylistCoverAndTracksInOrder() {
        val fakeEmbedHtml = """
            <html><body>
            <script id="__NEXT_DATA__" type="application/json">
            {"props":{"pageProps":{"state":{"data":{"entity":{
              "name":"Night Drive Mix",
              "coverArt":{"sources":[{"url":"https://i.scdn.co/image/small.jpg"},{"url":"https://i.scdn.co/image/large.jpg"}]},
              "trackList":[
                {"title":"Blinding Lights","subtitle":"The Weeknd","duration":200000},
                {"title":"Midnight City","subtitle":"M83","duration":243000},
                {"title":"Nightcall","subtitle":"Kavinsky","duration":258000}
              ]
            }}}}}}
            </script>
            </body></html>
        """.trimIndent()

        val parsed = PlaylistImporter.parseSpotifyEmbedHtml(fakeEmbedHtml, "Fallback")
        assertEquals("Night Drive Mix", parsed.playlistName)
        assertEquals("Spotify", parsed.sourceLabel)
        assertEquals("https://i.scdn.co/image/large.jpg", parsed.coverArtUrl)
        assertEquals(3, parsed.queries.size)
        assertEquals("Blinding Lights", parsed.queries[0].title)
        assertEquals("The Weeknd", parsed.queries[0].artist)
        assertEquals("Midnight City", parsed.queries[1].title)
        assertEquals("M83", parsed.queries[1].artist)
        assertEquals("Nightcall", parsed.queries[2].title)
        assertEquals("Kavinsky", parsed.queries[2].artist)
    }

    @Test
    fun parseCsvTracklistHandlesQuotedSpotifyExportRows() {
        val csv = """
            Track URI,Track Name,Artist Name(s),Album Name
            spotify:track:1,"Hello, World","Artist One; Artist Two",Demo Album
            spotify:track:2,Starboy,The Weeknd,Starboy
        """.trimIndent()

        val parsed = PlaylistImporter.parseCsvTracklist(csv, "Spotify Export")
        assertEquals(2, parsed.queries.size)
        assertEquals("Hello, World", parsed.queries[0].title)
        assertEquals("Artist One, Artist Two", parsed.queries[0].artist)
        assertEquals("Starboy", parsed.queries[1].title)
        assertEquals("The Weeknd", parsed.queries[1].artist)
    }

    @Test
    fun parseM3uAndPlainTextTracklistsParseArtistAndTitle() {
        val m3u = """
            #EXTM3U
            #PLAYLIST:Chill Mix
            #EXTINF:210,Daft Punk - Instant Crush
            /music/instant_crush.mp3
            #EXTINF:195,Linkin Park — Numb
            /music/numb.mp3
        """.trimIndent()

        val m3uParsed = PlaylistImporter.parseM3uTracklist(m3u, "Fallback")
        assertEquals("Chill Mix", m3uParsed.playlistName)
        assertEquals(2, m3uParsed.queries.size)
        assertEquals("Daft Punk", m3uParsed.queries[0].artist)
        assertEquals("Instant Crush", m3uParsed.queries[0].title)
        assertEquals("Linkin Park", m3uParsed.queries[1].artist)
        assertEquals("Numb", m3uParsed.queries[1].title)

        val plain = """
            1. Miyagi - Kosandra
            2) Eminem — Mockingbird
        """.trimIndent()
        val plainParsed = PlaylistImporter.parsePlainTextTracklist(plain, "Custom")
        assertEquals(2, plainParsed.queries.size)
        assertEquals("Miyagi", plainParsed.queries[0].artist)
        assertEquals("Kosandra", plainParsed.queries[0].title)
        assertEquals("Eminem", plainParsed.queries[1].artist)
        assertEquals("Mockingbird", plainParsed.queries[1].title)
    }

    @Test
    fun toSpotifyEmbedUrlConvertsStandardAndLocalizedUrls() {
        assertEquals(
            "https://open.spotify.com/embed/playlist/37i9dQZF1DXcBWIGoYBM5M",
            PlaylistImporter.toSpotifyEmbedUrl("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=abc"),
        )
        assertEquals(
            "https://open.spotify.com/embed/track/0VjIjW4GlUZAMYd2vXMi3b",
            PlaylistImporter.toSpotifyEmbedUrl("https://open.spotify.com/intl-ru/track/0VjIjW4GlUZAMYd2vXMi3b"),
        )
    }

    @Test
    fun validateAndParseFileContentAcceptsValidPlaylistFormatsAndRejectsInvalidFiles() {
        val validCsv = "Track Name,Artist Name(s),Album Name\nKosandra,Miyagi & Andy Panda,Yamakasi\n"
            .toByteArray()
        val csvParsed = PlaylistImporter.validateAndParseFileContent(
            fileName = "spotify_liked.csv",
            mimeType = "text/csv",
            rawBytes = validCsv,
        )
        assertEquals(1, csvParsed.queries.size)
        assertEquals("Kosandra", csvParsed.queries[0].title)
        assertEquals("Yamakasi", csvParsed.queries[0].albumTitle)

        val validTxt = "1. Miyagi - Kosandra\n2. Daft Punk - Instant Crush\n".toByteArray()
        val txtParsed = PlaylistImporter.validateAndParseFileContent(
            fileName = "tracks.txt",
            mimeType = "text/plain",
            rawBytes = validTxt,
        )
        assertEquals(2, txtParsed.queries.size)

        val validJson = """[{"title":"Starboy","artist":"The Weeknd","album":"Starboy"}]""".toByteArray()
        val jsonParsed = PlaylistImporter.validateAndParseFileContent(
            fileName = "export.json",
            mimeType = "application/json",
            rawBytes = validJson,
        )
        assertEquals(1, jsonParsed.queries.size)

        fun assertRejected(fileName: String, mimeType: String?, bytes: ByteArray) {
            var thrown = false
            try {
                PlaylistImporter.validateAndParseFileContent(fileName, mimeType, bytes)
            } catch (_: IllegalArgumentException) {
                thrown = true
            }
            assertTrue("Expected $fileName ($mimeType) to be rejected", thrown)
        }

        // Disallowed binary/audio/document extensions
        assertRejected("song.mp3", "audio/mpeg", "ID3\u0003\u0000\u0000".toByteArray())
        assertRejected("doc.pdf", "application/pdf", "%PDF-1.7\n...".toByteArray())
        assertRejected("archive.zip", "application/zip", byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00))
        assertRejected("image.png", "image/png", byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A))
        // Empty file
        assertRejected("empty.txt", "text/plain", ByteArray(0))
        // Spoofed binary bytes inside .txt
        assertRejected("fake.txt", "text/plain", byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D, 0x31))
        // Unstructured prose .txt without Artist - Title rows
        assertRejected("notes.txt", "text/plain", "Just some random notes\nNothing like tracks here\nHello world".toByteArray())
        // HTML page saved as .json or .txt
        assertRejected("page.json", "application/json", "<!DOCTYPE html><html><body>Hi</body></html>".toByteArray())
    }

    @Test
    fun buildLazyImportedTrackCreatesDeterministicIdAndOnDemandResolveUri() {
        val query = com.example.data.remote.ImportedTrackQuery(
            title = "Kosandra",
            artist = "Miyagi",
            durationMs = 215_000L,
            albumTitle = "Yamakasi",
            coverArtUrl = "https://example.com/cover.jpg",
        )
        val built = PlaylistImporter.buildLazyImportedTrack(query)!!
        assertEquals(PlaylistImporter.deterministicImportTrackId(query), built.id)
        assertTrue(built.sourceUri!!.startsWith("soundlapa://resolve-full?"))
        assertTrue(built.isAutoImported)
        assertEquals("https://example.com/cover.jpg", built.coverArtPath)
        assertEquals("Yamakasi", built.albumTitle)
    }

    @Test
    fun playlistMetadataHelperSeparatesImportSourceFromUserDescriptionAndHandlesLegacyStrings() {
        val encoded = PlaylistMetadataHelper.encodeImportedDescription("Spotify", "Вечерний вайб")
        val parsed = PlaylistMetadataHelper.parse(encoded)
        assertEquals("Spotify", parsed.importSource)
        assertEquals("Вечерний вайб", parsed.userDescription)

        val legacy = PlaylistMetadataHelper.parse("Импорт (Spotify) • 12 трек(ов) Любимые биты")
        assertEquals("Spotify", legacy.importSource)
        assertEquals("Любимые биты", legacy.userDescription)

        val edited = PlaylistMetadataHelper.mergeUserEdit(encoded, "Новое описание")
        val reparsed = PlaylistMetadataHelper.parse(edited)
        assertEquals("Spotify", reparsed.importSource)
        assertEquals("Новое описание", reparsed.userDescription)
    }

    @Test
    fun explicitContentDetectorIdentifiesProfanityInMetadataAndStrippedLyricsWithoutFalsePositives() {
        assertTrue(ExplicitContentDetector.containsExplicitLanguage("стало похуй"))
        assertTrue(ExplicitContentDetector.containsExplicitLanguage("No More Shit (Explicit)"))
        assertTrue(ExplicitContentDetector.isLyricsExplicit("[00:12.34] Yeah fuck the noise\n[00:16.00] Clean line"))
        assertFalse(ExplicitContentDetector.containsExplicitLanguage("Хорошая добрая песня о лете"))
        assertFalse(ExplicitContentDetector.isLyricsExplicit("[00:10.00] Sunlight over the ocean"))
    }

    @Test
    fun listeningStatsAggregatorComputesWindowsTopEntitiesAndDeterministicUid() {
        val nowMs = 1_730_000_000_000L
        val today = ListeningStatsAggregator.dayEpochFor(nowMs)
        val events = listOf(
            ListeningPlayEvent(
                dayEpoch = today,
                trackId = "t1",
                title = "Kosandra",
                artist = "Miyagi & Andy Panda",
                genre = "Хип-хоп",
                listenedMs = TimeUnit.MINUTES.toMillis(20),
                playCount = 6,
                lastPlayedAtMs = nowMs,
            ),
            ListeningPlayEvent(
                dayEpoch = today - 10,
                trackId = "t2",
                title = "Minor",
                artist = "Miyagi",
                genre = "Хип-хоп",
                listenedMs = TimeUnit.MINUTES.toMillis(15),
                playCount = 4,
                lastPlayedAtMs = nowMs - TimeUnit.DAYS.toMillis(10),
            ),
            ListeningPlayEvent(
                dayEpoch = today - 45,
                trackId = "t3",
                title = "Blinding Lights",
                artist = "The Weeknd",
                genre = "Pop",
                listenedMs = TimeUnit.MINUTES.toMillis(50),
                playCount = 14,
                lastPlayedAtMs = nowMs - TimeUnit.DAYS.toMillis(45),
            ),
        )

        val week = ListeningStatsAggregator.compute(events, ListeningStatsPeriod.WEEK, nowMs)
        assertEquals(20, week.totalMinutes)
        assertEquals("Miyagi", week.favoriteArtist?.artistName)
        assertEquals("Kosandra", week.favoriteTrack?.title)

        val month = ListeningStatsAggregator.compute(events, ListeningStatsPeriod.MONTH, nowMs)
        assertEquals(35, month.totalMinutes)
        assertEquals("Miyagi", month.favoriteArtist?.artistName)

        val year = ListeningStatsAggregator.compute(events, ListeningStatsPeriod.YEAR, nowMs)
        assertEquals(85, year.totalMinutes)
        assertEquals("The Weeknd", year.favoriteArtist?.artistName)
        assertEquals("Blinding Lights", year.favoriteTrack?.title)

        val merged = ListeningStatsAggregator.mergeEvents(
            local = listOf(events.first()),
            remote = listOf(events.first().copy(listenedMs = TimeUnit.MINUTES.toMillis(30), playCount = 9)),
            nowMs = nowMs,
        )
        assertEquals(TimeUnit.MINUTES.toMillis(30), merged.first { it.trackId == "t1" }.listenedMs)

        val uid1 = SoundLapUidFormatter.format("firebase_uid_abc")
        val uid2 = SoundLapUidFormatter.format("firebase_uid_abc")
        val uid3 = SoundLapUidFormatter.format("firebase_uid_xyz")
        assertEquals(uid1, uid2)
        assertNotEquals(uid1, uid3)
        assertTrue(uid1.matches(Regex("^SL-[0-9A-F]{4}-[0-9A-F]{4}$")))
        assertEquals("SL-GUEST", SoundLapUidFormatter.format(null))
    }

    @Test
    fun recommendationsAndForYouAvoidDuplicatesAndRespectLanguageAffinity() {
        val nowMs = System.currentTimeMillis()
        val ruPlayed = track("r1", "Ночной рейс", "Куроков", "Хип-хоп", playCount = 150)
        val ruFresh1 = track("r2", "Стало проще", "Куроков", "Хип-хоп", playCount = 120)
        val ruFresh2 = track("r3", "Среди огней", "Скриптонит", "Хип-хоп", playCount = 300)
        val enPop = track("e1", "Neon Skies", "Random Pop Star", "Pop", playCount = 500)

        val events = listOf(
            ListeningPlayEvent(
                dayEpoch = ListeningStatsAggregator.dayEpochFor(nowMs),
                trackId = "r1",
                title = "Ночной рейс",
                artist = "Куроков",
                genre = "Хип-хоп",
                listenedMs = TimeUnit.MINUTES.toMillis(25),
                playCount = 5,
                lastPlayedAtMs = nowMs,
            ),
        )
        val history = listOf(
            ListeningHistoryEntry(
                trackId = "r1",
                title = "Ночной рейс",
                artist = "Куроков",
                genre = "Хип-хоп",
                playCount = 5,
                lastPlayedAtMs = nowMs,
            ),
        )

        val recs = RecommendationEngine.computeRecommendations(
            allTracks = listOf(ruPlayed, ruFresh1, ruFresh2, enPop),
            listeningHistory = history,
            listeningEvents = events,
            searchHistory = listOf("Куроков"),
            limit = 2,
        )
        val recIds = recs.tracks.map { it.id }.toSet()
        assertFalse(recIds.contains("e1"))

        val forYou = RecommendationEngine.computeForYouTracks(
            allTracks = listOf(ruPlayed, ruFresh1, ruFresh2, enPop),
            listeningHistory = history,
            listeningEvents = events,
            searchHistory = listOf("Куроков"),
            excludeIds = recIds,
            limit = 2,
        )
        assertTrue(forYou.none { it.id in recIds })
    }

    @Test
    fun recommendationsAndForYouExcludeUnrequestedGoosebumpsAndPassionfruit() {
        val nowMs = System.currentTimeMillis()
        val ru1 = track("ru1", "Минор", "Miyagi & Эндшпиль", "Хип-хоп", playCount = 50_000)
        val ru2 = track("ru2", "Косандра", "Miyagi & Эндшпиль", "Хип-хоп", playCount = 40_000)
        val ru3 = track("ru3", "Там ревели горы", "Miyagi & Эндшпиль", "Хип-хоп", playCount = 35_000)
        val ru4 = track("ru4", "Отпускай", "Три дня дождя", "Рок", playCount = 45_000)
        val goosebumps = track("sp_g", "goosebumps", "Travis Scott", "Хип-хоп", playCount = 2_000_000_000)
        val passionfruit = track("sp_p", "Passionfruit", "Drake", "Поп", playCount = 1_800_000_000)

        val history = listOf(
            ListeningHistoryEntry(
                trackId = "ru1",
                title = "Минор",
                artist = "Miyagi & Эндшпиль",
                genre = "Хип-хоп",
                playCount = 3,
                lastPlayedAtMs = nowMs,
            ),
        )

        val allTracks = listOf(ru1, ru2, ru3, ru4, goosebumps, passionfruit)
        val recs = RecommendationEngine.computeRecommendations(
            allTracks = allTracks,
            listeningHistory = history,
            limit = 6,
            appLanguage = "ru",
        )
        assertTrue(recs.tracks.none { it.title.equals("goosebumps", ignoreCase = true) })
        assertTrue(recs.tracks.none { it.title.equals("passionfruit", ignoreCase = true) })

        val forYou = RecommendationEngine.computeForYouTracks(
            allTracks = allTracks,
            listeningHistory = history,
            limit = 6,
            appLanguage = "ru",
        )
        assertTrue(forYou.none { it.title.equals("goosebumps", ignoreCase = true) })
        assertTrue(forYou.none { it.title.equals("passionfruit", ignoreCase = true) })
    }

    @Test
    fun coldStartRecommendationsFollowAppLanguageFreshHits() {
        val ruHit = track("ru_hit", "По барам", "ANNA ASTI", "Поп", playCount = 120_000)
        val enHit = track("en_hit", "Espresso", "Sabrina Carpenter", "Pop", playCount = 900_000)
        val goosebumps = track("sp_g", "goosebumps", "Travis Scott", "Хип-хоп", playCount = 2_000_000_000)

        val ruRecs = RecommendationEngine.computeRecommendations(
            allTracks = listOf(ruHit, enHit, goosebumps),
            listeningHistory = emptyList(),
            limit = 2,
            appLanguage = "ru",
        )
        assertEquals("ru_hit", ruRecs.tracks.first().id)
        assertTrue(ruRecs.tracks.none { it.id == "sp_g" })

        val enRecs = RecommendationEngine.computeRecommendations(
            allTracks = listOf(ruHit, enHit, goosebumps),
            listeningHistory = emptyList(),
            limit = 2,
            appLanguage = "en",
        )
        assertEquals("en_hit", enRecs.tracks.first().id)
        assertTrue(enRecs.tracks.none { it.id == "sp_g" })

        val ruDefaults = com.example.data.remote.SpotifyCatalogClient.defaultTrendingArtistsForLanguage("ru")
        val enDefaults = com.example.data.remote.SpotifyCatalogClient.defaultTrendingArtistsForLanguage("en")
        assertTrue(ruDefaults.none { it.equals("Travis Scott", ignoreCase = true) || it.equals("Drake", ignoreCase = true) })
        assertTrue(enDefaults.none { it.equals("Travis Scott", ignoreCase = true) || it.equals("Drake", ignoreCase = true) })
        assertNotEquals(ruDefaults, enDefaults)
    }

    @Test
    fun developerPresenceKeepsBackgroundPlaybackAsPlayingAndRespectsExplicitPause() {
        val nowMs = 1_750_000_000_000L

        // Background playback: window minimized (isOnline = false), fresh active track heartbeat
        val bgPlayingProfile = com.example.data.remote.CloudUserProfile(
            userId = "u1",
            displayName = "Listener",
            isOnline = false,
            lastActiveAtMs = nowMs - 30_000L,
            currentTrackId = "t1",
            currentTrackTitle = "Kosandra",
            currentTrackArtist = "Miyagi",
            currentTrackIsPlaying = true,
            currentTrackUpdatedAtMs = nowMs - 25_000L,
        )
        val (bgOnline, bgPlaying) = com.example.data.remote.CloudLibraryRepository
            .resolveDeveloperPresenceStatus(bgPlayingProfile, nowMs)
        assertTrue(bgOnline)
        assertTrue(bgPlaying)

        // Explicitly paused in foreground
        val pausedProfile = bgPlayingProfile.copy(
            isOnline = true,
            currentTrackIsPlaying = false,
        )
        val (pausedOnline, pausedPlaying) = com.example.data.remote.CloudLibraryRepository
            .resolveDeveloperPresenceStatus(pausedProfile, nowMs)
        assertTrue(pausedOnline)
        assertFalse(pausedPlaying)

        // Stale crashed playback older than 5 minutes expires cleanly
        val staleProfile = bgPlayingProfile.copy(
            lastActiveAtMs = nowMs - 10L * 60_000L,
            currentTrackUpdatedAtMs = nowMs - 10L * 60_000L,
        )
        val (staleOnline, stalePlaying) = com.example.data.remote.CloudLibraryRepository
            .resolveDeveloperPresenceStatus(staleProfile, nowMs)
        assertFalse(staleOnline)
        assertFalse(stalePlaying)
    }

    @Test
    fun startupProgressStateCalculatesCleanPercentageBounds() {
        val init = com.example.ui.components.StartupProgressState(
            progress = 0.14f,
            stage = com.example.ui.components.StartupBootStage.INITIALIZING,
        )
        val ready = com.example.ui.components.StartupProgressState(
            progress = 1.25f,
            stage = com.example.ui.components.StartupBootStage.READY,
        )
        assertEquals(14, init.percentInt)
        assertEquals(100, ready.percentInt)
    }

    @Test
    fun goosebumpsAndPassionfruitAreUnconditionallyExcludedFromRecommendationsForYouAndWave() {
        val nowMs = System.currentTimeMillis()
        val goosebumps = track("sp_g", "goosebumps (feat. Kendrick Lamar)", "Travis Scott", "Хип-хоп", playCount = 9_999_999, isLiked = true)
        val passionfruit = track("sp_p", "Passionfruit", "Drake", "Поп", playCount = 8_888_888, isLiked = true)
        val clean1 = track("c1", "Minor", "Miyagi & Эндшпиль", "Хип-хоп", playCount = 10_000)
        val clean2 = track("c2", "Kosandra", "Miyagi & Эндшпиль", "Хип-хоп", playCount = 12_000)

        assertTrue(RecommendationEngine.isBlockedRecommendationTrack(goosebumps))
        assertTrue(RecommendationEngine.isBlockedRecommendationTrack(passionfruit))
        assertFalse(RecommendationEngine.isBlockedRecommendationTrack(clean1))

        val poisonedHistory = listOf(
            ListeningHistoryEntry("sp_g", "goosebumps", "Travis Scott", "Хип-хоп", playCount = 20, lastPlayedAtMs = nowMs),
            ListeningHistoryEntry("sp_p", "Passionfruit", "Drake", "Поп", playCount = 20, lastPlayedAtMs = nowMs),
        )
        val pool = listOf(goosebumps, passionfruit, clean1, clean2)

        val recs = RecommendationEngine.computeRecommendations(
            allTracks = pool,
            listeningHistory = poisonedHistory,
            searchHistory = listOf("Travis Scott", "Drake"),
            limit = 10,
            appLanguage = "ru",
        )
        assertTrue(recs.tracks.none { it.id == "sp_g" || it.id == "sp_p" })

        val forYou = RecommendationEngine.computeForYouTracks(
            allTracks = pool,
            listeningHistory = poisonedHistory,
            searchHistory = listOf("Travis Scott", "Drake"),
            limit = 10,
            appLanguage = "ru",
        )
        assertTrue(forYou.none { it.id == "sp_g" || it.id == "sp_p" })

        val wave = RecommendationEngine.computeWaveTracks(
            allTracks = pool,
            listeningHistory = poisonedHistory,
            config = com.example.data.local.WaveFilterConfig(),
            limit = 10,
        )
        assertTrue(wave.none { it.id == "sp_g" || it.id == "sp_p" })

        val trending = RecommendationEngine.computeTrendingSimilarTracks(
            allTracks = pool,
            listeningHistory = poisonedHistory,
            limit = 10,
        )
        assertTrue(trending.none { it.id == "sp_g" || it.id == "sp_p" })
    }

    @Test
    fun adminRoleAccountUidHelpersRecognizeDeveloperAndModeratorUids() {
        assertTrue(com.example.ui.auth.isVerifiedDeveloperAccountUid("SL-D1E6-EE60"))
        assertTrue(com.example.ui.auth.isVerifiedDeveloperAccountUid("sl-d1e6-ee60"))
        assertFalse(com.example.ui.auth.isVerifiedDeveloperAccountUid("SL-C526-964C"))
        assertFalse(com.example.ui.auth.isVerifiedDeveloperAccountUid("SL-6721-BEED"))
        assertFalse(com.example.ui.auth.isVerifiedDeveloperAccountUid("SL-0000-0000"))

        assertTrue(com.example.ui.auth.isVerifiedModeratorAccountUid("SL-C526-964C"))
        assertTrue(com.example.ui.auth.isVerifiedModeratorAccountUid("sl-c526-964c"))
        assertTrue(com.example.ui.auth.isVerifiedModeratorAccountUid("SL-6721-BEED"))
        assertTrue(com.example.ui.auth.isVerifiedModeratorAccountUid("sl-6721-beed"))
        assertFalse(com.example.ui.auth.isVerifiedModeratorAccountUid("SL-D1E6-EE60"))
        assertFalse(com.example.ui.auth.isVerifiedModeratorAccountUid(null))

        // Regular accounts are denied both split biometric scopes
        assertFalse(
            com.example.ui.auth.isAuthorizedForBiometricScope(
                scope = com.example.ui.auth.AdminBiometricScope.DEVELOPER_ONLY,
                firebaseUid = "regular_user_uid",
                email = "user@example.com",
            ),
        )
        assertFalse(
            com.example.ui.auth.isAuthorizedForBiometricScope(
                scope = com.example.ui.auth.AdminBiometricScope.MODERATOR_OR_DEVELOPER,
                firebaseUid = "regular_user_uid",
                email = "user@example.com",
            ),
        )
    }

    @Test
    fun playlistImportLimitIs500AndLongCoverUrlsSurviveCloudSanitization() {
        assertEquals(500, PlaylistImporter.MAX_IMPORT_TRACKS)
        val longCoverUrl = "https://mosaic.scdn.co/640/" + "a".repeat(420)
        val localPlaylist = com.example.data.local.PlaylistEntity(
            id = "pl_long_cover",
            name = "Imported Mix",
            description = "Test",
            createdAt = 1_000L,
            coverArtPath = longCoverUrl,
        )
        val synced = com.example.data.remote.CloudUserPlaylist.fromPlaylistEntity(
            userId = "user_1",
            playlist = localPlaylist,
        )
        assertEquals(longCoverUrl, synced.coverArtPath)

        // Missing local file path should NOT shadow valid cloud cover URL on restore
        val staleLocal = localPlaylist.copy(coverArtPath = "/nonexistent/path/missing_cover.jpg")
        val restored = synced.toLocalPlaylistEntity(existing = staleLocal)
        assertEquals(longCoverUrl, restored.coverArtPath)
    }
}



