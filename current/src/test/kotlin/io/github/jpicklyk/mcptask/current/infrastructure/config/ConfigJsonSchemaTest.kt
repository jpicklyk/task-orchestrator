package io.github.jpicklyk.mcptask.current.infrastructure.config

import io.github.jpicklyk.mcptask.current.test.StrictConfigCases
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.name
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item edae92cb / Q16) for [ConfigJsonSchema] and the schema document it serves:
 * S13 (the resource), S14 (parity between the parser's key catalog and the schema's properties, both directions) and
 * decision D4 (one copy only).
 *
 * Oracles: P s4.4 (JSON Schema 2020-12, one copy, `application/schema+json`); task-scope build item 1
 * (`additionalProperties: false` at every modeled level). The parity walk navigates by the config FORMAT
 * (`work_item_schemas.<type>.notes[]`, `traits.<name>`), following local `$ref`s generically, so it does not depend on
 * the schema's internal definition names.
 */
class ConfigJsonSchemaTest {
    private val schema: JsonObject get() = Json.parseToJsonElement(ConfigJsonSchema.text).jsonObject

    // ---------------------------------------------------------------------------------------------
    // S13 - the resource
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S13 the constants name the classpath resource and the schema media type`() {
        assertEquals("/config/config.schema.json", ConfigJsonSchema.RESOURCE_PATH)
        assertEquals("application/schema+json", ConfigJsonSchema.MEDIA_TYPE)
    }

    @Test
    fun `S13 bytes are exactly the classpath resource and text is their UTF-8 decoding`() {
        val onClasspath =
            assertNotNull(
                ConfigJsonSchemaTest::class.java.getResourceAsStream("/config/config.schema.json"),
                "schema must be on the classpath"
            ).use {
                it.readBytes()
            }

        assertTrue(onClasspath.isNotEmpty())
        assertTrue(onClasspath.contentEquals(ConfigJsonSchema.bytes), "bytes must equal the resource byte-for-byte")
        assertEquals(String(onClasspath, Charsets.UTF_8), ConfigJsonSchema.text)
    }

    @Test
    fun `S13 the document declares JSON Schema 2020-12`() {
        val declared = (schema["\$schema"] as? JsonPrimitive)?.contentOrNull

        assertEquals("https://json-schema.org/draft/2020-12/schema", declared)
    }

    @Test
    fun `S13 the etag is non-blank and stable across reads`() {
        val first = ConfigJsonSchema.etag
        val second = ConfigJsonSchema.etag

        assertEquals(first, second)
        assertTrue(first.isNotBlank())
    }

    @Test
    fun `D4 exactly one config schema file exists under the current module outside build output`() {
        val current = StrictConfigCases.repoRoot().resolve("current")
        val found =
            Files.walk(current).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) && it.name == "config.schema.json" }
                    .filter { path ->
                        path
                            .toString()
                            .replace('\\', '/')
                            .split('/')
                            .none { it == "build" }
                    }.map { it.toString().replace('\\', '/') }
                    .toList()
            }

        assertEquals(1, found.size, "the schema must have exactly one source copy, found: $found")
        assertTrue(found.single().endsWith("src/main/resources/config/config.schema.json"), "unexpected location: $found")
    }

    // ---------------------------------------------------------------------------------------------
    // S14 - parity with the parser key catalog
    // ---------------------------------------------------------------------------------------------

    private fun resolve(
        node: JsonObject,
        depth: Int = 0
    ): JsonObject {
        val ref = (node["\$ref"] as? JsonPrimitive)?.contentOrNull ?: return node
        check(ref.startsWith("#/") && depth < 20) { "unsupported or cyclic \$ref: $ref" }
        var current: JsonElement = schema
        for (segment in ref.removePrefix("#/").split('/')) {
            current = (current as JsonObject)[segment.replace("~1", "/").replace("~0", "~")] ?: error("dangling \$ref $ref at $segment")
        }
        return resolve(current.jsonObject, depth + 1)
    }

    /** The node plus its oneOf/anyOf/allOf alternatives, each resolved. */
    private fun alternatives(element: JsonElement?): List<JsonObject> {
        val node = resolve((element as? JsonObject) ?: error("expected a schema object, got $element"))
        val nested =
            listOf("oneOf", "anyOf", "allOf").flatMap { keyword ->
                ((node[keyword] as? JsonArray) ?: JsonArray(emptyList())).flatMap { alternatives(it) }
            }
        return listOf(node) + nested
    }

    private fun properties(nodes: List<JsonObject>): Map<String, JsonElement> =
        nodes.flatMap { (it["properties"] as? JsonObject)?.entries.orEmpty() }.associate {
            it.key to
                it.value
        }

    private fun valueSchema(nodes: List<JsonObject>): List<JsonObject> {
        val values =
            nodes.flatMap { node ->
                val additional = node["additionalProperties"] as? JsonObject
                val pattern = (node["patternProperties"] as? JsonObject)?.values?.toList().orEmpty()
                listOfNotNull(additional) + pattern.map { it.jsonObject }
            }
        check(values.isNotEmpty()) { "no value schema found for a name-keyed mapping" }
        return values.flatMap { alternatives(it) }
    }

    private fun itemSchema(nodes: List<JsonObject>): List<JsonObject> {
        val items = nodes.mapNotNull { it["items"] as? JsonObject }
        check(items.isNotEmpty()) { "no items schema found for a list" }
        return items.flatMap { alternatives(it) }
    }

    private fun levelWithProperties(nodes: List<JsonObject>): List<JsonObject> = nodes.filter { it["properties"] is JsonObject }

    private val rootNodes: List<JsonObject> get() = alternatives(schema)
    private val schemaLevel: List<JsonObject> get() =
        levelWithProperties(
            valueSchema(alternatives(properties(rootNodes).getValue("work_item_schemas")))
        )
    private val traitLevel: List<JsonObject> get() =
        levelWithProperties(
            valueSchema(alternatives(properties(rootNodes).getValue("traits")))
        )
    private val noteEntryLevel: List<JsonObject> get() =
        levelWithProperties(
            itemSchema(alternatives(properties(schemaLevel).getValue("notes")))
        )

    @Test
    fun `S14 top-level schema properties equal the parser TOP_LEVEL catalog both ways`() {
        assertEquals(ConfigKeyCatalog.TOP_LEVEL, properties(rootNodes).keys)
    }

    @Test
    fun `S14 schema-level properties equal the parser SCHEMA_LEVEL catalog both ways`() {
        assertEquals(ConfigKeyCatalog.SCHEMA_LEVEL, properties(schemaLevel).keys)
    }

    @Test
    fun `S14 trait-level properties equal the parser TRAIT_LEVEL catalog both ways`() {
        assertEquals(ConfigKeyCatalog.TRAIT_LEVEL, properties(traitLevel).keys)
    }

    @Test
    fun `S14 note-entry properties equal the parser NOTE_ENTRY catalog both ways`() {
        assertEquals(ConfigKeyCatalog.NOTE_ENTRY, properties(noteEntryLevel).keys)
    }

    @Test
    fun `S14 every modeled level forbids additional properties`() {
        val levels =
            mapOf(
                "top level" to rootNodes,
                "schema level" to schemaLevel,
                "trait level" to traitLevel,
                "note entry" to noteEntryLevel
            )

        for ((name, nodes) in levels) {
            val withProperties = levelWithProperties(nodes)
            assertTrue(withProperties.isNotEmpty(), "$name: no node declares properties")
            for (node in withProperties) {
                assertEquals(
                    false,
                    (node["additionalProperties"] as? JsonPrimitive)?.booleanOrNull,
                    "$name must set additionalProperties: false"
                )
            }
        }
    }

    @Test
    fun `S14 the migration-only top-level keys are not properties of the strict schema`() {
        val modeled = properties(rootNodes).keys

        for (key in ConfigKeyCatalog.TOP_LEVEL_MIGRATION_ONLY) {
            assertTrue(
                key !in modeled,
                "$key is accepted only when migrating stored 3.x versions and must not be modeled by the strict schema"
            )
        }
    }

    @Test
    fun `S14 extensions is modeled as an open mapping`() {
        val extensions = alternatives(properties(rootNodes).getValue("extensions"))

        assertTrue(
            extensions.none {
                (it["additionalProperties"] as? JsonPrimitive)?.booleanOrNull == false
            },
            "extensions must not forbid additional properties"
        )
    }
}
