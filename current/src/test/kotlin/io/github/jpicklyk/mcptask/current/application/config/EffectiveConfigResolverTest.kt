package io.github.jpicklyk.mcptask.current.application.config

import io.github.jpicklyk.mcptask.current.domain.model.DispatchProfile
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.ResourceDefinition
import io.github.jpicklyk.mcptask.current.domain.model.ResourceRequirement
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.model.WorkItemSchema
import io.github.jpicklyk.mcptask.current.infrastructure.config.YamlStatusLabelService
import io.github.jpicklyk.mcptask.current.infrastructure.config.YamlWorkItemSchemaService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * S6 — reproduces the LEGACY precedence facets already characterized by S1-S3 of
 * `LegacyPrecedenceCharacterizationTest`, but calling [EffectiveConfigResolver] directly with a
 * [ServiceBackedGlobalLookup] and a hand-written fake [PerRootConfigSource] — no
 * `ToolExecutionContext` involved anywhere in this file. Oracle: item `ce346f52`'s frozen
 * `task-scope` quirks Q1/Q3/Q4 (type/tag precedence) and Q6-Q12 (facet merge rules), restated by
 * `test-plan` S6 as "reproduces S1-S3. No revert red; substitute: orchestrator mutation (Q1 order
 * swap; Q8 early return removed) must redden S1/S4/S6."
 *
 * NEW-SURFACE (test-plan S6): [EffectiveConfigResolver] and [ServiceBackedGlobalLookup] are
 * introduced by this feature, so no source revert can yield behavioral red here — the red-proof
 * for this file is the orchestrator-run mutation named above, applied to
 * `EffectiveConfigResolver`/`LayeredConfig` and re-run against these same assertions.
 */
class EffectiveConfigResolverTest {
    private fun writeGlobalYaml(content: String): Path {
        val path = Files.createTempFile("effective-config-resolver-global", ".yaml")
        Files.writeString(path, content)
        return path
    }

    // Deliberately no "default" schema, so a total type+tag miss can be observed as a clean null
    // (S2's "resolveTypeSchema returns null on a total type miss" mirror).
    private val globalNoDefaultYaml =
        """
        work_item_schemas:
          feature-task:
            notes:
              - key: base-note-global
                role: queue
                required: false
                description: "base-global"
          container:
            notes:
              - key: container-note
                role: queue
                required: false
                description: "c"
        traits:
          trait-a:
            resources: [global-resource]
            dispatch:
              work: { agent: global-agent }
            notes:
              - key: trait-a-global-note
                role: queue
                required: false
                description: "pg"
          trait-b:
            notes:
              - key: trait-b-note
                role: queue
                required: false
                description: "b"
        note_limits:
          mode: warn
        status_labels:
          start: "global-started"
          complete: "global-done"
          cascade: "global-cascaded"
        resources:
          shared-key:
            description: "global version"
            defaultTtlSeconds: 222
        """.trimIndent()

    private lateinit var globalLookup: GlobalConfigLookup

    @BeforeEach
    fun setUp() {
        val path = writeGlobalYaml(globalNoDefaultYaml)
        globalLookup =
            ServiceBackedGlobalLookup(
                YamlWorkItemSchemaService(path),
                YamlStatusLabelService(path)
            )
    }

    private class FakePerRootConfigSource(
        private val layers: Map<UUID, ConfigLayer?>
    ) : PerRootConfigSource {
        override suspend fun layer(rootId: UUID): ConfigLayer? = layers[rootId]
    }

