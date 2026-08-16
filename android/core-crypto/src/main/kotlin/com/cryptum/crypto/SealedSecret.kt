package com.cryptum.crypto

/**
 * One encrypted payload and the key material needed to read it back.
 *
 * [ciphertext] carries the GCM tag appended, which is what the backend stores
 * and what [CryptoCore.open] expects.
 *
 * [dek] is plaintext key material and must not outlive its use: wrap the caller
 * in [use] so it is zeroed even when the body throws.
 */
class SealedSecret(
    val ciphertext: ByteArray,
    val nonce: ByteArray,
    val dek: ByteArray,
) {
    /**
     * Runs [block] and then zeroes the DEK, whatever happens.
     *
     * Zeroing is best-effort on a managed runtime — the GC may have copied the
     * array during a compaction and that copy cannot be reached. It still
     * removes the long-lived copy, which is the one a heap dump or a swapped
     * page would expose.
     */
    fun <T> use(block: (SealedSecret) -> T): T =
        try {
            block(this)
        } finally {
            dek.fill(0)
        }
}
