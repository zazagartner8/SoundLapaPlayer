package com.example.data.remote

import android.graphics.Bitmap
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.HarmBlockThreshold
import com.google.firebase.ai.type.HarmCategory
import com.google.firebase.ai.type.SafetySetting
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Collections

sealed interface AvatarModerationVerdict {
    data object Safe : AvatarModerationVerdict
    data class Flagged18Plus(val reason: String) : AvatarModerationVerdict
    data class Error(val message: String) : AvatarModerationVerdict
}

object AvatarModerationService {
    private const val TAG = "AvatarModeration"
    private const val PRIMARY_MODEL_NAME = "gemini-3.5-flash-lite"
    private const val FALLBACK_MODEL_NAME = "gemini-3.8-flash"
    private const val MODERATION_TIMEOUT_MS = 18_000L
    private const val FALLBACK_TIMEOUT_MS = 22_000L
    private const val MAX_MODERATION_IMAGE_PX = 256

    const val ERR_APPCHECK = "moderation_appcheck_error"
    const val ERR_NETWORK = "moderation_network_error"
    const val ERR_TIMEOUT = "moderation_timeout"
    const val ERR_GENERIC = "moderation_error"

    private val verdictCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, AvatarModerationVerdict>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AvatarModerationVerdict>?): Boolean =
                size > 32
        },
    )

    private val moderationSchema = Schema.obj(
        properties = mapOf(
            "isUnsafeAdultContent" to Schema.boolean(
                description = "True if the image contains 18+ adult content, nudity, pornography, sexually explicit acts, hentai, erotic/NSFW imagery, or exposed intimate body parts.",
            ),
            "reason" to Schema.string(
                description = "Short neutral explanation of the moderation verdict.",
            ),
        ),
    )

    private val permissiveSafetySettings = listOf(
        SafetySetting(HarmCategory.HARASSMENT, HarmBlockThreshold.NONE),
        SafetySetting(HarmCategory.HATE_SPEECH, HarmBlockThreshold.NONE),
        SafetySetting(HarmCategory.SEXUALLY_EXPLICIT, HarmBlockThreshold.NONE),
        SafetySetting(HarmCategory.DANGEROUS_CONTENT, HarmBlockThreshold.NONE),
    )

    private fun buildModel(modelName: String) = Firebase.ai.generativeModel(
        modelName = modelName,
        generationConfig = generationConfig {
            responseMimeType = "application/json"
            responseSchema = moderationSchema
        },
        safetySettings = permissiveSafetySettings,
    )

    private val primaryModel by lazy { buildModel(PRIMARY_MODEL_NAME) }
    private val fallbackModel by lazy { buildModel(FALLBACK_MODEL_NAME) }

    suspend fun checkAvatarSafety(bitmap: Bitmap): AvatarModerationVerdict = withContext(Dispatchers.IO) {
        val preparedBitmap = prepareModerationBitmap(bitmap)
        val cacheKey = computeBitmapHash(preparedBitmap)
        if (cacheKey != null) {
            verdictCache[cacheKey]?.let { cached ->
                if (preparedBitmap !== bitmap && !preparedBitmap.isRecycled) {
                    preparedBitmap.recycle()
                }
                return@withContext cached
            }
        }
        try {
            val promptContent = content {
                image(preparedBitmap)
                text(
                    "Classify avatar for 18+/NSFW. Set isUnsafeAdultContent=true ONLY for explicit nudity, pornography, hentai, erotic/NSFW imagery, or exposed genitalia/breasts/buttocks. Portraits, art, anime faces, pets, objects are SAFE.",
                )
            }

            val firstAttempt = runCatching {
                withTimeout(MODERATION_TIMEOUT_MS) {
                    primaryModel.generateContent(promptContent)
                }
            }

            val response = firstAttempt.getOrElse { primaryErr ->
                if (primaryErr is CancellationException && primaryErr !is TimeoutCancellationException) {
                    throw primaryErr
                }
                Log.w(TAG, "Primary model ($PRIMARY_MODEL_NAME) failed, trying fallback ($FALLBACK_MODEL_NAME)", primaryErr)
                try {
                    withTimeout(FALLBACK_TIMEOUT_MS) {
                        fallbackModel.generateContent(promptContent)
                    }
                } catch (fallbackErr: TimeoutCancellationException) {
                    Log.e(TAG, "Avatar moderation timed out on fallback", fallbackErr)
                    return@withContext AvatarModerationVerdict.Error(ERR_TIMEOUT)
                } catch (fallbackErr: CancellationException) {
                    throw fallbackErr
                } catch (fallbackErr: Exception) {
                    Log.e(TAG, "Avatar moderation failed on fallback", fallbackErr)
                    return@withContext AvatarModerationVerdict.Error(classifyErrorCode(fallbackErr))
                }
            }

            val rawJson = response.text
            if (rawJson.isNullOrBlank()) {
                Log.e(TAG, "Avatar moderation returned empty response")
                AvatarModerationVerdict.Error(ERR_GENERIC)
            } else {
                val verdict = parseModerationVerdictJson(rawJson)
                if (cacheKey != null && verdict !is AvatarModerationVerdict.Error) {
                    verdictCache[cacheKey] = verdict
                }
                verdict
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Avatar moderation timed out", e)
            AvatarModerationVerdict.Error(ERR_TIMEOUT)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Avatar moderation failed", e)
            AvatarModerationVerdict.Error(classifyErrorCode(e))
        } finally {
            if (preparedBitmap !== bitmap && !preparedBitmap.isRecycled) {
                preparedBitmap.recycle()
            }
        }
    }

    private fun computeBitmapHash(bitmap: Bitmap): String? = runCatching {
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 75, baos)
        val digest = MessageDigest.getInstance("SHA-256").digest(baos.toByteArray())
        digest.joinToString("") { "%02x".format(it) }
    }.getOrNull()

    private fun prepareModerationBitmap(source: Bitmap): Bitmap {
        val software = if (source.config != Bitmap.Config.ARGB_8888) {
            source.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            source
        }
        val maxDim = maxOf(software.width, software.height)
        if (maxDim <= MAX_MODERATION_IMAGE_PX) return software
        val scale = MAX_MODERATION_IMAGE_PX.toFloat() / maxDim.toFloat()
        val w = (software.width * scale).toInt().coerceAtLeast(1)
        val h = (software.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(software, w, h, true).also { scaled ->
            if (software !== source && scaled !== software && !software.isRecycled) {
                software.recycle()
            }
        }
    }

    internal fun classifyErrorCode(error: Throwable): String {
        val msg = generateSequence(error) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" | ")
            .lowercase()
        return when {
            msg.contains("appcheck") || msg.contains("app check") || msg.contains("403") || msg.contains("attestation") ->
                ERR_APPCHECK
            msg.contains("timeout") || msg.contains("timed out") ->
                ERR_TIMEOUT
            msg.contains("unable to resolve host") || msg.contains("network") || msg.contains("failed to connect") ||
                msg.contains("socket") || msg.contains("unknownhost") || msg.contains("ioexception") ->
                ERR_NETWORK
            else -> ERR_GENERIC
        }
    }

    fun parseModerationVerdictJson(rawJson: String): AvatarModerationVerdict {
        return try {
            val cleaned = rawJson
                .trim()
                .removePrefix("```json")
                .removePrefix("```JSON")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
            val jsonSlice = if (!cleaned.startsWith("{")) {
                val start = cleaned.indexOf('{')
                val end = cleaned.lastIndexOf('}')
                if (start >= 0 && end > start) cleaned.substring(start, end + 1) else cleaned
            } else {
                cleaned
            }
            val obj = JSONObject(jsonSlice)
            val isUnsafe = obj.optBoolean("isUnsafeAdultContent", false)
            val reason = obj.optString("reason", "").trim()
            if (isUnsafe) {
                AvatarModerationVerdict.Flagged18Plus(
                    reason.ifBlank { "18+ / NSFW content detected" },
                )
            } else {
                AvatarModerationVerdict.Safe
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse moderation response: $rawJson", e)
            AvatarModerationVerdict.Error(ERR_GENERIC)
        }
    }
}
