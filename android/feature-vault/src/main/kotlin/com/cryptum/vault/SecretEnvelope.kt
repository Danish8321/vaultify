package com.cryptum.vault

import com.cryptum.api.model.CreateSecretRequest
import com.cryptum.api.model.ItemResponse
import com.cryptum.crypto.CryptoCore
import com.cryptum.crypto.SealedSecret
import kotlinx.serialization.json.Json

/**
 * The boundary between a Secret the user can read and the bytes the server is
 * allowed to hold.
 *
 * Nothing above this object ever sees a DEK, and nothing below it ever sees a
 * password. Both directions of that translation live here so there is exactly
 * one place to audit.
 */
object SecretEnvelope {

    /**
     * `encodeDefaults = false` keeps a null field out of the JSON entirely
     * rather than writing `"url":null`, which shortens the plaintext and stops
     * the ciphertext length from advertising how many fields exist.
     */
    private val json = Json {
        encodeDefaults = false
        ignoreUnknownKeys = true
    }

    /** Seals [payload] into a request body. [title] is sent in plaintext (ADR-0002). */
    fun seal(title: String, payload: SecretPayload): CreateSecretRequest {
        val plaintext = json.encodeToString(SecretPayload.serializer(), payload).toByteArray()

        val sealed = CryptoCore.seal(plaintext)
        // The plaintext JSON held every field in the clear. It is dead the
        // moment the ciphertext exists, and a long-lived copy is what a heap
        // dump would find.
        plaintext.fill(0)

        return CreateSecretRequest(
            title = title,
            ciphertext = sealed.ciphertext,
            nonce = sealed.nonce,
            dek = sealed.dek,
        )
    }

    /**
     * Opens a Secret returned by the server.
     *
     * Throws [javax.crypto.AEADBadTagException] if the bytes were altered in
     * transit or at rest. Left to propagate: a Vault entry that fails its
     * authentication tag must be reported, never rendered as an empty Secret.
     */
    fun open(ciphertext: ByteArray, nonce: ByteArray, dek: ByteArray): SecretPayload {
        val plaintext = CryptoCore.open(SealedSecret(ciphertext, nonce, dek), dek)

        return try {
            json.decodeFromString(SecretPayload.serializer(), plaintext.decodeToString())
        } finally {
            plaintext.fill(0)
        }
    }

    /** Opens a Secret straight from the contract's response type. */
    fun open(item: ItemResponse): SecretPayload = open(item.ciphertext, item.nonce, item.dek)
}
