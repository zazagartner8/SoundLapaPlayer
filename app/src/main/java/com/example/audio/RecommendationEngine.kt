package com.example.audio

import com.example.data.local.ListeningHistoryEntry
import com.example.data.local.TrackEntity
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
        val recentTopIds = listeningHistory.take(2).mapTo(HashSet()) { it.trackId }

        val scored = allTracks.map { track ->
            val plays = track.playCount.coerceAtLeast(0)
            val likes = track.likeCount.coerceAtLeast(0)
            val popularityScore = ln((plays + 1).toDouble()).toFloat() * 14f +
                ln((likes + 1).toDouble()).toFloat() * 5f +
                when {
                    plays >= 100_000 -> 26f
                    plays >= 10_000 -> 14f
                    plays >= 1_000 -> 6f
                    else -> 0f
                }

            var score = popularityScore
            if (track.isUserUpload && plays < 10) {
                score -= 40f
            }

            if (hasPersonalSignals) {
                val normArtist = normalizeKey(track.artist)
                val normGenre = normalizeKey(track.genre)
                val trackTokens = tokenize("${track.title} ${track.artist}")
                val artistTokens = tokenize(track.artist)

                val directArtistWeight = artistWeights[normArtist] ?: 0f
                val artistTokenOverlap = artistTokens.sumOf { (tokenWeights[it] ?: 0f).toDouble() }.toFloat()
                val genreWeight = genreWeights[normGenre] ?: 0f
                val tokenMatchScore = trackTokens.sumOf { (tokenWeights[it] ?: 0f).toDouble() }.toFloat()

                val tasteSimilarity = directArtistWeight * 22f +
                    artistTokenOverlap * 12f +
                    genreWeight * 18f +
                    tokenMatchScore * 9f

                // Reward tracks that are BOTH genuinely popular AND match the user's taste
                val popularityGate = if (plays >= 500 || likes >= 20) 1.0f else 0.35f
                score += tasteSimilarity * popularityGate

                if (track.id in recentTopIds) {
                    score -= 18f
                }
            }

            track to score
        }.sortedByDescending { it.second }

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
        return picked
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
}
