package io.github.jpicklyk.mcptask.current.infrastructure.database.repository

import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.LeaseAcquireResult
import io.github.jpicklyk.mcptask.current.domain.repository.LeaseReleaseResult
import io.github.jpicklyk.mcptask.current.domain.repository.ResourceLeaseRepository
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.test.SQLiteRepositoryTestBase
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.VarCharColumnType
import org.jetbrains.exposed.v1.core.java.UUIDColumnType
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration tests for the `resource_lease_history` write paths on
 * [io.github.jpicklyk.mcptask.current.infrastructure.repository.SQLiteResourceLeaseRepository] —
 * interval-open-on-acquire, refresh-extends-in-place, steal-closes-prior-as-expired,
 * release/force-release close reasons, at-T boundary semantics, and history's survival of holder
 * work-item deletion (unlike the CASCADE-linked live `resource_leases` row). Companion to
 * [SQLiteResourceLeaseRepositoryTest], which covers the live-row behavior this history mirrors.
 */
class SQLiteResourceLeaseRepositoryHistoryTest : SQLiteRepositoryTestBase() {
    private lateinit var repository: ResourceLeaseRepository

    @BeforeEach
    fun setUp() {
        repository = repositoryProvider.resourceLeaseRepository()
    }

    private suspend fun createHolder(title: String = "Holder"): UUID {
        val result = repositoryProvider.workItemRepository().create(WorkItem(title = title))
        assertIs<Result.Success<WorkItem>>(result)
        return result.data.id
    }

    /**
     * Backdates the lease for (resourceKey, holderItemId) to an already-expired expires_at — in
     * BOTH the live table and the open history interval. Production writes keep the two tables'
     * expiry in agreement (history is written from the live row's values in the same transaction),
     * so simulating time-passage must age them together; diverging them would exercise a state the
     * repository never produces.
     */
    private fun expireLease(
        resourceKey: String,
        holderItemId: UUID
    ) {
        transaction(db = database) {
            val uuidType = UUIDColumnType()
            val keyType = VarCharColumnType(255)
            exec(
                """
                UPDATE resource_leases
                   SET expires_at = datetime('now', '-10 seconds')
                 WHERE resource_key = ? AND holder_item_id = ?
                """.trimIndent(),
                args = listOf(keyType to resourceKey, uuidType to holderItemId)
            )
            exec(
                """
                UPDATE resource_lease_history
                   SET expires_at = datetime('now', '-10 seconds')
                 WHERE resource_key = ? AND holder_item_id = ? AND released_at IS NULL
                """.trimIndent(),
                args = listOf(keyType to resourceKey, uuidType to holderItemId)
            )
        }
    }

    // -----------------------------------------------------------------------
    // Interval lifecycle: open / refresh / steal
    // -----------------------------------------------------------------------

    @Test
    fun `a fresh acquire opens exactly one interval`(): Unit =
        runBlocking {
            val holder = createHolder()
            assertIs<LeaseAcquireResult.Success>(repository.acquireAll(holder, "agent-a", listOf("staging-db" to 900)))

            val intervals = repository.findRecentIntervals("staging-db", 10)
            assertEquals(1, intervals.size)
            val interval = intervals.single()
            assertEquals(holder, interval.holderItemId)
            assertEquals("agent-a", interval.acquiredByActorId)
            assertNull(interval.releasedAt, "a freshly opened interval must have no releasedAt")
            assertNull(interval.releaseReason)
        }

    @Test
    fun `a same-holder refresh extends expiresAt in place without opening a new row`(): Unit =
        runBlocking {
            val holder = createHolder()
            val first = repository.acquireAll(holder, "agent-a", listOf("staging-db" to 900))
            assertIs<LeaseAcquireResult.Success>(first)
            val firstExpiry = first.leases.single().expiresAt

            Thread.sleep(1100)

            val second = repository.acquireAll(holder, "agent-a", listOf("staging-db" to 1800))
            assertIs<LeaseAcquireResult.Success>(second)

            val intervals = repository.findRecentIntervals("staging-db", 10)
            assertEquals(1, intervals.size, "a same-holder refresh must extend the OPEN interval, not open a second row")
            val interval = intervals.single()
            assertNull(interval.releasedAt)
            assertTrue(interval.expiresAt > firstExpiry, "refresh must extend expiresAt")
        }

