package com.example.data.local

import androidx.compose.runtime.staticCompositionLocalOf
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * CompositionLocal providing IDs of tracks identified as explicit (either from lyrics or metadata).
 */
val LocalExplicitTrackIds = staticCompositionLocalOf<Set<String>> { emptySet() }

/**
 * Detects explicit / profanity content in track metadata (title, tags, platform flags) and lyrics
 * across Russian, English, and Italian so explicit [E] badges appear before opening lyrics.
 */
object ExplicitContentDetector {

    private val preDetectedExplicitIds: MutableSet<String> =
        Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    fun markPreDetectedExplicit(trackId: String?) {
        val clean = trackId?.trim().orEmpty()
        if (clean.isNotEmpty()) {
            preDetectedExplicitIds.add(clean)
        }
    }

    fun isPreDetectedExplicit(trackId: String?): Boolean {
        val clean = trackId?.trim().orEmpty()
        return clean.isNotEmpty() && clean in preDetectedExplicitIds
    }

    private val METADATA_EXPLICIT_MARKER = Regex(
        pattern = """(?:\[\s*E\s*\]|\(\s*explicit\s*\)|\bexplicit\b|\b18\s*\+|\bнецензурн|\bмат\b)""",
        option = RegexOption.IGNORE_CASE,
    )

    // Russian profanity roots & common obfuscated / spaced forms.
    private val RU_PROFANITY_REGEX = Regex(
        pattern = """(?iu)(?<![а-яёa-z0-9])(?:""" +
            """бля(?:д[а-яё]*|т[а-яё]*|х[а-яё]*)?|""" +
            """б\*я(?:ть|д[а-яё]*)?|""" +
            """сук[аиуойе][а-яё]*|суч[аиуек][а-яё]*|""" +
            """ху[йяеюи][а-яё]*|наху[йя]|поху[йю]|хуё[а-яё]*|аху[еи][а-яё]*|оху[еи][а-яё]*|""" +
            """пизд[а-яё]*|п\*зд[а-яё]*|рас?пизд[а-яё]*|""" +
            """(?:^|за|на|от|по|про|вы|в|до|под|раз|с|у|пере|об|съ|въ)?(?:еб|ёб)[ауеиоыюя][а-яё]*|""" +
            """долбо[её]б[а-яё]*|у[её]б[а-яё]*|""" +
            """пид[аое]р[а-яё]*|педик[а-яё]*|""" +
            """муд[аио][кч][а-яё]*|мудил[а-яё]*|""" +
            """г[ао]ндон[а-яё]*|шлюх[а-яё]*|шлюш[а-яё]*|""" +
            """др[оа]ч[иаеу][а-яё]*|член[ауеом]*|мраз[ьи][а-яё]*|""" +
            """дерьм[аоуе][а-яё]*|го[вм]н[аоуеы][а-яё]*|""" +
            """жоп[ауеыой][а-яё]*|залуп[а-яё]*""" +
            """)(?![а-яёa-z0-9])""",
    )

    // English & Italian explicit profanity / slurs (word-bounded so normal words never match).
    private val EN_IT_PROFANITY_REGEX = Regex(
        pattern = """(?i)\b(?:""" +
            """f+u+c+k+(?:ing|er|ed|s|boy|boys)?|f\*+c?k(?:ing|er|ed)?|mf|motherfuck(?:er|ing|ed|s)?|""" +
            """sh+i+t+(?:ty|ting|ted|s)?|bullshit|s\*+t|""" +
            """b+i+t+c+h+(?:es|ing|ed|y)?|""" +
            """a+s+s+h+o+l+e+s?|""" +
            """c+u+n+t+s?|""" +
            """d+i+c+k+(?:head|s)?|c+o+c+k+(?:sucker|s)?|""" +
            """p+u+s+s+y+|p+u+s+s+i+e+s|""" +
            """wh+o+r+e+s?|sl+u+t+s?|""" +
            """nig+g+(?:a+s?|er+s?)|fag+(?:ot+s?)?|""" +
            """b+a+s+t+a+r+d+s?|goddamn(?:it)?|""" +
            """cazz[oi]|vaffancul[oi]|fottut[oi]|puttan[ae]|stronz[oi]|merd[ae]""" +
            """)\b""",
    )

    private val NON_LYRIC_BRACKET_META = Regex("""\[[^\]]+\]""")

    fun containsExplicitLanguage(text: String?): Boolean {
        val raw = text?.trim().orEmpty()
        if (raw.isEmpty()) return false
        if (METADATA_EXPLICIT_MARKER.containsMatchIn(raw)) return true
        val cleaned = NON_LYRIC_BRACKET_META.replace(raw, " ")
        return RU_PROFANITY_REGEX.containsMatchIn(cleaned) ||
            EN_IT_PROFANITY_REGEX.containsMatchIn(cleaned)
    }

    fun isTrackMetadataExplicit(track: TrackEntity): Boolean {
        if (track.id in preDetectedExplicitIds) return true
        if (containsExplicitLanguage(track.title)) return true
        if (!track.albumTitle.isNullOrBlank() && METADATA_EXPLICIT_MARKER.containsMatchIn(track.albumTitle)) {
            return true
        }
        if (track.description.isNotBlank() && METADATA_EXPLICIT_MARKER.containsMatchIn(track.description)) {
            return true
        }
        return false
    }

    fun isLyricsExplicit(lyricsText: String?): Boolean {
        val raw = lyricsText?.trim().orEmpty()
        if (raw.isEmpty()) return false
        val normalized = raw
            .replace(Regex("""\[[^\]]+\]"""), " ")
            .replace(Regex("""<[^>]+>"""), " ")
        return RU_PROFANITY_REGEX.containsMatchIn(normalized) ||
            EN_IT_PROFANITY_REGEX.containsMatchIn(normalized)
    }

    fun isExplicit(
        track: TrackEntity,
        cachedExplicitIds: Set<String> = emptySet(),
    ): Boolean {
        if (track.id in cachedExplicitIds || track.id in preDetectedExplicitIds) return true
        return isTrackMetadataExplicit(track)
    }
}
