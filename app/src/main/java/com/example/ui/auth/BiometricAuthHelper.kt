package com.example.ui.auth

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

enum class BiometricAvailability {
    AVAILABLE,
    NONE_ENROLLED,
    UNAVAILABLE,
}

private const val ALLOWED_AUTHENTICATORS =
    BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK

tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
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
    title: String,
    subtitle: String,
    negativeButtonText: String,
    unavailableMessage: String,
    failedMessage: String,
    onAuthenticated: () -> Unit,
    onFallbackOrDismiss: () -> Unit,
    onError: (String) -> Unit,
) {
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
            onAuthenticated()
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
            // Keep prompt open; system shows shake/retry feedback automatically
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
