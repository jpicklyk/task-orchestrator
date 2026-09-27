package io.github.jpicklyk.mcptask.current.application.config

import io.github.jpicklyk.mcptask.current.application.service.NoOpStatusLabelService
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.infrastructure.config.GlobalConfigFile
import io.github.jpicklyk.mcptask.current.infrastructure.config.YamlWorkItemSchemaService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals

/**
 * Independently authored against the frozen `task-scope`/`test-plan` notes on item `f2c50e6d` —
 * scenario S9. Oracle: the declarations' stated outcome for `EffectiveConfigResolver.availableTraits`
 * — `(perRootTraits + global.traitNames()).distinct()` — combined with the declared
 * `LayerBackedGlobalLookup.traitNames()` = `document.traits.keys.toList()` (YAML document key
 * order) and `ServiceBackedGlobalLookup.traitNames()` = `noteSchemaService.getAvailableTraits()`.
 * `distinct()` keeps the FIRST occurrence, so a trait present in both layers must appear once, at
 * its PER-ROOT position. This is an ORDER-SENSITIVE assertion (`List` equality), per the dispatch
 * contract's explicit instruction that S9 must never use `toSet()`.
 *
 * EXISTING-SURFACE per the test-plan label: `availableTraits`/`traitNames()` already exist
 * (introduced by C2). Substitute per the frozen plan: "sort LayerBacked.traitNames -> red" — an
 * orchestrator-run mutation, since sorting the global trait list would move "alpha"/"mid" ahead of
 * where this fixture's assertions require them.
 */
class AvailableTraitsOrderTest {
    @TempDir
    lateinit var tempDir: Path

    private fun writeGlobalYaml(): Path {
        val content =
            """
            traits:
              zeta:
                notes: []
              alpha:
                notes: []
              mid:
                notes: []
            """.trimIndent()
        val file = File(tempDir.toFile(), "config.yaml")
        file.writeText(content)
        return file.toPath()
    }

    private val perRootDoc =
        ConfigDocument(
            workItemSchemas = emptyMap(),
            traits =
                linkedMapOf(
                    "pr-b" to emptyList<NoteSchemaEntry>(),
                    "alpha" to emptyList(),
                    "pr-a" to emptyList(),
                ),
        )

    private fun perRootSource(): PerRootConfigSource =
        object : PerRootConfigSource {
            override suspend fun layer(rootId: UUID): ConfigLayer? = ConfigLayer(perRootDoc, "pr-fp", ConfigSource.PER_ROOT)
        }

    private val expectedOrder = listOf("pr-b", "alpha", "pr-a", "zeta", "mid")

    @Test
    fun `S9 - availableTraits orders per-root keys first then global, deduped, over ServiceBackedGlobalLookup`(): Unit =
        runBlocking {
            val path = writeGlobalYaml()
            val rootId = UUID.randomUUID()
            val global = ServiceBackedGlobalLookup(YamlWorkItemSchemaService(path), NoOpStatusLabelService)
            val resolver = EffectiveConfigResolver(global, perRootSource())

            assertEquals(listOf("zeta", "alpha", "mid"), global.traitNames(), "sanity: global YAML document key order")
            assertEquals(expectedOrder, resolver.availableTraits(listOf(rootId)))
        }

    @Test
    fun `S9 - the same order holds over LayerBackedGlobalLookup(GlobalConfigFile)`(): Unit =
        runBlocking {
            val path = writeGlobalYaml()
            val rootId = UUID.randomUUID()
            val global = LayerBackedGlobalLookup(GlobalConfigFile(path).layer())
            val resolver = EffectiveConfigResolver(global, perRootSource())

            assertEquals(listOf("zeta", "alpha", "mid"), global.traitNames(), "sanity: global YAML document key order")
            assertEquals(expectedOrder, resolver.availableTraits(listOf(rootId)))
        }
}
