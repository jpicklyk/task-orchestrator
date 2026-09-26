package io.github.jpicklyk.mcptask.current.infrastructure.config

import io.github.jpicklyk.mcptask.current.application.config.SchemaResolutionMode
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independently authored against the frozen `task-scope`/`test-plan` notes on item `8879f554`
 * (scenarios S7, S11). Mirrors [io.github.jpicklyk.mcptask.current.infrastructure.config.ConfigDocumentParseTest]'s
 * SafeConstructor-based `YamlSchemaParser.parseRoot` harness (item `df7d579a`) for the parser-side
 * warning (build 8), and exercises [GlobalConfigFile] directly for the global-only isolated
 * warning (build 9).
 *
 * Oracle: task-scope build 8, warning text verbatim `Unrecognized schema_resolution value
 * '<raw>' (expected legacy, layered or isolated); treating as absent`; build 9, warning text
 * verbatim `schema_resolution: isolated has no effect in the global config (nothing to isolate
 * from); treating as layered`.
 */
class SchemaResolutionWarningsTest {
    // ──────────────────────────────────────────────
    // S11 — YamlSchemaParser: invalid schema_resolution values warn; valid ones never do
    // ──────────────────────────────────────────────

    private fun parse(
        yaml: String,
        warnOnMissingSchemas: Boolean = false,
    ): YamlSchemaParser.ParsedConfig {
        @Suppress("UNCHECKED_CAST")
        val root = Yaml(SafeConstructor(LoaderOptions())).load<Map<String, Any>>(yaml) ?: emptyMap()
        return YamlSchemaParser.parseRoot(root, warnOnMissingSchemas = warnOnMissingSchemas)
    }

    private val noSchemaSection = "work_item_schemas:\n  default:\n    notes: []\n"

    @Test
    fun `S11 - an explicit YAML null for schema_resolution yields null plus exactly one warning naming the key`() {
        val baseline = parse(noSchemaSection)
        val doc = parse("schema_resolution: null\n$noSchemaSection")

        assertNull(doc.schemaResolution)
        assertEquals(baseline.warnings.size + 1, doc.warnings.size, "warnings: ${doc.warnings}")
        assertEquals(1, doc.warnings.count { it.contains("schema_resolution") }, "warnings: ${doc.warnings}")
    }

    @Test
    fun `S11 - an explicit YAML null is reported with the literal 'null' raw value`() {
        val doc = parse("schema_resolution: null\n$noSchemaSection")

        assertTrue(
            doc.warnings.any { it.contains("schema_resolution") && it.contains("null") },
            "warnings: ${doc.warnings}"
        )
    }

    @Test
    fun `S11 - every valid schema_resolution value adds zero warnings beyond the no-key baseline`() {
        val baseline = parse(noSchemaSection)

        for (valid in listOf("legacy", "layered", "isolated")) {
            val doc = parse("schema_resolution: $valid\n$noSchemaSection")
            assertEquals(baseline.warnings.size, doc.warnings.size, "value=$valid warnings: ${doc.warnings}")
        }
    }

    // ──────────────────────────────────────────────
    // S7 — GlobalConfigFile: schema_resolution: isolated has no effect in the GLOBAL file
    // ──────────────────────────────────────────────

    private fun writeGlobalConfig(content: String): java.nio.file.Path {
        val tempDir = Files.createTempDirectory("schema-resolution-global-warning").toFile()
        tempDir.deleteOnExit()
        val configFile = File(tempDir, "config.yaml")
        configFile.writeText(content)
        return configFile.toPath()
    }

    @Test
    fun `S7 - global schema_resolution isolated adds exactly one 'isolated' warning and keeps ISOLATED`() {
        val layer = GlobalConfigFile(writeGlobalConfig("schema_resolution: isolated\n$noSchemaSection")).layer()!!

        assertEquals(SchemaResolutionMode.ISOLATED, layer.document.schemaResolution)
        assertEquals(1, layer.document.warnings.count { it.contains("isolated") }, "warnings: ${layer.document.warnings}")
    }

    @Test
    fun `S7 - the global isolated warning names the treat-as-layered fallback`() {
        val layer = GlobalConfigFile(writeGlobalConfig("schema_resolution: isolated\n$noSchemaSection")).layer()!!

        assertTrue(
            layer.document.warnings.any { it.contains("isolated") && it.contains("layered") },
            "warnings: ${layer.document.warnings}"
        )
    }

    @Test
    fun `S7 - a legacy or layered global schema_resolution adds no isolated-specific warning`() {
        for (valid in listOf("legacy", "layered")) {
            val layer = GlobalConfigFile(writeGlobalConfig("schema_resolution: $valid\n$noSchemaSection")).layer()!!
            assertTrue(layer.document.warnings.none { it.contains("isolated") }, "value=$valid warnings: ${layer.document.warnings}")
        }
    }
}
