package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository

import io.github.jpicklyk.mcptask.current.application.port.ClaimResult
import io.github.jpicklyk.mcptask.current.application.port.Clock
import io.github.jpicklyk.mcptask.current.application.port.LeaseAcquireResult
import io.github.jpicklyk.mcptask.current.application.port.LeaseReleaseResult
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.test.SettableClock
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P7 (item beeef6f7): resource-lease SQL bound to the injected clock with the exact-expiry boundary, the SQL-side
 * lease-history limit, and one instant shared by every store call inside a unit.
 *
 * Oracles (frozen test-plan): plan 3.12 l.352-354 (clock bound into claim/lease SQL), task-scope D5 (an instant
 * equal to the expiry is expired, including at release: `expires_at <= now` closes the interval as 'expired'),
 * lease contention retryAfterMs = MIN(expires_at) - now (floor 1), AR-103 part (history `limit` applied in SQL after
 * the acquiredAt DESC order), AR-50 (claim and lease writes in one unit see one instant).
 * Fixture time T = 2001-02-03 04:05:06.789Z (far from wall-clock time). Table and column names are the ones pinned
 * by the committed schema snapshots (resource_leases, resource_lease_history).
 */
class P7LeaseBoundaryTest {
    private val t: Instant = Instant.parse("2001-02-03T04:05:06.789Z")
    private val clock = SettableClock(t)

    @RegisterExtension
    @JvmField
    val sqliteDb = SqliteTestDatabase.perMethod(clock = clock)

    private val provider get() = sqliteDb.repositoryProvider()
    private val leases get() = provider.resourceLeaseRepository()
    private val jdbc get() = sqliteDb.jdbcUrl

    private suspend fun holder(title: String = "Holder"): UUID = provider.itemStore().create(WorkItem(title = title)).id

    // ---- S10: lease contention, expiry and release at the exact boundary --------------------------------------

    @Test
    fun `S10 a lease acquired at T with ttl 30 is stored as canonical UTC text from the bound clock`(): Unit =
        runBlocking {
            val h = holder()

            val result = leases.acquireAll(h, "actor-a", listOf("k" to 30))

            val success = assertIs<LeaseAcquireResult.Success>(result)
            assertEquals(t.plusSeconds(30), success.leases.single().expiresAt)
            val row =
                P7Raw
                    .query(
                        jdbc,
                        "SELECT acquired_at, expires_at, original_acquired_at FROM resource_leases WHERE resource_key = ?",
                        "k"
                    ) { Triple(it.getString(1), it.getString(2), it.getString(3)) }
                    .single()
            assertEquals(Triple("2001-02-03 04:05:06.789", "2001-02-03 04:05:36.789", "2001-02-03 04:05:06.789"), row)
        }

    @Test
    fun `S10 at E minus 1ms a competing holder is contended with retryAfter 1 and at E it acquires`(): Unit =
        runBlocking {
            val h1 = holder("H1")
            val h2 = holder("H2")
            assertIs<LeaseAcquireResult.Success>(leases.acquireAll(h1, "actor-a", listOf("k" to 30)))
            val e = t.plusSeconds(30)

            clock.set(e.minusMillis(1))
            val contended = leases.acquireAll(h2, "actor-b", listOf("k" to 30))
            val c = assertIs<LeaseAcquireResult.Contended>(contended)
            assertEquals(listOf("k"), c.contendedKeys)
            assertEquals(1L, c.retryAfterMs, "retryAfterMs is MIN(expires_at) - now = 1ms")
            assertEquals(listOf(h1), leases.findActiveByKeys(listOf("k")).map { it.holderItemId })
            assertEquals(listOf(h1), leases.findActiveForItem(h1).map { it.holderItemId })
            assertEquals(listOf(h1), leases.findAllActive().map { it.holderItemId })

            clock.set(e)
            assertEquals(emptyList(), leases.findActiveByKeys(listOf("k")).map { it.holderItemId }, "at E the lease is expired")
            assertEquals(emptyList(), leases.findActiveForItem(h1).map { it.holderItemId })
            assertEquals(emptyList(), leases.findAllActive().map { it.holderItemId })

            val taken = leases.acquireAll(h2, "actor-b", listOf("k" to 30))
            val success = assertIs<LeaseAcquireResult.Success>(taken)
            assertEquals(h2, success.leases.single().holderItemId)

            val intervals = leases.findRecentIntervals("k", 10)
            assertEquals(2, intervals.size)
            val closed = intervals.single { it.holderItemId == h1 }
            assertEquals("expired", closed.releaseReason)
            assertEquals(e, closed.releasedAt, "the lapsed interval closes at its own expiry")
            assertNull(intervals.single { it.holderItemId == h2 }.releasedAt)
        }