    @Test
    fun `stealing an expired lease closes the prior holder's interval as expired at its own old expiry`(): Unit =
        runBlocking {
            val holderA = createHolder("Holder A")
            val holderB = createHolder("Holder B")

            val acquiredByA = repository.acquireAll(holderA, "agent-a", listOf("staging-db" to 900))
            assertIs<LeaseAcquireResult.Success>(acquiredByA)
            // Backdates the LIVE row's expires_at to ~10s in the past — the steal must close A's
            // interval at THAT (post-backdate) expiry, which is what the DB factually records as
            // the moment the lease lapsed.
            expireLease("staging-db", holderA)

            val stolen = repository.acquireAll(holderB, "agent-b", listOf("staging-db" to 900))
            assertIs<LeaseAcquireResult.Success>(stolen)

            val intervals = repository.findRecentIntervals("staging-db", 10)
            assertEquals(2, intervals.size, "expect the closed A interval plus the new open B interval")

            val aInterval = intervals.single { it.holderItemId == holderA }
            val bInterval = intervals.single { it.holderItemId == holderB }
            assertEquals("expired", aInterval.releaseReason)
            val aReleasedAt = requireNotNull(aInterval.releasedAt)
            assertTrue(
                aReleasedAt.isBefore(bInterval.acquiredAt.minusSeconds(5)),
                "A's interval must close at its (backdated) old expiry — clearly before the steal, not 'now'",
            )

            assertNull(bInterval.releasedAt, "B's new interval must be open")
        }

    @Test
    fun `re-take after an intervening expired holder closes that holder's interval — no double-holder at-T`(): Unit =
        runBlocking {
            // Regression for the post-merge field report on PR #262: A holds K, expires; B steals,
            // expires; A RE-TAKES (own expired row still present -> refresh branch). Before the fix,
            // staleOtherRows was only computed when A had no prior row, so B's interval stayed open —
            // and a later releaseAllForItem(B) closed it at 'now', making an at-T query report both
            // A and B as simultaneous holders.
            val holderA = createHolder("Holder A")
            val holderB = createHolder("Holder B")

            assertIs<LeaseAcquireResult.Success>(repository.acquireAll(holderA, "agent-a", listOf("staging-db" to 900)))
            expireLease("staging-db", holderA)
            assertIs<LeaseAcquireResult.Success>(repository.acquireAll(holderB, "agent-b", listOf("staging-db" to 900)))
            expireLease("staging-db", holderB)

            // A re-takes: own stale row exists (refresh path) AND B's stale row exists.
            val retaken = repository.acquireAll(holderA, "agent-a", listOf("staging-db" to 900))
            assertIs<LeaseAcquireResult.Success>(retaken)

            val bInterval =
                repository
                    .findRecentIntervals("staging-db", 10)
                    .single { it.holderItemId == holderB }
            assertEquals("expired", bInterval.releaseReason, "B's interval must be closed as expired by A's re-take")
            assertNotNull(bInterval.releasedAt)

            // Even if B's item releases later, the audit must never show two holders at once:
            assertIs<LeaseReleaseResult.Success>(repository.releaseAllForItem(holderB))
            val now = Instant.now()
            val holdersNow = repository.findHoldersAt("staging-db", now)
            assertEquals(1, holdersNow.size, "exactly one holder at T — got: ${holdersNow.map { it.holderItemId }}")
            assertEquals(holderA, holdersNow.single().holderItemId)
        }

    @Test
    fun `releasing an already-expired lease clamps releasedAt to expiresAt with reason expired`(): Unit =
        runBlocking {
            // The close timestamp must never extend an interval past its own expiry — otherwise a
            // late releaseAllForItem inflates the apparent hold window for at-T queries.
            val holder = createHolder()
            assertIs<LeaseAcquireResult.Success>(repository.acquireAll(holder, "agent-a", listOf("staging-db" to 900)))
            expireLease("staging-db", holder)

            assertIs<LeaseReleaseResult.Success>(repository.releaseAllForItem(holder))

            val interval = repository.findRecentIntervals("staging-db", 10).single()
            assertEquals("expired", interval.releaseReason, "an already-lapsed hold closes as expired, not released")
            val releasedAt = requireNotNull(interval.releasedAt)
            assertTrue(
                !releasedAt.isAfter(Instant.now().minusSeconds(5)),
                "releasedAt must be clamped to the (backdated) expiry, not stamped 'now': $releasedAt",
            )
        }

