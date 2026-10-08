package io.github.jpicklyk.mcptask.current.infrastructure.sqlite

import io.github.jpicklyk.mcptask.current.application.port.Clock
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.readTx
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.writeTx
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.sqlite.SQLiteErrorCode
import org.sqlite.SQLiteException
import java.sql.DriverManager
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Independent tests for the P5a unit of work (item 9343ad8d), authored blind against the frozen task-scope
 * (plans/v4-phase1-core.md section 3.2, lines 69-101) and test-plan. Scenario ids follow test-plan.
 *
 * S1 join, S2 rollback rule, S4 cancellation, S5 reader is query_only, S6 writer serialization, S7 own writes,
 * S14 clock; plus the hook contract (carry-in 8) and the nesting / write-in-read probes.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class SqliteUnitOfWorkTest {
    @RegisterExtension
    @JvmField
    val sqlite = SqliteTestDatabase.perMethod()

    private val db get() = sqlite.db
    private val dm get() = db.databaseManager

    // ---------------------------------------------------------------- S1
    @Test
    fun `S1 nested write and read join the ambient writer transaction and finish within 2s`(): Unit =
        runBlocking {
            val uow = db.uow()
            val result =
                withTimeout(2_000) {
                    uow.write("S1.outer") {
                        val outerTx = assertNotNull(TransactionManager.currentOrNull(), "outer block must run in a transaction")
                        assertSame(dm.writer(), outerTx.db, "a write unit runs on the writer database")
                        uow.write("S1.inner") {
                            assertSame(outerTx, TransactionManager.currentOrNull(), "inner write must join the SAME transaction")
                            Outcome.Ok(Unit)
                        }
                        val joinedRead =
                            uow.read {
                                assertSame(outerTx, TransactionManager.currentOrNull(), "inner read must join the SAME transaction")
                                7
                            }
                        Outcome.Ok(joinedRead)
                    }
                }
            assertEquals(Outcome.Ok(7), result)
        }

    @Test
    fun `S1 nesting depth 3 still joins one transaction`(): Unit =
        runBlocking {
            val uow = db.uow()
            val result =
                withTimeout(2_000) {
                    uow.write("S1.d1") {
                        val tx = assertNotNull(TransactionManager.currentOrNull())
                        uow.write("S1.d2") {
                            uow.write("S1.d3") {
                                assertSame(tx, TransactionManager.currentOrNull())
                                Outcome.Ok("deep")
                            }
                        }
                    }
                }
            assertEquals(Outcome.Ok("deep"), result)
        }

    @Test
    fun `S1 an empty write block commits and returns its Ok`(): Unit =
        runBlocking {
            assertEquals(Outcome.Ok(Unit), db.uow().write("S1.empty") { Outcome.Ok(Unit) })
        }

    @Test
    fun `S1 a read unit runs on the reader database which is distinct from the writer`(): Unit =
        runBlocking {
            val uow = db.uow()
            assertTrue(dm.reader() !== dm.writer(), "reader and writer are two distinct Databases")
            val onReader =
                uow.read {
                    dm.readTx { TransactionManager.current().db === dm.reader() }
                }
            assertTrue(onReader)
        }

    @Test
    fun `a write attempted inside a read unit fails with IllegalStateException naming the op`(): Unit =
        runBlocking {
            val uow = db.uow()
            val e =
                assertFailsWith<IllegalStateException> {
                    uow.read { uow.write("S1.writeInRead") { Outcome.Ok(Unit) } }
                }
            assertTrue("S1.writeInRead" in e.message.orEmpty(), "message must name the op: ${e.message}")
        }

    // ---------------------------------------------------------------- S2
    @Test
    fun `S2 inner Err propagated by the outer block rolls everything back`(): Unit =
        runBlocking {
            db.createProbeTables()
            val uow = db.uow()
            val result =
                uow.write<Unit>("S2.outer") {
                    dm.writeTx("S2.row1") { exec("INSERT INTO p5a_probe (id, v) VALUES (1, 1)") }
                    uow.write<Unit>("S2.inner") {
                        dm.writeTx("S2.row2") { exec("INSERT INTO p5a_probe (id, v) VALUES (2, 2)") }
                        Outcome.Err(err("inner failed"))
                    }
                }
            val e = assertIs<Outcome.Err>(result)
            assertEquals("inner failed", e.error.message)
            assertEquals(0, dm.countRows("p5a_probe"), "an outermost Err must roll back inner AND outer writes")
        }

    @Test
    fun `S2 inner Err swallowed by an outer block that returns Ok commits both writes`(): Unit =
        runBlocking {
            db.createProbeTables()
            val uow = db.uow()
            val result =
                uow.write("S2.outer") {
                    dm.writeTx("S2.row1") { exec("INSERT INTO p5a_probe (id, v) VALUES (1, 1)") }
                    val inner =
                        uow.write<Unit>("S2.inner") {
                            dm.writeTx("S2.row2") { exec("INSERT INTO p5a_probe (id, v) VALUES (2, 2)") }
                            Outcome.Err(err("inner failed"))
                        }
                    assertIs<Outcome.Err>(inner, "the inner Err is returned to the outer block, not thrown")
                    Outcome.Ok("swallowed")
                }
            assertEquals(Outcome.Ok("swallowed"), result)
            assertEquals(2, dm.countRows("p5a_probe"), "rollback happens iff the OUTERMOST block returns Err or throws")
        }

    @Test
    fun `S2 an outer throw rolls back and the exception propagates unchanged`(): Unit =
        runBlocking {
            db.createProbeTables()
            val uow = db.uow()
            val boom = IllegalStateException("custom failure")
            val thrown =
                assertFailsWith<IllegalStateException> {
                    uow.write<Unit>("S2.throw") {
                        dm.writeTx("S2.row") { exec("INSERT INTO p5a_probe (id, v) VALUES (1, 1)") }
                        throw boom
                    }
                }
            // kotlinx.coroutines stack-trace recovery may copy the instance; type and message must be unchanged.
            assertEquals(boom.message, thrown.message)
            assertEquals(0, dm.countRows("p5a_probe"))
        }

    // ---------------------------------------------------------------- S4
    @Test
    fun `S4 cancelling a write unit rethrows CancellationException never an Err and frees the writer`(): Unit =
        runBlocking {
            db.createProbeTables()
            val uow = db.uow()
            val started = CompletableDeferred<Unit>()
            val never = CompletableDeferred<Unit>()
            var outcome: Any? = null
            var caught: Throwable? = null
            val job =
                launch(Dispatchers.Default) {
                    try {
                        outcome =
                            uow.write("S4.cancel") {
                                dm.writeTx("S4.row") { exec("INSERT INTO p5a_probe (id, v) VALUES (1, 1)") }
                                started.complete(Unit)
                                never.await()
                                Outcome.Ok(Unit)
                            }
                    } catch (e: CancellationException) {
                        caught = e
                        throw e
                    }
                }
            started.await()
            job.cancelAndJoin()
            assertNull(outcome, "a cancelled unit must not return an Outcome")
            assertTrue(caught is CancellationException, "cancellation must propagate as CancellationException, got $caught")

            val t0 = System.nanoTime()
            val next = uow.write("S4.next") { Outcome.Ok("after") }
            val elapsedMs = (System.nanoTime() - t0) / 1_000_000
            assertEquals(Outcome.Ok("after"), next)
            // A leaked writer connection (pool of 1) would make this wait for the 2 s checkout; a leaked Mutex would hang.
            assertTrue(elapsedMs < 500, "the next write must not wait on the cancelled one, took ${elapsedMs}ms")
            assertEquals(0, dm.countRows("p5a_probe"), "the cancelled unit's insert must have rolled back")
        }

    @Test
    fun `S4 afterRollback is skipped when the unit is cancelled`(): Unit =
        runBlocking {
            val uow = db.uow()
            val started = CompletableDeferred<Unit>()
            val fired = AtomicInteger()
            val job =
                launch(Dispatchers.Default) {
                    uow.write<Unit>("S4.hook") {
                        afterRollback { fired.incrementAndGet() }
                        started.complete(Unit)
                        CompletableDeferred<Unit>().await()
                        Outcome.Ok(Unit)
                    }
                }
            started.await()
            job.cancelAndJoin()
            assertEquals(0, fired.get(), "afterRollback must be skipped on cancellation")
        }

    // ---------------------------------------------------------------- S5
    @Test
    fun `S5 a write statement inside a read unit fails with SQLITE_READONLY`(): Unit =
        runBlocking {
            db.createProbeTables()
            val uow = db.uow()
            val e =
                assertFailsWith<Throwable> {
                    uow.read { dm.readTx { exec("INSERT INTO p5a_probe (id, v) VALUES (1, 1)") } }
                }
            assertTrue(hasReadOnlyCode(e), "expected SQLITE_READONLY in the cause chain of: $e")
            assertEquals(0, dm.countRows("p5a_probe"))
        }

    @Test
    fun `S5 an implicit read outside a unit runs on the reader and cannot write`(): Unit =
        runBlocking {
            db.createProbeTables()
            assertTrue(dm.readTx { TransactionManager.current().db === dm.reader() })
            val e = assertFailsWith<Throwable> { dm.readTx { exec("INSERT INTO p5a_probe (id, v) VALUES (1, 1)") } }
            assertTrue(hasReadOnlyCode(e), "expected SQLITE_READONLY in the cause chain of: $e")
        }

    private fun hasReadOnlyCode(t: Throwable): Boolean =
        generateSequence(t) { it.cause }
            .filterIsInstance<SQLiteException>()
            .any { it.resultCode.name.startsWith("SQLITE_READONLY") }

    // ---------------------------------------------------------------- S6
    @Test
    fun `S6 eight coroutines of ten read-modify-write units each reach 80 with no failures`(): Unit =
        runBlocking {
            db.createProbeTables()
            dm.writeTx("S6.seed") { exec("INSERT INTO p5a_probe (id, v) VALUES (1, 0)") }
            val uow = db.uow()
            val blockRuns = AtomicInteger()
            val results =
                withTimeout(60_000) {
                    coroutineScope {
                        (1..8)
                            .map {
                                async(Dispatchers.Default) {
                                    (1..10).map {
                                        uow.write("S6.rmw") {
                                            blockRuns.incrementAndGet()
                                            val current = dm.probeValue(1) ?: error("seed row missing")
                                            dm.writeTx("S6.update") { exec("UPDATE p5a_probe SET v = ${current + 1} WHERE id = 1") }
                                            Outcome.Ok(current + 1)
                                        }
                                    }
                                }
                            }.awaitAll()
                            .flatten()
                    }
                }
            assertTrue(results.all { it is Outcome.Ok }, "every unit must commit: ${results.filterIsInstance<Outcome.Err>()}")
            assertEquals(80, dm.probeValue(1), "no lost update: writes are serialized")
            assertEquals(80, blockRuns.get(), "no unit may have been retried (attempts == 1)")
            assertEquals(80, results.map { (it as Outcome.Ok).value }.toSet().size, "every unit saw a distinct counter value")
        }

    // S6 (queueing past the pool checkout window): oracle plan section 3.2 / PART A - in-process writers queue on the
    // writer Mutex, bounded by the 10 s unit deadline, instead of failing `unavailable` when the 2 s pool checkout
    // window (connectionTimeout) expires. With the Mutex removed the second unit would wait on the pool and time out.
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    fun `S6 a second writer queued behind a unit holding the writer for 3s succeeds instead of unavailable`(): Unit =
        runBlocking {
            db.createProbeTables()
            val uow = db.uow()
            val holding = CompletableDeferred<Unit>()
            val first =
                async(Dispatchers.Default) {
                    uow.write("S6.hold") {
                        dm.writeTx("S6.hold.row") { exec("INSERT INTO p5a_probe (id, v) VALUES (1, 1)") }
                        holding.complete(Unit)
                        delay(3_000)
                        Outcome.Ok("first")
                    }
                }
            holding.await()
            val t0 = System.nanoTime()
            val second =
                async(Dispatchers.Default) {
                    uow.write("S6.queued") {
                        dm.writeTx("S6.queued.row") { exec("INSERT INTO p5a_probe (id, v) VALUES (2, 2)") }
                        Outcome.Ok("second")
                    }
                }
            assertEquals(Outcome.Ok("first"), first.await())
            val secondResult = second.await()
            val waitedMs = (System.nanoTime() - t0) / 1_000_000
            assertEquals(Outcome.Ok("second"), secondResult, "a queued in-process writer must not fail `unavailable` after ${waitedMs}ms")
            assertTrue(waitedMs >= 2_000, "the second unit must have queued past the 2 s checkout window, waited ${waitedMs}ms")
            assertEquals(2, dm.countRows("p5a_probe"), "both units committed")
        }

    // ---------------------------------------------------------------- S7
    @Test
    fun `S7 a unit reads its own uncommitted writes while a concurrent outside read does not`(): Unit =
        runBlocking {
            val uow = db.uow()
            val item = WorkItem(title = "S7 own-write item")
            var outsideSawItem: Boolean? = null
            val result =
                uow.write("S7.create") {
                    val repo = stores.workItemRepository()
                    assertNotNull(repo.create(item))
                    assertNotNull(repo.getById(item.id), "the unit must see its own insert")
                    // A separate thread has no ambient unit: it reads from the reader pool and must not see an uncommitted row.
                    val outside = db.repositoryProvider().workItemRepository()
                    val t = thread { runBlocking { outsideSawItem = outside.getById(item.id) != null } }
                    t.join(10_000)
                    Outcome.Ok(Unit)
                }
            assertEquals(Outcome.Ok(Unit), result)
            assertEquals(false, outsideSawItem, "an outside read before commit must not see the row")
            val after = db.repositoryProvider().workItemRepository().getById(item.id)
            assertNotNull(after, "after commit the row is visible to everyone")
        }

    // ---------------------------------------------------------------- S14
    @Test
    fun `S14 scope now is the injected clock reading and a nested unit shares it`(): Unit =
        runBlocking {
            val fixed = Instant.parse("2026-01-02T03:04:05Z")
            val uow = db.uow(Clock { fixed })
            val seen = CopyOnWriteArrayList<Instant>()
            uow.write("S14.fixed") {
                seen += now
                uow.read { seen += now }
                Outcome.Ok(Unit)
            }
            assertEquals(listOf(fixed, fixed), seen.toList())
        }

    @Test
    fun `S14 a BUSY retry starts a fresh attempt that re-reads the clock`(): Unit =
        runBlocking {
            val reads = AtomicInteger()
            val base = Instant.parse("2026-01-02T03:04:05Z")
            val uow = db.uow(Clock { base.plusSeconds(reads.getAndIncrement().toLong()) })
            val nows = CopyOnWriteArrayList<Instant>()
            val result =
                uow.write("S14.retry") {
                    nows += now
                    if (nows.size == 1) throw SQLiteException("simulated busy", SQLiteErrorCode.SQLITE_BUSY)
                    Outcome.Ok(Unit)
                }
            assertEquals(Outcome.Ok(Unit), result)
            assertEquals(2, reads.get(), "the clock is read exactly once per attempt")
            assertEquals(listOf(base, base.plusSeconds(1)), nows.toList())
        }

    // ---------------------------------------------------------------- hooks (carry-in 8)
    @Test
    fun `afterCommit registered from a joined scope fires once on the outermost commit after the data is visible`(): Unit =
        runBlocking {
            db.createProbeTables()
            val uow = db.uow()
            val events = CopyOnWriteArrayList<String>()
            val rowsSeenByHook = CopyOnWriteArrayList<Int>()
            val hook: (String) -> suspend () -> Unit = { name ->
                {
                    events += name
                    DriverManager.getConnection(db.jdbcUrl).use { c ->
                        c.createStatement().use { st ->
                            st.executeQuery("SELECT count(*) FROM p5a_probe").use {
                                it.next()
                                rowsSeenByHook += it.getInt(1)
                            }
                        }
                    }
                }
            }
            uow.write("hooks.outer") {
                dm.writeTx("hooks.row") { exec("INSERT INTO p5a_probe (id, v) VALUES (1, 1)") }
                uow.write("hooks.inner") {
                    afterCommit(hook("inner"))
                    Outcome.Ok(Unit)
                }
                afterCommit(hook("outer"))
                assertTrue(events.isEmpty(), "no commit hook may fire before COMMIT")
                Outcome.Ok(Unit)
            }
            assertEquals(listOf("inner", "outer"), events.toList(), "each hook fires exactly once, in registration order")
            assertEquals(listOf(1, 1), rowsSeenByHook.toList(), "hooks run after COMMIT: the row is already visible")
        }

    @Test
    fun `a hook registered by a BUSY-abandoned attempt never fires`(): Unit =
        runBlocking {
            val uow = db.uow()
            val fired = CopyOnWriteArrayList<String>()
            val attempt = AtomicInteger()
            val result =
                uow.write("hooks.retry") {
                    val n = attempt.incrementAndGet()
                    afterCommit { fired += "commit-$n" }
                    afterRollback { fired += "rollback-$n" }
                    if (n == 1) throw SQLiteException("simulated busy", SQLiteErrorCode.SQLITE_BUSY)
                    Outcome.Ok(Unit)
                }
            assertEquals(Outcome.Ok(Unit), result)
            assertEquals(2, attempt.get())
            assertEquals(listOf("commit-2"), fired.toList(), "only the surviving attempt's hooks fire; the abandoned attempt fires nothing")
        }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    fun `an Err from the outermost block fires a joined afterRollback once with that error and no commit hooks`(): Unit =
        runBlocking {
            val uow = db.uow()
            val rollbacks = CopyOnWriteArrayList<Any?>()
            val commits = AtomicInteger()
            val error = err("declined")
            val result =
                uow.write<Unit>("hooks.err") {
                    uow.write("hooks.err.inner") {
                        afterRollback { rollbacks += it }
                        afterCommit { commits.incrementAndGet() }
                        Outcome.Ok(Unit)
                    }
                    Outcome.Err(error)
                }
            assertEquals(Outcome.Err(error), result)
            assertEquals(listOf<Any?>(error), rollbacks.toList(), "joined-scope rollback hook fires once, with the Err")
            assertEquals(0, commits.get())
        }

    @Test
    fun `a non-persistence throw fires afterRollback with null and rethrows`(): Unit =
        runBlocking {
            val uow = db.uow()
            val rollbacks = CopyOnWriteArrayList<Any?>()
            assertFailsWith<IllegalStateException> {
                uow.write<Unit>("hooks.throw") {
                    afterRollback { rollbacks += (it ?: "null") }
                    throw IllegalStateException("not a sql fault")
                }
            }
            assertEquals(listOf<Any?>("null"), rollbacks.toList())
        }

    @Test
    fun `a persistence fault fires afterRollback with the translated error`(): Unit =
        runBlocking {
            db.createProbeTables()
            val uow = db.uow()
            val rollbacks = CopyOnWriteArrayList<ErrorCode?>()
            val result =
                uow.write("hooks.dup") {
                    afterRollback { rollbacks += it?.code }
                    dm.writeTx("hooks.dup.1") { exec("INSERT INTO p5a_probe (id, v) VALUES (1, 1)") }
                    dm.writeTx("hooks.dup.2") { exec("INSERT INTO p5a_probe (id, v) VALUES (1, 2)") }
                    Outcome.Ok(Unit)
                }
            assertIs<Outcome.Err>(result)
            assertEquals(listOf<ErrorCode?>(ErrorCode.DUPLICATE), rollbacks.toList())
        }

    @Test
    fun `a failing afterCommit hook does not change the Outcome or the committed data`(): Unit =
        runBlocking {
            db.createProbeTables()
            val uow = db.uow()
            val result =
                uow.write("hooks.fail") {
                    dm.writeTx("hooks.fail.row") { exec("INSERT INTO p5a_probe (id, v) VALUES (1, 1)") }
                    afterCommit { throw IllegalStateException("hook blew up") }
                    Outcome.Ok("done")
                }
            assertEquals(Outcome.Ok("done"), result)
            assertEquals(1, dm.countRows("p5a_probe"))
        }

    @Test
    fun `a write after a failed unit succeeds so the failed unit released the writer`(): Unit =
        runBlocking {
            val uow = db.uow()
            assertFailsWith<IllegalStateException> { uow.write<Unit>("after.fail") { throw IllegalStateException("x") } }
            assertEquals(Outcome.Ok("fine"), uow.write("after.ok") { Outcome.Ok("fine") })
        }
}
