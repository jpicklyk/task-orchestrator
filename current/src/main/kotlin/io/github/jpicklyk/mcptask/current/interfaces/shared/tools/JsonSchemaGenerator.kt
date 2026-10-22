package io.github.jpicklyk.mcptask.current.interfaces.shared.tools

import io.github.jpicklyk.mcptask.current.application.api.Doc
import io.github.jpicklyk.mcptask.current.application.api.IsoInstantSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.getContextualDescriptor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import java.time.Instant

/**
 * Generates the JSON Schema of a request class by walking its [SerialDescriptor] (W2 brief section 3.3),
 * so a request class is the one source of both decoding and documentation.
 *
 * | Descriptor | Schema |
 * |---|---|
 * | CLASS | `{type:object, properties, required?}`; `required` lists the non-optional elements in declaration order and is omitted when empty |
 * | OBJECT | `{type:object}` |
 * | STRING, CHAR | `{type:string}` |
 * | BYTE, SHORT, INT, LONG | `{type:integer}` |
 * | FLOAT, DOUBLE | `{type:number}` |
 * | BOOLEAN | `{type:boolean}` |
 * | ENUM | `{type:string, enum:[serial names]}` |
 * | LIST | `{type:array, items}` |
 * | MAP | `{type:object, additionalProperties: <value schema>}` |
 * | contextual or `IsoInstantSerializer` `Instant` | `{type:string, format:date-time}` |
 * | `IdRef` and other inline or string leaves | `{type:string}` |
 * | `JsonObject` | `{type:object}` |
 * | `JsonArray` | `{type:array}` |
 * | `JsonElement`, `JsonPrimitive` | `{}` (any value) |
 *
 * A nullable member keeps its type (optionality is expressed by `required` only). `description` is the
 * property's [Doc], else the member type's class [Doc], else absent; the root schema takes its class
 * [Doc]. Keys are emitted in the order `type, description, format, enum, items, properties, required,
 * additionalProperties`. A polymorphic or sealed member, or a recursive class, is rejected with
 * [IllegalArgumentException] naming its path.
 */
@OptIn(ExperimentalSerializationApi::class)
object JsonSchemaGenerator {
    private val module = SerializersModule { contextual(Instant::class, IsoInstantSerializer) }

    private const val JSON_PACKAGE = "kotlinx.serialization.json."

    fun schema(descriptor: SerialDescriptor): JsonObject = generate(descriptor, classDoc(descriptor), "$", ArrayList())

    /**
     * The schema of each top-level element of a CLASS descriptor, by name in declaration order, with the
     * same description rule as [schema]. Empty for an OBJECT descriptor.
     */
    fun properties(descriptor: SerialDescriptor): LinkedHashMap<String, JsonObject> {
        val d = resolve(descriptor)
        val out = LinkedHashMap<String, JsonObject>()
        if (d.kind != StructureKind.CLASS) return out
        for (i in 0 until d.elementsCount) {
            val name = d.getElementName(i)
            out[name] = member(d, i, "$/$name", arrayListOf(baseName(d)))
        }
        return out
    }

    /** The names of the non-optional top-level elements of a CLASS descriptor, in declaration order. */
    fun required(descriptor: SerialDescriptor): List<String> {
        val d = resolve(descriptor)
        if (d.kind != StructureKind.CLASS) return emptyList()
        return (0 until d.elementsCount).filter { !d.isElementOptional(it) }.map { d.getElementName(it) }
    }

    /** The [Doc] text of a class descriptor, else null. */
    internal fun classDoc(descriptor: SerialDescriptor): String? {
        val d =
            if (descriptor.kind == SerialKind.CONTEXTUAL) module.getContextualDescriptor(descriptor) ?: descriptor else descriptor
        return d.annotations
            .filterIsInstance<Doc>()
            .firstOrNull()
            ?.text
    }

