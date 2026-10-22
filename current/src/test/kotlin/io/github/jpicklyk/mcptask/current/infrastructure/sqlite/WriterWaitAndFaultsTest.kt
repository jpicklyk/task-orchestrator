package io.github.jpicklyk.mcptask.current.infrastructure.sqlite

import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.writeTx
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Review follow-up tests for item 9343ad8d.
 * B1: an in-process write unit waiting for the writer is bounded by the 10 s unit deadline and then returns
 * UNAVAILABLE (kind shedding) with a retry hint, it never hangs (plan section 3.2 / PART A: writers queue "bounded by
 * the 10 s unit deadline"; UnitOfWork KDoc: persistence faults become `unavailable`).
 * B2: an ordinary unique-constraint fault is a translated DUPLICATE and must not churn the writer connection
 * (a constraint violation is not a connection fault). The pool is private, so connection survival is observed through
 * a TEMP table, which lives and dies with the one writer connection.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class WriterWaitAndFaultsTest {
    @RegisterExtension
    @JvmField
    val sqlite = SqliteTestDatabase.perMethod()

    private val db get() = sqlite.db
    private val dm get() = db.databaseManager

    // ---------------------------------------------------------------- B1
    @Test
    @Tag("serial")
    @Timeout(value = 45, unit = TimeUnit.SECONDS)
    fun `B1 a writer waiting longer than the unit deadline returns UNAVAILABLE with a retry hint and does not hang`(): Unit =
        runBlocking {
            db.createProbeTables()
            val uow = db.uow()
            val holding = CompletableDeferred<Unit>()
            val first =
                async(Dispatchers.Default) {
                    uow.write("B1.hold") {
                        dm.writeTx("B1.hold.row") { exec("INSERT INTO p5a_probe (id, v) VALUES (1, 1)") }
                        holding.complete(Unit)
                        delay(20_000)
                        Outcome.Ok("first")
                    }
                }
            try {
                holding.await()
                val t0 = System.nanoTime()
                val result =
                    uow.write("B1.waiter") {
                        dm.writeTx("B1.waiter.row") { exec("INSERT INTO p5a_probe (id, v) VALUES (2, 2)") }
                        Outcome.Ok("second")
                    }
                val waitedMs = (System.nanoTime() - t0) / 1_000_000
                val err = assertIs<Outcome.Err>(result, "a writer that cannot get the writer within the deadline is shed").error
                assertEquals(ErrorCode.UNAVAILABLE, err.code)
                val hint = assertIs<ErrorDetail.Unavailable>(err.detail).retryAfterMs
                assertTrue(hint > 0, "retry hint must be positive, was $hint")
                assertTrue(waitedMs >= 9_000, "must have waited for the writer up to the 10 s deadline, waited ${waitedMs}ms")
                assertTrue(waitedMs <= 11_500, "must give up within about the 10 s deadline (+ one attempt), waited ${waitedMs}ms")
            } finally {
                first.cancelAndJoin()
            }
            assertEquals(0, dm.countRows("p5a_probe"), "neither unit committed: the holder was cancelled, the waiter was shed")
            assertEquals(Outcome.Ok("free"), uow.write("B1.after") { Outcome.Ok("free") }, "the writer is usable afterwards")
        }

    // ---------------------------------------------------------------- B2
    @Test
    fun `B2 a unique constraint fault returns DUPLICATE and the next unit succeeds on the same writer connection`(): Unit =
        runBlocking {
            db.createProbeTables()
            val uow = db.uow()
            // A TEMP table exists only on the single writer connection: if a fault evicted the connection it would vanish.
            assertEquals(
                Outcome.Ok(Unit),
                uow.write("B2.marker") {
                    dm.writeTx("B2.marker.ddl") { exec("CREATE TEMP TABLE p5a_conn_marker (x INTEGER)") }
                    Outcome.Ok(Unit)
                },
            )
            dm.writeTx("B2.seed") { exec("INSERT INTO p5a_probe (id, v) VALUES (1, 1)") }

            repeat(3) { round ->
                val dup =
                    uow.write<Unit>("B2.dup.$round") {
                        dm.writeTx("B2.dup.insert") { exec("INSERT INTO p5a_probe (id, v) VALUES (1, 2)") }
                        Outcome.Ok(Unit)
                    }
                val err = assertIs<Outcome.Err>(dup, "round $round").error
                assertEquals(ErrorCode.DUPLICATE, err.code, "round $round")
                assertEquals(
                    Outcome.Ok("fine-$round"),
                    uow.write("B2.next.$round") {
                        dm.writeTx("B2.next.row") { exec("INSERT INTO p5a_probe (id, v) VALUES (${10 + round}, 1)") }
                        Outcome.Ok("fine-$round")
                    },
                    "a write after a duplicate fault succeeds (round $round)",
                )
            }

            var markerRows = -1
            uow.write("B2.check") {
                dm.writeTx("B2.check.q") {
                    exec("SELECT count(*) FROM sqlite_temp_master WHERE name = 'p5a_conn_marker'") { rs ->
                        if (rs.next()) markerRows = rs.getInt(1)
                    }
                }
                Outcome.Ok(Unit)
            }
            assertEquals(1, markerRows, "the writer connection survived the constraint faults (TEMP marker still present)")
            assertEquals(4, dm.countRows("p5a_probe"), "seed + 3 follow-up rows; the duplicate inserts rolled back")
        }
}
