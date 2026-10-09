package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.port.ClaimResult
import io.github.jpicklyk.mcptask.current.application.port.EventRecord
import io.github.jpicklyk.mcptask.current.application.port.LeaseAcquireResult
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.str
import io.github.jpicklyk.mcptask.current.test.SettableClock
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item 5cd1086c) for Rule X of [ClaimService]: lapsed claims and lease rows are
 * reported as `claim.expired` / `lease.expired` exactly once, by whichever detector meets them first. Scenarios
 * S7-S14 of the frozen test-plan and its expiry probes.
 *
 * Oracles: task-scope "Rule X (expiry)" and plan 3.12: an instance is lapsed when its expiry is at or before the unit
 * instant ("expiresAt <= at = expired"), one boundary for claims and leases. Fixture timeline: everything is claimed or
 * acquired at T with ttl 60 s, so the expiry E is T + 60 s; the settable clock is moved to E - 1 ms, E and E + 1 s.
 * Claims are NOT consumed by detection (columns stay), lapsed lease rows ARE deleted by sweep and by an acquire.
 * Payload expiresAt is ISO-8601 text; it is parsed with Instant.parse, never string-compared.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class ClaimServiceExpiryTest {
    private val clock = SettableClock(CLAIM_T)

    @RegisterExtension
    @JvmField
    val sqlite = SqliteTestDatabase.perMethod(clock = clock)

    private val e: Instant = CLAIM_T.plusSeconds(60)

    private fun rig() = ClaimRig(sqlite.db, clock)

    private fun assertExpiredClaim(
        row: EventRecord,
        item: WorkItem,
        holder: String
    ) {
        assertEquals("claim.expired", row.type)
        assertEquals(item.id, row.entityId)
        assertEquals(holder, row.str("holder"))
        assertEquals(e, Instant.parse(row.str("expiresAt")!!), "claim.expired carries the expiry the claim had")
    }

    private fun assertExpiredLease(
        row: EventRecord,
        holder: WorkItem,
        key: String
    ) {
        assertEquals("lease.expired", row.type)
        assertEquals(holder.id, row.entityId)
        assertEquals(key, row.str("key"))
        assertEquals(e, Instant.parse(row.str("expiresAt")!!), "lease.expired carries the expiry the row had")
    }

    private class Lapsed(
        val i: WorkItem,
        val j: WorkItem,
        val h: WorkItem,
        val h2: WorkItem
    )

    /**
     * At T: i claimed by agent-a (60 s), j claimed by agent-c (3600 s), lease res-k held by h (60 s), lease res-m held by
     * h2 (3600 s). Then the clock is set to E: i and res-k are lapsed, j and res-m are active.
     */
    private suspend fun lapsedFixture(rig: ClaimRig): Lapsed {
        val i = rig.seed("i lapsed claim")
        val j = rig.seed("j active claim")
        val h = rig.seed("h lapsed lease")
        val h2 = rig.seed("h2 active lease")
        rig.service.claim(i.id, "agent-a", 60).ok()
        rig.service.claim(j.id, "agent-c", 3600).ok()
        rig.service.acquireLeases(h.id, "actor-1", listOf("res-k" to 60)).ok()
        rig.service.acquireLeases(h2.id, "actor-1", listOf("res-m" to 3600)).ok()
        clock.set(e)
        return Lapsed(i, j, h, h2)
    }

    private fun advanceService(rig: ClaimRig) =
        AdvanceService(
            workItemRepository = rig.provider.workItemRepository(),
            roleTransitionRepository = rig.provider.roleTransitionRepository(),
            dependencyRepository = rig.provider.dependencyRepository(),
            noteRepository = rig.provider.noteRepository(),
            schemaResolver = { null },
            unitOfWork = rig.uow,
            clock = clock,
            claimService = rig.service
        )

    private suspend fun AdvanceService.start(item: WorkItem): AdvanceOutcome =
        advance(item, "start", null, null, null, DegradedModePolicy.ACCEPT_CACHED, enforceOwnership = false)

    // ---------------------------------------------------------------------------------------------
    // S7 -- the exact boundary: E - 1 ms still held, E is expired
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S7 a claim one millisecond before its expiry is still held and records only a rejection`(): Unit =
        runBlocking {
            val rig = rig()
            val i = rig.seed("S7 before")
            rig.service.claim(i.id, "agent-a", 60).ok()
            clock.set(e.minusMillis(1))

            val (result, rows) = rig.written { rig.service.claim(i.id, "agent-b", 60).ok() }

            assertIs<ClaimResult.AlreadyClaimed>(result)
            assertEquals(listOf("claim.rejected"), rows.map { it.type }, "no expiry before the boundary: $rows")
            assertEquals("agent-a", rig.reload(i.id).claimedBy)
        }

    @Test
    fun `S7 a contender at exactly the expiry takes over and the rows are expired for the old holder then acquired`(): Unit =
        runBlocking {
            val rig = rig()
            val i = rig.seed("S7 at")
            rig.service.claim(i.id, "agent-a", 60).ok()
            clock.set(e)

            val (result, rows) = rig.written { rig.service.claim(i.id, "agent-b", 60).ok() }

            assertIs<ClaimResult.Success>(result)
            assertEquals(listOf("claim.expired", "claim.acquired"), rows.map { it.type }, "Rule X: expiry then take-over: $rows")
            assertExpiredClaim(rows[0], i, "agent-a")
            assertEquals(i.id, rows[1].entityId)
            assertEquals("agent-b", rows[1].str("holder"))
            assertEquals("agent-b", rig.reload(i.id).claimedBy, "control: the take-over really happened")
        }

    // ---------------------------------------------------------------------------------------------
    // S8 -- heartbeat (the holder re-claims)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S8 a holder re-claim one millisecond before expiry is a plain refresh with no expired row`(): Unit =
        runBlocking {
            val rig = rig()
            val i = rig.seed("S8 refresh")
            rig.service.claim(i.id, "agent-a", 60).ok()
            clock.set(e.minusMillis(1))

            val (result, rows) = rig.written { rig.service.claim(i.id, "agent-a", 60).ok() }

            assertIs<ClaimResult.Success>(result)
            assertEquals(listOf("claim.acquired"), rows.map { it.type }, "an active claim refreshed in time never expires: $rows")
            assertEquals("agent-a", rig.reload(i.id).claimedBy)
        }

    @Test
    fun `S8 a holder re-claim after the expiry records expired for the lapsed claim then acquired`(): Unit =
        runBlocking {
            val rig = rig()
            val i = rig.seed("S8 late")
            rig.service.claim(i.id, "agent-a", 60).ok()
            clock.set(e.plusSeconds(1))

            val (result, rows) = rig.written { rig.service.claim(i.id, "agent-a", 60).ok() }

            assertIs<ClaimResult.Success>(result)
            assertEquals(listOf("claim.expired", "claim.acquired"), rows.map { it.type }, "Rule X: $rows")
            assertExpiredClaim(rows[0], i, "agent-a")
            assertEquals("agent-a", rows[1].str("holder"))
        }

    // ---------------------------------------------------------------------------------------------
    // S9 -- a lapsed claim is never reported as released / cleared / superseded
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S9 releasing a lapsed claim records expired and never released`(): Unit =
        runBlocking {
            val rig = rig()
            val i = rig.seed("S9 release")
            rig.service.claim(i.id, "agent-a", 60).ok()
            clock.set(e.plusSeconds(1))

            val (_, rows) = rig.written { rig.service.release(i.id, "agent-a").ok() }

            assertEquals(listOf("claim.expired"), rows.map { it.type }, "Rule X: a lapsed claim is not released: $rows")
            assertExpiredClaim(rows.single(), i, "agent-a")
        }

    @Test
    fun `S9 clearing a lapsed claim records expired and never released with reason cleared`(): Unit =
        runBlocking {
            val rig = rig()
            val i = rig.seed("S9 clear")
            rig.service.claim(i.id, "agent-a", 60).ok()
            clock.set(e.plusSeconds(1))

            val (_, rows) = rig.written { rig.service.clearClaim(i.id).ok() }

            assertEquals(listOf("claim.expired"), rows.map { it.type }, "Rule X: $rows")
            assertExpiredClaim(rows.single(), i, "agent-a")
            assertNull(rig.reload(i.id).claimedBy, "control: the clear still removed the claim")
        }

    @Test
    fun `S9 superseding a lapsed claim records expired for it and acquired for the new item with no released row`(): Unit =
        runBlocking {
            val rig = rig()
            val i = rig.seed("S9 old")
            val j = rig.seed("S9 new")
            rig.service.claim(i.id, "agent-a", 60).ok()
            clock.set(e.plusSeconds(1))

            val (_, rows) = rig.written { rig.service.claim(j.id, "agent-a", 60).ok() }

            assertEquals(listOf("claim.acquired", "claim.expired"), rows.map { it.type }.sorted(), "Rule X: $rows")
            assertExpiredClaim(rows.single { it.type == "claim.expired" }, i, "agent-a")
            assertEquals(j.id, rows.single { it.type == "claim.acquired" }.entityId)
            assertEquals("agent-a", rig.reload(j.id).claimedBy)
        }

    // ---------------------------------------------------------------------------------------------
    // S10 -- the sweep
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S10 sweepExpired reports lapsed claims and leases, keeps claims, deletes lapsed lease rows, and is idempotent`(): Unit =
        runBlocking {
            val rig = rig()
            val f = lapsedFixture(rig)
            val leases = rig.provider.resourceLeaseRepository()
            val counts = rig.provider.workItemRepository()
            // controls: the fixture really holds one lapsed and one active instance of each kind before the sweep
            assertEquals(1, counts.countByClaimStatus().expired)
            assertEquals(1, counts.countByClaimStatus().active)
            assertEquals(1, leases.findLapsed().size)

            val (swept, rows) = rig.written { rig.service.sweepExpired().ok() }

            assertEquals(ExpirySweep(claimsExpired = 1, leasesExpired = 1), swept)
            assertEquals(listOf("claim.expired", "lease.expired"), rows.map { it.type }.sorted(), "$rows")
            assertExpiredClaim(rows.single { it.type == "claim.expired" }, f.i, "agent-a")
            assertExpiredLease(rows.single { it.type == "lease.expired" }, f.h, "res-k")
            assertEquals("agent-a", rig.reload(f.i.id).claimedBy, "claims are NOT consumed by detection")
            assertEquals(1, counts.countByClaimStatus().expired, "the expired-claim inventory keeps its meaning")
            assertEquals("agent-c", rig.reload(f.j.id).claimedBy, "the active claim is untouched")
            assertEquals(emptyList(), leases.findLapsed(), "lapsed lease rows ARE consumed")
            assertEquals(1, leases.findActiveByKeys(listOf("res-m")).size, "the active lease is untouched")

            val (again, againRows) = rig.written { rig.service.sweepExpired().ok() }
            assertEquals(ExpirySweep(0, 0), again, "replay: nothing new is lapsed")
            assertEquals(emptyList(), againRows, "replay records no row")
        }

    @Test
    fun `S10 a sweep with nothing lapsed reports zero and records no row`(): Unit =
        runBlocking {
            val rig = rig()
            val i = rig.seed("S10 active")
            rig.service.claim(i.id, "agent-a", 60).ok()
            clock.set(e.minusMillis(1))

            val (swept, rows) = rig.written { rig.service.sweepExpired().ok() }

            assertEquals(ExpirySweep(0, 0), swept)
            assertEquals(emptyList(), rows)
        }

    // ---------------------------------------------------------------------------------------------
    // S11 -- exactly once across detectors
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S11 after a sweep a take-over and a fresh acquire record only acquired rows`(): Unit =
        runBlocking {
            val rig = rig()
            val f = lapsedFixture(rig)
            val o = rig.seed("o contender")
            rig.service.sweepExpired().ok()

            val (claim, claimRows) = rig.written { rig.service.claim(f.i.id, "agent-b", 60).ok() }
            val (lease, leaseRows) = rig.written { rig.service.acquireLeases(o.id, "actor-2", listOf("res-k" to 60)).ok() }

            assertIs<ClaimResult.Success>(claim)
            assertEquals(listOf("claim.acquired"), claimRows.map { it.type }, "already reported by the sweep: $claimRows")
            assertIs<LeaseAcquireResult.Success>(lease)
            assertEquals(listOf("lease.acquired"), leaseRows.map { it.type }, "already reported by the sweep: $leaseRows")
        }

    @Test
    fun `S11 after a take-over and a steal a sweep reports nothing more`(): Unit =
        runBlocking {
            val rig = rig()
            val f = lapsedFixture(rig)
            val o = rig.seed("o contender")
            rig.service.claim(f.i.id, "agent-b", 60).ok()
            rig.service.acquireLeases(o.id, "actor-2", listOf("res-k" to 60)).ok()

            val (swept, rows) = rig.written { rig.service.sweepExpired().ok() }

            assertEquals(ExpirySweep(0, 0), swept, "both lapsed instances were already reported by their contenders")
            assertEquals(emptyList(), rows)
        }

    @Test
    fun `S11 superseding a lapsed claim and then sweeping in the same moment reports it once`(): Unit =
        runBlocking {
            val rig = rig()
            val i = rig.seed("S11 old")
            val j = rig.seed("S11 new")
            rig.service.claim(i.id, "agent-a", 60).ok()
            clock.set(e)

            val (_, rows) =
                rig.written {
                    rig.service.claim(j.id, "agent-a", 60).ok()
                    rig.service.sweepExpired().ok()
                }

            assertEquals(1, rows.count { it.type == "claim.expired" }, "one lapsed instance, one expired row: $rows")
        }

    // ---------------------------------------------------------------------------------------------
    // S12 -- leases: steal, boundary, own re-take
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S12 a contender at the lease expiry steals it and the rows are expired for the old holder and acquired for the new`(): Unit =
        runBlocking {
            val rig = rig()
            val h = rig.seed("S12 holder")
            val o = rig.seed("S12 other")
            rig.service.acquireLeases(h.id, "actor-1", listOf("res-k" to 60)).ok()
            clock.set(e)

            val (result, rows) = rig.written { rig.service.acquireLeases(o.id, "actor-2", listOf("res-k" to 60)).ok() }

            assertIs<LeaseAcquireResult.Success>(result)
            assertEquals(listOf("lease.acquired", "lease.expired"), rows.map { it.type }.sorted(), "$rows")
            assertExpiredLease(rows.single { it.type == "lease.expired" }, h, "res-k")
            val acquired = rows.single { it.type == "lease.acquired" }
            assertEquals(o.id, acquired.entityId)
            assertEquals("res-k", acquired.str("key"))

            val leases = rig.provider.resourceLeaseRepository()
            assertEquals(emptyList(), leases.findLapsed(), "the stolen row no longer lingers as a lapsed row")
            assertEquals(1, leases.findActiveByKeys(listOf("res-k")).size, "control: the new holder owns the key")
            val (swept, sweepRows) = rig.written { rig.service.sweepExpired().ok() }
            assertEquals(ExpirySweep(0, 0), swept, "a later sweep finds nothing to report")
            assertEquals(emptyList(), sweepRows)
        }

    @Test
    fun `S12 a contender one millisecond before the lease expiry is rejected and nothing expires`(): Unit =
        runBlocking {
            val rig = rig()
            val h = rig.seed("S12 holder")
            val o = rig.seed("S12 other")
            rig.service.acquireLeases(h.id, "actor-1", listOf("res-k" to 60)).ok()
            clock.set(e.minusMillis(1))

            val (result, rows) = rig.written { rig.service.acquireLeases(o.id, "actor-2", listOf("res-k" to 60)).ok() }

            assertIs<LeaseAcquireResult.Contended>(result)
            assertEquals(listOf("lease.rejected"), rows.map { it.type }, "$rows")
        }

    @Test
    fun `S12 the holder re-acquiring its own lapsed lease records expired then acquired`(): Unit =
        runBlocking {
            val rig = rig()
            val h = rig.seed("S12 own")
            rig.service.acquireLeases(h.id, "actor-1", listOf("res-k" to 60)).ok()
            clock.set(e.plusSeconds(1))

            val (result, rows) = rig.written { rig.service.acquireLeases(h.id, "actor-1", listOf("res-k" to 60)).ok() }

            assertIs<LeaseAcquireResult.Success>(result)
            assertEquals(listOf("lease.acquired", "lease.expired"), rows.map { it.type }.sorted(), "$rows")
            assertExpiredLease(rows.single { it.type == "lease.expired" }, h, "res-k")
        }

    // ---------------------------------------------------------------------------------------------
    // S13 -- releasing lapsed leases
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S13 releasing the leases of a holder whose lease lapsed records expired and no released row for it`(): Unit =
        runBlocking {
            val rig = rig()
            val h = rig.seed("S13 holder")
            rig.service.acquireLeases(h.id, "actor-1", listOf("res-k" to 60)).ok()
            clock.set(e.plusSeconds(1))

            val (_, rows) = rig.written { rig.service.releaseLeases(setOf(h.id)).ok() }

            assertEquals(listOf("lease.expired"), rows.map { it.type }, "Rule X: $rows")
            assertExpiredLease(rows.single(), h, "res-k")
        }

    @Test
    fun `S13 a release over one lapsed and one active lease records expired for the first and released for the second`(): Unit =
        runBlocking {
            val rig = rig()
            val h = rig.seed("S13 mixed")
            rig.service.acquireLeases(h.id, "actor-1", listOf("res-k" to 60, "res-long" to 3600)).ok()
            clock.set(e.plusSeconds(1))

            val (_, rows) = rig.written { rig.service.releaseLeases(setOf(h.id)).ok() }

            assertEquals(listOf("lease.expired", "lease.released"), rows.map { it.type }.sorted(), "$rows")
            assertExpiredLease(rows.single { it.type == "lease.expired" }, h, "res-k")
            val released = rows.single { it.type == "lease.released" }
            assertEquals("res-long", released.str("key"))
            assertEquals("false", released.str("forced"))
        }

    @Test
    fun `S13 force releasing a lapsed lease records expired and no released row`(): Unit =
        runBlocking {
            val rig = rig()
            val h = rig.seed("S13 forced")
            rig.service.acquireLeases(h.id, "actor-1", listOf("res-k" to 60)).ok()
            clock.set(e.plusSeconds(1))

            val (_, rows) = rig.written { rig.service.forceReleaseLease("res-k", "operator").ok() }

            assertEquals(listOf("lease.expired"), rows.map { it.type }, "Rule X: $rows")
            assertExpiredLease(rows.single(), h, "res-k")
        }

    // ---------------------------------------------------------------------------------------------
    // S14 -- an advance that commits on an item with a lapsed claim
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S14 a committing advance on an item with a lapsed claim records claim expired once and a later sweep adds nothing`(): Unit =
        runBlocking {
            val rig = rig()
            val i = rig.seed("S14 advance")
            rig.service.claim(i.id, "agent-a", 60).ok()
            clock.set(e)
            val advance = advanceService(rig)

            val (outcome, rows) = rig.written { advance.start(rig.reload(i.id)) }

            assertIs<AdvanceOutcome.Success>(outcome, "control: the advance committed: $outcome")
            assertEquals(listOf("claim.expired", "item.transitioned"), rows.map { it.type }.sorted(), "$rows")
            assertExpiredClaim(rows.single { it.type == "claim.expired" }, i, "agent-a")
            assertEquals("agent-a", rig.reload(i.id).claimedBy, "claims are NOT consumed by the advance")

            val (swept, sweepRows) = rig.written { rig.service.sweepExpired().ok() }
            assertEquals(0, swept.claimsExpired, "exactly once: the advance already reported it")
            assertEquals(emptyList(), sweepRows)
        }

    @Test
    fun `S14 an advance after a sweep records no claim expired row`(): Unit =
        runBlocking {
            val rig = rig()
            val i = rig.seed("S14 sweep first")
            rig.service.claim(i.id, "agent-a", 60).ok()
            clock.set(e)
            val (swept, _) = rig.written { rig.service.sweepExpired().ok() }
            assertEquals(1, swept.claimsExpired, "control: the sweep reported the lapsed claim")

            val (outcome, rows) = rig.written { advanceService(rig).start(rig.reload(i.id)) }

            assertIs<AdvanceOutcome.Success>(outcome)
            assertEquals(listOf("item.transitioned"), rows.map { it.type }, "exactly once across detectors: $rows")
        }

    @Test
    fun `S14 a gate-rejected advance records no claim expired row while the same fixture without the block does`(): Unit =
        runBlocking {
            val rig = rig()
            val blocker = rig.seed("S14 blocker")
            val blocked = rig.seed("S14 blocked")
            val free = rig.seed("S14 free")
            rig.provider.dependencyRepository().create(
                Dependency(fromItemId = blocker.id, toItemId = blocked.id, type = DependencyType.BLOCKS)
            )
            rig.service.claim(blocked.id, "agent-a", 60).ok()
            rig.service.claim(free.id, "agent-c", 60).ok()
            clock.set(e)
            val advance = advanceService(rig)

            val (rejected, rejectedRows) = rig.written { advance.start(rig.reload(blocked.id)) }
            val (accepted, acceptedRows) = rig.written { advance.start(rig.reload(free.id)) }

            assertFalse(rejected is AdvanceOutcome.Success, "fixture: the unfinished blocker rejects the start: $rejected")
            assertTrue(rejectedRows.none { it.type == "claim.expired" }, "a rejected advance reports no expiry: $rejectedRows")
            assertIs<AdvanceOutcome.Success>(accepted, "control: the unblocked twin advanced")
            assertTrue(acceptedRows.any { it.type == "claim.expired" }, "control: the twin reported its lapsed claim: $acceptedRows")
        }

    @Test
    fun `S14 an advance unit that rolls back loses the claim expired row and the sweep then records it once`(): Unit =
        runBlocking {
            val rig = rig()
            val i = rig.seed("S14 rollback")
            rig.service.claim(i.id, "agent-a", 60).ok()
            clock.set(e)
            val advance = advanceService(rig)
            val item = rig.reload(i.id)
            val mark = rig.maxSeq()

            val outer =
                rig.uow.write<Unit>("S14.outer") {
                    advance.start(item)
                    Outcome.Err(DomainError(ErrorCode.INTERNAL, "boom"))
                }

            assertIs<Outcome.Err>(outer, "control: the enclosing unit rolled back")
            assertEquals(Role.QUEUE, rig.reload(i.id).role, "control: the advance was rolled back with it")
            assertTrue(rig.rowsAfter(mark).none { it.type == "claim.expired" }, "the rolled-back unit loses its expiry row")

            val (swept, rows) = rig.written { rig.service.sweepExpired().ok() }
            assertEquals(1, swept.claimsExpired, "the sweep backstops the lost row")
            assertEquals(listOf("claim.expired"), rows.map { it.type }, "recorded once: $rows")
        }
}
