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
        // Uses a visited set to detect pre-existing cycles in the hierarchy (not just depth-bounded)
        val visited = mutableSetOf<UUID>()
        var cursor: UUID? = parentId
        while (cursor != null && visited.size <= MAX_DEPTH) {
            if (!visited.add(cursor)) break // Pre-existing cycle — stop walking
            val ancestor =
                when (val ancestorResult = repo.getById(cursor)) {
                    is Result.Success -> ancestorResult.data
                    is Result.Error -> break
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
