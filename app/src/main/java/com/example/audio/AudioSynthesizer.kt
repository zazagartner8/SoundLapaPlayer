package com.example.audio

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

object AudioSynthesizer {

    private const val SAMPLE_RATE = 22050

    data class SynthPreset(
        val id: String,
        val title: String,
        val artist: String,
        val genre: String,
        val description: String,
        val durationSeconds: Int,
        val bpm: Double,
        val rootHz: Double,
        val chordOffsets: List<Int>,
        val accentColorHex: Long,
        val secondaryColorHex: Long,
        val playCount: Int,
        val likeCount: Int,
        val seedMode: Int,
    )

    val starterPresets = listOf(
        SynthPreset(
            id = "track_terracotta_dusk",
            title = "Terracotta Dusk",
            artist = "Lapa Beats",
            genre = "Синтвейв",
            description = "Тёплый аналоговый бас и закатные арпеджио на 108 BPM.",
            durationSeconds = 22,
            bpm = 108.0,
            rootHz = 146.83, // D3
            chordOffsets = listOf(0, 3, 7, 10),
            accentColorHex = 0xFFFF5500,
            secondaryColorHex = 0xFF9C2B00,
            playCount = 1420,
            likeCount = 284,
            seedMode = 0,
        ),
        SynthPreset(
            id = "track_liquid_horizon",
            title = "Liquid Glass Horizon",
            artist = "Mila K.",
            genre = "Электроника",
            description = "Кристальные колокольчики и глубокий саб-пульс в стиле жидкого стекла.",
            durationSeconds = 20,
            bpm = 120.0,
            rootHz = 164.81, // E3
            chordOffsets = listOf(0, 4, 7, 11),
            accentColorHex = 0xFF5CE1E6,
            secondaryColorHex = 0xFFA78BFA,
            playCount = 980,
            likeCount = 196,
            seedMode = 1,
        ),
        SynthPreset(
            id = "track_night_tram",
            title = "Night Tram 04",
            artist = "Koto & Moss",
            genre = "Lo-Fi",
            description = "Мягкие септаккорды ночного трамвая для фокуса и поздних прогулок.",
            durationSeconds = 24,
            bpm = 84.0,
            rootHz = 130.81, // C3
            chordOffsets = listOf(0, 3, 7, 10),
            accentColorHex = 0xFFFF9248,
            secondaryColorHex = 0xFFFB7185,
            playCount = 2310,
            likeCount = 512,
            seedMode = 2,
        ),
        SynthPreset(
            id = "track_neon_pawprint",
            title = "Neon Pawprint",
            artist = "Sora Echo",
            genre = "Электроника",
            description = "Энергичный грув с плавающим фильтром и упругим битом.",
            durationSeconds = 18,
            bpm = 126.0,
            rootHz = 174.61, // F3
            chordOffsets = listOf(0, 5, 7, 12),
            accentColorHex = 0xFFA78BFA,
            secondaryColorHex = 0xFFFF5500,
            playCount = 764,
            likeCount = 149,
            seedMode = 3,
        ),
        SynthPreset(
            id = "track_amber_fog",
            title = "Amber Fog",
            artist = "Vesper Lab",
            genre = "Амбиент",
            description = "Медленно перетекающие обертоны и прозрачная атмосфера.",
            durationSeconds = 24,
            bpm = 72.0,
            rootHz = 110.0, // A2
            chordOffsets = listOf(0, 7, 12, 16),
            accentColorHex = 0xFF34D399,
            secondaryColorHex = 0xFF5CE1E6,
            playCount = 615,
            likeCount = 138,
            seedMode = 4,
        ),
        SynthPreset(
            id = "track_velvet_static",
            title = "Velvet Static",
            artist = "Deniz R.",
            genre = "Инди",
            description = "Тёплый щипковый синт и лёгкий перегруз в инди-стилистике.",
            durationSeconds = 20,
            bpm = 96.0,
            rootHz = 196.0, // G3
            chordOffsets = listOf(0, 3, 7, 14),
            accentColorHex = 0xFFFB7185,
            secondaryColorHex = 0xFFFF9248,
            playCount = 1105,
            likeCount = 241,
            seedMode = 5,
        ),
    )

