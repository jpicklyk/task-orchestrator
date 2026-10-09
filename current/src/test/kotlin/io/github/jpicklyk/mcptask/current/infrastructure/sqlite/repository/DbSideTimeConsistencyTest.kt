package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository

import io.github.jpicklyk.mcptask.current.application.port.ClaimResult
import io.github.jpicklyk.mcptask.current.application.port.ReadScope
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.port.WorkItemRepository
import io.github.jpicklyk.mcptask.current.application.service.LeaseInput
import io.github.jpicklyk.mcptask.current.application.service.OwnershipInput
import io.github.jpicklyk.mcptask.current.application.service.TransitionSnapshotLoader
import io.github.jpicklyk.mcptask.current.domain.lifecycle.Decision
import io.github.jpicklyk.mcptask.current.domain.lifecycle.GateId
import io.github.jpicklyk.mcptask.current.domain.lifecycle.TransitionPolicy
import io.github.jpicklyk.mcptask.current.domain.lifecycle.Trigger
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.test.SettableClock
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * H3: claim-freshness consistency - every claim decision is made against the ONE bound clock instant, never a
 * database clock or a per-call JVM read.
 *
 * The repositories are opened over a [SettableClock], so expiry is tested by moving the clock (no sleeping):
 *
 * 1. `findForNextItem`: a claimed item is excluded while the claim is active and returns once the clock passes
 *    the expiry; an expiry equal to now is already expired.
 * 2. The ownership gate decides claim freshness at the snapshot's `now` (the unit instant), not `Instant.now()`.
 * 3. `countByClaimStatus`: active/expired counts follow the clock.
 * 4. `retryAfterMs` is the time left on the bound clock, at least 1.
 */
class DbSideTimeConsistencyTest {
    private val clock = SettableClock()

    @RegisterExtension
    @JvmField
    val sqliteDb = SqliteTestDatabase.perMethod(clock = clock)

    private val database get() = sqliteDb.database
    private val repositoryProvider get() = sqliteDb.repositoryProvider()

    private lateinit var repository: WorkItemRepository

    @BeforeEach
    fun setUp() {
        repository = repositoryProvider.workItemRepository()
    }

    private suspend fun createItem(
        title: String = "Test Item",
        role: Role = Role.QUEUE
    ): WorkItem {
        val result = repository.create(WorkItem(title = title, role = role))
        assertNotNull(result)
        return result
    }

    // -----------------------------------------------------------------------
    // 1. findForNextItem follows the bound clock
    // -----------------------------------------------------------------------

    /**
     * Claim an item with a 2-second TTL, move the clock past the expiry, then verify findForNextItem
     * (excludeActiveClaims=true) includes the item again. At exactly the expiry instant the claim is already
     * expired (an expiry equal to now is not active).
     */
    @Test
    fun `findForNextItem includes item once the bound clock passes the claim expiry`(): Unit =
        runBlocking {
            val item = createItem("Claim-expiry item")

            val claimResult = repository.claim(item.id, "agent-ttl-test", ttlSeconds = 2)
            assertIs<ClaimResult.Success>(claimResult)

            // Immediately after claiming, the item should be excluded from next-item results.
            val beforeIds = repository.findForNextItem(Role.QUEUE, excludeActiveClaims = true).map { it.id }.toSet()
            assertTrue(item.id !in beforeIds, "Item should be excluded from findForNextItem while claim is active")

            // One millisecond before the expiry: still active.
            clock.advance(java.time.Duration.ofMillis(1999))
            val almostIds = repository.findForNextItem(Role.QUEUE, excludeActiveClaims = true).map { it.id }.toSet()
            assertTrue(item.id !in almostIds, "Item should still be excluded 1 ms before the expiry")

            // At exactly the expiry instant: expired.
            clock.advance(java.time.Duration.ofMillis(1))
            val afterIds = repository.findForNextItem(Role.QUEUE, excludeActiveClaims = true).map { it.id }.toSet()
            assertTrue(item.id in afterIds, "Item should reappear in findForNextItem at the expiry instant")
        }

    // -----------------------------------------------------------------------
    // 2. The ownership gate respects the snapshot's `now` (the unit instant)
    // -----------------------------------------------------------------------

    /** Evaluates `start` on [item] with ownership enforced and no caller identity, as of [now]. */
    private suspend fun ownershipDecision(
        item: WorkItem,
        now: Instant
    ): Decision {
        val scope =
            object : ReadScope {
                override val stores: RepositoryProvider get() = repositoryProvider
                override val now: Instant = now
            }
        val loader =
            TransitionSnapshotLoader(
                repository,
                repositoryProvider.dependencyRepository(),
                repositoryProvider.noteRepository(),
                null,
                schemaResolver = { null }
            )
        val snapshot = loader.load(scope, item, Trigger.User.START, OwnershipInput(enforced = true, callerId = null), LeaseInput.OFF)
        return TransitionPolicy().evaluate(snapshot, Trigger.User.START)
    }