    @Test
    fun `S10 retryAfter is the remaining time to the earliest expiry`(): Unit =
        runBlocking {
            val h1 = holder("H1")
            val h2 = holder("H2")
            assertIs<LeaseAcquireResult.Success>(leases.acquireAll(h1, "actor-a", listOf("k" to 30)))

            clock.advanceSeconds(10)
            val contended = leases.acquireAll(h2, "actor-b", listOf("k" to 30))

            assertEquals(20_000L, assertIs<LeaseAcquireResult.Contended>(contended).retryAfterMs)
        }

    @Test
    fun `S10 releasing exactly at the expiry records reason expired with releasedAt equal to the expiry`(): Unit =
        runBlocking {
            val h = holder()
            assertIs<LeaseAcquireResult.Success>(leases.acquireAll(h, "actor-a", listOf("k" to 30)))
            val e = t.plusSeconds(30)
            clock.set(e)

            assertIs<LeaseReleaseResult.Success>(leases.releaseAllForItem(h))

            val interval = leases.findRecentIntervals("k", 10).single()
            assertEquals("expired", interval.releaseReason)
            assertEquals(e, interval.releasedAt)
            assertEquals(P7Raw.canon(e), P7Raw.text(jdbc, "SELECT released_at FROM resource_lease_history WHERE resource_key = ?", "k"))
        }

    @Test
    fun `S10 releasing one millisecond before the expiry records reason released at that instant`(): Unit =
        runBlocking {
            val h = holder()
            assertIs<LeaseAcquireResult.Success>(leases.acquireAll(h, "actor-a", listOf("k" to 30)))
            val before = t.plusSeconds(30).minusMillis(1)
            clock.set(before)

            assertIs<LeaseReleaseResult.Success>(leases.releaseAllForItem(h))

            val interval = leases.findRecentIntervals("k", 10).single()
            assertEquals("released", interval.releaseReason)
            assertEquals(before, interval.releasedAt)
        }

    @Test
    fun `S10 probe releasing long after the expiry clamps releasedAt to the expiry`(): Unit =
        runBlocking {
            val h = holder()
            assertIs<LeaseAcquireResult.Success>(leases.acquireAll(h, "actor-a", listOf("k" to 30)))
            clock.set(t.plusSeconds(30).plusSeconds(5))

            assertIs<LeaseReleaseResult.Success>(leases.releaseAllForItem(h))

            val interval = leases.findRecentIntervals("k", 10).single()
            assertEquals("expired", interval.releaseReason)
            assertEquals(t.plusSeconds(30), interval.releasedAt)
        }

    // ---- S11: history limit and ordering ---------------------------------------------------------------------

    private fun insertOpenInterval(
        key: String,
        holderId: UUID,
        acquiredAt: Instant,
        expiresAt: Instant
    ) {
        // The history table has no foreign key (pinned by the committed schema snapshot), so a raw row is a valid
        // fixture for an interval whose holder need not exist.
        P7Raw.exec(
            jdbc,
            "INSERT INTO resource_lease_history (resource_key, holder_item_id, acquired_at, expires_at, released_at) " +
                "VALUES (?, ?, ?, ?, NULL)",
            key,
            holderId,
            P7Raw.canon(acquiredAt),
            P7Raw.canon(expiresAt)
        )
    }

