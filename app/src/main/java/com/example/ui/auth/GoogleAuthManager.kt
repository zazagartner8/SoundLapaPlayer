package com.example.ui.auth

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.example.R
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

internal fun FirebaseAuth.authStateFlow(): Flow<FirebaseUser?> = callbackFlow {
    val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
        trySend(firebaseAuth.currentUser)
    }
    addAuthStateListener(listener)
    awaitClose { removeAuthStateListener(listener) }
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

object GoogleAuthManager {

    private const val TAG = "Auth"

    fun attemptAutoSignIn(
        context: Context,
        credentialManager: CredentialManager,
        onAuthSuccess: () -> Unit,
        onUnauthenticated: () -> Unit,
        scope: CoroutineScope,
    ) {
        if (Firebase.auth.currentUser != null) {
            onAuthSuccess()
            return
        }
        val clientId = try {
            context.getString(R.string.default_web_client_id)
        } catch (_: Exception) {
            onUnauthenticated()
            return
        }

        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(true)
            .setServerClientId(clientId)
            .setAutoSelectEnabled(true)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        scope.launch {
            try {
                val targetContext = context.findActivity() ?: context
                val result = credentialManager.getCredential(targetContext, request)
                val credential = result.credential
                if (credential is CustomCredential && credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
                    val authCredential = GoogleAuthProvider.getCredential(googleIdToken, null)
                    withContext(kotlinx.coroutines.NonCancellable) {
                        Firebase.auth.signInWithCredential(authCredential).await()
                    }
                    withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                        onAuthSuccess()
                    }
                } else {
                    withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                        onUnauthenticated()
                    }
                }
            } catch (_: Exception) {
                withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                    onUnauthenticated()
                }
            }
        }
    }

    fun onGoogleSignInClicked(
        context: Context,
        credentialManager: CredentialManager,
        onAuthSuccess: () -> Unit,
        onAuthError: (String) -> Unit,
        scope: CoroutineScope,
        onAuthCancelled: () -> Unit = {},
    ) {
        val clientId = try {
            context.getString(R.string.default_web_client_id)
        } catch (_: Exception) {
            onAuthError("Google Sign-In configuration missing: default_web_client_id not found")
            return
        }

        val activity = context.findActivity()
        if (activity == null) {
            onAuthError("Activity context required for Google Sign-In")
            return
        }

        scope.launch {
            try {
                val signInOption = GetSignInWithGoogleOption.Builder(serverClientId = clientId).build()
                val primaryRequest = GetCredentialRequest.Builder()
                    .addCredentialOption(signInOption)
                    .build()

                val response = try {
                    credentialManager.getCredential(activity, primaryRequest)
                } catch (cancel: GetCredentialCancellationException) {
                    throw cancel
                } catch (primaryErr: androidx.credentials.exceptions.GetCredentialException) {
                    Log.w(TAG, "Primary SignInWithGoogle flow failed (${primaryErr.type}), trying GoogleIdOption fallback")
                    val fallbackOption = GetGoogleIdOption.Builder()
                        .setFilterByAuthorizedAccounts(false)
                        .setServerClientId(clientId)
                        .setAutoSelectEnabled(false)
                        .build()
                    val fallbackRequest = GetCredentialRequest.Builder()
                        .addCredentialOption(fallbackOption)
                        .build()
                    credentialManager.getCredential(activity, fallbackRequest)
                }

                val credential = response.credential
                if (credential is CustomCredential && credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
                    val authCredential = GoogleAuthProvider.getCredential(googleIdToken, null)
                    withContext(kotlinx.coroutines.NonCancellable) {
                        Firebase.auth.signInWithCredential(authCredential).await()
                    }
                    withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                        onAuthSuccess()
                    }
                } else {
                    withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                        onAuthError("Unexpected credential type: ${credential.type}")
                    }
                }
            } catch (e: GetCredentialCancellationException) {
                Log.w(TAG, "Google Sign-In flow cancelled or dismissed: ${e.message}", e)
                withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                    onAuthCancelled()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Google Sign-In failed", e)
                withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                    onAuthError(e.localizedMessage ?: "Sign in failed")
                }
            }
        }
    }

    fun signOut(
        credentialManager: CredentialManager,
        scope: CoroutineScope,
        onSignOutComplete: () -> Unit = {},
    ) {
        scope.launch {
            withContext(NonCancellable) {
                try {
                    credentialManager.clearCredentialState(ClearCredentialStateRequest())
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to clear credential state: ${e.message}")
                } finally {
                    Firebase.auth.signOut()
                    onSignOutComplete()
                }
            }
        }
    }
}