    // Trait notes: [defaultTraits then item traits, distinct]; per-root trait list replaces
    // global wholesale (Q6). Resources: per-root trait entry wins the union (Q7). Dispatch:
    // per-root role map replaces global wholesale (Q8). Registry: global wins collision (Q9).
    // note_limits: per-root explicit wins (Q10). Status labels: per-root explicit null wins,
    // absent trigger falls through (Q11). availableTraits: per-root keys then global (Q12).
    private val perRootDoc =
        ConfigDocument(
            workItemSchemas =
                mapOf(
                    "feature-task" to
                        WorkItemSchema(
                            type = "feature-task",
                            defaultTraits = listOf("trait-a"),
                            notes = listOf(NoteSchemaEntry(key = "base-note", role = Role.QUEUE))
                        ),
                    "bug-fix" to
                        WorkItemSchema(
                            type = "bug-fix",
                            notes = listOf(NoteSchemaEntry(key = "bug-fix-note", role = Role.QUEUE))
                        )
                ),
            traits = mapOf("trait-a" to listOf(NoteSchemaEntry(key = "trait-a-per-root-note", role = Role.QUEUE))),
            traitResources = mapOf("trait-a" to listOf(ResourceRequirement(key = "per-root-resource"))),
            traitDispatch = mapOf("trait-a" to mapOf(Role.WORK to DispatchProfile(agent = "per-root-agent"))),
            resourceRegistry =
                mapOf(
                    "shared-key" to
                        ResourceDefinition(key = "shared-key", description = "per-root version", defaultTtlSeconds = 111)
                ),
            noteLimitsMode = "reject",
            statusLabels = mapOf("start" to "root-started", "complete" to null)
        )

    private fun resolverWithRoot(rootId: UUID): EffectiveConfigResolver =
        EffectiveConfigResolver(
            globalLookup,
            FakePerRootConfigSource(mapOf(rootId to ConfigLayer(perRootDoc, "pr-fp", ConfigSource.PER_ROOT)))
        )

    private fun makeItem(
        type: String?,
        tags: List<String>,
        rootId: UUID?
    ): WorkItem =
        WorkItem(
            id = UUID.randomUUID(),
            title = "S6 item",
            type = type,
            tags = if (tags.isEmpty()) null else tags.joinToString(","),
            rootId = rootId,
            depth = 0
        )

    @Test
    fun `S6 - resolveSchemaWithSource picks the per-root exact type match over global (Q1), notes trait-merged per resolveSchema`(): Unit =
        runBlocking {
            val rootId = UUID.randomUUID()
            val resolver = resolverWithRoot(rootId)

            val item = makeItem(type = "feature-task", tags = emptyList(), rootId = rootId)
            val result = resolver.resolveSchemaWithSource(item)!!

            // Per the pre-existing resolveSchemaWithSource KDoc: "Same resolution as [resolveSchema]
            // ... Trait notes are merged in identically to [resolveSchema] ... [ResolvedSchema.source]
            // and [ResolvedSchema.fingerprint] describe the base schema's provenance only." The item's
            // resolved type (feature-task) carries defaultTraits=[trait-a], so the per-root trait-a
            // note is merged in (Q6) even though source/fingerprint stay base-only (PER_ROOT / the
            // per-root row's fingerprint).
            assertEquals(ConfigSource.PER_ROOT, result.source)
            assertEquals(listOf("base-note", "trait-a-per-root-note"), result.schema.notes.map { it.key })
            assertEquals("pr-fp", result.fingerprint)
        }

    @Test
    fun `S6 - resolveSchemaWithSource falls through to the global exact type when per-root misses (Q1)`(): Unit =
        runBlocking {
            val rootId = UUID.randomUUID()
            val resolver = resolverWithRoot(rootId)

            val item = makeItem(type = "container", tags = emptyList(), rootId = rootId)
            val result = resolver.resolveSchemaWithSource(item)!!

            assertEquals(ConfigSource.GLOBAL, result.source)
            assertEquals(listOf("container-note"), result.schema.notes.map { it.key })
        }

    @Test
    fun `S6 - resolveSchemaWithSource falls through to the global tag probe when type and per-root both miss (Q3, Q4)`(): Unit =
        runBlocking {
            val rootId = UUID.randomUUID()
            val resolver = resolverWithRoot(rootId)

            val item = makeItem(type = null, tags = listOf("container"), rootId = rootId)
            val result = resolver.resolveSchemaWithSource(item)!!

            assertEquals(ConfigSource.GLOBAL, result.source)
            assertEquals(listOf("container-note"), result.schema.notes.map { it.key })
        }

