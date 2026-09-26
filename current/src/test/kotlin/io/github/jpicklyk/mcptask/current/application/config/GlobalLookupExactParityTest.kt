package io.github.jpicklyk.mcptask.current.application.config

import io.github.jpicklyk.mcptask.current.infrastructure.config.GlobalConfigFile
import io.github.jpicklyk.mcptask.current.infrastructure.config.YamlStatusLabelService
import io.github.jpicklyk.mcptask.current.infrastructure.config.YamlWorkItemSchemaService
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Independently authored against the frozen `test-plan` note on item `8879f554` (scenario S9).
 * Oracle: `NoteSchemaService.kt` KDoc (task-scope build 6): "exact match only -- no `default`
 * fallback"; `ServiceBackedGlobalLookup` task-scope build 2: "the fold moves HERE from the Yaml
 * service". Mirrors [GlobalLookupParityTest]'s harness style (item `ce346f52`).
 */
class GlobalLookupExactParityTest {
    @TempDir
    lateinit var tempDir: Path

    private fun writeConfig(content: String): Path {
        val configFile = File(tempDir.toFile(), "config-${System.nanoTime()}.yaml")
        configFile.writeText(content)
        return configFile.toPath()
    }

    private val yamlWithDefault =
        """
        work_item_schemas:
          bug:
            notes:
              - key: bug-note
                role: queue
                required: false
                description: "b"
          default:
            notes:
              - key: global-default-note
                role: queue
                required: false
                description: "gd"
        """.trimIndent()

    // ──────────────────────────────────────────────
    // S9 — YamlWorkItemSchemaService is now exact (the default fold moved to ServiceBackedGlobalLookup)
    // ──────────────────────────────────────────────

    @Test
    fun `S9 - YamlWorkItemSchemaService is exact - no default fold on getSchemaForType or getSchemaForTags`() {
        val service = YamlWorkItemSchemaService(writeConfig(yamlWithDefault))

        assertNull(service.getSchemaForType("unknown"))
        assertNull(service.getSchemaForTags(listOf("unknown")))
        assertNull(service.getSchemaForTags(emptyList()))
    }

    @Test
    fun `S9 - ServiceBackedGlobalLookup restores the default fold on top of the now-exact Yaml service`() {
        val path = writeConfig(yamlWithDefault)
        val lookup = ServiceBackedGlobalLookup(YamlWorkItemSchemaService(path), YamlStatusLabelService(path))

        assertEquals("default", lookup.schemaForType("unknown")?.type)
        assertEquals(listOf("global-default-note"), lookup.notesForTags(listOf("unknown"))?.map { it.key })
    }

    // ──────────────────────────────────────────────
    // S9 — ServiceBacked vs LayerBacked parity on the three NEW exact accessors
    // ──────────────────────────────────────────────

    @Test
    fun `S9 - exactSchema and hasExactTagSchema agree between ServiceBacked and LayerBacked over the same YAML, for every schema_resolution override`() {
        val path = writeConfig(yamlWithDefault)

        for (mode in listOf(null, SchemaResolutionMode.LEGACY, SchemaResolutionMode.LAYERED, SchemaResolutionMode.ISOLATED)) {
            val serviceBacked = ServiceBackedGlobalLookup(YamlWorkItemSchemaService(path), YamlStatusLabelService(path), mode)
            val layerBacked = LayerBackedGlobalLookup(GlobalConfigFile(path).layer())

            for (key in listOf("bug", "unknown", "default")) {
                assertEquals(serviceBacked.exactSchema(key), layerBacked.exactSchema(key), "exactSchema($key), mode=$mode")
            }
            for (tag in listOf("bug", "unknown")) {
                assertEquals(
                    serviceBacked.hasExactTagSchema(tag),
                    layerBacked.hasExactTagSchema(tag),
                    "hasExactTagSchema($tag), mode=$mode"
                )
            }
        }
    }

    @Test
    fun `S9 - schemaResolution() parity between ServiceBacked (default null override) and LayerBacked when the YAML carries no key`() {
        val path = writeConfig(yamlWithDefault)
        val serviceBacked = ServiceBackedGlobalLookup(YamlWorkItemSchemaService(path), YamlStatusLabelService(path))
        val layerBacked = LayerBackedGlobalLookup(GlobalConfigFile(path).layer())

        assertEquals(serviceBacked.schemaResolution(), layerBacked.schemaResolution())
        assertNull(layerBacked.schemaResolution(), "the YAML carries no schema_resolution key")
    }

    @Test
    fun `S9 - LayerBacked schemaResolution() reflects a YAML-declared value that ServiceBacked's default construction cannot see`() {
        val path = writeConfig("schema_resolution: layered\n$yamlWithDefault")
        val layerBacked = LayerBackedGlobalLookup(GlobalConfigFile(path).layer())

        assertEquals(SchemaResolutionMode.LAYERED, layerBacked.schemaResolution())
    }
}
