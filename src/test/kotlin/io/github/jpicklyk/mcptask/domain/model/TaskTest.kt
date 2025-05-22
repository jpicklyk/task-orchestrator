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
    fun `create task fails with empty summary`() {
        // When & Then
        assertThrows<IllegalArgumentException> {
            Task.create {
                it.copy(
                    title = "Task with no summary",
                    summary = ""
                )
            }
        }
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

        // When & Then - Empty summary
        assertThrows<IllegalArgumentException> {
            originalTask.update {
                it.copy(summary = "")
            }
        }
        
        // When & Then - Invalid complexity
        assertThrows<IllegalArgumentException> {
            originalTask.update {
                it.copy(complexity = 0)
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
}