package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.port.ClaimResult
import io.github.jpicklyk.mcptask.current.application.port.EventRecord
import io.github.jpicklyk.mcptask.current.application.port.LeaseAcquireResult
import io.github.jpicklyk.mcptask.current.application.port.LeaseReleaseResult
import io.github.jpicklyk.mcptask.current.application.port.ReleaseResult
import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.payload
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.str
import io.github.jpicklyk.mcptask.current.test.SettableClock
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The deterministic start instant every ClaimService test pins the settable clock to. */
internal val CLAIM_T: Instant = Instant.parse("2026-03-01T10:00:00Z")

/** Unwraps an [Outcome.Ok] value; any fault fails the test with the outcome text. */
internal fun <T> Outcome<T>.ok(): T = getOrNull() ?: error("expected Outcome.Ok, got $this")

/** An integer-valued key of an event row data payload. */
internal fun EventRecord.intKey(key: String): Int = payload()[key]!!.jsonPrimitive.content.toInt()

/** A long-valued key of an event row data payload. */
internal fun EventRecord.longKey(key: String): Long = payload()[key]!!.jsonPrimitive.content.toLong()

/**
 * Real SQLite fixture for the ClaimService tests: production repositories and unit of work bound to one settable
 * clock, with rows read back through the same provider event store. Items are seeded through the store (no
 * event rows are expected from seeding; every assertion reads only the rows written after a recorded high-water mark).
 */
internal class ClaimRig(
    val db: SqliteTestDatabase,
    val clock: SettableClock
) {
    val provider get() = db.repositoryProvider()
    val uow: UnitOfWork = db.unitOfWork(clock)
    val service = ClaimService(provider, uow)

    suspend fun seed(
        title: String,
        role: Role = Role.QUEUE
    ): WorkItem = provider.workItemRepository().create(WorkItem(title = title, role = role, depth = 0))

    suspend fun reload(id: UUID): WorkItem = provider.workItemRepository().getById(id) ?: error("item $id vanished")

    suspend fun maxSeq(): Long = provider.eventStore().maxSeq()

    suspend fun rowsAfter(seq: Long): List<EventRecord> = provider.eventStore().readAfter(seq, null, 100_000)

    /** Runs [block] and returns its result with the rows it wrote (seq above the mark taken before it). */
    suspend fun <T> written(block: suspend () -> T): Pair<T, List<EventRecord>> {
        val mark = maxSeq()
        val result = block()
        return result to rowsAfter(mark)
    }
}

