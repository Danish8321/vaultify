package com.cryptum.crypto

import javax.crypto.AEADBadTagException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class CryptoCoreTest {

    @Test
    fun `encrypting then decrypting returns the original plaintext`() {
        val plaintext = "hunter2".encodeToByteArray()

        val sealed = CryptoCore.seal(plaintext)
        val recovered = CryptoCore.open(sealed, sealed.dek)

        assertContentEquals(plaintext, recovered)
    }

    @Test
    fun `a tampered ciphertext fails authentication rather than returning garbage`() {
        // The failure mode that matters. An unauthenticated mode would hand back
        // plausible-looking bytes here, and the caller would have no way to know
        // the Vault entry had been altered in transit or at rest.
        val sealed = CryptoCore.seal("hunter2".encodeToByteArray())
        sealed.ciphertext[0] = (sealed.ciphertext[0].toInt() xor 0x01).toByte()

        assertFailsWith<AEADBadTagException> { CryptoCore.open(sealed, sealed.dek) }
    }

    @Test
    fun `a tampered tag fails authentication`() {
        // The tag lives in the last 16 bytes. Flipping the ciphertext and
        // flipping the tag are different attacks: one alters the message, the
        // other forges the proof that the message is intact.
        val sealed = CryptoCore.seal("hunter2".encodeToByteArray())
        val last = sealed.ciphertext.lastIndex
        sealed.ciphertext[last] = (sealed.ciphertext[last].toInt() xor 0x01).toByte()

        assertFailsWith<AEADBadTagException> { CryptoCore.open(sealed, sealed.dek) }
    }

    @Test
    fun `a tampered nonce fails authentication`() {
        val sealed = CryptoCore.seal("hunter2".encodeToByteArray())
        sealed.nonce[0] = (sealed.nonce[0].toInt() xor 0x01).toByte()

        assertFailsWith<AEADBadTagException> { CryptoCore.open(sealed, sealed.dek) }
    }

    @Test
    fun `no nonce and no DEK is ever generated twice`() {
        // GCM's one catastrophic misuse is reusing a nonce under the same key:
        // it leaks the XOR of the two plaintexts and, worse, the authentication
        // subkey, which turns forgery from impossible into arithmetic. Fresh
        // keys make a collision harmless in principle, so this asserts both are
        // fresh — the property has to hold on its own, not because something
        // else happens to cover it.
        val generations = 20_000
        val nonces = HashSet<String>(generations)
        val deks = HashSet<String>(generations)

        repeat(generations) {
            val sealed = CryptoCore.seal(byteArrayOf(1))
            nonces += sealed.nonce.joinToString("") { b -> "%02x".format(b) }
            deks += sealed.dek.joinToString("") { b -> "%02x".format(b) }
        }

        assertEquals(generations, nonces.size, "a nonce repeated")
        assertEquals(generations, deks.size, "a DEK repeated")
    }

    @Test
    fun `sealing the same plaintext twice produces different ciphertext`() {
        // The observable consequence of the property above. Equal ciphertexts
        // would let the server tell that two Vault entries hold the same secret
        // without decrypting either.
        val plaintext = "hunter2".encodeToByteArray()

        val first = CryptoCore.seal(plaintext)
        val second = CryptoCore.seal(plaintext)

        assertFalse(first.ciphertext.contentEquals(second.ciphertext))
    }

    @Test
    fun `use zeroes the DEK even when the body throws`() {
        // Key material must not survive a failure path. This is the case that
        // gets missed: the happy path is usually written with a clear() at the
        // end, and the throw skips it.
        val sealed = CryptoCore.seal("hunter2".encodeToByteArray())

        assertFailsWith<IllegalStateException> {
            sealed.use { error("boom") }
        }

        assertContentEquals(ByteArray(CryptoCore.DEK_LENGTH), sealed.dek)
    }

    @Test
    fun `the wrong DEK cannot open a secret`() {
        // Crypto-shred rests entirely on this: once the KEK is gone the DEK
        // cannot be recovered, and a DEK that is merely the right shape must not
        // work. If this ever passed, deletion would be cosmetic.
        val sealed = CryptoCore.seal("hunter2".encodeToByteArray())
        val wrongDek = ByteArray(CryptoCore.DEK_LENGTH) { 7 }

        assertFailsWith<AEADBadTagException> { CryptoCore.open(sealed, wrongDek) }
    }
}
