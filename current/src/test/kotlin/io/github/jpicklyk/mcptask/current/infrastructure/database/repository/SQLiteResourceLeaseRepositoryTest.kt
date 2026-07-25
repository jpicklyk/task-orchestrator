package io.github.jpicklyk.mcptask.current.infrastructure.database.repository

import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.LeaseAcquireResult
import io.github.jpicklyk.mcptask.current.domain.repository.LeaseReleaseResult
import io.github.jpicklyk.mcptask.current.domain.repository.ResourceLeaseRepository
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.test.SQLiteRepositoryTestBase
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Integration tests for [ResourceLeaseRepository] (SQLite implementation), using a real SQLite
 * in-memory database (via [SQLiteRepositoryTestBase]) since the acquire path uses SQLite-specific
 * `datetime('now', '+N seconds')` / `ON CONFLICT ... DO UPDATE` / `julianday()` syntax that H2
 * does not support.
 */
class SQLiteResourceLeaseRepositoryTest : SQLiteRepositoryTestBase() {
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

    /** Backdates every lease row for (resourceKey, holderItemId) to an already-expired expires_at. */
    private fun expireLease(
        resourceKey: String,
        holderItemId: UUID
    ) {
        transaction(db = database) {
            val uuidType =
                org.jetbrains.exposed.v1.core.java
                    .UUIDColumnType()
            val keyType =
                org.jetbrains.exposed.v1.core
                    .VarCharColumnType(255)
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
    // Acquire — success cases
    // -----------------------------------------------------------------------

    @Test
    fun `fresh acquire on an unheld key succeeds and sets all three timestamps`(): Unit =
        runBlocking {
            val holder = createHolder()

            val result = repository.acquireAll(holder, "agent-a", listOf("staging-db" to 900))

            assertIs<LeaseAcquireResult.Success>(result)
            assertEquals(1, result.leases.size)
            val lease = result.leases.single()
            assertEquals("staging-db", lease.resourceKey)
            assertEquals(holder, lease.holderItemId)
            assertEquals("agent-a", lease.acquiredByActorId)
            assertTrue(lease.acquiredAt <= lease.expiresAt)
            assertEquals(
                lease.acquiredAt.epochSecond,
                lease.originalAcquiredAt.epochSecond,
                "originalAcquiredAt should equal acquiredAt on first acquire"
            )
        }

    @Test
    fun `acquiring multiple distinct keys in one call succeeds for all of them`(): Unit =
        runBlocking {
            val holder = createHolder()

            val result = repository.acquireAll(holder, "agent-a", listOf("key-a" to 900, "key-b" to 900, "key-c" to 900))

            assertIs<LeaseAcquireResult.Success>(result)
            assertEquals(setOf("key-a", "key-b", "key-c"), result.leases.map { it.resourceKey }.toSet())
        }

    @Test
    fun `same-item re-acquire refreshes TTL but preserves originalAcquiredAt`(): Unit =
        runBlocking {
            val holder = createHolder()

            val first = repository.acquireAll(holder, "agent-a", listOf("staging-db" to 900))
            assertIs<LeaseAcquireResult.Success>(first)
            val firstOriginal = first.leases.single().originalAcquiredAt

            Thread.sleep(1100)

            val second = repository.acquireAll(holder, "agent-a", listOf("staging-db" to 1800))
            assertIs<LeaseAcquireResult.Success>(second)
            val secondLease = second.leases.single()

            assertEquals(
                firstOriginal.epochSecond,
                secondLease.originalAcquiredAt.epochSecond,
                "originalAcquiredAt must be preserved on re-acquire by the same holder"
            )
            assertTrue(
                secondLease.expiresAt > first.leases.single().expiresAt,
                "re-acquire should extend expiresAt"
            )
        }

    // -----------------------------------------------------------------------
    // Acquire — contention cases
    // -----------------------------------------------------------------------

    @Test
    fun `different-item acquire on an actively held key returns Contended with positive retryAfterMs`(): Unit =
        runBlocking {
            val holderA = createHolder("Holder A")
            val holderB = createHolder("Holder B")

            assertIs<LeaseAcquireResult.Success>(repository.acquireAll(holderA, "agent-a", listOf("staging-db" to 900)))

            val result = repository.acquireAll(holderB, "agent-b", listOf("staging-db" to 900))

            assertIs<LeaseAcquireResult.Contended>(result)
            assertEquals(listOf("staging-db"), result.contendedKeys)
            assertTrue(result.retryAfterMs > 0, "retryAfterMs should be positive for an actively held lease")
        }

    @Test
    fun `an expired lease is stealable by a different item`(): Unit =
        runBlocking {
            val holderA = createHolder("Holder A")
            val holderB = createHolder("Holder B")

            assertIs<LeaseAcquireResult.Success>(repository.acquireAll(holderA, "agent-a", listOf("staging-db" to 900)))
            expireLease("staging-db", holderA)

            val result = repository.acquireAll(holderB, "agent-b", listOf("staging-db" to 900))

            assertIs<LeaseAcquireResult.Success>(result)
            assertEquals(holderB, result.leases.single().holderItemId)
        }

    @Test
    fun `all-or-nothing - one contended key among several blocks the whole batch and persists nothing`(): Unit =
        runBlocking {
            val holderA = createHolder("Holder A")
            val holderB = createHolder("Holder B")

            // Holder A holds "key-x"; "key-y" is free.
            assertIs<LeaseAcquireResult.Success>(repository.acquireAll(holderA, "agent-a", listOf("key-x" to 900)))

            val result = repository.acquireAll(holderB, "agent-b", listOf("key-y" to 900, "key-x" to 900))

            assertIs<LeaseAcquireResult.Contended>(result)
            assertEquals(listOf("key-x"), result.contendedKeys)

            // Nothing must have been written for holder B — not even for the free "key-y".
            val holderBLeases = repository.findActiveForItem(holderB)
            assertTrue(holderBLeases.isEmpty(), "No partial rows may persist for holder B after a Contended result")

            val keyYLeases = repository.findActiveByKeys(listOf("key-y"))
            assertTrue(keyYLeases.isEmpty(), "key-y must remain unacquired after the all-or-nothing rollback")
        }

    // -----------------------------------------------------------------------
    // Release
    // -----------------------------------------------------------------------

    @Test
    fun `releaseAllForItem removes every lease held by that item and no others`(): Unit =
        runBlocking {
            val holderA = createHolder("Holder A")
            val holderB = createHolder("Holder B")
            assertIs<LeaseAcquireResult.Success>(repository.acquireAll(holderA, "agent-a", listOf("key-a" to 900, "key-b" to 900)))
            assertIs<LeaseAcquireResult.Success>(repository.acquireAll(holderB, "agent-b", listOf("key-c" to 900)))

            val result = repository.releaseAllForItem(holderA)

            assertIs<LeaseReleaseResult.Success>(result)
            assertEquals(2, result.releasedCount)
            assertTrue(repository.findActiveForItem(holderA).isEmpty())
            assertEquals(1, repository.findActiveForItem(holderB).size, "Holder B's lease must be untouched")
        }

    @Test
    fun `forceReleaseByKey frees the key for the next acquire regardless of current holder`(): Unit =
        runBlocking {
            val holderA = createHolder("Holder A")
            val holderB = createHolder("Holder B")
            assertIs<LeaseAcquireResult.Success>(repository.acquireAll(holderA, "agent-a", listOf("staging-db" to 900)))

            val released = repository.forceReleaseByKey("staging-db")
            assertIs<LeaseReleaseResult.Success>(released)
            assertEquals(1, released.releasedCount)

            val result = repository.acquireAll(holderB, "agent-b", listOf("staging-db" to 900))
            assertIs<LeaseAcquireResult.Success>(result)
            assertEquals(holderB, result.leases.single().holderItemId)
        }

    // -----------------------------------------------------------------------
    // Reads
    // -----------------------------------------------------------------------

    @Test
    fun `findActiveByKeys excludes expired rows`(): Unit =
        runBlocking {
            val holder = createHolder()
            assertIs<LeaseAcquireResult.Success>(repository.acquireAll(holder, "agent-a", listOf("staging-db" to 900)))
            expireLease("staging-db", holder)

            val active = repository.findActiveByKeys(listOf("staging-db"))
            assertTrue(active.isEmpty(), "Expired leases must not be reported as active")
        }

    @Test
    fun `findActiveForItem excludes expired rows`(): Unit =
        runBlocking {
            val holder = createHolder()
            assertIs<LeaseAcquireResult.Success>(repository.acquireAll(holder, "agent-a", listOf("staging-db" to 900)))
            expireLease("staging-db", holder)

            assertTrue(repository.findActiveForItem(holder).isEmpty())
        }

    @Test
    fun `findAllActive excludes expired rows across all holders`(): Unit =
        runBlocking {
            val holderA = createHolder("Holder A")
            val holderB = createHolder("Holder B")
            assertIs<LeaseAcquireResult.Success>(repository.acquireAll(holderA, "agent-a", listOf("key-a" to 900)))
            assertIs<LeaseAcquireResult.Success>(repository.acquireAll(holderB, "agent-b", listOf("key-b" to 900)))
            expireLease("key-a", holderA)

            val active = repository.findAllActive()
            assertEquals(1, active.size)
            assertEquals("key-b", active.single().resourceKey)
        }
}
