package io.github.jpicklyk.mcptask.domain.model

import java.time.Instant
import java.util.*

/**
 * Represents a template for creating structured content sections in tasks or features.
 * Templates provide a reusable pattern for consistently organizing entity documentation.
 */
data class Template(
    val id: UUID = UUID.randomUUID(),
    val name: String,
    val description: String,
    val targetEntityType: EntityType, // TASK or FEATURE
    val isBuiltIn: Boolean = false,
    val isProtected: Boolean = false,
    val isEnabled: Boolean = true,
    val createdBy: String? = null,
    val tags: List<String> = emptyList(),
    val createdAt: Instant = Instant.now(),
    val modifiedAt: Instant = Instant.now()
) {
    /**
     * Validates the template data for consistency and completeness.
     * @throws IllegalArgumentException if validation fails
     */
    fun validate() {
        require(name.isNotBlank()) { "Name must not be empty" }
        require(description.isNotBlank()) { "Description must not be empty" }
    }

    /**
     * Creates a copy of this template with updated modification time.
     * Use this when updating a template to ensure modification time is tracked.
     */
    fun withUpdatedModificationTime(): Template {
        return this.copy(modifiedAt = Instant.now())
    }

    /**
     * Creates a copy of this template with a new id.
     * Useful for creating derivations of built-in templates.
     */
    fun duplicate(newName: String = "Copy of $name", createdBy: String? = null): Template {
        return this.copy(
            id = UUID.randomUUID(),
            name = newName,
            isBuiltIn = false,
            isProtected = false,
            createdBy = createdBy,
            createdAt = Instant.now(),
            modifiedAt = Instant.now()
        )
    }
}

/**
 * Represents a template section definition within a template.
 * This defines the structure of sections that will be created when the template is applied.
 */
data class TemplateSection(
    val id: UUID = UUID.randomUUID(),
    val templateId: UUID,
    val title: String,
    val usageDescription: String,
    val contentSample: String,
    val contentFormat: ContentFormat = ContentFormat.MARKDOWN,
    val ordinal: Int,
    val isRequired: Boolean = false,
    val tags: List<String> = emptyList()
) {
    /**
     * Validates the template section data for consistency and completeness.
     * @throws IllegalArgumentException if validation fails
     */
    fun validate() {
        require(title.isNotBlank()) { "Title must not be empty" }
        require(usageDescription.isNotBlank()) { "Usage description must not be empty" }
        require(ordinal >= 0) { "Ordinal must be a non-negative integer" }
    }

    /**
     * Converts this template section to a regular Section that can be applied to an entity.
     * @param entityType The type of entity this section will be applied to
     * @param entityId The ID of the entity this section will be applied to
     * @param ordinalOffset Offset to add to the template's ordinal to avoid conflicts
     * @return A new Section instance ready to be persisted
     */
    fun toSection(entityType: EntityType, entityId: UUID, ordinalOffset: Int = 0): Section {
        return Section(
            id = UUID.randomUUID(),
            entityType = entityType,
            entityId = entityId,
            title = title,
            usageDescription = usageDescription,
            content = contentSample,
            contentFormat = contentFormat,
            ordinal = ordinal + ordinalOffset,
            tags = tags
        )
    }
}
