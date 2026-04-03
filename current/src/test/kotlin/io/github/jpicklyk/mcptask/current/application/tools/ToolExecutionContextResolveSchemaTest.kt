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

    private fun reviewEntry(): NoteSchemaEntry =
        NoteSchemaEntry(key = "review-note", role = Role.REVIEW, required = false)

    private fun workEntry(): NoteSchemaEntry =
        NoteSchemaEntry(key = "work-note", role = Role.WORK, required = false)

    private fun schemaWithReview(type: String): WorkItemSchema =
        WorkItemSchema(type = type, notes = listOf(reviewEntry()))

    private fun schemaWithoutReview(type: String): WorkItemSchema =
        WorkItemSchema(type = type, notes = listOf(workEntry()))

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
}