/**
 * Independent tests (test-author seat, item 5cd1086c) for the event-recording half of [ClaimService]: scenarios
 * S1-S6 of the frozen test-plan plus the casing / empty / duplicate probes.
 *
 * Oracles: task-scope "Event rules" E1-E6 (parity with the P8 decorator for ACTIVE instances) and plan 3.7 ("each
 * service records its own events", "failures are recorded too"). The row payload keys (holder, ttlSeconds,
 * reason, retryAfterMs, key, contendedKeys, count, forced) are the declared DomainEvent payload shapes.
 * Expected values are derived from those clauses, never from what ClaimService returns.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class ClaimServiceTest {
    private val clock = SettableClock(CLAIM_T)

    @RegisterExtension
    @JvmField
    val sqlite = SqliteTestDatabase.perMethod(clock = clock)

    private fun rig() = ClaimRig(sqlite.db, clock)

    // ---------------------------------------------------------------------------------------------
    // S1 -- fresh claim records exactly one claim.acquired
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S1 a fresh claim records exactly one claim acquired row with the holder and the ttl`(): Unit =
        runBlocking {
            val rig = rig()
            val i = rig.seed("S1")

            val (result, rows) = rig.written { rig.service.claim(i.id, "agent-a", 60).ok() }

            assertIs<ClaimResult.Success>(result)
            assertEquals(listOf("claim.acquired"), rows.map { it.type }, "E1: exactly one row for a fresh claim: $rows")
            val row = rows.single()
            assertEquals(i.id, row.entityId)
            assertEquals("agent-a", row.str("holder"))
            assertEquals(60, row.intKey("ttlSeconds"))
            // control: the claim really landed, so the single row is attributable to the write and not to a no-op
            assertEquals("agent-a", rig.reload(i.id).claimedBy)
        }

    // ---------------------------------------------------------------------------------------------
    // S2 -- claiming another item supersedes the agent other active claim
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S2 claiming a second item records acquired for it and released with reason superseded for the first`(): Unit =
        runBlocking {
            val rig = rig()
            val i = rig.seed("S2 first")
            val j = rig.seed("S2 second")
            rig.service.claim(i.id, "agent-a", 600).ok()

            val (result, rows) = rig.written { rig.service.claim(j.id, "agent-a", 600).ok() }

            val success = assertIs<ClaimResult.Success>(result)
            assertEquals(listOf(i.id), success.releasedItemIds)
            assertEquals(listOf("claim.acquired", "claim.released"), rows.map { it.type }.sorted(), "E1: $rows")
            val acquired = rows.single { it.type == "claim.acquired" }
            val released = rows.single { it.type == "claim.released" }
            assertEquals(j.id, acquired.entityId)
            assertEquals(i.id, released.entityId)
            assertEquals("superseded", released.str("reason"))
            assertNull(rig.reload(i.id).claimedBy, "control: the first claim was really released")
        }

    // ---------------------------------------------------------------------------------------------
    // S3 -- contention records exactly one claim.rejected, surviving the caller rollback
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S3 a contended claim records exactly one claim rejected row and changes nothing else`(): Unit =
        runBlocking {
            val rig = rig()
            val i = rig.seed("S3")
            rig.service.claim(i.id, "agent-a", 60).ok()
            clock.advanceSeconds(10)

            val (result, rows) = rig.written { rig.service.claim(i.id, "agent-b", 60).ok() }

            val contended = assertIs<ClaimResult.AlreadyClaimed>(result)
            assertEquals(listOf("claim.rejected"), rows.map { it.type }, "E2: $rows")
            val row = rows.single()
            assertEquals(i.id, row.entityId)
            assertTrue(row.longKey("retryAfterMs") > 0, "the 50 s remaining on the holder claim is a positive retry hint")
            assertEquals(contended.retryAfterMs, row.longKey("retryAfterMs"), "the row carries the same hint the caller got")
            assertEquals("agent-a", rig.reload(i.id).claimedBy, "control: agent-a still holds the item")
        }

    @Test
    fun `S3 the rejection row survives a caller unit that rolls back`(): Unit =
        runBlocking {
            val rig = rig()
            val i = rig.seed("S3 rollback")
            rig.service.claim(i.id, "agent-a", 60).ok()
            clock.advanceSeconds(10)
            val mark = rig.maxSeq()
            var seen: ClaimResult? = null

            val outer =
                rig.uow.write<Unit>("S3.outer") {
                    seen = rig.service.claim(i.id, "agent-b", 60).ok()
                    Outcome.Err(DomainError(ErrorCode.INTERNAL, "boom"))
                }

            assertIs<Outcome.Err>(outer, "control: the caller unit really failed")
            assertIs<ClaimResult.AlreadyClaimed>(seen)
            val rows = rig.rowsAfter(mark)
            assertEquals(listOf("claim.rejected"), rows.map { it.type }, "E2: the rejection outlives the rollback: $rows")
            assertEquals(i.id, rows.single().entityId)
        }

    @Test
    fun `S3 a claim on an unknown or terminal item records nothing`(): Unit =
        runBlocking {
            val rig = rig()
            val terminal = rig.seed("S3 terminal", Role.TERMINAL)

            val (unknown, unknownRows) = rig.written { rig.service.claim(UUID.randomUUID(), "agent-a", 60).ok() }
            val (done, doneRows) = rig.written { rig.service.claim(terminal.id, "agent-a", 60).ok() }

            assertIs<ClaimResult.NotFound>(unknown)
            assertIs<ClaimResult.TerminalItem>(done)
            assertEquals(emptyList(), unknownRows, "E2: NotFound records no row")
            assertEquals(emptyList(), doneRows, "E2: TerminalItem records no row")
        }

    // ---------------------------------------------------------------------------------------------
    // S4 -- release
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S4 a release by the holder records one claim released row with reason released`(): Unit =
        runBlocking {
            val rig = rig()
            val i = rig.seed("S4")
            rig.service.claim(i.id, "agent-a", 600).ok()

            val (result, rows) = rig.written { rig.service.release(i.id, "agent-a").ok() }

            assertIs<ReleaseResult.Success>(result)
            assertEquals(listOf("claim.released"), rows.map { it.type }, "E3: $rows")
            assertEquals(i.id, rows.single().entityId)
            assertEquals("released", rows.single().str("reason"))
            assertNull(rig.reload(i.id).claimedBy, "control: the claim is really gone")
        }

    @Test
    fun `S4 a release by a non-holder or of an unknown item records nothing and leaves the claim`(): Unit =
        runBlocking {
            val rig = rig()
            val i = rig.seed("S4 non-holder")
            rig.service.claim(i.id, "agent-a", 600).ok()

            val (wrongAgent, wrongRows) = rig.written { rig.service.release(i.id, "agent-b").ok() }
            val (unknown, unknownRows) = rig.written { rig.service.release(UUID.randomUUID(), "agent-a").ok() }

            assertIs<ReleaseResult.NotClaimedByYou>(wrongAgent)
            assertIs<ReleaseResult.NotFound>(unknown)
            assertEquals(emptyList(), wrongRows)
            assertEquals(emptyList(), unknownRows)
            assertEquals("agent-a", rig.reload(i.id).claimedBy, "control: the non-holder release left the claim in place")
        }

    // ---------------------------------------------------------------------------------------------
    // S5 -- clearClaim
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S5 clearing an actively claimed item records claim released with reason cleared`(): Unit =
        runBlocking {
            val rig = rig()
            val i = rig.seed("S5")
            rig.service.claim(i.id, "agent-a", 600).ok()

            val (cleared, rows) = rig.written { rig.service.clearClaim(i.id).ok() }

            assertTrue(cleared, "an active claim was cleared")
            assertEquals(listOf("claim.released"), rows.map { it.type }, "E4: $rows")
            assertEquals(i.id, rows.single().entityId)
            assertEquals("cleared", rows.single().str("reason"))
            assertNull(rig.reload(i.id).claimedBy, "control: the columns are really cleared")
        }

    @Test
    fun `S5 clearing an unclaimed item records nothing`(): Unit =
        runBlocking {
            val rig = rig()
            val i = rig.seed("S5 unclaimed")

            val (_, rows) = rig.written { rig.service.clearClaim(i.id).ok() }

            assertEquals(emptyList(), rows, "E4: nothing was claimed, so nothing is recorded")
        }

    // ---------------------------------------------------------------------------------------------
    // S6 -- leases
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S6 acquiring two keys records one lease acquired row per key with its ttl`(): Unit =
        runBlocking {
            val rig = rig()
            val h = rig.seed("S6 holder")

            val (result, rows) =
                rig.written { rig.service.acquireLeases(h.id, "actor-1", listOf("res-a" to 600, "res-b" to 120)).ok() }

            assertIs<LeaseAcquireResult.Success>(result)
            assertEquals(listOf("lease.acquired", "lease.acquired"), rows.map { it.type }, "E5: $rows")
            assertTrue(rows.all { it.entityId == h.id }, "the holder item is the row entity: $rows")
            assertEquals(mapOf("res-a" to 600L, "res-b" to 120L), rows.associate { it.str("key")!! to it.longKey("ttlSeconds") })
            assertEquals(
                2,
                rig.provider
                    .resourceLeaseRepository()
                    .findActiveForItem(h.id)
                    .size,
                "control: both leases exist"
            )
        }

    @Test
    fun `S6 a contended acquire records one lease rejected row naming the contended key and writes nothing`(): Unit =
        runBlocking {
            val rig = rig()
            val h = rig.seed("S6 holder")
            val o = rig.seed("S6 other")
            rig.service.acquireLeases(h.id, "actor-1", listOf("res-a" to 600)).ok()

            val (result, rows) = rig.written { rig.service.acquireLeases(o.id, "actor-2", listOf("res-a" to 600, "res-free" to 600)).ok() }

            assertIs<LeaseAcquireResult.Contended>(result)
            assertEquals(listOf("lease.rejected"), rows.map { it.type }, "E5: $rows")
            val row = rows.single()
            assertEquals(o.id, row.entityId)
            assertEquals(listOf("res-a"), row.payload()["contendedKeys"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals(
                0,
                rig.provider
                    .resourceLeaseRepository()
                    .findActiveForItem(o.id)
                    .size,
                "nothing is written, not even the free key"
            )
        }

    @Test
    fun `S6 releasing the holder leases records one lease released row per key and a force release marks forced`(): Unit =
        runBlocking {
            val rig = rig()
            val h = rig.seed("S6 holder")
            rig.service.acquireLeases(h.id, "actor-1", listOf("res-a" to 600, "res-b" to 600)).ok()

            val (released, rows) = rig.written { rig.service.releaseLeases(setOf(h.id)).ok() }

            assertEquals(2, assertIs<LeaseReleaseResult.Success>(released).releasedCount)
            assertEquals(listOf("lease.released", "lease.released"), rows.map { it.type }, "E6: $rows")
            assertEquals(setOf("res-a", "res-b"), rows.map { it.str("key") }.toSet())
            assertTrue(rows.all { it.intKey("count") == 1 && it.payload()["forced"]!!.jsonPrimitive.content == "false" }, "$rows")
            assertEquals(
                0,
                rig.provider
                    .resourceLeaseRepository()
                    .findActiveForItem(h.id)
                    .size,
                "control: the leases are gone"
            )

            rig.service.acquireLeases(h.id, "actor-1", listOf("res-c" to 600)).ok()
            val (_, forced) = rig.written { rig.service.forceReleaseLease("res-c", "operator").ok() }
            assertEquals(listOf("lease.released"), forced.map { it.type })
            assertEquals("res-c", forced.single().str("key"))
            assertEquals(
                "true",
                forced
                    .single()
                    .payload()["forced"]!!
                    .jsonPrimitive.content
            )
            assertEquals(h.id, forced.single().entityId)
        }

    // ---------------------------------------------------------------------------------------------
    // Probes
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `probe holder ids are case sensitive so a differently cased agent is a different holder`(): Unit =
        runBlocking {
            val rig = rig()
            val i = rig.seed("casing")
            rig.service.claim(i.id, "agent-a", 600).ok()

            val (claim, claimRows) = rig.written { rig.service.claim(i.id, "AGENT-A", 600).ok() }
            val (release, releaseRows) = rig.written { rig.service.release(i.id, "AGENT-A").ok() }

            assertIs<ClaimResult.AlreadyClaimed>(claim)
            assertEquals(listOf("claim.rejected"), claimRows.map { it.type })
            assertIs<ReleaseResult.NotClaimedByYou>(release)
            assertEquals(emptyList(), releaseRows)
            assertEquals("agent-a", rig.reload(i.id).claimedBy)
        }

    @Test
    fun `probe empty requirements and an empty holder set record no rows`(): Unit =
        runBlocking {
            val rig = rig()
            val h = rig.seed("empty")

            val (acquire, acquireRows) = rig.written { rig.service.acquireLeases(h.id, "actor-1", emptyList()).ok() }
            val (release, releaseRows) = rig.written { rig.service.releaseLeases(emptySet()).ok() }

            assertIs<LeaseAcquireResult.Success>(acquire)
            assertEquals(emptyList(), acquireRows)
            assertEquals(0, assertIs<LeaseReleaseResult.Success>(release).releasedCount)
            assertEquals(emptyList(), releaseRows)
        }

    @Test
    fun `probe a duplicate key in the requirements records exactly one lease acquired row`(): Unit =
        runBlocking {
            val rig = rig()
            val h = rig.seed("duplicate")

            val (_, rows) = rig.written { rig.service.acquireLeases(h.id, "actor-1", listOf("res-a" to 600, "res-a" to 600)).ok() }

            assertEquals(listOf("lease.acquired"), rows.map { it.type }, "one key, one row: $rows")
            assertEquals("res-a", rows.single().str("key"))
            assertEquals(
                1,
                rig.provider
                    .resourceLeaseRepository()
                    .findActiveForItem(h.id)
                    .size
            )
        }
}