    fun generateWavTrack(preset: SynthPreset, targetFile: File): List<Float> {
        val totalSamples = SAMPLE_RATE * preset.durationSeconds
        val pcmSamples = ShortArray(totalSamples)
        val beatDurationSec = 60.0 / preset.bpm
        val progression = listOf(0, -2, 3, -5)

        val peaksBuckets = 64
        val bucketSize = (totalSamples / peaksBuckets).coerceAtLeast(1)
        val bucketEnergy = DoubleArray(peaksBuckets)
        val bucketCounts = IntArray(peaksBuckets)

        for (i in 0 until totalSamples) {
            val t = i.toDouble() / SAMPLE_RATE
            val beatPos = t / beatDurationSec
            val barIndex = (beatPos / 4.0).toInt()
            val beatFrac = beatPos - beatPos.toInt()
            val semitoneShift = progression[barIndex % progression.size]
            val chordBaseHz = preset.rootHz * Math.pow(2.0, semitoneShift / 12.0)

            // Warm sub-bass with gentle rhythmic pump
            val pump = if (preset.seedMode % 2 == 0) {
                0.55 + 0.45 * sin(PI * beatFrac)
            } else {
                0.75 + 0.25 * sin(2.0 * PI * beatFrac)
            }
            val subBass = sin(2.0 * PI * (chordBaseHz * 0.5) * t) * 0.34 * pump

            // Lush detuned chord pad
            var pad = 0.0
            for (idx in preset.chordOffsets.indices) {
                val freq = chordBaseHz * Math.pow(2.0, preset.chordOffsets[idx] / 12.0)
                val detune = 1.0 + (idx - 1.5) * 0.0022
                pad += sin(2.0 * PI * freq * detune * t) * (0.14 / (1.0 + idx * 0.2))
            }

            // Melodic arpeggio pluck
            val stepIndex = (beatPos * 2.0).toInt()
            val stepFrac = (beatPos * 2.0) - stepIndex
            val arpNoteOffset = preset.chordOffsets[stepIndex % preset.chordOffsets.size] + 12
            val arpFreq = chordBaseHz * Math.pow(2.0, arpNoteOffset / 12.0)
            val arpEnv = exp(-stepFrac * (3.2 + (preset.seedMode % 3) * 0.9))
            val arp = (sin(2.0 * PI * arpFreq * t) + 0.3 * sin(4.0 * PI * arpFreq * t)) * 0.22 * arpEnv

            // Soft rhythmic kick pulse on quarter beats
            val kickEnv = exp(-beatFrac * 14.0)
            val kickFreq = 48.0 + 95.0 * exp(-beatFrac * 24.0)
            val kick = if (preset.seedMode != 4) {
                sin(2.0 * PI * kickFreq * beatFrac * beatDurationSec) * 0.28 * kickEnv
            } else {
                0.0
            }

            // Global fade-in / fade-out envelope (0.6s)
            val fadeSamples = (SAMPLE_RATE * 0.6).toInt()
            val globalEnv = when {
                i < fadeSamples -> i.toDouble() / fadeSamples
                i > totalSamples - fadeSamples -> (totalSamples - i).toDouble() / fadeSamples
                else -> 1.0
            }

            val mixed = ((subBass + pad + arp + kick) * globalEnv).coerceIn(-0.95, 0.95)
            val sampleShort = (mixed * Short.MAX_VALUE).toInt().toShort()
            pcmSamples[i] = sampleShort

            val bucket = (i / bucketSize).coerceAtMost(peaksBuckets - 1)
            bucketEnergy[bucket] += mixed * mixed
            bucketCounts[bucket] += 1
        }

        writeWavFile(targetFile, pcmSamples)

        val rmsList = List(peaksBuckets) { b ->
            val count = bucketCounts[b].coerceAtLeast(1)
            sqrt(bucketEnergy[b] / count).toFloat()
        }
        val maxRms = (rmsList.maxOrNull() ?: 1f).coerceAtLeast(0.001f)
        return rmsList.map { (it / maxRms).coerceIn(0.14f, 1f) }
    }

    private fun writeWavFile(file: File, pcm: ShortArray) {
        file.parentFile?.mkdirs()
        val dataSize = pcm.size * 2
        val totalFileSize = 36 + dataSize

        BufferedOutputStream(FileOutputStream(file)).use { out ->
            fun writeString(s: String) {
                out.write(s.toByteArray(Charsets.US_ASCII))
            }

            fun writeIntLE(v: Int) {
                out.write(v and 0xFF)
                out.write((v shr 8) and 0xFF)
                out.write((v shr 16) and 0xFF)
                out.write((v shr 24) and 0xFF)
            }

            fun writeShortLE(v: Short) {
                val i = v.toInt()
                out.write(i and 0xFF)
                out.write((i shr 8) and 0xFF)
            }

            writeString("RIFF")
            writeIntLE(totalFileSize)
            writeString("WAVE")
            writeString("fmt ")
            writeIntLE(16) // Subchunk1Size for PCM
            writeShortLE(1) // AudioFormat = PCM
            writeShortLE(1) // NumChannels = Mono
            writeIntLE(SAMPLE_RATE)
            writeIntLE(SAMPLE_RATE * 2) // ByteRate
            writeShortLE(2) // BlockAlign
            writeShortLE(16) // BitsPerSample
            writeString("data")
            writeIntLE(dataSize)

            for (sample in pcm) {
                writeShortLE(sample)
            }
        }
    }
}
