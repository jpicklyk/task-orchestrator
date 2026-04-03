package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItemSchema
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

class PhaseNoteContextTest {
    private fun note(
        key: String,
        body: String = "content"
    ) = Note(
        id = UUID.randomUUID(),
        itemId = UUID.randomUUID(),
        key = key,
        role = "queue",
        body = body
    )

    @Test
    fun `returns null for terminal items`() {
        val schema =
            listOf(
                NoteSchemaEntry(key = "spec", role = Role.QUEUE, required = true, guidance = "Write spec")
            )
        val result = computePhaseNoteContext(Role.TERMINAL, schema, emptyMap())
        assertNull(result)
    }

    @Test
    fun `returns null when schema is null`() {
        val result = computePhaseNoteContext(Role.QUEUE, null, emptyMap())
        assertNull(result)
    }

    @Test
    fun `computes correct counts with no notes filled`() {
        val schema =
            listOf(
                NoteSchemaEntry(key = "spec", role = Role.QUEUE, required = true, guidance = "Write spec"),
                NoteSchemaEntry(key = "design", role = Role.QUEUE, required = true, guidance = "Write design")
            )
        val result = computePhaseNoteContext(Role.QUEUE, schema, emptyMap())

        assertNotNull(result)
        assertEquals("Write spec", result.guidancePointer)
        assertEquals(listOf("spec", "design"), result.missingKeys)
        assertEquals(0, result.filled)
        assertEquals(2, result.remaining)
        assertEquals(2, result.total)
    }

    @Test
    fun `computes correct counts with some notes filled`() {
        val schema =
            listOf(
                NoteSchemaEntry(key = "spec", role = Role.QUEUE, required = true, guidance = "Write spec"),
                NoteSchemaEntry(key = "design", role = Role.QUEUE, required = true, guidance = "Write design"),
                NoteSchemaEntry(key = "risks", role = Role.QUEUE, required = true, guidance = "List risks")
            )
        val notesByKey = mapOf("spec" to note("spec"))
        val result = computePhaseNoteContext(Role.QUEUE, schema, notesByKey)

        assertNotNull(result)
        assertEquals("Write design", result.guidancePointer)
        assertEquals(listOf("design", "risks"), result.missingKeys)
        assertEquals(1, result.filled)
        assertEquals(2, result.remaining)
        assertEquals(3, result.total)
    }

    @Test
    fun `all notes filled gives null guidancePointer`() {
        val schema =
            listOf(
                NoteSchemaEntry(key = "spec", role = Role.QUEUE, required = true, guidance = "Write spec")
            )
        val notesByKey = mapOf("spec" to note("spec"))
        val result = computePhaseNoteContext(Role.QUEUE, schema, notesByKey)

        assertNotNull(result)
        assertNull(result.guidancePointer)
        assertEquals(emptyList(), result.missingKeys)
        assertEquals(1, result.filled)
        assertEquals(0, result.remaining)
        assertEquals(1, result.total)
    }

    @Test
    fun `blank body does not count as filled`() {
        val schema =
            listOf(
                NoteSchemaEntry(key = "spec", role = Role.QUEUE, required = true, guidance = "Write spec")
            )
        val notesByKey = mapOf("spec" to note("spec", body = ""))
        val result = computePhaseNoteContext(Role.QUEUE, schema, notesByKey)

        assertNotNull(result)
        assertEquals("Write spec", result.guidancePointer)
        assertEquals(1, result.remaining)
        assertEquals(0, result.filled)
    }

    @Test
    fun `only considers notes for the current role`() {
        val schema =
            listOf(
                NoteSchemaEntry(key = "spec", role = Role.QUEUE, required = true, guidance = "Queue guidance"),
                NoteSchemaEntry(key = "impl", role = Role.WORK, required = true, guidance = "Work guidance")
            )
        // Item is in WORK phase — should only consider work-role notes
        val result = computePhaseNoteContext(Role.WORK, schema, emptyMap())

        assertNotNull(result)
        assertEquals("Work guidance", result.guidancePointer)
        assertEquals(listOf("impl"), result.missingKeys)
        assertEquals(0, result.filled)
        assertEquals(1, result.remaining)
        assertEquals(1, result.total)
    }

