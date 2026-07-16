package io.github.jpicklyk.mcptask.current.application.tools

import io.github.jpicklyk.mcptask.current.application.service.NoteSchemaService
import io.github.jpicklyk.mcptask.current.application.service.StatusLabelService
import io.github.jpicklyk.mcptask.current.domain.model.*
import io.github.jpicklyk.mcptask.current.infrastructure.config.PerRootConfigService
import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
 * Unit tests for [ToolExecutionContext.resolveSchema] and [ToolExecutionContext.resolveHasReviewPhase].
 *
 * Verifies:
 * - Type-first lookup: when item.type is set and getSchemaForType returns a schema, that schema is returned
 * - Type miss, tag fallback: when getSchemaForType returns null, falls back to getSchemaForTags
 * - No type, tag match: items without type use getSchemaForTags
 * - No type, no tag match: returns null (schema-free)
 * - Empty tags with default schema: empty tag list uses getSchemaForTags([]) — falls back to default
 * - resolveHasReviewPhase true/false delegation
 */
class ToolExecutionContextResolveSchemaTest {
    private lateinit var noteSchemaService: NoteSchemaService
    private lateinit var context: ToolExecutionContext

    @BeforeEach
    fun setUp() {
        noteSchemaService = mockk()
        // Default stubs for trait-related methods (tests override as needed)
        every { noteSchemaService.getSchemaForType(any()) } returns null
        every { noteSchemaService.getDefaultTraits(any()) } returns emptyList()
        every { noteSchemaService.getTraitNotes(any()) } returns null

        val repoProvider = mockk<RepositoryProvider>(relaxed = true)
        context = ToolExecutionContext(repoProvider, noteSchemaService)
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

    // ──────────────────────────────────────────────
    // Type-first lookup
    // ──────────────────────────────────────────────

    @Test
    fun `resolveSchema returns type-based schema when item has type and schema exists`() =
        runBlocking {
            val item = makeItem(type = "feature-task", tags = "some-tag")
            val expected = schemaWithReview("feature-task")

            every { noteSchemaService.getSchemaForType("feature-task") } returns expected
            // Tags should NOT be consulted when type lookup succeeds
            every { noteSchemaService.getSchemaForTags(any()) } throws AssertionError("should not call getSchemaForTags")

            val result = context.resolveSchema(item)
            assertEquals(expected, result)
        }

    @Test
    fun `resolveSchema falls back to tag lookup when type lookup returns null`() =
        runBlocking {
            val item = makeItem(type = "unknown-type", tags = "feature-task")
            val tagSchema = schemaWithReview("feature-task")

            every { noteSchemaService.getSchemaForType("unknown-type") } returns null
            every { noteSchemaService.getSchemaForTags(listOf("feature-task")) } returns tagSchema.notes

            val result = context.resolveSchema(item)
            assertNotNull(result)
            assertTrue(result.hasReviewPhase())
            assertEquals(tagSchema.notes, result.notes)
        }

    // ──────────────────────────────────────────────
    // No-type items use tag lookup
    // ──────────────────────────────────────────────

    @Test
    fun `resolveSchema uses tag lookup when item has no type`() =
        runBlocking {
            val item = makeItem(type = null, tags = "feature-task")
            val tagSchema = schemaWithReview("feature-task")

            every { noteSchemaService.getSchemaForTags(listOf("feature-task")) } returns tagSchema.notes

            val result = context.resolveSchema(item)
            assertNotNull(result)
            assertEquals(tagSchema.notes, result.notes)
        }

    @Test
    fun `resolveSchema returns null when no type and tag lookup returns null`() =
        runBlocking {
            val item = makeItem(type = null, tags = "unmatched-tag")

            every { noteSchemaService.getSchemaForTags(listOf("unmatched-tag")) } returns null

            val result = context.resolveSchema(item)
            assertNull(result)
        }

    @Test
    fun `resolveSchema returns null for schema-free item with no type and no tags`() =
        runBlocking {
            val item = makeItem(type = null, tags = null)

            every { noteSchemaService.getSchemaForTags(emptyList()) } returns null

            val result = context.resolveSchema(item)
            assertNull(result)
        }

    @Test
    fun `resolveSchema returns default schema when item has no tags and getSchemaForTags returns default entries`() =
        runBlocking {
            val item = makeItem(type = null, tags = null)
            val defaultEntries = listOf(workEntry())

            // Service returns a default schema for empty tag list (internal fallback in YamlNoteSchemaService)
            every { noteSchemaService.getSchemaForTags(emptyList()) } returns defaultEntries

            val result = context.resolveSchema(item)
            assertNotNull(result)
            assertEquals("default", result.type)
            assertEquals(defaultEntries, result.notes)
        }

    // ──────────────────────────────────────────────
    // Multi-tag items: first-match semantics
    // ──────────────────────────────────────────────

    @Test
    fun `resolveSchema uses first matching tag when multiple tags and first has no schema`() =
        runBlocking {
            val item = makeItem(type = null, tags = "no-match, feature-task")

            // Full list lookup returns the schema (first-match logic inside service)
            every { noteSchemaService.getSchemaForTags(listOf("no-match", "feature-task")) } returns
                schemaWithReview("feature-task").notes

            // Individual tag lookups used to determine matched key
            every { noteSchemaService.getSchemaForTags(listOf("no-match")) } returns null
            every { noteSchemaService.getSchemaForTags(listOf("feature-task")) } returns
                schemaWithReview("feature-task").notes

            val result = context.resolveSchema(item)
            assertNotNull(result)
            assertEquals("feature-task", result.type)
            assertTrue(result.hasReviewPhase())
        }

    // ──────────────────────────────────────────────
    // resolveHasReviewPhase
    // ──────────────────────────────────────────────

    @Test
    fun `resolveHasReviewPhase returns true when schema has review phase`() =
        runBlocking {
            val item = makeItem(type = "feature-task")
            every { noteSchemaService.getSchemaForType("feature-task") } returns schemaWithReview("feature-task")

            assertTrue(context.resolveHasReviewPhase(item))
        }

    @Test
    fun `resolveHasReviewPhase returns false when schema has no review phase`() =
        runBlocking {
            val item = makeItem(type = "simple-task")
            every { noteSchemaService.getSchemaForType("simple-task") } returns schemaWithoutReview("simple-task")

            assertFalse(context.resolveHasReviewPhase(item))
        }

    @Test
    fun `resolveHasReviewPhase returns false when no schema matches`() =
        runBlocking {
            val item = makeItem(type = null, tags = null)
            every { noteSchemaService.getSchemaForTags(emptyList()) } returns null

            assertFalse(context.resolveHasReviewPhase(item))
        }

    // ──────────────────────────────────────────────
    // Trait merging
    // ──────────────────────────────────────────────

    private fun makeItemWithProperties(
        type: String? = null,
        tags: String? = null,
        properties: String? = null
    ): WorkItem =
        WorkItem(
            id = UUID.randomUUID(),
            title = "Test Item",
            type = type,
            tags = tags,
            properties = properties,
            depth = 0
        )

    private fun traitEntry(
        key: String,
        role: Role = Role.REVIEW
    ): NoteSchemaEntry = NoteSchemaEntry(key = key, role = role, required = true, description = "Trait note: $key")

    @Test
    fun `resolveSchema merges trait notes after base schema notes`() =
        runBlocking {
            val baseSchema =
                WorkItemSchema(
                    type = "feature-task",
                    notes = listOf(workEntry()),
                    defaultTraits = listOf("needs-security-review")
                )
            every { noteSchemaService.getSchemaForType("feature-task") } returns baseSchema
            every { noteSchemaService.getTraitNotes("needs-security-review") } returns listOf(traitEntry("security-assessment"))
            every { noteSchemaService.getDefaultTraits("feature-task") } returns listOf("needs-security-review")

            val item = makeItem(type = "feature-task")
            val result = context.resolveSchema(item)!!

            assertEquals(2, result.notes.size)
            assertEquals("work-note", result.notes[0].key)
            assertEquals("security-assessment", result.notes[1].key)
        }

    @Test
    fun `resolveSchema base key wins over trait key with same name`() =
        runBlocking {
            val baseSchema =
                WorkItemSchema(
                    type = "feature-task",
                    notes = listOf(NoteSchemaEntry(key = "shared-key", role = Role.WORK, required = false, description = "base")),
                    defaultTraits = listOf("my-trait")
                )
            every { noteSchemaService.getSchemaForType("feature-task") } returns baseSchema
            every { noteSchemaService.getTraitNotes("my-trait") } returns
                listOf(
                    NoteSchemaEntry(key = "shared-key", role = Role.REVIEW, required = true, description = "trait")
                )
            every { noteSchemaService.getDefaultTraits("feature-task") } returns listOf("my-trait")

            val item = makeItem(type = "feature-task")
            val result = context.resolveSchema(item)!!

            assertEquals(1, result.notes.size)
            assertEquals("base", result.notes[0].description)
        }

    @Test
    fun `resolveSchema skips unknown traits with no error`() =
        runBlocking {
            val baseSchema =
                WorkItemSchema(
                    type = "feature-task",
                    notes = listOf(workEntry()),
                    defaultTraits = listOf("nonexistent-trait")
                )
            every { noteSchemaService.getSchemaForType("feature-task") } returns baseSchema
            every { noteSchemaService.getTraitNotes("nonexistent-trait") } returns null
            every { noteSchemaService.getDefaultTraits("feature-task") } returns listOf("nonexistent-trait")

            val item = makeItem(type = "feature-task")
            val result = context.resolveSchema(item)!!

            assertEquals(1, result.notes.size)
            assertEquals("work-note", result.notes[0].key)
        }

    @Test
    fun `resolveSchema merges per-item traits from properties JSON`() =
        runBlocking {
            val baseSchema = WorkItemSchema(type = "feature-task", notes = listOf(workEntry()))
            every { noteSchemaService.getSchemaForType("feature-task") } returns baseSchema
            every { noteSchemaService.getTraitNotes("needs-perf-review") } returns listOf(traitEntry("performance-baseline", Role.QUEUE))
            every { noteSchemaService.getDefaultTraits("feature-task") } returns emptyList()

            val item =
                makeItemWithProperties(
                    type = "feature-task",
                    properties = """{"traits": ["needs-perf-review"]}"""
                )
            val result = context.resolveSchema(item)!!

            assertEquals(2, result.notes.size)
            assertEquals("performance-baseline", result.notes[1].key)
        }

    @Test
    fun `resolveSchema unions default and per-item traits with deduplication`() =
        runBlocking {
            val baseSchema =
                WorkItemSchema(
                    type = "feature-task",
                    notes = listOf(workEntry()),
                    defaultTraits = listOf("trait-a")
                )
            every { noteSchemaService.getSchemaForType("feature-task") } returns baseSchema
            every { noteSchemaService.getTraitNotes("trait-a") } returns listOf(traitEntry("note-a"))
            every { noteSchemaService.getTraitNotes("trait-b") } returns listOf(traitEntry("note-b"))
            every { noteSchemaService.getDefaultTraits("feature-task") } returns listOf("trait-a")

            val item =
                makeItemWithProperties(
                    type = "feature-task",
                    properties = """{"traits": ["trait-a", "trait-b"]}"""
                )
            val result = context.resolveSchema(item)!!

            assertEquals(3, result.notes.size)
            assertEquals("note-a", result.notes[1].key)
            assertEquals("note-b", result.notes[2].key)
        }

    @Test
    fun `resolveSchema returns base schema unchanged when no traits`() =
        runBlocking {
            val baseSchema = WorkItemSchema(type = "feature-task", notes = listOf(workEntry()))
            every { noteSchemaService.getSchemaForType("feature-task") } returns baseSchema
            every { noteSchemaService.getDefaultTraits("feature-task") } returns emptyList()

            val item = makeItem(type = "feature-task")
            val result = context.resolveSchema(item)!!

            assertEquals(baseSchema, result)
        }

    @Test
    fun `resolveHasReviewPhase returns true when trait adds review note`() =
        runBlocking {
            val baseSchema =
                WorkItemSchema(
                    type = "feature-task",
                    notes = listOf(workEntry()),
                    defaultTraits = listOf("needs-review-trait")
                )
            every { noteSchemaService.getSchemaForType("feature-task") } returns baseSchema
            every { noteSchemaService.getTraitNotes("needs-review-trait") } returns listOf(traitEntry("review-from-trait", Role.REVIEW))
            every { noteSchemaService.getDefaultTraits("feature-task") } returns listOf("needs-review-trait")

            val item = makeItem(type = "feature-task")
            assertTrue(context.resolveHasReviewPhase(item))
        }

    @Test
    fun `resolveSchema first trait wins for duplicate trait note keys`() =
        runBlocking {
            val baseSchema =
                WorkItemSchema(
                    type = "feature-task",
                    notes = listOf(workEntry()),
                    defaultTraits = listOf("trait-a", "trait-b")
                )
            every { noteSchemaService.getSchemaForType("feature-task") } returns baseSchema
            every { noteSchemaService.getTraitNotes("trait-a") } returns
                listOf(
                    NoteSchemaEntry(key = "duplicate-key", role = Role.REVIEW, required = true, description = "from trait-a")
                )
            every { noteSchemaService.getTraitNotes("trait-b") } returns
                listOf(
                    NoteSchemaEntry(key = "duplicate-key", role = Role.QUEUE, required = false, description = "from trait-b")
                )
            every { noteSchemaService.getDefaultTraits("feature-task") } returns listOf("trait-a", "trait-b")

            val item = makeItem(type = "feature-task")
            val result = context.resolveSchema(item)!!

            assertEquals(2, result.notes.size)
            assertEquals("from trait-a", result.notes[1].description)
        }

    // ──────────────────────────────────────────────
    // Bug regression: schema fallback preserves lifecycleMode + defaultTraits (Bug 5)
    // ──────────────────────────────────────────────

    @Test
    fun `resolveSchema preserves lifecycleMode and defaultTraits on default fallback (bug 5 regression)`() =
        runBlocking {
            // Scenario: item has tags ["bug","tools-layer"], no type set.
            // No individual tag matches a schema.
            // getSchemaForTags(["bug","tools-layer"]) falls back to default entries.
            // getSchemaForType("default") returns a WorkItemSchema with MANUAL lifecycle + defaultTraits.
            // Before fix: getSchemaForTags was called a THIRD time in the fallback constructor.
            //   (Redundant, but functionally equivalent — the real risk is a future refactor breaks capture.)
            //   The fix captures tagNotes once and reuses them. Verified via: getSchemaForType("default") returned.
            val item = makeItem(type = null, tags = "bug,tools-layer")
            val defaultNotes = listOf(workEntry())
            val defaultSchema =
                WorkItemSchema(
                    type = "default",
                    lifecycleMode = LifecycleMode.MANUAL,
                    notes = defaultNotes,
                    defaultTraits = listOf("some-trait")
                )

            // Individual tag lookups return null (neither "bug" nor "tools-layer" matches)
            every { noteSchemaService.getSchemaForTags(listOf("bug")) } returns null
            every { noteSchemaService.getSchemaForTags(listOf("tools-layer")) } returns null
            // Full tag list lookup falls back to default schema entries
            every { noteSchemaService.getSchemaForTags(listOf("bug", "tools-layer")) } returns defaultNotes
            // getSchemaForType("default") returns the full WorkItemSchema with lifecycle + traits
            every { noteSchemaService.getSchemaForType("default") } returns defaultSchema

            val result = context.resolveSchema(item)
            assertNotNull(result, "Should return a schema (default fallback)")
            assertEquals("default", result.type)
            assertEquals(
                LifecycleMode.MANUAL,
                result.lifecycleMode,
                "lifecycleMode must be preserved from the WorkItemSchema returned by getSchemaForType"
            )
            assertEquals(listOf("some-trait"), result.defaultTraits, "defaultTraits must be preserved from the WorkItemSchema")
            assertEquals(defaultNotes, result.notes)
        }

    @Test
    fun `resolveSchema uses captured tagNotes when getSchemaForType returns null (bug 5 graceful fallback)`() =
        runBlocking {
            // Scenario: item has multiple tags, none individually match a schema,
            // getSchemaForTags(fullList) returns default notes (default fallback inside the service),
            // but getSchemaForType("default") returns null (default not defined as a full WorkItemSchema).
            // Before fix: getSchemaForTags(tags) was called a SECOND time inside the fallback constructor.
            // After fix: tagNotes is captured once and reused — functionally equivalent, no redundant call.
            val item = makeItem(type = null, tags = "bug,tools-layer")
            val fallbackNotes = listOf(workEntry())

            // Individual tag lookups return null — no single tag matches
            every { noteSchemaService.getSchemaForTags(listOf("bug")) } returns null
            every { noteSchemaService.getSchemaForTags(listOf("tools-layer")) } returns null
            // Full tag list returns notes (service's internal default fallback)
            every { noteSchemaService.getSchemaForTags(listOf("bug", "tools-layer")) } returns fallbackNotes
            // getSchemaForType("default") returns null — no full WorkItemSchema defined for default
            every { noteSchemaService.getSchemaForType("default") } returns null

            val result = context.resolveSchema(item)
            assertNotNull(result, "Should return a bare schema using the captured tagNotes")
            assertEquals("default", result.type)
            assertEquals(
                fallbackNotes,
                result.notes,
                "Notes must come from the already-captured getSchemaForTags result — no redundant call"
            )
        }

    // ──────────────────────────────────────────────
    // T3.2: per-root config layering (perRootConfigService)
    // ──────────────────────────────────────────────

    @Test
    fun `resolveSchema per-root type match overrides global type lookup`() =
        runBlocking {
            val rootId = UUID.randomUUID()
            val perRoot = mockk<PerRootConfigService>()
            val perRootSchema = schemaWithReview("feature-task")
            coEvery { perRoot.getSnapshot(rootId) } returns
                PerRootConfigService.Snapshot(
                    workItemSchemas = mapOf("feature-task" to perRootSchema),
                    traits = emptyMap(),
                    noteLimitsModeExplicit = null,
                    statusLabels = null,
                    fingerprint = "fp"
                )

            val repoProvider = mockk<RepositoryProvider>(relaxed = true)
            val ctx = ToolExecutionContext(repoProvider, noteSchemaService, perRootConfigService = perRoot)

            val item = makeItem(type = "feature-task", rootId = rootId)
            val result = ctx.resolveSchema(item)

            assertEquals(perRootSchema, result)
            // The global type lookup must be short-circuited once the per-root layer matches.
            verify(exactly = 0) { noteSchemaService.getSchemaForType("feature-task") }
        }

    @Test
    fun `resolveSchema per-root type miss falls through to global type lookup`() =
        runBlocking {
            val rootId = UUID.randomUUID()
            val perRoot = mockk<PerRootConfigService>()
            // Per-root row exists but defines neither the exact type nor "default".
            coEvery { perRoot.getSnapshot(rootId) } returns
                PerRootConfigService.Snapshot(
                    workItemSchemas = emptyMap(),
                    traits = emptyMap(),
                    noteLimitsModeExplicit = null,
                    statusLabels = null,
                    fingerprint = "fp"
                )

            val globalSchema = schemaWithReview("feature-task")
            every { noteSchemaService.getSchemaForType("feature-task") } returns globalSchema

            val repoProvider = mockk<RepositoryProvider>(relaxed = true)
            val ctx = ToolExecutionContext(repoProvider, noteSchemaService, perRootConfigService = perRoot)

            val item = makeItem(type = "feature-task", rootId = rootId)
            val result = ctx.resolveSchema(item)

            assertEquals(globalSchema, result)
            // Exactly ONE snapshot fetch for the whole resolveSchema call (schema/trait resolution
            // reuse the same already-fetched snapshot instead of re-querying per facet).
            coVerify(exactly = 1) { perRoot.getSnapshot(rootId) }
        }

    @Test
    fun `resolveSchema per-root default wins over global exact type match (whole-algorithm-first)`() =
        runBlocking {
            val rootId = UUID.randomUUID()
            val perRoot = mockk<PerRootConfigService>()
            val perRootDefault = WorkItemSchema(type = "default", notes = listOf(workEntry()))
            coEvery { perRoot.getSnapshot(rootId) } returns
                PerRootConfigService.Snapshot(
                    workItemSchemas = mapOf("default" to perRootDefault),
                    traits = emptyMap(),
                    noteLimitsModeExplicit = null,
                    statusLabels = null,
                    fingerprint = "fp"
                )

            // Global has an EXACT type match — under whole-algorithm-first, the per-root default
            // must still win; the global layer must never be consulted.
            val globalSchema = schemaWithReview("feature-task")
            every { noteSchemaService.getSchemaForType("feature-task") } returns globalSchema

            val repoProvider = mockk<RepositoryProvider>(relaxed = true)
            val ctx = ToolExecutionContext(repoProvider, noteSchemaService, perRootConfigService = perRoot)

            val item = makeItem(type = "feature-task", rootId = rootId)
            val result = ctx.resolveSchema(item)

            assertEquals(perRootDefault, result)
            verify(exactly = 0) { noteSchemaService.getSchemaForType("feature-task") }
        }

    @Test
    fun `resolveSchema empty per-root default schema fences off global entirely`() =
        runBlocking {
            val rootId = UUID.randomUUID()
            val perRoot = mockk<PerRootConfigService>()
            val emptyPerRootDefault = WorkItemSchema(type = "default", notes = emptyList())
            coEvery { perRoot.getSnapshot(rootId) } returns
                PerRootConfigService.Snapshot(
                    workItemSchemas = mapOf("default" to emptyPerRootDefault),
                    traits = emptyMap(),
                    noteLimitsModeExplicit = null,
                    statusLabels = null,
                    fingerprint = "fp"
                )

            val repoProvider = mockk<RepositoryProvider>(relaxed = true)
            val ctx = ToolExecutionContext(repoProvider, noteSchemaService, perRootConfigService = perRoot)

            val item = makeItem(type = "feature-task", rootId = rootId)
            val result = ctx.resolveSchema(item)

            assertNotNull(result)
            assertTrue(result.notes.isEmpty(), "Empty per-root default must resolve to a zero-note schema")
            // Global must NEVER be consulted once a per-root default (even an empty one) resolves.
            verify(exactly = 0) { noteSchemaService.getSchemaForType(any()) }
            verify(exactly = 0) { noteSchemaService.getSchemaForTags(any()) }
        }

    @Test
    fun `resolveSchema no per-root row and no global exact match falls through to global default`() =
        runBlocking {
            val rootId = UUID.randomUUID()
            val perRoot = mockk<PerRootConfigService>()
            // No per-root config row at all for this root.
            coEvery { perRoot.getSnapshot(rootId) } returns null

            // No global exact match either — but getSchemaForType("feature-task") mimics the real
            // YamlNoteSchemaService's own internal exact -> "default" fallback by directly returning
            // the global default schema here (the mock stands in for that internal behavior).
            val globalDefault = WorkItemSchema(type = "default", notes = listOf(workEntry()))
            every { noteSchemaService.getSchemaForType("feature-task") } returns globalDefault

            val repoProvider = mockk<RepositoryProvider>(relaxed = true)
            val ctx = ToolExecutionContext(repoProvider, noteSchemaService, perRootConfigService = perRoot)

            val item = makeItem(type = "feature-task", rootId = rootId)
            val result = ctx.resolveSchema(item)

            assertEquals(globalDefault, result)
        }

    @Test
    fun `resolveSchema with null rootId skips the per-root layer entirely`() =
        runBlocking {
            val perRoot = mockk<PerRootConfigService>()
            val globalSchema = schemaWithReview("feature-task")
            every { noteSchemaService.getSchemaForType("feature-task") } returns globalSchema

            val repoProvider = mockk<RepositoryProvider>(relaxed = true)
            val ctx = ToolExecutionContext(repoProvider, noteSchemaService, perRootConfigService = perRoot)

            val item = makeItem(type = "feature-task", rootId = null)
            val result = ctx.resolveSchema(item)

            assertEquals(globalSchema, result)
            // Zero interactions with the per-root layer at all — a null rootId must never even
            // attempt a snapshot fetch.
            coVerify(exactly = 0) { perRoot.getSnapshot(any()) }
        }

    @Test
    fun `resolveSchema per-root trait notes override the global trait definition`() =
        runBlocking {
            val rootId = UUID.randomUUID()
            val perRoot = mockk<PerRootConfigService>()

            val baseSchema =
                WorkItemSchema(
                    type = "feature-task",
                    notes = listOf(workEntry()),
                    defaultTraits = listOf("needs-perf-review")
                )
            val perRootTraitNote = traitEntry("performance-baseline-per-root")
            // Type lookup misses per-root (exact and "default") and falls to the global base
            // schema — trait layering is independent of where the base schema itself came from.
            coEvery { perRoot.getSnapshot(rootId) } returns
                PerRootConfigService.Snapshot(
                    workItemSchemas = emptyMap(),
                    traits = mapOf("needs-perf-review" to listOf(perRootTraitNote)),
                    noteLimitsModeExplicit = null,
                    statusLabels = null,
                    fingerprint = "fp"
                )
            every { noteSchemaService.getSchemaForType("feature-task") } returns baseSchema
            every { noteSchemaService.getDefaultTraits("feature-task") } returns listOf("needs-perf-review")

            val repoProvider = mockk<RepositoryProvider>(relaxed = true)
            val ctx = ToolExecutionContext(repoProvider, noteSchemaService, perRootConfigService = perRoot)

            val item = makeItem(type = "feature-task", rootId = rootId)
            val result = ctx.resolveSchema(item)!!

            assertEquals(2, result.notes.size)
            assertEquals("performance-baseline-per-root", result.notes[1].key)
            // The global trait lookup must be short-circuited once the per-root layer provides notes.
            verify(exactly = 0) { noteSchemaService.getTraitNotes("needs-perf-review") }
        }

    @Test
    fun `resolveSchema per-root default schema wins in the tag fallback path`() =
        runBlocking {
            val rootId = UUID.randomUUID()
            val perRoot = mockk<PerRootConfigService>()
            val perRootDefault = WorkItemSchema(type = "default", notes = listOf(workEntry()))
            coEvery { perRoot.getSnapshot(rootId) } returns
                PerRootConfigService.Snapshot(
                    workItemSchemas = mapOf("default" to perRootDefault),
                    traits = emptyMap(),
                    noteLimitsModeExplicit = null,
                    statusLabels = null,
                    fingerprint = "fp"
                )

            val repoProvider = mockk<RepositoryProvider>(relaxed = true)
            val ctx = ToolExecutionContext(repoProvider, noteSchemaService, perRootConfigService = perRoot)

            val item = makeItem(type = null, tags = "unmatched-tag", rootId = rootId)
            val result = ctx.resolveSchema(item)

            assertEquals(perRootDefault, result)
            // The global tag algorithm must be short-circuited once the per-root tag/"default" match resolves.
            verify(exactly = 0) { noteSchemaService.getSchemaForTags(any()) }
        }

    @Test
    fun `resolveSchema treats a null perRootConfigService identically to a per-root config miss`() =
        runBlocking {
            val rootId = UUID.randomUUID()
            val globalSchema = schemaWithReview("feature-task")
            every { noteSchemaService.getSchemaForType("feature-task") } returns globalSchema

            // No perRootConfigService wired at all (default null) — must behave exactly like a
            // wired service that returns null for every call (e.g. a per-root config row that
            // fails to parse). PerRootConfigService's contract treats "no row" and "parse failure"
            // identically (both return null); this asserts resolveSchema treats "no service" the
            // same way too, so all three collapse to the one "fall through to global" behavior.
            val repoProvider = mockk<RepositoryProvider>(relaxed = true)
            val ctxWithoutService = ToolExecutionContext(repoProvider, noteSchemaService)

            val item = makeItem(type = "feature-task", rootId = rootId)
            val result = ctxWithoutService.resolveSchema(item)

            assertEquals(globalSchema, result)
        }

    // ──────────────────────────────────────────────
    // T3: resolveNoteLimitsMode / resolveStatusLabel (per-root layering)
    // ──────────────────────────────────────────────

    @Test
    fun `resolveNoteLimitsMode falls through to global when rootId is null`() =
        runBlocking {
            val perRoot = mockk<PerRootConfigService>()
            val globalStatusLabels = mockk<StatusLabelService>(relaxed = true)
            val repoProvider = mockk<RepositoryProvider>(relaxed = true)
            every { noteSchemaService.getNoteLimitsMode() } returns "warn"
            val ctx =
                ToolExecutionContext(
                    repoProvider,
                    noteSchemaService,
                    statusLabelService = globalStatusLabels,
                    perRootConfigService = perRoot
                )

            assertEquals("warn", ctx.resolveNoteLimitsMode(null))
            coVerify(exactly = 0) { perRoot.getSnapshot(any()) }
        }

    @Test
    fun `resolveNoteLimitsMode falls through to global when the per-root doc has no explicit mode`() =
        runBlocking {
            val rootId = UUID.randomUUID()
            val perRoot = mockk<PerRootConfigService>()
            coEvery { perRoot.getSnapshot(rootId) } returns
                PerRootConfigService.Snapshot(
                    workItemSchemas = emptyMap(),
                    traits = emptyMap(),
                    noteLimitsModeExplicit = null,
                    statusLabels = null,
                    fingerprint = "fp"
                )
            every { noteSchemaService.getNoteLimitsMode() } returns "warn"
            val repoProvider = mockk<RepositoryProvider>(relaxed = true)
            val ctx = ToolExecutionContext(repoProvider, noteSchemaService, perRootConfigService = perRoot)

            assertEquals("warn", ctx.resolveNoteLimitsMode(rootId))
        }

    @Test
    fun `resolveNoteLimitsMode uses the per-root explicit mode over the global mode`() =
        runBlocking {
            val rootId = UUID.randomUUID()
            val perRoot = mockk<PerRootConfigService>()
            coEvery { perRoot.getSnapshot(rootId) } returns
                PerRootConfigService.Snapshot(
                    workItemSchemas = emptyMap(),
                    traits = emptyMap(),
                    noteLimitsModeExplicit = "reject",
                    statusLabels = null,
                    fingerprint = "fp"
                )
            every { noteSchemaService.getNoteLimitsMode() } returns "warn"
            val repoProvider = mockk<RepositoryProvider>(relaxed = true)
            val ctx = ToolExecutionContext(repoProvider, noteSchemaService, perRootConfigService = perRoot)

            assertEquals("reject", ctx.resolveNoteLimitsMode(rootId))
        }

    @Test
    fun `resolveStatusLabel falls through to global when rootId is null`() =
        runBlocking {
            val perRoot = mockk<PerRootConfigService>()
            val globalLabels = mockk<StatusLabelService>()
            every { globalLabels.resolveLabel("start") } returns "in-progress"
            val repoProvider = mockk<RepositoryProvider>(relaxed = true)
            val ctx =
                ToolExecutionContext(
                    repoProvider,
                    noteSchemaService,
                    statusLabelService = globalLabels,
                    perRootConfigService = perRoot
                )

            assertEquals("in-progress", ctx.resolveStatusLabel("start", null))
            coVerify(exactly = 0) { perRoot.getSnapshot(any()) }
        }

    @Test
    fun `resolveStatusLabel falls through to global per-trigger when the per-root map is partial`() =
        runBlocking {
            val rootId = UUID.randomUUID()
            val perRoot = mockk<PerRootConfigService>()
            coEvery { perRoot.getSnapshot(rootId) } returns
                PerRootConfigService.Snapshot(
                    workItemSchemas = emptyMap(),
                    traits = emptyMap(),
                    noteLimitsModeExplicit = null,
                    statusLabels = mapOf("start" to "root-started"),
                    fingerprint = "fp"
                )
            val globalLabels = mockk<StatusLabelService>()
            every { globalLabels.resolveLabel("complete") } returns "done"
            val repoProvider = mockk<RepositoryProvider>(relaxed = true)
            val ctx =
                ToolExecutionContext(
                    repoProvider,
                    noteSchemaService,
                    statusLabelService = globalLabels,
                    perRootConfigService = perRoot
                )

            assertEquals("root-started", ctx.resolveStatusLabel("start", rootId))
            assertEquals(
                "done",
                ctx.resolveStatusLabel("complete", rootId),
                "A trigger absent from the partial per-root map must fall through to global"
            )
        }

    @Test
    fun `resolveStatusLabel honors an explicit null per-root label without falling through`() =
        runBlocking {
            val rootId = UUID.randomUUID()
            val perRoot = mockk<PerRootConfigService>()
            coEvery { perRoot.getSnapshot(rootId) } returns
                PerRootConfigService.Snapshot(
                    workItemSchemas = emptyMap(),
                    traits = emptyMap(),
                    noteLimitsModeExplicit = null,
                    statusLabels = mapOf("complete" to null),
                    fingerprint = "fp"
                )
            val globalLabels = mockk<StatusLabelService>()
            every { globalLabels.resolveLabel("complete") } returns "done"
            val repoProvider = mockk<RepositoryProvider>(relaxed = true)
            val ctx =
                ToolExecutionContext(
                    repoProvider,
                    noteSchemaService,
                    statusLabelService = globalLabels,
                    perRootConfigService = perRoot
                )

            assertNull(
                ctx.resolveStatusLabel("complete", rootId),
                "An explicit null value for a present trigger key must win over the global label, not fall through"
            )
            verify(exactly = 0) { globalLabels.resolveLabel("complete") }
        }

    @Test
    fun `resolveStatusLabels resolves the whole trigger set from a single snapshot fetch`() =
        runBlocking {
            val rootId = UUID.randomUUID()
            val perRoot = mockk<PerRootConfigService>()
            coEvery { perRoot.getSnapshot(rootId) } returns
                PerRootConfigService.Snapshot(
                    workItemSchemas = emptyMap(),
                    traits = emptyMap(),
                    noteLimitsModeExplicit = null,
                    statusLabels = mapOf("start" to "root-started"),
                    fingerprint = "fp"
                )
            val globalLabels = mockk<StatusLabelService>()
            every { globalLabels.resolveLabel("cascade") } returns "done"
            val repoProvider = mockk<RepositoryProvider>(relaxed = true)
            val ctx =
                ToolExecutionContext(
                    repoProvider,
                    noteSchemaService,
                    statusLabelService = globalLabels,
                    perRootConfigService = perRoot
                )

            val resolved = ctx.resolveStatusLabels(setOf("start", "cascade"), rootId)

            assertEquals("root-started", resolved["start"])
            assertEquals("done", resolved["cascade"])
            coVerify(exactly = 1) { perRoot.getSnapshot(rootId) }
        }
}
