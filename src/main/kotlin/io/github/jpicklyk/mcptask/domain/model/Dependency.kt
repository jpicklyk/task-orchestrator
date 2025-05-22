package io.github.jpicklyk.mcptask.domain.model

import io.github.jpicklyk.mcptask.domain.validation.ValidationException
import java.time.Instant
import java.util.*

/**
 * Dependency entity representing a relationship between two tasks.
 */
data class Dependency(
    val id: UUID = UUID.randomUUID(),
    val fromTaskId: UUID,
    val toTaskId: UUID,
    val type: DependencyType = DependencyType.BLOCKS,
    val createdAt: Instant = Instant.now()
) {
    init {
        validate()
    }

    /**
     * Validates the dependency entity.
     * @throws ValidationException if validation fails
     */
    fun validate() {
        if (fromTaskId == toTaskId) {
            throw ValidationException("A task cannot depend on itself")
        }
    }
}

/**
 * Enum representing the possible types of task dependencies.
 */
enum class DependencyType {
    /**
     * The 'from' task blocks the 'to' task from being completed.
     */
    BLOCKS,

    /**
     * The 'from' task is blocked by the 'to' task.
     */
    IS_BLOCKED_BY,

    /**
     * The 'from' task relates to the 'to' task without a strict dependency.
     */
    RELATES_TO;

    companion object {
        fun fromString(value: String): DependencyType = try {
            valueOf(value.uppercase().replace('-', '_'))
        } catch (e: IllegalArgumentException) {
            throw ValidationException("Invalid dependency type: $value")
        }
    }
}
