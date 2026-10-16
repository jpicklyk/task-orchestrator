package io.github.jpicklyk.mcptask.current.application.api

import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.config.ConfigDocument
import io.github.jpicklyk.mcptask.current.application.config.ConfigLayer
import io.github.jpicklyk.mcptask.current.application.config.ConfigSource
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.domain.model.*
import io.github.jpicklyk.mcptask.current.infrastructure.config.PerRootConfigService
import io.github.jpicklyk.mcptask.current.test.MutableServerConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [ToolExecutionContext.resolveSchema] and [ToolExecutionContext.resolveHasReviewPhase] over a real
 * server config document ([MutableServerConfig]) and, where relevant, a per-root layer.
 *
 * Verifies the one precedence rule: per-root exact type -> server exact type -> per-root `"default"` -> server
 * `"default"`; tags are never consulted; a null type uses only the `"default"` steps. Trait notes merge in item-first
 * order with `required` true when any declaration marks it.
 */
class ToolExecutionContextResolveSchemaTest {
    private lateinit var server: MutableServerConfig
    private lateinit var context: ToolExecutionContext

    @BeforeEach
    fun setUp() {
        server = MutableServerConfig()
        val repoProvider = mockk<RepositoryProvider>(relaxed = true)
        context = ToolExecutionContext(repoProvider, configResolver = server.resolver())
    }

    // ──────────────────────────────────────────────
    // Helper builders
    // ──────────────────────────────────────────────

    private fun makeItem(
        type: String? = null,
        tags: String? = null,
        rootId: UUID? = null
    ): WorkItem =
        WorkItem(
            id = UUID.randomUUID(),
            title = "Test Item",
            type = type,
            tags = tags,
            rootId = rootId,
            // need parentId=null and depth=0 to be valid
            depth = 0
        )

    private fun reviewEntry(): NoteSchemaEntry = NoteSchemaEntry(key = "review-note", role = Role.REVIEW, required = false)

    private fun workEntry(): NoteSchemaEntry = NoteSchemaEntry(key = "work-note", role = Role.WORK, required = false)

    private fun schemaWithReview(type: String): WorkItemSchema = WorkItemSchema(type = type, notes = listOf(reviewEntry()))

    private fun schemaWithoutReview(type: String): WorkItemSchema = WorkItemSchema(type = type, notes = listOf(workEntry()))

    private fun perRootWith(
        rootId: UUID,
        document: ConfigDocument?
    ): PerRootConfigService {
        val perRoot = mockk<PerRootConfigService>()
        coEvery { perRoot.layer(rootId) } returns
            document?.let { ConfigLayer(document = it, fingerprint = "fp", source = ConfigSource.PER_ROOT) }
        return perRoot
    }

    private fun contextWith(perRoot: PerRootConfigService?): ToolExecutionContext =
        ToolExecutionContext(
            mockk<RepositoryProvider>(relaxed = true),
            perRootConfigService = perRoot,
            configResolver = server.resolver(perRoot)
        )

    private fun schemasDoc(vararg schemas: Pair<String, WorkItemSchema>): ConfigDocument =
        ConfigDocument(workItemSchemas = mapOf(*schemas), traits = emptyMap())

    // ──────────────────────────────────────────────
    // Type lookup; tags are never consulted
    // ──────────────────────────────────────────────

    @Test
    fun `resolveSchema returns type-based schema when item has type and schema exists`(): Unit =
        runBlocking {
            val expected = schemaWithReview("feature-task")
            server.schemas["feature-task"] = expected
            server.schemas["some-tag"] = schemaWithoutReview("some-tag")

            assertEquals(expected, context.resolveSchema(makeItem(type = "feature-task", tags = "some-tag")))
        }

    @Test
    fun `resolveSchema ignores a tag naming a schema when the type has none`(): Unit =
        runBlocking {
            server.schemas["feature-task"] = schemaWithReview("feature-task")

            assertNull(context.resolveSchema(makeItem(type = "unknown-type", tags = "feature-task")), "tags never select a schema")
        }

    @Test
    fun `resolveSchema with no type uses only the default chain, never the tags`(): Unit =
        runBlocking {
            server.schemas["feature-task"] = schemaWithReview("feature-task")

            assertNull(context.resolveSchema(makeItem(type = null, tags = "feature-task")))
        }

