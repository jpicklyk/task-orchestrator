package io.github.jpicklyk.mcptask.current.lifecycle

import io.github.jpicklyk.mcptask.current.dependency.BlockerEvaluator
import io.github.jpicklyk.mcptask.current.model.item.BlockedReason
import io.github.jpicklyk.mcptask.current.model.item.ItemSlice
import io.github.jpicklyk.mcptask.current.model.item.Role
import io.github.jpicklyk.mcptask.current.model.item.WorkItem
import io.github.jpicklyk.mcptask.current.platform.port.AccessScope
import io.github.jpicklyk.mcptask.current.platform.port.ListPage
import io.github.jpicklyk.mcptask.current.platform.port.PageRequest
import io.github.jpicklyk.mcptask.current.platform.port.ReadScope
import io.github.jpicklyk.mcptask.current.platform.port.UnitOfWork
import java.util.UUID

/**
 * The read model: the three work views (`next`, `blocked`, `context`), each served by ONE read unit
 * with a bounded number of statements (at most a fixed N per view; an empty id set skips its statement), whatever
 * the number of rows, children, dependency edges or claims behind it. Lists are paged ([PageRequest]) with a stable order ending in an `id` tiebreak; each page's `total` is
 * counted in the same unit as its items. Views return plain data ([NextView], [BlockedEntry], ...); the
 * tools and routes that serve them render it.
 *
 * Paging ([ListPage.of], envelope amendment U22): every page reports the full `total`; a page's `next` cursor
 * encodes `offset + items.size` and is present exactly when items exist after the page, so the final page of a
 * multi-page list has no cursor. Pages are disjoint and stable for unchanged data.
 */
