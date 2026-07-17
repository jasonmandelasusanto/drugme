package com.drugme.app.data.crypto

import kotlinx.serialization.Serializable
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Argon2id parameters, stored alongside the wrapped key rather than hardcoded.
 *
 * This is not incidental. If the cost were a constant in the app, raising it later would
 * change how every existing user's key is derived — and since the KDF output *is* the
 * unwrapping key, every existing user would be locked out of their own data permanently.
 * Persisting the parameters that were actually used means the cost can be raised for new
 * keys while old ones keep deriving exactly as before.
 */
@Serializable
data class KdfParams(
    val memoryKib: Int,
    val iterations: Int,
    val parallelism: Int,
    val version: Int = Argon2Parameters.ARGON2_VERSION_13,
) {
    companion object {
        /**
         * Defaults for a mid-range phone.
         *
         * 64 MiB / t=3 / p=1 lands around 250–500ms on typical hardware — slow enough to
         * make offline brute-force of a stolen blob expensive, fast enough that unlocking
         * doesn't feel broken. Memory is the lever that matters: it is what denies a GPU or
         * ASIC attacker their parallelism advantage, so raise memoryKib before iterations.
         */
        val DEFAULT = KdfParams(memoryKib = 64 * 1024, iterations = 3, parallelism = 1)
    }
}

/**
 * Derives key-encryption keys from a passphrase or recovery code.
 *
 * BouncyCastle's pure-JVM Argon2 is used rather than a native binding: it needs no NDK, no
 * ABI splits, and no JNI, and this runs once per unlock rather than in a hot loop.
 */
@Singleton
class Argon2Kdf @Inject constructor() {

    /**
     * @param secret passphrase or recovery code
     * @param salt per-key random salt, stored with the wrapped key
     * @return a 32-byte KEK
     */
    fun deriveKey(secret: CharArray, salt: ByteArray, params: KdfParams): ByteArray {
        require(salt.size >= SALT_BYTES) { "Salt must be at least $SALT_BYTES bytes" }

        val generator = Argon2BytesGenerator()
        generator.init(
            Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(params.version)
                .withIterations(params.iterations)
                .withMemoryAsKB(params.memoryKib)
                .withParallelism(params.parallelism)
                .withSalt(salt)
                .build()
        )
        val out = ByteArray(KEY_BYTES)
        generator.generateBytes(secret, out)
        return out
    }

    companion object {
        const val KEY_BYTES = 32
        const val SALT_BYTES = 16
    }
}
