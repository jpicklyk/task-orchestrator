package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository

import io.github.jpicklyk.mcptask.current.application.port.EventStore
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.event.DomainEvent
import io.github.jpicklyk.mcptask.current.test.RetentionRows
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import java.sql.DriverManager
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item 0da29ea1) for [EventStore.prune] over the production SQLite store: the
 * store-level half of scenarios S1 (strict cutoff), S4 (the seq floor argument) and S8 (the per-call batch limit) of the
 * frozen test-plan.
 *
 * Oracles (frozen task-scope decisions D4/D5 and the W6 brief delete SQL): `prune(type, cutoff, protectFrom, batch)` deletes
 * at most [batch] rows of exactly [type] with `occurred_at < cutoff` (strict) AND `seq < protectFrom` (strict), and returns the
 * number deleted; it writes no event. Every expected value is an input this test chose (seqs, instants, counts).
 *
 * Rows are inserted with raw SQL at explicit seqs above [EventStore.SEQ_FLOOR] (the shape the live writer produces).
 * Forbidden-construct declaration: none.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class SqliteEventStorePruneTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    private val floor = EventStore.SEQ_FLOOR
    private val cutoff: Instant = Instant.parse("2026-01-01T00:00:00Z")
    private val ancient: Instant = Instant.parse("2020-01-01T00:00:00Z")
    private val upserted = DomainEvent.NOTE_UPSERTED
    private val noteDeleted = DomainEvent.NOTE_DELETED

    private val store get() = db.repositoryProvider().eventStore()

    private fun row(
        seq: Long,
        type: String,
        at: Instant
    ) = RetentionRows.insertEvent(db.jdbcUrl, seq, type, at)

    private fun seqs(type: String? = null) =
        if (type ==
            null
        ) {
            RetentionRows.eventSeqs(db.jdbcUrl)
        } else {
            RetentionRows.eventSeqs(db.jdbcUrl, type)
        }

    /** Runs [EventStore.prune] inside one production write unit and returns what it answered. */
    private suspend fun prune(
        type: String,
        cutoff: Instant,
        protectFrom: Long,
        batch: Int
    ): Int {
        var deleted = -1
        db.unitOfWork().write("test.prune") {
            deleted = stores.eventStore().prune(type, cutoff, protectFrom, batch)
            Outcome.Ok(deleted)
        }
        return deleted
    }

    // S1 - strict cutoff
    @Test
    fun `S1 prune deletes rows strictly older than the cutoff, keeps the cutoff row and newer, and returns the count`(): Unit =
        runBlocking {
            row(floor + 1, upserted, cutoff.minusMillis(1))
            row(floor + 2, upserted, cutoff)
            row(floor + 3, upserted, cutoff.plusMillis(1))

            val deleted = prune(upserted, cutoff, Long.MAX_VALUE, 100)

            assertEquals(1, deleted)
            assertEquals(listOf(floor + 2, floor + 3), seqs())
        }

    @Test
    fun `S1 prune touches only the asked type`(): Unit =
        runBlocking {
            row(floor + 1, upserted, ancient)
            row(floor + 2, noteDeleted, ancient)

            assertEquals(1, prune(upserted, cutoff, Long.MAX_VALUE, 100))

            assertEquals(emptyList(), seqs(upserted))
            assertEquals(listOf(floor + 2), seqs(noteDeleted), "a different type of the same age must survive")
        }

    @Test
    fun `S1 prune on an empty table and a second prune over the same data both answer zero`(): Unit =
        runBlocking {
            assertEquals(0, prune(upserted, cutoff, Long.MAX_VALUE, 100))
            row(floor + 1, upserted, ancient)
            assertEquals(1, prune(upserted, cutoff, Long.MAX_VALUE, 100))
            assertEquals(0, prune(upserted, cutoff, Long.MAX_VALUE, 100))
        }

    @Test
    fun `S1 prune writes no event rows of its own`(): Unit =
        runBlocking {
            for (i in 1..6) row(floor + i, upserted, ancient)
            val before = RetentionRows.eventCount(db.jdbcUrl)

            val deleted = prune(upserted, cutoff, Long.MAX_VALUE, 100)

            assertEquals(6, deleted)
            assertEquals(before - deleted, RetentionRows.eventCount(db.jdbcUrl), "count after = count before - deleted, nothing added")
        }

    // S4 - the protectFrom argument
    @Test
    fun `S4 rows at or above protectFrom survive, rows strictly below it are deleted`(): Unit =
        runBlocking {
            for (i in 1..5) row(floor + i, upserted, ancient)

            val deleted = prune(upserted, cutoff, protectFrom = floor + 3, batch = 100)

            assertEquals(2, deleted, "seq floor+1 and floor+2 are below protectFrom")
            assertEquals(listOf(floor + 3, floor + 4, floor + 5), seqs(), "the row AT protectFrom is kept")
        }

    @Test
    fun `S4 probe - protectFrom of zero protects every row`(): Unit =
        runBlocking {
            for (i in 1..3) row(floor + i, upserted, ancient)
            assertEquals(0, prune(upserted, cutoff, protectFrom = 0, batch = 100))
            assertEquals(3, seqs().size)
        }

    @Test
    fun `S4 probe - a protectFrom above every seq protects nothing the cutoff would delete`(): Unit =
        runBlocking {
            for (i in 1..3) row(floor + i, upserted, ancient)
            assertEquals(3, prune(upserted, cutoff, protectFrom = floor + 4, batch = 100))
        }

    @Test
    fun `S4 prune does not lower maxSeq while the protected newest row survives`(): Unit =
        runBlocking {
            for (i in 1..4) row(floor + i, upserted, ancient)
            val maxBefore = store.maxSeq()

            prune(upserted, cutoff, protectFrom = maxBefore, batch = 100)

            assertEquals(maxBefore, store.maxSeq(), "protecting the newest row keeps the replay-gap reference point")
        }

    // S8 - the per-call batch limit
    @Test
    fun `S8 prune deletes at most batch rows per call and a repeated call drains the rest`(): Unit =
        runBlocking {
            for (i in 1..12) row(floor + i, upserted, ancient)

            assertEquals(5, prune(upserted, cutoff, Long.MAX_VALUE, 5))
            assertEquals(7, seqs().size)
            assertEquals(5, prune(upserted, cutoff, Long.MAX_VALUE, 5))
            assertEquals(2, seqs().size)
            assertEquals(2, prune(upserted, cutoff, Long.MAX_VALUE, 5))
            assertEquals(0, prune(upserted, cutoff, Long.MAX_VALUE, 5))
            assertEquals(emptyList(), seqs())
        }

    @Test
    fun `S8 probe - batch of one deletes exactly one row`(): Unit =
        runBlocking {
            for (i in 1..3) row(floor + i, upserted, ancient)
            assertEquals(1, prune(upserted, cutoff, Long.MAX_VALUE, 1))
            assertEquals(2, seqs().size)
        }

    @Test
    fun `S8 a batch bound does not delete a row the other conditions protect`(): Unit =
        runBlocking {
            row(floor + 1, upserted, ancient)
            row(floor + 2, upserted, cutoff.plusMillis(1))
            row(floor + 3, upserted, ancient)

            val deleted = prune(upserted, cutoff, protectFrom = floor + 3, batch = 100)

            assertEquals(1, deleted, "only floor+1 is both older than the cutoff and below protectFrom")
            assertEquals(listOf(floor + 2, floor + 3), seqs())
        }

    // The delete is meant to ride the (type, occurred_at) index
    @Test
    fun `S1 probe - the (type, occurred_at) index the delete is specified to use exists`() {
        val sql = "SELECT sql FROM sqlite_master WHERE type = 'index' AND name = 'idx_events_type_occurred'"
        val ddl =
            DriverManager.getConnection(db.jdbcUrl).use { c ->
                c.createStatement().use { st ->
                    st.executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else null }
                }
            }
        assertTrue(ddl != null && "events(type, occurred_at)" in ddl, "idx_events_type_occurred missing or reshaped: $ddl")
    }
}