    /**
     * Scenario: an item has an active claim (expiresAt = now + 10 minutes).
     * - At real time: the claim is active -> the ownership gate rejects (no caller identity).
     * - At a "now" 5 minutes past the expiry: the claim is expired -> allowed.
     *
     * This proves the claim freshness decision uses the snapshot's `now`, not `Instant.now()`.
     */
    @Test
    fun `ownership gate uses the snapshot now for freshness — JVM-ahead skew treats active claim as expired`(): Unit =
        runBlocking {
            val realNow = Instant.now()
            val claimExpiresAt = realNow.plusSeconds(600) // expires in 10 minutes

            val claimedItem =
                WorkItem(
                    title = "Skew test item",
                    role = Role.QUEUE,
                    claimedBy = "holder-agent",
                    claimedAt = realNow.minusSeconds(60),
                    claimExpiresAt = claimExpiresAt,
                    originalClaimedAt = realNow.minusSeconds(60)
                )

            val atRealNow = ownershipDecision(claimedItem, realNow)
            assertEquals(GateId.OWNERSHIP, assertIs<Decision.Reject>(atRealNow).gate, "an active claim rejects a caller with no identity")

            val atAheadNow = ownershipDecision(claimedItem, claimExpiresAt.plusSeconds(300))
            assertIs<Decision.Allow>(atAheadNow, "past the expiry the claim is treated as expired")
        }

    @Test
    fun `ownership gate uses the snapshot now — JVM-behind skew treats expired claim as active`(): Unit =
        runBlocking {
            val realNow = Instant.now()
            // Claim expired 5 minutes ago from the DB's perspective.
            val claimExpiresAt = realNow.minusSeconds(300)

            val claimedItem =
                WorkItem(
                    title = "Behind-skew test item",
                    role = Role.QUEUE,
                    claimedBy = "stale-agent",
                    claimedAt = realNow.minusSeconds(900),
                    claimExpiresAt = claimExpiresAt,
                    originalClaimedAt = realNow.minusSeconds(900)
                )

            val atRealNow = ownershipDecision(claimedItem, realNow)
            assertIs<Decision.Allow>(atRealNow, "an expired claim is treated as unclaimed")

            val atBehindNow = ownershipDecision(claimedItem, claimExpiresAt.minusSeconds(600))
            assertEquals(GateId.OWNERSHIP, assertIs<Decision.Reject>(atBehindNow).gate, "before the expiry the claim is active")
        }

    // -----------------------------------------------------------------------
    // 3. countByClaimStatus follows the bound clock
    // -----------------------------------------------------------------------

    @Test
    fun `countByClaimStatus follows the bound clock after the claim TTL elapses`(): Unit =
        runBlocking {
            val item = createItem("ClaimStatus count item")
            repository.claim(item.id, "agent-count-test", ttlSeconds = 2)

            // Immediately: active=1, expired=0.
            val beforeResult = repository.countByClaimStatus()
            assertEquals(1, beforeResult.active, "exactly 1 active claim right after claiming")
            assertEquals(0, beforeResult.expired)

            clock.advanceSeconds(3)

            val afterResult = repository.countByClaimStatus()
            assertEquals(0, afterResult.active, "Active claim count should be 0 once the clock passes the TTL")
            assertEquals(1, afterResult.expired, "Expired claim count should be 1 once the clock passes the TTL")
        }

    // -----------------------------------------------------------------------
    // 4. retryAfterMs uses the bound clock
    // -----------------------------------------------------------------------

    @Test
    fun `AlreadyClaimed retryAfterMs is positive and reflects the remaining TTL on the bound clock`(): Unit =
        runBlocking {
            val item = createItem("RetryAfter item")
            val ttlSeconds = 30

            // Agent A claims the item.
            repository.claim(item.id, "agent-a-retry", ttlSeconds = ttlSeconds)
            clock.advanceSeconds(10)

            // Agent B tries to claim — should get AlreadyClaimed with positive retryAfterMs.
            val result = repository.claim(item.id, "agent-b-retry", ttlSeconds = 60)

            assertIs<ClaimResult.AlreadyClaimed>(result)
            val retryMs = result.retryAfterMs
            assertNotNull(retryMs, "retryAfterMs should not be null when another agent holds the claim")
            assertTrue(retryMs > 0, "retryAfterMs should be positive (remaining TTL from the bound clock)")
            // 10 of the 30 seconds have elapsed on the bound clock, so exactly 20 000 ms remain.
            assertEquals(20_000L, retryMs, "retryAfterMs must be the exact time left on the bound clock")
        }
}
