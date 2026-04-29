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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
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
    fun `sequential claim attempt by second agent on same item returns already_claimed`(): Unit =
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

    /**
     * TEST-C2: Verifies the atomic SQL claim guarantee under genuine two-thread contention.
     *
     * Two real threads race to call `repository.claim()` on the same unclaimed item at the same
     * instant. SQLite serializes the transactions via its write-lock mechanism. The canonical SQL
     * pattern (UPDATE WHERE claimed_by IS NULL OR expired OR same agent) ensures exactly one thread
     * writes the claim row — the other reads back `claimedBy != itsAgentId` and returns AlreadyClaimed.
     *
     * A `busy_timeout` PRAGMA of 5 000 ms is set before the race to ensure the losing thread
     * waits for the lock rather than failing immediately with SQLITE_BUSY.
     */
    @Test
    fun `concurrent claim race with two real threads — only one wins`(): Unit =
        runBlocking {
            // Set busy_timeout on the shared-cache database so the losing thread waits rather
            // than immediately returning SQLITE_BUSY when the winning thread holds the write lock.
            org.jetbrains.exposed.v1.jdbc.transactions.transaction(db = database) {
                exec("PRAGMA busy_timeout = 5000")
            }

            val item = createItem()
            val executor = Executors.newFixedThreadPool(2)

            // Latch ensures both threads start the claim call at the same instant.
            val startGate = CountDownLatch(1)
            val result1 = AtomicReference<ClaimResult>()
            val result2 = AtomicReference<ClaimResult>()

            val future1 =
                executor.submit {
                    startGate.await()
                    val r = runBlocking { repository.claim(item.id, "agent-thread-1", 900) }
                    result1.set(r)
                }
            val future2 =
                executor.submit {
                    startGate.await()
                    val r = runBlocking { repository.claim(item.id, "agent-thread-2", 900) }
                    result2.set(r)
                }

            // Release both threads simultaneously.
            startGate.countDown()

            // Wait up to 15 seconds for both to finish (includes SQLite serialization + busy_timeout).
            future1.get(15, TimeUnit.SECONDS)
            future2.get(15, TimeUnit.SECONDS)
            executor.shutdown()

            val r1 = result1.get()
            val r2 = result2.get()

            assertNotNull(r1, "Thread 1 must produce a result")
            assertNotNull(r2, "Thread 2 must produce a result")

            val successes = listOf(r1, r2).filterIsInstance<ClaimResult.Success>()
            val rejections = listOf(r1, r2).filterIsInstance<ClaimResult.AlreadyClaimed>()

            assertEquals(1, successes.size, "Exactly one thread must win the claim race; got: r1=$r1, r2=$r2")
            assertEquals(1, rejections.size, "Exactly one thread must lose the claim race; got: r1=$r1, r2=$r2")

            // Verify the winning claim fields are stable (no corruption).
            val winner = successes[0].item
            assertEquals(item.id, winner.id, "Winner's item ID must match the contested item")
            assertNotNull(winner.claimedBy, "Winner must have claimedBy set")
            assertNotNull(winner.claimedAt, "Winner must have claimedAt set")
            assertNotNull(winner.claimExpiresAt, "Winner must have claimExpiresAt set")
            assertNotNull(winner.originalClaimedAt, "Winner must have originalClaimedAt set")
            assertTrue(
                winner.claimedBy == "agent-thread-1" || winner.claimedBy == "agent-thread-2",
                "Winner must be one of the two competing agents, but was: ${winner.claimedBy}"
            )

            // Verify the loser's contendedItemId matches the contested item.
            assertEquals(item.id, rejections[0].itemId, "Loser's contendedItemId must match the contested item")
        }

    /**
     * NICE-N5: Verifies that concurrent release operations on the same item do not corrupt data.
     *
     * Two real threads both call `repository.release(sameItemId, agentId)` concurrently.
     * The release implementation uses a `newSuspendedTransaction` with a read-then-update pattern.
     *
     * Safety property: regardless of which thread wins the race (or whether both encounter lock
     * contention), the final DB state must be consistent — claim fields are either all null (released)
     * or all non-null (still claimed). There must never be a partially-written row where some
     * claim fields are null and others are not.
     *
     * Possible outcomes per thread:
     *   - ReleaseResult.Success         — this thread released the item
     *   - ReleaseResult.NotClaimedByYou — item was already released by the other thread
     *   - ReleaseResult.NotFound        — lock contention caught by repository error handler
     *   - Exception                     — lock-contention propagated through coroutine boundary
     *
     * Under shared-cache SQLite, `SQLITE_LOCKED_SHAREDCACHE` is not handled by `busy_timeout`
     * (which only covers file-level `SQLITE_BUSY`). Both threads may fail — that is a documented
     * limitation of the in-memory shared-cache fixture, not a production concern (production uses
     * WAL-mode file-backed SQLite with `busy_timeout = 5000`).
     */
    @Test
    fun `concurrent release operations on same item do not corrupt claim fields`(): Unit =
        runBlocking {
            val item = createItem()
            // Establish a claim that both threads will try to release.
            assertIs<ClaimResult.Success>(repository.claim(item.id, "agent-releaser", 900))

            val executor = Executors.newFixedThreadPool(2)
            val startGate = CountDownLatch(1)
            val result1 = AtomicReference<Any?>() // ReleaseResult or Exception
            val result2 = AtomicReference<Any?>()

            val future1 =
                executor.submit {
                    startGate.await()
                    try {
                        val r = runBlocking { repository.release(item.id, "agent-releaser") }
                        result1.set(r)
                    } catch (e: Exception) {
                        result1.set(e)
                    }
                }
            val future2 =
                executor.submit {
                    startGate.await()
                    try {
                        val r = runBlocking { repository.release(item.id, "agent-releaser") }
                        result2.set(r)
                    } catch (e: Exception) {
                        result2.set(e)
                    }
                }

            startGate.countDown()
            future1.get(15, TimeUnit.SECONDS)
            future2.get(15, TimeUnit.SECONDS)
            executor.shutdown()

            val r1 = result1.get()
            val r2 = result2.get()

            // Each thread must produce a non-null outcome — null means the future never ran.
            assertNotNull(r1, "Thread 1 must produce a result (non-null)")
            assertNotNull(r2, "Thread 2 must produce a result (non-null)")

            // THE CRITICAL SAFETY PROPERTY: regardless of lock-contention outcomes,
            // the final DB row must NOT be partially written. The claim fields are an atomic
            // set — all four are written (or cleared) in a single SQL UPDATE. If there is
            // ever a row where some fields are null and others are not, that is data corruption.
            val finalResult = repository.getById(item.id)
            assertIs<Result.Success<WorkItem>>(finalResult)
            val finalItem = finalResult.data

            val nullCount =
                listOf(finalItem.claimedBy, finalItem.claimedAt, finalItem.claimExpiresAt, finalItem.originalClaimedAt).count {
                    it ==
                        null
                }
            assertTrue(
                nullCount == 0 || nullCount == 4,
                "Claim fields must be atomically consistent: all null (released) or all non-null (still claimed). " +
                    "Partial state detected — claimedBy=${finalItem.claimedBy}, " +
                    "claimedAt=${finalItem.claimedAt}, " +
                    "claimExpiresAt=${finalItem.claimExpiresAt}, " +
                    "originalClaimedAt=${finalItem.originalClaimedAt}"
            )
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
