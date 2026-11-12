package com.example

import com.example.data.local.TrackEntity
import com.example.data.remote.FullStreamResolver
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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
}
