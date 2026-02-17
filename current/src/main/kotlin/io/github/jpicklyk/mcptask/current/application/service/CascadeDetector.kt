package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.domain.model.*
import io.github.jpicklyk.mcptask.current.domain.repository.DependencyRepository
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository
import java.util.UUID

/**
 * Event indicating a parent WorkItem should advance to TERMINAL
 * because all of its children have reached TERMINAL role.
 */
data class CascadeEvent(
    val itemId: UUID,
    val currentRole: Role,
    val targetRole: Role,
    val trigger: String = "cascade"
)

/**
 * A downstream WorkItem that has become unblocked because all of its
 * incoming blocking dependencies are now satisfied.
 */
data class UnblockedItem(
    val itemId: UUID,
    val title: String
)

/**
 * Detects cascade advancement and unblock events after a WorkItem transitions.
 *
 * Two responsibilities:
 * 1. **Cascade detection** -- when a WorkItem reaches TERMINAL, check whether all siblings
 *    under the same parent are also TERMINAL. If so, the parent should advance too.
 *    Detection recurses up to [MAX_DEPTH] ancestors, but reads current persisted DB
 *    state at each level. For multi-level cascades (parent -> grandparent), callers
 *    should use an iterative detect-apply loop: apply the first cascade event, then
 *    re-detect from the cascaded parent with fresh DB state. See
 *    [AdvanceItemTool] for the canonical usage pattern.
 *
 * 2. **Unblock detection** -- when a WorkItem transitions, find any downstream items
 *    whose incoming blocking dependencies are now fully satisfied.
 */
class CascadeDetector {

    companion object {
        /** Maximum ancestor depth for recursive cascade detection. */
        const val MAX_DEPTH = 3
    }

    // -----------------------------------------------------------------------
    // Cascade Detection
    // -----------------------------------------------------------------------

    /**
     * Detect cascade events starting from [item] up through its ancestor chain.
     *
     * If [item] has no parent (root item), returns an empty list.
     * Otherwise checks whether all children of the parent are TERMINAL.
     * If so, creates a [CascadeEvent] for that parent and recursively
     * checks the grandparent, bounded by [MAX_DEPTH].
     *
     * **Important:** Detection reads current persisted DB state. When cascading
     * multi-level hierarchies, only the first returned event is guaranteed to
     * reflect accurate state. Callers must apply cascades iteratively --
     * apply the first event, persist it, then re-invoke this method on the
     * cascaded parent to detect the next level.
     *
     * @return cascade events in order from closest parent to most distant ancestor
     */
    suspend fun detectCascades(
        item: WorkItem,
        workItemRepository: WorkItemRepository
    ): List<CascadeEvent> {
        val parentId = item.parentId ?: return emptyList()
        return detectCascadesRecursive(parentId, workItemRepository, depth = 0)
    }

    private suspend fun detectCascadesRecursive(
        parentId: UUID,
        workItemRepository: WorkItemRepository,
        depth: Int
    ): List<CascadeEvent> {
        if (depth >= MAX_DEPTH) return emptyList()

        // Get role counts for all children of the parent
        val countsResult = workItemRepository.countChildrenByRole(parentId)
        val roleCounts = when (countsResult) {
            is Result.Success -> countsResult.data
            is Result.Error -> return emptyList()
        }

        // If there are no children at all, no cascade
        if (roleCounts.isEmpty()) return emptyList()

        // Check if ALL children are TERMINAL
        val allTerminal = roleCounts.all { (role, _) -> role == Role.TERMINAL }
        if (!allTerminal) return emptyList()

        // All children are terminal -- create cascade event for the parent
        val parentResult = workItemRepository.getById(parentId)
        val parent = when (parentResult) {
            is Result.Success -> parentResult.data
            is Result.Error -> return emptyList()
        }

        // If parent is already terminal, no cascade needed
        if (parent.role == Role.TERMINAL) return emptyList()

        val event = CascadeEvent(
            itemId = parent.id,
            currentRole = parent.role,
            targetRole = Role.TERMINAL
        )

        // Recursively check the parent's parent
        val upstreamEvents = if (parent.parentId != null) {
            detectCascadesRecursive(parent.parentId, workItemRepository, depth + 1)
        } else {
            emptyList()
        }

        return listOf(event) + upstreamEvents
    }

    // -----------------------------------------------------------------------
    // Unblock Detection
    // -----------------------------------------------------------------------

    /**
     * Find all downstream WorkItems that are now fully unblocked after [item] transitioned.
     *
     * Checks outgoing BLOCKS dependencies from [item]. For each target, verifies
     * that ALL of its incoming blocking dependencies (BLOCKS / IS_BLOCKED_BY) have
     * their blocker at or beyond the required [Dependency.effectiveUnblockRole] threshold.
     *
     * RELATES_TO dependencies are ignored (no blocking semantics).
     *
     * @return list of items that are now fully unblocked
     */
    suspend fun findUnblockedItems(
        item: WorkItem,
        dependencyRepository: DependencyRepository,
        workItemRepository: WorkItemRepository
    ): List<UnblockedItem> {
        // Find all outgoing dependencies where this item is the source
        val outgoingDeps = dependencyRepository.findByFromItemId(item.id)

        // Only consider BLOCKS dependencies
        val blockingDeps = outgoingDeps.filter { it.type == DependencyType.BLOCKS }
        if (blockingDeps.isEmpty()) return emptyList()

        // Collect unique target item IDs to check
        val targetIds = blockingDeps.map { it.toItemId }.toSet()
        val unblockedItems = mutableListOf<UnblockedItem>()

        for (targetId in targetIds) {
            if (isFullyUnblocked(targetId, dependencyRepository, workItemRepository)) {
                // Fetch the target item to get its title
                val targetResult = workItemRepository.getById(targetId)
                val targetItem = when (targetResult) {
                    is Result.Success -> targetResult.data
                    is Result.Error -> continue
                }
                unblockedItems.add(UnblockedItem(itemId = targetItem.id, title = targetItem.title))
            }
        }

        return unblockedItems
    }

    /**
     * Check whether all incoming blocking dependencies on [itemId] are satisfied.
     */
    private suspend fun isFullyUnblocked(
        itemId: UUID,
        dependencyRepository: DependencyRepository,
        workItemRepository: WorkItemRepository
    ): Boolean {
        val incomingDeps = dependencyRepository.findByToItemId(itemId)

        for (dep in incomingDeps) {
            // RELATES_TO has no blocking semantics
            if (dep.type == DependencyType.RELATES_TO) continue

            val threshold = dep.effectiveUnblockRole() ?: continue
            val thresholdRole = Role.fromString(threshold) ?: continue

            // Get the blocker's current state
            val blockerResult = workItemRepository.getById(dep.fromItemId)
            val blockerItem = when (blockerResult) {
                is Result.Success -> blockerResult.data
                is Result.Error -> return false // Missing blocker counts as still blocked
            }

            // If the blocker hasn't reached the threshold, this item is still blocked
            if (!Role.isAtOrBeyond(blockerItem.role, thresholdRole)) {
                return false
            }
        }

        return true
    }
}
