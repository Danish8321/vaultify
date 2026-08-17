package com.cryptum.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.util.Base64

/**
 * The contract's `format: byte` fields — ciphertext, nonce and DEK.
 *
 * This is the one hand-written type in a generated module, and it is not a
 * duplicate of a contract type: it carries an *encoding*, which the contract
 * states and the generator has no way to express. Without it the generator
 * emits a bare `ByteArray`, which kotlinx.serialization writes as a JSON array
 * of integers. That client compiles and type-checks and never decrypts
 * anything, because the server is reading base64.
 *
 * The generator is pointed at this alias via `typeMappings` in build.gradle.kts.
 */
typealias Base64Bytes = @Serializable(with = Base64ByteArraySerializer::class) ByteArray

/**
 * Base64, as RFC 4648 §4 with padding — the encoding ASP.NET Core's
 * `format: byte` produces and accepts.
 *
 * Not URL-safe base64: these values travel in a JSON body, never in a path or a
 * query string, and the two alphabets are not interchangeable.
 */
object Base64ByteArraySerializer : KSerializer<ByteArray> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.cryptum.api.Base64Bytes", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ByteArray) {
        encoder.encodeString(Base64.getEncoder().encodeToString(value))
    }

    override fun deserialize(decoder: Decoder): ByteArray =
        Base64.getDecoder().decode(decoder.decodeString())
}
