package com.example.data.remote

import android.graphics.Bitmap
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

sealed interface AvatarModerationVerdict {
    data object Safe : AvatarModerationVerdict
    data class Flagged18Plus(val reason: String) : AvatarModerationVerdict
    data class Error(val message: String) : AvatarModerationVerdict
}

object AvatarModerationService {
    private const val TAG = "AvatarModeration"
    private const val MODEL_NAME = "gemini-3.8-flash"
    private const val MODERATION_TIMEOUT_MS = 45_000L

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

    private val model by lazy {
        Firebase.ai.generativeModel(
            modelName = MODEL_NAME,
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = moderationSchema
            },
        )
    }

    suspend fun checkAvatarSafety(bitmap: Bitmap): AvatarModerationVerdict {
        return try {
            val response = withTimeout(MODERATION_TIMEOUT_MS) {
                model.generateContent(
                    content {
                        image(bitmap)
                        text(
                            "Analyze this profile avatar image for 18+ / NSFW content moderation. " +
                                "Set isUnsafeAdultContent=true if it contains nudity, sexually explicit acts, " +
                                "pornography, hentai, erotic/NSFW artwork, exposed genitalia/breasts/buttocks, " +
                                "or explicit 18+ adult material. Normal portraits, selfies, musicians, album covers " +
                                "without explicit nudity, landscapes, pets, cartoons, and abstract art are safe (isUnsafeAdultContent=false).",
                        )
                    },
                )
            }
            val rawJson = response.text
            if (rawJson.isNullOrBlank()) {
                Log.e(TAG, "Avatar moderation returned empty response")
                AvatarModerationVerdict.Error("Moderation service returned an empty response.")
            } else {
                parseModerationVerdictJson(rawJson)
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Avatar moderation timed out", e)
            AvatarModerationVerdict.Error("Moderation timed out. Please try again.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Avatar moderation failed", e)
            AvatarModerationVerdict.Error(e.localizedMessage ?: "Failed to verify avatar safety.")
        }
    }

    fun parseModerationVerdictJson(rawJson: String): AvatarModerationVerdict {
        return try {
            val obj = JSONObject(rawJson)
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
            AvatarModerationVerdict.Error("Invalid moderation response.")
        }
    }
}