    @Test
    fun `S6 - resolveTypeSchema does not fall back to tags, unlike resolveSchemaWithSource (S2 mirror)`(): Unit =
        runBlocking {
            val rootId = UUID.randomUUID()
            val resolver = resolverWithRoot(rootId)

            val typeOnly = resolver.resolveTypeSchema("unknown", rootId)
            assertNull(typeOnly, "no default schema anywhere in this fixture, and resolveTypeSchema must not consult tags")

            val itemWithTag = makeItem(type = "unknown", tags = listOf("container"), rootId = rootId)
            val viaFull = resolver.resolveSchema(itemWithTag)
            assertEquals(
                listOf("container-note"),
                viaFull!!.notes.map { it.key },
                "sanity: the tag path resolveTypeSchema skips does resolve via resolveSchema"
            )
        }

    @Test
    fun `S6 - resolveSchema merges the per-root trait wholesale over the global one, base key first (Q6)`(): Unit =
        runBlocking {
            val rootId = UUID.randomUUID()
            val resolver = resolverWithRoot(rootId)

            val item = makeItem(type = "feature-task", tags = emptyList(), rootId = rootId)
            val resolved = resolver.resolveSchema(item)!!

            assertEquals(listOf("base-note", "trait-a-per-root-note"), resolved.notes.map { it.key })
        }

    @Test
    fun `S6 - resolveResourceRequirements takes the per-root trait entry over global (Q7)`(): Unit =
        runBlocking {
            val rootId = UUID.randomUUID()
            val resolver = resolverWithRoot(rootId)

            val item = makeItem(type = "feature-task", tags = emptyList(), rootId = rootId)
            val reqs = resolver.resolveResourceRequirements(item)

            assertEquals(listOf("per-root-resource"), reqs.map { it.key })
        }

    @Test
    fun `S6 - resolveDispatchProfile takes the per-root trait entry over global (Q8)`(): Unit =
        runBlocking {
            val rootId = UUID.randomUUID()
            val resolver = resolverWithRoot(rootId)

            val item = makeItem(type = "feature-task", tags = emptyList(), rootId = rootId)
            val schema = resolver.resolveSchema(item)
            val profile = resolver.resolveDispatchProfile(item, Role.WORK, schema)

            assertEquals(DispatchProfile(agent = "per-root-agent"), profile)
        }

    @Test
    fun `S6 - resolveResourceRegistry merges non-colliding keys, global wins on collision (Q9)`(): Unit =
        runBlocking {
            val rootId = UUID.randomUUID()
            val resolver = resolverWithRoot(rootId)

            val registry = resolver.resolveResourceRegistry(rootId)

            assertEquals(1, registry.size)
            assertEquals("global version", registry["shared-key"]?.description, "global wins on the colliding key")
            assertEquals(222, registry["shared-key"]?.defaultTtlSeconds)
        }

    @Test
    fun `S6 - resolveNoteLimitsMode prefers an explicit per-root value over global (Q10)`(): Unit =
        runBlocking {
            val rootId = UUID.randomUUID()
            val resolver = resolverWithRoot(rootId)

            assertEquals("reject", resolver.resolveNoteLimitsMode(rootId))
        }

    @Test
    fun `S6 - resolveStatusLabels honors an explicit per-root null and falls through for a missing trigger (Q11)`(): Unit =
        runBlocking {
            val rootId = UUID.randomUUID()
            val resolver = resolverWithRoot(rootId)

            val labels = resolver.resolveStatusLabels(listOf("start", "complete", "cascade"), rootId)

            assertEquals("root-started", labels["start"])
            assertNull(labels["complete"], "an explicit per-root null must win over the global label, not fall through")
            assertEquals("global-cascaded", labels["cascade"], "a trigger absent from the per-root map must fall through to global")
        }

    @Test
    fun `S6 - availableTraits unions per-root keys then global, distinct (Q12)`(): Unit =
        runBlocking {
            val rootId = UUID.randomUUID()
            val resolver = resolverWithRoot(rootId)

            val traits = resolver.availableTraits(listOf(rootId))

            assertEquals(setOf("trait-a", "trait-b"), traits.toSet())
        }
}
