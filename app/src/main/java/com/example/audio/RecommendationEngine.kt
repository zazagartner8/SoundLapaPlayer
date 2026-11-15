package com.example.audio

import com.example.data.local.ListeningHistoryEntry
import com.example.data.local.TrackEntity
import com.example.data.local.WaveFilterConfig
import kotlin.math.ln

data class RecommendationResult(
    val tracks: List<TrackEntity>,
    val topSeedArtists: List<String>,
    val topSeedGenres: List<String>,
    val onlineSeedQueries: List<String>,
)

object RecommendationEngine {

    private val stopWords = setOf(
        "the", "and", "feat", "ft", "with", "official", "audio", "video",
        "lyrics", "lyric", "remix", "version", "edit", "prod", "music",
        "topic", "live", "cover", "mix", "slowed", "reverb", "sped",
        "клип", "трек", "песня", "версия", "аудио", "микс",
    )

    fun computeRecommendations(
        allTracks: List<TrackEntity>,
        listeningHistory: List<ListeningHistoryEntry>,
        searchHistory: List<String> = emptyList(),
        limit: Int = 12,
    ): RecommendationResult {
        if (allTracks.isEmpty()) {
            return RecommendationResult(
                tracks = emptyList(),
                topSeedArtists = emptyList(),
                topSeedGenres = emptyList(),
                onlineSeedQueries = emptyList(),
            )
        }

        val artistWeights = HashMap<String, Float>()
        val artistDisplayByKey = HashMap<String, String>()
        val genreWeights = HashMap<String, Float>()
        val genreDisplayByKey = HashMap<String, String>()
        val tokenWeights = HashMap<String, Float>()

        // 1. Signals from actual listening history (recency + repeat play count)
        listeningHistory.forEachIndexed { index, entry ->
            val recencyFactor = 1.6f / (1f + index * 0.25f)
            val freqFactor = (1f + ln((entry.playCount.coerceAtLeast(1) + 1).toDouble()).toFloat())
            val weight = recencyFactor * freqFactor

            addArtistSignal(entry.artist, weight, artistWeights, artistDisplayByKey)
            addGenreSignal(entry.genre, weight, genreWeights, genreDisplayByKey)
            tokenize("${entry.title} ${entry.artist}").forEach { token ->
                tokenWeights[token] = (tokenWeights[token] ?: 0f) + weight * 0.7f
            }
        }

        // 2. Signals from liked tracks and user uploads
        allTracks.forEach { track ->
            if (track.isLiked) {
                addArtistSignal(track.artist, 2.4f, artistWeights, artistDisplayByKey)
                addGenreSignal(track.genre, 1.8f, genreWeights, genreDisplayByKey)
                tokenize("${track.title} ${track.artist}").forEach { token ->
                    tokenWeights[token] = (tokenWeights[token] ?: 0f) + 1.0f
                }
            } else if (track.isUserUpload) {
                addArtistSignal(track.artist, 0.9f, artistWeights, artistDisplayByKey)
                addGenreSignal(track.genre, 0.8f, genreWeights, genreDisplayByKey)
            }
        }

        // 3. Signals from recent searches
        searchHistory.take(5).forEachIndexed { index, query ->
            val qWeight = 1.1f / (1f + index * 0.3f)
            tokenize(query).forEach { token ->
                tokenWeights[token] = (tokenWeights[token] ?: 0f) + qWeight
            }
        }

        val topSeedArtists = artistWeights.entries
            .sortedByDescending { it.value }
            .mapNotNull { artistDisplayByKey[it.key] }
            .distinct()
            .take(3)

        val topSeedGenres = genreWeights.entries
            .sortedByDescending { it.value }
            .mapNotNull { genreDisplayByKey[it.key] }
            .distinct()
            .take(2)

        val hasPersonalSignals = artistWeights.isNotEmpty() || genreWeights.isNotEmpty() || tokenWeights.isNotEmpty()
        val recentExactIds = listeningHistory.take(3).mapTo(HashSet()) { it.trackId }
        val allHistoryIds = listeningHistory.mapTo(HashSet()) { it.trackId }

        val scored = allTracks.map { track ->
            val normArtist = normalizeKey(track.artist)
            val normGenre = normalizeKey(track.genre)
            val trackTokens = tokenize("${track.title} ${track.artist}")

            var score = 0f
            if (hasPersonalSignals) {
                // Direct or overlapping artist match
                val directArtistWeight = artistWeights[normArtist] ?: 0f
                score += directArtistWeight * 42f

                val trackArtistTokens = tokenize(track.artist)
                val artistTokenOverlap = trackArtistTokens.sumOf { tok ->
                    val w = tokenWeights[tok] ?: 0f
                    w.toDouble()
                }.toFloat()
                score += artistTokenOverlap * 16f

                // Genre affinity
                val genreWeight = genreWeights[normGenre] ?: 0f
                score += genreWeight * 22f

                // Title & vibe token similarity
                val tokenMatchScore = trackTokens.sumOf { tok ->
                    (tokenWeights[tok] ?: 0f).toDouble()
                }.toFloat()
                score += tokenMatchScore * 11f

                // Surface undiscovered / unplayed tracks ahead of tracks just played seconds ago
                if (track.id in recentExactIds) {
                    score -= 48f
                } else if (track.id in allHistoryIds) {
                    score -= 12f
                }

                // Modest popularity tie-breaker so genuinely well-known matching tracks rise cleanly
                score += ln((track.playCount.coerceAtLeast(1) + 1).toDouble()).toFloat() * 0.9f
            } else {
                // Cold-start fallback: popular tracks skipping the very top 4 quick-pick items
                score = ln((track.playCount.coerceAtLeast(1) + 1).toDouble()).toFloat() * 3f +
                    if (track.isLiked) 6f else 0f
            }
            track to score
        }.sortedByDescending { it.second }

        // Enforce artist diversity (max 2 per artist in primary pass) so recommendations stay varied
        val picked = mutableListOf<TrackEntity>()
        val artistCounts = HashMap<String, Int>()
        for ((track, _) in scored) {
            if (picked.size >= limit) break
            val key = normalizeKey(track.artist).ifBlank { track.id }
            val count = artistCounts[key] ?: 0
            if (count < 2) {
                picked += track
                artistCounts[key] = count + 1
            }
        }
        if (picked.size < limit) {
            val pickedIds = picked.mapTo(HashSet()) { it.id }
            for ((track, _) in scored) {
                if (picked.size >= limit) break
                if (track.id !in pickedIds) {
                    picked += track
                }
            }
        }

        // Build online discovery queries based on top listened artists + top search terms
        val onlineQueries = buildList {
            topSeedArtists.take(2).forEach { artist ->
                add(artist)
            }
            val latestEntry = listeningHistory.firstOrNull()
            if (latestEntry != null && latestEntry.artist.isNotBlank() && topSeedGenres.isNotEmpty()) {
                val mixQuery = "${latestEntry.artist} ${topSeedGenres.first()}"
                if (none { it.equals(mixQuery, ignoreCase = true) }) {
                    add(mixQuery)
                }
            }
            if (isEmpty()) {
                searchHistory.firstOrNull()?.let { add(it) }
            }
        }.distinct().take(3)

        return RecommendationResult(
            tracks = picked,
            topSeedArtists = topSeedArtists,
            topSeedGenres = topSeedGenres,
            onlineSeedQueries = onlineQueries,
        )
    }

