package io.github.jpicklyk.mcptask.current.domain.model

import java.time.Instant

/**
 * The three claim states a work item can be filtered or counted by. Partition at an instant `at`:
 * [UNCLAIMED] has no `claimedBy`; [CLAIMED] has an active [ClaimState]; [EXPIRED] has a `claimedBy`
 * whose claim is not active (past its expiry, or missing its timestamps).
 */
enum class ClaimStatus(
    val wire: String
) {
    CLAIMED("claimed"),
    UNCLAIMED("unclaimed"),
    EXPIRED("expired");

    companion object {
        /** Parses the wire value (case-insensitive); null when unknown. */
        fun fromWire(value: String?): ClaimStatus? = value?.let { v -> entries.firstOrNull { it.wire.equals(v.trim(), ignoreCase = true) } }

        /** Classifies [item] at [at]. */
        fun of(
            item: WorkItem,
            at: Instant
        ): ClaimStatus =
            when {
                item.claimedBy == null -> UNCLAIMED
                ClaimState.isActive(item, at) -> CLAIMED
                else -> EXPIRED
            }
    }
}
