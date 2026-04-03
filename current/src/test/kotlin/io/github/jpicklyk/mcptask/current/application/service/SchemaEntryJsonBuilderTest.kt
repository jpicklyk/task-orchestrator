package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.Role
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class SchemaEntryJsonBuilderTest {
    // ──────────────────────────────────────────────
    // buildExpectedNotesJson
    // ──────────────────────────────────────────────

    @Test
    fun `null schema returns empty array`() {
        val result = buildExpectedNotesJson(schema = null)
        assertEquals(0, result.size)
    }

    @Test
    fun `empty schema list returns empty array`() {
        val result = buildExpectedNotesJson(schema = emptyList())
        assertEquals(0, result.size)
    }

    @Test
    fun `schema with entries and no existing notes has all exists false`() {
        val schema =
            listOf(
                NoteSchemaEntry(key = "spec", role = Role.QUEUE, required = true, description = "Spec desc"),
                NoteSchemaEntry(key = "impl", role = Role.WORK, required = false, description = "Impl desc")
            )

        val result = buildExpectedNotesJson(schema = schema)

        assertEquals(2, result.size)
        val first = result[0].jsonObject
        assertEquals("spec", first["key"]!!.jsonPrimitive.content)
        assertEquals("queue", first["role"]!!.jsonPrimitive.content)
        assertTrue(first["required"]!!.jsonPrimitive.boolean)
        assertEquals("Spec desc", first["description"]!!.jsonPrimitive.content)
        assertFalse(first["exists"]!!.jsonPrimitive.boolean)
        assertFalse(first.containsKey("filled"), "filled should be absent when filledNoteKeys is null")

        val second = result[1].jsonObject
        assertEquals("impl", second["key"]!!.jsonPrimitive.content)
        assertFalse(second["exists"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `schema with existingNoteKeys reflects exists state correctly`() {
        val schema =
            listOf(
                NoteSchemaEntry(key = "spec", role = Role.QUEUE, required = true, description = "Spec"),
                NoteSchemaEntry(key = "impl", role = Role.WORK, required = false, description = "Impl")
            )

        val result =
            buildExpectedNotesJson(
                schema = schema,
                existingNoteKeys = setOf("spec")
            )

        assertEquals(2, result.size)
        val specEntry = result[0].jsonObject
        assertTrue(specEntry["exists"]!!.jsonPrimitive.boolean, "spec exists should be true")

        val implEntry = result[1].jsonObject
        assertFalse(implEntry["exists"]!!.jsonPrimitive.boolean, "impl exists should be false")
    }

    @Test
    fun `filledNoteKeys adds filled field and reflects state correctly`() {
        val schema =
            listOf(
                NoteSchemaEntry(key = "spec", role = Role.QUEUE, required = true, description = "Spec"),
                NoteSchemaEntry(key = "impl", role = Role.WORK, required = false, description = "Impl")
            )

        val result =
            buildExpectedNotesJson(
                schema = schema,
                existingNoteKeys = setOf("spec", "impl"),
                filledNoteKeys = setOf("spec")
            )

        assertEquals(2, result.size)
        val specEntry = result[0].jsonObject
        assertTrue(specEntry.containsKey("filled"), "filled field should be present when filledNoteKeys is non-null")
        assertTrue(specEntry["filled"]!!.jsonPrimitive.boolean, "spec filled should be true")
        assertTrue(specEntry["exists"]!!.jsonPrimitive.boolean)

        val implEntry = result[1].jsonObject
        assertTrue(implEntry.containsKey("filled"), "filled field should be present for all entries when filledNoteKeys is non-null")
        assertFalse(implEntry["filled"]!!.jsonPrimitive.boolean, "impl filled should be false")
        assertTrue(implEntry["exists"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `filterRole only includes entries matching the specified role`() {
        val schema =
            listOf(
                NoteSchemaEntry(key = "spec", role = Role.QUEUE, required = true, description = "Spec"),
                NoteSchemaEntry(key = "impl", role = Role.WORK, required = false, description = "Impl"),
                NoteSchemaEntry(key = "review-notes", role = Role.REVIEW, required = true, description = "Review")
            )

        val result =
            buildExpectedNotesJson(
                schema = schema,
                filterRole = Role.WORK
            )

        assertEquals(1, result.size)
        val entry = result[0].jsonObject
        assertEquals("impl", entry["key"]!!.jsonPrimitive.content)
        assertEquals("work", entry["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun `filterRole with no matching entries returns empty array`() {
        val schema =
            listOf(
                NoteSchemaEntry(key = "spec", role = Role.QUEUE, required = true, description = "Spec")
            )

        val result =
            buildExpectedNotesJson(
                schema = schema,
                filterRole = Role.REVIEW
            )

        assertEquals(0, result.size)
    }

    @Test
    fun `entry with null guidance omits guidance field`() {
        val schema =
            listOf(
                NoteSchemaEntry(key = "spec", role = Role.QUEUE, required = true, description = "Spec", guidance = null)
            )

        val result = buildExpectedNotesJson(schema = schema)

        assertEquals(1, result.size)
        val entry = result[0].jsonObject
        assertFalse(entry.containsKey("guidance"), "guidance field should be absent when null")
    }

    @Test
    fun `entry with non-null guidance includes guidance field`() {
        val schema =
            listOf(
                NoteSchemaEntry(key = "spec", role = Role.QUEUE, required = true, description = "Spec", guidance = "Do it this way")
            )

        val result = buildExpectedNotesJson(schema = schema)

        assertEquals(1, result.size)
        val entry = result[0].jsonObject
        assertTrue(entry.containsKey("guidance"), "guidance field should be present when non-null")
        assertEquals("Do it this way", entry["guidance"]!!.jsonPrimitive.content)
    }

    @Test
    fun `null filledNoteKeys means no filled field on any entry`() {
        val schema =
            listOf(
                NoteSchemaEntry(key = "spec", role = Role.QUEUE, required = true, description = "Spec")
            )

        val result =
            buildExpectedNotesJson(
                schema = schema,
                existingNoteKeys = setOf("spec"),
                filledNoteKeys = null
            )

        assertEquals(1, result.size)
        val entry = result[0].jsonObject
        assertFalse(entry.containsKey("filled"), "filled should be absent when filledNoteKeys is null")
        assertTrue(entry["exists"]!!.jsonPrimitive.boolean)
    }

    // ──────────────────────────────────────────────
    // buildSchemaResponseFields
    // ──────────────────────────────────────────────

    @Test
    fun `buildSchemaResponseFields with null schema returns schemaMatch false and empty array`() {
        val result = buildSchemaResponseFields(schema = null)

        assertFalse(result.schemaMatch)
        assertEquals(0, result.expectedNotes.size)
    }

    @Test
    fun `buildSchemaResponseFields with non-null schema returns schemaMatch true`() {
        val schema =
            listOf(
                NoteSchemaEntry(key = "spec", role = Role.QUEUE, required = true, description = "Spec")
            )

        val result = buildSchemaResponseFields(schema = schema)

        assertTrue(result.schemaMatch)
        assertEquals(1, result.expectedNotes.size)
    }

    @Test
    fun `buildSchemaResponseFields with empty schema list returns schemaMatch true and empty array`() {
        val result = buildSchemaResponseFields(schema = emptyList())

        assertTrue(result.schemaMatch, "schemaMatch should be true when schema is non-null (even if empty)")
        assertEquals(0, result.expectedNotes.size)
    }

    @Test
    fun `buildSchemaResponseFields entries have exists false`() {
        val schema =
            listOf(
                NoteSchemaEntry(key = "spec", role = Role.QUEUE, required = true, description = "Spec"),
                NoteSchemaEntry(key = "impl", role = Role.WORK, required = false, description = "Impl")
            )

        val result = buildSchemaResponseFields(schema = schema)

        assertEquals(2, result.expectedNotes.size)
        for (element in result.expectedNotes) {
            val entry = element.jsonObject
            assertFalse(entry["exists"]!!.jsonPrimitive.boolean, "exists should always be false in creation shape")
            assertFalse(entry.containsKey("filled"), "filled should be absent in creation shape")
        }
    }
}