    @Test
    fun `resolveSchema returns null for schema-free item with no type and no tags`(): Unit =
        runBlocking {
            assertNull(context.resolveSchema(makeItem(type = null, tags = null)))
        }

    @Test
    fun `resolveSchema returns the default schema when the item has no type`(): Unit =
        runBlocking {
            val defaultSchema = WorkItemSchema(type = "default", notes = listOf(workEntry()))
            server.schemas["default"] = defaultSchema

            val result = context.resolveSchema(makeItem(type = null, tags = "unmatched-tag"))
            assertNotNull(result)
            assertEquals("default", result.type)
            assertEquals(defaultSchema.notes, result.notes)
        }

    @Test
    fun `resolveSchema falls back to the default schema when the type has none`(): Unit =
        runBlocking {
            val defaultSchema = WorkItemSchema(type = "default", notes = listOf(workEntry()))
            server.schemas["default"] = defaultSchema

            assertEquals(defaultSchema, context.resolveSchema(makeItem(type = "unknown-type")))
        }

    // ──────────────────────────────────────────────
    // resolveHasReviewPhase
    // ──────────────────────────────────────────────

    @Test
    fun `resolveHasReviewPhase returns true when schema has review phase`(): Unit =
        runBlocking {
            server.schemas["feature-task"] = schemaWithReview("feature-task")
            assertTrue(context.resolveHasReviewPhase(makeItem(type = "feature-task")))
        }

    @Test
    fun `resolveHasReviewPhase returns false when schema has no review phase`(): Unit =
        runBlocking {
            server.schemas["simple-task"] = schemaWithoutReview("simple-task")
            assertFalse(context.resolveHasReviewPhase(makeItem(type = "simple-task")))
        }

    @Test
    fun `resolveHasReviewPhase returns false when no schema matches`(): Unit =
        runBlocking {
            assertFalse(context.resolveHasReviewPhase(makeItem(type = null, tags = null)))
        }

    // ──────────────────────────────────────────────
    // Trait merging
    // ──────────────────────────────────────────────

    private fun makeItemWithProperties(
        type: String? = null,
        traits: List<String> = emptyList()
    ): WorkItem =
        WorkItem(
            id = UUID.randomUUID(),
            title = "Test Item",
            type = type,
            traits = traits,
            depth = 0
        )

    private fun traitEntry(
        key: String,
        role: Role = Role.REVIEW
    ): NoteSchemaEntry = NoteSchemaEntry(key = key, role = role, required = true, description = "Trait note: $key")

    @Test
    fun `resolveSchema merges trait notes after base schema notes`(): Unit =
        runBlocking {
            server.schemas["feature-task"] =
                WorkItemSchema(type = "feature-task", notes = listOf(workEntry()), defaultTraits = listOf("needs-security-review"))
            server.traits["needs-security-review"] = listOf(traitEntry("security-assessment"))

            val result = context.resolveSchema(makeItem(type = "feature-task"))!!

            assertEquals(2, result.notes.size)
            assertEquals("work-note", result.notes[0].key)
            assertEquals("security-assessment", result.notes[1].key)
        }

    @Test
    fun `resolveSchema base content wins over a trait key with the same name and required is ORed`(): Unit =
        runBlocking {
            server.schemas["feature-task"] =
                WorkItemSchema(
                    type = "feature-task",
                    notes = listOf(NoteSchemaEntry(key = "shared-key", role = Role.WORK, required = false, description = "base")),
                    defaultTraits = listOf("my-trait")
                )
            server.traits["my-trait"] =
                listOf(NoteSchemaEntry(key = "shared-key", role = Role.REVIEW, required = true, description = "trait"))

            val result = context.resolveSchema(makeItem(type = "feature-task"))!!

            assertEquals(1, result.notes.size)
            assertEquals("base", result.notes[0].description)
            assertEquals(Role.WORK, result.notes[0].role)
            assertTrue(result.notes[0].required, "required is true when any declaration marks it")
        }

    @Test
    fun `resolveSchema skips unknown traits with no error`(): Unit =
        runBlocking {
            server.schemas["feature-task"] =
                WorkItemSchema(type = "feature-task", notes = listOf(workEntry()), defaultTraits = listOf("nonexistent-trait"))

            val result = context.resolveSchema(makeItem(type = "feature-task"))!!

            assertEquals(1, result.notes.size)
            assertEquals("work-note", result.notes[0].key)
        }

