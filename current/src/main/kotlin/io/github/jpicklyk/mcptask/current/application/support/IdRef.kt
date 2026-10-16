package io.github.jpicklyk.mcptask.current.application.support

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * An entity id as the caller wrote it: a canonical 36-character UUID (8-4-4-4-12 hex, any case) or a
 * dash-free hex prefix of 8..32 characters. Format only: [raw] is stored unnormalized, and resolving
 * it against the store (prefix lookup, ambiguity) is the id resolver's job, never this type's.
 */
@Serializable(with = IdRef.Serializer::class)
@JvmInline
value class IdRef(
    val raw: String
) {
    companion object {
        private val UUID_FORM = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        private val HEX_FORM = Regex("^[0-9a-fA-F]{8,32}$")

        /** The reason a malformed id is rejected, as reported in `invalid_request` field violations. */
        const val FORMAT_REASON = "must be a UUID or 8 to 32 hex characters"

        /** [raw] as an [IdRef] when it has a valid format, else null. */
        fun parse(raw: String): IdRef? = if (UUID_FORM.matches(raw) || HEX_FORM.matches(raw)) IdRef(raw) else null
    }

    /** Strings on the wire; a malformed id fails decoding (the request decoder reports it as `invalid_request`). */
    object Serializer : KSerializer<IdRef> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("io.github.jpicklyk.mcptask.current.application.support.IdRef", PrimitiveKind.STRING)

        override fun serialize(
            encoder: Encoder,
            value: IdRef
        ) {
            encoder.encodeString(value.raw)
        }

        override fun deserialize(decoder: Decoder): IdRef {
            val raw = decoder.decodeString()
            return parse(raw) ?: throw SerializationException("Invalid id '$raw': $FORMAT_REASON")
        }
    }
}
