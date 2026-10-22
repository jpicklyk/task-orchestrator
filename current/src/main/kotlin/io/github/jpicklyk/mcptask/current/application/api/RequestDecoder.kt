package io.github.jpicklyk.mcptask.current.application.api

import io.github.jpicklyk.mcptask.current.application.support.IdRef
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.FieldViolation
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.getContextualDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * ISO-8601 [Instant] on the wire (`2026-10-10T12:00:00Z`). Registered as the contextual serializer of
 * [Instant] in [RequestDecoder], so a request type may declare `@Contextual val at: Instant` or
 * `@Serializable(with = IsoInstantSerializer::class) val at: Instant`.
 */
object IsoInstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("io.github.jpicklyk.mcptask.current.application.api.IsoInstant", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: Instant
    ) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): Instant {
        val raw = decoder.decodeString()
        return try {
            Instant.parse(raw)
        } catch (e: DateTimeParseException) {
            throw SerializationException("Invalid instant '$raw'")
        }
    }
}

/**
 * Decodes a tool request body (a JSON object) into a request type with strict JSON rules
 * (`ignoreUnknownKeys = false`, `isLenient = false`, `coerceInputValues = false`) and reports EVERY
 * problem at once, as JSON-pointer paths sorted by [String.compareTo]:
 *
 * 1. Top-level [reserved] keys (the dispatcher reads them itself, e.g. `select`) are removed first and never reported.
 * 2. A descriptor-driven walk collects every unknown key at every depth (objects inside arrays and
 *    maps included); any found -> `unknown_parameter` with `detail.parameters` (it wins over value
 *    faults: fix the shape first).
 * 3. Otherwise a second walk collects every value fault - wrong JSON type, bad enum value, bad
 *    instant, bad [IdRef], missing required field, `null` for a non-nullable field - as
 *    `invalid_request` with `detail.fields`.
 * 4. A non-object input is `invalid_request` on field `$`.
 *
 * Enums, instants and ids are parsed here, so handlers receive typed values only. Members typed as
 * `JsonElement`/`JsonObject` are free-form and are not walked into; polymorphic members are decoded
 * but not walked.
 */
@OptIn(ExperimentalSerializationApi::class)
class RequestDecoder {
    private val json =
        Json {
            ignoreUnknownKeys = false
            isLenient = false
            coerceInputValues = false
            serializersModule = SerializersModule { contextual(Instant::class, IsoInstantSerializer) }
        }

    private class LeafType(
        val serializer: KSerializer<*>,
        val reason: String
    )

    /** Leaf types whose validity needs their own serializer, keyed by serial name. */
    private val leafTypes: Map<String, LeafType> =
        mapOf(
            IsoInstantSerializer.descriptor.serialName to LeafType(IsoInstantSerializer, "must be an ISO-8601 instant"),
            IdRef.Serializer.descriptor.serialName to LeafType(IdRef.Serializer, IdRef.FORMAT_REASON)
        )

    fun <T> decode(
        serializer: KSerializer<T>,
        input: JsonElement,
        reserved: Set<String> = emptySet()
    ): Outcome<T> {
        val obj =
            input as? JsonObject
                ?: return Outcome.Err(invalidRequest(listOf(FieldViolation(ROOT, "must be a JSON object", received(input)))))
        val body = JsonObject(obj.filterKeys { it !in reserved })

        val unknown = ArrayList<String>()
        collectUnknown(serializer.descriptor, body, "", unknown)
        if (unknown.isNotEmpty()) return Outcome.Err(unknownParameter(unknown.sorted()))

        val faults = ArrayList<FieldViolation>()
        checkValue(serializer.descriptor, body, "", faults)
        if (faults.isNotEmpty()) return Outcome.Err(invalidRequest(faults.sortedBy { it.field }))

        return try {
            Outcome.Ok(json.decodeFromJsonElement(serializer, body))
        } catch (e: SerializationException) {
            // Reached only for a fault the walks cannot see (a custom serializer of a member type they do
            // not know); its text names serializer internals, so it stays generic.
            Outcome.Err(invalidRequest(listOf(FieldViolation(ROOT, DECODE_FAILED))))
        } catch (e: IllegalArgumentException) {
            // A require() failure thrown while constructing the request (e.g. a DTO init block): its message
            // is the reason. Request DTOs should validate in their handlers instead.
            Outcome.Err(invalidRequest(listOf(FieldViolation(ROOT, e.message?.takeIf { it.isNotBlank() } ?: DECODE_FAILED))))
        }
    }

