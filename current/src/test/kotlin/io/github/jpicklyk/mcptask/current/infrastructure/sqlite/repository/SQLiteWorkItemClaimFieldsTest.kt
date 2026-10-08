package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository

import io.github.jpicklyk.mcptask.current.application.port.ClaimResult
import io.github.jpicklyk.mcptask.current.application.port.ReleaseResult
import io.github.jpicklyk.mcptask.current.application.port.WorkItemRepository
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
import kotlin.test.assertNull

/**
 * Tests that the four claim fields (claimedBy, claimedAt, claimExpiresAt, originalClaimedAt)
 * round-trip through create, are written only by the claim store (never by update), and default to null on new items.
 */
class SQLiteWorkItemClaimFieldsTest {
    private val clock = SettableClock()

    @RegisterExtension
    @JvmField
    val sqliteDb = SqliteTestDatabase.perMethod(clock = clock)

    private val repositoryProvider get() = sqliteDb.repositoryProvider()

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
            assertNotNull(result)
            val retrieved = result
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
            assertNotNull(result)
            val retrieved = result
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
    fun `update never writes the claim columns - setting them on the item has no effect`() =
        runBlocking {
            val item = WorkItem(title = "Will not be claimed by update")
            repository.create(item)

            val now = clock.now()
            val claimed =
                item.copy(
                    claimedBy = "agent-xyz",
                    claimedAt = now,
                    claimExpiresAt = now.plusSeconds(600),
                    originalClaimedAt = now,
                )
            assertNotNull(repository.update(claimed))

            val retrieved = repository.getById(item.id)!!
            assertNull(retrieved.claimedBy, "the claim columns belong to the claim store")
            assertNull(retrieved.claimedAt)
            assertNull(retrieved.claimExpiresAt)
            assertNull(retrieved.originalClaimedAt)
        }

    @Test
    fun `update never writes the claim columns - clearing them on the item leaves the claim in place`() =
        runBlocking {
            val item = WorkItem(title = "Claim survives update")
            repository.create(item)
            repository.claim(item.id, "agent-release", 900)
            val claimed = repository.getById(item.id)!!

            val cleared = claimed.copy(claimedBy = null, claimedAt = null, claimExpiresAt = null, originalClaimedAt = null)
            assertNotNull(repository.update(cleared))

            val retrieved = repository.getById(item.id)!!
            assertEquals("agent-release", retrieved.claimedBy)
            assertEquals(claimed.claimExpiresAt, retrieved.claimExpiresAt)
        }

    @Test
    fun `release clears all four claim fields`() =
        runBlocking {
            val item = WorkItem(title = "Release test item")
            repository.create(item)
            repository.claim(item.id, "agent-release", 900)

            val released = repository.release(item.id, "agent-release")
            assertIs<ReleaseResult.Success>(released)

            val retrieved = repository.getById(item.id)!!
            assertNull(retrieved.claimedBy)
            assertNull(retrieved.claimedAt)
            assertNull(retrieved.claimExpiresAt)
            assertNull(retrieved.originalClaimedAt)
        }

    @Test
    fun `same-agent re-claim refreshes the TTL and preserves originalClaimedAt`() =
        runBlocking {
            val item = WorkItem(title = "Re-claim test item")
            repository.create(item)
            val first = clock.now()
            repository.claim(item.id, "agent-reclaim", 900)

            clock.advanceSeconds(450)
            val refresh = clock.now()
            repository.claim(item.id, "agent-reclaim", 900)

            val retrieved = repository.getById(item.id)!!
            assertEquals("agent-reclaim", retrieved.claimedBy)
            assertEquals(refresh, retrieved.claimedAt)
            assertEquals(refresh.plusSeconds(900), retrieved.claimExpiresAt)
            assertEquals(first, retrieved.originalClaimedAt, "originalClaimedAt is preserved from the first claim")
        }

    @Test
    fun `a different agent taking over an expired claim resets originalClaimedAt`() =
        runBlocking {
            val item = WorkItem(title = "Agent takeover test")
            repository.create(item)
            repository.claim(item.id, "agent-first", 900)

            clock.advanceSeconds(1000)
            val takeover = clock.now()
            assertIs<ClaimResult.Success>(repository.claim(item.id, "agent-second", 900))

            val retrieved = repository.getById(item.id)!!
            assertEquals("agent-second", retrieved.claimedBy)
            assertEquals(takeover, retrieved.originalClaimedAt, "a new holder starts its own original claim time")
        }

    @Test
    fun `claim and release do not bump the item version`() =
        runBlocking {
            val item = WorkItem(title = "Version stays")
            repository.create(item)
            val before = repository.getById(item.id)!!.version

            repository.claim(item.id, "agent-v", 900)
            assertEquals(before, repository.getById(item.id)!!.version, "claim must not bump version")
            repository.release(item.id, "agent-v")
            assertEquals(before, repository.getById(item.id)!!.version, "release must not bump version")
        }
}
