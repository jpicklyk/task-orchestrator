package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.ActorKind
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.VerificationResult
import io.github.jpicklyk.mcptask.current.domain.model.VerificationStatus
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Independent tests (test-author seat, item 46a83aca) for [FenceGuard] and [ClaimHolder]: scenario S1 of the frozen test-plan.
 *
 * Oracles (frozen, planning phase; none read from the implementation): task-scope item 2 (`Ok` unless the claim is active and the
 * caller is not the holder; a null caller on a fenced item is refused; the holder id never appears in the message, detail or
 * fix args), task-scope item 1 (`holds` is an exact, case-sensitive compare; `assignmentId` stays null and is not compared until
 * R22), the pre-existing `ClaimState` KDoc (a claim is active while `expiresAt` is strictly after the instant asked about, so an
 * instant equal to `expiresAt` is already expired; a row with `claimedBy` set but no expiry has no usable claim) and the error
 * catalog declaration (`not_claim_holder`, permanent, HTTP 403, detail `NotClaimHolder{itemId}`).
 *
 * Every cell of S1 is NEW-SURFACE (FenceGuard and ClaimHolder are new types). The partial-claim fixture cannot satisfy the
 * all-or-none claim invariant, so it is built through the declared `diagnostics` parameter, exactly as ClaimStatePredicateTest does.
 */
class FenceGuardTest {
    private val now: Instant = Instant.parse("2001-02-03T04:05:06.789Z")
    private val holderB = "agent-b"
    private val callerA = ClaimHolder("agent-a")

    /** A claim by [by] taken a minute before it expires at [expiresAt]; claimedAt <= expiresAt and originalClaimedAt == claimedAt by construction. */
    private fun claimed(
        by: String = holderB,
        expiresAt: Instant
    ): WorkItem {
        val claimedAt = expiresAt.minusSeconds(60)
        return WorkItem(
            title = "claimed",
            claimedBy = by,
            claimedAt = claimedAt,
            claimExpiresAt = expiresAt,
            originalClaimedAt = claimedAt
        )
    }

    private fun refusal(outcome: Outcome<Unit>): DomainError =
        when (outcome) {
            is Outcome.Err -> outcome.error
            is Outcome.Ok -> fail("expected not_claim_holder, got Ok")
        }

    private fun assertAllowed(
        item: WorkItem,
        caller: ClaimHolder?,
        label: String
    ) {
        val outcome = FenceGuard.check(item, caller, now)
        assertTrue(outcome is Outcome.Ok, "$label: expected Ok, got $outcome")
    }

    private fun assertFenced(
        item: WorkItem,
        caller: ClaimHolder?,
        label: String
    ): DomainError {
        val error = refusal(FenceGuard.check(item, caller, now))
        assertEquals(ErrorCode.NOT_CLAIM_HOLDER, error.code, "$label: wrong code")
        return error
    }

    // ---- S1: the refusal ---------------------------------------------------------------------------------------

    @Test
    fun `S1 an active claim by another principal refuses the caller with not_claim_holder naming only the item`() {
        val item = claimed(expiresAt = now.plusSeconds(60))

        val error = assertFenced(item, callerA, "active by B, caller A")

        assertEquals(ErrorDetail.NotClaimHolder(item.id), error.detail, "the detail carries the item id and nothing else")
        for (secret in listOf(holderB, callerA.principalId)) {
            assertFalse(secret in error.message, "message must not name a principal: ${error.message}")
            assertFalse(secret in error.detail.toString(), "detail must not name a principal: ${error.detail}")
            assertFalse(secret in error.toString(), "no field of the error may name a principal: $error")
        }
    }

    @Test
    fun `S1 notClaimHolder builds the same code and detail the guard refuses with`() {
        val item = claimed(expiresAt = now.plusSeconds(60))
        val fromGuard = assertFenced(item, callerA, "guard")

        val built = FenceGuard.notClaimHolder(item.id)

        assertEquals(ErrorCode.NOT_CLAIM_HOLDER, built.code)
        assertEquals(ErrorDetail.NotClaimHolder(item.id), built.detail)
        assertEquals(fromGuard.message, built.message, "one message wording for both producers")
    }

    @Test
    fun `S1 the error code is the permanent 403 not_claim_holder of the catalog`() {
        assertEquals("not_claim_holder", ErrorCode.NOT_CLAIM_HOLDER.wire)
        assertEquals(403, ErrorCode.NOT_CLAIM_HOLDER.httpStatus)
    }

    // ---- S1: the holder and the lapsed/absent claim cells --------------------------------------------------------

    @Test
    fun `S1 the holder itself is allowed, also one millisecond before its own expiry`() {
        assertAllowed(claimed(by = "agent-a", expiresAt = now.plusSeconds(60)), callerA, "active by A, caller A")
        assertAllowed(claimed(by = "agent-a", expiresAt = now.plusMillis(1)), callerA, "E - 1 ms, holder")
    }