    private fun resolve(descriptor: SerialDescriptor): SerialDescriptor {
        var current = descriptor
        if (current.kind == SerialKind.CONTEXTUAL) {
            current = json.serializersModule.getContextualDescriptor(current) ?: return current
        }
        while (current.isInline && baseName(current) !in leafTypes) current = current.getElementDescriptor(0)
        return current
    }

    private fun baseName(descriptor: SerialDescriptor): String = descriptor.serialName.removeSuffix("?")

    /** Free-form JSON members (`JsonElement`, `JsonObject`, ...) accept anything. */
    private fun isFreeForm(descriptor: SerialDescriptor): Boolean = baseName(descriptor).startsWith("kotlinx.serialization.json.")

    private fun collectUnknown(
        descriptor: SerialDescriptor,
        value: JsonElement,
        path: String,
        out: MutableList<String>
    ) {
        if (value is JsonNull) return
        val d = resolve(descriptor)
        if (isFreeForm(d) || baseName(d) in leafTypes) return
        when (d.kind) {
            StructureKind.CLASS, StructureKind.OBJECT -> {
                val obj = value as? JsonObject ?: return
                for ((key, child) in obj) {
                    val index = d.getElementIndex(key)
                    if (index == CompositeDecoder.UNKNOWN_NAME) {
                        out.add(childPath(path, key))
                    } else {
                        collectUnknown(d.getElementDescriptor(index), child, childPath(path, key), out)
                    }
                }
            }
            StructureKind.LIST -> {
                val array = value as? JsonArray ?: return
                array.forEachIndexed {
                    i,
                    element
                    ->
                    collectUnknown(d.getElementDescriptor(0), element, childPath(path, i.toString()), out)
                }
            }
            StructureKind.MAP -> {
                val obj = value as? JsonObject ?: return
                for ((key, child) in obj) collectUnknown(d.getElementDescriptor(1), child, childPath(path, key), out)
            }
            else -> {}
        }
    }

