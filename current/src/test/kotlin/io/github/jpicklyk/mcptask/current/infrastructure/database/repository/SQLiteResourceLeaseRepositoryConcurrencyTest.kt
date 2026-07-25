package io.github.jpicklyk.mcptask.current.infrastructure.database.repository

import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.ClaimResult
import io.github.jpicklyk.mcptask.current.domain.repository.LeaseAcquireResult
import io.github.jpicklyk.mcptask.current.domain.repository.ResourceLeaseRepository
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository
import io.github.jpicklyk.mcptask.current.test.SQLiteRepositoryTestBase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Concurrency tests for [ResourceLeaseRepository.acquireAll], mirroring the real-thread race
 * pattern in [SQLiteWorkItemRepositoryClaimTest] (`concurrent claim race with two real threads`).
 *
 * Also covers the gap-#1 regression: a claim ([WorkItemRepository.claim]) and a resource lease
 * ([ResourceLeaseRepository.acquireAll]) on the SAME item are independent lifecycles — acquiring a
 * lease must never disturb an existing claim, and refreshing a claim must never disturb existing
 * leases (see the "Isolation from claims" section of [ResourceLeaseRepository]'s KDoc).
 */
class SQLiteResourceLeaseRepositoryConcurrencyTest : SQLiteRepositoryTestBase() {
    private fun leaseRepository(): ResourceLeaseRepository = repositoryProvider.resourceLeaseRepository()

    private fun workItemRepository(): WorkItemRepository = repositoryProvider.workItemRepository()

    private suspend fun createHolder(title: String = "Holder"): UUID {
        val result = workItemRepository().create(WorkItem(title = title))
        assertIs<Result.Success<WorkItem>>(result)
        return result.data.id
    }

    @Test
    fun `N threads race one key — exactly one Success`(): Unit =
        runBlocking {
            org.jetbrains.exposed.v1.jdbc.transactions.transaction(db = database) {
                exec("PRAGMA busy_timeout = 15000")
            }

            val threadCount = 5
            val holders = (1..threadCount).map { createHolder("Racer $it") }
            val executor = Executors.newFixedThreadPool(threadCount)
            val startGate = CountDownLatch(1)
            val results = List(threadCount) { AtomicReference<Any?>() }

            val futures =
                (0 until threadCount).map { i ->
                    executor.submit {
                        startGate.await()
                        try {
                            results[i].set(
                                runBlocking {
                                    leaseRepository().acquireAll(holders[i], "agent-$i", listOf("contended-key" to 900))
                                }
                            )
                        } catch (e: Exception) {
                            results[i].set(e)
                        }
                    }
                }

            startGate.countDown()
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
            executor.shutdown()

            val outcomes = results.map { it.get() }
            outcomes.forEach { assertNotNull(it, "Every thread must produce an outcome (result or exception)") }

            val successes = outcomes.filterIsInstance<LeaseAcquireResult.Success>()
            assertEquals(1, successes.size, "Exactly one thread may win the lease race; got outcomes: $outcomes")

            // The final DB row must be atomically consistent — held by exactly the single winner.
            val active = leaseRepository().findActiveByKeys(listOf("contended-key"))
            assertEquals(1, active.size)
            assertEquals(
                successes
                    .single()
                    .leases
                    .single()
                    .holderItemId,
                active.single().holderItemId
            )
        }

    // -----------------------------------------------------------------------
    // Gap-#1 regression: claim and lease acquisition are independent lifecycles
    // -----------------------------------------------------------------------

    @Test
    fun `acquiring a resource lease does not disturb an existing claim on the same item`(): Unit =
        runBlocking {
            val holder = createHolder()

            val claim = workItemRepository().claim(holder, "agent-x", 900)
            assertIs<ClaimResult.Success>(claim)

            val lease = leaseRepository().acquireAll(holder, "agent-x", listOf("staging-db" to 900))
            assertIs<LeaseAcquireResult.Success>(lease)

            val afterLease = workItemRepository().getById(holder)
            assertIs<Result.Success<WorkItem>>(afterLease)
            assertEquals("agent-x", afterLease.data.claimedBy, "The claim must survive lease acquisition")
            assertNotNull(afterLease.data.claimedAt)
            assertNotNull(afterLease.data.claimExpiresAt)
            assertNotNull(afterLease.data.originalClaimedAt)
        }

    @Test
    fun `refreshing a claim does not disturb existing resource leases held by the same item`(): Unit =
        runBlocking {
            val holder = createHolder()

            val lease = leaseRepository().acquireAll(holder, "agent-x", listOf("staging-db" to 900))
            assertIs<LeaseAcquireResult.Success>(lease)

            // Re-claim (refresh TTL) on the same item.
            val refreshedClaim = workItemRepository().claim(holder, "agent-x", 1800)
            assertIs<ClaimResult.Success>(refreshedClaim)

            val activeLeases = leaseRepository().findActiveForItem(holder)
            assertEquals(1, activeLeases.size, "The lease must survive a claim refresh")
            assertEquals("staging-db", activeLeases.single().resourceKey)
        }

    @Test
    fun `releasing an item's claim does not release its resource leases`(): Unit =
        runBlocking {
            val holder = createHolder()

            assertIs<ClaimResult.Success>(workItemRepository().claim(holder, "agent-x", 900))
            assertIs<LeaseAcquireResult.Success>(leaseRepository().acquireAll(holder, "agent-x", listOf("staging-db" to 900)))

            workItemRepository().release(holder, "agent-x")

            val afterRelease = workItemRepository().getById(holder)
            assertIs<Result.Success<WorkItem>>(afterRelease)
            assertNull(afterRelease.data.claimedBy, "Claim must be released")

            val activeLeases = leaseRepository().findActiveForItem(holder)
            assertEquals(1, activeLeases.size, "Releasing the claim must not release the item's resource leases")
        }
}
