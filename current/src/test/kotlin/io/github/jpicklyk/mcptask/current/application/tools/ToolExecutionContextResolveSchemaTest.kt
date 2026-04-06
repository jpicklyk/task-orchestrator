package io.github.jpicklyk.mcptask.current.application.tools

import io.github.jpicklyk.mcptask.current.application.service.NoteSchemaService
import io.github.jpicklyk.mcptask.current.domain.model.*
import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider
import io.mockk.every
import io.mockk.mockk
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
        tags: String? = null
    ): WorkItem =
        WorkItem(
            id = UUID.randomUUID(),
            title = "Test Item",
            type = type,
            tags = tags,
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
    fun `resolveSchema returns type-based schema when item has type and schema exists`() {
        val item = makeItem(type = "feature-task", tags = "some-tag")
        val expected = schemaWithReview("feature-task")

        every { noteSchemaService.getSchemaForType("feature-task") } returns expected
        // Tags should NOT be consulted when type lookup succeeds
        every { noteSchemaService.getSchemaForTags(any()) } throws AssertionError("should not call getSchemaForTags")

        val result = context.resolveSchema(item)
        assertEquals(expected, result)
    }

    @Test
    fun `resolveSchema falls back to tag lookup when type lookup returns null`() {
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
    fun `resolveSchema uses tag lookup when item has no type`() {
        val item = makeItem(type = null, tags = "feature-task")
        val tagSchema = schemaWithReview("feature-task")

        every { noteSchemaService.getSchemaForTags(listOf("feature-task")) } returns tagSchema.notes

        val result = context.resolveSchema(item)
        assertNotNull(result)
        assertEquals(tagSchema.notes, result.notes)
    }

    @Test
    fun `resolveSchema returns null when no type and tag lookup returns null`() {
        val item = makeItem(type = null, tags = "unmatched-tag")

        every { noteSchemaService.getSchemaForTags(listOf("unmatched-tag")) } returns null

        val result = context.resolveSchema(item)
        assertNull(result)
    }

    @Test
    fun `resolveSchema returns null for schema-free item with no type and no tags`() {
        val item = makeItem(type = null, tags = null)

        every { noteSchemaService.getSchemaForTags(emptyList()) } returns null

        val result = context.resolveSchema(item)
        assertNull(result)
    }

    @Test
    fun `resolveSchema returns default schema when item has no tags and getSchemaForTags returns default entries`() {
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
    fun `resolveSchema uses first matching tag when multiple tags and first has no schema`() {
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
    fun `resolveHasReviewPhase returns true when schema has review phase`() {
        val item = makeItem(type = "feature-task")
        every { noteSchemaService.getSchemaForType("feature-task") } returns schemaWithReview("feature-task")

        assertTrue(context.resolveHasReviewPhase(item))
    }

    @Test
    fun `resolveHasReviewPhase returns false when schema has no review phase`() {
        val item = makeItem(type = "simple-task")
        every { noteSchemaService.getSchemaForType("simple-task") } returns schemaWithoutReview("simple-task")

        assertFalse(context.resolveHasReviewPhase(item))
    }

    @Test
    fun `resolveHasReviewPhase returns false when no schema matches`() {
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
    fun `resolveSchema merges trait notes after base schema notes`() {
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
    fun `resolveSchema base key wins over trait key with same name`() {
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
    fun `resolveSchema skips unknown traits with no error`() {
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
    fun `resolveSchema merges per-item traits from properties JSON`() {
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
    fun `resolveSchema unions default and per-item traits with deduplication`() {
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
    fun `resolveSchema returns base schema unchanged when no traits`() {
        val baseSchema = WorkItemSchema(type = "feature-task", notes = listOf(workEntry()))
        every { noteSchemaService.getSchemaForType("feature-task") } returns baseSchema
        every { noteSchemaService.getDefaultTraits("feature-task") } returns emptyList()

        val item = makeItem(type = "feature-task")
        val result = context.resolveSchema(item)!!

        assertEquals(baseSchema, result)
    }

    @Test
    fun `resolveHasReviewPhase returns true when trait adds review note`() {
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
    fun `resolveSchema first trait wins for duplicate trait note keys`() {
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
}
