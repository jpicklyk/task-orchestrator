package io.github.jpicklyk.mcptask.domain.model

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskTest {

    @Test
    fun `create task with valid data`() {
        // Given
        val title = "Implement Task entity model"
        val summary = "Create the Task data class with all required properties"
        val complexity = 5
        
        // When
        val task = Task.create {
            it.copy(
                title = title,
                summary = summary,
                complexity = complexity
            )
        }
        
        // Then
        assertEquals(title, task.title)
        assertEquals(summary, task.summary)
        assertEquals(complexity, task.complexity)
        assertEquals(TaskStatus.PENDING, task.status) // Default status
        assertEquals(Priority.MEDIUM, task.priority) // Default priority
        assertNull(task.projectId) // Default projectId should be null
        assertNull(task.featureId) // Default featureId should be null
    }

    @Test
    fun `create task with project ID`() {
        // Given
        val projectId = UUID.randomUUID()

        // When
        val task = Task.create {
            it.copy(
                title = "Task with project association",
                summary = "This task belongs to a project",
                projectId = projectId
            )
        }

        // Then
        assertEquals(projectId, task.projectId)
        assertNull(task.featureId)
    }

    @Test
    fun `create task with both project ID and feature ID`() {
        // Given
        val projectId = UUID.randomUUID()
        val featureId = UUID.randomUUID()

        // When
        val task = Task.create {
            it.copy(
                title = "Task with both associations",
                summary = "This task belongs to a project and feature",
                projectId = projectId,
                featureId = featureId
            )
        }

        // Then
        assertEquals(projectId, task.projectId)
        assertEquals(featureId, task.featureId)
    }
    
    @Test
    fun `create task fails with empty title`() {
        // When & Then
        assertThrows<IllegalArgumentException> {
            Task.create {
                it.copy(
                    title = "",
                    summary = "This task has no title"
                )
            }
        }
    }

    @Test
    fun `create task with empty summary is allowed`() {
        // Given - summary defaults to empty string and is allowed
        val title = "Task with empty summary"

        // When
        val task = Task.create {
            it.copy(
                title = title,
                summary = ""
            )
        }

        // Then
        assertEquals("", task.summary)
        assertNull(task.description)
    }
    
    @Test
    fun `create task fails with invalid complexity`() {
        // When & Then - Complexity below range
        assertThrows<IllegalArgumentException> {
            Task.create {
                it.copy(
                    title = "Invalid complexity task",
                    summary = "Testing invalid complexity",
                    complexity = 0 // Below valid range (1-10)
                )
            }
        }
        
        // When & Then - Complexity above range
        assertThrows<IllegalArgumentException> {
            Task.create {
                it.copy(
                    title = "Invalid complexity task",
                    summary = "Testing invalid complexity",
                    complexity = 11 // Above valid range (1-10)
                )
            }
        }
    }

    @Test
    fun `update task properly changes fields`() {
        // Given
        val originalTask = Task.create {
            it.copy(
                title = "Original title",
                summary = "Original summary",
                status = TaskStatus.PENDING,
                priority = Priority.LOW
            )
        }
        val originalModifiedTime = originalTask.modifiedAt
        
        // When
        val updatedTask = originalTask.update {
            it.copy(
                title = "Updated title",
                status = TaskStatus.IN_PROGRESS,
                priority = Priority.HIGH
            )
        }
        
        // Then
        assertEquals("Updated title", updatedTask.title)
        assertEquals("Original summary", updatedTask.summary) // Unchanged
        assertEquals(TaskStatus.IN_PROGRESS, updatedTask.status)
        assertEquals(Priority.HIGH, updatedTask.priority)
        assertEquals(originalTask.id, updatedTask.id) // ID should not change
        assertEquals(originalTask.createdAt, updatedTask.createdAt) // Creation time should not change

        // Check that modified time has changed - use isAfter to avoid timing issues
        assertTrue(
            updatedTask.modifiedAt.isAfter(originalModifiedTime),
            "Updated timestamp should be after original timestamp"
        )
    }

    @Test
    fun `update task with projectId`() {
        // Given
        val originalTask = Task.create {
            it.copy(
                title = "Original task",
                summary = "Task without project",
                projectId = null
            )
        }
        val projectId = UUID.randomUUID()

        // When
        val updatedTask = originalTask.update {
            it.copy(
                projectId = projectId
            )
        }

        // Then
        assertEquals(projectId, updatedTask.projectId)
        assertEquals(originalTask.title, updatedTask.title) // Unchanged
        assertEquals(originalTask.summary, updatedTask.summary) // Unchanged
    }

    @Test
    fun `update task to remove projectId`() {
        // Given
        val projectId = UUID.randomUUID()
        val originalTask = Task.create {
            it.copy(
                title = "Original task",
                summary = "Task with project",
                projectId = projectId
            )
        }

        // When
        val updatedTask = originalTask.update {
            it.copy(
                projectId = null
            )
        }

        // Then
        assertNull(updatedTask.projectId)
        assertEquals(originalTask.title, updatedTask.title) // Unchanged
        assertEquals(originalTask.summary, updatedTask.summary) // Unchanged
    }
    
    @Test
    fun `update task fails with invalid data`() {
        // Given
        val originalTask = Task.create {
            it.copy(
                title = "Original title",
                summary = "Original summary",
                complexity = 5
            )
        }
        
        // When & Then - Empty title
        assertThrows<IllegalArgumentException> {
            originalTask.update {
                it.copy(title = "")
            }
        }

        // When & Then - Invalid complexity
        assertThrows<IllegalArgumentException> {
            originalTask.update {
                it.copy(complexity = 0)
            }
        }

        // When & Then - Summary exceeding max length
        assertThrows<IllegalArgumentException> {
            originalTask.update {
                it.copy(summary = "x".repeat(501))
            }
        }
    }
    
    @Test
    fun `task with tags is created correctly`() {
        // Given
        val tags = listOf("kotlin", "entity", "model")

        // When
        val task = Task.create {
            it.copy(
                title = "Implement Task entity with tags",
                summary = "A task with tags for testing",
                tags = tags
            )
        }

        // Then
        assertEquals(tags, task.tags)
        assertEquals(3, task.tags.size)
    }

    // ========== Description Field Tests ==========

    @Test
    fun `create task with nullable description`() {
        // Given
        val title = "Task without description"
        val summary = "Task summary"

        // When
        val task = Task.create {
            it.copy(
                title = title,
                summary = summary,
                description = null
            )
        }

        // Then
        assertEquals(title, task.title)
        assertEquals(summary, task.summary)
        assertNull(task.description)
    }

    @Test
    fun `create task with description`() {
        // Given
        val title = "Task with description"
        val summary = "Task summary"
        val description = "Detailed description of what needs to be done"

        // When
        val task = Task.create {
            it.copy(
                title = title,
                summary = summary,
                description = description
            )
        }

        // Then
        assertEquals(title, task.title)
        assertEquals(summary, task.summary)
        assertEquals(description, task.description)
    }

    @Test
    fun `create task with description but empty summary`() {
        // Given - description provided but summary empty
        val description = "This is what needs to be done"

        // When
        val task = Task.create {
            it.copy(
                title = "Task title",
                summary = "",
                description = description
            )
        }

        // Then
        assertEquals("", task.summary)
        assertEquals(description, task.description)
    }

    @Test
    fun `create task fails with blank description`() {
        // When & Then - Blank description should fail validation
        assertThrows<IllegalArgumentException> {
            Task.create {
                it.copy(
                    title = "Task title",
                    summary = "Task summary",
                    description = "   " // Only whitespace
                )
            }
        }
    }

    @Test
    fun `create task fails with empty string description`() {
        // When & Then - Empty string description should fail validation
        assertThrows<IllegalArgumentException> {
            Task.create {
                it.copy(
                    title = "Task title",
                    summary = "Task summary",
                    description = "" // Empty string
                )
            }
        }
    }

    @Test
    fun `update task can set description from null to value`() {
        // Given
        val originalTask = Task.create {
            it.copy(
                title = "Original task",
                summary = "Original summary",
                description = null
            )
        }

        val newDescription = "Added description after creation"

        // When
        val updatedTask = originalTask.update {
            it.copy(description = newDescription)
        }

        // Then
        assertEquals(newDescription, updatedTask.description)
        assertEquals(originalTask.summary, updatedTask.summary) // Summary unchanged
    }

    @Test
    fun `update task can change description`() {
        // Given
        val originalTask = Task.create {
            it.copy(
                title = "Original task",
                summary = "Original summary",
                description = "Original description"
            )
        }

        val newDescription = "Updated description"

        // When
        val updatedTask = originalTask.update {
            it.copy(description = newDescription)
        }

        // Then
        assertEquals(newDescription, updatedTask.description)
        assertEquals(originalTask.summary, updatedTask.summary)
    }

    @Test
    fun `update task can remove description`() {
        // Given
        val originalTask = Task.create {
            it.copy(
                title = "Original task",
                summary = "Original summary",
                description = "Original description"
            )
        }

        // When
        val updatedTask = originalTask.update {
            it.copy(description = null)
        }

        // Then
        assertNull(updatedTask.description)
        assertEquals(originalTask.summary, updatedTask.summary)
    }

    @Test
    fun `update task fails with blank description`() {
        // Given
        val originalTask = Task.create {
            it.copy(
                title = "Original task",
                summary = "Original summary",
                description = "Original description"
            )
        }

        // When & Then
        assertThrows<IllegalArgumentException> {
            originalTask.update {
                it.copy(description = "   ")
            }
        }
    }

    @Test
    fun `update task can modify summary independently of description`() {
        // Given
        val originalTask = Task.create {
            it.copy(
                title = "Original task",
                summary = "Original summary",
                description = "Original description"
            )
        }

        val newSummary = "Updated summary - what was accomplished"

        // When
        val updatedTask = originalTask.update {
            it.copy(summary = newSummary)
        }

        // Then
        assertEquals(newSummary, updatedTask.summary)
        assertEquals("Original description", updatedTask.description) // Description unchanged
    }

    @Test
    fun `create task with summary exceeding max length fails`() {
        // Given - Summary longer than 500 characters
        val longSummary = "x".repeat(501)

        // When & Then
        assertThrows<IllegalArgumentException> {
            Task.create {
                it.copy(
                    title = "Task with long summary",
                    summary = longSummary
                )
            }
        }
    }

    @Test
    fun `create task with summary at max length succeeds`() {
        // Given - Summary exactly 500 characters
        val maxLengthSummary = "x".repeat(500)

        // When
        val task = Task.create {
            it.copy(
                title = "Task with max summary",
                summary = maxLengthSummary
            )
        }

        // Then
        assertEquals(500, task.summary.length)
    }

    @Test
    fun `description has no length limit`() {
        // Given - Very long description (no limit specified)
        val veryLongDescription = "x".repeat(10000)

        // When
        val task = Task.create {
            it.copy(
                title = "Task with long description",
                summary = "Brief summary",
                description = veryLongDescription
            )
        }

        // Then
        assertEquals(10000, task.description?.length)
    }

    @Test
    fun `both description and summary can be set together`() {
        // Given - Both fields provided
        val description = "This is what needs to be done - the user-provided intent"
        val summary = "This is what was accomplished - the agent-generated result"

        // When
        val task = Task.create {
            it.copy(
                title = "Task with both fields",
                description = description,
                summary = summary
            )
        }

        // Then
        assertEquals(description, task.description)
        assertEquals(summary, task.summary)
    }
}