    @Test
    fun `S1 boundary - another principal is still fenced one millisecond before expiry and free at exactly expiry`() {
        assertFenced(claimed(expiresAt = now.plusMillis(1)), callerA, "E - 1 ms, non-holder")
        assertAllowed(claimed(expiresAt = now), callerA, "expiresAt == now is already lapsed")
        assertAllowed(claimed(expiresAt = now.minusMillis(1)), callerA, "E + 1 ms is lapsed")
    }

    @Test
    fun `S1 a long-expired claim fences nothing`() {
        assertAllowed(claimed(expiresAt = now.minusSeconds(3600)), callerA, "expired claim, caller A")
        assertAllowed(claimed(expiresAt = now.minusSeconds(3600)), null, "expired claim, null caller")
    }

    @Test
    fun `S1 an unclaimed item fences nothing, for a caller and for none`() {
        val item = WorkItem(title = "unclaimed")

        assertAllowed(item, callerA, "unclaimed, caller A")
        assertAllowed(item, null, "unclaimed, null caller")
    }

    @Test
    fun `S1 a row with claimedBy set but no expiry has no usable claim and fences nothing`() {
        val partial =
            WorkItem(
                title = "partial claim",
                claimedBy = holderB,
                claimedAt = now.minusSeconds(60),
                claimExpiresAt = null,
                originalClaimedAt = null,
                diagnostics =
                    listOf(
                        "Claim fields (claimedBy, claimedAt, claimExpiresAt, originalClaimedAt) must all be set or all be null"
                    )
            )

        assertAllowed(partial, callerA, "claimedBy without expiry, caller A")
        assertAllowed(partial, null, "claimedBy without expiry, null caller")
    }

    // ---- S1: the null caller fails closed ------------------------------------------------------------------------

    @Test
    fun `S1 a null caller on an actively claimed item is refused, fail closed`() {
        val item = claimed(expiresAt = now.plusSeconds(60))

        val error = assertFenced(item, null, "active by B, null caller")

        assertEquals(ErrorDetail.NotClaimHolder(item.id), error.detail)
    }

    @Test
    fun `S1 a null caller is refused even when the claim is held by the same string the caller might have used`() {
        // The claim is by "agent-a"; a null caller is no principal at all, so it cannot be the holder.
        assertFenced(claimed(by = "agent-a", expiresAt = now.plusSeconds(60)), null, "active by A, null caller")
    }

    // ---- S1 probes: the holder compare is exact -----------------------------------------------------------------

    @Test
    fun `probe holder ids differing by case, trailing space, a leading space or emptiness are all fenced`() {
        val item = claimed(by = "agent-b", expiresAt = now.plusSeconds(60))

        for (spelling in listOf("Agent-B", "AGENT-B", "agent-b ", " agent-b", "agent-b\u0000", "agent-", "agent-bb", "")) {
            assertFenced(item, ClaimHolder(spelling), "caller spelled '${spelling.length} chars: $spelling'")
        }
        assertAllowed(item, ClaimHolder("agent-b"), "control: the exact spelling is the holder")
    }

    @Test
    fun `probe the assignment id is not compared - same principal with and without an assignment is the holder`() {
        val item = claimed(by = "agent-a", expiresAt = now.plusSeconds(60))

        assertAllowed(item, ClaimHolder("agent-a", UUID.randomUUID()), "holder with an assignment id")
        assertAllowed(item, ClaimHolder("agent-a", null), "holder without one")
        assertFenced(
            claimed(expiresAt = now.plusSeconds(60)),
            ClaimHolder("agent-a", UUID.randomUUID()),
            "non-holder with an assignment id"
        )
    }

    // ---- ClaimHolder ---------------------------------------------------------------------------------------------

    @Test
    fun `S1 holds is an exact case-sensitive compare of the principal id with the claimant`() {
        val item = claimed(by = "agent-b", expiresAt = now.plusSeconds(60))

        assertTrue(ClaimHolder("agent-b").holds(item))
        assertFalse(ClaimHolder("Agent-B").holds(item))
        assertFalse(ClaimHolder("agent-b ").holds(item))
        assertFalse(ClaimHolder("agent-a").holds(item))
        assertFalse(ClaimHolder("agent-b").holds(WorkItem(title = "unclaimed")), "no claimant means nobody holds it")
    }

    @Test
    fun `S1 a holder constructed without an assignment carries a null assignment id`() {
        assertNull(ClaimHolder("agent-a").assignmentId, "assignmentId stays null until R22")
    }

    @Test
    fun `S1 resolve under accept-cached builds a holder for the claimed actor id with no assignment`() {
        val actor = ActorClaim(id = "agent-a", kind = ActorKind.SUBAGENT)
        val verification = VerificationResult(status = VerificationStatus.UNCHECKED, verifier = "noop")

        val resolved = ClaimHolder.resolve(actor, verification, DegradedModePolicy.ACCEPT_CACHED)

        val holder = assertIs<Outcome.Ok<ClaimHolder>>(resolved).value
        assertEquals("agent-a", holder.principalId)
        assertNull(holder.assignmentId, "assignmentId stays null until R22")
    }
}
