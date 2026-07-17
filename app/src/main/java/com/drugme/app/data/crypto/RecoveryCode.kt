package com.drugme.app.data.crypto

import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The second, independent way into a user's data.
 *
 * A passphrase alone would mean one forgotten word destroys a medication history with no
 * recourse — and in a zero-knowledge design there genuinely is no recourse, because we
 * cannot reset what we cannot read. The recovery code exists so that outcome requires
 * losing *two* things rather than one.
 */
@Singleton
class RecoveryCodeGenerator @Inject constructor() {

    /**
     * Generates a ~128-bit code, hyphenated into groups for transcription.
     *
     * Entropy has to survive being written on paper and typed back months later, so the
     * alphabet omits every character people mistranscribe — O/0 and I/1/L are simply
     * absent rather than disambiguated. Length carries the entropy instead of alphabet
     * size: 26 chars over 31 symbols ≈ 128.8 bits.
     */
    fun generate(): String {
        val random = SecureRandom()
        val chars = CharArray(CODE_LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] }
        return chars.concatToString().chunked(GROUP_SIZE).joinToString("-")
    }

    companion object {
        /** No O, 0, I, 1, or L — the pairs that get misread off paper. */
        const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        private const val CODE_LENGTH = 26
        private const val GROUP_SIZE = 5

        /**
         * Canonicalises typed input for comparison.
         *
         * Someone copying a code off paper will add spaces, drop or move hyphens, and use
         * lower case; none of that should read as "wrong code". Ambiguous characters can't
         * be corrected here — since O/0/I/1/L never occur in a real code, one appearing
         * means a genuine typo, and quietly rewriting it to a plausible neighbour would
         * turn a clear failure into a confusing one.
         */
        fun normalize(input: String): String =
            input.uppercase().filter { it in ALPHABET }
    }
}
