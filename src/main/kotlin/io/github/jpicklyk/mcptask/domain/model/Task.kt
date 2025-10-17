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
 */
enum class TaskStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    CANCELLED,
    DEFERRED
}