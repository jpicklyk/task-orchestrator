package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository

import io.github.jpicklyk.mcptask.current.application.port.ClaimResult
import io.github.jpicklyk.mcptask.current.application.port.LeaseAcquireResult
import io.github.jpicklyk.mcptask.current.application.port.LeaseStore
import io.github.jpicklyk.mcptask.current.application.port.WorkItemRepository
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Isolation tests for claims and leases on the same item. The contention races (N holders racing one key, N agents racing
 * one claim) live in the `contention` package, which runs them through the unit of work over a file-backed WAL database
 * and asserts every loser's exact result.
 *
 * Covers the gap-#1 regression: a claim ([WorkItemRepository.claim]) and a resource lease
 * ([LeaseStore.acquireAll]) on the SAME item are independent lifecycles — acquiring a
 * lease must never disturb an existing claim, and refreshing a claim must never disturb existing
 * leases (see the "Isolation from claims" section of [LeaseStore]'s KDoc).
 */
class SQLiteResourceLeaseRepositoryConcurrencyTest {
    @RegisterExtension
    @JvmField
    val sqliteDb = SqliteTestDatabase.perMethod()

    private val database get() = sqliteDb.database
    private val repositoryProvider get() = sqliteDb.repositoryProvider()

    private fun leaseRepository(): LeaseStore = repositoryProvider.resourceLeaseRepository()

    private fun workItemRepository(): WorkItemRepository = repositoryProvider.workItemRepository()

    private suspend fun createHolder(title: String = "Holder"): UUID {
        val result = workItemRepository().create(WorkItem(title = title))
        assertNotNull(result)
        return result.id
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
            assertNotNull(afterLease)
            assertEquals("agent-x", afterLease.claimedBy, "The claim must survive lease acquisition")
            assertNotNull(afterLease.claimedAt)
            assertNotNull(afterLease.claimExpiresAt)
            assertNotNull(afterLease.originalClaimedAt)
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
            assertNotNull(afterRelease)
            assertNull(afterRelease.claimedBy, "Claim must be released")

            val activeLeases = leaseRepository().findActiveForItem(holder)
            assertEquals(1, activeLeases.size, "Releasing the claim must not release the item's resource leases")
        }
}
