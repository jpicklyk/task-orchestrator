package io.github.jpicklyk.mcptask.current.infrastructure.config

import io.github.jpicklyk.mcptask.current.application.config.ConfigDocument
import io.github.jpicklyk.mcptask.current.application.config.ConfigDocumentParser
import io.github.jpicklyk.mcptask.current.application.config.SchemaResolutionMode
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independently authored against the frozen `test-plan` note on item `df7d579a` (scenarios
 * S5, S7, S8, S12, S14). Exercises [YamlSchemaParser.parseRoot] directly (mirroring
 * [YamlSchemaParserRoleWarningTest]'s SafeConstructor-based parsing style) for the new
 * [ConfigDocument] fields C1 introduces (`noteLimitsMode`, `statusLabels`, `schemaResolution`,
 * `actorAuthenticationSection`, `presentSections`), plus [YamlConfigDocumentParser] (the new
 * exception-to-`Outcome` wrapper) and the [ConfigDocument.PER_ROOT_HONORED_SECTIONS] constant.
 */
class ConfigDocumentParseTest {
    private fun parse(
        yaml: String,
        warnOnMissingSchemas: Boolean = false,
    ): YamlSchemaParser.ParsedConfig {
        @Suppress("UNCHECKED_CAST")
        val root = Yaml(SafeConstructor(LoaderOptions())).load<Map<String, Any>>(yaml) ?: emptyMap()
        return YamlSchemaParser.parseRoot(root, warnOnMissingSchemas = warnOnMissingSchemas)
    }

    private val noSchemaSection = "work_item_schemas:\n  default:\n    notes: []\n"

    // ──────────────────────────────────────────────
    // S5 — noteLimitsMode
    // ──────────────────────────────────────────────

    @Test
    fun `S5 note_limits absent yields null noteLimitsMode`() {
        assertNull(parse(noSchemaSection).noteLimitsMode)
    }

    @Test
    fun `S5 note_limits with mode reject yields reject`() {
        assertEquals("reject", parse("note_limits:\n  mode: reject\n").noteLimitsMode)
    }

    @Test
    fun `S5 note_limits present as an empty map yields warn with no warning`() {
        val doc = parse("note_limits: {}\n")
        assertEquals("warn", doc.noteLimitsMode)
        assertTrue(doc.warnings.none { it.contains("note_limits") }, "warnings: ${doc.warnings}")
    }

    @Test
    fun `S5 note_limits present as an explicit null yields warn with no warning`() {
        val doc = parse("note_limits: ~\n")
        assertEquals("warn", doc.noteLimitsMode)
        assertTrue(doc.warnings.none { it.contains("note_limits") }, "warnings: ${doc.warnings}")
    }

    @Test
    fun `S5 note_limits with an invalid mode yields warn plus exactly one warning`() {
        val doc = parse("note_limits:\n  mode: bogus-mode\n")
        assertEquals("warn", doc.noteLimitsMode)
        assertEquals(1, doc.warnings.count { it.contains("note_limits") }, "warnings: ${doc.warnings}")
    }

    // ──────────────────────────────────────────────
    // S5 — statusLabels
    // ──────────────────────────────────────────────

    @Test
    fun `S5 status_labels absent yields null`() {
        assertNull(parse(noSchemaSection).statusLabels)
    }

    @Test
    fun `S5 status_labels with an explicit null trigger value is a present key mapped to null`() {
        val doc = parse("status_labels:\n  start: null\n")
        assertNotNull(doc.statusLabels)
        assertTrue(doc.statusLabels!!.containsKey("start"))
        assertNull(doc.statusLabels!!["start"])
    }

    @Test
    fun `S5 status_labels as a scalar yields null plus a warning`() {
        val doc = parse("status_labels: \"oops\"\n")
        assertNull(doc.statusLabels)
        assertTrue(doc.warnings.any { it.contains("status_labels") }, "warnings: ${doc.warnings}")
    }

    // ──────────────────────────────────────────────
    // S7 — schemaResolution
    // ──────────────────────────────────────────────

    @Test
    fun `S7 schema_resolution legacy, layered and isolated parse to their enum values`() {
        assertEquals(SchemaResolutionMode.LEGACY, parse("schema_resolution: legacy\n").schemaResolution)
        assertEquals(SchemaResolutionMode.LAYERED, parse("schema_resolution: layered\n").schemaResolution)
        assertEquals(SchemaResolutionMode.ISOLATED, parse("schema_resolution: isolated\n").schemaResolution)
    }

    @Test
    fun `S7 schema_resolution absent yields null`() {
        assertNull(parse(noSchemaSection).schemaResolution)
    }

    @Test
    fun `S7 schema_resolution invalid values yield null and add exactly one warning`() {
        val baseline = parse(noSchemaSection)

        val wrongCase = parse("schema_resolution: LAYERED\n$noSchemaSection")
        assertNull(wrongCase.schemaResolution, "SchemaResolutionMode.fromConfigString is exact-lowercase only")
        assertEquals(baseline.warnings.size + 1, wrongCase.warnings.size, "warnings: ${wrongCase.warnings}")

        val bogus = parse("schema_resolution: bogus\n$noSchemaSection")
        assertNull(bogus.schemaResolution)
        assertEquals(baseline.warnings.size + 1, bogus.warnings.size, "warnings: ${bogus.warnings}")

        val numeric = parse("schema_resolution: 5\n$noSchemaSection")
        assertNull(numeric.schemaResolution)
        assertEquals(baseline.warnings.size + 1, numeric.warnings.size, "warnings: ${numeric.warnings}")

        val mapValue = parse("schema_resolution:\n  nested: true\n$noSchemaSection")
        assertNull(mapValue.schemaResolution)
        assertEquals(baseline.warnings.size + 1, mapValue.warnings.size, "warnings: ${mapValue.warnings}")
    }

    // ──────────────────────────────────────────────
    // S8 — presentSections / actorAuthenticationSection
    // ──────────────────────────────────────────────

    @Test
    fun `S8 presentSections lists top-level keys in document order`() {
        val doc =
            parse(
                """
                status_labels:
                  start: "x"
                work_item_schemas:
                  default:
                    notes: []
                note_limits:
                  mode: warn
                """.trimIndent(),
            )
        assertEquals(listOf("status_labels", "work_item_schemas", "note_limits"), doc.presentSections.toList())
    }

    @Test
    fun `S8 presentSections is empty for a blank document`() {
        assertTrue(parse("").presentSections.isEmpty())
    }

    @Test
    fun `S8 actorAuthenticationSection carries the raw map value`() {
        val doc =
            parse(
                """
                actor_authentication:
                  enabled: true
                  verifier:
                    type: noop
                """.trimIndent(),
            )
        val section = doc.actorAuthenticationSection
        assertTrue(section is Map<*, *>)
        assertEquals(true, (section as Map<*, *>)["enabled"])
    }

    @Test
    fun `S8 actorAuthenticationSection carries a raw scalar value unchanged`() {
        assertEquals("reject", parse("actor_authentication: reject\n").actorAuthenticationSection)
    }

    @Test
    fun `S8 actorAuthenticationSection is null when the section is absent`() {
        assertNull(parse(noSchemaSection).actorAuthenticationSection)
    }

    // ──────────────────────────────────────────────
    // S12 — YamlConfigDocumentParser
    // ──────────────────────────────────────────────

    @Test
    fun `S12 blank yaml parses to the EMPTY document with a null rawRoot`() {
        val outcome = YamlConfigDocumentParser.parse("", warnOnMissingSchemas = true)
        assertTrue(outcome is ConfigDocumentParser.Outcome.Parsed)
        val parsed = outcome as ConfigDocumentParser.Outcome.Parsed
        assertEquals(ConfigDocument.EMPTY, parsed.document)
        assertNull(parsed.rawRoot)
    }

    @Test
    fun `S12 a list root fails to parse`() {
        val outcome = YamlConfigDocumentParser.parse("- a\n- b\n", warnOnMissingSchemas = true)
        assertTrue(outcome is ConfigDocumentParser.Outcome.Failed)
    }

    @Test
    fun `S12 a disallowed yaml tag fails to parse`() {
        val outcome = YamlConfigDocumentParser.parse("value: !!javax.script.ScriptEngineManager {}", warnOnMissingSchemas = true)
        assertTrue(outcome is ConfigDocumentParser.Outcome.Failed)
    }

    @Test
    fun `S12 a valid document parses with the raw root map populated`() {
        val yaml = "work_item_schemas:\n  default:\n    notes: []\n"
        val outcome = YamlConfigDocumentParser.parse(yaml, warnOnMissingSchemas = true)
        assertTrue(outcome is ConfigDocumentParser.Outcome.Parsed)
        val parsed = outcome as ConfigDocumentParser.Outcome.Parsed
        assertNotNull(parsed.rawRoot)
        assertTrue(parsed.rawRoot!!.containsKey("work_item_schemas"))
        assertEquals(setOf("default"), parsed.document.workItemSchemas.keys)
    }

    @Test
    fun `S12 warnOnMissingSchemas true warns about the missing note_schemas key`() {
        val outcome = YamlConfigDocumentParser.parse("project:\n  name: x\n", warnOnMissingSchemas = true)
        val parsed = outcome as ConfigDocumentParser.Outcome.Parsed
        assertTrue(parsed.document.warnings.any { it.contains("note_schemas") }, "warnings: ${parsed.document.warnings}")
    }

    @Test
    fun `S12 warnOnMissingSchemas false does not warn about the missing note_schemas key`() {
        val outcome = YamlConfigDocumentParser.parse("project:\n  name: x\n", warnOnMissingSchemas = false)
        val parsed = outcome as ConfigDocumentParser.Outcome.Parsed
        assertTrue(parsed.document.warnings.none { it.contains("note_schemas") }, "warnings: ${parsed.document.warnings}")
    }

    // ──────────────────────────────────────────────
    // S14 — PER_ROOT_HONORED_SECTIONS
    // ──────────────────────────────────────────────

    @Test
    fun `S14 PER_ROOT_HONORED_SECTIONS is exactly the eight documented sections`() {
        assertEquals(
            setOf(
                "work_item_schemas",
                "note_schemas",
                "traits",
                "project",
                "note_limits",
                "status_labels",
                "resources",
                "schema_resolution",
            ),
            ConfigDocument.PER_ROOT_HONORED_SECTIONS,
        )
    }
}
