package io.github.jpicklyk.mcptask.current.infrastructure.database.repository

import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository
import io.github.jpicklyk.mcptask.current.test.BaseRepositoryTest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tests that the four claim fields (claimedBy, claimedAt, claimExpiresAt, originalClaimedAt)
 * round-trip correctly through create and update operations, and default to null on new items.
 */
class SQLiteWorkItemClaimFieldsTest : BaseRepositoryTest() {
    private lateinit var repository: WorkItemRepository

    @BeforeEach
    fun setUp() {
        repository = repositoryProvider.workItemRepository()
    }

    @Test
    fun `new WorkItem defaults all claim fields to null`() {
        val item = WorkItem(title = "Unclaimed item")
        assertNull(item.claimedBy)
        assertNull(item.claimedAt)
        assertNull(item.claimExpiresAt)
        assertNull(item.originalClaimedAt)
    }

    @Test
    fun `create persists null claim fields and getById returns nulls`() =
        runBlocking {
            val item = WorkItem(title = "Unclaimed persisted item")
            repository.create(item)

            val result = repository.getById(item.id)
            assertIs<Result.Success<WorkItem>>(result)
            val retrieved = result.data
            assertNull(retrieved.claimedBy)
            assertNull(retrieved.claimedAt)
            assertNull(retrieved.claimExpiresAt)
            assertNull(retrieved.originalClaimedAt)
        }

    @Test
    fun `create persists all claim fields and getById retrieves them`() =
        runBlocking {
            val now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
            val expiresAt = now.plusSeconds(900)
            val item =
                WorkItem(
                    title = "Claimed item",
                    claimedBy = "agent-abc-123",
                    claimedAt = now,
                    claimExpiresAt = expiresAt,
                    originalClaimedAt = now,
                )
            repository.create(item)

            val result = repository.getById(item.id)
            assertIs<Result.Success<WorkItem>>(result)
            val retrieved = result.data
            assertEquals("agent-abc-123", retrieved.claimedBy)
            // Timestamps must round-trip with exact epoch-millisecond precision (not just non-null).
            // The !! operator will throw NullPointerException if the field is null, surfacing
            // a storage bug as clearly as assertNotNull would. Epoch-millis equality is stronger:
            // it pins the exact value, catching both null regressions and precision loss.
            assertEquals(now.toEpochMilli(), retrieved.claimedAt!!.toEpochMilli())
            assertEquals(expiresAt.toEpochMilli(), retrieved.claimExpiresAt!!.toEpochMilli())
            assertEquals(now.toEpochMilli(), retrieved.originalClaimedAt!!.toEpochMilli())
        }

    @Test
    fun `update can set claim fields on a previously unclaimed item`() =
        runBlocking {
            val item = WorkItem(title = "Will be claimed")
            repository.create(item)

            val now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
            val claimed =
                item.copy(
                    claimedBy = "agent-xyz",
                    claimedAt = now,
                    claimExpiresAt = now.plusSeconds(600),
                    originalClaimedAt = now,
                )
            val updateResult = repository.update(claimed)
            assertIs<Result.Success<WorkItem>>(updateResult)

            val result = repository.getById(item.id)
            assertIs<Result.Success<WorkItem>>(result)
            val retrieved = result.data
            assertEquals("agent-xyz", retrieved.claimedBy)
            assertNotNull(retrieved.claimedAt)
            assertNotNull(retrieved.claimExpiresAt)
            assertNotNull(retrieved.originalClaimedAt)
        }

    @Test
    fun `update can clear claim fields (release a claim)`() =
        runBlocking {
            val now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
            val item =
                WorkItem(
                    title = "Release test item",
                    claimedBy = "agent-release",
                    claimedAt = now,
                    claimExpiresAt = now.plusSeconds(900),
                    originalClaimedAt = now,
                )
            repository.create(item)

            // Release by setting all claim fields to null
            val released =
                item.copy(
                    claimedBy = null,
                    claimedAt = null,
                    claimExpiresAt = null,
                    originalClaimedAt = null,
                )
            val updateResult = repository.update(released)
            assertIs<Result.Success<WorkItem>>(updateResult)

            val result = repository.getById(item.id)
            assertIs<Result.Success<WorkItem>>(result)
            val retrieved = result.data
            assertNull(retrieved.claimedBy)
            assertNull(retrieved.claimedAt)
            assertNull(retrieved.claimExpiresAt)
            assertNull(retrieved.originalClaimedAt)
        }

    @Test
    fun `update preserves originalClaimedAt across TTL refresh (re-claim)`() =
        runBlocking {
            val firstClaimTime = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
            val item =
                WorkItem(
                    title = "Re-claim test item",
                    claimedBy = "agent-reclaim",
                    claimedAt = firstClaimTime,
                    claimExpiresAt = firstClaimTime.plusSeconds(900),
                    originalClaimedAt = firstClaimTime,
                )
            repository.create(item)

            // Simulate re-claim (extend TTL): claimedAt and claimExpiresAt refresh, originalClaimedAt stays
            val refreshTime = firstClaimTime.plusSeconds(450)
            val reClaimed =
                item.copy(
                    claimedAt = refreshTime,
                    claimExpiresAt = refreshTime.plusSeconds(900),
                    originalClaimedAt = firstClaimTime, // preserved
                )
            val updateResult = repository.update(reClaimed)
            assertIs<Result.Success<WorkItem>>(updateResult)

            val result = repository.getById(item.id)
            assertIs<Result.Success<WorkItem>>(result)
            val retrieved = result.data
            assertEquals("agent-reclaim", retrieved.claimedBy)
            assertEquals(refreshTime.toEpochMilli(), retrieved.claimedAt!!.toEpochMilli())
            // originalClaimedAt preserved from first claim
            assertEquals(firstClaimTime.toEpochMilli(), retrieved.originalClaimedAt!!.toEpochMilli())
        }

    @Test
    fun `update resets originalClaimedAt when different agent takes over`() =
        runBlocking {
            val firstClaimTime = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
            val item =
                WorkItem(
                    title = "Agent takeover test",
                    claimedBy = "agent-first",
                    claimedAt = firstClaimTime,
                    claimExpiresAt = firstClaimTime.plusSeconds(900),
                    originalClaimedAt = firstClaimTime,
                )
            repository.create(item)

            // Different agent takes over: all claim fields reset to new agent's times
            val newClaimTime = firstClaimTime.plusSeconds(1000)
            val takenOver =
                item.copy(
                    claimedBy = "agent-second",
                    claimedAt = newClaimTime,
                    claimExpiresAt = newClaimTime.plusSeconds(900),
                    originalClaimedAt = newClaimTime, // reset for new agent
                )
            val updateResult = repository.update(takenOver)
            assertIs<Result.Success<WorkItem>>(updateResult)

            val result = repository.getById(item.id)
            assertIs<Result.Success<WorkItem>>(result)
            val retrieved = result.data
            assertEquals("agent-second", retrieved.claimedBy)
            assertEquals(newClaimTime.toEpochMilli(), retrieved.originalClaimedAt!!.toEpochMilli())
        }
}
