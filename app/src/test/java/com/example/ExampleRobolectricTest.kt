package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.audio.RecommendationEngine
import com.example.data.local.ListeningHistoryEntry
import com.example.data.local.PlaylistEntity
import com.example.data.local.PlaylistTrackCrossRef
import com.example.data.local.SoundLapaDatabase
import com.example.data.local.SoundLapaSettings
import com.example.data.local.TrackEntity
import com.example.data.remote.AudiusRepository
import com.example.data.remote.FullStreamResolver
import com.example.data.remote.SpotifyCatalogClient
import com.example.data.remote.isTrackPlayableOffline
import androidx.compose.ui.text.font.FontWeight
import com.example.ui.HomeRoute
import com.example.ui.i18n.stringsForLanguage
import com.example.ui.theme.ComfortaaBoldFamily
import com.example.ui.theme.SoundLapaTypography
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("SoundLapa", appName)
  }

  @Test
  fun `default theme accent is purple and v3_1 strings are configured`() {
    assertEquals(0xFF8B5CF6L, SoundLapaSettings().accentColorArgb)
    assertFalse(SoundLapaSettings().isGuestMode)
    assertEquals("3.1", BuildConfig.VERSION_NAME)
    assertNotNull(HomeRoute)

    val ru = stringsForLanguage("ru")
    assertEquals("Недавно прослушанные", ru.quickPicks)
    assertEquals("Лайкнутые", ru.favoritesTab)
    assertEquals("Загруженные треки", ru.myTracksTab)
    assertEquals("Сингл", ru.singleReleaseLabel)
    assertTrue(ru.aboutWhatsNewTitle.contains("3.1"))
    assertTrue(stringsForLanguage("en").aboutWhatsNewTitle.contains("3.1"))
    assertTrue(stringsForLanguage("it").aboutWhatsNewTitle.contains("3.1"))
    assertTrue(ru.confirmDeleteTitle.isNotBlank())
    assertTrue(ru.deleteConfirmAction.isNotBlank())
    assertTrue(ru.artistSearchProfileSubtitle.isNotBlank())

    assertTrue(ru.aboutWhatsNewItems.isNotEmpty())
    assertTrue(stringsForLanguage("en").aboutWhatsNewItems.isNotEmpty())
    assertTrue(stringsForLanguage("it").aboutWhatsNewItems.isNotEmpty())
  }

  @Test
  fun `playlist entity persists coverArtPath and preserves ordered track positions`() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = Room.inMemoryDatabaseBuilder(context, SoundLapaDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    try {
      val dao = db.soundLapaDao()
      val tracks = listOf(
        TrackEntity(
          id = "t_first",
          title = "First Song",
          artist = "Artist A",
          genre = "Pop",
          durationMs = 180_000L,
          waveformJson = "[]",
          cachedFilePath = null,
          sourceUri = null,
          coverArtPath = null,
          accentColorHex = 0xFF10B981L,
          secondaryColorHex = 0xFF065F46L,
          isUserUpload = false,
          isCachedOffline = false,
          fileSizeBytes = 0L,
        ),
        TrackEntity(
          id = "t_second",
          title = "Second Song",
          artist = "Artist A",
          genre = "Pop",
          durationMs = 190_000L,
          waveformJson = "[]",
          cachedFilePath = null,
          sourceUri = null,
          coverArtPath = null,
          accentColorHex = 0xFF10B981L,
          secondaryColorHex = 0xFF065F46L,
          isUserUpload = false,
          isCachedOffline = false,
          fileSizeBytes = 0L,
        ),
      )
      dao.upsertTracks(tracks)
      dao.upsertPlaylist(
        PlaylistEntity(
          id = "pl_1",
          name = "Ordered Mix",
          description = "Spotify",
          coverArtPath = "https://i.scdn.co/image/cover123",
          createdAt = 1000L,
        ),
      )
      // Insert in reverse ID order but explicit position order 0, 1
      dao.addTrackToPlaylist(
        PlaylistTrackCrossRef(playlistId = "pl_1", trackId = "t_second", position = 1, addedAt = 1001L),
      )
      dao.addTrackToPlaylist(
        PlaylistTrackCrossRef(playlistId = "pl_1", trackId = "t_first", position = 0, addedAt = 1000L),
      )

      val playlists = dao.observeAllPlaylists().first()
      assertEquals(1, playlists.size)
      assertEquals("https://i.scdn.co/image/cover123", playlists.first().coverArtPath)

      val refs = dao.observeOrderedPlaylistRefs().first()
      assertEquals(listOf("t_first", "t_second"), refs.map { it.trackId })
      assertEquals(1, dao.getMaxPlaylistTrackPosition("pl_1"))
    } finally {
      db.close()
    }
  }

  @Test
  fun `strict artist track matcher accepts target artist and rejects unrelated artists`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = Room.inMemoryDatabaseBuilder(context, SoundLapaDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    try {
      val repo = AudiusRepository(context, db.soundLapaDao())
      val ownTrack = TrackEntity(
        id = "o1",
        title = "Yamakasi",
        artist = "Miyagi & Andy Panda",
        genre = "Hip-Hop",
        durationMs = 200_000L,
        waveformJson = "[]",
        cachedFilePath = null,
        sourceUri = null,
        coverArtPath = null,
        accentColorHex = 0xFF10B981L,
        secondaryColorHex = 0xFF065F46L,
        isUserUpload = false,
        isCachedOffline = false,
        fileSizeBytes = 0L,
      )
      val fanReupload = ownTrack.copy(id = "o2", artist = "Miyagi Type Beat Producer")
      val wrongArtist = ownTrack.copy(id = "o3", artist = "Random Cover Band")

      assertTrue(repo.isTrackByArtist(ownTrack, listOf("Miyagi")))
      assertFalse(repo.isTrackByArtist(fanReupload, listOf("Miyagi")))
      assertFalse(repo.isTrackByArtist(wrongArtist, listOf("Miyagi")))
    } finally {
      db.close()
    }
  }

  @Test
  fun `app typography uses Comfortaa Bold 700 with Cyrillic and Latin support across all text styles`() {
    val styles = listOf(
      SoundLapaTypography.displayLarge,
      SoundLapaTypography.displayMedium,
      SoundLapaTypography.displaySmall,
      SoundLapaTypography.headlineLarge,
      SoundLapaTypography.headlineMedium,
      SoundLapaTypography.headlineSmall,
      SoundLapaTypography.titleLarge,
      SoundLapaTypography.titleMedium,
      SoundLapaTypography.titleSmall,
      SoundLapaTypography.bodyLarge,
      SoundLapaTypography.bodyMedium,
      SoundLapaTypography.bodySmall,
      SoundLapaTypography.labelLarge,
      SoundLapaTypography.labelMedium,
      SoundLapaTypography.labelSmall,
    )
    styles.forEach { style ->
      assertEquals(ComfortaaBoldFamily, style.fontFamily)
      assertEquals(FontWeight.Bold, style.fontWeight)
      assertEquals(700, style.fontWeight?.weight)
    }
  }

  @Test
  fun `spotify SSR initialState and embed artist parsers extract tracks and albums`() {
    val initialJson = """
      {"entities":{"items":{"spotify:artist:abc123":{
        "profile":{"name":"MiyaGi"},
        "visuals":{"avatarImage":{"sources":[{"width":640,"url":"https://i.scdn.co/image/avatar640"}]}},
        "stats":{"followers":3021020},
        "discography":{
          "topTracks":{"items":[{"track":{
            "uri":"spotify:track:t999",
            "name":"Bismarck",
            "playcount":"57223089",
            "artists":{"items":[{"profile":{"name":"MiyaGi"}},{"profile":{"name":"TumaniYO"}}]},
            "albumOfTrack":{"name":"Buster Keaton","coverArt":{"sources":[{"width":640,"url":"https://i.scdn.co/image/alb640"}]}}
          }}]},
          "popularReleasesAlbums":{"items":[
            {"uri":"spotify:album:alb1","name":"Buster Keaton","coverArt":{"sources":[{"width":640,"url":"https://i.scdn.co/image/alb640"}]}}
          ]}
        }
      }}}}
    """.trimIndent()
    val encoded = android.util.Base64.encodeToString(initialJson.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
    val fakeSsrHtml = """<html><body><script id="initialState">$encoded</script></body></html>"""

    val parsedSsr = SpotifyCatalogClient.parseArtistInitialState(fakeSsrHtml, "abc123")
    assertNotNull(parsedSsr)
    assertEquals("MiyaGi", parsedSsr!!.info.name)
    assertEquals("https://i.scdn.co/image/avatar640", parsedSsr.info.avatarUrl)
    assertEquals(1, parsedSsr.topTracks.size)
    assertEquals("Bismarck", parsedSsr.topTracks[0].title)
    assertEquals("MiyaGi, TumaniYO", parsedSsr.topTracks[0].artist)
    assertEquals("Buster Keaton", parsedSsr.topTracks[0].albumTitle)
    assertEquals(1, parsedSsr.albums.size)
    assertEquals("https://open.spotify.com/album/alb1", parsedSsr.albums[0].sourceUrl)

    val fakeEmbedHtml = """
      <html><body><script id="__NEXT_DATA__" type="application/json">
      {"props":{"pageProps":{"state":{"data":{"entity":{
        "name":"MiyaGi",
        "trackList":[
          {"uri":"spotify:track:t111","title":"Captain","subtitle":"MiyaGi","duration":215000}
        ]
      }}}}}}
      </script></body></html>
    """.trimIndent()
    val embedTracks = SpotifyCatalogClient.parseArtistEmbedTracks(fakeEmbedHtml, "MiyaGi", "https://i.scdn.co/image/avatar640")
    assertEquals(1, embedTracks.size)
    assertEquals("Captain", embedTracks[0].title)
    assertEquals(215_000L, embedTracks[0].durationMs)
    assertTrue(embedTracks[0].sourceUri?.startsWith("soundlapa://resolve-full") == true)
  }

  @Test
  fun `soundcloud api track parser maps tracks and filters out hour-long sets`() {
    val json = """
      [
        {
          "id": 1001,
          "title": "Fabula",
          "permalink_url": "https://soundcloud.com/miyagi-andy-panda/fabula",
          "duration": 195000,
          "streamable": true,
          "artwork_url": "https://i1.sndcdn.com/artworks-abc-large.jpg",
          "playback_count": 450000,
          "likes_count": 12000,
          "genre": "Hip-hop",
          "user": {"username": "Miyagi & Эндшпиль"}
        },
        {
          "id": 1002,
          "title": "Burning Man 1 Hour DJ Set",
          "permalink_url": "https://soundcloud.com/miyagi/set",
          "duration": 3600000,
          "streamable": true,
          "user": {"username": "DJ Miyagi"}
        }
      ]
    """.trimIndent()
    val arr = Json.parseToJsonElement(json).jsonArray
    val mapped = FullStreamResolver.parseSoundCloudApiTracks(arr, "Miyagi", null)
    assertEquals(1, mapped.size)
    assertEquals("Fabula", mapped[0].title)
    assertEquals("https://soundcloud.com/miyagi-andy-panda/fabula", mapped[0].sourceUri)
    assertEquals("https://i1.sndcdn.com/artworks-abc-t500x500.jpg", mapped[0].coverArtPath)
  }

  @Test
  fun `offline track detection distinguishes cached and uploaded tracks from remote streams`() {
    val tempAudio = java.io.File.createTempFile("offline_track_", ".mp3").apply {
      writeBytes(byteArrayOf(1, 2, 3))
      deleteOnExit()
    }
    val base = TrackEntity(
      id = "t_base",
      title = "Track",
      artist = "Artist",
      genre = "Pop",
      durationMs = 180_000L,
      waveformJson = "[]",
      cachedFilePath = null,
      sourceUri = "https://example.com/stream.mp3",
      coverArtPath = null,
      accentColorHex = 0xFF10B981L,
      secondaryColorHex = 0xFF065F46L,
      isUserUpload = false,
      isCachedOffline = false,
      fileSizeBytes = 0L,
    )
    assertFalse(isTrackPlayableOffline(base))
    assertTrue(isTrackPlayableOffline(base.copy(cachedFilePath = tempAudio.absolutePath, isCachedOffline = true)))
    assertTrue(isTrackPlayableOffline(base.copy(sourceUri = "content://media/external/audio/123", isUserUpload = true)))

    listOf("ru", "en", "it").forEach { lang ->
      val s = stringsForLanguage(lang)
      assertTrue(s.offlineErrorTitle.isNotBlank())
      assertTrue(s.offlineGoToDownloadedAction.isNotBlank())
      assertTrue(s.offlineErrorWithDownloadsMessage(2).contains("2"))
      assertTrue(s.settingsImportPublicNotice.isNotBlank())
      assertTrue(s.networkReconnectedMessage.isNotBlank())
      assertTrue(s.playlistShuffleLabel.isNotBlank())
      assertTrue(s.playlistTotalDurationMinutes(12).contains("12"))
    }
  }

  @Test
  fun `trending similar tracks prioritize high-play tracks matching listening history`() {
    fun track(
      id: String,
      artist: String,
      genre: String,
      playCount: Int,
      likeCount: Int,
      title: String = "Song $id",
    ) = TrackEntity(
      id = id,
      title = title,
      artist = artist,
      genre = genre,
      durationMs = 180_000L,
      waveformJson = "[]",
      cachedFilePath = null,
      sourceUri = "https://example.com/$id.mp3",
      coverArtPath = null,
      accentColorHex = 0xFF10B981L,
      secondaryColorHex = 0xFF065F46L,
      isUserUpload = false,
      isCachedOffline = false,
      fileSizeBytes = 0L,
      playCount = playCount,
      likeCount = likeCount,
    )

    val tracks = listOf(
      track("pop_low_match", "Miyagi", "Hip-Hop", playCount = 50, likeCount = 5, title = "Demo"),
      track("hiphop_hit_1", "Miyagi", "Hip-Hop", playCount = 950_000, likeCount = 35_000, title = "Yamakasi"),
      track("hiphop_hit_2", "Miyagi", "Hip-Hop", playCount = 720_000, likeCount = 24_000, title = "Minor"),
      track("hiphop_hit_3", "Miyagi", "Hip-Hop", playCount = 650_000, likeCount = 21_000, title = "Captain"),
      track("hiphop_other", "Andy Panda", "Hip-Hop", playCount = 610_000, likeCount = 19_000, title = "Kosandra"),
      track("unrelated_hit", "Classical Orchestra", "Classical", playCount = 500_000, likeCount = 12_000, title = "Symphony"),
      track("ambient_hit", "Lo-Fi Radio", "Ambient", playCount = 120_000, likeCount = 4_000, title = "Rain"),
    )
    val history = listOf(
      ListeningHistoryEntry(
        trackId = "seed_1",
        title = "Yamakasi",
        artist = "Miyagi",
        genre = "Hip-Hop",
        playCount = 6,
        lastPlayedAtMs = 10_000L,
      ),
    )

    val trending = RecommendationEngine.computeTrendingSimilarTracks(
      allTracks = tracks,
      listeningHistory = history,
      searchHistory = listOf("Miyagi Hip-Hop"),
      limit = 4,
    )

    assertEquals(4, trending.size)
    assertTrue(trending.zipWithNext().all { (a, b) -> a.playCount >= b.playCount })
    // High-play matching Hip-Hop tracks rank above low-play match and unrelated genres,
    // while enforcing max 2 tracks per artist in primary pass.
    assertTrue(trending.take(3).any { it.id == "hiphop_hit_1" || it.id == "hiphop_hit_2" })
    assertTrue(trending.any { it.id == "hiphop_other" })
    val miyagiCount = trending.count { it.artist == "Miyagi" }
    assertTrue(miyagiCount <= 2)
    assertFalse(trending.take(3).any { it.id == "pop_low_match" })
  }

  @Test
  fun `upsertTrack and upsertPlaylist preserve existing playlist track cross refs`() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = Room.inMemoryDatabaseBuilder(context, SoundLapaDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    try {
      val dao = db.soundLapaDao()
      val originalTrack = TrackEntity(
        id = "t_keep",
        title = "Minor",
        artist = "Miyagi & Andy Panda",
        genre = "Hip-Hop",
        durationMs = 175_000L,
        waveformJson = "[]",
        cachedFilePath = null,
        sourceUri = "https://example.com/minor.mp3",
        coverArtPath = "https://i.scdn.co/image/minor",
        accentColorHex = 0xFF10B981L,
        secondaryColorHex = 0xFF065F46L,
        isUserUpload = false,
        isCachedOffline = false,
        fileSizeBytes = 0L,
        isLiked = true,
      )
      val playlist = PlaylistEntity(
        id = "pl_persist",
        name = "Saved Mix",
        description = "Cloud",
        coverArtPath = "https://i.scdn.co/image/cover",
        createdAt = 5000L,
      )
      dao.upsertTrack(originalTrack)
      dao.upsertPlaylist(playlist)
      dao.addTrackToPlaylist(
        PlaylistTrackCrossRef(playlistId = "pl_persist", trackId = "t_keep", position = 0, addedAt = 5001L),
      )

      // Re-upserting the same track and playlist must update in-place without CASCADE-deleting cross refs
      dao.upsertTracks(listOf(originalTrack.copy(playCount = 999)))
      dao.upsertPlaylist(playlist.copy(description = "Updated"))

      val refs = dao.getAllPlaylistRefsOnce()
      assertEquals(1, refs.size)
      assertEquals("t_keep", refs.first().trackId)
      assertEquals("pl_persist", refs.first().playlistId)
    } finally {
      db.close()
    }
  }

  @Test
  fun `artist track matcher includes featured collaborations in title and credits`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = Room.inMemoryDatabaseBuilder(context, SoundLapaDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    try {
      val repo = AudiusRepository(context, db.soundLapaDao())
      val base = TrackEntity(
        id = "c1",
        title = "Колибри",
        artist = "Other Artist",
        genre = "Hip-Hop",
        durationMs = 200_000L,
        waveformJson = "[]",
        cachedFilePath = null,
        sourceUri = null,
        coverArtPath = null,
        accentColorHex = 0xFF10B981L,
        secondaryColorHex = 0xFF065F46L,
        isUserUpload = false,
        isCachedOffline = false,
        fileSizeBytes = 0L,
      )
      val featInTitle = base.copy(id = "c2", title = "Колибри (feat. Miyagi)", artist = "Скриптонит")
      val collabX = base.copy(id = "c3", title = "Yamakasi", artist = "Miyagi x TumaniYO")
      val typeBeatNoise = base.copy(id = "c4", title = "Miyagi Type Beat (feat. Miyagi)", artist = "Beatmaker")

      assertTrue(repo.isTrackByArtist(featInTitle, listOf("Miyagi")))
      assertTrue(repo.isTrackByArtist(collabX, listOf("Miyagi")))
      assertFalse(repo.isTrackByArtist(typeBeatNoise, listOf("Miyagi")))
    } finally {
      db.close()
    }
  }

  @Test
  fun `haptic defaults and localized playlist duration strings are configured`() {
    val defaults = SoundLapaSettings()
    assertTrue(defaults.hapticsEnabled)
    assertEquals(70, defaults.hapticIntensity)
    assertTrue(defaults.syncedCloudUids.isEmpty())

    val ru = stringsForLanguage("ru")
    val en = stringsForLanguage("en")
    val it = stringsForLanguage("it")

    assertEquals("Длительность плейлиста: 15 мин", ru.playlistDurationLabel("15 мин"))
    assertEquals("Playlist duration: 15 min", en.playlistDurationLabel("15 min"))
    assertEquals("Durata playlist: 15 min", it.playlistDurationLabel("15 min"))

    listOf(ru, en, it).forEach { s ->
      assertTrue(s.settingsHapticsTitle.isNotBlank())
      assertTrue(s.settingsHapticsSubtitle.isNotBlank())
      assertTrue(s.settingsHapticsIntensityLabel(80).contains("80"))
    }
  }

  @Test
  fun `new settings labels guest warning and developer password match requirements`() {
    assertEquals("shelban852", com.example.data.remote.DEVELOPER_REPORTS_PASSWORD)

    val ru = stringsForLanguage("ru")
    assertEquals("Плавность перехода трека и файлы", ru.settingsPlaybackSection)
    assertEquals(
      "Длительность кроссфейда (сливание треков при переходе): 4 сек",
      ru.settingsSmoothTransitionDurationLabel(4),
    )
    assertEquals("Виброотклик", ru.settingsHapticsSection)
    assertEquals("Экономия трафика", ru.settingsDataSaverSection)
    assertEquals("Для разработчика", ru.settingsDeveloperSection)
    assertEquals("Репорты", ru.settingsDeveloperReportsTitle)
    assertEquals("Войти как гость", ru.authContinueAsGuest)
    assertTrue(ru.authGuestWarningMessage.contains("Google"))

    listOf("ru", "en", "it").forEach { lang ->
      val s = stringsForLanguage(lang)
      assertTrue(s.authContinueAsGuest.isNotBlank())
      assertTrue(s.authGuestWarningMessage.isNotBlank())
      assertTrue(s.bugReportDialogTitle.isNotBlank())
      assertTrue(s.settingsDeveloperSection.isNotBlank())
      assertTrue(s.settingsDeveloperReportsTitle.isNotBlank())
      assertTrue(s.settingsProfileEditTitle.isNotBlank())
      assertTrue(s.settingsProfileModeratingLabel.isNotBlank())
      assertTrue(s.settingsProfileAvatarBlockedTitle.isNotBlank())
      assertTrue(s.settingsProfileAvatarBlockedNotice.isNotBlank())
      assertTrue(s.settingsProfileModerationErrorFallback.isNotBlank())
    }
  }

  @Test
  fun `avatar moderation service parses safe, flagged 18 plus, and invalid verdicts strictly`() {
    val safe = com.example.data.remote.AvatarModerationService.parseModerationVerdictJson(
      """{"isUnsafeAdultContent": false, "reason": "Portrait photo"}""",
    )
    assertTrue(safe is com.example.data.remote.AvatarModerationVerdict.Safe)

    val flagged = com.example.data.remote.AvatarModerationService.parseModerationVerdictJson(
      """{"isUnsafeAdultContent": true, "reason": "Explicit nudity"}""",
    )
    assertTrue(flagged is com.example.data.remote.AvatarModerationVerdict.Flagged18Plus)
    assertEquals(
      "Explicit nudity",
      (flagged as com.example.data.remote.AvatarModerationVerdict.Flagged18Plus).reason,
    )

    val invalid = com.example.data.remote.AvatarModerationService.parseModerationVerdictJson("not-json")
    assertTrue(invalid is com.example.data.remote.AvatarModerationVerdict.Error)
  }

  @Test
  fun `cloud storage headline formats real 1 GiB quota, exact sizes, and tracks payload compaction`() {
    assertEquals(1_073_741_824L, com.example.data.remote.DEFAULT_CLOUD_QUOTA_BYTES)
    assertEquals(
      "0.5ГБ/1ГБ | 50.0%",
      com.example.data.remote.formatCloudStorageHeadline(
        usedBytes = 536_870_912L,
        quotaBytes = com.example.data.remote.DEFAULT_CLOUD_QUOTA_BYTES,
        gbUnit = "ГБ",
      ),
    )
    assertEquals(
      "<0.0001ГБ/1ГБ | <0.01%",
      com.example.data.remote.formatCloudStorageHeadline(
        usedBytes = 10_240L,
        quotaBytes = com.example.data.remote.DEFAULT_CLOUD_QUOTA_BYTES,
        gbUnit = "ГБ",
      ),
    )
    assertEquals(
      "0.0ГБ/1ГБ | 0.0%",
      com.example.data.remote.formatCloudStorageHeadline(
        usedBytes = 0L,
        quotaBytes = com.example.data.remote.DEFAULT_CLOUD_QUOTA_BYTES,
        gbUnit = "ГБ",
      ),
    )
    assertEquals(
      "10.0 КБ",
      com.example.data.remote.formatCloudExactSize(10_240L),
    )
    assertEquals(
      "512.00 МБ",
      com.example.data.remote.formatCloudExactSize(536_870_912L),
    )

    val stripped = com.example.data.remote.compactCloudUrl(
      "https://open.spotify.com/track/123?si=abcdef&utm_source=copy&foo=bar",
    )
    assertEquals("https://open.spotify.com/track/123?foo=bar", stripped)

    val preservedLocalResolve = com.example.data.remote.compactCloudUrl(
      "soundlapa://resolve-full?artist=Miyagi&title=Yamakasi",
    )
    assertEquals("soundlapa://resolve-full?artist=Miyagi&title=Yamakasi", preservedLocalResolve)
  }

  @Test
  fun `developer biometric storage and startup loading strings are localized in RU EN IT`() {
    listOf("ru", "en", "it").forEach { lang ->
      val s = stringsForLanguage(lang)
      assertTrue(s.settingsDeveloperEntrySubtitle.isNotBlank())
      assertTrue(s.settingsDeveloperBiometricButton.isNotBlank())
      assertTrue(s.settingsDeveloperStorageTitle.isNotBlank())
      assertTrue(s.cloudStorageGbUnit.isNotBlank())
      assertTrue(s.cloudStorageQuotaBadge.isNotBlank())
      assertTrue(s.developerDeleteReportConfirmButton.isNotBlank())
      assertTrue(s.startupLoadingTitle.isNotBlank())
      assertTrue(s.startupLoadingCoversStatus(2, 5).contains("2"))
    }
    assertEquals("Использование хранилища облака", stringsForLanguage("ru").settingsDeveloperStorageTitle)
    assertEquals("или", stringsForLanguage("ru").settingsDeveloperOrPasswordDivider)
    assertEquals("or", stringsForLanguage("en").settingsDeveloperOrPasswordDivider)
    assertEquals("oppure", stringsForLanguage("it").settingsDeveloperOrPasswordDivider)
  }
}
