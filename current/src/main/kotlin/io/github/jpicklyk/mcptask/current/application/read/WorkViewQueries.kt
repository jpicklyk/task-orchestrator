package io.github.jpicklyk.mcptask.current.application.read

import io.github.jpicklyk.mcptask.current.application.api.ListPage
import io.github.jpicklyk.mcptask.current.application.api.PageRequest
import io.github.jpicklyk.mcptask.current.application.port.AccessScope
import io.github.jpicklyk.mcptask.current.application.port.ItemSlice
import io.github.jpicklyk.mcptask.current.application.port.ReadScope
import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.application.service.NextItemRecommender
import io.github.jpicklyk.mcptask.current.domain.graph.BlockerEvaluator
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import java.util.UUID

/**
 * The read model: the four work views (`next`, `blocked`, `overview`, `context`), each served by ONE read unit
 * with a bounded number of statements (at most a fixed N per view; an empty id set skips its statement), whatever
 * the number of rows, children, dependency edges or claims behind it. Lists are paged ([PageRequest]) with a stable order ending in an `id` tiebreak; each page's `total` is
 * counted in the same unit as its items. Views return plain data ([OverviewView], [BlockedEntry], ...); the
 * tools and routes that serve them render it.
 *
 * Paging ([ListPage.of], envelope amendment U22): every page reports the full `total`; a page's `next` cursor
 * encodes `offset + items.size` and is present exactly when items exist after the page, so the final page of a
 * multi-page list has no cursor. Pages are disjoint and stable for unchanged data.
 */
