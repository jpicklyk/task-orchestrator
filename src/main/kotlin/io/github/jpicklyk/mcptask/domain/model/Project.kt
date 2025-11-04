package io.github.jpicklyk.mcptask.domain.model

import java.time.Instant
import java.util.*

/**
 * Represents a project entity, which is the top-level container in the system hierarchy.
 * Projects can contain multiple features and tasks.
 *
 * @property id Unique identifier for the project.
 * @property name Name of the project.
 * @property description Optional detailed description provided by user.
 * @property summary Brief summary describing the project (agent-generated, max 500 chars).
 * @property status Current status of the project.
 * @property createdAt Timestamp when the project was created.
 * @property modifiedAt Timestamp when the project was last modified.
 * @property tags List of tags associated with the project for categorization.
 */
data class Project(
    val id: UUID = UUID.randomUUID(),
    val name: String,
    /** Optional detailed description provided by user */
    val description: String? = null,
    /** Brief summary of the project (agent-generated, max 500 chars) */
    val summary: String = "",
    val status: ProjectStatus = ProjectStatus.PLANNING,
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
     * Validates that the project data is valid.
     * @throws IllegalArgumentException if validation fails.
     */
    fun validate() {
        require(name.isNotBlank()) { "Project name cannot be empty" }
        require(summary.length <= 500) { "Project summary must not exceed 500 characters" }

        // Description cannot be blank if provided
        description?.let {
            require(it.isNotBlank()) { "Project description must not be blank if provided" }
        }
    }

    /**
     * Creates an updated copy of this project with the specified modifications.
     *
     * @param name New name for the project (defaults to current name).
     * @param description New description for the project (defaults to current description).
     * @param summary New summary for the project (defaults to current summary).
     * @param status New status for the project (defaults to current status).
     * @param tags New tags for the project (defaults to current tags).
     * @return A new Project instance with updated fields and modification timestamp.
     */
    fun update(
        name: String = this.name,
        description: String? = this.description,
        summary: String = this.summary,
        status: ProjectStatus = this.status,
        tags: List<String> = this.tags
    ): Project {
        return copy(
            name = name,
            description = description,
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
         * @param description Optional detailed description provided by user.
         * @param summary Brief summary describing the project (defaults to empty).
         * @param status Status of the project (defaults to PLANNING).
         * @param tags List of tags for categorization (defaults to empty list).
         * @return A new validated Project instance.
         */
        fun create(
            name: String,
            description: String? = null,
            summary: String = "",
            status: ProjectStatus = ProjectStatus.PLANNING,
            tags: List<String> = emptyList()
        ): Project {
            return Project(
                name = name,
                description = description,
                summary = summary,
                status = status,
                tags = tags
            )
        }
    }
}