    private fun member(
        parent: SerialDescriptor,
        index: Int,
        path: String,
        stack: ArrayList<String>
    ): JsonObject {
        val child = parent.getElementDescriptor(index)
        val doc =
            parent
                .getElementAnnotations(index)
                .filterIsInstance<Doc>()
                .firstOrNull()
                ?.text
                ?: classDoc(child)
        return generate(child, doc, path, stack)
    }

    private fun resolve(descriptor: SerialDescriptor): SerialDescriptor {
        var current = descriptor
        if (current.kind == SerialKind.CONTEXTUAL) current = module.getContextualDescriptor(current) ?: return current
        while (current.isInline) current = current.getElementDescriptor(0)
        return current
    }

    private fun baseName(descriptor: SerialDescriptor): String = descriptor.serialName.removeSuffix("?")

    private fun generate(
        descriptor: SerialDescriptor,
        description: String?,
        path: String,
        stack: ArrayList<String>
    ): JsonObject {
        val d = resolve(descriptor)
        val name = baseName(d)
        // Free-form JSON members first: JsonElement's own descriptor is a sealed (polymorphic) kind.
        if (name.startsWith(JSON_PACKAGE)) {
            return when (name.removePrefix(JSON_PACKAGE)) {
                "JsonObject" -> node("object", description)
                "JsonArray" -> node("array", description)
                else -> node(null, description)
            }
        }
        if (d.kind == SerialKind.CONTEXTUAL) throw IllegalArgumentException("$path: contextual type '$name' has no registered serializer")
        if (d.kind is PolymorphicKind) {
            throw IllegalArgumentException(
                "$path: polymorphic or sealed type '$name' is not allowed in a request"
            )
        }
        if (name == baseName(IsoInstantSerializer.descriptor)) {
            return node("string", description) { put("format", "date-time") }
        }

        return when (val kind = d.kind) {
            StructureKind.CLASS -> {
                if (name in stack) throw IllegalArgumentException("$path: recursive type '$name' is not allowed in a request")
                stack.add(name)
                try {
                    val properties = LinkedHashMap<String, JsonObject>()
                    val required = ArrayList<String>()
                    for (i in 0 until d.elementsCount) {
                        val element = d.getElementName(i)
                        properties[element] = member(d, i, "$path/$element", stack)
                        if (!d.isElementOptional(i)) required.add(element)
                    }
                    node("object", description) {
                        put("properties", JsonObject(properties))
                        if (required.isNotEmpty()) put("required", JsonArray(required.map { JsonPrimitive(it) }))
                    }
                } finally {
                    stack.removeAt(stack.size - 1)
                }
            }
            StructureKind.OBJECT -> node("object", description)
            StructureKind.LIST -> {
                val items = generate(d.getElementDescriptor(0), classDoc(d.getElementDescriptor(0)), "$path/items", stack)
                node("array", description) { put("items", items) }
            }
            StructureKind.MAP -> {
                val value = generate(d.getElementDescriptor(1), classDoc(d.getElementDescriptor(1)), "$path/additionalProperties", stack)
                node("object", description) { put("additionalProperties", value) }
            }
            SerialKind.ENUM ->
                node("string", description) {
                    put("enum", JsonArray((0 until d.elementsCount).map { JsonPrimitive(d.getElementName(it)) }))
                }
            PrimitiveKind.STRING, PrimitiveKind.CHAR -> node("string", description)
            PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG -> node("integer", description)
            PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> node("number", description)
            PrimitiveKind.BOOLEAN -> node("boolean", description)
            is PolymorphicKind, SerialKind.CONTEXTUAL -> throw IllegalArgumentException("$path: unsupported type '$name' ($kind)")
        }
    }

    private fun node(
        type: String?,
        description: String?,
        rest: JsonObjectBuilder.() -> Unit = {}
    ): JsonObject =
        buildJsonObject {
            type?.let { put("type", it) }
            description?.let { put("description", it) }
            rest()
        }
}