class WorkViewQueries(
    private val unitOfWork: UnitOfWork,
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
    ): ListPage<WorkItem> =
        unitOfWork.read {
            val slice = stores.claimStore().findEligible(criteria.toEligibilityQuery(includeClaimed), page.limit, page.offset)
            pageOf(slice.items, slice.total, page)
        }

    /**
     * One page of the blocked items in [scope]: role BLOCKED ([BlockType.EXPLICIT]) or QUEUE/WORK/REVIEW with at
     * least one unsatisfied blocking dependency ([BlockType.DEPENDENCY]); never TERMINAL. Each entry lists every
     * blocking dependency into the item (satisfied ones too), ordered by blocker id. BLOCKED items come first,
     * then queue, work, review; ties by id.
     */
    suspend fun blocked(
        scope: BlockedScope = BlockedScope(),
        page: PageRequest = PageRequest(),
    ): ListPage<BlockedEntry> =
        unitOfWork.read {
            val slice = stores.itemStore().findBlocked(scope.parentId, scope.ancestorId, page.limit, page.offset)
            val ids = slice.items.map { it.id }.toSet()
            val edges = stores.dependencyRepository().blockingEdgesFor(ids)
            val blockers = stores.itemStore().findByIdSet(edges.map { it.blocker }.toSet()).associateBy { it.id }
            val edgesByBlocked = edges.groupBy { it.blocked }
            val entries =
                slice.items.map { item ->
                    val views =
                        (edgesByBlocked[item.id] ?: emptyList())
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
                    BlockedEntry(item, if (item.role == Role.BLOCKED) BlockType.EXPLICIT else BlockType.DEPENDENCY, views)
                }
            pageOf(entries, slice.total, page)
        }

    /**
     * The overview selected by [request]: global (parentless roots), scoped ([OverviewRequest.itemId]: the item,
     * its direct child counts and a page of its children) or anchored ([OverviewRequest.anchorId]: a page of the
     * anchor's children with full-subtree descendant counts). [OverviewView.NotFound] when the item or anchor does
     * not exist.
     */
    suspend fun overview(request: OverviewRequest): OverviewView =
        unitOfWork.read {
            val items = stores.itemStore()
            val page = request.page
            val scope = request.scope
            when {
                request.itemId != null -> {
                    val item = items.getById(request.itemId) ?: return@read OverviewView.NotFound(request.itemId)
                    val slice = items.findChildrenPage(item.id, request.excludeTerminal, page.limit, page.offset, scope)
                    // One grouped count serves the item's own (unfiltered) child counts and each listed child's counts.
                    val counts = items.countByParentAndRole(slice.items.map { it.id }.toSet() + item.id, scope)
                    val entries = slice.items.map { OverviewEntry(it, counts[it.id] ?: emptyMap()) }
                    OverviewView.Scoped(item, counts[item.id] ?: emptyMap(), pageOf(entries, slice.total, page))
                }

                request.anchorId != null -> {
                    val anchor = items.getById(request.anchorId) ?: return@read OverviewView.NotFound(request.anchorId)
                    val slice = items.findChildrenPage(anchor.id, request.excludeTerminal, page.limit, page.offset, scope)
                    val rollup = items.rollupByTopChild(slice.items.map { it.id }.toSet(), scope)
                    val entries = slice.items.map { OverviewEntry(it, rollup[it.id] ?: emptyMap()) }
                    OverviewView.Anchored(anchor, pageOf(entries, slice.total, page))
                }

                else -> {
                    val slice = items.findChildrenPage(null, request.excludeTerminal, page.limit, page.offset, scope)
                    val rootIds = slice.items.map { it.id }.toSet()
                    val claims = stores.claimStore().claimSummaryByParent(rootIds, scope)
                    val children = if (request.includeChildren) globalChildren(rootIds, request.excludeTerminal, scope) else null
                    val counts = items.countByParentAndRole(rootIds + (children?.childIds ?: emptySet()), scope)
                    val entries =
                        slice.items.map { root ->
                            OverviewEntry(
                                item = root,
                                childCounts = counts[root.id] ?: emptyMap(),
                                claimSummary = claims[root.id],
                                children =
                                    children?.let { c ->
                                        (c.byParent[root.id] ?: emptyList()).map { OverviewEntry(it, counts[it.id] ?: emptyMap()) }
                                    },
                            )
                        }
                    OverviewView.Global(pageOf(entries, slice.total, page))
                }
            }
        }

    /**
     * The health / session-resume item data for [scope]: a page of the active (WORK, REVIEW) items, a page of the
     * role-BLOCKED items (both with exact totals), the claim summary over the scope, and the notes of every active
     * item on the page in one batched read. Stalled classification (which needs schemas) is left to the caller.
     */
    suspend fun context(
        scope: ContextScope = ContextScope(),
        page: PageRequest = PageRequest(),
    ): ContextView =
        unitOfWork.read {
            val items = stores.itemStore()
            val rootIds = scope.ancestorId?.let { setOf(it) }
            val active = items.findByRoles(setOf(Role.WORK, Role.REVIEW), rootIds, page.limit, page.offset)
            val blocked = items.findByRoles(setOf(Role.BLOCKED), rootIds, page.limit, page.offset)
            val claims = stores.claimStore().claimSummary(rootIds)
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

    /** Global `includeChildren`: the roots' direct children, with the `excludeTerminal` rule applied in batch. */
    private suspend fun ReadScope.globalChildren(
        rootIds: Set<UUID>,
        excludeTerminal: Boolean,
        scope: AccessScope,
    ): Children {
        val all = stores.itemStore().findByParents(rootIds, scope)
        val visible =
            if (!excludeTerminal) {
                all
            } else {
                val terminal =
                    all.values
                        .flatten()
                        .filter { it.role == Role.TERMINAL }
                        .map { it.id }
                        .toSet()
                val below = stores.itemStore().rollupByTopChild(terminal, scope)
                all.mapValues { (_, kids) ->
                    kids.filter { kid ->
                        kid.role != Role.TERMINAL || (below[kid.id] ?: emptyMap()).any { (role, n) -> role != Role.TERMINAL && n > 0 }
                    }
                }
            }
        return Children(
            visible,
            visible.values
                .flatten()
                .map { it.id }
                .toSet()
        )
    }

    private class Children(
        val byParent: Map<UUID, List<WorkItem>>,
        val childIds: Set<UUID>,
    )

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
