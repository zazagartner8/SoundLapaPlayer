package com.example.ui.auth

import android.content.Context
import android.content.ContextWrapper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.example.data.local.SoundLapUidFormatter
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.KeyGenerator

enum class BiometricAvailability {
    AVAILABLE,
    NONE_ENROLLED,
    UNAVAILABLE,
}

private const val ALLOWED_AUTHENTICATORS =
    BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK

private const val DEV_EMAIL_SHA256 = "15f1e7a5e2780e58b8f5fdb2bf5fd811e841467762a079169d03c1d8552479d3"
const val DEVELOPER_ACCOUNT_UID = "SL-D1E6-EE60"
const val MODERATOR_ACCOUNT_UID = "SL-C526-964C"
const val SECOND_MODERATOR_ACCOUNT_UID = "SL-6721-BEED"
val MODERATOR_ACCOUNT_UIDS: Set<String> = setOf(
    MODERATOR_ACCOUNT_UID,
    SECOND_MODERATOR_ACCOUNT_UID,
)

enum class AdminBiometricScope {
    DEVELOPER_ONLY,
    MODERATOR_OR_DEVELOPER,
}

private const val DEV_KEYSTORE_PROVIDER = "AndroidKeyStore"
private const val DEV_KEY_ALIAS_PREFIX = "soundlap_dev_bio_v1_"

tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}

fun sha256NormalizedHex(value: String?): String {
    val normalized = value?.trim()?.lowercase().orEmpty()
    if (normalized.isEmpty()) return ""
    val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}

fun isAllowedDeveloperEmail(email: String?): Boolean =
    sha256NormalizedHex(email) == DEV_EMAIL_SHA256

fun isVerifiedDeveloperUid(firebaseUid: String?): Boolean {
    val clean = firebaseUid?.trim().orEmpty()
    if (clean.isEmpty()) return false
    return SoundLapUidFormatter.format(clean).equals(DEVELOPER_ACCOUNT_UID, ignoreCase = true)
}

fun isVerifiedModeratorUid(firebaseUid: String?): Boolean {
    val clean = firebaseUid?.trim().orEmpty()
    if (clean.isEmpty()) return false
    val formatted = SoundLapUidFormatter.format(clean).uppercase()
    return formatted in MODERATOR_ACCOUNT_UIDS
}

fun isVerifiedModeratorAccountUid(accountUid: String?): Boolean {
    val clean = accountUid?.trim()?.uppercase().orEmpty()
    return clean.isNotEmpty() && clean in MODERATOR_ACCOUNT_UIDS
}

fun isVerifiedDeveloperAccountUid(accountUid: String?): Boolean =
    accountUid?.trim()?.equals(DEVELOPER_ACCOUNT_UID, ignoreCase = true) == true

fun isVerifiedDeveloperAccount(firebaseUid: String?, email: String?): Boolean =
    isAllowedDeveloperEmail(email) || isVerifiedDeveloperUid(firebaseUid)

fun isAdminPanelAccount(firebaseUid: String?, email: String?): Boolean =
    isVerifiedDeveloperAccount(firebaseUid, email) || isVerifiedModeratorUid(firebaseUid)

fun isAuthorizedForBiometricScope(
    scope: AdminBiometricScope,
    firebaseUid: String?,
    email: String?,
): Boolean = when (scope) {
    AdminBiometricScope.DEVELOPER_ONLY -> isVerifiedDeveloperAccount(firebaseUid, email)
    AdminBiometricScope.MODERATOR_OR_DEVELOPER -> isAdminPanelAccount(firebaseUid, email)
}

fun hasProvisionedDeveloperBiometricKey(uid: String?): Boolean {
    val cleanUid = uid?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    return runCatching {
        val ks = KeyStore.getInstance(DEV_KEYSTORE_PROVIDER).apply { load(null) }
        ks.containsAlias("$DEV_KEY_ALIAS_PREFIX$cleanUid")
    }.getOrDefault(false)
}

fun provisionDeveloperBiometricKey(uid: String, email: String?): Boolean {
    if (!isAdminPanelAccount(uid, email)) return false
    val cleanUid = uid.trim().takeIf { it.isNotEmpty() } ?: return false
    return runCatching {
        val alias = "$DEV_KEY_ALIAS_PREFIX$cleanUid"
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, DEV_KEYSTORE_PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        keyGenerator.generateKey()
        true
    }.getOrDefault(false)
}

fun revokeDeveloperBiometricKey(uid: String?) {
    val cleanUid = uid?.trim()?.takeIf { it.isNotEmpty() } ?: return
    runCatching {
        val ks = KeyStore.getInstance(DEV_KEYSTORE_PROVIDER).apply { load(null) }
        val alias = "$DEV_KEY_ALIAS_PREFIX$cleanUid"
        if (ks.containsAlias(alias)) {
            ks.deleteEntry(alias)
        }
    }
}

fun checkBiometricAvailability(context: Context): BiometricAvailability {
    val manager = BiometricManager.from(context)
    return when (manager.canAuthenticate(ALLOWED_AUTHENTICATORS)) {
        BiometricManager.BIOMETRIC_SUCCESS -> BiometricAvailability.AVAILABLE
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricAvailability.NONE_ENROLLED
        else -> BiometricAvailability.UNAVAILABLE
    }
}

fun launchDeveloperBiometricPrompt(
    context: Context,
    signedInUid: String?,
    signedInEmail: String?,
    title: String,
    subtitle: String,
    negativeButtonText: String,
    unavailableMessage: String,
    failedMessage: String,
    scope: AdminBiometricScope = AdminBiometricScope.MODERATOR_OR_DEVELOPER,
    onAuthenticated: () -> Unit,
    onDismissed: () -> Unit,
    onError: (String) -> Unit,
) {
    if (!isAuthorizedForBiometricScope(scope, signedInUid, signedInEmail) || signedInUid.isNullOrBlank()) {
        onError(failedMessage)
        return
    }
    if (!hasProvisionedDeveloperBiometricKey(signedInUid)) {
        provisionDeveloperBiometricKey(signedInUid, signedInEmail)
    }
    if (!hasProvisionedDeveloperBiometricKey(signedInUid)) {
        onError(failedMessage)
        return
    }
    val activity = context.findFragmentActivity()
    if (activity == null || activity.isFinishing || activity.isDestroyed) {
        onError(unavailableMessage)
        return
    }
    if (checkBiometricAvailability(context) != BiometricAvailability.AVAILABLE) {
        onError(unavailableMessage)
        return
    }

    val executor = ContextCompat.getMainExecutor(activity)
    val callback = object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            super.onAuthenticationSucceeded(result)
            if (isAuthorizedForBiometricScope(scope, signedInUid, signedInEmail) &&
                hasProvisionedDeveloperBiometricKey(signedInUid)
            ) {
                onAuthenticated()
            } else {
                onError(failedMessage)
            }
        }

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            super.onAuthenticationError(errorCode, errString)
            when (errorCode) {
                BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                BiometricPrompt.ERROR_USER_CANCELED,
                BiometricPrompt.ERROR_CANCELED,
                -> onDismissed()
                else -> onError(errString.toString().ifBlank { failedMessage })
            }
        }

        override fun onAuthenticationFailed() {
            super.onAuthenticationFailed()
        }
    }

    val prompt = BiometricPrompt(activity, executor, callback)
    val promptInfo = BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .setSubtitle(subtitle)
        .setNegativeButtonText(negativeButtonText)
        .setAllowedAuthenticators(ALLOWED_AUTHENTICATORS)
        .build()

    prompt.authenticate(promptInfo)
}