    @Test
    fun `resolveSchema merges per-item traits from properties JSON`(): Unit =
        runBlocking {
            server.schemas["feature-task"] = WorkItemSchema(type = "feature-task", notes = listOf(workEntry()))
            server.traits["needs-perf-review"] = listOf(traitEntry("performance-baseline", Role.QUEUE))

            val item = makeItemWithProperties(type = "feature-task", traits = listOf("needs-perf-review"))
            val result = context.resolveSchema(item)!!

            assertEquals(2, result.notes.size)
            assertEquals("performance-baseline", result.notes[1].key)
        }

    @Test
    fun `resolveSchema unions item and default traits item-first with deduplication`(): Unit =
        runBlocking {
            server.schemas["feature-task"] =
                WorkItemSchema(type = "feature-task", notes = listOf(workEntry()), defaultTraits = listOf("trait-a"))
            server.traits["trait-a"] = listOf(traitEntry("note-a"))
            server.traits["trait-b"] = listOf(traitEntry("note-b"))

            val item = makeItemWithProperties(type = "feature-task", traits = listOf("trait-b", "trait-a"))
            val result = context.resolveSchema(item)!!

            assertEquals(listOf("work-note", "note-b", "note-a"), result.notes.map { it.key })
        }

    @Test
    fun `resolveSchema returns base schema unchanged when no traits`(): Unit =
        runBlocking {
            val baseSchema = WorkItemSchema(type = "feature-task", notes = listOf(workEntry()))
            server.schemas["feature-task"] = baseSchema

            assertEquals(baseSchema, context.resolveSchema(makeItem(type = "feature-task")))
        }

    @Test
    fun `resolveHasReviewPhase returns true when trait adds review note`(): Unit =
        runBlocking {
            server.schemas["feature-task"] =
                WorkItemSchema(type = "feature-task", notes = listOf(workEntry()), defaultTraits = listOf("needs-review-trait"))
            server.traits["needs-review-trait"] = listOf(traitEntry("review-from-trait", Role.REVIEW))

            assertTrue(context.resolveHasReviewPhase(makeItem(type = "feature-task")))
        }

    @Test
    fun `resolveSchema first trait wins for duplicate trait note keys`(): Unit =
        runBlocking {
            server.schemas["feature-task"] =
                WorkItemSchema(type = "feature-task", notes = listOf(workEntry()), defaultTraits = listOf("trait-a", "trait-b"))
            server.traits["trait-a"] =
                listOf(NoteSchemaEntry(key = "duplicate-key", role = Role.REVIEW, required = true, description = "from trait-a"))
            server.traits["trait-b"] =
                listOf(NoteSchemaEntry(key = "duplicate-key", role = Role.QUEUE, required = false, description = "from trait-b"))

            val result = context.resolveSchema(makeItem(type = "feature-task"))!!

            assertEquals(2, result.notes.size)
            assertEquals("from trait-a", result.notes[1].description)
        }

    @Test
    fun `resolveSchema preserves lifecycleMode and defaultTraits on the default fallback`(): Unit =
        runBlocking {
            val defaultNotes = listOf(workEntry())
            server.schemas["default"] =
                WorkItemSchema(
                    type = "default",
                    lifecycleMode = LifecycleMode.MANUAL,
                    notes = defaultNotes,
                    defaultTraits = listOf("some-trait")
                )

            val result = context.resolveSchema(makeItem(type = null, tags = "bug,tools-layer"))
            assertNotNull(result, "Should return a schema (default fallback)")
            assertEquals("default", result.type)
            assertEquals(LifecycleMode.MANUAL, result.lifecycleMode)
            assertEquals(listOf("some-trait"), result.defaultTraits)
            assertEquals(defaultNotes, result.notes)
        }

    // ──────────────────────────────────────────────
    // Per-root layering
    // ──────────────────────────────────────────────

    @Test
    fun `resolveSchema per-root type match overrides server type`(): Unit =
        runBlocking {
            val rootId = UUID.randomUUID()
            val perRootSchema = schemaWithReview("feature-task")
            server.schemas["feature-task"] = schemaWithoutReview("feature-task")
            val ctx = contextWith(perRootWith(rootId, schemasDoc("feature-task" to perRootSchema)))

            assertEquals(perRootSchema, ctx.resolveSchema(makeItem(type = "feature-task", rootId = rootId)))
        }

