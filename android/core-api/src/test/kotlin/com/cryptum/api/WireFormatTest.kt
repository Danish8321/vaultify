package com.cryptum.api

import com.cryptum.api.model.CreateSecretRequest
import com.cryptum.api.model.ItemResponse
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The seam under test is the generated client's wire format — the only part of
 * a generated module worth asserting on.
 *
 * Not tested here: that the generator ran, or that the classes have the shape
 * the generator gave them. Those are tautologies; they would pass by
 * construction whatever the contract said. What can genuinely disagree with the
 * server is the encoding of a field, and every field that carries key material
 * on this contract is `format: byte` — base64 — where a wrong guess (raw
 * string, hex, an array of ints) produces a client that compiles, type-checks,
 * and silently fails to decrypt anything.
 *
 * The expected values below are literals taken from the contract's own encoding
 * rules, not values recomputed the way the code computes them.
 */
class WireFormatTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `byte fields travel as base64, not as raw text`() {
        // "hello" in bytes. Base64 of it is a known-good literal, not a value
        // this test derives: aGVsbG8=
        val request = CreateSecretRequest(
            title = "a title",
            ciphertext = "hello".toByteArray(),
            nonce = ByteArray(12) { 0 },
            dek = ByteArray(32) { 0 },
        )

        val encoded = json.encodeToString(CreateSecretRequest.serializer(), request)

        assertEquals(
            """{"title":"a title","ciphertext":"aGVsbG8=","nonce":"AAAAAAAAAAAAAAAA","dek":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="}""",
            encoded,
        )
    }

    @Test
    fun `a response decodes its byte fields back to the original bytes`() {
        val body = """
            {"id":"3f1a0c58-0c5a-4a1e-9f4a-0f2f3b7c9d10","title":"a title",
             "ciphertext":"aGVsbG8=","nonce":"AAAAAAAAAAAAAAAA",
             "dek":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
             "updatedAt":"2026-08-17T09:30:00Z"}
        """.trimIndent()

        val item = json.decodeFromString(ItemResponse.serializer(), body)

        assertEquals("hello", item.ciphertext.decodeToString())
        assertEquals(12, item.nonce.size)
        assertEquals(32, item.dek.size)
    }
}
