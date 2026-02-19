package io.github.jpicklyk.mcptask.domain.model

import io.github.jpicklyk.mcptask.domain.validation.ValidationException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.*

class FeatureTest {

    @Test
    fun `should create valid feature`() {
        val feature = Feature(
            name = "Authentication System",
            summary = "Implement user authentication",
            status = FeatureStatus.PLANNING,
            priority = Priority.HIGH
        )

        assertEquals("Authentication System", feature.name)
        assertEquals("Implement user authentication", feature.summary)
        assertEquals(FeatureStatus.PLANNING, feature.status)
        assertEquals(Priority.HIGH, feature.priority)
        assertNull(feature.projectId, "ProjectId should be null by default")
    }

    @Test
    fun `should create valid feature with projectId`() {
        val projectId = UUID.randomUUID()
        val feature = Feature(
            name = "Authentication System",
            summary = "Implement user authentication",
            projectId = projectId,
            status = FeatureStatus.PLANNING,
            priority = Priority.HIGH
        )

        assertEquals("Authentication System", feature.name)
        assertEquals("Implement user authentication", feature.summary)
        assertEquals(FeatureStatus.PLANNING, feature.status)
        assertEquals(Priority.HIGH, feature.priority)
        assertEquals(projectId, feature.projectId, "ProjectId should match the provided value")
    }

    @Test
    fun `should throw exception when creating feature with empty name`() {
        val exception = assertThrows<ValidationException> {
            Feature(
                name = "",
                summary = "Some summary"
            )
        }
        assertTrue(exception.message!!.contains("name cannot be empty"))
    }

    @Test
    fun `should allow empty summary for agent-generated content`() {
        // Summary is agent-generated and defaults to empty
        val feature = Feature(
            name = "Valid Name",
            summary = ""
        )
        assertEquals("", feature.summary)
    }

    @Test
    fun `should update feature correctly`() {
        val original = Feature(
            name = "Original Feature",
            summary = "Original summary",
            status = FeatureStatus.PLANNING,
            priority = Priority.MEDIUM
        )

        // Adding a small delay to ensure timestamps are different
        Thread.sleep(10)

        val updated = original.update(
            name = "Updated Feature",
            summary = "Updated summary",
            status = FeatureStatus.IN_DEVELOPMENT,
            priority = Priority.HIGH
        )

        assertEquals("Updated Feature", updated.name)
        assertEquals("Updated summary", updated.summary)
        assertEquals(FeatureStatus.IN_DEVELOPMENT, updated.status)
        assertEquals(Priority.HIGH, updated.priority)

        // modifiedAt should be updated
        assertTrue(
            updated.modifiedAt.isAfter(original.modifiedAt),
            "Modified timestamp should be updated: original=${original.modifiedAt}, updated=${updated.modifiedAt}"
        )
        
        // Other fields should remain the same
        assertEquals(original.id, updated.id)
        assertEquals(original.createdAt, updated.createdAt)
        assertEquals(original.projectId, updated.projectId, "ProjectId should remain unchanged")
    }

    @Test
    fun `should update feature projectId correctly`() {
        val original = Feature(
            name = "Original Feature",
            summary = "Original summary",
            projectId = null
        )

        val projectId = UUID.randomUUID()
        val updated = original.update(
            projectId = projectId
        )

        assertEquals(original.name, updated.name, "Name should remain unchanged")
        assertEquals(original.summary, updated.summary, "Summary should remain unchanged")
        assertEquals(original.status, updated.status, "Status should remain unchanged")
        assertEquals(original.priority, updated.priority, "Priority should remain unchanged")
        assertEquals(projectId, updated.projectId, "ProjectId should be updated")

        // modifiedAt should be updated
        assertTrue(
            updated.modifiedAt.isAfter(original.modifiedAt),
            "Modified timestamp should be updated"
        )
    }

    @Test
    fun `should remove feature from project by setting projectId to null`() {
        val projectId = UUID.randomUUID()
        val original = Feature(
            name = "Original Feature",
            summary = "Original summary",
            projectId = projectId
        )

        val updated = original.update(
            projectId = null
        )

        assertEquals(original.name, updated.name, "Name should remain unchanged")
        assertEquals(original.summary, updated.summary, "Summary should remain unchanged")
        assertNull(updated.projectId, "ProjectId should be set to null")
    }
}
