package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.port.ClaimResult
import io.github.jpicklyk.mcptask.current.application.port.ReleaseResult
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.ActorKind
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.validation.ValidationException
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.str
import io.github.jpicklyk.mcptask.current.test.SettableClock
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item 95a22e6e, R22) for the claim HOLDER PAIR: a claim is held by `(principal,
 * assignment)`, not by the principal alone, so two seats of one principal never evict or release each other. Scenarios S5 and S21
 * of the frozen test-plan, the `FenceGuard` / `ClaimHolder.holds` half of decision D10, and the `WorkItem` invariant.
 *
 * Oracles: task-scope "Holder" (`(principal, assignment)` in `ClaimService`; auto-release is per holder; `FenceGuard` compares the
 * pair), D10 (`holds` = the principal AND the assignment both equal, a null assignment equals only a null assignment; a held claim
 * is cleared and re-written with its holder's pair; `WorkItem`: a claim assignment requires a claimant), and the pre-existing
 * ClaimService semantics of `ClaimServiceTest` (a holder claiming a second item releases its first with reason `superseded`;
 * a claim held by another is `AlreadyClaimed`; a release by a non-holder is `NotClaimedByYou`). The two assignment ids are fixed
 * literals, never read from a run.
 *
 * Negative-assertion fixture conditions: every "X is still held" assertion follows a second claim by the SAME principal under
 * another assignment, which under a principal-only compare would release X (control: the same principal with no assignment, and the
 * same holder twice, DO release it).
 *
 * Domain invariants satisfied by construction: held items carry all four claim fields (claimed-at is one minute before expiry and
 * the original claim equals it).
 *
 * Forbidden-construct declaration: none.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class ClaimHolderPairTest {
    private val clock = SettableClock(CLAIM_T)

    @RegisterExtension
    @JvmField
    val sqlite = SqliteTestDatabase.perMethod(clock = clock)

    private fun rig() = ClaimRig(sqlite.db, clock)

    private val principal = "agent-p"
    private val seatA: UUID = UUID.fromString("a5a5a5a5-0000-4000-8000-00000000000a")
    private val seatB: UUID = UUID.fromString("b5b5b5b5-0000-4000-8000-00000000000b")

    private val holderA = ClaimHolder(principal, seatA)
    private val holderB = ClaimHolder(principal, seatB)
    private val holderBare = ClaimHolder(principal)

    // ------------------------------------------------------------------ S5: sibling seats of one principal

    @Test
    fun `S5 two seats of one principal each hold their own claim - the second claim does not release the first`(): Unit =
        runBlocking {
            val rig = rig()
            val x = rig.seed("S5 X")
            val y = rig.seed("S5 Y")
            rig.service.claim(x.id, holderA, 600).ok()

            val (result, rows) = rig.written { rig.service.claim(y.id, holderB, 600).ok() }

            val success = assertIs<ClaimResult.Success>(result)
            assertEquals(emptyList(), success.releasedItemIds, "seat B's claim supersedes nothing of seat A")
            assertEquals(listOf("claim.acquired"), rows.map { it.type }, "no claim.released row: $rows")
            val heldX = rig.reload(x.id)
            assertEquals(principal, heldX.claimedBy, "X is still claimed")
            assertEquals(seatA, heldX.claimAssignmentId, "X is still held by seat A's pair")
            val heldY = rig.reload(y.id)
            assertEquals(principal, heldY.claimedBy)
            assertEquals(seatB, heldY.claimAssignmentId, "Y is held by seat B's pair")
        }

    @Test
    fun `S5 control - the same holder claiming a second item releases its first, with reason superseded`(): Unit =
        runBlocking {
            val rig = rig()
            val x = rig.seed("S5 control X")
            val z = rig.seed("S5 control Z")
            rig.service.claim(x.id, holderA, 600).ok()

            val (result, rows) = rig.written { rig.service.claim(z.id, holderA, 600).ok() }

            assertEquals(listOf(x.id), assertIs<ClaimResult.Success>(result).releasedItemIds)
            val released = rows.single { it.type == "claim.released" }
            assertEquals(x.id, released.entityId)
            assertEquals("superseded", released.str("reason"))
            assertNull(rig.reload(x.id).claimedBy, "X is really released")
            assertNull(rig.reload(x.id).claimAssignmentId, "its assignment is cleared with it")
        }

    @Test
    fun `S5 probe the principal-level holder (no assignment) neither evicts nor is evicted by a seat of the same principal`(): Unit =
        runBlocking {
            val rig = rig()
            val x = rig.seed("S5 bare X")
            val y = rig.seed("S5 bare Y")
            val w = rig.seed("S5 bare W")
            rig.service.claim(x.id, holderA, 600).ok()

            assertEquals(emptyList(), assertIs<ClaimResult.Success>(rig.service.claim(y.id, holderBare, 600).ok()).releasedItemIds)
            assertEquals(seatA, rig.reload(x.id).claimAssignmentId, "the bare claim left seat A's claim alone")

            // and the other way round: a seat claiming does not release the principal-level claim
            assertEquals(emptyList(), assertIs<ClaimResult.Success>(rig.service.claim(w.id, holderB, 600).ok()).releasedItemIds)
            assertEquals(principal, rig.reload(y.id).claimedBy, "the seat claim left the principal-level claim alone")
            assertNull(rig.reload(y.id).claimAssignmentId)

            // control: two principal-level claims of one principal DO supersede (the pre-seat behaviour is unchanged)
            val v = rig.seed("S5 bare V")
            assertEquals(listOf(y.id), assertIs<ClaimResult.Success>(rig.service.claim(v.id, holderBare, 600).ok()).releasedItemIds)
        }

    // ------------------------------------------------------------------ S21: the holder of one item

    @Test
    fun `S21 a seat's claim is AlreadyClaimed for the same principal under another seat or none, and the reverse`(): Unit =
        runBlocking {
            val rig = rig()
            val x = rig.seed("S21 X")
            val bareHeld = rig.seed("S21 bare-held")
            rig.service.claim(x.id, holderA, 600).ok()
            rig.service.claim(bareHeld.id, holderBare, 600).ok()
            clock.advanceSeconds(10)

            assertIs<ClaimResult.AlreadyClaimed>(rig.service.claim(x.id, holderB, 600).ok(), "other seat, same principal")
            assertIs<ClaimResult.AlreadyClaimed>(rig.service.claim(x.id, holderBare, 600).ok(), "no seat, same principal")
            assertIs<ClaimResult.AlreadyClaimed>(
                rig.service.claim(bareHeld.id, holderA, 600).ok(),
                "a seat against a principal-level claim"
            )
            assertEquals(seatA, rig.reload(x.id).claimAssignmentId, "seat A still holds X")
            assertNull(rig.reload(bareHeld.id).claimAssignmentId, "the principal-level claim is still principal-level")

            // control: the exact pair is the holder and refreshes its own claim
            assertIs<ClaimResult.Success>(rig.service.claim(x.id, holderA, 600).ok(), "the exact pair")
            assertIs<ClaimResult.Success>(rig.service.claim(bareHeld.id, holderBare, 600).ok(), "the exact bare holder")
        }

    @Test
    fun `S21 a release by another seat of the same principal, or by no seat, leaves the claim - only the exact pair releases it`(): Unit =
        runBlocking {
            val rig = rig()
            val x = rig.seed("S21 release")
            rig.service.claim(x.id, holderA, 600).ok()

            val (byOtherSeat, otherRows) = rig.written { rig.service.release(x.id, holderB).ok() }
            val (byBare, bareRows) = rig.written { rig.service.release(x.id, holderBare).ok() }
            assertIs<ReleaseResult.NotClaimedByYou>(byOtherSeat)
            assertIs<ReleaseResult.NotClaimedByYou>(byBare)
            assertEquals(emptyList(), otherRows)
            assertEquals(emptyList(), bareRows)
            assertEquals(seatA, rig.reload(x.id).claimAssignmentId, "control: the claim is still in place")

            val (byHolder, holderRows) = rig.written { rig.service.release(x.id, holderA).ok() }
            assertIs<ReleaseResult.Success>(byHolder)
            assertEquals(listOf("claim.released"), holderRows.map { it.type })
            assertNull(rig.reload(x.id).claimedBy)
            assertNull(rig.reload(x.id).claimAssignmentId, "the assignment is cleared with the claim")
        }

    @Test
    fun `S21 a bare-string release is the principal-level holder and cannot release a seat claim`(): Unit =
        runBlocking {
            val rig = rig()
            val x = rig.seed("S21 string")
            rig.service.claim(x.id, holderA, 600).ok()

            assertIs<ReleaseResult.NotClaimedByYou>(rig.service.release(x.id, principal).ok(), "the string overload carries no assignment")
            assertEquals(seatA, rig.reload(x.id).claimAssignmentId)

            // control: it does release a principal-level claim of the same principal
            val y = rig.seed("S21 string control")
            rig.service.claim(y.id, principal, 600).ok()
            assertIs<ReleaseResult.Success>(rig.service.release(y.id, principal).ok())
        }

    @Test
    fun `S21 the assignment does not outlive the claim - a later claim by another holder starts with its own pair`(): Unit =
        runBlocking {
            val rig = rig()
            val x = rig.seed("S21 lapse")
            rig.service.claim(x.id, holderA, 60).ok()
            clock.advanceSeconds(120)

            assertIs<ClaimResult.Success>(rig.service.claim(x.id, ClaimHolder("agent-q"), 60).ok(), "the lapsed claim is takeable")

            val taken = rig.reload(x.id)
            assertEquals("agent-q", taken.claimedBy)
            assertNull(taken.claimAssignmentId, "the old seat's assignment is not inherited by the new holder")
        }

    // ------------------------------------------------------------------ ClaimHolder.holds and FenceGuard

    private val now: Instant = Instant.parse("2001-02-03T04:05:06.789Z")

    private fun claimed(
        by: String,
        assignment: UUID?,
        expiresAt: Instant = now.plusSeconds(60)
    ): WorkItem {
        val claimedAt = expiresAt.minusSeconds(60)
        return WorkItem(
            title = "claimed",
            claimedBy = by,
            claimedAt = claimedAt,
            claimExpiresAt = expiresAt,
            originalClaimedAt = claimedAt,
            claimAssignmentId = assignment
        )
    }

    @Test
    fun `holds is true only when both the principal and the assignment are equal, and a null assignment equals only a null assignment`() {
        val seated = claimed(principal, seatA)
        assertTrue(holderA.holds(seated), "the exact pair")
        assertFalse(holderB.holds(seated), "same principal, other assignment")
        assertFalse(holderBare.holds(seated), "same principal, no assignment")
        assertFalse(ClaimHolder("agent-q", seatA).holds(seated), "same assignment, other principal")
        assertFalse(ClaimHolder("Agent-P", seatA).holds(seated), "the principal compare stays case-sensitive")

        val bare = claimed(principal, null)
        assertTrue(holderBare.holds(bare), "no assignment holds a principal-level claim")
        assertFalse(holderA.holds(bare), "a seat does not hold a principal-level claim")
        assertFalse(holderBare.holds(WorkItem(title = "unclaimed")), "nobody holds an unclaimed item")
    }

    @Test
    fun `resolve fills the assignment id and keeps the principal`() {
        val claim = ActorClaim(id = principal, kind = ActorKind.SUBAGENT)

        assertEquals(ClaimHolder(principal, seatA), ClaimHolder.resolve(claim, seatA))
        assertEquals(ClaimHolder(principal, null), ClaimHolder.resolve(claim, null))
    }

    @Test
    fun `FenceGuard fences a seat claim against the same principal under another seat or none, and admits only the exact pair`() {
        val seated = claimed(principal, seatA)
        assertTrue(FenceGuard.check(seated, holderA, now) is Outcome.Ok, "the exact pair is allowed")
        for ((label, caller) in listOf("other seat" to holderB, "no seat" to holderBare, "no caller" to null)) {
            val outcome = FenceGuard.check(seated, caller, now)
            assertIs<Outcome.Err>(outcome, label)
            assertEquals(ErrorCode.NOT_CLAIM_HOLDER, outcome.error.code, label)
        }
        // a principal-level claim fences a seat of the same principal
        val bare = claimed(principal, null)
        assertTrue(FenceGuard.check(bare, holderBare, now) is Outcome.Ok, "the bare holder is allowed")
        assertIs<Outcome.Err>(FenceGuard.check(bare, holderA, now), "a seat is fenced by a principal-level claim")
    }

    @Test
    fun `FenceGuard fences nothing once the seat claim has lapsed, for every caller`() {
        val lapsed = claimed(principal, seatA, expiresAt = now.minusSeconds(1))
        for (caller in listOf(holderA, holderB, holderBare, null)) {
            assertTrue(FenceGuard.check(lapsed, caller, now) is Outcome.Ok, "lapsed claim, caller $caller")
        }
    }

    // ------------------------------------------------------------------ the holder lookups

    @Test
    fun `the holder's held-items lookup is per pair - a seat sees its own items and the bare holder sees none of them`(): Unit =
        runBlocking {
            val rig = rig()
            val x = rig.seed("lookup X")
            val y = rig.seed("lookup Y")
            val z = rig.seed("lookup Z")
            rig.service.claim(x.id, holderA, 600).ok()
            rig.service.claim(y.id, holderB, 600).ok()
            rig.service.claim(z.id, holderBare, 600).ok()
            val store = rig.provider.claimStore()

            assertEquals(listOf(x.id), store.findHeldBy(principal, seatA).map { it.id })
            assertEquals(listOf(y.id), store.findHeldBy(principal, seatB).map { it.id })
            assertEquals(listOf(z.id), store.findHeldBy(principal).map { it.id }, "the bare lookup returns only the principal-level claim")
            assertEquals(emptyList(), store.findHeldBy(principal, UUID.randomUUID()).map { it.id }, "an unknown assignment holds nothing")
            assertEquals(
                emptyList(),
                store.findHeldBy("agent-q", seatA).map { it.id },
                "another principal under the same assignment holds nothing"
            )
        }

    // ------------------------------------------------------------------ the WorkItem invariant

    @Test
    fun `a claim assignment without a claimant is refused on construction, and with a claimant or none it is accepted`() {
        val thrown = assertFailsWith<ValidationException> { WorkItem(title = "orphan assignment", claimAssignmentId = seatA) }
        assertTrue("claimAssignmentId requires claimedBy" in (thrown.message ?: ""), "message: ${thrown.message}")

        // controls: the claimant present, and no assignment at all
        assertEquals(seatA, claimed(principal, seatA).claimAssignmentId)
        assertNull(WorkItem(title = "plain").claimAssignmentId)
        assertNull(claimed(principal, null).claimAssignmentId)
    }
}
