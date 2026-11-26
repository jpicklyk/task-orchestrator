package io.github.jpicklyk.mcptask.current.item

import io.github.jpicklyk.mcptask.current.claim.ClaimStatusCounts
import io.github.jpicklyk.mcptask.current.model.item.BlockedReason
import io.github.jpicklyk.mcptask.current.model.item.Role
import io.github.jpicklyk.mcptask.current.model.item.WorkItem
import io.github.jpicklyk.mcptask.current.platform.port.AccessScope
import io.github.jpicklyk.mcptask.current.platform.port.ListPage
import io.github.jpicklyk.mcptask.current.platform.port.PageRequest
import java.util.UUID

/*
 * The plain data the overview read model returns ([OverviewQueries]). No JSON: the tool that serves a view renders it.
 */

/**
 * The request of [OverviewQueries.overview]. At most one of [itemId] (scoped) and [anchorId] (anchored); neither
 * means the global overview over parentless roots.
 *
 * @property page The page of the listed items: roots (global), the item's children (scoped) or the anchor's
 *   children (anchored).
 * @property excludeTerminal Drop TERMINAL items from the listed items (and from the global `children`) unless they
 *   still have a non-terminal descendant; applied identically in all three modes. Counts are never filtered.
 * @property includeChildren Global mode only: attach each root's direct children.
 * @property scope What the caller may see. The view is visible-only: listed items, `childCounts`, roll-ups, claim
 *   summaries and the `excludeTerminal` "open below" test count visible items only, in SQL. The requested
 *   [itemId]/[anchorId] itself is NOT checked here: the caller authorizes it first (`ScopeGuard`).
 */
data class OverviewRequest(
    val itemId: UUID? = null,
    val anchorId: UUID? = null,
    val page: PageRequest = PageRequest(),
    val excludeTerminal: Boolean = false,
    val includeChildren: Boolean = false,
    val scope: AccessScope = AccessScope.unrestricted(),
) {
    init {
        require(itemId == null || anchorId == null) { "itemId and anchorId are mutually exclusive" }
    }
}

/**
 * One listed item of an overview.
 *
 * @property childCounts Counts by role, never including the item itself: its DIRECT children in the global and
 *   scoped modes, ALL its descendants in the anchored mode. Roles with zero items are omitted.
 * @property claimSummary Global mode: claim-status counts over the item's direct children; null elsewhere.
 * @property blockedByReason The BLOCKED part of [childCounts] split by typed reason (NULL counts as manual); zero counts omitted.
 * @property children Global mode with `includeChildren`: the item's direct children (each with its own direct
 *   [childCounts]); null elsewhere.
 */
data class OverviewEntry(
    val item: WorkItem,
    val childCounts: Map<Role, Int>,
    val claimSummary: ClaimStatusCounts? = null,
    val children: List<OverviewEntry>? = null,
    val blockedByReason: Map<BlockedReason, Int> = emptyMap(),
)

/** The result of [OverviewQueries.overview]. */
sealed interface OverviewView {
    /** Parentless roots, newest `createdAt` first. */
    data class Global(
        val roots: ListPage<OverviewEntry>,
    ) : OverviewView

    /** [item], its unfiltered direct [childCounts], and a page of its direct children. */
    data class Scoped(
        val item: WorkItem,
        val childCounts: Map<Role, Int>,
        val children: ListPage<OverviewEntry>,
        val blockedByReason: Map<BlockedReason, Int> = emptyMap(),
    ) : OverviewView

    /** A page of [anchor]'s direct children, each with its full-subtree descendant counts. */
    data class Anchored(
        val anchor: WorkItem,
        val items: ListPage<OverviewEntry>,
    ) : OverviewView

    /** The requested `itemId` / `anchorId` does not exist. */
    data class NotFound(
        val id: UUID,
    ) : OverviewView
}
