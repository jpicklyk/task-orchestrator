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
    val unblockAt: String? = null,
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

        // Validate unblockAt if provided
        if (unblockAt != null) {
            if (unblockAt !in StatusRole.VALID_ROLE_NAMES) {
                throw ValidationException(
                    "Invalid unblockAt role: '$unblockAt'. Valid values: ${StatusRole.VALID_ROLE_NAMES}"
                )
            }
            // unblockAt only makes sense for BLOCKS and IS_BLOCKED_BY
            if (type == DependencyType.RELATES_TO) {
                throw ValidationException(
                    "unblockAt cannot be set on RELATES_TO dependencies (no blocking semantics)"
                )
            }
        }
    }

    /**
     * Returns the effective unblock role for this dependency.
     * null defaults to "terminal" for backward compatibility.
     * RELATES_TO always returns null (no blocking semantics).
     */
    fun effectiveUnblockRole(): String? {
        if (type == DependencyType.RELATES_TO) return null
        return unblockAt ?: "terminal"
    }

    /**
     * Returns the ID of the task that acts as the blocker, regardless of dependency direction.
     * - BLOCKS: fromTaskId is the blocker (fromTask blocks toTask)
     * - IS_BLOCKED_BY: toTaskId is the blocker (fromTask is blocked by toTask)
     * - RELATES_TO: fromTaskId by convention (no blocking semantics)
     */
    fun getBlockerTaskId(): UUID = when (type) {
        DependencyType.BLOCKS -> fromTaskId
        DependencyType.IS_BLOCKED_BY -> toTaskId
        DependencyType.RELATES_TO -> fromTaskId
    }

    /**
     * Returns the ID of the task that is blocked, regardless of dependency direction.
     * - BLOCKS: toTaskId is blocked (fromTask blocks toTask)
     * - IS_BLOCKED_BY: fromTaskId is blocked (fromTask is blocked by toTask)
     * - RELATES_TO: toTaskId by convention (no blocking semantics)
     */
    fun getBlockedTaskId(): UUID = when (type) {
        DependencyType.BLOCKS -> toTaskId
        DependencyType.IS_BLOCKED_BY -> fromTaskId
        DependencyType.RELATES_TO -> toTaskId
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
