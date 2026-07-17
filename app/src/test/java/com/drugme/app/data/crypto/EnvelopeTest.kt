package com.drugme.app.data.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The failure mode this guards is silence.
 *
 * Broken crypto doesn't crash — it produces bytes that look fine and protect nothing, or
 * it locks a user out of their own medication history with no way back. So the negative
 * paths (wrong key, tampering, swapped records, changed cost parameters) matter more here
 * than the round trip.
 *
 * Runs on the JVM, so the Android Base64 in Envelope.b64 is unavailable — these tests work
 * with raw byte arrays and construct KdfParams directly.
 */
class EnvelopeTest {

    private val envelope = Envelope()
    private val kdf = Argon2Kdf()

    // Argon2 at production cost (64 MiB) makes a test suite crawl. The parameters under
    // test are the plumbing, not the numbers, so cost is turned down here; KdfParamsTest
    // covers that the stored values are honoured.
    private val fastParams = KdfParams(memoryKib = 256, iterations = 1, parallelism = 1)

    @Test
    fun `seal then open round-trips`() {
        val key = envelope.generateDek()
        val plaintext = "metformin 500mg".toByteArray()

        val sealed = envelope.seal(key, plaintext)
        val opened = envelope.open(key, sealed)

        assertArrayEquals(plaintext, opened)
    }

    @Test
    fun `ciphertext does not contain the plaintext`() {
        val key = envelope.generateDek()
        val plaintext = "sertraline".toByteArray()

        val sealed = envelope.seal(key, plaintext)

        // The whole promise in one assertion: what leaves the device reveals nothing.
        assertFalse(
            "plaintext leaked into ciphertext",
            String(sealed.ciphertext, Charsets.ISO_8859_1).contains("sertraline"),
        )
    }

    @Test
    fun `wrong key fails cleanly rather than returning garbage`() {
        val sealed = envelope.seal(envelope.generateDek(), "secret".toByteArray())

        // Null, not an exception and not junk bytes. Garbage would be worse than failure:
        // the app would render nonsense as if it were the user's data.
        assertNull(envelope.open(envelope.generateDek(), sealed))
    }

    @Test
    fun `tampered ciphertext is rejected`() {
        val key = envelope.generateDek()
        val sealed = envelope.seal(key, "warfarin 5mg".toByteArray())

        val tampered = sealed.ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }

        assertNull(envelope.open(key, Sealed(sealed.nonce, tampered)))
    }

    @Test
    fun `aad mismatch is rejected — a blob cannot be moved between records`() {
        val key = envelope.generateDek()
        val aadA = "drugme:record:v1:uid1:recordA".toByteArray()
        val aadB = "drugme:record:v1:uid1:recordB".toByteArray()

        val sealed = envelope.seal(key, "dose".toByteArray(), aad = aadA)

        assertArrayEquals("dose".toByteArray(), envelope.open(key, sealed, aad = aadA))
        // Opening record A's blob as record B must fail even with the right key: otherwise
        // an attacker who cannot read anything could still shuffle doses between drugs.
        assertNull(envelope.open(key, sealed, aad = aadB))
    }

    @Test
    fun `nonces are never reused across seals`() {
        val key = envelope.generateDek()
        val nonces = (1..500).map { envelope.seal(key, "x".toByteArray()).nonce.toList() }

        // GCM nonce reuse under one key leaks the plaintext XOR and the auth subkey. A
        // duplicate here would be a catastrophic, and entirely silent, break.
        assertEquals("duplicate nonce generated", nonces.size, nonces.toSet().size)
    }

    @Test
    fun `same plaintext seals to different ciphertext each time`() {
        val key = envelope.generateDek()
        val a = envelope.seal(key, "amlodipine".toByteArray())
        val b = envelope.seal(key, "amlodipine".toByteArray())

        // Deterministic ciphertext would let the server tell which users take the same drug.
        assertNotEquals(a.ciphertext.toList(), b.ciphertext.toList())
    }

    @Test
    fun `dek is 256 bits`() {
        assertEquals(32, envelope.generateDek().size)
    }

    @Test
    fun `nonce is 96 bits`() {
        assertEquals(12, envelope.seal(envelope.generateDek(), "x".toByteArray()).nonce.size)
    }

    @Test
    fun `argon2 derives the same key for the same inputs`() {
        val salt = envelope.generateSalt()
        val a = kdf.deriveKey("hunter2".toCharArray(), salt, fastParams)
        val b = kdf.deriveKey("hunter2".toCharArray(), salt, fastParams)

        assertArrayEquals(a, b)
    }

    @Test
    fun `argon2 derives different keys for different salts`() {
        val a = kdf.deriveKey("hunter2".toCharArray(), envelope.generateSalt(), fastParams)
        val b = kdf.deriveKey("hunter2".toCharArray(), envelope.generateSalt(), fastParams)

        assertFalse(a.contentEquals(b))
    }

    @Test
    fun `argon2 output is 256 bits`() {
        assertEquals(32, kdf.deriveKey("x".toCharArray(), envelope.generateSalt(), fastParams).size)
    }
}
