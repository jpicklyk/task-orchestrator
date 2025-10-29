package io.github.jpicklyk.mcptask.domain.model

import java.time.Instant
import java.util.*

/**
 * Represents a Task entity, which is the primary data element in the MCP Task Orchestrator.
 * Tasks can exist independently or be associated with a Feature and/or Project.
 * Detailed content is stored in separate Section entities for context efficiency.
 */
data class Task(
    /** Unique identifier for the task */
    val id: UUID = UUID.randomUUID(),

    /** Optional reference to a parent project */
    val projectId: UUID? = null,
    
    /** Optional reference to a parent feature */
    val featureId: UUID? = null,
    
    /** Required title describing the task */
    val title: String,

    /** Optional detailed description provided by user */
    val description: String? = null,

    /** Brief summary of the task (agent-generated, max 500 chars) */
    val summary: String = "",
    
    /** Current status of the task */
    val status: TaskStatus = TaskStatus.PENDING,
    
    /** Priority level of the task */
    val priority: Priority = Priority.MEDIUM,
    
    /** Complexity score (1-10 scale) */
    val complexity: Int = 5,
    
    /** When the task was created */
    val createdAt: Instant = Instant.now(),
    
    /** When the task was last modified */
    val modifiedAt: Instant = Instant.now(),
    
    /** Optimistic concurrency version */
    val version: Long = 1,
    
    /** Session that last modified this task */
    val lastModifiedBy: String? = null,
    
    /** Current lock state for quick reference */
    val lockStatus: TaskLockStatus = TaskLockStatus.UNLOCKED,
    
    /** Optional tags for categorization */
    val tags: List<String> = emptyList()
) {
    /**
     * Validates that the task meets all business rules.
     * @throws IllegalArgumentException if any validation rule is violated
     */
    fun validate() {
        require(title.isNotBlank()) { "Task title must not be empty" }
        require(complexity in 1..10) { "Task complexity must be between 1 and 10" }
        require(summary.length <= 500) { "Task summary must not exceed 500 characters" }

        // Description cannot be blank if provided
        description?.let {
            require(it.isNotBlank()) { "Task description must not be blank if provided" }
        }

        // Note: Validation of feature/project relationship consistency
        // is handled at the repository level, as it requires checking against the database
    }
    
    /**
     * Creates a copy of this task with updated fields and validation.
     * @param builder A function that modifies a copy of this task
     * @return A new valid task instance
     */
    fun update(builder: (Task) -> Task): Task {
        // Create a modified timestamp that's guaranteed to be after the current modifiedAt
        // by adding at least 1 millisecond
        val newModifiedAt = Instant.now().plusMillis(1).coerceAtLeast(modifiedAt.plusMillis(1))
        
        val updated = builder(this).copy(
            modifiedAt = newModifiedAt
        )
        updated.validate()
        return updated
    }
    
    companion object {
        /**
         * Creates a new Task instance with validation.
         * @param builder A function that builds a task
         * @return A new valid task instance
         */
        fun create(builder: (Task) -> Task): Task {
            val now = Instant.now()
            val task = builder(
                Task(
                    title = "",
                    description = null,
                    summary = "",
                    createdAt = now,
                    modifiedAt = now
                )
            )
            task.validate()
            return task
        }
    }
}

/**
 * Represents the current status of a task.
 *
 * v2.0: Additional orchestration statuses added for config-driven workflows.
 * When .taskorchestrator/config.yaml exists, validation uses config instead of these enum values.
 */
enum class TaskStatus {
    // v1.0 original statuses
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    CANCELLED,    // Explicitly cancelled by user
    DEFERRED,     // Postponed indefinitely

    // v2.0 orchestration statuses
    BACKLOG,            // Task in backlog, not yet ready for work
    IN_REVIEW,          // Task implementation complete, awaiting review
    CHANGES_REQUESTED,  // Review completed, changes requested
    ON_HOLD,            // Task temporarily paused
    TESTING,            // Implementation complete, running tests
    READY_FOR_QA,       // Testing complete, ready for quality assurance review
    INVESTIGATING,      // Actively investigating issues or technical approach
    BLOCKED,            // Blocked by incomplete dependencies
    DEPLOYED;           // Task successfully deployed to production environment

    companion object {
        /**
         * Converts a string to a TaskStatus enum value (case-insensitive).
         * Supports multiple format variations:
         * - Hyphen-separated: "in-progress", "in-review", "changes-requested", "on-hold", "ready-for-qa"
         * - Underscore-separated: "in_progress", "in_review", "changes_requested", "on_hold", "ready_for_qa"
         * - No separator: "inprogress", "inreview", "changesrequested", "onhold", "readyforqa"
         *
         * @param value The string representation of the status
         * @return The TaskStatus enum value, or null if invalid
         */
        fun fromString(value: String): TaskStatus? {
            // Normalize the input by converting to uppercase and replacing hyphens
            val normalized = value.uppercase().replace('-', '_')

            return try {
                valueOf(normalized)
            } catch (_: IllegalArgumentException) {
                // Try compound word variations (no separator) and spelling variants
                when (normalized.replace("_", "")) {
                    "INPROGRESS" -> IN_PROGRESS
                    "INREVIEW" -> IN_REVIEW
                    "CHANGESREQUESTED" -> CHANGES_REQUESTED
                    "ONHOLD" -> ON_HOLD
                    "READYFORQA" -> READY_FOR_QA
                    "CANCELED" -> CANCELLED  // US spelling variant
                    else -> null
                }
            }
        }
    }

    /**
     * Returns the string representation of this status in uppercase with underscores.
     */
    override fun toString(): String = name
}