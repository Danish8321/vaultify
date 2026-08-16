package com.cryptum.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.security.Security
import javax.crypto.AEADBadTagException

/**
 * Runs core-crypto's vectors where they actually have to work.
 *
 * On Android the AES-GCM implementation comes from Conscrypt, not the JDK
 * providers the JVM suite exercises. The two should agree — AES-256-GCM is a
 * specification, not an implementation detail — but "should agree" is the class
 * of claim this repo does not accept without evidence (ticket 13).
 */
@RunWith(AndroidJUnit4::class)
class CryptoCoreOnDeviceTest {

    private fun hex(s: String): ByteArray =
        s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun decrypts_a_published_vector_it_did_not_produce_itself() {
        // NIST GCM validation vector for AES-256: 32-byte zero key, 12-byte zero
        // IV, empty plaintext, tag 530f8afbc74536b9a963b4f1c4cb738b.
        //
        // This is the only test here that can detect a provider disagreement. A
        // round trip cannot: it is self-consistent even if encrypt and decrypt
        // are wrong in the same direction. The expected value comes from the
        // published vector, not from running this code.
        val sealed = SealedSecret(
            ciphertext = hex("530f8afbc74536b9a963b4f1c4cb738b"),
            nonce = ByteArray(CryptoCore.NONCE_LENGTH),
            dek = ByteArray(CryptoCore.DEK_LENGTH),
        )

        val recovered = CryptoCore.open(sealed, sealed.dek)

        assertArrayEquals(ByteArray(0), recovered)
    }

    @Test
    fun round_trips_on_device() {
        val plaintext = "hunter2".encodeToByteArray()

        val sealed = CryptoCore.seal(plaintext)

        assertArrayEquals(plaintext, CryptoCore.open(sealed, sealed.dek))
    }

    @Test(expected = AEADBadTagException::class)
    fun rejects_a_tampered_ciphertext_on_device() {
        // Conscrypt could in principle report authentication failure through a
        // different exception type. The production code catches nothing, so a
        // different type would surface as a crash rather than a handled error —
        // worth pinning here rather than discovering in the field.
        val sealed = CryptoCore.seal("hunter2".encodeToByteArray())
        sealed.ciphertext[0] = (sealed.ciphertext[0].toInt() xor 0x01).toByte()

        CryptoCore.open(sealed, sealed.dek)
    }

    @Test
    fun generates_distinct_nonces_from_the_device_entropy_source() {
        // SecureRandom is seeded differently on Android than on the JVM. Fewer
        // iterations than the JVM property test because instrumented tests are
        // slow, so this is a smoke check that the device source is not stuck,
        // not a restatement of the JVM guarantee.
        val nonces = (1..2_000).map { CryptoCore.seal(byteArrayOf(1)).nonce.toList() }.toHashSet()

        assertEquals(2_000, nonces.size)
    }

    @Test
    fun the_provider_backing_aes_gcm_is_recorded() {
        // Not an assertion about which provider is correct — a record of which
        // one these results came from, so a future failure can be attributed.
        val provider = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").provider.name
        println("AES/GCM/NoPadding provider on device: $provider")
        println("installed: " + Security.getProviders().joinToString { it.name })

        assertNotEquals("", provider)
    }
}
