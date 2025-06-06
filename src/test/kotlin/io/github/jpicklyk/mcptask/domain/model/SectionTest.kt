package io.github.jpicklyk.mcptask.domain.model

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class SectionTest {

    @Test
    fun `validate should pass for valid section`() {
        // Arrange
        val section = Section(
            entityType = EntityType.TASK,
            entityId = UUID.randomUUID(),
            title = "Implementation Details",
            usageDescription = "Technical implementation notes for developers",
            content = "Implementation content",
            contentFormat = ContentFormat.MARKDOWN,
            ordinal = 1,
            tags = listOf("technical", "implementation")
        )

        // Act & Assert
        assertDoesNotThrow { section.validate() }
    }

    @Test
    fun `validate should throw exception for empty title`() {
        // Arrange
        val section = Section(
            entityType = EntityType.TASK,
            entityId = UUID.randomUUID(),
            title = "",
            usageDescription = "Technical implementation notes",
            content = "Content",
            contentFormat = ContentFormat.MARKDOWN,
            ordinal = 1
        )

        // Act & Assert
        val exception = assertThrows(IllegalArgumentException::class.java) {
            section.validate()
        }
        assertTrue(exception.message?.contains("Title must not be empty") == true)
    }
}