    @Test
    fun `resolveSchema per-root type miss falls through to server type`(): Unit =
        runBlocking {
            val rootId = UUID.randomUUID()
            val perRoot = perRootWith(rootId, schemasDoc())
            val globalSchema = schemaWithReview("feature-task")
            server.schemas["feature-task"] = globalSchema

            assertEquals(globalSchema, contextWith(perRoot).resolveSchema(makeItem(type = "feature-task", rootId = rootId)))
            // Exactly ONE per-root fetch for the whole resolveSchema call.
            coVerify(exactly = 1) { perRoot.layer(rootId) }
        }

    @Test
    fun `resolveSchema server exact type beats a per-root default`(): Unit =
        runBlocking {
            val rootId = UUID.randomUUID()
            val perRootDefault = WorkItemSchema(type = "default", notes = listOf(workEntry()))
            val globalSchema = schemaWithReview("feature-task")
            server.schemas["feature-task"] = globalSchema
            val ctx = contextWith(perRootWith(rootId, schemasDoc("default" to perRootDefault)))

            assertEquals(globalSchema, ctx.resolveSchema(makeItem(type = "feature-task", rootId = rootId)))
        }

    @Test
    fun `resolveSchema empty per-root default beats the server default when no layer has the type`(): Unit =
        runBlocking {
            val rootId = UUID.randomUUID()
            server.schemas["default"] = WorkItemSchema(type = "default", notes = listOf(workEntry()))
            val ctx = contextWith(perRootWith(rootId, schemasDoc("default" to WorkItemSchema(type = "default", notes = emptyList()))))

            val result = ctx.resolveSchema(makeItem(type = "feature-task", rootId = rootId))

            assertNotNull(result)
            assertTrue(result.notes.isEmpty(), "the per-root default beats the server default")
        }

    @Test
    fun `resolveSchema no per-root row and no server exact match falls through to server default`(): Unit =
        runBlocking {
            val rootId = UUID.randomUUID()
            val globalDefault = WorkItemSchema(type = "default", notes = listOf(workEntry()))
            server.schemas["default"] = globalDefault

            assertEquals(
                globalDefault,
                contextWith(perRootWith(rootId, null)).resolveSchema(makeItem(type = "feature-task", rootId = rootId))
            )
        }

    @Test
    fun `resolveSchema with null rootId skips the per-root layer entirely`(): Unit =
        runBlocking {
            val perRoot = mockk<PerRootConfigService>()
            val globalSchema = schemaWithReview("feature-task")
            server.schemas["feature-task"] = globalSchema

            assertEquals(globalSchema, contextWith(perRoot).resolveSchema(makeItem(type = "feature-task", rootId = null)))
            coVerify(exactly = 0) { perRoot.layer(any()) }
        }

    @Test
    fun `resolveSchema per-root trait notes override the server trait definition`(): Unit =
        runBlocking {
            val rootId = UUID.randomUUID()
            server.schemas["feature-task"] =
                WorkItemSchema(type = "feature-task", notes = listOf(workEntry()), defaultTraits = listOf("needs-perf-review"))
            server.traits["needs-perf-review"] = listOf(traitEntry("performance-baseline-server"))
            val perRootDoc =
                ConfigDocument(
                    workItemSchemas = emptyMap(),
                    traits =
                        mapOf(
                            "needs-perf-review" to listOf(traitEntry("performance-baseline-per-root"))
                        )
                )

            val result = contextWith(perRootWith(rootId, perRootDoc)).resolveSchema(makeItem(type = "feature-task", rootId = rootId))!!

            assertEquals(listOf("work-note", "performance-baseline-per-root"), result.notes.map { it.key })
        }

    @Test
    fun `resolveSchema per-root default beats the server default for an untyped item`(): Unit =
        runBlocking {
            val rootId = UUID.randomUUID()
            val perRootDefault = WorkItemSchema(type = "default", notes = listOf(workEntry()))
            server.schemas["default"] = schemaWithReview("default")
            val ctx = contextWith(perRootWith(rootId, schemasDoc("default" to perRootDefault)))

            assertEquals(perRootDefault, ctx.resolveSchema(makeItem(type = null, tags = "unmatched-tag", rootId = rootId)))
        }

