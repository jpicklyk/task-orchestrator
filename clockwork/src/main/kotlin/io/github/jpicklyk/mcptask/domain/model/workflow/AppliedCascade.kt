package io.github.jpicklyk.mcptask.domain.model.workflow

import java.util.UUID

/**
 * Result of applying a single cascade event during auto-cascade processing.
 * Used by CascadeService to track cascade application results.
 */
data class AppliedCascade(
    val event: String,
    val targetType: String,
    val targetId: UUID,
    val targetName: String,
    val previousStatus: String,
    val newStatus: String,
    val applied: Boolean,
    val reason: String,
    val error: String? = null,
    val cleanup: CascadeCleanupResult? = null,
    val unblockedTasks: List<UnblockedTask> = emptyList(),
    val childCascades: List<AppliedCascade> = emptyList()
)

/**
 * Represents a task that has become unblocked after dependency resolution.
 */
data class UnblockedTask(
    val taskId: UUID,
    val title: String
)

/**
 * Summary of cleanup operations performed during cascade processing.
 */
data class CascadeCleanupResult(
    val performed: Boolean,
    val tasksDeleted: Int,
    val tasksRetained: Int,
    val retainedTaskIds: List<UUID>,
    val sectionsDeleted: Int,
    val dependenciesDeleted: Int,
    val reason: String
)
