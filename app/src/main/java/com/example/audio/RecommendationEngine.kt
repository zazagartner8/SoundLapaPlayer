package com.example.audio

import com.example.data.local.ListeningHistoryEntry
import com.example.data.local.ListeningPlayEvent
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

    private data class TasteProfile(
        val artistWeights: Map<String, Float>,
        val genreWeights: Map<String, Float>,
        val tokenWeights: Map<String, Float>,
        val topSeedArtists: List<String>,
        val topSeedGenres: List<String>,
        val preferredLanguage: String, // "ru", "en", or "auto"
        val cyrillicShare: Float,
        val hasPersonalSignals: Boolean,
    )

    private fun buildTasteProfile(
        allTracks: List<TrackEntity>,
        listeningHistory: List<ListeningHistoryEntry>,
        listeningEvents: List<ListeningPlayEvent>,
        searchHistory: List<String>,
    ): TasteProfile {
        val artistWeights = HashMap<String, Float>()
        val artistDisplayByKey = HashMap<String, String>()
        val genreWeights = HashMap<String, Float>()
        val genreDisplayByKey = HashMap<String, String>()
        val tokenWeights = HashMap<String, Float>()

        var cyrillicWeight = 0f
        var latinWeight = 0f

        // 1. Duration-weighted recent listening events (minutes listened reflect true affinity)
        val nowMs = System.currentTimeMillis()
        for (event in listeningEvents) {
            val ageDays = ((nowMs - event.lastPlayedAtMs).coerceAtLeast(0L) / 86_400_000f)
            val recencyFactor = 1.8f / (1f + ageDays * 0.14f)
            val minutes = (event.listenedMs.coerceAtLeast(15_000L) / 60_000f).coerceIn(0.25f, 8f)
            val weight = recencyFactor * (0.8f + minutes)
            addArtistSignal(event.artist, weight, artistWeights, artistDisplayByKey)
            addGenreSignal(event.genre, weight * 0.9f, genreWeights, genreDisplayByKey)
            tokenize("${event.title} ${event.artist}").forEach { token ->
                tokenWeights[token] = (tokenWeights[token] ?: 0f) + weight * 0.55f
            }
            val sample = "${event.title} ${event.artist}"
            if (containsCyrillic(sample)) cyrillicWeight += weight else latinWeight += weight
        }

        // 2. Signals from aggregate listening history
        listeningHistory.forEachIndexed { index, entry ->
            val recencyFactor = 1.6f / (1f + index * 0.22f)
            val freqFactor = (1f + ln((entry.playCount.coerceAtLeast(1) + 1).toDouble()).toFloat())
            val weight = recencyFactor * freqFactor

            addArtistSignal(entry.artist, weight, artistWeights, artistDisplayByKey)
            addGenreSignal(entry.genre, weight, genreWeights, genreDisplayByKey)
            tokenize("${entry.title} ${entry.artist}").forEach { token ->
                tokenWeights[token] = (tokenWeights[token] ?: 0f) + weight * 0.65f
            }
            val sample = "${entry.title} ${entry.artist}"
            if (containsCyrillic(sample)) cyrillicWeight += weight * 0.7f else latinWeight += weight * 0.7f
        }

        // 3. Signals from liked tracks and user uploads
        allTracks.forEach { track ->
            if (track.isLiked) {
                addArtistSignal(track.artist, 2.5f, artistWeights, artistDisplayByKey)
                addGenreSignal(track.genre, 1.9f, genreWeights, genreDisplayByKey)
                tokenize("${track.title} ${track.artist}").forEach { token ->
                    tokenWeights[token] = (tokenWeights[token] ?: 0f) + 1.0f
                }
                if (containsCyrillic("${track.title} ${track.artist}")) cyrillicWeight += 1.4f else latinWeight += 1.4f
            } else if (track.isUserUpload) {
                addArtistSignal(track.artist, 0.9f, artistWeights, artistDisplayByKey)
                addGenreSignal(track.genre, 0.8f, genreWeights, genreDisplayByKey)
            }
        }

        // 4. Signals from recent searches (artists, moods, genres user actively searches)
        searchHistory.take(8).forEachIndexed { index, query ->
            val qWeight = 1.35f / (1f + index * 0.25f)
            addArtistSignal(query, qWeight * 0.85f, artistWeights, artistDisplayByKey)
            tokenize(query).forEach { token ->
                tokenWeights[token] = (tokenWeights[token] ?: 0f) + qWeight
            }
            if (containsCyrillic(query)) cyrillicWeight += qWeight * 0.8f else latinWeight += qWeight * 0.8f
        }

        val topSeedArtists = artistWeights.entries
            .sortedByDescending { it.value }
            .mapNotNull { artistDisplayByKey[it.key] }
            .distinct()
            .take(4)

        val topSeedGenres = genreWeights.entries
            .sortedByDescending { it.value }
            .mapNotNull { genreDisplayByKey[it.key] }
            .distinct()
            .take(3)

        val totalLang = cyrillicWeight + latinWeight
        val cyrShare = if (totalLang > 0f) cyrillicWeight / totalLang else 0.5f
        val prefLang = when {
            totalLang < 1.5f -> "auto"
            cyrShare >= 0.62f -> "ru"
            cyrShare <= 0.35f -> "en"
            else -> "auto"
        }

        val hasSignals = artistWeights.isNotEmpty() || genreWeights.isNotEmpty() || tokenWeights.isNotEmpty()
        return TasteProfile(
            artistWeights = artistWeights,
            genreWeights = genreWeights,
            tokenWeights = tokenWeights,
            topSeedArtists = topSeedArtists,
            topSeedGenres = topSeedGenres,
            preferredLanguage = prefLang,
            cyrillicShare = cyrShare,
            hasPersonalSignals = hasSignals,
        )
    }

    /**
     * Permanently excludes unwanted legacy seed tracks ("goosebumps" and "passionfruit")
     * from Recommendations, For You, Trending, and Wave feeds regardless of old local cache
     * or incidental artist overlap. Users can still explicitly find them in Search.
     */
    fun isBlockedRecommendationTrack(track: TrackEntity): Boolean {
        val normTitle = normalizeKey(track.title)
        return normTitle.contains("goosebumps") ||
            normTitle.contains("passionfruit") ||
            normTitle.contains("passion fruit")
    }

    private fun computePersonalAffinity(
        track: TrackEntity,
        profile: TasteProfile,
    ): Float {
        if (!profile.hasPersonalSignals) return 0f
        val normArtist = normalizeKey(track.artist)
        val normGenre = normalizeKey(track.genre)
        val trackTokens = tokenize("${track.title} ${track.artist}")
        val trackArtistTokens = tokenize(track.artist)

        val directArtistWeight = profile.artistWeights[normArtist] ?: 0f
        val artistTokenOverlap = trackArtistTokens.sumOf { tok ->
            (profile.tokenWeights[tok] ?: 0f).toDouble()
        }.toFloat()
        val genreWeight = profile.genreWeights[normGenre] ?: 0f
        val tokenMatchScore = trackTokens.sumOf { tok ->
            (profile.tokenWeights[tok] ?: 0f).toDouble()
        }.toFloat()

        return directArtistWeight * 40f +
            artistTokenOverlap * 16f +
            genreWeight * 24f +
            tokenMatchScore * 12f
    }

    private fun languageColdStartBonus(track: TrackEntity, appLanguage: String): Float {
        val sample = "${track.title} ${track.artist}"
        val isCyr = containsCyrillic(sample)
        return when (appLanguage.trim().lowercase()) {
            "ru" -> if (isCyr) 42f else -18f
            "en", "it" -> if (!isCyr) 34f else -18f
            else -> 0f
        }
    }

    fun computeRecommendations(
        allTracks: List<TrackEntity>,
        listeningHistory: List<ListeningHistoryEntry>,
        searchHistory: List<String> = emptyList(),
        limit: Int = 12,
        listeningEvents: List<ListeningPlayEvent> = emptyList(),
        excludeIds: Set<String> = emptySet(),
        appLanguage: String = "ru",
    ): RecommendationResult {
        if (allTracks.isEmpty()) {
            return RecommendationResult(
                tracks = emptyList(),
                topSeedArtists = emptyList(),
                topSeedGenres = emptyList(),
                onlineSeedQueries = emptyList(),
            )
        }

        val profile = buildTasteProfile(allTracks, listeningHistory, listeningEvents, searchHistory)
        val recentExactIds = listeningHistory.take(5).mapTo(HashSet()) { it.trackId }
        val allHistoryIds = listeningHistory.mapTo(HashSet()) { it.trackId }
        val nowMs = System.currentTimeMillis()
        val lastPlayedById = listeningHistory.associate { it.trackId to it.lastPlayedAtMs }

        val cleanedAll = allTracks.filterNot { isBlockedRecommendationTrack(it) }
        if (cleanedAll.isEmpty()) {
            return RecommendationResult(
                tracks = emptyList(),
                topSeedArtists = profile.topSeedArtists.take(3),
                topSeedGenres = profile.topSeedGenres.take(2),
                onlineSeedQueries = emptyList(),
            )
        }
        val candidatePool = if (excludeIds.isNotEmpty()) {
            cleanedAll.filter { it.id !in excludeIds }.ifEmpty { cleanedAll }
        } else {
            cleanedAll
        }

        val scoredEntries = candidatePool.map { track ->
            val isCyr = containsCyrillic("${track.title} ${track.artist}")
            val affinity = computePersonalAffinity(track, profile)
            var score = affinity

            if (profile.hasPersonalSignals) {
                score += when (profile.preferredLanguage) {
                    "ru" -> if (isCyr) 26f else -16f
                    "en" -> if (!isCyr) 22f else -14f
                    else -> if (isCyr) (profile.cyrillicShare - 0.5f) * 20f else ((1f - profile.cyrillicShare) - 0.5f) * 20f
                }

                // Strong novelty bias so recently played tracks never clutter Recommendations repeatedly
                val lastPlayed = lastPlayedById[track.id] ?: 0L
                val hoursSincePlay = if (lastPlayed > 0L) ((nowMs - lastPlayed).coerceAtLeast(0L) / 3_600_000f) else 999f
                when {
                    track.id in recentExactIds || hoursSincePlay < 4f -> score -= 95f
                    track.id in allHistoryIds || hoursSincePlay < 24f -> score -= 42f
                    else -> score += 16f // Fresh discovery bonus
                }

                score += ln((track.playCount.coerceAtLeast(1) + 1).toDouble()).toFloat() * 0.65f
            } else {
                // Cold start: fresh hits matching the app language
                score = languageColdStartBonus(track, appLanguage) +
                    ln((track.playCount.coerceAtLeast(1) + 1).toDouble()).toFloat() * 2.8f +
                    (if (track.isLiked) 6f else 0f) +
                    if (track.id in recentExactIds) -40f else 0f
            }
            Triple(track, score, affinity)
        }

        // When user has personal signals, strictly prioritize tracks with genuine taste affinity
        // so unrelated global hits never hijack personal recommendations via raw playCount.
        val filteredOrdered = if (profile.hasPersonalSignals) {
            val relevant = scoredEntries
                .filter { it.third > 0f }
                .sortedByDescending { it.second }
                .map { it.first }
            if (relevant.size >= minOf(limit, 4)) {
                relevant
            } else {
                val relevantIds = relevant.mapTo(HashSet()) { it.id }
                val langMatchedFallback = scoredEntries
                    .filter { it.first.id !in relevantIds }
                    .filter { entry ->
                        val isCyr = containsCyrillic("${entry.first.title} ${entry.first.artist}")
                        when (profile.preferredLanguage) {
                            "ru" -> isCyr
                            "en" -> !isCyr
                            else -> if (appLanguage.equals("ru", ignoreCase = true)) isCyr else true
                        }
                    }
                    .sortedByDescending { it.second }
                    .map { it.first }
                (relevant + langMatchedFallback).ifEmpty {
                    scoredEntries.sortedByDescending { it.second }.map { it.first }
                }
            }
        } else {
            val langPreferred = scoredEntries
                .filter { languageColdStartBonus(it.first, appLanguage) >= 0f }
                .sortedByDescending { it.second }
                .map { it.first }
            val preferredIds = langPreferred.mapTo(HashSet()) { it.id }
            val rest = scoredEntries
                .filter { it.first.id !in preferredIds }
                .sortedByDescending { it.second }
                .map { it.first }
            langPreferred + rest
        }

        val maxPerArtist = if (filteredOrdered.size >= limit * 2) 1 else 2
        val picked = pickDiverseTracks(filteredOrdered, limit = limit, maxPerArtist = maxPerArtist)

        val onlineQueries = buildList {
            profile.topSeedArtists.take(2).forEach { add(it) }
            val latestArtist = listeningHistory.firstOrNull()?.artist?.trim().orEmpty()
            if (latestArtist.isNotBlank() && profile.topSeedGenres.isNotEmpty()) {
                val mixQuery = "$latestArtist ${profile.topSeedGenres.first()}"
                if (none { it.equals(mixQuery, ignoreCase = true) }) add(mixQuery)
            }
            if (isEmpty()) {
                searchHistory.firstOrNull()?.let { add(it) }
            }
        }.distinct().take(3)

        return RecommendationResult(
            tracks = picked,
            topSeedArtists = profile.topSeedArtists.take(3),
            topSeedGenres = profile.topSeedGenres.take(2),
            onlineSeedQueries = onlineQueries,
        )
    }

    fun computeForYouTracks(
        allTracks: List<TrackEntity>,
        listeningHistory: List<ListeningHistoryEntry>,
        listeningEvents: List<ListeningPlayEvent> = emptyList(),
        searchHistory: List<String> = emptyList(),
        excludeIds: Set<String> = emptySet(),
        limit: Int = 36,
        appLanguage: String = "ru",
    ): List<TrackEntity> {
        if (allTracks.isEmpty()) return emptyList()
        val profile = buildTasteProfile(allTracks, listeningHistory, listeningEvents, searchHistory)
        val allHistoryIds = listeningHistory.mapTo(HashSet()) { it.trackId }

        val cleanedAll = allTracks.filterNot { isBlockedRecommendationTrack(it) }
        if (cleanedAll.isEmpty()) return emptyList()
        val nonExcluded = cleanedAll.filter { it.id !in excludeIds }
        val pool = nonExcluded.ifEmpty { cleanedAll }

        val scoredEntries = pool.map { track ->
            val normArtist = normalizeKey(track.artist)
            val normGenre = normalizeKey(track.genre)
            val isCyr = containsCyrillic("${track.title} ${track.artist}")
            val trackTokens = tokenize("${track.title} ${track.artist}")
            val affinity = computePersonalAffinity(track, profile)

            var score = 0f
            if (profile.hasPersonalSignals) {
                score += (profile.genreWeights[normGenre] ?: 0f) * 32f
                score += (profile.artistWeights[normArtist] ?: 0f) * 24f
                score += trackTokens.sumOf { (profile.tokenWeights[it] ?: 0f).toDouble() }.toFloat() * 14f
                score += when (profile.preferredLanguage) {
                    "ru" -> if (isCyr) 28f else -18f
                    "en" -> if (!isCyr) 24f else -16f
                    else -> 0f
                }
                // Strongly boost unheard tracks so "Для вас" differs cleanly from "Рекомендации"
                if (track.id !in allHistoryIds) score += 26f else score -= 34f
            } else {
                score += languageColdStartBonus(track, appLanguage)
                score += ln((track.playCount.coerceAtLeast(1) + 1).toDouble()).toFloat() * 2.5f
            }
            if (track.isLiked) score -= 8f // Let fresh tracks float above already-liked catalog
            score += ln((track.playCount.coerceAtLeast(1) + 1).toDouble()).toFloat() * 0.6f
            Triple(track, score, affinity)
        }

        val ordered = if (profile.hasPersonalSignals) {
            val relevant = scoredEntries
                .filter { it.third > 0f }
                .sortedByDescending { it.second }
                .map { it.first }
            val relevantIds = relevant.mapTo(HashSet()) { it.id }
            val langMatchedExtra = scoredEntries
                .filter { it.first.id !in relevantIds }
                .filter { entry ->
                    val isCyr = containsCyrillic("${entry.first.title} ${entry.first.artist}")
                    when (profile.preferredLanguage) {
                        "ru" -> isCyr
                        "en" -> !isCyr
                        else -> if (appLanguage.equals("ru", ignoreCase = true)) isCyr else true
                    }
                }
                .sortedByDescending { it.second }
                .map { it.first }
            (relevant + langMatchedExtra).ifEmpty {
                scoredEntries.sortedByDescending { it.second }.map { it.first }
            }
        } else {
            val langPreferred = scoredEntries
                .filter { languageColdStartBonus(it.first, appLanguage) >= 0f }
                .sortedByDescending { it.second }
                .map { it.first }
            val preferredIds = langPreferred.mapTo(HashSet()) { it.id }
            val rest = scoredEntries
                .filter { it.first.id !in preferredIds }
                .sortedByDescending { it.second }
                .map { it.first }
            langPreferred + rest
        }

        return pickDiverseTracks(ordered, limit = limit, maxPerArtist = 2)
    }

    private fun pickDiverseTracks(
        ordered: List<TrackEntity>,
        limit: Int,
        maxPerArtist: Int,
    ): List<TrackEntity> {
        val picked = mutableListOf<TrackEntity>()
        val seenTitles = HashSet<String>()
        val artistCounts = HashMap<String, Int>()
        for (track in ordered) {
            if (picked.size >= limit) break
            val titleKey = normalizeKey(track.title)
            if (titleKey.isNotBlank() && !seenTitles.add(titleKey)) continue
            val key = normalizeKey(track.artist.substringBefore(",").trim()).ifBlank { track.id }
            val count = artistCounts[key] ?: 0
            if (count < maxPerArtist) {
                picked += track
                artistCounts[key] = count + 1
            }
        }
        if (picked.size < limit) {
            val pickedIds = picked.mapTo(HashSet()) { it.id }
            for (track in ordered) {
                if (picked.size >= limit) break
                if (track.id !in pickedIds) {
                    picked += track
                    pickedIds += track.id
                }
            }
        }
        return picked
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
            .filter { !isBlockedRecommendationTrack(it) && !(it.isUserUpload && it.playCount < 10) }
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

        val cleanedWave = allTracks.filterNot { isBlockedRecommendationTrack(it) }
        if (cleanedWave.isEmpty()) return emptyList()
        val candidates = cleanedWave.filter { it.id !in excludeIds }
            .ifEmpty { cleanedWave }

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
