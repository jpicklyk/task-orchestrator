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
 * - Ancestor cycle detection (walk-up loop, unbounded — relies on DB BEFORE-UPDATE trigger for cycle enforcement)
 * - Depth computation from parent
 *
 * No maximum depth is enforced at the application layer. Cycle protection is delegated to the
 * DB BEFORE-UPDATE trigger on work_items.parent_id introduced in V7.
 */
class ItemHierarchyValidator {
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

        // Guard: ancestor cycle check — walk up from parentId, ensure itemId is not an ancestor.
        // Uses a visited set to detect pre-existing cycles in the hierarchy (unbounded walk).
        // DB BEFORE-UPDATE trigger from V7 is the authoritative cycle guard for persistence;
        // this app-layer walk is a best-effort fast-fail for reparent operations.
        val visited = mutableSetOf<UUID>()
        var cursor: UUID? = parentId
        while (cursor != null) {
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
            is Result.Success -> parentResult.data.depth + 1
            is Result.Error -> throw ToolValidationException(
                "$errorPrefix: parent '$parentId' not found"
            )
        }
    }
}
