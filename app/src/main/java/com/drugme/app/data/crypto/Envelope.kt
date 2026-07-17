package com.drugme.app.data.crypto

import kotlinx.serialization.Serializable
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/** Ciphertext plus the nonce it was produced under. */
data class Sealed(val nonce: ByteArray, val ciphertext: ByteArray) {
    // Data classes compare arrays by reference; without these, equality silently means
    // identity and tests comparing Sealed values would pass or fail for the wrong reason.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Sealed) return false
        return nonce.contentEquals(other.nonce) && ciphertext.contentEquals(other.ciphertext)
    }

    override fun hashCode(): Int = 31 * nonce.contentHashCode() + ciphertext.contentHashCode()
}

/** A DEK wrapped under one secret, with everything needed to unwrap it again. */
@Serializable
data class WrappedKey(
    /** Base64 of the wrapping nonce. */
    val nonce: String,
    /** Base64 of the wrapped DEK. */
    val ciphertext: String,
    /** Base64 of the KDF salt. */
    val salt: String,
    val kdf: KdfParams,
)

/**
 * AES-256-GCM sealing and DEK wrapping.
 *
 * Uses the platform JCE rather than Tink's keyset abstraction: what's needed here is a raw
 * 32-byte DEK that can be wrapped by a KDF output and handed around, and Tink's keysets
 * are designed to manage their own key material rather than adopt someone else's.
 */
@Singleton
class Envelope @Inject constructor() {

    private val random = SecureRandom()

    fun generateDek(): ByteArray = ByteArray(KEY_BYTES).also { random.nextBytes(it) }

    fun generateSalt(): ByteArray = ByteArray(Argon2Kdf.SALT_BYTES).also { random.nextBytes(it) }

    /**
     * Seals [plaintext] under [key].
     *
     * [aad] is authenticated but not encrypted. Callers bind the record id and uid into it
     * so a blob cannot be lifted from one record and pasted over another: without that, a
     * server-side attacker who can't read anything could still swap two doses' ciphertexts
     * and change what the app displays.
     *
     * A fresh random nonce is drawn per call and never reused. GCM nonce reuse under the
     * same key is catastrophic — it leaks the XOR of two plaintexts and hands over the
     * authentication subkey — so nonces are never derived from counters or content here.
     */
    fun seal(key: ByteArray, plaintext: ByteArray, aad: ByteArray? = null): Sealed {
        require(key.size == KEY_BYTES) { "Key must be $KEY_BYTES bytes" }
        val nonce = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORM).apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, ALGO), GCMParameterSpec(TAG_BITS, nonce))
            aad?.let { updateAAD(it) }
        }
        return Sealed(nonce, cipher.doFinal(plaintext))
    }

    /**
     * Opens a sealed blob.
     *
     * Returns null on any authentication failure rather than throwing. GCM verifies the tag
     * before releasing plaintext, so a wrong key, a tampered ciphertext and mismatched AAD
     * are indistinguishable here — and that is the point: reporting *why* it failed would
     * hand an attacker an oracle.
     */
    fun open(key: ByteArray, sealed: Sealed, aad: ByteArray? = null): ByteArray? = try {
        val cipher = Cipher.getInstance(TRANSFORM).apply {
            init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, ALGO),
                GCMParameterSpec(TAG_BITS, sealed.nonce),
            )
            aad?.let { updateAAD(it) }
        }
        cipher.doFinal(sealed.ciphertext)
    } catch (e: GeneralSecurityException) {
        null
    }

    /** Wraps [dek] under a key derived from [secret]. */
    fun wrapDek(
        dek: ByteArray,
        secret: CharArray,
        kdf: Argon2Kdf,
        params: KdfParams = KdfParams.DEFAULT,
    ): WrappedKey {
        val salt = generateSalt()
        val kek = kdf.deriveKey(secret, salt, params)
        try {
            val sealed = seal(kek, dek, aad = WRAP_AAD)
            return WrappedKey(
                nonce = b64(sealed.nonce),
                ciphertext = b64(sealed.ciphertext),
                salt = b64(salt),
                kdf = params,
            )
        } finally {
            // The KEK is reconstructible from the secret, but leaving it in a live array
            // widens the window in which a heap dump yields it.
            kek.fill(0)
        }
    }

    /**
     * Recovers a DEK, or null if [secret] is wrong.
     *
     * The stored [WrappedKey.kdf] is used rather than the current default, so raising the
     * KDF cost for new users never locks out existing ones.
     */
    fun unwrapDek(wrapped: WrappedKey, secret: CharArray, kdf: Argon2Kdf): ByteArray? {
        val salt = unb64(wrapped.salt)
        val kek = kdf.deriveKey(secret, salt, wrapped.kdf)
        return try {
            open(kek, Sealed(unb64(wrapped.nonce), unb64(wrapped.ciphertext)), aad = WRAP_AAD)
        } finally {
            kek.fill(0)
        }
    }

    companion object {
        const val KEY_BYTES = 32

        /** 96 bits: the size GCM is specified for and the only one with a clean security proof. */
        const val NONCE_BYTES = 12
        const val TAG_BITS = 128
        private const val ALGO = "AES"
        private const val TRANSFORM = "AES/GCM/NoPadding"

        /** Domain separation: a wrapped key must never be openable as a record blob. */
        private val WRAP_AAD = "drugme:dek-wrap:v1".toByteArray()

        fun b64(b: ByteArray): String = android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP)
        fun unb64(s: String): ByteArray = android.util.Base64.decode(s, android.util.Base64.NO_WRAP)
    }
}
