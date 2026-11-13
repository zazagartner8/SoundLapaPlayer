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
        assertTrue(upgradeUri.startsWith("soundlapa://resolve-full"))
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
}
