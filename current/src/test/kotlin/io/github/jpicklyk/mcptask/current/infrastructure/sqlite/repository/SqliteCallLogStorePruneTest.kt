package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository

import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.test.RetentionRows
import io.github.jpicklyk.mcptask.current.test.sampleCallLogRecord
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.testReqId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

/**
 * Independent tests (test-author seat, item 0da29ea1) for `CallLogStore.prune(cutoff, batch)` over the production SQLite
 * store: the store-level half of scenarios S7 (cutoff) and S8 (batch limit) of the frozen test-plan.
 *
 * Oracle (frozen task-scope decision D5 and the W6 brief delete SQL): deletes at most [batch] rows with `at < cutoff`
 * (strict) and returns the number deleted. The fold-watermark gate is the pruner's job and is tested in
 * [io.github.jpicklyk.mcptask.current.infrastructure.RetentionPrunerTest]. Every expected value is an input this test chose.
 *
 * Forbidden-construct declaration: none.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class SqliteCallLogStorePruneTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    private val cutoff: Instant = Instant.parse("2026-01-01T00:00:00.000Z")

    private suspend fun add(
        n: Int,
        at: Instant
    ) {
        db.repositoryProvider().callLogStore().append(listOf(sampleCallLogRecord(testReqId(n), at = at)))
    }

    private suspend fun prune(
        cutoff: Instant,
        batch: Int
    ): Int {
        var deleted = -1
        db.unitOfWork().write("test.prune") {
            deleted = stores.callLogStore().prune(cutoff, batch)
            Outcome.Ok(deleted)
        }
        return deleted
    }

    private fun ids() = RetentionRows.callLogIds(db.jdbcUrl)

    @Test
    fun `S7 prune deletes rows strictly older than the cutoff, keeps the cutoff row and newer, and returns the count`(): Unit =
        runBlocking {
            add(1, cutoff.minusMillis(1))
            add(2, cutoff)
            add(3, cutoff.plusMillis(1))

            val deleted = prune(cutoff, 100)

            assertEquals(1, deleted)
            assertEquals(listOf(testReqId(2), testReqId(3)), ids())
        }

    @Test
    fun `S7 prune on an empty table and a second prune both answer zero`(): Unit =
        runBlocking {
            assertEquals(0, prune(cutoff, 100))
            add(1, cutoff.minusSeconds(5))
            assertEquals(1, prune(cutoff, 100))
            assertEquals(0, prune(cutoff, 100))
        }

    @Test
    fun `S8 prune deletes at most batch rows per call and a repeated call drains the rest`(): Unit =
        runBlocking {
            for (n in 1..12) add(n, cutoff.minusSeconds(n.toLong()))

            assertEquals(5, prune(cutoff, 5))
            assertEquals(7, ids().size)
            assertEquals(5, prune(cutoff, 5))
            assertEquals(2, prune(cutoff, 5))
            assertEquals(0, prune(cutoff, 5))
            assertEquals(emptyList(), ids())
        }

    @Test
    fun `S8 a batch bound never deletes a row at or after the cutoff`(): Unit =
        runBlocking {
            add(1, cutoff.minusSeconds(10))
            add(2, cutoff.plusSeconds(10))

            assertEquals(1, prune(cutoff, 100))

            assertEquals(listOf(testReqId(2)), ids())
        }
}
