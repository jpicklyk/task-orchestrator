package io.github.jpicklyk.mcptask.current.platform.sqlite.repository

import io.github.jpicklyk.mcptask.current.model.claim.ClaimStatus
import io.github.jpicklyk.mcptask.current.platform.sqlite.schema.WorkItemsTable
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import java.time.Instant
import java.util.UUID

/**
 * THE claim predicates: the only place SQL decides whether a claim is active, expired or acquirable. They are the
 * SQL form of `ClaimState.isActive` (active iff `claim_expires_at > at`, so an expiry equal to `at` is expired; a
 * `claimed_by` with no usable timestamps is never active). Every claim-aware query in the item and claim stores
 * builds its condition here; a guard test keeps `claim_expires_at` comparisons out of every other file.
 */
internal object ClaimPredicates {
    /** `claimed_by` is set and the claim is still running at [at]. */
    fun active(at: Instant): Op<Boolean> =
        WorkItemsTable.claimedBy.isNotNull() and
            WorkItemsTable.claimedAt.isNotNull() and
            WorkItemsTable.claimExpiresAt.isNotNull() and
            (WorkItemsTable.claimExpiresAt greater at)

    /** `claimed_by` is set but the claim is not active at [at] (lapsed, or missing its timestamps). */
    fun expired(at: Instant): Op<Boolean> =
        WorkItemsTable.claimedBy.isNotNull() and
            (WorkItemsTable.claimedAt.isNull() or WorkItemsTable.claimExpiresAt.isNull() or (WorkItemsTable.claimExpiresAt lessEq at))

    /** `claimed_by` is set with a recorded expiry that is at or before [at]: a claim that ran out. */
    fun lapsed(at: Instant): Op<Boolean> =
        WorkItemsTable.claimedBy.isNotNull() and
            WorkItemsTable.claimExpiresAt.isNotNull() and
            (WorkItemsTable.claimExpiresAt lessEq at)

    /** Nobody holds the item. */
    fun unclaimed(): Op<Boolean> = WorkItemsTable.claimedBy.isNull()

    /** No active claim at [at]: [unclaimed] or [expired]. */
    fun notActive(at: Instant): Op<Boolean> = unclaimed() or expired(at)

    /**
     * The claim columns name the holder pair ([agentId], [assignmentId]) exactly: `claimed_by = agentId` AND
     * `claim_assignment_id IS assignmentId` (null-safe, so a principal-level holder never matches a seat's claim and a
     * seat never matches a principal-level claim). Always TRUE or FALSE, never NULL, on a row with `claimed_by` set.
     */
    fun holderIs(
        agentId: String,
        assignmentId: UUID?
    ): Op<Boolean> {
        val assignment =
            if (assignmentId == null) {
                WorkItemsTable.claimAssignmentId.isNull()
            } else {
                WorkItemsTable.claimAssignmentId.isNotNull() and (WorkItemsTable.claimAssignmentId eq assignmentId)
            }
        return WorkItemsTable.claimedBy.isNotNull() and (WorkItemsTable.claimedBy eq agentId) and assignment
    }

    /** The holder pair ([agentId], [assignmentId]) may take or refresh the item at [at]: no active claim, or already its own. */
    fun acquirable(
        agentId: String,
        assignmentId: UUID?,
        at: Instant
    ): Op<Boolean> = notActive(at) or holderIs(agentId, assignmentId)

    /** The condition for a [ClaimStatus] filter at [at]. */
    fun forStatus(
        status: ClaimStatus,
        at: Instant
    ): Op<Boolean> =
        when (status) {
            ClaimStatus.CLAIMED -> active(at)
            ClaimStatus.UNCLAIMED -> unclaimed()
            ClaimStatus.EXPIRED -> expired(at)
        }
}
