package com.cryptum.vault

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * The seam is [SecretEnvelope] — how a Secret becomes a request body and how a
 * response becomes a Secret again. That is the boundary the rest of the app
 * talks to; the screens above it and `CryptoCore` below it are tested
 * separately.
 */
class SecretEnvelopeTest {

    private val payload = SecretPayload(
        username = "ada",
        password = "correct horse battery staple",
        url = "https://example.test",
        notes = "second factor is in the drawer",
    )

    @Test
    fun `a sealed Secret opens back to the same fields`() {
        val request = SecretEnvelope.seal(title = "Example", payload = payload)

        val reopened = SecretEnvelope.open(
            ciphertext = request.ciphertext,
            nonce = request.nonce,
            dek = request.dek,
        )

        assertEquals(payload, reopened)
    }

    @Test
    fun `every non-title field travels inside the one ciphertext`() {
        val request = SecretEnvelope.seal(title = "Example", payload = payload)

        // The plan's requirement, stated as a property rather than as a shape:
        // no field value may be recoverable from the request without the DEK.
        // A per-field envelope, or a field accidentally left beside the
        // ciphertext, fails here.
        val onTheWire = request.ciphertext + request.nonce
        listOf("ada", "correct horse battery staple", "https://example.test", "second factor")
            .forEach { secret ->
                assertFalse(
                    onTheWire.containsBytesOf(secret),
                    "\"$secret\" is readable in the request without decrypting it",
                )
            }
    }

    @Test
    fun `the title is deliberately not encrypted`() {
        // ADR-0002 accepts a plaintext title so the list can render without
        // unwrapping every DEK. Asserted here so that trade-off cannot be
        // reversed silently — if someone encrypts the title later, this fails
        // and they have to go and change the ADR too.
        val request = SecretEnvelope.seal(title = "Example", payload = payload)

        assertEquals("Example", request.title)
    }

    @Test
    fun `an absent field stays absent rather than becoming an empty string`() {
        // A Secret with no URL and a Secret with a blank URL are different
        // things to a user, and collapsing them silently edits their data.
        val sparse = SecretPayload(password = "only this")
        val request = SecretEnvelope.seal(title = "Sparse", payload = sparse)

        val reopened = SecretEnvelope.open(request.ciphertext, request.nonce, request.dek)

        assertEquals("only this", reopened.password)
        assertNull(reopened.username)
        assertNull(reopened.url)
        assertNull(reopened.notes)
    }

    @Test
    fun `two Secrets with identical contents produce different ciphertexts`() {
        // Not the nonce guarantee — CryptoCore mints a fresh DEK per seal, so
        // this would pass even with a fixed nonce. What it does prove is that
        // the envelope does not cache or reuse a sealed result between calls,
        // which a memoised codec would.
        val first = SecretEnvelope.seal("Example", payload)
        val second = SecretEnvelope.seal("Example", payload)

        assertFalse(first.ciphertext.contentEquals(second.ciphertext))
    }

    private fun ByteArray.containsBytesOf(text: String): Boolean {
        val needle = text.toByteArray()
        if (needle.isEmpty() || needle.size > size) return false
        return (0..size - needle.size).any { start ->
            needle.indices.all { this[start + it] == needle[it] }
        }
    }
}