    @Test
    fun `history close comparisons normalize mixed timestamp shapes via datetime()`(): Unit =
        runBlocking {
            // Constants-only pin of the sub-second cross-shape bug (beta field report, PR #262):
            // Exposed timestamp columns store fractional seconds; datetime('now') is
            // second-precision; raw TEXT comparison misorders them within a shared second. The
            // clamp SQL must therefore normalize both sides with datetime(). No wall clock here —
            // the boundary cannot be exercised deterministically with real time.
            transaction(db = database) {
                var sameSecondRaw = ""
                var normLt = ""
                var normMin = ""
                exec(
                    "SELECT '2026-01-01 00:00:00.937' < '2026-01-01 00:00:00', " +
                        "datetime('2026-01-01 00:00:00.937') < '2026-01-01 00:00:01', " +
                        "min('2026-01-01 00:00:01', datetime('2026-01-01 00:00:00.937'))"
                ) { rs ->
                    if (rs.next()) {
                        sameSecondRaw = rs.getString(1)
                        normLt = rs.getString(2)
                        normMin = rs.getString(3)
                    }
                }
                assertEquals("0", sameSecondRaw, "raw cross-shape compare within a shared second misorders (the bug shape)")
                assertEquals("1", normLt, "datetime() normalization restores chronological ordering")
                assertEquals("2026-01-01 00:00:00", normMin, "the clamp picks the normalized expiry, not 'now'")
            }
        }

    // -----------------------------------------------------------------------
    // Release / force-release close reasons
    // -----------------------------------------------------------------------

    @Test
    fun `releaseAllForItem closes the open interval with reason released`(): Unit =
        runBlocking {
            val holder = createHolder()
            assertIs<LeaseAcquireResult.Success>(repository.acquireAll(holder, "agent-a", listOf("staging-db" to 900)))

            val released = repository.releaseAllForItem(holder)
            assertIs<LeaseReleaseResult.Success>(released)

            val interval = repository.findRecentIntervals("staging-db", 10).single()
            assertEquals("released", interval.releaseReason)
            assertEquals(null, interval.releasedByActorId, "a normal release has no acting principal")
            assertTrue(interval.releasedAt != null)
        }

    @Test
    fun `forceReleaseByKey closes the open interval with reason force_released and records the actor`(): Unit =
        runBlocking {
            val holder = createHolder()
            assertIs<LeaseAcquireResult.Success>(repository.acquireAll(holder, "agent-a", listOf("staging-db" to 900)))

            val released = repository.forceReleaseByKey("staging-db", "admin-token-123")
            assertIs<LeaseReleaseResult.Success>(released)

            val interval = repository.findRecentIntervals("staging-db", 10).single()
            assertEquals("force_released", interval.releaseReason)
            assertEquals("admin-token-123", interval.releasedByActorId)
        }

    @Test
    fun `forceReleaseByKey with no actorId records a null released-by`(): Unit =
        runBlocking {
            val holder = createHolder()
            assertIs<LeaseAcquireResult.Success>(repository.acquireAll(holder, "agent-a", listOf("staging-db" to 900)))

            assertIs<LeaseReleaseResult.Success>(repository.forceReleaseByKey("staging-db"))

            val interval = repository.findRecentIntervals("staging-db", 10).single()
            assertEquals("force_released", interval.releaseReason)
            assertNull(interval.releasedByActorId)
        }

    // -----------------------------------------------------------------------
    // findHoldersAt — boundary semantics
    // -----------------------------------------------------------------------

    @Test
    fun `findHoldersAt treats at equal to acquiredAt as held`(): Unit =
        runBlocking {
            val holder = createHolder()
            val acquired = repository.acquireAll(holder, "agent-a", listOf("staging-db" to 900))
            assertIs<LeaseAcquireResult.Success>(acquired)
            val interval = repository.findRecentIntervals("staging-db", 1).single()

            val holders = repository.findHoldersAt("staging-db", interval.acquiredAt)
            assertEquals(1, holders.size, "at == acquiredAt must be treated as held (inclusive lower bound)")
            assertEquals(holder, holders.single().holderItemId)
        }

