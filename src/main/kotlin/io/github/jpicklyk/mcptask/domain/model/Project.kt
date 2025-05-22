package io.github.jpicklyk.mcptask.domain.model

import java.time.Instant
import java.util.*

/**
 * Represents a project entity, which is the top-level container in the system hierarchy.
 * Projects can contain multiple features and tasks.
 *
 * @property id Unique identifier for the project.
 * @property name Name of the project.
 * @property summary Brief summary describing the project.
 * @property status Current status of the project.
 * @property createdAt Timestamp when the project was created.
 * @property modifiedAt Timestamp when the project was last modified.
 * @property tags List of tags associated with the project for categorization.
 */
data class Project(
    val id: UUID = UUID.randomUUID(),
    val name: String,
    val summary: String,
    val status: ProjectStatus = ProjectStatus.PLANNING,
    val createdAt: Instant = Instant.now(),
    val modifiedAt: Instant = Instant.now(),
    val tags: List<String> = emptyList()
) {
    init {
        validate()
    }

    /**
     * Validates that the project data is valid.
     * @throws IllegalArgumentException if validation fails.
     */
    fun validate() {
        require(name.isNotBlank()) { "Project name cannot be empty" }
        require(summary.isNotBlank()) { "Project summary cannot be empty" }
    }

    /**
     * Creates an updated copy of this project with the specified modifications.
     *
     * @param name New name for the project (defaults to current name).
     * @param summary New summary for the project (defaults to current summary).
     * @param status New status for the project (defaults to current status).
     * @param tags New tags for the project (defaults to current tags).
     * @return A new Project instance with updated fields and modification timestamp.
     */
    fun update(
        name: String = this.name,
        summary: String = this.summary,
        status: ProjectStatus = this.status,
        tags: List<String> = this.tags
    ): Project {
        return copy(
            name = name,
            summary = summary,
            status = status,
            modifiedAt = Instant.now(),
            tags = tags
        ).also { it.validate() }
    }

    companion object {
        /**
         * Creates a new project with the specified parameters.
         * This factory method is useful for creating projects with validation.
         *
         * @param name Name of the project.
         * @param summary Brief summary describing the project.
         * @param status Status of the project (defaults to PLANNING).
         * @param tags List of tags for categorization (defaults to empty list).
         * @return A new validated Project instance.
         */
        fun create(
            name: String,
            summary: String,
            status: ProjectStatus = ProjectStatus.PLANNING,
            tags: List<String> = emptyList()
        ): Project {
            return Project(
                name = name,
                summary = summary,
                status = status,
                tags = tags
            )
        }
    }
}