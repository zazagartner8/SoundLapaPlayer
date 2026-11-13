package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
  fun `default theme accent is emerald green and v2_5_stable strings are configured`() {
    assertEquals(0xFF10B981L, SoundLapaSettings().accentColorArgb)
    assertEquals("2.5-stable", BuildConfig.VERSION_NAME)
    assertNotNull(HomeRoute)

    val ru = stringsForLanguage("ru")
    assertEquals("Недавно прослушанные", ru.quickPicks)
    assertEquals("Лайкнутые", ru.favoritesTab)
    assertEquals("Загруженные треки", ru.myTracksTab)
    assertEquals("Сингл", ru.singleReleaseLabel)
    assertTrue(ru.aboutWhatsNewTitle.contains("2.5-stable"))
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
    }
  }
}
