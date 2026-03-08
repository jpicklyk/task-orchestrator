package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository
import java.util.UUID

/**
 * Validates parent-child hierarchy constraints for WorkItems and computes depth.
 *
 * Consolidates the duplicated validation logic previously in create and update operations:
 * - Self-parent guard
 * - Ancestor cycle detection (walk-up loop)
 * - Depth computation from parent
 * - Maximum depth enforcement
 */
class ItemHierarchyValidator {

    companion object {
        /** Maximum allowed nesting depth for WorkItems. */
        const val MAX_DEPTH = 3
    }

    /**
     * Validates hierarchy constraints and computes the depth for an item given its parent.
     *
     * @param itemId The UUID of the item being created or updated
     * @param parentId The target parent UUID, or null for root items
     * @param repo The WorkItem repository for ancestor lookups
     * @param errorPrefix Context string for error messages (e.g., "Item at index 2" or "Item 'abc-123'")
     * @return The computed depth (0 for root items, parent.depth + 1 for children)
     * @throws ToolValidationException if any hierarchy constraint is violated
     */
    suspend fun validateAndComputeDepth(
        itemId: UUID,
        parentId: UUID?,
        repo: WorkItemRepository,
        errorPrefix: String
    ): Int {
        if (parentId == null) return 0

        // Guard: self-parent check
        if (parentId == itemId) {
            throw ToolValidationException("$errorPrefix: cannot be its own parent")
        }

        // Guard: ancestor cycle check — walk up from parentId, ensure itemId is not an ancestor
        var cursor: UUID? = parentId
        repeat(MAX_DEPTH + 1) {
            val cursorId = cursor ?: return@repeat
            val ancestorResult = repo.getById(cursorId)
            val ancestor = when (ancestorResult) {
                is Result.Success -> ancestorResult.data
                is Result.Error -> return@repeat
            }
            if (ancestor.id == itemId) {
                throw ToolValidationException(
                    "$errorPrefix: reparenting to '$parentId' would create a circular hierarchy"
                )
            }
            cursor = ancestor.parentId
        }

        // Compute depth from parent
        val parentResult = repo.getById(parentId)
        return when (parentResult) {
            is Result.Success -> {
                val computedDepth = parentResult.data.depth + 1
                if (computedDepth > MAX_DEPTH) {
                    throw ToolValidationException(
                        "$errorPrefix: depth $computedDepth exceeds maximum depth of $MAX_DEPTH"
                    )
                }
                computedDepth
            }
            is Result.Error -> throw ToolValidationException(
                "$errorPrefix: parent '$parentId' not found"
            )
        }
    }
}
