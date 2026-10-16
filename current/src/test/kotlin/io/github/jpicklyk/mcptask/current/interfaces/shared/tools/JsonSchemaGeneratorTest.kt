package io.github.jpicklyk.mcptask.current.interfaces.shared.tools

import io.github.jpicklyk.mcptask.current.application.api.Doc
import io.github.jpicklyk.mcptask.current.application.api.IsoInstantSerializer
import io.github.jpicklyk.mcptask.current.application.support.IdRef
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item 713c5c61) for [JsonSchemaGenerator], scenarios S1 and S2 of the frozen test-plan
 * (NEW-SURFACE).
 *
 * Oracles: the task-scope Contract mapping for `JsonSchemaGenerator` (CLASS -> object with `properties`, `required` = elements
 * with `!isElementOptional` in declaration order and omitted when empty; STRING/CHAR -> string; BYTE/SHORT/INT/LONG -> integer;
 * FLOAT/DOUBLE -> number; BOOLEAN -> boolean; ENUM -> string with the serial names; LIST -> array with items; MAP -> object with
 * additionalProperties; contextual Instant -> string date-time; IdRef and other string leaves -> string; JsonObject -> object;
 * JsonElement -> no `type`; nullable keeps its type; `description` = property `@Doc`, else the member type class `@Doc`, else
 * absent) and JSON Schema keyword semantics. Expected schemas are literal JSON written from those rules.
 *
 * Fixtures are the test's own `@Serializable` types; the generator reads their serial descriptors.
 */
class JsonSchemaGeneratorTest {
    @Serializable
    @Doc("Hue of the thing.")
    enum class Color { RED, DARK_BLUE }

    @Serializable
    @Doc("A nested thing.")
    data class Inner(
        @Doc("Inner name.") val name: String,
        val plain: Int = 0
    )

    @Serializable
    data class Everything(
        val s: String,
        val c: Char = 'x',
        val b: Byte = 0,
        val sh: Short = 0,
        val i: Int = 0,
        val l: Long = 0,
        val f: Float = 0f,
        val d: Double = 0.0,
        val flag: Boolean = false,
        val color: Color = Color.RED,
        val names: List<String> = emptyList(),
        val byKey: Map<String, Int> = emptyMap(),
        @Serializable(with = IsoInstantSerializer::class) val at: Instant? = null,
        val ref: IdRef? = null,
        val obj: JsonObject? = null,
        val any: JsonElement? = null,
        val inner: Inner? = null,
        val maybe: String? = null
    )

    @Serializable
    data class Mixed(
        val a: String,
        val b: Int?,
        val c: Long = 3,
        val d: List<Int>
    )

    @Serializable
    data class AllOptional(
        val x: Int = 1,
        val y: String? = null
    )

    @Serializable
    data class Docs(
        @Doc("Prop wins.") val explicit: Inner,
        val fromClass: Inner,
        val neither: Int = 0,
        val enumFromClass: Color = Color.RED,
        @Doc("Enum prop doc.") val enumExplicit: Color = Color.RED
    )

    @Serializable
    data class Holder(
        val rows: List<Inner>
    )

    @Serializable
    data class WithContextual(
        @Contextual val at: Instant
    )

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private inline fun <reified T> schemaOf(): JsonObject = JsonSchemaGenerator.schema(serializer<T>().descriptor)

    private fun JsonObject.prop(name: String): JsonObject =
        this
            .getValue("properties")
            .jsonObject
            .getValue(name)
            .jsonObject

    // ------------------------------------------------------------------ S1

    @Test
    fun `S1 each scalar kind maps to its JSON Schema type`() {
        val schema = schemaOf<Everything>()
        assertEquals("object", schema.getValue("type").jsonPrimitive.content)
        assertEquals(json("""{"type":"string"}"""), schema.prop("s"))
        assertEquals(json("""{"type":"string"}"""), schema.prop("c"), "CHAR is a string")
        assertEquals(json("""{"type":"integer"}"""), schema.prop("b"), "BYTE is an integer")
        assertEquals(json("""{"type":"integer"}"""), schema.prop("sh"), "SHORT is an integer")
        assertEquals(json("""{"type":"integer"}"""), schema.prop("i"))
        assertEquals(json("""{"type":"integer"}"""), schema.prop("l"), "LONG is an integer, not a number")
        assertEquals(json("""{"type":"number"}"""), schema.prop("f"))
        assertEquals(json("""{"type":"number"}"""), schema.prop("d"))
        assertEquals(json("""{"type":"boolean"}"""), schema.prop("flag"))
    }

