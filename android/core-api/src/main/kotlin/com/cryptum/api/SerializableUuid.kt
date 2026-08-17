package com.cryptum.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.util.UUID

/**
 * The contract's `format: uuid` fields — every Item identifier.
 *
 * Same problem as [Base64Bytes], different type: the generator maps
 * `format: uuid` to `java.util.UUID` and marks it `@Contextual`, which throws at
 * runtime unless a serializer is registered somewhere. Binding the serializer to
 * the type through an alias means it cannot be forgotten by a caller who builds
 * their own `Json` instance.
 *
 * `UUID.fromString` is deliberately strict about the canonical 8-4-4-4-12 form.
 * An identifier that does not parse is a contract violation, and failing loudly
 * on it is better than carrying a malformed id into an authorization check.
 */
typealias SerializableUuid = @Serializable(with = UuidSerializer::class) UUID

object UuidSerializer : KSerializer<UUID> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.cryptum.api.SerializableUuid", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: UUID) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): UUID = UUID.fromString(decoder.decodeString())
}