    fun computeTrendingSimilarTracks(
        allTracks: List<TrackEntity>,
        listeningHistory: List<ListeningHistoryEntry>,
        searchHistory: List<String> = emptyList(),
        limit: Int = 6,
    ): List<TrackEntity> {
        if (allTracks.isEmpty()) return emptyList()

        val artistWeights = HashMap<String, Float>()
        val artistDisplayByKey = HashMap<String, String>()
        val genreWeights = HashMap<String, Float>()
        val genreDisplayByKey = HashMap<String, String>()
        val tokenWeights = HashMap<String, Float>()

        listeningHistory.forEachIndexed { index, entry ->
            val recencyFactor = 1.6f / (1f + index * 0.25f)
            val freqFactor = 1f + ln((entry.playCount.coerceAtLeast(1) + 1).toDouble()).toFloat()
            val weight = recencyFactor * freqFactor

            addArtistSignal(entry.artist, weight, artistWeights, artistDisplayByKey)
            addGenreSignal(entry.genre, weight, genreWeights, genreDisplayByKey)
            tokenize("${entry.title} ${entry.artist}").forEach { token ->
                tokenWeights[token] = (tokenWeights[token] ?: 0f) + weight * 0.7f
            }
        }

        allTracks.forEach { track ->
            if (track.isLiked) {
                addArtistSignal(track.artist, 2.0f, artistWeights, artistDisplayByKey)
                addGenreSignal(track.genre, 1.6f, genreWeights, genreDisplayByKey)
                tokenize("${track.title} ${track.artist}").forEach { token ->
                    tokenWeights[token] = (tokenWeights[token] ?: 0f) + 0.8f
                }
            }
        }

        searchHistory.take(5).forEachIndexed { index, query ->
            val qWeight = 1.0f / (1f + index * 0.3f)
            tokenize(query).forEach { token ->
                tokenWeights[token] = (tokenWeights[token] ?: 0f) + qWeight
            }
        }

        val hasPersonalSignals = artistWeights.isNotEmpty() || genreWeights.isNotEmpty() || tokenWeights.isNotEmpty()

        // Rank candidates primarily by real Spotify play counts (and verified stream counts),
        // giving a slight tie-break / selection preference to Spotify-verified and taste-matching popular hits.
        val rankedCandidates = allTracks
            .filter { !(it.isUserUpload && it.playCount < 10) }
            .sortedWith(
                compareByDescending<TrackEntity> {
                    val isSpotifyStat = it.id.startsWith("sp_") || it.description.contains("Spotify", ignoreCase = true)
                    if (isSpotifyStat && it.playCount >= 10_000) 1 else 0
                }.thenByDescending { track ->
                    val tasteBoost = if (hasPersonalSignals) {
                        val normArtist = normalizeKey(track.artist)
                        val normGenre = normalizeKey(track.genre)
                        val directArtist = artistWeights[normArtist] ?: 0f
                        val genreWeight = genreWeights[normGenre] ?: 0f
                        if (track.playCount >= 50_000 && (directArtist > 0f || genreWeight > 0f)) {
                            (track.playCount * 0.12).toLong()
                        } else {
                            0L
                        }
                    } else {
                        0L
                    }
                    track.playCount.toLong() + tasteBoost
                }.thenByDescending { it.likeCount },
            )

        val picked = mutableListOf<TrackEntity>()
        val seenTitles = HashSet<String>()
        val artistCounts = HashMap<String, Int>()
        for (track in rankedCandidates) {
            if (picked.size >= limit) break
            val titleKey = normalizeKey(track.title)
            if (titleKey.isNotBlank() && !seenTitles.add(titleKey)) continue
            val artistKey = normalizeKey(track.artist.substringBefore(",").trim()).ifBlank { track.id }
            val count = artistCounts[artistKey] ?: 0
            if (count < 2) {
                picked += track
                artistCounts[artistKey] = count + 1
            }
        }
        if (picked.size < limit) {
            val pickedIds = picked.mapTo(HashSet()) { it.id }
            for (track in rankedCandidates) {
                if (picked.size >= limit) break
                if (track.id !in pickedIds) {
                    picked += track
                }
            }
        }
        // Final Trending display order strictly sorted from highest playCount to lowest
        return picked.sortedWith(
            compareByDescending<TrackEntity> { it.playCount }
                .thenByDescending { it.likeCount },
        )
    }