class WorkViewQueries(
    private val unitOfWork: UnitOfWork<LifecycleStoreProvider>,
) {
    /**
     * One page of the items eligible under [criteria] (see `ClaimStore.findEligible`): no active claim, no
     * ancestor claim by a disqualifying agent, no unsatisfied blocking dependency; ordered by
     * [NextItemRecommender.Criteria.orderBy] then `id`. With [includeClaimed] the claim predicates are off (claimed
     * items stay, ancestor claims shield nothing) while dependency blocking still applies. `total` is the exact
     * eligible count.
     */
    suspend fun next(
        criteria: NextItemRecommender.Criteria,
        page: PageRequest = PageRequest(),
        includeClaimed: Boolean = false,
        scope: AccessScope = AccessScope.unrestricted(),
    ): ListPage<WorkItem> = nextView(criteria, page, includeClaimed, scope).page

    /**
     * [next] plus, when nothing is eligible at all, why ([NextView.emptyReason]) - decided in the same read unit by
     * one more statement ([ClaimStore.explainIneligible]) that runs only for an empty answer. Only items visible under
     * [scope] are listed, counted and explained; dependency and claim state are evaluated on the true rows.
     */
    suspend fun nextView(
        criteria: NextItemRecommender.Criteria,
        page: PageRequest = PageRequest(),
        includeClaimed: Boolean = false,
        scope: AccessScope = AccessScope.unrestricted(),
    ): NextView =
        unitOfWork.read {
            val claims = stores.claimStore()
            val query = criteria.toEligibilityQuery(includeClaimed)
            val slice = claims.findEligible(query, page.limit, page.offset, scope)
            val reason =
                if (slice.total > 0) {
                    null
                } else {
                    val breakdown = claims.explainIneligible(query, scope)
                    val shielded = maxOf(0, breakdown.matched - breakdown.activelyClaimed - breakdown.dependencyBlocked)
                    when {
                        breakdown.matched == 0 -> null
                        !includeClaimed && breakdown.activelyClaimed + shielded > 0 -> NextEmptyReason.CLAIM_HELD
                        else -> NextEmptyReason.DEPENDENCY_UNMET
                    }
                }
            NextView(pageOf(slice.items, slice.total, page), reason)
        }

    /**
     * One page of the blocked items in [scope]: role BLOCKED ([BlockType.EXPLICIT]) or QUEUE/WORK/REVIEW with at
     * least one unsatisfied blocking dependency ([BlockType.DEPENDENCY]); never TERMINAL. Each entry lists every
     * blocking dependency into the item (satisfied ones too), ordered by blocker id. BLOCKED items come first,
     * then queue, work, review; ties by id.
     *
     * Only items visible under [scope] are listed and counted; blocked-ness and every blocker's satisfaction are
     * decided on the true rows, and the blockers listed are the ones visible under [scope] (a blocker outside it is
     * omitted from [BlockedEntry.blockers] while its item stays listed).
     */
    suspend fun blocked(
        filter: BlockedScope = BlockedScope(),
        page: PageRequest = PageRequest(),
        scope: AccessScope = AccessScope.unrestricted(),
    ): ListPage<BlockedEntry> =
        unitOfWork.read {
            val slice = stores.itemStore().findBlocked(filter.parentId, filter.ancestorId, page.limit, page.offset, scope)
            val ids = slice.items.map { it.id }.toSet()
            val edges = stores.dependencyRepository().blockingEdgesFor(ids)
            val blockers = stores.itemStore().findByIdSet(edges.map { it.blocker }.toSet()).associateBy { it.id }
            val visibleBlockers = visibleIds(blockers.values, scope)
            val edgesByBlocked = edges.groupBy { it.blocked }
            // One batched read for every awaiting_decision entry's decision (constant query count); none when the page has none.
            val decisionIds =
                slice.items
                    .filter { it.role == Role.BLOCKED && it.effectiveBlockedReason == BlockedReason.AWAITING_DECISION }
                    .mapNotNull { it.blockedRef }
                    .toSet()
            val decisions =
                if (decisionIds.isEmpty()) {
                    emptyMap()
                } else {
                    stores.decisionReads().findByIds(decisionIds).associateBy { it.id }
                }
            val entries =
                slice.items.map { item ->
                    val views =
                        (edgesByBlocked[item.id] ?: emptyList())
                            .filter { visibleBlockers == null || it.blocker in visibleBlockers }
                            .map { edge ->
                                val blocker = blockers[edge.blocker]
                                BlockerView(
                                    blockerId = edge.blocker,
                                    title = blocker?.title,
                                    role = blocker?.role,
                                    threshold = edge.unblockAt,
                                    satisfied = BlockerEvaluator.isSatisfied(edge, blocker?.role),
                                )
                            }.sortedBy { it.blockerId.toString() }
                    if (item.role == Role.BLOCKED) {
                        val reason = item.effectiveBlockedReason
                        val awaiting = reason == BlockedReason.AWAITING_DECISION
                        BlockedEntry(
                            item = item,
                            blockType = BlockType.EXPLICIT,
                            blockers = views,
                            reason = reason,
                            decisionId = item.blockedRef,
                            resumable = if (awaiting) decisions[item.blockedRef]?.status?.isPending != true else null,
                        )
                    } else {
                        BlockedEntry(item, BlockType.DEPENDENCY, views)
                    }
                }
            pageOf(entries, slice.total, page)
        }

    /**
     * The health / session-resume item data for [scope]: a page of the active (WORK, REVIEW) items, a page of the
     * role-BLOCKED items (both with exact totals), the claim summary over the scope, and the notes of every active
     * item on the page in one batched read. Stalled classification (which needs schemas) is left to the caller.
     * The blocked page is the SAME set as [blocked] (explicit and dependency, within [filter]'s ancestor), so a
     * health view and the blocked view never disagree. Only items visible under [scope] are listed and counted.
     */
    suspend fun context(
        filter: ContextScope = ContextScope(),
        page: PageRequest = PageRequest(),
        scope: AccessScope = AccessScope.unrestricted(),
    ): ContextView =
        unitOfWork.read {
            val items = stores.itemStore()
            val rootIds = filter.ancestorId?.let { setOf(it) }
            val active = items.findByRoles(setOf(Role.WORK, Role.REVIEW), rootIds, page.limit, page.offset, scope)
            val blocked = items.findBlocked(null, filter.ancestorId, page.limit, page.offset, scope)
            val claims = stores.claimStore().claimSummary(rootIds, scope)
            val activeIds = active.items.map { it.id }.toSet()
            val found = stores.noteRepository().findByItemIds(activeIds)
            val notes = activeIds.associateWith { found[it] ?: emptyList() }
            ContextView(
                active = page(active, page),
                blocked = page(blocked, page),
                claimSummary = claims,
                notes = notes,
            )
        }

    /**
     * The ids of [items] visible under [scope] (one statement), or null when [scope] restricts nothing.
     */
    private suspend fun ReadScope<LifecycleStoreProvider>.visibleIds(
        items: Collection<WorkItem>,
        scope: AccessScope,
    ): Set<UUID>? {
        if (scope.isUnrestricted || items.isEmpty()) return null
        return stores.itemStore().filterVisible(items.map { it.id }.toSet(), scope)
    }

    private fun page(
        slice: ItemSlice,
        page: PageRequest,
    ): ListPage<WorkItem> = pageOf(slice.items, slice.total, page)

    private fun <T> pageOf(
        items: List<T>,
        total: Int,
        page: PageRequest,
    ): ListPage<T> {
        // Page and count share one read unit, so total >= items.size; the max only guards the ListPage invariant.
        return ListPage.of(items, total = maxOf(total, items.size), page = page)
    }
}
