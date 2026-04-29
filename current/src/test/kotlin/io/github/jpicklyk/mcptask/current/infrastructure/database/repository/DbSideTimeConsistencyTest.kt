package io.github.jpicklyk.mcptask.current.infrastructure.database.repository

import io.github.jpicklyk.mcptask.current.application.service.RoleTransitionHandler
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.ClaimResult
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository
import io.github.jpicklyk.mcptask.current.infrastructure.repository.SQLiteWorkItemRepository
import io.github.jpicklyk.mcptask.current.test.SQLiteRepositoryTestBase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * H3: DB-side time consistency — verifies that claim-freshness decisions are evaluated against
 * the database clock, not the JVM clock.
 *
 * ### Test strategy
 *
 * True clock-skew simulation (mocking `Instant.now()`) is impractical in Kotlin without
 * a `Clock` abstraction. Instead, we use three complementary approaches:
 *
 * 1. **`dbNow()` sanity check** — verify the new method returns an Instant within 5 seconds of
 *    JVM time (regression guard: it's not returning epoch zero or some wildly wrong value).
 *
 * 2. **Proof-by-construction for `findForNextItem`** — demonstrate that claim-freshness
 *    filtering is now delegated to the DB by verifying that an item with an already-expired
 *    claim (claimExpiresAt set via a very short TTL) becomes visible in `findForNextItem`
 *    results once the TTL lapses — confirming the DB-side `claim_expires_at <= datetime('now')`
 *    predicate drives inclusion, not JVM-side filtering.
 *
 * 3. **`checkOwnershipForTransition` clock injection** — verify that the function accepts
 *    an explicit `now` parameter, and that passing a "future" now (simulating JVM-ahead skew)
 *    causes an active claim to be seen as expired (i.e., the DB-time parameter wins).
 *
 * 4. **`countByClaimStatus` regression** — after expiry, expired counts reflect DB-side time.
 */
class DbSideTimeConsistencyTest : SQLiteRepositoryTestBase() {

    private lateinit var repository: WorkItemRepository
    private val handler = RoleTransitionHandler()

    @BeforeEach
    fun setUp() {
        repository = repositoryProvider.workItemRepository()
    }

    private suspend fun createItem(title: String = "Test Item", role: Role = Role.QUEUE): WorkItem {
        val result = repository.create(WorkItem(title = title, role = role))
        assertIs<Result.Success<WorkItem>>(result)
        return result.data
    }

    // -----------------------------------------------------------------------
    // 1. dbNow() sanity check
    // -----------------------------------------------------------------------

    @Test
    fun `dbNow returns an Instant within 5 seconds of JVM now`(): Unit =
        runBlocking {
            val jvmBefore = Instant.now()
            val dbNow = repository.dbNow()
            val jvmAfter = Instant.now()

            // DB time should be between jvmBefore-5s and jvmAfter+5s to allow for clock drift.
            assertTrue(
                dbNow.isAfter(jvmBefore.minusSeconds(5)),
                "dbNow ($dbNow) should be no more than 5 seconds before JVM time ($jvmBefore)"
            )
            assertTrue(
                dbNow.isBefore(jvmAfter.plusSeconds(5)),
                "dbNow ($dbNow) should be no more than 5 seconds after JVM time ($jvmAfter)"
            )
        }

    @Test
    fun `dbNow is called multiple times without error and returns monotonically non-decreasing values`(): Unit =
        runBlocking {
            val t1 = repository.dbNow()
            Thread.sleep(100)
            val t2 = repository.dbNow()

            // SQLite datetime resolution is 1-second; allow t2 == t1 but not t2 < t1.
            assertTrue(
                !t2.isBefore(t1),
                "Second dbNow ($t2) should not be before first dbNow ($t1)"
            )
        }

    // -----------------------------------------------------------------------
    // 2. findForNextItem uses DB-side freshness
    // -----------------------------------------------------------------------

    /**
     * Claim an item with a 1-second TTL, wait for it to expire (2s), then verify that
     * findForNextItem (excludeActiveClaims=true) includes the item — proving the freshness
     * check uses DB-side `claim_expires_at <= datetime('now')` rather than a stale JVM snapshot.
     */
    @Test
    fun `findForNextItem includes item after DB-side claim expiry`(): Unit =
        runBlocking {
            val item = createItem("Claim-expiry item")

            // Claim with a 1-second TTL so the claim expires almost immediately.
            val claimResult = repository.claim(item.id, "agent-ttl-test", ttlSeconds = 1)
            assertIs<ClaimResult.Success>(claimResult)

            // Immediately after claiming, the item should be excluded from next-item results.
            val beforeExpiry = repository.findForNextItem(Role.QUEUE, excludeActiveClaims = true)
            assertIs<Result.Success<List<WorkItem>>>(beforeExpiry)
            val beforeIds = beforeExpiry.data.map { it.id }.toSet()
            assertTrue(
                item.id !in beforeIds,
                "Item should be excluded from findForNextItem while claim is active"
            )

            // Wait 2 seconds for the claim to expire in the DB.
            Thread.sleep(2100)

            // After expiry, the item should reappear (DB-side comparison now sees it as expired).
            val afterExpiry = repository.findForNextItem(Role.QUEUE, excludeActiveClaims = true)
            assertIs<Result.Success<List<WorkItem>>>(afterExpiry)
            val afterIds = afterExpiry.data.map { it.id }.toSet()
            assertTrue(
                item.id in afterIds,
                "Item should reappear in findForNextItem once DB-side claim has expired"
            )
        }

    // -----------------------------------------------------------------------
    // 3. checkOwnershipForTransition respects injected `now` parameter
    // -----------------------------------------------------------------------

    /**
     * Demonstrates the clock-injection surface of checkOwnershipForTransition.
     *
     * Scenario: an item has an active claim (expiresAt = now + 10 minutes).
     * - When `now` is JVM-real-time: hasActiveClaim = true → Rejected (no actor credentials).
     * - When `now` is simulated "5 minutes ahead" (i.e., skewed so expiry is in the past):
     *   hasActiveClaim = false → Allowed (expired claim is treated as unclaimed).
     *
     * This proves that the function uses the injected `now`, not `Instant.now()` internally.
     */
    @Test
    fun `checkOwnershipForTransition uses injected now for freshness — JVM-ahead skew treats active claim as expired`(): Unit =
        runBlocking {
            val realNow = Instant.now()
            val claimExpiresAt = realNow.plusSeconds(600) // expires in 10 minutes

            // Build an item that looks claimed from the DB's perspective.
            val claimedItem =
                WorkItem(
                    title = "Skew test item",
                    role = Role.QUEUE,
                    claimedBy = "holder-agent",
                    claimedAt = realNow.minusSeconds(60),
                    claimExpiresAt = claimExpiresAt,
                    originalClaimedAt = realNow.minusSeconds(60)
                )

            // Case A: real now — claim is active → ownership check rejects (no actor provided).
            val resultRealNow =
                handler.checkOwnershipForTransition(
                    item = claimedItem,
                    actorClaim = null,
                    verification = null,
                    degradedModePolicy = DegradedModePolicy.ACCEPT_CACHED,
                    now = realNow
                )
            assertIs<io.github.jpicklyk.mcptask.current.application.service.OwnershipCheckResult.Rejected>(
                resultRealNow,
                "With real-time now, active claim should cause rejection when no actor provided"
            )

            // Case B: simulated "JVM 15 minutes ahead of DB" — claim looks expired → allowed.
            val jvmAheadNow = claimExpiresAt.plusSeconds(300) // 5 min past expiry
            val resultAheadNow =
                handler.checkOwnershipForTransition(
                    item = claimedItem,
                    actorClaim = null,
                    verification = null,
                    degradedModePolicy = DegradedModePolicy.ACCEPT_CACHED,
                    now = jvmAheadNow
                )
            assertIs<io.github.jpicklyk.mcptask.current.application.service.OwnershipCheckResult.Allowed>(
                resultAheadNow,
                "With skewed-ahead now past expiry, claim should be treated as expired → Allowed"
            )
        }

    @Test
    fun `checkOwnershipForTransition uses injected now — JVM-behind skew treats expired claim as active`(): Unit =
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

            // Case A: real now — claim is expired → Allowed (unclaimed).
            val resultRealNow =
                handler.checkOwnershipForTransition(
                    item = claimedItem,
                    actorClaim = null,
                    verification = null,
                    degradedModePolicy = DegradedModePolicy.ACCEPT_CACHED,
                    now = realNow
                )
            assertIs<io.github.jpicklyk.mcptask.current.application.service.OwnershipCheckResult.Allowed>(
                resultRealNow,
                "With real-time now, expired claim should be treated as unclaimed → Allowed"
            )

            // Case B: simulated "JVM 10 minutes behind DB" — claim looks active → Rejected.
            val jvmBehindNow = claimExpiresAt.minusSeconds(600) // 10 min before expiry
            val resultBehindNow =
                handler.checkOwnershipForTransition(
                    item = claimedItem,
                    actorClaim = null,
                    verification = null,
                    degradedModePolicy = DegradedModePolicy.ACCEPT_CACHED,
                    now = jvmBehindNow
                )
            assertIs<io.github.jpicklyk.mcptask.current.application.service.OwnershipCheckResult.Rejected>(
                resultBehindNow,
                "With skewed-behind now before expiry, claim should appear active → Rejected"
            )
        }

    // -----------------------------------------------------------------------
    // 4. countByClaimStatus uses DB-side time
    // -----------------------------------------------------------------------

    @Test
    fun `countByClaimStatus reflects DB-side expiry after claim TTL elapses`(): Unit =
        runBlocking {
            val item = createItem("ClaimStatus count item")

            // Claim with 1-second TTL.
            repository.claim(item.id, "agent-count-test", ttlSeconds = 1)

            // Immediately: active=1, expired=0.
            val beforeResult = repository.countByClaimStatus()
            assertIs<Result.Success<io.github.jpicklyk.mcptask.current.domain.repository.ClaimStatusCounts>>(beforeResult)
            assertTrue(
                beforeResult.data.active >= 1,
                "There should be at least 1 active claim right after claiming"
            )

            // Wait for TTL to expire.
            Thread.sleep(2100)

            // After expiry: active should have decreased, expired should have increased.
            val afterResult = repository.countByClaimStatus()
            assertIs<Result.Success<io.github.jpicklyk.mcptask.current.domain.repository.ClaimStatusCounts>>(afterResult)
            assertEquals(
                0,
                afterResult.data.active,
                "Active claim count should be 0 after TTL expires (DB-side evaluation)"
            )
            assertTrue(
                afterResult.data.expired >= 1,
                "Expired claim count should be >= 1 after TTL expires (DB-side evaluation)"
            )
        }

    // -----------------------------------------------------------------------
    // 5. retryAfterMs uses DB clock
    // -----------------------------------------------------------------------

    @Test
    fun `AlreadyClaimed retryAfterMs is positive and reflects remaining DB-side TTL`(): Unit =
        runBlocking {
            val item = createItem("RetryAfter item")
            val ttlSeconds = 30

            // Agent A claims the item.
            repository.claim(item.id, "agent-a-retry", ttlSeconds = ttlSeconds)

            // Agent B tries to claim — should get AlreadyClaimed with positive retryAfterMs.
            val result = repository.claim(item.id, "agent-b-retry", ttlSeconds = 60)

            assertIs<ClaimResult.AlreadyClaimed>(result)
            val retryMs = result.retryAfterMs
            assertNotNull(retryMs, "retryAfterMs should not be null when another agent holds the claim")
            assertTrue(retryMs > 0, "retryAfterMs should be positive (remaining TTL from DB clock)")
            // TTL is 30 seconds so remaining should be <= 30000 ms.
            assertTrue(
                retryMs <= ttlSeconds * 1000L,
                "retryAfterMs ($retryMs ms) should not exceed the original TTL (${ttlSeconds * 1000L} ms)"
            )
        }
}