    private fun addArtistSignal(
        rawArtist: String,
        weight: Float,
        weights: MutableMap<String, Float>,
        displayByKey: MutableMap<String, String>,
    ) {
        val cleaned = rawArtist.trim()
        if (cleaned.isBlank() ||
            cleaned.equals("SoundLapa", ignoreCase = true) ||
            cleaned.equals("Мой канал", ignoreCase = true) ||
            cleaned.equals("YouTube Music", ignoreCase = true) ||
            cleaned.equals("SoundCloud", ignoreCase = true)
        ) {
            return
        }
        val key = normalizeKey(cleaned)
        if (key.isBlank()) return
        weights[key] = (weights[key] ?: 0f) + weight
        displayByKey.putIfAbsent(key, cleaned)
    }

    private fun addGenreSignal(
        rawGenre: String,
        weight: Float,
        weights: MutableMap<String, Float>,
        displayByKey: MutableMap<String, String>,
    ) {
        val cleaned = rawGenre.trim()
        if (cleaned.isBlank() ||
            cleaned.equals("Мои треки", ignoreCase = true) ||
            cleaned.equals("Авто-загрузка", ignoreCase = true)
        ) {
            return
        }
        val key = normalizeKey(cleaned)
        if (key.isBlank()) return
        weights[key] = (weights[key] ?: 0f) + weight
        displayByKey.putIfAbsent(key, cleaned)
    }

