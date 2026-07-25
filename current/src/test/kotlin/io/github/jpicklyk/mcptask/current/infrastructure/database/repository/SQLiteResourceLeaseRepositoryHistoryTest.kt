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

    /** Backdates every LIVE lease row for (resourceKey, holderItemId) to an already-expired expires_at. */
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
