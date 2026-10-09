package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.port.EventRecord
import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.ActorKind
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.ExpirySweeper
import io.github.jpicklyk.mcptask.current.test.SettableClock
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item 5cd1086c, round r2) closing the test-independence-audit gaps 1, 2, 3 and 7.
 *
 * Oracles: task-scope Rule X ("Exactly ONE *.expired per lapsed instance across all detectors"; a lapsed instance that
 * is overwritten, cleared or removed is recorded as expired and NOT released / cleared), decision 1 (lapsed claims are
 * not consumed by detection; terminal entry clears the claim columns, plan 3.7), Non-goals ("resource_lease_history ...
 * closing intervals 'expired' on sweep/steal"), Sweep ("Sweep rows carry no actor") and fix-decisions-r1 K2 (a sweep run
 * inside a request records that request's principal; the scheduled sweeper's rows carry none). Fixture timeline as in
 * [ClaimServiceExpiryTest]: everything is claimed or leased at T with ttl 60 s so E = T + 60 s, and the clock is set to E.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class ClaimServiceTerminalExpiryTest {
    private val clock = SettableClock(CLAIM_T)

    @RegisterExtension
    @JvmField
    val sqlite = SqliteTestDatabase.perMethod(clock = clock)

    private val e: Instant = CLAIM_T.plusSeconds(60)

    private fun rig() = ClaimRig(sqlite.db, clock)

    private fun advanceService(rig: ClaimRig) =
        AdvanceService(
            workItemRepository = rig.provider.workItemRepository(),
            roleTransitionRepository = rig.provider.roleTransitionRepository(),
            dependencyRepository = rig.provider.dependencyRepository(),
            noteRepository = rig.provider.noteRepository(),
            schemaResolver = { null },
            resourceLeaseRepository = rig.provider.resourceLeaseRepository(),
            unitOfWork = rig.uow,
            clock = clock,
            claimService = rig.service
        )

    private suspend fun AdvanceService.trigger(
        item: WorkItem,
        trigger: String
    ): AdvanceOutcome = advance(item, trigger, null, null, null, DegradedModePolicy.ACCEPT_CACHED, enforceOwnership = false)

    private fun List<EventRecord>.claimTypes() = map { it.type }.filter { it.startsWith("claim.") }

    private fun List<EventRecord>.leaseTypes() = map { it.type }.filter { it.startsWith("lease.") }

    private suspend fun ClaimRig.child(parent: WorkItem): WorkItem =
        provider.workItemRepository().create(
            WorkItem(title = "child of ${parent.title}", role = Role.WORK, parentId = parent.id, depth = 1)
        )

    private suspend fun assertClaimColumnsCleared(
        rig: ClaimRig,
        item: WorkItem
    ) {
        val after = rig.reload(item.id)
        assertEquals(Role.TERMINAL, after.role, "control: the item reached terminal")
        assertNull(after.claimedBy, "terminal entry clears claimedBy")
        assertNull(after.claimedAt, "terminal entry clears claimedAt")
        assertNull(after.claimExpiresAt, "terminal entry clears claimExpiresAt")
        assertNull(after.originalClaimedAt, "terminal entry clears originalClaimedAt")
    }

    // ---------------------------------------------------------------------------------------------
    // Gap 1 -- terminal advance on an item whose claim lapsed: one expired, never released/cleared
    // ---------------------------------------------------------------------------------------------

    private suspend fun terminalAdvanceOnLapsedClaim(trigger: String) {
        val rig = rig()
        val i = rig.seed("terminal $trigger", Role.WORK)
        rig.service.claim(i.id, "agent-a", 60).ok()
        clock.set(e)
        val control = rig.reload(i.id)
        assertEquals("agent-a", control.claimedBy, "control: the lapsed claim is still stored before the advance")

        val (outcome, rows) = rig.written { advanceService(rig).trigger(control, trigger) }

        assertIs<AdvanceOutcome.Success>(outcome, "control: the $trigger committed: $outcome")
        assertEquals(listOf("claim.expired"), rows.claimTypes(), "Rule X: exactly one expired, no released/cleared: $rows")
        val expired = rows.single { it.type == "claim.expired" }
        assertEquals(i.id, expired.entityId)
        assertClaimColumnsCleared(rig, i)
        val (swept, sweepRows) = rig.written { rig.service.sweepExpired().ok() }
        assertEquals(0, swept.claimsExpired, "exactly once: a later sweep finds the claim already gone")
        assertEquals(emptyList(), sweepRows)
    }

    @Test
    fun `G1 a complete on a WORK item with a lapsed claim records exactly one claim expired and clears the columns`(): Unit =
        runBlocking { terminalAdvanceOnLapsedClaim("complete") }

    @Test
    fun `G1 a cancel on a WORK item with a lapsed claim records exactly one claim expired and clears the columns`(): Unit =
        runBlocking { terminalAdvanceOnLapsedClaim("cancel") }

    @Test
    fun `G1 control a complete on an active claim records no expired row and clears the columns`(): Unit =
        runBlocking {
            val rig = rig()
            val i = rig.seed("terminal active", Role.WORK)
            rig.service.claim(i.id, "agent-a", 60).ok()
            clock.set(e.minusMillis(1))

            val (outcome, rows) = rig.written { advanceService(rig).trigger(rig.reload(i.id), "complete") }

            assertIs<AdvanceOutcome.Success>(outcome)
            assertTrue(rows.claimTypes().none { it == "claim.expired" }, "an active claim never expires: $rows")
            assertClaimColumnsCleared(rig, i)
        }

    // ---------------------------------------------------------------------------------------------
    // Gap 2 -- cascade paths
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `G2 a parent cascaded to terminal while holding a lapsed claim records exactly one claim expired for it`(): Unit =
        runBlocking {
            val rig = rig()
            val parent = rig.seed("cascade parent", Role.WORK)
            val child = rig.child(parent)
            rig.service.claim(parent.id, "agent-a", 60).ok()
            clock.set(e)

            val (outcome, rows) = rig.written { advanceService(rig).trigger(rig.reload(child.id), "complete") }

            assertIs<AdvanceOutcome.Success>(outcome, "control: the child completed: $outcome")
            assertEquals(1, outcome.result.cascadeEvents.size, "control: the parent cascaded: ${outcome.result.cascadeEvents}")
            assertEquals(listOf("claim.expired"), rows.claimTypes(), "Rule X: one expired, no released/cleared: $rows")
            assertEquals(parent.id, rows.single { it.type == "claim.expired" }.entityId)
            assertClaimColumnsCleared(rig, parent)
        }

    @Test
    fun `G2 a cascade WORK exit with a lapsed lease records one lease expired and consumes the row`(): Unit =
        runBlocking {
            val rig = rig()
            val parent = rig.seed("cascade lease parent", Role.WORK)
            val child = rig.child(parent)
            rig.service.acquireLeases(parent.id, "actor-1", listOf("res-k" to 60)).ok()
            clock.set(e)
            val leases = rig.provider.resourceLeaseRepository()
            assertEquals(1, leases.findLapsed().size, "control: the lease is lapsed before the cascade")

            val (outcome, rows) = rig.written { advanceService(rig).trigger(rig.reload(child.id), "complete") }

            assertIs<AdvanceOutcome.Success>(outcome, "control: the child completed: $outcome")
            assertEquals(1, outcome.result.cascadeEvents.size, "control: the parent cascaded out of WORK")
            assertEquals(listOf("lease.expired"), rows.leaseTypes(), "Rule X: one expired, no released: $rows")
            val expired = rows.single { it.type == "lease.expired" }
            assertEquals(parent.id, expired.entityId)
            assertEquals(emptyList(), leases.findLapsed(), "the lapsed lease row is consumed")
            assertEquals(emptyList(), leases.findActiveByKeys(listOf("res-k")), "and no live row remains")
        }

    // ---------------------------------------------------------------------------------------------
    // Gap 3 -- resource_lease_history closes 'expired' at the row's expires_at
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `G3 a sweep closes the lapsed lease history interval as expired at the row expiry`(): Unit =
        runBlocking {
            val rig = rig()
            val h = rig.seed("history holder")
            rig.service.acquireLeases(h.id, "actor-1", listOf("res-k" to 60)).ok()
            val leases = rig.provider.resourceLeaseRepository()
            val open = leases.findRecentIntervals("res-k", 10).single()
            assertNull(open.releasedAt, "control: the interval is open before the sweep")
            assertEquals(e, open.expiresAt, "control: the interval expiry is T + ttl")
            clock.set(e.plusSeconds(500))

            rig.service.sweepExpired().ok()

            val closed = leases.findRecentIntervals("res-k", 10).single()
            assertEquals("expired", closed.releaseReason)
            assertEquals(e, closed.releasedAt, "closed at the row's expires_at, not at the sweep instant")
        }

    @Test
    fun `G3 a steal closes the prior holder interval as expired at its expiry and opens the new one`(): Unit =
        runBlocking {
            val rig = rig()
            val h = rig.seed("history old")
            val o = rig.seed("history thief")
            rig.service.acquireLeases(h.id, "actor-1", listOf("res-k" to 60)).ok()
            clock.set(e.plusSeconds(500))

            rig.service.acquireLeases(o.id, "actor-2", listOf("res-k" to 60)).ok()

            val intervals = rig.provider.resourceLeaseRepository().findRecentIntervals("res-k", 10)
            assertEquals(2, intervals.size, "closed interval plus the new open one: $intervals")
            val old = intervals.single { it.holderItemId == h.id }
            assertEquals("expired", old.releaseReason)
            assertEquals(e, old.releasedAt, "closed at the old row's expires_at, not at the steal instant")
            assertNull(intervals.single { it.holderItemId == o.id }.releasedAt, "the thief's interval is open")
        }

    // ---------------------------------------------------------------------------------------------
    // Gap 7 -- K2: ambient principal on an in-request sweep, none on the scheduled sweeper
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `G7 K2 a sweep inside a request records that principal while the scheduled sweeper rows carry none`(): Unit =
        runBlocking {
            val rig = rig()
            val inRequest = rig.seed("K2 in request")
            val scheduled = rig.seed("K2 scheduled")
            rig.service.claim(inRequest.id, "agent-a", 60).ok()
            rig.service.claim(scheduled.id, "agent-b", 120).ok()
            val caller = ActorClaim(id = "agent-x", kind = ActorKind.SUBAGENT, parent = "orch-1")

            clock.set(e)
            val (first, requestRows) = rig.written { withEventActor(caller) { rig.service.sweepExpired().ok() } }
            clock.set(CLAIM_T.plusSeconds(121))
            val (_, sweeperRows) = rig.written { ExpirySweeper(rig.service).sweepOnce() }

            assertEquals(1, first.claimsExpired, "control: only the first claim had lapsed at E")
            assertEquals(listOf("claim.expired"), requestRows.map { it.type })
            assertEquals(inRequest.id, requestRows.single().entityId)
            assertEquals("agent-x", requestRows.single().principalId, "the detector is the caller: its principal is recorded")
            assertEquals(listOf("claim.expired"), sweeperRows.map { it.type }, "control: the sweeper reported the second claim")
            assertEquals(scheduled.id, sweeperRows.single().entityId)
            assertNull(sweeperRows.single().principalId, "the scheduled sweeper is a system action: no principal")
        }
}
