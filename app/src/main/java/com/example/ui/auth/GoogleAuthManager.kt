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
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialInterruptedException
import com.example.R
import com.example.SoundLapaApplication
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal fun FirebaseAuth.authStateFlow(): Flow<FirebaseUser?> = callbackFlow {
    trySend(currentUser)
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

    private val _currentUserState = MutableStateFlow(
        runCatching { Firebase.auth.currentUser }.getOrNull(),
    )

    @Volatile
    private var authListenerRegistered = false

    private val sharedAuthListener = FirebaseAuth.AuthStateListener { auth ->
        _currentUserState.value = auth.currentUser
    }

    fun observeCurrentUser(): StateFlow<FirebaseUser?> {
        ensureAuthListenerRegistered()
        refreshCurrentUser()
        return _currentUserState.asStateFlow()
    }

    fun refreshCurrentUser() {
        runCatching {
            _currentUserState.value = Firebase.auth.currentUser
        }
    }

    private fun ensureAuthListenerRegistered() {
        if (!authListenerRegistered) {
            synchronized(this) {
                if (!authListenerRegistered) {
                    runCatching {
                        Firebase.auth.addAuthStateListener(sharedAuthListener)
                        _currentUserState.value = Firebase.auth.currentUser
                        authListenerRegistered = true
                    }
                }
            }
        }
    }

    private fun extractGoogleIdToken(credential: androidx.credentials.Credential): String? {
        if (credential is CustomCredential &&
            (credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL ||
                credential.type.contains("googleid", ignoreCase = true))
        ) {
            return runCatching {
                GoogleIdTokenCredential.createFrom(credential.data).idToken
            }.getOrNull()
        }
        return null
    }

    fun attemptAutoSignIn(
        context: Context,
        credentialManager: CredentialManager,
        onAuthSuccess: () -> Unit,
        onUnauthenticated: () -> Unit,
        scope: CoroutineScope,
    ) {
        ensureAuthListenerRegistered()
        if (Firebase.auth.currentUser != null) {
            _currentUserState.value = Firebase.auth.currentUser
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

        val app = context.applicationContext as? SoundLapaApplication
        val runScope = app?.appScope ?: scope

        runScope.launch {
            try {
                val targetContext = context.findActivity() ?: context
                val manager = CredentialManager.create(targetContext)
                val result = manager.getCredential(targetContext, request)
                val googleIdToken = extractGoogleIdToken(result.credential)
                if (!googleIdToken.isNullOrBlank()) {
                    val authCredential = GoogleAuthProvider.getCredential(googleIdToken, null)
                    val signedIn = withContext(NonCancellable) {
                        withTimeout(25_000L) {
                            Firebase.auth.signInWithCredential(authCredential).await()
                        }
                    }
                    _currentUserState.value = signedIn.user ?: Firebase.auth.currentUser
                    app?.preferences?.setGuestMode(false)
                    withContext(Dispatchers.Main.immediate) {
                        onAuthSuccess()
                    }
                } else {
                    withContext(Dispatchers.Main.immediate) {
                        onUnauthenticated()
                    }
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main.immediate) {
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
        ensureAuthListenerRegistered()
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

        val app = context.applicationContext as? SoundLapaApplication
        val runScope = app?.appScope ?: scope
        val activeManager = runCatching { CredentialManager.create(activity) }.getOrDefault(credentialManager)

        runScope.launch {
            try {
                val signInOption = GetSignInWithGoogleOption.Builder(serverClientId = clientId).build()
                val primaryRequest = GetCredentialRequest.Builder()
                    .addCredentialOption(signInOption)
                    .build()

                val response = try {
                    activeManager.getCredential(activity, primaryRequest)
                } catch (cancel: GetCredentialCancellationException) {
                    throw cancel
                } catch (interrupted: GetCredentialInterruptedException) {
                    delay(350L)
                    activeManager.getCredential(activity, primaryRequest)
                } catch (primaryErr: GetCredentialException) {
                    Log.w(TAG, "Primary SignInWithGoogle flow failed (${primaryErr.type}), trying GoogleIdOption fallback")
                    val fallbackOption = GetGoogleIdOption.Builder()
                        .setFilterByAuthorizedAccounts(false)
                        .setServerClientId(clientId)
                        .setAutoSelectEnabled(false)
                        .build()
                    val fallbackRequest = GetCredentialRequest.Builder()
                        .addCredentialOption(fallbackOption)
                        .build()
                    activeManager.getCredential(activity, fallbackRequest)
                }

                val googleIdToken = extractGoogleIdToken(response.credential)
                if (!googleIdToken.isNullOrBlank()) {
                    val authCredential = GoogleAuthProvider.getCredential(googleIdToken, null)
                    val authResult = withContext(NonCancellable) {
                        withTimeout(25_000L) {
                            Firebase.auth.signInWithCredential(authCredential).await()
                        }
                    }
                    val signedInUser = authResult.user ?: Firebase.auth.currentUser
                    if (signedInUser == null) {
                        withContext(Dispatchers.Main.immediate) {
                            onAuthError("Firebase Sign-In did not return a user session")
                        }
                        return@launch
                    }
                    withContext(NonCancellable) {
                        app?.preferences?.setGuestMode(false)
                    }
                    _currentUserState.value = signedInUser
                    withContext(Dispatchers.Main.immediate) {
                        onAuthSuccess()
                    }
                } else {
                    withContext(Dispatchers.Main.immediate) {
                        onAuthError("Unexpected credential type: ${response.credential.type}")
                    }
                }
            } catch (e: GetCredentialCancellationException) {
                Log.w(TAG, "Google Sign-In flow cancelled or dismissed: ${e.message}", e)
                withContext(Dispatchers.Main.immediate) {
                    onAuthCancelled()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Google Sign-In failed", e)
                withContext(Dispatchers.Main.immediate) {
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
        ensureAuthListenerRegistered()
        scope.launch {
            withContext(NonCancellable) {
                try {
                    credentialManager.clearCredentialState(ClearCredentialStateRequest())
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to clear credential state: ${e.message}")
                } finally {
                    Firebase.auth.signOut()
                    _currentUserState.value = null
                    withContext(Dispatchers.Main.immediate) {
                        onSignOutComplete()
                    }
                }
            }
        }
    }
}
