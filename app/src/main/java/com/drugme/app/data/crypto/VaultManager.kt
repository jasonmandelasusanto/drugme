package com.drugme.app.data.crypto

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Both wrapped copies of one DEK. Either opens it; neither reveals it. */
@Serializable
data class VaultKeyDoc(
    val passphrase: WrappedKey,
    val recovery: WrappedKey,
    val version: Int = 1,
)

sealed interface VaultState {
    /** No vault exists for this user yet — first sign-in on any device. */
    data object NeedsSetup : VaultState

    /** A vault exists but the DEK isn't in memory; passphrase or recovery code required. */
    data object Locked : VaultState

    data object Unlocked : VaultState

    /** Signed out, or never signed in. Sync is simply off; local data still works. */
    data object NoUser : VaultState
}

/**
 * Owns the data-encryption key: creation, unlocking, caching, and destruction.
 *
 * The guarantee this class exists to keep: the server stores only ciphertext and two
 * wrapped copies of a key it cannot derive. Neither Google nor the developer can read a
 * user's medications. Both secrets lost means the data is gone — permanently, for
 * everyone, including us. That is the design working, not failing.
 */
@Singleton
class VaultManager @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val envelope: Envelope,
    private val kdf: Argon2Kdf,
    private val dekCache: KeystoreDekCache,
    private val recoveryCodes: RecoveryCodeGenerator,
) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The live DEK. Held only in memory and only while unlocked.
     *
     * Not exposed: callers get seal/open, never the key itself, so there is exactly one
     * place that can leak it.
     */
    private var dek: ByteArray? = null

    private val _state = MutableStateFlow<VaultState>(VaultState.NoUser)
    val state: StateFlow<VaultState> = _state.asStateFlow()

    val isUnlocked: Boolean get() = dek != null

    /**
     * Establishes what the vault needs from the user after sign-in.
     *
     * Tries the Keystore cache first so a returning user on their own phone isn't asked
     * for a passphrase they already proved once.
     */
    suspend fun refresh(uid: String?): VaultState {
        if (uid == null) {
            lock()
            _state.value = VaultState.NoUser
            return VaultState.NoUser
        }

        dekCache.load()?.let { cached ->
            dek = cached
            _state.value = VaultState.Unlocked
            return VaultState.Unlocked
        }

        val exists = runCatching { keyDoc(uid).get().await().exists() }.getOrElse { t ->
            // Offline: a vault we can't reach isn't a vault that doesn't exist. Report
            // Locked rather than NeedsSetup — the latter would walk the user into creating
            // a second DEK and orphaning everything encrypted under the first.
            Log.w(TAG, "Could not reach key doc; assuming vault exists", t)
            true
        }

        val next = if (exists) VaultState.Locked else VaultState.NeedsSetup
        _state.value = next
        return next
    }

    /**
     * Creates a vault: a random DEK wrapped twice, under the passphrase and under a
     * freshly generated recovery code.
     *
     * @return the recovery code, which is shown to the user exactly once and never stored
     *   in recoverable form — storing it would defeat the entire design.
     */
    suspend fun setup(uid: String, passphrase: CharArray): Result<String> = runCatching {
        val newDek = envelope.generateDek()
        val code = recoveryCodes.generate()

        val doc = VaultKeyDoc(
            passphrase = envelope.wrapDek(newDek, passphrase, kdf),
            recovery = envelope.wrapDek(newDek, RecoveryCodeGenerator.normalize(code).toCharArray(), kdf),
        )

        // Write the key doc before caching locally: if this fails the user must not end up
        // with data encrypted under a DEK that exists nowhere else.
        keyDoc(uid).set(mapOf(FIELD to json.encodeToString(doc))).await()

        dek = newDek
        dekCache.store(newDek)
        _state.value = VaultState.Unlocked
        code
    }.onFailure { Log.e(TAG, "Vault setup failed", it) }

    /** @return true if the passphrase was correct. */
    suspend fun unlock(uid: String, passphrase: CharArray): Boolean =
        unlockWith(uid) { doc -> envelope.unwrapDek(doc.passphrase, passphrase, kdf) }

    /** @return true if the recovery code was correct. */
    suspend fun unlockWithRecoveryCode(uid: String, code: String): Boolean =
        unlockWith(uid) { doc ->
            envelope.unwrapDek(doc.recovery, RecoveryCodeGenerator.normalize(code).toCharArray(), kdf)
        }

    private suspend fun unlockWith(uid: String, attempt: (VaultKeyDoc) -> ByteArray?): Boolean {
        val doc = fetchKeyDoc(uid) ?: return false
        val opened = attempt(doc) ?: return false
        dek = opened
        dekCache.store(opened)
        _state.value = VaultState.Unlocked
        return true
    }

    /**
     * Replaces the passphrase.
     *
     * Rewraps the same DEK rather than generating a new one — re-keying would require
     * re-encrypting and re-uploading every record, and a failure midway would leave a
     * vault half-readable. The recovery code deliberately keeps working: changing a
     * passphrase is not a reason to invalidate the user's paper backup.
     */
    suspend fun changePassphrase(uid: String, newPassphrase: CharArray): Result<Unit> = runCatching {
        val current = dek ?: error("Vault is locked")
        val existing = fetchKeyDoc(uid) ?: error("No vault to update")

        val updated = existing.copy(passphrase = envelope.wrapDek(current, newPassphrase, kdf))
        keyDoc(uid).set(mapOf(FIELD to json.encodeToString(updated))).await()
    }

    /** Seals record plaintext under the live DEK. AAD binds it to its record and owner. */
    fun seal(plaintext: ByteArray, recordId: String, uid: String): Sealed? {
        val key = dek ?: return null
        return envelope.seal(key, plaintext, aad = aad(recordId, uid))
    }

    /** Opens a record blob. Null means locked, wrong key, tampering, or a swapped record. */
    fun open(sealed: Sealed, recordId: String, uid: String): ByteArray? {
        val key = dek ?: return null
        return envelope.open(key, sealed, aad = aad(recordId, uid))
    }

    /** Drops the in-memory DEK. The cache survives, so this is not a sign-out. */
    fun lock() {
        dek?.fill(0)
        dek = null
        if (_state.value == VaultState.Unlocked) _state.value = VaultState.Locked
    }

    /** Full teardown on sign-out: forget the key and the cache. Local Room data stays. */
    suspend fun forget() {
        lock()
        dekCache.clear()
        _state.value = VaultState.NoUser
    }

    private suspend fun fetchKeyDoc(uid: String): VaultKeyDoc? = runCatching {
        val snap = keyDoc(uid).get().await()
        val raw = snap.getString(FIELD) ?: return null
        json.decodeFromString<VaultKeyDoc>(raw)
    }.onFailure { Log.e(TAG, "Could not read key doc", it) }.getOrNull()

    private fun keyDoc(uid: String) =
        firestore.collection("users").document(uid).collection("keys").document("wrapped")

    /**
     * Binds ciphertext to its record id and owner.
     *
     * Without this, someone with write access to the raw store could move a blob between
     * records — unable to read it, but able to make the app show the wrong dose for the
     * wrong drug. Confidentiality alone isn't enough when the data is medical.
     */
    private fun aad(recordId: String, uid: String): ByteArray =
        "drugme:record:v1:$uid:$recordId".toByteArray()

    private companion object {
        const val TAG = "VaultManager"
        const val FIELD = "doc"
    }
}
