package io.github.jpicklyk.mcptask.current.domain.model

import io.github.jpicklyk.mcptask.current.domain.validation.ValidationException
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NoteTest {

    private val testItemId = UUID.randomUUID()

    // --- Valid creation ---

    @Test
    fun `valid creation with role queue`() {
        val note = Note(itemId = testItemId, key = "requirements", role = "queue")
        assertEquals(testItemId, note.itemId)
        assertEquals("requirements", note.key)
        assertEquals("queue", note.role)
        assertEquals("", note.body)
    }

    @Test
    fun `valid creation with role work`() {
        val note = Note(itemId = testItemId, key = "implementation", role = "work", body = "some notes")
        assertEquals("work", note.role)
        assertEquals("some notes", note.body)
    }

    @Test
    fun `valid creation with role review`() {
        val note = Note(itemId = testItemId, key = "review-checklist", role = "review")
        assertEquals("review", note.role)
    }

    @Test
    fun `valid creation preserves all fields`() {
        val id = UUID.randomUUID()
        val note = Note(
            id = id,
            itemId = testItemId,
            key = "my-key",
            role = "work",
            body = "my body"
        )
        assertEquals(id, note.id)
        assertEquals(testItemId, note.itemId)
        assertEquals("my-key", note.key)
        assertEquals("work", note.role)
        assertEquals("my body", note.body)
    }

    // --- Validation failures ---

    @Test
    fun `blank key throws ValidationException`() {
        val ex = assertFailsWith<ValidationException> {
            Note(itemId = testItemId, key = "   ", role = "queue")
        }
        assertTrue(ex.message!!.contains("key must not be blank"))
    }

    @Test
    fun `empty key throws ValidationException`() {
        assertFailsWith<ValidationException> {
            Note(itemId = testItemId, key = "", role = "queue")
        }
    }

    @Test
    fun `key exceeding 200 chars throws ValidationException`() {
        val longKey = "a".repeat(201)
        val ex = assertFailsWith<ValidationException> {
            Note(itemId = testItemId, key = longKey, role = "queue")
        }
        assertTrue(ex.message!!.contains("key must not exceed 200 characters"))
    }

    @Test
    fun `key at exactly 200 chars is valid`() {
        val key = "a".repeat(200)
        Note(itemId = testItemId, key = key, role = "queue")
    }

    @Test
    fun `role terminal throws ValidationException`() {
        val ex = assertFailsWith<ValidationException> {
            Note(itemId = testItemId, key = "test", role = "terminal")
        }
        assertTrue(ex.message!!.contains("Note role must be one of"))
    }

    @Test
    fun `role blocked throws ValidationException`() {
        val ex = assertFailsWith<ValidationException> {
            Note(itemId = testItemId, key = "test", role = "blocked")
        }
        assertTrue(ex.message!!.contains("Note role must be one of"))
    }

    @Test
    fun `empty role throws ValidationException`() {
        val ex = assertFailsWith<ValidationException> {
            Note(itemId = testItemId, key = "test", role = "")
        }
        assertTrue(ex.message!!.contains("Note role must be one of"))
    }

    @Test
    fun `invalid role string throws ValidationException`() {
        assertFailsWith<ValidationException> {
            Note(itemId = testItemId, key = "test", role = "invalid")
        }
    }
}
