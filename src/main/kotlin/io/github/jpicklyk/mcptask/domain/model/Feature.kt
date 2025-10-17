package io.github.jpicklyk.mcptask.domain.model

import io.github.jpicklyk.mcptask.domain.validation.ValidationException
import java.time.Instant
import java.util.*

/**
 * Feature entity representing an optional organizational grouping for tasks.
 * Features can optionally belong to a Project container.
 * Detailed content is stored in separate Section entities for context efficiency.
 */
data class Feature(
    val id: UUID = UUID.randomUUID(),
    val projectId: UUID? = null, // Project association (optional)
    val name: String,
    /** Optional detailed description provided by user */
    val description: String? = null,
    /** Brief summary of the feature (agent-generated, max 500 chars) */
    val summary: String = "",
    val status: FeatureStatus = FeatureStatus.PLANNING,
    val priority: Priority = Priority.MEDIUM,
    val createdAt: Instant = Instant.now(),
    val modifiedAt: Instant = Instant.now(),
    val tags: List<String> = emptyList(),
    /** Optimistic concurrency version */
    val version: Long = 1
) {
    init {
        validate()
    }

    /**
     * Validates the feature entity.
     * @throws ValidationException if validation fails
     */
    fun validate() {
        if (name.isBlank()) {
            throw ValidationException("Feature name cannot be empty")
        }

        if (summary.length > 500) {
            throw ValidationException("Feature summary must not exceed 500 characters")
        }

        // Description cannot be blank if provided
        description?.let {
            if (it.isBlank()) {
                throw ValidationException("Feature description must not be blank if provided")
            }
        }
    }

    /**
     * Updates modifiable fields in the feature.
     */
    fun update(
        name: String = this.name,
        projectId: UUID? = this.projectId,
        description: String? = this.description,
        summary: String = this.summary,
        status: FeatureStatus = this.status,
        priority: Priority = this.priority,
        tags: List<String> = this.tags
    ): Feature {
        // Ensure we always generate a new timestamp that's definitely after the current modifiedAt
        // by adding at least 1 millisecond
        val newModifiedAt = Instant.now().plusMillis(1).coerceAtLeast(modifiedAt.plusMillis(1))

        return copy(
            name = name,
            projectId = projectId,
            description = description,
            summary = summary,
            status = status,
            priority = priority,
            modifiedAt = newModifiedAt,
            tags = tags
        ).also { it.validate() }
    }
}

/**
 * Enum representing the possible statuses of a feature.
 */
enum class FeatureStatus {
    PLANNING,
    IN_DEVELOPMENT,
    COMPLETED,
    ARCHIVED;

    companion object {
        fun fromString(value: String): FeatureStatus = try {
            valueOf(value.uppercase().replace('-', '_'))
        } catch (_: IllegalArgumentException) {
            throw ValidationException("Invalid feature status: $value")
        }
    }
}
