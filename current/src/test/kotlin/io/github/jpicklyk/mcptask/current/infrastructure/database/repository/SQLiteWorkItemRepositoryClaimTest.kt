package io.github.jpicklyk.mcptask.current.infrastructure.database.repository

import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.ClaimResult
import io.github.jpicklyk.mcptask.current.domain.repository.ReleaseResult
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository
import io.github.jpicklyk.mcptask.current.test.SQLiteRepositoryTestBase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration tests for the claim/release operations on [WorkItemRepository].
 *
 * Uses a real SQLite in-memory database (via [SQLiteRepositoryTestBase]) to verify the
 * canonical SQL claim pattern, auto-release, re-claim, expiry filtering, and release
 * semantics. SQLite is required because the claim SQL uses SQLite-specific
 * `datetime('now', '+N seconds')` syntax that H2 does not support.
 */
class SQLiteWorkItemRepositoryClaimTest : SQLiteRepositoryTestBase() {
    private lateinit var repository: WorkItemRepository

    @BeforeEach
    fun setUp() {
        repository = repositoryProvider.workItemRepository()
    }

    private suspend fun createItem(
        title: String = "Test Item",
        role: Role = Role.QUEUE
    ): WorkItem {
        val item = WorkItem(title = title, role = role)
        val result = repository.create(item)
        assertIs<Result.Success<WorkItem>>(result)
        return result.data
    }

    // -----------------------------------------------------------------------
    // Claim — success cases
    // -----------------------------------------------------------------------

    @Test
    fun `single claim succeeds and sets all four claim fields`(): Unit =
        runBlocking {
            val item = createItem()

            val result = repository.claim(item.id, "agent-a", 900)

            assertIs<ClaimResult.Success>(result)
            val claimed = result.item
            assertEquals("agent-a", claimed.claimedBy)
            assertNotNull(claimed.claimedAt)
            assertNotNull(claimed.claimExpiresAt)
            assertNotNull(claimed.originalClaimedAt)
            // claimedAt <= claimExpiresAt
            assertTrue(claimed.claimedAt!! <= claimed.claimExpiresAt!!)
            // originalClaimedAt == claimedAt on first claim
            assertEquals(
                claimed.claimedAt!!.toEpochMilli() / 1000L,
                claimed.originalClaimedAt!!.toEpochMilli() / 1000L,
                "originalClaimedAt should equal claimedAt on first claim (within 1 second)"
            )
        }

    @Test
    fun `re-claim by same agent refreshes TTL but preserves originalClaimedAt`(): Unit =
        runBlocking {
            val item = createItem()

            // First claim
            val first = repository.claim(item.id, "agent-b", 900)
            assertIs<ClaimResult.Success>(first)
            val firstOriginalClaimedAt = first.item.originalClaimedAt!!

            // Small delay to ensure DB-side datetime('now') advances
            Thread.sleep(1100)

            // Re-claim (extend TTL)
            val second = repository.claim(item.id, "agent-b", 1800)
            assertIs<ClaimResult.Success>(second)

            // originalClaimedAt must be preserved from first claim
            assertEquals(
                firstOriginalClaimedAt.toEpochMilli() / 1000L,
                second.item.originalClaimedAt!!.toEpochMilli() / 1000L,
                "originalClaimedAt must be preserved on re-claim by same agent (within 1 second)"
            )
            // claimExpiresAt should be extended
            assertTrue(
                second.item.claimExpiresAt!! > first.item.claimExpiresAt!!,
                "re-claim should extend claimExpiresAt"
            )
        }

    @Test
    fun `claiming item B auto-releases prior claim on item A by same agent`(): Unit =
        runBlocking {
            val itemA = createItem("Item A")
            val itemB = createItem("Item B")

            // Agent claims A
            assertIs<ClaimResult.Success>(repository.claim(itemA.id, "agent-c", 900))

            // Agent claims B — auto-releases A
            assertIs<ClaimResult.Success>(repository.claim(itemB.id, "agent-c", 900))

            // Item A should be unclaimed now
            val aResult = repository.getById(itemA.id)
            assertIs<Result.Success<WorkItem>>(aResult)
            assertNull(aResult.data.claimedBy, "Item A should be auto-released when agent claims Item B")

            // Item B should be claimed
            val bResult = repository.getById(itemB.id)
            assertIs<Result.Success<WorkItem>>(bResult)
            assertEquals("agent-c", bResult.data.claimedBy)
        }

    // -----------------------------------------------------------------------
    // Claim — contention cases
    // -----------------------------------------------------------------------

