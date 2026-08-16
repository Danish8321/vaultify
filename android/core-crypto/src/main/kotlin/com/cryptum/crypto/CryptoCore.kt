package com.cryptum.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Client-side AES-256-GCM. Every [seal] mints a fresh DEK and a fresh nonce, so
 * no key or nonce is ever reused across two encryptions (ADR-0006).
 *
 * The server never sees any of this in plaintext form except the DEK, which it
 * receives wrapped. Cryptum is server-blind, not end-to-end (ADR-0001).
 *
 * Tested on the JVM rather than on a device, because nothing here touches an
 * Android API. Android supplies these primitives through Conscrypt rather than
 * the JDK providers, so an instrumented test still has to confirm the same
 * vectors on-device before release — see ticket 13.
 */
object CryptoCore {

    /** AES-256. */
    const val DEK_LENGTH: Int = 32

    /**
     * 96 bits, the only nonce length GCM is actually specified for. Longer or
     * shorter nonces get hashed into shape by GHASH, which costs the guarantee
     * that distinct nonces stay distinct.
     */
    const val NONCE_LENGTH: Int = 12

    /** 128-bit tag, appended to the ciphertext, matching the backend's layout. */
    const val TAG_LENGTH_BITS: Int = 128

    private val random = SecureRandom()

    /** Encrypts [plaintext] under a freshly generated DEK and nonce. */
    fun seal(plaintext: ByteArray): SealedSecret {
        val dek = ByteArray(DEK_LENGTH).also(random::nextBytes)
        val nonce = ByteArray(NONCE_LENGTH).also(random::nextBytes)

        val key = SecretKeySpec(dek, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, nonce))
        }

        return SealedSecret(
            ciphertext = cipher.doFinal(plaintext),
            nonce = nonce,
            dek = dek,
        )
    }

    /**
     * Decrypts [sealed] with [dek].
     *
     * Throws [javax.crypto.AEADBadTagException] if the ciphertext, tag or nonce
     * has been altered. That throw is the point: GCM authenticates, so a
     * tampered Vault entry must fail loudly rather than decrypt to garbage a
     * caller might go on to trust.
     */
    fun open(sealed: SealedSecret, dek: ByteArray): ByteArray {
        val key = SecretKeySpec(dek, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, sealed.nonce))
        }

        return cipher.doFinal(sealed.ciphertext)
    }
}
