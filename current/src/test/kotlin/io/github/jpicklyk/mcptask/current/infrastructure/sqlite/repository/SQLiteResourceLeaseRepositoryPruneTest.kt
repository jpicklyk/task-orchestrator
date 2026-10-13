package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository

import io.github.jpicklyk.mcptask.current.application.port.LeaseAcquireResult
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.test.RetentionRows
import io.github.jpicklyk.mcptask.current.test.SettableClock
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * Independent tests (test-author seat, item 0da29ea1) for `LeaseStore.pruneClosed(cutoff, batch)` over the production SQLite
 * store: the store-level half of scenarios S6 (closed intervals only, strict cutoff) and S8 (batch limit) of the frozen
 * test-plan.
 *
 * Oracle (frozen task-scope invariant 4 and decision D5): only intervals with `released_at IS NOT NULL` are pruned, and only
 * when `released_at < cutoff` (strict); at most [batch] per call; the number deleted is returned; an open interval
 * (`released_at` NULL) is never pruned however old its `acquired_at`. Every expected value is an input this test chose.
 *
 * Forbidden-construct declaration: none.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class SQLiteResourceLeaseRepositoryPruneTest {
    private val clock = SettableClock(Instant.parse("2025-06-01T00:00:00Z"))

    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod(clock = clock)

    private val cutoff: Instant = Instant.parse("2026-01-01T00:00:00Z")
    private val ancient: Instant = Instant.parse("2020-01-01T00:00:00Z")

    private suspend fun pruneClosed(
        cutoff: Instant,
        batch: Int
    ): Int {
        var deleted = -1
        db.unitOfWork().write("test.prune") {
            deleted = stores.resourceLeaseRepository().pruneClosed(cutoff, batch)
            Outcome.Ok(deleted)
        }
        return deleted
    }

    private suspend fun createHolder(title: String): java.util.UUID {
        val created = db.repositoryProvider().workItemRepository().create(WorkItem(title = title))
        assertNotNull(created)
        return created.id
    }

    private fun keys() = RetentionRows.leaseKeys(db.jdbcUrl)

    @Test
    fun `S6 pruneClosed deletes closed intervals strictly older than the cutoff and keeps the boundary and newer`(): Unit =
        runBlocking {
            RetentionRows.insertLeaseInterval(db.jdbcUrl, "k-before", ancient, cutoff.minusMillis(1))
            RetentionRows.insertLeaseInterval(db.jdbcUrl, "k-at", ancient, cutoff)
            RetentionRows.insertLeaseInterval(db.jdbcUrl, "k-after", ancient, cutoff.plusMillis(1))

            val deleted = pruneClosed(cutoff, 100)

            assertEquals(1, deleted)
            assertEquals(listOf("k-after", "k-at"), keys())
        }

    @Test
    fun `S6 an open interval is never pruned however old it is, and is not counted`(): Unit =
        runBlocking {
            RetentionRows.insertLeaseInterval(db.jdbcUrl, "k-open-ancient", ancient, null)
            RetentionRows.insertLeaseInterval(db.jdbcUrl, "k-closed-ancient", ancient, ancient.plusSeconds(60))

            val deleted = pruneClosed(cutoff, 100)

            assertEquals(1, deleted, "only the closed interval counts")
            assertEquals(listOf("k-open-ancient"), keys())
        }

    @Test
    fun `S6 an interval opened by a real acquire survives, and one closed by a real release is pruned once old enough`(): Unit =
        runBlocking {
            val repo = db.repositoryProvider().resourceLeaseRepository()
            val holderA = createHolder("Holder A")
            val holderB = createHolder("Holder B")
            assertIs<LeaseAcquireResult.Success>(repo.acquireAll(holderA, "agent-a", listOf("res-a" to 900)))
            assertIs<LeaseAcquireResult.Success>(repo.acquireAll(holderB, "agent-b", listOf("res-b" to 900)))
            clock.advanceSeconds(60)
            repo.releaseAllForItem(holderA) // released_at = clock now (2025-06-01T00:01:00Z)

            // A cutoff well after the release: the closed interval goes, the still-open one stays.
            val deleted = pruneClosed(cutoff, 100)

            assertEquals(1, deleted)
            assertEquals(listOf("res-b"), keys())
        }

    @Test
    fun `S6 pruneClosed on an empty table and a second call both answer zero`(): Unit =
        runBlocking {
            assertEquals(0, pruneClosed(cutoff, 100))
            RetentionRows.insertLeaseInterval(db.jdbcUrl, "k", ancient, ancient.plusSeconds(1))
            assertEquals(1, pruneClosed(cutoff, 100))
            assertEquals(0, pruneClosed(cutoff, 100))
        }

    @Test
    fun `S8 pruneClosed deletes at most batch rows per call and a repeated call drains the rest`(): Unit =
        runBlocking {
            for (i in 1..12) RetentionRows.insertLeaseInterval(db.jdbcUrl, "k-%02d".format(i), ancient, ancient.plusSeconds(i.toLong()))

            assertEquals(5, pruneClosed(cutoff, 5))
            assertEquals(7, keys().size)
            assertEquals(5, pruneClosed(cutoff, 5))
            assertEquals(2, pruneClosed(cutoff, 5))
            assertEquals(0, pruneClosed(cutoff, 5))
            assertEquals(emptyList(), keys())
        }
}
