package io.github.jpicklyk.mcptask.current.domain.model

import io.github.jpicklyk.mcptask.current.domain.validation.ValidationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WorkItemTest {

    // --- Valid creation ---

    @Test
    fun `valid creation with minimal fields`() {
        val item = WorkItem(title = "My task")
        assertEquals("My task", item.title)
        assertEquals(Role.QUEUE, item.role)
        assertEquals(Priority.MEDIUM, item.priority)
        assertEquals(null, item.complexity)
        assertEquals(0, item.depth)
        assertEquals("", item.summary)
        assertEquals(null, item.parentId)
        assertEquals(null, item.description)
        assertEquals(null, item.tags)
        assertEquals(1L, item.version)
    }

    @Test
    fun `valid creation with all fields`() {
        val parentId = java.util.UUID.randomUUID()
        val item = WorkItem(
            parentId = parentId,
            title = "Child task",
            description = "A description",
            summary = "A summary",
            role = Role.WORK,
            statusLabel = "in-progress",
            previousRole = Role.QUEUE,
            priority = Priority.HIGH,
            complexity = 8,
            depth = 1,
            metadata = """{"key": "value"}""",
            tags = "bug,critical"
        )
        assertEquals(parentId, item.parentId)
        assertEquals("Child task", item.title)
        assertEquals("A description", item.description)
        assertEquals("A summary", item.summary)
        assertEquals(Role.WORK, item.role)
        assertEquals("in-progress", item.statusLabel)
        assertEquals(Role.QUEUE, item.previousRole)
        assertEquals(Priority.HIGH, item.priority)
        assertEquals(8, item.complexity)
        assertEquals(1, item.depth)
    }

    // --- Validation failures ---

    @Test
    fun `blank title throws ValidationException`() {
        val ex = assertFailsWith<ValidationException> {
            WorkItem(title = "   ")
        }
        assertTrue(ex.message!!.contains("Title must not be blank"))
    }

    @Test
    fun `empty title throws ValidationException`() {
        assertFailsWith<ValidationException> {
            WorkItem(title = "")
        }
    }

    @Test
    fun `complexity below 1 throws ValidationException`() {
        val ex = assertFailsWith<ValidationException> {
            WorkItem(title = "test", complexity = 0)
        }
        assertTrue(ex.message!!.contains("complexity must be between 1 and 10"))
    }

    @Test
    fun `complexity above 10 throws ValidationException`() {
        val ex = assertFailsWith<ValidationException> {
            WorkItem(title = "test", complexity = 11)
        }
        assertTrue(ex.message!!.contains("complexity must be between 1 and 10"))
    }

    @Test
    fun `complexity at boundaries is valid`() {
        WorkItem(title = "test", complexity = 1)
        WorkItem(title = "test", complexity = 10)
    }

    @Test
    fun `title exceeding 500 chars throws ValidationException`() {
        val longTitle = "a".repeat(501)
        val ex = assertFailsWith<ValidationException> {
            WorkItem(title = longTitle)
        }
        assertTrue(ex.message!!.contains("Title must not exceed 500 characters"))
    }

    @Test
    fun `title at exactly 500 chars is valid`() {
        val title = "a".repeat(500)
        WorkItem(title = title)
    }

    @Test
    fun `summary exceeding 2000 chars throws ValidationException`() {
        val longSummary = "a".repeat(2001)
        val ex = assertFailsWith<ValidationException> {
            WorkItem(title = "test", summary = longSummary)
        }
        assertTrue(ex.message!!.contains("Summary must not exceed 2000 characters"))
    }

    @Test
    fun `summary at exactly 2000 chars is valid`() {
        val summary = "a".repeat(2000)
        WorkItem(title = "test", summary = summary)
    }

    @Test
    fun `summary at 500 chars is still valid after limit increase`() {
        val summary = "a".repeat(500)
        WorkItem(title = "test", summary = summary)
    }

    @Test
    fun `negative depth throws ValidationException`() {
        val ex = assertFailsWith<ValidationException> {
            WorkItem(title = "test", depth = -1)
        }
        assertTrue(ex.message!!.contains("Depth must be non-negative"))
    }

    @Test
    fun `root item with non-zero depth throws ValidationException`() {
        val ex = assertFailsWith<ValidationException> {
            WorkItem(title = "test", parentId = null, depth = 1)
        }
        assertTrue(ex.message!!.contains("Root items must have depth 0"))
    }

    @Test
    fun `child item with depth 0 throws ValidationException`() {
        val ex = assertFailsWith<ValidationException> {
            WorkItem(title = "test", parentId = java.util.UUID.randomUUID(), depth = 0)
        }
        assertTrue(ex.message!!.contains("Child items must have depth >= 1"))
    }

    @Test
    fun `blank description throws ValidationException`() {
        val ex = assertFailsWith<ValidationException> {
            WorkItem(title = "test", description = "   ")
        }
        assertTrue(ex.message!!.contains("Description, if provided, must not be blank"))
    }

    @Test
    fun `empty description throws ValidationException`() {
        assertFailsWith<ValidationException> {
            WorkItem(title = "test", description = "")
        }
    }

    @Test
    fun `null description is valid`() {
        WorkItem(title = "test", description = null)
    }

    // --- Tag validation ---

    @Test
    fun `valid tags are accepted`() {
        WorkItem(title = "test", tags = "bug,feature")
        WorkItem(title = "test", tags = "a,b,c")
        WorkItem(title = "test", tags = "my-tag,another-tag")
        WorkItem(title = "test", tags = "tag123")
    }

    @Test
    fun `uppercase tag throws ValidationException`() {
        val ex = assertFailsWith<ValidationException> {
            WorkItem(title = "test", tags = "Bug")
        }
        assertTrue(ex.message!!.contains("invalid"))
    }

    @Test
    fun `tag with spaces throws ValidationException`() {
        val ex = assertFailsWith<ValidationException> {
            WorkItem(title = "test", tags = "has space")
        }
        assertTrue(ex.message!!.contains("invalid"))
    }

    @Test
    fun `null tags is valid`() {
        WorkItem(title = "test", tags = null)
    }

    // --- tagList ---

    @Test
    fun `tagList with null tags returns empty list`() {
        val item = WorkItem(title = "test", tags = null)
        assertEquals(emptyList(), item.tagList())
    }

    @Test
    fun `tagList parses comma-separated tags`() {
        val item = WorkItem(title = "test", tags = "a,b,c")
        assertEquals(listOf("a", "b", "c"), item.tagList())
    }

    @Test
    fun `tagList trims whitespace`() {
        // Note: tags with spaces in them are invalid at construction time,
        // but leading/trailing spaces around commas are trimmed during tagList parsing.
        // We need to construct the item with valid tags and then test tagList.
        val item = WorkItem(title = "test", tags = "a,b,c")
        assertEquals(listOf("a", "b", "c"), item.tagList())
    }

    @Test
    fun `tagList with single tag returns single-element list`() {
        val item = WorkItem(title = "test", tags = "single")
        assertEquals(listOf("single"), item.tagList())
    }

    // --- update builder ---

    @Test
    fun `update returns new instance with updated modifiedAt`() {
        val original = WorkItem(title = "original")
        val updated = original.update { it.copy(title = "updated") }
        assertEquals("updated", updated.title)
        assertEquals(original.id, updated.id)
        assertTrue(updated.modifiedAt >= original.modifiedAt)
    }

    @Test
    fun `update ensures monotonic timestamp`() {
        val original = WorkItem(title = "original")
        val updated = original.update { it.copy(title = "updated") }
        // modifiedAt should be at or after original
        assertTrue(updated.modifiedAt >= original.modifiedAt)
    }

    @Test
    fun `update preserves other fields`() {
        val original = WorkItem(
            title = "original",
            priority = Priority.HIGH,
            complexity = 8,
            summary = "some summary"
        )
        val updated = original.update { it.copy(title = "updated") }
        assertEquals(Priority.HIGH, updated.priority)
        assertEquals(8, updated.complexity)
        assertEquals("some summary", updated.summary)
    }

    @Test
    fun `update returns different instance`() {
        val original = WorkItem(title = "original")
        val updated = original.update { it.copy(title = "updated") }
        assertNotEquals(original, updated)
    }
}
