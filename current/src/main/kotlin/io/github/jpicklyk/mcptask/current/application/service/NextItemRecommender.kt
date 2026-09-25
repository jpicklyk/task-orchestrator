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
 * "what's eligible next" recommendation query. Both `GetNextItemTool` and the
 * selector path of `ClaimItemTool` call into this service so there is one
 * authoritative source of truth for "what item should an agent pick up next."
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
        val modifiedAfter: Instant? = null,
        val modifiedBefore: Instant? = null,
        val roleChangedAfter: Instant? = null,
        val roleChangedBefore: Instant? = null,
        val orderBy: NextItemOrder = NextItemOrder.PRIORITY_THEN_COMPLEXITY,
        /**
         * Optional agent identifier forwarded to [WorkItemRepository.findClaimable] for
         * ancestor-claim sub-tree isolation.
         *
         * When non-null, candidates whose ancestor chain contains a live claim held by a
         * *different* agent are excluded; candidates under the same agent's claimed ancestor
         * are retained (enabling the hybrid fleet pattern: claim at parent feature, orchestrate
         * sub-tree below). When null, any live ancestor claim disqualifies the candidate (strict
         * exclusion for callers without actor context, such as [GetNextItemTool]).
         */
        val requestingAgentId: String? = null,
        /**
         * Optional subtree scope forwarded to [WorkItemRepository.findClaimable]. When null
         * (default), unscoped. When non-null, candidates are restricted to the subtree(s)
         * rooted at these UUIDs (roots included) — same resolution as [WorkItemRepository.findInScope].
         */
        val ancestorIds: Set<UUID>? = null,
    )

    /**
     * Returns the top [limit] unblocked, claimable work items that match [criteria].
     *
     * Algorithm:
     * 1. Over-fetch up to [OVER_FETCH_LIMIT] candidates from [WorkItemRepository.findClaimable]
     *    (active-claim exclusion is always applied by the repository).
     * 2. Propagate repository errors immediately.
     * 3. Walk each candidate through [isBlocked] and discard blocked items.
     * 4. Take the top [limit] unblocked items and return them as [Result.Success].
     */
    suspend fun recommend(
        criteria: Criteria,
        limit: Int
    ): Result<List<WorkItem>> {
        val candidatesResult =
            workItemRepo.findClaimable(
                role = criteria.role,
                parentId = criteria.parentId,
                tags = criteria.tags,
                priority = criteria.priority,
                type = criteria.type,
                complexityMax = criteria.complexityMax,
                createdAfter = criteria.createdAfter,
                createdBefore = criteria.createdBefore,
                modifiedAfter = criteria.modifiedAfter,
                modifiedBefore = criteria.modifiedBefore,
                roleChangedAfter = criteria.roleChangedAfter,
                roleChangedBefore = criteria.roleChangedBefore,
                orderBy = criteria.orderBy,
                limit = OVER_FETCH_LIMIT,
                requestingAgentId = criteria.requestingAgentId,
                rootIds = criteria.ancestorIds,
            )

        if (candidatesResult is Result.Error) {
            return candidatesResult
        }

        val candidates = (candidatesResult as Result.Success).data

        // Early-exit walk: stop once we have `limit` unblocked items. Sequences cannot
        // call suspending isBlocked, so this is a manual loop. Each isBlocked() call costs
        // 2 DB queries; for the dominant selector limit=1 case this caps the walk at the
        // first unblocked candidate instead of all 200 over-fetched.
        val unblocked = mutableListOf<WorkItem>()
        for (item in candidates) {
            if (!isBlocked(item)) {
                unblocked.add(item)
                if (unblocked.size >= limit) break
            }
        }

        return Result.Success(unblocked)
    }

    /**
     * Breaks down why [recommend] returned an empty list for [criteria] into three
     * non-overlapping-by-construction counts, for `ClaimItemTool`'s selector path to distinguish
     * "queue empty" (nothing matches at all) from "matches exist but none are claimable right
     * now" (transient — worth retrying).
     *
     * Call this ONLY after [recommend] has already returned an empty list for the same
     * [criteria] — it re-queries rather than reusing [recommend]'s candidates, so calling it
     * unconditionally would double the DB round-trips for the common non-empty case.
     *
     * Counts, over items matching [criteria]'s selector filters (via
     * [WorkItemRepository.countSelectorMatches]):
     * - [ExclusionCounts.claimed] — live item-level claim, any holder (incl. the caller).
     * - [ExclusionCounts.dependencyBlocked] — unclaimed, ancestor-claim-passing candidates (the
     *   same set [recommend] would have ranked) that [isBlocked] drops.
     * - [ExclusionCounts.ancestorClaimed] — the remainder: `matched - claimed - dependencyBlocked`,
     *   floored at 0. This also absorbs any matching rows beyond [OVER_FETCH_LIMIT] that neither
     *   the claim count nor the dependency walk below ever sees.
     *
     * `matched == 0` iff the caller should report `queue_empty`; otherwise the three counts here
     * are the transient `none_eligible` outcome's `excluded` breakdown.
     */
    suspend fun explainEmpty(criteria: Criteria): Result<ExclusionCounts> {
        val countsResult =
            workItemRepo.countSelectorMatches(
                role = criteria.role,
                parentId = criteria.parentId,
                tags = criteria.tags,
                priority = criteria.priority,
                type = criteria.type,
                complexityMax = criteria.complexityMax,
                createdAfter = criteria.createdAfter,
                createdBefore = criteria.createdBefore,
                modifiedAfter = criteria.modifiedAfter,
                modifiedBefore = criteria.modifiedBefore,
                roleChangedAfter = criteria.roleChangedAfter,
                roleChangedBefore = criteria.roleChangedBefore,
                rootIds = criteria.ancestorIds,
            )
        if (countsResult is Result.Error) {
            return countsResult
        }
        val counts = (countsResult as Result.Success).data

        if (counts.matched == 0) {
            return Result.Success(ExclusionCounts(claimed = 0, ancestorClaimed = 0, dependencyBlocked = 0))
        }

        // Re-derive the same claimable candidate set `recommend` ranked (unclaimed at the item
        // level, ancestor-claim-passing) to count how many of THOSE are dependency-blocked.
        val candidatesResult =
            workItemRepo.findClaimable(
                role = criteria.role,
                parentId = criteria.parentId,
                tags = criteria.tags,
                priority = criteria.priority,
                type = criteria.type,
                complexityMax = criteria.complexityMax,
                createdAfter = criteria.createdAfter,
                createdBefore = criteria.createdBefore,
                modifiedAfter = criteria.modifiedAfter,
                modifiedBefore = criteria.modifiedBefore,
                roleChangedAfter = criteria.roleChangedAfter,
                roleChangedBefore = criteria.roleChangedBefore,
                orderBy = criteria.orderBy,
                limit = OVER_FETCH_LIMIT,
                requestingAgentId = criteria.requestingAgentId,
                rootIds = criteria.ancestorIds,
            )
        if (candidatesResult is Result.Error) {
            return candidatesResult
        }
        val candidates = (candidatesResult as Result.Success).data

        var dependencyBlocked = 0
        for (item in candidates) {
            if (isBlocked(item)) dependencyBlocked++
        }

        val ancestorClaimed = maxOf(0, counts.matched - counts.activelyClaimed - dependencyBlocked)

        return Result.Success(
            ExclusionCounts(
                claimed = counts.activelyClaimed,
                ancestorClaimed = ancestorClaimed,
                dependencyBlocked = dependencyBlocked,
            )
        )
    }

    /**
     * Breakdown of why a selector query matched no claimable item — see [explainEmpty]. Aggregate
     * counts only; never item identities, so a caller reporting this to a remote agent cannot leak
     * who holds a competing claim.
     */
    data class ExclusionCounts(
        val claimed: Int,
        val ancestorClaimed: Int,
        val dependencyBlocked: Int,
    ) {
        val total: Int get() = claimed + ancestorClaimed + dependencyBlocked
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
     *
     * Visibility: `internal` so [GetNextItemTool]'s includeClaimed=true path can reuse this
     * method directly instead of duplicating the dependency-walk logic.
     */
    internal suspend fun isBlocked(item: WorkItem): Boolean {
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

    companion object {
        /**
         * Pre-fetch budget for [recommend]. Sized to leave room for the dependency-blocking
         * filter to drop candidates without starving the caller's `limit` (default 1, max 20
         * for `get_next_item`). Increase if blocking-filter drop rates are observed to climb.
         *
         * `internal` so [GetNextItemTool]'s includeClaimed=true path can reference the same
         * value when it invokes `findForNextItem` directly (the recommender is bypassed
         * because findClaimable always excludes active claims).
         */
        internal const val OVER_FETCH_LIMIT = 200
    }
}