    @Test
    fun `S1 an enum is a string with its serial names in declaration order`() {
        val color = schemaOf<Everything>().prop("color")
        assertEquals("string", color.getValue("type").jsonPrimitive.content)
        assertEquals(listOf("RED", "DARK_BLUE"), color.getValue("enum").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `S1 a list is an array with items and a map is an object with additionalProperties of its value schema`() {
        val schema = schemaOf<Everything>()
        assertEquals(json("""{"type":"array","items":{"type":"string"}}"""), schema.prop("names"))
        assertEquals(json("""{"type":"object","additionalProperties":{"type":"integer"}}"""), schema.prop("byKey"))
    }

    @Test
    fun `S1 a list of classes carries the class object schema as items`() {
        val rows = schemaOf<Holder>().prop("rows")
        assertEquals("array", rows.getValue("type").jsonPrimitive.content)
        val items = rows.getValue("items").jsonObject
        assertEquals("object", items.getValue("type").jsonPrimitive.content)
        assertEquals(
            listOf("name", "plain"),
            items
                .getValue("properties")
                .jsonObject.keys
                .toList()
        )
        assertEquals(listOf("name"), items.getValue("required").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `S1 IdRef and an Instant serializer are strings and JsonObject is an object with no further keys`() {
        val schema = schemaOf<Everything>()
        assertEquals(json("""{"type":"string"}"""), schema.prop("ref"))
        assertEquals(
            "string",
            schema
                .prop("at")
                .getValue("type")
                .jsonPrimitive.content
        )
        assertEquals(json("""{"type":"object"}"""), schema.prop("obj"))
    }

    @Test
    fun `S1 a contextual Instant is a string with format date-time`() {
        val at = schemaOf<WithContextual>().prop("at")
        assertEquals(json("""{"type":"string","format":"date-time"}"""), at)
    }

    @Test
    fun `S1 JsonElement has no type keyword`() {
        val any = schemaOf<Everything>().prop("any")
        assertFalse("type" in any, "a JsonElement can be any JSON value: $any")
    }

    @Test
    fun `S1 a nested class is an object with its own properties and required`() {
        val inner = schemaOf<Everything>().prop("inner")
        assertEquals("object", inner.getValue("type").jsonPrimitive.content)
        assertEquals(json("""{"type":"string","description":"Inner name."}"""), inner.prop("name"))
        assertEquals(json("""{"type":"integer"}"""), inner.prop("plain"))
        assertEquals(listOf("name"), inner.getValue("required").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `S1 required is exactly the elements without defaults, in declaration order, nullable ones included`() {
        val schema = schemaOf<Mixed>()
        assertEquals(listOf("a", "b", "d"), schema.getValue("required").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("a", "b", "d"), JsonSchemaGenerator.required(serializer<Mixed>().descriptor))
        assertEquals(json("""{"type":"integer"}"""), schema.prop("b"), "a nullable keeps its type; optionality is only required")
        assertEquals(json("""{"type":"integer"}"""), schema.prop("c"))
    }

    @Test
    fun `S1 required is omitted when every element has a default`() {
        val schema = schemaOf<AllOptional>()
        assertFalse("required" in schema, "an empty required list must be absent, got $schema")
        assertEquals(emptyList(), JsonSchemaGenerator.required(serializer<AllOptional>().descriptor))
    }

    @Test
    fun `S1 required in Everything lists only the one non-defaulted element`() {
        val schema = schemaOf<Everything>()
        assertEquals(listOf("s"), schema.getValue("required").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `S1 properties keeps declaration order and matches the schema properties`() {
        val descriptor = serializer<Everything>().descriptor
        val props = JsonSchemaGenerator.properties(descriptor)
        assertEquals(
            listOf("s", "c", "b", "sh", "i", "l", "f", "d", "flag", "color", "names", "byKey", "at", "ref", "obj", "any", "inner", "maybe"),
            props.keys.toList()
        )
        assertEquals(
            JsonSchemaGenerator
                .schema(descriptor)
                .getValue("properties")
                .jsonObject
                .toMap(),
            props.toMap()
        )
    }

    // ------------------------------------------------------------------ S2

    @Test
    fun `S2 a property Doc wins over the member class Doc`() {
        assertEquals(
            "Prop wins.",
            schemaOf<Docs>()
                .prop("explicit")
                .getValue("description")
                .jsonPrimitive.content
        )
    }

    @Test
    fun `S2 without a property Doc the member class Doc is the description`() {
        assertEquals(
            "A nested thing.",
            schemaOf<Docs>()
                .prop("fromClass")
                .getValue("description")
                .jsonPrimitive.content
        )
        assertEquals(
            "Hue of the thing.",
            schemaOf<Docs>()
                .prop("enumFromClass")
                .getValue("description")
                .jsonPrimitive.content
        )
    }

    @Test
    fun `S2 an enum property Doc wins over the enum class Doc`() {
        assertEquals(
            "Enum prop doc.",
            schemaOf<Docs>()
                .prop("enumExplicit")
                .getValue("description")
                .jsonPrimitive.content
        )
    }

    @Test
    fun `S2 with neither Doc there is no description key`() {
        val neither = schemaOf<Docs>().prop("neither")
        assertEquals(json("""{"type":"integer"}"""), neither)
        assertFalse("description" in neither)
    }

    @Test
    fun `S2 a nested property keeps its own Doc and carries no description from its parent`() {
        val inner = schemaOf<Docs>().prop("explicit")
        assertEquals(
            "Inner name.",
            inner
                .prop("name")
                .getValue("description")
                .jsonPrimitive.content
        )
        assertFalse("description" in inner.prop("plain"), "plain has no Doc of its own: ${inner.prop("plain")}")
    }

    @Test
    fun `S2 a schema with no Doc anywhere has no description at any level`() {
        val schema = schemaOf<Mixed>()
        assertFalse(schema.toString().contains("description"), "no @Doc was declared on Mixed: $schema")
        assertTrue(schema.getValue("properties").jsonObject.isNotEmpty())
    }
}
