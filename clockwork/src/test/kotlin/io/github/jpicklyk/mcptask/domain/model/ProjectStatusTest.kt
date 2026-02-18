package io.github.jpicklyk.mcptask.domain.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ProjectStatusTest {

    @Test
    fun `should convert string to ProjectStatus`() {
        // Act & Assert
        assertEquals(ProjectStatus.PLANNING, ProjectStatus.fromString("PLANNING"))
        assertEquals(ProjectStatus.IN_DEVELOPMENT, ProjectStatus.fromString("IN_DEVELOPMENT"))
        assertEquals(ProjectStatus.COMPLETED, ProjectStatus.fromString("COMPLETED"))
        assertEquals(ProjectStatus.ARCHIVED, ProjectStatus.fromString("ARCHIVED"))
    }

    @Test
    fun `should convert case-insensitive string to ProjectStatus`() {
        // Act & Assert
        assertEquals(ProjectStatus.PLANNING, ProjectStatus.fromString("planning"))
        assertEquals(ProjectStatus.IN_DEVELOPMENT, ProjectStatus.fromString("in_development"))
        assertEquals(ProjectStatus.COMPLETED, ProjectStatus.fromString("completed"))
        assertEquals(ProjectStatus.ARCHIVED, ProjectStatus.fromString("archived"))
    }

    @Test
    fun `should convert hyphenated string to ProjectStatus`() {
        // Act & Assert
        assertEquals(ProjectStatus.IN_DEVELOPMENT, ProjectStatus.fromString("in-development"))
    }

    @Test
    fun `should return null for invalid status string`() {
        // Act & Assert
        assertNull(ProjectStatus.fromString("invalid_status"))
        assertNull(ProjectStatus.fromString(""))
    }

    @Test
    fun `should return default status`() {
        // Act & Assert
        assertEquals(ProjectStatus.PLANNING, ProjectStatus.getDefault())
    }
}
