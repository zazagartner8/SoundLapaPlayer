package com.example

import com.example.data.local.TrackEntity
import com.example.data.remote.FullStreamResolver
import com.example.data.remote.StreamPayloadKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FullStreamResolverTest {

    private fun sampleTrack(id: String, sourceUri: String?, durationMs: Long = 185_000L) = TrackEntity(
        id = id,
        title = "Династия",
        artist = "madk1d",
        genre = "Хип-хоп",
        durationMs = durationMs,
        waveformJson = "[]",
        cachedFilePath = null,
        sourceUri = sourceUri,
        coverArtPath = null,
        accentColorHex = 0xFFFF5500L,
        secondaryColorHex = 0xFF9C2B00L,
        isUserUpload = false,
        isAutoImported = false,
        isCachedOffline = false,
        fileSizeBytes = 0L,
    )

    @Test
    fun `legacy preview tracks are flagged for automatic full track upgrade`() {
        val itunesTrack = sampleTrack(
            id = "itunes_12345",
            sourceUri = "https://audio-ssl.itunes.apple.com/itunes-assets/AudioPreview.m4a",
            durationMs = 30_000L,
        )
        val deezerTrack = sampleTrack(
            id = "deezer_98765",
            sourceUri = "https://cdnt-preview.dzcdn.net/api/1/1/a/b/c.mp3",
            durationMs = 30_000L,
        )
        val fullYtTrack = sampleTrack(
            id = "yt_dQw4w9WgXcQ",
            sourceUri = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            durationMs = 212_000L,
        )

        assertTrue(FullStreamResolver.isShortPreviewTrack(itunesTrack))
        assertTrue(FullStreamResolver.isShortPreviewTrack(deezerTrack))
        assertFalse(FullStreamResolver.isShortPreviewTrack(fullYtTrack))

        val upgradeUri = FullStreamResolver.buildLegacyUpgradeUri(itunesTrack)
        assertTrue(upgradeUri.startsWith("soundlap://resolve-full"))
        assertTrue(FullStreamResolver.isResolvablePageOrUpgradeUri(upgradeUri))
    }

    @Test
    fun `page urls require resolution while direct cdn urls pass through`() {
        assertTrue(FullStreamResolver.isResolvablePageOrUpgradeUri("https://www.youtube.com/watch?v=abc123"))
        assertTrue(FullStreamResolver.isResolvablePageOrUpgradeUri("https://music.youtube.com/watch?v=abc123"))
        assertTrue(FullStreamResolver.isResolvablePageOrUpgradeUri("https://soundcloud.com/madk1d/dynasty"))

        assertFalse(FullStreamResolver.isResolvablePageOrUpgradeUri("https://rr3---sn-4g5e6nze.googlevideo.com/videoplayback?id=1"))
        assertFalse(FullStreamResolver.isResolvablePageOrUpgradeUri("https://api.audius.co/v1/tracks/123/stream?app_name=SoundLapa"))
        assertFalse(FullStreamResolver.isResolvablePageOrUpgradeUri("file:///data/user/0/com.example/files/audio_cache/track.mp3"))
    }

    @Test
    fun `low-res artwork urls upgrade cleanly to high resolution with valid fallbacks`() {
        val ytMusicThumb = "https://lh3.googleusercontent.com/abc123=w120-h120-l90-rj"
        assertEquals(
            "https://lh3.googleusercontent.com/abc123=w1000-h1000-l90-rj",
            FullStreamResolver.upgradeArtworkUrl(ytMusicThumb),
        )

        val scThumb = "https://i1.sndcdn.com/artworks-xyz-large.jpg"
        val scUpgraded = FullStreamResolver.upgradeArtworkUrl(scThumb)
        assertEquals("https://i1.sndcdn.com/artworks-xyz-t500x500.jpg", scUpgraded)
        assertEquals(scThumb, FullStreamResolver.fallbackArtworkUrl(scUpgraded))

        val ytSignedThumb = "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg?sqp=-oaymwEcCNACELwB&rs=AOn4CL"
        val ytUpgraded = FullStreamResolver.upgradeArtworkUrl(ytSignedThumb)
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg", ytUpgraded)
        assertEquals(
            "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
            FullStreamResolver.fallbackArtworkUrl(ytUpgraded),
        )
    }

    @Test
    fun `userAgentForStreamUrl matches YouTube client query parameter`() {
        assertTrue(
            FullStreamResolver.userAgentForStreamUrl("https://rr1.googlevideo.com/videoplayback?c=IOS&itag=140")
                .contains("iPhone"),
        )
        assertTrue(
            FullStreamResolver.userAgentForStreamUrl("https://rr1.googlevideo.com/videoplayback?c=ANDROID_VR&itag=140")
                .contains("oculus"),
        )
        assertTrue(
            FullStreamResolver.userAgentForStreamUrl("https://rr1.googlevideo.com/videoplayback?c=ANDROID&itag=140")
                .contains("com.google.android.youtube"),
        )
        assertEquals(
            FullStreamResolver.DEFAULT_BROWSER_USER_AGENT,
            FullStreamResolver.userAgentForStreamUrl("https://cf-media.sndcdn.com/audio.mp3"),
        )
    }

    @Test
    fun `classifyStreamProbe rejects HTML and JSON and detects HLS vs progressive audio`() {
        assertEquals(
            StreamPayloadKind.REJECTED,
            FullStreamResolver.classifyStreamProbe(
                finalUrl = "https://www.youtube.com/watch?v=abc",
                contentType = "text/html; charset=utf-8",
                headBytes = "<!doctype html><html></html>".toByteArray(),
            ),
        )
        assertEquals(
            StreamPayloadKind.REJECTED,
            FullStreamResolver.classifyStreamProbe(
                finalUrl = "https://audius-node.example/stream",
                contentType = "application/octet-stream",
                headBytes = "  <html><body>Cloudflare</body></html>".toByteArray(),
            ),
        )
        assertEquals(
            StreamPayloadKind.REJECTED,
            FullStreamResolver.classifyStreamProbe(
                finalUrl = "https://api.audius.co/v1/tracks/1/stream",
                contentType = "application/json",
                headBytes = "{\"error\":\"unavailable\"}".toByteArray(),
            ),
        )
        assertEquals(
            StreamPayloadKind.HLS_PLAYLIST,
            FullStreamResolver.classifyStreamProbe(
                finalUrl = "https://playback.media-streaming.soundcloud.com/playlist?foo=bar",
                contentType = "text/plain",
                headBytes = "#EXTM3U\n#EXT-X-VERSION:3\n".toByteArray(),
            ),
        )
        assertEquals(
            StreamPayloadKind.PROGRESSIVE_AUDIO,
            FullStreamResolver.classifyStreamProbe(
                finalUrl = "https://rr3.googlevideo.com/videoplayback?itag=140",
                contentType = "audio/mp4",
                headBytes = byteArrayOf(0x00, 0x00, 0x00, 0x18, 0x66, 0x74, 0x79, 0x70),
            ),
        )
    }

    @Test
    fun `isLikelyPlayableLocalFile rejects corrupt HTML cache files and accepts audio bytes`() {
        val tempDir = Files.createTempDirectory("soundlapa_cache_test").toFile()
        try {
            val corruptHtmlFile = File(tempDir, "bad_track.mp3").apply {
                writeText("<!DOCTYPE html><html>" + "x".repeat(2048) + "</html>")
            }
            assertFalse(FullStreamResolver.isLikelyPlayableLocalFile(corruptHtmlFile))

            val validAudioFile = File(tempDir, "good_track.m4a").apply {
                val bytes = ByteArray(4096) { 0x11 }
                bytes[4] = 'f'.code.toByte()
                bytes[5] = 't'.code.toByte()
                bytes[6] = 'y'.code.toByte()
                bytes[7] = 'p'.code.toByte()
                writeBytes(bytes)
            }
            assertTrue(FullStreamResolver.isLikelyPlayableLocalFile(validAudioFile))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `computeSynchronizedVideoPositionMs aligns matching, intro-extended, shorter, and canvas clips cleanly`() {
        // Matching duration (<= 1.5s diff): 1:1 tracking
        assertEquals(
            90_000L,
            FullStreamResolver.computeSynchronizedVideoPositionMs(
                audioPositionMs = 90_000L,
                audioDurationMs = 180_000L,
                clipDurationMs = 181_000L,
            ),
        )

        // Shorter official MV edit (160s vs 200s): maps proportionally so clip completes alongside track
        assertEquals(
            80_000L,
            FullStreamResolver.computeSynchronizedVideoPositionMs(
                audioPositionMs = 100_000L,
                audioDurationMs = 200_000L,
                clipDurationMs = 160_000L,
            ),
        )

        // MV with cinematic intro (+20s longer): skips intro portion & tracks proportionally to end
        val withIntroMid = FullStreamResolver.computeSynchronizedVideoPositionMs(
            audioPositionMs = 90_000L,
            audioDurationMs = 180_000L,
            clipDurationMs = 200_000L,
        )
        assertTrue(withIntroMid in 95_000L..110_000L)

        // Short visual teaser / canvas (30s clip for 180s track): modulo loops cleanly
        assertEquals(
            15_000L,
            FullStreamResolver.computeSynchronizedVideoPositionMs(
                audioPositionMs = 75_000L,
                audioDurationMs = 180_000L,
                clipDurationMs = 30_000L,
            ),
        )
    }

    @Test
    fun `transliterateToLatin and scoreTrackMatch accurately match Cyrillic queries and reject wrong songs`() {
        assertEquals("kurokov", FullStreamResolver.transliterateToLatin("Куроков"))
        assertTrue(FullStreamResolver.transliterateToLatin("стало похуй").contains("stalo"))

        val exactTranslit = TrackEntity(
            id = "sc_exact",
            title = "stalo pohui",
            artist = "kurokov",
            genre = "Hip-Hop",
            durationMs = 148_000L,
            waveformJson = "[]",
            cachedFilePath = null,
            sourceUri = "https://soundcloud.com/kurokov/stalo-pohui",
            coverArtPath = null,
            accentColorHex = 0xFFFF5500L,
            secondaryColorHex = 0xFF9C2B00L,
            isUserUpload = false,
            isCachedOffline = false,
            fileSizeBytes = 0L,
        )
        val unrelatedTrack = TrackEntity(
            id = "yt_wrong",
            title = "Совсем Другой Трек",
            artist = "Неизвестный Артист",
            genre = "Rap",
            durationMs = 210_000L,
            waveformJson = "[]",
            cachedFilePath = null,
            sourceUri = "https://youtube.com/watch?v=wrong",
            coverArtPath = null,
            accentColorHex = 0xFFFF5500L,
            secondaryColorHex = 0xFF9C2B00L,
            isUserUpload = false,
            isCachedOffline = false,
            fileSizeBytes = 0L,
        )
        val slowedRemix = TrackEntity(
            id = "yt_slowed",
            title = "стало похуй (slowed + reverb remix)",
            artist = "kurokov fan",
            genre = "Hip-Hop",
            durationMs = 185_000L,
            waveformJson = "[]",
            cachedFilePath = null,
            sourceUri = "https://youtube.com/watch?v=slowed",
            coverArtPath = null,
            accentColorHex = 0xFFFF5500L,
            secondaryColorHex = 0xFF9C2B00L,
            isUserUpload = false,
            isCachedOffline = false,
            fileSizeBytes = 0L,
        )

        val picked = FullStreamResolver.pickBestTrackMatch(
            candidates = listOf(unrelatedTrack, slowedRemix, exactTranslit),
            targetArtist = "kurokov",
            targetTitle = "стало похуй",
            expectedDurationMs = 149_000L,
        )
        assertEquals("sc_exact", picked?.id)
        assertTrue(FullStreamResolver.scoreTrackMatch(unrelatedTrack, "kurokov", "стало похуй", 149_000L) < 0)
        assertTrue(FullStreamResolver.isUnwantedVariantTitle(slowedRemix.title))
    }

    @Test
    fun `computeSynchronizedVideoPlaybackSpeed adapts playback speed smoothly for slightly mismatched clip lengths`() {
        // Matching within 2 seconds -> exact 1.0x speed
        assertEquals(
            1.0f,
            FullStreamResolver.computeSynchronizedVideoPlaybackSpeed(180_000L, 181_500L),
            0.001f,
        )
        // Slightly longer clip (200s vs 180s) -> proportional ratio clamped smoothly (~1.11x)
        val faster = FullStreamResolver.computeSynchronizedVideoPlaybackSpeed(180_000L, 200_000L)
        assertTrue(faster in 1.10f..1.12f)
        // Slightly shorter clip (160s vs 180s) -> proportional ratio (~0.89x)
        val slower = FullStreamResolver.computeSynchronizedVideoPlaybackSpeed(180_000L, 160_000L)
        assertTrue(slower in 0.88f..0.90f)
        // Short looping canvas (15s vs 180s) -> normal 1.0x speed
        assertEquals(
            1.0f,
            FullStreamResolver.computeSynchronizedVideoPlaybackSpeed(180_000L, 15_000L),
            0.001f,
        )
    }

    @Test
    fun `resolved video stream carries optional paired audio url and muxed state`() {
        val separateAudioStream = com.example.data.remote.ResolvedVideoStream(
            videoUrl = "https://example.com/video.mp4",
            title = "Official Music Video",
            durationMs = 200_000L,
            sourcePageUrl = "https://youtube.com/watch?v=dQw4w9WgXcQ",
            width = 1280,
            height = 720,
            audioUrl = "https://example.com/clip-audio.m4a",
            isMuxed = false,
        )
        assertEquals("https://example.com/clip-audio.m4a", separateAudioStream.audioUrl)
        assertFalse(separateAudioStream.isMuxed)

        val muxedStream = separateAudioStream.copy(audioUrl = null, isMuxed = true)
        assertTrue(muxedStream.isMuxed)
        assertEquals(null, muxedStream.audioUrl)
        assertTrue(muxedStream.hasAudio)

        val canvasStream = muxedStream.copy(
            isLoopingCanvas = true,
            sourceKind = com.example.data.remote.VideoStreamSource.SPOTIFY_CANVAS,
        )
        assertFalse(canvasStream.hasAudio)
    }

    @Test
    fun `spotify clip resolution prioritizes spotify video then canvas and falls back to youtube music`() {
        val spotifyCanvas = com.example.data.remote.ResolvedVideoStream(
            videoUrl = "https://canvaz.scdn.co/upload/artist/sample.cnvs.mp4",
            title = "Canvas",
            durationMs = 8_000L,
            sourcePageUrl = "https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT",
            isLoopingCanvas = true,
            sourceKind = com.example.data.remote.VideoStreamSource.SPOTIFY_CANVAS,
        )
        val youtubeMv = com.example.data.remote.ResolvedVideoStream(
            videoUrl = "https://example.com/yt-mv.mp4",
            title = "YT Official Clip",
            durationMs = 215_000L,
            sourcePageUrl = "https://youtube.com/watch?v=test",
            isMuxed = true,
            isLoopingCanvas = false,
            sourceKind = com.example.data.remote.VideoStreamSource.YOUTUBE_MUSIC,
        )

        val chosenSpotifyFirst = FullStreamResolver.resolveMusicVideoWithFallback(
            spotifyLookup = { spotifyCanvas },
            youtubeMusicFallback = { youtubeMv },
        )
        assertEquals(spotifyCanvas, chosenSpotifyFirst)

        val chosenFallback = FullStreamResolver.resolveMusicVideoWithFallback(
            spotifyLookup = { null },
            youtubeMusicFallback = { youtubeMv },
        )
        assertEquals(youtubeMv, chosenFallback)
    }

    @Test
    fun `spotify catalog parses initialState canvas and music video json plus canvaz protobuf payloads`() {
        val canvasJson = """{"entities":{"items":{"spotify:track:123":{"canvas":{"url":"https://canvaz.scdn.co/upload/licensor/abc.cnvs.mp4"}}}}}"""
        val parsedCanvas = com.example.data.remote.SpotifyCatalogClient.parseTrackClipFromInitialStateJson(
            jsonText = canvasJson,
            spotifyTrackId = "123",
            titleHint = "Track",
            trackDurationMs = 190_000L,
        )
        assertTrue(parsedCanvas != null && parsedCanvas.isLoopingCanvas)
        assertEquals("https://canvaz.scdn.co/upload/licensor/abc.cnvs.mp4", parsedCanvas?.videoUrl)

        val videoJson = """{"entities":{"items":{"spotify:track:123":{"musicVideo":{"url":"https://cdn.spotify.com/video/clip.mp4","durationMs":224000,"width":1920,"height":1080}}}}}"""
        val parsedVideo = com.example.data.remote.SpotifyCatalogClient.parseTrackClipFromInitialStateJson(
            jsonText = videoJson,
            spotifyTrackId = "123",
            titleHint = "Track",
            trackDurationMs = 190_000L,
        )
        assertTrue(parsedVideo != null && !parsedVideo.isLoopingCanvas && parsedVideo.isMuxed)
        assertEquals(224_000L, parsedVideo?.durationMs)

        val encodedReq = com.example.data.remote.SpotifyCatalogClient.encodeCanvazRequestProtobuf("spotify:track:4cOdK2wGLETKBW3PvgPWqT")
        assertTrue(encodedReq.isNotEmpty())

        val canvasUrlBytes = "https://canvaz.scdn.co/upload/artist/test_loop.cnvs.mp4".toByteArray(Charsets.UTF_8)
        val innerProto = byteArrayOf(0x12, canvasUrlBytes.size.toByte()) + canvasUrlBytes
        val outerProto = byteArrayOf(0x0A, innerProto.size.toByte()) + innerProto
        val decodedUrls = com.example.data.remote.SpotifyCatalogClient.extractProtobufVideoUrls(outerProto)
        assertEquals(listOf("https://canvaz.scdn.co/upload/artist/test_loop.cnvs.mp4"), decodedUrls)
    }
}
