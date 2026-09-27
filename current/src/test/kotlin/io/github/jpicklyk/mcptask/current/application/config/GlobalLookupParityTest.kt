package io.github.jpicklyk.mcptask.current.application.config

import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.infrastructure.config.GlobalConfigFile
import io.github.jpicklyk.mcptask.current.infrastructure.config.YamlStatusLabelService
import io.github.jpicklyk.mcptask.current.infrastructure.config.YamlWorkItemSchemaService
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals

/**
 * S7 — parity between [ServiceBackedGlobalLookup] (today's production path, service-backed) and
 * [LayerBackedGlobalLookup] (the layer-backed implementation C3 will switch production to) over
 * every [GlobalConfigLookup] method. Oracle: `test-plan` S7, "ServiceBacked(...) ==
 * LayerBackedGlobalLookup(...) on every method for S1/S3 yaml, absent file, empty file,
 * status_labels absent|null value|missing key." `[KD]` = the pre-existing
 * `YamlWorkItemSchemaService`/`NoOpStatusLabelService` getter contracts named in the declarations.
 *
 * NEW-SURFACE (test-plan S7): both lookup implementations are introduced by this feature, so no
 * source revert can yield behavioral red. Substitute per the frozen plan: "mutate LayerBacked to
 * drop default fold -> red" — an orchestrator-run mutation, not something this file can trigger
 * itself without touching `src/main`.
 */
class GlobalLookupParityTest {
    @TempDir
    lateinit var tempDir: Path

    private fun writeConfig(content: String): Path {
        val configFile = File(tempDir.toFile(), "config-${System.nanoTime()}.yaml")
        configFile.writeText(content)
        return configFile.toPath()
    }

    private fun absentConfigPath(): Path = tempDir.resolve("absent-${System.nanoTime()}/config.yaml")

    private fun serviceBacked(path: Path): GlobalConfigLookup =
        ServiceBackedGlobalLookup(YamlWorkItemSchemaService(path), YamlStatusLabelService(path))

    private fun layerBacked(path: Path): GlobalConfigLookup = LayerBackedGlobalLookup(GlobalConfigFile(path).layer())

    private val types = listOf("feature-task", "container", "unknown", "default")
    private val tagSets = listOf(emptyList(), listOf("feature-task"), listOf("unknown"), listOf("unknown", "container"))
    private val traitNames = listOf("trait-a", "trait-b", "unknown-trait")
    private val triggers = listOf("start", "complete", "cascade", "block", "cancel", "resume", "reopen", "zzz")

    private fun assertParity(
        path: Path,
        label: String
    ) {
        val service = serviceBacked(path)
        val layer = layerBacked(path)

        for (type in types) {
            assertEquals(service.schemaForType(type), layer.schemaForType(type), "$label - schemaForType($type)")
        }
        for (tags in tagSets) {
            assertEquals(service.notesForTags(tags), layer.notesForTags(tags), "$label - notesForTags($tags)")
        }
        for (trait in traitNames) {
            assertEquals(service.traitNotes(trait), layer.traitNotes(trait), "$label - traitNotes($trait)")
            assertEquals(service.traitResources(trait), layer.traitResources(trait), "$label - traitResources($trait)")
            assertEquals(service.traitDispatch(trait), layer.traitDispatch(trait), "$label - traitDispatch($trait)")
        }
        assertEquals(service.resourceRegistry(), layer.resourceRegistry(), "$label - resourceRegistry()")
        assertEquals(service.noteLimitsMode(), layer.noteLimitsMode(), "$label - noteLimitsMode()")
        assertEquals(service.fingerprint(), layer.fingerprint(), "$label - fingerprint()")
        assertEquals(service.traitNames().toSet(), layer.traitNames().toSet(), "$label - traitNames()")
        for (trigger in triggers) {
            assertEquals(service.statusLabel(trigger), layer.statusLabel(trigger), "$label - statusLabel($trigger)")
        }
    }

    @Test
    fun `S7 - parity over a full facet fixture (schemas, traits with resources+dispatch, note_limits, status_labels, registry)`() {
        val content =
            """
            work_item_schemas:
              feature-task:
                default_traits: [trait-a]
                notes:
                  - key: base-note
                    role: queue
                    required: true
                    description: "Base"
              container:
                notes:
                  - key: container-note
                    role: queue
                    required: false
                    description: "c"
              default:
                notes:
                  - key: default-note
                    role: queue
                    required: false
                    description: "d"
            traits:
              trait-a:
                resources: [trait-resource]
                dispatch:
                  work: { agent: trait-agent }
                notes:
                  - key: trait-a-note
                    role: queue
                    required: false
                    description: "ta"
            note_limits:
              mode: reject
            status_labels:
              start: "custom-started"
              complete: null
            resources:
              shared-key:
                description: "registry"
                defaultTtlSeconds: 99
            """.trimIndent()
        val path = writeConfig(content)

        assertParity(path, "full facet fixture")

        // Assert the merge/fold facets actually fired identically on BOTH sides, not just that
        // both sides happen to return the same (possibly empty) value.
        val service = serviceBacked(path)
        assertEquals(listOf("base-note"), service.schemaForType("feature-task")?.notes?.map { it.key })
        assertEquals(listOf("default-note"), service.schemaForType("unknown")?.notes?.map { it.key }, "global default fold")
        assertEquals("reject", service.noteLimitsMode())
        assertEquals("custom-started", service.statusLabel("start"))
        assertEquals(null, service.statusLabel("complete"), "explicit null is a present key, not absence")
        assertEquals(null, service.statusLabel("cascade"), "a trigger absent from the map resolves to null (no NoOp default fold here)")
        assertEquals(setOf(Role.WORK), service.traitDispatch("trait-a").keys)
    }

    @Test
    fun `S7 - parity when the global config file is absent`() {
        val path = absentConfigPath()
        assertParity(path, "absent config file")

        // NoOpStatusLabelService's hardcoded defaults must show up identically on both sides.
        val service = serviceBacked(path)
        assertEquals("in-progress", service.statusLabel("start"))
        assertEquals("done", service.statusLabel("complete"))
    }

    @Test
    fun `S7 - parity over an empty config file`() {
        val path = writeConfig("")
        assertParity(path, "empty config file")

        val service = serviceBacked(path)
        assertEquals("warn", service.noteLimitsMode(), "absent note_limits section defaults to warn on both sides")
    }

    @Test
    fun `S7 - parity when status_labels is absent, present with an explicit null value, and missing a key`() {
        val absentSection = writeConfig("work_item_schemas:\n  default:\n    notes: []\n")
        assertParity(absentSection, "status_labels absent")
        assertEquals("in-progress", serviceBacked(absentSection).statusLabel("start"), "NoOp default fold when the section is absent")

        val explicitNull = writeConfig("status_labels:\n  start: null\n")
        assertParity(explicitNull, "status_labels.start explicit null")
        assertEquals(null, serviceBacked(explicitNull).statusLabel("start"), "an explicit null is a present key on both sides")
        assertEquals(
            null,
            serviceBacked(explicitNull).statusLabel("complete"),
            "a missing key resolves to null, no NoOp fold once the section exists"
        )

        val missingKey = writeConfig("status_labels:\n  start: \"root-started\"\n")
        assertParity(missingKey, "status_labels present, complete key missing")
        assertEquals("root-started", serviceBacked(missingKey).statusLabel("start"))
        assertEquals(null, serviceBacked(missingKey).statusLabel("complete"))
    }
}
