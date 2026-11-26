package io.github.jpicklyk.mcptask.current.lifecycle

import io.github.jpicklyk.mcptask.current.claim.ClaimStatusCounts
import io.github.jpicklyk.mcptask.current.model.item.BlockedReason
import io.github.jpicklyk.mcptask.current.model.item.Role
import io.github.jpicklyk.mcptask.current.model.item.WorkItem
import io.github.jpicklyk.mcptask.current.model.note.Note
import io.github.jpicklyk.mcptask.current.platform.port.ListPage
import java.util.UUID

/*
 * The plain data the read model returns ([WorkViewQueries]). No JSON: the tools and routes that serve a view
 * render it. Every list is a [ListPage] whose `total` was counted in the same read unit as its items.
 */

/** Which items [WorkViewQueries.blocked] considers: direct children of [parentId] and/or the subtree of [ancestorId] (inclusive), AND-ed. */
data class BlockedScope(
    val parentId: UUID? = null,
    val ancestorId: UUID? = null,
)

/** Why the `next` view is empty although items matched its selector. */
enum class NextEmptyReason {
    /** Matched items are held by an active claim (their own, or an ancestor's). */
    CLAIM_HELD,

    /** Matched items are free of claims but wait on an unsatisfied blocking dependency. */
    DEPENDENCY_UNMET,
}

/**
 * One page of the `next` view: [page] of the eligible items with the exact eligible total, and [emptyReason] set only when
 * nothing is eligible although items matched the selector (null when something is eligible, and when nothing matched).
 */
data class NextView(
    val page: ListPage<WorkItem>,
    val emptyReason: NextEmptyReason? = null,
)

/** Why an item is in the blocked view. */
enum class BlockType {
    /** The item's role is BLOCKED. */
    EXPLICIT,

    /** The item is QUEUE, WORK or REVIEW with at least one unsatisfied blocking dependency. */
    DEPENDENCY,
}

/**
 * One blocking dependency of a blocked item.
 *
 * @property blockerId The item that blocks.
 * @property title The blocker's title, or null when its row could not be read.
 * @property role The blocker's role, or null when its row could not be read (never satisfies).
 * @property threshold The role the blocker must reach (the edge's `unblockAt`, TERMINAL when unset).
 * @property satisfied Whether [role] is at or beyond [threshold] (BLOCKED and unknown never are).
 */
data class BlockerView(
    val blockerId: UUID,
    val title: String?,
    val role: Role?,
    val threshold: Role,
    val satisfied: Boolean,
)

/**
 * One entry of the blocked view: the item, why it is listed, and EVERY blocking dependency into it (satisfied
 * ones included), in a stable order.
 */
data class BlockedEntry(
    val item: WorkItem,
    val blockType: BlockType,
    val blockers: List<BlockerView>,
    /** EXPLICIT entries: the item's effective typed reason (a legacy NULL reads as manual); null on a DEPENDENCY entry. */
    val reason: BlockedReason? = null,
    /** The decision an awaiting_decision entry waits on. */
    val decisionId: UUID? = null,
    /**
     * Awaiting-decision entries only: true when the decision is answered, withdrawn or missing (the block can be
     * resumed), false while it is open or acknowledged; null on every other entry.
     */
    val resumable: Boolean? = null,
) {
    /** The number of [blockers] not yet satisfied (at least 1 for [BlockType.DEPENDENCY]). */
    val unsatisfiedCount: Int get() = blockers.count { !it.satisfied }
}

/** Which items [WorkViewQueries.context] considers: the subtree of [ancestorId] (inclusive), or everything. */
data class ContextScope(
    val ancestorId: UUID? = null,
)

/**
 * The health / session-resume item data.
 *
 * @property active WORK and REVIEW items (work first, then longest in the role first).
 * @property blocked The blocked items (the [WorkViewQueries.blocked] set: role BLOCKED, or open with an unsatisfied
 *   blocking dependency), BLOCKED first, then queue, work, review; no blocker detail.
 * @property claimSummary Claim-status counts over every item in scope.
 * @property notes The notes of each item on the [active] page, in one batched read. Every listed active id is a
 *   key (an empty list when it has none).
 */
data class ContextView(
    val active: ListPage<WorkItem>,
    val blocked: ListPage<WorkItem>,
    val claimSummary: ClaimStatusCounts,
    val notes: Map<UUID, List<Note>>,
)
