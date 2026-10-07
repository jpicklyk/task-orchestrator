package io.github.jpicklyk.mcptask.current.infrastructure.database

import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.infrastructure.repository.readTx
import io.github.jpicklyk.mcptask.current.infrastructure.repository.writeTx
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.sql.DriverManager
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * S17 (added by the orchestrator after a production leak was found; oracle: plans/v4-phase1-core.md section 3.2
 * cancellation rule plus that decision): cancelling a coroutine during an IMPLICIT read outside a unit (readTx) must
 * return the pooled connection and leave no open transaction.
 *
 * The reader pool is small and its checkout timeout is 2 s, so a leaked connection per cancel is detectable on any
 * OS: after more cancellations than the pool holds, a fresh read must still start promptly. An open transaction that
 * outlived the cancel would pin the WAL, so a TRUNCATE checkpoint from a separate connection must not report busy.
 * The fixture's close (via use) fails on a leaked connection on Windows.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class ReadCancellationTest {
    @Test
    fun `S17 cancelling mid-read outside a unit returns the connection and leaves no open transaction`(): Unit =
        runBlocking {
            SqliteTestDatabase.open().use { db ->
                val dm = db.databaseManager
                db.createProbeTables()
                dm.writeTx("S17.seed") { exec("INSERT INTO p5a_probe (id, v) VALUES (1, 1)") }

                repeat(12) { i ->
                    val started = CompletableDeferred<Unit>()
                    val never = CompletableDeferred<Unit>()
                    var caught: Throwable? = null
                    var outcome: Any? = null
                    val job =
                        launch(Dispatchers.Default) {
                            try {
                                outcome =
                                    dm.readTx {
                                        exec("SELECT v FROM p5a_probe WHERE id = 1") { rs -> rs.next() }
                                        started.complete(Unit)
                                        never.await()
                                        "unreachable"
                                    }
                            } catch (e: CancellationException) {
                                caught = e
                                throw e
                            }
                        }
                    started.await()
                    job.cancelAndJoin()
                    assertNull(outcome, "cancelled read #$i must not complete")
                    assertTrue(caught is CancellationException, "read #$i must surface CancellationException, got $caught")
                }
                assertNull(TransactionManager.currentOrNull(), "no transaction may remain open on the calling thread")

                // (a) a subsequent write unit succeeds promptly, not waiting out a busy_timeout or deadline.
                val t0 = System.nanoTime()
                val write = withTimeout(5_000) { db.uow().write("S17.after") { Outcome.Ok("written") } }
                val writeMs = (System.nanoTime() - t0) / 1_000_000
                assertEquals(Outcome.Ok("written"), write)
                assertTrue(writeMs < 1_000, "write after cancelled reads took ${writeMs}ms")

                // The pool (default 10 readers) is not exhausted: 12 leaked connections would time out here after 2 s.
                val t1 = System.nanoTime()
                val rows = withTimeout(5_000) { dm.countRows("p5a_probe") }
                val readMs = (System.nanoTime() - t1) / 1_000_000
                assertEquals(1, rows)
                assertTrue(readMs < 1_000, "read after 12 cancelled reads took ${readMs}ms; a connection leaked from the reader pool")

                // No reader still holds a WAL snapshot: a TRUNCATE checkpoint from a separate connection is not busy.
                DriverManager.getConnection(db.jdbcUrl).use { raw ->
                    raw.createStatement().use { st ->
                        st.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)").use { rs ->
                            assertTrue(rs.next())
                            assertEquals(0, rs.getInt(1), "checkpoint reported busy: a cancelled read left a transaction open")
                        }
                    }
                }
            } // (b) close() throws IllegalStateException if a connection leaked
        }
}
