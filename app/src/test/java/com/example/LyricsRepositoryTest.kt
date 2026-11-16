package com.example

import com.example.data.remote.LyricsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsRepositoryTest {

    @Test
    fun `parseSpicyLyricsJson parses synced lines and syllables and rejects error responses`() {
        val errorJson = """{"error": true, "message": "Track not found"}"""
        assertNull(LyricsRepository.parseSpicyLyricsJson(errorJson, 180_000L))

        val statusErrorJson = """{"status": 404, "Content": []}"""
        assertNull(LyricsRepository.parseSpicyLyricsJson(statusErrorJson, 180_000L))

        val lineJson = """
            {
              "Content": [
                {"Text": "First line", "StartTime": 1.25},
                {"Text": "Second line", "StartTime": 5.5}
              ]
            }
        """.trimIndent()
        val parsed = LyricsRepository.parseSpicyLyricsJson(lineJson, 180_000L)
        assertNotNull(parsed)
        assertEquals(2, parsed!!.first.size)
        assertEquals(1250L, parsed.first[0].timeMs)
        assertEquals("First line", parsed.first[0].text)
        assertEquals(5500L, parsed.first[1].timeMs)

        val syllableJson = """
            {
              "Content": [
                {
                  "StartTime": 10200,
                  "Lead": {
                    "Syllables": [
                      {"Text": "Hel", "IsPartOfWord": true},
                      {"Text": "lo", "IsPartOfWord": false},
                      {"Text": "world", "IsPartOfWord": false}
                    ]
                  }
                }
              ]
            }
        """.trimIndent()
        val syllableParsed = LyricsRepository.parseSpicyLyricsJson(syllableJson, 180_000L)
        assertNotNull(syllableParsed)
        assertEquals("Hello world", syllableParsed!!.first.single().text)
    }

    @Test
    fun `isVerifiedCandidateMatch rejects mismatched artists, titles, and durations`() {
        assertTrue(
            LyricsRepository.isVerifiedCandidateMatch(
                targetArtist = "Miyagi",
                targetTitle = "Kosandra",
                expectedDurationMs = 215_000L,
                candArtist = "Miyagi & Andy Panda",
                candTitle = "Kosandra",
                candDurationMs = 216_000L,
            ),
        )

        // Transliterated match works
        assertTrue(
            LyricsRepository.isVerifiedCandidateMatch(
                targetArtist = "kurokov",
                targetTitle = "стало похуй",
                expectedDurationMs = 150_000L,
                candArtist = "Куроков",
                candTitle = "Stalo Pohui",
                candDurationMs = 151_000L,
            ),
        )

        // Wrong track title rejected
        assertFalse(
            LyricsRepository.isVerifiedCandidateMatch(
                targetArtist = "Miyagi",
                targetTitle = "Kosandra",
                expectedDurationMs = 215_000L,
                candArtist = "Miyagi",
                candTitle = "Completely Different Song",
                candDurationMs = 215_000L,
            ),
        )

        // Wrong artist rejected
        assertFalse(
            LyricsRepository.isVerifiedCandidateMatch(
                targetArtist = "kurokov",
                targetTitle = "стало похуй",
                expectedDurationMs = 150_000L,
                candArtist = "Random Artist",
                candTitle = "стало похуй",
                candDurationMs = 150_000L,
            ),
        )

        // Large duration mismatch (> 45s) rejected
        assertFalse(
            LyricsRepository.isVerifiedCandidateMatch(
                targetArtist = "Miyagi",
                targetTitle = "Kosandra",
                expectedDurationMs = 215_000L,
                candArtist = "Miyagi",
                candTitle = "Kosandra",
                candDurationMs = 95_000L,
            ),
        )
    }

    @Test
    fun `parseLrc parses multi-timestamp and sorted lines accurately`() {
        val raw = """
            [00:15.50]Chorus start
            [00:04.20][01:02.00]Repeated hook
        """.trimIndent()
        val parsed = LyricsRepository.parseLrc(raw)
        assertEquals(3, parsed.size)
        assertEquals(4_200L, parsed[0].timeMs)
        assertEquals("Repeated hook", parsed[0].text)
        assertEquals(15_500L, parsed[1].timeMs)
        assertEquals("Chorus start", parsed[1].text)
        assertEquals(62_000L, parsed[2].timeMs)
        assertEquals("Repeated hook", parsed[2].text)
    }

    @Test
    fun `isVerifiedCandidateMatch supports multi-artist credits and Cyrillic yo-e normalization`() {
        assertTrue(
            LyricsRepository.isVerifiedCandidateMatch(
                targetArtist = "Miyagi, TumaniYO",
                targetTitle = "Самый тёмный вечер",
                expectedDurationMs = 200_000L,
                candArtist = "TumaniYO",
                candTitle = "Самый темный вечер",
                candDurationMs = 201_000L,
            ),
        )
    }
}
