package io.github.jpicklyk.mcptask.current.domain.model

import java.time.Instant

/**
 * The claim held on a work item, as one value. THE definition of "active": a claim is active while
 * `expiresAt` is strictly after the instant being asked about, so an instant equal to `expiresAt` is
 * already expired. Every Kotlin-side freshness decision goes through [isActive]; the matching SQL
 * predicates live in one place (the SQLite claim store).
 */
data class ClaimState(
    val claimedBy: String,
    val claimedAt: Instant,
    val expiresAt: Instant,
    val originalClaimedAt: Instant?
) {
    /** True while the claim still holds at [at]; false at exactly [expiresAt] and after. */
    fun isActive(at: Instant): Boolean = expiresAt.isAfter(at)

    companion object {
        /**
         * The claim on [item], or null when it carries none. A row with `claimedBy` set but no
         * `claimedAt` or no expiry has no usable claim and is never active.
         */
        fun of(item: WorkItem): ClaimState? {
            val by = item.claimedBy ?: return null
            val at = item.claimedAt ?: return null
            val expires = item.claimExpiresAt ?: return null
            return ClaimState(by, at, expires, item.originalClaimedAt)
        }

        /** The timestamp-ordering violations among a claim's raw fields (empty when the order is sound). */
        fun orderingViolations(
            claimedAt: Instant?,
            expiresAt: Instant?,
            originalClaimedAt: Instant?
        ): List<String> {
            val out = mutableListOf<String>()
            if (claimedAt != null && expiresAt != null && claimedAt.isAfter(expiresAt)) {
                out += "claimedAt must not be after claimExpiresAt"
            }
            if (originalClaimedAt != null && claimedAt != null && originalClaimedAt.isAfter(claimedAt)) {
                out += "originalClaimedAt must not be after claimedAt"
            }
            return out
        }

        /** True when [item] has a claim that is still active at [at]. */
        fun isActive(
            item: WorkItem,
            at: Instant
        ): Boolean = of(item)?.isActive(at) ?: false
    }
}