    @Test
    fun `findHoldersAt treats at equal to the open interval's expiresAt as NOT held`(): Unit =
        runBlocking {
            val holder = createHolder()
            assertIs<LeaseAcquireResult.Success>(repository.acquireAll(holder, "agent-a", listOf("staging-db" to 900)))
            val interval = repository.findRecentIntervals("staging-db", 1).single()

            val holders = repository.findHoldersAt("staging-db", interval.expiresAt)
            assertTrue(holders.isEmpty(), "at == expiresAt must be treated as NOT held (exclusive upper bound)")
        }

    @Test
    fun `findHoldersAt treats at equal to a closed interval's releasedAt as NOT held`(): Unit =
        runBlocking {
            val holder = createHolder()
            assertIs<LeaseAcquireResult.Success>(repository.acquireAll(holder, "agent-a", listOf("staging-db" to 900)))
            // SQLite timestamps are second-precision: a same-second acquire+release produces an
            // empty [S, S) interval where nothing is ever "held". Sleep past the second boundary
            // so acquired_at < released_at and the just-before probe has a real interval to hit.
            Thread.sleep(1100)
            assertIs<LeaseReleaseResult.Success>(repository.releaseAllForItem(holder))
            val interval = repository.findRecentIntervals("staging-db", 1).single()
            val releasedAt = requireNotNull(interval.releasedAt)

            val heldAtRelease = repository.findHoldersAt("staging-db", releasedAt)
            assertTrue(heldAtRelease.isEmpty(), "at == releasedAt must be treated as NOT held (exclusive upper bound)")

            val heldJustBefore = repository.findHoldersAt("staging-db", releasedAt.minusMillis(1))
            assertEquals(1, heldJustBefore.size, "an instant just before releasedAt must still be held")
        }

    @Test
    fun `an open interval is bounded by expiresAt for at-T queries even though it never closed`(): Unit =
        runBlocking {
            val holder = createHolder()
            assertIs<LeaseAcquireResult.Success>(repository.acquireAll(holder, "agent-a", listOf("staging-db" to 900)))
            val interval = repository.findRecentIntervals("staging-db", 1).single()

            val farFuture = interval.expiresAt.plusSeconds(3600)
            val holders = repository.findHoldersAt("staging-db", farFuture)
            assertTrue(holders.isEmpty(), "an open interval must not be reported held past its own expiresAt")
        }

    @Test
    fun `findHoldersAt with a null resourceKey searches across all keys`(): Unit =
        runBlocking {
            val holderA = createHolder("Holder A")
            val holderB = createHolder("Holder B")
            assertIs<LeaseAcquireResult.Success>(repository.acquireAll(holderA, "agent-a", listOf("key-a" to 900)))
            assertIs<LeaseAcquireResult.Success>(repository.acquireAll(holderB, "agent-b", listOf("key-b" to 900)))

            val now = Instant.now()
            val holders = repository.findHoldersAt(null, now)
            assertEquals(2, holders.size)
            assertEquals(setOf(holderA, holderB), holders.map { it.holderItemId }.toSet())
        }

    // -----------------------------------------------------------------------
    // History survives holder deletion
    // -----------------------------------------------------------------------

    @Test
    fun `history survives work-item DELETE`(): Unit =
        runBlocking {
            // The live-row ON DELETE CASCADE is pinned by V15ResourceLeasesMigrationTest /
            // V16ResourceLeaseHistoryMigrationTest with PRAGMA foreign_keys=ON (the shared test-base
            // connection does not enable that pragma, so cascades are not observable here). This
            // test pins the complementary property: history rows have NO foreign key and outlive
            // both the lease lifecycle and the holder work item itself.
            val holder = createHolder()
            assertIs<LeaseAcquireResult.Success>(repository.acquireAll(holder, "agent-a", listOf("staging-db" to 900)))
            assertIs<LeaseReleaseResult.Success>(repository.releaseAllForItem(holder))

            val deleteResult = repositoryProvider.workItemRepository().delete(holder)
            assertIs<Result.Success<Boolean>>(deleteResult)

            val intervals = repository.findRecentIntervals("staging-db", 10)
            assertEquals(1, intervals.size, "the history row must survive holder deletion — no FK, no CASCADE")
            assertEquals(holder, intervals.single().holderItemId)
            assertEquals("released", intervals.single().releaseReason)
        }
}
