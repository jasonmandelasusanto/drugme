package com.drugme.app.data.auth

import android.content.Context
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.drugme.app.R
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/** Who is signed in, if anyone. */
data class AuthUser(
    val uid: String,
    val email: String?,
    val displayName: String?,
    val photoUrl: String?,
)

sealed interface SignInResult {
    data class Success(val user: AuthUser) : SignInResult

    /** User dismissed the sheet. Not an error — must not surface as one. */
    data object Cancelled : SignInResult

    /** No Google account on the device, or none available to share. */
    data object NoAccounts : SignInResult
    data class Failure(val message: String, val cause: Throwable? = null) : SignInResult
}

@Singleton
class AuthRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val firebaseAuth: FirebaseAuth,
) {

    private val credentialManager = CredentialManager.create(context)

    val currentUser: AuthUser? get() = firebaseAuth.currentUser?.toAuthUser()

    /** Emits on sign-in and sign-out, including token revocation from outside the app. */
    val authState: Flow<AuthUser?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { auth ->
            trySend(auth.currentUser?.toAuthUser())
        }
        firebaseAuth.addAuthStateListener(listener)
        awaitClose { firebaseAuth.removeAuthStateListener(listener) }
    }

    /**
     * Google sign-in via Credential Manager.
     *
     * [activityContext] must be an Activity — Credential Manager renders a system sheet and
     * an application context cannot host it.
     *
     * Requires the app's SHA-1 to be registered in the Firebase console. When it isn't,
     * Play services rejects the request and the user simply sees the sheet vanish, which
     * is why the failure path below is explicit about the cause.
     */
    suspend fun signInWithGoogle(activityContext: Context): SignInResult {
        val webClientId = context.getString(R.string.default_web_client_id)

        // A nonce binds this request to this attempt, so a captured ID token cannot be
        // replayed into a later sign-in.
        val rawNonce = generateNonce()
        val hashedNonce = sha256(rawNonce)

        val googleIdOption = GetGoogleIdOption.Builder()
            // false so the sheet also offers accounts that have never used this app;
            // filtering to authorized accounts shows an empty sheet on first run.
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(webClientId)
            .setAutoSelectEnabled(false)
            .setNonce(hashedNonce)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        return try {
            val response = credentialManager.getCredential(activityContext, request)
            val credential = response.credential

            if (credential !is CustomCredential ||
                credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                return SignInResult.Failure("Unexpected credential type: ${credential.type}")
            }

            val googleToken = GoogleIdTokenCredential.createFrom(credential.data)
            val firebaseCredential = GoogleAuthProvider.getCredential(googleToken.idToken, null)
            val result = firebaseAuth.signInWithCredential(firebaseCredential).await()

            val user = result.user?.toAuthUser()
                ?: return SignInResult.Failure("Signed in but no user returned")

            Log.i(TAG, "Signed in uid=${user.uid}")
            SignInResult.Success(user)
        } catch (e: GetCredentialCancellationException) {
            SignInResult.Cancelled
        } catch (e: NoCredentialException) {
            SignInResult.NoAccounts
        } catch (e: GetCredentialException) {
            // The usual cause is an unregistered SHA-1 — say so rather than leaving the
            // user staring at a generic failure.
            Log.e(TAG, "Credential Manager failed", e)
            SignInResult.Failure(
                "Google sign-in was rejected. If this persists, the app's signing " +
                    "certificate may not be registered with the project.",
                e,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Sign-in failed", t)
            SignInResult.Failure(t.message ?: "Sign-in failed", t)
        }
    }

    /**
     * Signs out of Firebase and clears the credential provider's state.
     *
     * Local data is deliberately left alone: it is the user's, it works offline, and
     * signing out is not a request to delete their medication history. Wiping the DEK
     * cache is handled by the crypto layer.
     */
    suspend fun signOut() {
        firebaseAuth.signOut()
        runCatching {
            credentialManager.clearCredentialState(
                androidx.credentials.ClearCredentialStateRequest()
            )
        }.onFailure { Log.w(TAG, "clearCredentialState failed", it) }
    }

    private fun com.google.firebase.auth.FirebaseUser.toAuthUser() = AuthUser(
        uid = uid,
        email = email,
        displayName = displayName,
        photoUrl = photoUrl?.toString(),
    )

    private fun generateNonce(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun sha256(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "AuthRepository"
    }
}
