package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.domain.model.*
import io.github.jpicklyk.mcptask.current.domain.repository.DependencyRepository
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository
import org.slf4j.LoggerFactory
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
 *
 * ## Transaction-gap caveat
 *
 * Detection and application are intentionally **separate transactions**. The detect phase
 * reads a snapshot of DB state, computes which cascade events are warranted, and returns
 * them to the caller. The caller then applies each event in its own transaction. No single
 * transaction spans both detect and apply.
 *
 * In theory this creates a window where concurrent writers could observe stale state
 * (e.g. a sibling's role changes between detect and apply). In practice SQLite's
 * single-writer model means only one write can proceed at a time, which eliminates
 * the concurrency risk for the common deployment scenario.
 *
 * For multi-level hierarchies (child → parent → grandparent), [AdvanceItemTool]
 * compensates with an **iterative detect-apply loop**: after each cascade event is
 * applied and persisted, detection is re-run on the newly advanced item with fresh
 * DB state. This ensures each level's decision is based on accurate, up-to-date data.
 * The split-transaction design is intentional — not a bug.
 */
class CascadeDetector {
    companion object {
        /** Maximum ancestor depth for recursive cascade detection. */
        const val MAX_DEPTH = 3

        private val logger = LoggerFactory.getLogger(CascadeDetector::class.java)
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
     * **Lifecycle-aware:** If [schemaResolver] is provided, the parent's [WorkItemSchema]
     * is checked before creating a cascade event. Parents with [LifecycleMode.MANUAL] or
     * [LifecycleMode.PERMANENT] will suppress terminal cascades and stop further recursion.
     *
     * **Important:** Detection reads current persisted DB state. When cascading
     * multi-level hierarchies, only the first returned event is guaranteed to
     * reflect accurate state. Callers must apply cascades iteratively --
     * apply the first event, persist it, then re-invoke this method on the
     * cascaded parent to detect the next level.
     *
     * @param schemaResolver optional function to resolve the [WorkItemSchema] for a parent item.
     *   Used to check [LifecycleMode] and suppress cascades for MANUAL or PERMANENT schemas.
     * @return cascade events in order from closest parent to most distant ancestor
     */
    suspend fun detectCascades(
        item: WorkItem,
        workItemRepository: WorkItemRepository,
        schemaResolver: ((WorkItem) -> WorkItemSchema?)? = null
    ): List<CascadeEvent> {
        val parentId = item.parentId ?: return emptyList()
        return detectCascadesRecursive(parentId, workItemRepository, depth = 0, schemaResolver = schemaResolver)
    }

    private suspend fun detectCascadesRecursive(
        parentId: UUID,
        workItemRepository: WorkItemRepository,
        depth: Int,
        schemaResolver: ((WorkItem) -> WorkItemSchema?)? = null
    ): List<CascadeEvent> {
        if (depth >= MAX_DEPTH) return emptyList()

        // Get role counts for all children of the parent
        val countsResult = workItemRepository.countChildrenByRole(parentId)
        val roleCounts =
            when (countsResult) {
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
        val parent =
            when (parentResult) {
                is Result.Success -> parentResult.data
                is Result.Error -> return emptyList()
            }

        // If parent is already terminal, no cascade needed
        if (parent.role == Role.TERMINAL) return emptyList()

        // Check lifecycle mode: MANUAL or PERMANENT suppress terminal cascade
        if (schemaResolver != null) {
            val schema = schemaResolver(parent)
            val lifecycleMode = schema?.lifecycleMode
            if (lifecycleMode == LifecycleMode.MANUAL || lifecycleMode == LifecycleMode.PERMANENT) {
                logger.debug(
                    "Terminal cascade suppressed for item {} (lifecycleMode={})",
                    parent.id,
                    lifecycleMode
                )
                return emptyList()
            }
        }

        val event =
            CascadeEvent(
                itemId = parent.id,
                currentRole = parent.role,
                targetRole = Role.TERMINAL
            )

        // Recursively check the parent's parent
        val upstreamEvents =
            if (parent.parentId != null) {
                detectCascadesRecursive(parent.parentId, workItemRepository, depth + 1, schemaResolver)
            } else {
                emptyList()
            }

        return listOf(event) + upstreamEvents
    }

    // -----------------------------------------------------------------------
    // Start Cascade Detection
    // -----------------------------------------------------------------------

    /**
     * Detect start cascade: if [item] just entered WORK and has a parent in QUEUE,
     * the parent should auto-advance to WORK as well.
     *
     * Returns a single-element list with the CascadeEvent for the parent, or empty list
     * if no cascade is warranted.
     */
    suspend fun detectStartCascades(
        item: WorkItem,
        workItemRepository: WorkItemRepository
    ): List<CascadeEvent> {
        // Only fires when the item is in WORK role
        if (item.role != Role.WORK) return emptyList()

        // Must have a parent
        val parentId = item.parentId ?: return emptyList()

        // Fetch parent
        val parentResult = workItemRepository.getById(parentId)
        val parent =
            when (parentResult) {
                is Result.Success -> parentResult.data
                is Result.Error -> return emptyList()
            }

        // Parent must be in QUEUE to cascade
        if (parent.role != Role.QUEUE) return emptyList()

        return listOf(
            CascadeEvent(
                itemId = parent.id,
                currentRole = parent.role,
                targetRole = Role.WORK
            )
        )
    }

    // -----------------------------------------------------------------------
    // Reopen Cascade Detection
    // -----------------------------------------------------------------------

    /**
     * Detect whether reopening an item should cascade to reopen its parent.
     * If the item was just reopened (now in QUEUE) and its parent is TERMINAL,
     * the parent should reopen to WORK since it now has a non-terminal child.
     * Only checks immediate parent — no recursion.
     *
     * **Lifecycle-aware:** If [schemaResolver] is provided, the parent's [WorkItemSchema]
     * is checked before creating a reopen cascade event. Parents with [LifecycleMode.MANUAL]
     * or [LifecycleMode.PERMANENT] will suppress reopen cascades. [LifecycleMode.AUTO_REOPEN]
     * and [LifecycleMode.AUTO] allow the reopen cascade.
     *
     * @param schemaResolver optional function to resolve the [WorkItemSchema] for a parent item.
     *   Used to check [LifecycleMode] and suppress reopen cascades for MANUAL or PERMANENT schemas.
     */
    suspend fun detectReopenCascades(
        item: WorkItem,
        workItemRepository: WorkItemRepository,
        schemaResolver: ((WorkItem) -> WorkItemSchema?)? = null
    ): List<CascadeEvent> {
        // Only applies to items that just entered QUEUE via reopen
        if (item.role != Role.QUEUE) return emptyList()

        val parentId = item.parentId ?: return emptyList()

        val parent =
            when (val result = workItemRepository.getById(parentId)) {
                is Result.Success -> result.data
                is Result.Error -> return emptyList()
            }

        // Only cascade if parent is TERMINAL
        if (parent.role != Role.TERMINAL) return emptyList()

        // Check lifecycle mode: MANUAL or PERMANENT suppress reopen cascade
        if (schemaResolver != null) {
            val schema = schemaResolver(parent)
            val lifecycleMode = schema?.lifecycleMode
            if (lifecycleMode == LifecycleMode.MANUAL || lifecycleMode == LifecycleMode.PERMANENT) {
                logger.debug(
                    "Reopen cascade suppressed for item {} (lifecycleMode={})",
                    parent.id,
                    lifecycleMode
                )
                return emptyList()
            }
        }

        return listOf(
            CascadeEvent(
                itemId = parent.id,
                currentRole = Role.TERMINAL,
                targetRole = Role.WORK
            )
        )
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
        // Collect target item IDs from both blocking directions:
        // 1. Outgoing BLOCKS deps: item BLOCKS target → target is dep.toItemId
        val outgoingDeps = dependencyRepository.findByFromItemId(item.id)
        val blocksTargets =
            outgoingDeps
                .filter { it.type == DependencyType.BLOCKS }
                .map { it.toItemId }

        // 2. Incoming IS_BLOCKED_BY deps: target IS_BLOCKED_BY item → target is dep.fromItemId
        val incomingDeps = dependencyRepository.findByToItemId(item.id)
        val isBlockedByTargets =
            incomingDeps
                .filter { it.type == DependencyType.IS_BLOCKED_BY }
                .map { it.fromItemId }

        val targetIds = (blocksTargets + isBlockedByTargets).toSet()
        if (targetIds.isEmpty()) return emptyList()

        val unblockedItems = mutableListOf<UnblockedItem>()

        for (targetId in targetIds) {
            if (isFullyUnblocked(targetId, dependencyRepository, workItemRepository)) {
                // Fetch the target item to get its title
                val targetResult = workItemRepository.getById(targetId)
                val targetItem =
                    when (targetResult) {
                        is Result.Success -> targetResult.data
                        is Result.Error -> continue
                    }
                unblockedItems.add(UnblockedItem(itemId = targetItem.id, title = targetItem.title))
            }
        }

        return unblockedItems
    }

    /**
     * Check whether all blocking dependencies on [itemId] are satisfied.
     * Checks both incoming BLOCKS deps and outgoing IS_BLOCKED_BY deps.
     */
    private suspend fun isFullyUnblocked(
        itemId: UUID,
        dependencyRepository: DependencyRepository,
        workItemRepository: WorkItemRepository
    ): Boolean {
        // Check incoming BLOCKS deps (blocker is dep.fromItemId)
        val incomingDeps = dependencyRepository.findByToItemId(itemId)
        for (dep in incomingDeps) {
            if (dep.type == DependencyType.RELATES_TO) continue
            if (dep.type == DependencyType.IS_BLOCKED_BY) continue // handled below via outgoing

            val threshold = dep.effectiveUnblockRole() ?: continue
            val thresholdRole = Role.fromString(threshold) ?: continue

            val blockerResult = workItemRepository.getById(dep.fromItemId)
            val blockerItem =
                when (blockerResult) {
                    is Result.Success -> blockerResult.data
                    is Result.Error -> return false
                }

            if (!Role.isAtOrBeyond(blockerItem.role, thresholdRole)) {
                return false
            }
        }

        // Check outgoing IS_BLOCKED_BY deps (blocker is dep.toItemId)
        val outgoingDeps = dependencyRepository.findByFromItemId(itemId)
        for (dep in outgoingDeps) {
            if (dep.type != DependencyType.IS_BLOCKED_BY) continue

            val threshold = dep.effectiveUnblockRole() ?: continue
            val thresholdRole = Role.fromString(threshold) ?: continue

            val blockerResult = workItemRepository.getById(dep.toItemId)
            val blockerItem =
                when (blockerResult) {
                    is Result.Success -> blockerResult.data
                    is Result.Error -> return false
                }

            if (!Role.isAtOrBeyond(blockerItem.role, thresholdRole)) {
                return false
            }
        }

        return true
    }
}
