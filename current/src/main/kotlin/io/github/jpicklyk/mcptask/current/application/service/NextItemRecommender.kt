package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.model.NextItemOrder
import io.github.jpicklyk.mcptask.current.domain.model.Priority
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.DependencyRepository
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository
import java.time.Instant
import java.util.UUID

/**
 * Service that wraps `findClaimable` + dependency-blocking walk into a single
 * "what's eligible next" recommendation query.
 *
 * Phase 3 (GetNextItemTool) and Phase 4 (ClaimItemTool selector) share this service
 * so there is one authoritative source of truth for "what item should an agent pick up next."
 */
class NextItemRecommender(
    private val workItemRepo: WorkItemRepository,
    private val dependencyRepo: DependencyRepository,
) {
    /**
     * Filter criteria for [recommend].  All fields mirror the parameters of
     * [WorkItemRepository.findClaimable]; the service maps them 1:1 to the repository call.
     */
    data class Criteria(
        val role: Role = Role.QUEUE,
        val parentId: UUID? = null,
        val tags: List<String>? = null,
        val priority: Priority? = null,
        val type: String? = null,
        val complexityMax: Int? = null,
        val createdAfter: Instant? = null,
        val createdBefore: Instant? = null,
        val roleChangedAfter: Instant? = null,
        val roleChangedBefore: Instant? = null,
        val orderBy: NextItemOrder = NextItemOrder.PRIORITY_THEN_COMPLEXITY,
    )

    /**
     * Returns the top [limit] unblocked, claimable work items that match [criteria].
     *
     * Algorithm:
     * 1. Over-fetch up to 200 candidates from [WorkItemRepository.findClaimable] (active-claim
     *    exclusion is always applied by the repository).
     * 2. Propagate repository errors immediately.
     * 3. Walk each candidate through [isBlocked] and discard blocked items.
     * 4. Take the top [limit] unblocked items and return them as [Result.Success].
     */
    suspend fun recommend(criteria: Criteria, limit: Int): Result<List<WorkItem>> {
        val candidatesResult = workItemRepo.findClaimable(
            role = criteria.role,
            parentId = criteria.parentId,
            tags = criteria.tags,
            priority = criteria.priority,
            type = criteria.type,
            complexityMax = criteria.complexityMax,
            createdAfter = criteria.createdAfter,
            createdBefore = criteria.createdBefore,
            roleChangedAfter = criteria.roleChangedAfter,
            roleChangedBefore = criteria.roleChangedBefore,
            orderBy = criteria.orderBy,
            limit = 200,
        )

        if (candidatesResult is Result.Error) {
            return candidatesResult
        }

        val candidates = (candidatesResult as Result.Success).data

        val unblocked = candidates.filter { item -> !isBlocked(item) }

        return Result.Success(unblocked.take(limit))
    }

    /**
     * Returns true if [item] is dependency-blocked by any unsatisfied dependency.
     *
     * Checks two dependency directions:
     * - Incoming BLOCKS deps (`dep.toItemId == item.id`): the blocker is `dep.fromItemId`.
     * - Outgoing IS_BLOCKED_BY deps (`dep.fromItemId == item.id`): the blocker is `dep.toItemId`.
     *
     * RELATES_TO dependencies carry no blocking semantics and are ignored.
     * If a blocker item cannot be fetched (e.g. deleted), the dependency is skipped conservatively.
     */
    private suspend fun isBlocked(item: WorkItem): Boolean {
        // Check BLOCKS deps where this item is the target (dep.toItemId = item.id)
        val incomingDeps = dependencyRepo.findByToItemId(item.id)
        for (dep in incomingDeps) {
            if (dep.type == DependencyType.BLOCKS) {
                val threshold = dep.effectiveUnblockRole() ?: continue
                val thresholdRole = Role.fromString(threshold) ?: continue
                // Blocker is dep.fromItemId
                val blockerResult = workItemRepo.getById(dep.fromItemId)
                val blocker =
                    when (blockerResult) {
                        is Result.Success -> blockerResult.data
                        is Result.Error -> continue // Skip — can't determine blocker state
                    }
                if (!Role.isAtOrBeyond(blocker.role, thresholdRole)) {
                    return true // Unsatisfied dependency
                }
            }
        }

        // Check IS_BLOCKED_BY deps where this item is the source (dep.fromItemId = item.id)
        val outgoingDeps = dependencyRepo.findByFromItemId(item.id)
        for (dep in outgoingDeps) {
            if (dep.type == DependencyType.IS_BLOCKED_BY) {
                val threshold = dep.effectiveUnblockRole() ?: continue
                val thresholdRole = Role.fromString(threshold) ?: continue
                // Blocker is dep.toItemId
                val blockerResult = workItemRepo.getById(dep.toItemId)
                val blocker =
                    when (blockerResult) {
                        is Result.Success -> blockerResult.data
                        is Result.Error -> continue // Skip — can't determine blocker state
                    }
                if (!Role.isAtOrBeyond(blocker.role, thresholdRole)) {
                    return true // Unsatisfied dependency
                }
            }
        }

        return false
    }
}