    @Test
    fun `non-required notes are excluded from counts`() {
        val schema =
            listOf(
                NoteSchemaEntry(key = "spec", role = Role.QUEUE, required = true, guidance = "Required"),
                NoteSchemaEntry(key = "optional-notes", role = Role.QUEUE, required = false, guidance = "Optional")
            )
        val result = computePhaseNoteContext(Role.QUEUE, schema, emptyMap())

        assertNotNull(result)
        assertEquals(1, result.total)
        assertEquals(1, result.remaining)
        assertEquals("Required", result.guidancePointer)
    }

    @Test
    fun `empty schema returns zero counts with null guidance`() {
        val result = computePhaseNoteContext(Role.QUEUE, emptyList(), emptyMap())

        assertNotNull(result)
        assertNull(result.guidancePointer)
        assertEquals(emptyList(), result.missingKeys)
        assertEquals(0, result.filled)
        assertEquals(0, result.remaining)
        assertEquals(0, result.total)
    }

    // ──────────────────────────────────────────────
    // WorkItemSchema overload
    // ──────────────────────────────────────────────

    @Test
    fun `WorkItemSchema overload delegates correctly — all notes missing`() {
        val schema = WorkItemSchema(
            type = "feature-task",
            notes = listOf(
                NoteSchemaEntry(key = "spec", role = Role.QUEUE, required = true, guidance = "Write spec"),
                NoteSchemaEntry(key = "design", role = Role.QUEUE, required = true, guidance = "Write design")
            )
        )
        val result = computePhaseNoteContext(Role.QUEUE, schema, emptyMap())

        assertNotNull(result)
        assertEquals("Write spec", result.guidancePointer)
        assertEquals(listOf("spec", "design"), result.missingKeys)
        assertEquals(0, result.filled)
        assertEquals(2, result.remaining)
        assertEquals(2, result.total)
    }

    @Test
    fun `WorkItemSchema overload returns null for terminal role`() {
        val schema = WorkItemSchema(
            type = "feature-task",
            notes = listOf(
                NoteSchemaEntry(key = "spec", role = Role.QUEUE, required = true, guidance = "Write spec")
            )
        )
        val result = computePhaseNoteContext(Role.TERMINAL, schema, emptyMap())
        assertNull(result)
    }

    @Test
    fun `WorkItemSchema overload respects only the current role`() {
        val schema = WorkItemSchema(
            type = "feature-task",
            notes = listOf(
                NoteSchemaEntry(key = "spec", role = Role.QUEUE, required = true, guidance = "Queue guidance"),
                NoteSchemaEntry(key = "impl", role = Role.WORK, required = true, guidance = "Work guidance")
            )
        )
        val result = computePhaseNoteContext(Role.WORK, schema, emptyMap())

        assertNotNull(result)
        assertEquals("Work guidance", result.guidancePointer)
        assertEquals(listOf("impl"), result.missingKeys)
        assertEquals(1, result.total)
    }

    @Test
    fun `WorkItemSchema overload counts filled notes correctly`() {
        val schema = WorkItemSchema(
            type = "feature-task",
            notes = listOf(
                NoteSchemaEntry(key = "spec", role = Role.QUEUE, required = true, guidance = "Write spec"),
                NoteSchemaEntry(key = "risks", role = Role.QUEUE, required = true, guidance = "List risks")
            )
        )
        val notesByKey = mapOf("spec" to note("spec"))
        val result = computePhaseNoteContext(Role.QUEUE, schema, notesByKey)

        assertNotNull(result)
        assertEquals(1, result.filled)
        assertEquals(1, result.remaining)
        assertEquals("List risks", result.guidancePointer)
    }
}
