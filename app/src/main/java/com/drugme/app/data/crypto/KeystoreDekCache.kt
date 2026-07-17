package com.drugme.app.data.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

// Separate DataStore from settings: this holds key material and should not sit in the
// same file as UI preferences.
private val Context.vaultStore: DataStore<Preferences> by preferencesDataStore(name = "vault")

/**
 * Keeps the DEK usable across app restarts without re-asking for the passphrase.
 *
 * The DEK is stored wrapped by a hardware-backed Android Keystore key that cannot be
 * exported — so the cached blob is worthless off-device, and worthless to any other app on
 * it. Without this, every cold start would demand the passphrase, and an app that nags
 * hourly trains people to pick a weak one.
 *
 * This is a convenience cache, never the source of truth: the authoritative wrapped DEKs
 * live in Firestore under the passphrase and recovery code. Losing this cache costs an
 * unlock prompt, nothing more.
 */
@Singleton
class KeystoreDekCache @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    suspend fun store(dek: ByteArray) {
        runCatching {
            val cipher = Cipher.getInstance(TRANSFORM).apply {
                init(Cipher.ENCRYPT_MODE, keystoreKey())
            }
            val ct = cipher.doFinal(dek)
            context.vaultStore.edit {
                it[KEY_NONCE] = Envelope.b64(cipher.iv)
                it[KEY_BLOB] = Envelope.b64(ct)
            }
        }.onFailure { Log.w(TAG, "Could not cache DEK; user will re-enter passphrase", it) }
    }

    suspend fun load(): ByteArray? = runCatching {
        val prefs = context.vaultStore.data.first()
        val nonce = prefs[KEY_NONCE]?.let(Envelope::unb64) ?: return null
        val blob = prefs[KEY_BLOB]?.let(Envelope::unb64) ?: return null

        val cipher = Cipher.getInstance(TRANSFORM).apply {
            init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, nonce))
        }
        cipher.doFinal(blob)
    }.getOrElse {
        // Keystore keys are invalidated by events like the user removing their screen lock.
        // That's recoverable — drop the cache and fall back to the passphrase.
        Log.w(TAG, "Cached DEK unreadable; clearing", it)
        clear()
        null
    }

    suspend fun clear() {
        runCatching {
            context.vaultStore.edit { it.remove(KEY_NONCE); it.remove(KEY_BLOB) }
        }
    }

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                // Deliberately NOT setUserAuthenticationRequired(true): the alarm path must
                // never touch this, and requiring auth would also break the cache for users
                // with no screen lock. Confidentiality against the server — the actual
                // threat model — is carried by the passphrase-wrapped copy in Firestore.
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val TAG = "KeystoreDekCache"
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "drugme_dek_cache"
        const val TRANSFORM = "AES/GCM/NoPadding"
        val KEY_NONCE = stringPreferencesKey("dek_nonce")
        val KEY_BLOB = stringPreferencesKey("dek_blob")
    }
}
