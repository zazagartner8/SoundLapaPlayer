package com.example.ui.auth

import android.content.Context
import android.content.ContextWrapper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.KeyStore
import javax.crypto.KeyGenerator

enum class BiometricAvailability {
    AVAILABLE,
    NONE_ENROLLED,
    UNAVAILABLE,
}

private const val ALLOWED_AUTHENTICATORS =
    BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK

private const val DEV_ALLOWED_EMAIL = "livex0464@gmail.com"
private const val DEV_KEYSTORE_PROVIDER = "AndroidKeyStore"
private const val DEV_KEY_ALIAS_PREFIX = "soundlap_dev_bio_v1_"

tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}

fun isAllowedDeveloperEmail(email: String?): Boolean =
    email?.trim()?.equals(DEV_ALLOWED_EMAIL, ignoreCase = true) == true

fun hasProvisionedDeveloperBiometricKey(uid: String?): Boolean {
    val cleanUid = uid?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    return runCatching {
        val ks = KeyStore.getInstance(DEV_KEYSTORE_PROVIDER).apply { load(null) }
        ks.containsAlias("$DEV_KEY_ALIAS_PREFIX$cleanUid")
    }.getOrDefault(false)
}

fun provisionDeveloperBiometricKey(uid: String, email: String?): Boolean {
    if (!isAllowedDeveloperEmail(email)) return false
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
    activatedDevUid: String?,
    title: String,
    subtitle: String,
    negativeButtonText: String,
    unavailableMessage: String,
    failedMessage: String,
    onAuthenticated: () -> Unit,
    onFallbackOrDismiss: () -> Unit,
    onError: (String) -> Unit,
) {
    if (!isAllowedDeveloperEmail(signedInEmail) ||
        signedInUid.isNullOrBlank() ||
        activatedDevUid != signedInUid ||
        !hasProvisionedDeveloperBiometricKey(signedInUid)
    ) {
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
            if (isAllowedDeveloperEmail(signedInEmail) &&
                activatedDevUid == signedInUid &&
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
                -> onFallbackOrDismiss()
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
