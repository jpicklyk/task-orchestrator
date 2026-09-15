package io.github.jpicklyk.mcptask.current.domain.model

/**
 * An item's resolved ancestor chain plus an explicit completeness signal.
 *
 * Returned by
 * [io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository.findAncestorChainsDetailed].
 * The plain
 * [io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository.findAncestorChains]
 * returns only [ancestors], which makes a truncated chain indistinguishable from a genuinely
 * shallow one. Callers that make a safety decision on chain completeness (root-scope
 * authorization, reparent cycle guards) should use the detailed form and treat
 * `truncated == true` as "unknown", never as "no further ancestors".
 *
 * @property ancestors       Ancestors ordered root-first, excluding the item itself. Possibly
 *   partial when [truncated] is true — the entries present are still correct and root-first
 *   relative to each other, but the walk stopped before reaching a parentless root.
 * @property truncated       True when the upward walk stopped early instead of reaching an item
 *   with no parent.
 * @property truncationReason Why the walk stopped, or null when [truncated] is false. One of
 *   [REASON_CYCLE] or [REASON_MISSING_ANCESTOR].
 */
data class AncestorChain(
    val ancestors: List<WorkItem>,
    val truncated: Boolean,
    val truncationReason: String? = null,
) {
    companion object {
        /** The walk revisited an ancestor — the `parent_id` graph contains a cycle. */
        const val REASON_CYCLE = "cycle"

        /**
         * The next ancestor row was absent or failed domain validation
         * (dropped by the repository's row mapper), so the chain above it is unknown.
         */
        const val REASON_MISSING_ANCESTOR = "missing-ancestor"
    }
}