    @Test
    fun `resolveSchema treats a null perRootConfigService identically to a per-root config miss`(): Unit =
        runBlocking {
            val globalSchema = schemaWithReview("feature-task")
            server.schemas["feature-task"] = globalSchema

            assertEquals(globalSchema, contextWith(null).resolveSchema(makeItem(type = "feature-task", rootId = UUID.randomUUID())))
        }

    @Test
    fun `the default context resolver has no server config`(): Unit =
        runBlocking {
            val ctx = ToolExecutionContext(mockk<RepositoryProvider>(relaxed = true))
            assertNull(ctx.resolveSchema(makeItem(type = "feature-task")))
            assertNull(ctx.configResolver.global.fingerprint())
        }

    // ──────────────────────────────────────────────
    // resolveNoteLimitsMode / resolveStatusLabel (per-root layering)
    // ──────────────────────────────────────────────

    @Test
    fun `resolveNoteLimitsMode falls through to the server when rootId is null`(): Unit =
        runBlocking {
            val perRoot = mockk<PerRootConfigService>()
            server.noteLimitsMode = "reject"

            assertEquals("reject", contextWith(perRoot).resolveNoteLimitsMode(null))
            coVerify(exactly = 0) { perRoot.layer(any()) }
        }

    @Test
    fun `resolveNoteLimitsMode falls through to the server when the per-root doc has no explicit mode`(): Unit =
        runBlocking {
            val rootId = UUID.randomUUID()
            server.noteLimitsMode = "reject"

            assertEquals("reject", contextWith(perRootWith(rootId, schemasDoc())).resolveNoteLimitsMode(rootId))
        }

    @Test
    fun `resolveNoteLimitsMode uses the per-root explicit mode over the server mode`(): Unit =
        runBlocking {
            val rootId = UUID.randomUUID()
            val ctx = contextWith(perRootWith(rootId, schemasDoc().copy(noteLimitsMode = "reject")))

            assertEquals("reject", ctx.resolveNoteLimitsMode(rootId))
        }

    @Test
    fun `resolveStatusLabel falls through to the server when rootId is null`(): Unit =
        runBlocking {
            val perRoot = mockk<PerRootConfigService>()
            server.statusLabels = mapOf("start" to "in-progress")

            assertEquals("in-progress", contextWith(perRoot).resolveStatusLabel("start", null))
            coVerify(exactly = 0) { perRoot.layer(any()) }
        }

    @Test
    fun `resolveStatusLabel falls through to the server per-trigger when the per-root map is partial`(): Unit =
        runBlocking {
            val rootId = UUID.randomUUID()
            server.statusLabels = mapOf("complete" to "done")
            val ctx = contextWith(perRootWith(rootId, schemasDoc().copy(statusLabels = mapOf("start" to "root-started"))))

            assertEquals("root-started", ctx.resolveStatusLabel("start", rootId))
            assertEquals(
                "done",
                ctx.resolveStatusLabel("complete", rootId),
                "A trigger absent from the partial per-root map must fall through"
            )
        }

    @Test
    fun `resolveStatusLabel honors an explicit null per-root label without falling through`(): Unit =
        runBlocking {
            val rootId = UUID.randomUUID()
            server.statusLabels = mapOf("complete" to "done")
            val ctx = contextWith(perRootWith(rootId, schemasDoc().copy(statusLabels = mapOf("complete" to null))))

            assertNull(
                ctx.resolveStatusLabel("complete", rootId),
                "An explicit null value for a present trigger key must win over the server label, not fall through"
            )
        }

    @Test
    fun `resolveStatusLabels resolves the whole trigger set from a single snapshot fetch`(): Unit =
        runBlocking {
            val rootId = UUID.randomUUID()
            server.statusLabels = mapOf("cascade" to "done")
            val perRoot = perRootWith(rootId, schemasDoc().copy(statusLabels = mapOf("start" to "root-started")))

            val resolved = contextWith(perRoot).resolveStatusLabels(setOf("start", "cascade"), rootId)

            assertEquals("root-started", resolved["start"])
            assertEquals("done", resolved["cascade"])
            coVerify(exactly = 1) { perRoot.layer(rootId) }
        }
}
