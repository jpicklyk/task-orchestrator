package io.github.jpicklyk.mcptask.current.item

import io.github.jpicklyk.mcptask.current.application.port.ItemStoreProvider
import io.github.jpicklyk.mcptask.current.model.item.Role
import io.github.jpicklyk.mcptask.current.model.item.WorkItem
import io.github.jpicklyk.mcptask.current.platform.port.AccessScope
import io.github.jpicklyk.mcptask.current.platform.port.ListPage
import io.github.jpicklyk.mcptask.current.platform.port.PageRequest
import io.github.jpicklyk.mcptask.current.platform.port.ReadScope
import io.github.jpicklyk.mcptask.current.platform.port.UnitOfWork
import java.util.UUID

/**
 * The overview read model: the hierarchy summary an `items_read` `overview` serves, in ONE read unit with a bounded
 * number of statements (an empty id set skips its statement), whatever the number of rows, children or claims behind it.
 * Lists are paged ([PageRequest]) with a stable order ending in an `id` tiebreak; each page's `total` is counted in the
 * same unit as its items, and paging follows [ListPage.of] (envelope amendment U22). The view is plain data
 * ([OverviewView]) that the tool renders.
 */
class OverviewQueries(
    private val unitOfWork: UnitOfWork<ItemStoreProvider>,
) {
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
                    val ids = slice.items.map { it.id }.toSet() + item.id
                    val counts = items.countByParentAndRole(ids, scope)
                    val blocked = items.countBlockedByParentAndReason(ids, scope)
                    val entries =
                        slice.items.map {
                            OverviewEntry(
                                it,
                                counts[it.id] ?: emptyMap(),
                                blockedByReason =
                                    blocked[it.id] ?: emptyMap()
                            )
                        }
                    OverviewView.Scoped(
                        item,
                        counts[item.id] ?: emptyMap(),
                        pageOf(entries, slice.total, page),
                        blocked[item.id] ?: emptyMap()
                    )
                }

                request.anchorId != null -> {
                    val anchor = items.getById(request.anchorId) ?: return@read OverviewView.NotFound(request.anchorId)
                    val slice = items.findChildrenPage(anchor.id, request.excludeTerminal, page.limit, page.offset, scope)
                    val topIds = slice.items.map { it.id }.toSet()
                    val rollup = items.rollupByTopChild(topIds, scope)
                    val blockedRollup = items.rollupBlockedByTopChild(topIds, scope)
                    val entries =
                        slice.items.map {
                            OverviewEntry(it, rollup[it.id] ?: emptyMap(), blockedByReason = blockedRollup[it.id] ?: emptyMap())
                        }
                    OverviewView.Anchored(anchor, pageOf(entries, slice.total, page))
                }

                else -> {
                    val slice = items.findChildrenPage(null, request.excludeTerminal, page.limit, page.offset, scope)
                    val rootIds = slice.items.map { it.id }.toSet()
                    val claims = stores.claimStore().claimSummaryByParent(rootIds, scope)
                    val children = if (request.includeChildren) globalChildren(rootIds, request.excludeTerminal, scope) else null
                    val countIds = rootIds + (children?.childIds ?: emptySet())
                    val counts = items.countByParentAndRole(countIds, scope)
                    val blocked = items.countBlockedByParentAndReason(countIds, scope)
                    val entries =
                        slice.items.map { root ->
                            OverviewEntry(
                                item = root,
                                childCounts = counts[root.id] ?: emptyMap(),
                                claimSummary = claims[root.id],
                                blockedByReason = blocked[root.id] ?: emptyMap(),
                                children =
                                    children?.let { c ->
                                        (c.byParent[root.id] ?: emptyList()).map {
                                            OverviewEntry(it, counts[it.id] ?: emptyMap(), blockedByReason = blocked[it.id] ?: emptyMap())
                                        }
                                    },
                            )
                        }
                    OverviewView.Global(pageOf(entries, slice.total, page))
                }
            }
        }

    /** Global `includeChildren`: the roots' direct children, with the `excludeTerminal` rule applied in batch. */
    private suspend fun ReadScope<ItemStoreProvider>.globalChildren(
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

    private fun <T> pageOf(
        items: List<T>,
        total: Int,
        page: PageRequest,
    ): ListPage<T> {
        // Page and count share one read unit, so total >= items.size; the max only guards the ListPage invariant.
        return ListPage.of(items, total = maxOf(total, items.size), page = page)
    }
}