    @Test
    fun `concurrent claim by second agent on same item returns already_claimed`(): Unit =
        runBlocking {
            val item = createItem()

            // Agent 1 claims first
            assertIs<ClaimResult.Success>(repository.claim(item.id, "agent-1", 900))

            // Agent 2 tries to claim the same item
            val result = repository.claim(item.id, "agent-2", 900)
            assertIs<ClaimResult.AlreadyClaimed>(result)
            assertEquals(item.id, result.itemId)
            // retryAfterMs should be positive (claim is not expired)
            assertNotNull(result.retryAfterMs)
            assertTrue(result.retryAfterMs!! > 0, "retryAfterMs should be positive for a live claim")
        }

    @Test
    fun `expired claim is treated as absent and next claimer wins`(): Unit =
        runBlocking {
            // Create item with a TTL of 1 second
            val item = createItem()
            assertIs<ClaimResult.Success>(repository.claim(item.id, "agent-old", 1))

            // Wait for the claim to expire
            Thread.sleep(2000)

            // New agent should be able to claim it
            val result = repository.claim(item.id, "agent-new", 900)
            assertIs<ClaimResult.Success>(result)
            assertEquals("agent-new", result.item.claimedBy)
            // originalClaimedAt should be reset for the new agent
            assertNotNull(result.item.originalClaimedAt)
        }

    @Test
    fun `claim on terminal item returns terminal_item`(): Unit =
        runBlocking {
            val item = WorkItem(title = "Terminal item", role = Role.TERMINAL)
            val createResult = repository.create(item)
            assertIs<Result.Success<WorkItem>>(createResult)

            val result = repository.claim(item.id, "agent-x", 900)
            assertIs<ClaimResult.TerminalItem>(result)
            assertEquals(item.id, result.itemId)
        }

    @Test
    fun `claim on non-existent item returns not_found`(): Unit =
        runBlocking {
            val fakeId = UUID.randomUUID()
            val result = repository.claim(fakeId, "agent-x", 900)
            assertIs<ClaimResult.NotFound>(result)
            assertEquals(fakeId, result.itemId)
        }

    // -----------------------------------------------------------------------
    // Claim — role coverage
    // -----------------------------------------------------------------------

    @Test
    fun `claim succeeds on WORK-role item`(): Unit =
        runBlocking {
            val item = createItem(role = Role.WORK)
            val result = repository.claim(item.id, "agent-work", 900)
            assertIs<ClaimResult.Success>(result)
        }

    @Test
    fun `claim succeeds on REVIEW-role item`(): Unit =
        runBlocking {
            val item = createItem(role = Role.REVIEW)
            val result = repository.claim(item.id, "agent-review", 900)
            assertIs<ClaimResult.Success>(result)
        }

    @Test
    fun `claim succeeds on BLOCKED-role item`(): Unit =
        runBlocking {
            val item = createItem(role = Role.BLOCKED)
            val result = repository.claim(item.id, "agent-blocked", 900)
            assertIs<ClaimResult.Success>(result)
        }

    // -----------------------------------------------------------------------
    // Release — success cases
    // -----------------------------------------------------------------------

    @Test
    fun `release by current claimer clears all four claim fields`(): Unit =
        runBlocking {
            val item = createItem()
            assertIs<ClaimResult.Success>(repository.claim(item.id, "agent-release", 900))

            val result = repository.release(item.id, "agent-release")
            assertIs<ReleaseResult.Success>(result)

            val retrieved = result.item
            assertNull(retrieved.claimedBy)
            assertNull(retrieved.claimedAt)
            assertNull(retrieved.claimExpiresAt)
            assertNull(retrieved.originalClaimedAt)
        }

    @Test
    fun `release by non-claimer returns not_claimed_by_you`(): Unit =
        runBlocking {
            val item = createItem()
            assertIs<ClaimResult.Success>(repository.claim(item.id, "agent-holder", 900))

            val result = repository.release(item.id, "agent-other")
            assertIs<ReleaseResult.NotClaimedByYou>(result)
            assertEquals(item.id, result.itemId)
        }

    @Test
    fun `release on unclaimed item returns not_claimed_by_you`(): Unit =
        runBlocking {
            val item = createItem()

            val result = repository.release(item.id, "agent-nobody")
            assertIs<ReleaseResult.NotClaimedByYou>(result)
        }

    @Test
    fun `release on non-existent item returns not_found`(): Unit =
        runBlocking {
            val fakeId = UUID.randomUUID()
            val result = repository.release(fakeId, "agent-x")
            assertIs<ReleaseResult.NotFound>(result)
            assertEquals(fakeId, result.itemId)
        }
}