    @Test
    fun `S11 findHoldersAt applies the limit after the newest-first acquiredAt order`(): Unit =
        runBlocking {
            val holders = (1..4).map { UUID.randomUUID() }
            holders.forEachIndexed { i, id ->
                insertOpenInterval("k", id, t.plusSeconds(i + 1L), t.plusSeconds(3_600))
            }
            val at = t.plusSeconds(10)

            assertEquals(listOf(holders[3], holders[2]), leases.findHoldersAt("k", at, 2).map { it.holderItemId })
            assertEquals(listOf(holders[3]), leases.findHoldersAt("k", at, 1).map { it.holderItemId })
            assertEquals(holders.reversed(), leases.findHoldersAt("k", at, 4).map { it.holderItemId })
            assertEquals(4, leases.findHoldersAt("k", at, 100).size)
            assertEquals(4, leases.findHoldersAt("k", at).size, "the default limit must not truncate")
        }

    @Test
    fun `S11 the limit applies across keys when no key is given`(): Unit =
        runBlocking {
            val onK = (1..3).map { UUID.randomUUID() }
            onK.forEachIndexed { i, id -> insertOpenInterval("k", id, t.plusSeconds(i + 1L), t.plusSeconds(3_600)) }
            val onOther = UUID.randomUUID()
            insertOpenInterval("other", onOther, t.plusSeconds(5), t.plusSeconds(3_600))

            val newestTwo = leases.findHoldersAt(null, t.plusSeconds(10), 2).map { it.holderItemId }

            assertEquals(listOf(onOther, onK[2]), newestTwo)
        }

    // ---- S5: one instant for every store call inside a unit --------------------------------------------------

    /** Every read advances one second, so two separate reads of the clock can never agree. */
    private class TickingClock(
        private val start: Instant
    ) : Clock {
        private val reads = AtomicLong()

        override fun now(): Instant = start.plusSeconds(reads.getAndIncrement())
    }

    private val ticking = TickingClock(t)

    @RegisterExtension
    @JvmField
    val tickingDb = SqliteTestDatabase.perMethod(clock = ticking)

    @Test
    fun `S5 a claim and a lease acquired in one unit share the unit instant`(): Unit =
        runBlocking {
            val stores = tickingDb.repositoryProvider()
            val itemId = stores.itemStore().create(WorkItem(title = "Both")).id
            var scopeNow: Instant? = null

            val outcome =
                tickingDb.unitOfWork().write("P7.S5") {
                    scopeNow = now
                    val claim = stores.claimStore().claim(itemId, "agent-a", 120)
                    val lease = stores.resourceLeaseRepository().acquireAll(itemId, "agent-a", listOf("k" to 120))
                    Outcome.Ok(claim to lease)
                }

            val ok = assertIs<Outcome.Ok<*>>(outcome)
            val results = ok.value as Pair<*, *>
            assertIs<ClaimResult.Success>(results.first)
            assertIs<LeaseAcquireResult.Success>(results.second)
            val unitInstant = assertNotNull(scopeNow)
            val db = tickingDb.jdbcUrl
            assertEquals(
                P7Raw.canon(unitInstant),
                P7Raw.text(db, "SELECT claimed_at FROM work_items WHERE id = ?", itemId),
                "claimed_at must be the unit instant"
            )
            assertEquals(
                P7Raw.canon(unitInstant),
                P7Raw.text(db, "SELECT acquired_at FROM resource_leases WHERE resource_key = ?", "k"),
                "the lease acquired_at must be the same unit instant as the claim"
            )
            assertEquals(
                P7Raw.canon(unitInstant.plusSeconds(120)),
                P7Raw.text(db, "SELECT claim_expires_at FROM work_items WHERE id = ?", itemId)
            )
            assertEquals(
                P7Raw.canon(unitInstant.plusSeconds(120)),
                P7Raw.text(db, "SELECT expires_at FROM resource_leases WHERE resource_key = ?", "k")
            )
            assertTrue(ticking.now() > unitInstant, "the ticking clock really advances, so a second read would have differed")
        }
}
