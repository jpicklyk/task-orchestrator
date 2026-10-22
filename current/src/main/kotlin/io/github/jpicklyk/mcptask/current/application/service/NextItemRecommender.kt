package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.port.EligibilityQuery
import io.github.jpicklyk.mcptask.current.application.port.WorkItemRepository
import io.github.jpicklyk.mcptask.current.domain.model.NextItemOrder
import io.github.jpicklyk.mcptask.current.domain.model.Priority
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import java.time.Instant
import java.util.UUID

/**
 * Service that answers "what's eligible next": the one authoritative source of truth for which item an agent
 * should pick up next. Both `items_read` `select=next` (through `WorkViewQueries`) and the selector path of the `advance` claim op use it.
 *
 * Eligibility is decided entirely in SQL ([WorkItemRepository.findEligible]): the selector filters, the
 * active-claim and ancestor-claim exclusions, and dependency blocking are all predicates of one query that pages
 * and counts, so an eligible item is found however many ineligible ones rank ahead of it.
 */
class NextItemRecommender(
    private val workItemRepo: WorkItemRepository,
) {
    /** Filter criteria for [recommend]; they map 1:1 onto an [EligibilityQuery] ([toEligibilityQuery]). */
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
         * Optional agent identifier for ancestor-claim sub-tree isolation.
         *
         * When non-null, candidates whose ancestor chain contains a live claim held by a
         * *different* agent are excluded; candidates under the same agent's claimed ancestor
         * are retained (enabling the hybrid fleet pattern: claim at parent feature, orchestrate
         * sub-tree below). When null, any live ancestor claim disqualifies the candidate (strict
         * exclusion for callers without actor context, such as the `items_read` `next` select).
         */
        val requestingAgentId: String? = null,
        /**
         * With [requestingAgentId], the seat assignment of the requesting holder (R22): an ancestor claim shields
         * nothing only when it is held by exactly this (agent, assignment) pair.
         */
        val requestingAssignmentId: UUID? = null,
        /**
         * Optional subtree scope. When null (default), unscoped. When non-null, candidates are restricted to the
         * subtree(s) rooted at these UUIDs (roots included) — same resolution as [WorkItemRepository.find]'s
         * `AccessScope.rootIds`.
         */
        val ancestorIds: Set<UUID>? = null,
    ) {
        /** These criteria as the store's [EligibilityQuery]; [includeClaimed] turns the claim predicates off. */
        fun toEligibilityQuery(includeClaimed: Boolean = false): EligibilityQuery =
            EligibilityQuery(
                role = role,
                parentId = parentId,
                tags = tags,
                priority = priority,
                type = type,
                complexityMax = complexityMax,
                createdAfter = createdAfter,
                createdBefore = createdBefore,
                modifiedAfter = modifiedAfter,
                modifiedBefore = modifiedBefore,
                roleChangedAfter = roleChangedAfter,
                roleChangedBefore = roleChangedBefore,
                orderBy = orderBy,
                requestingAgentId = requestingAgentId,
                requestingAssignmentId = requestingAssignmentId,
                rootIds = ancestorIds,
                includeClaimed = includeClaimed,
            )
    }

    /**
     * Returns the top [limit] eligible work items that match [criteria]: no active claim, no ancestor claim by a
     * disqualifying agent, no unsatisfied blocking dependency, ranked by [Criteria.orderBy] (ties by id).
     */
    suspend fun recommend(
        criteria: Criteria,
        limit: Int
    ): List<WorkItem> = recommendCounted(criteria, limit).items

    /** The recommended items plus the exact number of eligible items for the criteria. */
    data class Counted(
        val items: List<WorkItem>,
        val candidateCount: Int
    )

    /**
     * [recommend] that also reports [Counted.candidateCount]: the EXACT number of items eligible under [criteria]
     * (all pages, not a window), for the call-log `eligible_count` of a `next` read. [recommend] shares this one
     * implementation. Items and count come from one read transaction.
     */
    suspend fun recommendCounted(
        criteria: Criteria,
        limit: Int
    ): Counted {
        val slice = workItemRepo.findEligible(criteria.toEligibilityQuery(), limit = limit, offset = 0)
        return Counted(slice.items, slice.total)
    }

    /**
     * Breaks down why [recommend] returned an empty list for [criteria] into three
     * non-overlapping-by-construction counts, for the `advance` claim op's selector path to distinguish
     * "queue empty" (nothing matches at all) from "matches exist but none are claimable right
     * now" (transient — worth retrying).
     *
     * Call this ONLY after [recommend] has already returned an empty list for the same
     * [criteria] — it re-queries rather than reusing [recommend]'s result.
     *
     * Counts, over items matching [criteria]'s selector filters, all exact and from one statement
     * ([WorkItemRepository.explainIneligible]):
     * - [ExclusionCounts.claimed] — live item-level claim, any holder (incl. the caller).
     * - [ExclusionCounts.dependencyBlocked] — unclaimed, ancestor-claim-passing items (the same set
     *   [recommend] ranks) that have an unsatisfied blocking dependency.
     * - [ExclusionCounts.ancestorClaimed] — the remainder: `matched - claimed - dependencyBlocked`,
     *   floored at 0. After an empty [recommend] this is exactly the items shielded by an ancestor claim.
     *
     * `matched == 0` iff the caller should report `queue_empty`; otherwise the three counts here
     * are the transient `none_eligible` outcome's `excluded` breakdown, and they sum to `matched`.
     */
    suspend fun explainEmpty(criteria: Criteria): ExclusionCounts {
        val breakdown = workItemRepo.explainIneligible(criteria.toEligibilityQuery())
        if (breakdown.matched == 0) {
            return ExclusionCounts(claimed = 0, ancestorClaimed = 0, dependencyBlocked = 0)
        }
        val ancestorClaimed = maxOf(0, breakdown.matched - breakdown.activelyClaimed - breakdown.dependencyBlocked)
        return ExclusionCounts(
            claimed = breakdown.activelyClaimed,
            ancestorClaimed = ancestorClaimed,
            dependencyBlocked = breakdown.dependencyBlocked,
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
}