    private fun normalizeKey(value: String): String {
        return value.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun tokenize(value: String): Set<String> {
        return normalizeKey(value)
            .split(" ")
            .filter { it.length >= 3 && it !in stopWords }
            .toSet()
    }

    fun deriveDefaultWaveConfig(
        allTracks: List<TrackEntity>,
        listeningHistory: List<ListeningHistoryEntry>,
        appLanguage: String,
    ): WaveFilterConfig {
        val genreScores = HashMap<String, Float>()
        listeningHistory.forEachIndexed { idx, entry ->
            val w = 1.6f / (1f + idx * 0.25f)
            val mapped = mapRawGenreToWaveKey(entry.genre)
            if (mapped != null) {
                genreScores[mapped] = (genreScores[mapped] ?: 0f) + w
            }
        }
        allTracks.filter { it.isLiked }.forEach { track ->
            val mapped = mapRawGenreToWaveKey(track.genre)
            if (mapped != null) {
                genreScores[mapped] = (genreScores[mapped] ?: 0f) + 1.8f
            }
        }
        val topGenre = genreScores.maxByOrNull { it.value }?.key ?: "auto"

        var cyrillicScore = 0f
        var latinScore = 0f
        listeningHistory.forEach { entry ->
            val sample = "${entry.title} ${entry.artist}"
            if (containsCyrillic(sample)) cyrillicScore += entry.playCount.coerceAtLeast(1)
            else latinScore += entry.playCount.coerceAtLeast(1)
        }
        allTracks.filter { it.isLiked }.forEach { track ->
            val sample = "${track.title} ${track.artist}"
            if (containsCyrillic(sample)) cyrillicScore += 2f
            else latinScore += 2f
        }
        val derivedLang = when {
            cyrillicScore > latinScore * 1.15f -> "ru"
            latinScore > cyrillicScore * 1.25f && (cyrillicScore + latinScore) >= 3f -> "en"
            appLanguage == "ru" && cyrillicScore > 0f -> "ru"
            else -> "auto"
        }

        val likedOrRecentTracks = buildList {
            val byId = allTracks.associateBy { it.id }
            listeningHistory.take(8).mapNotNull { byId[it.trackId] }.forEach { add(it) }
            allTracks.filter { it.isLiked }.take(8).forEach { add(it) }
        }
        val instrumentalCount = likedOrRecentTracks.count { isInstrumentalTrack(it) }
        val derivedVocals = if (likedOrRecentTracks.size >= 4 && instrumentalCount >= (likedOrRecentTracks.size * 0.65f)) {
            "instrumental"
        } else {
            "all"
        }

        val derivedMood = when (topGenre) {
            "hiphop", "rock", "electronic" -> "energetic"
            "lofi", "ambient" -> "chill"
            "indie" -> "sad"
            else -> "auto"
        }

        return WaveFilterConfig(
            genre = topGenre,
            language = derivedLang,
            vocals = derivedVocals,
            mood = derivedMood,
            hasCustomized = false,
        )
    }

    fun computeWaveTracks(
        allTracks: List<TrackEntity>,
        listeningHistory: List<ListeningHistoryEntry>,
        config: WaveFilterConfig,
        excludeIds: Set<String> = emptySet(),
        limit: Int = 24,
    ): List<TrackEntity> {
        if (allTracks.isEmpty()) return emptyList()

        val artistWeights = HashMap<String, Float>()
        val artistDisplay = HashMap<String, String>()
        listeningHistory.forEachIndexed { idx, entry ->
            val w = 1.5f / (1f + idx * 0.25f) * (1f + ln((entry.playCount + 1).toDouble()).toFloat())
            addArtistSignal(entry.artist, w, artistWeights, artistDisplay)
        }
        allTracks.filter { it.isLiked }.forEach { track ->
            addArtistSignal(track.artist, 2.2f, artistWeights, artistDisplay)
        }
        val recentIds = listeningHistory.take(4).mapTo(HashSet()) { it.trackId }

        val candidates = allTracks.filter { it.id !in excludeIds }
            .ifEmpty { allTracks }

        val scored = candidates.map { track ->
            var score = 0f
            val mappedGenre = mapRawGenreToWaveKey(track.genre)
            val textSample = "${track.title} ${track.artist} ${track.description}"
            val trackIsCyrillic = containsCyrillic(textSample)
            val trackIsInstrumental = isInstrumentalTrack(track)

            // 1. Genre filter
            when (config.genre) {
                "auto" -> Unit
                else -> {
                    if (mappedGenre == config.genre || track.genre.contains(config.genre, ignoreCase = true)) {
                        score += 48f
                    } else {
                        score -= 22f
                    }
                }
            }

            // 2. Language filter
            when (config.language) {
                "ru" -> score += if (trackIsCyrillic) 34f else -18f
                "en" -> score += if (!trackIsCyrillic) 30f else -18f
                else -> Unit
            }

            // 3. Vocals filter
            when (config.vocals) {
                "instrumental" -> score += if (trackIsInstrumental) 42f else -28f
                "with_vocals" -> score += if (!trackIsInstrumental) 28f else -28f
                else -> Unit
            }

            // 4. Mood heuristic filter
            when (config.mood) {
                "energetic" -> {
                    val energetic = mappedGenre in setOf("hiphop", "electronic", "rock", "pop") ||
                        hasAnyKeyword(textSample, listOf("club", "bass", "bounce", "drift", "phonk", "dance", "fire", "energy", "party"))
                    score += if (energetic) 30f else -10f
                }
                "chill" -> {
                    val chill = mappedGenre in setOf("lofi", "ambient", "indie") ||
                        hasAnyKeyword(textSample, listOf("chill", "lofi", "relax", "night", "warm", "dream", "sunset", "ночь", "тихо"))
                    score += if (chill) 30f else -10f
                }
                "sad" -> {
                    val sad = hasAnyKeyword(textSample, listOf("sad", "cry", "rain", "alone", "pain", "miss", "heart", "memory", "груст", "боль", "один", "дождь", "тоск", "слез"))
                    score += if (sad) 32f else if (mappedGenre in setOf("indie", "ambient", "lofi")) 8f else -8f
                }
                "focus" -> {
                    val focus = trackIsInstrumental || mappedGenre in setOf("ambient", "lofi", "electronic") ||
                        hasAnyKeyword(textSample, listOf("study", "focus", "deep", "piano", "instrumental", "ambient"))
                    score += if (focus) 30f else -10f
                }
                else -> Unit
            }

            // Personal affinity & discovery balance
            val artistW = artistWeights[normalizeKey(track.artist)] ?: 0f
            score += artistW * 10f
            if (track.isLiked) score += 8f
            score += ln((track.playCount.coerceAtLeast(1) + 1).toDouble()).toFloat() * 1.4f
            if (track.id in recentIds) score -= 35f

            track to score
        }.sortedByDescending { it.second }

        val picked = mutableListOf<TrackEntity>()
        val artistCounts = HashMap<String, Int>()
        for ((track, _) in scored) {
            if (picked.size >= limit) break
            val aKey = normalizeKey(track.artist).ifBlank { track.id }
            val c = artistCounts[aKey] ?: 0
            if (c < 2) {
                picked += track
                artistCounts[aKey] = c + 1
            }
        }
        if (picked.size < limit) {
            val usedIds = picked.mapTo(HashSet()) { it.id }
            for ((track, _) in scored) {
                if (picked.size >= limit) break
                if (track.id !in usedIds) picked += track
            }
        }
        return picked
    }

    fun buildWaveOnlineQueries(
        config: WaveFilterConfig,
        listeningHistory: List<ListeningHistoryEntry>,
        allTracks: List<TrackEntity>,
    ): List<String> {
        val genreTerm = when (config.genre) {
            "hiphop" -> "hip hop rap"
            "electronic" -> "electronic synthwave"
            "pop" -> "pop hits"
            "rock" -> "rock alternative"
            "lofi" -> "lo-fi chill beats"
            "ambient" -> "ambient atmosphere"
            "indie" -> "indie music"
            else -> ""
        }
        val moodTerm = when (config.mood) {
            "energetic" -> "upbeat energetic"
            "chill" -> "chill relax"
            "sad" -> "sad melancholic"
            "focus" -> "focus deep"
            else -> ""
        }
        val vocalTerm = when (config.vocals) {
            "instrumental" -> "instrumental"
            "with_vocals" -> ""
            else -> ""
        }
        val langTerm = when (config.language) {
            "ru" -> "русский"
            "en" -> "english"
            else -> ""
        }

        val queries = mutableListOf<String>()
        val combined = listOf(langTerm, genreTerm, moodTerm, vocalTerm)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .trim()
        if (combined.length >= 3) {
            queries += combined
        }

        val topArtist = listeningHistory
            .map { it.artist.trim() }
            .firstOrNull { it.isNotBlank() && !it.equals("SoundLapa", ignoreCase = true) }
            ?: allTracks.firstOrNull { it.isLiked && it.artist.isNotBlank() }?.artist

        if (!topArtist.isNullOrBlank()) {
            val seedQuery = listOfNotNull(
                topArtist,
                moodTerm.takeIf { it.isNotBlank() } ?: genreTerm.takeIf { it.isNotBlank() },
                vocalTerm.takeIf { it.isNotBlank() },
            ).joinToString(" ").trim()
            if (seedQuery.length >= 3) queries += seedQuery
        }

        if (queries.isEmpty()) {
            queries += "trending popular hits"
        }
        return queries.distinct().take(3)
    }

    private fun mapRawGenreToWaveKey(rawGenre: String): String? {
        val norm = rawGenre.trim().lowercase()
        return when {
            norm.contains("хип") || norm.contains("rap") || norm.contains("hip") || norm.contains("trap") -> "hiphop"
            norm.contains("электр") || norm.contains("electr") || norm.contains("house") ||
                norm.contains("techno") || norm.contains("синт") || norm.contains("synth") || norm.contains("edm") -> "electronic"
            norm.contains("поп") || norm.contains("pop") -> "pop"
            norm.contains("рок") || norm.contains("rock") || norm.contains("metal") || norm.contains("punk") -> "rock"
            norm.contains("lo-fi") || norm.contains("lofi") -> "lofi"
            norm.contains("амбиент") || norm.contains("ambient") -> "ambient"
            norm.contains("инди") || norm.contains("indie") -> "indie"
            else -> null
        }
    }

    private fun isInstrumentalTrack(track: TrackEntity): Boolean {
        val g = track.genre.lowercase()
        val sample = "${track.title} ${track.description}".lowercase()
        return g.contains("ambient") || g.contains("амбиент") || g.contains("lo-fi") || g.contains("lofi") ||
            hasAnyKeyword(sample, listOf("instrumental", "без слов", "beats", "beat", "piano", "orchestral", "soundtrack", "ost", "lofi", "ambient"))
    }

    private fun containsCyrillic(text: String): Boolean =
        text.any { it in '\u0400'..'\u04FF' }

    private fun hasAnyKeyword(text: String, keywords: List<String>): Boolean {
        val lower = text.lowercase()
        return keywords.any { lower.contains(it) }
    }
}
