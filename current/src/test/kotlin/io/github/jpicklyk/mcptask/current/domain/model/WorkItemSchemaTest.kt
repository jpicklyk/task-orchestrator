package io.github.jpicklyk.mcptask.current.domain.model

import org.junit.jupiter.api.Test
import kotlin.test.*

class WorkItemSchemaTest {
    // ──────────────────────────────────────────────
    // hasReviewPhase
    // ──────────────────────────────────────────────

    @Test
    fun `hasReviewPhase returns false when notes list is empty`() {
        val schema = WorkItemSchema(type = "feature-task")
        assertFalse(schema.hasReviewPhase())
    }

    @Test
    fun `hasReviewPhase returns false when no REVIEW role notes exist`() {
        val schema =
            WorkItemSchema(
                type = "feature-task",
                notes =
                    listOf(
                        NoteSchemaEntry(key = "spec", role = Role.QUEUE, required = true),
                        NoteSchemaEntry(key = "impl-notes", role = Role.WORK, required = true)
                    )
            )
        assertFalse(schema.hasReviewPhase())
    }

    @Test
    fun `hasReviewPhase returns true when at least one REVIEW role note exists`() {
        val schema =
            WorkItemSchema(
                type = "feature-task",
                notes =
                    listOf(
                        NoteSchemaEntry(key = "spec", role = Role.QUEUE, required = true),
                        NoteSchemaEntry(key = "review-checklist", role = Role.REVIEW, required = true)
                    )
            )
        assertTrue(schema.hasReviewPhase())
    }

    @Test
    fun `hasReviewPhase returns true when multiple REVIEW role notes exist`() {
        val schema =
            WorkItemSchema(
                type = "feature-task",
                notes =
                    listOf(
                        NoteSchemaEntry(key = "review-checklist", role = Role.REVIEW, required = true),
                        NoteSchemaEntry(key = "test-coverage", role = Role.REVIEW, required = false)
                    )
            )
        assertTrue(schema.hasReviewPhase())
    }

    // ──────────────────────────────────────────────
    // requiredNotesForRole
    // ──────────────────────────────────────────────

    @Test
    fun `requiredNotesForRole returns empty list when schema has no notes`() {
        val schema = WorkItemSchema(type = "simple-task")
        val result = schema.requiredNotesForRole(Role.QUEUE)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `requiredNotesForRole returns only required entries for the given role`() {
        val schema =
            WorkItemSchema(
                type = "feature-task",
                notes =
                    listOf(
                        NoteSchemaEntry(key = "spec", role = Role.QUEUE, required = true),
                        NoteSchemaEntry(key = "optional-context", role = Role.QUEUE, required = false),
                        NoteSchemaEntry(key = "impl-notes", role = Role.WORK, required = true),
                        NoteSchemaEntry(key = "review-checklist", role = Role.REVIEW, required = true)
                    )
            )

        val queueRequired = schema.requiredNotesForRole(Role.QUEUE)
        assertEquals(1, queueRequired.size)
        assertEquals("spec", queueRequired[0].key)
    }

    @Test
    fun `requiredNotesForRole returns empty list when no required notes exist for a role`() {
        val schema =
            WorkItemSchema(
                type = "feature-task",
                notes =
                    listOf(
                        NoteSchemaEntry(key = "spec", role = Role.QUEUE, required = true),
                        NoteSchemaEntry(key = "optional-review", role = Role.REVIEW, required = false)
                    )
            )

        val reviewRequired = schema.requiredNotesForRole(Role.REVIEW)
        assertTrue(reviewRequired.isEmpty())
    }

    @Test
    fun `requiredNotesForRole returns multiple required entries for the given role`() {
        val schema =
            WorkItemSchema(
                type = "feature-task",
                notes =
                    listOf(
                        NoteSchemaEntry(key = "acceptance-criteria", role = Role.QUEUE, required = true),
                        NoteSchemaEntry(key = "risk-assessment", role = Role.QUEUE, required = true),
                        NoteSchemaEntry(key = "impl-notes", role = Role.WORK, required = true)
                    )
            )

        val queueRequired = schema.requiredNotesForRole(Role.QUEUE)
        assertEquals(2, queueRequired.size)
        assertEquals(setOf("acceptance-criteria", "risk-assessment"), queueRequired.map { it.key }.toSet())
    }

    @Test
    fun `requiredNotesForRole returns empty when role has no entries at all`() {
        val schema =
            WorkItemSchema(
                type = "simple",
                notes =
                    listOf(
                        NoteSchemaEntry(key = "spec", role = Role.QUEUE, required = true)
                    )
            )
        val workRequired = schema.requiredNotesForRole(Role.WORK)
        assertTrue(workRequired.isEmpty())
    }

    // ──────────────────────────────────────────────
    // Default values
    // ──────────────────────────────────────────────

    @Test
    fun `default lifecycleMode is AUTO`() {
        val schema = WorkItemSchema(type = "feature-task")
        assertEquals(LifecycleMode.AUTO, schema.lifecycleMode)
    }

    @Test
    fun `default notes list is empty`() {
        val schema = WorkItemSchema(type = "feature-task")
        assertTrue(schema.notes.isEmpty())
    }

    @Test
    fun `lifecycleMode can be set explicitly`() {
        val schema = WorkItemSchema(type = "feature-task", lifecycleMode = LifecycleMode.PERMANENT)
        assertEquals(LifecycleMode.PERMANENT, schema.lifecycleMode)
    }

    // ──────────────────────────────────────────────
    // defaultTraits
    // ──────────────────────────────────────────────

    @Test
    fun `default defaultTraits is empty list`() {
        val schema = WorkItemSchema(type = "feature-task")
        assertTrue(schema.defaultTraits.isEmpty())
    }

    @Test
    fun `defaultTraits can be set explicitly`() {
        val schema =
            WorkItemSchema(
                type = "feature-task",
                defaultTraits = listOf("needs-security-review", "needs-perf-review")
            )
        assertEquals(listOf("needs-security-review", "needs-perf-review"), schema.defaultTraits)
    }

    @Test
    fun `defaultTraits does not affect hasReviewPhase`() {
        val schema =
            WorkItemSchema(
                type = "feature-task",
                notes =
                    listOf(
                        NoteSchemaEntry(key = "work-note", role = Role.WORK, required = false)
                    ),
                defaultTraits = listOf("some-trait")
            )
        assertFalse(schema.hasReviewPhase())
    }

    @Test
    fun `defaultTraits does not affect requiredNotesForRole`() {
        val schema =
            WorkItemSchema(
                type = "feature-task",
                notes =
                    listOf(
                        NoteSchemaEntry(key = "spec", role = Role.QUEUE, required = true)
                    ),
                defaultTraits = listOf("some-trait")
            )
        assertEquals(1, schema.requiredNotesForRole(Role.QUEUE).size)
        assertEquals("spec", schema.requiredNotesForRole(Role.QUEUE)[0].key)
    }
}
