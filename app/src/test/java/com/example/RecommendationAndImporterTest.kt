package com.example

import com.example.audio.RecommendationEngine
import com.example.data.local.ListeningHistoryEntry
import com.example.data.local.TrackEntity
import com.example.data.remote.PlaylistImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