    private fun checkValue(
        descriptor: SerialDescriptor,
        value: JsonElement,
        path: String,
        out: MutableList<FieldViolation>
    ) {
        if (value is JsonNull) {
            if (!descriptor.isNullable) out.add(FieldViolation(pointer(path), "must not be null", "null"))
            return
        }
        val d = resolve(descriptor)
        if (isFreeForm(d)) return
        leafTypes[baseName(d)]?.let { leaf ->
            if (!decodes(leaf.serializer, value)) out.add(FieldViolation(pointer(path), leaf.reason, received(value)))
            return
        }
        val primitive = value as? JsonPrimitive
        when (val kind = d.kind) {
            StructureKind.CLASS, StructureKind.OBJECT -> {
                val obj = value as? JsonObject
                if (obj == null) {
                    out.add(FieldViolation(pointer(path), "must be an object", received(value)))
                    return
                }
                if (kind == StructureKind.OBJECT) return
                for (i in 0 until d.elementsCount) {
                    val name = d.getElementName(i)
                    val child = obj[name]
                    if (child == null) {
                        if (!d.isElementOptional(i)) out.add(FieldViolation(childPath(path, name), "is required"))
                    } else {
                        checkValue(d.getElementDescriptor(i), child, childPath(path, name), out)
                    }
                }
            }
            StructureKind.LIST -> {
                val array = value as? JsonArray
                if (array == null) {
                    out.add(FieldViolation(pointer(path), "must be an array", received(value)))
                    return
                }
                array.forEachIndexed { i, element -> checkValue(d.getElementDescriptor(0), element, childPath(path, i.toString()), out) }
            }
            StructureKind.MAP -> {
                val obj = value as? JsonObject
                if (obj == null) {
                    out.add(FieldViolation(pointer(path), "must be an object", received(value)))
                    return
                }
                for ((key, child) in obj) checkValue(d.getElementDescriptor(1), child, childPath(path, key), out)
            }
            SerialKind.ENUM -> {
                val names = (0 until d.elementsCount).map { d.getElementName(it) }
                if (primitive == null || !primitive.isString || primitive.content !in names) {
                    out.add(FieldViolation(pointer(path), "must be one of: ${names.joinToString(", ")}", received(value)))
                }
            }
            PrimitiveKind.STRING ->
                if (primitive == null || !primitive.isString) out.add(FieldViolation(pointer(path), "must be a string", received(value)))
            PrimitiveKind.CHAR ->
                if (primitive == null || !primitive.isString || primitive.content.length != 1) {
                    out.add(FieldViolation(pointer(path), "must be a single character", received(value)))
                }
            PrimitiveKind.BOOLEAN ->
                if (primitive == null || primitive.isString || primitive.booleanOrNull == null) {
                    out.add(FieldViolation(pointer(path), "must be a boolean", received(value)))
                }
            PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG -> {
                val range = INTEGER_RANGES.getValue(kind as PrimitiveKind)
                val number =
                    if (primitive == null ||
                        primitive.isString ||
                        !INTEGER.matches(primitive.content)
                    ) {
                        null
                    } else {
                        primitive.content.toLongOrNull()
                    }
                if (number == null ||
                    number !in range
                ) {
                    out.add(FieldViolation(pointer(path), "must be an integer in $range", received(value)))
                }
            }
            PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE ->
                if (primitive == null || primitive.isString || primitive.doubleOrNull == null) {
                    out.add(FieldViolation(pointer(path), "must be a number", received(value)))
                }
            is PolymorphicKind, SerialKind.CONTEXTUAL -> {}
        }
    }

    private fun decodes(
        serializer: KSerializer<*>,
        value: JsonElement
    ): Boolean =
        try {
            json.decodeFromJsonElement(serializer, value)
            true
        } catch (e: IllegalArgumentException) {
            false
        }

    private fun invalidRequest(fields: List<FieldViolation>): DomainError =
        DomainError(
            code = ErrorCode.INVALID_REQUEST,
            message = "The request has ${fields.size} invalid field(s): ${fields.joinToString(", ") { it.field }}.",
            detail = ErrorDetail.InvalidRequest(fields)
        )

    private fun unknownParameter(parameters: List<String>): DomainError =
        DomainError(
            code = ErrorCode.UNKNOWN_PARAMETER,
            message = "The request has ${parameters.size} unknown parameter(s): ${parameters.joinToString(", ")}.",
            detail = ErrorDetail.UnknownParameter(parameters)
        )

    private companion object {
        /** The field name of a fault on the whole request. */
        const val ROOT = "$"

        const val MAX_RECEIVED = 100

        const val DECODE_FAILED = "could not be decoded"

        val INTEGER = Regex("^-?(0|[1-9][0-9]*)$")

        val INTEGER_RANGES: Map<PrimitiveKind, LongRange> =
            mapOf(
                PrimitiveKind.BYTE to Byte.MIN_VALUE.toLong()..Byte.MAX_VALUE.toLong(),
                PrimitiveKind.SHORT to Short.MIN_VALUE.toLong()..Short.MAX_VALUE.toLong(),
                PrimitiveKind.INT to Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong(),
                PrimitiveKind.LONG to Long.MIN_VALUE..Long.MAX_VALUE
            )

        /** RFC 6901 child pointer: `~` -> `~0`, `/` -> `~1`. */
        fun childPath(
            path: String,
            token: String
        ): String = "$path/${token.replace("~", "~0").replace("/", "~1")}"

        fun pointer(path: String): String = path.ifEmpty { ROOT }

        fun received(value: JsonElement): String =
            when (value) {
                is JsonNull -> "null"
                is JsonPrimitive -> value.content.take(MAX_RECEIVED)
                is JsonObject -> "object"
                is JsonArray -> "array"
            }
    }
}
