package io.github.jpicklyk.mcptask.domain.model

import java.time.Instant
import java.util.*

/**
 * Represents a content section that can be attached to Features or Tasks.
 * Provides flexible, structured content organization.
 */
data class Section(
    val id: UUID = UUID.randomUUID(),
    val entityType: EntityType,
    val entityId: UUID,
    val title: String,
    val usageDescription: String,
    val content: String,
    val contentFormat: ContentFormat = ContentFormat.MARKDOWN,
    val ordinal: Int,
    val tags: List<String> = emptyList(),
    val createdAt: Instant = Instant.now(),
    val modifiedAt: Instant = Instant.now(),
    /** Optimistic concurrency version */
    val version: Long = 1
) {
    /**
     * Validates the section data for consistency and completeness.
     * @throws io.github.jpicklyk.mcptask.domain.validation.ValidationException if validation fails
     */
    fun validate() {
        require(title.isNotBlank()) { "Title must not be empty" }
        require(usageDescription.isNotBlank()) { "Usage description must not be empty" }
        require(ordinal >= 0) { "Ordinal must be a non-negative integer" }
    }

    /**
     * Creates a copy of this section with updated modification time.
     * Use this when updating a section to ensure modification time is tracked.
     */
    fun withUpdatedModificationTime(): Section {
        return this.copy(
            modifiedAt = Instant.now()
        )
    }
}

/**
 * Defines the format of section content for proper interpretation.
 */
enum class ContentFormat {
    PLAIN_TEXT,
    MARKDOWN,
    JSON,
    CODE
}

/**
 * Entity types that can have sections.
 */
enum class EntityType {
    PROJECT,
    FEATURE,
    TASK,
    TEMPLATE,
    SECTION
}
